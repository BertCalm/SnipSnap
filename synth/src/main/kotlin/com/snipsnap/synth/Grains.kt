package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * GRAINS — granular resynthesis: the engine that eats your captures.
 *
 * Every other engine here synthesizes from nothing. GRAINS takes a *source
 * snip* — a capture, a synth render, anything — and rebuilds it as a cloud:
 * tiny Hann-windowed slices scattered over time, re-pitched, overlapped.
 * Two seconds off a video becomes a pad, a stutter, a shimmering wash.
 * Capture and synthesis stop being two features.
 *
 * Offline rendering is granular's best case: no scheduler, just windowed
 * copies summed into a buffer, deterministic per seed. Macros stay in the
 * house style — 0..1, plain words, bounded so every roll is a texture and
 * none is a mistake:
 *
 * - SIZE — grain length: tiny stutter → smeared wash
 * - SMEAR — how many grains overlap at once
 * - DRIFT — how far grains wander from the scrub point
 * - PITCH — snapped semitones, −12 at 0, +12 at 1, 0.5 = native
 * - SHINE — a quiet octave-up shimmer layer
 *
 * The scrub point scans the source start → end across the output, so the
 * cloud keeps the source's own arc — a granulated bell still blooms and
 * fades like a bell.
 */
object Grains {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("SIZE", 0.5f),
        MacroSpec("SMEAR", 0.55f),
        MacroSpec("DRIFT", 0.25f),
        MacroSpec("PITCH", 0.5f),
        MacroSpec("SHINE", 0.2f),
    )

    const val PITCH_RANGE_SEMITONES = 12

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The snapped semitone offset a PITCH macro position lands on. */
    fun semitonesFor(pitch: Float): Int =
        Math.round((pitch.coerceIn(0f, 1f) - 0.5f) * 2f * PITCH_RANGE_SEMITONES)

    fun render(
        source: Snip,
        macros: Map<String, Float> = emptyMap(),
        seconds: Float = 2.5f,
        seed: Int = 1,
    ): Snip {
        require(source.frameCount > 256) { "source too short to granulate" }
        require(seconds in 0.2f..8f) { "output length out of range: $seconds" }

        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val src = if (source.channels == 1) source.samples else Cleanup.toMono(source).samples
        val rate = source.sampleRate

        val grainFrames = (Dsp.expMap(m.getValue("SIZE"), 0.015f, 0.25f) * rate).toInt()
        val overlap = Dsp.lin(m.getValue("SMEAR"), 1.5f, 6f)
        val driftFrames = (m.getValue("DRIFT") * 0.5f * src.size).toInt()
        val ratio = 2f.pow(semitonesFor(m.getValue("PITCH")) / 12f)
        val shine = m.getValue("SHINE") * 0.5f

        val outFrames = (seconds * rate).toInt()
        val out = FloatArray(outFrames)
        val random = Random(seed)

        // Grains fire every grainFrames/overlap frames; each reads from the
        // scrub position (scanning the source) plus a seeded wander.
        val hop = (grainFrames / overlap).toInt().coerceAtLeast(32)
        var start = 0
        while (start < outFrames) {
            val scan = (start.toFloat() / outFrames * (src.size - grainFrames))
                .coerceAtLeast(0f)
            val wander = if (driftFrames > 0) random.nextInt(-driftFrames, driftFrames + 1) else 0
            val readStart = (scan + wander).coerceIn(0f, (src.size - 2).toFloat())

            addGrain(out, start, src, readStart, grainFrames, ratio, 1f)
            if (shine > 0.001f) {
                addGrain(out, start, src, readStart, grainFrames, ratio * 2f, shine)
            }
            start += hop
        }

        Dsp.normalize(out)
        Dsp.fadeTail(out, 12f)
        return Snip(out, channels = 1, sampleRate = rate)
    }

    /** One Hann-windowed, linearly-resampled grain summed into [out]. */
    private fun addGrain(
        out: FloatArray,
        outStart: Int,
        src: FloatArray,
        readStart: Float,
        grainFrames: Int,
        ratio: Float,
        gain: Float,
    ) {
        for (g in 0 until grainFrames) {
            val o = outStart + g
            if (o >= out.size) return
            val pos = readStart + g * ratio
            val i0 = pos.toInt()
            if (i0 + 1 >= src.size) return
            val frac = pos - i0
            val sample = src[i0] + (src[i0 + 1] - src[i0]) * frac
            val window = 0.5f * (1f - kotlin.math.cos(2.0 * PI * g / grainFrames).toFloat())
            out[o] += sample * window * gain
        }
    }
}
