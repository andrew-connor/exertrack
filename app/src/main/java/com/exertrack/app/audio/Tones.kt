package com.exertrack.app.audio

import com.exertrack.app.engine.ChimeKind
import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthesised 16-bit mono PCM for each chime, generated once and cached. Every buffer starts with
 * ~150 ms of silence so Bluetooth links do not clip the attack. See docs/DESIGN.md for the
 * character of each sound (DONE: one ~400 ms decaying tone; GO: two short rising tones;
 * TICK: ~60 ms blip; COMPLETE: three ascending tones). Use short linear fades to avoid clicks.
 */
object Tones {
    const val SAMPLE_RATE = 44_100

    /** Silence in front of every buffer so the first cycles are not swallowed by a link warming up. */
    private const val LEAD_SILENCE_MS = 150
    /** Linear fade applied to the start and end of every note. */
    private const val FADE_MS = 5
    /** Peak level as a fraction of full scale. */
    private const val AMPLITUDE = 0.6

    private val cache = EnumMap<ChimeKind, ShortArray>(ChimeKind::class.java)

    /** The PCM buffer for [kind]; synthesised on first use and shared afterwards (do not mutate). */
    @Synchronized
    fun pcm(kind: ChimeKind): ShortArray = cache.getOrPut(kind) { synthesize(kind) }

    private fun synthesize(kind: ChimeKind): ShortArray = when (kind) {
        ChimeKind.DONE -> concat(
            silence(LEAD_SILENCE_MS),
            note(660.0, 420, decayMs = 140),
        )
        ChimeKind.GO -> concat(
            silence(LEAD_SILENCE_MS),
            note(880.0, 110),
            silence(40),
            note(1175.0, 200),
        )
        ChimeKind.TICK -> concat(
            silence(LEAD_SILENCE_MS),
            note(1000.0, 60),
        )
        ChimeKind.COMPLETE -> concat(
            silence(LEAD_SILENCE_MS),
            note(784.0, 150),
            silence(30),
            note(988.0, 150),
            silence(30),
            note(1319.0, 420, decayMs = 160),
        )
    }

    private fun samples(ms: Int): Int = ms * SAMPLE_RATE / 1000

    private fun silence(ms: Int): ShortArray = ShortArray(samples(ms))

    /**
     * A sine note of [freqHz] lasting [ms] with a linear fade in and out. When [decayMs] > 0 the
     * level also decays exponentially with that time constant, giving a struck-bell character.
     */
    private fun note(freqHz: Double, ms: Int, decayMs: Int = 0): ShortArray {
        val n = samples(ms)
        val fade = samples(FADE_MS).coerceAtLeast(1)
        val out = ShortArray(n)
        val phaseStep = 2.0 * PI * freqHz / SAMPLE_RATE
        val fullScale = Short.MAX_VALUE.toDouble()
        for (i in 0 until n) {
            var envelope = AMPLITUDE
            if (decayMs > 0) {
                val tMs = i * 1000.0 / SAMPLE_RATE
                envelope *= exp(-tMs / decayMs)
            }
            if (i < fade) envelope *= i.toDouble() / fade
            val tail = n - 1 - i
            if (tail < fade) envelope *= tail.toDouble() / fade
            val value = (sin(phaseStep * i) * envelope * fullScale).roundToInt()
            out[i] = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (part in parts) {
            part.copyInto(out, pos)
            pos += part.size
        }
        return out
    }
}
