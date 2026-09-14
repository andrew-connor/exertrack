package com.exertrack.app.audio

import com.exertrack.app.engine.ChimeKind

/**
 * Synthesised 16-bit mono PCM for each chime, generated once and cached. Every buffer starts with
 * ~150 ms of silence so Bluetooth links do not clip the attack. See docs/DESIGN.md for the
 * character of each sound (DONE: one ~400 ms decaying tone; GO: two short rising tones;
 * TICK: ~60 ms blip; COMPLETE: three ascending tones). Use short linear fades to avoid clicks.
 */
object Tones {
    const val SAMPLE_RATE = 44_100

    fun pcm(kind: ChimeKind): ShortArray = TODO("implemented by the audio module")
}
