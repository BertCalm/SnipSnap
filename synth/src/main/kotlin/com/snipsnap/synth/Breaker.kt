package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * BREAKER's drums: the kick and snare the owner accepted (rounds 0 to 1b), and round 2's hats, cross
 * stick, shaker, tambourine, three toms and ride (docs/BREAKER.md). The ride has no drum class of its
 * own in the classifier; it files as PERC, as BACKWASH's does.
 */
enum class BreakerVoice(val drumClass: DrumClass) {
    KICK(DrumClass.KICK), SNARE(DrumClass.SNARE),
    HAT_CLOSED(DrumClass.HAT_CLOSED), HAT_OPEN(DrumClass.HAT_OPEN),
    STICK(DrumClass.PERC), SHAKER(DrumClass.PERC), TAMBOURINE(DrumClass.PERC),
    TOM_FLOOR(DrumClass.TOM), TOM_RACK(DrumClass.TOM), TOM_HIGH(DrumClass.TOM),
    RIDE(DrumClass.PERC),
}

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

    /**
     * Each drum's place in the rig (shape; round 2's are proposals for the owner's listen). The hats
     * barely touch the spring and do not slap; the cross stick is the slap; the toms drip, the floor
     * most; the ride washes; the shaker and tambourine sit lightly in the tank.
     */
    private val voiceDefaults: Map<BreakerVoice, Map<String, Float>> = mapOf(
        BreakerVoice.KICK to mapOf("DRIP" to .2f),
        BreakerVoice.SNARE to mapOf("DRIP" to .6f),
        BreakerVoice.HAT_CLOSED to mapOf("DRIP" to .1f, "SLAP" to 0f, "CRUSH" to .6f),
        BreakerVoice.HAT_OPEN to mapOf("DRIP" to .15f, "SLAP" to 0f, "CRUSH" to .6f),
        BreakerVoice.STICK to mapOf("DRIP" to .3f, "SLAP" to .45f, "CRUSH" to .7f),
        BreakerVoice.SHAKER to mapOf("DRIP" to .15f, "SLAP" to .1f, "CRUSH" to .4f),
        BreakerVoice.TAMBOURINE to mapOf("DUST" to .3f, "DRIP" to .35f, "CRUSH" to .5f),
        BreakerVoice.TOM_FLOOR to mapOf("DRIP" to .6f, "CRUSH" to .8f),
        BreakerVoice.TOM_RACK to mapOf("DRIP" to .45f, "CRUSH" to .8f),
        BreakerVoice.TOM_HIGH to mapOf("DRIP" to .4f, "CRUSH" to .8f),
        BreakerVoice.RIDE to mapOf("DUST" to .3f, "DRIP" to .45f, "SLAP" to 0f, "CRUSH" to .6f),
    )

    fun macrosFor(voice: BreakerVoice): List<MacroSpec> {
        val own = voiceDefaults.getValue(voice)
        return controls.map { spec -> own[spec.name]?.let { spec.copy(default = it) } ?: spec }
    }

    fun defaults(voice: BreakerVoice) = macrosFor(voice).associate { it.name to it.default }

    fun settled(voice: BreakerVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k, d) ->
        macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d
    }

    fun scramble(voice: BreakerVoice, random: Random, temperature: Float = .35f, near: Patch? = null) =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)

    fun drumClassFor(voice: BreakerVoice): DrumClass = voice.drumClass

    /**
     * The SKIN sources, every control written out so a later SKIN preset edit cannot recolour a saved
     * BREAKER hit:
     * - the kick, snare, hats, cross stick and shaker are Backroom's as accepted (BoomBapKits.parts():
     *   each SKIN default, its preset, its overrides);
     * - the toms are one SKIN shell (a little bend, a stick on the head) at about 124, 154 and 188 Hz,
     *   near a major third apart;
     * - the ride is SKIN's LONG RIDE with its rivets sizzling, for the wash.
     */
    private val SOURCES: Map<BreakerVoice, Pair<SkinVoice, Map<String, Float>>> = mapOf(
        BreakerVoice.KICK to (SkinVoice.KICK to mapOf("TUNE" to .38f, "DECAY" to .32f, "TONE" to .30f, "PUNCH" to .70f, "CLICK" to .40f, "DROP" to .20f)),
        BreakerVoice.SNARE to (SkinVoice.SNARE to mapOf("TUNE" to .47f, "SNAP" to .70f, "DECAY" to .40f, "PUNCH" to .76f, "RATTLE" to .62f, "SIZZLE" to .44f)),
        BreakerVoice.HAT_CLOSED to (SkinVoice.HAT_CLOSED to mapOf("TUNE" to .54f, "DECAY" to .18f, "TONE" to .40f, "PUNCH" to .55f, "RING" to .27f)),
        BreakerVoice.HAT_OPEN to (SkinVoice.HAT_OPEN to mapOf("TUNE" to .54f, "DECAY" to .32f, "TONE" to .42f, "PUNCH" to .55f, "RING" to .28f)),
        BreakerVoice.STICK to (SkinVoice.STICK to mapOf("TUNE" to .35f, "DECAY" to .44f, "PUNCH" to .55f, "BODY" to .60f, "CLICK" to .22f)),
        BreakerVoice.SHAKER to (SkinVoice.SHAKER to mapOf("TONE" to .55f, "DECAY" to .44f, "PUNCH" to .35f, "SWELL" to .18f, "GRAIN" to .62f)),
        BreakerVoice.TOM_FLOOR to (SkinVoice.TOM to tom(.35f, .65f)),
        BreakerVoice.TOM_RACK to (SkinVoice.TOM to tom(.60f, .55f)),
        BreakerVoice.TOM_HIGH to (SkinVoice.TOM to tom(.82f, .50f)),
        BreakerVoice.RIDE to (SkinVoice.RIDE to mapOf("TUNE" to .68f, "DECAY" to .89f, "TONE" to .48f, "PUNCH" to .45f, "SIZZLE" to .35f)),
    )

    /** One tom shell, tuned (SKIN's TOM maps TUNE 0..1 onto 90..220 Hz, exponentially) and damped. */
    private fun tom(tune: Float, decay: Float) =
        mapOf("TUNE" to tune, "DECAY" to decay, "TONE" to .50f, "PUNCH" to .55f, "DROP" to .15f, "CLICK" to .25f)

    /**
     * The dry drum. TUNE moves the source's own TUNE by up to a quarter of its range either way
     * (shape); on the shaker, which has none, it moves the band (TONE).
     *
     * The velocity is the hit's strength:
     * - SKIN's struck voices (kick, snare, toms) take it as their own contact law.
     * - SKIN does not strike the hats, ride, shaker and cross stick, so their sources are always the
     *   full hit. [render] darkens the finished hit instead ([UNSTRUCK_SOFTEN] says why).
     * - The tambourine is BACKWASH's CC0 recordings, and the velocity picks the recorded dynamic layer.
     *
     * [take] re-strikes it: a fresh noise seed in the SKIN source, or the next recorded alternate on
     * the tambourine (it has two).
     */
    internal fun source(voice: BreakerVoice, m: Map<String, Float>, velocity: Float, take: Int): Snip {
        val shift = (m.getValue("TUNE") - .5f) * .5f
        if (voice == BreakerVoice.TAMBOURINE) {
            val tamb = BackwashVoice.TAMBOURINE
            return BackwashAcoustic.source(tamb, Backwash.defaults(tamb) + ("TUNE" to (.5f + shift).coerceIn(0f, 1f)), velocity, take)
        }
        val (skin, macros) = SOURCES.getValue(voice)
        var own = Skin.defaults(skin) + macros
        // The shaker has no TUNE: its pitch is its band, so TUNE moves TONE (shape).
        val pitch = if ("TUNE" in own) "TUNE" else "TONE"
        own = own + (pitch to (own.getValue(pitch) + shift).coerceIn(0f, 1f))
        // SKIN reads the velocity only on its struck voices; the unstruck ones soften after the rig.
        // Every BREAKER source re-seeds per take, the unstruck ones included.
        return Skin.render(skin, own, if (skin in Skin.STRUCK) velocity else 1f, take, reseedUnstruck = true)
    }

    /** The hats, ride, shaker and cross stick: SKIN does not strike them, so they soften after the rig. */
    private fun unstruck(voice: BreakerVoice) = SOURCES[voice]?.first?.let { it !in Skin.STRUCK } ?: false

    /**
     * How far [Velocity.soften] darkens an unstruck drum's finished hit at velocity 0 (shape): at 40%
     * its corner is near 4.8 kHz.
     *
     * Why after the rig. Measured at round 2, a 40% closed hat darkened at the source came out of the
     * rig with more energy above 6 kHz than a full one: +1.4 dB with SKIN's TONE scaled down (a hat's
     * TONE spreads its partials, and spreading them less pulls the top ones under DUST's filter), and
     * +1.8 dB low-passed near 2.3 kHz. The rig levels every hit into the tube, so DUST, the tube and
     * the crush set the top end, not the input. A softer hand on these drums is the finished hit
     * with its top rolled off, as [Velocity.soften] does for captured audio.
     */
    private const val UNSTRUCK_SOFTEN = .6f

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
        require(model >= 2 || voice == BreakerVoice.KICK || voice == BreakerVoice.SNARE) { "breaker model 1 has no $voice" }
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
        val done = if (unstruck(voice)) Velocity.soften(Snip(out, 1, rate), (1f - v) * UNSTRUCK_SOFTEN).samples else out
        Dsp.levelTo(done, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(done)
        return Snip(done, 1, rate)
    }
}
