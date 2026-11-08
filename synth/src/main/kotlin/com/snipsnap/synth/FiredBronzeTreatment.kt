package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/**
 * Explicit forward treatment followed by a separate amp. FxChain's fixed order
 * cannot express this whole route in one pass. Each rack primitive keeps its
 * existing gain law; this is a behavioral study, not a circuit emulation.
 */
internal object FiredBronzeTreatment {
    const val MAX_SECONDS = 1.49f

    private fun trailScale(part: FiredBronze.Part): Float = when (part) {
        FiredBronze.Part.KICK -> .03f
        FiredBronze.Part.SNARE -> .25f
        FiredBronze.Part.CLOSED_HAT, FiredBronze.Part.OPEN_HAT, FiredBronze.Part.CHHING -> .08f
        FiredBronze.Part.BAMBOO -> .12f
        FiredBronze.Part.BONANG_LOW, FiredBronze.Part.BONANG_HIGH, FiredBronze.Part.KEMPUL -> .65f
        else -> .4f
    }

    fun delayControl(bpm: Float): Float = (ln((60f / bpm / 4f) / .06f) / ln(.35f / .06f)).coerceIn(0f, 1f)

    fun processor(dry: Snip, part: FiredBronze.Part, m: Map<String, Float>, bpm: Float): Snip {
        require(dry.channels == 1 && dry.sampleRate == Dsp.RATE)
        val heat = m.getValue("HEAT")
        val press = m.getValue("PRESS")
        val trail = m.getValue("TRAIL") * trailScale(part)
        var s = Eq.process(dry, mapOf("BASS" to .5f, "MID" to .46f, "AIR" to .48f))
        s = gate(s, m.getValue("TIGHT"))
        // Retain the transient through a parallel path; the existing compressor
        // is not labeled FET/VCA because its detector is neither circuit model.
        val compressed = Compressor.process(s, mapOf("AMOUNT" to press, "ATTACK" to .55f, "RECOVER" to .68f))
        s = s.copy(samples = FloatArray(s.samples.size) { .45f * s.samples[it] + .55f * compressed.samples[it] })
        if (heat > 0f) s = Valve.process(s, mapOf("DRIVE" to .32f + heat * .20f,
            "SAG" to 0f, "TONE" to .5f, "CAB" to 0f))
        if (trail > 0f) {
            s = Echo.process(s, mapOf("TIME" to delayControl(bpm), "REPEAT" to .18f,
                "TONE" to .22f, "MIX" to trail, "HEAD" to 0f))
            s = Spring.process(s, mapOf("SIZE" to .12f + .18f * m.getValue("TRAIL"),
                "TONE" to .28f, "MIX" to trail * .6f))
        }
        return finish(s, dry.frameCount)
    }

    fun process(dry: Snip, part: FiredBronze.Part, m: Map<String, Float>, bpm: Float): Snip =
        amp(processor(dry, part, m, bpm), part, m, dry.frameCount)

    internal fun amp(s: Snip, part: FiredBronze.Part, m: Map<String, Float>, sourceFrames: Int): Snip {
        val metal = part in listOf(FiredBronze.Part.CLOSED_HAT, FiredBronze.Part.OPEN_HAT,
            FiredBronze.Part.BONANG_LOW, FiredBronze.Part.BONANG_HIGH, FiredBronze.Part.KEMPUL, FiredBronze.Part.CHHING)
        val heat = m.getValue("HEAT")
        val out = Valve.process(s, mapOf("DRIVE" to .35f + heat * if (metal) .21f else .29f,
            "SAG" to if (part == FiredBronze.Part.KICK) .05f else .15f,
            "TONE" to if (metal) .58f else .5f, "CAB" to if (metal) .22f else .35f))
        return finish(out, sourceFrames)
    }

    /** Input gate only: it cannot chop the later reverb. Hold prevents chatter
     * at zero crossings, and smoothed gain prevents a click on opening/closing. */
    internal fun gate(s: Snip, tight: Float): Snip {
        val threshold = s.peak() * 10.0.pow((-64.0 + 24 * tight) / 20).toFloat()
        val hold = ((.065 - .04 * tight) * s.sampleRate).toInt()
        val release = exp(-1.0 / ((.075 - .05 * tight) * s.sampleRate)).toFloat()
        val attack = exp(-1.0 / (.0003 * s.sampleRate)).toFloat()
        var left = 0
        var gain = 0f
        val out = FloatArray(s.frameCount)
        for (i in out.indices) {
            if (abs(s.samples[i]) > threshold) left = hold
            val target = if (left > 0) 1f else 0f
            val a = if (target > gain) attack else release
            gain = target + a * (gain - target)
            out[i] = s.samples[i] * gain
            if (left > 0) left--
        }
        return s.copy(samples = out)
    }

    private fun finish(s: Snip, sourceFrames: Int): Snip {
        val frames = minOf(s.frameCount, sourceFrames + Dsp.RATE, (MAX_SECONDS * Dsp.RATE).toInt())
        val out = s.samples.copyOf(frames)
        FiredBronze.condition(out)
        // A short finite-kit adaptation, not the natural decay of a large gong.
        Dsp.fadeTailCosine(out, if (frames < s.frameCount) .08f else .008f, rate = Dsp.RATE)
        Dsp.limitPeak(out, .99f)
        return Snip(out, 1, Dsp.RATE)
    }
}
