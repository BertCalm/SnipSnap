package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.TempoFit
import java.util.Random
import kotlin.math.floor

/** Corner of the tilt's one-pole low-pass. */
const val TILT_HZ = 3000f

/** The four spreads one dial position asks for: level fraction, pitch cents, start trim seconds, tilt depth. */
data class Spreads(val level: Float, val cents: Float, val trimSec: Float, val tilt: Float)

/**
 * Round-robin takes scaled by the ear-tested amount dial (C..H). The
 * per-take random draws never depend on the amount, so moving the dial
 * scales the very same takes rather than reshuffling them.
 */
object RobinVariation {

    val POINTS: List<Spreads> = listOf(
        Spreads(0.14f, 18f, 0.003f, 0.15f),
        Spreads(0.22f, 28f, 0.004f, 0.30f),
        Spreads(0.30f, 38f, 0.005f, 0.42f),
        Spreads(0.40f, 50f, 0.006f, 0.56f),
        Spreads(0.50f, 65f, 0.008f, 0.72f),
        Spreads(0.60f, 80f, 0.010f, 0.88f),
    )

    /** [amount] clamped to 0..1, linear between neighbouring points; exact points return the table entry. */
    fun spreadsAt(amount: Float): Spreads {
        val a = amount.coerceIn(0f, 1f)
        val pos = a * (POINTS.size - 1)
        val i = floor(pos).toInt().coerceAtMost(POINTS.size - 2)
        val f = pos - i
        if (f < 1e-5f) return POINTS[i]
        if (1f - f < 1e-5f) return POINTS[i + 1]
        val p = POINTS[i]
        val q = POINTS[i + 1]
        fun lerp(x: Float, y: Float) = x + (y - x) * f
        return Spreads(lerp(p.level, q.level), lerp(p.cents, q.cents), lerp(p.trimSec, q.trimSec), lerp(p.tilt, q.tilt))
    }

    /** [count] takes; take one is [original] itself, the rest seeded variants scaled by [amount]. */
    fun takes(original: Snip, count: Int, amount: Float, rng: Random): List<Snip> {
        val sp = spreadsAt(amount)
        return buildList {
            add(original)
            repeat(count - 1) { add(variant(original, sp, rng)) }
        }
    }

    /**
     * How far under the original's peak the trimmed take's own peak may fall: 6 dB, half the peak's
     * amplitude. Measured on the five ear-tested DrumSynth hits, H's longest trim (10 ms) costs them
     * 0.0 to 5.3 dB, so the cap never touches the trim the owner picked; a 20 ms click loses 57 dB to
     * the same trim, and is the hit it exists for. (A 1 dB floor would have halved F's trim on the
     * kick and cut the snare's and hat's to 2-3 ms.)
     */
    private val TRIM_PEAK_FLOOR = StrictMath.pow(10.0, -6.0 / 20.0).toFloat()

    /** Ceiling a louder take is scaled down to rather than clipped. */
    const val TAKE_CEILING = 0.999f

    /**
     * The front trim actually taken for a [drawn] one: never past the last frame still within 6 dB of
     * [original]'s peak ([TRIM_PEAK_FLOOR]), so a short hit (a click whose energy is all in its first milliseconds) keeps
     * its hit; a drum that decays slower than the trim keeps the whole ear-tested trim. Silence keeps
     * its start.
     */
    internal fun trimFor(original: Snip, drawn: Int): Int {
        if (drawn <= 0) return 0
        val ch = original.channels
        var peak = 0f
        for (x in original.samples) peak = maxOf(peak, kotlin.math.abs(x))
        if (peak == 0f) return 0
        val floor = peak * TRIM_PEAK_FLOOR
        var lastLoud = 0
        for (i in original.samples.indices.reversed()) {
            if (kotlin.math.abs(original.samples[i]) >= floor) {
                lastLoud = i / ch
                break
            }
        }
        return minOf(drawn, lastLoud)
    }

    private fun variant(original: Snip, sp: Spreads, rng: Random): Snip {
        // All four draws up front, so the stream never depends on this take's values.
        val u1 = rng.nextFloat()
        val u2 = rng.nextFloat()
        val u3 = rng.nextFloat()
        val u4 = rng.nextFloat()
        val trimFrames = trimFor(original, (u1 * sp.trimSec * original.sampleRate).toInt().coerceAtMost(original.frameCount / 4))
        val cents = (2f * u2 - 1f) * sp.cents
        val gain = 1f + (2f * u3 - 1f) * sp.level
        val t = (2f * u4 - 1f) * sp.tilt

        // The trim may shave leading silence or a hair of onset, never the hit itself ([trimFor]).
        var s = if (trimFrames > 0) {
            Snip(
                original.samples.copyOfRange(trimFrames * original.channels, original.samples.size),
                original.channels,
                original.sampleRate,
            )
        } else {
            original
        }
        // StrictMath: the same bytes on every CPU, so a re-deal on another device matches.
        val speed = StrictMath.pow(2.0, cents / 1200.0).toFloat()
        s = TempoFit.repitch(s, 1000f, 1000f * speed)

        val ch = s.channels
        val a = StrictMath.exp(-2.0 * Math.PI * TILT_HZ / s.sampleRate).toFloat()
        val lp = FloatArray(ch)
        val out = FloatArray(s.samples.size)
        var peak = 0f
        for (i in s.samples.indices) {
            val c = i % ch
            val x = s.samples[i]
            lp[c] = (1f - a) * x + a * lp[c]
            val y = if (t < 0f) x + (-t) * (lp[c] - x) else x + t * (x - lp[c])
            out[i] = y * gain
            peak = maxOf(peak, kotlin.math.abs(out[i]))
        }
        // A louder take of a hit already near full scale is scaled into headroom, the whole take at
        // once, rather than clipped: its shape survives, it varies less upward.
        if (peak > TAKE_CEILING) {
            val k = TAKE_CEILING / peak
            for (i in out.indices) out[i] = (out[i] * k).coerceIn(-TAKE_CEILING, TAKE_CEILING)
        }
        return Snip(out, ch, s.sampleRate)
    }
}
