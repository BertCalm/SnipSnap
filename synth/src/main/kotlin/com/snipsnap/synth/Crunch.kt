package com.snipsnap.synth

import com.snipsnap.audio.Snip

/**
 * CRUNCH — vintage sampler character as a per-pad pass.
 *
 * Not a synth: a processor that makes anything sound like it spent 1987
 * inside a 12-bit sampler. The chain is era-shaped — input grit, zero-order
 * hold at a lowered sample rate (the aliasing *is* the sound), bit-depth
 * quantization, then an output tone filter. Works on synthesized voices and
 * captured snips alike; on captured material it's the "make my rip sound
 * like 1987" knob.
 *
 * Same macro discipline as THUMP: everything 0..1, bounded musical ranges,
 * all-zeros ≈ transparent. Output level is matched to the input's peak so
 * character never masquerades as loudness.
 */
object Crunch {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("BITS", 0.5f),   // word size: 16 bits down to 7
        MacroSpec("RATE", 0.45f),  // hold rate: native down to ~10.5 kHz
        MacroSpec("TONE", 0.75f),  // output low-pass: dark to open
        MacroSpec("GRIT", 0.15f),  // input drive into the converter
    )

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val bits = Dsp.lin(1f - m.getValue("BITS"), 7f, 16f)
        val holdRate = Dsp.expMap(1f - m.getValue("RATE"), 10_500f, snip.sampleRate.toFloat())
        val toneHz = Dsp.expMap(m.getValue("TONE"), 1_500f, 16_000f)
        val grit = m.getValue("GRIT")

        val src = snip.samples
        val out = FloatArray(src.size)
        val levels = Math.pow(2.0, bits.toDouble()).toFloat() / 2f
        val step = holdRate / snip.sampleRate

        var inPeak = 0f
        for (v in src) { val a = if (v < 0) -v else v; if (a > inPeak) inPeak = a }

        for (ch in 0 until snip.channels) {
            var holdPhase = 1.0  // sample immediately on the first frame
            var held = 0f
            // Three cascaded poles: the vintage units had steep output
            // reconstruction filters, and one pole leaves enough hold-image
            // trash near the hold rate to drag a kick's centroid into tom
            // territory. Steepness here is fidelity, not politeness.
            val lp1 = Dsp.OnePole(snip.sampleRate)
            val lp2 = Dsp.OnePole(snip.sampleRate)
            val lp3 = Dsp.OnePole(snip.sampleRate)
            var i = ch
            while (i < src.size) {
                // Input grit: drive into the "converter".
                val driven = Dsp.drive(src[i], grit)

                // Zero-order hold: the converter only looks up at holdRate.
                holdPhase += step
                if (holdPhase >= 1.0) {
                    holdPhase -= 1.0
                    // Bit-depth quantization happens at sampling time, like
                    // the hardware it imitates.
                    held = Math.round(driven * levels) / levels
                }

                out[i] = lp3.lp(lp2.lp(lp1.lp(held, toneHz), toneHz), toneHz)
                i += snip.channels
            }
        }

        // Character, not loudness: match the input's peak.
        var outPeak = 0f
        for (v in out) { val a = if (v < 0) -v else v; if (a > outPeak) outPeak = a }
        if (outPeak > 1e-9f && inPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }

        return Snip(out, snip.channels, snip.sampleRate)
    }
}
