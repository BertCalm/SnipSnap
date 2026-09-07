package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dice a snip into grains, fingerprint each with the classifier's own
 * feature extractor, and lay them out on a 2D timbre map — a scatter you
 * can scrub across to hear how a sample's texture drifts over time.
 *
 * The projection is a hand-rolled PCA: no linear-algebra library exists in
 * this repo, and the input is always a small 9x9 covariance matrix, so a
 * from-scratch power iteration is cheap and exact enough.
 *   1. Mean-center the grains x 9 feature matrix.
 *   2. Form the 9x9 covariance matrix (centered^T . centered / (n-1)).
 *   3. Power iteration finds the dominant eigenvector: repeatedly multiply
 *      a unit vector by the matrix and renormalize; it converges toward the
 *      eigenvector with the largest eigenvalue (Rayleigh quotient gives the
 *      eigenvalue itself once it has converged).
 *   4. Deflation (`C' = C - lambda * v * v^T`) removes that component so a
 *      second power iteration on `C'` finds the runner-up eigenvector —
 *      the standard trick for getting the top-K eigenpairs one at a time
 *      without a full eigendecomposition.
 *   5. Each grain's row is dotted with both eigenvectors to get its (x, y);
 *      both axes are then min-max normalized to 0..1 independently.
 */
object GrainField {
    /** ~93ms @ 44.1kHz — matches [FeatureExtractor]'s head window exactly. */
    const val GRAIN_FRAMES = 4_096
    const val HOP_FRAMES = 2_048

    /** Grains quieter than this peak are silence, not texture. */
    private const val SILENCE_PEAK = 1e-4f

    /** Minimum surviving (audible) grains before a map is even meaningful. */
    private const val MIN_GRAINS = 4

    private const val POWER_ITERATIONS = 50
    private const val DEGENERATE_EPSILON = 1e-9f

    data class Grain(val startFrame: Int, val x: Float, val y: Float)
    data class GrainMap(
        val grains: List<Grain>,
        val grainFrames: Int = GRAIN_FRAMES,
        val projector: Projector? = null,
    )

    /**
     * Captures the PCA basis [analyze] fit to one sample's grains so it can
     * be reapplied to NEW audio — projecting foreign material into an
     * existing timbre map instead of building a brand-new one.
     *
     * Must reproduce EXACTLY the normalization the original grains got:
     * mean-center, dot with each stored axis, then min-max normalize with
     * the SAME min/max the grains were fit against. A degenerate axis (the
     * grains got index-spread instead, since there is no meaningful
     * variance to normalize) always returns 0.5 for new audio — the
     * index-spread fallback has no equivalent for a single incoming vector.
     */
    class Projector internal constructor(
        internal val means: FloatArray,
        internal val axis1: FloatArray,
        internal val axis2: FloatArray,
        internal val min1: Float, internal val max1: Float,
        internal val min2: Float, internal val max2: Float,
        internal val degenerate1: Boolean, internal val degenerate2: Boolean,
    ) {
        /** Projects a [Similar.vector]-style 9-dim vector into the map's 0..1 space. */
        fun project(vector: FloatArray): Pair<Float, Float> {
            val centered = FloatArray(vector.size) { i -> vector[i] - means[i] }
            val x = axisCoordinate(dot(centered, axis1), min1, max1, degenerate1)
            val y = axisCoordinate(dot(centered, axis2), min2, max2, degenerate2)
            return x to y
        }

        private fun axisCoordinate(value: Float, minV: Float, maxV: Float, degenerate: Boolean): Float {
            if (degenerate) return 0.5f
            val range = maxV - minV
            val normalized = (value - minV) / range
            return normalized.coerceIn(0f, 1f)
        }
    }

    fun analyze(snip: Snip): GrainMap? {
        val mono = if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples

        // Step 1: slide the window, drop silent grains.
        val starts = ArrayList<Int>()
        var start = 0
        while (start + GRAIN_FRAMES <= mono.size) {
            if (peakOf(mono, start, GRAIN_FRAMES) >= SILENCE_PEAK) starts.add(start)
            start += HOP_FRAMES
        }

        // Step 2: too few audible grains to build a field.
        if (starts.size < MIN_GRAINS) return null

        // Step 3: per-grain fingerprint -> grains x 9 feature matrix.
        val matrix = Array(starts.size) { i ->
            val grainSamples = mono.copyOfRange(starts[i], starts[i] + GRAIN_FRAMES)
            val grainSnip = Snip(grainSamples, channels = 1, sampleRate = snip.sampleRate)
            Similar.vector(FeatureExtractor.extract(grainSnip))
        }

        // Step 4: PCA by hand.
        val means = columnMeans(matrix)
        val centered = centerColumns(matrix, means)
        val covariance = covarianceOf(centered)
        val (v1, lambda1) = powerIteration(covariance)
        val deflated = deflate(covariance, v1, lambda1)
        val (v2, _) = powerIteration(deflated)

        val pc1 = FloatArray(centered.size) { i -> dot(centered[i], v1) }
        val pc2 = FloatArray(centered.size) { i -> dot(centered[i], v2) }

        // Step 5: min-max normalize each axis, spreading degenerate axes by index.
        val axis1Range = axisRange(pc1)
        val axis2Range = axisRange(pc2)
        val xs = normalizeAxis(pc1, axis1Range)
        val ys = normalizeAxis(pc2, axis2Range)

        // Step 6: already built in ascending startFrame order; sort defensively.
        val grains = starts.indices
            .map { i -> Grain(starts[i], xs[i], ys[i]) }
            .sortedBy { it.startFrame }

        val projector = Projector(
            means = means,
            axis1 = v1,
            axis2 = v2,
            min1 = axis1Range.min, max1 = axis1Range.max,
            min2 = axis2Range.min, max2 = axis2Range.max,
            degenerate1 = axis1Range.degenerate,
            degenerate2 = axis2Range.degenerate,
        )

        return GrainMap(grains, projector = projector)
    }

    private fun peakOf(mono: FloatArray, start: Int, len: Int): Float {
        var peak = 0f
        for (i in start until start + len) {
            val s = mono[i]
            if (!s.isFinite()) continue
            val a = abs(s)
            if (a > peak) peak = a
        }
        return peak
    }

    private fun columnMeans(matrix: Array<FloatArray>): FloatArray {
        val n = matrix.size
        val dims = Similar.DIMENSIONS
        val means = FloatArray(dims)
        for (row in matrix) for (j in 0 until dims) means[j] += row[j]
        for (j in 0 until dims) means[j] /= n
        return means
    }

    private fun centerColumns(matrix: Array<FloatArray>, means: FloatArray): Array<FloatArray> {
        val dims = Similar.DIMENSIONS
        return Array(matrix.size) { i -> FloatArray(dims) { j -> matrix[i][j] - means[j] } }
    }

    private fun covarianceOf(centered: Array<FloatArray>): Array<FloatArray> {
        val n = centered.size
        val dims = Similar.DIMENSIONS
        val denom = (n - 1).coerceAtLeast(1).toFloat()
        return Array(dims) { j ->
            FloatArray(dims) { k ->
                var sum = 0f
                for (row in centered) sum += row[j] * row[k]
                sum / denom
            }
        }
    }

    /**
     * Dominant eigenvector/eigenvalue of a symmetric matrix via power
     * iteration: repeatedly apply the matrix to a unit vector and
     * renormalize. Converges to the top eigenvector; the eigenvalue falls
     * out as the Rayleigh quotient v^T . C . v once it has converged.
     */
    private fun powerIteration(c: Array<FloatArray>, iterations: Int = POWER_ITERATIONS): Pair<FloatArray, Float> {
        val dims = c.size
        var v = FloatArray(dims) { 1f / sqrt(dims.toFloat()) }
        repeat(iterations) {
            val next = FloatArray(dims) { i -> dot(c[i], v) }
            val norm = sqrt(next.sumOf { (it * it).toDouble() }).toFloat()
            if (norm > 1e-12f) {
                for (i in next.indices) next[i] = next[i] / norm
                v = next
            }
            // else: matrix collapses this vector to ~0 (degenerate axis) — keep v as-is.
        }
        val cv = FloatArray(dims) { i -> dot(c[i], v) }
        return v to dot(v, cv)
    }

    /** Removes [v]'s contribution from [c] so the next power iteration finds the runner-up. */
    private fun deflate(c: Array<FloatArray>, v: FloatArray, lambda: Float): Array<FloatArray> {
        val dims = c.size
        return Array(dims) { i -> FloatArray(dims) { j -> c[i][j] - lambda * v[i] * v[j] } }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** Min/max of an axis's raw (pre-normalization) values plus whether it's degenerate. */
    private class AxisRange(val min: Float, val max: Float, val degenerate: Boolean)

    private fun axisRange(values: FloatArray): AxisRange {
        val minV = values.min()
        val maxV = values.max()
        return AxisRange(minV, maxV, (maxV - minV) <= DEGENERATE_EPSILON)
    }

    /**
     * Min-max normalize to 0..1. A degenerate axis (near-identical grains,
     * range below [DEGENERATE_EPSILON]) is spread by grain index instead so
     * the field never collapses every point onto a single spot.
     */
    private fun normalizeAxis(values: FloatArray, range: AxisRange): FloatArray {
        val n = values.size
        return if (!range.degenerate) {
            val span = range.max - range.min
            FloatArray(n) { (values[it] - range.min) / span }
        } else {
            FloatArray(n) { if (n > 1) it / (n - 1).toFloat() else 0f }
        }
    }
}
