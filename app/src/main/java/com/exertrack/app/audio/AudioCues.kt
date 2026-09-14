package com.exertrack.app.audio

import android.content.Context
import com.exertrack.app.data.Settings
import com.exertrack.app.engine.ChimeKind
import com.exertrack.app.engine.Cues
import kotlinx.coroutines.flow.StateFlow

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
    override fun chime(kind: ChimeKind): Unit = TODO("implemented by the audio module")
    override fun speak(text: String, interrupt: Boolean): Unit = TODO("implemented by the audio module")
    override fun stopSpeaking(): Unit = TODO("implemented by the audio module")
    override fun prepare(): Unit = TODO("implemented by the audio module")

    /** Play a chime regardless of the mute setting (Settings screen test buttons). */
    fun test(kind: ChimeKind): Unit = TODO("implemented by the audio module")
    /** Speak a sample sentence regardless of the mute setting. */
    fun testSpeech(): Unit = TODO("implemented by the audio module")

    fun release(): Unit = TODO("implemented by the audio module")
}
