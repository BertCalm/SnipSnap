package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/**
 * Four authored contact/body studies, not measured replicas of regional instruments.
 * The prior Detail presets remain unchanged. Modal weights compensate the resonator's
 * low-frequency gain so the intended upper body modes survive the Cleaner chain.
 */
object PatioArticulation {
    const val VERSION = 1
    const val NAME = "SnipSnap Patio Articulation"
    val IDS = PatioDetail.IDS

    // ratio, attack weight, RT60 seconds. All values are explicit sound-design shapes.
    private data class Partial(val ratio: Float, val weight: Float, val decay: Float)
    private fun body(id: String): List<Partial> = when (id) {
        "llamador" -> listOf(Partial(1f, 1f, .16f), Partial(1.59f, .65f, .095f),
            Partial(2.14f, .38f, .065f), Partial(3.12f, .24f, .035f), Partial(4.6f, .10f, .018f))
        "repicador" -> listOf(Partial(1f, .16f, .25f), Partial(1.59f, .55f, .20f),
            Partial(2.14f, 1f, .17f), Partial(2.92f, .85f, .13f), Partial(4.06f, .65f, .08f),
            Partial(5.14f, .35f, .045f))
        "agogo-low" -> listOf(Partial(1f, .48f, .40f), Partial(1.48f, 1f, .32f),
            Partial(2.03f, .18f, .20f), Partial(2.71f, .62f, .16f), Partial(3.88f, .35f, .09f))
        "agogo-high" -> listOf(Partial(1f, .32f, .62f), Partial(1.52f, .62f, .48f),
            Partial(1.535f, .30f, .45f), Partial(2.76f, 1f, .34f),
            Partial(3.94f, .68f, .23f), Partial(5.21f, .28f, .12f))
        else -> error("unsupported articulation")
    }
    private fun pitch(id: String): Float = when (id) {
        "llamador" -> 205f; "repicador" -> 270f
        "agogo-low" -> 580f; "agogo-high" -> 820f
        else -> error("unsupported articulation")
    }

    // Pitch override is an internal diagnostic: compare body identities at equal pitch.
    internal fun source(id: String, seed: Int, pitchHz: Float = pitch(id), hollowRevision: Int = 1, touch: Float = 1f, contactOffset: Float = 0f): Snip {
        require(touch.isFinite() && touch in 0f..1f && contactOffset.isFinite() && contactOffset in -1f..1f)
        require(hollowRevision in 1..2)
        val deeper = id == "agogo-low" && hollowRevision == 2
        require(id in IDS && pitchHz.isFinite() && pitchHz in 100f..1000f)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val n = (.85 * rate).toInt()
        val contact = FloatArray(n)
        fun pulse(at: Double, width: Double, gain: Float) {
            val start = ((at + contactOffset * .00012) * rate).toInt()
            val count = max(4, (width * (1.0 + (1 - touch) * 1.6 + contactOffset * .12) * rate).toInt())
            repeat(count) { i -> contact[start + i] += gain * sin(PI * i / count).pow(2).toFloat() / count }
        }
        when (id) {
            "llamador" -> pulse(.002, .0011, 1f) // Broad palm contact.
            "repicador" -> { // Distinct finger contacts, not a retriggered drum tail.
                pulse(.002, .00022, .8f); pulse(.0027, .00018, .6f); pulse(.0035, .00020, .45f)
            }
            "agogo-low" -> pulse(.002, .00032, 1f)
            "agogo-high" -> pulse(.002, .00012, 1f)
        }
        // Revision 2 retains a low body and a short, softly beating ring.
        val partials = if (deeper) listOf(Partial(1f, .68f, .46f),
            Partial(1.48f, 1f, .50f), Partial(1.505f, .25f, .38f),
            Partial(2.03f, .16f, .18f), Partial(2.71f, .38f, .12f),
            Partial(3.88f, .18f, .065f)) else body(id)
        val modes = partials.map { p -> Modes.Mode(p.ratio,
            p.weight * (1f - (1f - touch) * .65f * (p.ratio - 1f) / p.ratio) * (1f + contactOffset * .09f * (p.ratio - 1f)) * sin(2 * PI * pitchHz * p.ratio / rate).toFloat(), p.decay) }
        val resonant = Modes.ring(contact, pitchHz, modes, rate)
        Dsp.normalize(resonant, .8f)
        val noise = Dsp.Noise(Dsp.seedFor(seed, "articulation-contact-v1"))
        val centre = when (id) { "llamador" -> 950f; "repicador" -> 2400f; "agogo-low" -> 1700f; else -> 3600f }
        val filter = Dsp.Biquad().apply { bandpass(centre, .8f, rate) }
        val skin = FloatArray(n)
        val contactDecay = if (id == "llamador") .005 else if (id == "repicador") .007 else .0018
        for (i in skin.indices) {
            val t = i.toDouble() / rate - .002
            val envelope = if (t <= 0.0) 0.0 else (1 - exp(-t / .00025)) * exp(-t / contactDecay)
            skin[i] = filter.process((noise.next() * envelope).toFloat())
        }
        Dsp.normalize(skin, .8f)
        val skinGain = when (id) { "llamador" -> .12f; "repicador" -> .38f; "agogo-low" -> .10f; else -> .16f }
        for (i in resonant.indices) {
            val t = i.toDouble() / rate
            // The low bell is hand-damped; the high bell keeps beating upper modes.
            if (id == "agogo-low") {
                val dampAt = if (deeper) .10 else .085
                val release = if (deeper) .060 else .025
                resonant[i] *= exp(-max(0.0, t - dampAt) / release).toFloat()
            }
            resonant[i] += skinGain * touch * skin[i]
        }
        Tide.bandLimit(resonant, rate)
        val output = PatioWear.finish(Snip(Dsp.decimate(resonant, Dsp.RATE), 1, Dsp.RATE))
        Dsp.levelTo(output.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(output.samples)
        return output
    }
    fun dry(r: PatioStudy.Recipe): Snip = if (r.id in IDS) source(r.id, r.seed) else PatioCleanup.dry(r)
    fun render(r: PatioStudy.Recipe): Snip = if (r.id in IDS)
        PatioCleanup.process(dry(r), r.seed) else PatioCleanup.render(r)
}
