package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Opt-in steady held-note experiment. It does not change either saved OVERLAP model.
 *
 * Gesture: a periodic six-node rubbing source opens -> the same passive scattering
 * network sustains -> note-off withdraws that source -> stored energy rings away.
 * Geometry, loss, routing and pickup follow V2. The new source repeats a deterministic
 * noise block whose period fits approximately an integer number of reference cycles.
 * The physical geometry stays at the requested note; source-grid fitting does not
 * silently retune it. This periodic texture is a deliberate held-mode trade.
 *
 * Supported controls: KNOT and RING retain their V2 meanings; HOLD must be 1.
 * PULSE and GLOW must be 0. LOCK is inactive with PULSE off. Breathing and harmonic
 * illumination need their own recurrence proof before being added to this experiment.
 *
 * The feedback path stays passive. Orthogonal V2 scattering, contractive interpolation
 * and damping, and line gains below one give the same small-gain stability argument.
 * State comparisons include cursor-relative delay storage and all persistent filters.
 * No state reset, audio wrap repair or end fade is used to manufacture convergence.
 *
 * Why this shape: periodic forcing permits a real recurrence check; guard audio protects
 * the shared decimator from artificial boundaries; note-off changes excitation only;
 * keeping this object separate protects every approved finite recipe and its bytes.
 */
object OverlapHeldPrototype {
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val OS = Dsp.OVERSAMPLE
    private const val INTERNAL_RATE = RATE * OS
    private const val TWO_PI = 2.0 * PI
    private const val DAMPING = .22
    private const val GUARD_FRAMES = 256
    private const val STATE_TOLERANCE = 2e-7
    private const val AUDIO_TOLERANCE = 1e-8
    private const val MAX_ATTEMPTS = 192
    private val SEGMENTS = doubleArrayOf(1.2, 1.6, 2.0, 3.0, 2.4, 1.8)

    data class Diagnostics(
        val strategy: String,
        val periodFrames: Int, val periodSeconds: Double, val requestedPeriodSeconds: Double,
        val referenceHz: Double, val sourceRootHz: Double,
        val attempts: Int, val maxAttempts: Int, val converged: Boolean,
        val stateError: Double, val audioError: Double, val seam: Double?, val nextSeam: Double?,
        val rawPeak: Double, val rawRms: Double, val maxBufferAbs: Double, val maxLoopGain: Double,
        val internalFrames: Long, val setupMillis: Double, val solveMillis: Double,
        val conditioningMillis: Double, val totalMillis: Double,
        val tailRmsRatio: Double, val tailWasExplicit: Boolean, val delaySamples: List<Double>,
    )

    /** seamContext is three independently evolved native-level periods; kept starts at periodFrames. */
    data class LoopResult(
        val snip: Snip, val raw: FloatArray, val seamContext: FloatArray, val diagnostics: Diagnostics,
    )

    /**
     * internalRaw is the causal 4x output, before shared decimation or uniform gain.
     * It proves exact prefix invariance; centered decimation can look ahead at note-off.
     * Native leveling uses one scalar for the complete capture, so native gain can
     * depend on capture length. Prefix equality applies before that gain.
     * raw is the unfaded, unleveled 44.1-kHz capture. No late fade hides tail energy.
     */
    data class HeldNoteResult(
        val snip: Snip, val raw: FloatArray, val internalRaw: FloatArray,
        val noteOffFrame: Int, val driveStopsFrame: Int, val diagnostics: Diagnostics,
    )

    private data class Plan(
        val topology: OverlapTopology, val knot: Double, val ring: Double, val midi: Int,
        val velocity: Float, val requestedSeconds: Double, val frames: Int, val cycles: Int,
        val referenceHz: Double, val lengths: DoubleArray, val scattering: Array<DoubleArray>,
    ) {
        val seconds get() = frames.toDouble() / RATE
        val count get() = frames * OS
        val sourceRootHz get() = cycles * RATE.toDouble() / frames
        val t60 get() = .55 * 24.0.pow(ring)
    }

    private fun plan(topology: OverlapTopology, macros: Map<String, Float>, midi: Int,
        velocity: Float, periodSeconds: Double): Plan {
        require(midi in Overlap.MIN_MIDI..Overlap.MAX_MIDI) { "held prototype MIDI out of supported range: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "held prototype velocity must be in 0..1" }
        require(periodSeconds.isFinite() && periodSeconds in .25..3.0) { "prototype periodSeconds must be in .25..3" }
        val m = (Overlap.defaults(topology, model = 2) +
            mapOf("PULSE" to 0f, "GLOW" to 0f, "HOLD" to 1f)).toMutableMap()
        for ((key, value) in macros) {
            require(key in m) { "unknown held prototype macro $key" }
            require(value.isFinite() && value in 0f..1f) { "held prototype $key must be in 0..1" }
            m[key] = value
        }
        require(m.getValue("PULSE") == 0f) { "held prototype supports PULSE=0 only; breathing recurrence is not implemented" }
        require(m.getValue("GLOW") == 0f) { "held prototype supports GLOW=0 only; harmonic-state recurrence is not implemented" }
        require(m.getValue("HOLD") == 1f) { "held prototype requires HOLD=1; finite V2 recipes remain unchanged" }
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val cycles = max(1, (periodSeconds * hz).roundToInt())
        val frames = (cycles * RATE / hz).roundToInt()
        val knot = m.getValue("KNOT").toDouble()
        val denominator = 3.0 + knot * (when (topology) {
            OverlapTopology.SELF -> 3.0
            OverlapTopology.TREFOIL -> 7.2
            OverlapTopology.MIRROR -> 10.0
            OverlapTopology.TORUS -> 24.0
        } - 3.0)
        val omega = TWO_PI * hz / INTERNAL_RATE
        val lengths = DoubleArray(6) { calibratedDelay(TWO_PI * SEGMENTS[it] / denominator, omega) }
        val columns = Array(6) { column -> OverlapV2.scatter(topology, knot,
            DoubleArray(6) { if (it == column) 1.0 else 0.0 }) }
        val matrix = Array(6) { row -> DoubleArray(6) { column -> columns[column][row] } }
        return Plan(topology, knot, m.getValue("RING").toDouble(), midi, velocity,
            periodSeconds, frames, cycles, hz, lengths, matrix)
    }

    /** V2's physical phase budget, retained here without touching its approved renderer. */
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
        // Compare the future-readable order, not rotating backing-array addresses.
        fun state(): DoubleArray = DoubleArray(buffer.size) { buffer[(position + it) % buffer.size] }
    }

    private class Machine(val plan: Plan, private val cancelled: () -> Boolean = { false }) {
        private val lines = Array(6) { Delay(plan.lengths[it]) }
        private val previous = DoubleArray(6)
        private val damped = DoubleArray(6)
        private val inputLow = DoubleArray(6)
        private val weights = doubleArrayOf(.83, .71, .88, 1.0, .79, .92)
        private val pickup = doubleArrayOf(.75, .57, -.63, .90, -.61, .70)
        val gains = DoubleArray(6) {
            exp(-6.90775527898 * (plan.lengths[it] + DAMPING) / (INTERNAL_RATE * plan.t60))
        }
        private val driver = Array(6) { node ->
            val noise = Dsp.Noise(Dsp.seedFor("OVERLAP held prototype 1", plan.midi, node))
            FloatArray(plan.count) { if (it % 8192 == 0) stopIfUnwanted(cancelled); noise.next() }
        }
        // A finite random block can contain DC. Remove its exact period mean before
        // the resonators, so long RING is not spent maintaining an incidental offset.
        private val driverMean = DoubleArray(6) { node -> driver[node].sumOf { it.toDouble() } / plan.count }
        private var cursor = 0
        private var finalLow = 0.0
        private var dcInput = 0.0
        private var dcOutput = 0.0
        private val sourceAlpha = 1.0 - exp(-TWO_PI * 6_000.0 / INTERNAL_RATE)
        private val finalLowAlpha = 1.0 - exp(-TWO_PI * 10_000.0 / INTERNAL_RATE)
        private val dcPole = exp(-TWO_PI * 8.0 / INTERNAL_RATE)
        var maxBufferAbs = 0.0
            private set
        var processed = 0L
            private set
        fun step(drive: Double): Float {
            for (i in 0..5) {
                inputLow[i] += sourceAlpha * (driver[i][cursor] - driverMean[i] - inputLow[i])
                val read = lines[i].read()
                damped[i] = (1.0 - DAMPING) * read + DAMPING * previous[i]
                previous[i] = read
            }
            var output = 0.0
            for (i in 0..5) {
                var routed = 0.0
                for (j in 0..5) routed += plan.scattering[i][j] * damped[j]
                val excitation = .014 * plan.velocity * weights[i] * inputLow[i] * drive
                val write = excitation + gains[i] * routed
                lines[i].write(write)
                maxBufferAbs = max(maxBufferAbs, abs(write))
                output += pickup[i] * damped[i]
            }
            finalLow += finalLowAlpha * (.38 * output - finalLow)
            val dc = finalLow - dcInput + dcPole * dcOutput
            dcInput = finalLow; dcOutput = dc
            check(dc.isFinite() && maxBufferAbs < 1e6) { "held prototype non-finite or unbounded state" }
            cursor++
            if (cursor == plan.count) cursor = 0
            processed++
            return dc.toFloat()
        }
        fun block(count: Int): FloatArray = FloatArray(count) { i ->
            if (i % 8192 == 0) stopIfUnwanted(cancelled)
            step(1.0)
        }
        fun state(): DoubleArray {
            val rings = lines.map { it.state() }
            val result = DoubleArray(rings.sumOf { it.size } + 6 + 6 + 4)
            var at = 0
            for (ring in rings) for (x in ring) result[at++] = x
            for (x in previous) result[at++] = x
            for (x in inputLow) result[at++] = x
            result[at++] = finalLow; result[at++] = dcInput; result[at++] = dcOutput
            result[at] = cursor.toDouble() / plan.count
            return result
        }
    }

    private fun stopIfUnwanted(cancelled: () -> Boolean = { false }) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw java.util.concurrent.CancellationException("OVERLAP held proof cancelled")
    }
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1e6
    private fun rms(s: FloatArray, a: Int = 0, b: Int = s.size): Double =
        if (a >= b) 0.0 else sqrt((a until b).sumOf { s[it].toDouble() * s[it] } / (b - a))
    private fun peak(s: FloatArray) = s.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
    private fun difference(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)
        return a.indices.maxOf { abs(a[it] - b[it]) }
    }
    private fun audioDifference(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 1.0
        var error = 0.0; var power = 0.0
        for (i in a.indices) { val d = a[i].toDouble() - b[i]; error += d * d; power += b[i].toDouble() * b[i] }
        return if (power <= 1e-30) error else error / power
    }
    private fun leveled(raw: FloatArray, velocity: Float): FloatArray = raw.copyOf().also { samples ->
        Dsp.levelTo(samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val dynamics = sqrt(velocity)
        for (i in samples.indices) samples[i] *= dynamics
    }

    fun renderLoopMeasured(
        topology: OverlapTopology, macros: Map<String, Float> = emptyMap(), midi: Int = 48,
        velocity: Float = .8f, periodSeconds: Double = 1.5,
        cancelled: () -> Boolean = { false },
    ): LoopResult {
        stopIfUnwanted(cancelled)
        val started = System.nanoTime()
        val p = plan(topology, macros, midi, velocity, periodSeconds)
        val machine = Machine(p, cancelled)
        val setup = elapsed(started)
        val solving = System.nanoTime()
        val budget = (ceil((3.0 * p.t60 + 2.0) / p.seconds).toInt() + 2).coerceIn(6, MAX_ATTEMPTS)
        var previous: FloatArray? = null
        var current = FloatArray(0)
        var stateError = 1.0
        var audioError = 1.0
        var confirmations = 0
        var attempts = 0
        for (attempt in 1..budget) {
            val before = machine.state()
            current = machine.block(p.count)
            stateError = difference(before, machine.state())
            audioError = previous?.let { audioDifference(it, current) } ?: 1.0
            attempts = attempt
            if (stateError < STATE_TOLERANCE && audioError < AUDIO_TOLERANCE) confirmations++ else confirmations = 0
            if (confirmations >= 2) break
            previous = current
        }
        check(confirmations >= 2) { "OVERLAP held loop did not converge in $attempts/$budget periods: state=$stateError audio=$audioError" }
        val beforeNext = machine.state()
        val next = machine.block(p.count)
        stateError = max(stateError, difference(beforeNext, machine.state()))
        audioError = max(audioError, audioDifference(current, next))
        check(stateError < STATE_TOLERANCE && audioError < AUDIO_TOLERANCE) { "OVERLAP held recurrence did not persist" }
        val guard = machine.block(GUARD_FRAMES * OS)
        val solve = elapsed(solving)
        val conditioning = System.nanoTime()
        // All three periods and following guards were synthesized by the same live
        // machine. No copy of an exported period is substituted at either seam.
        val joined = requireNotNull(previous) + current + next + guard
        val context = Dsp.decimate(joined, RATE).copyOfRange(0, 3 * p.frames)
        val preceding = context.copyOfRange(0, p.frames)
        val raw = context.copyOfRange(p.frames, 2 * p.frames)
        val following = context.copyOfRange(2 * p.frames, 3 * p.frames)
        val seam = if (velocity == 0f) 0.0 else Keys.seamError(preceding + raw, p.frames)
        val nextSeam = if (velocity == 0f) 0.0 else Keys.seamError(raw + following, p.frames)
        check(seam.isFinite() && nextSeam.isFinite() && seam < Keys.MAX_SEAM_ERROR && nextSeam < Keys.MAX_SEAM_ERROR) {
            "OVERLAP held physical seam failed: before=$seam after=$nextSeam"
        }
        val samples = leveled(raw, velocity)
        val rawPeak = peak(raw)
        val gain = if (rawPeak > 1e-30) peak(samples) / rawPeak else 1.0
        for (i in context.indices) context[i] = (context[i] * gain).toFloat()
        val condition = elapsed(conditioning)
        val diagnostics = Diagnostics("periodic six-node source; full-state and independent-period recurrence",
            p.frames, p.seconds, p.requestedSeconds, p.referenceHz, p.sourceRootHz,
            attempts, budget, true, stateError, audioError, seam, nextSeam, rawPeak, rms(raw),
            machine.maxBufferAbs, machine.gains.maxOrNull() ?: 0.0, machine.processed,
            setup, solve, condition, elapsed(started), 0.0, false, p.lengths.map { it / OS })
        // Export the kept physical period after the SAME scalar as its true
        // neighbors. House gain inference can differ by an ULP from separate
        // Float gain stages; do not return a separately rounded recording.
        stopIfUnwanted(cancelled)
        return LoopResult(Snip(context.copyOfRange(p.frames, 2 * p.frames), 1, RATE), raw, context, diagnostics)
    }

    /** Actual source gate, exposed internally to check note-off during the attack. */
    internal fun excitationEnvelope(n: Int, off: Int, stops: Int): Double {
        // A key released before the attack completes withdraws the level reached
        // at that instant; continuing the attack could add more drive after off.
        val attack = (min(n, off) / (.48 * INTERNAL_RATE)).coerceIn(0.0, 1.0)
            .let { it * it * (3.0 - 2.0 * it) }
        val release = when {
            n < off -> 1.0
            n >= stops -> 0.0
            else -> .5 + .5 * cos(PI * (n - off) / (stops - off))
        }
        return attack * release
    }

    fun renderHeldNote(
        topology: OverlapTopology, macros: Map<String, Float> = emptyMap(), midi: Int = 48,
        velocity: Float = .8f, sustainSeconds: Double = 3.0, releaseSeconds: Double = .25,
        periodSeconds: Double = 1.5, tailSeconds: Double? = null,
    ): HeldNoteResult {
        val started = System.nanoTime()
        val p = plan(topology, macros, midi, velocity, periodSeconds)
        require(sustainSeconds.isFinite() && sustainSeconds in .1..10.0) { "sustainSeconds must be in .1..10" }
        require(releaseSeconds.isFinite() && releaseSeconds in .02..2.0) { "releaseSeconds must be in .02..2" }
        val tail = tailSeconds ?: max(1.0, 1.6 * p.t60 + .5)
        require(tail.isFinite() && tail in .1..24.0) { "tailSeconds must be in .1..24" }
        val noteOff = (sustainSeconds * RATE).roundToInt()
        val stops = noteOff + (releaseSeconds * RATE).roundToInt()
        val frames = stops + (tail * RATE).roundToInt()
        val count = frames * OS
        val offInternal = noteOff * OS
        val stopInternal = stops * OS
        val machine = Machine(p)
        val setup = elapsed(started)
        val solving = System.nanoTime()
        fun drive(n: Int) = excitationEnvelope(n, offInternal, stopInternal)
        val internal = FloatArray(count) { n ->
            if (n % 8192 == 0) stopIfUnwanted()
            machine.step(drive(n))
        }
        // Following real passive motion, not zeros, protects the last captured
        // frames from the centered resampler's right-edge padding.
        val guard = FloatArray(GUARD_FRAMES * OS) { i -> machine.step(drive(count + i)) }
        val solve = elapsed(solving)
        val conditioning = System.nanoTime()
        val raw = Dsp.decimate(internal + guard, RATE).copyOf(frames)
        val samples = leveled(raw, velocity)
        val window = min(RATE / 5, raw.size)
        var power = 0.0
        for (i in 0 until window) power += raw[i].toDouble() * raw[i]
        var maximum = power
        for (i in window until raw.size) {
            power += raw[i].toDouble() * raw[i] - raw[i - window].toDouble() * raw[i - window]
            maximum = max(maximum, power)
        }
        val body = sqrt(maximum / max(1, window))
        val tailRmsRatio = if (body > 1e-30) rms(raw, raw.size - window, raw.size) / body else 0.0
        val condition = elapsed(conditioning)
        val diagnostics = Diagnostics("causal held gesture; source-only cosine release; unfaded passive tail",
            p.frames, p.seconds, p.requestedSeconds, p.referenceHz, p.sourceRootHz,
            0, 0, false, 0.0, 0.0, null, null, peak(raw), rms(raw),
            machine.maxBufferAbs, machine.gains.maxOrNull() ?: 0.0, machine.processed,
            setup, solve, condition, elapsed(started), tailRmsRatio, tailSeconds != null, p.lengths.map { it / OS })
        return HeldNoteResult(Snip(samples, 1, RATE), raw, internal, noteOff, stops, diagnostics)
    }
}
