package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

enum class TesseraVoice { WOOD, COURSE, TUBE, ANSWER, FOLDING, CHAMBER }

/**
 * One imaginary struck object: two four-mode bars, two courses of paired six-mode
 * stiff strings, eight tube modes and three frame modes. Strings use a harmonic
 * modal truncation rather than a delay-line string: all four strings have their own
 * loss/stiffness, but share the same contact, frame and returning acoustic ports.
 * These ratios and chamber rules are musical hypotheses, not measured instruments.
 *
 * Coordinates are x=omega*q and v. Free motion is an exact damped rotation. Frame
 * coupling is a reciprocal velocity exchange: the difference velocity contracts
 * while the common velocity is unchanged. This avoids a spring's tuning shift and
 * cannot supply energy. Acoustic ports are orthogonal rotations of one normalized
 * material velocity and one traveling wave. FOLD is also a sequence of orthogonal
 * rotations, so changing a route never replaces a ringing delay or amplifies it.
 *
 * Fractional moving delays use a traveling-energy ledger. Convex interpolation and
 * read-head velocity compensation make the ordinary read dissipative; its emitted
 * energy is additionally bounded by stored traveling energy. Powered wall gain is
 * charged separately against a finite measured work budget. The model thus does
 * not assume a time-varying delay is passive. Collectors split off incoming energy,
 * and each smooth finite answer spends a reserved spring budget using its actual
 * incremental modal work, including the pre-existing port velocity.
 */
object Tessera {
    const val RATE = Dsp.RATE
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = .99f
    const val HOLD_LOOP = LOOP_THRESHOLD
    const val MAX_SECONDS = 6f
    const val MAX_COLLECTOR_RELEASES = 6
    const val MIN_REFRACTORY_SECONDS = .13f
    const val MAX_WALL_WORK_FRACTION = .18
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val STEP = 1.0 / INTERNAL_RATE
    private const val WOOD_END = 8
    private const val COURSE_END = 32
    private const val TUBE_END = 40
    private const val COUNT = 43
    private const val CONTROL = 64
    private const val LOOP_SECONDS = 2.0
    private const val LOOP_PREROLL = 12
    private const val RADIATION = 27.0
    private const val OUTPUT_GAIN = .62

    private data class Shape(val material: Float, val hammer: Float, val scale: Float,
        val fold: Float, val motion: Float, val loss: Double, val collector: Double,
        val chamber: Double, val route: Double)
    private fun shape(voice: TesseraVoice) = when (voice) {
        TesseraVoice.WOOD -> Shape(.10f, .40f, .35f, .30f, .20f, .88, .88, .78, -.08)
        TesseraVoice.COURSE -> Shape(.45f, .55f, .45f, .40f, .25f, 1.0, .92, .83, .02)
        TesseraVoice.TUBE -> Shape(.90f, .45f, .60f, .35f, .20f, 1.12, .85, .90, -.03)
        TesseraVoice.ANSWER -> Shape(.40f, .50f, .65f, .70f, .35f, .90, 1.32, 1.10, .14)
        TesseraVoice.FOLDING -> Shape(.55f, .60f, .50f, .80f, .75f, .94, 1.10, 1.0, .22)
        TesseraVoice.CHAMBER -> Shape(.50f, .35f, .85f, .60f, .50f, 1.15, 1.08, 1.25, .08)
    }
    fun macrosFor(voice: TesseraVoice): List<MacroSpec> {
        val s = shape(voice)
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("MATERIAL", s.material, .45f),
            MacroSpec("HAMMER", s.hammer, .4f), MacroSpec("SCALE", s.scale, .4f),
            MacroSpec("FOLD", s.fold, .35f), MacroSpec("MOTION", s.motion, 0f),
            MacroSpec("HOLD", 0f, 0f))
    }
    fun defaults(voice: TesseraVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(macros: Map<String, Float>, voice: TesseraVoice): Map<String, Float> =
        defaults(voice).mapValues { (k, d) -> macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d }
    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: TesseraVoice) = ROOT_MIDI
    fun midiFor(voice: TesseraVoice, tune: Float): Int = rootMidi(voice) +
        ((if (tune.isFinite()) tune else .5f).coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: TesseraVoice, tune: Float) = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float) = hold.isFinite() && hold >= LOOP_THRESHOLD
    fun drumClassFor(voice: TesseraVoice, macros: Map<String, Float> = emptyMap()) =
        if (isLoop(settled(macros, voice).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
    fun scramble(voice: TesseraVoice, random: Random = Random.Default, temperature: Float = .35f,
        near: Patch? = null): Map<String, Float> {
        val d = defaults(voice)
        val seed = d + (near?.macros?.filterKeys { it in d } ?: emptyMap())
        return Dsp.scrambleNear(seed, if (temperature.isFinite()) temperature else .35f, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }
    }

    internal data class ProbeOptions(
        val normalize: Boolean = false, val chamberEnabled: Boolean = true,
        val receiversEnabled: Boolean = true, val collectorsEnabled: Boolean = true,
        val primaryStrikeEnabled: Boolean = true, val wallMotionEnabled: Boolean = true,
        val frameCouplingEnabled: Boolean = true, val isolatedMaterial: Int? = null,
        val durationSeconds: Float? = null, val seedContext: Int = 0,
        val recordDiagnostics: Boolean = true,
    )
    internal data class CollectorEvent(val timeSeconds: Float, val material: Int, val strength: Float,
        val spentEnergy: Double, val remainingBudget: Double)
    internal data class ArrivalEvent(val timeSeconds: Float, val path: Int, val material: Int,
        val strength: Float, val delaySeconds: Float)
    internal data class HammerContact(val timeSeconds: Float, val rebound: Boolean,
        val reservedEnergy: Double, val durationSeconds: Float)
    internal data class GeometrySnapshot(val timeSeconds: Float, val size: Float, val fold: Float,
        val velocity: Float, val wallWork: Double, val passiveEnergy: Double,
        val collectorEnergy: Double, val releaseCount: Int)
    internal data class Probe(
        val samples: FloatArray, val directMaterials: Array<FloatArray>,
        val reexcitedMaterials: Array<FloatArray>, val chamber: FloatArray, val frame: FloatArray,
        val continuousReturns: Array<FloatArray>, val collectorEvents: List<CollectorEvent>,
        val arrivals: List<ArrivalEvent>, val geometry: List<GeometrySnapshot>,
        val rawPeak: Float, val finalPassiveEnergy: Double, val maxPassiveEnergy: Double,
        val wallWork: Double, val initialCollectorBudget: Double, val remainingCollectorBudget: Double,
        val recoveredStates: Int, val previousCycle: FloatArray = FloatArray(0),
        val seamError: Double = 0.0, val loopStartFrame: Int = 0,
        val loopStateError: Double = 0.0, val loopConverged: Boolean = true,
        val sampleRate: Int = RATE, val primaryWork: Double = 0.0, val wallWorkBudget: Double = 0.0,
        val loopStateErrors: Map<String, Double> = emptyMap(),
        val primaryWorkBudget: Double = 0.0, val hammerContacts: List<HammerContact> = emptyList(),
    )

    /** Smooth finite contact, including a small delayed rebound reserved from the same source. */
    private class Pulse(val start: Int, val length: Int, val projection: DoubleArray,
        val force: Double, var energy: Double, val returned: Boolean,
        val contactLength: Int, val reboundStart: Int, val reboundForce: Double,
        var reboundEnergy: Double)
    private data class PlannedCollector(val frame: Int, val source: Int, val material: Int, val energy: Double)

    private class Path(val reference: Double, val loss: Double) {
        private val data = FloatArray((.90 * INTERNAL_RATE).toInt() + 4)
        private var write = 0
        private var low = 0.0
        var delay = reference * INTERNAL_RATE
            private set
        var energy = 0.0
            private set
        var wallSpent = 0.0
            private set
        // Stored pressure is absorbed by the damped chamber even when interpolation
        // or cancellation makes its listener readout small. This is physical loss of
        // the remaining traveling reserve, not merely a limit on future output.
        private val decay = exp(-2.5 / INTERNAL_RATE)
        fun read(targetSeconds: Double, wallGain: Double, wallCredit: Double): Double {
            val target = (targetSeconds * INTERNAL_RATE).coerceIn(.022 * INTERNAL_RATE, .86 * INTERNAL_RATE)
            // At the most extreme compression, speed is still below 0.9% of the clock.
            val move = (target - delay).coerceIn(-.009, .009)
            delay += move
            val whole = delay.toInt()
            val frac = delay - whole
            var a = write - whole
            if (a < 0) a += data.size
            var b = a - 1
            if (b < 0) b += data.size
            val pressure = (data[a] * (1 - frac) + data[b] * frac) * sqrt(1 - move)
            low += .065 * (pressure - low)
            var traveling = low
            energy *= decay
            val need = .5 * traveling * traveling
            if (need > energy && need > 0) traveling *= sqrt(energy / need)
            val removed = .5 * traveling * traveling
            energy = max(0.0, energy - removed)
            val passive = traveling * loss
            val emitted = removed * loss * loss
            val wanted = emitted * (wallGain * wallGain - 1).coerceAtLeast(0.0)
            wallSpent = min(wanted, max(0.0, wallCredit))
            return if (emitted > 1e-30) passive * sqrt(1 + wallSpent / emitted) else passive
        }
        fun put(wave: Double) {
            val stored = wave.toFloat()
            data[write] = stored
            energy += .5 * stored.toDouble() * stored
            write++
            if (write == data.size) write = 0
        }
        fun state(): DoubleArray = DoubleArray(data.size + 3).also { a ->
            for (i in data.indices) a[i] = data[(write + i) % data.size].toDouble()
            a[data.size] = low; a[data.size + 1] = delay / INTERNAL_RATE
            a[data.size + 2] = energy
        }
    }

    private class Engine(val voice: TesseraVoice, val m: Map<String, Float>, val velocity: Double,
        val options: ProbeOptions, val held: Boolean) {
        val s = shape(voice)
        val material = m.getValue("MATERIAL").toDouble()
        val hammer = m.getValue("HAMMER").toDouble()
        val scale = m.getValue("SCALE").toDouble()
        val fold = m.getValue("FOLD").toDouble()
        val motion = if (options.wallMotionEnabled) m.getValue("MOTION").toDouble() else 0.0
        val continuation = if (held) 1.0 else m.getValue("HOLD").toDouble()
        val finiteDriveEnd = .30 + 1.55 * continuation
        val x = DoubleArray(COUNT)
        val v = DoubleArray(COUNT)
        val rx = if (options.recordDiagnostics) DoubleArray(COUNT) else DoubleArray(0)
        val rv = if (options.recordDiagnostics) DoubleArray(COUNT) else DoubleArray(0)
        val cr = DoubleArray(COUNT)
        val sr = DoubleArray(COUNT)
        val pickup = DoubleArray(COUNT)
        val ports = Array(3) { DoubleArray(COUNT) }
        val contact = DoubleArray(COUNT)
        val pulses = ArrayList<Pulse>()
        val events = ArrayList<CollectorEvent>()
        val hammerContacts = ArrayList<HammerContact>()
        private val learnedCollectors = ArrayList<PlannedCollector>()
        private var heldPlan: List<PlannedCollector>? = null
        val arrivals = ArrayList<ArrivalEvent>()
        val snapshots = ArrayList<GeometrySnapshot>()
        val pathEnvelope = DoubleArray(6)
        val arrivalArmed = BooleanArray(6) { true }
        val incoming = DoubleArray(6)
        val returns = DoubleArray(3)
        val envelopes = DoubleArray(3)
        val baseline = DoubleArray(3)
        val collectorArmed = BooleanArray(3) { true }
        val lastRelease = IntArray(3) { -INTERNAL_RATE }
        val lastDestinationRelease = IntArray(3) { -INTERNAL_RATE }
        var lastAnyRelease = -INTERNAL_RATE
        val initialEnergy = .70 * velocity * velocity
        val initialCollectorBudget = if (options.collectorsEnabled && options.primaryStrikeEnabled)
            initialEnergy * .18 * s.collector * (if (held) 1.0 else 1 + .65 * continuation) else 0.0
        var collectorBudget = initialCollectorBudget
        val collected = DoubleArray(3)
        val wallBudget = initialEnergy * MAX_WALL_WORK_FRACTION * motion
        var wallCredit = wallBudget
        var wallWork = 0.0
        var releases = 0
        var maxEnergy = 0.0
        var sourceEnergy = 0.0
        var primaryWorkBudget = 0.0
        var size = scale
        var movingFold = fold
        var wallVelocity = 0.0
        var foldVelocity = 0.0
        var gestureEnvelope = 0.0
        var wallGain = 1.0
        var chamberPickup = 0.0
        var framePickup = 0.0
        var recoveredStates = 0
        var sample = 0
        // All HOLD clocks close together: source quarters, 64-frame wall controls,
        // and 4x/output frame boundaries. Finite continuation retains exact 500ms hits.
        val period = if (held) {
            val grid = CONTROL * Dsp.OVERSAMPLE
            (LOOP_SECONDS * INTERNAL_RATE / grid).roundToInt() * grid
        } else (LOOP_SECONDS * INTERNAL_RATE).roundToInt()
        val paths = Array(6) { p ->
            val base = .032 + .31 * scale.pow(1.2)
            Path(base * doubleArrayOf(.85, 1.17, 1.45, .72, 1.02, 1.66)[p] * (1 + .13 * fold),
                (.66 + .15 * scale - .05 * (p % 2)).coerceAtMost(.86))
        }
        val rotationCos = DoubleArray(4)
        val rotationSin = DoubleArray(4)
        val routeDestinations = IntArray(6) { it / 2 }
        val pressureDecay = exp(-2 * STEP)
        val coupling = (1 - exp(-2 * (2.3 + 3.7 * fold) * STEP)) * .5
        val theta = sqrt((.85 + .65 * scale + .30 * fold) * STEP)
        val portCos = cos(theta)
        val portSin = sin(theta)
        val familyOut = DoubleArray(3)
        val familyReturnOut = DoubleArray(3)
        val materialWeights = DoubleArray(3)

        init {
            val random = Random(Dsp.seedFor("TESSERA", voice.name, midiFor(voice, m.getValue("TUNE")), options.seedContext))
            val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
            fun mode(i: Int, frequency: Double, t60: Double, gain: Double, projection: Double) {
                val f = frequency.coerceAtMost(16_000.0)
                val r = exp(-6.907755278982137 / (t60 * INTERNAL_RATE))
                cr[i] = r * cos(2 * PI * f / INTERNAL_RATE)
                sr[i] = r * sin(2 * PI * f / INTERNAL_RATE)
                val band = ((17_000 - frequency) / 3_000).coerceIn(0.0, 1.0)
                pickup[i] = gain * band
                val family = if (i < WOOD_END) 0 else if (i < COURSE_END) 1 else 2
                if (i < TUBE_END) ports[family][i] = projection * band
            }
            val woodRatio = doubleArrayOf(1.0, 4.0, 9.05, 13.2)
            for (bar in 0..1) for (j in 0..3) {
                val detune = if (j == 0) 1.0 else 1 + (random.nextDouble() - .5) * .003
                mode(bar * 4 + j, hz * woodRatio[j] * detune,
                    (.88 + .55 * scale) * s.loss / (1 + j * .85 + .08 * bar),
                    doubleArrayOf(.86, .23, .10, .045)[j] / sqrt(2.0),
                    doubleArrayOf(1.0, .13 + .32 * hammer, .06 + .24 * hammer, .015 + .16 * hammer)[j] * (if (bar == 0) 1.0 else .81))
            }
            for (course in 0..1) for (pair in 0..1) for (j in 0..5) {
                val n = j + 1.0
                val cents = (if (pair == 0) -1 else 1) * (1.05 + course * .6 + random.nextDouble() * .28)
                val stiffness = .00035 + .00016 * course + .00005 * pair
                val ratio = n * sqrt((1 + stiffness * n * n) / (1 + stiffness))
                val index = WOOD_END + (course * 2 + pair) * 6 + j
                mode(index, hz * ratio * 2.0.pow(cents / 1200),
                    (1.7 + .9 * scale) * s.loss * (1 - pair * .035) / (1 + .36 * j),
                    .46 / n.pow(.80), (1 / n.pow(.9 - .32 * hammer)) * (if (course == 0) 1.0 else .87))
            }
            val tubeRatio = doubleArrayOf(1.0, 2.70, 4.60, 6.90, 9.45, 12.1, 15.15, 18.50)
            for (j in 0..7) mode(COURSE_END + j, hz * tubeRatio[j] * (if (j == 0) 1.0 else 1 + (random.nextDouble() - .5) * .0015),
                (2.65 + 1.05 * scale) * s.loss / (1 + .20 * j),
                doubleArrayOf(1.16, .30, .24, .18, .13, .095, .065, .04)[j],
                if (j == 0) 1.0 else (.20 + .64 * hammer) / (j + 1.0).pow(.48))
            for (j in 0..2) mode(TUBE_END + j, hz * doubleArrayOf(1.0, 1.49, 2.23)[j],
                .38 + .20 * scale, .10 / (j + 1), 0.0)
            for (f in 0..2) {
                val norm = sqrt(ports[f].sumOf { it * it })
                for (i in 0 until COUNT) ports[f][i] /= norm
                materialWeights[f] = .045 + when (f) {
                    0 -> exp(-((material / .37).pow(2)))
                    1 -> exp(-(((material - .48) / .30).pow(2)))
                    else -> exp(-(((material - 1) / .38).pow(2)))
                }
                if (options.isolatedMaterial != null) materialWeights[f] = if (options.isolatedMaterial == f) 1.0 else 0.0
            }
            val norm = sqrt(materialWeights.sumOf { it * it })
            for (f in 0..2) for (i in 0 until COUNT) contact[i] += ports[f][i] * materialWeights[f] / norm
            updateGeometry()
        }
        private fun projected(state: DoubleArray, p: DoubleArray): Double {
            var result = 0.0
            for (i in 0 until COUNT) result += state[i] * p[i]
            return result
        }
        private fun impulse(p: DoubleArray, amount: Double, returned: Boolean) {
            for (i in 0 until COUNT) {
                v[i] += p[i] * amount
                if (returned && rv.isNotEmpty()) rv[i] += p[i] * amount
            }
        }
        private fun launch(projection: DoubleArray, energy: Double, returned: Boolean, softness: Double = hammer) {
            val contactLength = ((.0038 * (1 - softness) + .00055) * INTERNAL_RATE).roundToInt().coerceAtLeast(16)
            // A harder common hammer reserves at most 6.5% of its existing event
            // energy for rebound. Pressure-collector mechanisms use one contact.
            val reboundEnergy = if (returned) 0.0 else energy * .065 * softness * softness
            val primaryEnergy = energy - reboundEnergy
            val reboundStart = contactLength + ((.00075 + .0018 * (1 - softness)) * INTERNAL_RATE).roundToInt()
            val length = if (reboundEnergy > 0) reboundStart + contactLength else contactLength
            // The raised-cosine force begins and ends at zero; its integral is one impulse.
            val force = sqrt(2 * primaryEnergy) * 2 / (contactLength * STEP)
            val reboundForce = sqrt(2 * reboundEnergy) * 2 / (contactLength * STEP)
            pulses.add(Pulse(sample, length, projection, force, primaryEnergy, returned,
                contactLength, reboundStart, reboundForce, reboundEnergy))
            if (!returned) {
                primaryWorkBudget += energy
                if (options.recordDiagnostics) {
                    hammerContacts.add(HammerContact((sample * STEP).toFloat(), false, primaryEnergy,
                        (contactLength * STEP).toFloat()))
                    if (reboundEnergy > 0) hammerContacts.add(HammerContact(((sample + reboundStart) * STEP).toFloat(),
                        true, reboundEnergy, (contactLength * STEP).toFloat()))
                }
            }
        }
        fun passiveEnergy(): Double {
            var energy = 0.0
            for (i in 0 until COUNT) energy += .5 * (x[i] * x[i] + v[i] * v[i])
            for (p in paths) energy += p.energy
            for (e in collected) energy += e
            return energy
        }
        private fun exchange(state: DoubleArray, f: Int, frame: Int) {
            val materialVelocity = projected(state, ports[f])
            val change = coupling * (state[frame] - materialVelocity)
            for (i in 0 until COUNT) state[i] += ports[f][i] * change
            state[frame] -= change
        }
        private fun updateGeometry() {
            val dt = CONTROL * STEP
            val response = 9.0 + 8 * hammer
            val target = (scale + motion * .375 * gestureEnvelope * (1 - .22 * scale)).coerceIn(0.0, 1.0)
            val foldTarget = (fold + motion * .225 * gestureEnvelope * (material - .38)).coerceIn(0.0, 1.0)
            if (motion > 0) {
                wallVelocity += (response * response * (target - size) - 2 * response * wallVelocity) * dt
                foldVelocity += (response * response * (foldTarget - movingFold) - 2 * response * foldVelocity) * dt
                wallVelocity = wallVelocity.coerceIn(-3.0, 3.0)
                foldVelocity = foldVelocity.coerceIn(-3.0, 3.0)
                size = (size + wallVelocity * dt).coerceIn(0.0, 1.0)
                movingFold = (movingFold + foldVelocity * dt).coerceIn(0.0, 1.0)
            }
            val angles = doubleArrayOf(.28 + 1.05 * movingFold + s.route,
                .12 + .77 * movingFold * (1 + .35 * material),
                -.25 - .88 * movingFold, .08 + .55 * movingFold * size)
            for (j in angles.indices) { rotationCos[j] = cos(angles[j]); rotationSin[j] = sin(angles[j]) }
            // Unit path probes expose the strongest actual receiving projection of the
            // orthogonal routing matrix, rather than guessing from the macro value.
            for (p in 0..5) {
                val unit = DoubleArray(6).also { it[p] = 1.0 }
                fun rotate(a: Int, b: Int, r: Int) {
                    val av = unit[a]; val bv = unit[b]
                    unit[a] = rotationCos[r] * av - rotationSin[r] * bv
                    unit[b] = rotationSin[r] * av + rotationCos[r] * bv
                }
                rotate(0, 2, 0); rotate(2, 4, 1); rotate(1, 5, 2); rotate(1, 3, 3)
                routeDestinations[p] = (0..2).maxBy { f -> unit[f * 2].pow(2) + unit[f * 2 + 1].pow(2) }
            }
            wallGain = 1 + .10 * motion * min(1.0, abs(wallVelocity) + abs(foldVelocity))
        }
        private fun route(a: Int, b: Int, rotation: Int) {
            val av = incoming[a]; val bv = incoming[b]
            incoming[a] = rotationCos[rotation] * av - rotationSin[rotation] * bv
            incoming[b] = rotationSin[rotation] * av + rotationCos[rotation] * bv
        }
        private fun release(source: Int, destination: Int, requestedEnergy: Double) {
            val cost = min(collectorBudget, requestedEnergy)
            if (cost <= 0) return
            collectorBudget -= cost
            collectorArmed[source] = false
            collected[source] = max(0.0, collected[source] - min(collected[source], cost * .08))
            launch(ports[destination], cost, true, (.20 + .64 * hammer).coerceIn(0.0, 1.0))
            releases++; lastRelease[source] = sample; lastDestinationRelease[destination] = sample; lastAnyRelease = sample
            if (held && heldPlan == null) learnedCollectors.add(PlannedCollector(sample, source, destination, cost))
            if (options.recordDiagnostics) events.add(CollectorEvent((sample * STEP).toFloat(), destination,
                sqrt(2 * cost).toFloat(), cost, collectorBudget))
        }
        /**
         * HOLD learns its finite reply score from returning pressure, then repeats that
         * compatible source score. The entire sounding object continues to evolve and
         * settle afterward; this is not a copied audio cycle. Closing the event score
         * prevents a near-threshold collector from alternating between different cycles.
         */
        private fun settleHeldPlan() {
            val retained = learnedCollectors.filter { it.frame >= period && it.frame < 2 * period }
                .map { it.copy(frame = it.frame % period) }.sortedBy { it.frame }.toMutableList()
            while (retained.size > 1) {
                var bad: Int? = null
                for (i in retained.indices) {
                    val next = (i + 1) % retained.size
                    val gap = (retained[next].frame - retained[i].frame + period) % period
                    if (gap < .060 * INTERNAL_RATE) { bad = next; break }
                }
                if (bad == null) for (port in 0..5) {
                    val indices = retained.indices.filter {
                        if (port < 3) retained[it].material == port else retained[it].source == port - 3
                    }
                    if (indices.size < 2) continue
                    for (j in indices.indices) {
                        val a = indices[j]; val b = indices[(j + 1) % indices.size]
                        val gap = (retained[b].frame - retained[a].frame + period) % period
                        if (gap < MIN_REFRACTORY_SECONDS * INTERNAL_RATE) { bad = b; break }
                    }
                    if (bad != null) break
                }
                if (bad == null) break
                retained.removeAt(bad)
            }
            heldPlan = retained.take(MAX_COLLECTOR_RELEASES)
            learnedCollectors.clear()
        }
        fun step(): Double {
            if (held && sample == 2 * period) settleHeldPlan()
            if (held && sample % period == 0) { releases = 0; wallCredit = wallBudget }
            if (options.primaryStrikeEnabled && (sample == 0 || sample % (period / 4) == 0 &&
                    (held || continuation > 0 && sample * STEP < finiteDriveEnd)))
                launch(contact, initialEnergy * (if (sample == 0) 1.0 else .16 * continuation * continuation), false)
            for (i in 0 until COUNT) {
                val a = x[i]; val b = v[i]
                x[i] = cr[i] * a + sr[i] * b; v[i] = cr[i] * b - sr[i] * a
                if (rx.isNotEmpty()) {
                    val ra = rx[i]; val rb = rv[i]
                    rx[i] = cr[i] * ra + sr[i] * rb; rv[i] = cr[i] * rb - sr[i] * ra
                }
            }
            if (options.frameCouplingEnabled) for (f in 0..2) {
                exchange(v, f, TUBE_END + f)
                if (rv.isNotEmpty()) exchange(rv, f, TUBE_END + f)
                // Sharing the fundamental frame velocity lets a struck material reach the others.
                exchange(v, f, TUBE_END)
                if (rv.isNotEmpty()) exchange(rv, f, TUBE_END)
            }
            val iterator = pulses.iterator()
            while (iterator.hasNext()) {
                val pulse = iterator.next()
                val age = sample - pulse.start
                if (age < 0) continue
                if (age >= pulse.length) { iterator.remove(); continue }
                val rebound = pulse.reboundEnergy > 0 && age >= pulse.reboundStart
                if (age >= pulse.contactLength && !rebound) continue
                val contactAge = if (rebound) age - pulse.reboundStart else age
                val force = if (rebound) pulse.reboundForce else pulse.force
                val available = if (rebound) pulse.reboundEnergy else pulse.energy
                var kick = force * (.5 - .5 * cos(2 * PI * contactAge / pulse.contactLength)) * STEP
                val before = projected(v, pulse.projection)
                val work = kick * before + .5 * kick * kick
                if (work > available) kick = -before + sqrt(before * before + 2 * max(0.0, available))
                val charged = max(0.0, kick * before + .5 * kick * kick)
                if (rebound) pulse.reboundEnergy = max(0.0, pulse.reboundEnergy - charged)
                else pulse.energy = max(0.0, pulse.energy - charged)
                if (!pulse.returned) sourceEnergy += charged
                impulse(pulse.projection, kick, pulse.returned)
            }
            var modalEnergy = 0.0
            for (i in 0 until COUNT) modalEnergy += .5 * (x[i] * x[i] + v[i] * v[i])
            gestureEnvelope += (sqrt(modalEnergy / max(1e-12, initialEnergy)).coerceIn(0.0, 1.5) - gestureEnvelope) * .000075
            if (sample % CONTROL == 0) updateGeometry()
            chamberPickup = 0.0
            returns.fill(0.0)
            if (options.chamberEnabled) {
                for (p in 0..5) {
                    val stretch = 1 + 1.30 * (size - scale) + .30 * (movingFold - fold) * (if (p % 2 == 0) 1 else -1)
                    val wave = paths[p].read(paths[p].reference * stretch, wallGain, wallCredit)
                    wallCredit = max(0.0, wallCredit - paths[p].wallSpent)
                    wallWork += paths[p].wallSpent
                    incoming[p] = wave
                    chamberPickup += wave * (if (p % 2 == 0) 1.0 else .73)
                    pathEnvelope[p] += .00045 * (abs(wave) - pathEnvelope[p])
                    if (pathEnvelope[p] < .000018 * velocity) arrivalArmed[p] = true
                    if (options.recordDiagnostics && arrivalArmed[p] && pathEnvelope[p] > .000040 * velocity && velocity > 0) {
                        arrivalArmed[p] = false
                        arrivals.add(ArrivalEvent((sample * STEP).toFloat(), p,
                            routeDestinations[p], pathEnvelope[p].toFloat(),
                            (paths[p].delay / INTERNAL_RATE).toFloat()))
                    }
                }
                route(0, 2, 0); route(2, 4, 1); route(1, 5, 2); route(1, 3, 3)
                for (p in 0..5) {
                    val f = p / 2
                    val wave = incoming[p]
                    val port = projected(v, ports[f])
                    val portion = if (options.receiversEnabled) wave * sqrt(.84) else 0.0
                    val portAfter = portCos * port + portSin * portion
                    val increment = portAfter - port
                    impulse(ports[f], increment, false)
                    if (rv.isNotEmpty()) {
                        val oldReturn = projected(rv, ports[f])
                        val change = (portCos - 1) * oldReturn + portSin * portion
                        for (i in 0 until COUNT) rv[i] += ports[f][i] * change
                    }
                    returns[f] += portSin * portion / STEP
                    if (options.receiversEnabled && options.collectorsEnabled) collected[f] += .5 * wave * wave * .16
                    // Receiver mute absorbs incoming pressure but preserves the initial radiation path.
                    val outgoing = portSin * port - portCos * portion
                    paths[p].put(outgoing)
                }
            }
            for (f in 0..2) {
                collected[f] *= pressureDecay
                val pressure = abs(incoming[f * 2]) + abs(incoming[f * 2 + 1])
                envelopes[f] += .00024 * (pressure - envelopes[f])
                baseline[f] += .000013 * (envelopes[f] - baseline[f])
                if (held) collectorBudget += (initialCollectorBudget - collectorBudget) * .000015
                val threshold = (.000065 + .00009 * (1 - fold)) * velocity
                if (envelopes[f] < max(threshold * .65, baseline[f] * .98)) collectorArmed[f] = true
                val excited = envelopes[f] > max(threshold, baseline[f] * 1.08)
                val destination = (f + (if (movingFold < .58) 1 else 2)) % 3
                if (heldPlan == null && options.receiversEnabled && options.collectorsEnabled && collectorArmed[f] && excited && releases < MAX_COLLECTOR_RELEASES &&
                    sample - lastRelease[f] >= (MIN_REFRACTORY_SECONDS * INTERNAL_RATE).toInt() &&
                    sample - lastDestinationRelease[destination] >= (MIN_REFRACTORY_SECONDS * INTERNAL_RATE).toInt() &&
                    sample - lastAnyRelease >= (.060 * INTERNAL_RATE).toInt() && collectorBudget > initialCollectorBudget * .025 &&
                    collected[f] > initialEnergy * .000004) {
                    // A path favors a DIFFERENT material; its compliance colors the finite answer.
                    val cost = min(collectorBudget, initialCollectorBudget * (.13 + .045 * fold))
                    release(f, destination, cost)
                }
            }
            if (heldPlan != null) for (planned in heldPlan!!)
                if (sample % period == planned.frame) release(planned.source, planned.material, planned.energy)
            familyOut.fill(0.0); familyReturnOut.fill(0.0); framePickup = 0.0
            for (i in 0 until COUNT) {
                if (!x[i].isFinite() || !v[i].isFinite())
                    throw IllegalStateException("TESSERA non-finite modal state $i at sample $sample")
                if (abs(x[i]) < 1e-25) x[i] = 0.0
                if (abs(v[i]) < 1e-25) v[i] = 0.0
                if (i >= TUBE_END) framePickup += v[i] * pickup[i]
                else {
                    val f = if (i < WOOD_END) 0 else if (i < COURSE_END) 1 else 2
                    familyOut[f] += v[i] * pickup[i]
                    if (rv.isNotEmpty()) familyReturnOut[f] += rv[i] * pickup[i]
                }
            }
            val energy = passiveEnergy()
            if (!energy.isFinite()) throw IllegalStateException("TESSERA non-finite traveling energy at sample $sample")
            maxEnergy = max(maxEnergy, energy)
            if (options.recordDiagnostics && sample % (INTERNAL_RATE / 40) == 0)
                snapshots.add(GeometrySnapshot((sample * STEP).toFloat(), size.toFloat(), movingFold.toFloat(),
                    wallVelocity.toFloat(), wallWork, energy, collected.sum(), releases))
            sample++
            return OUTPUT_GAIN * (familyOut.sum() + framePickup + chamberPickup * RADIATION * s.chamber)
        }
        fun stateGroups(): List<DoubleArray> = listOf(x.copyOf() + v.copyOf(),
            doubleArrayOf(size, movingFold), doubleArrayOf(wallVelocity, foldVelocity, gestureEnvelope),
            doubleArrayOf(wallCredit), collected.copyOf(), envelopes.copyOf() + baseline.copyOf(),
            doubleArrayOf(collectorBudget), doubleArrayOf(releases.toDouble()) +
                collectorArmed.map { if (it) 1.0 else 0.0 }.toDoubleArray(),
                lastRelease.map { min(MIN_REFRACTORY_SECONDS.toDouble(), (sample - it) * STEP) }.toDoubleArray() +
                lastDestinationRelease.map { min(MIN_REFRACTORY_SECONDS.toDouble(), (sample - it) * STEP) }.toDoubleArray() +
                doubleArrayOf(min(.060, (sample - lastAnyRelease) * STEP)),
            // The learned score is fixed configuration; its source clock phase belongs
            // to the converging state, alongside every still-pending finite contact.
            doubleArrayOf((sample % period).toDouble(), (sample % CONTROL).toDouble(),
                if (heldPlan == null) 0.0 else 1.0),
            pulses.flatMap { listOf((sample - it.start).toDouble() / INTERNAL_RATE, it.energy, it.force,
                it.length.toDouble() / INTERNAL_RATE, if (it.returned) 1.0 else 0.0,
                it.contactLength.toDouble() / INTERNAL_RATE, it.reboundStart.toDouble() / INTERNAL_RATE,
                it.reboundForce, it.reboundEnergy) + it.projection.toList() }.toDoubleArray()) +
            paths.map { it.state() }
    }

    /** Every complete-state group, each path's four parts included, must change by less than this per cycle. */
    internal const val STATE_TOLERANCE = .003

    /** True when every measured group is finite and inside [STATE_TOLERANCE]. */
    internal fun withinTolerance(errors: Map<String, Double>) =
        errors.isNotEmpty() && errors.values.all { it.isFinite() && it < STATE_TOLERANCE }

    internal fun stateErrors(a: List<DoubleArray>, b: List<DoubleArray>): Map<String, Double> {
        val result = linkedMapOf<String, Double>()
        val names = listOf("modal", "geometry", "wallVelocity", "wallCredit", "pressureStores",
            "pressureEnvelopes", "collectorBudget", "eventGates", "refractory", "sourcePhase", "pendingPulses")
        for (g in a.indices) {
            if (g >= names.size) {
                pathErrors("path${g - names.size}", a[g], b[g], result); continue
            }
            if (a[g].size != b[g].size || (g == 7 || g == 9) && !a[g].contentEquals(b[g])) {
                result[names[g]] = Double.POSITIVE_INFINITY; continue
            }
            result[names[g]] = relative(a[g], b[g], 0, a[g].size)
        }
        return result
    }

    /** Root-mean-square difference over [from, until) relative to the two sides' own level. */
    private fun relative(a: DoubleArray, b: DoubleArray, from: Int, until: Int): Double {
        var diff = 0.0; var norm = 0.0
        for (i in from until until) {
            val delta = a[i] - b[i]; diff += delta * delta
            norm += .5 * (a[i] * a[i] + b[i] * b[i])
        }
        return sqrt(diff / max(1e-14, norm))
    }

    /**
     * A path's state is its pressure history, the low-passed pressure, its delay in seconds and its stored
     * energy ([Path.state]). One norm over all four lets the half-second delay outweigh a quiet history:
     * a history flipped in sign at 1e-6 read 0.16% against the 0.3% gate. Each part is compared on its own
     * scale instead, and the filter state on the history's, since it is that history smoothed.
     */
    private fun pathErrors(name: String, a: DoubleArray, b: DoubleArray, result: MutableMap<String, Double>) {
        if (a.size != b.size || a.size < 4) {
            for (part in listOf("pressure", "filter", "delay", "energy")) result["$name.$part"] = Double.POSITIVE_INFINITY
            return
        }
        val history = a.size - 3
        result["$name.pressure"] = relative(a, b, 0, history)
        var level = 0.0
        for (i in 0 until history) level += .5 * (a[i] * a[i] + b[i] * b[i])
        result["$name.filter"] = abs(a[history] - b[history]) / max(1e-7, sqrt(level / history))
        result["$name.delay"] = relative(a, b, history + 1, history + 2)
        result["$name.energy"] = relative(a, b, history + 2, history + 3)
    }

    /** Source taps stay raw. Only samples/previousCycle receive shared melodic loudness. */
    internal fun probe(voice: TesseraVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        options: ProbeOptions = ProbeOptions(), cancelled: () -> Boolean = { false }): Probe {
        val m = settled(macros, voice)
        val strength = (if (velocity.isFinite()) velocity else 1f).coerceIn(0f, 1f).toDouble()
        val held = isLoop(m.getValue("HOLD")) && options.durationSeconds == null
        val engine = Engine(voice, m, strength, options, held)
        val cycle = engine.period
        val preroll = if (held) LOOP_PREROLL * cycle else 0
        val n = if (held) 2 * cycle else ((options.durationSeconds?.takeIf { it.isFinite() } ?: MAX_SECONDS)
            .coerceIn(.01f, MAX_SECONDS) * INTERNAL_RATE).roundToInt() / Dsp.OVERSAMPLE * Dsp.OVERSAMPLE
        val raw = FloatArray(n)
        val taps = if (options.recordDiagnostics) Array(11) { FloatArray(n) } else emptyArray()
        var precedingState: List<DoubleArray>? = null
        var closingState: List<DoubleArray>? = null
        for (i in 0 until preroll + n) {
            if (i % 8192 == 0 && (cancelled() || Thread.currentThread().isInterrupted)) throw CancellationException("TESSERA render no longer wanted")
            val value = engine.step()
            if (i >= preroll) {
                val j = i - preroll
                raw[j] = value.toFloat()
                if (taps.isNotEmpty()) {
                    for (f in 0..2) {
                        taps[f][j] = (OUTPUT_GAIN * (engine.familyOut[f] - engine.familyReturnOut[f])).toFloat()
                        taps[3 + f][j] = (OUTPUT_GAIN * engine.familyReturnOut[f]).toFloat()
                        taps[8 + f][j] = engine.returns[f].toFloat()
                    }
                    taps[6][j] = (OUTPUT_GAIN * RADIATION * engine.s.chamber * engine.chamberPickup).toFloat()
                    taps[7][j] = (OUTPUT_GAIN * engine.framePickup).toFloat()
                }
            }
            if (held && i + 1 == preroll + cycle) precedingState = engine.stateGroups()
            if (held && i + 1 == preroll + 2 * cycle) closingState = engine.stateGroups()
        }
        Tide.bandLimit(raw, INTERNAL_RATE)
        val finished = Dsp.decimate(raw, RATE)
        val hp = Dsp.OnePole(RATE)
        for (i in finished.indices) finished[i] -= hp.lp(finished[i], 16f)
        var previous = FloatArray(0)
        val samples = if (held) {
            val periodOut = cycle / Dsp.OVERSAMPLE
            previous = finished.copyOfRange(0, periodOut)
            val candidate = finished.copyOfRange(periodOut, 2 * periodOut)
            // Unity-sum smoothstep wrap with a real preceding cycle, as in Corolla/Tremor.
            val fade = 1024; val seam = 256
            for (i in 0 until fade + seam) {
                val w = if (i >= fade) 1.0 else (i / fade.toDouble()).let { it * it * (3 - 2 * it) }
                val j = candidate.size - fade - seam + i
                candidate[j] = ((1 - w) * candidate[j] + w * previous[j]).toFloat()
            }
            candidate
        } else finished
        val rawPeak = samples.maxOfOrNull { abs(it) } ?: 0f
        if (options.normalize) {
            Dsp.levelTo(samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val gain = if (rawPeak > 1e-12f) (samples.maxOfOrNull { abs(it) } ?: 0f) / rawPeak else 1f
            for (i in previous.indices) previous[i] *= gain
        }
        if (!held) Dsp.fadeTail(samples)
        val errors = if (held) stateErrors(precedingState!!, closingState!!) else emptyMap()
        val error = errors.values.maxOrNull() ?: 0.0
        val seam = if (held && samples.any { it != 0f }) Keys.seamError(previous + samples, previous.size) else 0.0
        fun tap(index: Int): FloatArray {
            if (taps.isEmpty()) return FloatArray(0)
            val down = Dsp.decimate(taps[index], RATE)
            return if (held) down.copyOfRange(down.size / 2, down.size) else down
        }
        val offset = if (held) (preroll + cycle) * STEP else 0.0
        val end = offset + samples.size.toDouble() / RATE
        return Probe(samples, Array(3) { tap(it) }, Array(3) { tap(3 + it) }, tap(6), tap(7),
            Array(3) { tap(8 + it) },
            engine.events.filter { it.timeSeconds >= offset && it.timeSeconds < end }.map { it.copy(timeSeconds = (it.timeSeconds - offset).toFloat()) },
            engine.arrivals.filter { it.timeSeconds >= offset && it.timeSeconds < end }.map { it.copy(timeSeconds = (it.timeSeconds - offset).toFloat()) },
            engine.snapshots.filter { it.timeSeconds >= offset && it.timeSeconds < end }.map { it.copy(timeSeconds = (it.timeSeconds - offset).toFloat()) },
            rawPeak, engine.passiveEnergy(), engine.maxEnergy, engine.wallWork, engine.initialCollectorBudget,
            engine.collectorBudget, engine.recoveredStates, previous, seam, if (held) 0 else 0,
            error, !held || withinTolerance(errors) && seam < Keys.MAX_SEAM_ERROR, primaryWork = engine.sourceEnergy,
            wallWorkBudget = engine.wallBudget * (if (held) LOOP_PREROLL + 2 else 1), loopStateErrors = errors,
            primaryWorkBudget = engine.primaryWorkBudget,
            hammerContacts = engine.hammerContacts.filter { it.timeSeconds >= offset && it.timeSeconds < end }
                .map { it.copy(timeSeconds = (it.timeSeconds - offset).toFloat()) })
    }

    internal fun renderLoop(voice: TesseraVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        normalize: Boolean = true, cancelled: () -> Boolean = { false }): FloatArray {
        val result = probe(voice, macros + ("HOLD" to 1f), velocity,
            ProbeOptions(normalize = normalize, recordDiagnostics = false), cancelled)
        check(result.loopConverged) { "TESSERA $voice held object did not settle: state=${result.loopStateError}, seam=${result.seamError}" }
        return result.samples
    }
    fun render(voice: TesseraVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip =
        Snip(probe(voice, macros, velocity, ProbeOptions(normalize = true, recordDiagnostics = false)).also {
            check(it.loopConverged) { "TESSERA $voice held object did not settle: state=${it.loopStateError}" }
        }.samples, channels = 1, sampleRate = RATE)
}
