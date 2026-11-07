package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How long a held zone's hit lasts before the zone's own loop takes over ([ArcoKeys], [BoreKeys]).
 *
 * The owner heard the first held keys on 2026-11-06 and said "either the initial sound is too long or there is too much
 * difference between the hit and the held portion". Measured, the start already matched the loop after its first 0.1 to
 * 0.6 s; the difference was the hit itself (CELLO's bow bite, ERHU's shorter one, FLUTE's chiff, SAX's dark reed onset).
 * On the A/B of four lengths the owner picked [SHORT] ("Short sound good in audition", 2026-11-06), for both engines.
 *
 * [shape] keeps the zone's own audio for [Hit.pure] seconds, then hands it to the zone's own loop over a quarter turn
 * that ends at [Hit.handover]. The loop is turned to line up with the note (the best match over the handover itself, so
 * the vibrato's phase lines up as well as the waveform), and it plays under the start's own level rise (the start's 20 ms level against the
 * loop's, rising only), so the swell into the note stays the note's. The handover's weights are BORE's blend law
 * ([BoreKeys.assembleTail], [Bore.assembleLoop]): cos t / k on the note and sin t / k on the loop, k = sqrt(1 + r sin 2t),
 * r the two waves' correlation where they were matched. So the level holds through the handover whether the waves line
 * up (r near 1) or not (a soft stroke still settling): a plain crossfade of two waves that do not line up dips about
 * 3 dB halfway. Where that law would take a frame over the zones' 0.99 ceiling, the handover runs at r = 1 instead. The loop marker moves to where the handover and the
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

    /** The zones' own ceiling ([ArcoKeys], [BoreKeys]): no frame of a shaped start passes it. */
    private const val PEAK_CEILING = 0.99f

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
        if (note.snip.channels != 1 || ls <= 0 || lf <= 0 || h >= ls) return note
        fun loop(i: Int) = x[ls + Math.floorMod(i, lf)]

        // Turn the loop to match the note over the whole handover: there the vibrato's phase has to line up as well as the
        // waveform's. A 30 ms match lined up the waveform alone, and at ERHU's high notes the two waves then drifted apart
        // across the handover and dipped (2.5 dB at D5's soft layer).
        val (turn, r) = match(x, a, h - a, ls, lf)
        fun held(t: Int) = loop(turn + t - a)

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

        // The handover at the waves' correlation; if that would take a frame over the ceiling, at r = 1, whose weights sum to
        // one, so no frame passes the louder of the two waves.
        for (law in listOf(r, 1.0)) {
            val out = FloatArray(end + lf)
            for (t in 0 until end) {
                out[t] = when {
                    t < a -> x[t]
                    t < h -> {
                        val turnAngle = 0.5 * PI * (t - a) / (h - a)
                        val k = sqrt(1.0 + law * sin(2.0 * turnAngle))
                        ((cos(turnAngle) / k) * x[t] + (sin(turnAngle) / k) * gain[t] * held(t)).toFloat()
                    }
                    else -> (gain[t] * held(t)).toFloat()
                }
            }
            for (t in end until end + lf) out[t] = held(t)
            if (law == 1.0 || out.all { kotlin.math.abs(it) <= PEAK_CEILING }) {
                return KeyNote(Snip(out, channels = 1, sampleRate = sr), loopStartFrame = end.toLong())
            }
        }
        error("unreachable: the r = 1 handover is always returned")
    }

    /**
     * The turn of the loop ([ls] .. [ls] + [lf] of [x], read round) that best matches [x]'s [n] frames from [from], and how
     * well they match there (normalised correlation, 0 to 1). Every turn at once, as a cross-correlation by FFT: the loop
     * extended by the window, against the window reversed; each turn's score is its correlation over the loop's own energy
     * under the window, so a loud stretch of the loop does not win by level.
     */
    private fun match(x: FloatArray, from: Int, n: Int, ls: Int, lf: Int): Pair<Int, Double> {
        val ext = lf + n
        var size = 1
        while (size < ext + n) size = size shl 1
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (j in 0 until ext) re[j] = x[ls + j % lf]
        val wRe = FloatArray(size)
        val wIm = FloatArray(size)
        var window = 0.0
        for (k in 0 until n) {
            val v = x[from + n - 1 - k]
            wRe[k] = v
            window += v.toDouble() * v
        }
        Fft.forward(re, im)
        Fft.forward(wRe, wIm)
        for (i in 0 until size) {
            val pr = re[i] * wRe[i] - im[i] * wIm[i]
            val pi = re[i] * wIm[i] + im[i] * wRe[i]
            re[i] = pr
            im[i] = pi
        }
        Fft.inverse(re, im)
        val energy = DoubleArray(ext + 1)
        for (j in 0 until ext) {
            val v = x[ls + j % lf].toDouble()
            energy[j + 1] = energy[j] + v * v
        }
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (d in 0 until lf) {
            val e = energy[d + n] - energy[d]
            if (e <= 0.0) continue
            val score = re[d + n - 1] / sqrt(e)
            if (score > bestScore) { bestScore = score; best = d }
        }
        val r = if (window > 0.0 && bestScore.isFinite()) (bestScore / sqrt(window)).coerceIn(0.0, 1.0) else 0.0
        return best to r
    }
}
