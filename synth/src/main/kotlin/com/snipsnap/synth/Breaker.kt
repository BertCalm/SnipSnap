package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/** BREAKER's drums. Round one ships the two the owner heard at round 0; the rest of the kit waits for its own listen. */
enum class BreakerVoice(val drumClass: DrumClass) { KICK(DrumClass.KICK), SNARE(DrumClass.SNARE) }

/**
 * BREAKER: surf rock by way of boom bap. Drums as if sampled off a surf record,
 * pushed through a drum processor and a surf amp.
 *
 * The characteristic gesture is **dusty crack → spring drip → crushed swell → speaker bark**.
 *
 * The chain, in the processor's reverse order (docs/BREAKER.md):
 * 1. **Source:** Backroom's accepted SKIN kick and snare (docs/BOOM_BAP.md), their full macro maps
 *    frozen here, struck at the pad's velocity and re-struck per take.
 * 2. **DUST:** the record and the sampler, the rack's own CRUNCH and VINYL.
 * 3. **Saturation:** an asymmetric tube curve, 4x.
 * 4. **CRUSH, first half:** an FET-style compressor gluing the hit, before the spring.
 * 5. **DRIP:** a spring tank. Its echoes rise because the springs' low notes travel faster.
 * 6. **SLAP:** one echo line.
 * 7. **CRUSH, second half:** the same compressor after the spring. Each hit ducks the drip and it
 *    swells back up, the processor's reverse mode.
 * 8. **AMP:** the rack's VALVE, a bright open-back combo.
 *
 * The rack cannot make this: its compressor and VALVE sit before its spring. So, as BACKWASH, the
 * order is the engine's own.
 *
 * Literal: the spring's dispersion (a stretched allpass chain, the parametric model of Välimäki,
 * Parker and Abel, JAES 58(7/8), 2010, not the springs' physics) and the compressor's detector and
 * smoother (Giannoulis, Massberg and Reiss, JAES 60(6), 2012). Analogy: the tube curve, the FET's
 * grit, the dwell drive. Every constant is a shape the owner heard at round 0, not a measurement of
 * the processor, the amp or a record. No samples of any record are used.
 *
 * No HOLD: every hit is a one-shot, [SECONDS] long, under the classifier's loop threshold.
 */
object Breaker {
    /**
     * 2 (round 1b): the kick rings through tank A, the tank its round-0 pick was heard through, and
     * the slap is the round-0 95 ms. 1 (round 1): every voice through tank B, a 90 ms slap. A save
     * keeps the model it was made with.
     */
    const val MODEL_VERSION = 2

    /** The render's length: tails are capped here, with a [FADE_SECONDS] raised-cosine fade, so a hit never files as a LOOP (1.5 s). */
    const val SECONDS = 1.49f
    private const val FADE_SECONDS = 0.25f

    // The owner's round-0 picks (2026-11-09): the kick at high crush and a little spring, the snare
    // into tank B at high crush ("Swells, I like it"). The rest are the round-0 defaults.
    private val controls = listOf(
        MacroSpec("TUNE", .5f), MacroSpec("DUST", .35f), MacroSpec("DRIP", .45f),
        MacroSpec("SLAP", .2f), MacroSpec("CRUSH", .9f), MacroSpec("AMP", .35f),
    )

    fun macrosFor(voice: BreakerVoice): List<MacroSpec> = controls.map {
        if (it.name == "DRIP") it.copy(default = if (voice == BreakerVoice.KICK) .2f else .6f) else it
    }

    fun defaults(voice: BreakerVoice) = macrosFor(voice).associate { it.name to it.default }

    fun settled(voice: BreakerVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k, d) ->
        macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d
    }

    fun scramble(voice: BreakerVoice, random: Random, temperature: Float = .35f, near: Patch? = null) =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)

    fun drumClassFor(voice: BreakerVoice): DrumClass = voice.drumClass

    /**
     * Backroom's kick and snare as accepted (BoomBapKits.parts(): each SKIN default, its preset, its
     * overrides), written out so a later SKIN preset edit cannot recolour a saved BREAKER hit.
     */
    private val SOURCES: Map<BreakerVoice, Pair<SkinVoice, Map<String, Float>>> = mapOf(
        BreakerVoice.KICK to (SkinVoice.KICK to mapOf("TUNE" to .38f, "DECAY" to .32f, "TONE" to .30f, "PUNCH" to .70f, "CLICK" to .40f, "DROP" to .20f)),
        BreakerVoice.SNARE to (SkinVoice.SNARE to mapOf("TUNE" to .47f, "SNAP" to .70f, "DECAY" to .40f, "PUNCH" to .76f, "RATTLE" to .62f, "SIZZLE" to .44f)),
    )

    /** The dry drum: TUNE moves the source's own TUNE by up to a quarter of its range either way (shape). */
    internal fun source(voice: BreakerVoice, m: Map<String, Float>, velocity: Float, take: Int): Snip {
        val (skin, macros) = SOURCES.getValue(voice)
        val tune = (macros.getValue("TUNE") + (m.getValue("TUNE") - .5f) * .5f).coerceIn(0f, 1f)
        return SkinPatch("Breaker source", skin, Skin.defaults(skin) + macros + ("TUNE" to tune)).render(velocity, take)
    }

    /**
     * DUST (shape): 0 transparent; .35 Backroom's accepted crunch with a light record; 1 a sampler
     * under 12 bits at about 18 kHz on a busy record. Piecewise linear between the three.
     */
    internal fun dustChain(d: Float, voice: BreakerVoice): FxChain? {
        if (d <= 0f) return null
        fun seg(a: Float, b: Float, c: Float): Float = if (d <= .35f) a + (b - a) * (d / .35f) else b + (c - b) * ((d - .35f) / .65f)
        val tone = if (voice == BreakerVoice.SNARE) .55f else .43f
        return FxChain(
            crunch = mapOf("BITS" to seg(0f, .16f, .5f), "RATE" to seg(0f, .29f, .62f), "TONE" to seg(1f, tone, .35f), "GRIT" to seg(0f, .34f, .5f)),
            vinyl = mapOf("CRACKLE" to seg(0f, .15f, .5f), "RUMBLE" to seg(0f, .1f, .25f), "HISS" to seg(0f, .1f, .3f)),
        )
    }

    /**
     * Each drum's tank, as the owner picked it at round 0. The snare rings through tank B, the
     * surf spring they chose. The kick rings through tank A, the tank its "high crush" pick was
     * heard through: even at the kick's short DRIP the tank colours the tail, so a kick through
     * tank B is not the kick that was picked (round 1 heard it "close, but not it"). Model 1 put
     * both through tank B.
     */
    private fun tankFor(voice: BreakerVoice, model: Int) =
        if (model >= 2 && voice == BreakerVoice.KICK) BreakerChain.TANK_A else BreakerChain.TANK_B

    fun render(
        voice: BreakerVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        take: Int = 0,
        model: Int = MODEL_VERSION,
    ): Snip {
        require(model in 1..MODEL_VERSION) { "unsupported breaker model $model" }
        val m = settled(voice, macros)
        val v = velocity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val dry = source(voice, m, v, take)
        val dusted = dustChain(m.getValue("DUST"), voice)?.process(dry) ?: dry
        val rate = Dsp.RATE
        val frames = (SECONDS * rate).toInt()
        val crush = m.getValue("CRUSH").toDouble()
        var x = BreakerChain.saturate(DoubleArray(dusted.samples.size) { dusted.samples[it].toDouble() }, rate)
        x = BreakerChain.crush(x, minOf(1.0, crush / .5) * .7, rate)
        x = BreakerChain.tankMix(x, m.getValue("DRIP").toDouble(), frames, rate, tankFor(voice, model))
        x = BreakerChain.slap(x, m.getValue("SLAP").toDouble(), rate, if (model >= 2) BreakerChain.SLAP_SECONDS else .09)
        x = BreakerChain.crush(x, maxOf(0.0, (crush - .5) / .5), rate)
        val amp = m.getValue("AMP")
        val amped = Valve.process(Snip(FloatArray(frames) { x[it].toFloat() }, 1, rate),
            mapOf("DRIVE" to .3f + .45f * amp, "SAG" to .35f, "TONE" to .6f, "CAB" to .25f))
        val out = amped.samples.copyOf(frames)
        val mean = out.average().toFloat()
        val dc = Dsp.FixedOnePole(20f, rate)
        for (i in out.indices) { val s = out[i] - mean; out[i] = s - dc.lp(s) }
        val fade = (FADE_SECONDS * rate).toInt()
        for (k in 0 until fade) out[frames - fade + k] *= (0.5 + 0.5 * cos(PI * k / fade)).toFloat()
        Dsp.levelTo(out, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, 1, rate)
    }
}
