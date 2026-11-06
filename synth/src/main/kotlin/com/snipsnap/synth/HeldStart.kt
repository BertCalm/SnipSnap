package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How long a held zone's hit lasts before the zone's own loop takes over ([ArcoKeys], [BoreKeys]).
 *
 * The owner heard the first held keys on 2026-11-06 and said "either the initial sound is too long or there is too much
 * difference between the hit and the held portion". Measured, the start already matched the loop after its first 0.1 to
 * 0.6 s; the difference was the hit itself (CELLO's bow bite, ERHU's shorter one, FLUTE's chiff, SAX's dark reed onset).
 * On the A/B of four lengths the owner picked [SHORT] ("Short sound good in audition", 2026-11-06), for both engines.
 *
 * [shape] keeps the zone's own audio for [Hit.pure] seconds, then hands it to the zone's own loop over a raised cosine
 * that ends at [Hit.handover]. The loop is turned to line up with the note (the best waveform match over a 30 ms window
 * where the handover is half done), and it plays under the start's own level rise (the start's 20 ms level against the
 * loop's, rising only), so the swell into the note stays the note's. The loop marker moves to where the handover and the
 * swell are both done, after [SETTLED_FRAMES] frames of pure loop, and the loop after it is the zone's loop turned to
 * start there: its wrap is two consecutive frames of the shipped loop, so the seam ([Keys.seamError]) is the shipped one
 * or better, and nothing after the marker changes level.
 */
object HeldStart {

    /** A hit length: the zone's own audio until [pure] seconds, the loop's tone fully in by [handover] seconds. */
    class Hit(val pure: Float, val handover: Float) {
        init {
            require(pure >= 0f && handover > pure) { "a hit wants 0 <= pure < handover, got $pure and $handover" }
        }
    }

    /** The owner's pick: the hit as itself for 80 ms, the held tone fully in by 300 ms. */
    val SHORT = Hit(pure = 0.08f, handover = 0.30f)

    /**
     * Frames of pure loop before the moved marker. The house seam check ([Keys.seamError]) reads the 256 frames before
     * the marker against the loop's last 256, so what plays into the marker is then exactly what plays into the wrap.
     */
    const val SETTLED_FRAMES = 300

    /** The waveform match's window, and where it sits: no earlier than this, where a tone has a period to match. */
    private const val ALIGN_WINDOW_SECONDS = 0.03f
    private const val ALIGN_EARLIEST_SECONDS = 0.15f

    /** The start's level is read over this half-width either side of each frame. */
    private const val LEVEL_HALF_SECONDS = 0.01f

    /** The start counts as arrived at the loop's level at this share of the loop's RMS. */
    private const val ARRIVED = 0.98

    /**
     * [note] with its start shaped by [hit], or [note] itself for a null [hit]. Also [note] itself when it has no loop, a
     * start too short for the handover, or a start that never reaches its loop's level before its own marker: the shape
     * only ever moves the marker earlier, never refuses a zone.
     */
    fun shape(note: KeyNote, hit: Hit?): KeyNote {
        if (hit == null) return note
        val x = note.snip.samples
        val sr = note.snip.sampleRate
        val ls = note.loopStartFrame.toInt()
        val lf = x.size - ls
        val a = (hit.pure * sr).roundToInt()
        val h = max(a + 1, (hit.handover * sr).roundToInt())
        val w = (ALIGN_WINDOW_SECONDS * sr).roundToInt()
        val t0 = max((a + h) / 2, (ALIGN_EARLIEST_SECONDS * sr).roundToInt())
        if (note.snip.channels != 1 || ls <= 0 || lf <= w || t0 + w >= ls || h >= ls) return note
        fun loop(i: Int) = x[ls + Math.floorMod(i, lf)]

        // Turn the loop to match the note where the handover is half done: every other offset, then the best's neighbours.
        fun match(d: Int): Double {
            var dot = 0.0
            var energy = 0.0
            for (k in 0 until w) {
                val l = loop(d + k)
                dot += x[t0 + k] * l
                energy += l * l
            }
            return dot / sqrt(energy + 1e-12)
        }
        var turn = 0
        var best = Double.NEGATIVE_INFINITY
        var d = 0
        while (d < lf) {
            val c = match(d)
            if (c > best) { best = c; turn = d }
            d += 2
        }
        for (dd in turn - 2..turn + 2) {
            val c = match(dd)
            if (c > best) { best = c; turn = dd }
        }
        fun held(t: Int) = loop(turn + t - t0)

        // The start's level against the loop's, rising only, capped at 1: the loop plays under the note's own swell.
        var loopEnergy = 0.0
        for (i in ls until x.size) loopEnergy += x[i].toDouble() * x[i]
        val loopRms = sqrt(loopEnergy / lf)
        if (loopRms <= 0.0) return note
        val half = (LEVEL_HALF_SECONDS * sr).roundToInt()
        val gain = DoubleArray(ls)
        var running = 0.0
        var acc = 0.0
        for (i in 0 until min(ls, half)) acc += x[i].toDouble() * x[i]
        for (t in 0 until ls) {
            if (t + half < x.size) acc += x[t + half].toDouble() * x[t + half]
            if (t - half - 1 >= 0) acc -= x[t - half - 1].toDouble() * x[t - half - 1]
            val level = sqrt(max(acc, 0.0) / (2 * half + 1))
            running = max(running, min(1.0, level / (ARRIVED * loopRms)))
            gain[t] = running
        }
        var end = h
        while (end < ls && gain[end] < 1.0) end++
        end += SETTLED_FRAMES
        if (end >= ls) return note

        val out = FloatArray(end + lf)
        for (t in 0 until end) {
            val wgt = when {
                t < a -> 0.0
                t < h -> 0.5 - 0.5 * cos(PI * (t - a) / (h - a))
                else -> 1.0
            }
            out[t] = ((1 - wgt) * x[t] + wgt * gain[t] * held(t)).toFloat()
        }
        for (t in end until end + lf) out[t] = held(t)
        return KeyNote(Snip(out, channels = 1, sampleRate = sr), loopStartFrame = end.toLong())
    }
}
