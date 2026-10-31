package com.snipsnap.synth

import java.math.BigInteger

/*
 * Seed expansion and PCG64 XSL-RR are adapted from the MIT-licensed algorithms
 * in NumPy 2.3.5, read for this port on 2026-10-26:
 * https://github.com/numpy/numpy/blob/v2.3.5/numpy/random/bit_generator.pyx
 * https://github.com/numpy/numpy/blob/v2.3.5/numpy/random/src/pcg64/pcg64.h
 * https://github.com/numpy/numpy/blob/v2.3.5/numpy/random/src/pcg64/pcg64.c
 *
 * The MIT License (MIT)
 * Copyright (c) 2014, 2015 Melissa E. O'Neill
 * Copyright (c) 2015 Robert Kern
 * Copyright (c) 2019 NumPy Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 * The approved Python core's exact NumPy PCG64 stream for a single uint32 seed.
 *
 * This is deliberately separate from the house noise generator: changing the
 * reference's seed expansion would move already auditioned captured fragments.
 * BigInteger arithmetic is limited to the bounded event precompute, never the
 * audio loop. This supports no spawning, integer draws, or arbitrary entropy.
 */
internal class ObliteratePcg64(seed: Long) {
    private var state = BigInteger.ZERO
    private val increment: BigInteger

    init {
        require(seed in 0..0xffff_ffffL) { "seed must fit an unsigned 32-bit integer" }
        val words = seedWords(seed.toInt())
        val initialState = unsigned64(words[0], words[1]).shiftLeft(64)
            .or(unsigned64(words[2], words[3]))
        val initialSequence = unsigned64(words[4], words[5]).shiftLeft(64)
            .or(unsigned64(words[6], words[7]))
        increment = initialSequence.shiftLeft(1).or(BigInteger.ONE).and(MASK_128)
        step()
        state = state.add(initialState).and(MASK_128)
        step()
    }

    /** Same 53-bit conversion as Generator(PCG64(seed)).random(). */
    fun nextDouble(): Double {
        step()
        val mixed = state.shiftRight(64).xor(state).toLong()
        val rotated = java.lang.Long.rotateRight(mixed, state.shiftRight(122).toInt())
        return (rotated ushr 11).toDouble() * (1.0 / 9_007_199_254_740_992.0)
    }

    /** Keep multiply then add separate, as in NumPy's uniform distribution. */
    fun uniform(low: Double, high: Double): Double = low + (high - low) * nextDouble()

    private fun step() {
        state = state.multiply(MULTIPLIER).add(increment).and(MASK_128)
    }

    private companion object {
        val MASK_128: BigInteger = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        val MULTIPLIER = BigInteger("2360ed051fc65da44385df649fccf645", 16)

        fun unsigned64(low: Int, high: Int): BigInteger =
            BigInteger.valueOf(high.toLong() and 0xffff_ffffL).shiftLeft(32)
                .or(BigInteger.valueOf(low.toLong() and 0xffff_ffffL))

        /** Default four-word SeedSequence pool, one uint32 entropy word. */
        fun seedWords(seed: Int): IntArray {
            var hashConstant = 0x43b0d7e5
            fun hash(value: Int): Int {
                var result = value xor hashConstant
                hashConstant *= 0x931e8875.toInt()
                result *= hashConstant
                return result xor (result ushr 16)
            }
            fun mix(left: Int, right: Int): Int {
                val result = 0xca01f9dd.toInt() * left - 0x4973f715 * right
                return result xor (result ushr 16)
            }
            val pool = IntArray(4) { hash(if (it == 0) seed else 0) }
            for (source in pool.indices) {
                for (destination in pool.indices) {
                    if (source != destination) pool[destination] = mix(pool[destination], hash(pool[source]))
                }
            }
            hashConstant = 0x8b51f9dd.toInt()
            return IntArray(8) { index ->
                var value = pool[index % pool.size] xor hashConstant
                hashConstant *= 0x58f38ded
                value *= hashConstant
                value xor (value ushr 16)
            }
        }
    }
}
