package com.exertrack.app.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.exertrack.app.data.Settings
import com.exertrack.app.data.VibrationMode
import com.exertrack.app.engine.ChimeKind
import com.exertrack.app.engine.Cues
import kotlinx.coroutines.flow.StateFlow
import java.util.EnumMap

/**
 * Production [Cues]: AudioTrack playback of [Tones] on USAGE_MEDIA / CONTENT_TYPE_SONIFICATION,
 * transient-may-duck audio focus (when Settings.duckOtherAudio) held across a burst of sounds and
 * released ~1.5 s after the last one, vibration through VibratorManager according to
 * Settings.vibrationMode (WHEN_SILENT = ringer not normal, or Do Not Disturb active, or media
 * volume 0), and speech through [Speaker]. Settings.chimesMuted silences sounds and speech but
 * not vibration. All methods are called on the main thread; playback itself must not block it.
 */
class AudioCues(
    private val context: Context,
    private val settings: StateFlow<Settings>,
) : Cues {
    private companion object {
        const val TAG = "AudioCues"
        /** How long focus is kept after the last sound or utterance, so 3-2-1 ticks do not pump the music. */
        const val FOCUS_RELEASE_DELAY_MS = 1500L
        const val TEST_SENTENCE = "Push ups, 40 seconds. Rest, 15 seconds. Next: squats, 1 minute."
    }

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager: NotificationManager? = appContext.getSystemService(NotificationManager::class.java)
    private val vibrator: Vibrator? by lazy {
        try {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } catch (e: Exception) {
            Log.w(TAG, "Vibrator unavailable", e)
            null
        }
    }
    /** Alarm usage so the pulses are delivered under Do Not Disturb and with the screen off. */
    private val vibrationAttributes: VibrationAttributes by lazy {
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
    }

    private val playbackAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(playbackAttributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener { }, handler)
        .build()

    private val tracks = EnumMap<ChimeKind, AudioTrack>(ChimeKind::class.java)
    private var focusHeld = false
    private val releaseFocus = Runnable {
        if (!focusHeld) return@Runnable
        focusHeld = false
        try {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "Could not abandon audio focus", e)
        }
    }

    private val speaker = Speaker(
        appContext,
        onSpeechStart = { holdFocus() },
        onSpeechDone = { scheduleFocusRelease() },
    )

    // ---- Cues ----

    override fun chime(kind: ChimeKind) {
        if (!settings.value.chimesMuted) play(kind)
        vibrate(kind)
    }

    override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?) {
        if (settings.value.chimesMuted || text.isBlank()) {
            // Nothing will be spoken, so whatever waits on the announcement can proceed now.
            onDone?.invoke()
            return
        }
        say(text, interrupt, onDone)
    }

    override fun stopSpeaking() {
        speaker.stop()
    }

    override fun prepare() {
        speaker.prepare()
    }

    /** Play a chime regardless of the mute setting (Settings screen test buttons). */
    fun test(kind: ChimeKind) {
        play(kind)
        vibrate(kind)
    }

    /** Speak a sample sentence regardless of the mute setting. */
    fun testSpeech() {
        say(TEST_SENTENCE, interrupt = true)
    }

    fun release() {
        handler.removeCallbacks(releaseFocus)
        releaseFocus.run()
        for (track in tracks.values) {
            try {
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "Could not release a track", e)
            }
        }
        tracks.clear()
        speaker.shutdown()
    }

    // ---- sounds ----

    private fun play(kind: ChimeKind) {
        holdFocus()
        try {
            val existing = tracks[kind]?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
            if (existing == null || !restart(existing)) {
                existing?.let { discard(kind, it) }
                createTrack(kind).play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not play $kind", e)
            tracks.remove(kind)?.let { track -> runCatching { track.release() } }
        }
        scheduleFocusRelease()
    }

    /** Rewind a static track and play it from the top; false when the track must be rebuilt. */
    private fun restart(track: AudioTrack): Boolean {
        return try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            if (track.reloadStaticData() != AudioTrack.SUCCESS) return false
            track.play()
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Track could not be restarted; rebuilding it", e)
            false
        }
    }

    private fun discard(kind: ChimeKind, track: AudioTrack) {
        tracks.remove(kind)
        try {
            track.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release a stale track", e)
        }
    }

    private fun createTrack(kind: ChimeKind): AudioTrack {
        val pcm = Tones.pcm(kind)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Tones.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        val written = track.write(pcm, 0, pcm.size)
        if (written < pcm.size || track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Static buffer for $kind not accepted (wrote $written of ${pcm.size})")
        }
        tracks[kind] = track
        return track
    }

    // ---- audio focus ----

    private fun holdFocus() {
        handler.removeCallbacks(releaseFocus)
        if (focusHeld || !settings.value.duckOtherAudio) return
        val result = try {
            audioManager?.requestAudioFocus(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request audio focus", e)
            null
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun scheduleFocusRelease() {
        handler.removeCallbacks(releaseFocus)
        handler.postDelayed(releaseFocus, FOCUS_RELEASE_DELAY_MS)
    }

    // ---- speech ----

    private fun say(text: String, interrupt: Boolean, onDone: (() -> Unit)? = null) {
        // Take focus now so other audio ducks before the first word; the release timer is armed
        // in case the engine never reports a start (for example when it failed to initialise).
        holdFocus()
        scheduleFocusRelease()
        speaker.speak(text, interrupt, onDone)
    }

    // ---- vibration ----

    private fun vibrate(kind: ChimeKind) {
        val pattern = when (kind) {
            ChimeKind.DONE -> longArrayOf(0, 400)
            ChimeKind.GO -> longArrayOf(0, 120, 80, 120)
            ChimeKind.COMPLETE -> longArrayOf(0, 150, 100, 150, 100, 300)
            ChimeKind.TICK -> return
        }
        if (!shouldVibrate()) return
        val device = vibrator ?: return
        try {
            if (!device.hasVibrator()) return
            device.vibrate(VibrationEffect.createWaveform(pattern, -1), vibrationAttributes)
        } catch (e: Exception) {
            Log.w(TAG, "Could not vibrate", e)
        }
    }

    private fun shouldVibrate(): Boolean = when (settings.value.vibrationMode) {
        VibrationMode.ALWAYS -> true
        VibrationMode.NEVER -> false
        VibrationMode.WHEN_SILENT -> isSilent()
    }

    /** Ringer not normal, Do Not Disturb active, or media volume at zero. */
    private fun isSilent(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return true
            val filter = notificationManager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return true
            am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the silent state", e)
            false
        }
    }
}
