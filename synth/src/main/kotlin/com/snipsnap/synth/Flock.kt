package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

enum class FlockVoice { GATHER, RIBBON, SPLIT }

/** Listening controls: freeze the initial cloud, remove neighbors, or run the complete model. */
enum class FlockMode { FLOCK, FIXED, INDEPENDENT }

/**
 * A finite, seeded group of 24 audible oscillators. The simulation runs at 420 Hz,
 * with pitch measured in semitones and energy in a second dimension. Every force
 * reads one complete previous snapshot. Audio interpolates the resulting coordinates;
 * neither the neighbor graph nor the group motion is simulated by the stereo field.
 *
 * HOLD selects a finite 2–6 second performance. Model 2 gives FLOW a simulation
 * clock as well as its original neighbor response, anchored to the model-1 defaults.
 */
object Flock {
    const val MODEL_VERSION = 2
    const val PARTICLES = 24
    const val CONTROL_RATE = 420
    const val MIN_MIDI = 36
    const val MAX_MIDI = 84
    const val DEFAULT_SEED = 0x464C4F434BL
    const val PERTURBATION_SECONDS = 1.0
    const val MAX_NEIGHBORS = 4
    private const val TABLE_BITS = 15
    private const val TABLE_SIZE = 1 shl TABLE_BITS
    private val sine = DoubleArray(TABLE_SIZE + 1) { sin(2.0 * PI * it / TABLE_SIZE) }

    data class TraceFrame(val seconds: Double, val pitchSemitones: List<Double>, val energy: List<Double>)

    /** Directed graph: each listed receiver reads particle zero in this step's old snapshot. */
    data class NeighborFrame(val seconds: Double, val receiversOfZero: List<Int>)

    data class Diagnostics(
        val durationSeconds: Double,
        val rawPeak: Double,
        val rawRms: Double,
        val outputPeak: Double,
        val minFrequencyHz: Double,
        val maxFrequencyHz: Double,
        /** Maximum pitch speed in semitones per wall-clock second. */
        val maxSpeed: Double,
        val neighborMean: Double,
        val perturbationSeconds: Double?,
        val directNeighborsAtImpulse: List<Int>,
        val perturbationNeighbors: List<NeighborFrame>,
        val motionRate: Double = 1.0,
    )

    data class Probe(
        val snip: Snip,
        /** Decimated mono audio before the common perceived-loudness stage. */
        val rawSamples: FloatArray,
        val trace: List<TraceFrame>,
        val diagnostics: Diagnostics,
    ) {
        val samples get() = snip.samples
        val sampleRate get() = snip.sampleRate
    }

    fun rootMidi(voice: FlockVoice): Int = 60

    fun macrosFor(voice: FlockVoice): List<MacroSpec> {
        val d = when (voice) {
            FlockVoice.GATHER -> floatArrayOf(.55f, .73f, .48f, .86f, .30f, .45f)
            FlockVoice.RIBBON -> floatArrayOf(.62f, .28f, .78f, .52f, .46f, .72f)
            FlockVoice.SPLIT -> floatArrayOf(.44f, .54f, .62f, .68f, .35f, .65f)
        }
        return listOf("SPREAD", "PULL", "FLOW", "SCATTER", "EDGE", "HOLD").mapIndexed { i, name ->
            MacroSpec(name, d[i], if (name == "HOLD") 0f else .5f)
        }
    }

    fun defaults(voice: FlockVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    fun supportsModel(model: Int): Boolean = model in 1..MODEL_VERSION

    /**
     * FLOW slows or accelerates the existing group equations, rather than adding a
     * modulation source. Each original voice default is an exact 1x anchor, so its
     * accepted sound is unchanged. Saved model-1 performances always retain 1x time.
     */
    fun motionRate(voice: FlockVoice, flow: Float, model: Int = MODEL_VERSION): Double {
        require(supportsModel(model)) { "unsupported flock model $model" }
        require(flow.isFinite() && flow in 0f..1f) { "FLOCK FLOW must be 0..1" }
        if (model == 1) return 1.0
        val anchor = defaults(voice).getValue("FLOW")
        if (flow == anchor) return 1.0
        return if (flow < anchor) .12.pow(1.0 - flow.toDouble() / anchor.toDouble())
        else 4.0.pow((flow.toDouble() - anchor.toDouble()) / (1.0 - anchor.toDouble()))
    }

    internal fun settled(macros: Map<String, Float>, voice: FlockVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((key, value) in macros) {
            require(key in m) { "unknown FLOCK macro $key" }
            require(value.isFinite() && value in 0f..1f) { "FLOCK $key must be in 0..1" }
            m[key] = value
        }
        return m
    }

    fun drumClassFor(voice: FlockVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        settled(macros, voice)
        return DrumClass.TONAL
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    private fun wave(phase: Double): Double {
        val p = (phase - floor(phase)) * TABLE_SIZE
        val i = p.toInt()
        return sine[i] + (sine[i + 1] - sine[i]) * (p - i)
    }

    private class Cloud(
        val voice: FlockVoice, val m: Map<String, Float>, velocity: Float, seed: Long,
        val mode: FlockMode, val perturb: Boolean, val motionRate: Double,
    ) {
        val spread = m.getValue("SPREAD").toDouble()
        val pull = m.getValue("PULL").toDouble()
        val flow = m.getValue("FLOW").toDouble()
        val scatter = m.getValue("SCATTER").toDouble() * velocity
        val random = Random(seed)
        val group = IntArray(PARTICLES) { if (voice == FlockVoice.SPLIT) it / 8 else 0 }
        val anchor = DoubleArray(PARTICLES) { when (group[it]) { 1 -> 7.01955; 2 -> 12.0; else -> 0.0 } }
        var pitch = DoubleArray(PARTICLES)
        var energy = DoubleArray(PARTICLES)
        var pitchSpeed = DoubleArray(PARTICLES)
        var energySpeed = DoubleArray(PARTICLES)
        var nextPitch = DoubleArray(PARTICLES)
        var nextEnergy = DoubleArray(PARTICLES)
        var nextPitchSpeed = DoubleArray(PARTICLES)
        var nextEnergySpeed = DoubleArray(PARTICLES)
        val drivePhase = DoubleArray(PARTICLES)
        val driveRate = DoubleArray(PARTICLES)
        val oscillatorPhase = DoubleArray(PARTICLES)
        var maxSpeed = 0.0
        var totalNeighbors = 0.0
        var neighborObservations = 0
        val perturbationNeighbors = mutableListOf<NeighborFrame>()
        var directNeighborsAtImpulse: List<Int> = emptyList()
        val nearest = IntArray(MAX_NEIGHBORS)
        val nearestDistance = DoubleArray(MAX_NEIGHBORS)

        init {
            val width = .045 + spread * (.24 + 4.2 * scatter)
            for (i in 0 until PARTICLES) {
                pitch[i] = anchor[i] + (random.nextDouble() * 2.0 - 1.0) * width
                energy[i] = (random.nextDouble() * 2.0 - 1.0) * (.12 + .52 * scatter)
                pitchSpeed[i] = (random.nextDouble() * 2.0 - 1.0) * (0.12 + 4.0 * scatter)
                energySpeed[i] = (random.nextDouble() * 2.0 - 1.0) * .7 * scatter
                drivePhase[i] = random.nextDouble() * 2.0 * PI
                driveRate[i] = .19 + random.nextDouble() * .41
                oscillatorPhase[i] = random.nextDouble()
            }
        }

        /** One synchronous, time-scaled semi-implicit step. No particle reads a newly written neighbor. */
        fun advance(tick: Int) {
            if (mode == FlockMode.FIXED) {
                pitch.copyInto(nextPitch); energy.copyInto(nextEnergy)
                return
            }
            val dt = (1.0 / CONTROL_RATE) * motionRate
            val time = tick * dt
            val wallTime = tick * (1.0 / CONTROL_RATE)
            val radius = 1.15 + 1.8 * flow + 1.1 * spread
            val radius2 = radius * radius
            val damping = if (voice == FlockVoice.RIBBON) .65 + 1.7 * pull else .95 + 2.1 * pull
            val decay = exp(-damping * dt)
            val recordGraph = tick in CONTROL_RATE..(CONTROL_RATE * 5 / 4)
            val zeroReceivers = if (recordGraph) mutableListOf<Int>() else null
            for (i in 0 until PARTICLES) {
                var weight = 0.0
                var alignPitch = 0.0; var alignEnergy = 0.0
                var coherePitch = 0.0; var cohereEnergy = 0.0
                var separatePitch = 0.0; var separateEnergy = 0.0
                var neighbors = 0
                if (mode == FlockMode.FLOCK) {
                    nearest.fill(-1); nearestDistance.fill(radius2)
                    // A local directed nearest-four graph stays local even after the flock
                    // gathers. Ranking reads the same old state as every force; index order
                    // breaks exact-distance ties deterministically. Positions may reorder it.
                    for (j in 0 until PARTICLES) {
                        if (i == j) continue
                        val dx = pitch[j] - pitch[i]
                        val dy = (energy[j] - energy[i]) * 1.3
                        val d2 = dx * dx + dy * dy
                        var slot = MAX_NEIGHBORS
                        for (k in 0 until MAX_NEIGHBORS) if (d2 < nearestDistance[k]) { slot = k; break }
                        if (slot == MAX_NEIGHBORS) continue
                        for (k in MAX_NEIGHBORS - 1 downTo slot + 1) {
                            nearest[k] = nearest[k - 1]; nearestDistance[k] = nearestDistance[k - 1]
                        }
                        nearest[slot] = j; nearestDistance[slot] = d2
                    }
                    if (zeroReceivers != null && 0 in nearest) zeroReceivers += i
                    for (k in 0 until MAX_NEIGHBORS) {
                    val j = nearest[k]
                    if (j == -1) continue
                    val dx = pitch[j] - pitch[i]
                    val d2 = nearestDistance[k]
                    val w = (1.0 - d2 / radius2).pow(2)
                    weight += w; neighbors++
                    alignPitch += w * (pitchSpeed[j] - pitchSpeed[i])
                    alignEnergy += w * (energySpeed[j] - energySpeed[i])
                    coherePitch += w * dx
                    cohereEnergy += w * (energy[j] - energy[i])
                    // Soft finite repulsion has no singularity at an overlap, and fades with distance.
                    val repel = w * exp(-d2 / (.18 + .55 * spread)) / (.12 + d2)
                    separatePitch -= dx * repel
                    separateEnergy -= (energy[j] - energy[i]) * repel
                    }
                }
                totalNeighbors += neighbors
                neighborObservations++
                if (weight > 1e-10) {
                    alignPitch /= weight; alignEnergy /= weight
                    coherePitch /= weight; cohereEnergy /= weight
                    separatePitch /= weight; separateEnergy /= weight
                }
                val phase = 2.0 * PI * driveRate[i] * time + drivePhase[i]
                val activity = when (voice) {
                    FlockVoice.GATHER -> .10 + .34 * exp(-time / .65)
                    FlockVoice.RIBBON -> .72
                    FlockVoice.SPLIT -> .36
                }
                // A weak individual drive is also present in INDEPENDENT. The comparison therefore
                // removes only the three neighbor terms, rather than substituting another wanderer.
                val drive = activity * (.3 + 2.0 * spread) * sin(phase)
                val splitDrift = if (voice == FlockVoice.SPLIT)
                    .70 * spread * sin(time * 2.0 + group[i] * 2.0 * PI / 3.0) else 0.0
                val target = anchor[i] + splitDrift
                val tether = (target - pitch[i]) * (1.4 + 10.0 * pull)
                val ax = tether + drive + (1.0 + 9.0 * flow) * alignPitch +
                    (.5 + 2.2 * pull) * coherePitch + (1.0 + 5.0 * spread) * tanh(separatePitch)
                val ay = -energy[i] * (1.8 + 2.0 * pull) + .45 * activity * sin(phase * .73 + 1.2) +
                    (1.0 + 7.0 * flow) * alignEnergy + (1.0 + pull) * cohereEnergy +
                    (.25 + spread) * tanh(separateEnergy)
                val impulse = if (perturb && tick == CONTROL_RATE && i == 0) 9.0 else 0.0
                val vx = ((pitchSpeed[i] + ax.coerceIn(-65.0, 65.0) * dt + impulse) * decay).coerceIn(-12.0, 12.0)
                val vy = ((energySpeed[i] + ay.coerceIn(-20.0, 20.0) * dt) * decay).coerceIn(-3.0, 3.0)
                nextPitchSpeed[i] = vx; nextEnergySpeed[i] = vy
                nextPitch[i] = (pitch[i] + vx * dt).coerceIn(anchor[i] - 7.0, anchor[i] + 7.0)
                nextEnergy[i] = (energy[i] + vy * dt).coerceIn(-1.0, 1.0)
                maxSpeed = max(maxSpeed, abs(vx) * motionRate)
            }
            if (zeroReceivers != null) {
                perturbationNeighbors += NeighborFrame(wallTime, zeroReceivers.toList())
                if (tick == CONTROL_RATE) directNeighborsAtImpulse = zeroReceivers.toList()
            }
        }

        fun commit() {
            var old = pitch; pitch = nextPitch; nextPitch = old
            old = energy; energy = nextEnergy; nextEnergy = old
            if (mode != FlockMode.FIXED) {
                old = pitchSpeed; pitchSpeed = nextPitchSpeed; nextPitchSpeed = old
                old = energySpeed; energySpeed = nextEnergySpeed; nextEnergySpeed = old
            }
        }

        fun trace(tick: Int) = TraceFrame(tick.toDouble() / CONTROL_RATE, pitch.toList(), energy.toList())
    }

    private fun stopIfUnwanted(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("FLOCK render no longer wanted")
    }

    fun renderDetailed(
        voice: FlockVoice,
        macros: Map<String, Float> = defaults(voice),
        midi: Int = 60,
        velocity: Float = 1f,
        seed: Long = DEFAULT_SEED,
        mode: FlockMode = FlockMode.FLOCK,
        perturb: Boolean = false,
        cancelled: () -> Boolean = { false },
        oversample: Int = 4,
        model: Int = MODEL_VERSION,
    ): Probe {
        require(supportsModel(model)) { "unsupported flock model $model" }
        require(midi in MIN_MIDI..MAX_MIDI) { "FLOCK midi must be $MIN_MIDI..$MAX_MIDI" }
        require(velocity.isFinite() && velocity in 0f..1f) { "FLOCK velocity must be 0..1" }
        require(oversample == 1 || oversample == 4) { "FLOCK oversample must be 1 or 4" }
        stopIfUnwanted(cancelled)
        val m = settled(macros, voice)
        val seconds = 2.0 + 4.0 * m.getValue("HOLD")
        val rate = Dsp.RATE * oversample
        val block = rate / CONTROL_RATE
        check(block * CONTROL_RATE == rate)
        val frames = (seconds * rate).roundToInt()
        val raw = FloatArray(frames)
        val motionRate = motionRate(voice, m.getValue("FLOW"), model)
        val cloud = Cloud(voice, m, velocity, seed, mode, perturb, motionRate)
        val trace = mutableListOf(cloud.trace(0))
        val hz = Keys.midiHz(midi).toDouble()
        val edge = m.getValue("EDGE").toDouble()
        val frequency = DoubleArray(PARTICLES)
        val frequencyRatio = DoubleArray(PARTICLES)
        val energy = DoubleArray(PARTICLES)
        val energyStep = DoubleArray(PARTICLES)
        var minHz = Double.POSITIVE_INFINITY
        var maxHz = 0.0
        val attack = if (voice == FlockVoice.RIBBON) .045 else .012
        val release = .22 + .18 * m.getValue("HOLD")
        var tick = 0
        var frame = 0
        while (frame < frames) {
            if (tick % 10 == 0) stopIfUnwanted(cancelled)
            cloud.advance(tick)
            for (i in 0 until PARTICLES) {
                val start = hz * 2.0.pow(cloud.pitch[i] / 12.0)
                val end = hz * 2.0.pow(cloud.nextPitch[i] / 12.0)
                frequency[i] = start
                frequencyRatio[i] = 2.0.pow((cloud.nextPitch[i] - cloud.pitch[i]) / (12.0 * block))
                energy[i] = cloud.energy[i]; energyStep[i] = (cloud.nextEnergy[i] - energy[i]) / block
                minHz = minOf(minHz, start, end); maxHz = maxOf(maxHz, start, end)
            }
            val count = minOf(block, frames - frame)
            repeat(count) { offset ->
                val time = (frame + offset).toDouble() / rate
                val env = smooth(time / attack) * (1.0 - smooth((time - seconds + release) / release)) *
                    (.82 + .18 * exp(-time / .30)) * velocity
                var sum = 0.0
                for (i in 0 until PARTICLES) {
                    val e = energy[i]
                    val h2 = edge * .48 * (1.0 + .25 * e)
                    val h3 = edge * edge * .22 * (1.0 + .35 * e)
                    val p = cloud.oscillatorPhase[i]
                    val carrier = (wave(p) + h2 * wave(2.0 * p) + h3 * wave(3.0 * p)) / (1.0 + h2 + h3)
                    sum += carrier * (.78 + .20 * e)
                    val phase = p + frequency[i] / rate
                    cloud.oscillatorPhase[i] = phase - floor(phase)
                    frequency[i] *= frequencyRatio[i]; energy[i] += energyStep[i]
                }
                // A strict sum budget stays safe even when every oscillator becomes correlated.
                raw[frame + offset] = (sum * (.70 / PARTICLES) * env).toFloat()
            }
            frame += count
            cloud.commit(); tick++
            if (tick % 10 == 0 || tick in CONTROL_RATE..(CONTROL_RATE * 5 / 4) || frame == frames) trace += cloud.trace(tick)
        }
        stopIfUnwanted(cancelled)
        val native = if (oversample == 4) Dsp.decimate(raw, Dsp.RATE) else raw
        Dsp.fadeTail(native)
        val samples = native.copyOf()
        // The shared project loudness measurement leaves scatter and motion intact; velocity
        // retains an amplitude component after leveling as well as disturbing the initial state.
        Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.toDouble().pow(.65).toFloat(), ceiling = .94f)
        stopIfUnwanted(cancelled)
        var peak = 0.0; var squares = 0.0; var outPeak = 0.0
        for (i in native.indices) {
            val x = native[i].toDouble()
            check(x.isFinite() && samples[i].isFinite()) { "FLOCK produced non-finite audio" }
            peak = max(peak, abs(x)); squares += x * x
            outPeak = max(outPeak, abs(samples[i].toDouble()))
        }
        return Probe(Snip(samples, channels = 1, sampleRate = Dsp.RATE), native, trace,
            Diagnostics(native.size.toDouble() / Dsp.RATE, peak, sqrt(squares / max(1, native.size)), outPeak,
                minHz, maxHz, cloud.maxSpeed,
                if (cloud.neighborObservations == 0) 0.0 else cloud.totalNeighbors / cloud.neighborObservations,
                if (perturb && mode != FlockMode.FIXED) PERTURBATION_SECONDS else null,
                cloud.directNeighborsAtImpulse, cloud.perturbationNeighbors.toList(), motionRate))
    }

    fun render(
        voice: FlockVoice,
        macros: Map<String, Float> = defaults(voice),
        midi: Int = 60,
        velocity: Float = 1f,
        seed: Long = DEFAULT_SEED,
        mode: FlockMode = FlockMode.FLOCK,
        perturb: Boolean = false,
        cancelled: () -> Boolean = { false },
        model: Int = MODEL_VERSION,
    ): Snip = renderDetailed(voice, macros, midi, velocity, seed, mode, perturb, cancelled, model = model).snip
}
