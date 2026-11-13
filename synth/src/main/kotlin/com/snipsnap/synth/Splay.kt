package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The first finite listening proof, before the proposed six-voice library. */
enum class SplayVoice { TONGUE }

/** Causal listening controls. None changes the initial-condition family. */
enum class SplayMode { FULL, FROZEN, UPPER_ONLY, LOWER_ONLY, SOLO_REFERENCE }

data class SplayContactEvent(
    val seconds: Double,
    val participantId: Int,
    val arm: String,
    val direction: Int,
    /** Geometry-derived bounded strength, before velocity and participant admission. */
    val strength: Double,
    val simulationRelativeSpeed: Double,
    val zoneErrorRadians: Double,
    /** Model 2 observes the upper arm at the same crossing to locate the acoustic port. */
    val upperAngleRadians: Double = 0.0,
    val modalGains: List<Double> = emptyList(),
    /** Bounded coincidence compensation prepared from the complete active event population. */
    val coincidenceGain: Double = 1.0,
)

data class SplayTraceFrame(
    val seconds: Double,
    val simulationSeconds: Double,
    val stateDistance: List<Double>,
    val endpointDistance: List<Double>,
    val upperPressure: List<Double>,
    val lowerPressure: List<Double>,
)

data class SplayDiagnostics(
    val model: Int,
    val mode: String,
    val participants: Int,
    val effectivePopulation: Double,
    val gapDegrees: Double,
    val motionSpeed: Double,
    val releaseAngleRadians: Double,
    val activeSeconds: Double,
    val outputSeconds: Double,
    val rawPeak: Double,
    val rawRms: Double,
    val rawDc: Double,
    val maxRelativeEnergyDrift: Double,
    val solverEvaluations: Int,
    val contactCount: Int,
    val earlyCorrelation: Double,
    val lateCorrelation: Double,
    val preparationMilliseconds: Double,
    val audioMilliseconds: Double,
    /** Exact linear superposition of independently mapped participant forces. */
    val forceSuperposition: Boolean,
    val contactGainMin: Double = 1.0,
    val contactGainMax: Double = 1.0,
    val contactGainMean: Double = 1.0,
)

data class SplayRender(
    val snip: Snip,
    /** Conditioned and decimated mono output before the shared loudness stage. */
    val raw: FloatArray,
    val trace: List<SplayTraceFrame>,
    val events: List<SplayContactEvent>,
    val diagnostics: SplayDiagnostics,
)

/**
 * Independent conservative double pendulums read by two imagined acoustic contacts.
 * Gravity is solved before audio. The upper arm gates broad friction; relative lower-arm
 * crossings add finite catches. Neither force is returned to the mechanical solver.
 *
 * Four positively damped modes bear the requested pitch. Their coefficients are identical
 * for every participant, so summing the independently mapped force for each mode before
 * filtering is exact linear superposition. It does not average states or contact descriptors.
 * [renderDetailed]'s referenceBank path instead renders each complete modal bank and sums
 * its output; tests compare the two paths. No participant has an independent onset phase,
 * detune or noise stream. Shared roughness is acoustic variation only, never a gravity seed.
 *
 * Model 2 softens the common strike, shortens modal memory, and reads the upper arm's position
 * into each lower catch's excitation spectrum. Its contact gain is prepared from actual nearby
 * crossing times, not a programmed divergence envelope or an output-level controller. Identical
 * contacts retain inverse-population gain; isolated contacts approach inverse-square-root gain.
 * Model 1 retains its original arithmetic, voicing, defaults and acoustic seed domain.
 *
 * Both models are six-second finite gestures with a two-second bounded release. HOLD is explicitly
 * unavailable: no claim of a periodic chaotic orbit, or a seamless reconstructed loop, is made.
 */
object Splay {
    const val MODEL_VERSION = 2
    const val DEFAULT_SEED = 41001
    const val DEFAULT_MIDI = 60
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val MIN_MIDI = MIDI_MIN
    const val MAX_MIDI = MIDI_MAX
    const val ACTIVE_SECONDS = 6.0
    const val RELEASE_SECONDS = 2.0
    private const val CONTROL_RATE = 1000
    private const val RATE = Dsp.RATE
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    private const val LN_1000 = 6.907755278982137
    private const val LOWER_ZONE = .45
    private val RATIOS = doubleArrayOf(1.0, 2.0, 3.0, 4.035)
    private val T60 = doubleArrayOf(2.4, 1.1, .7, .45)
    private val UPPER_GAIN = doubleArrayOf(1.0, .32, .15, .065)
    private val LOWER_GAIN = doubleArrayOf(.88, .48, .23, .11)
    private val T60_V2 = doubleArrayOf(1.35, .48, .29, .19)
    private val UPPER_GAIN_V2 = doubleArrayOf(.82, .45, .25, .13)
    private val LOWER_GAIN_V2 = doubleArrayOf(.78, .76, .50, .28)
    private const val COINCIDENCE_SIGMA_SECONDS = .008
    private data class BankKey(val energy: Double, val gap: Double, val speed: Double, val count: Int)
    // Pitch, velocity and acoustic seed do not belong in this key: they cannot affect gravity.
    private val trajectoryCache = LinkedHashMap<BankKey, SplayTrajectoryBank.Bank>()

    private fun trajectory(energy: Double, gap: Double, speed: Double, count: Int): SplayTrajectoryBank.Bank {
        val key = BankKey(energy, gap, speed, count)
        synchronized(trajectoryCache) { trajectoryCache[key]?.let { return it } }
        val bank = SplayTrajectoryBank.build(energy, gap, speed, ACTIVE_SECONDS, count)
        synchronized(trajectoryCache) {
            trajectoryCache[key] = bank
            while (trajectoryCache.size > 3) trajectoryCache.remove(trajectoryCache.keys.first())
        }
        return bank
    }

    fun supportsModel(model: Int): Boolean = model in 1..MODEL_VERSION

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: SplayVoice, model: Int = MODEL_VERSION): List<MacroSpec> {
        require(supportsModel(model)) { "unsupported SPLAY model $model" }
        return if (model == 1) listOf(
            MacroSpec("TRACE", .55f), MacroSpec("SEED GAP", .5f), MacroSpec("ENERGY", .65f),
            MacroSpec("CONTACT", .60f), MacroSpec("POPULATION", 15f / 99f), MacroSpec("HOLD", 0f, 0f),
        ) else listOf(
            MacroSpec("TRACE", .62f), MacroSpec("SEED GAP", .72f), MacroSpec("ENERGY", .78f),
            MacroSpec("CONTACT", .70f), MacroSpec("POPULATION", 15f / 99f), MacroSpec("HOLD", 0f, 0f),
        )
    }

    fun defaults(voice: SplayVoice, model: Int = MODEL_VERSION): Map<String, Float> =
        macrosFor(voice, model).associate { it.name to it.default }

    fun scramble(voice: SplayVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val model = (near as? SplayPatch)?.model ?: MODEL_VERSION
        val base = defaults(voice, model) + if (near?.engine == "SPLAY") near.macros else emptyMap()
        return Dsp.scrambleNear(base, temperature.coerceIn(0f, 1f), random) + ("HOLD" to 0f)
    }

    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: SplayVoice,
        @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.TONAL

    fun isLoop(@Suppress("UNUSED_PARAMETER") hold: Float): Boolean = false

    /** Version-1 common simulation clock: 1x..10x at the default..35x. */
    fun motionSpeed(trace: Float): Double {
        require(trace.isFinite() && trace in 0f..1f)
        val t = trace.toDouble()
        return if (t <= .55) 10.0 * .1.pow(1.0 - t / .55)
        else 10.0 * 3.5.pow((t - .55) / .45)
    }

    /** Adjacent initial spacing, in degrees. Exact zero has a separate identical-state case. */
    fun gapDegrees(gap: Float): Double {
        require(gap.isFinite() && gap in 0f..1f)
        val p = gap.toDouble()
        // The tiny ramp connects zero continuously to the proposed logarithmic family.
        // The near-machine-precision end is a diagnostic range, not an accuracy claim.
        return if (p < .01) 1e-10 * (p / .01).pow(2.0)
        else 1e-10 * 1e9.pow((p - .01) / .99)
    }

    /** One to 100 stable IDs; only the newly admitted final participant has fractional weight. */
    fun populationWeights(population: Float): DoubleArray {
        require(population.isFinite() && population in 0f..1f)
        val count = 1.0 + 99.0 * population
        val whole = floor(count + 1e-6).toInt().coerceIn(1, 100)
        val fraction = (count - whole).coerceIn(0.0, 1.0)
        return if (whole == 100 || fraction < 1e-6) DoubleArray(whole) { 1.0 }
        else DoubleArray(whole + 1) { if (it == whole) fraction else 1.0 }
    }

    private fun settled(voice: SplayVoice, macros: Map<String, Float>, model: Int): Map<String, Float> {
        val result = defaults(voice, model).toMutableMap()
        for ((key, value) in macros) {
            require(key in result) { "unknown SPLAY macro $key" }
            require(value.isFinite() && value in 0f..1f) { "SPLAY $key must be 0..1" }
            result[key] = value
        }
        require(result.getValue("HOLD") == 0f) { "SPLAY is finite only; HOLD is not implemented" }
        return result
    }

    fun render(voice: SplayVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .8f, seed: Int = DEFAULT_SEED, model: Int = MODEL_VERSION): Snip =
        renderDetailed(voice, macros, midi, velocity, seed, model).snip

    fun renderDetailed(voice: SplayVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .8f, seed: Int = DEFAULT_SEED, model: Int = MODEL_VERSION,
        mode: SplayMode = SplayMode.FULL, referenceBank: Boolean = false): SplayRender {
        checkCancellation()
        require(supportsModel(model)) { "unsupported SPLAY model $model" }
        require(midi in MIDI_MIN..MIDI_MAX) { "SPLAY MIDI must be $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SPLAY velocity must be 0..1" }
        val start = System.nanoTime()
        val m = settled(voice, macros, model)
        val speed = motionSpeed(m.getValue("TRACE"))
        val gap = gapDegrees(m.getValue("SEED GAP"))
        val weights = if (mode == SplayMode.SOLO_REFERENCE) doubleArrayOf(1.0)
        else populationWeights(m.getValue("POPULATION"))
        val bank = trajectory(m.getValue("ENERGY").toDouble(), gap, speed, weights.size)
        val mapped = contacts(bank, m.getValue("CONTACT").toDouble(), mode, model)
        val descriptors = if (model == 1) mapped else compensateCoincidence(mapped, weights)
        val prepared = System.nanoTime()
        val raw = renderAudio(descriptors, weights, midi, velocity, seed, m.getValue("CONTACT").toDouble(),
            mode, referenceBank, model)
        val rendered = System.nanoTime()
        var peak = 0.0; var squares = 0.0; var sum = 0.0
        for (x in raw) { peak = max(peak, abs(x.toDouble())); squares += x.toDouble() * x; sum += x }
        val out = raw.copyOf()
        Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.toDouble().pow(.65).toFloat(), ceiling = .94f)
        val trace = trace(bank, descriptors, speed, mode)
        val diagnostics = SplayDiagnostics(model, mode.name, weights.size, weights.sum(), gap, speed,
            .15 + 2.65 * m.getValue("ENERGY"), ACTIVE_SECONDS, raw.size.toDouble() / RATE,
            peak, sqrt(squares / raw.size), sum / raw.size, bank.maxRelativeEnergyDrift,
            bank.evaluations, descriptors.events.size, correlation(descriptors, 0, CONTROL_RATE),
            correlation(descriptors, CONTROL_RATE * 4, CONTROL_RATE * 6),
            (prepared - start) / 1e6, (rendered - prepared) / 1e6, !referenceBank,
            descriptors.events.minOfOrNull { it.coincidenceGain } ?: 1.0,
            descriptors.events.maxOfOrNull { it.coincidenceGain } ?: 1.0,
            descriptors.events.map { it.coincidenceGain }.average().takeIf { it.isFinite() } ?: 1.0)
        return SplayRender(Snip(out, 1, RATE), raw, trace, descriptors.events, diagnostics)
    }

    private data class Contacts(
        val upper: Array<DoubleArray>, val lower: Array<DoubleArray>,
        /** Per-member, per-mode smooth excitation spectra at the descriptor clock. */
        val upperPort: Array<Array<DoubleArray>>, val lowerPort: Array<Array<DoubleArray>>,
        val events: List<SplayContactEvent>, val frames: Int,
    )

    private fun pressure(angle: Double, width: Double): Double = exp(-2.0 * (1.0 - cos(angle)) / (width * width))

    private fun contacts(bank: SplayTrajectoryBank.Bank, contact: Double, mode: SplayMode, model: Int): Contacts {
        val count = bank.members.size
        val frames = bank.frames
        val upper = Array(count) { DoubleArray(frames) }
        val lower = Array(count) { DoubleArray(frames) }
        val upperPort = Array(count) { Array(4) { DoubleArray(frames) } }
        val lowerPort = Array(count) { Array(4) { DoubleArray(frames) } }
        val events = ArrayList<SplayContactEvent>()
        val upperWidth = if (model == 1) .95 - .38 * contact else .85 - .43 * contact
        val lowerWidth = if (model == 1) .50 - .30 * contact else .45 - .28 * contact
        val upperGains = if (model == 1) UPPER_GAIN else UPPER_GAIN_V2
        val lowerGains = if (model == 1) LOWER_GAIN else LOWER_GAIN_V2
        for (id in 0 until count) {
            checkCancellation()
            val state = bank.members[id]
            var refractory = -1.0
            var armed = true
            for (k in 0 until frames) {
                val j = if (mode == SplayMode.FROZEN) 0 else k * 4
                val a = state[j]; val b = state[j + 1]
                val w1 = if (mode == SplayMode.FROZEN) 0.0 else state[j + 2]
                val relativeSpeed = if (mode == SplayMode.FROZEN) 0.0 else state[j + 3] - w1
                val relative = b - a - LOWER_ZONE
                // Small pressure floor lets the bowed surface breathe between its main zones.
                val u = if (model == 1)
                    (.045 + .955 * pressure(a, upperWidth)) * (.25 + .75 * abs(w1) / (1.0 + abs(w1)))
                else (.010 + .990 * pressure(a, upperWidth)) * (.25 + .75 * abs(w1) / (1.0 + abs(w1)))
                val l = pressure(relative, lowerWidth) * abs(relativeSpeed) / (1.0 + abs(relativeSpeed))
                upper[id][k] = u; lower[id][k] = l
                for (p in 0..3) {
                    // Excitation position changes the upper-mode balance without moving poles.
                    val position = .5 + .5 * cos(a + p * .71)
                    upperPort[id][p][k] = u * upperGains[p] * (if (p == 0) 1.0 else .45 + .55 * position)
                    lowerPort[id][p][k] = l * lowerGains[p] * (if (p == 0) 1.0 else .65 + .35 * cos(relative * (p + 1)).pow(2))
                }
                if (k == 0 || mode == SplayMode.FROZEN || mode == SplayMode.UPPER_ONLY) continue
                val old = state[(k - 1) * 4 + 1] - state[(k - 1) * 4] - LOWER_ZONE
                val x0 = sin(old); val x1 = sin(relative)
                if (!armed && abs(x1) > .018) armed = true
                val now = k * bank.dtSeconds
                if (armed && now >= refractory && x0 * x1 <= 0.0 && x0 != x1 && cos(relative) > .75) {
                    val oldSpeed = state[(k - 1) * 4 + 3] - state[(k - 1) * 4 + 2]
                    // Velocity-aware interpolation of the actual angular zone crossing. The
                    // stored omega is per simulation second, so scale its tangent once here.
                    val simulationDt = bank.dtSeconds * bank.speed
                    fun angleAt(t: Double): Double {
                        val t2 = t * t; val t3 = t2 * t
                        return (2 * t3 - 3 * t2 + 1) * old + (t3 - 2 * t2 + t) * simulationDt * oldSpeed +
                            (-2 * t3 + 3 * t2) * relative + (t3 - t2) * simulationDt * relativeSpeed
                    }
                    var left = 0.0; var right = 1.0
                    repeat(28) {
                        val middle = (left + right) * .5
                        if (sin(angleAt(middle)) * x0 > 0.0) left = middle else right = middle
                    }
                    val mix = (left + right) * .5
                    val time = ((k - 1) + mix) * bank.dtSeconds
                    val speedAtCrossing = oldSpeed + (relativeSpeed - oldSpeed) * mix
                    val angleAtCrossing = angleAt(mix)
                    val error = atan2(sin(angleAtCrossing), cos(angleAtCrossing))
                    val strength = if (model == 1)
                        (.018 + .105 * contact.pow(1.3)) * abs(speedAtCrossing) / (1.0 + abs(speedAtCrossing))
                    else (.028 + .14 * contact.pow(1.2)) * abs(speedAtCrossing) / (1.0 + abs(speedAtCrossing))
                    if (model == 1) {
                        events += SplayContactEvent(time, id, "LOWER", if (x1 > x0) 1 else -1,
                            strength, speedAtCrossing, error)
                    } else {
                        val t2 = mix * mix; val t3 = t2 * mix
                        val upperAtCrossing = (2 * t3 - 3 * t2 + 1) * state[(k - 1) * 4] +
                            (t3 - 2 * t2 + mix) * simulationDt * state[(k - 1) * 4 + 2] +
                            (-2 * t3 + 3 * t2) * a + (t3 - t2) * simulationDt * w1
                        // One shared acoustic surface: the upper arm's observed position locates
                        // a modal port. Equal histories excite exactly the same spectrum. The
                        // fundamental port stays strong while upper-mode radiation can differ.
                        val position = .22 + .17 * sin(upperAtCrossing)
                        val gains = (0..3).map { p ->
                            LOWER_GAIN_V2[p] * (if (p == 0) 1.0
                                else .45 + .75 * sin(PI * (p + 1) * position).pow(2))
                        }
                        events += SplayContactEvent(time, id, "LOWER", if (x1 > x0) 1 else -1,
                            strength, speedAtCrossing, error, upperAtCrossing, gains)
                    }
                    refractory = time + .012
                    armed = false
                }
            }
        }
        return Contacts(upper, lower, upperPort, lowerPort,
            events.sortedWith(compareBy<SplayContactEvent> { it.seconds }.thenBy { it.participantId }), frames)
    }

    /**
     * Contact-only population gain, independent of audio level, pitch, velocity and seed.
     * Each other ID contributes at most its admission weight through a smooth 8 ms Gaussian
     * around actual observed catch times. W/coincidence interpolates from coherent to sparse
     * contacts; the square root is bounded at sqrt(W), including fractional admission.
     *
     * Prepare this from the full population once. Rendering one reference-bank pass must not
     * recalculate gain from that pass's single member, which would change the instrument.
     */
    private fun compensateCoincidence(c: Contacts, weights: DoubleArray): Contacts {
        if (c.events.isEmpty() || weights.size == 1) return c
        val totalWeight = weights.sum()
        val ceiling = sqrt(totalWeight)
        val window = 6.0 * COINCIDENCE_SIGMA_SECONDS
        val contributions = DoubleArray(weights.size)
        var first = 0
        val adjusted = c.events.map { event ->
            while (first < c.events.size && c.events[first].seconds < event.seconds - window) first++
            contributions.fill(0.0)
            var j = first
            while (j < c.events.size) {
                val other = c.events[j]
                if (other.seconds > event.seconds + window) break
                val relativeTime = (other.seconds - event.seconds) / COINCIDENCE_SIGMA_SECONDS
                val contribution = exp(-.5 * relativeTime * relativeTime)
                contributions[other.participantId] = max(contributions[other.participantId], contribution)
                j++
            }
            var coincidentWeight = 0.0
            for (id in weights.indices) coincidentWeight += weights[id] * contributions[id]
            val gain = sqrt(totalWeight / coincidentWeight.coerceAtLeast(1e-12)).coerceIn(1.0, ceiling)
            event.copy(coincidenceGain = gain)
        }
        return c.copy(events = adjusted)
    }

    private class ModalBank(hz: Double, model: Int) {
        private val re = DoubleArray(4)
        private val im = DoubleArray(4)
        private val cr = DoubleArray(4)
        private val sr = DoubleArray(4)
        init {
            val t60 = if (model == 1) T60 else T60_V2
            for (p in 0..3) {
                val radius = exp(-LN_1000 / (t60[p] * INTERNAL_RATE))
                val frequency = hz * RATIOS[p]
                if (frequency < 17_500.0) {
                    cr[p] = radius * cos(TAU * frequency / INTERNAL_RATE)
                    sr[p] = radius * sin(TAU * frequency / INTERNAL_RATE)
                }
            }
        }
        fun tick(force: DoubleArray): Double {
            var sum = 0.0
            for (p in 0..3) {
                val x = cr[p] * re[p] - sr[p] * im[p]
                im[p] = sr[p] * re[p] + cr[p] * im[p] + force[p]
                re[p] = x
                sum += re[p]
            }
            return sum
        }
    }

    private fun renderAudio(c: Contacts, weights: DoubleArray, midi: Int, velocity: Float, seed: Int,
        contact: Double, mode: SplayMode, reference: Boolean, model: Int): FloatArray {
        val frames = ((ACTIVE_SECONDS + RELEASE_SECONDS) * INTERNAL_RATE).roundToInt()
        val raw = FloatArray(frames)
        val hz = Keys.midiHz(midi).toDouble()
        val noise = Dsp.Noise(Dsp.seedFor("SPLAY", model, seed, "shared-contact"))
        val roughness = FloatArray(frames)
        var lp = 0.0
        val noiseA = 1.0 - exp(-TAU * (1700 + 3500 * contact) / INTERNAL_RATE)
        // One identical acoustic stream is read by every participant, including at nonzero gap.
        for (i in roughness.indices) { lp += noiseA * (noise.next() - lp); roughness[i] = lp.toFloat() }
        val sumWeight = weights.sum()
        val gain = velocity.toDouble().pow(.85) / sumWeight
        val passes = if (reference) weights.size else 1
        for (pass in 0 until passes) {
            checkCancellation()
            val fromId = if (reference) pass else 0
            val untilId = if (reference) pass + 1 else weights.size
            val upper = Array(4) { DoubleArray(c.frames) }
            val lower = Array(4) { DoubleArray(c.frames) }
            for (id in fromId until untilId) for (p in 0..3) for (k in 0 until c.frames) {
                upper[p][k] += c.upperPort[id][p][k] * weights[id]
                lower[p][k] += c.lowerPort[id][p][k] * weights[id]
            }
            // Only finite catch pulses need audio-rate buffers; broad contact is interpolated.
            val pulses = Array(4) { FloatArray(frames) }
            if (mode != SplayMode.UPPER_ONLY && mode != SplayMode.FROZEN) {
                for (event in c.events) {
                    if (event.participantId !in fromId until untilId) continue
                    val width = if (model == 1)
                        (.0048 * (1.0 - contact) + .00065).coerceAtMost(.40 / hz)
                    else (.0022 * (1.0 - contact) + .00042).coerceAtMost(.40 / hz)
                    val samples = max(12, (width * INTERNAL_RATE).roundToInt())
                    val first = (event.seconds * INTERNAL_RATE).roundToInt()
                    val taper = smooth((ACTIVE_SECONDS - event.seconds) / .20)
                    val amplitude = if (model == 1) event.strength * weights[event.participantId] * taper
                    else event.strength * weights[event.participantId] * taper * event.coincidenceGain
                    // Raised-cosine area is one. Energy is bounded independently of pulse width.
                    for (j in 0 until samples) {
                        val n = first + j
                        if (n >= frames) break
                        val kernel = (1.0 - cos(TAU * (j + .5) / samples)) / samples
                        for (p in 0..3) {
                            val port = if (model == 1) LOWER_GAIN[p] else event.modalGains[p]
                            pulses[p][n] += (amplitude * port * kernel).toFloat()
                        }
                    }
                }
            }
            val bank = ModalBank(hz, model)
            val force = DoubleArray(4)
            val coherentWeight = (fromId until untilId).sumOf { weights[it] }
            val strikeFrames = max(16, (min(.0014, .35 / hz) * INTERNAL_RATE).roundToInt())
            for (i in 0 until frames) {
                if (i % 16384 == 0) checkCancellation()
                val time = i.toDouble() / INTERNAL_RATE
                val position = time * CONTROL_RATE
                val k = min(position.toInt(), c.frames - 2)
                val mix = (position - k).coerceIn(0.0, 1.0)
                val active = if (time < ACTIVE_SECONDS) smooth(time / .012) * smooth((ACTIVE_SECONDS - time) / .20) else 0.0
                val strike = if (i < strikeFrames) {
                    val strength = if (model == 1) .22 else .070
                    strength * coherentWeight * (1.0 - cos(TAU * (i + .5) / strikeFrames)) / strikeFrames
                } else 0.0
                for (p in 0..3) {
                    val u = upper[p][k] + mix * (upper[p][k + 1] - upper[p][k])
                    val l = lower[p][k] + mix * (lower[p][k + 1] - lower[p][k])
                    val frictionGain = if (model == 1) .00058 else .00035
                    val rubbing = roughness[i] * frictionGain * active *
                        ((if (mode == SplayMode.LOWER_ONLY) 0.0 else u) +
                            (if (mode == SplayMode.UPPER_ONLY || mode == SplayMode.FROZEN) 0.0 else l * .22))
                    force[p] = rubbing + pulses[p][i] + strike * (if (p == 0) 1.0 else .20 / p)
                }
                val value = bank.tick(force) * gain
                check(value.isFinite()) { "SPLAY nonfinite modal output" }
                raw[i] += value.toFloat()
            }
        }
        Tide.bandLimit(raw, INTERNAL_RATE)
        val out = Dsp.decimate(raw, RATE)
        // A fixed DC blocker is distinct from level correction and never reaches gravity.
        val dcA = 1.0 - exp(-TAU * 8.0 / RATE)
        var dc = 0.0
        for (i in out.indices) {
            dc += dcA * (out[i] - dc)
            out[i] = (out[i] - dc).toFloat()
        }
        Dsp.fadeTail(out, ms = 45f)
        check(out.all { it.isFinite() }) { "SPLAY nonfinite decimated output" }
        return out
    }

    private fun trace(bank: SplayTrajectoryBank.Bank, c: Contacts, speed: Double, mode: SplayMode): List<SplayTraceFrame> {
        return (0 until bank.frames step 40).map { k ->
            val frame = if (mode == SplayMode.FROZEN) 0 else k
            val ref = bank.members[0]
            val j = frame * 4
            val x0 = sin(ref[j]) + sin(ref[j + 1]); val y0 = -cos(ref[j]) - cos(ref[j + 1])
            val distances = ArrayList<Double>(bank.members.size)
            val endpoints = ArrayList<Double>(bank.members.size)
            for (member in bank.members) {
                var square = 0.0
                for (a in 0..1) {
                    square += (sin(member[j + a]) - sin(ref[j + a])).pow(2)
                    square += (cos(member[j + a]) - cos(ref[j + a])).pow(2)
                    // Same characteristic velocity sqrt(g/L)=1 as the physics diagnostics.
                    square += (member[j + a + 2] - ref[j + a + 2]).pow(2)
                }
                distances += sqrt(square)
                val x = sin(member[j]) + sin(member[j + 1]); val y = -cos(member[j]) - cos(member[j + 1])
                endpoints += sqrt((x - x0).pow(2) + (y - y0).pow(2))
            }
            SplayTraceFrame(k * bank.dtSeconds, frame * bank.dtSeconds * speed, distances, endpoints,
                c.upper.map { it[k] }, c.lower.map { it[k] })
        }
    }

    /** Mean per-participant descriptor correlation with ID zero, in a labeled time window. */
    private fun correlation(c: Contacts, from: Int, until: Int): Double {
        if (c.upper.size == 1) return 1.0
        val end = min(until, c.frames)
        if (end <= from + 1) return 1.0
        val n = end - from
        val reference = c.upper[0]
        var sum = 0.0
        for (id in 1 until c.upper.size) {
            val member = c.upper[id]
            var a = 0.0; var b = 0.0; var aa = 0.0; var bb = 0.0; var ab = 0.0
            for (k in from until end) {
                a += reference[k]; b += member[k]
                aa += reference[k] * reference[k]; bb += member[k] * member[k]; ab += reference[k] * member[k]
            }
            val varianceA = (aa - a * a / n).coerceAtLeast(0.0)
            val varianceB = (bb - b * b / n).coerceAtLeast(0.0)
            val denominator = sqrt(varianceA * varianceB)
            sum += if (denominator > 1e-20) ((ab - a * b / n) / denominator).coerceIn(-1.0, 1.0)
            else if (reference.contentEquals(member)) 1.0 else 0.0
        }
        return sum / (c.upper.size - 1)
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("SPLAY render cancelled")
    }
}
