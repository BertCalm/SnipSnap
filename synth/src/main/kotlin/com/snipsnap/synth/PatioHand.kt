package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/**
 * Third-audition hand strokes. Head, shell/air and skin contact have separate
 * time scales. All weights and contact laws are authored hypotheses, not fits
 * to recorded alegre drums. The v1/v2 sources remain byte-replayable.
 */
object PatioHand {
    const val VERSION = 1
    const val REFERENCE_VERSION = 1
    enum class Stroke { OPEN, SLAP }
    data class Settings(val tune: Float = .5f, val touch: Float = .6f, val hold: Float = .45f) {
        init { require(listOf(tune, touch, hold).all { it.isFinite() && it in 0f..1f }) }
    }

    internal data class Parts(val head: FloatArray, val cavity: FloatArray, val skin: FloatArray)

    internal fun parts(stroke: Stroke, m: Settings, seed: Int, reference: Boolean = false): Parts {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val n = ((.55 + .65 * m.hold) * rate).toInt()
        val slap = stroke == Stroke.SLAP
        val hz = if (reference) 230f * 2f.pow((m.tune - .5f) * .8f) else Dsp.expMap(m.tune, 150f, 310f)
        val drive = FloatArray(n)
        val random = Dsp.Noise(seed)
        fun pulse(time: Double, width: Double, amplitude: Double) {
            val start = (time * rate).toInt()
            val frames = max(4, (width * rate).toInt())
            for (i in 0 until frames) {
                drive[start + i] += (amplitude * sin(PI * i / frames).pow(2) / frames).toFloat()
            }
        }
        // The earlier 2.3 ms open pulse suppressed most upper-head modes.
        // A firmer finger edge now sits beside the broader palm contact.
        pulse(.003, .0016 - .0007 * m.touch, if (slap) .18 else .8)
        repeat(if (slap) 4 else 2) { finger ->
            pulse(.0033 + finger * .00045 + .00008 * random.next(),
                .00032 - .00015 * m.touch, if (slap) .7 else .24)
        }
        val ratios = floatArrayOf(1f, 1.593f, 1.622f, 2.136f, 2.296f, 2.653f, 2.918f, 3.60f, 4.06f, 4.61f, 5.14f, 6.18f)
        if (reference) { ratios[1] = 370f / 230f; ratios[2] = 376f / 230f; ratios[3] = 485f / 230f }
        val weights = if (reference) {
            if (slap) floatArrayOf(.4f, 1f, .2f, 1.1f, .22f, .28f, .2f, .13f, .08f, .05f, .03f, .02f)
            else floatArrayOf(1f, .72f, .18f, .62f, .16f, .18f, .12f, .08f, .05f, .03f, .02f, .01f)
        } else if (slap) floatArrayOf(.07f, .20f, .17f, .60f, .68f, .85f, 1f, .92f, .72f, .60f, .42f, .28f)
            else floatArrayOf(.55f, 1f, .46f, .72f, .55f, .40f, .30f, .22f, .14f, .10f, .07f, .04f)
        val modes = ratios.mapIndexed { i, ratio ->
            // Compensation removes the resonator's 1/sin(theta) low-frequency
            // bias, so these weights describe attack balance rather than pole gain.
            val gain = weights[i] * sin(2 * PI * hz * ratio / rate).toFloat()
            val t60 = if (reference) (.32f + .40f * m.hold) / (1 + .28f * i) else if (slap) (.060f + .075f * m.hold) / (1 + .10f * i)
                else (.42f + .48f * m.hold) / (1 + .38f * i)
            Modes.Mode(ratio, gain, t60)
        }
        val head = Modes.ring(drive, hz, modes, rate)
        Dsp.normalize(head, .8f)
        if (reference) {
            // A resting hand adds loss after the attack. This time-varying loss
            // only removes energy; it does not pitch-sweep or add a second hit.
            val contactAt = if (slap) .009 else .055 + .035 * m.hold
            val loss = if (slap) 55.0 + 65.0 * m.touch else 5.0 + 8.0 * m.touch
            for (i in head.indices) {
                val t = max(0.0, i.toDouble() / rate - contactAt)
                head[i] *= exp(-loss * (t - .003 * (1 - exp(-t / .003)))).toFloat()
            }
        }
        // Feedforward air/shell response: the head drives it. There is no
        // artificial late impulse and no unstable feedback hidden by a limiter.
        val cavity = Modes.ring(head, hz, listOf(
            Modes.Mode(.72f, 1f, if (slap) .035f else .095f + .07f * m.hold),
            Modes.Mode(2.75f, .65f, .022f), Modes.Mode(4.35f, .3f, .013f)), rate)
        Dsp.normalize(cavity, .8f)
        val skin = FloatArray(n)
        val low = Dsp.Biquad().apply { bandpass(if (slap) 1800f else 1200f, .85f, rate) }
        val high = Dsp.Biquad().apply { bandpass(3400f + 2000f * m.touch, .75f, rate) }
        val noise = Dsp.Noise(Dsp.seedFor(seed, "hand-skin"))
        for (i in skin.indices) {
            val time = i.toDouble() / rate - .003
            // A finite skin-contact burst, with a sub-ms rise and a defined
            // release. Unlike snare wires it cannot chatter through the tail.
            val decay = if (slap) .0025 + .002 * m.touch else .008 + .008 * m.touch
            val envelope = if (time <= 0) 0.0 else (1 - exp(-time / .0003)) * exp(-time / decay)
            val x = (noise.next() * envelope).toFloat()
            skin[i] = .45f * low.process(x) + (if (slap) .85f else .35f) * high.process(x)
        }
        Dsp.normalize(skin, .8f)
        return Parts(head, cavity, skin)
    }

    fun render(stroke: Stroke, settings: Settings = Settings(),
               seed: Int = Dsp.seedFor("PATIO_HAND", stroke), reference: Boolean = false): Snip {
        val p = parts(stroke, settings, seed, reference)
        val slap = stroke == Stroke.SLAP
        val raw = FloatArray(p.head.size) { i ->
            val head = if (reference) .9f else if (slap) .38f else .72f
            val cavity = if (reference) (if (slap) .08f else .16f) else if (slap) .06f else .38f
            val skin = if (reference) (if (slap) .14f + .22f * settings.touch else .065f + .1f * settings.touch) else if (slap) .65f + .35f * settings.touch else .16f + .24f * settings.touch
            head * p.head[i] + cavity * p.cavity[i] + skin * p.skin[i]
        }
        Tide.bandLimit(raw, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = PatioWear.finish(Snip(Dsp.decimate(raw, Dsp.RATE), 1, Dsp.RATE))
        Dsp.levelTo(out.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out.samples)
        return out
    }
}
