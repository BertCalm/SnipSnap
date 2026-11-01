package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ferry's energy-normalized complex network, da/dt = (i H - Gamma) a.
 * H is reciprocal and real symmetric; Gamma is positive amplitude damping.
 * This is deliberately separate from Modes.Bank's displacement/velocity springs.
 */
internal object FerryNetwork {
    const val SIZE = 22
    const val ROOT = 21
    const val FUNDAMENTAL_CAP_CENTS = 5.0
    private val fundamental = setOf(0, 4, 8)

    data class Model(
        val h: Array<DoubleArray>,
        val gamma: DoubleArray,
        val pickup: DoubleArray,
        val capScale: Double = 1.0,
        val rootHz: Double = 0.0,
    ) {
        val size get() = gamma.size
    }

    /** MATCH separates upper B modes while the four fundamentals retain a shared root. */
    fun build(midi: Int, pace: Double, support: Boolean = true, mistuneCents: Double = 0.0): Model {
        require(mistuneCents.isFinite() && mistuneCents in 0.0..Ferry.MAX_MISTUNE_CENTS) {
            "FERRY mistuning must be finite and in 0..${Ferry.MAX_MISTUNE_CENTS} cents"
        }
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val frequency = DoubleArray(SIZE)
        for (k in 0..3) {
            frequency[k] = hz * (k + 1)
            // Keep the played note anchored. Detuning the receiving fundamental
            // made it pull the low-note tail sharp after the struck wood faded.
            frequency[k + 4] = hz * (k + 1) * 2.0.pow(if (k == 0) 0.0 else mistuneCents / 1200.0)
            frequency[k + 8] = hz * (k + 1)
        }
        for (j in 0..8) frequency[12 + j] = 2 * hz * 2.0.pow(.35 * (j / 4.0 - 1.0))
        frequency[ROOT] = hz
        val original = Array(SIZE) { i -> DoubleArray(SIZE) { j -> if (i == j) 2 * PI * frequency[i] else 0.0 } }
        fun link(i: Int, j: Int, gain: Double) { original[i][j] = gain; original[j][i] = gain }
        for (k in 0..3) {
            link(k, k + 4, (k + 2.0) * pace)
            if (support) { link(k, k + 8, .7); link(k + 4, k + 8, .55 * pace) }
        }
        if (support) for (j in 0..8) for (k in 0..3) link(k + 8, j + 12, .28)
        fun scaled(alpha: Double) = Array(SIZE) { i -> DoubleArray(SIZE) { j ->
            original[i][j] * if (i != j && (i in fundamental || j in fundamental)) alpha else 1.0
        } }
        fun withinCap(h: Array<DoubleArray>): Boolean {
            val values = Modes.symmetricEigen(h).first.sorted()
            return (0..3).all { abs(1200.0 * ln(values[it] / (2 * PI * hz)) / ln(2.0)) <= FUNDAMENTAL_CAP_CENTS }
        }
        // The full H includes support-to-bank loading. Scaling a direct A/B edge
        // alone would miss this path and could fail the low-note splitting bound.
        var alpha = 1.0
        if (!withinCap(original)) {
            var lo = 0.0
            var hi = 1.0
            repeat(40) {
                val mid = (lo + hi) * .5
                if (withinCap(scaled(mid))) lo = mid else hi = mid
            }
            alpha = lo
        }
        return Model(scaled(alpha), doubleArrayOf(
            .5, .8, 1.2, 1.7, .18, .25, .32, .4, .5, .6, .7, .8,
            .12, .14, .16, .18, .20, .22, .24, .26, .28, .35,
        ), doubleArrayOf(
            1.0, .42, .2, .1, .28, .65, .95, 1.2, .07, .07, .07, .07,
            .22, .22, .22, .22, .22, .22, .22, .22, .22, .8,
        ), alpha, hz)
    }

    /** Dense complex storage is confined to compile time and one update per block. */
    internal class Matrix(val size: Int, val re: DoubleArray, val im: DoubleArray) {
        fun apply(reIn: DoubleArray, imIn: DoubleArray, reOut: DoubleArray, imOut: DoubleArray) {
            for (i in 0 until size) {
                var r = 0.0; var v = 0.0
                var ij = i * size
                for (j in 0 until size) {
                    r += re[ij] * reIn[j] - im[ij] * imIn[j]
                    v += im[ij] * reIn[j] + re[ij] * imIn[j]
                    ij++
                }
                reOut[i] = r; imOut[i] = v
            }
        }

        fun squared(): Matrix {
            val r = DoubleArray(re.size); val v = DoubleArray(im.size)
            for (i in 0 until size) for (k in 0 until size) {
                val ik = i * size + k
                for (j in 0 until size) {
                    val ij = i * size + j; val kj = k * size + j
                    r[ij] += re[ik] * re[kj] - im[ik] * im[kj]
                    v[ij] += re[ik] * im[kj] + im[ik] * re[kj]
                }
            }
            return Matrix(size, r, v)
        }
    }

    /**
     * U = D exp(i H dt) D is contractive: D = exp(-Gamma dt/2).
     * An accurate symmetric eigensolve builds the unitary middle factor once.
     * Different material damping remains in physical coordinates, never replaced
     * by independent decay constants on H's eigenvectors.
     */
    class Compiled(val model: Model, val rate: Int, val block: Int = 64) {
        val size = model.size
        val step: Matrix
        private val advance: Matrix
        private val readRe = DoubleArray(block * size)
        private val readIm = DoubleArray(block * size)
        val eigenvalues: DoubleArray
        val eigenResidual: Double
        val orthogonalityError: Double

        init {
            require(rate > 0 && block > 0 && block.countOneBits() == 1)
            require(model.h.size == size && model.pickup.size == size)
            require(model.gamma.all { it.isFinite() && it >= 0.0 }) { "FERRY damping must be finite and nonnegative" }
            val (values, q) = Modes.symmetricEigen(model.h)
            require(values.all { it.isFinite() && it > 0.0 && it < PI * rate }) { "FERRY modes outside the internal band" }
            eigenvalues = values.sortedArray()
            var residual = 0.0; var orthogonal = 0.0; var scale = 0.0
            for (i in 0 until size) for (j in 0 until size) {
                require(model.h[i].size == size && model.h[i][j].isFinite())
                var hq = 0.0; var qq = 0.0
                for (k in 0 until size) { hq += model.h[i][k] * q[k][j]; qq += q[k][i] * q[k][j] }
                residual = max(residual, abs(hq - values[j] * q[i][j]))
                orthogonal = max(orthogonal, abs(qq - if (i == j) 1.0 else 0.0))
                scale = max(scale, abs(model.h[i][j]))
            }
            eigenResidual = residual / scale.coerceAtLeast(1.0)
            orthogonalityError = orthogonal
            check(eigenResidual < 1e-11 && orthogonalityError < 1e-11) { "FERRY eigensolve failed validation" }
            val cr = DoubleArray(size) { cos(values[it] / rate) }
            val ci = DoubleArray(size) { sin(values[it] / rate) }
            val damping = DoubleArray(size) { exp(-model.gamma[it] / (2.0 * rate)) }
            val r = DoubleArray(size * size); val v = DoubleArray(size * size)
            for (i in 0 until size) for (j in 0 until size) {
                var rr = 0.0; var vv = 0.0
                for (k in 0 until size) { val w = q[i][k] * q[j][k]; rr += w * cr[k]; vv += w * ci[k] }
                val d = damping[i] * damping[j]
                r[i * size + j] = rr * d; v[i * size + j] = vv * d
            }
            step = Matrix(size, r, v)
            for (j in 0 until size) readRe[j] = model.pickup[j]
            // r_k = pickup^T U^k. Dotting r_k with a block's actual state
            // exactly reproduces sample stepping; this is not a gain envelope.
            for (frame in 1 until block) for (j in 0 until size) {
                var rr = 0.0; var vv = 0.0
                for (i in 0 until size) {
                    val prev = (frame - 1) * size + i; val ij = i * size + j
                    rr += readRe[prev] * r[ij] - readIm[prev] * v[ij]
                    vv += readRe[prev] * v[ij] + readIm[prev] * r[ij]
                }
                readRe[frame * size + j] = rr; readIm[frame * size + j] = vv
            }
            var power = step
            var count = 1
            while (count < block) { power = power.squared(); count *= 2 }
            advance = power
        }

        fun render(initial: DoubleArray, frames: Int): FloatArray {
            require(initial.size == size && initial.all { it.isFinite() } && frames >= 0)
            val output = FloatArray(frames)
            var re = initial.copyOf(); var im = DoubleArray(size)
            var nextRe = DoubleArray(size); var nextIm = DoubleArray(size)
            var offset = 0
            while (offset < frames) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("FERRY render cancelled")
                for (frame in 0 until minOf(block, frames - offset)) {
                    var value = 0.0
                    val base = frame * size
                    for (i in 0 until size) value += readRe[base + i] * re[i] - readIm[base + i] * im[i]
                    check(value.isFinite()) { "FERRY nonfinite state" }
                    output[offset + frame] = value.toFloat()
                }
                advance.apply(re, im, nextRe, nextIm)
                val oldRe = re; re = nextRe; nextRe = oldRe
                val oldIm = im; im = nextIm; nextIm = oldIm
                offset += block
            }
            return output
        }

        /** Diagnostic replay of actual states, independent of output leveling. */
        fun state(initial: DoubleArray, frames: Int): Pair<DoubleArray, DoubleArray> {
            require(initial.size == size && frames >= 0)
            var re = initial.copyOf(); var im = DoubleArray(size)
            var nr = DoubleArray(size); var ni = DoubleArray(size)
            repeat(frames / block) {
                advance.apply(re, im, nr, ni)
                val tr = re; re = nr; nr = tr; val ti = im; im = ni; ni = ti
            }
            repeat(frames % block) {
                step.apply(re, im, nr, ni)
                val tr = re; re = nr; nr = tr; val ti = im; im = ni; ni = ti
            }
            return re to im
        }
    }
}
