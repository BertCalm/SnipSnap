package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Stretch

/**
 * SWELL — the sound arrives before it strikes. The reverse-cymbal trick,
 * generalized to any hit: the sound's own head, stretched to RISE
 * seconds through the paulstretch wash (random phases, one fixed seed),
 * played backwards and faded in, then the hit itself, untouched, on the
 * downbeat. One macro, RISE, 0..1 onto 0..[RISE_MAX_SEC]; zero (or a rise
 * too short to stretch) is transparent. First in the rack, so what
 * REVERSE and the rest see is the swelled sound as a whole. The swell
 * sits under the hit's own peak, so the strike still lands.
 */
object Swell {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("RISE", 0.5f),  // how long the sound takes to arrive: none .. RISE_MAX_SEC
    )

    const val RISE_MAX_SEC = 1.5f

    /** The head of the sound the swell is grown from. */
    const val HEAD_SEC = 0.4f

    /** The swell's ceiling relative to the hit's own peak. */
    const val SWELL_LEVEL = 0.8f

    /** One seed for every swell: the same hit swells the same way. */
    const val SEED = 7L

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun riseFrames(macro: Float, sampleRate: Int): Int = (macro.coerceIn(0f, 1f) * RISE_MAX_SEC * sampleRate).toInt()

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val rise = riseFrames(m.getValue("RISE"), snip.sampleRate)
        // The stretch engine wants more than one window of output; under that there is no swell to make.
        if (rise <= Stretch.WINDOW || snip.frameCount == 0) return snip

        // The head, stretched to the rise, backwards, faded in, under the hit's level.
        val headFrames = minOf(snip.frameCount, (HEAD_SEC * snip.sampleRate).toInt(), rise / Stretch.MIN_FACTOR.toInt())
            .coerceAtLeast(1)
        val head = Snip(snip.samples.copyOfRange(0, headFrames * snip.channels), snip.channels, snip.sampleRate)
        val factor = (rise.toFloat() / headFrames).coerceIn(Stretch.MIN_FACTOR, Stretch.MAX_FACTOR)
        // A head so short that even the deepest stretch can't fill one window has no swell in it: transparent, never a throw.
        if (headFrames * factor <= Stretch.WINDOW) return snip
        val wash = Stretch.stretch(head, factor, SEED).let { if (snip.channels == 1) Cleanup.toMono(it) else it }
        val ch = snip.channels
        val swell = FloatArray(rise * ch)
        val available = minOf(rise, wash.frameCount)
        for (k in 0 until available) {
            val src = available - 1 - k  // backwards
            val gain = k.toFloat() / rise  // the fade in, dying into the hit
            for (c in 0 until ch) swell[(rise - available + k) * ch + c] = wash.samples[src * ch + c] * gain
        }
        val target = snip.peak() * SWELL_LEVEL
        val swellPeak = Snip(swell, ch, snip.sampleRate).peak()
        if (swellPeak > 0f && target > 0f) {
            val g = target / swellPeak
            for (i in swell.indices) swell[i] *= g
        }

        // Then the hit itself, untouched, on the downbeat.
        val out = FloatArray((rise + snip.frameCount) * ch)
        System.arraycopy(swell, 0, out, 0, swell.size)
        System.arraycopy(snip.samples, 0, out, swell.size, snip.samples.size)
        return Snip(out, ch, snip.sampleRate)
    }
}
