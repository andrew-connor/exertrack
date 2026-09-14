package com.exertrack.app.audio

import android.content.Context

/**
 * Thin wrapper over the platform TextToSpeech. Initialisation is asynchronous: utterances
 * requested before the engine is ready are queued and flushed once it is (or dropped if the
 * engine fails). Uses USAGE_MEDIA audio attributes so speech follows the chimes to earbuds.
 * [onSpeechStart] / [onSpeechDone] let AudioCues hold audio focus while speaking.
 */
class Speaker(
    private val context: Context,
    private val onSpeechStart: () -> Unit,
    private val onSpeechDone: () -> Unit,
) {
    /** Start engine initialisation (idempotent) and speak a silent utterance to warm it up. */
    fun prepare(): Unit = TODO("implemented by the audio module")

    fun speak(text: String, interrupt: Boolean): Unit = TODO("implemented by the audio module")

    fun stop(): Unit = TODO("implemented by the audio module")

    fun shutdown(): Unit = TODO("implemented by the audio module")
}
