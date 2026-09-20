package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * DRAW — the oscillator you draw. The Fairlight's Page 6 and the Prophet
 * VS did this forty years ago: a single cycle drawn on a screen, looped
 * at a pitch. SNAP already plays a 256-point line off a photo, and a
 * drawn line is the same 256 numbers from a different pen, so this
 * object is only the pen: strokes onto a table, starting shapes to draw
 * over, a smoother for a shaky finger. The sound is [Snap]'s.
 *
 * The same pen draws the volume [Shape] of a note — [ENVELOPE_SIZE]
 * points across the note's length, 0 silent to 255 full — which
 * [Snap.render] plays in place of its own decay when a pad carries one.
 *
 * Coordinates are 0..1 both ways, x left to right, y **up** (1 is the
 * top, 255); the screen flips its own pixels. Every function returns a
 * new array and leaves its input alone, so a screen's draft and its
 * committed line never share storage.
 */
object Draw {

    /** Points in a drawn volume shape. Fewer than the cycle's 256: a note's outline has no fine detail worth storing. */
    const val ENVELOPE_SIZE = 64

    /** The middle brightness: a blank line rests here, silent until drawn on. */
    const val REST = 128

    /** Starting shapes for the cycle, the four an oscillator switch has had since 1970 plus a narrow pulse. */
    enum class Wave { SINE, TRIANGLE, SAW, SQUARE, PULSE }

    /**
     * Starting shapes for the volume: a gated hold, the plain fall SNAP's
     * own decay makes, a pluck (fast fall to a quiet body), a swell (the
     * sound arrives before it strikes) and a bounce (two hits in one).
     */
    enum class Shape { HOLD, FALL, PLUCK, SWELL, BOUNCE }

    /** A flat line at [REST]: nothing to hear until something is drawn on it. */
    fun blank(): IntArray = IntArray(Snap.TABLE_SIZE) { REST }

    fun wave(wave: Wave): IntArray = IntArray(Snap.TABLE_SIZE) { i ->
        val x = i.toFloat() / Snap.TABLE_SIZE
        val v = when (wave) {
            Wave.SINE -> sin(2.0 * PI * x).toFloat()
            Wave.TRIANGLE -> if (x < 0.5f) 4f * x - 1f else 3f - 4f * x
            Wave.SAW -> 2f * x - 1f
            Wave.SQUARE -> if (x < 0.5f) 1f else -1f
            Wave.PULSE -> if (x < 0.25f) 1f else -1f
        }
        toByte(0.5f + 0.5f * v)
    }

    fun shape(shape: Shape): IntArray = IntArray(ENVELOPE_SIZE) { i ->
        val x = i.toFloat() / (ENVELOPE_SIZE - 1)
        val v = when (shape) {
            Shape.HOLD -> 1f
            // The exponential SNAP's own decay makes, -60 dB at the end.
            Shape.FALL -> Dsp.envAt(x, 1f)
            Shape.PLUCK -> if (x < 0.08f) 1f - x / 0.08f * 0.7f else 0.3f * Dsp.envAt((x - 0.08f) / 0.92f, 1f)
            Shape.SWELL -> if (x < 0.6f) (x / 0.6f) * (x / 0.6f) else 1f - (x - 0.6f) / 0.4f
            Shape.BOUNCE -> if (x < 0.5f) Dsp.envAt(x / 0.5f, 0.8f) else 0.6f * Dsp.envAt((x - 0.5f) / 0.5f, 0.8f)
        }
        toByte(v)
    }

    /**
     * A pen stroke from ([x0], [y0]) to ([x1], [y1]) laid onto [table]: every
     * point the segment crosses takes the segment's height there, points
     * either side are left as they were. Successive touch samples arrive
     * as short segments, so a fast finger that skips twenty points still
     * draws a line through all of them rather than a dotted one. Either
     * direction works; a stroke inside one point's width sets that point.
     * Coordinates outside 0..1 are clamped, so a finger that leaves the
     * panel keeps drawing along its edge.
     */
    fun stroke(table: IntArray, x0: Float, y0: Float, x1: Float, y1: Float): IntArray {
        val n = table.size
        val out = table.copyOf()
        val ax = x0.coerceIn(0f, 1f)
        val bx = x1.coerceIn(0f, 1f)
        val ay = y0.coerceIn(0f, 1f)
        val by = y1.coerceIn(0f, 1f)
        val ia = pointAt(ax, n)
        val ib = pointAt(bx, n)
        if (ia == ib) {
            out[ia] = toByte(by)
            return out
        }
        val lo = min(ia, ib)
        val hi = max(ia, ib)
        // Height along the segment by point index, so the line is straight
        // between the two samples whatever the pen's speed.
        val (yLo, yHi) = if (ia < ib) ay to by else by to ay
        for (i in lo..hi) {
            val t = (i - lo).toFloat() / (hi - lo)
            out[i] = toByte(yLo + (yHi - yLo) * t)
        }
        return out
    }

    /**
     * A three-point average, [passes] times: takes the finger's shake out
     * of a drawn line without moving where it goes. [circular] for a
     * cycle, whose last point neighbours its first; not for a shape,
     * whose ends are the note's start and finish and must stay put.
     */
    fun smooth(table: IntArray, circular: Boolean, passes: Int = 1): IntArray {
        var cur = table.copyOf()
        val n = cur.size
        if (n < 3) return cur
        repeat(passes.coerceAtLeast(0)) {
            val next = IntArray(n)
            for (i in 0 until n) {
                if (!circular && (i == 0 || i == n - 1)) {
                    // The note's start and finish are where they were drawn.
                    next[i] = cur[i]
                    continue
                }
                val l = if (i > 0) cur[i - 1] else cur[n - 1]
                val r = if (i < n - 1) cur[i + 1] else cur[0]
                next[i] = Math.round((l + 2f * cur[i] + r) / 4f)
            }
            cur = next
        }
        return cur
    }

    /** The largest step between neighbours, 0..255 — what [smooth] brings down; a screen can show it. */
    fun roughness(table: IntArray, circular: Boolean): Int {
        var worst = 0
        val n = table.size
        for (i in 0 until n) {
            val j = if (i + 1 < n) i + 1 else if (circular) 0 else break
            worst = max(worst, abs(table[j] - table[i]))
        }
        return worst
    }

    private fun pointAt(x: Float, n: Int): Int = min(n - 1, (x * n).toInt())

    private fun toByte(v: Float): Int = Math.round(v.coerceIn(0f, 1f) * 255f)
}
