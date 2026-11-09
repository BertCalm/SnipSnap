package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * A damped drum followed by a moving double, parallel compression and a small amp.
 * Shape constants are audition hypotheses, not measurements of vintage drums or pedals.
 * The dedicated drum source and VALVE use 4x paths; delay and dynamics run at 44.1 kHz.
 * No bus state is shared between pads and no repeating/HOLD output is promised.
 */
internal object BackwashV4 {
    const val MODEL_VERSION = 4
    internal const val TAIL_SECONDS = .65f
    private val controls = listOf(
        MacroSpec("TUNE", .5f), MacroSpec("DAMP", .75f), MacroSpec("SWAY", .3f),
        MacroSpec("TRAIL", .25f), MacroSpec("PRESS", .45f), MacroSpec("DRIVE", .25f),
    )

    fun macrosFor(voice: BackwashVoice): List<MacroSpec> = controls.map {
        if (it.name == "TUNE") it.copy(default = when (voice) {
            BackwashVoice.KICK -> .35f
            BackwashVoice.SNARE -> .65f
            BackwashVoice.TOM -> .35f
            else -> .5f
        }) else if (!voice.legacy) it.copy(default = when (it.name) {
            "SWAY" -> .55f
            "TRAIL" -> .55f
            "PRESS" -> .75f
            "DRIVE" -> .65f
            else -> it.default
        }) else it
    }
    fun defaults(voice: BackwashVoice) = macrosFor(voice).associate { it.name to it.default }
    fun settled(voice: BackwashVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k, d) ->
        macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d
    }
    fun scramble(voice: BackwashVoice, random: Random, temperature: Float = .35f, near: Patch? = null) =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)
    fun drumClassFor(voice: BackwashVoice): DrumClass = voice.drumClass

    /** The dry source is exposed internally for stage auditions, not as a second saved recipe. */
    internal fun source(voice: BackwashVoice, macros: Map<String, Float>, velocity: Float = 1f): Snip {
        val m = settled(voice, macros)
        val damp = m.getValue("DAMP")
        val v = velocity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        return if (voice.legacy) BackwashDrums.render(voice, m.getValue("TUNE"), damp, v)
            else BackwashPercussion.render(voice, m.getValue("TUNE"), damp, v)
    }

    /**
     * Linear interpolation has nonnegative weights summing to one; the feedback filter
     * is also a convex one-pole. With feedback <= .74 the loop is a contraction in peak
     * norm even while the delay moves. No clipper is used to make regeneration stable.
     * A smooth sinusoid avoids discontinuities in delay position. The fixed seed phase
     * is per voice, so unrelated knob changes never replace the modulation trajectory.
     */
    internal fun chorus(input: Snip, sway: Float, trail: Float, voice: BackwashVoice): Snip {
        val rate = input.sampleRate
        val extension = ((trail - .5f) * 2f).coerceIn(0f, 1f)
        val tail = if (extension == 1f) .9f else TAIL_SECONDS + .25f * extension
        val out = FloatArray(input.samples.size + (tail * rate).toInt())
        val line = FloatArray(((if (sway <= .5f && trail <= .5f) .06f else .08f) * rate).toInt())
        val lp = Dsp.FixedOnePole(5500f, rate)
        val feedback = extended(trail, .58f * trail, .29f, .74f)
        val wet = extended(trail, .12f + .48f * trail, .36f, .95f)
        val center = if (trail <= .5f) .018 + .006 * trail else .021 + .007 * extension
        val motion = ((sway - .5f) * 2f).coerceIn(0f, 1f).toDouble()
        val depth = if (sway <= .5f) .006 * sway else if (sway == 1f) .014 else .003 + .011 * motion
        val speed = if (sway <= .5f) .8 else if (sway == 1f) 1.15 else .8 + .35 * motion
        val phase = (Dsp.seedFor("BACKWASH", 1, voice.name).toUInt().toDouble() / UInt.MAX_VALUE.toDouble()) * 2.0 * PI
        var write = 0
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val delay = ((if (trail == 1f) .028 else center) + depth * sin(2.0 * PI * speed * t + phase)) * rate
            val delayed = Dsp.tap(line, write, delay.toFloat())
            val x = input.samples.getOrElse(i) { 0f }
            line[write] = x + feedback * lp.lp(delayed)
            out[i] = x + wet * delayed
            write = (write + 1) % line.size
        }
        return Snip(out, 1, rate)
    }

    // Keep the heard factory range exactly; the upper half reaches the approved study.
    private fun extended(value: Float, original: Float, midpoint: Float, top: Float): Float =
        if (value <= .5f) original else if (value == 1f) top
        else midpoint + (top - midpoint) * ((value - .5f) * 2f)

    internal fun press(input: Snip, amount: Float): Snip {
        if (amount == 0f) return input
        val compressed = Compressor.process(input, mapOf(
            "AMOUNT" to extended(amount, .15f + .45f * amount, .375f, .88f),
            "ATTACK" to extended(amount, .7f, .7f, .58f),
            "RECOVER" to extended(amount, .6f, .6f, .84f),
        ))
        val blend = extended(amount, .75f * amount, .375f, .9f)
        val dry = if (amount == 1f) .1f else 1f - blend
        // Compressor preserves frame count: this dry path is aligned with its output.
        return Snip(FloatArray(input.samples.size) {
            input.samples[it] * dry + compressed.samples[it] * blend
        }, 1, input.sampleRate)
    }

    internal data class Stages(val dry: Snip, val moving: Snip, val pressed: Snip, val amp: Snip, val output: Snip)

    internal fun inspect(voice: BackwashVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Stages {
        val m = settled(voice, macros)
        val dry = source(voice, m, velocity)
        val delayed = chorus(dry, m.getValue("SWAY"), m.getValue("TRAIL"), voice)
        // Open source + maximum regeneration stays a one-shot, below the LOOP boundary.
        val moving = if (delayed.samples.size < (1.49f * Dsp.RATE).toInt()) delayed
            else Snip(delayed.samples.copyOf((1.49f * Dsp.RATE).toInt()), 1, Dsp.RATE)
        val pressed = press(moving, m.getValue("PRESS"))
        val drive = m.getValue("DRIVE")
        // The upper half adds saturation and a low-band parallel path for body.
        val amp = Valve.process(pressed, mapOf("DRIVE" to extended(drive, Dsp.lin(drive, .32f, .65f), Dsp.lin(.5f, .32f, .65f), .84f),
            "SAG" to extended(drive, .2f, .2f, .35f), "TONE" to .48f, "CAB" to .55f))
        val out = amp.samples.copyOf()
        if (drive > .5f) {
            val blend = .2f * ((drive - .5f) * 2f)
            val low = Dsp.FixedOnePole(160f, Dsp.RATE)
            for (i in out.indices) out[i] = (1f - blend) * out[i] + blend * low.lp(pressed.samples.getOrElse(i) { 0f })
        }
        val mean = out.average().toFloat()
        val dc = Dsp.FixedOnePole(20f, Dsp.RATE)
        for (i in out.indices) { val x = out[i] - mean; out[i] = x - dc.lp(x) }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Stages(dry, moving, pressed, amp, Snip(out, 1, Dsp.RATE))
    }

    fun render(voice: BackwashVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip =
        inspect(voice, macros, velocity).output
}
