package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Audition-only energy motion in the approved TREFOIL held circuit. This is not a
 * saved engine model, a phone mode, or a general LOCK implementation.
 *
 * The six periodic noise sources, geometry, routing, damping and pickup are the
 * steady proof's exact circuit. Moving modes apply V2's per-node excitation gate
 * and complementary absorption. No gain clock is applied to the combined output.
 * Independent breathing uses integer counts over four source periods; synchronized
 * breathing is its aligned endpoint. Intermediate Kuramoto entrainment is unproved
 * here and deliberately has no accepted recipe.
 *
 * Why this shape: a longer integer superperiod permits slow motion and a complete
 * physical recurrence check. Closed-form control phases avoid integration drift or
 * a control-stride remainder at the seam. A separate implementation protects the
 * accepted held renderer; exact STEADY raw parity guards this isolated duplication.
 * No cache is added: this bounded, cancellable solver serves an offline audition.
 * Source generation and physical blocks check cancellation every 8192 internal
 * frames. Shared Dsp.decimate has no callback: its synchronous conditioning
 * interval is checked immediately before and after, rather than mid-filter.
 */
object OverlapBreathingPrototype {
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val OS = Dsp.OVERSAMPLE
    private const val INTERNAL_RATE = RATE * OS
    private const val TWO_PI = 2.0 * PI
    private const val DAMPING = .22
    private const val CONTROL_STRIDE = 32
    private const val GUARD_FRAMES = 256
    private const val STATE_TOLERANCE = 2e-7
    private const val AUDIO_TOLERANCE = 1e-8
    private const val MAX_ATTEMPTS = 192
    private const val TRACE_WINDOWS = 128
    private val SEGMENTS = doubleArrayOf(1.2, 1.6, 2.0, 3.0, 2.4, 1.8)
    private val FREE_COUNTS = listOf(3, 4, 4, 5, 3, 5)
    private val FREE_OFFSETS = listOf(.13, 1.23, 2.41, 3.07, 4.45, 5.51)

    enum class Mode {
        STEADY, INDEPENDENT, SYNCHRONIZED;

        companion object {
            /** Only the two proposed endpoints exist; this does not emulate intermediate LOCK. */
            fun fromLock(lock: Float): Mode {
                require(lock.isFinite() && (lock == 0f || lock == 1f)) {
                    "breathing audition supports LOCK endpoints 0 or 1 only"
                }
                return if (lock == 0f) INDEPENDENT else SYNCHRONIZED
            }
        }
    }

    /** The circuit is intentionally fixed. Velocity is exposed to verify true silence. */
    data class Recipe(val mode: Mode, val velocity: Float = .8f) {
        init { require(velocity.isFinite() && velocity in 0f..1f) { "breathing velocity must be in 0..1" } }
    }

    data class Plan(
        val recipe: Recipe,
        val topology: OverlapTopology, val midi: Int, val knot: Float, val ring: Float,
        val referenceHz: Double, val sourceRootHz: Double,
        val sourcePeriodFrames: Int, val sourcePeriodSeconds: Double,
        val periodFrames: Int, val periodSeconds: Double,
        val counts: List<Int>, val offsets: List<Double>, val ratesHz: List<Double>,
        val delaySamples: List<Double>, val controlStride: Int,
    )

    data class Diagnostics(
        val strategy: String, val attempts: Int, val maxAttempts: Int, val converged: Boolean,
        val stateError: Double, val audioError: Double, val seam: Double, val nextSeam: Double,
        val rawPeak: Double, val rawRms: Double, val rawMean: Double,
        val maxBufferAbs: Double, val maxLoopGain: Double, val internalFrames: Long,
        val setupMillis: Double, val solveMillis: Double, val conditioningMillis: Double,
        val totalMillis: Double, val stateSize: Int,
        val cycleSourceCursor: Int, val cycleControlCursor: Int,
        val maxInternalFrames: Long, val cancellationChecks: Long, val minLoopGain: Double,
    )

    /**
     * Rows are time windows; columns are the six nodes, before pickup or gain.
     * nodeRms measures damped delay reads. nodeEnergyShares is their windowed
     * squared-signal power share, not total energy stored throughout a delay line.
     */
    data class Trace(
        val windowStartFrames: List<Int>, val windowEndFrames: List<Int>,
        val gateMean: List<List<Double>>, val extraLossMean: List<List<Double>>,
        val nodeRms: List<List<Double>>, val excitationRms: List<List<Double>>,
        val nodeEnergyShares: List<List<Double>>,
    )

    /** All audio is unfaded and unlevelled. The kept cycle begins at plan.periodFrames in rawContext. */
    data class LoopResult(
        val raw: FloatArray, val rawContext: FloatArray, val plan: Plan,
        val diagnostics: Diagnostics, val trace: Trace,
    )

    fun plan(recipe: Recipe): Plan {
        val midi = 48
        val knot = 1f
        val ring = .6f
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val cycles = max(1, (1.5 * hz).roundToInt())
        val sourceFrames = (cycles * RATE / hz).roundToInt()
        val periodFrames = sourceFrames * if (recipe.mode == Mode.STEADY) 1 else 4
        val seconds = periodFrames.toDouble() / RATE
        val counts = when (recipe.mode) {
            Mode.STEADY -> List(6) { 0 }
            Mode.INDEPENDENT -> FREE_COUNTS.toList()
            Mode.SYNCHRONIZED -> List(6) { 4 }
        }
        val offsets = if (recipe.mode == Mode.INDEPENDENT) FREE_OFFSETS.toList() else List(6) { 0.0 }
        val omega = TWO_PI * hz / INTERNAL_RATE
        val lengths = DoubleArray(6) { calibratedDelay(TWO_PI * SEGMENTS[it] / 7.2, omega) }
        return Plan(recipe, OverlapTopology.TREFOIL, midi, knot, ring, hz,
            cycles * RATE.toDouble() / sourceFrames, sourceFrames, sourceFrames.toDouble() / RATE,
            periodFrames, seconds, counts, offsets, counts.map { it / seconds },
            lengths.map { it / OS }, CONTROL_STRIDE)
    }

    /** Exact V2 phase budget, including the same forty fractional-delay bisections. */
    private fun calibratedDelay(targetPhase: Double, omega: Double): Double {
        val wanted = targetPhase - atan2(DAMPING * sin(omega), 1.0 - DAMPING + DAMPING * cos(omega))
        var lo = 1.0
        var hi = max(2.0, wanted / omega + 2.0)
        repeat(40) {
            val middle = (lo + hi) * .5
            val whole = floor(middle)
            val fraction = middle - whole
            val phase = whole * omega + atan2(fraction * sin(omega), 1.0 - fraction + fraction * cos(omega))
            if (phase < wanted) lo = middle else hi = middle
        }
        return (lo + hi) * .5
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
        fun write(x: Double) {
            buffer[position] = x
            position++
            if (position == buffer.size) position = 0
        }
        fun state(): DoubleArray = DoubleArray(buffer.size) { buffer[(position + it) % buffer.size] }
    }

    private class Meter(private val frames: Int) {
        private val count = LongArray(TRACE_WINDOWS)
        private val gate = Array(TRACE_WINDOWS) { DoubleArray(6) }
        private val loss = Array(TRACE_WINDOWS) { DoubleArray(6) }
        private val node = Array(TRACE_WINDOWS) { DoubleArray(6) }
        private val excitation = Array(TRACE_WINDOWS) { DoubleArray(6) }
        fun add(n: Int, gates: DoubleArray, losses: DoubleArray, nodes: DoubleArray, drives: DoubleArray) {
            val window = (n.toLong() * TRACE_WINDOWS / (frames * OS)).toInt()
            count[window]++
            for (i in 0..5) {
                gate[window][i] += gates[i]
                loss[window][i] += losses[i]
                node[window][i] += nodes[i] * nodes[i]
                excitation[window][i] += drives[i] * drives[i]
            }
        }
        fun finish(): Trace {
            fun mean(values: Array<DoubleArray>) = values.indices.map { w -> values[w].map { it / count[w] } }
            fun rms(values: Array<DoubleArray>) = values.indices.map { w -> values[w].map { sqrt(it / count[w]) } }
            val shares = node.map { row ->
                val sum = row.sum()
                row.map { if (sum <= 1e-30) 0.0 else it / sum }
            }
            return Trace(List(TRACE_WINDOWS) { ((it.toLong() * frames + TRACE_WINDOWS - 1) / TRACE_WINDOWS).toInt() },
                List(TRACE_WINDOWS) { (((it + 1L) * frames + TRACE_WINDOWS - 1) / TRACE_WINDOWS).toInt() },
                mean(gate), mean(loss), rms(node), rms(excitation), shares)
        }
    }

    private data class Block(val audio: FloatArray, val trace: Trace)

    private class Machine(val plan: Plan, private val cancelled: () -> Boolean) {
        private val sourceCount = plan.sourcePeriodFrames * OS
        private val controlCount = plan.periodFrames * OS
        private val lengths = plan.delaySamples.map { it * OS }.toDoubleArray()
        private val lines = Array(6) { Delay(lengths[it]) }
        private val columns = Array(6) { column -> OverlapV2.scatter(plan.topology, plan.knot.toDouble(),
            DoubleArray(6) { if (it == column) 1.0 else 0.0 }) }
        private val scattering = Array(6) { row -> DoubleArray(6) { column -> columns[column][row] } }
        private val previous = DoubleArray(6)
        private val damped = DoubleArray(6)
        private val inputLow = DoubleArray(6)
        private val excitation = DoubleArray(6)
        private val weights = doubleArrayOf(.83, .71, .88, 1.0, .79, .92)
        private val pickup = doubleArrayOf(.75, .57, -.63, .90, -.61, .70)
        private val lineSeconds = DoubleArray(6) { (lengths[it] + DAMPING) / INTERNAL_RATE }
        private val t60 = .55 * 24.0.pow(plan.ring.toDouble())
        val gains = DoubleArray(6) { exp(-6.90775527898 * (lengths[it] + DAMPING) / (INTERNAL_RATE * t60)) }
        private val movingGains = gains.copyOf()
        private val gates = DoubleArray(6) { 1.0 }
        private val losses = DoubleArray(6)
        private val driver = Array(6) { node ->
            val noise = Dsp.Noise(Dsp.seedFor("OVERLAP held prototype 1", plan.midi, node))
            FloatArray(sourceCount) { if (it % 8192 == 0) stop(cancelled); noise.next() }
        }
        private val driverMean = DoubleArray(6) { node ->
            var sum = 0.0
            for (i in 0 until sourceCount) {
                if (i % 8192 == 0) stop(cancelled)
                sum += driver[node][i].toDouble()
            }
            sum / sourceCount
        }
        private var sourceCursor = 0
        private var controlCursor = 0
        private var finalLow = 0.0
        private var dcInput = 0.0
        private var dcOutput = 0.0
        private val sourceAlpha = 1.0 - exp(-TWO_PI * 6_000.0 / INTERNAL_RATE)
        private val finalLowAlpha = 1.0 - exp(-TWO_PI * 10_000.0 / INTERNAL_RATE)
        private val dcPole = exp(-TWO_PI * 8.0 / INTERNAL_RATE)
        var maxBufferAbs = 0.0
            private set
        var maxLoopGain = 0.0
            private set
        var minLoopGain = 1.0
            private set
        var processed = 0L
            private set

        init { updateControl() }

        private fun updateControl() {
            if (plan.recipe.mode != Mode.STEADY) {
                // Cursor is periodic. Every stride begins from its exact closed-form
                // phase, without accumulating a phase increment or remainder error.
                val anchor = controlCursor / CONTROL_STRIDE * CONTROL_STRIDE
                for (i in 0..5) {
                    val phase = TWO_PI * plan.counts[i] * anchor / controlCount + plan.offsets[i]
                    val inhale = max(0.0, sin(phase))
                    gates[i] = .012 + .988 * inhale * inhale * inhale
                    losses[i] = 18.0 * (1.0 - gates[i])
                    movingGains[i] = gains[i] * exp(-lineSeconds[i] * losses[i])
                }
            }
            // Each held control value is applied by the following sample. Report
            // observed circuit gains, rather than the nominal unmodulated array.
            for (gain in movingGains) {
                maxLoopGain = max(maxLoopGain, gain)
                minLoopGain = kotlin.math.min(minLoopGain, gain)
            }
        }

        private fun step(): Float {
            if (controlCursor % CONTROL_STRIDE == 0) updateControl()
            for (i in 0..5) {
                inputLow[i] += sourceAlpha * (driver[i][sourceCursor] - driverMean[i] - inputLow[i])
                val read = lines[i].read()
                damped[i] = (1.0 - DAMPING) * read + DAMPING * previous[i]
                previous[i] = read
            }
            var output = 0.0
            for (i in 0..5) {
                var routed = 0.0
                for (j in 0..5) routed += scattering[i][j] * damped[j]
                excitation[i] = .014 * plan.recipe.velocity * weights[i] * inputLow[i] * gates[i]
                val write = excitation[i] + movingGains[i] * routed
                lines[i].write(write)
                maxBufferAbs = max(maxBufferAbs, abs(write))
                output += pickup[i] * damped[i]
            }
            finalLow += finalLowAlpha * (.38 * output - finalLow)
            val dc = finalLow - dcInput + dcPole * dcOutput
            dcInput = finalLow; dcOutput = dc
            check(dc.isFinite() && maxBufferAbs < 1e6) { "breathing proof non-finite or unbounded state" }
            sourceCursor++
            if (sourceCursor == sourceCount) sourceCursor = 0
            controlCursor++
            if (controlCursor == controlCount) controlCursor = 0
            processed++
            return dc.toFloat()
        }

        fun block(): Block {
            val meter = Meter(plan.periodFrames)
            val audio = FloatArray(controlCount) { n ->
                if (n % 8192 == 0) stop(cancelled)
                val out = step()
                meter.add(n, gates, losses, damped, excitation)
                out
            }
            return Block(audio, meter.finish())
        }

        fun guard(): FloatArray = FloatArray(GUARD_FRAMES * OS) { n ->
            if (n % 8192 == 0) stop(cancelled)
            step()
        }

        fun sourcePosition(): Int = sourceCursor
        fun controlPosition(): Int = controlCursor

        fun state(): DoubleArray {
            val rings = lines.map { it.state() }
            val result = DoubleArray(rings.sumOf { it.size } + 6 + 6 + 5 + 18)
            var at = 0
            for (ring in rings) for (x in ring) result[at++] = x
            for (x in previous) result[at++] = x
            for (x in inputLow) result[at++] = x
            result[at++] = finalLow; result[at++] = dcInput; result[at++] = dcOutput
            result[at++] = sourceCursor.toDouble() / sourceCount
            result[at++] = controlCursor.toDouble() / controlCount
            // Cached control values also belong to the complete machine state.
            for (x in gates) result[at++] = x
            for (x in losses) result[at++] = x
            for (x in movingGains) result[at++] = x
            return result
        }
    }

    fun renderLoopMeasured(recipe: Recipe, cancelled: () -> Boolean = { false }): LoopResult {
        var cancellationChecks = 0L
        val checkedCancellation = { cancellationChecks++; cancelled() }
        stop(checkedCancellation)
        val started = System.nanoTime()
        val p = plan(recipe)
        val machine = Machine(p, checkedCancellation)
        val setup = elapsed(started)
        val solving = System.nanoTime()
        val t60 = .55 * 24.0.pow(p.ring.toDouble())
        val budget = (ceil((3.0 * t60 + 2.0) / p.periodSeconds).toInt() + 2).coerceIn(6, MAX_ATTEMPTS)
        var previous: Block? = null
        var leadingGuard: FloatArray? = null
        var current: Block? = null
        var stateError = 1.0
        var audioError = 1.0
        var confirmations = 0
        var attempts = 0
        for (attempt in 1..budget) {
            stop(checkedCancellation)
            val before = machine.state()
            val block = machine.block()
            current = block
            stateError = difference(before, machine.state())
            audioError = previous?.let { audioDifference(it.audio, block.audio) } ?: 1.0
            attempts = attempt
            if (stateError < STATE_TOLERANCE && audioError < AUDIO_TOLERANCE) confirmations++ else confirmations = 0
            if (confirmations >= 2) break
            // Retain real history immediately before the period that will become
            // the predecessor. At convergence this is the tail of attempt n-2,
            // never a copied wrap from predecessor/current/following audio.
            leadingGuard = previous?.audio?.let { it.copyOfRange(it.size - GUARD_FRAMES * OS, it.size) }
            previous = block
        }
        check(confirmations >= 2) { "OVERLAP breathing did not converge in $attempts/$budget cycles: state=$stateError audio=$audioError" }
        val kept = requireNotNull(current)
        val beforeNext = machine.state()
        val next = machine.block()
        stateError = max(stateError, difference(beforeNext, machine.state()))
        audioError = max(audioError, audioDifference(kept.audio, next.audio))
        check(stateError < STATE_TOLERANCE && audioError < AUDIO_TOLERANCE) { "OVERLAP breathing recurrence did not persist" }
        val cycleSourceCursor = machine.sourcePosition()
        val cycleControlCursor = machine.controlPosition()
        val stateSize = machine.state().size
        val guard = machine.guard()
        val solve = elapsed(solving)
        val conditioning = System.nanoTime()
        stop(checkedCancellation)
        // Both guards are genuine evolution outside the three exported periods.
        // The leading guard was retained during settling, so no extra frame count
        // or copied wrap is needed to protect even the predecessor's first taps.
        val joined = requireNotNull(leadingGuard) + requireNotNull(previous).audio + kept.audio + next.audio + guard
        val context = Dsp.decimate(joined, RATE).copyOfRange(GUARD_FRAMES, GUARD_FRAMES + 3 * p.periodFrames)
        stop(checkedCancellation)
        val raw = context.copyOfRange(p.periodFrames, 2 * p.periodFrames)
        val preceding = context.copyOfRange(0, p.periodFrames)
        val following = context.copyOfRange(2 * p.periodFrames, 3 * p.periodFrames)
        val seam = if (recipe.velocity == 0f) 0.0 else Keys.seamError(preceding + raw, p.periodFrames)
        val nextSeam = if (recipe.velocity == 0f) 0.0 else Keys.seamError(raw + following, p.periodFrames)
        check(seam.isFinite() && nextSeam.isFinite() && seam < Keys.MAX_SEAM_ERROR && nextSeam < Keys.MAX_SEAM_ERROR) {
            "OVERLAP breathing physical seam failed: before=$seam after=$nextSeam"
        }
        var peak = 0.0; var sum = 0.0; var squares = 0.0
        for (i in raw.indices) {
            if (i % 8192 == 0) stop(checkedCancellation)
            val x = raw[i].toDouble()
            peak = max(peak, abs(x)); sum += x; squares += x * x
        }
        val condition = elapsed(conditioning)
        val diagnostics = Diagnostics("periodic six-node source; node excitation and absorption; full-state recurrence",
            attempts, budget, true, stateError, audioError, seam, nextSeam, peak, sqrt(squares / raw.size),
            sum / raw.size, machine.maxBufferAbs, machine.maxLoopGain, machine.processed,
            setup, solve, condition, elapsed(started), stateSize, cycleSourceCursor, cycleControlCursor,
            (budget + 1L) * p.periodFrames * OS + GUARD_FRAMES * OS, cancellationChecks, machine.minLoopGain)
        stop(checkedCancellation)
        return LoopResult(raw, context, p, diagnostics, kept.trace)
    }

    private fun difference(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)
        return a.indices.maxOf { abs(a[it] - b[it]) }
    }

    private fun audioDifference(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 1.0
        var error = 0.0; var power = 0.0
        for (i in a.indices) {
            val d = a[i].toDouble() - b[i]
            error += d * d; power += b[i].toDouble() * b[i]
        }
        return if (power <= 1e-30) error else error / power
    }

    private fun stop(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("OVERLAP breathing audition cancelled")
    }
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1e6
}
