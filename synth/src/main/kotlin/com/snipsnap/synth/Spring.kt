package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.pow

/**
 * SPRING — reverb, baked into the sample like it's 1993.
 *
 * A Schroeder network (1962, as era-correct as DSP gets): four parallel
 * combs with mutually-prime delays make the density, two series allpasses
 * smear it into a tail. Each comb's feedback runs through a low-pass, so
 * the room darkens as it rings — TONE is that filter.
 *
 * SIZE moves the comb lengths and the decay time together: small is a
 * closet slap, big is a hall you shouldn't afford. The tail is bounded so
 * a scrambled SPRING can't push a hit into loop territory.
 */
object Spring {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("SIZE", 0.35f),
        MacroSpec("TONE", 0.55f),
        MacroSpec("MIX", 0.3f),
    )

    /** The most tail any setting may add, seconds. */
    const val MAX_TAIL_SECONDS = 1.2f

    // Mutually prime, Schroeder's own neighbourhood of values at 44.1 kHz.
    private val COMB_DELAYS = intArrayOf(1687, 1601, 2053, 2251)
    private val ALLPASS_DELAYS = intArrayOf(347, 113)
    private const val ALLPASS_GAIN = 0.7f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val mix = m.getValue("MIX")
        if (mix <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val size = m.getValue("SIZE")
        val scale = Dsp.lin(size, 0.5f, 1.5f) * snip.sampleRate / 44_100f
        val rt60 = Dsp.expMap(size, 0.25f, 1.4f)
        val toneHz = Dsp.expMap(m.getValue("TONE"), 1_800f, 9_500f)

        val tail = (rt60 * snip.sampleRate).toInt()
            .coerceAtMost((MAX_TAIL_SECONDS * snip.sampleRate).toInt())
        val outFrames = snip.frameCount + tail

        var inPeak = 0f
        for (v in snip.samples) { val a = if (v < 0) -v else v; if (a > inPeak) inPeak = a }

        val out = FloatArray(outFrames * snip.channels)
        for (ch in 0 until snip.channels) {
            val combs = COMB_DELAYS.map { (it * scale).toInt().coerceAtLeast(32) }
            val combBufs = combs.map { FloatArray(it) }
            // Feedback per comb from the shared decay: -60 dB at rt60
            // whatever the comb's own length.
            val combFb = combs.map { d -> 10f.pow(-3f * d / (rt60 * snip.sampleRate)) }
            val combLps = combs.map { Dsp.OnePole(snip.sampleRate) }
            val apBufs = ALLPASS_DELAYS.map { FloatArray((it * scale).toInt().coerceAtLeast(16)) }
            var frame = 0

            while (frame < outFrames) {
                val dry = if (frame < snip.frameCount) snip.samples[frame * snip.channels + ch] else 0f

                var wet = 0f
                for (c in combs.indices) {
                    val buf = combBufs[c]
                    val idx = frame % buf.size
                    val fed = buf[idx]
                    wet += fed
                    buf[idx] = dry + combFb[c] * combLps[c].lp(fed, toneHz)
                }
                wet *= 0.25f

                for (a in apBufs.indices) {
                    val buf = apBufs[a]
                    val idx = frame % buf.size
                    val delayed = buf[idx]
                    buf[idx] = wet + ALLPASS_GAIN * delayed
                    wet = delayed - ALLPASS_GAIN * buf[idx]
                }

                out[frame * snip.channels + ch] = dry + mix * wet
                frame++
            }
        }

        var outPeak = 0f
        for (v in out) { val a = if (v < 0) -v else v; if (a > outPeak) outPeak = a }
        if (outPeak > 1e-9f && inPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
