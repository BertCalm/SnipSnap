package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * NACRE GROTTO (saved model 8): a bowed bass string under quiet glassy glints, in NACRE's water. The one NACRE voice so
 * far with a real held loop.
 *
 * **The gesture.** The bow settles onto the bass harp's root string with NACRE's slow swell (the stroke takes 0.35 to
 * 0.8 s, slower for a lighter TOUCH) and the string speaks in Helmholtz motion; the harp's eight sympathetic strings
 * wake; quiet harmonic glints from the upper harp answer, late; the coral and seaweed carry everything back. A finite
 * note's bow then lifts and the strings ring out; a held note (HOLD's top step) sustains as a loop.
 *
 * **The string.** A true stick-slip bowed waveguide, [Strings.Bow] (STK's reflection table on a two-segment string),
 * in ARCO CELLO's measured C2 to C4 window: its position ([Arco.BETA]), its sustain speed ([Arco.V_SUSTAIN]) and its
 * grip window (pressure and bridge corner per semitone, [Arco.gripFor]), which locks into one slip a period at every
 * one of those 25 notes. GROTTO walks that window with TOUCH and velocity together ([gripFor]): a firmer touch or a
 * harder note presses harder on a brighter string, and a softer note also reaches the harps darker ([brightShare]).
 * Once the stroke is in, nothing moves inside the bow and nothing
 * downstream feeds back into it; the string's level swells with the internal water (±[SWELL]) on its way to the harps.
 *
 * **The glints.** The owner's glassy harmonics ([NacreVoices.harmonicString], HARMONICS' touched-node pluck) on the
 * upper harp's strings at `2 f0 * {1, 2, 4, 8}`, about 15 to 20 dB under the bow. DUET sets how many there are in
 * each loop's length ([glintCount]: none at DUET 0, two at the default, five at the top) and how strong; TOUCH sets
 * how late the first one comes (a firmer bow is heard first). They sit on a circular schedule: the same glints, at
 * the same places, every loop's length, so a held loop repeats them exactly.
 *
 * **The chain.** NACRE's own, unchanged: the two harp bodies and eight sympathetic strings ([NacreVoices.Harps]), the
 * cross-harp drive scaled by the water, PEARL's water loading on the glint strings, [NacreVoices.condition], the coral
 * and seaweed returns, a fixed reference output gain ([OUTPUT_GAIN]: level follows velocity, as every NACRE voice's
 * does) and a scalar peak guard. Every water clock runs on the held clock ([NacreHeld]): whole cycles of the loop,
 * read loop-relative.
 *
 * **The loop.** Two parts, each from a proven precedent:
 *  1. The bow closes by period, ARCO's route ([LoopRetune]): whole note periods in whole frames ([NacreHeld.plan],
 *     about 3.71 s, two swells of the water), the played length measured after a warm-up of the stroke plus
 *     `max(2 s, 200 periods)` and one more lap, and the bow re-rendered at the corrected pitch up to five times.
 *  2. The linear chain closes by settling: given a bow and water that repeat every loop, it converges. The render
 *     simulates the lap before the kept loop and the lap after it straight on and measures the house seam against
 *     both ([NacreHeld.closure]); a loop over [Keys.MAX_SEAM_ERROR] is refused by name, never crossfaded.
 *
 * **One instrument.** A finite note runs the same renderer with the same clock and the same retuned pitch, and lifts
 * the bow at [bowSeconds]; it is the held note, sample for sample, until the bow begins to lift.
 */
internal object NacreGrotto {
    const val MODEL = 8
    const val MIN_MIDI = 36
    const val MAX_MIDI = 60
    const val DEFAULT_MIDI = 36

    /**
     * The fixed reference output gain: a hard held C2 at the default controls peaks about 0.64 (HARMONICS' hard C3 peaks
     * 0.81); a sustained bow is louder than a pluck at the same peak, so GROTTO sits a little under it.
     */
    const val OUTPUT_GAIN = 1.6
    const val PEAK_CEILING = NacreVoices.PEAK_CEILING

    /**
     * The sympathetic strings' extra share under a bow. A held bow drives the bodies without pause, so the strings
     * sounding in sympathy would sit 26 dB under it at NACRE's own gain; this keeps them about 16 dB under on the loop,
     * inside the design's 18 dB audibility line.
     */
    const val SYMPATHETIC_LIFT = 3.0

    /** The bowed string into the bass harp, scaled by velocity ([bowGain]). */
    const val BOW_LEVEL = .5

    /**
     * How far the bowed string's level swells with the water, either way: two swells a loop, on the string's output
     * before the harps, never inside the bow. A swell of the bow's speed was tried first and broke the loop above C2:
     * the string's pitch follows the bow's speed, so the retune could not fit whole periods (cycle error 2e-2 at F2,
     * 0.36 at C3 and 2.5 at C4, against 1e-6 to 1e-5 with the bow held steady).
     */
    const val SWELL = .14

    /** Where the bow sits in the water: the bass harp's own position in PEARL's field. */
    private const val BASS_POSITION = .2
    private const val UPPER_POSITION = 1.9

    /** The stroke: the bow reaches its speed over this long, slower at a lighter TOUCH. */
    const val STROKE_SLOW_SECONDS = .8
    const val STROKE_FAST_SECONDS = .35

    /** A finite note's bow: on for [BOW_SECONDS_LOW] at HOLD 0 up to [BOW_SECONDS_HIGH] at HOLD .98, then lifted over [RELEASE_SECONDS]. */
    const val BOW_SECONDS_LOW = 2.5f
    const val BOW_SECONDS_HIGH = 8f
    const val RELEASE_SECONDS = .12f

    /** A finite note rings this long after the bow is off: the free string, the sympathetic strings, the glints and the returns. */
    const val RING_SECONDS = 3f

    /** Frames past the lap after the kept loop, so the seam and the retune's long-lag measurement have samples to read. */
    private const val PAD_FRAMES = 2048

    /** The glints: their pluck strength at a hard note and the default DUET, and when the first one may come. */
    const val GLINT_STRENGTH = .6
    const val GLINT_FIRST_SECONDS = 1.0
    const val GLINT_TOUCH_DELAY_SECONDS = .6

    private const val CONTROL_BLOCK = 32
    private const val RATE = Dsp.RATE
    private const val OVER = Dsp.OVERSAMPLE
    private const val RAW_RATE = Dsp.RATE * Dsp.OVERSAMPLE

    fun macrosFor(): List<MacroSpec> = NacreVoices.macrosFor()
    fun defaults(): Map<String, Float> = NacreVoices.defaults()

    fun supportsMidi(midi: Int): Boolean = midi in MIN_MIDI..MAX_MIDI

    internal fun settled(macros: Map<String, Float>): Map<String, Float> {
        val defaults = defaults()
        require(macros.keys.all { it in defaults }) { "NACRE contains unknown macros: ${macros.keys - defaults.keys}" }
        return defaults.mapValues { (name, default) -> (macros[name] ?: default).also {
            require(it.isFinite() && it in 0f..1f) { "NACRE $name must be finite and in 0..1, got $it" }
        } }
    }

    fun isLoop(macros: Map<String, Float>): Boolean = NacreHeld.isLoop(settled(macros).getValue("HOLD"))

    fun drumClassFor(macros: Map<String, Float>): DrumClass = if (isLoop(macros)) DrumClass.LOOP else DrumClass.TONAL

    /** A finite note's bow-on time before the lift begins: HOLD 0 is [BOW_SECONDS_LOW], .98 and up [BOW_SECONDS_HIGH]. */
    fun bowSeconds(hold: Float): Float {
        require(!NacreHeld.isLoop(hold)) { "HOLD $hold is the loop, not a finite bow" }
        return BOW_SECONDS_LOW + (BOW_SECONDS_HIGH - BOW_SECONDS_LOW) * (hold / .98f).coerceIn(0f, 1f)
    }

    /**
     * The HOLD that bows a finite note for [seconds] before the lift ([bowSeconds]' inverse, in the same float arithmetic the
     * nacre-grotto-1 desk used for its 4 s bows).
     */
    fun holdFor(seconds: Float): Float {
        require(seconds in BOW_SECONDS_LOW..BOW_SECONDS_HIGH) { "a finite GROTTO bow is $BOW_SECONDS_LOW to $BOW_SECONDS_HIGH s, got $seconds" }
        return (seconds - BOW_SECONDS_LOW) / (BOW_SECONDS_HIGH - BOW_SECONDS_LOW) * .98f
    }

    /** Where GROTTO plays ARCO CELLO's grip window: TOUCH and velocity together, 0 the lightest, 1 the firmest. */
    fun gripFor(touch: Double, velocity: Float): Float = ((.15 + .85 * touch) * (.6 + .4 * velocity)).toFloat().coerceIn(0f, 1f)

    /** The bowed string's share of the bass harp: level follows velocity. */
    fun bowGain(velocity: Float): Double = BOW_LEVEL * (.35 + .65 * velocity)

    /**
     * How much of the string reaches the harps undarkened: the rest passes a one-pole low-pass at [SOFT_CORNER_RATIO]
     * times the note. Down at C2 ARCO's grip window is narrow (pressure 0.97 to 1, corner 1000 to 1500 Hz), so the grip
     * alone barely changes a soft note's colour; this does, linearly and after the bow, so the loop is not touched.
     */
    fun brightShare(velocity: Float): Double = .25 + .75 * velocity
    const val SOFT_CORNER_RATIO = 4.0

    /** Glints per loop's length: none at DUET 0, two at the default .15, five at 1. */
    fun glintCount(duet: Double): Int = if (duet <= 0.0) 0 else 1 + (4.0 * duet.pow(.7)).roundToInt()

    fun strokeSeconds(touch: Double): Double = STROKE_SLOW_SECONDS + (STROKE_FAST_SECONDS - STROKE_SLOW_SECONDS) * touch

    // ---- the plan ---------------------------------------------------------------

    /**
     * A note's frames at 44.1 kHz: the loop ([loop], whole periods in whole frames), the warm-up before the first lap
     * ([warmFrames]: the stroke plus ARCO's `max(2 s, 200 periods)`), the kept loop's start one lap later ([loopStart]),
     * and the held render's length ([heldFrames]: a lap after the kept one, and a pad for the measurements).
     */
    internal class Plan(val midi: Int, val hz: Float, val loop: Bore.LoopPlan, val warmFrames: Int) {
        val loopFrames get() = loop.frames
        val loopStart get() = warmFrames + loop.frames
        val heldFrames get() = loopStart + 2 * loop.frames + PAD_FRAMES
        val period get() = loop.frames * OVER
    }

    internal fun planFor(midi: Int, touch: Double): Plan {
        val hz = Keys.midiHz(midi)
        val stroke = (strokeSeconds(touch) * RATE).roundToInt()
        return Plan(midi, hz, NacreHeld.plan(hz), stroke + Arco.warmFramesFor(hz))
    }

    // ---- the bow ----------------------------------------------------------------

    private class BowSettings(val pressure: Float, val corner: Float, val strokeRaw: Int) {
        val slope get() = 5f - 4f * pressure
    }

    private fun bowSettings(midi: Int, touch: Double, velocity: Float): BowSettings {
        val semitone = midi - MIN_MIDI
        val grip = gripFor(touch, velocity)
        return BowSettings(Arco.pressureFor(ArcoVoice.CELLO, semitone, grip), Arco.cornerFor(ArcoVoice.CELLO, semitone, grip),
            (strokeSeconds(touch) * RAW_RATE).roundToInt())
    }

    /**
     * The bowed string at [tunedHz], [frames] raw samples: the stroke, then ARCO's sustain speed held steady, and at raw
     * frame [lift] (if any) the bow's contact eased off over [RELEASE_SECONDS] and the bow lifted. [bowPoint] receives
     * the string's velocity under the bow, for the slip counter.
     */
    private fun bowStream(s: BowSettings, tunedHz: Float, frames: Int, lift: Int, bowPoint: FloatArray? = null): FloatArray {
        val bow = Strings.Bow(f = tunedHz, beta = Arco.BETA, bridgeHz = s.corner, share = Strings.Bow.SHARE, rate = RAW_RATE)
        val out = FloatArray(frames)
        val slope = s.slope
        val release = (RELEASE_SECONDS * RAW_RATE).roundToInt()
        for (i in 0 until frames) {
            if (i % 32768 == 0) NacreVoices.stopIfInterrupted()
            val stroke = if (i < s.strokeRaw) NacreVoices.smooth(i.toDouble() / s.strokeRaw) else 1.0
            val v = (Arco.V_SUSTAIN * stroke).toFloat()
            out[i] = when {
                lift < 0 || i < lift -> bow.next(v, slope)
                i < lift + release -> bow.next(v, slope, 0f, (1.0 - NacreVoices.smooth((i - lift).toDouble() / release)).toFloat())
                else -> {
                    if (i == lift + release) bow.lift()
                    bow.next(0f, slope)
                }
            }
            if (bowPoint != null) bowPoint[i] = bow.bowPoint
        }
        return out
    }

    private data class BowKey(val touch: Float, val velocity: Float, val midi: Int)
    private val tunings = object : LinkedHashMap<BowKey, LoopRetune.Retuned>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BowKey, LoopRetune.Retuned>?) = size > 2
    }

    /** The held bow, retuned so the loop's whole periods fill its whole frames (ARCO's route, [LoopRetune]). */
    private fun retuned(s: BowSettings, plan: Plan, key: BowKey): LoopRetune.Retuned {
        synchronized(tunings) { tunings[key]?.let { return it } }
        val r = LoopRetune.retuned(plan.hz, plan.loop, plan.loopStart) { hz ->
            bowStream(s, hz, plan.heldFrames * OVER, -1)
        }
        synchronized(tunings) { tunings[key] = r }
        return r
    }

    /**
     * The held bow alone at its retuned pitch, with the string's velocity under the bow beside it (for the slip counter):
     * what the loop's source is, before the harps. Tests read it; the render never calls it.
     */
    internal fun heldBow(midi: Int, touch: Float, velocity: Float): Triple<FloatArray, FloatArray, Plan> {
        val plan = planFor(midi, touch.toDouble())
        val settings = bowSettings(midi, touch.toDouble(), velocity)
        val tuning = retuned(settings, plan, BowKey(touch, velocity, midi))
        val point = FloatArray(plan.heldFrames * OVER)
        val bow = bowStream(settings, tuning.tunedHz.toFloat(), point.size, -1, point)
        return Triple(bow, point, plan)
    }

    // ---- the glints -------------------------------------------------------------

    /** One glint of the circular schedule: the [k]th, on upper string [stringId], [offsetRaw] raw frames in, every loop's length after. */
    internal class Glint(val k: Int, val stringId: Int, val offsetRaw: Int, val strength: Double)

    internal fun glints(touch: Double, duet: Double, velocity: Float, seed: Long, plan: Plan): List<Glint> {
        val count = glintCount(duet)
        val first = (GLINT_FIRST_SECONDS + GLINT_TOUCH_DELAY_SECONDS * touch) * RAW_RATE
        val period = plan.period
        return List(count) { k ->
            val jitter = NacreVoices.variation(MODEL, seed, "glint", k, "time")
            val offset = (first + period * (k + .5 + .35 * jitter) / count).roundToInt()
            val id = ((NacreVoices.variation(MODEL, seed, "glint", k, "string") + 1.0) * 2.0).toInt().coerceIn(0, 3)
            val strength = GLINT_STRENGTH * (.55 + .45 * velocity) * (.75 + .25 * duet) *
                (.9 + .1 * NacreVoices.variation(MODEL, seed, "glint", k, "strength")) * .82.pow(id)
            Glint(k, id, offset, strength)
        }
    }

    // ---- the core ---------------------------------------------------------------

    /** The conditioned 44.1 kHz streams of one note, before the returns: what CURRENT, VENUE and BLOOM play over. */
    private class Core(val plan: Plan, val bass: FloatArray, val upper: FloatArray, val sympathetic: FloatArray,
                       val events: List<Nacre.Event>, val diagnostics: Map<String, Double>) {
        val bytes: Long get() = 4L * (bass.size + upper.size + sympathetic.size)
    }

    /** [liftFrames] is the frame the finite bow begins to lift, or -1 for a held note. */
    private data class CoreKey(val touch: Float, val duet: Float, val midi: Int, val velocity: Float, val seed: Long,
                               val liftFrames: Int)

    private val cores = NacreCoreCache<CoreKey, Core>(4) { it.bytes }

    internal fun clearCaches() {
        cores.clear()
        synchronized(tunings) { tunings.clear() }
    }

    private fun core(key: CoreKey): Pair<Core, Boolean> = cores.getOrBuild(key) { build(key) }

    private fun build(key: CoreKey): Core {
        val touch = key.touch.toDouble()
        val duet = key.duet.toDouble()
        val velocity = key.velocity
        val plan = planFor(key.midi, touch)
        val f0 = plan.hz.toDouble()
        val water = NacreHeld.field(MODEL, key.seed, plan.loopFrames)
        val settings = bowSettings(key.midi, touch, velocity)
        val tuning = retuned(settings, plan, BowKey(key.touch, velocity, key.midi))
        val held = key.liftFrames < 0
        val frames = if (held) plan.heldFrames * OVER else
            (key.liftFrames + ((RELEASE_SECONDS + RING_SECONDS) * RATE).roundToInt()) * OVER
        val lift = if (held) -1 else key.liftFrames * OVER
        val bow = if (held) tuning.raw else bowStream(settings, tuning.tunedHz.toFloat(), frames, lift)
        val period = plan.period
        // The glints of every lap that start while the bow is on: the circular schedule, cut at the lift.
        val until = if (held) frames else lift
        val schedule = glints(touch, duet, velocity, key.seed, plan)
        val strings = ArrayList<NacreVoices.HarmonicString>()
        val events = ArrayList<Nacre.Event>()
        events += Nacre.Event("bass", 0, 0.0, f0, bowGain(velocity), "bowed")
        for (lap in 0..frames / period) for (g in schedule) {
            val start = g.offsetRaw + lap * period
            if (start >= until) continue
            val event = Nacre.Event("upper", g.stringId, start.toDouble() / RAW_RATE, 2.0 * f0 * NacreVoices.RATIOS[g.stringId],
                g.strength, "glint")
            events += event
            strings += NacreVoices.harmonicString(event, start, touch, velocity,
                NacreVoices.variation(MODEL, key.seed, "glint", g.k, "pick"),
                Dsp.seedFor("NACRE", MODEL, key.seed, "glint", g.k, "pluck"), RAW_RATE, RAW_RATE)
        }
        strings.sortBy { it.start }
        events.sortWith(compareBy<Nacre.Event> { it.timeSeconds }.thenBy { it.performer }.thenBy { it.stringId })

        val harps = NacreVoices.Harps(f0, RAW_RATE)
        val bassOut = FloatArray(frames)
        val upperOut = FloatArray(frames)
        val symOut = FloatArray(frames)
        val gain = bowGain(velocity)
        val active = ArrayList<NacreVoices.HarmonicString>()
        var next = 0
        var upperFlow = 0.0
        var crossBass = 1.0
        var crossUpper = 1.0
        var internalPeak = 0.0
        var bowPeak = 0.0
        val soft = NacreVoices.LowPass(SOFT_CORNER_RATIO * f0, RAW_RATE)
        val bright = brightShare(velocity)
        for (i in 0 until frames) {
            if (i % 32768 == 0) NacreVoices.stopIfInterrupted()
            val relative = i % period
            val block = relative % CONTROL_BLOCK == 0
            if (block) {
                // The water on the held clock, in loop-relative control blocks: the same values every lap.
                val time = NacreHeld.fieldSeconds(i, period, RAW_RATE)
                val bassFlow = water.at(time, BASS_POSITION)
                upperFlow = water.at(time, UPPER_POSITION)
                crossBass = NacreVoices.crossFor(water.depth, bassFlow)
                crossUpper = NacreVoices.crossFor(water.depth, upperFlow)
                for (s in active) s.loadWater(upperFlow, water.depth)
            }
            while (next < strings.size && strings[next].start == i) {
                strings[next].loadWater(upperFlow, water.depth)
                active += strings[next++]
            }
            var upper = 0.0
            var expired = false
            for (s in active) {
                val age = i - s.start
                if (age > 4.0 * s.t60 * RAW_RATE) { expired = true; continue }
                val excitation = if (age in s.burst.indices) s.burst[age] else 0f
                val y = s.next(age, excitation)
                internalPeak = maxOf(internalPeak, abs(y))
                upper += y
            }
            // A glint string four of its T60s old is 240 dB down: it stops being simulated, at the same age every lap.
            if (expired) active.removeAll { i - it.start > 4.0 * it.t60 * RAW_RATE }
            bowPeak = maxOf(bowPeak, abs(bow[i].toDouble()))
            // The string's level swells with the water at the bass harp, on the held clock.
            val string = bright * bow[i] + (1.0 - bright) * soft.next(bow[i].toDouble())
            val bass = gain * string * (1.0 + SWELL * water.at(NacreHeld.fieldSeconds(i, period, RAW_RATE), BASS_POSITION))
            harps.next(bass, upper, crossBass, crossUpper)
            val b = harps.bass
            val u = harps.upper
            val sym = harps.sympathetic
            check(b.isFinite() && u.isFinite() && sym.isFinite()) { "NACRE GROTTO nonfinite state at frame $i" }
            bassOut[i] = b.toFloat()
            upperOut[i] = u.toFloat()
            symOut[i] = (sym * harps.sympatheticGain * SYMPATHETIC_LIFT).toFloat()
        }
        val diagnostics = linkedMapOf(
            "tunedHz" to tuning.tunedHz,
            "retunePasses" to tuning.passes.toDouble(),
            "loopFrames" to plan.loopFrames.toDouble(),
            "loopPeriods" to plan.loop.periods.toDouble(),
            "loopSeconds" to plan.loopFrames.toDouble() / RATE,
            "loopHz" to plan.loop.periods * RATE.toDouble() / plan.loopFrames,
            "warmFrames" to plan.warmFrames.toDouble(),
            "heldFieldHz" to water.hz,
            "bowPressure" to settings.pressure.toDouble(),
            "bowCornerHz" to settings.corner.toDouble(),
            "bowRawPeak" to bowPeak,
            "internalSourcePeak" to internalPeak,
            "glintsPerLoop" to schedule.size.toDouble(),
        )
        return Core(plan, NacreVoices.condition(bassOut), NacreVoices.condition(upperOut), NacreVoices.condition(symOut),
            events, diagnostics)
    }

    // ---- the render -------------------------------------------------------------

    /**
     * One rendered note: [probe] as every NACRE voice reports it (its [Nacre.Probe.snip] is what [render] returns: the
     * loop alone for a held note), [native] the whole output after the gain and guard, and for a held note where its
     * loop starts in [native] ([loopStart], [loopFrames]) and how well it closes ([closure]).
     */
    internal class Render(val probe: Nacre.Probe, val native: FloatArray, val loopStart: Int, val loopFrames: Int,
                          val closure: NacreHeld.Closure?)

    fun render(macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI, velocity: Float = 1f,
               seed: Long = Nacre.DEFAULT_SEED): Snip = play(macros, midi, velocity, seed, record = false).probe.snip

    fun inspect(macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI, velocity: Float = 1f,
                seed: Long = Nacre.DEFAULT_SEED): Nacre.Probe = play(macros, midi, velocity, seed, record = true).probe

    /**
     * A held key as one file: the note's own stroke and warm-up, the lap before the loop, then exactly one loop; the
     * loop starts at [Render.loopStart]. Its attack is the render's real history, never a crossfade.
     */
    fun renderKey(macros: Map<String, Float>, midi: Int = DEFAULT_MIDI, velocity: Float = 1f,
                  seed: Long = Nacre.DEFAULT_SEED): Pair<FloatArray, Int> {
        val r = played(macros + ("HOLD" to 1f), midi, velocity, seed)
        return r.native.copyOf(r.loopStart + r.loopFrames) to r.loopStart
    }

    /** The whole render with its measurements (tests and the audition read it). */
    internal fun played(macros: Map<String, Float>, midi: Int = DEFAULT_MIDI, velocity: Float = 1f,
                        seed: Long = Nacre.DEFAULT_SEED, record: Boolean = true, refuse: Boolean = true): Render =
        play(macros, midi, velocity, seed, record, refuse)

    private fun play(macros: Map<String, Float>, midi: Int, velocity: Float, seed: Long, record: Boolean,
                     refuse: Boolean = true): Render {
        require(supportsMidi(midi)) { "NACRE GROTTO plays MIDI $MIN_MIDI..$MAX_MIDI (C2 to C4, the bow's measured range), got $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "NACRE velocity must be finite and in 0..1, got $velocity" }
        val m = settled(macros)
        val hold = m.getValue("HOLD")
        val held = NacreHeld.isLoop(hold)
        val touch = m.getValue("TOUCH")
        val liftFrames = if (held) -1 else (bowSeconds(hold) * RATE).roundToInt()
        NacreVoices.stopIfInterrupted()
        val (c, hit) = core(CoreKey(touch, m.getValue("DUET"), midi, velocity, seed, liftFrames))
        val plan = c.plan
        val sourceMix = FloatArray(c.bass.size) { c.bass[it] + c.upper[it] }
        val venue = m.getValue("VENUE").toDouble()
        val current = m.getValue("CURRENT")
        val bloom = m.getValue("BLOOM")
        val returnGain = Nacre.REFERENCE_RETURN_GAIN * Nacre.returnAmount(bloom)
        val symGain = Nacre.sympatheticAmount(bloom)
        val coral = if (venue < 1.0) NacreHeld.heldReturns(sourceMix, current, false, plan.loopFrames) else null
        val weed = if (venue > 0.0) NacreHeld.heldReturns(sourceMix, current, true, plan.loopFrames) else null
        val wet = FloatArray(sourceMix.size) { i ->
            val blend = when (venue) {
                0.0 -> coral!![i].toDouble()
                1.0 -> weed!![i].toDouble()
                else -> (1.0 - venue) * coral!![i] + venue * weed!![i]
            }
            (returnGain * blend).toFloat()
        }
        val sympathetic = FloatArray(c.sympathetic.size) { (c.sympathetic[it] * symGain).toFloat() }
        val raw = FloatArray(sourceMix.size) { sourceMix[it] + sympathetic[it] + wet[it] }
        val native = FloatArray(raw.size) { (raw[it] * OUTPUT_GAIN).toFloat() }
        val preGuardPeak = native.maxOf { abs(it) }
        val peakSafetyGain = if (preGuardPeak > PEAK_CEILING) (PEAK_CEILING / preGuardPeak).toDouble() else 1.0
        // A scalar guard over the whole render (a held note's loop and its key are one buffer), a hair under the ceiling.
        Dsp.limitPeak(native, PEAK_CEILING - 1e-5f)
        NacreVoices.stopIfInterrupted()
        val closure = if (held) NacreHeld.closure(native, plan.loopStart, plan.loopFrames) else null
        if (closure != null && refuse) {
            require(closure.worstSeam < Keys.MAX_SEAM_ERROR && closure.cycleError < Keys.MAX_SEAM_ERROR) {
                ("NACRE GROTTO MIDI $midi velocity $velocity TOUCH $touch: the held loop does not close (seam before %.2e, after %.2e, " +
                    "cycle %.2e, bar %.0e)").format(java.util.Locale.ROOT, closure.before, closure.after, closure.cycleError, Keys.MAX_SEAM_ERROR)
            }
        }
        val out = if (held) native.copyOfRange(plan.loopStart, plan.loopStart + plan.loopFrames) else native
        var energy = 0.0
        for (v in raw) energy += v.toDouble() * v
        val diagnostics = LinkedHashMap(c.diagnostics).also {
            it["modelVersion"] = MODEL.toDouble()
            it["rootHz"] = plan.hz.toDouble()
            it["held"] = if (held) 1.0 else 0.0
            it["loopStart"] = if (held) plan.loopStart.toDouble() else -1.0
            it["bowSeconds"] = if (held) -1.0 else liftFrames.toDouble() / RATE
            it["heldApertureHz"] = NacreHeld.fittedHz(NacreHeld.apertureCycles(current, plan.loopFrames), plan.loopFrames)
            it["returnGain"] = returnGain
            it["sympatheticGain"] = symGain
            it["referenceOutputGain"] = OUTPUT_GAIN
            it["peakSafetyGain"] = peakSafetyGain
            it["venueFeedbackGain"] = 0.0
            it["rawRms"] = sqrt(energy / raw.size)
            it["rawPeak"] = raw.maxOf { v -> abs(v).toDouble() }
            it["coreCacheHit"] = if (hit) 1.0 else 0.0
            if (closure != null) {
                it["seamBefore"] = closure.before
                it["seamAfter"] = closure.after
                it["cycleError"] = closure.cycleError
            }
        }
        val stems = if (!record) emptyMap() else linkedMapOf("bass" to c.bass.copyOf(), "upper" to c.upper.copyOf(),
            "sympathetic" to sympathetic, "venue" to wet)
        val probe = Nacre.Probe(Snip(out, channels = 1, sampleRate = RATE), raw, stems, c.events.toList(), diagnostics)
        return Render(probe, native, if (held) plan.loopStart else -1, if (held) plan.loopFrames else 0, closure)
    }

    /** The length of a render in frames, in closed form: one loop at HOLD's top step, else the bow, its lift and the ring. */
    fun renderFrames(macros: Map<String, Float>, midi: Int): Int {
        val m = settled(macros)
        val hold = m.getValue("HOLD")
        return if (NacreHeld.isLoop(hold)) NacreHeld.plan(Keys.midiHz(midi)).frames
        else (bowSeconds(hold) * RATE).roundToInt() + ((RELEASE_SECONDS + RING_SECONDS) * RATE).roundToInt()
    }
}
