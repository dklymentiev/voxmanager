// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.voxmanager.MainActivity
import com.voxmanager.R
import com.voxmanager.service.VoiceService
import com.voxmanager.wifi.WifiKeyboardManager

/**
 * Final design "Wave + Text" (variant B): a single screen where the bottom
 * waveform is both the live indicator and the start/stop button.
 */
class KeyboardFragment : Fragment() {

    private var wifiManager: WifiKeyboardManager? = null
    private var voiceService: VoiceService? = null
    private var serviceBound = false

    private var isListening = false
    private var isConnected = false
    private var currentServerIp: String? = null

    /** Alpha-pulse on the top-left status dot while a link is being established. */
    private var dotPulse: android.animation.Animator? = null

    /** True while the screen is showing us (foreground). */
    private var resumed = false
    /** One-shot: start dictation automatically right after a fresh connection,
     *  so the user doesn't have to tap the wave a second time. */
    private var autoStartPending = false

    /** Everything dictated this session — committed phrases, each ending with a blank line. */
    private val dictated = StringBuilder()
    private val phraseGap = "\n\n"
    private var prevLineCount = 0

    // LLM-style reveal: buffer the recognised text and drain it at a steady, eased
    // pace with a soft translucent leading edge — independent of the recogniser's bursts.
    private val typeHandler = Handler(Looper.getMainLooper())
    private var typeTarget = ""   // buffer: full text we want to show
    private var typeShown = 0     // how many chars are revealed so far

    /** Constant translucent tail so the leading edge softly materialises (no pop). */
    private class FadeSpan(var alpha: Int) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) { tp.alpha = alpha }
    }

    private val streamTick = object : Runnable {
        override fun run() {
            if (typeShown >= typeTarget.length) return
            val backlog = typeTarget.length - typeShown
            val step = (backlog / 8).coerceIn(1, 3)   // eased catch-up, never a dump
            typeShown = (typeShown + step).coerceAtMost(typeTarget.length)
            val streaming = typeShown < typeTarget.length
            renderShown(streaming)
            if (streaming) typeHandler.postDelayed(this, 28)
        }
    }

    private fun renderShown(streaming: Boolean) {
        if (!::recognizedText.isInitialized) return
        val txt = typeTarget.substring(0, typeShown)
        if (streaming && typeShown > 0) {
            val ss = SpannableString(txt)
            val tail = minOf(5, typeShown)
            ss.setSpan(FadeSpan(120), typeShown - tail, typeShown, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            recognizedText.text = ss
        } else {
            recognizedText.text = txt
        }
        recognizedScroll.post {
            recognizedScroll.fullScroll(View.FOCUS_DOWN)
            slideUpOnGrowth()
        }
    }

    /**
     * When new line(s) appear, push the text back to its old position and animate it
     * gliding up — so the previous phrase eases upward instead of snapping.
     */
    private fun slideUpOnGrowth() {
        val lines = recognizedText.lineCount
        val delta = lines - prevLineCount
        // Only the blank-line gap (>=2 lines) — i.e. a new block after a pause — glides;
        // word-wrap inside a phrase (1 line) doesn't animate, so nothing moves while talking.
        if (prevLineCount > 0 && delta >= 2) {
            val dy = (delta * recognizedText.lineHeight).toFloat()
            recognizedText.translationY = recognizedText.translationY + dy
            recognizedText.animate()
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        prevLineCount = lines
    }

    private lateinit var connText: TextView
    private lateinit var connDot: View
    private lateinit var gearButton: ImageView
    private lateinit var recognizedScroll: ScrollView
    private lateinit var recognizedText: TextView
    private lateinit var waveHub: View
    private lateinit var waveform: WaveformView
    private lateinit var reconnectView: View
    private lateinit var reconnectText: TextView
    private lateinit var reconnectSub: TextView
    private lateinit var errorToast: TextView

    private val accent = 0xFF8B5CF6.toInt()
    private val inkColor = 0xFFECECF7.toInt()
    private val faintColor = 0xFF5E5E7C.toInt()
    private val mutedColor = 0xFF9A9AB5.toInt()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceService.LocalBinder
            voiceService = binder.getService()
            serviceBound = true

            wifiManager?.let { voiceService?.setWifiManager(it) }

            voiceService?.onPartialResult = { text ->
                activity?.runOnUiThread { showPartial(text) }
            }
            voiceService?.onTextRecognized = { text ->
                activity?.runOnUiThread { commitPhrase(text) }
            }
            voiceService?.onListeningStateChanged = { listening ->
                activity?.runOnUiThread { renderListening(listening) }
            }
            voiceService?.onRmsChanged = { rms ->
                activity?.runOnUiThread { if (isListening) waveform.setAmplitude(rms) }
            }

            renderListening(voiceService?.isListening() ?: false)
            maybeAutoStartListening()   // if we connected before the service bound
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            serviceBound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_keyboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connText = view.findViewById(R.id.connText)
        connDot = view.findViewById(R.id.connDot)
        gearButton = view.findViewById(R.id.gearButton)
        recognizedScroll = view.findViewById(R.id.recognizedScroll)
        recognizedText = view.findViewById(R.id.recognizedText)
        waveHub = view.findViewById(R.id.waveHub)
        waveform = view.findViewById(R.id.waveform)
        reconnectView = view.findViewById(R.id.reconnectView)
        reconnectText = view.findViewById(R.id.reconnectText)
        reconnectSub = view.findViewById(R.id.reconnectSub)
        errorToast = view.findViewById(R.id.errorToast)

        waveHub.setOnClickListener { onHubTapped() }
        gearButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        updateConnectionUi()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateConnectionUi()
        Intent(requireContext(), VoiceService::class.java).also { intent ->
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        keepScreenOn(false)  // never hold the screen while we're off-screen
        // Losing focus stops dictation so we never transcribe in the background.
        if (serviceBound) voiceService?.stopListening()
        typeHandler.removeCallbacks(streamTick)
        // Stop animating the wave while we're not on screen (battery).
        if (::waveform.isInitialized) waveform.setActive(false)
        pulseDot(false)
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    fun setWifiManager(wifi: WifiKeyboardManager) {
        wifiManager = wifi
        voiceService?.setWifiManager(wifi)
    }

    fun setConnected(connected: Boolean, serverIp: String? = null) {
        val wasConnected = isConnected
        isConnected = connected
        currentServerIp = serverIp
        if (isAdded) updateConnectionUi()

        if (connected && isAdded) {
            startPersistentService()
            if (!wasConnected) autoStartPending = true   // fresh link -> auto-start once
            maybeAutoStartListening()
        } else if (!connected && isAdded) {
            requireContext().stopService(Intent(requireContext(), VoiceService::class.java))
        }
    }

    /** Start dictation automatically once, right after a fresh connection — but only
     *  while we're in the foreground and the service is ready. */
    private fun maybeAutoStartListening() {
        if (autoStartPending && resumed && isConnected && serviceBound && !isListening) {
            autoStartPending = false
            voiceService?.startListening()
        }
    }

    /** Establishing a link — spinner + status. [reconnect] = we had a link and lost it. */
    fun setConnecting(reconnect: Boolean) {
        if (!isAdded || !::waveform.isInitialized) return
        // Top-left during a connect = a pulsing amber dot only. The center overlay
        // already spells out Connecting / Reconnecting, so we don't repeat the word.
        connDot.setBackgroundResource(R.drawable.dot_amber)
        connText.text = ""
        pulseDot(true)
        reconnectText.text = getString(if (reconnect) R.string.status_reconnecting else R.string.status_connecting)
        if (reconnect) {
            reconnectSub.text = currentServerIp?.let { getString(R.string.reconnect_sub, it) } ?: ""
            reconnectSub.visibility = View.VISIBLE
        } else {
            reconnectSub.visibility = View.GONE
        }
        reconnectView.visibility = View.VISIBLE
        recognizedScroll.visibility = View.GONE
        errorToast.visibility = View.GONE
        waveHub.setBackgroundResource(R.drawable.bg_hub_off)
        waveform.setMode(WaveformView.MODE_OFFLINE)
        waveform.setActive(false)
    }

    /** Pulse the top-left status dot (amber) while a connection is being established;
     *  the center overlay carries the word, so up here a blinking dot is enough. */
    private fun pulseDot(active: Boolean) {
        if (!::connDot.isInitialized) return
        dotPulse?.cancel()
        dotPulse = null
        if (active) {
            dotPulse = android.animation.ObjectAnimator.ofFloat(connDot, View.ALPHA, 1f, 0.2f).apply {
                duration = 620
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else {
            connDot.alpha = 1f
        }
    }

    /** Brief error banner above the wave (e.g. the link was lost). */
    fun showConnectionLost() {
        if (!isAdded || !::errorToast.isInitialized) return
        errorToast.text = getString(R.string.connection_lost_toast)
        errorToast.visibility = View.VISIBLE
        errorToast.postDelayed({ if (isAdded) errorToast.visibility = View.GONE }, 4500)
    }

    private fun onHubTapped() {
        if (!isConnected) {
            (activity as? MainActivity)?.requestConnect()
            return
        }
        val starting = !isListening
        playToggleFeedback(starting)
        if (starting) voiceService?.startListening() else voiceService?.stopListening()
    }

    /** Distinct vibration when arming/disarming the mic. (No audio tone: holding a
     *  ToneGenerator output session jammed mic capture on Android 14+/Samsung,
     *  causing "read EOF" / no recognition.) */
    private fun playToggleFeedback(starting: Boolean) {
        val ctx = context ?: return
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            val effect = if (starting)
                android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            else
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 22, 45, 22), -1)
            vib.vibrate(effect)
        } catch (e: Exception) { /* haptics are best-effort */ }
    }

    /** Idle / disconnected appearance (listening handled by [renderListening]). */
    private fun updateConnectionUi() {
        if (!isAdded || !::waveform.isInitialized) return
        if (isListening) return

        // Leaving any reconnecting state.
        pulseDot(false)
        reconnectView.visibility = View.GONE
        recognizedScroll.visibility = View.VISIBLE

        // Prompts sit centred; dictation (handled in renderListening) sits at the bottom.
        recognizedText.gravity = Gravity.CENTER

        // Idle hub = still (flat) wave bars, per the design.
        waveform.setActive(false)

        if (isConnected) {
            connText.text = currentServerIp ?: getString(R.string.status_connected)
            connText.setTextColor(0xFFC0C0D4.toInt())
            connDot.setBackgroundResource(R.drawable.dot_green)
            waveHub.setBackgroundResource(R.drawable.bg_hub_idle)
            waveform.setMode(WaveformView.MODE_IDLE)
            recognizedText.text = getString(R.string.prompt_tap_wave)
            recognizedText.setTextColor(faintColor)
        } else {
            connText.text = getString(R.string.status_disconnected)
            connText.setTextColor(faintColor)
            connDot.setBackgroundResource(R.drawable.dot_gray)
            waveHub.setBackgroundResource(R.drawable.bg_hub_off)
            waveform.setMode(WaveformView.MODE_OFFLINE)
            recognizedText.text = getString(R.string.prompt_not_connected)
            recognizedText.setTextColor(faintColor)
        }
    }

    /** Show WHERE dictated text will land (the PC's focused window), refreshed at the
     *  moment dictation starts so it's accurate, not stale from connect time. */
    private fun showTypingTarget() {
        val wifi = wifiManager ?: return
        renderTargetLabel(wifi.lastActiveWindow)            // last known, immediately
        viewLifecycleOwner.lifecycleScope.launch {
            wifi.verifyConnection()                         // refresh lastActiveWindow
            if (isAdded && isListening) renderTargetLabel(wifi.lastActiveWindow)
        }
    }

    private fun renderTargetLabel(win: String?) {
        if (!::connText.isInitialized) return
        if (!win.isNullOrBlank()) {
            connText.text = getString(R.string.typing_into, win)
            connText.setTextColor(mutedColor)
        }
    }

    private fun renderListening(listening: Boolean) {
        isListening = listening
        if (!isAdded || !::waveform.isInitialized) return
        // Keep the screen awake for the whole dictation session so the phone never
        // locks while you're using it (no re-unlock + re-activate). Cleared on stop.
        keepScreenOn(listening)
        if (listening) {
            pulseDot(false)
            reconnectView.visibility = View.GONE
            errorToast.visibility = View.GONE
            recognizedScroll.visibility = View.VISIBLE
            waveHub.setBackgroundResource(R.drawable.bg_hub_listening)
            waveform.setMode(WaveformView.MODE_RECORDING)
            waveform.setActive(true)
            resetCrawl()
            recognizedText.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            recognizedText.text = ""
            recognizedText.setTextColor(inkColor)
            showTypingTarget()
        } else {
            waveform.setActive(false)
            updateConnectionUi()
        }
    }

    /** Hold the screen on (only) while dictation is active — battery-safe. */
    private fun keepScreenOn(on: Boolean) {
        val w = activity?.window ?: return
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** In-progress phrase streams below the committed blocks (which end with a gap). */
    private fun showPartial(text: String) {
        renderCrawl(dictated.toString() + text)
    }

    /** A finished phrase (after a pause): seal it and add the gap that opens space below. */
    private fun commitPhrase(text: String) {
        if (text.isBlank()) return
        dictated.append(text).append(phraseGap)
        renderCrawl(dictated.toString())
    }

    /** Update the buffer and keep streaming; rewind only to where text diverged. */
    private fun renderCrawl(text: String) {
        if (!::recognizedText.isInitialized) return
        recognizedText.setTextColor(inkColor)
        val shown = typeTarget.take(typeShown)
        val limit = minOf(shown.length, text.length)
        var k = 0
        while (k < limit && shown[k] == text[k]) k++
        typeShown = k          // keep the matching prefix, re-stream the rest
        typeTarget = text
        typeHandler.removeCallbacks(streamTick)
        typeHandler.post(streamTick)
    }

    private fun resetCrawl() {
        typeHandler.removeCallbacks(streamTick)
        dictated.clear()
        typeTarget = ""
        typeShown = 0
        prevLineCount = 0
        if (::recognizedText.isInitialized) recognizedText.translationY = 0f
    }

    private fun startPersistentService() {
        Intent(requireContext(), VoiceService::class.java).also { intent ->
            intent.action = VoiceService.ACTION_SHOW_PERSISTENT
            requireContext().startForegroundService(intent)
        }
    }
}
