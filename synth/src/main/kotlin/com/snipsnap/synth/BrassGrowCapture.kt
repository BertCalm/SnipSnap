package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A native-pitch sustain capture closed against the played source's real continuation. */
internal object BrassGrowCapture {
    data class Result(val samples: FloatArray, val metadata: Map<String, Any?>)

    private const val MIN_CORRELATION = .80
    private const val MAX_DIP_DB = 1.0
    private const val MAX_STEP_RATIO = 2.0
    private const val SEARCH_STRIDE = 8
    private const val REFINE_RADIUS = SEARCH_STRIDE * 2

    private data class Candidate(
        val samples: FloatArray,
        val start: Int,
        val length: Int,
        val correlation: Double,
        val dipDb: Double,
        val junction: Int,
        val blendExit: Int,
        val junctionStep: Double,
        val wrapStep: Double,
        val blendExitStep: Double,
        val interiorP99: Double,
    ) {
        val junctionRatio get() = junctionStep / interiorP99
        val wrapRatio get() = wrapStep / interiorP99
        val blendExitRatio get() = blendExitStep / interiorP99
        val accepted get() = correlation >= MIN_CORRELATION && dipDb >= -MAX_DIP_DB &&
            junctionRatio <= MAX_STEP_RATIO && wrapRatio <= MAX_STEP_RATIO &&
            blendExitRatio <= MAX_STEP_RATIO
    }

    /**
     * Search proxies nominate lengths; every emitted sample and quality measurement uses
     * the original source rate. The result does not change pitch or gain. A failed quality
     * report is retained for diagnostics and must be refused by the publishing renderer.
     */
    fun close(source: FloatArray, cancelled: () -> Boolean = { false }): Result {
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted || cancelled())
                throw CancellationException("BRASS GROW capture no longer wanted")
        }
        checkCancelled()
        var peak = 0f
        for (i in source.indices) {
            if (i % 8192 == 0) checkCancelled()
            require(source[i].isFinite()) { "BRASS GROW capture source is not finite" }
            peak = maxOf(peak, abs(source[i]))
        }
        require(peak > 0f) { "BRASS GROW capture source is silent" }
        val blend = frames(.3)
        val starts = listOf(frames(2.5), frames(3.0))
        require(source.size >= starts.last() + frames(3.72) + blend) {
            "BRASS GROW capture needs at least 7.02 seconds of continuous source"
        }
        val candidates = ArrayList<Candidate>(17)

        fun consider(start: Int, length: Int, correlation: Double) {
            checkCancelled()
            val unturned = Bore.assembleLoop(source, start, length, blend, correlation)
            val dip = blendDipDb(unturned, blend, ::checkCancelled)
            checkCancelled()
            val (loop, junction) = Bore.rotateOntoCrossing(unturned)
            checkCancelled()
            val steps = DoubleArray(length - 1) { i ->
                if (i % 8192 == 0) checkCancelled()
                require(loop[i].isFinite() && loop[i + 1].isFinite()) {
                    "BRASS GROW capture produced a non-finite sample"
                }
                abs(loop[i + 1].toDouble() - loop[i])
            }
            steps.sort()
            checkCancelled()
            val p99 = steps[((steps.size - 1) * .99).toInt()]
            require(correlation.isFinite() && dip.isFinite() && p99.isFinite() && p99 > 0.0) {
                "BRASS GROW capture has no finite non-silent join measurement"
            }
            val blendExit = (junction + blend) % length
            fun stepAt(frame: Int) = abs(loop[frame].toDouble() - loop[(frame - 1 + length) % length])
            candidates += Candidate(loop, start, length, correlation, dip, junction, blendExit,
                stepAt(junction), stepAt(0), stepAt(blendExit), p99)
        }

        fun search(start: Int, low: Int, high: Int) {
            checkCancelled()
            val proxy = FloatArray((source.size - start + SEARCH_STRIDE - 1) / SEARCH_STRIDE) { i ->
                if (i % 4096 == 0) checkCancelled()
                source[start + i * SEARCH_STRIDE]
            }
            val (coarseLength, _) = bestLength(proxy, 0, blend / SEARCH_STRIDE,
                low / SEARCH_STRIDE, high / SEARCH_STRIDE, ::checkCancelled)
            val nominalLength = coarseLength * SEARCH_STRIDE
            val (length, correlation) = bestLength(source, start, blend,
                maxOf(low, nominalLength - REFINE_RADIUS),
                minOf(high, nominalLength + REFINE_RADIUS), ::checkCancelled)
            consider(start, length, correlation)
        }

        for (start in starts) search(start, frames(3.48), frames(3.72))
        val broaderSearchUsed = candidates.none { it.accepted }
        val attemptedBroaderStarts = ArrayList<Int>()
        if (broaderSearchUsed) for (i in 0..14) {
            val start = frames(1.5 + i * .25)
            val low = frames(2.5)
            val high = minOf(frames(5.0), source.size - start - blend)
            if (high < low) continue
            attemptedBroaderStarts += start
            search(start, low, high)
        }
        checkCancelled()
        val accepted = candidates.filter { it.accepted }
        val chosen = accepted.ifEmpty { candidates }.maxWithOrNull(
            compareBy<Candidate> { it.correlation }.thenBy { it.dipDb }
                .thenBy { -it.length }.thenBy { -it.start },
        )!!
        return Result(chosen.samples, linkedMapOf(
            "method" to "Native-pitch played-source capture with a correlation-compensated continuation crossfade; not periodic-state convergence or duplicated-buffer seam evidence.",
            "sampleRate" to Dsp.RATE,
            "sourceFrames" to source.size,
            "sourceCaptureStartFrames" to chosen.start,
            "sourceCaptureEndExclusiveFrames" to chosen.start + chosen.length,
            "sourceContinuationEndExclusiveFrames" to chosen.start + chosen.length + blend,
            "captureStartSeconds" to chosen.start.toDouble() / Dsp.RATE,
            "captureEndSeconds" to (chosen.start + chosen.length).toDouble() / Dsp.RATE,
            "lengthFrames" to chosen.length,
            "lengthSeconds" to chosen.length.toDouble() / Dsp.RATE,
            "blendFrames" to blend,
            "blendSeconds" to .3,
            "correlation" to chosen.correlation,
            "blendDipDb" to chosen.dipDb,
            "junctionFrames" to chosen.junction,
            "blendExitFrames" to chosen.blendExit,
            "junctionStep" to chosen.junctionStep,
            "wrapStep" to chosen.wrapStep,
            "blendExitStep" to chosen.blendExitStep,
            "interiorP99Step" to chosen.interiorP99,
            "junctionStepToP99Ratio" to chosen.junctionRatio,
            "wrapStepToP99Ratio" to chosen.wrapRatio,
            "blendExitStepToP99Ratio" to chosen.blendExitRatio,
            "minimumCorrelation" to MIN_CORRELATION,
            "maximumBlendDipDb" to MAX_DIP_DB,
            "maximumStepToP99Ratio" to MAX_STEP_RATIO,
            "candidateCount" to candidates.size,
            "search" to linkedMapOf(
                "initialStartFrames" to starts,
                "initialLengthRangeFrames" to listOf(frames(3.48), frames(3.72)),
                "broaderSearchUsed" to broaderSearchUsed,
                "broaderStartFrames" to attemptedBroaderStarts,
                "broaderRequestedLengthRangeFrames" to listOf(frames(2.5), frames(5.0)),
                "proxySampleStride" to SEARCH_STRIDE,
                "fullRateRefinementRadiusFrames" to REFINE_RADIUS,
                "method" to "Stride-only correlation proxy nominates one length per start, refined and checked at full source rate. No output resampling.",
            ),
            "qualityPassed" to chosen.accepted,
            "normalized" to false,
            "playbackPitchRatio" to 1,
        ))
    }

    /** Cancellable BORE correlation search, with deterministic shorter-length tie breaking. */
    private fun bestLength(x: FloatArray, start: Int, blend: Int, low: Int, high: Int,
                           checkCancelled: () -> Unit): Pair<Int, Double> {
        require(low > 0 && high >= low && start >= 0 && start + high + blend <= x.size)
        var head = 0.0
        var tail = 0.0
        for (i in 0 until blend) {
            if (i % 4096 == 0) checkCancelled()
            head += x[start + i].toDouble() * x[start + i]
            tail += x[start + low + i].toDouble() * x[start + low + i]
        }
        var best = Double.NEGATIVE_INFINITY
        var bestLength = low
        for (length in low..high) {
            // One look every 32 candidate lags bounds cancellation latency even in
            // the broad proxy search, without a callback in the inner dot product.
            if ((length - low) % 32 == 0) checkCancelled()
            if (length > low) {
                val gone = x[start + length - 1].toDouble()
                val came = x[start + length + blend - 1].toDouble()
                tail += came * came - gone * gone
            }
            var dot = 0.0
            val offset = start + length
            for (i in 0 until blend) dot += x[start + i].toDouble() * x[offset + i]
            val denominator = head * tail
            val r = if (denominator > 0.0) dot / sqrt(denominator) else 0.0
            if (r > best) { best = r; bestLength = length }
        }
        checkCancelled()
        return bestLength to best
    }

    /** BORE's 50 ms blend measure, keeping its ratio meaningful at tiny native velocities. */
    private fun blendDipDb(samples: FloatArray, blend: Int, checkCancelled: () -> Unit): Double {
        val window = frames(.05)
        val hop = window / 2
        val inside = ArrayList<Double>()
        val outside = ArrayList<Double>()
        for (start in 0..samples.size - window step hop) {
            checkCancelled()
            var energy = 0.0
            for (i in start until start + window) energy += samples[i].toDouble() * samples[i]
            val db = 10.0 * log10((energy / window).coerceAtLeast(1e-300))
            if (start + window <= blend) inside += db else if (start >= blend) outside += db
        }
        outside.sort()
        return (inside.minOrNull() ?: 0.0) - outside[outside.size / 2]
    }

    private fun frames(seconds: Double) = (seconds * Dsp.RATE).roundToInt()
}
