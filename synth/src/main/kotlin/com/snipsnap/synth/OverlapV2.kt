package com.snipsnap.synth

import com.snipsnap.audio.Snip
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
import kotlin.math.tanh

/**
 * Revision two: a loss-controlled scattering resonator with audible modal families.
 *
 * One set of segment proportions belongs to the instrument, across every topology.
 * A SINGLE geometry scale changes between self and the selected full circuit: the
 * longest effective circuit is root-referenced, rather than independently shrinking
 * every circuit into the same root-period comb. The other circuits retain their
 * different lengths. SELF has a reference root and upper intervals; TREFOIL has a
 * root/fifth pair of comb families; MIRROR has three odd-harmonic families; TORUS
 * has one odd-harmonic family. Intermediate geometry and resonances remain exploratory.
 *
 * KNOT composes proper rotations, preserving instantaneous scattering energy at
 * every depth. A pure three-pair or six-cycle permutation has determinant
 * -1 and cannot be continuously reached from I inside six-dimensional orthogonal
 * matrices. MIRROR therefore includes one polarity inversion per pair; TORUS has
 * one inverted reflection in its six-cycle. These signs are part of the physical
 * scattering model, not optional correction hidden in a normalizer. TREFOIL's
 * two positive three-cycles need no sign inversion.
 *
 * Fixed linear delay interpolation and two-tap damping each have L2 gain <= 1;
 * scattering is orthogonal and explicit line gains remain < 1. The small-gain
 * argument therefore applies before output conditioning, including time-varying
 * breath absorption. No output limiter conceals expanding feedback.
 *
 * The six Kuramoto phases gate actual per-node excitation and absorption. LOCK
 * aligns those six energy processes; there is no independent master amplitude
 * clock. PULSE=0 bypasses both gates and breath absorption. HOLD extends finite
 * excitation, with a slow onset at high HOLD instead of a dominating initial click.
 */
internal object OverlapV2 {
    private const val RATE = 44_100
    private const val OVERSAMPLE = 4
    private const val DAMPING = .22
    private const val CONTROL_STRIDE = 32
    private const val TWO_PI = 2.0 * PI
    private val SEGMENTS = doubleArrayOf(1.2, 1.6, 2.0, 3.0, 2.4, 1.8)

    fun rootMidi(topology: OverlapTopology): Int = 48

    fun macrosFor(topology: OverlapTopology): List<MacroSpec> = listOf(
        MacroSpec("KNOT", if (topology == OverlapTopology.SELF) 0f else 1f, 0f),
        MacroSpec("PULSE", .35f, 0f), MacroSpec("LOCK", .55f, 0f),
        MacroSpec("GLOW", .2f, 0f), MacroSpec("RING", .70f, .4f),
        MacroSpec("HOLD", 0f, 0f),
    )

    fun defaults(topology: OverlapTopology): Map<String, Float> =
        macrosFor(topology).associate { it.name to it.default }

    private fun settled(topology: OverlapTopology, macros: Map<String, Float>): Map<String, Float> {
        val result = defaults(topology).toMutableMap()
        for ((key, value) in macros) {
            require(key in result) { "unknown OVERLAP macro $key" }
            require(value.isFinite()) { "OVERLAP $key must be finite" }
            result[key] = value.coerceIn(0f, 1f)
        }
        return result
    }

    /**
     * Stable interpolation of topology, exposed to the norm-preservation tests.
     * Applying a positive quarter-turn to (a,b) sends a to b and b to -a.
     * Descending adjacent turns give the signed six-cycle at full depth.
     */
    internal fun scatter(topology: OverlapTopology, depth: Double, input: DoubleArray): DoubleArray {
        require(input.size == 6) { "OVERLAP scattering needs six inputs" }
        require(depth.isFinite() && depth in 0.0..1.0) { "OVERLAP depth must be in 0..1" }
        val out = input.copyOf()
        if (depth == 0.0 || topology == OverlapTopology.SELF) return out
        fun turn(a: Int, b: Int, angle: Double) {
            val c = cos(angle)
            val s = sin(angle)
            val x = out[a]
            val y = out[b]
            out[a] = c * x - s * y
            out[b] = s * x + c * y
        }
        fun triple(start: Int) {
            val angle = TWO_PI * depth / 3.0
            val c = cos(angle)
            val s = sin(angle) / sqrt(3.0)
            val x = out[start]
            val y = out[start + 1]
            val z = out[start + 2]
            val common = (1.0 - c) * (x + y + z) / 3.0
            out[start] = c * x + common + s * (z - y)
            out[start + 1] = c * y + common + s * (x - z)
            out[start + 2] = c * z + common + s * (y - x)
        }
        when (topology) {
            OverlapTopology.SELF -> Unit
            OverlapTopology.TREFOIL -> { triple(0); triple(3) }
            OverlapTopology.MIRROR -> {
                val angle = PI * .5 * depth
                turn(0, 5, angle); turn(1, 4, angle); turn(2, 3, angle)
            }
            OverlapTopology.TORUS -> {
                val angle = PI * .5 * depth
                for (i in 4 downTo 0) turn(i, i + 1, angle)
            }
        }
        return out
    }

    private fun matrix(topology: OverlapTopology, depth: Double): Array<DoubleArray> {
        val columns = Array(6) { column -> scatter(topology, depth, DoubleArray(6) { if (it == column) 1.0 else 0.0 }) }
        return Array(6) { row -> DoubleArray(6) { column -> columns[column][row] } }
    }

    private fun dampingPhase(omega: Double): Double =
        atan2(DAMPING * sin(omega), 1.0 - DAMPING + DAMPING * cos(omega))

    private fun delayPhase(delay: Double, omega: Double): Double {
        val whole = floor(delay)
        val fraction = delay - whole
        return whole * omega + atan2(fraction * sin(omega), 1.0 - fraction + fraction * cos(omega))
    }

    /** Match the actual fractional-delay + damping phase, including short segments. */
    private fun calibratedDelay(targetPhase: Double, omega: Double): Double {
        val wanted = targetPhase - dampingPhase(omega)
        var lo = 1.0
        var hi = max(2.0, wanted / omega + 2.0)
        repeat(40) {
            val middle = (lo + hi) * .5
            if (delayPhase(middle, omega) < wanted) lo = middle else hi = middle
        }
        return (lo + hi) * .5
    }

    private fun delays(topology: OverlapTopology, knot: Double, hz: Double, rate: Int): DoubleArray {
        val endpoint = when (topology) {
            OverlapTopology.SELF -> 3.0
            OverlapTopology.TREFOIL -> 7.2
            OverlapTopology.MIRROR -> 10.0
            OverlapTopology.TORUS -> 24.0
        }
        // Positive cycles repeat after one circuit; a negative circuit repeats after
        // two. This SINGLE scale preserves the six segment proportions. At full depth
        // the longest effective cycle reaches the root, while shorter cycles remain
        // separate families (e.g. TREFOIL lengths 4.8 and 7.2 give a genuine 3:2).
        val denominator = 3.0 + knot * (endpoint - 3.0)
        val omega = TWO_PI * hz / rate
        return DoubleArray(6) { calibratedDelay(TWO_PI * SEGMENTS[it] / denominator, omega) }
    }

    private class Delay(length: Double) {
        private val whole = floor(length).toInt()
        private val fraction = length - whole
        private val buffer = DoubleArray(whole + 2)
        private var position = 0
        fun read(): Double {
            var first = position - whole
            if (first < 0) first += buffer.size
            var second = first - 1
            if (second < 0) second += buffer.size
            return buffer[first] * (1.0 - fraction) + buffer[second] * fraction
        }
        fun write(value: Double) {
            buffer[position] = value
            position++
            if (position == buffer.size) position = 0
        }
    }

    private class Breathing(pulse: Double, lock: Double, private val rate: Int) {
        val phases = doubleArrayOf(.13, 1.23, 2.41, 3.07, 4.45, 5.51)
        private val frequencies = doubleArrayOf(.78, .91, 1.04, 1.19, .86, 1.12)
        // .65 -> about 1.4 Hz, enough to hear several emergent shared surges.
        private val hz = .2 * 20.0.pow(pulse)
        private val coupling = 24.0 * lock * lock
        private val next = DoubleArray(6)
        private val enabled = pulse > 0.0
        val gate = DoubleArray(6) { 1.0 }
        val extraLoss = DoubleArray(6)
        fun order(): Double {
            var x = 0.0; var y = 0.0
            for (p in phases) { x += cos(p); y += sin(p) }
            return sqrt(x * x + y * y) / phases.size
        }
        fun update() {
            val dt = CONTROL_STRIDE.toDouble() / rate
            for (i in phases.indices) {
                var attraction = 0.0
                for (j in phases.indices) attraction += sin(phases[j] - phases[i])
                val advance = TWO_PI * hz * frequencies[i] + coupling * attraction / 6.0
                val value = phases[i] + advance * dt
                next[i] = value - TWO_PI * floor(value / TWO_PI)
            }
            for (i in phases.indices) {
                phases[i] = next[i]
                if (enabled) {
                    val inhale = max(0.0, sin(phases[i]))
                    gate[i] = .012 + .988 * inhale * inhale * inhale
                    // A closed node withdraws excitation AND absorbs circulating
                    // energy. At full synchronization every node truly closes;
                    // free phases keep different parts of the network breathing.
                    extraLoss[i] = 18.0 * (1.0 - gate[i])
                }
            }
        }
    }

    private fun smoothstep(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    private fun rms(samples: FloatArray, start: Int, end: Int): Double {
        if (end <= start) return 0.0
        var squares = 0.0
        for (i in start until end) squares += samples[i].toDouble() * samples[i]
        return sqrt(squares / (end - start))
    }

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("OVERLAP render cancelled")
    }

    fun probe(
        topology: OverlapTopology, macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(topology), velocity: Float = 1f, seconds: Double? = null,
    ): Overlap.Probe {
        require(midi in Overlap.MIN_MIDI..Overlap.MAX_MIDI) { "OVERLAP midi out of ${Overlap.MIN_MIDI}..${Overlap.MAX_MIDI}: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OVERLAP velocity out of 0..1: $velocity" }
        val m = settled(topology, macros)
        val knot = m.getValue("KNOT").toDouble()
        val pulse = m.getValue("PULSE").toDouble()
        val lock = m.getValue("LOCK").toDouble()
        val glow = m.getValue("GLOW").toDouble()
        val ring = m.getValue("RING").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val duration = seconds ?: min(8.0, 3.0 + 3.0 * ring + 3.0 * hold)
        require(duration.isFinite() && duration in .05..8.0) { "OVERLAP seconds must be finite and in .05..8" }
        val rate = RATE * OVERSAMPLE
        val frames = (duration * RATE).roundToInt()
        val count = frames * OVERSAMPLE
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val length = delays(topology, knot, hz, rate)
        val lines = Array(6) { Delay(length[it]) }
        val scattering = matrix(topology, knot)
        val t60 = .55 * 24.0.pow(ring)
        val decayRate = 6.90775527898 / t60
        val lineSeconds = DoubleArray(6) { (length[it] + DAMPING) / rate }
        val baseGain = DoubleArray(6) { exp(-decayRate * lineSeconds[it]) }
        val movingGain = baseGain.copyOf()
        val previous = DoubleArray(6)
        val damped = DoubleArray(6)
        val excitationWeights = doubleArrayOf(.83, .71, .88, 1.0, .79, .92)
        val pickup = doubleArrayOf(.75, .57, -.63, .90, -.61, .70)
        val breathing = Breathing(pulse, lock, rate)
        val orderStart = breathing.order()
        val history = mutableListOf(orderStart)
        // The same six deterministic excitation streams belong to every topology
        // and depth. Independent node streams avoid accidental cancellation of an
        // entire family by injecting a perfectly coherent equal signal everywhere.
        val noise = Array(6) { Dsp.Noise(0x4f5652 + midi * 97 + it * 10_007) }
        val inputLow = DoubleArray(6)
        val excitationSeconds = .040 + 5.96 * hold * hold
        val oversampled = FloatArray(count)
        var envelope = 0.0
        var glowDc = 0.0
        var glowLow = 0.0
        var finalLow = 0.0
        var dcInput = 0.0
        var dcOutput = 0.0
        var maximumState = 0.0
        val sourceAlpha = 1.0 - exp(-TWO_PI * 6_000.0 / rate)
        val envelopeAlpha = 1.0 - exp(-1.0 / (.025 * rate))
        val glowDcAlpha = 1.0 - exp(-TWO_PI * 25.0 / rate)
        val glowLowAlpha = 1.0 - exp(-TWO_PI * min(8_000.0, hz * 18.0) / rate)
        val finalLowAlpha = 1.0 - exp(-TWO_PI * 10_000.0 / rate)
        val dcPole = exp(-TWO_PI * 8.0 / rate)
        val meterStride = rate / 10
        for (n in 0 until count) {
            if (n % 8192 == 0) stopIfUnwanted()
            if (n % CONTROL_STRIDE == 0) {
                breathing.update()
                for (i in 0..5) movingGain[i] = baseGain[i] * exp(-lineSeconds[i] * breathing.extraLoss[i])
            }
            if (n > 0 && n % meterStride == 0) history += breathing.order()
            val time = n.toDouble() / rate
            // The routing hammer is only 40ms, with gentle boundaries. High HOLD
            // removes it: sustained comparisons begin with the real rubbing source
            // ramping in, so a common initial transient cannot dominate the result.
            val hammer = if (time >= .040) 0.0 else
                .23 * (1.0 - hold).pow(2.0) * smoothstep(time / .0015) *
                    exp(-time / .007) * smoothstep((.040 - time) / .010)
            val rub = if (time >= excitationSeconds) 0.0 else
                .014 * hold * hold * smoothstep(time / (.08 + .40 * hold)) *
                    smoothstep((excitationSeconds - time) / .35)
            for (i in 0..5) {
                inputLow[i] += sourceAlpha * (noise[i].next().toDouble() - inputLow[i])
                val read = lines[i].read()
                damped[i] = (1.0 - DAMPING) * read + DAMPING * previous[i]
                previous[i] = read
            }
            var output = 0.0
            for (i in 0..5) {
                var routed = 0.0
                for (j in 0..5) routed += scattering[i][j] * damped[j]
                val excitation = velocity * excitationWeights[i] * inputLow[i] *
                    (hammer + rub * breathing.gate[i])
                val write = excitation + movingGain[i] * routed
                lines[i].write(write)
                maximumState = max(maximumState, abs(write))
                output += pickup[i] * damped[i]
            }
            output *= .38
            // This layer is output-derived, bounded, oversampled, and outside
            // scattering/feedback. Preserve revision one's stronger GLOW voicing.
            envelope += envelopeAlpha * (abs(output) - envelope)
            val bounded = tanh(output / (.002 + 1.6 * envelope))
            val even = bounded * bounded
            glowDc += glowDcAlpha * (even - glowDc)
            val harmonic = (even - glowDc) + .65 * (bounded * bounded * bounded - .55 * bounded)
            glowLow += glowLowAlpha * (harmonic * envelope * 2.7 - glowLow)
            val illuminated = output + glow * glowLow
            finalLow += finalLowAlpha * (illuminated - finalLow)
            val dc = finalLow - dcInput + dcPole * dcOutput
            dcInput = finalLow; dcOutput = dc
            check(dc.isFinite() && maximumState < 1e6) { "OVERLAP non-finite or unbounded internal state" }
            oversampled[n] = dc.toFloat()
        }
        stopIfUnwanted()
        val raw = Dsp.decimate(oversampled, RATE)
        var peak = 0.0; var sum = 0.0
        for (x in raw) { peak = max(peak, abs(x.toDouble())); sum += x }
        val window = min(raw.size, RATE / 4)
        val diagnostics = Overlap.Diagnostics(
            peak, rms(raw, 0, raw.size), sum / max(1, raw.size),
            rms(raw, 0, window), rms(raw, raw.size - window, raw.size),
            orderStart, breathing.order(), length.map { it / OVERSAMPLE }, baseGain.maxOrNull() ?: 0.0,
            maximumState, hz, excitationSeconds, history.toList(),
        )
        val samples = raw.copyOf()
        Dsp.fadeTail(samples, ms = 20f, rate = RATE)
        return Overlap.Probe(raw, samples, RATE, diagnostics)
    }

    fun render(
        topology: OverlapTopology, macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(topology), velocity: Float = 1f, seconds: Double? = null,
    ): Snip {
        val samples = probe(topology, macros, midi, velocity, seconds).samples
        Dsp.levelTo(samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        for (i in samples.indices) samples[i] *= sqrt(velocity)
        return Snip(samples, channels = 1, sampleRate = RATE)
    }
}
