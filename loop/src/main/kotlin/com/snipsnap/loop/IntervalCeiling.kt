package com.snipsnap.loop

import com.snipsnap.audio.SongBus
import kotlin.math.roundToInt

/**
 * The grid's ceiling: one gain per interval, so six engaged tracks summed
 * never reach the clamp in `WavWriter` or the device.
 *
 * Before this, nothing sat between [Mixer] and the output. Six tracks of
 * the same kick bounced at a peak of 5.1 and `WavWriter` clipped them hard
 * (docs/plans/2026-10-30-song-making.md, phase 1). Playback and bounce
 * both hand their mixed interval through here, in that order, which is
 * what keeps [Bouncer]'s promise that a bounce is what was heard.
 *
 * Why per interval, not a limiter: the whole interval is mixed before a
 * sample of it is written, so its peak is known exactly and one number
 * brings it under [SongBus.PEAK] without bending a waveform. A limiter
 * would move inside the bar and colour the hits; this only ever turns
 * an interval down as a whole.
 *
 * Why a cut is immediate and a rise is ramped: the next interval is not
 * mixed yet while this one plays, so there is no lookahead across the
 * boundary. A gain that ramped *down* would let the first frames of a loud
 * interval through at the old gain, and a downbeat kick lives in exactly
 * those frames. So a cut lands at the boundary in one step, and the way
 * back up eases over [RAMP_MS] (any gain on the way up is under the
 * target, so the ceiling holds throughout). The cost is disclosed: on a
 * sound that runs straight through the boundary (a drone, a repeating
 * loop) a cut is a level step of `(old − new) × |sample|` at that frame.
 *
 * An interval under the ceiling, after one that was too, is not touched at
 * all, so a session that never gets loud bounces to the same bytes it did
 * before this class existed.
 *
 * Stateful by design (it carries the last interval's gain), so a playback
 * and a bounce each own one. Not thread-safe; it lives on the thread that
 * mixes.
 */
internal class IntervalCeiling {

    /** The gain the last interval ended on. 1 means untouched. */
    var gain: Float = 1f
        private set

    /** Bring one mixed, interleaved stereo interval under [SongBus.PEAK], in place. */
    fun apply(block: FloatArray, sampleRate: Int) {
        val peak = SongBus.peakOf(block)
        val target = if (peak > SongBus.PEAK) SongBus.PEAK / peak else 1f
        val from = gain
        gain = target
        if (from == 1f && target == 1f) return

        if (target <= from) {
            for (i in block.indices) block[i] *= target
            return
        }
        val frames = block.size / 2
        val ramp = (RAMP_MS * sampleRate / 1000f).roundToInt().coerceIn(1, maxOf(1, frames))
        for (f in 0 until frames) {
            val g = if (f < ramp) from + (target - from) * (f + 1) / ramp else target
            block[f * 2] *= g
            block[f * 2 + 1] *= g
        }
    }

    companion object {
        /** How long a rise back toward unity takes: short enough to keep the downbeat, long enough not to click. */
        const val RAMP_MS = 10f
    }
}
