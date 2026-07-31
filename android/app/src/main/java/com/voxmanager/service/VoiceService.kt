// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.voxmanager.MainActivity
import com.voxmanager.R
import com.voxmanager.speech.SpeechManager
import com.voxmanager.wifi.WifiKeyboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "VoxManager_Channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_LISTENING = "com.voxmanager.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.voxmanager.STOP_LISTENING"
        const val ACTION_SHOW_PERSISTENT = "com.voxmanager.SHOW_PERSISTENT"
        // Small gap before re-arming the recognizer. Restarting synchronously from
        // inside onError/onResults re-enters the engine before its audio path has torn
        // down, which on some devices (Galaxy S25 / Android 16) wedges it into an
        // endless ERROR_NO_MATCH loop. A brief post lets it reset cleanly.
        private const val RESTART_DELAY_MS = 150L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaSession: MediaSessionCompat? = null

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var speechManager: SpeechManager? = null
    private var wifiManager: WifiKeyboardManager? = null
    private var isListening = false

    // Streaming mode
    var streamingMode = false
    private var lastSentText = ""

    // Recognition language: user choice from settings, or device locale if "system".
    private fun recognitionLanguage(): String {
        val saved = getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
            .getString("recognition_language", null)
        return if (saved.isNullOrBlank()) Locale.getDefault().toLanguageTag() else saved
    }

    var onTextRecognized: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null
    var onRmsChanged: ((Float) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "Vox Manager")
        speechManager = SpeechManager(this)
        speechManager?.initialize()
        setupSpeechCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                stopListening()
                updateNotification("Ready", null)
            }
            ACTION_START_LISTENING -> {
                startListening()
            }
            ACTION_SHOW_PERSISTENT -> {
                // Just show notification, don't start listening
                startForeground(NOTIFICATION_ID, createNotification("Ready", null))
            }
            else -> {
                // Default - show persistent notification
                startForeground(NOTIFICATION_ID, createNotification("Ready", null))
            }
        }
        // Don't auto-resurrect after the OS kills us or the task is removed; the
        // user reopens the app to reconnect. (See onTaskRemoved.)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away / "Close all" -> fully stop. No lingering
        // foreground service the user has to force-stop.
        stopListening()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        speechManager?.cleanup()
    }

    fun setWifiManager(manager: WifiKeyboardManager) {
        wifiManager = manager
    }

    // Calculate what to send and update state synchronously
    private fun calculateStreamingDelta(newText: String): Pair<Int, String>? {
        if (newText == lastSentText) return null

        // Find common prefix
        var commonLength = 0
        val minLen = minOf(lastSentText.length, newText.length)
        while (commonLength < minLen && lastSentText[commonLength] == newText[commonLength]) {
            commonLength++
        }

        val charsToDelete = lastSentText.length - commonLength
        val newPart = newText.substring(commonLength)

        // Update state immediately (synchronously on main thread)
        lastSentText = newText

        return Pair(charsToDelete, newPart)
    }

    // Send the calculated delta asynchronously
    private suspend fun sendStreamingDelta(charsToDelete: Int, newPart: String) {
        // Send backspaces for corrections
        if (charsToDelete > 0) {
            repeat(charsToDelete) {
                wifiManager?.sendKey("BACKSPACE")
            }
        }

        // Send new text
        if (newPart.isNotEmpty()) {
            wifiManager?.sendText(newPart)
        }
    }

    fun startListening() {
        if (isListening) return

        isListening = true
        lastSentText = "" // Reset for new session
        updateNotification("Listening...")
        speechManager?.startListening(recognitionLanguage())
        onListeningStateChanged?.invoke(true)
    }

    fun stopListening() {
        if (!isListening) return

        isListening = false
        mainHandler.removeCallbacksAndMessages(null)  // drop any pending auto-restart
        speechManager?.stopListening()
        updateNotification("Ready")
        onListeningStateChanged?.invoke(false)
    }

    /** Re-arm the recognizer after a brief gap, but only if we're still listening
     *  (the user may have stopped while we were waiting). */
    private fun scheduleRestart() {
        if (!isListening) return
        mainHandler.postDelayed({
            if (isListening) speechManager?.startListening(recognitionLanguage())
        }, RESTART_DELAY_MS)
    }

    fun isListening(): Boolean = isListening

    private fun setupSpeechCallbacks() {
        speechManager?.setListener(object : SpeechManager.SpeechListener {
            override fun onReadyForSpeech() {
                updateNotification("Listening...")
            }

            override fun onBeginningOfSpeech() {
                updateNotification("Speaking...")
            }

            override fun onRmsChanged(rmsdB: Float) {
                this@VoiceService.onRmsChanged?.invoke(rmsdB)
            }

            override fun onEndOfSpeech() {
                updateNotification("Processing...")
            }

            override fun onPartialResult(text: String) {
                updateNotification("Hearing:", text)
                onPartialResult?.invoke(text)

                // In streaming mode, send text incrementally
                if (streamingMode && text.isNotEmpty()) {
                    // Calculate delta synchronously on main thread
                    val delta = calculateStreamingDelta(text)
                    if (delta != null) {
                        serviceScope.launch {
                            sendStreamingDelta(delta.first, delta.second)
                        }
                    }
                }
            }

            override fun onResult(text: String) {
                if (text.isNotEmpty()) {
                    if (streamingMode) {
                        // Calculate and send final delta + space
                        val delta = calculateStreamingDelta(text)
                        serviceScope.launch {
                            if (delta != null) {
                                sendStreamingDelta(delta.first, delta.second)
                            }
                            wifiManager?.sendText(" ")
                        }
                        updateNotification("Sent: \"$text\"")
                    } else {
                        // Non-streaming: send full text
                        updateNotification("Sent: \"$text\"")
                        serviceScope.launch {
                            wifiManager?.sendText(text + " ")
                        }
                    }
                    onTextRecognized?.invoke(text)
                }

                // Reset for next phrase
                lastSentText = ""

                // Auto-restart listening (deferred — see RESTART_DELAY_MS)
                scheduleRestart()
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                // Auto-restart on recoverable errors
                if (isListening && (errorCode == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                            errorCode == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    updateNotification("Listening...")
                    scheduleRestart()
                } else if (isListening) {
                    // Stop listening on other errors, but keep service running
                    stopListening()
                    updateNotification("Error - tap Start to retry")
                }
            }
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Vox Manager",
            NotificationManager.IMPORTANCE_LOW  // Low importance - no pop-ups
        ).apply {
            description = "Voice recognition service"
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(statusText: String = "Ready", partialText: String? = null): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Start listening action
        val startIntent = Intent(this, VoiceService::class.java).apply {
            action = ACTION_START_LISTENING
        }
        val startPendingIntent = PendingIntent.getService(
            this, 1, startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop listening action
        val stopIntent = Intent(this, VoiceService::class.java).apply {
            action = ACTION_STOP_LISTENING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true) // Prevent repeated pop-ups on updates
            .setSilent(true) // No sound/vibration on updates

        if (isListening) {
            // Listening state - show Stop button, green color
            builder.setContentTitle("Vox Manager — listening")
                .setContentText(statusText)
                .setColor(0xFF4CAF50.toInt()) // Green
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .setShowWhen(true)
                .setUsesChronometer(true)

            // Use MediaStyle for music player look
            builder.setStyle(MediaStyle()
                .setShowActionsInCompactView(0)
                .setMediaSession(mediaSession?.sessionToken))

        } else {
            // Ready state - show Start button, blue color
            builder.setContentTitle("Vox Manager — ready")
                .setContentText(statusText)
                .setColor(0xFF2196F3.toInt()) // Blue
                .addAction(android.R.drawable.ic_media_play, "Start", startPendingIntent)
                .setShowWhen(false)
                .setUsesChronometer(false)

            // Use MediaStyle for music player look
            builder.setStyle(MediaStyle()
                .setShowActionsInCompactView(0)
                .setMediaSession(mediaSession?.sessionToken))
        }

        if (!partialText.isNullOrEmpty()) {
            builder.setContentText("$statusText\n\"$partialText\"")
        }

        return builder.build()
    }

    fun updateNotification(statusText: String, partialText: String? = null) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(statusText, partialText))
    }
}
