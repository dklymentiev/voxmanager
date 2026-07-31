// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Speech recognition, hardened for cross-device reliability.
 *
 * The device-default recognizer differs wildly across OEMs/Android versions and is
 * sometimes an on-device engine with a flaky audio path (observed on Galaxy S25 /
 * Android 16: "read EOF from AudioAccessor" -> empty results / ERROR_NO_MATCH).
 * To behave the same everywhere we explicitly prefer Google's recognizer and favour
 * ONLINE recognition (uniform, no per-device offline model), with safe fallbacks.
 */
class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
        // Recognizer packages we trust, most-preferred first. The device default is
        // often Google TTS's bundled recognizer (com.google.android.tts), which on some
        // phones (e.g. Galaxy S25 / Android 16) returns ERROR_NO_MATCH on every
        // utterance. Prefer the modern on-device engine (Android System Intelligence,
        // com.google.android.as — what Gboard/Recorder use), then the Google app, then
        // TTS as a last resort.
        private val PREFERRED_PACKAGES = listOf(
            "com.google.android.as",                    // Android System Intelligence (modern on-device)
            "com.google.android.googlequicksearchbox",  // Google app (online-capable)
            "com.google.android.tts",                   // Google TTS recognizer (frequent default)
        )
        // A flaky audio path (e.g. Galaxy S25 / Android 16) returns empty results /
        // ERROR_NO_MATCH on EVERY utterance and never self-heals while reusing the same
        // recognizer instance. After this many in a row, rebuild the recognizer so the
        // next start gets a fresh AudioRecord; after the larger count, drop to the
        // device-default engine entirely.
        private const val REBUILD_AFTER_NO_MATCH = 2
        private const val FALLBACK_AFTER_NO_MATCH = 5
    }

    interface SpeechListener {
        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onEndOfSpeech()
        fun onPartialResult(text: String)
        fun onResult(text: String)
        fun onError(errorCode: Int, errorMessage: String)
        fun onRmsChanged(rmsdB: Float) {}
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: SpeechListener? = null
    private var isListening = false

    /** True while we're on the preferred (Google) recognizer; false once we've
     *  fallen back to the device default. */
    private var usingPreferred = false

    /** Consecutive empty results (NO_MATCH / SPEECH_TIMEOUT). Reset on any real text.
     *  Drives recognizer rebuild + fallback so a stuck audio path self-heals. */
    private var consecutiveNoMatch = 0

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            listener?.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Beginning of speech")
            listener?.onBeginningOfSpeech()
        }

        override fun onRmsChanged(rmsdB: Float) {
            listener?.onRmsChanged(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            isListening = false
            listener?.onEndOfSpeech()
        }

        override fun onError(error: Int) {
            Log.e(TAG, "Speech error: $error (recognizer=${if (usingPreferred) "google" else "default"})")
            isListening = false

            // If the preferred recognizer can't capture/serve audio — OR doesn't have
            // the requested language offline (12/13) — drop to the device-default
            // (system) recognizer once and let the caller retry. We only switch engine;
            // we never force online routing — whatever the system recognizer does is its
            // own choice. ERROR_LANGUAGE_NOT_SUPPORTED/UNAVAILABLE exist on API 33+;
            // their literal codes (12/13) are matched directly for older runtimes too.
            if (usingPreferred && (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_AUDIO ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        error == 12 ||   // ERROR_LANGUAGE_NOT_SUPPORTED
                        error == 13)) {  // ERROR_LANGUAGE_UNAVAILABLE
                Log.w(TAG, "Preferred recognizer failed ($error) -> falling back to system recognizer")
                createRecognizer(preferGoogle = false)
                consecutiveNoMatch = 0
            } else if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                // Empty result. A few in a row means the audio path is wedged (it hears
                // speech but returns nothing) — reusing the same recognizer just loops
                // forever. Rebuild for a fresh AudioRecord; if that still can't capture,
                // fall all the way back to the device-default engine.
                consecutiveNoMatch++
                if (consecutiveNoMatch >= FALLBACK_AFTER_NO_MATCH && usingPreferred) {
                    Log.w(TAG, "${consecutiveNoMatch}x NO_MATCH -> falling back to device default")
                    createRecognizer(preferGoogle = false)
                    consecutiveNoMatch = 0
                } else if (consecutiveNoMatch >= REBUILD_AFTER_NO_MATCH) {
                    Log.w(TAG, "${consecutiveNoMatch}x NO_MATCH -> rebuilding recognizer (fresh audio path)")
                    createRecognizer(preferGoogle = usingPreferred)
                }
            }

            listener?.onError(error, getErrorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            Log.d(TAG, "Speech results received")
            isListening = false
            consecutiveNoMatch = 0
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            listener?.onResult(matches?.firstOrNull() ?: "")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) {
                consecutiveNoMatch = 0  // real audio is flowing — path isn't wedged
                listener?.onPartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun setListener(listener: SpeechListener?) {
        this.listener = listener
    }

    fun initialize(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            return false
        }
        createRecognizer(preferGoogle = true)
        return speechRecognizer != null
    }

    /** (Re)build the recognizer, optionally pinned to a trusted package. */
    private fun createRecognizer(preferGoogle: Boolean) {
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        val comp = if (preferGoogle) findPreferredRecognizer() else null
        speechRecognizer = try {
            if (comp != null) SpeechRecognizer.createSpeechRecognizer(context, comp)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.e(TAG, "createSpeechRecognizer($comp) failed: ${e.message}")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        speechRecognizer?.setRecognitionListener(recognitionListener)
        usingPreferred = comp != null
        Log.d(TAG, "Using recognizer: ${comp?.flattenToShortString() ?: "device-default"}")
    }

    /** Find a trusted recognizer service installed on this device, or null. */
    private fun findPreferredRecognizer(): ComponentName? {
        return try {
            val services = context.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE), 0)
            for (pkg in PREFERRED_PACKAGES) {
                val s = services.firstOrNull { it.serviceInfo?.packageName == pkg }
                if (s != null) return ComponentName(s.serviceInfo.packageName, s.serviceInfo.name)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "queryIntentServices failed: ${e.message}")
            null
        }
    }

    fun startListening(languageCode: String = "en-US") {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Favour ONLINE recognition (uniform across devices); avoids each OEM's
            // on-device model/audio quirks when a network is available.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        isListening = true
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            isListening = false
            createRecognizer(preferGoogle = false)  // rebuild and let caller retry
            listener?.onError(SpeechRecognizer.ERROR_CLIENT, "recognizer restarted")
        }
    }

    fun stopListening() {
        if (!isListening) return
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        isListening = false
    }

    fun cancel() {
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        isListening = false
    }

    fun isListening(): Boolean = isListening

    fun cleanup() {
        cancel()
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            12 -> "Language not supported"      // ERROR_LANGUAGE_NOT_SUPPORTED (API 33+)
            13 -> "Language unavailable offline" // ERROR_LANGUAGE_UNAVAILABLE (API 33+)
            else -> "Unknown error: $errorCode"
        }
    }
}
