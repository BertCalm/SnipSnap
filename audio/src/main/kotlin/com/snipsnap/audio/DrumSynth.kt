package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic drum sounds — test material and first-run demo content.
 *
 * Not high fidelity, but structurally honest: kicks are low with a pitch drop,
 * hats are high-passed noise, snares are tone plus noise, and decay times are
 * in the right ballpark. Those are the properties the classifier keys on, so a
 * classifier that can't tell these apart can't tell real ones apart either.
 */
object DrumSynth {

    const val RATE = 44_100

    private fun snip(samples: FloatArray) = Snip(samples, 1, RATE)

    private fun frames(seconds: Float) = (seconds * RATE).toInt()

    /** Low sine with a fast pitch drop — the defining shape of a kick. */
    fun kick(seconds: Float = 0.35f, decay: Double = 22.0): Snip {
        val n = frames(seconds)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val freq = 45.0 + 75.0 * exp(-45.0 * t) // 120 Hz down to 45 Hz
            out[i] = (0.9 * exp(-decay * t) * sin(2.0 * PI * freq * t)).toFloat()
        }
        return snip(out)
    }

    /**
     * Body plus noise.
     *
     * The noise is low-passed rather than white: a real snare's rattle rolls off
     * well below Nyquist, and unfiltered white noise would put the spectral
     * centre up where a cymbal lives, making the test material easier to tell
     * apart than the real thing.
     */
    fun snare(seconds: Float = 0.25f, decay: Double = 26.0, noiseMix: Float = 0.5f, seed: Int = 1): Snip {
        val n = frames(seconds)
        val out = FloatArray(n)
        val rng = Random(seed)
        var lowPassed = 0f
        val alpha = 0.35f // one-pole, corner around 5-6 kHz
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val env = exp(-decay * t)
            val white = rng.nextFloat() * 2f - 1f
            lowPassed += alpha * (white - lowPassed)
            val tone = sin(2.0 * PI * 190.0 * t).toFloat() * (1f - noiseMix)
            out[i] = (0.85 * env * (tone + lowPassed * noiseMix * 2.2f)).toFloat()
        }
        return snip(out)
    }

    /**
     * High-passed noise.
     *
     * Differencing successive noise samples is a crude one-pole high-pass, which
     * is enough to push the spectral centre where a cymbal's belongs.
     */
    fun hat(seconds: Float, decay: Double, seed: Int = 2): Snip {
        val n = frames(seconds)
        val out = FloatArray(n)
        val rng = Random(seed)
        var previous = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val white = rng.nextFloat() * 2f - 1f
            val highPassed = white - previous
            previous = white
            out[i] = (0.7 * exp(-decay * t) * highPassed).toFloat()
        }
        return snip(out)
    }

    fun closedHat(seed: Int = 2): Snip = hat(seconds = 0.08f, decay = 90.0, seed = seed)

    fun openHat(seed: Int = 3): Snip = hat(seconds = 0.5f, decay = 7.0, seed = seed)

    /** A few noise bursts in quick succession, then a short tail. */
    fun clap(seed: Int = 4): Snip {
        val n = frames(0.3f)
        val out = FloatArray(n)
        val rng = Random(seed)
        val burstOffsets = listOf(0, frames(0.010f), frames(0.020f), frames(0.032f))
        for (offset in burstOffsets) {
            val length = frames(0.012f)
            for (i in 0 until length) {
                val f = offset + i
                if (f >= n) break
                val env = exp(-160.0 * i / RATE)
                out[f] += (0.6 * env * (rng.nextFloat() * 2f - 1f)).toFloat()
            }
        }
        // Body after the bursts.
        val bodyStart = frames(0.032f)
        for (f in bodyStart until n) {
            val t = (f - bodyStart).toDouble() / RATE
            out[f] += (0.35 * exp(-22.0 * t) * (rng.nextFloat() * 2f - 1f)).toFloat()
        }
        return snip(out)
    }

    /** Mid-frequency tone, longer decay than a kick, little noise. */
    fun tom(seconds: Float = 0.4f, freq: Double = 150.0): Snip {
        val n = frames(seconds)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val f = freq * (1.0 + 0.15 * exp(-30.0 * t))
            out[i] = (0.85 * exp(-9.0 * t) * sin(2.0 * PI * f * t)).toFloat()
        }
        return snip(out)
    }

    /** Sustained pitched material — a bass note or a held chord. */
    fun tonal(seconds: Float = 1.2f, freq: Double = 110.0): Snip {
        val n = frames(seconds)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val env = if (t < 0.01) t / 0.01 else 1.0
            out[i] = (0.7 * env * (sin(2.0 * PI * freq * t) + 0.4 * sin(2.0 * PI * freq * 2 * t))).toFloat()
        }
        return snip(out)
    }

    /** Several seconds of mixed material — a captured bar rather than a hit. */
    fun loop(seconds: Float = 2.4f, seed: Int = 7): Snip {
        val n = frames(seconds)
        val out = FloatArray(n)
        val kick = kick().samples
        val snare = snare(seed = seed).samples
        val hat = closedHat(seed = seed).samples

        var position = 0
        var index = 0
        while (position < n) {
            val source = when (index % 4) {
                0 -> kick
                2 -> snare
                else -> hat
            }
            for (i in source.indices) {
                val f = position + i
                if (f >= n) break
                out[f] += source[i]
            }
            position += frames(0.25f)
            index++
        }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return snip(out)
    }
}
