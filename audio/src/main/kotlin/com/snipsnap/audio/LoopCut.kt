package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sustain loops on captured notes — the organ's whole-period loop cut,
 * generalized to real audio. Both MPC generations share one loop idiom:
 * **loop start → sample end**. So the job is to pick a loop start a whole
 * number of periods before a good ending point, trim the sample to end
 * exactly there, and — because real notes carry vibrato and noise the
 * synthetic organ never had — bake a short crossfade when the raw seam
 * isn't clean enough. Hold the pad and the note sings forever.
 *
 * Refusals are honest: no confident pitch, or not enough sustained
 * material, and the note simply plays unlooped — a pluck doesn't loop,
 * and pretending otherwise sounds worse than decay.
 */
object LoopCut {

    /** Seam scoring window, frames. */
    private const val SEAM_WINDOW = 512

    /** Raw seams worse than this get a crossfade baked in. */
    const val CLEAN_SEAM_ERROR = 0.01f

    /** The envelope floor under which the tail is fade, not sustain. */
    private const val SOLID_FLOOR = 0.05f

    /** Never loop into the attack: the earliest allowed loop start. */
    private const val ATTACK_GUARD_SEC = 0.08f

    /**
     * Max level change across the loop (~2.6 dB). A loop that decays more
     * than this pumps audibly on every pass — a real decay should just
     * play out instead.
     */
    private const val MAX_LEVEL_DRIFT = 1.35f

    data class Looped(
        /** Trimmed (and possibly crossfaded) — ends exactly on the loop boundary. */
        val snip: Snip,
        /** Where playback wraps back to. */
        val loopStartFrame: Long,
        /** Seam periodicity error actually achieved (diff energy / signal energy). */
        val seamError: Float,
        val crossfaded: Boolean,
    )

    /**
     * Find and cut a sustain loop, or null when the material honestly
     * doesn't have one ([minLoopSec] of loopable sustain after the attack).
     */
    fun sustainLoop(snip: Snip, minLoopSec: Float = 0.25f): Looped? {
        val rate = snip.sampleRate
        val est = Pitch.detect(snip) ?: return null
        if (est.confidence < 0.5f) return null
        val period = rate / est.hz.toDouble()

        val mono = if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples
        if (mono.isEmpty()) return null

        // The last solid frame: past it the note is fading out, and a loop
        // that includes the fade pumps on every pass.
        var peak = 0f
        for (v in mono) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak <= 0f) return null
        var solidEnd = mono.size - 1
        while (solidEnd > 0 && abs(mono[solidEnd]) < peak * SOLID_FLOOR) solidEnd--
        val loopEnd = min(solidEnd, mono.size - 1 - SEAM_WINDOW)

        val attackGuard = (ATTACK_GUARD_SEC * rate).toInt()
        val minLoopFrames = (minLoopSec * rate).toInt()
        val kMin = ceil(minLoopFrames / period).toInt().coerceAtLeast(2)
        val kMax = ((loopEnd - attackGuard) / period).toInt()
        if (kMax < kMin) return null

        // Search whole-period loop lengths for the cleanest raw seam:
        // continuity wants s[start + i] == s[end + i]. A candidate whose
        // level changes across the loop is rejected outright — looping a
        // decay pumps on every pass, which is worse than no loop.
        val endRms = localRms(mono, loopEnd)
        if (endRms <= 0f) return null
        var bestStart = -1
        var bestError = Float.MAX_VALUE
        for (k in kMin..min(kMax, kMin + 60)) {
            val start = (loopEnd - k * period).roundToInt()
            if (start < attackGuard) break
            val ratio = localRms(mono, start) / endRms
            if (ratio > MAX_LEVEL_DRIFT || ratio < 1f / MAX_LEVEL_DRIFT) continue
            val err = seamError(mono, start, loopEnd)
            if (err < bestError) {
                bestError = err
                bestStart = start
            }
        }
        if (bestStart < 0) return null

        val channels = snip.channels
        val out = FloatArray(loopEnd * channels)
        System.arraycopy(snip.samples, 0, out, 0, out.size)

        var crossfaded = false
        if (bestError > CLEAN_SEAM_ERROR) {
            // Bake the seam: blend the approach to the end into the approach
            // to the loop start, per channel, so the wrap lands mid-phrase
            // exactly where it will continue from.
            crossfaded = true
            val fade = min(min((period * 2).toInt(), (loopEnd - bestStart) / 4), (0.030 * rate).toInt())
            for (i in 0 until fade) {
                val t = (i + 1).toFloat() / fade
                for (ch in 0 until channels) {
                    val at = (loopEnd - fade + i) * channels + ch
                    val from = (bestStart - fade + i) * channels + ch
                    if (from >= 0) out[at] = out[at] * (1 - t) + snip.samples[from] * t
                }
            }
        }

        val result = Snip(out, channels, rate)
        val resultMono = if (channels == 1) result.samples else Cleanup.toMono(result).samples
        val achieved = wrapError(resultMono, bestStart)
        return Looped(result, bestStart.toLong(), achieved, crossfaded)
    }

    private fun localRms(mono: FloatArray, center: Int, window: Int = 1024): Float {
        val from = (center - window / 2).coerceAtLeast(0)
        val to = (center + window / 2).coerceAtMost(mono.size)
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += mono[i].toDouble() * mono[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    /** Diff energy over signal energy for s[start+i] vs s[end+i]. */
    private fun seamError(mono: FloatArray, start: Int, end: Int): Float {
        var diff = 0.0
        var energy = 0.0
        for (i in 0 until SEAM_WINDOW) {
            val a = mono[start + i]
            val b = mono[end + i]
            diff += (a - b).toDouble() * (a - b)
            energy += b.toDouble() * b
        }
        if (energy <= 0.0) return Float.MAX_VALUE
        return (diff / energy).toFloat()
    }

    /**
     * Continuity across the actual wrap of the trimmed buffer: the jump
     * from the last frame back to the loop start, normalized by how much
     * the signal typically moves per frame inside the loop.
     */
    private fun wrapError(mono: FloatArray, loopStart: Int): Float {
        val end = mono.size
        var typical = 0.0
        var n = 0
        for (i in loopStart until end - 1) {
            val d = mono[i + 1] - mono[i]
            typical += d.toDouble() * d
            n++
        }
        if (n == 0) return Float.MAX_VALUE
        val typicalRms = sqrt(typical / n)
        if (typicalRms <= 0.0) return Float.MAX_VALUE
        val jump = abs(mono[loopStart] - mono[end - 1]).toDouble()
        return (jump / (typicalRms * 8.0)).toFloat().coerceAtMost(Float.MAX_VALUE)
    }
}
