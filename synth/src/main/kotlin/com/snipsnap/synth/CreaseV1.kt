package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Preserved first listening model. Audio arithmetic remains unchanged. */
internal object CreaseV1 {
    const val RATE = Dsp.RATE
    const val DEFAULT_MIDI = 48
    const val MIDI_MIN = 24
    const val MIDI_MAX = 84
    const val HOLD_LOOP = .99f
    const val MAX_FINITE_SECONDS = 8.0
    const val COEFFICIENT_BUDGET = .9
    const val LOOP_STATE_TOLERANCE = 1e-7
    const val MAX_PREROLL_CYCLES = 18
    private const val TAU = 2.0 * PI
    private const val TRACE_HZ = 200

    fun macrosFor(voice: CreaseVoice): List<MacroSpec> {
        val values = when (voice) {
            CreaseVoice.ROUND -> floatArrayOf(.20f, .25f, .15f, .15f, .30f)
            CreaseVoice.PINCH -> floatArrayOf(.70f, .55f, .20f, .45f, .35f)
            CreaseVoice.RUBBER -> floatArrayOf(.50f, .70f, .75f, .55f, .65f)
        }
        return listOf("FOLD", "PRESSURE", "DRAG", "SPLIT", "RELAX").mapIndexed { i, name ->
            MacroSpec(name, values[i], values[i])
        } + MacroSpec("HOLD", 0f, 0f)
    }

    fun defaults(voice: CreaseVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    fun isLoop(hold: Float) = hold >= HOLD_LOOP

    private fun settled(voice: CreaseVoice, macros: Map<String, Float>) =
        defaults(voice).toMutableMap().also { result ->
            for ((key, value) in macros) if (key in result && value.isFinite()) result[key] = value.coerceIn(0f, 1f)
        }

    fun render(voice: CreaseVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f): Snip =
        Snip(synthesize(voice, macros, midi, velocity).samples, 1, RATE)

    fun probe(voice: CreaseVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f,
        history: CreaseHistory = CreaseHistory.STATEFUL, coupling: Boolean = true,
        oversample: Int = 4, normalize: Boolean = true): CreaseProbe =
        synthesize(voice, macros, midi, velocity, history, coupling, oversample, normalize)

    fun synthesize(voice: CreaseVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f,
        history: CreaseHistory = CreaseHistory.STATEFUL, coupling: Boolean = true,
        oversample: Int = 4, normalize: Boolean = true): CreaseProbe {
        require(midi in MIDI_MIN..MIDI_MAX) { "CREASE supports MIDI $MIDI_MIN..$MIDI_MAX" }
        require(oversample == 4 || oversample == 16) { "CREASE diagnostic oversample must be 4 or 16" }
        val started = System.nanoTime()
        val m = settled(voice, macros)
        val vel = if (velocity.isFinite()) velocity.coerceIn(0f, 1f).toDouble() else .75
        val requestedHz = Keys.midiHz(midi).toDouble()
        val held = isLoop(m.getValue("HOLD"))
        val shape = Shape(voice, m, vel)
        check(held || shape.duration <= MAX_FINITE_SECONDS) { "CREASE finite note exceeded duration cap" }
        // Same principle as Keys.planLoop: integer root cycles, then a tiny documented retuning
        // to integer output frames. Both layers have this exact root, with no detune or fifth.
        val rootCycles = if (held) (shape.loopSeconds * requestedHz).roundToInt().coerceAtLeast(8) else 0
        val frames = if (held) (rootCycles * RATE / requestedHz).roundToInt()
            else (shape.duration * RATE).roundToInt()
        val renderedHz = if (held) rootCycles * RATE.toDouble() / frames else requestedHz
        val rate = RATE * oversample
        val model = Model(shape, rate, renderedHz, history, coupling)
        val output = OutputPath(rate, oversample)
        val stats = Stats()
        val traces = TraceBuilder()
        var preroll = 0
        var converged = !held
        var stateError = 0.0
        var startState = DoubleArray(0)
        var endState = DoubleArray(0)
        var previous = FloatArray(0)

        fun state() = model.state() + output.state()
        fun cycle(record: Boolean): FloatArray {
            val out = FloatArray(frames)
            val internalFrames = frames * oversample
            val traceStride = max(oversample, rate / TRACE_HZ)
            var at = 0
            for (i in 0 until internalFrames) {
                if (i % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("CREASE render no longer wanted")
                val time = i.toDouble() / rate
                // A rational, periodic integer clock also closes at 16x. No floating phase
                // accumulator is reset while carrying residual phase error across the seam.
                val phase = if (held) TAU * ((i.toDouble() * rootCycles / internalFrames) % 1.0)
                    else TAU * ((time * renderedHz) % 1.0)
                val raw = model.step(phase, time, if (held) i.toDouble() / internalFrames else null)
                val value = output.step(raw)
                if (value != null) out[at++] = value.toFloat()
                if (record) {
                    stats.add(raw, model)
                    if (i % traceStride == 0) traces.add(time, model)
                }
            }
            check(at == frames) { "CREASE decimator did not complete a period" }
            return out
        }

        val prelevel: FloatArray
        if (held) {
            var before = state()
            for (period in 1..MAX_PREROLL_CYCLES) {
                cycle(false)
                val after = state()
                stateError = distance(before, after)
                before = after
                preroll = period
                if (period >= 3 && stateError < LOOP_STATE_TOLERANCE) {
                    converged = true
                    break
                }
            }
            check(converged) { "CREASE $voice HOLD did not converge after $preroll periods: $stateError" }
            previous = cycle(false)
            startState = state()
            prelevel = cycle(true)
            endState = state()
            stateError = distance(startState, endState)
            check(stateError < LOOP_STATE_TOLERANCE) { "CREASE HOLD state recurrence regressed: $stateError" }
        } else {
            prelevel = cycle(true)
            // The envelope completes a true -60 dB release before this conventional final fade.
            Dsp.fadeTail(prelevel)
        }

        val samples = prelevel.copyOf()
        if (normalize && vel > 0.0) Dsp.levelTo(samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET * vel.toFloat().pow(.4f))
        if (held && normalize) {
            // Apply one scalar to both independently evolved cycles. Never normalize each
            // separately and accidentally conceal failure to reach a periodic orbit.
            val index = prelevel.indices.maxByOrNull { abs(prelevel[it]) } ?: 0
            val gain = if (prelevel[index] == 0f) 1f else samples[index] / prelevel[index]
            for (i in previous.indices) previous[i] *= gain
        }
        val seam = if (held && vel > 0.0) Keys.seamError(previous + samples, previous.size) else 0.0
        check(!held || seam < 1e-3) { "CREASE $voice HOLD audio did not recur: $seam" }
        return CreaseProbe(samples, prelevel, RATE, CreaseDiagnostics(
            stats.peak, sqrt(stats.square / stats.count.coerceAtLeast(1)), stats.sum / stats.count.coerceAtLeast(1),
            stats.budget, stats.derivative, max(stats.state, output.maxAbsState),
            requestedHz, renderedHz, held, if (held) frames else 0, rootCycles,
            seam, stateError, converged, preroll, System.nanoTime() - started,
            stats.maxDerivative, stats.curvature, 1,
        ), traces.finish(), previous, startState, endState)
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double = a.indices.maxOf { abs(a[it] - b[it]) }
    private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t
    private fun smooth(t: Double): Double = t.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    private class Shape(val voice: CreaseVoice, m: Map<String, Float>, val velocity: Double) {
        val fold = m.getValue("FOLD").toDouble().pow(.65)
        val pressure = m.getValue("PRESSURE").toDouble().pow(.65)
        val drag = m.getValue("DRAG").toDouble()
        val split = m.getValue("SPLIT").toDouble().pow(.65)
        val relax = m.getValue("RELAX").toDouble()
        val held = isLoop(m.getValue("HOLD"))
        private val finiteHold = (m.getValue("HOLD") / HOLD_LOOP).coerceIn(0f, 1f).toDouble()
        val attack = when (voice) { CreaseVoice.ROUND -> .009; CreaseVoice.PINCH -> .014; CreaseVoice.RUBBER -> .027 }
        val gate = (when (voice) { CreaseVoice.ROUND -> .66; CreaseVoice.PINCH -> .88; CreaseVoice.RUBBER -> 1.35 }) *
            2.0.pow(finiteHold)
        val releaseT60 = when (voice) { CreaseVoice.ROUND -> .35 + 1.5 * sqrt(relax)
            CreaseVoice.PINCH -> .35 + 1.8 * sqrt(relax); CreaseVoice.RUBBER -> .65 + 2.5 * sqrt(relax) }
        val duration = gate + releaseT60 + .035
        val loopSeconds = when (voice) { CreaseVoice.ROUND -> 1.45; CreaseVoice.PINCH -> 1.65; CreaseVoice.RUBBER -> 2.1 }
        val rise = .002 * 40.0.pow(sqrt(drag)) * if (voice == CreaseVoice.RUBBER) 1.5 else 1.0
        // This range describes T60, not a 1.5-second time constant requiring a ten-second tail.
        val recovery = (.10 * 18.0.pow(relax)) / ln(1000.0)
        val baseBudget = .035 + .29 * fold
        val excursion = .14 + .43 * pressure
        // Provisional per-geometry calibration: compare deformation energy to an attainable
        // amount for that shape, rather than allowing a rounded map's tiny feature to vanish.
        val loadCalibration = when (voice) { CreaseVoice.ROUND -> 12.0; CreaseVoice.PINCH -> 8.0; CreaseVoice.RUBBER -> 10.0 }
        val geometry = when (voice) {
            CreaseVoice.ROUND -> doubleArrayOf(.86, .12, .02)
            CreaseVoice.PINCH -> doubleArrayOf(.65, -.30, .05)
            CreaseVoice.RUBBER -> doubleArrayOf(.08, -.78, .14)
        }
        val crease = when (voice) {
            CreaseVoice.ROUND -> doubleArrayOf(.70, -.24, .06)
            CreaseVoice.PINCH -> doubleArrayOf(.10, -.85, .05)
            CreaseVoice.RUBBER -> doubleArrayOf(.82, -.14, .04)
        }
        val other = when (voice) {
            CreaseVoice.ROUND -> doubleArrayOf(.0, -.88, .12)
            CreaseVoice.PINCH -> doubleArrayOf(.80, .16, .04)
            CreaseVoice.RUBBER -> doubleArrayOf(.18, -.76, .06)
        }

        fun amplitude(t: Double): Double {
            if (held) return velocity * .68
            val sustain = if (voice == CreaseVoice.ROUND) .38 else .62
            val decay = if (voice == CreaseVoice.RUBBER) .42 else .19
            val body = sustain + (1.0 - sustain) * exp(-min(t, gate) / decay)
            val release = if (t <= gate) 1.0 else exp(-ln(1000.0) * (t - gate) / releaseT60)
            return velocity * smooth(t / attack) * body * release
        }

        fun forcing(t: Double, loop: Double?): Double {
            if (loop != null) {
                // Smooth periodic pressure, never an amplitude attack/retrigger. The second
                // harmonic makes rise and unfolding asymmetrical while retaining exact closure.
                val a = .5 - .5 * cos(TAU * loop)
                val b = .5 - .5 * cos(2.0 * TAU * loop + .65)
                return (.10 + .73 * a.pow(2.0) + .17 * b).coerceIn(0.0, 1.0)
            }
            val onset = exp(-t / if (voice == CreaseVoice.RUBBER) .13 else .065)
            val push = exp(-((t - (.17 + .15 * drag)) / (.055 + .07 * drag)).pow(2.0))
            val late = exp(-((t - gate * .74) / (.08 + .12 * relax)).pow(2.0))
            val release = if (t <= gate) 1.0 else exp(-(t - gate) / (.025 + .10 * relax))
            return ((.09 + .73 * onset + .63 * push + .24 * late) * release).coerceIn(0.0, 1.0)
        }
    }

    private class Model(val shape: Shape, rate: Int, hz: Double,
        val history: CreaseHistory, val coupled: Boolean) {
        var pressure = 0.0
        var dA = 0.0; var dB = 0.0
        var recoveryA = 0.0; var recoveryB = 0.0
        var loadA = 0.0; var loadB = 0.0
        var budget = 0.0; var minDerivative = 1.0
        var maxDerivative = 1.0; var maxCurvature = 0.0
        private val dt = 1.0 / rate
        private val riseA = exp(-dt / shape.rise)
        private val riseB = exp(-dt / (shape.rise * (1.0 + 2.8 * shape.split)))
        private val fallA = exp(-dt / shape.recovery)
        private val fallB = exp(-dt / (shape.recovery * (1.0 + 1.8 * shape.split)))
        private val recoverA = exp(-dt / (.012 + shape.recovery * 1.5))
        private val recoverB = exp(-dt / ((.012 + shape.recovery * 1.5) * (1.0 + 2.2 * shape.split)))
        private val pressureRise = exp(-dt / .003)
        private val pressureFall = exp(-dt / .022)
        // Two or more root cycles make the feature substantially register-independent.
        private val loadPole = exp(-dt / max(2.0 / hz, .008 + .060 * shape.drag))

        fun state() = doubleArrayOf(pressure, dA, dB, recoveryA, recoveryB, loadA, loadB)

        fun step(phase: Double, t: Double, loop: Double?): Double {
            val amplitude = shape.amplitude(t)
            val force = shape.forcing(t, loop) * 1.25 * shape.velocity.pow(1.25)
            // Energy estimate of the unwarped root excitation, not the final leveled signal.
            // It is shared by the ablations, so their pressure and amplitude schedules match.
            val excitation = if (shape.velocity == 0.0) 0.0 else (amplitude / shape.velocity).pow(2.0)
            val pTarget = (force * (.90 + .10 * excitation)).coerceIn(0.0, 1.0)
            val pp = if (pTarget > pressure) pressureRise else pressureFall
            pressure = pTarget + (pressure - pTarget) * pp
            val coupling = if (coupled) .90 * (.40 + .60 * shape.split) else 0.0
            // Both loads are the previous completed update. No algebraic feedback loop.
            val targetA = (pressure * (1.0 - coupling * loadB)).coerceIn(0.0, 1.0)
            val targetB = (pressure * (1.0 + .65 * coupling * loadA)).coerceIn(0.0, 1.0)
            if (history == CreaseHistory.INSTANTANEOUS) {
                dA = targetA; dB = targetB; recoveryA = dA; recoveryB = dB
            } else {
                dA = targetA + (dA - targetA) * if (targetA > dA) riseA else fallA
                dB = targetB + (dB - targetB) * if (targetB > dB) riseB else fallB
                recoveryA = dA + (recoveryA - dA) * recoverA
                recoveryB = dB + (recoveryB - dB) * recoverB
            }
            val s = sin(phase); val c = cos(phase)
            val s2 = 2.0 * s * c; val c2 = 2.0 * c * c - 1.0
            val s3 = s * (3.0 - 4.0 * s * s); val c3 = c * (4.0 * c * c - 3.0)
            val amountA = (shape.baseBudget + shape.excursion * dA).coerceAtMost(COEFFICIENT_BUDGET)
            val amountB = (shape.baseBudget + shape.excursion * dB).coerceAtMost(COEFFICIENT_BUDGET)
            val motionA = (.16 + .60 * dA + .24 * recoveryA).coerceIn(0.0, 1.0)
            val motionB = (.16 + .60 * dB + .24 * recoveryB).coerceIn(0.0, 1.0)
            var mapA = phase; var mapB = phase
            var derivativeA = 1.0; var derivativeB = 1.0
            var curvatureA = 0.0; var curvatureB = 0.0
            var boundA = 0.0; var boundB = 0.0
            for (k in 0..2) {
                val a = mix(shape.geometry[k], shape.crease[k], motionA) * amountA
                val commonB = mix(shape.geometry[k], shape.crease[k], motionB)
                val b = mix(commonB, shape.other[k], shape.split * (.60 + .40 * recoveryB)) * amountB
                val sine = when (k) { 0 -> s; 1 -> s2; else -> s3 }
                val cosine = when (k) { 0 -> c; 1 -> c2; else -> c3 }
                mapA += a * sine / (k + 1)
                mapB += b * sine / (k + 1)
                derivativeA += a * cosine
                derivativeB += b * cosine
                curvatureA -= a * (k + 1) * sine
                curvatureB -= b * (k + 1) * sine
                boundA += abs(a); boundB += abs(b)
            }
            val a = sin(mapA); val b = sin(mapB)
            // Squared root-normalized waveform-derivative residual detects spectral deformation
            // even when RMS/crest are invariant (e.g. phi+c*sin(3phi)). No velocity/gain ambiguity.
            val featureA = (shape.loadCalibration * (cos(mapA) * derivativeA - c).pow(2.0)).coerceIn(0.0, 1.0)
            val featureB = (shape.loadCalibration * (cos(mapB) * derivativeB - c).pow(2.0)).coerceIn(0.0, 1.0)
            loadA = featureA + (loadA - featureA) * loadPole
            loadB = featureB + (loadB - featureB) * loadPole
            budget = max(boundA, boundB)
            minDerivative = min(derivativeA, derivativeB)
            maxDerivative = max(derivativeA, derivativeB)
            maxCurvature = max(abs(curvatureA), abs(curvatureB))
            // Symmetric maps keep each fundamental's phase aligned. A small fixed root retains
            // the requested pitch through extreme geometry without concealing its harmonics.
            return amplitude * (.45 * a + .41 * b + .14 * s)
        }
    }

    /** Streaming equivalent of Tide.bandLimit's four fixed TPT low-pass sections. Keeping their
     * two integrators explicit makes all conditioning state part of the held convergence proof. */
    private class LowPass(rate: Int, damping: Double) {
        private val g = tan(PI * Tide.BAND_LIMIT_HZ / rate)
        private val a1 = 1.0 / (1.0 + g * (g + damping))
        private val a2 = g * a1
        private val a3 = g * a2
        var ic1 = 0.0; var ic2 = 0.0
        fun step(x: Double): Double {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2.0 * v1 - ic1
            ic2 = 2.0 * v2 - ic2
            return v2
        }
    }

    /** The Blackman-windowed sinc used by Skitter, evaluated causally with a 24-output-frame
     * delay. Unlike a reset batch converter, its FIR history is carried and checked at wraps.
     * This supports the same output filter at both 4x and the diagnostic 16x reference. */
    private class OutputPath(rate: Int, private val oversample: Int) {
        private val damping = doubleArrayOf(1.9616, 1.6629, 1.1111, .3902)
        private val filters = Array(damping.size) { LowPass(rate, damping[it]) }
        private val radius = 24 * oversample
        private val kernel = DoubleArray(radius * 2 + 1)
        private val ring = DoubleArray(kernel.size)
        private var write = 0
        private var phase = 0
        private var dc = 0.0
        var maxAbsState = 0.0
            private set
        private val dcPole = exp(-TAU * 8.0 / rate)
        init {
            var total = 0.0
            for (i in kernel.indices) {
                val j = i - radius
                val z = j.toDouble() / oversample
                val sinc = if (j == 0) .84 else sin(PI * .84 * z) / (PI * z)
                kernel[i] = sinc * (.42 + .5 * cos(PI * j / radius) + .08 * cos(TAU * j / radius))
                total += kernel[i]
            }
            for (i in kernel.indices) kernel[i] /= total
        }
        fun step(raw: Double): Double? {
            var x = raw
            for (filter in filters) {
                x = filter.step(x)
                maxAbsState = max(maxAbsState, max(abs(filter.ic1), abs(filter.ic2)))
            }
            dc = x + (dc - x) * dcPole
            ring[write] = x - dc
            maxAbsState = max(maxAbsState, max(abs(dc), abs(ring[write])))
            write++
            if (write == ring.size) write = 0
            phase++
            if (phase < oversample) return null
            phase = 0
            var out = 0.0
            var at = write
            for (tap in kernel) {
                out += tap * ring[at]
                at++
                if (at == ring.size) at = 0
            }
            return out
        }
        fun state(): DoubleArray {
            val state = DoubleArray(filters.size * 2 + 2 + ring.size)
            for ((i, filter) in filters.withIndex()) { state[i * 2] = filter.ic1; state[i * 2 + 1] = filter.ic2 }
            state[8] = dc; state[9] = phase.toDouble()
            // Canonical chronological order, rather than the arbitrary circular write index.
            for (i in ring.indices) state[10 + i] = ring[(write + i) % ring.size]
            return state
        }
    }

    private class Stats {
        var count = 0L; var sum = 0.0; var square = 0.0; var peak = 0.0
        var budget = 0.0; var derivative = 1.0; var state = 0.0
        var maxDerivative = 1.0; var curvature = 0.0
        fun add(raw: Double, model: Model) {
            check(raw.isFinite()) { "CREASE produced a non-finite sample" }
            count++; sum += raw; square += raw * raw; peak = max(peak, abs(raw))
            budget = max(budget, model.budget); derivative = min(derivative, model.minDerivative)
            maxDerivative = max(maxDerivative, model.maxDerivative); curvature = max(curvature, model.maxCurvature)
            state = max(state, max(max(model.pressure, max(model.dA, model.dB)),
                max(max(model.recoveryA, model.recoveryB), max(model.loadA, model.loadB))))
        }
    }

    private class TraceBuilder {
        private val times = ArrayList<Float>(); private val p = ArrayList<Float>()
        private val a = ArrayList<Float>(); private val b = ArrayList<Float>()
        private val la = ArrayList<Float>(); private val lb = ArrayList<Float>()
        fun add(time: Double, model: Model) {
            times.add(time.toFloat()); p.add(model.pressure.toFloat())
            a.add(model.dA.toFloat()); b.add(model.dB.toFloat())
            la.add(model.loadA.toFloat()); lb.add(model.loadB.toFloat())
        }
        fun finish() = CreaseTrace(times.toFloatArray(), p.toFloatArray(), a.toFloatArray(),
            b.toFloatArray(), la.toFloatArray(), lb.toFloatArray())
    }
}
