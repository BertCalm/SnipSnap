package com.snipsnap.synth

import com.snipsnap.audio.Snip

/**
 * SQUASH — punch in a knob.
 *
 * A feed-forward compressor whose whole personality is its attack: the
 * detector reacts *slowly* enough to let the hit's first milliseconds
 * through untouched, then leans on the body. Peak-matched like every pass
 * here — SQUASH changes the shape of loudness, not the amount of it.
 *
 * AMOUNT is threshold and ratio moving together (one knob, always musical);
 * ATTACK is how much transient escapes before the clamp arrives.
 */
object Squash {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("AMOUNT", 0.5f),  // gentle glue up to full slam
        MacroSpec("ATTACK", 0.5f),  // clamp speed: instant up to "let it slap"
    )

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val amount = m.getValue("AMOUNT")
        if (amount <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        var inPeak = 0f
        for (v in snip.samples) { val a = if (v < 0) -v else v; if (a > inPeak) inPeak = a }
        if (inPeak <= 1e-9f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val threshold = inPeak * Dsp.expMap(1f - amount, 0.12f, 0.9f)
        val ratio = Dsp.lin(amount, 1.5f, 8f)
        // Top of the range is ~8 ms: on a decaying one-shot a slower clamp
        // than that never engages before the sound is gone, and the knob's
        // top third would do nothing.
        val attackS = Dsp.expMap(m.getValue("ATTACK"), 0.0004f, 0.008f)
        val releaseS = 0.09f
        val aAtk = 1f - Math.exp(-1.0 / (attackS * snip.sampleRate)).toFloat()
        val aRel = 1f - Math.exp(-1.0 / (releaseS * snip.sampleRate)).toFloat()
        val slope = 1f - 1f / ratio

        // 2 ms of lookahead: the detector reads ahead of the audio. Without
        // it the first wavefront always escapes before the envelope charges,
        // and even the fastest ATTACK only ever crushed the body - the
        // opposite of glue. With it, fast ATTACK catches the peak (glue) and
        // slow ATTACK deliberately lets it through (punch).
        val look = (0.002f * snip.sampleRate).toInt()
        val frames = snip.frameCount
        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val env = FloatArray(frames)
            var e = 0f
            for (f in 0 until frames) {
                val x = snip.samples[f * snip.channels + ch]
                val a = if (x < 0) -x else x
                e += (if (a > e) aAtk else aRel) * (a - e)
                env[f] = e
            }
            for (f in 0 until frames) {
                val ahead = env[if (f + look < frames) f + look else frames - 1]
                val gain = if (ahead > threshold) {
                    Math.pow((threshold / ahead).toDouble(), slope.toDouble()).toFloat()
                } else 1f
                out[f * snip.channels + ch] = snip.samples[f * snip.channels + ch] * gain
            }
        }

        var outPeak = 0f
        for (v in out) { val a = if (v < 0) -v else v; if (a > outPeak) outPeak = a }
        if (outPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
