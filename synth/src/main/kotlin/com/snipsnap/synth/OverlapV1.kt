package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Overlap.Diagnostics
import com.snipsnap.synth.Overlap.Probe
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

/** The guide's actual permutations, rather than the original implementation's adjacency matrix. */

/**
 * A finite, struck six-line resonator audition. No original OVERLAP DSP is copied.
 *
 * M = (1 - KNOT) I + KNOT P, where P is a permutation. Every feedback operation
 * (linear delay interpolation, two-tap damping, M, positive gain < 1) is a convex
 * combination or contraction in the infinity norm. GLOW and breathing live outside
 * this feedback loop. Stability therefore does not depend on output normalization.
 *
 * Tuning has two honest endpoints. At KNOT=0 six independent strings sound the
 * requested root and its integer harmonics. At KNOT=1 the phase delays around EACH
 * selected cycle sum to one root period, including damping and interpolation phase.
 * A six-way cycle is NOT six root-period lines (which would introduce root/6).
 * Geometry interpolates between those endpoints. Mixed self/cycle routes have more
 * than one path length: intermediate KNOT can change the strongest pitch and is a
 * timbre experiment, not a claim of pitch-invariant morphing. LOCK never changes a
 * delay or an audio oscillator frequency. HOLD extends noise excitation only; even
 * at 1 this renderer returns a finite tail and does not claim a seamless loop.
 */
internal object OverlapV1 {
    const val MIN_MIDI = 24
    const val MAX_MIDI = 84
    const val SAMPLE_RATE = 44_100
    private const val OVERSAMPLE = 4
    private const val DAMPING = .22
    private const val CONTROL_STRIDE = 64
    private const val TWO_PI = 2.0 * PI

    fun rootMidi(topology: OverlapTopology): Int = 48

    fun macrosFor(topology: OverlapTopology): List<MacroSpec> = listOf(
        MacroSpec("KNOT", if (topology == OverlapTopology.SELF) 0f else 1f, 0f),
        MacroSpec("PULSE", .35f, 0f), MacroSpec("LOCK", .55f, 0f),
        MacroSpec("GLOW", .2f, 0f), MacroSpec("RING", .65f, .4f),
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

    /** Destination i receives previous output P[i]; cycles are explicit and independently testable. */
    fun permutation(topology: OverlapTopology): List<Int> = when (topology) {
        OverlapTopology.SELF -> listOf(0, 1, 2, 3, 4, 5)
        OverlapTopology.TREFOIL -> listOf(2, 0, 1, 5, 3, 4) // (0 1 2), (3 4 5)
        OverlapTopology.MIRROR -> listOf(5, 4, 3, 2, 1, 0) // (0 5), (1 4), (2 3)
        OverlapTopology.TORUS -> listOf(5, 0, 1, 2, 3, 4) // (0 1 2 3 4 5)
    }

    private fun cycles(p: List<Int>): List<List<Int>> {
        val seen = BooleanArray(6)
        val result = mutableListOf<List<Int>>()
        for (start in 0..5) if (!seen[start]) {
            val cycle = mutableListOf<Int>()
            var i = start
            do { cycle += i; seen[i] = true; i = p[i] } while (i != start)
            result += cycle
        }
        return result
    }

    private fun dampingPhase(omega: Double): Double =
        atan2(DAMPING * sin(omega), 1.0 - DAMPING + DAMPING * cos(omega))

    /** Actual phase lag of a linear-interpolating delay, without a small-angle approximation. */
    private fun delayPhase(delay: Double, omega: Double): Double {
        val whole = floor(delay)
        val fraction = delay - whole
        return whole * omega + atan2(fraction * sin(omega), 1.0 - fraction + fraction * cos(omega))
    }

    private fun calibratedDelay(targetPhase: Double, omega: Double): Double {
        val wanted = targetPhase - dampingPhase(omega)
        var lo = 1.0
        var hi = max(2.0, wanted / omega + 2.0)
        repeat(40) {
            val mid = (lo + hi) * .5
            if (delayPhase(mid, omega) < wanted) lo = mid else hi = mid
        }
        return (lo + hi) * .5
    }

    private fun delays(topology: OverlapTopology, knot: Double, hz: Double, rate: Int): DoubleArray {
        val harmonics = doubleArrayOf(1.0, 2.0, 3.0, 1.0, 2.0, 4.0)
        val rootOmega = TWO_PI * hz / rate
        val self = DoubleArray(6) { calibratedDelay(TWO_PI, rootOmega * harmonics[it]) }
        if (knot == 0.0 || topology == OverlapTopology.SELF) return self
        // Unequal lengths reveal transit structure. Their PHASE shares, rather than
        // rounded sample counts, sum to exactly one root cycle in every permutation cycle.
        val weights = doubleArrayOf(.77, 1.14, 1.31, .86, 1.08, .94)
        val full = self.copyOf()
        for (cycle in cycles(permutation(topology))) {
            val sum = cycle.sumOf { weights[it] }
            for (i in cycle) full[i] = calibratedDelay(TWO_PI * weights[i] / sum, rootOmega)
        }
        return DoubleArray(6) { self[it] + knot * (full[it] - self[it]) }
    }

    private class Delay(val length: Double) {
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
        private val frequencies = doubleArrayOf(.83, .94, 1.05, 1.16, .90, 1.12)
        private val hz = .12 * 20.0.pow(pulse)
        private val coupling = 20.0 * lock * lock
        private val next = DoubleArray(6)
        val gain = DoubleArray(6) { 1.0 }
        private val depth = if (pulse <= 0.0) 0.0 else .48
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
                gain[i] = 1.0 - depth + depth * (.5 + .5 * sin(phases[i]))
            }
        }
    }

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
    ): Probe {
        require(midi in MIN_MIDI..MAX_MIDI) { "OVERLAP midi out of $MIN_MIDI..$MAX_MIDI: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OVERLAP velocity out of 0..1: $velocity" }
        val m = settled(topology, macros)
        val knot = m.getValue("KNOT").toDouble()
        val pulse = m.getValue("PULSE").toDouble()
        val lock = m.getValue("LOCK").toDouble()
        val glow = m.getValue("GLOW").toDouble()
        val ring = m.getValue("RING").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val duration = seconds ?: (2.0 + 3.0 * ring + 1.0 * hold)
        require(duration.isFinite() && duration in .05..8.0) { "OVERLAP seconds must be finite and in .05..8" }
        val rate = SAMPLE_RATE * OVERSAMPLE
        val frames = (duration * SAMPLE_RATE).roundToInt()
        val count = frames * OVERSAMPLE
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val length = delays(topology, knot, hz, rate)
        val lines = Array(6) { Delay(length[it]) }
        val p = permutation(topology)
        val t60 = .32 * 22.0.pow(ring)
        // Per-hop loss follows transit time, so long cycles do not automatically decay
        // six times slower. Damping/interpolation add frequency-dependent losses: this
        // is a nominal low-frequency T60, not a promise that every partial shares it.
        val gain = DoubleArray(6) { exp(-6.90775527898 * (length[it] + DAMPING) / (rate * t60)) }
        val previous = DoubleArray(6)
        val damped = DoubleArray(6)
        val excitationWeights = doubleArrayOf(1.0, .14, .51, -.28, .32, .09)
        val pickup = doubleArrayOf(1.0, .23, -.37, .41, -.16, .11)
        val breathing = Breathing(pulse, lock, rate)
        val orderStart = breathing.order()
        val history = mutableListOf(orderStart)
        // Macros/topology never enter this seed: isolated comparisons share the exciter.
        val noise = Dsp.Noise(0x4f564c + midi * 97)
        val excitationSeconds = .008 + 2.8 * hold * hold
        val oversampled = FloatArray(count)
        var inputLow = 0.0
        var envelope = 0.0
        var glowDc = 0.0
        var glowLow = 0.0
        var finalLow = 0.0
        var dcInput = 0.0
        var dcOutput = 0.0
        var maximumState = 0.0
        val envelopeAlpha = 1.0 - exp(-1.0 / (.025 * rate))
        val glowDcAlpha = 1.0 - exp(-TWO_PI * 25.0 / rate)
        val glowLowAlpha = 1.0 - exp(-TWO_PI * min(8_000.0, hz * 14.0) / rate)
        val finalLowAlpha = 1.0 - exp(-TWO_PI * 10_000.0 / rate)
        val dcPole = exp(-TWO_PI * 8.0 / rate)
        val meterStride = rate / 10
        for (n in 0 until count) {
            if (n % 8192 == 0) stopIfUnwanted()
            if (n % CONTROL_STRIDE == 0) breathing.update()
            if (n > 0 && n % meterStride == 0) history += breathing.order()
            val time = n.toDouble() / rate
            // A colored, finite noise hammer. HOLD adds an explicitly gated rubbing
            // excitation; it never changes loop gain or synthesizes a completed loop.
            inputLow += .18 * (noise.next().toDouble() - inputLow)
            val strike = .42 * exp(-time / .0017)
            val gate = if (time >= excitationSeconds) 0.0 else {
                val attack = min(1.0, time / .025)
                val release = min(1.0, (excitationSeconds - time) / .10)
                .031 * hold * hold * attack * release
            }
            val excitation = velocity * inputLow * (strike + gate)
            for (i in 0..5) {
                val read = lines[i].read()
                damped[i] = (1.0 - DAMPING) * read + DAMPING * previous[i]
                previous[i] = read
            }
            var output = 0.0
            for (i in 0..5) {
                val routed = (1.0 - knot) * damped[i] + knot * damped[p[i]]
                val write = excitationWeights[i] * excitation + gain[i] * routed
                lines[i].write(write)
                maximumState = max(maximumState, abs(write))
                output += pickup[i] * damped[i] * breathing.gain[i]
            }
            output *= .65
            // Output-derived upper harmonics. The bounded polynomial inputs are
            // envelope-normalized, then scaled back by the *actual* audio envelope;
            // no independent oscillator survives a silent resonator. All nonlinear
            // work runs at 4x and is decimated through the house antialias filter.
            envelope += envelopeAlpha * (abs(output) - envelope)
            // The floor is below the quiet raw network body, so GLOW remains
            // audible before house makeup gain, rather than only on the hammer.
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
        val raw = Dsp.decimate(oversampled, SAMPLE_RATE)
        var peak = 0.0; var sum = 0.0
        for (x in raw) { peak = max(peak, abs(x.toDouble())); sum += x }
        val window = min(raw.size, SAMPLE_RATE / 4)
        val diagnostics = Diagnostics(
            peak, rms(raw, 0, raw.size), sum / max(1, raw.size),
            rms(raw, 0, window), rms(raw, raw.size - window, raw.size),
            orderStart, breathing.order(), length.map { it / OVERSAMPLE }, gain.maxOrNull() ?: 0.0,
            maximumState, hz, excitationSeconds, history.toList(),
        )
        val samples = raw.copyOf()
        Dsp.fadeTail(samples, ms = 12f, rate = SAMPLE_RATE)
        return Probe(raw, samples, SAMPLE_RATE, diagnostics)
    }

    fun render(
        topology: OverlapTopology, macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(topology), velocity: Float = 1f, seconds: Double? = null,
    ): Snip {
        val samples = probe(topology, macros, midi, velocity, seconds).samples
        Dsp.levelTo(samples, SAMPLE_RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        // Keep playing dynamics after level matching; velocity=0 stays digital silence.
        for (i in samples.indices) samples[i] *= sqrt(velocity)
        return Snip(samples, channels = 1, sampleRate = SAMPLE_RATE)
    }
}
