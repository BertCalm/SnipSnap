package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.exp

/** Candidate recipe, kept separate so the selected Chewed kit remains replayable. */
object PatioCleanup {
    const val VERSION = 1
    const val NAME = "SnipSnap Patio Cleaner"
    // Retain Chewed's movement; reduce the nonlinear gain and recovery pumping.
    val treatment = PatioTreatments.Recipe("cleaner", "Cleaner Chewed", .78f, .95f,
        1.35f, .64f, .22f, .52f, .42f, .60f)

    fun dry(r: PatioStudy.Recipe): Snip {
        val s = PatioWorkingKit.dry(r)
        // Let the low drums' attacks through, then clear space between strokes.
        if (r.id !in setOf("surdo", "cajon")) return s
        return s.copy(samples = FloatArray(s.samples.size) { i ->
            val t = i.toDouble() / s.sampleRate
            s.samples[i] * exp(-maxOf(0.0, t - .10) / .20).toFloat()
        })
    }

    fun process(input: Snip, seed: Int = 729, recipe: PatioTreatments.Recipe = treatment): Snip {
        // Broad low-mid relief before saturation avoids amplifying congested body.
        val body = Dsp.Biquad().apply { peaking(310f, -3.5f, .75f, input.sampleRate) }
        val edge = Dsp.FixedOnePole(6500f, input.sampleRate)
        val prepared = input.copy(samples = FloatArray(input.samples.size) { edge.lp(body.process(input.samples[it])) })
        val treated = PatioTreatments.process(prepared, recipe, seed)
        // Amp-generated edge needs its own gentle trim after the nonlinear stage.
        val finish = Dsp.FixedOnePole(8000f, input.sampleRate)
        return PatioWear.finish(treated.copy(samples = FloatArray(treated.samples.size) { finish.lp(treated.samples[it]) }))
    }
    fun render(r: PatioStudy.Recipe): Snip = process(dry(r), r.seed)
}
