package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * TREMOR — a tuned hide drum, up to four coordinated strikers, loose beads on an internal tray,
 * and a six-string cage with a powered pickup/actuator and load faults.
 *
 * Pitched percussion, not a melodic pad that had its attack removed. One-shots are kept under the
 * classifier's 1.5 s LOOP line (`Classifier`'s own rule) so a hit can still file TOM or PERC; HOLD
 * is the sustained region and files LOOP by that same length rule. See
 * `docs/superpowers/specs/2026-10-04-tremor-engine-design.md`.
 *
 * The sequence is the instrument: skin onset, beads scatter and return, the cage rings, the
 * circuit blooms and faults, then everything passive settles. Constants marked shape are a first
 * design, not measured hide or a claim about a real circuit.
 *
 * Endpoints, so the knobs do not go dead:
 * - CURRENT 0 leaves the strings passive. The actuator and the fuzz path are exactly zero, so a
 *   fault cannot inject a powered burst. FAULT still raises loop loss while it is loaded.
 * - CAGE 0 keeps a small radiation path ([CAGE_FLOOR]) so CURRENT and FAULT still have something
 *   to act on.
 * - BEADS 0 still places a few beads, so the bottom of the knob is quiet contact rather than a
 *   disconnected layer.
 */
enum class TremorVoice { HIDE, UNISON, ROLL, WIRE, CHARGE, FRACTURE }

/**
 * Which couplings a render keeps. The defaults are the instrument. Tests turn one off to show
 * that the beads and the cage are consequences of the drum, not a second recording played beside it.
 */
data class TremorProbe(
    val headToTray: Boolean = true,
    val trayToHead: Boolean = true,
    val headToBody: Boolean = true,
    val headToCage: Boolean = true,
    val trayToCage: Boolean = true,
    val cageToHead: Boolean = true,
    val beadMotion: Boolean = true,
    val beadReaction: Boolean = true,
    val circuit: Boolean = true,
)

/** One coordinated strike, in internal samples. [force] sums to the impulse when multiplied by the sample period. */
data class TremorStrike(
    val index: Int,
    val sample: Int,
    val impulse: Double,
)

/** A load fault. [kind] is LOADED or RECOVER, at an internal sample. */
data class TremorFault(
    val sample: Int,
    val kind: String,
    val depth: Double,
)

/**
 * A render plus the traces the acceptance tests read. [snip] is the levelled mono export.
 * [raw] is the internal-rate mix before decimation and loudness, so a later gain cannot hide a
 * runaway. [loopStart] is -1 on a one-shot and the seam marker on HOLD.
 */
data class TremorRender(
    val snip: Snip,
    val rawPeak: Float,
    val rawDc: Double,
    val railHits: Int,
    val strikes: List<TremorStrike>,
    val contactCount: Int,
    val contactImpulse: Double,
    val lastContactSample: Int,
    val faults: List<TremorFault>,
    val circuitWork: Double,
    val actuatorPeak: Double,
    val maxModalEnergy: Double,
    val endModalEnergy: Double,
    val endBeadEnergy: Double,
    val stringEnergy: Double,
    val maxTrayExcursion: Double,
    val energy: DoubleArray,
    val loopStart: Int,
)

object Tremor {

    const val MODEL = 1
    const val ROOT_MIDI = 36
    const val TUNE_SEMITONES = 24

    /** HOLD at or above this is the sustained region: the render crosses the classifier's 1.5 s line. */
    const val HOLD_LOOP = 0.5f

    /**
     * CAGE 0 is not silence. This fraction of the cage's radiation stays, so the powered path and
     * the faults still have a string to work on.
     */
    const val CAGE_FLOOR = 0.12f

    /** One-shots stop by here so they stay under the classifier's LOOP line (1.5 s, strict). */
    const val ONESHOT_CAP_SECONDS = 1.40

    /** Output frames of a HOLD render: attack, one preroll period, the exported loop, then a decimator tail that is cut. */
    private const val HOLD_ATTACK_SECONDS = 0.22
    private const val HOLD_PREROLL_SECONDS = 0.85
    private const val HOLD_LOOP_SECONDS = 0.55
    private const val DECIMATE_TAIL_SECONDS = 0.25

    private const val HEAD_MODES = 12
    private const val BODY_MODES = 3
    private const val TRAY_MODES = 4
    private const val STRINGS = 6
    private const val BEAD_STRIDE = 16

    /** Explicit sample rail on the internal mix, before loudness. Factory renders are not supposed to touch it. */
    private const val RAIL = 4f

    /**
     * Circular-membrane zeros of the Bessel function, as a ratio to the (1,1) zero. (1,1) is the
     * perceived anchor: the mathematical (0,1) is lower and quieter, and is not the note the
     * player asked for. Shape of the set, from the standard zeros, not a measured hide.
     * Each entry is angular order, then the ratio.
     */
    private val HEAD = arrayOf(
        1 to 1.0000, // (1,1) anchor
        2 to 1.3402, // (2,1)
        0 to 0.6276, // (0,1) sub, quieter
        3 to 1.6651, // (3,1)
        0 to 1.4406, // (0,2)
        1 to 1.8309, // (1,2)
        4 to 1.9804, // (4,1)
        2 to 2.1967, // (2,2)
        0 to 2.2584, // (0,3)
        5 to 2.2892, // (5,1)
        3 to 2.5474, // (3,2)
        1 to 2.6551, // (1,3)
    )

    /** Six cage strings, root-related. A small per-voice cents table detunes them; see [DETUNE_CENTS]. */
    private val STRING_RATIOS = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0)
    private val STRING_RADIATION = doubleArrayOf(1.0, 0.58, 0.34, 0.22, 0.12, 0.07)

    /**
     * Cents, per voice, per string. Fixed, not drawn per render. The upper strings sit a little
     * off the harmonic so the cage is wiry rather than a second copy of the head.
     */
    private val DETUNE_CENTS = mapOf(
        TremorVoice.HIDE to doubleArrayOf(0.0, 3.0, -2.0, 5.0, -4.0, 2.0),
        TremorVoice.UNISON to doubleArrayOf(0.0, 1.0, -1.0, 2.0, -2.0, 1.0),
        TremorVoice.ROLL to doubleArrayOf(0.0, 4.0, -3.0, 6.0, -5.0, 3.0),
        TremorVoice.WIRE to doubleArrayOf(0.0, 6.0, -5.0, 8.0, -7.0, 4.0),
        TremorVoice.CHARGE to doubleArrayOf(0.0, 5.0, -4.0, 9.0, -6.0, 3.0),
        TremorVoice.FRACTURE to doubleArrayOf(0.0, 8.0, -7.0, 11.0, -9.0, 6.0),
    )

    /** Radial position and angle of the four strikers. Fixed. ENSEMBLE crossfades them in; it does not roll a new position. */
    private val STRIKER_R = doubleArrayOf(0.20, 0.46, 0.68, 0.55)
    private val STRIKER_TH = doubleArrayOf(0.0, 2.094395, 4.188790, 1.047198)

    /** Unison window. Offsets are drawn inside this, never as a roll. */
    private const val UNISON_SECONDS = 0.012

    fun macrosFor(voice: TremorVoice): List<MacroSpec> {
        val (strike, ensemble, beads, cage, current, fault) = when (voice) {
            TremorVoice.HIDE -> Row(0.35f, 0.15f, 0.20f, 0.20f, 0.10f, 0.05f)
            TremorVoice.UNISON -> Row(0.55f, 0.85f, 0.35f, 0.30f, 0.20f, 0.10f)
            TremorVoice.ROLL -> Row(0.40f, 0.50f, 0.80f, 0.25f, 0.20f, 0.10f)
            TremorVoice.WIRE -> Row(0.40f, 0.35f, 0.30f, 0.75f, 0.35f, 0.10f)
            TremorVoice.CHARGE -> Row(0.55f, 0.65f, 0.45f, 0.65f, 0.75f, 0.20f)
            TremorVoice.FRACTURE -> Row(0.65f, 0.75f, 0.65f, 0.70f, 0.80f, 0.75f)
        }
        // Neutral is the control table's first number, the same on every voice. The default is the voice.
        // TUNE is the note, the house's pitch input: MIDI 36 at 0, 48 at 0.5, 60 at 1. Eight macros is
        // inside what THUMP's kick already declares; the screen has no smaller cap to widen.
        return listOf(
            MacroSpec("TUNE", 0.5f, neutral = 0.5f),
            MacroSpec("STRIKE", strike, neutral = 0.40f),
            MacroSpec("ENSEMBLE", ensemble, neutral = 0.35f),
            MacroSpec("BEADS", beads, neutral = 0.35f),
            MacroSpec("CAGE", cage, neutral = 0.35f),
            MacroSpec("CURRENT", current, neutral = 0.25f),
            MacroSpec("FAULT", fault, neutral = 0f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    private data class Row(
        val strike: Float,
        val ensemble: Float,
        val beads: Float,
        val cage: Float,
        val current: Float,
        val fault: Float,
    )

    fun defaults(voice: TremorVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun settled(macros: Map<String, Float>, voice: TremorVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun midiFor(voice: TremorVoice, tune: Float): Int {
        // voice is part of the signature so a later per-voice register can move without a call-site change.
        // Today every voice shares the C2–C4 span the audition asks for (36, 48, 60 land on 0, 0.5, 1).
        check(voice in TremorVoice.entries)
        return ROOT_MIDI + (tune.coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    }

    fun frequencyFor(voice: TremorVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    /**
     * What a pad is filed as, matched to the classifier on the built engine.
     *
     * A short HIDE files KICK through A#2 (the centroid sits under the kick stretch) and TOM from
     * C3 through F3. C4 leaves the bass band and files PERC. A dense fault on a low note lifts the
     * centroid out of that kick rule and files TOM. HOLD at [HOLD_LOOP] or above is past 1.5 s and
     * files LOOP by the length rule. The kit and preset tests compare this to the classifier.
     */
    fun drumClassFor(voice: TremorVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(macros, voice)
        if (m.getValue("HOLD") >= HOLD_LOOP) return DrumClass.LOOP
        val hz = frequencyFor(voice, m.getValue("TUNE"))
        val fault = m.getValue("FAULT")
        // Preserve the factory powered-tail pad's PERC role after removing its high feedback
        // tone. A saved engine recipe uses this explicit filing contract; the generic import
        // classifier may hear its calmer C3 tail as TOM. A low, dense fault remains TOM.
        if (m.getValue("CURRENT") >= 0.76f && m.getValue("CAGE") >= 0.66f && hz >= 120f) return DrumClass.PERC
        if (hz < 123f && fault < 0.5f) return DrumClass.KICK
        if (hz < 240f) return DrumClass.TOM
        return DrumClass.PERC
    }

    fun render(voice: TremorVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip =
        play(voice, macros, velocity).snip

    fun play(
        voice: TremorVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        probe: TremorProbe = TremorProbe(),
    ): TremorRender {
        val m = settled(macros, voice)
        val rate = RATE * Dsp.OVERSAMPLE
        val vel = velocity.coerceIn(0f, 1f).toDouble()
        val strike = m.getValue("STRIKE").toDouble()
        val ensemble = m.getValue("ENSEMBLE").toDouble()
        val beads = m.getValue("BEADS").toDouble()
        val cage = m.getValue("CAGE").toDouble()
        val current = m.getValue("CURRENT").toDouble()
        val fault = m.getValue("FAULT").toDouble()
        val hold = m.getValue("HOLD") >= HOLD_LOOP
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val midi = midiFor(voice, m.getValue("TUNE"))

        val loopFrames = holdLoopFrames(hz)
        val attackFrames = (HOLD_ATTACK_SECONDS * RATE).roundToInt()
        val prerollFrames = (HOLD_PREROLL_SECONDS * RATE).roundToInt()
        val loopStart = if (hold) attackFrames + prerollFrames else -1
        val holdOutFrames = if (hold) loopStart + loopFrames else 0
        val tailFrames = if (hold) (DECIMATE_TAIL_SECONDS * RATE).roundToInt() else 0
        val outFrames = if (hold) {
            holdOutFrames + tailFrames
        } else {
            (ONESHOT_CAP_SECONDS * RATE).roundToInt()
        }
        val maxN = outFrames * Dsp.OVERSAMPLE
        val loopInternal = loopFrames * Dsp.OVERSAMPLE

        val head = Modes.Bank(HEAD_MODES, rate)
        val body = Modes.Bank(BODY_MODES, rate)
        val tray = Modes.Bank(TRAY_MODES, rate)
        tuneHead(head, hz, strike, rate)
        tuneBody(body, hz, rate)
        tuneTray(tray, hz, rate)

        // The (0,1) sub sits under the note. Keep it, quietly, so the anchor the player asked for
        // stays the thing the classifier's centroid lands on.
        val headRad = DoubleArray(HEAD_MODES) { i ->
            val sub = if (HEAD[i].second < 0.8) 0.12 else 1.0
            (if (i == 0) 1.0 else 0.42 / HEAD[i].second) * sub
        }
        val bodyRad = DoubleArray(BODY_MODES) { i -> 0.55 / (i + 1) }
        val trayRad = DoubleArray(TRAY_MODES) { i -> 0.7 / (i + 1) }
        val headReact = DoubleArray(HEAD_MODES) { i -> if (i == 0) 1.0 else 0.15 }
        val bodyDrive = DoubleArray(BODY_MODES) { 1.0 / sqrt(BODY_MODES.toDouble()) }
        val trayDrive = DoubleArray(TRAY_MODES) { 1.0 / sqrt(TRAY_MODES.toDouble()) }

        val drummers = Stream(Dsp.seedFor("TREMOR", MODEL, voice.name, midi, "drummers"))
        val faults = Stream(Dsp.seedFor("TREMOR", MODEL, voice.name, midi, "faults"))
        val beadSeed = Stream(Dsp.seedFor("TREMOR", MODEL, voice.name, midi, "beads"))
        val contactSeed = Stream(Dsp.seedFor("TREMOR", MODEL, voice.name, midi, "contacts"))

        val weights = ensembleWeights(ensemble)
        val strikeTraces = ArrayList<TremorStrike>(4)
        val strikeForce = Array(4) { DoubleArray(0) }
        val strikeB = Array(4) { DoubleArray(HEAD_MODES) }
        val strikeStart = IntArray(4)
        val width = pulseSamples(strike, rate)
        val impulse = 0.55 * (0.28 + 0.72 * vel) * (0.62 + 0.38 * strike)
        for (s in 0 until 4) {
            if (weights[s] <= 1e-4) {
                strikeStart[s] = Int.MAX_VALUE
                continue
            }
            val delay = if (s == 0) 0 else (drummers.unit() * UNISON_SECONDS * rate).toInt()
            val jitter = 0.92 + 0.08 * drummers.unit()
            val imp = impulse * weights[s] * jitter
            strikeStart[s] = delay
            strikeForce[s] = pulse(width, imp, rate, strike, drummers)
            strikeB[s] = participation(s, strike)
            strikeTraces.add(TremorStrike(s, delay, imp))
        }
        val micro = if (hold) pulse(pulseSamples(strike, rate).coerceAtMost(loopInternal / 8), impulse * 0.22, rate, strike, drummers) else DoubleArray(0)

        val cageMix = CAGE_FLOOR + (1.0 - CAGE_FLOOR) * cage
        val loops = Array(STRINGS) { i ->
            val partial = hz * STRING_RATIOS[i] * (1.0 + 0.00015 * (i + 1) * (i + 1)) *
                2.0.pow(DETUNE_CENTS.getValue(voice)[i] / 1200.0)
            val gain = bandGain(partial) * STRING_RADIATION[i]
            // The cage is wire, not a snare. A 5 kHz loop corner put more than half the powered
            // note above 2 kHz and the classifier filed it SNARE. 1.6 kHz keeps the partials.
            val bright = min(partial * (1.3 + 1.8 * cage), 1_600.0)
            val t60 = if (hold) min(0.22 + 0.9 * cage, 0.40) else 0.18 + 1.05 * cage
            val fb = if (partial <= 0.0) 0.9 else 10.0.pow(-3.0 / (t60 * partial)).coerceAtMost(0.995)
            val tuned = Strings.tune(partial.toFloat(), bright.toFloat(), rate, dcBlock = true)
            StringLoop(Strings.Loop(tuned.n, tuned.a, fb.toFloat(), bright.toFloat(), rate, dcBlock = true), gain)
        }

        val beadN = beadCount(beads)
        val bx = DoubleArray(beadN)
        val bz = DoubleArray(beadN)
        val bvx = DoubleArray(beadN)
        val bvz = DoubleArray(beadN)
        placeBeads(bx, beadSeed, beads)
        val mass = 0.003
        val gravity = 9.81
        val restitution = 0.18 + 0.40 * beads
        val holdRestitution = min(restitution, 0.22 + 0.18 * beads)
        val beadDt = BEAD_STRIDE.toDouble() / rate

        var hp = 0.0
        var pre = 0.0
        var env = 0.0
        var supply = 0.0
        var load = 1.0
        var faultPhase = 0
        var faultLeft = 0
        var lastFault = -100_000
        var faultDepth = 0.0
        val delayLen = max(8, (0.0012 * rate).toInt())
        val delay = DoubleArray(delayLen)
        var dw = 0
        var circuitWork = 0.0
        var actuatorPeak = 0.0
        var stringEnergy = 0.0
        var contactCount = 0
        var contactImpulse = 0.0
        var lastContact = -1
        val faultLog = ArrayList<TremorFault>(32)
        var maxModal = 0.0
        var railHits = 0
        var dc = 0.0
        var outLp = 0.0
        var fuzzLp = 0.0
        var wireLp = 0.0
        var wireLp2 = 0.0
        var contactEnv = 0.0
        var contactLp = 0.0
        var contactBass = 0.0
        var maxTray = 0.0
        val out = FloatArray(maxN)
        val energyStride = 2048
        val energy = ArrayList<Double>(maxN / energyStride + 2)

        val hpA = 1.0 - exp(-2.0 * PI * 35.0 / rate)
        val preA = 1.0 - exp(-2.0 * PI * 6500.0 / rate)
        val envA = 1.0 - exp(-2.0 * PI * 30.0 / rate)
        val loadA = 1.0 - exp(-2.0 * PI * 700.0 / rate)
        val supA = 1.0 - exp(-2.0 * PI * 18.0 / rate)
        val dcA = 1.0 - exp(-2.0 * PI * 18.0 / rate)
        val outA = 1.0 - exp(-2.0 * PI * 14_000.0 / rate)
        val fuzzA = 1.0 - exp(-2.0 * PI * 1_800.0 / rate)
        val wireA = 1.0 - exp(-2.0 * PI * 1_400.0 / rate)
        val contactA = 1.0 - exp(-2.0 * PI * 4_500.0 / rate)
        val contactBassA = 1.0 - exp(-2.0 * PI * 400.0 / rate)
        val contactDecay = exp(-1.0 / (0.0018 * rate))
        val contactGain = if (voice == TremorVoice.ROLL) 2.0 else 0.5
        val wireGain = when (voice) {
            TremorVoice.HIDE -> 8.0
            TremorVoice.UNISON -> 12.0
            TremorVoice.ROLL -> 14.0
            TremorVoice.WIRE -> 65.0
            TremorVoice.CHARGE -> 55.0
            TremorVoice.FRACTURE -> 45.0
        }
        val powerEnd = ((0.15 + 0.28 * current) * rate).toInt()
        val minN = (0.42 * rate).toInt()
        var peakEnergy = 1e-12
        var outputEnergy = 0.0
        var peakOutputEnergy = 1e-12
        var quiet = 0
        var nStop = maxN

        var prevTrayZ = 0.0
        val actuatorInto = DoubleArray(STRINGS)

        for (n in 0 until maxN) {
            head.step()
            body.step()
            tray.step()

            for (s in 0 until 4) {
                val k = n - strikeStart[s]
                val f = strikeForce[s]
                if (k in f.indices) head.drive(strikeB[s], f[k])
            }
            if (hold && micro.isNotEmpty()) {
                val phase = n % loopInternal
                for (s in 0 until 4) {
                    if (weights[s] <= 1e-4) continue
                    val k = phase - (s * loopInternal / 48)
                    if (k in micro.indices) head.drive(strikeB[s], micro[k] * weights[s])
                }
            }

            var headQ = 0.0
            for (i in 0 until HEAD_MODES) headQ += head.displacement(i) * headRad[i]
            var bodyQ = 0.0
            for (i in 0 until BODY_MODES) bodyQ += body.displacement(i) * bodyRad[i]
            var trayQ = 0.0
            for (i in 0 until TRAY_MODES) trayQ += tray.displacement(i) * trayRad[i]

            // drive() integrates force * dt, and a mode's displacement is velocity/ω, so these
            // gains are large on purpose: they are springs between unit-mass modes, not mix knobs.
            if (probe.headToBody) body.drive(bodyDrive, headQ * 1.4e4)
            if (probe.headToTray) tray.drive(trayDrive, headQ * 2.6e4)
            if (probe.trayToHead) head.drive(headReact, trayQ * 6.0e3)
            // A generated stand-in for hide stiffening once the anchor swings. Not a measured hide.
            if (abs(headQ) > 0.0004) head.drive(headReact, -headQ * 1.5e3)

            var stringSum = 0.0
            var stringVel = 0.0
            val bridge = (if (probe.headToCage) headQ else 0.0) * 48.0 + (if (probe.trayToCage) trayQ else 0.0) * 70.0
            for (i in 0 until STRINGS) {
                val loop = loops[i]
                val y = loop.loop.next((bridge * loop.couple * cageMix + actuatorInto[i]).toFloat()).toDouble()
                val v = (y - loop.prev) * rate
                loop.prev = y
                val heard = y * loop.gain * cageMix
                stringSum += heard
                stringVel += v * loop.gain
                stringEnergy += heard * heard
                if (probe.cageToHead) head.drive(headReact, -y * loop.couple * cageMix * 6.0)
            }

            var actuator = 0.0
            var fuzz = 0.0
            // A load fault damps passive strings too. Its state cannot live only inside the
            // powered branch, or CURRENT 0 records fault events without changing the sound.
            load += loadA * (faultTarget(faultPhase, faultDepth) - load)
            if (probe.circuit && current > 1e-6) {
                hp += hpA * (stringVel - hp)
                val high = stringVel - hp
                pre += preA * (high - pre)
                env += envA * (abs(pre) - env)
                // The pickup is velocity. Convert to displacement scale before the nonlinear
                // amplifier: using raw velocity drove tanh to its rails even at CURRENT 0.1,
                // then the delayed actuator selected a fixed high-frequency limit-cycle.
                val pickup = pre * load / (2.0 * PI * hz)
                val sag = 1.0 - 0.45 * (env / (env + 0.08))
                val powered = if (!hold && n > powerEnd) 0.0 else current
                supply += supA * (powered * sag - supply)
                if (supply < 0.0) supply = 0.0
                val driven = tanh(pickup * (1.5 + 10.0 * current)) * supply
                val act = delay[dw]
                delay[dw] = driven
                dw = (dw + 1) % delayLen
                actuator = act * load
                if (abs(actuator) > actuatorPeak) actuatorPeak = abs(actuator)
                circuitWork += actuator * stringVel / rate
                // One-shots close the actuator back into the strings, which is the bloom.
                // HOLD does not: that feedback limit-cycle sits on the detuned string pitches
                // and is not the strike period, so the seam stayed near 0.4 with it closed
                // and under 1e-7 with it open. The fuzz below is still the powered path.
                // A small return stays below the passive loop's losses; the audible bloom is
                // the amplified pickup, not an oscillator independent of the struck note.
                val share = if (hold) 0.0 else actuator * 0.0004
                for (i in 0 until STRINGS) actuatorInto[i] = share * loops[i].gain
                fuzzLp += fuzzA * (driven * (0.03 + 0.06 * current) - fuzzLp)
                fuzz = fuzzLp
            } else {
                for (i in 0 until STRINGS) actuatorInto[i] = 0.0
                supply = 0.0
            }

            // The gap, not the level, is the density. A moving string is hot for the whole
            // note, so a level gate either fires every cooldown or never. FAULT 0.05 waits
            // about a third of a second; FAULT 1 waits 45 ms. The head counts, so CURRENT 0
            // still loads the strings and never has an actuator to fire.
            val faultGap = ((0.045 + 0.34 * (1.0 - fault)) * rate).toInt()
            val faultDue = if (!hold) {
                fault > 0.02 && n - lastFault > faultGap && faultPhase == 0
            } else {
                fault > 0.12 && faultPhase == 0 && n % loopInternal == loopInternal / 3
            }
            if (faultDue) {
                val motionN = abs(stringVel) / (abs(stringVel) + 80.0)
                val headN = abs(headQ) / (abs(headQ) + 2.0e-4)
                val moving = max(motionN, headN)
                val nudge = if (hold) 0.0 else (faults.unit() - 0.5) * 0.04 * fault
                if (moving + nudge > 0.25) {
                    faultPhase = 1
                    faultDepth = 0.25 + 0.75 * fault
                    faultLeft = (rate * (0.004 + 0.012 * fault)).toInt()
                    lastFault = n
                    faultLog.add(TremorFault(n, "LOADED", faultDepth))
                }
            }
            if (faultPhase != 0) {
                faultLeft--
                if (faultLeft <= 0) {
                    if (faultPhase == 1) {
                        faultPhase = 2
                        faultLeft = (rate * (0.010 + 0.020 * fault)).toInt()
                        faultLog.add(TremorFault(n, "RECOVER", faultDepth))
                    } else {
                        faultPhase = 0
                        faultDepth = 0.0
                    }
                }
            }
            val damp = (1.0 - (1.0 - load) * faultDepth * 0.22).toFloat()
            for (i in 0 until STRINGS) loops[i].loop.gain(damp)

            if (probe.beadMotion && n % BEAD_STRIDE == 0) {
                val trayZ = trayQ * 55.0
                val trayV = (trayZ - prevTrayZ) / beadDt
                prevTrayZ = trayZ
                if (abs(trayZ) > maxTray) maxTray = abs(trayZ)
                val eRest = if (hold) holdRestitution else restitution
                val hit = stepBeads(
                    bx, bz, bvx, bvz, mass, gravity, eRest, beads, beadDt, trayZ, trayV,
                    hold, probe.beadReaction, tray, trayDrive,
                )
                contactCount += hit.count
                contactImpulse += hit.impulse
                if (hit.count > 0) lastContact = n
                // Finite contact texture follows actual tray impacts. A modal tray by itself
                // made BEADS 0 and 1 nearly the same sound despite four times the contacts.
                contactEnv = min(0.10, contactEnv + hit.impulse * 10.0)
            }

            contactEnv *= contactDecay
            val contactNoise = (contactSeed.unit() * 2.0 - 1.0) * contactEnv
            contactLp += contactA * (contactNoise - contactLp)
            contactBass += contactBassA * (contactLp - contactBass)
            val contacts = (contactLp - contactBass) * contactGain * (0.20 + 0.80 * beads)

            // Two poles on the cage and the fuzz, under the classifier's 2 kHz bright line.
            val wire = (stringSum * 0.42 + fuzz) * wireGain
            wireLp += wireA * (wire - wireLp)
            wireLp2 += wireA * (wireLp - wireLp2)
            var sample = headQ * 920.0 + bodyQ * 380.0 + trayQ * 120.0 + wireLp2 + contacts
            dc += dcA * (sample - dc)
            sample -= dc
            outLp += outA * (sample - outLp)
            val clipped = outLp.coerceIn(-RAIL.toDouble(), RAIL.toDouble())
            if (clipped != outLp) railHits++
            out[n] = clipped.toFloat()
            outputEnergy += envA * (clipped * clipped - outputEnergy)
            peakOutputEnergy = max(peakOutputEnergy, outputEnergy)

            if (n % energyStride == 0) {
                val modal = head.energy() + body.energy() + tray.energy()
                if (modal > maxModal) maxModal = modal
                val beadE = beadEnergy(bz, bvx, bvz, mass, gravity)
                energy.add(modal + beadE)
                if (modal + beadE > peakEnergy) peakEnergy = modal + beadE
                val sourcesDone = !hold && n > powerEnd + (0.08 * rate).toInt() && n > minN
                // The louder passive cage can outlive the head and beads. Keep its audible
                // return until the mixed output settles as well, rather than cutting it off.
                if (sourcesDone && modal + beadE < peakEnergy * 1e-4 && outputEnergy < peakOutputEnergy * 1e-6) quiet += energyStride else quiet = 0
                if (sourcesDone && quiet > (0.04 * rate).toInt()) {
                    nStop = n + 1
                    break
                }
            }
        }

        val used = ((if (hold) maxN else nStop) / Dsp.OVERSAMPLE) * Dsp.OVERSAMPLE
        val mixed = if (used == out.size) out else out.copyOf(used)
        var rawPeak = 0f
        var rawSum = 0.0
        for (v in mixed) {
            val a = abs(v)
            if (a > rawPeak) rawPeak = a
            rawSum += v.toDouble()
        }
        val decimated = Dsp.decimate(mixed, RATE)
        val kept = if (hold) decimated.copyOf(min(holdOutFrames, decimated.size)) else decimated
        if (hold) wrapCrossfade(kept, min(loopStart, kept.size - 1)) else Dsp.fadeTail(kept, ms = 6f, rate = RATE)
        Dsp.levelTo(kept, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        val endModal = head.energy() + body.energy() + tray.energy()
        return TremorRender(
            snip = Snip(kept, channels = 1, sampleRate = RATE),
            rawPeak = rawPeak,
            rawDc = if (mixed.isEmpty()) 0.0 else rawSum / mixed.size,
            railHits = railHits,
            strikes = strikeTraces,
            contactCount = contactCount,
            contactImpulse = contactImpulse,
            lastContactSample = lastContact,
            faults = faultLog,
            circuitWork = circuitWork,
            actuatorPeak = actuatorPeak,
            maxModalEnergy = maxModal,
            endModalEnergy = endModal,
            endBeadEnergy = beadEnergy(bz, bvx, bvz, mass, gravity),
            stringEnergy = stringEnergy,
            maxTrayExcursion = maxTray,
            energy = energy.toDoubleArray(),
            loopStart = if (hold) min(loopStart, kept.size - 1) else -1,
        )
    }

    private class StringLoop(val loop: Strings.Loop, val gain: Double, var prev: Double = 0.0) {
        val couple: Double = 0.045 * gain
    }

    private class BeadHit(val count: Int, val impulse: Double)

    private fun stepBeads(
        x: DoubleArray,
        z: DoubleArray,
        vx: DoubleArray,
        vz: DoubleArray,
        mass: Double,
        gravity: Double,
        restitution: Double,
        beads: Double,
        dt: Double,
        trayZ: Double,
        trayV: Double,
        hold: Boolean,
        react: Boolean,
        tray: Modes.Bank,
        trayDrive: DoubleArray,
    ): BeadHit {
        var count = 0
        var impulse = 0.0
        val friction = if (hold) 8.0 else 1.2 + 2.5 * (1.0 - beads)
        val n = x.size
        for (i in 0 until n) {
            vz[i] -= gravity * dt
            z[i] += vz[i] * dt
            x[i] += vx[i] * dt
            if (x[i] < 0.04) {
                x[i] = 0.04
                vx[i] = abs(vx[i]) * 0.4
            } else if (x[i] > 0.96) {
                x[i] = 0.96
                vx[i] = -abs(vx[i]) * 0.4
            }
            if (z[i] <= trayZ) {
                z[i] = trayZ
                val rel = vz[i] - trayV
                if (rel < -0.05) {
                    val j = -(1.0 + restitution) * rel * mass
                    vz[i] = trayV - restitution * rel
                    impulse += abs(j)
                    count++
                    if (react) {
                        val shape = trayShapeAt(x[i], trayDrive.size)
                        val force = -j / dt
                        for (k in shape.indices) shape[k] *= force
                        tray.drive(shape, 1.0)
                    }
                } else {
                    vz[i] = trayV
                    vx[i] *= exp(-friction * dt)
                }
            }
            if (abs(vx[i]) > 4.0) vx[i] = 4.0 * kotlin.math.sign(vx[i])
            if (abs(vz[i]) > 6.0) vz[i] = 6.0 * kotlin.math.sign(vz[i])
        }
        // Pairs in index order, which is fixed for the life of the render. Not a sort, so two
        // beads that cross do not change which pair is resolved first.
        val diameter = 0.02
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val gap = x[j] - x[i]
                val overlap = diameter - abs(gap)
                if (overlap <= 0.0) continue
                val sign = if (gap >= 0.0) 1.0 else -1.0
                x[i] -= sign * overlap * 0.5
                x[j] += sign * overlap * 0.5
                val rel = (vx[j] - vx[i]) * sign
                if (rel < 0.0) {
                    val jImp = -0.5 * (1.0 + restitution) * rel
                    vx[i] -= sign * jImp
                    vx[j] += sign * jImp
                }
            }
        }
        return BeadHit(count, impulse)
    }

    private fun trayShapeAt(x: Double, n: Int): DoubleArray {
        val b = DoubleArray(n)
        for (k in 0 until n) b[k] = sin((k + 1) * PI * x.coerceIn(0.0, 1.0))
        return b
    }

    private fun beadEnergy(z: DoubleArray, vx: DoubleArray, vz: DoubleArray, mass: Double, gravity: Double): Double {
        var e = 0.0
        for (i in z.indices) e += 0.5 * mass * (vx[i] * vx[i] + vz[i] * vz[i]) + mass * gravity * max(z[i], 0.0)
        return e
    }

    private fun placeBeads(x: DoubleArray, seed: Stream, beads: Double) {
        val n = x.size
        if (n == 0) return
        val span = 0.62 + 0.20 * beads
        val left = (1.0 - span) * 0.5
        for (i in 0 until n) {
            val jitter = (seed.unit() - 0.5) * 0.15 / n
            x[i] = if (n == 1) 0.5 else left + span * i / (n - 1) + jitter
            x[i] = x[i].coerceIn(0.08, 0.92)
        }
    }

    /** A few beads at the bottom of the knob, a tray full at the top. Count is chosen once, at the start. */
    private fun beadCount(beads: Double): Int = (6 + (18 * beads).roundToInt()).coerceIn(6, 24)

    private fun faultTarget(phase: Int, depth: Double): Double = when (phase) {
        1 -> 1.0 - 0.75 * depth
        2 -> 1.0 - 0.25 * depth
        else -> 1.0
    }

    private fun tuneHead(bank: Modes.Bank, hz: Double, strike: Double, rate: Int) {
        val t60 = 0.48 + 0.25 * (1.0 - strike)
        for (i in HEAD.indices) {
            val ratio = HEAD[i].second
            val freq = min(hz * ratio, rate * 0.45)
            val decay = t60 / (1.0 + 0.55 * (ratio - 0.6))
            bank.tune(i, freq.coerceAtLeast(25.0), decay.coerceAtLeast(0.05))
        }
    }

    private fun tuneBody(bank: Modes.Bank, hz: Double, rate: Int) {
        val base = max(70.0, hz * 0.95)
        val ratios = doubleArrayOf(0.72, 1.15, 1.70)
        val t60 = doubleArrayOf(0.34, 0.22, 0.14)
        for (i in ratios.indices) bank.tune(i, min(base * ratios[i], rate * 0.45), t60[i])
    }

    private fun tuneTray(bank: Modes.Bank, hz: Double, rate: Int) {
        val base = max(90.0, hz * 2.1)
        val ratios = doubleArrayOf(1.0, 1.47, 2.15, 2.90)
        for (i in ratios.indices) {
            bank.tune(i, min(base * ratios[i], rate * 0.45), 0.16 / (1.0 + 0.25 * i))
        }
    }

    /**
     * Fixed strikers, fixed total energy. The weights' squares sum to 1, so adding a drummer
     * spreads the blow instead of stacking another full hit on top of it.
     */
    internal fun ensembleWeights(ensemble: Double): DoubleArray {
        val e = ensemble.coerceIn(0.0, 1.0)
        fun smooth(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
        val w = doubleArrayOf(
            1.0,
            smooth((e - 0.08) / 0.30),
            smooth((e - 0.36) / 0.28),
            smooth((e - 0.62) / 0.30),
        )
        var sum = 0.0
        for (v in w) sum += v * v
        val n = 1.0 / sqrt(sum)
        for (i in w.indices) w[i] *= n
        return w
    }

    private fun pulseSamples(strike: Double, rate: Int): Int {
        val seconds = 0.0062 - 0.0050 * strike
        return max(8, (seconds * rate).toInt())
    }

    /** Raised cosine whose samples, times the period, sum to [impulse]. A little seeded grit rides on a hard hit. */
    private fun pulse(n: Int, impulse: Double, rate: Int, strike: Double, grit: Stream): DoubleArray {
        val w = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            w[i] = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1).coerceAtLeast(1)))
            sum += w[i]
        }
        val rough = 0.04 + 0.20 * strike
        for (i in 0 until n) {
            val noise = (grit.unit() - 0.5) * 2.0 * rough
            w[i] = impulse * (w[i] / sum) * rate * (1.0 + noise)
        }
        return w
    }

    /**
     * Where striker [index] meets the head. A soft strike blurs toward a broad, low-order shape;
     * a hard one keeps the point. The vector is scaled so its squares sum to 1.
     * Angular shapes stand in for Bessel values: m = 0 is centre-weighted, m > 0 is quiet at the centre.
     */
    private fun participation(index: Int, strike: Double): DoubleArray {
        val r = STRIKER_R[index]
        val th = STRIKER_TH[index]
        val footprint = 1.0 - strike
        val b = DoubleArray(HEAD_MODES)
        for (i in HEAD.indices) {
            val m = HEAD[i].first
            val point = if (m == 0) cos(PI * r * 0.5) else sin(m * PI * r) * cos(m * th)
            val broad = if (m == 0) 0.7 else 0.12 * cos(th)
            val bright = if (HEAD[i].second > 1.4) 0.20 + 0.80 * strike else 1.0
            val sub = if (HEAD[i].second < 0.8) 0.12 else 1.0
            b[i] = (point * (1.0 - footprint) + broad * footprint) * bright * sub
        }
        var sum = 0.0
        for (v in b) sum += v * v
        if (sum < 1e-8) {
            b[0] = 1.0
            return b
        }
        val n = 1.0 / sqrt(sum)
        for (i in b.indices) b[i] *= n
        return b
    }

    /** Fade a partial that would sit past the band the loop was listened in, instead of pinning it to one ceiling. */
    private fun bandGain(hz: Double): Double = when {
        hz < 30.0 || hz > 8_000.0 -> 0.0
        hz > 6_000.0 -> (8_000.0 - hz) / 2_000.0
        else -> 1.0
    }

    /**
     * The last 1 280 output frames of a HOLD render are blended onto the same stretch of the
     * preroll, and the final 256 of those are a copy. [Keys.seamError] compares exactly those
     * 256 frames. The drive is already periodic (a passive hold measures under 1e-7); the blend
     * is what closes the join once the powered fuzz, a filter with its own memory, is in the mix.
     * Where the two stretches already match, the blend changes nothing.
     */
    private fun wrapCrossfade(s: FloatArray, loopStart: Int) {
        val fade = 1024
        val seam = 256
        val need = fade + seam
        if (loopStart < need || s.size - loopStart < need) return
        val end = s.size
        for (i in 0 until need) {
            val src = loopStart - need + i
            val dst = end - need + i
            val w = if (i >= fade) {
                1.0
            } else {
                val t = i / fade.toDouble()
                t * t * (3.0 - 2.0 * t)
            }
            s[dst] = ((1.0 - w) * s[dst] + w * s[src]).toFloat()
        }
    }

    private fun holdLoopFrames(hz: Double): Int {
        val k = max(8, (HOLD_LOOP_SECONDS * hz).roundToInt())
        return max(k, (k * RATE / hz).roundToInt())
    }

    /** A labelled deterministic unit stream. Not [kotlin.random.Random], and not shared across renders. */
    private class Stream(seed: Int) {
        private var s = seed xor 0x9E3779B9.toInt()
        fun unit(): Double {
            s = s * 1664525 + 1013904223
            return ((s ushr 8) and 0xFFFFFF) / 16777216.0
        }
    }
}
