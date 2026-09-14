package com.exertrack.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper over the platform TextToSpeech. Initialisation is asynchronous: utterances
 * requested before the engine is ready are queued and flushed once it is (or dropped if the
 * engine fails). A failed engine is not final: [prepare] always tries again and [speak] retries
 * after a short cooldown, so a transient failure (engine package being updated, voice data
 * still downloading) does not silence speech for the rest of the process. Uses USAGE_MEDIA
 * audio attributes so speech follows the chimes to earbuds. [onSpeechStart] / [onSpeechDone]
 * let AudioCues hold audio focus while speaking.
 */
class Speaker(
    private val context: Context,
    private val onSpeechStart: () -> Unit,
    private val onSpeechDone: () -> Unit,
) {
    private companion object {
        const val TAG = "Speaker"
        const val UTTERANCE_PREFIX = "exertrack-"
        const val WARM_UP_ID = "exertrack-warm-up"
        /** Minimum gap between engine retries triggered by ordinary utterances. */
        const val RETRY_DELAY_MS = 10_000L
    }

    private enum class EngineState { NONE, INITIALIZING, READY, FAILED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var engineState = EngineState.NONE
    /** Utterances requested while the engine is still starting: text to interrupt flag. */
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private var utteranceCounter = 0L
    private var warmUpRequested = false
    /** elapsedRealtime of the last failure; gates cooldown retries from [speak]. */
    private var failedAt = 0L
    /** Bumped whenever an engine is created or discarded so stale init callbacks are ignored. */
    private var initGeneration = 0

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            if (isTracked(utteranceId)) mainHandler.post { onSpeechStart() }
        }

        override fun onDone(utteranceId: String?) {
            if (isTracked(utteranceId)) mainHandler.post { onSpeechDone() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (isTracked(utteranceId)) mainHandler.post { onSpeechDone() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "Utterance $utteranceId failed with code $errorCode")
            if (isTracked(utteranceId)) mainHandler.post { onSpeechDone() }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (isTracked(utteranceId)) mainHandler.post { onSpeechDone() }
        }

        /** Real utterances only; the warm-up utterance must not touch audio focus. */
        private fun isTracked(utteranceId: String?): Boolean =
            utteranceId != null && utteranceId != WARM_UP_ID && utteranceId.startsWith(UTTERANCE_PREFIX)
    }

    /**
     * Start engine initialisation (idempotent) and speak a silent utterance to warm it up.
     * A session start is a deliberate user action, so a previously failed engine is retried
     * immediately rather than waiting out the cooldown.
     */
    fun prepare() {
        warmUpRequested = true
        ensureEngine(retryFailedNow = true)
        if (engineState == EngineState.READY) warmUp()
    }

    fun speak(text: String, interrupt: Boolean) {
        if (text.isBlank()) return
        ensureEngine()
        when (engineState) {
            EngineState.FAILED, EngineState.NONE -> return
            EngineState.READY -> speakNow(text, interrupt)
            EngineState.INITIALIZING -> {
                if (interrupt) pending.clear()
                pending.addLast(text to interrupt)
            }
        }
    }

    fun stop() {
        pending.clear()
        if (engineState != EngineState.READY) return
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop speech", e)
        }
    }

    fun shutdown() {
        pending.clear()
        warmUpRequested = false
        val engine = tts
        tts = null
        engineState = EngineState.NONE
        // A late init callback from this engine must not be attributed to a later one.
        initGeneration++
        if (engine != null) {
            try {
                engine.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Could not shut the speech engine down", e)
            }
        }
    }

    // ---- internals ----

    /**
     * Create the engine if there is none. A failed engine is recreated when [retryFailedNow] is
     * set, or once [RETRY_DELAY_MS] has passed since the failure, so a persistently missing
     * engine costs at most one bind attempt per cooldown.
     */
    private fun ensureEngine(retryFailedNow: Boolean = false) {
        when (engineState) {
            EngineState.INITIALIZING, EngineState.READY -> return
            EngineState.FAILED ->
                if (!retryFailedNow && SystemClock.elapsedRealtime() - failedAt < RETRY_DELAY_MS) return
            EngineState.NONE -> Unit
        }
        engineState = EngineState.INITIALIZING
        val generation = ++initGeneration
        try {
            // The init callback is posted so it always runs after the constructor has returned,
            // even when no engine is installed and the platform reports the failure synchronously.
            // The generation check drops callbacks from an engine that has since been replaced
            // or shut down.
            tts = TextToSpeech(context.applicationContext) { status ->
                mainHandler.post { if (generation == initGeneration) onInit(status) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Text-to-speech engine unavailable", e)
            fail()
        }
    }

    private fun onInit(status: Int) {
        if (engineState != EngineState.INITIALIZING) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "Text-to-speech initialisation failed with status $status")
            fail()
            return
        }
        val configured = try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(progressListener)
            selectLanguage(engine)
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure the speech engine", e)
            false
        }
        if (!configured) {
            fail()
            return
        }
        engineState = EngineState.READY
        if (warmUpRequested) warmUp()
        flushPending()
    }

    /** True when the device locale, or failing that US English, is available. */
    private fun selectLanguage(engine: TextToSpeech): Boolean {
        val candidates = listOf(Locale.getDefault(), Locale.US).distinct()
        for (locale in candidates) {
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) return true
        }
        Log.w(TAG, "No supported speech language among $candidates")
        return false
    }

    private fun fail() {
        engineState = EngineState.FAILED
        failedAt = SystemClock.elapsedRealtime()
        pending.clear()
        val engine = tts
        tts = null
        if (engine != null) {
            try {
                engine.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Could not shut the failed speech engine down", e)
            }
        }
    }

    private fun warmUp() {
        warmUpRequested = false
        val engine = tts ?: return
        try {
            engine.speak("", TextToSpeech.QUEUE_ADD, null, WARM_UP_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up utterance failed", e)
        }
    }

    private fun flushPending() {
        while (pending.isNotEmpty()) {
            val (text, interrupt) = pending.removeFirst()
            speakNow(text, interrupt)
        }
    }

    private fun speakNow(text: String, interrupt: Boolean) {
        val engine = tts ?: return
        val id = UTTERANCE_PREFIX + (++utteranceCounter)
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = try {
            engine.speak(text, mode, null, id)
        } catch (e: Exception) {
            Log.w(TAG, "Could not speak", e)
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) Log.w(TAG, "Speech request rejected with $result")
    }
}
