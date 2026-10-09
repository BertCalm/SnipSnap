package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Excitation preferences of one complete six-cymbal instrument. */
enum class NimbusVoice { RING, SHIMMER, GATHER, THROAT, CONTACT, SUSPEND }

/**
 * Six same-root imaginary suspended metal plates, with passive traveling pressure ports.
 * The normalized modal coordinates are (omega*q, velocity). Free motion is an exact damped
 * rotation and every acoustic junction is orthogonal: neither retuning, geometric loading,
 * nor a stronger field can manufacture modal energy. Delay interpolation and loss contract
 * the waves. The suspension's bounded vibration disturbance is paid for by modal energy;
 * ordinary restoration is a damped positive spring, not a sustain supply.
 *
 * These ratios, the chamber and suspension mappings are sound-design choices, not measured
 * cymbals or a hardware levitation simulation. All six principal modes have the requested
 * frequency, without static detuning or octave displacement. HOLD explicitly powers the
 * modes, settles the complete state, and returns a loop-only region after the gathering attack.
 */
object Nimbus {
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = .99f
    const val MAX_SECONDS = 8f
    private const val CYMBALS = 6
    private const val MODES = 16
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val DEFAULT_CONTROL_STRIDE = 128
    private const val SNAPSHOT_STRIDE = 4096
    private const val HOLD_PREROLL_CYCLES = 6
    private const val OUTPUT_TRIM = .34
    private const val MASS = .08
    private const val RIM_SCALE = 6.0
    private const val CONTACT_STIFFNESS = 850.0
    private const val CONTACT_CAP = .003
    // Musical limits for the imaginary field-mediated body release, not measured magnetics.
    private const val BODY_ONSET_SECONDS = .90
    private const val BODY_RELEASES_PER_PLATE = 2

    private data class Shape(
        val excite: Float, val spacing: Float, val height: Float, val field: Float, val funnel: Float,
        val selected: Int, val decay: Double,
        val rootShare: Double, val spectralTilt: Double, val upperDecay: Double,
        val releaseStrength: Double, val pullMillis: Double, val releases: Int,
    )
    private fun shape(voice: NimbusVoice) = when (voice) {
        NimbusVoice.RING -> Shape(.50f, .70f, .65f, .55f, .35f, 0, 1.0, .38, .15, 1.10, 1.05, .42, 1)
        NimbusVoice.SHIMMER -> Shape(.35f, .50f, .70f, .45f, .50f, 2, .74, .14, .95, .85, 1.05, .95, 3)
        NimbusVoice.GATHER -> Shape(.65f, .35f, .50f, .35f, .55f, 4, .90, .23, .50, 1.40, .88, .74, 2)
        NimbusVoice.THROAT -> Shape(.45f, .45f, .15f, .50f, .85f, 3, .69, .24, -2.00, 1.50, 1.45, .65, 1)
        NimbusVoice.CONTACT -> Shape(.65f, .10f, .50f, .55f, .55f, 5, .65, .17, .90, .80, 1.06, .34, 4)
        NimbusVoice.SUSPEND -> Shape(.30f, .55f, .55f, .65f, .65f, 1, 1.20, .22, -.10, 1.65, .72, 2.60, 2)
    }
    fun macrosFor(voice: NimbusVoice): List<MacroSpec> {
        val s = shape(voice)
        return listOf(
            MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("EXCITE", s.excite, neutral = .40f),
            MacroSpec("SPACING", s.spacing, neutral = .45f),
            MacroSpec("HEIGHT", s.height, neutral = .50f),
            MacroSpec("FIELD", s.field, neutral = .40f),
            MacroSpec("FUNNEL", s.funnel, neutral = .40f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }
    fun defaults(voice: NimbusVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun selectedCymbalFor(voice: NimbusVoice): Int = shape(voice).selected
    internal fun settled(voice: NimbusVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, default) -> (macros[key]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }
    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: NimbusVoice): Int = ROOT_MIDI
    fun midiFor(voice: NimbusVoice, tune: Float): Int = rootMidi(voice) + ((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: NimbusVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: NimbusVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(macros["HOLD"] ?: 0f)) DrumClass.LOOP else DrumClass.TONAL
    fun scramble(voice: NimbusVoice, random: Random = Random.Default): Map<String, Float> =
        defaults(voice).mapValues { (key, _) -> if (key == "HOLD") 0f else random.nextFloat() }
    fun render(voice: NimbusVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip =
        Snip(probe(voice, macros, velocity, ProbeOptions(normalize = true, recordDiagnostics = false)).samples, channels = 1, sampleRate = RATE)
    internal fun renderLoop(voice: NimbusVoice, macros: Map<String, Float>, cancelled: () -> Boolean = { false }): FloatArray =
        probe(voice, macros + ("HOLD" to 1f), options = ProbeOptions(normalize = true, recordDiagnostics = false), cancelled = cancelled).samples

    internal data class ProbeOptions(
        val normalize: Boolean = false,
        val couplingEnabled: Boolean = true,
        val funnelEnabled: Boolean = true,
        val contactsEnabled: Boolean = true,
        val primaryStrikeEnabled: Boolean = true,
        val selectedCymbal: Int? = null,
        val durationSeconds: Float? = null,
        val seedContext: Int = 0,
        val recordDiagnostics: Boolean = true,
        val controlStride: Int = DEFAULT_CONTROL_STRIDE,
        val poweredDriveEnabled: Boolean = true,
        val snapshotStride: Int = SNAPSHOT_STRIDE,
    )
    internal data class StateSnapshot(
        val timeSeconds: Float,
        val heights: FloatArray,
        val restingHeights: FloatArray,
        val velocities: FloatArray,
        val modalEnergy: Double,
        val passiveEnergy: Double,
        val mechanicalEnergy: Double,
        val controllerWork: Double,
        val contactPenetration: Float,
        val geometryValid: Boolean,
        val plateEnergy: DoubleArray = DoubleArray(0),
        val plateUpperEnergy: DoubleArray = DoubleArray(0),
        val bodyReservoirEnergy: DoubleArray = DoubleArray(0),
    )
    internal data class BodyTransferEvent(
        val timeSeconds: Float,
        val cymbal: Int,
        val source: Int,
        val reservedEnergy: Double,
        val injectedEnergy: Double,
        val gathering: Boolean,
        val gap: Float,
    )
    internal data class Probe(
        val samples: FloatArray,
        val cymbals: Array<FloatArray>,
        val funnel: FloatArray,
        val contact: FloatArray,
        val returns: FloatArray,
        val snapshots: List<StateSnapshot>,
        val rawPeak: Float,
        val initialEnergy: Double,
        val finalPassiveEnergy: Double,
        val rootHz: Float,
        val modeRatios: Array<FloatArray>,
        val peakContactPenetration: Float,
        val contactEvents: Int,
        val poweredDriveWork: Double,
        val previousCycle: FloatArray = FloatArray(0),
        val seamError: Double = 0.0,
        val sampleRate: Int = RATE,
        val loopStartFrame: Int = 0,
        val selectedCymbal: Int = 0,
        val bodyTransfers: List<BodyTransferEvent> = emptyList(),
    )

    // Body, Bell, Paper, Dark, Flex, Wire. These are persistent identities, not voice enums.
    private val RATIOS = arrayOf(
        doubleArrayOf(1.0, 1.68, 2.40, 3.21, 4.30, 4.63, 5.39, 6.70, 7.08, 7.85, 9.15, 9.67, 10.52, 11.43, 12.40, 14.08),
        doubleArrayOf(1.0, 1.91, 3.10, 3.63, 4.45, 5.20, 5.77, 6.89, 8.10, 8.47, 9.68, 10.34, 11.65, 12.16, 13.57, 15.30),
        doubleArrayOf(1.0, 1.77, 2.73, 3.47, 4.07, 4.86, 5.21, 5.83, 6.44, 7.37, 8.18, 8.79, 9.53, 10.48, 12.03, 14.75),
        doubleArrayOf(1.0, 1.54, 2.18, 2.79, 3.32, 3.83, 4.48, 5.09, 5.76, 6.29, 7.15, 8.38, 9.06, 9.82, 10.43, 11.18),
        doubleArrayOf(1.0, 1.83, 2.57, 3.38, 4.09, 4.65, 5.23, 6.02, 7.08, 7.62, 8.27, 9.11, 10.13, 11.29, 12.42, 13.86),
        doubleArrayOf(1.0, 2.06, 3.47, 4.12, 4.79, 6.19, 6.47, 7.71, 8.62, 9.79, 10.17, 11.58, 13.21, 14.09, 15.47, 17.37),
    )
    // Excitation participation is separate from listener radiation: do not square these weights.
    private val WEIGHTS = arrayOf(
        doubleArrayOf(1.0, .86, .75, .63, .67, .55, .50, .48, .39, .43, .36, .32, .29, .27, .25, .20),
        doubleArrayOf(.90, .50, .85, .46, .62, .76, .41, .61, .70, .38, .58, .34, .55, .31, .40, .33),
        doubleArrayOf(.70, .54, .61, .74, .67, .81, .76, .69, .82, .75, .65, .72, .62, .68, .56, .49),
        doubleArrayOf(.94, .82, .86, .77, .75, .72, .59, .51, .46, .41, .30, .23, .19, .15, .12, .09),
        doubleArrayOf(.88, .68, .78, .70, .64, .73, .62, .60, .67, .54, .56, .51, .44, .40, .35, .29),
        doubleArrayOf(.76, .42, .87, .30, .44, .79, .54, .35, .49, .76, .40, .62, .68, .31, .59, .43),
    )
    private val ROOT_DECAY = doubleArrayOf(4.4, 3.4, 2.9, 3.6, 4.0, 3.1)
    private val UPPER_DECAY = doubleArrayOf(3.4, 2.8, 3.1, 2.6, 3.5, 2.5)
    private val LOSS_EXP = doubleArrayOf(.16, .20, .08, .34, .13, .12)

    internal fun probe(
        voice: NimbusVoice,
        macros: Map<String, Float> = emptyMap(),
        energy: Float = 1f,
        options: ProbeOptions = ProbeOptions(),
        cancelled: () -> Boolean = { false },
    ): Probe {
        val m = settled(voice, macros)
        val velocity = (energy.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
        val held = isLoop(m.getValue("HOLD"))
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        // An integer number of principal periods; quantization is under .03 cents here.
        val cycleFrames = ((hz * 1.35).roundToInt() * RATE / hz).roundToInt().coerceAtLeast(1024)
        val root = if (held) (hz * 1.35).roundToInt() * RATE.toDouble() / cycleFrames else hz
        val cycle = if (held) cycleFrames * Dsp.OVERSAMPLE else 0
        val padding = if (held) 256 * Dsp.OVERSAMPLE else 0
        val start = if (held) HOLD_PREROLL_CYCLES * cycle - padding else 0
        val frames = if (held) (HOLD_PREROLL_CYCLES + 2) * cycle + padding else
            (((options.durationSeconds?.takeIf { it.isFinite() } ?: (5.0f + 1.4f * m.getValue("FIELD") + .5f * m.getValue("FUNNEL") + 1.7f * m.getValue("HOLD"))).coerceIn(.10f, MAX_SECONDS)) * INTERNAL_RATE).roundToInt()
        val seed = Dsp.seedFor("NIMBUS", voice, frequencyFor(voice, m.getValue("TUNE")), velocity, options.seedContext)
        val simulation = Simulation(voice, m, velocity.toDouble(), options, root, seed, cycle, cancelled)
        val result = simulation.run(frames, start)
        val taps = result.taps.map { if (it.isEmpty()) it else Dsp.decimate(it, RATE) }
        val pad = padding / Dsp.OVERSAMPLE
        val previous = if (held) taps[0].copyOfRange(pad, pad + cycleFrames) else FloatArray(0)
        val audio = if (held) taps[0].copyOfRange(pad + cycleFrames, pad + 2 * cycleFrames) else taps[0]
        val rawPeak = audio.maxOfOrNull { abs(it) } ?: 0f
        if (options.normalize) {
            Dsp.levelTo(audio, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val gain = if (rawPeak > 1e-12f) (audio.maxOfOrNull { abs(it) } ?: 0f) / rawPeak else 1f
            for (i in previous.indices) previous[i] *= gain
        }
        if (!held) {
            Dsp.fadeTail(audio, ms = 8f)
            for (i in 1 until taps.size) Dsp.fadeTail(taps[i], ms = 8f)
        }
        val seam = if (held && previous.any { it != 0f }) Keys.seamError(previous + audio, previous.size) else 0.0
        fun tap(i: Int): FloatArray = if (taps[i].isEmpty()) taps[i] else if (held) taps[i].copyOfRange(pad + cycleFrames, pad + 2 * cycleFrames) else taps[i]
        val offset = if (held) (HOLD_PREROLL_CYCLES + 1) * cycle.toFloat() / INTERNAL_RATE else 0f
        val snapshots = if (held) result.snapshots.filter { it.timeSeconds >= offset && it.timeSeconds < offset + cycle.toFloat() / INTERNAL_RATE }
            .map { it.copy(timeSeconds = it.timeSeconds - offset) } else result.snapshots
        return Probe(audio, Array(CYMBALS) { tap(it + 1) }, tap(7), tap(8), tap(9), snapshots,
            rawPeak, result.initialEnergy, simulation.passiveEnergy(), root.toFloat(),
            Array(CYMBALS) { RATIOS[it].map(Double::toFloat).toFloatArray() }, result.peakPenetration,
            result.contacts, simulation.driveWork, previous, seam,
            selectedCymbal = (options.selectedCymbal ?: shape(voice).selected).coerceIn(0, CYMBALS - 1),
            bodyTransfers = if (held) simulation.bodyTransfers.filter { it.timeSeconds >= offset && it.timeSeconds < offset + cycle.toFloat() / INTERNAL_RATE }
                .map { it.copy(timeSeconds = it.timeSeconds - offset) } else simulation.bodyTransfers.toList())
    }

    /** Exact energy-normalized free rotation; a finite impulse is a velocity increment. */
    private class Bank(val size: Int) {
        val x = DoubleArray(size)
        val v = DoubleArray(size)
        val w = DoubleArray(size)
        private val cr = DoubleArray(size)
        private val sr = DoubleArray(size)
        fun tune(i: Int, hz: Double, t60: Double) {
            w[i] = 2 * PI * hz
            val radius = exp(-6.907755278982137 / (t60 * INTERNAL_RATE))
            cr[i] = radius * cos(w[i] / INTERNAL_RATE)
            sr[i] = radius * sin(w[i] / INTERNAL_RATE)
        }
        fun step() {
            for (i in 0 until size) {
                val a = x[i]
                x[i] = cr[i] * a + sr[i] * v[i]
                v[i] = cr[i] * v[i] - sr[i] * a
                if (!x[i].isFinite() || !v[i].isFinite()) { x[i] = 0.0; v[i] = 0.0 }
                if (abs(x[i]) + abs(v[i]) < 1e-25) { x[i] = 0.0; v[i] = 0.0 }
            }
        }
        fun velocity(b: DoubleArray): Double { var value = 0.0; for (i in 0 until size) value += b[i] * v[i]; return value }
        fun displacement(b: DoubleArray): Double { var value = 0.0; for (i in 0 until size) value += b[i] * x[i] / w[i]; return value }
        fun addVelocity(b: DoubleArray, delta: Double) { for (i in 0 until size) v[i] += b[i] * delta }
        fun energy(): Double { var e = 0.0; for (i in 0 until size) e += .5 * (x[i] * x[i] + v[i] * v[i]); return e }
        fun upperEnergy(): Double { var e = 0.0; for (i in 1 until size) e += .5 * (x[i] * x[i] + v[i] * v[i]); return e }
        fun deplete(amount: Double): Double {
            val e = energy()
            val taken = minOf(e, max(0.0, amount))
            if (e > 0.0) { val g = sqrt(max(0.0, 1 - taken / e)); for (i in 0 until size) { x[i] *= g; v[i] *= g } }
            return taken
        }
    }
    /** Convex fractional interpolation and an attenuated, finite traveling-wave store. */
    private class Delay(seconds: Double, private val loss: Double) {
        private val frames = seconds * INTERNAL_RATE
        private val whole = frames.toInt().coerceAtLeast(1)
        private val fraction = frames - frames.toInt()
        private val values = DoubleArray(ceil(frames).toInt() + 2)
        private var write = 0
        private var squares = 0.0
        fun read(): Double {
            var a = write - whole; if (a < 0) a += values.size
            var b = a - 1; if (b < 0) b += values.size
            return loss * ((1 - fraction) * values[a] + fraction * values[b])
        }
        fun put(value: Double) {
            squares += value * value - values[write] * values[write]
            values[write] = value
            if (++write == values.size) write = 0
        }
        fun energy(): Double = .5 * max(0.0, squares)
    }
    private data class RunResult(
        val taps: Array<FloatArray>, val snapshots: List<StateSnapshot>, val initialEnergy: Double,
        val peakPenetration: Float, val contacts: Int,
    )

    private class Simulation(
        voice: NimbusVoice, private val m: Map<String, Float>, private val eventVelocity: Double,
        private val options: ProbeOptions, private val root: Double, seed: Int,
        private val cycle: Int, private val cancelled: () -> Boolean,
    ) {
        private val shape = shape(voice)
        private val excite = m.getValue("EXCITE").toDouble()
        private val spacing = m.getValue("SPACING").toDouble()
        private val height = m.getValue("HEIGHT").toDouble()
        private val field = m.getValue("FIELD").toDouble()
        private val funnel = if (options.funnelEnabled) m.getValue("FUNNEL").toDouble() else 0.0
        private val hold = m.getValue("HOLD").toDouble()
        private val finiteDriveSeconds = 1.0 + 3.1 * hold
        private val selected = (options.selectedCymbal ?: shape.selected).coerceIn(0, CYMBALS - 1)
        private val controlStride = options.controlStride.coerceIn(16, 512)
        private val random = Random(seed)
        private val plates = Array(CYMBALS) { Bank(MODES) }
        private val chamber = Bank(4)
        private val supported = Array(CYMBALS) { i -> BooleanArray(MODES) { j -> root * RATIOS[i][j] < RATE * .43 } }
        private val port = Array(CYMBALS) { i -> normalize(DoubleArray(MODES) { j ->
            if (!supported[i][j]) 0.0 else if (j == 0) .85 else .25 * sqrt(WEIGHTS[i][j]) * (.75 + .45 * excite)
        }) }
        private val pickup = Array(CYMBALS) { DoubleArray(MODES) }
        private val rootShare = (shape.rootShare * (1.10 - .35 * excite)).coerceIn(.10, .55)
        private val bodyImpulse = Array(CYMBALS) { i ->
            val b = normalize(DoubleArray(MODES) { j ->
                if (j == 0 || !supported[i][j]) 0.0 else {
                    val participation = WEIGHTS[i][j] * RATIOS[i][j].pow((shape.spectralTilt + .55 * excite - .20) * .36)
                    val sign = if (sin((j + 1) * 2.07 + i * .73) >= 0) 1.0 else -1.0
                    participation * sign * (.91 + .18 * random.nextDouble())
                }
            })
            for (j in b.indices) b[j] *= sqrt(1 - rootShare)
            b
        }
        // A separate root-free flexural port preserves each material's upper response.
        // It carries actual wave energy; receivers can store some of that energy before
        // spending it on their own finite body projection rather than on the donor's pitch.
        private val bodyPort = Array(CYMBALS) { normalize(bodyImpulse[it].copyOf()) }
        private val bodyWaves = Array(10) { p ->
            Delay(.012 + .023 * spacing + .0011 * (p / 2), .945 - .015 * spacing)
        }
        private val bodyReservoir = DoubleArray(CYMBALS)
        private val bodyPending = DoubleArray(CYMBALS)
        private val bodyChargeFrame = IntArray(CYMBALS) { -1 }
        private val bodyReleaseAge = IntArray(CYMBALS) { -1 }
        private val bodyReleaseAmplitude = DoubleArray(CYMBALS)
        private val bodyArrivalSign = DoubleArray(CYMBALS) { 1.0 }
        private val bodyReleaseSign = DoubleArray(CYMBALS) { 1.0 }
        private val bodyChargeThreshold = .00010 * eventVelocity * eventVelocity
        private val bodySource = IntArray(CYMBALS) { -1 }
        private val bodySourcePeak = DoubleArray(CYMBALS)
        private val bodyReleaseCount = IntArray(CYMBALS)
        private val bodyEventIndex = IntArray(CYMBALS) { -1 }
        val bodyTransfers = ArrayList<BodyTransferEvent>()
        private val bodyLeak = exp(-1.0 / (.11 * INTERNAL_RATE))
        private val bodyBurstFrames = ((.000045 + .00005 * (1 - excite)) * INTERNAL_RATE).roundToInt().coerceAtLeast(8)
        private val chamberPort = Array(CYMBALS) { i -> normalize(DoubleArray(4) { j -> .36 + .60 * abs(sin((i + 1) * (j + 1) * .73)) }) }
        private val gap = .032 + .104 * spacing * spacing * spacing
        private val restSpan = 5 * gap
        private val center = .055 + restSpan / 2 + height * (.89 - restSpan)
        private val resting = DoubleArray(CYMBALS) { center + (it - 2.5) * gap }
        private val z = resting.copyOf()
        private val velocity = DoubleArray(CYMBALS)
        private val displacementBound = minOf(.029, gap * .225)
        private val controllerOmega = 5.0 + 8.0 * field
        private val controllerDamping = .64 + .38 * field
        private val smoothedEnergy = DoubleArray(CYMBALS)
        private var controllerWork = 0.0
        private var disturbanceBudget = if (options.primaryStrikeEnabled) .028 * eventVelocity * eventVelocity else 0.0
        private val neighbors = Array(10) { p -> Delay(.00075 + .0080 * spacing + .00017 * (p / 2), .989 - .032 * spacing) }
        private val early = Array(12) { p ->
            val i = p / 2
            // Fixed source-specific path lengths per note: moving loading changes the returns
            // without reading a delay sample twice or introducing uncontrolled Doppler energy.
            Delay(.003 + .006 * funnel + .012 * (1 - resting[i]) + .00031 * i, .78 + .13 * funnel)
        }
        private val rates = Array(CYMBALS) { i -> DoubleArray(MODES) { j ->
            val hz = root * RATIOS[i][j]
            if (cycle > 0) ((hz * cycle / INTERNAL_RATE).roundToInt() * INTERNAL_RATE.toDouble() / cycle) else hz
        } }
        private val lossTimes = Array(CYMBALS) { i -> DoubleArray(MODES) { j ->
            if (j == 0) ROOT_DECAY[i] * shape.decay else
                (UPPER_DECAY[i] * shape.upperDecay / RATIOS[i][j].pow(LOSS_EXP[i])).coerceAtMost(4.5)
        } }
        private val driveX = Array(CYMBALS) { DoubleArray(MODES) }
        private val driveY = Array(CYMBALS) { DoubleArray(MODES) }
        private val driveCos = Array(CYMBALS) { i -> DoubleArray(MODES) { j -> cos(2 * PI * rates[i][j] / INTERNAL_RATE) } }
        private val driveSin = Array(CYMBALS) { i -> DoubleArray(MODES) { j -> sin(2 * PI * rates[i][j] / INTERNAL_RATE) } }
        private val drivePhase = Array(CYMBALS) { i -> DoubleArray(MODES) { j -> if (j == 0) (if (i % 2 == 0) -.90 else .90) + .03 * random.nextDouble() else 2 * PI * random.nextDouble() } }
        private val driveAmplitude = Array(CYMBALS) { i -> DoubleArray(MODES) { j ->
            val preference = .30 + .70 * exp(-abs(i - selected) * (.58 + .32 * excite))
            val target = if (j == 0) (if (selected == 5) .26 else .12) * sqrt(rootShare) * preference else
                (.115 + .035 * funnel) * bodyImpulse[i][j] * shape.releaseStrength * preference
            2 * 6.907755278982137 / lossTimes[i][j] / INTERNAL_RATE * target
        } }
        var driveWork = 0.0; private set
        private val incomingNeighbors = DoubleArray(10)
        private val outgoingNeighbors = DoubleArray(10)
        private val incomingBody = DoubleArray(10)
        private val outgoingBody = DoubleArray(10)
        private val bodyCos = DoubleArray(5)
        private val bodySin = DoubleArray(5)
        private val donorBodyCos = DoubleArray(5)
        private val donorBodySin = DoubleArray(5)
        private var bodyWindow = true
        private val incomingEarly = DoubleArray(12)
        private val outgoingEarly = DoubleArray(12)
        private val neighborCos = DoubleArray(5)
        private val neighborSin = DoubleArray(5)
        private val funnelCos = DoubleArray(6)
        private val funnelSin = DoubleArray(6)
        private val chamberCos = DoubleArray(6)
        private val chamberSin = DoubleArray(6)
        private val contactActive = BooleanArray(5)
        private var contactEnergy = 0.0
        private val pulseFrames = ((.00015 + .001 * shape.pullMillis * (1 - .50 * excite)) * INTERNAL_RATE).roundToInt().coerceAtLeast(12)
        private val releaseFrames = ((.000035 + .00010 * (1 - excite)) * INTERNAL_RATE).roundToInt().coerceAtLeast(6)
        private val releaseStart = (.00018 * shape.pullMillis * (1 - .60 * excite) * INTERNAL_RATE).roundToInt()
        private val releaseInterval = (.00017 * INTERNAL_RATE).roundToInt()
        private val eventFrames = max(pulseFrames, releaseStart + (shape.releases - 1) * releaseInterval + releaseFrames)
        private var eventWork = 0.0
        private var initialEnergy = 0.0
        private var maxPenetration = 0f
        private var currentPenetration = 0f
        private var contacts = 0
        private var lastContact = 0.0
        init {
            load()
            val ratios = doubleArrayOf(.73, 1.57, 2.91, 4.43)
            for (j in 0 until 4) chamber.tune(j, root * ratios[j] * (.91 + .17 * funnel), .16 + .46 * funnel + .11 * (3 - j))
            resetDrive()
        }
        private fun resetDrive() {
            for (i in 0 until CYMBALS) for (j in 0 until MODES) {
                driveX[i][j] = cos(drivePhase[i][j]); driveY[i][j] = sin(drivePhase[i][j])
            }
        }
        private fun load() {
            for (i in 0 until CYMBALS - 1) {
                val separation = ((z[i + 1] - z[i]) / gap).coerceIn(.50, 1.5)
                val coupling = (1.7 + 5.8 * (1 - spacing).pow(2.0)) * (.56 + .64 * field) /
                    (separation * (1 + .5 * spacing))
                val angle = sqrt(coupling / INTERNAL_RATE)
                neighborCos[i] = cos(angle); neighborSin[i] = sin(angle)
                // Close, yielding stacks exchange body energy quickly; spread plates take
                // longer to charge and give it back. This uses the actual suspension gap.
                val bodyCoupling = if (bodyWindow) (130.0 + 340.0 * (1 - spacing)) *
                    (.65 + .35 * field) / separation else 0.0
                val bodyAngle = sqrt(bodyCoupling / INTERNAL_RATE)
                bodyCos[i] = cos(bodyAngle); bodySin[i] = sin(bodyAngle)
                // The struck Flex face is more susceptible to the field: it donates more
                // of its existing upper energy through the same passive wave junction.
                val donorAngle = bodyAngle * (if (selected == 4) sqrt(1.40) else 1.0)
                donorBodyCos[i] = cos(donorAngle); donorBodySin[i] = sin(donorAngle)
            }
            for (i in 0 until CYMBALS) {
                val enclosure = funnel * (1 - z[i])
                val loading = (.48 + 2.8 * enclosure) * (1 + .28 * (1 - spacing) * funnel)
                val angle = sqrt(loading / INTERNAL_RATE)
                funnelCos[i] = cos(angle); funnelSin[i] = sin(angle)
                chamberCos[i] = cos(angle * .83); chamberSin[i] = sin(angle * .83)
                val moving = abs(z[i] - resting[i]) / displacementBound
                for (j in 0 until MODES) {
                    // Static chamber loading changes losses, not note. Only Flex yields a tiny
                    // bounded transient stiffness shift; exact rotations still preserve energy.
                    val flex = if (i == 4) 1 + moving * (if (j == 0) .0005 else .008 + .004 * sin(j * .91)) else 1.0
                    // Enclosure remains dissipative, but its growing upper loss is gentle
                    // enough to preserve recipient bodies after the finite field transfer.
                    val t60 = lossTimes[i][j] / (1 + enclosure * (.25 + .25 * ln(RATIOS[i][j])) + moving * .045)
                    // Mouth exposure radiates more upper flexural modes. This is independent
                    // of overall level, while enclosure also changes the actual modal losses.
                    pickup[i][j] = if (!supported[i][j]) 0.0 else if (j == 0) .76 else
                        (.57 + .10 * sin(j * 1.13 + i * .63)) * (.52 + 1.10 * z[i]) *
                            (if (i == 4) 1 + .55 * moving * sin(j * 1.33) else 1.0)
                    plates[i].tune(j, rates[i][j] * flex, t60)
                }
            }
        }
        private fun mechanicalEnergy(): Double {
            var e = 0.0
            for (i in z.indices) {
                val displacement = z[i] - resting[i]
                e += .5 * MASS * (velocity[i] * velocity[i] + controllerOmega * controllerOmega * displacement * displacement)
            }
            return e
        }
        fun passiveEnergy(): Double = plates.sumOf { it.energy() } + chamber.energy() +
            (if (options.couplingEnabled) neighbors.sumOf { it.energy() } + bodyWaves.sumOf { it.energy() } else 0.0) +
            bodyReservoir.sum() + bodyPending.sum() +
            (if (options.funnelEnabled) early.sumOf { it.energy() } else 0.0) + mechanicalEnergy() + contactEnergy
        private fun depleteModal(amount: Double): Double {
            val total = plates.sumOf { it.energy() }
            if (total < 1e-24 || amount <= 0) return 0.0
            var taken = 0.0
            for (plate in plates) taken += plate.deplete(amount * plate.energy() / total)
            return taken
        }
        private fun control() {
            val dt = controlStride.toDouble() / INTERNAL_RATE
            val envelope = exp(-dt / .037)
            for (i in 0 until CYMBALS) {
                smoothedEnergy[i] = envelope * smoothedEnergy[i] + (1 - envelope) * plates[i].energy()
                val radial = (i - 2.5) / 2.5
                val disturbance = displacementBound * (.65 + .28 * excite) * (1 - .54 * field) * radial *
                    (smoothedEnergy[i] / (.018 + smoothedEnergy[i]))
                val spring = -MASS * controllerOmega * controllerOmega * (z[i] - resting[i])
                val damping = -2 * MASS * controllerDamping * controllerOmega * velocity[i]
                var forcing = MASS * controllerOmega * controllerOmega * disturbance
                val wanted = max(0.0, forcing * velocity[i] * dt + forcing * forcing * dt * dt / (2 * MASS))
                val budget = if (cycle > 0 && options.poweredDriveEnabled) .000015 * dt else disturbanceBudget
                val funded = minOf(wanted, budget)
                if (wanted > 1e-24) forcing *= sqrt(funded / wanted)
                val paid = depleteModal(funded)
                if (funded > 1e-24 && paid < funded) forcing *= sqrt(paid / funded)
                disturbanceBudget = max(0.0, disturbanceBudget - paid)
                controllerWork += paid
                velocity[i] += (spring + damping + forcing) / MASS * dt
            }
            load()
        }
        /** Slow forces run cheaply at control rate, but contact impulses and travel resolve at4x. */
        private fun advanceMotion() {
            for (i in z.indices) {
                z[i] += velocity[i] / INTERNAL_RATE
                val lower = resting[i] - displacementBound
                val upper = resting[i] + displacementBound
                if (z[i] < lower) { z[i] = lower; velocity[i] = max(0.0, velocity[i]) }
                if (z[i] > upper) { z[i] = upper; velocity[i] = minOf(0.0, velocity[i]) }
                if (!z[i].isFinite() || !velocity[i].isFinite()) { z[i] = resting[i]; velocity[i] = 0.0 }
            }
        }
        /** Orthogonal scattering exchanges one normalized modal velocity with one wave. */
        private fun scatter(bank: Bank, b: DoubleArray, wave: Double, c: Double, s: Double): Double {
            val v = bank.velocity(b)
            bank.addVelocity(b, (c - 1) * v + s * wave)
            return -s * v + c * wave
        }
        /** Split an arriving wave without gain: the missing wave energy charges the field. */
        private fun receiveBody(cymbal: Int, source: Int, wave: Double, frame: Int): Double {
            // The initially struck plate donates and reacts through ordinary wave ports;
            // only unstruck recipients turn stored field energy into native body answers.
            if (!bodyWindow || cymbal == selected || bodyReleaseCount[cymbal] >= BODY_RELEASES_PER_PLATE) return wave
            val absorbed = .44
            val deposit = .5 * absorbed * wave * wave
            bodyReservoir[cymbal] += deposit
            if (bodyChargeFrame[cymbal] < 0 && bodyReservoir[cymbal] > max(1e-9, bodyChargeThreshold)) bodyChargeFrame[cymbal] = frame
            if (abs(wave) > bodySourcePeak[cymbal]) {
                bodySourcePeak[cymbal] = abs(wave)
                bodySource[cymbal] = source
                bodyArrivalSign[cymbal] = if (wave < 0) -1.0 else 1.0
            }
            return sqrt(1 - absorbed) * wave
        }
        /**
         * Only energy that arrived through a body wave can pay for a recipient flex release.
         * A bounded charging interval follows the real gap and returning suspension motion.
         * Positive velocity-increment work is paid exactly; negative work is dissipated.
         */
        private fun releaseBodies(frame: Int) {
            for (i in 0 until CYMBALS) {
                bodyReservoir[i] *= bodyLeak
                if (!bodyWindow) bodyChargeFrame[i] = -1
                if (bodyWindow && bodyChargeFrame[i] >= 0 && bodyReleaseAge[i] < 0 &&
                    bodyReleaseCount[i] < BODY_RELEASES_PER_PLATE) {
                    val source = bodySource[i].coerceIn(0, CYMBALS - 1)
                    val gapNow = if (source == i) gap else abs(z[i] - z[source])
                    val separation = (gapNow / gap).coerceIn(.5, 1.5)
                    val yielding = abs(z[i] - resting[i]) / displacementBound
                    val gathering = (z[i] - resting[i]) * velocity[i] < 0
                    val wait = (.014 + .032 * spacing) * separation * (1 + .45 * yielding) *
                        (if (gathering) .67 else 1.0) / (.82 + .35 * field)
                    if (frame - bodyChargeFrame[i] >= wait * INTERNAL_RATE && bodyReservoir[i] > 1e-8) {
                        val reserved = bodyReservoir[i] * .88
                        bodyReservoir[i] -= reserved
                        bodyPending[i] = reserved
                        bodyReleaseAmplitude[i] = sqrt(2 * reserved)
                        // Stored energy has no retained carrier phase. Let a flex release
                        // accelerate the recipient's own motion, rather than canceling its
                        // previous native ring because a donor wave happened to oppose it.
                        val recipientVelocity = plates[i].velocity(bodyPort[i])
                        bodyReleaseSign[i] = if (abs(recipientVelocity) > 1e-9) {
                            if (recipientVelocity < 0) -1.0 else 1.0
                        } else bodyArrivalSign[i]
                        bodyReleaseAge[i] = 0
                        bodyReleaseCount[i]++
                        bodyChargeFrame[i] = -1
                        bodySourcePeak[i] = 0.0
                        if (options.recordDiagnostics) {
                            bodyEventIndex[i] = bodyTransfers.size
                            bodyTransfers.add(BodyTransferEvent(frame.toFloat() / INTERNAL_RATE, i, source,
                                reserved, 0.0, gathering, gapNow.toFloat()))
                        }
                    }
                }
                val age = bodyReleaseAge[i]
                if (age >= 0) {
                    val pulse = sin(PI * (age + .5) / bodyBurstFrames) * PI / (2 * bodyBurstFrames)
                    val direction = bodyReleaseSign[i]
                    var amount = bodyReleaseAmplitude[i] * pulse
                    val projected = direction * plates[i].velocity(bodyPort[i])
                    val wanted = projected * amount + .5 * amount * amount
                    if (wanted > bodyPending[i]) {
                        amount = max(0.0, -projected + sqrt(projected * projected + 2 * bodyPending[i]))
                    }
                    val paid = max(0.0, projected * amount + .5 * amount * amount)
                    plates[i].addVelocity(bodyPort[i], direction * amount)
                    bodyPending[i] = max(0.0, bodyPending[i] - paid)
                    val index = bodyEventIndex[i]
                    if (index >= 0) {
                        val event = bodyTransfers[index]
                        bodyTransfers[index] = event.copy(injectedEnergy = event.injectedEnergy + paid)
                    }
                    bodyReleaseAge[i]++
                    if (bodyReleaseAge[i] >= bodyBurstFrames) {
                        bodyPending[i] = 0.0 // Unspent reserved work is a loss, never a fresh attack.
                        bodyReleaseAge[i] = -1
                        bodyEventIndex[i] = -1
                    }
                }
                if (bodyReservoir[i] < 1e-25) bodyReservoir[i] = 0.0
            }
        }
        private fun contacts(): Double {
            var audible = 0.0
            contactEnergy = 0.0
            currentPenetration = 0f
            if (!options.contactsEnabled || spacing > .28) { lastContact = 0.0; return 0.0 }
            for (i in 0 until CYMBALS - 1) {
                val a = plates[i]; val b = plates[i + 1]
                val gapNow = z[i + 1] - z[i] + RIM_SCALE * (b.displacement(port[i + 1]) - a.displacement(port[i]))
                val penetration = max(0.0, .0318 - gapNow)
                maxPenetration = max(maxPenetration, penetration.toFloat())
                currentPenetration = max(currentPenetration, penetration.toFloat())
                if (penetration > 1e-8) {
                    if (!contactActive[i]) contacts++
                    contactActive[i] = true
                    val relative = velocity[i + 1] - velocity[i] + RIM_SCALE * (b.velocity(port[i + 1]) - a.velocity(port[i]))
                    val restoring = CONTACT_STIFFNESS * penetration / (1 + penetration / CONTACT_CAP)
                    val damping = .35 * max(0.0, -relative) / (1 + abs(relative) / 2.0)
                    val force = restoring + damping
                    val kick = force / INTERNAL_RATE
                    a.addVelocity(port[i], -kick * RIM_SCALE)
                    b.addVelocity(port[i + 1], kick * RIM_SCALE)
                    velocity[i] -= kick / MASS
                    velocity[i + 1] += kick / MASS
                    contactEnergy += CONTACT_STIFFNESS * CONTACT_CAP * (penetration - CONTACT_CAP * ln(1 + penetration / CONTACT_CAP))
                    // Impact radiation is the actual relative rim speed under compliant pressure.
                    audible += .12 * force * relative
                } else contactActive[i] = false
            }
            lastContact = audible
            return audible
        }
        fun run(frames: Int, captureStart: Int): RunResult {
            val length = frames - captureStart
            val taps = Array(10) { i -> if (i == 0 || options.recordDiagnostics) FloatArray(length) else FloatArray(0) }
            val snapshots = ArrayList<StateSnapshot>()
            val snapshotStride = options.snapshotStride.coerceIn(1, 65536)
            val selectedStart = if (cycle > 0) captureStart + 256 * Dsp.OVERSAMPLE + cycle else 0
            val selectedEnd = if (cycle > 0) selectedStart + cycle else frames
            var selectedPeak = 0f
            var selectedContacts = 0
            if (options.recordDiagnostics) snapshots.add(snapshot(0))
            for (frame in 0 until frames) {
                if (frame % 8192 == 0 && cancelled()) throw CancellationException("NIMBUS render cancelled")
                if (bodyWindow && frame >= BODY_ONSET_SECONDS * INTERNAL_RATE) { bodyWindow = false; load() }
                if (frame % controlStride == 0) control()
                for (plate in plates) plate.step()
                chamber.step()
                if (options.primaryStrikeEnabled && frame < eventFrames && eventVelocity > 0) {
                    val rootPulse = if (frame < pulseFrames) sin(PI * (frame + .5) / pulseFrames) * PI / (2 * pulseFrames) else 0.0
                    // The selected plate is the only initial source. Every other native
                    // body response is funded by subsequent wave, chamber or rim interaction.
                    for (i in selected..selected) {
                        val strength = eventVelocity * (.79 + .26 * excite)
                        val before = plates[i].energy()
                        plates[i].v[0] += sqrt(rootShare) * strength * rootPulse
                        for (release in 0 until shape.releases) {
                            val age = frame - releaseStart - release * releaseInterval
                            if (age in 0 until releaseFrames) {
                                val pulse = sin(PI * (age + .5) / releaseFrames) * PI / (2 * releaseFrames)
                                // A few finite pull/release projections excite the same plate. The
                                // contact point moves across modes; no independent noise is mixed.
                                val gain = strength * shape.releaseStrength * pulse / sqrt(shape.releases.toDouble())
                                for (j in 1 until MODES) {
                                    val projection = if (release == 0) 1.0 else sin(j * .71 + release * 1.43).let { if (it >= 0) 1.0 else -1.0 }
                                    plates[i].v[j] += bodyImpulse[i][j] * gain * projection
                                }
                            }
                        }
                        // Positive work supplied by the finite note event; discarded work and
                        // passive loss do not become a reusable unlimited excitation budget.
                        eventWork += max(0.0, plates[i].energy() - before)
                    }
                }
                val time = frame.toDouble() / INTERNAL_RATE
                val driveEnvelope = if (cycle > 0) 1.0 else if (hold > 0.0 && time < finiteDriveSeconds) {
                    hold * minOf(1.0, time / .08) * minOf(1.0, (finiteDriveSeconds - time) / .20)
                } else 0.0
                if (driveEnvelope > 0.0 && options.poweredDriveEnabled) {
                    if (cycle > 0 && frame % cycle == 0) resetDrive()
                    for (i in 0 until CYMBALS) for (j in 0 until MODES) {
                        val old = driveX[i][j]
                        driveX[i][j] = driveCos[i][j] * old - driveSin[i][j] * driveY[i][j]
                        driveY[i][j] = driveSin[i][j] * old + driveCos[i][j] * driveY[i][j]
                        val dv = driveAmplitude[i][j] * driveX[i][j] * driveEnvelope
                        driveWork += plates[i].v[j] * dv + .5 * dv * dv
                        plates[i].v[j] += dv
                    }
                }
                var returnAudio = 0.0
                if (options.couplingEnabled) {
                    for (p in neighbors.indices) incomingNeighbors[p] = neighbors[p].read()
                    for (i in 0 until CYMBALS - 1) {
                        outgoingNeighbors[2 * i] = scatter(plates[i], port[i], incomingNeighbors[2 * i + 1], neighborCos[i], neighborSin[i])
                        outgoingNeighbors[2 * i + 1] = scatter(plates[i + 1], port[i + 1], incomingNeighbors[2 * i], neighborCos[i], neighborSin[i])
                        returnAudio += incomingNeighbors[2 * i] + incomingNeighbors[2 * i + 1]
                    }
                    for (p in neighbors.indices) neighbors[p].put(outgoingNeighbors[p])
                    for (p in bodyWaves.indices) incomingBody[p] = bodyWaves[p].read()
                    for (i in 0 until CYMBALS - 1) {
                        val towardLower = receiveBody(i, i + 1, incomingBody[2 * i + 1], frame)
                        val towardUpper = receiveBody(i + 1, i, incomingBody[2 * i], frame)
                        // A recipient's finite charging port disengages after its two
                        // funded answers, preserving its own native ring. The struck source
                        // keeps donating stored body energy throughout the onset window.
                        val lowerOpen = bodyWindow && (i == selected || bodyReleaseCount[i] < BODY_RELEASES_PER_PLATE)
                        val upperOpen = bodyWindow && (i + 1 == selected || bodyReleaseCount[i + 1] < BODY_RELEASES_PER_PLATE)
                        outgoingBody[2 * i] = if (lowerOpen) scatter(plates[i], bodyPort[i], towardLower,
                            if (i == selected) donorBodyCos[i] else bodyCos[i],
                            if (i == selected) donorBodySin[i] else bodySin[i]) else towardLower
                        outgoingBody[2 * i + 1] = if (upperOpen) scatter(plates[i + 1], bodyPort[i + 1], towardUpper,
                            if (i + 1 == selected) donorBodyCos[i] else bodyCos[i],
                            if (i + 1 == selected) donorBodySin[i] else bodySin[i]) else towardUpper
                    }
                    // A passive direction permutation lets the field packet pass through
                    // an interior plate instead of reflecting at every nearest-neighbor port.
                    // Both modal scatters above are still reciprocal orthogonal junctions.
                    for (i in 1 until CYMBALS - 1) {
                        val lower = 2 * (i - 1) + 1
                        val upper = 2 * i
                        val swap = outgoingBody[lower]
                        outgoingBody[lower] = outgoingBody[upper]
                        outgoingBody[upper] = swap
                    }
                    for (p in bodyWaves.indices) bodyWaves[p].put(outgoingBody[p])
                    releaseBodies(frame)
                }
                if (options.funnelEnabled) {
                    for (p in early.indices) incomingEarly[p] = early[p].read()
                    for (i in 0 until CYMBALS) {
                        outgoingEarly[2 * i] = scatter(plates[i], port[i], incomingEarly[2 * i + 1], funnelCos[i], funnelSin[i])
                        outgoingEarly[2 * i + 1] = scatter(chamber, chamberPort[i], incomingEarly[2 * i], chamberCos[i], chamberSin[i])
                        returnAudio += 1.8 * incomingEarly[2 * i + 1]
                    }
                    for (p in early.indices) early[p].put(outgoingEarly[p])
                }
                val countBefore = contacts
                val contactAudio = contacts()
                advanceMotion()
                if (frame >= selectedStart && frame < selectedEnd) {
                    selectedPeak = max(selectedPeak, currentPenetration)
                    selectedContacts += contacts - countBefore
                }
                if (frame == eventFrames - 1) initialEnergy = eventWork
                if (frame >= captureStart) {
                    val index = frame - captureStart
                    var metal = 0.0
                    for (i in 0 until CYMBALS) {
                        val radiation = .69 + .43 * z[i] + .16 * funnel * (1 - z[i]) * (1 + .11 * i)
                        val value = plates[i].velocity(pickup[i]) * radiation
                        metal += value
                        if (options.recordDiagnostics) taps[i + 1][index] = (value * OUTPUT_TRIM).toFloat()
                    }
                    var chamberAudio = 0.0
                    for (j in 0 until 4) chamberAudio += chamber.v[j] / (1 + .40 * j)
                    chamberAudio *= 1.65 + 1.3 * funnel
                    val returns = returnAudio * 2.8
                    taps[0][index] = ((metal + chamberAudio + contactAudio + returns) * OUTPUT_TRIM).toFloat()
                    if (options.recordDiagnostics) {
                        taps[7][index] = (chamberAudio * OUTPUT_TRIM).toFloat()
                        taps[8][index] = (contactAudio * OUTPUT_TRIM).toFloat()
                        taps[9][index] = (returns * OUTPUT_TRIM).toFloat()
                    }
                }
                if (options.recordDiagnostics && (frame % snapshotStride == snapshotStride - 1 || frame == frames - 1)) snapshots.add(snapshot(frame + 1))
            }
            return RunResult(taps, snapshots, initialEnergy, selectedPeak, selectedContacts)
        }
        private fun snapshot(frame: Int) = StateSnapshot(
            frame.toFloat() / INTERNAL_RATE, z.map(Double::toFloat).toFloatArray(), resting.map(Double::toFloat).toFloatArray(),
            velocity.map(Double::toFloat).toFloatArray(), plates.sumOf { it.energy() } + chamber.energy(), passiveEnergy(),
            mechanicalEnergy(), controllerWork, currentPenetration, z.all { it >= .01 && it <= .99 } &&
                (0 until CYMBALS - 1).all { z[it + 1] - z[it] >= .017 },
            DoubleArray(CYMBALS) { plates[it].energy() }, DoubleArray(CYMBALS) { plates[it].upperEnergy() },
            DoubleArray(CYMBALS) { bodyReservoir[it] + bodyPending[it] },
        )
    }
    private fun normalize(values: DoubleArray): DoubleArray {
        val norm = sqrt(values.sumOf { it * it }).coerceAtLeast(1e-12)
        for (i in values.indices) values[i] /= norm
        return values
    }
}
