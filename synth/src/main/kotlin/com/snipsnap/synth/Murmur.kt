package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** Musical roles for an original FM and moving-formant instrument; no sampled vowels. */
enum class MurmurVoice { BASS, KEY, PLUCK, LEAD, CHOIR, METAL }

/** Acyclic four-operator phase-modulation graphs. Indices are radians, never Hz. */
enum class MurmurRouting { PARALLEL, PAIRS, STACK }
enum class MurmurTrajectory { ATTACK, CYCLE }

/** Absolute-Hz spectral targets. Widths are bandwidths in Hz and gains are linear amplitude. */
data class MurmurFormantTarget(
    val name: String,
    val centers: List<Double>,
    val widths: List<Double>,
    val gains: List<Double>,
    val sourceBalance: Double,
) {
    init {
        require(name.isNotBlank()) { "a Murmur target needs a name" }
        require(centers.size in 4..5 && widths.size == centers.size && gains.size == centers.size) {
            "Murmur targets need four or five matching bands"
        }
        require(centers.all { it.isFinite() && it in 150.0..6_000.0 }) { "invalid Murmur center frequency" }
        require(centers.zipWithNext().all { (a, b) -> a < b }) { "Murmur band centers must ascend" }
        require(widths.all { it.isFinite() && it in 60.0..2_000.0 }) { "invalid Murmur bandwidth" }
        require(gains.all { it.isFinite() && it in 0.0..3.0 }) { "invalid Murmur formant gain" }
        require(sourceBalance.isFinite() && sourceBalance in 0.0..1.0) { "invalid Murmur source balance" }
    }
}

/**
 * Complete, serializable patch identity. Ratios belong to the patch and never change on a macro
 * sweep. Envelope times are seconds; sustain is amplitude. Direction is exactly -1 or +1.
 * Lists are read-only values: render takes a validated defensive snapshot before processing.
 */
data class MurmurConfig(
    val routing: MurmurRouting,
    val ratios: List<Double>,
    val trajectory: MurmurTrajectory,
    val direction: Int,
    val referenceSeconds: Double,
    val attack: Double,
    val decay: Double,
    val sustain: Double,
    val release: Double,
    val targets: List<MurmurFormantTarget>,
) {
    init {
        require(ratios.size == 4 && ratios.all { it.isFinite() && it in .5..8.0 }) { "Murmur needs four bounded operator ratios" }
        require(ratios[0] == 1.0) { "Murmur's principal carrier must bear the requested root" }
        require(direction == -1 || direction == 1) { "Murmur trajectory direction must be -1 or +1" }
        require(referenceSeconds.isFinite() && referenceSeconds in .1..3.0) { "invalid Murmur trajectory time" }
        require(attack.isFinite() && attack in .001..1.0) { "invalid Murmur attack" }
        require(decay.isFinite() && decay in .02..3.0) { "invalid Murmur decay" }
        require(sustain.isFinite() && sustain in .01..1.0) { "invalid Murmur sustain" }
        require(release.isFinite() && release in .02..3.0) { "invalid Murmur release" }
        require(targets.size in 2..8 && targets.map { it.centers.size }.distinct().size == 1) { "Murmur needs a consistent spectral-target path" }
    }
}

/** The raw output and independently simulated adjacent held cycle, before shared leveling. */
internal data class MurmurProbe(
    val samples: FloatArray,
    val previousCycle: FloatArray = FloatArray(0),
    val rootHz: Double,
    val seamError: Double = 0.0,
)

/**
 * Four phase operators excite five continuously retuned TPT state-variable formants. A
 * root-locked, polyBLEP ramp with its fundamental removed supplies voiced upper harmonics when
 * EDGE makes the operators almost sinusoidal, so moving bands have useful energy to shape.
 * The direct fundamental and direct FM body preserve pitch outside the formants. Seeded breath
 * and a finite contact accent drive the same bank, so mouth motion shapes both sources together.
 *
 * This is a resonant-bank simplification inspired by FM/formant instruments, not FS1R emulation.
 * Coefficients move at a bounded control rate with samplewise interpolation. No feedback graph,
 * speech input, samples, cross-note state, chords, or unbounded modulation is involved.
 */
object Murmur {
    const val DEFAULT_MIDI = 60
    const val MIN_MIDI = 36
    const val MAX_MIDI = 96
    const val LOOP_THRESHOLD = .99f
    const val MAX_SECONDS = 8.0
    internal const val MAX_INDEX_RADIANS = 4.5
    internal const val MAX_FORMANT_Q = 16.0
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val CONTROL_STRIDE = 64

    private data class Shape(val mouth: Float, val edge: Float, val breath: Float, val motion: Float, val body: Float, val gate: Double)
    private fun shape(voice: MurmurVoice) = when (voice) {
        MurmurVoice.BASS -> Shape(.25f, .35f, .08f, .25f, .75f, 1.25)
        MurmurVoice.KEY -> Shape(.40f, .40f, .12f, .25f, .55f, 2.10)
        MurmurVoice.PLUCK -> Shape(.35f, .55f, .10f, .35f, .45f, .95)
        MurmurVoice.LEAD -> Shape(.55f, .45f, .20f, .45f, .50f, 3.10)
        MurmurVoice.CHOIR -> Shape(.45f, .20f, .30f, .30f, .65f, 3.75)
        MurmurVoice.METAL -> Shape(.35f, .75f, .15f, .40f, .40f, 2.00)
    }

    fun macrosFor(voice: MurmurVoice): List<MacroSpec> {
        val s = shape(voice)
        return listOf(
            MacroSpec("MOUTH", s.mouth, neutral = .40f), MacroSpec("EDGE", s.edge, neutral = .35f),
            MacroSpec("BREATH", s.breath, neutral = .10f), MacroSpec("MOTION", s.motion, neutral = .20f),
            MacroSpec("BODY", s.body, neutral = .50f), MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }
    fun defaults(voice: MurmurVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: MurmurVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (name, default) -> (macros[name]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD
    fun drumClassFor(voice: MurmurVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    fun scramble(voice: MurmurVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = base + (near?.macros?.filterKeys { it in base } ?: emptyMap())
        val amount = (temperature.takeIf { it.isFinite() } ?: .35f).coerceIn(0f, 1f)
        val rolled = Dsp.scrambleNear(seed, amount, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(.95f)
        return settled(voice, rolled)
    }

    /** An original closed hum -> rounded -> open -> narrow target roster, shared by the six roles. */
    private fun originalTargets() = listOf(
        MurmurFormantTarget("Closed hum", listOf(270.0, 800.0, 1_500.0, 2_600.0, 3_800.0),
            listOf(100.0, 180.0, 260.0, 350.0, 500.0), listOf(1.8, .8, .25, .20, .10), .10),
        MurmurFormantTarget("Rounded", listOf(330.0, 750.0, 1_400.0, 2_350.0, 3_500.0),
            listOf(120.0, 160.0, 240.0, 350.0, 450.0), listOf(1.7, .9, .30, .20, .15), .22),
        MurmurFormantTarget("Open", listOf(760.0, 1_250.0, 2_450.0, 3_300.0, 4_400.0),
            listOf(190.0, 200.0, 300.0, 380.0, 520.0), listOf(1.3, 1.4, .65, .40, .15), .55),
        MurmurFormantTarget("Narrow", listOf(360.0, 2_050.0, 2_850.0, 3_800.0, 4_800.0),
            listOf(110.0, 230.0, 350.0, 420.0, 580.0), listOf(1.1, 1.35, .75, .30, .12), .30),
    )
    fun configurationFor(voice: MurmurVoice): MurmurConfig {
        val targets = originalTargets()
        return when (voice) {
            MurmurVoice.BASS -> MurmurConfig(MurmurRouting.PAIRS, listOf(1.0, 1.0, 2.0, 2.0), MurmurTrajectory.ATTACK, 1, .55, .009, .25, .52, .55, targets)
            MurmurVoice.KEY -> MurmurConfig(MurmurRouting.PAIRS, listOf(1.0, 2.0, 2.0, 3.0), MurmurTrajectory.ATTACK, -1, .55, .004, .55, .30, .90, targets)
            MurmurVoice.PLUCK -> MurmurConfig(MurmurRouting.STACK, listOf(1.0, 2.0, 3.0, 4.0), MurmurTrajectory.ATTACK, 1, .35, .003, .28, .12, .45, targets)
            MurmurVoice.LEAD -> MurmurConfig(MurmurRouting.PARALLEL, listOf(1.0, 2.0, 3.0, 1.0), MurmurTrajectory.CYCLE, 1, 1.50, .025, .25, .72, 1.00, targets)
            MurmurVoice.CHOIR -> MurmurConfig(MurmurRouting.PARALLEL, listOf(1.0, 1.0, 2.0, 1.0015), MurmurTrajectory.CYCLE, -1, 2.20, .16, .45, .82, 1.10, targets)
            MurmurVoice.METAL -> MurmurConfig(MurmurRouting.STACK, listOf(1.0, 2.0, 3.0, 5.0), MurmurTrajectory.ATTACK, -1, .65, .004, .65, .20, .90, targets)
        }
    }

    fun render(
        voice: MurmurVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        config: MurmurConfig = configurationFor(voice),
    ): Snip {
        val samples = renderRaw(voice, macros, midi, velocity, config)
        val v = cleanVelocity(velocity)
        if (v > 0.0) {
            Dsp.levelTo(samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            // Excitation still changes bite and softness before leveling; this retains playing dynamics.
            val dynamicGain = v.pow(.65).toFloat()
            for (i in samples.indices) samples[i] *= dynamicGain
        }
        return Snip(samples, channels = 1, sampleRate = RATE)
    }

    internal fun renderRaw(
        voice: MurmurVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        config: MurmurConfig = configurationFor(voice),
    ): FloatArray = probe(voice, macros, midi, velocity, config).samples

    /**
     * HOLD chooses the most accurate whole-root-cycle frame count in a 1.8-2.4 s window. It
     * fits the principal carrier once (well below a cent); subordinate ratios then receive their
     * nearest compatible cycle count. Integer and half-integer ratios stay exact by requiring an
     * even root count. For a patch's mild detune, compatibility costs <= half a cycle per loop,
     * never a coarse fixed-Hz quantization of the musical root. Noise repeats a whole table.
     */
    private data class LoopPlan(val frames: Int, val root: Double, val cycles: Int, val ratios: DoubleArray)
    private fun planLoop(hz: Double, ratios: List<Double>): LoopPlan {
        val low = (hz * 1.8).roundToInt().coerceAtLeast(2)
        val high = (hz * 2.4).roundToInt().coerceAtLeast(low + 2)
        var bestCycles = if (low % 2 == 0) low else low + 1
        // A control block divides every loop exactly, so coefficient interpolation itself also
        // repeats. Search carrier counts rather than accepting the first convenient duration.
        var bestFrames = (bestCycles * RATE / hz / 16.0).roundToInt() * 16
        var bestError = Double.POSITIVE_INFINITY
        for (cycles in bestCycles..high step 2) {
            val frames = (cycles * RATE / hz / 16.0).roundToInt() * 16
            val pitchError = abs(cycles * RATE / frames.toDouble() / hz - 1.0)
            // Pitch dominates; a very small preference keeps samples close to two seconds.
            val error = pitchError + abs(frames / RATE.toDouble() - 2.0) * 1e-10
            if (error < bestError) { bestError = error; bestCycles = cycles; bestFrames = frames }
        }
        val tuned = bestCycles * RATE / bestFrames.toDouble()
        return LoopPlan(bestFrames, tuned, bestCycles, DoubleArray(4) { (ratios[it] * bestCycles).roundToInt() / bestCycles.toDouble() })
    }

    private fun cleanVelocity(velocity: Float) = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f).toDouble()
    private fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3.0 - 2.0 * t) }

    internal fun probe(
        voice: MurmurVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        config: MurmurConfig = configurationFor(voice),
    ): MurmurProbe {
        val c = config.copy(ratios = config.ratios.toList(), targets = config.targets.map { target ->
            target.copy(centers = target.centers.toList(), widths = target.widths.toList(), gains = target.gains.toList())
        })
        val m = settled(voice, macros)
        val note = midi.coerceIn(MIN_MIDI, MAX_MIDI)
        val hz = Keys.midiHz(note).toDouble()
        val v = cleanVelocity(velocity)
        val held = isLoop(m.getValue("HOLD"))
        val plan = if (held) planLoop(hz, c.ratios) else null
        val effectiveAttack = c.attack * (.75 + m.getValue("BREATH") * .9 + (1.0 - v) * .4)
        val gate = max(shape(voice).gate + m.getValue("HOLD") * 1.4, effectiveAttack + c.decay + .05)
            .coerceAtMost(MAX_SECONDS - c.release)
        val seconds = (gate + c.release).coerceAtMost(MAX_SECONDS)
        val outputFrames = plan?.frames ?: (seconds * RATE).roundToInt().coerceAtLeast(64)
        if (v <= 0.0) return MurmurProbe(FloatArray(outputFrames), if (held) FloatArray(outputFrames) else FloatArray(0), plan?.root ?: hz)

        val period = outputFrames * Dsp.OVERSAMPLE
        // Two whole periods settle every oscillator, bank, source smoother and DC history. Keep
        // three continuously rendered periods plus decimator padding; compare the last two.
        val preroll = if (held) period * 2 else 0
        val keep = if (held) period * 3 + 512 * Dsp.OVERSAMPLE else period
        val raw = synthesize(voice, m, note, v, c, plan, gate, preroll, keep)
        val down = Dsp.decimate(raw, RATE)
        if (!held) {
            removeMean(down)
            Dsp.fadeTail(down)
            return MurmurProbe(down, rootHz = hz)
        }
        val previous = down.copyOfRange(outputFrames, outputFrames * 2)
        val samples = down.copyOfRange(outputFrames * 2, outputFrames * 3)
        // A shared DC correction preserves the actual adjacency used by the seam measurement.
        var mean = 0.0
        for (x in samples) mean += x
        val dc = (mean / samples.size).toFloat()
        for (i in samples.indices) { samples[i] -= dc; previous[i] -= dc }
        val seam = Keys.seamError(previous + samples, previous.size)
        return MurmurProbe(samples, previous, plan!!.root, seam)
    }

    private fun removeMean(samples: FloatArray) {
        if (samples.isEmpty()) return
        var mean = 0.0
        for (sample in samples) mean += sample
        val dc = (mean / samples.size).toFloat()
        for (i in samples.indices) samples[i] -= dc
    }

    /** Stable TPT bandpass, constant peak gain, with retained state during every retune. */
    private class Formant {
        private var ic1 = 0.0
        private var ic2 = 0.0
        private var g = .001
        private var k = .2
        private var gain = 0.0
        private var dg = 0.0
        private var dk = 0.0
        private var dGain = 0.0
        fun tune(center: Double, width: Double, amplitude: Double, immediate: Boolean) {
            val nextG = tan(PI * center.coerceIn(150.0, 7_000.0) / INTERNAL_RATE)
            val nextK = (width / center).coerceIn(1.0 / MAX_FORMANT_Q, 2.0)
            if (immediate) { g = nextG; k = nextK; gain = amplitude; dg = 0.0; dk = 0.0; dGain = 0.0 }
            else { dg = (nextG - g) / CONTROL_STRIDE; dk = (nextK - k) / CONTROL_STRIDE; dGain = (amplitude - gain) / CONTROL_STRIDE }
        }
        fun next(x: Double): Double {
            g += dg; k += dk; gain += dGain
            val a1 = 1.0 / (1.0 + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2.0 * v1 - ic1
            ic2 = 2.0 * v2 - ic2
            if (abs(ic1) < 1e-24) ic1 = 0.0
            if (abs(ic2) < 1e-24) ic2 = 0.0
            return gain * k * v1
        }
    }

    private fun polyBlep(phase: Double, step: Double): Double = when {
        phase < step -> { val x = phase / step; x + x - x * x - 1.0 }
        phase > 1.0 - step -> { val x = (phase - 1.0) / step; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private fun synthesize(
        voice: MurmurVoice, m: Map<String, Float>, midi: Int, velocity: Double, config: MurmurConfig,
        plan: LoopPlan?, gate: Double, preroll: Int, keep: Int,
    ): FloatArray {
        val held = plan != null
        val root = plan?.root ?: Keys.midiHz(midi).toDouble()
        val ratios = plan?.ratios ?: config.ratios.toDoubleArray()
        val upperOperator = ratios.indices.maxBy { ratios[it] }
        val mouth = m.getValue("MOUTH").toDouble()
        val edge = m.getValue("EDGE").toDouble()
        val breath = m.getValue("BREATH").toDouble()
        val motion = m.getValue("MOTION").toDouble()
        val body = m.getValue("BODY").toDouble()
        val fullness = body
        val motionDepth = if (motion == 0.0) 0.0 else .86 * motion.pow(.65)
        val targets = config.targets
        val bands = Array(targets.first().centers.size) { Formant() }
        val indexCeiling = minOf(MAX_INDEX_RADIANS, 7_200.0 / (root * ratios.max())).coerceAtLeast(.12)
        val indexBase = minOf(indexCeiling, (.10 + edge * 3.8) * (.25 + .75 * velocity))
        // A struck voice gets a clearly articulated journey rather than spending nearly its
        // entire note at the endpoint of a 150 ms gesture. Cycles are slow enough to hear the
        // distinct mouth shapes, with whole-cycle quantization in HOLD below.
        val requestedTrajectory = when (config.trajectory) {
            MurmurTrajectory.ATTACK -> config.referenceSeconds * (1.9 - .85 * sqrt(motion))
            MurmurTrajectory.CYCLE -> config.referenceSeconds / (1.0 + .55 * sqrt(motion))
        }.coerceIn(.1, 3.0)
        val trajectorySeconds = if (held) requestedTrajectory else minOf(requestedTrajectory, max(.1, gate - .05))
        val loopSeconds = plan?.frames?.div(RATE.toDouble()) ?: 0.0
        val contourCycles = if (held) (loopSeconds / trajectorySeconds).roundToInt().coerceIn(1, 24) else 0
        val attack = config.attack * (.75 + breath * .9 + (1.0 - velocity) * .4)
        val contactSeconds = (.007 + .025 * (1.0 - edge) + .018 * breath).coerceIn(.005, .060)
        val period = plan?.frames?.times(Dsp.OVERSAMPLE) ?: 0
        val seed = Dsp.seedFor("MURMUR", voice.name, midi, "BREATH")
        val noise = Dsp.Noise(seed)
        val repeatedNoise = if (held) FloatArray(period) { noise.next() } else null
        val total = preroll + keep
        val out = FloatArray(keep)
        val phases = DoubleArray(4)
        val steps = DoubleArray(4) { root * ratios[it] / INTERNAL_RATE }
        val noiseA = exp(-2.0 * PI * 6_000.0 / INTERNAL_RATE)
        // BODY's damping acts within the musical spectrum, while a register floor preserves
        // the root and a little overtone space at the top of the supported keyboard.
        val bodyCutoff = max(root * 1.5, 5_200.0 * (1_500.0 / 5_200.0).pow(fullness))
        val highA = exp(-2.0 * PI * bodyCutoff / INTERNAL_RATE)
        val dcA = exp(-2.0 * PI * 12.0 / INTERNAL_RATE)
        var noiseLow = 0.0
        var highLow = 0.0
        var dcLow = 0.0
        var sourceBalance = 0.0
        var sourceStep = 0.0
        val excitationGain = .24 * velocity.pow(.8)
        // Mostly absolute formants, with only a small extreme-register shift and broader sparse
        // harmonic sampling at the top. The mouth is never retuned to the requested note.
        val registerShift = 2.0.pow(((midi - 60).coerceIn(-24, 36) / 12.0) * .035)
        val registerWidth = 1.0 + ((midi - 72).coerceAtLeast(0) / 24.0) * .80
        for (i in 0 until total) {
            val t = i / INTERNAL_RATE.toDouble()
            val cycleFrame = if (held) i % period else i
            if (i % CONTROL_STRIDE == 0) {
                val low = mouth * (1.0 - motionDepth)
                val high = low + motionDepth
                val position = when {
                    motion == 0.0 -> mouth
                    held || config.trajectory == MurmurTrajectory.CYCLE -> {
                        val contour = if (held) 2.0 * PI * contourCycles * cycleFrame / period
                            else 2.0 * PI * t / trajectorySeconds
                        (low + high) * .5 + (high - low) * .5 * sin(config.direction * contour)
                    }
                    else -> {
                        val from = if (config.direction > 0) low else high
                        val to = if (config.direction > 0) high else low
                        from + (to - from) * smooth(t / trajectorySeconds)
                    }
                }.coerceIn(0.0, 1.0)
                val path = position * (targets.size - 1)
                val lower = floor(path).toInt().coerceAtMost(targets.size - 2)
                val blend = smooth(path - lower)
                val a = targets[lower]; val b = targets[lower + 1]
                for (band in bands.indices) {
                    val center = exp(ln(a.centers[band]) * (1.0 - blend) + ln(b.centers[band]) * blend) * registerShift
                    val width = exp(ln(a.widths[band]) * (1.0 - blend) + ln(b.widths[band]) * blend) * registerWidth *
                        if (band < 2) .65 + fullness * 1.1 else 1.0
                    val support = when (band) {
                        0 -> .75 + fullness * .35
                        1 -> .75 + fullness * .90
                        else -> 1.55 - fullness * 1.05
                    }
                    val gain = sqrt(a.gains[band].pow(2) * (1.0 - blend) + b.gains[band].pow(2) * blend) *
                        support
                    bands[band].tune(center, max(center / MAX_FORMANT_Q, width), gain, i == 0)
                }
                val nextBalance = a.sourceBalance * (1.0 - blend) + b.sourceBalance * blend
                if (i == 0) sourceBalance = nextBalance
                sourceStep = (nextBalance - sourceBalance) / CONTROL_STRIDE
            }
            sourceBalance += sourceStep
            val amplitude = if (held) 1.0 else {
                val beforeRelease = when {
                    t < attack -> smooth(t / attack)
                    t < attack + config.decay -> 1.0 + (config.sustain - 1.0) * smooth((t - attack) / config.decay)
                    else -> config.sustain
                }
                beforeRelease * (1.0 - smooth((t - gate) / config.release))
            }
            val transient = if (held) .25 else exp(-t / (.025 + config.decay * .35))
            val index = minOf(indexCeiling, indexBase * (.65 + .55 * edge * transient) * (1.0 + .18 * sourceBalance))
            val p0 = phases[0] * 2.0 * PI
            val p1 = phases[1] * 2.0 * PI
            val p2 = phases[2] * 2.0 * PI
            val p3 = phases[3] * 2.0 * PI
            val fm = when (config.routing) {
                MurmurRouting.PARALLEL -> .78 * sin(p0 + index * (.65 * sin(p1) + .35 * sin(p2))) + .22 * sin(p3)
                MurmurRouting.PAIRS -> .74 * sin(p0 + index * sin(p1)) + .26 * sin(p2 + .6 * index * sin(p3))
                MurmurRouting.STACK -> sin(p0 + .8 * index * sin(p1 + .55 * index * sin(p2 + .35 * index * sin(p3))))
            }
            val ramp = 2.0 * phases[0] - 1.0 - polyBlep(phases[0], steps[0])
            val upperRamp = ramp + (2.0 / PI) * sin(p0)
            val voiced = (.55 * fm + (.40 + .20 * sqrt(motion)) * upperRamp) * (.94 - .18 * breath)
            val n = repeatedNoise?.get(cycleFrame)?.toDouble() ?: noise.next().toDouble()
            noiseLow = noiseA * noiseLow + (1.0 - noiseA) * n
            val contact = if (held || t >= contactSeconds) 0.0 else sin(PI * t / contactSeconds).pow(2) * (.12 + .40 * edge)
            // Motion x breath and mouth x breath share the trajectory's changing source balance.
            val unvoiced = 3.0 * noiseLow * breath * ((.38 + .42 * sourceBalance) * (.70 + .30 * amplitude) + contact) * (0.6 + .4 * velocity)
            val drive = voiced + unvoiced
            var formant = 0.0
            for (band in bands) formant += band.next(drive)
            val anchor = sin(p0) * (.30 + .20 * fullness)
            // Chesty support contains pitched second/third harmonics, not an added sub or a
            // louder sine. Thin BODY keeps the existing upper carrier and a clearer FM edge;
            // full BODY supports low mids and rounds away upper-formant/direct brightness.
            // Quadrature keeps the chest partials from cancelling the ramp's sine harmonics
            // around rounded targets: full BODY must contain audible low mids, not revert to
            // an almost pure fundamental after shared loudness matching.
            val chest = fullness * (.55 * cos(2.0 * p0) + .24 * cos(3.0 * p0))
            val direct = fm * (.60 - .42 * fullness) + .40 * (1.0 - fullness) * cos(2.0 * PI * phases[upperOperator])
            var y = anchor + chest + direct + formant * (1.30 + .60 * sqrt(motion))
            highLow = highA * highLow + (1.0 - highA) * y
            y = .06 * y + .94 * highLow
            y *= excitationGain * amplitude
            dcLow = dcA * dcLow + (1.0 - dcA) * y
            y -= dcLow
            if (i >= preroll) out[i - preroll] = if (y.isFinite()) y.toFloat() else 0f
            for (operator in phases.indices) {
                phases[operator] += steps[operator]
                if (phases[operator] >= 1.0) phases[operator] -= floor(phases[operator])
            }
        }
        return out
    }
}
