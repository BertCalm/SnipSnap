package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * BREAKER's drums (docs/BREAKER.md):
 * - the kick and snare the owner accepted (rounds 0 to 1b);
 * - round 2's hats, cross stick, shaker, tambourine and three toms;
 * - round 3's ride and crash, a modal synth fitted to CC0 recordings ([BreakerCymbal]);
 * - the snare riser, a roll into the spring;
 * - model 5's Bump, the spring tank kicked.
 *
 * The cymbals, the riser and Bump have no drum class of their own in the classifier; they file as PERC,
 * as BACKWASH's percussion does.
 */
enum class BreakerVoice(val drumClass: DrumClass) {
    KICK(DrumClass.KICK), SNARE(DrumClass.SNARE),
    HAT_CLOSED(DrumClass.HAT_CLOSED), HAT_OPEN(DrumClass.HAT_OPEN),
    STICK(DrumClass.PERC), SHAKER(DrumClass.PERC), TAMBOURINE(DrumClass.PERC),
    TOM_FLOOR(DrumClass.TOM), TOM_RACK(DrumClass.TOM), TOM_HIGH(DrumClass.TOM),
    RIDE(DrumClass.PERC), CRASH(DrumClass.PERC), RISER(DrumClass.PERC),
    BUMP(DrumClass.PERC),
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
 * No HOLD. Every hit is a one-shot, [SECONDS] long, under the classifier's loop threshold, with two
 * exceptions that are longer and declared PERC: the crash ([CRASH_SECONDS]) and the riser
 * ([RISER_SECONDS]).
 */
object Breaker {
    /**
     * Renderer models. A save keeps the model it was made with.
     * - **5 (the genre kits, 2026-11-12):** DAMP, each drum's damping, and Bump, the spring tank kicked.
     *   The owner approved both for Dub Tank and Trip hop. At DAMP .5 every model-4 voice renders as it
     *   did on model 4.
     * - **4 (the kit's tone, 2026-11-12):** the owner heard the kit as mid heavy, "like listening
     *   through dollar tree headphones", and picked the open rig: DUST's filter opens and the amp
     *   loses its speaker ([open]). The cymbals and the riser keep the rig they were heard with.
     * - **3 (round 3):** the ride is the fitted modal ride, dry by default. The crash and the riser
     *   arrive.
     * - **2 (round 1b):** the kick rings through tank A, the tank its round-0 pick was heard through,
     *   and the slap is the round-0 95 ms. The ride is round 2's SKIN ride through the rig.
     * - **1 (round 1):** every voice through tank B, a 90 ms slap.
     */
    const val MODEL_VERSION = 5

    /** A hit's length: tails are capped here, with a [FADE_SECONDS] raised-cosine fade, so a hit never files as a LOOP (1.5 s). */
    const val SECONDS = 1.49f
    private const val FADE_SECONDS = 0.25f

    /** The crash rings past the one-shot limit (the owner's call, 2026-11-11); its pad is declared PERC, never classified. */
    const val CRASH_SECONDS = 3.2f

    /** Bump: the kicked tank rings out, so it is longer than a one-shot and declared PERC. */
    const val BUMP_SECONDS = 2.5f

    /** The riser: a 2.4 s build and the spring after it. */
    const val RISER_SECONDS = 3.4f
    private const val RISER_BUILD = 2.4f

    fun secondsFor(voice: BreakerVoice) = when (voice) {
        BreakerVoice.CRASH -> CRASH_SECONDS
        BreakerVoice.RISER -> RISER_SECONDS
        BreakerVoice.BUMP -> BUMP_SECONDS
        else -> SECONDS
    }

    /** The voices model 3 added. Model 2 renders the ride its own way, and refuses these. */
    private val MODEL_3 = setOf(BreakerVoice.CRASH, BreakerVoice.RISER)

    /** The voice model 5 added. Earlier models refuse it. */
    private val MODEL_5 = setOf(BreakerVoice.BUMP)

    /**
     * DAMP (model 5, shape): each drum's damping. At .5 nothing changes. Above it the drum is damped
     * and dies sooner; below it the drum rings longer.
     * - **SKIN's drums** (kick, snare, toms, hats, stick, shaker, the riser's strikes): their own DECAY
     *   moves by up to [DAMP_DECAY_SPAN] either way. Above .5 the drum is also damped as tape on a head
     *   damps it, an exponential fade on the drum before the rig, up to [DAMP_RATE] per second. In the
     *   rig, a drum's own DECAY barely moves the snare, hats, stick or floor tom, whose tails are the
     *   spring's (measured, 2026-11-12).
     * - **The cymbals:** every fitted mode's decay is steepened, or eased, by up to 2x.
     * - **Bump:** the kicked tank rings shorter or longer, by up to 2x.
     * - **The tambourine:** recordings cannot ring longer than they were played, so below .5 it is
     *   unchanged. Above .5 it is BACKWASH's own DAMP, which shortens the recording.
     */
    private const val DAMP_DECAY_SPAN = .3f

    /** The tape's fade at DAMP 1, per second: about 12 dB in the first 100 ms (shape). */
    private const val DAMP_RATE = 14.0

    private fun damp(m: Map<String, Float>, model: Int) = if (model >= 5) m["DAMP"] ?: .5f else .5f

    /** How much DAMP scales a decay rate: 1 at .5, 2 (twice as fast) at 1, 1/2 at 0. */
    private fun dampRate(damp: Float) = 2.0.pow((damp - .5) * 2)

    private fun modal(voice: BreakerVoice, model: Int) = voice == BreakerVoice.CRASH || (voice == BreakerVoice.RIDE && model >= 3)

    /**
     * Model 4's open rig, on every drum but the cymbals and the riser, which keep the rig the owner
     * heard them with at round 3. Measured on the kit (model 3), almost everything sat between about
     * 100 Hz and 4 kHz. DUST's crunch low-passed every drum near 4.2 kHz (5.5 kHz on the snare), and
     * the combo's speaker rolled off the top further and added a nasal bump near 3 kHz. The owner
     * heard four versions of the same groove and picked this one: the filter opened, the speaker gone,
     * and their snare, hat and kick heard as "better" (2026-11-12).
     */
    private fun open(voice: BreakerVoice, model: Int) =
        model >= 4 && voice != BreakerVoice.RIDE && voice != BreakerVoice.CRASH && voice != BreakerVoice.RISER

    /** DUST's crunch output filter at DUST .35 on the open rig: about 8.9 kHz, CRUNCH's own default. */
    private const val OPEN_DUST_TONE = .75f

    // The owner's round-0 picks (2026-11-09): the kick at high crush and a little spring, the snare
    // into tank B at high crush ("Swells, I like it"). The rest are the round-0 defaults.
    private val controls = listOf(
        MacroSpec("TUNE", .5f), MacroSpec("DUST", .35f), MacroSpec("DRIP", .45f),
        MacroSpec("SLAP", .2f), MacroSpec("CRUSH", .9f), MacroSpec("AMP", .35f),
        MacroSpec("DAMP", .5f),
    )

    /**
     * Each drum's place in the rig (shape), as the owner heard it.
     * - **Round 2 (2026-11-10):** the hats nearly dry, unslapped; the cross stick with the short slap;
     *   the toms drippy, the floor most; the shaker and tambourine light in the tank.
     * - **Round 3 (2026-11-11):**
     *   - The ride and crash are dry: every control at 0 is a true bypass, so the dial adds the rig.
     *   - The riser is tamed: half crush, a light amp, less dust.
     */
    private val voiceDefaults: Map<BreakerVoice, Map<String, Float>> = mapOf(
        BreakerVoice.KICK to mapOf("DRIP" to .2f),
        BreakerVoice.SNARE to mapOf("DRIP" to .6f),
        BreakerVoice.HAT_CLOSED to mapOf("DRIP" to .1f, "SLAP" to 0f, "CRUSH" to .6f),
        BreakerVoice.HAT_OPEN to mapOf("DRIP" to .15f, "SLAP" to 0f, "CRUSH" to .6f),
        BreakerVoice.STICK to mapOf("DRIP" to .3f, "SLAP" to .2f, "CRUSH" to .7f),
        BreakerVoice.SHAKER to mapOf("DRIP" to .15f, "SLAP" to .1f, "CRUSH" to .4f),
        BreakerVoice.TAMBOURINE to mapOf("DUST" to .3f, "DRIP" to .35f, "CRUSH" to .5f),
        BreakerVoice.TOM_FLOOR to mapOf("DRIP" to .6f, "CRUSH" to .8f),
        BreakerVoice.TOM_RACK to mapOf("DRIP" to .45f, "CRUSH" to .8f),
        BreakerVoice.TOM_HIGH to mapOf("DRIP" to .4f, "CRUSH" to .8f),
        BreakerVoice.RIDE to DRY,
        BreakerVoice.CRASH to DRY,
        BreakerVoice.RISER to mapOf("DUST" to .2f, "DRIP" to .6f, "SLAP" to .2f, "CRUSH" to .5f, "AMP" to .2f),
        // Bump: DRIP is how hard the tank is kicked. Like the cymbals, it skips the tube.
        BreakerVoice.BUMP to mapOf("DUST" to .2f, "DRIP" to .7f, "SLAP" to .2f, "CRUSH" to .5f, "AMP" to .3f),
    )

    private val DRY get() = mapOf("DUST" to 0f, "DRIP" to 0f, "SLAP" to 0f, "CRUSH" to 0f, "AMP" to 0f)

    /** Model 2's ride defaults (round 2): a save keeps only the controls it set, so the rest must stay these. */
    private val RIDE_MODEL_2 = mapOf("DUST" to .3f, "DRIP" to .45f, "SLAP" to 0f, "CRUSH" to .6f)

    fun macrosFor(voice: BreakerVoice, model: Int = MODEL_VERSION): List<MacroSpec> {
        val own = if (voice == BreakerVoice.RIDE && model < 3) RIDE_MODEL_2 else voiceDefaults.getValue(voice)
        // DAMP arrived with model 5: an older save has no such control.
        return controls.filter { model >= 5 || it.name != "DAMP" }.map { spec -> own[spec.name]?.let { spec.copy(default = it) } ?: spec }
    }

    fun defaults(voice: BreakerVoice, model: Int = MODEL_VERSION) = macrosFor(voice, model).associate { it.name to it.default }

    fun settled(voice: BreakerVoice, macros: Map<String, Float>, model: Int = MODEL_VERSION) = defaults(voice, model).mapValues { (k, d) ->
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
     * - model 2's ride is SKIN's LONG RIDE with its rivets sizzling. The owner heard it as a crash, and
     *   model 3's ride is [BreakerCymbal]'s.
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
    internal fun source(voice: BreakerVoice, m: Map<String, Float>, velocity: Float, take: Int, model: Int = MODEL_VERSION): Snip {
        val shift = (m.getValue("TUNE") - .5f) * .5f
        val damp = damp(m, model)
        if (voice == BreakerVoice.TAMBOURINE) {
            val tamb = BackwashVoice.TAMBOURINE
            val own = Backwash.defaults(tamb) + ("TUNE" to (.5f + shift).coerceIn(0f, 1f))
            // A recording cannot ring longer than it was played: DAMP only shortens it, through BACKWASH's own DAMP.
            val shortened = if (damp > .5f) own + ("DAMP" to ((damp - .5f) * 2f).coerceIn(0f, 1f)) else own
            return BackwashAcoustic.source(tamb, shortened, velocity, take)
        }
        val (skin, macros) = SOURCES.getValue(voice)
        var own = Skin.defaults(skin) + macros
        if (damp != .5f) own = own + ("DECAY" to (own.getValue("DECAY") + (.5f - damp) * 2f * DAMP_DECAY_SPAN).coerceIn(0f, 1f))
        // The shaker has no TUNE: its pitch is its band, so TUNE moves TONE (shape).
        val pitch = if ("TUNE" in own) "TUNE" else "TONE"
        own = own + (pitch to (own.getValue(pitch) + shift).coerceIn(0f, 1f))
        // SKIN reads the velocity only on its struck voices; the unstruck ones soften after the rig.
        // Every BREAKER source re-seeds per take, the unstruck ones included.
        val hit = Skin.render(skin, own, if (skin in Skin.STRUCK) velocity else 1f, take, reseedUnstruck = true)
        if (damp <= .5f) return hit
        val k = (damp - .5) * 2 * DAMP_RATE / hit.sampleRate
        return Snip(FloatArray(hit.samples.size) { (hit.samples[it] * exp(-k * it)).toFloat() }, hit.channels, hit.sampleRate)
    }

    /** The hats, shaker and cross stick (and model 2's ride): SKIN does not strike them, so they soften after the rig. */
    private fun unstruck(voice: BreakerVoice, model: Int) =
        !modal(voice, model) && (SOURCES[voice]?.first?.let { it !in Skin.STRUCK } ?: false)

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
    internal fun dustChain(d: Float, voice: BreakerVoice, model: Int = MODEL_VERSION): FxChain? {
        if (d <= 0f) return null
        fun seg(a: Float, b: Float, c: Float): Float = if (d <= .35f) a + (b - a) * (d / .35f) else b + (c - b) * ((d - .35f) / .65f)
        val tone = when {
            open(voice, model) -> OPEN_DUST_TONE
            // The riser is a snare roll, and the owner picked it with the snare's tone (round 3).
            voice == BreakerVoice.SNARE || voice == BreakerVoice.RISER -> .55f
            else -> .43f
        }
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

    /**
     * The snare riser: twenty fresh strikes of the snare over [RISER_BUILD], speeding up from about
     * 4 to 25 a second and rising in strength. They go through one pass of the rig, so the spring
     * fills and the crush pumps. The pad's velocity scales every strike; a take re-strikes them all.
     */
    private fun riserRoll(m: Map<String, Float>, velocity: Float, take: Int, rate: Int, model: Int): Snip {
        val dry = FloatArray(((RISER_BUILD + .5f) * rate).toInt())
        for (i in 0 until 20) {
            val p = i / 19f
            val at = (RISER_BUILD * (1 - (1 - p).toDouble().pow(1.6))).toFloat()
            val hit = source(BreakerVoice.SNARE, m, velocity * (.3f + .55f * p), take * 20 + i + 1, model).samples
            val o = (at * rate).toInt(); val g = .35f + .65f * p
            for (k in hit.indices) if (o + k < dry.size) dry[o + k] += g * hit[k]
        }
        var peak = 0f
        for (s in dry) peak = maxOf(peak, kotlin.math.abs(s))
        if (peak > 0f) for (i in dry.indices) dry[i] /= peak
        return Snip(dry, 1, rate)
    }

    /**
     * Bump: the spring tank kicked. The jolt moves the transducer all at once, so the springs get a
     * broadband slap: a 2 ms click and 25 ms of noise, re-seeded per take, into tank B with no dry signal.
     * - **DRIP** is how hard the tank is kicked: the dwell drive, so a harder kick clangs and rings longer.
     * - **TUNE** is the springs' tension: tank B's dispersion corner, half an octave either way.
     * - **DAMP** is how long the tank rings.
     * - **A softer pad** kicks it softer: less drive, and a duller jolt.
     * The tank's mechanical knock is the click's own 60 Hz thud, kept under the springs. All shape.
     */
    private fun bump(m: Map<String, Float>, velocity: Float, take: Int, frames: Int, rate: Int, model: Int): Snip {
        val rng = Random(Dsp.seedFor("BREAKER", "BUMP", take))
        val jolt = DoubleArray(frames)
        val bright = Dsp.OnePole(rate)
        val corner = 2_000f + 10_000f * velocity
        for (i in 0 until (.03 * rate).toInt()) {
            val t = i.toDouble() / rate
            val click = if (t < .002) .5 - .5 * cos(2 * PI * t / .002) else 0.0
            val noise = (rng.nextDouble() * 2 - 1) * exp(-t / .006)
            jolt[i] = bright.lp((click + noise).toFloat(), corner).toDouble()
        }
        val tension = 2.0.pow((m.getValue("TUNE") - .5) * 1.0)
        val tank = BreakerChain.Tank(BreakerChain.TANK_B.fc * tension, BreakerChain.TANK_B.k, BreakerChain.TANK_B.ap, BreakerChain.TANK_B.stages)
        val kick = m.getValue("DRIP").toDouble() * (.4 + .6 * velocity)
        val springs = BreakerChain.spring(jolt, kick, frames, rate, tank, decay = 1 / dampRate(damp(m, model)))
        var peak = 0.0
        for (v in springs) peak = maxOf(peak, kotlin.math.abs(v))
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val thud = if (t < .012) .25 * sin(2 * PI * 60 * t) * sin(PI * t / .012) else 0.0
            out[i] = ((if (peak > 0) springs[i] / peak else 0.0) + thud).toFloat()
        }
        return Snip(out, 1, rate)
    }

    /** The cymbals' TUNE: three semitones either way (shape). */
    private fun cymbalPitch(tune: Float) = 2.0.pow((tune - .5) * 6 / 12)

    fun render(
        voice: BreakerVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        take: Int = 0,
        model: Int = MODEL_VERSION,
    ): Snip {
        require(model in 1..MODEL_VERSION) { "unsupported breaker model $model" }
        require(model >= 2 || voice == BreakerVoice.KICK || voice == BreakerVoice.SNARE) { "breaker model 1 has no $voice" }
        require(model >= 3 || voice !in MODEL_3) { "breaker model $model has no $voice" }
        require(model >= 5 || voice !in MODEL_5) { "breaker model $model has no $voice" }
        val m = settled(voice, macros, model)
        val v = velocity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val rate = Dsp.RATE
        val seconds = secondsFor(voice)
        val frames = (seconds * rate).toInt()
        val cymbal = modal(voice, model)
        val riser = voice == BreakerVoice.RISER
        val bumped = voice == BreakerVoice.BUMP
        val dry = when {
            cymbal -> Snip(BreakerCymbal.render(if (voice == BreakerVoice.CRASH) BreakerCymbal.Kind.CRASH else BreakerCymbal.Kind.RIDE,
                v, take, seconds, cymbalPitch(m.getValue("TUNE")), rate, damping = dampRate(damp(m, model))), 1, rate)
            riser -> riserRoll(m, v, take, rate, model)
            bumped -> bump(m, v, take, frames, rate, model)
            else -> source(voice, m, v, take, model)
        }
        val dusted = dustChain(m.getValue("DUST"), voice, model)?.process(dry) ?: dry
        val crush = m.getValue("CRUSH").toDouble()
        var x = DoubleArray(dusted.samples.size) { dusted.samples[it].toDouble() }
        // The tube: the drums' character. The cymbals and the riser skip it: on noisy metal and a dense
        // roll it was the fizz the owner heard as harsh (round 3).
        if (!cymbal && !riser && !bumped) x = BreakerChain.saturate(x, rate)
        x = BreakerChain.crush(x, minOf(1.0, crush / .5) * .7, rate)
        // Bump is the tank itself: its DRIP already kicked it, so it is not sent to the tank again.
        if (!bumped) x = BreakerChain.tankMix(x, m.getValue("DRIP").toDouble(), frames, rate, tankFor(voice, model))
        x = BreakerChain.slap(x, m.getValue("SLAP").toDouble(), rate, if (model >= 2) BreakerChain.SLAP_SECONDS else .09)
        x = BreakerChain.crush(x, maxOf(0.0, (crush - .5) / .5), rate)
        val amp = m.getValue("AMP")
        val pre = FloatArray(frames) { x.getOrElse(it) { 0.0 }.toFloat() }
        // The combo: its tube always, its speaker (CAB .25, a bright open-back cab) except on the open rig.
        val amped = Valve.process(Snip(pre, 1, rate), mapOf("DRIVE" to .3f + .45f * amp, "SAG" to .35f, "TONE" to .6f, "CAB" to if (open(voice, model)) 0f else .25f))
        // On the cymbals AMP is how much of the combo you hear, so 0 is no amp at all; elsewhere the
        // combo is always there and AMP drives it.
        val out = if (cymbal) FloatArray(frames) { (1 - amp) * pre[it] + amp * amped.samples.getOrElse(it) { 0f } }
        else amped.samples.copyOf(frames)
        val mean = out.average().toFloat()
        val dc = Dsp.FixedOnePole(20f, rate)
        for (i in out.indices) { val s = out[i] - mean; out[i] = s - dc.lp(s) }
        val fade = (FADE_SECONDS * rate).toInt()
        for (k in 0 until fade) out[frames - fade + k] *= (0.5 + 0.5 * cos(PI * k / fade)).toFloat()
        if (riser) {
            // The crush evens a crescendo out, so the rise is a fader push after the rig: -12 dB to 0 over the build.
            for (i in out.indices) {
                val p = (i.toFloat() / rate / RISER_BUILD).coerceAtMost(1f)
                out[i] *= 10f.pow((-12f + 12f * p.pow(1.5f)) / 20f)
            }
        }
        val done = if (unstruck(voice, model)) Velocity.soften(Snip(out, 1, rate), (1f - v) * UNSTRUCK_SOFTEN).samples else out
        Dsp.levelTo(done, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(done)
        return Snip(done, 1, rate)
    }
}
