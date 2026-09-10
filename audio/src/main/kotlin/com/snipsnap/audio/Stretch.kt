package com.snipsnap.audio

/**
 * Extreme time-stretch, paulstretch-style: big Hann windows analyzed
 * along the source at 1/factor of the synthesis pace, every frame's
 * phases replaced with seeded random ones (conjugate symmetry kept, so
 * the frames stay real), overlap-added back together. Phase carries
 * *when*; magnitude carries *what*. Throw the when away and a 200 ms
 * hit becomes half a minute of evolving wash that still sounds like
 * itself — no grain artifacts, no chipmunk.
 *
 * [freeze] is the same move with the analysis position nailed down:
 * one instant of the source, held forever — the S-4's frozen texture.
 * The left and right channels draw different phases from the same
 * seed, so the output is a decorrelated stereo field; the peak is
 * normalized to the source's own, honest like the grain engine.
 */
object Stretch {

    /** Big on purpose: ~93 ms at 44.1 kHz — the paulstretch smear that reads as texture, not transient. */
    const val WINDOW = 4096

    const val MIN_FACTOR = 2f
    const val MAX_FACTOR = 100f
    const val MAX_OUT_SEC = 300f

    private const val HOP = WINDOW / 4
    private const val COLA = 1.5f

    private val HANN = FloatArray(WINDOW) { n ->
        (0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / WINDOW)).toFloat()
    }

    /** The source slowed by [factor], deterministic per [seed]. */
    fun stretch(source: Snip, factor: Float, seed: Long): Snip {
        require(factor in MIN_FACTOR..MAX_FACTOR) { "factor wants $MIN_FACTOR..$MAX_FACTOR, got $factor" }
        val mono = asMono(source)
        val outFrames = (mono.frameCount.toLong() * factor.toDouble()).toLong()
            .coerceAtMost((MAX_OUT_SEC * mono.sampleRate).toLong()).toInt()
        require(outFrames > WINDOW) { "the source is too short to stretch" }
        return resynth(mono, outFrames, seed) { outPos -> (outPos / factor).toInt() }
    }

    /** One instant of the source ([atSec], default its loudest moment) held for [seconds]. */
    fun freeze(source: Snip, seconds: Float, seed: Long, atSec: Float? = null): Snip {
        require(seconds > 0f && seconds <= MAX_OUT_SEC) { "seconds wants (0, $MAX_OUT_SEC], got $seconds" }
        val mono = asMono(source)
        val at = atSec ?: loudestSec(mono)
        require(at >= 0f && at <= mono.durationSeconds) {
            "--at %.2f is outside the source (%.2fs long)".format(java.util.Locale.ROOT, at, mono.durationSeconds)
        }
        val center = (at * mono.sampleRate).toInt()
        val start = (center - WINDOW / 2).coerceIn(0, (mono.frameCount - 1).coerceAtLeast(0))
        val outFrames = (seconds * mono.sampleRate).toInt()
        return resynth(mono, outFrames, seed) { start }
    }

    private fun asMono(source: Snip): Snip {
        require(source.frameCount > 0) { "the source is empty" }
        return if (source.channels == 1) source else Cleanup.toMono(source)
    }

    /** The source's loudest 50 ms, in seconds — where a freeze points by default. */
    fun loudestSec(source: Snip): Float {
        val mono = asMono(source)
        val win = (0.05f * mono.sampleRate).toInt().coerceAtLeast(1)
        var bestAt = 0
        var best = -1.0
        var at = 0
        while (at < mono.frameCount) {
            var acc = 0.0
            for (i in at until minOf(at + win, mono.frameCount)) {
                acc += mono.samples[i] * mono.samples[i].toDouble()
            }
            if (acc > best) {
                best = acc
                bestAt = at
            }
            at += win
        }
        return (bestAt + win / 2f) / mono.sampleRate
    }

    private inline fun resynth(mono: Snip, outFrames: Int, seed: Long, inStartFor: (Int) -> Int): Snip {
        val src = mono.samples
        val rng = java.util.Random(seed)
        val out = FloatArray(outFrames * 2)
        val re = FloatArray(WINDOW)
        val im = FloatArray(WINDOW)
        for (ch in 0 until 2) {
            val acc = FloatArray(outFrames + WINDOW)
            var pos = 0
            while (pos + WINDOW <= acc.size) {
                val inStart = inStartFor(pos).coerceIn(0, (src.size - 1).coerceAtLeast(0))
                for (n in 0 until WINDOW) {
                    val s = inStart + n
                    re[n] = (if (s < src.size) src[s] else 0f) * HANN[n]
                    im[n] = 0f
                }
                Fft.forward(re, im)
                // Keep every magnitude, replace every phase: DC and
                // Nyquist stay real, the mirror stays conjugate.
                re[0] = Math.abs(re[0])
                im[0] = 0f
                re[WINDOW / 2] = Math.abs(re[WINDOW / 2])
                im[WINDOW / 2] = 0f
                for (b in 1 until WINDOW / 2) {
                    val mag = Math.hypot(re[b].toDouble(), im[b].toDouble())
                    val phase = rng.nextDouble() * 2.0 * Math.PI
                    val pr = (mag * Math.cos(phase)).toFloat()
                    val pi = (mag * Math.sin(phase)).toFloat()
                    re[b] = pr
                    im[b] = pi
                    re[WINDOW - b] = pr
                    im[WINDOW - b] = -pi
                }
                Fft.inverse(re, im)
                for (n in 0 until WINDOW) {
                    acc[pos + n] += re[n] * HANN[n]
                }
                pos += HOP
            }
            for (f in 0 until outFrames) {
                out[f * 2 + ch] = acc[f] / COLA
            }
        }
        // Random phases add incoherently, so the raw level is arbitrary:
        // bring the peak home to the source's own.
        var srcPeak = 0f
        for (v in src) {
            val a = Math.abs(v)
            if (a > srcPeak) srcPeak = a
        }
        var outPeak = 0f
        for (v in out) {
            val a = Math.abs(v)
            if (a > outPeak) outPeak = a
        }
        if (outPeak > 0f && srcPeak > 0f) {
            val g = srcPeak / outPeak
            for (i in out.indices) out[i] *= g
        }
        return Snip(out, 2, mono.sampleRate)
    }
}
