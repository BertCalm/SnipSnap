package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Frozen model-3 renderer for saved recipes and the original factory bank.
 * A damped drum followed by a moving double, parallel compression and a small amp.
 * Shape constants are audition hypotheses, not measurements of vintage drums or pedals.
 * The dedicated drum source and VALVE use 4x paths; delay and dynamics run at 44.1 kHz.
 * No bus state is shared between pads and no repeating/HOLD output is promised.
 */
internal object BackwashV3 {
    const val MODEL_VERSION = 3
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
        return BackwashDrums.render(voice, m.getValue("TUNE"), damp, v)
    }

    /**
     * Linear interpolation has nonnegative weights summing to one; the feedback filter
     * is also a convex one-pole. With feedback <= .58 the loop is a contraction in peak
     * norm even while the delay moves. No clipper is used to make regeneration stable.
     * A smooth sinusoid avoids discontinuities in delay position. The fixed seed phase
     * is per voice, so unrelated knob changes never replace the modulation trajectory.
     */
    internal fun chorus(input: Snip, sway: Float, trail: Float, voice: BackwashVoice): Snip {
        val rate = input.sampleRate
        val out = FloatArray(input.samples.size + (TAIL_SECONDS * rate).toInt())
        val line = FloatArray((.06f * rate).toInt())
        val lp = Dsp.FixedOnePole(5500f, rate)
        val feedback = .58f * trail
        val wet = .12f + .48f * trail
        val phase = (Dsp.seedFor("BACKWASH", 1, voice.name).toUInt().toDouble() / UInt.MAX_VALUE.toDouble()) * 2.0 * PI
        var write = 0
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val delay = (.018 + .006 * trail + .006 * sway * sin(2.0 * PI * .8 * t + phase)) * rate
            val delayed = Dsp.tap(line, write, delay.toFloat())
            val x = input.samples.getOrElse(i) { 0f }
            line[write] = x + feedback * lp.lp(delayed)
            out[i] = x + wet * delayed
            write = (write + 1) % line.size
        }
        return Snip(out, 1, rate)
    }

    internal fun press(input: Snip, amount: Float): Snip {
        if (amount == 0f) return input
        val compressed = Compressor.process(input, mapOf(
            "AMOUNT" to (.15f + .45f * amount), "ATTACK" to .7f, "RECOVER" to .6f,
        ))
        val blend = .75f * amount
        // Compressor preserves frame count: this dry path is aligned with its output.
        return Snip(FloatArray(input.samples.size) {
            input.samples[it] * (1f - blend) + compressed.samples[it] * blend
        }, 1, input.sampleRate)
    }

    internal data class Stages(val dry: Snip, val moving: Snip, val pressed: Snip, val amp: Snip, val output: Snip)

    internal fun inspect(voice: BackwashVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Stages {
        val m = settled(voice, macros)
        val dry = source(voice, m, velocity)
        val moving = chorus(dry, m.getValue("SWAY"), m.getValue("TRAIL"), voice)
        val pressed = press(moving, m.getValue("PRESS"))
        // Stay in VALVE's gentle region. Its full drive range is intentionally much wilder.
        val amp = Valve.process(pressed, mapOf("DRIVE" to Dsp.lin(m.getValue("DRIVE"), .32f, .65f),
            "SAG" to .2f, "TONE" to .48f, "CAB" to .55f))
        val out = amp.samples.copyOf()
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
