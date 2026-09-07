package com.snipsnap.audio

import kotlin.math.ln
import kotlin.math.pow

/**
 * ATTACK KEPT, TAIL ETERNAL — a non-linear time map. The first [kneeSec]
 * of the hit passes untouched, bit for bit: the attack is the identity
 * of a drum and no stretch may blur it. Past the knee the tail slows
 * hyperbolically — speed 1 at the knee, `1 / (1 + τ/τ₀)` after it — so
 * the decay keeps decaying at first and then crawls toward a frozen
 * instant, [tailSec] long in all, with [Pghi] reinventing the phases
 * of the slowed magnitudes so a note stays a line and a wash stays a
 * wash. A short seam right after the knee crosses the real audio into
 * the resynthesis.
 *
 * TAIL is the knob, seconds; a tail already longer than it is refused
 * rather than sped up. Deterministic per seed.
 */
object Eternal {

    const val KNEE_DEFAULT_SEC = 0.03f
    const val KNEE_MIN_SEC = 0.005f
    const val KNEE_MAX_SEC = 0.5f

    const val TAIL_MIN_SEC = 0.5f
    const val TAIL_MAX_SEC = 30f
    const val TAIL_DEFAULT_SEC = 8f

    /** The crossfade from the real audio into the resynthesis, just after the knee. */
    const val SEAM_SEC = 0.005f

    private const val FRAME = Spectral.FRAME
    private const val HOP = Spectral.HOP
    private const val BINS = Spectral.BINS
    private const val MAKEUP_MAX = 4f

    /** AMOUNT 0..1 → the tail in seconds, exponential: the ear hears ratios of length. */
    fun tailFor(amount: Float): Float {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        return TAIL_MIN_SEC * (TAIL_MAX_SEC / TAIL_MIN_SEC).pow(amount)
    }

    /** Why [snip] would be refused for [tailSec] and [kneeSec], in words, or null. */
    fun refusal(snip: Snip, tailSec: Float, kneeSec: Float = KNEE_DEFAULT_SEC): String? {
        val knee = (kneeSec * snip.sampleRate).toInt()
        val tail = snip.frameCount - knee
        if (tail < HOP) return "the hit ends inside the knee - nothing after %.0f ms to slow".format(java.util.Locale.ROOT, kneeSec * 1000f)
        val have = tail.toFloat() / snip.sampleRate
        if (have > tailSec) return "the tail is already %.2f s - ask for at least that".format(java.util.Locale.ROOT, have)
        return null
    }

    /**
     * [snip] with its first [kneeSec] kept and its tail slowed into
     * [tailSec] seconds; the result is `knee + tail` long.
     */
    fun stretch(snip: Snip, tailSec: Float = TAIL_DEFAULT_SEC, kneeSec: Float = KNEE_DEFAULT_SEC, seed: Long = 0): Snip {
        require(tailSec in TAIL_MIN_SEC..TAIL_MAX_SEC) { "tail wants $TAIL_MIN_SEC..$TAIL_MAX_SEC s, got $tailSec" }
        require(kneeSec in KNEE_MIN_SEC..KNEE_MAX_SEC) { "knee wants $KNEE_MIN_SEC..$KNEE_MAX_SEC s, got $kneeSec" }
        require(snip.frameCount > 0) { "the source is empty" }
        refusal(snip, tailSec, kneeSec)?.let { throw IllegalArgumentException(it) }
        val rate = snip.sampleRate
        val channels = snip.channels
        val knee = (kneeSec * rate).toInt()
        val srcTail = snip.frameCount - knee
        val outTail = (tailSec * rate).toInt()

        // The map: source frames consumed by output time τ (frames) is
        // τ₀·ln(1 + τ/τ₀), τ₀ chosen so the whole tail is consumed exactly
        // at the end - speed 1 at the knee, ever slower after.
        val tau0 = solveTau0(srcTail.toDouble(), outTail.toDouble())
        fun sourceAt(outFrame: Double): Double = (tau0 * ln(1.0 + outFrame / tau0)).coerceIn(0.0, srcTail.toDouble())

        val out = FloatArray((knee + outTail) * channels)
        val seam = (SEAM_SEC * rate).toInt().coerceAtMost(srcTail)
        for (ch in 0 until channels) {
            // The tail's own spectrogram, then resampled through the map.
            val tailMono = Snip(FloatArray(srcTail) { i -> snip.samples[(knee + i) * channels + ch] }, 1, rate)
            val mags = ArrayList<FloatArray>()
            Spectral.forEachFrame(tailMono) { _, _, m -> mags.add(m.copyOf()) }
            val outCount = (outTail + 2 * FRAME) / HOP + 1
            val slowed = ArrayList<FloatArray>(outCount)
            for (j in 0 until outCount) {
                val x = sourceAt(j.toDouble() * HOP) / HOP
                val i0 = x.toInt().coerceAtMost(mags.size - 1)
                val i1 = (i0 + 1).coerceAtMost(mags.size - 1)
                val frac = (x - i0).toFloat()
                slowed.add(FloatArray(BINS) { b -> mags[i0][b] + (mags[i1][b] - mags[i0][b]) * frac })
            }
            val resynth = Pghi.invert(slowed, outTail, rate, seed + ch)
            // The resynthesis at the real tail's own peak: character, not loudness.
            val inPeak = tailMono.peak()
            val outPeak = resynth.peak()
            val makeup = if (inPeak > 0f && outPeak > 0f) (inPeak / outPeak).coerceAtMost(MAKEUP_MAX) else 1f
            // The head, bit for bit.
            for (i in 0 until knee) out[i * channels + ch] = snip.samples[i * channels + ch]
            // The seam, then the slowed tail.
            for (i in 0 until outTail) {
                val slow = resynth.samples[i] * makeup
                val v = if (i < seam) {
                    val t = (i + 1).toFloat() / (seam + 1)
                    snip.samples[(knee + i) * channels + ch] * (1f - t) + slow * t
                } else {
                    slow
                }
                out[(knee + i) * channels + ch] = v
            }
        }
        return Snip(out, channels, rate)
    }

    /** τ₀ with τ₀·ln(1 + T/τ₀) = L, by bisection: the map that lands exactly on the source's end. */
    private fun solveTau0(sourceFrames: Double, outFrames: Double): Double {
        if (outFrames <= sourceFrames) return Double.MAX_VALUE / 4
        var lo = 1e-6
        var hi = outFrames * 1e6
        repeat(200) {
            val mid = Math.sqrt(lo * hi)
            val consumed = mid * ln(1.0 + outFrames / mid)
            if (consumed < sourceFrames) lo = mid else hi = mid
        }
        return Math.sqrt(lo * hi)
    }
}
