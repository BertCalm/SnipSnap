package com.snipsnap.synth

import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Planned radix-2 transforms for CAUSTIC model 2's repeated source convolutions.
 * This stays an instrument opt-in: the shared audio FFT and saved model-1 renders
 * retain their existing implementation. Swaps and Float butterflies run in the
 * house FFT's original order. Each stage's immutable Double twiddles are generated
 * by that same recurrence, rather than independently evaluated trigonometry, so
 * caching removes repeated work without changing the resulting Float bits.
 *
 * Only plans are shared between threads; audio arrays remain caller-owned. The
 * access-ordered cache is bounded by both entry count and conservative array/object
 * byte estimates. A one-million-sample plan fits within its 32 MiB budget.
 */
internal object CausticFft {
    const val MAX_SIZE = 1 shl 20
    const val MAX_CACHE_ENTRIES = 8
    const val MAX_CACHE_BYTES = 32L * 1024 * 1024

    private class Stage(val length: Int, val real: DoubleArray, val imaginary: DoubleArray)
    private class Plan(val swaps: IntArray, val swapCount: Int, val stages: List<Stage>, val bytes: Long)
    internal data class CacheStats(val sizes: List<Int>, val bytes: Long)

    private val plans = LinkedHashMap<Int, Plan>(MAX_CACHE_ENTRIES, .75f, true)
    private var cachedBytes = 0L

    internal fun clearCache() = synchronized(plans) {
        plans.clear()
        cachedBytes = 0L
    }

    internal fun cacheStats(): CacheStats = synchronized(plans) {
        CacheStats(plans.keys.toList(), cachedBytes)
    }

    private fun validate(real: FloatArray, imaginary: FloatArray) {
        require(real.size == imaginary.size) { "re and im must be the same length" }
        val size = real.size
        require(size > 0 && (size and (size - 1)) == 0 && size <= MAX_SIZE) {
            "CAUSTIC FFT length must be a power of two in 1..$MAX_SIZE: $size"
        }
    }

    private fun plan(size: Int): Plan = synchronized(plans) {
        plans[size]?.let { return@synchronized it }
        // There can be at most size/2 swaps. Recording the house permutation's
        // pairs keeps both its order and its behavior for aliased real/im arrays.
        val swaps = IntArray(size)
        var swapCount = 0
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                swaps[swapCount++] = i
                swaps[swapCount++] = j
            }
        }
        val stages = ArrayList<Stage>()
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle)
            val wIm = sin(angle)
            val real = DoubleArray(length / 2)
            val imaginary = DoubleArray(length / 2)
            var currentRe = 1.0
            var currentIm = 0.0
            for (k in real.indices) {
                real[k] = currentRe
                imaginary[k] = currentIm
                val nextRe = currentRe * wRe - currentIm * wIm
                currentIm = currentRe * wIm + currentIm * wRe
                currentRe = nextRe
            }
            stages.add(Stage(length, real, imaginary))
            length = length shl 1
        }
        // Payloads plus a deliberately generous allowance for arrays, stages,
        // lists, cache nodes and the plan object. No audio buffer enters this cache.
        val bytes = size * 4L + (size - 1L) * 16L + 256L + stages.size * 128L
        val result = Plan(swaps, swapCount, stages, bytes)
        check(bytes <= MAX_CACHE_BYTES)
        while (plans.isNotEmpty() &&
            (plans.size >= MAX_CACHE_ENTRIES || cachedBytes + bytes > MAX_CACHE_BYTES)) {
            val oldest = plans.entries.iterator()
            val entry = oldest.next()
            cachedBytes -= entry.value.bytes
            oldest.remove()
        }
        plans[size] = result
        cachedBytes += bytes
        result
    }

    fun forward(re: FloatArray, im: FloatArray) {
        validate(re, im)
        if (re.size == 1) return
        val p = plan(re.size)
        var pair = 0
        while (pair < p.swapCount) {
            val i = p.swaps[pair++]
            val j = p.swaps[pair++]
            val real = re[i]
            re[i] = re[j]
            re[j] = real
            val imaginary = im[i]
            im[i] = im[j]
            im[j] = imaginary
        }
        for (stage in p.stages) {
            val half = stage.length / 2
            var i = 0
            while (i < re.size) {
                for (k in 0 until half) {
                    val currentRe = stage.real[k]
                    val currentIm = stage.imaginary[k]
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = (re[i + k + half] * currentRe - im[i + k + half] * currentIm).toFloat()
                    val vIm = (re[i + k + half] * currentIm + im[i + k + half] * currentRe).toFloat()
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                }
                i += stage.length
            }
        }
    }

    fun inverse(re: FloatArray, im: FloatArray) {
        validate(re, im)
        val size = re.size
        for (i in 0 until size) im[i] = -im[i]
        forward(re, im)
        for (i in 0 until size) {
            re[i] /= size
            im[i] = -im[i] / size
        }
    }
}
