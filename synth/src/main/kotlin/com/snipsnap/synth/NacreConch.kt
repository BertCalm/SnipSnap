package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * NACRE CONCH (saved model 9): a pair of blown shells in NACRE's water, the soft hollow breath across a shell's mouth
 * (the owner's choice, 15 Nov 2026), not a lip-buzzed blast. The second NACRE voice with a real held loop.
 *
 * **The gesture.** Breath enters the bass shell with NACRE's slow swell (0.55 to 1.4 s, slower for a lighter TOUCH);
 * the shell catches and sounds its note; the upper shell answers an octave up, later by DUET; the harp's eight
 * sympathetic strings ring with them and the coral and seaweed carry both back. A finite note's breath then fades
 * and the shells and strings ring out; a held note (HOLD's top step) keeps breathing as a loop.
 *
 * **The shell.** A jet across the mouth of a vessel ([Shell]): a [Modes.Bank] holding the cavity's own mode at the
 * note and three weak, inharmonic spiral-chamber modes ([CHAMBER_RATIOS]), driven by a delayed, soft-clipped jet. The
 * jet reads the cavity's pressure, crosses the mouth in about half a period ([JET_RATIO_SOFT]..[JET_RATIO_HARD], the
 * flute's register condition, as BORE FLUTE's jet uses on its pipe), and its flow drives the bank through `tanh`, so
 * the drive is bounded and the bank passive: the state cannot grow without bound. Only the cavity mode is in the
 * jet's loop; the chamber modes are driven by the same flow but never read back, so they colour the tone (and the
 * onset, where they ring at their own pitches) without ever sounding a second pitch of their own. That is the
 * hollow, few-overtone voice of a vessel flute, unlike BORE's and OBBLIGATO's harmonic pipes.
 *
 * **The breath.** TOUCH and velocity set one breath pressure ([pressureFor]): the jet's speed (level), how hard it
 * saturates and how far its flow reaches up the spectrum (colour), how much hiss rides on it, and a slightly
 * shorter jet transit, so a harder breath is brighter as well as louder. The breath's hiss ([BreathHiss]) is heard
 * breath only: a seeded noise of exactly one loop's length, coloured by the shell's own modes and laid beside the
 * tone, never inside the jet, so it cannot unlock the loop (the design's named fallback, §5.5). Turbulence reaches the
 * cavity only while the breath swells in ([CHIFF_LEVEL]): that is what starts the jet, and it is gone before the loop.
 * A firmer breath also sounds more of the jet's own edge tone ([EDGE_SHARE]) and of the spiral chamber: the colour.
 *
 * **The pair.** The bass shell sounds the note into the bass harp and the upper shell an octave above into the upper
 * harp; they never couple except downstream, through NACRE's linear chain. DUET sets how much later the upper breath
 * enters (PEARL's answering law, [NacreVoices.answerOffset], scaled to a breath) and, from [LEAN_DUET], leans the
 * shells against each other: the upper shell is given one ([beatCycles]) or two whole periods more than the octave
 * in the loop, a slow beat of exactly that many cycles per loop.
 *
 * **The chain and the loop** are GROTTO's ([NacreGrotto]): NACRE's bodies, sympathetic strings and cross-harp drive
 * ([NacreVoices.Harps]), the water on the held clock ([NacreHeld]), the returns, a fixed reference gain and a scalar
 * guard. Each shell closes by period through ARCO's retune ([LoopRetune]), independently; the linear chain closes by
 * settling; the render measures the house seam against the real lap before and after, and refuses a loop over
 * [Keys.MAX_SEAM_ERROR] by name, never crossfading. The level's slow tide ([SWELL]) moves after the shells, never
 * inside a jet: anything that bends pitch over time breaks the retune (GROTTO's lesson).
 *
 * **One instrument.** A finite note runs the same renderer with the same retuned pitches and fades the breath at
 * [breathSeconds]; it is the held note, sample for sample, until the breath begins to fade.
 */
internal object NacreConch {
    const val MODEL = 9
    const val MIN_MIDI = 36
    const val MAX_MIDI = 72
    const val DEFAULT_MIDI = 48

    /** The fixed reference output gain: level follows TOUCH and velocity, as every NACRE voice's does. */
    const val OUTPUT_GAIN = .55
    const val PEAK_CEILING = NacreVoices.PEAK_CEILING

    /**
     * The sympathetic strings' extra share under a steady breath (GROTTO needed the same lift under its bow), tilted up
     * below C3 ([sympatheticLift]): a shell's near-sinusoid gives the bass harp's body (138 Hz and up) little to answer
     * at C2, where the strings measured 25 dB under the shells against 15 dB from C3 up.
     */
    const val SYMPATHETIC_LIFT = 2.0
    private const val LIFT_REFERENCE_HZ = 130.81
    fun sympatheticLift(f0: Double): Double = SYMPATHETIC_LIFT * maxOf(1.0, LIFT_REFERENCE_HZ / f0).pow(1.2)

    /** Each shell's level into its harp. */
    const val SHELL_LEVEL = .5

    /** The upper shell's share against the bass shell: an octave answer, never louder than the note. */
    fun upperShare(duet: Double): Double = .48 + .22 * duet

    /** How far the shells' level swells with the water, either way: two swells a loop, after the shells, never in a jet. */
    const val SWELL = .12

    private const val BASS_POSITION = .2
    private const val UPPER_POSITION = 1.9

    // ---- the shell ----------------------------------------------------------------

    /** The cavity mode's Q: a vessel's Helmholtz resonance, hollow and a little breathy. */
    const val CAVITY_Q = 26.0

    /** The spiral chamber's three weak inharmonic modes, as ratios to the cavity, with their Qs, drive shares and heard shares. */
    internal val CHAMBER_RATIOS = doubleArrayOf(2.3, 3.8, 5.4)
    private val CHAMBER_Q = doubleArrayOf(16.0, 12.0, 9.0)
    private val CHAMBER_DRIVE = doubleArrayOf(.45, .32, .22)
    private val CHAMBER_HEARD = doubleArrayOf(.55, .40, .28)

    /** The jet crosses the mouth in this share of a period: half at the softest breath, a little less at the hardest. */
    const val JET_RATIO_SOFT = .50
    const val JET_RATIO_HARD = .43

    /** While the breath is still coming in (or fading) the slower jet takes up to this much longer: a small scoop into the note. */
    const val SCOOP = .10

    /** The jet's rest offset from the edge (an asymmetric jet makes the even partials). */
    const val JET_BIAS = .30

    /**
     * The jet's small-signal loop gain at the cavity, at the softest and hardest breath: over 1 speaks, higher saturates
     * (brighter). A real drive into one complex mode resonates only its positive-frequency half, so the jet's slope is
     * twice this over the speed ([Shell]); the first build left that half out and the softest notes never spoke.
     */
    const val LOOP_GAIN_SOFT = 1.6
    const val LOOP_GAIN_HARD = 4.0

    /** The jet's flow reaches up to this many times the note, from the softest to the hardest breath. */
    const val CORNER_SOFT = 2.2
    const val CORNER_HARD = 9.0

    /** How much of the jet's own edge tone is heard beside the shell, at the hardest breath. */
    const val EDGE_SHARE = 3.0

    /**
     * The edge tone adds level as well as colour (at TOUCH 1 it tripled the raw peak and the guard cut 5.7 dB): the
     * shell's heard sum is divided by `1 + EDGE_LEVEL * edge`, a constant per breath, so a harder breath stays brighter and
     * still louder, but by less. Linear and after the jet, so it cannot touch the loop.
     */
    const val EDGE_LEVEL = .5

    /**
     * The breath's turbulence while it comes in: noise into the cavity under a bump over the swell (peaking two thirds of
     * the way in, where the jet's gain first passes 1), the only thing that starts the jet. It ends with the swell, so a
     * held note's jet runs free, and periodic, from then on. A first build's 120 ms chiff died away before a slow swell
     * gave the jet its gain, and C6 and the softest TOUCH never spoke.
     */
    const val CHIFF_LEVEL = .40

    /** The heard breath's level against the tone, from the softest TOUCH to the firmest. */
    const val HISS_SOFT = .045
    const val HISS_HARD = .12

    /** The breath comes in over this long, slower at a lighter TOUCH: NACRE's own slow swell. */
    const val SWELL_SLOW_SECONDS = 1.4
    const val SWELL_FAST_SECONDS = .55

    /** How much later the upper shell's breath enters than PEARL's answering law says: a breath, not a pluck. */
    const val ANSWER_SCALE = 4.0

    /** From this DUET the shells lean against each other: a slow beat of whole cycles per loop. */
    const val LEAN_DUET = .65
    const val LEAN_TWO_DUET = .85

    /** A finite note's breath: on for [BREATH_SECONDS_LOW] at HOLD 0 up to [BREATH_SECONDS_HIGH] at HOLD .98, then fading over [RELEASE_SECONDS]. */
    const val BREATH_SECONDS_LOW = 2.5f
    const val BREATH_SECONDS_HIGH = 8f
    const val RELEASE_SECONDS = .30f

    /** A finite note rings this long after the breath is gone: the shells' modes, the sympathetic strings and the returns. */
    const val RING_SECONDS = 3f

    /** Frames past the lap after the kept loop, longer than the conditioning's 50 ms tail fade so no measured lap is faded. */
    private const val PAD_FRAMES = 4096

    private const val CONTROL_BLOCK = 32
    private const val RATE = Dsp.RATE
    private const val OVER = Dsp.OVERSAMPLE
    private const val RAW_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private val T60_LN = ln(1000.0)

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

    /** A finite note's breath before the fade begins: HOLD 0 is [BREATH_SECONDS_LOW], .98 and up [BREATH_SECONDS_HIGH]. */
    fun breathSeconds(hold: Float): Float {
        require(!NacreHeld.isLoop(hold)) { "HOLD $hold is the loop, not a finite breath" }
        return BREATH_SECONDS_LOW + (BREATH_SECONDS_HIGH - BREATH_SECONDS_LOW) * (hold / .98f).coerceIn(0f, 1f)
    }

    /**
     * The HOLD that breathes a finite note for [seconds] before the fade ([breathSeconds]' inverse, in the same float
     * arithmetic the nacre-conch-1 desk used for its 4 s breaths).
     */
    fun holdFor(seconds: Float): Float {
        require(seconds in BREATH_SECONDS_LOW..BREATH_SECONDS_HIGH) {
            "a finite CONCH breath is $BREATH_SECONDS_LOW to $BREATH_SECONDS_HIGH s, got $seconds"
        }
        return (seconds - BREATH_SECONDS_LOW) / (BREATH_SECONDS_HIGH - BREATH_SECONDS_LOW) * .98f
    }

    /** The breath pressure, 0 the lightest and 1 the firmest: TOUCH and velocity together. */
    fun pressureFor(touch: Double, velocity: Float): Double = ((.30 + .70 * touch.pow(.8)) * (.40 + .60 * velocity)).coerceIn(0.0, 1.0)

    /** The jet's steady speed at [pressure] (`U = sqrt(2P)`, normalised): the tone's level. */
    fun jetSpeed(pressure: Double): Double = sqrt(pressure)

    fun swellSeconds(touch: Double): Double = SWELL_SLOW_SECONDS + (SWELL_FAST_SECONDS - SWELL_SLOW_SECONDS) * touch

    /** How much later than the bass the upper breath starts (negative: the bass answers the upper), in seconds. */
    fun answerSeconds(duet: Double): Double = ANSWER_SCALE * NacreVoices.answerOffset(duet)

    /** Whole beat cycles per loop between the shells: none below [LEAN_DUET], one up to [LEAN_TWO_DUET], two above. */
    fun beatCycles(duet: Double): Int = when {
        duet < LEAN_DUET -> 0
        duet < LEAN_TWO_DUET -> 1
        else -> 2
    }

    // ---- the plan ---------------------------------------------------------------

    /**
     * A note's frames at 44.1 kHz: the bass shell's loop ([bass], whole periods in whole frames, [NacreHeld.plan]) and
     * the upper shell's ([upper]: the same frames, twice the periods plus [beatCycles]), when each breath starts
     * ([bassStart], [upperStart]), the warm-up before the first lap ([warmFrames]: the later breath's start and swell,
     * plus ARCO's `max(2 s, 200 periods)`), the kept loop's start one lap later ([loopStart]) and the held length.
     */
    internal class Plan(val midi: Int, val hz: Float, val bass: Bore.LoopPlan, val upper: Bore.LoopPlan,
                        val bassStart: Int, val upperStart: Int, val warmFrames: Int) {
        val loopFrames get() = bass.frames
        val loopStart get() = warmFrames + bass.frames
        val heldFrames get() = loopStart + 2 * bass.frames + PAD_FRAMES
        val period get() = bass.frames * OVER
        val upperHz get() = upper.periods * RATE.toDouble() / upper.frames
    }

    internal fun planFor(midi: Int, touch: Double, duet: Double): Plan {
        val hz = Keys.midiHz(midi)
        val bass = NacreHeld.plan(hz)
        val upper = Bore.LoopPlan(2 * bass.periods + beatCycles(duet), bass.frames)
        val answer = answerSeconds(duet)
        val bassStart = ((if (answer < 0.0) -answer else 0.0) * RATE).roundToInt()
        val upperStart = ((if (answer > 0.0) answer else 0.0) * RATE).roundToInt()
        val swell = (swellSeconds(touch) * RATE).roundToInt()
        return Plan(midi, hz, bass, upper, bassStart, upperStart, maxOf(bassStart, upperStart) + swell + Arco.warmFramesFor(hz))
    }

    // ---- the shell ----------------------------------------------------------------

    /** One shell's breath: its pressure, the jet's speed, gain, transit, reach and hiss, and when it starts and swells. */
    internal class Breath(val pressure: Double, val startRaw: Int, val swellRaw: Int, val touch: Double) {
        val speed = jetSpeed(pressure)
        val loopGain = LOOP_GAIN_SOFT + (LOOP_GAIN_HARD - LOOP_GAIN_SOFT) * pressure
        val jetRatio = JET_RATIO_SOFT + (JET_RATIO_HARD - JET_RATIO_SOFT) * pressure
        // The colour laws go with the pressure's square, so a soft breath stays pure and hollow and a hard one opens up.
        val corner = CORNER_SOFT + (CORNER_HARD - CORNER_SOFT) * pressure * pressure
        val edge = EDGE_SHARE * pressure * pressure
        /** A harder breath sounds more of the spiral chamber. */
        val chamber = .25 + 3.0 * pressure * pressure
        val hiss = (HISS_SOFT + (HISS_HARD - HISS_SOFT) * touch) * (.6 + .4 * pressure)

        /** The breath at raw frame [i] (0 before it starts, swelling to 1), faded from raw frame [fade] if that is not -1. */
        fun at(i: Int, fade: Int, release: Int): Double {
            if (i < startRaw) return 0.0
            val rise = if (i - startRaw < swellRaw) NacreVoices.smooth((i - startRaw).toDouble() / swellRaw) else 1.0
            return if (fade < 0 || i < fade) rise else rise * (1.0 - NacreVoices.smooth((i - fade).toDouble() / release))
        }
    }

    private fun breathFor(plan: Plan, upper: Boolean, touch: Double, velocity: Float): Breath =
        Breath(pressureFor(touch, velocity), (if (upper) plan.upperStart else plan.bassStart) * OVER,
            (swellSeconds(touch) * RAW_RATE).roundToInt(), touch)

    /**
     * One blown shell at [hz]: the cavity and chamber modes in a [Modes.Bank], the jet's delay line, and the drive's
     * low-pass. [next] takes the breath (0 to 1) and the chiff's force for one raw sample and returns what is heard.
     */
    internal class Shell(private val hz: Double, private val b: Breath, chiffSeed: Int) {
        private val bank = Modes.Bank(1 + CHAMBER_RATIOS.size, RAW_RATE)
        private val sigma: Double
        private val sense: Modes.Bank.Participation
        private val drive: Modes.Bank.Participation
        private val heard: Modes.Bank.Participation
        private val gain: Double
        private val period = RAW_RATE / hz
        private val ring = DoubleArray(ceil(period * JET_RATIO_SOFT * (1.0 + SCOOP)).toInt() + 4)
        private var head = 0
        private val lowA = 1.0 - kotlin.math.exp(-2.0 * PI * b.corner * hz / RAW_RATE)
        private var low = 0.0
        private val rest = tanh(JET_BIAS)
        private val chiff = Dsp.Noise(chiffSeed)
        var flowPeak = 0.0; private set

        init {
            val cavityT60 = CAVITY_Q * T60_LN / (PI * hz)
            bank.tune(0, hz, cavityT60)
            for (k in CHAMBER_RATIOS.indices) {
                val f = hz * CHAMBER_RATIOS[k]
                bank.tune(k + 1, f, CHAMBER_Q[k] * T60_LN / (PI * f))
            }
            sigma = T60_LN / cavityT60
            sense = bank.participation(DoubleArray(bank.size) { if (it == 0) 1.0 else 0.0 })
            drive = bank.participation(DoubleArray(bank.size) { if (it == 0) 1.0 else CHAMBER_DRIVE[it - 1] })
            heard = bank.participation(DoubleArray(bank.size) { if (it == 0) 1.0 else b.chamber * CHAMBER_HEARD[it - 1] })
            // Small-signal loop gain at the cavity is speed * g * sech^2(bias) / 2 (a real drive into a complex mode):
            // [Breath.loopGain] at the full breath.
            val sech = 1.0 / cosh(JET_BIAS)
            gain = 2.0 * b.loopGain / (b.speed * sech * sech)
        }

        fun next(i: Int, breath: Double): Double {
            bank.step()
            val p = bank.velocityAlong(sense)
            ring[head] = p
            // The jet crosses the mouth in [Breath.jetRatio] of a period, a little slower while the breath is not yet full.
            val delay = period * b.jetRatio * (1.0 + SCOOP * (1.0 - breath))
            val at = head - delay
            val i0 = kotlin.math.floor(at).toInt()
            val frac = at - i0
            val older = ring[Math.floorMod(i0, ring.size)]
            val newer = ring[Math.floorMod(i0 + 1, ring.size)]
            val eta = older + (newer - older) * frac
            head = (head + 1) % ring.size
            // The flow into the mouth, minus its rest: bounded by tanh, inverted across the half-period transit.
            val flow = -(tanh(gain * eta + JET_BIAS) - rest)
            low += lowA * (flow - low)
            val speed = b.speed * breath
            val age = i - b.startRaw
            val seed = if (age in 0 until b.swellRaw) {
                val x = age.toDouble() / b.swellRaw
                CHIFF_LEVEL * b.speed * 6.75 * x * x * (1.0 - x) * chiff.next()
            } else 0.0
            bank.drive(drive, sigma * (speed * low + seed))
            flowPeak = maxOf(flowPeak, abs(low))
            return (bank.velocityAlong(heard) + b.edge * speed * low) / (1.0 + EDGE_LEVEL * b.edge)
        }
    }

    /** One shell's tone alone at [hz], [frames] raw samples, its breath fading from raw frame [fade] (or never, at -1). */
    private fun shellStream(b: Breath, hz: Double, frames: Int, fade: Int, chiffSeed: Int): FloatArray =
        shellStreamMeasured(b, hz, frames, fade, chiffSeed).first

    /** [shellStream] with the jet's largest low-passed flow, which `tanh` bounds by `1 + tanh(JET_BIAS)` whatever the breath. */
    private fun shellStreamMeasured(b: Breath, hz: Double, frames: Int, fade: Int, chiffSeed: Int): Pair<FloatArray, Double> {
        val shell = Shell(hz, b, chiffSeed)
        val release = (RELEASE_SECONDS * RAW_RATE).roundToInt()
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            if (i % 32768 == 0) NacreVoices.stopIfInterrupted()
            out[i] = shell.next(i, b.at(i, fade, release)).toFloat()
        }
        return out to shell.flowPeak
    }

    /**
     * The shell's heard breath: a seeded white noise of exactly one loop ([period] raw samples, read circularly), through
     * band-passes at the shell's own modes, scaled by the breath. Linear and fed forward, never into the jet.
     */
    private class BreathHiss(hz: Double, private val b: Breath, seed: Int, private val period: Int) {
        private val table = Dsp.Noise(seed).let { n -> FloatArray(period) { n.next() } }
        private val ratios = doubleArrayOf(1.0) + CHAMBER_RATIOS
        private val qs = doubleArrayOf(7.0, 5.0, 4.0, 3.0)
        private val weights = doubleArrayOf(1.0, .75, .55, .40)
        private val bands = ratios.indices.map { NacreVoices.Mode(hz * ratios[it], qs[it], RAW_RATE) }
        // Unit-variance output per band: white noise of variance 1/3 through a peak-1 band-pass of bandwidth f/Q.
        private val norms = ratios.indices.map { sqrt(3.0 * qs[it] * RAW_RATE / (PI * hz * ratios[it])) }
        private val sum = weights.sum()

        fun next(i: Int, breath: Double): Double {
            val x = table[i % period].toDouble()
            var y = 0.0
            for (k in bands.indices) y += weights[k] * norms[k] * bands[k].next(x)
            return b.hiss * b.speed * breath * y / sum
        }
    }

    private data class ShellKey(val upper: Boolean, val touch: Float, val duet: Float, val velocity: Float, val midi: Int, val seed: Long)
    private val tunings = object : LinkedHashMap<ShellKey, LoopRetune.Retuned>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ShellKey, LoopRetune.Retuned>?) = size > 4
    }

    private fun chiffSeed(seed: Long, upper: Boolean) = Dsp.seedFor("NACRE", MODEL, seed, if (upper) "upper" else "bass", 0, "chiff")
    private fun hissSeed(seed: Long, upper: Boolean) = Dsp.seedFor("NACRE", MODEL, seed, if (upper) "upper" else "bass", 0, "breath")

    /** A held shell, retuned so the loop's whole periods fill its whole frames (ARCO's route, [LoopRetune]): each shell on its own. */
    private fun retuned(plan: Plan, upper: Boolean, b: Breath, key: ShellKey): LoopRetune.Retuned {
        synchronized(tunings) { tunings[key]?.let { return it } }
        val target = if (upper) plan.upperHz.toFloat() else plan.hz
        val r = LoopRetune.retuned(target, if (upper) plan.upper else plan.bass, plan.loopStart) { hz ->
            shellStream(b, hz.toDouble(), plan.heldFrames * OVER, -1, chiffSeed(key.seed, upper))
        }
        synchronized(tunings) { tunings[key] = r }
        return r
    }

    /** The held shell alone at its retuned pitch, with its plan: what a loop's source is, before the harps. Tests read it. */
    internal fun heldShell(midi: Int, touch: Float, duet: Float, velocity: Float, upper: Boolean, seed: Long = Nacre.DEFAULT_SEED):
        Pair<LoopRetune.Retuned, Plan> {
        val plan = planFor(midi, touch.toDouble(), duet.toDouble())
        val b = breathFor(plan, upper, touch.toDouble(), velocity)
        return retuned(plan, upper, b, ShellKey(upper, touch, duet, velocity, midi, seed)) to plan
    }

    // ---- the core ---------------------------------------------------------------

    private class Core(val plan: Plan, val bass: FloatArray, val upper: FloatArray, val sympathetic: FloatArray,
                       val events: List<Nacre.Event>, val diagnostics: Map<String, Double>) {
        val bytes: Long get() = 4L * (bass.size + upper.size + sympathetic.size)
    }

    /** [fadeFrames] is the frame the finite breath begins to fade, or -1 for a held note. */
    private data class CoreKey(val touch: Float, val duet: Float, val midi: Int, val velocity: Float, val seed: Long,
                               val fadeFrames: Int)

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
        val plan = planFor(key.midi, touch, duet)
        val f0 = plan.hz.toDouble()
        val water = NacreHeld.field(MODEL, key.seed, plan.loopFrames)
        val held = key.fadeFrames < 0
        val frames = if (held) plan.heldFrames * OVER else
            (key.fadeFrames + ((RELEASE_SECONDS + RING_SECONDS) * RATE).roundToInt()) * OVER
        val fade = if (held) -1 else key.fadeFrames * OVER
        val period = plan.period
        val breaths = listOf(false, true).map { breathFor(plan, it, touch, velocity) }
        val tunes = listOf(false, true).map { up ->
            retuned(plan, up, breaths[if (up) 1 else 0], ShellKey(up, key.touch, key.duet, velocity, key.midi, key.seed))
        }
        val flowPeaks = ArrayList<Double>(2)
        val tones = listOf(false, true).map { up ->
            val t = tunes[if (up) 1 else 0]
            // At the very pitch the retune rendered the held shell at (a Float), so a finite note is the held one until its fade.
            val (stream, peak) = shellStreamMeasured(breaths[if (up) 1 else 0], t.tunedHz.toFloat().toDouble(), frames, fade,
                chiffSeed(key.seed, up))
            flowPeaks += peak
            // The held note plays the retune's own stream; the measured one is the same arithmetic, cut at its fade if finite.
            if (held) t.raw else stream
        }
        val hiss = listOf(false, true).map { up ->
            BreathHiss(tunes[if (up) 1 else 0].tunedHz, breaths[if (up) 1 else 0], hissSeed(key.seed, up), period)
        }
        val bassGain = SHELL_LEVEL
        val upperGain = SHELL_LEVEL * upperShare(duet)
        val events = listOf(
            Nacre.Event("bass", 0, plan.bassStart.toDouble() / RATE, f0, bassGain * breaths[0].speed, "breath"),
            Nacre.Event("upper", 0, plan.upperStart.toDouble() / RATE, plan.upperHz, upperGain * breaths[1].speed, "breath"),
        ).sortedWith(compareBy<Nacre.Event> { it.timeSeconds }.thenBy { it.performer })

        val harps = NacreVoices.Harps(f0, RAW_RATE)
        val bassOut = FloatArray(frames)
        val upperOut = FloatArray(frames)
        val symOut = FloatArray(frames)
        var crossBass = 1.0
        var crossUpper = 1.0
        var bassFlow = 0.0
        var upperFlow = 0.0
        var tonePeak = 0.0
        val flowPeak = flowPeaks.max()
        val lift = sympatheticLift(f0)
        val release = (RELEASE_SECONDS * RAW_RATE).roundToInt()
        for (i in 0 until frames) {
            if (i % 32768 == 0) NacreVoices.stopIfInterrupted()
            if ((i % period) % CONTROL_BLOCK == 0) {
                // The water on the held clock, in loop-relative control blocks: the same values every lap.
                val time = NacreHeld.fieldSeconds(i, period, RAW_RATE)
                bassFlow = water.at(time, BASS_POSITION)
                upperFlow = water.at(time, UPPER_POSITION)
                crossBass = NacreVoices.crossFor(water.depth, bassFlow)
                crossUpper = NacreVoices.crossFor(water.depth, upperFlow)
            }
            tonePeak = maxOf(tonePeak, abs(tones[0][i].toDouble()), abs(tones[1][i].toDouble()))
            val time = NacreHeld.fieldSeconds(i, period, RAW_RATE)
            val bassShell = tones[0][i] + hiss[0].next(i, breaths[0].at(i, fade, release))
            val upperShell = tones[1][i] + hiss[1].next(i, breaths[1].at(i, fade, release))
            // The shells' level swells with the water at each harp, on the held clock, after the jets.
            val bass = bassGain * bassShell * (1.0 + SWELL * water.at(time, BASS_POSITION))
            val upper = upperGain * upperShell * (1.0 + SWELL * water.at(time, UPPER_POSITION))
            harps.next(bass, upper, crossBass, crossUpper)
            val b = harps.bass
            val u = harps.upper
            val sym = harps.sympathetic
            check(b.isFinite() && u.isFinite() && sym.isFinite()) { "NACRE CONCH nonfinite state at frame $i" }
            bassOut[i] = b.toFloat()
            upperOut[i] = u.toFloat()
            symOut[i] = (sym * harps.sympatheticGain * lift).toFloat()
        }
        val diagnostics = linkedMapOf(
            "tunedHz" to tunes[0].tunedHz,
            "upperTunedHz" to tunes[1].tunedHz,
            "retunePasses" to tunes[0].passes.toDouble(),
            "upperRetunePasses" to tunes[1].passes.toDouble(),
            "loopFrames" to plan.loopFrames.toDouble(),
            "loopPeriods" to plan.bass.periods.toDouble(),
            "upperLoopPeriods" to plan.upper.periods.toDouble(),
            "beatCyclesPerLoop" to beatCycles(duet).toDouble(),
            "loopSeconds" to plan.loopFrames.toDouble() / RATE,
            "loopHz" to plan.bass.periods * RATE.toDouble() / plan.loopFrames,
            "upperLoopHz" to plan.upperHz,
            "warmFrames" to plan.warmFrames.toDouble(),
            "upperStartSeconds" to plan.upperStart.toDouble() / RATE,
            "bassStartSeconds" to plan.bassStart.toDouble() / RATE,
            "heldFieldHz" to water.hz,
            "breathPressure" to breaths[0].pressure,
            "jetLoopGain" to breaths[0].loopGain,
            "jetRatio" to breaths[0].jetRatio,
            "toneRawPeak" to tonePeak,
            "jetFlowPeak" to flowPeak,
            "sympatheticLift" to lift,
        )
        return Core(plan, NacreVoices.condition(bassOut), NacreVoices.condition(upperOut), NacreVoices.condition(symOut),
            events, diagnostics)
    }

    // ---- the render -------------------------------------------------------------

    /** One rendered note, as [NacreGrotto.Render]: the probe, the whole native output, and a held note's loop and closure. */
    internal class Render(val probe: Nacre.Probe, val native: FloatArray, val loopStart: Int, val loopFrames: Int,
                          val closure: NacreHeld.Closure?)

    fun render(macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI, velocity: Float = 1f,
               seed: Long = Nacre.DEFAULT_SEED): Snip = play(macros, midi, velocity, seed, record = false).probe.snip

    fun inspect(macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI, velocity: Float = 1f,
                seed: Long = Nacre.DEFAULT_SEED): Nacre.Probe = play(macros, midi, velocity, seed, record = true).probe

    /** A held key as one file: the note's own breath and warm-up, the lap before the loop, then exactly one loop from [Render.loopStart]. */
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
        require(supportsMidi(midi)) { "NACRE CONCH plays MIDI $MIN_MIDI..$MAX_MIDI (C2 to C5), got $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "NACRE velocity must be finite and in 0..1, got $velocity" }
        val m = settled(macros)
        val hold = m.getValue("HOLD")
        val held = NacreHeld.isLoop(hold)
        val touch = m.getValue("TOUCH")
        val fadeFrames = if (held) -1 else (breathSeconds(hold) * RATE).roundToInt()
        NacreVoices.stopIfInterrupted()
        val (c, hit) = core(CoreKey(touch, m.getValue("DUET"), midi, velocity, seed, fadeFrames))
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
                ("NACRE CONCH MIDI $midi velocity $velocity TOUCH $touch DUET ${m.getValue("DUET")}: the held loop does not close " +
                    "(seam before %.2e, after %.2e, cycle %.2e, bar %.0e)").format(java.util.Locale.ROOT, closure.before, closure.after,
                    closure.cycleError, Keys.MAX_SEAM_ERROR)
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
            it["breathSeconds"] = if (held) -1.0 else fadeFrames.toDouble() / RATE
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

    /** The length of a render in frames, in closed form: one loop at HOLD's top step, else the breath, its fade and the ring. */
    fun renderFrames(macros: Map<String, Float>, midi: Int): Int {
        val m = settled(macros)
        val hold = m.getValue("HOLD")
        return if (NacreHeld.isLoop(hold)) NacreHeld.plan(Keys.midiHz(midi)).frames
        else (breathSeconds(hold) * RATE).roundToInt() + ((RELEASE_SECONDS + RING_SECONDS) * RATE).roundToInt()
    }
}
