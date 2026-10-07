package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** Six views of the same struck wood, traveling atmosphere and responding animal model. */
enum class MurkVoice { CLUNK, THWACK, FRONT, HOOT, GROVE, ALARM }

/**
 * MURK is an imaginary acoustic ecosystem, not a physical model of fog or owl physiology.
 * Four tuned six-mode trunks exchange waves through three lossy bidirectional fractional delays.
 * A separate finite event path carries pressure fronts; it never detects individual audio cycles.
 * Animal replies have their own bounded energy supply and can send smaller fronts back to the grove.
 *
 * The acoustic junction is an orthogonal Householder scattering of the tree's normalized contact
 * velocity and incoming waves. Modal damping, link attenuation and interpolation only remove
 * energy. In particular, the owls can be disabled without a limiter keeping the grove stable.
 * All coefficients and behavioral mappings below are design choices for audition, not measurements
 * of real wood, atmospheres or birds. The source specification is
 * docs/superpowers/specs/2026-10-03-murk-engine-engineering-spec.md.
 */
object Murk {
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = 0.99f
    const val MAX_SECONDS = 10f
    const val MAX_CALLS = 12
    const val MAX_CALL_GENERATION = 2
    const val MIN_REFRACTORY_SECONDS = 0.65f
    private const val TREES = 4
    private const val MODES = 6
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val MAX_FRONT_HOPS = 7
    private const val OUTPUT_TRIM = 0.56
    /**
     * The passive coupling port carries energy-normalized wave amplitudes, not listener SPL.
     * Readout radiation gains make arriving pressure and responding trunks audible without
     * returning any output gain to the grove. The first reference measured returns 49 dB and
     * traveling air 39 dB below the wood; these lifts put them near 23 and 19 dB below it.
     */
    private const val RETURN_RADIATION = 20.0
    private const val ATMOSPHERE_RADIATION = 10.0
    /**
     * Wave-port energy sums over samples, whereas the owl waveform is a radiation-pressure
     * pickup. Calibrating its emission prevents a quiet sustained call from carrying many
     * times the struck tree's finite energy. Behavioral call fronts retain their own bounded
     * event strengths; this factor controls only biological audio energy entering the grove.
     */
    private const val OWL_PORT_EMISSION = 0.025
    private const val CONTROL_FRAMES = 128
    private const val LOOP_PREROLL_CYCLES = 4
    private val NODE_PORTS = arrayOf(intArrayOf(1), intArrayOf(0, 3), intArrayOf(2, 5), intArrayOf(4))

    private data class Shape(
        val strike: Float, val trunk: Float, val fog: Float, val agitation: Float, val grove: Float,
        val wood: Double, val atmosphere: Double, val owl: Double, val decay: Double, val throat: Double,
    )

    private fun shape(voice: MurkVoice) = when (voice) {
        // Listening revision: the original THWACK was the requested bat. Preserve its
        // compact contact and material here; THWACK below adds scrub into a sharp release.
        MurkVoice.CLUNK -> Shape(.85f, .45f, .40f, .30f, .40f, 1.0, .52, .40, .97, 1.15)
        MurkVoice.THWACK -> Shape(.85f, .45f, .40f, .30f, .40f, 1.0, .52, .40, .97, 1.15)
        MurkVoice.FRONT -> Shape(.45f, .60f, .85f, .25f, .65f, .85, .95, .42, 1.08, .92)
        MurkVoice.HOOT -> Shape(.25f, .50f, .45f, .50f, .50f, .64, .48, .82, 1.12, .83)
        MurkVoice.GROVE -> Shape(.40f, .55f, .60f, .60f, .80f, .78, .76, .67, 1.15, 1.04)
        MurkVoice.ALARM -> Shape(.80f, .45f, .65f, .85f, .60f, .82, .73, .74, .93, 1.24)
    }

    fun macrosFor(voice: MurkVoice): List<MacroSpec> {
        val s = shape(voice)
        return listOf(
            MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("STRIKE", s.strike, neutral = .35f),
            MacroSpec("TRUNK", s.trunk, neutral = .5f),
            MacroSpec("FOG", s.fog, neutral = .35f),
            MacroSpec("AGITATION", s.agitation, neutral = .2f),
            MacroSpec("GROVE", s.grove, neutral = .4f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    fun defaults(voice: MurkVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: MurkVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (k, default) -> (macros[k]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: MurkVoice): Int = ROOT_MIDI
    fun midiFor(voice: MurkVoice, tune: Float): Int = rootMidi(voice) + (tune.coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: MurkVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: MurkVoice, @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.LOOP

    /** Random patches stay finite; recurring performers are an explicit HOLD choice. */
    fun scramble(voice: MurkVoice, random: Random = Random.Default): Map<String, Float> =
        defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else random.nextFloat() }

    /** Same velocity parameter used by the host's existing Velocity.atVelocity path. */
    fun render(voice: MurkVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val p = probe(voice, macros, velocity, ProbeOptions(normalize = true, recordDiagnostics = false))
        return Snip(p.samples, channels = 1, sampleRate = RATE)
    }

    /** Loop-only host contract: settled recurring grove, without the initial isolated strike. */
    internal fun renderLoop(voice: MurkVoice, macros: Map<String, Float>, cancelled: () -> Boolean = { false }): FloatArray =
        probe(voice, macros + ("HOLD" to 1f), options = ProbeOptions(normalize = true, recordDiagnostics = false), cancelled = cancelled).samples

    internal data class ProbeOptions(
        val normalize: Boolean = false,
        val linksEnabled: Boolean = true,
        val owlsEnabled: Boolean = true,
        val owlToFogEnabled: Boolean = true,
        val owlAudioEnabled: Boolean = true,
        val primaryStrikeEnabled: Boolean = true,
        val isolatedCall: Boolean = false,
        val durationSeconds: Float? = null,
        val seedContext: Int = 0,
        val recordDiagnostics: Boolean = true,
    )

    internal data class Arrival(
        val timeSeconds: Float, val destination: Int, val strength: Float, val origin: Int,
        val generation: Int, val fromCall: Boolean,
    )
    internal data class CallEvent(
        val timeSeconds: Float, val tree: Int, val agitation: Float, val strength: Float,
        val generation: Int, val remainingBudget: Int, val stimulusTimeSeconds: Float,
    )
    internal data class GestureEvent(val timeSeconds: Float, val tree: Int, val strength: Float)
    internal data class StateSnapshot(
        val timeSeconds: Float, val agitation: FloatArray, val remainingBudget: Int,
        val remainingVocalEnergy: Float, val passiveEnergy: Double,
    )
    internal data class Probe(
        val samples: FloatArray,
        val directTrunks: FloatArray,
        val reexcitedTrunks: FloatArray,
        val atmosphere: FloatArray,
        val owls: FloatArray,
        val owlFogInput: FloatArray,
        val arrivals: List<Arrival>,
        val calls: List<CallEvent>,
        val gestures: List<GestureEvent>,
        val stateSnapshots: List<StateSnapshot>,
        val rawPeak: Float,
        val finalPassiveEnergy: Double,
        val sampleRate: Int = RATE,
        val previousCycle: FloatArray = FloatArray(0),
        val seamError: Double = 0.0,
        val loopStartFrame: Int = 0,
    )

    private data class Front(
        val frame: Int, val destination: Int, val previous: Int, val strength: Double,
        val origin: Int, val generation: Int, val fromCall: Boolean, val hops: Int,
        val serial: Int,
    )

    private data class PlannedCall(val frame: Int, val tree: Int, val agitation: Double, val strength: Double, val generation: Int, val stimulusFrame: Int)

    /**
     * The diagnostic energy argument is velocity in the physical event, before final loudness.
     * Taps always retain raw levels; normalization acts only on samples and previousCycle.
     */
    internal fun probe(
        voice: MurkVoice,
        macros: Map<String, Float> = emptyMap(),
        energy: Float = 1f,
        options: ProbeOptions = ProbeOptions(),
        cancelled: () -> Boolean = { false },
    ): Probe {
        val m = settled(voice, macros)
        val eventEnergy = energy.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        // CLUNK inherits the exact original THWACK bat, including its contact/breath grains.
        val seedVoice = if (voice == MurkVoice.CLUNK) MurkVoice.THWACK else voice
        val seed = Dsp.seedFor("MURK", seedVoice, m.values.joinToString(","), eventEnergy, options.seedContext)
        val held = isLoop(m.getValue("HOLD"))
        if (!held) {
            val seconds = (options.durationSeconds ?: (5.8f + 2.1f * m.getValue("GROVE") + .8f * m.getValue("FOG"))).coerceIn(.1f, MAX_SECONDS)
            val frames = (seconds * RATE).roundToInt()
            val result = Simulation(voice, m, eventEnergy.toDouble(), options, seed, 0, null, cancelled).run(frames * Dsp.OVERSAMPLE, 0)
            return finish(result, options, held = false)
        }

        val controlAtOutputRate = CONTROL_FRAMES / Dsp.OVERSAMPLE
        val cycleFrames = (((2.7f + 2.0f * m.getValue("GROVE") + .55f * m.getValue("FOG")) * RATE / controlAtOutputRate).roundToInt() * controlAtOutputRate)
        val cycle = cycleFrames * Dsp.OVERSAMPLE
        // First settle the bounded animal model, then make the last cycle's responses periodic.
        // Repeating an event schedule derived from that model avoids a threshold crossing drifting
        // a bird by one frame at the loop seam; it does not replace causal one-shot behavior.
        val learningOptions = options.copy(normalize = false, recordDiagnostics = true)
        val learning = Simulation(voice, m, eventEnergy.toDouble(), learningOptions, seed, cycle, null, cancelled)
            .run(3 * cycle, 2 * cycle, captureAudio = false)
        val plan = closeCallPlan(learning.plannedCalls.map { it.copy(frame = it.frame - 2 * cycle, stimulusFrame = it.stimulusFrame - 2 * cycle) }, cycle, m)
        val capture = LOOP_PREROLL_CYCLES * cycle
        // The decimator sees audio on both sides of the two selected cycles.
        val padding = 256 * Dsp.OVERSAMPLE
        val result = Simulation(voice, m, eventEnergy.toDouble(), options, seed, cycle, plan, cancelled)
            .run(capture + 2 * cycle + padding, capture - padding)
        return finish(result, options, held = true, cycleFrames = cycleFrames, paddingFrames = padding / Dsp.OVERSAMPLE)
    }

    private class RunResult(
        val taps: Array<FloatArray>, val arrivals: List<Arrival>, val calls: List<CallEvent>,
        val gestures: List<GestureEvent>, val snapshots: List<StateSnapshot>,
        val passiveEnergy: Double, val plannedCalls: List<PlannedCall>,
    )

    /** A learned cycle must obey the same refractory interval across its wrap as inside it. */
    private fun closeCallPlan(calls: List<PlannedCall>, cycle: Int, macros: Map<String, Float>): List<PlannedCall> {
        val retained = calls.sortedBy { it.frame }.take(MAX_CALLS).toMutableList()
        val agitation = macros.getValue("AGITATION").toDouble()
        val strike = macros.getValue("STRIKE").toDouble()
        for (tree in 0 until TREES) {
            val individual = retained.filter { it.tree == tree }.toMutableList()
            while (individual.size > 1) {
                var removed = false
                for (i in individual.indices) {
                    val a = individual[i]
                    val b = individual[(i + 1) % individual.size]
                    val articulation = (agitation * .73 + a.agitation * .27 + strike * agitation * .09).coerceIn(0.0, 1.0)
                    val refractory = MIN_REFRACTORY_SECONDS + .65 * (1 - agitation) + .32 * articulation.pow(4.0) + .09 * tree
                    val gap = if (b.frame > a.frame) b.frame - a.frame else cycle + b.frame - a.frame
                    if (gap < (refractory * INTERNAL_RATE).roundToInt()) {
                        retained.remove(b)
                        individual.remove(b)
                        removed = true
                        break
                    }
                }
                if (!removed) break
            }
        }
        require(retained.sumOf { it.strength * it.strength * (.55 - .24 * it.agitation) } <= 4.8) { "MURK held call schedule exceeds its vocal energy budget" }
        return retained
    }

    private fun finish(result: RunResult, options: ProbeOptions, held: Boolean, cycleFrames: Int = 0, paddingFrames: Int = 0): Probe {
        val taps = result.taps.map { if (it.isEmpty()) FloatArray(0) else Dsp.decimate(it, RATE) }
        var previous = FloatArray(0)
        val audio = if (held) {
            previous = taps[0].copyOfRange(paddingFrames, paddingFrames + cycleFrames)
            taps[0].copyOfRange(paddingFrames + cycleFrames, paddingFrames + 2 * cycleFrames)
        } else taps[0]
        val peak = audio.maxOfOrNull { abs(it) } ?: 0f
        if (options.normalize) {
            val before = peak
            Dsp.levelTo(audio, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val after = audio.maxOfOrNull { abs(it) } ?: 0f
            val gain = if (before > 1e-12f) after / before else 1f
            for (i in previous.indices) previous[i] *= gain
        }
        if (!held) Dsp.fadeTail(audio)
        val seam = if (held) {
            val both = previous + audio
            if (both.all { it == 0f }) 0.0 else Keys.seamError(both, previous.size)
        } else 0.0
        fun tap(i: Int) = if (taps[i].isEmpty()) taps[i] else if (held) taps[i].copyOfRange(paddingFrames + cycleFrames, paddingFrames + 2 * cycleFrames) else taps[i]
        // All events in the returned held cycle use local cycle times. Learning/pre-roll events
        // are absent, while their settled state continues to influence the selected cycle.
        val offset = if (held) (LOOP_PREROLL_CYCLES + 1) * cycleFrames.toFloat() / RATE else 0f
        val end = if (held) offset + cycleFrames.toFloat() / RATE else Float.POSITIVE_INFINITY
        return Probe(
            audio, tap(1), tap(2), tap(3), tap(4), tap(5),
            result.arrivals.filter { it.timeSeconds >= offset && it.timeSeconds < end }.map { it.copy(timeSeconds = it.timeSeconds - offset) },
            result.calls.filter { it.timeSeconds >= offset && it.timeSeconds < end }.map { it.copy(timeSeconds = it.timeSeconds - offset, stimulusTimeSeconds = it.stimulusTimeSeconds - offset) },
            result.gestures.filter { it.timeSeconds >= offset && it.timeSeconds < end }.map { it.copy(timeSeconds = it.timeSeconds - offset) },
            result.snapshots.filter { it.timeSeconds >= offset && it.timeSeconds < end }.map { it.copy(timeSeconds = it.timeSeconds - offset) },
            peak, result.passiveEnergy, previousCycle = previous, seamError = seam,
        )
    }

    /** Constant fractional delay, convex interpolation and unity-DC-gain loss filter: all contractive. */
    private class Link(val travelFrames: Double, private val passageGain: Double, cutoffHz: Double) {
        private val delay = FloatArray(kotlin.math.ceil(travelFrames).toInt() + 2)
        private var write = 0
        private val whole = travelFrames.toInt()
        private val frac = travelFrames - whole
        private val lowCoefficient = 1.0 - exp(-2.0 * PI * cutoffHz / INTERNAL_RATE)
        private var low = 0.0
        private var squares = 0.0
        fun read(): Double {
            var a = write - whole
            if (a < 0) a += delay.size
            var b = a - 1
            if (b < 0) b += delay.size
            val wave = delay[a] * (1.0 - frac) + delay[b] * frac
            low += lowCoefficient * (wave - low)
            return low * passageGain
        }
        fun write(value: Double) {
            val old = delay[write].toDouble()
            val stored = value.toFloat()
            squares += stored.toDouble() * stored - old * old
            delay[write] = stored
            write++
            if (write == delay.size) write = 0
        }
        fun energy(): Double = max(0.0, squares) + low * low
    }

    private class Tree(
        val index: Int, rootHz: Double, trunk: Double, fog: Double, strike: Double, decay: Double,
    ) {
        val direct = Modes.Bank(MODES, INTERNAL_RATE)
        val returns = Modes.Bank(MODES, INTERNAL_RATE)
        val contact = doubleArrayOf(1.0, .40 + .32 * strike, .25 + .32 * strike, .12 + .34 * strike, 0.0, 0.0)
        private val pickup = doubleArrayOf(1.0, .38, .27, .18, .27 + .32 * trunk, .18 * trunk)
        private val hz = DoubleArray(MODES)
        private val t60 = DoubleArray(MODES)
        var pressure = 0.0
        private val relax = exp(-1.0 / ((.14 + .68 * fog + .2 * trunk) * INTERNAL_RATE))
        val portK = (.48 + 1.5 * fog + 1.2 * trunk * fog) / INTERNAL_RATE
        private val spring0 = .008 + .02 * trunk * (1 + .3 * fog)
        private val spring1 = .01 + .018 * trunk
        private var lastLoad = -1.0
        init {
            val norm = sqrt(contact.sumOf { it * it })
            for (i in contact.indices) contact[i] /= norm
            val ratios = doubleArrayOf(1.0, 2.12 + .16 * trunk, 3.52 + .31 * trunk, 5.63 + .55 * trunk, 1.42 + .14 * (1 - trunk), 2.76 + .3 * (1 - trunk))
            val fundamental = rootHz * (index + 1)
            val rootDecay = (.95 + 1.40 * trunk) * decay * (1 - .34 * fog) * (1 - .07 * index)
            for (i in 0 until MODES) {
                hz[i] = (fundamental * ratios[i]).coerceAtMost(30_000.0)
                // Atmospheric impedance loads the original wood, including its upper-mode loss,
                // before any traveling front returns; this is not an output low-pass filter.
                t60[i] = if (i < 4) rootDecay / ratios[i].pow(.68 + .35 * (1 - trunk) + .28 * fog) else (.30 + .5 * trunk) * (1 - .18 * fog)
                direct.tune(i, hz[i], t60[i])
                returns.tune(i, hz[i], t60[i])
            }
            // Hollow reinforcement is a reciprocal passive spring, not a second parallel sample.
            for (bank in listOf(direct, returns)) {
                bank.connect(0, 4, spring0)
                bank.connect(1, 5, spring1)
            }
            val anchor = direct.anchorScale()
            for (i in hz.indices) hz[i] *= anchor
            load()
        }
        fun load() {
            val bounded = pressure.coerceIn(0.0, 1.5)
            if (abs(bounded - lastLoad) < .0001) return
            lastLoad = bounded
            val loss = 1.0 + .8 * bounded
            for (i in hz.indices) {
                direct.tune(i, hz[i], t60[i] / loss)
                returns.tune(i, hz[i], t60[i] / loss)
            }
        }
        fun step() { pressure *= relax; direct.step(); returns.step() }
        fun stimulate(strength: Double) { pressure = (pressure + strength / (1 + strength)).coerceAtMost(1.5) }
        /** Energy of the summed physical state; the diagnostic decomposition is not two trees. */
        fun energy(): Double {
            var energy = 0.0
            for (i in hz.indices) {
                val v = direct.velocity(i) + returns.velocity(i)
                val q = direct.displacement(i) + returns.displacement(i)
                val wq = 2.0 * PI * hz[i] * q
                energy += .5 * (v * v + wq * wq)
            }
            val q0 = direct.displacement(0) + returns.displacement(0)
            val q1 = direct.displacement(1) + returns.displacement(1)
            val q4 = direct.displacement(4) + returns.displacement(4)
            val q5 = direct.displacement(5) + returns.displacement(5)
            val w0 = 2.0 * PI * minOf(hz[0], hz[4])
            val w1 = 2.0 * PI * minOf(hz[1], hz[5])
            energy -= spring0 * w0 * w0 * q0 * q4 + spring1 * w1 * w1 * q1 * q5
            return energy
        }
        fun directAudio(): Double = direct.velocityAlong(pickup) / (1.0 + .55 * index)
        fun returnAudio(): Double = returns.velocityAlong(pickup) / (1.0 + .55 * index)
    }

    private class Owl(private val tree: Int, private val anchor: Double, private val throatShape: Double) {
        var agitation = 0.0
        var pending = -1
        var pendingGeneration = 0
        var stimulusFrame = -1
        var refractoryUntil = 0
        var remaining = 4
        private var elapsed = -1
        private var frames = 0
        private var strength = 0.0
        private var articulation = 0.0
        private var phase = 0.0
        private var breath = 0.0
        private var noise = Dsp.Noise(1)
        private val throat = Dsp.TptSvf(INTERNAL_RATE)
        val active: Boolean get() = elapsed >= 0
        fun begin(intensity: Double, agitation: Double, seed: Int) {
            strength = intensity
            articulation = agitation.coerceIn(0.0, 1.0)
            frames = ((.50 - .25 * articulation + .04 * tree) * INTERNAL_RATE).toInt()
            elapsed = 0
            phase = 0.0
            noise = Dsp.Noise(seed)
            breath = 0.0
        }
        fun tick(): Double {
            if (elapsed < 0) return 0.0
            val t = elapsed.toDouble() / frames
            val envelope = sin(PI * t).pow(if (articulation < .55) 1.7 else .95)
            val paired = if (articulation in .32.. .72) (.77 + .23 * cos(4.0 * PI * t)) else 1.0
            val bend = 2.0.pow(((.38 * (1 - t).pow(2.0) - .11 * sin(PI * t)) * (.6 + articulation)) / 12.0)
            val hz = anchor * bend
            phase += hz / INTERNAL_RATE
            phase -= kotlin.math.floor(phase)
            val base = sin(2.0 * PI * phase)
            // Breath-driven pressure saturation is evaluated at 4x. The periodic anchor remains
            // audible even at the screech end; breath never becomes an unlimited noise source.
            val rounded = tanh(base * (1.05 + 2.8 * articulation)) / tanh(1.05 + 2.8 * articulation)
            breath += .04 * (noise.next() - breath)
            val source = rounded + articulation.pow(2) * .22 * breath + .06 * (1 - articulation) * breath
            throat.process((source * envelope * paired).toFloat(), (anchor * throatShape * (2.2 + articulation)).toFloat(), .85f)
            val sound = strength * (.78 * source * envelope * paired + .22 * throat.band)
            elapsed++
            if (elapsed >= frames) elapsed = -1
            return sound
        }
    }

    private class Simulation(
        private val voice: MurkVoice, private val m: Map<String, Float>, private val energy: Double,
        private val options: ProbeOptions, private val seed: Int, private val cycle: Int,
        private val repeatingCalls: List<PlannedCall>?, private val cancelled: () -> Boolean,
    ) {
        private val s = shape(voice)
        private val strike = m.getValue("STRIKE").toDouble()
        private val trunk = m.getValue("TRUNK").toDouble()
        private val fog = m.getValue("FOG").toDouble()
        private val agitation = m.getValue("AGITATION").toDouble()
        private val grove = m.getValue("GROVE").toDouble()
        private val root = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        private val trees = Array(TREES) { Tree(it, root, trunk, fog, strike, s.decay) }
        private val owls = Array(TREES) { Owl(it, root * if (it < 2) 1.0 else 2.0, s.throat * (1 + .025 * it)) }
        private val travel = DoubleArray(3) { i -> (.065 + .175 * fog + .12 * grove + .085 * fog * grove) * (1 + .115 * i + .035 * grove * i) * INTERNAL_RATE }
        private val passage = .42 + .21 * fog + .09 * grove - .045 * fog * grove
        private val links = Array(6) { direction -> Link(travel[direction / 2], passage, 2_900.0 - 1_900.0 * fog + 900.0 * strike) }
        private val incoming = DoubleArray(6)
        private val outgoing = DoubleArray(6)
        private val frontQueue = PriorityQueue<Front>(compareBy<Front> { it.frame }.thenBy { it.serial })
        private var frontSerial = 0
        private val arrivals = mutableListOf<Arrival>()
        private val calls = mutableListOf<CallEvent>()
        private val gestures = mutableListOf<GestureEvent>()
        private val snapshots = mutableListOf<StateSnapshot>()
        private val planned = mutableListOf<PlannedCall>()
        private var budget = MAX_CALLS
        private var vocalEnergy = 4.8
        private var primaryFrame = -1
        private var primaryTree = 0
        private var primaryStrength = 0.0
        private var contactNoise = Dsp.Noise(seed)
        private var scrubLow = 0.0
        private var scrubBand = 0.0
        private var scrubBand2 = 0.0
        // A soft contact must not span the fundamental's Fourier zero at a high TUNE.
        // The width cap keeps wood, rather than normalization or a later owl, carrying the note.
        private val contactFrames = (minOf(.0028 - .0021 * strike, .65 / root) * INTERNAL_RATE).roundToInt().coerceAtLeast(8)
        // THWACK's "thhh" is rough pressure on wood before its short "wack/k" release.
        // Every sample goes through the tree modes; there is no separate noise output layer.
        private val scrubFrames = if (voice == MurkVoice.THWACK) ((.020 + .025 * strike) * INTERNAL_RATE).roundToInt() else 0
        private val scrubContact = doubleArrayOf(.10, .62, .58, .52, 0.0, 0.0).also { b ->
            val norm = sqrt(b.sumOf { it * it })
            for (i in b.indices) b[i] /= norm
        }
        private val scrubHighPass = 1.0 - exp(-2.0 * PI * .70 * root / INTERNAL_RATE)
        private val scrubLowPass = 1.0 - exp(-2.0 * PI * minOf(9.0 * root, 9_000.0) / INTERNAL_RATE)
        private val scrubKick = if (scrubFrames > 0) .78 * (.55 + .45 * strike) / sqrt(scrubFrames.toDouble()) else 0.0
        private val gestureFrames = mutableListOf<Pair<Int, Int>>()
        private val owlRelax = exp(-1.0 / ((.50 + .65 * grove) * INTERNAL_RATE))
        private val pendingFloor = (.10 + .17 * (1 - agitation) + .07 * grove) * INTERNAL_RATE
        private val frontTransfer = .42 + .25 * fog + .12 * grove

        private fun impact(frame: Int, tree: Int, strength: Double) {
            val compression = strength * (.68 + .4 * strike + .48 * strike * fog) * (1 + .15 * grove)
            trees[tree].stimulate(compression * (.15 + .6 * fog))
            stimulateOwl(tree, strength * (.63 + .35 * strike), frame, 0)
            event(frame, tree, compression, 0, tree, false)
        }

        private fun event(frame: Int, tree: Int, value: Double, generation: Int, origin: Int, fromCall: Boolean) {
            if (!options.linksEnabled || value < .012) return
            val degree = if (tree == 0 || tree == 3) 1 else 2
            for (destination in intArrayOf(tree - 1, tree + 1)) if (destination in 0 until TREES) {
                val edge = minOf(tree, destination)
                frontQueue.add(Front(frame + travel[edge].roundToInt(), destination, tree, value * frontTransfer / degree,
                    origin, generation, fromCall, 1, frontSerial++))
            }
        }

        private fun stimulateOwl(tree: Int, value: Double, frame: Int, generation: Int, returningOwnCall: Boolean = false) {
            if (!options.owlsEnabled || agitation <= 0.0 || generation > MAX_CALL_GENERATION) return
            val owl = owls[tree]
            val influence = if (returningOwnCall) .3 else 1.0
            owl.agitation = (owl.agitation + value * (.34 + 1.22 * agitation) * influence).coerceAtMost(1.8)
            val threshold = .28 - .12 * agitation + .025 * tree
            if (repeatingCalls != null) return
            if (owl.pending < 0 && frame >= owl.refractoryUntil && !owl.active && owl.remaining > 0 && budget > 0 && owl.agitation >= threshold) {
                val latency = (pendingFloor * (1.0 + .12 * tree) * (1.0 - .32 * strike * agitation)).roundToInt()
                owl.pending = frame + latency
                owl.pendingGeneration = generation
                owl.stimulusFrame = frame
            }
        }

        private fun call(frame: Int, tree: Int, generation: Int, forced: PlannedCall? = null) {
            if (!options.owlsEnabled) return
            val owl = owls[tree]
            val state = forced?.agitation ?: owl.agitation.coerceIn(0.0, 1.0)
            val strength = forced?.strength ?: (.13 + .28 * agitation + .16 * state + .045 * strike * agitation) * s.owl
            val cost = strength * strength * (.55 - .24 * state)
            owl.pending = -1
            if (budget <= 0 || owl.remaining <= 0 || cost > vocalEnergy) return
            if (forced != null) check(!owl.active && frame >= owl.refractoryUntil) { "MURK recurring owl schedule overlaps its refractory interval" }
            budget = (budget - 1).coerceAtLeast(0)
            vocalEnergy = (vocalEnergy - cost).coerceAtLeast(0.0)
            owl.remaining = (owl.remaining - 1).coerceAtLeast(0)
            val stimulus = forced?.stimulusFrame ?: owl.stimulusFrame
            val articulation = (agitation * .73 + state * .27 + strike * agitation * .09).coerceIn(0.0, 1.0)
            owl.begin(strength, articulation, Dsp.seedFor(seed, tree, if (cycle > 0) frame % cycle else frame, generation))
            val refractory = MIN_REFRACTORY_SECONDS + .65 * (1 - agitation) + .32 * articulation.pow(4.0) + .09 * tree
            owl.refractoryUntil = frame + (refractory * INTERNAL_RATE).roundToInt()
            owl.agitation *= .35
            planned.add(PlannedCall(frame, tree, state, strength, generation, stimulus))
            if (options.recordDiagnostics) calls.add(CallEvent(frame.toFloat() / INTERNAL_RATE, tree, state.toFloat(), strength.toFloat(), generation, budget, stimulus.toFloat() / INTERNAL_RATE))
            if (options.owlToFogEnabled && generation < MAX_CALL_GENERATION) event(frame, tree, strength * (.65 + .6 * fog), generation + 1, tree, true)
        }

        private fun passiveEnergy(): Double = trees.sumOf { it.energy() } + links.sumOf { it.energy() }

        fun run(totalFrames: Int, captureStart: Int, captureAudio: Boolean = true): RunResult {
            val taps = Array(6) { if (captureAudio && (it == 0 || options.recordDiagnostics)) FloatArray(totalFrames - captureStart) else FloatArray(0) }
            if (cycle > 0) {
                var base = 0
                while (base < totalFrames) {
                    gestureFrames.add(base to 0)
                    // A second, lighter gesture makes the grove recur without raising feedback.
                    if (m.getValue("HOLD") > .995f && voice == MurkVoice.ALARM) gestureFrames.add(base + (cycle * .56).toInt() to 1)
                    base += cycle
                }
            } else {
                gestureFrames.add(0 to 0)
                val hold = m.getValue("HOLD")
                if (hold > .20f) {
                    val count = 1 + ((hold - .20f) * 3.5f).toInt()
                    val spacing = ((.95 + .8 * grove + .3 * fog) * INTERNAL_RATE).roundToInt()
                    for (i in 1..count) if (i * spacing < totalFrames / 2) gestureFrames.add(i * spacing to (if (i % 3 == 0) 1 else 0))
                }
            }
            var gestureIndex = 0
            var repeatingCallIndex = 0
            for (frame in 0 until totalFrames) {
                if (frame % 8192 == 0 && cancelled()) throw CancellationException("MURK render cancelled")
                if (cycle > 0 && frame % cycle == 0) {
                    // Biological source replenishment is bounded and periodic; no acoustic state resets.
                    budget = MAX_CALLS
                    vocalEnergy = 4.8
                    for (owl in owls) owl.remaining = 4
                    repeatingCallIndex = 0
                }
                val gesture = if (gestureIndex < gestureFrames.size && gestureFrames[gestureIndex].first == frame) gestureFrames[gestureIndex++].second else null
                if (gesture != null && options.primaryStrikeEnabled && energy > 0.0) {
                    primaryFrame = frame
                    primaryTree = gesture
                    primaryStrength = energy * if (gesture == 0) 1.0 else .58
                    contactNoise = Dsp.Noise(Dsp.seedFor(seed, gesture, if (cycle > 0) frame % cycle else frame))
                    scrubLow = 0.0
                    scrubBand = 0.0
                    scrubBand2 = 0.0
                    if (scrubFrames == 0) impact(frame, gesture, primaryStrength)
                    if (options.recordDiagnostics) gestures.add(GestureEvent(frame.toFloat() / INTERNAL_RATE, gesture, primaryStrength.toFloat()))
                }
                if (scrubFrames > 0 && primaryFrame >= 0 && frame == primaryFrame + scrubFrames) impact(frame, primaryTree, primaryStrength)
                while (frontQueue.isNotEmpty() && frontQueue.peek().frame <= frame) {
                    val front = frontQueue.remove()
                    trees[front.destination].stimulate(front.strength * (.18 + .62 * fog))
                    stimulateOwl(front.destination, front.strength, frame, front.generation, front.fromCall && front.origin == front.destination)
                    if (options.recordDiagnostics) arrivals.add(Arrival(frame.toFloat() / INTERNAL_RATE, front.destination, front.strength.toFloat(), front.origin, front.generation, front.fromCall))
                    if (front.hops < MAX_FRONT_HOPS && front.strength > .027) {
                        // Strength splits at an interior junction. No destination receives a full copy.
                        val degree = if (front.destination == 0 || front.destination == 3) 1 else 2
                        for (destination in intArrayOf(front.destination - 1, front.destination + 1)) if (destination in 0 until TREES) {
                            val returning = destination == front.previous
                            val gain = frontTransfer * (if (returning) .55 + .20 * grove else 1.0) / degree
                            val value = front.strength * gain
                            if (value >= .012) frontQueue.add(front.copy(frame = frame + travel[minOf(destination, front.destination)].roundToInt(), destination = destination,
                                previous = front.destination, strength = value, hops = front.hops + 1, serial = frontSerial++))
                        }
                    }
                }
                if (options.isolatedCall && frame == (INTERNAL_RATE * .1).toInt()) {
                    owls[0].stimulusFrame = frame - (INTERNAL_RATE * .05).toInt()
                    call(frame, 0, 0, PlannedCall(frame, 0, .55 + .4 * agitation, .30 * s.owl, 0, owls[0].stimulusFrame))
                }
                for (owl in owls) owl.agitation *= owlRelax
                if (repeatingCalls != null && cycle > 0) {
                    val phase = frame % cycle
                    while (repeatingCallIndex < repeatingCalls.size && repeatingCalls[repeatingCallIndex].frame == phase) {
                        val planned = repeatingCalls[repeatingCallIndex++]
                        call(frame, planned.tree, planned.generation, planned.copy(frame = frame, stimulusFrame = frame - planned.frame + planned.stimulusFrame))
                    }
                } else for (tree in 0 until TREES) {
                    val owl = owls[tree]
                    if (owl.pending >= 0 && frame >= owl.pending) call(frame, tree, owl.pendingGeneration)
                }
                if (frame % CONTROL_FRAMES == 0) for (tree in trees) tree.load()
                for (tree in trees) tree.step()
                val contactTime = frame - primaryFrame
                if (primaryFrame >= 0 && contactTime in 0 until scrubFrames) {
                    val t = (contactTime + .5) / scrubFrames
                    val grain = contactNoise.next().toDouble()
                    scrubLow += scrubHighPass * (grain - scrubLow)
                    scrubBand += scrubLowPass * (grain - scrubLow - scrubBand)
                    scrubBand2 += scrubLowPass * (scrubBand - scrubBand2)
                    val pressure = sin(PI * t).pow(1.4)
                    // sqrt(N) keeps the finite rough-contact energy from growing with duration.
                    // This is a modal force, so its woody pitch and decay belong to the trunk.
                    trees[primaryTree].direct.drive(scrubContact, primaryStrength * scrubKick * INTERNAL_RATE * pressure * scrubBand2)
                }
                val snapTime = contactTime - scrubFrames
                if (primaryFrame >= 0 && snapTime in 0 until contactFrames) {
                    val t = (snapTime + .5) / contactFrames
                    val pulse = sin(PI * t)
                    val texture = 1 + (.025 + .045 * strike) * contactNoise.next() * sin(PI * t)
                    // Integral of the half sine is 2/pi; contact character changes its duration,
                    // upper-mode vector and texture, while velocity controls impulse/compression.
                    val impulse = .57 * primaryStrength * (1.0 + .14 * strike) / (1.0 + .16 * trunk)
                    val force = impulse * PI / (2 * contactFrames) * INTERNAL_RATE * pulse * texture
                    trees[primaryTree].direct.drive(trees[primaryTree].contact, force)
                }
                for (i in incoming.indices) incoming[i] = if (options.linksEnabled) links[i].read() else 0.0
                var airAudio = 0.0
                if (options.linksEnabled) for (tree in 0 until TREES) {
                    val node = trees[tree]
                    val ports = NODE_PORTS[tree]
                    val k = node.portK * (1.0 + .16 * grove + .25 * node.pressure)
                    val v0 = sqrt(k)
                    val vl = sqrt((1 - k) / ports.size)
                    val xd = node.direct.velocityAlong(node.contact)
                    val xr = node.returns.velocityAlong(node.contact)
                    var dot = v0 * (xd + xr)
                    for (port in ports) dot += vl * incoming[port]
                    val directDelta = -2 * k * xd
                    val returnDelta = -2 * v0 * dot - directDelta
                    node.direct.drive(node.contact, directDelta * INTERNAL_RATE)
                    node.returns.drive(node.contact, returnDelta * INTERNAL_RATE)
                    for (port in ports) {
                        // Opposite directed link is outgoing from this junction.
                        outgoing[port xor 1] = incoming[port] - 2 * vl * dot
                        airAudio += incoming[port]
                    }
                }
                var owlAudio = 0.0
                var owlFog = 0.0
                for (tree in 0 until TREES) {
                    val sound = if (options.owlsEnabled) owls[tree].tick() else 0.0
                    owlAudio += sound / (1 + .15 * tree)
                    if (options.linksEnabled && options.owlToFogEnabled) {
                        val injection = sound * (.022 + .046 * fog) * OWL_PORT_EMISSION / sqrt(if (tree == 0 || tree == 3) 1.0 else 2.0)
                        if (tree > 0) outgoing[(tree - 1) * 2 + 1] += injection
                        if (tree < 3) outgoing[tree * 2] += injection
                        owlFog += injection
                    }
                }
                if (options.linksEnabled) for (i in links.indices) links[i].write(outgoing[i])
                if (captureAudio && frame >= captureStart) {
                    val i = frame - captureStart
                    val direct = trees.sumOf { it.directAudio() } * s.wood * OUTPUT_TRIM
                    val returns = trees.sumOf { it.returnAudio() } * s.wood * OUTPUT_TRIM * RETURN_RADIATION
                    // Traveling waves are energy-normalized per sample; this pickup restores an
                    // audible pressure body without feeding the pickup gain into the network.
                    val atmosphere = airAudio * (1.9 + 4.0 * fog + 2.2 * grove) * s.atmosphere * OUTPUT_TRIM * ATMOSPHERE_RADIATION
                    val audibleOwls = if (options.owlAudioEnabled) owlAudio * OUTPUT_TRIM else 0.0
                    taps[0][i] = (direct + returns + atmosphere + audibleOwls).toFloat()
                    if (options.recordDiagnostics) {
                        taps[1][i] = direct.toFloat()
                        taps[2][i] = returns.toFloat()
                        taps[3][i] = atmosphere.toFloat()
                        taps[4][i] = audibleOwls.toFloat()
                        taps[5][i] = (owlFog * OUTPUT_TRIM).toFloat()
                    }
                }
                if (options.recordDiagnostics && frame % (INTERNAL_RATE / 20) == 0) snapshots.add(StateSnapshot(frame.toFloat() / INTERNAL_RATE,
                    FloatArray(TREES) { owls[it].agitation.toFloat() }, budget, vocalEnergy.toFloat(), passiveEnergy()))
            }
            return RunResult(taps, arrivals, calls, gestures, snapshots, passiveEnergy(), planned.filter { it.frame >= captureStart })
        }
    }
}
