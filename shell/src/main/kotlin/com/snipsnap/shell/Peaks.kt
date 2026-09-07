package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import kotlin.math.max
import kotlin.math.min

/**
 * Min/max mip pyramid over a mono buffer, so waveform drawing costs the
 * same at every zoom level.
 *
 * Level 0 stores min/max per [baseBlock] frames; each level above halves
 * the resolution. [columns] answers "give me N drawable columns for frames
 * [start, end)" by aggregating whole blocks from the coarsest level that
 * still oversamples the request, then patching the ragged edges from the
 * raw samples — so the answer is *exact*, not approximate: the min/max of
 * every column equals a brute-force scan.
 *
 * Build is O(n); a query is O(columns + 2·baseBlock). The tape deck's
 * 60 s buffer at 44.1 kHz builds in a few ms and draws at any zoom for
 * free — this is the piece that makes drag-under-the-needle rendering
 * cheap enough for a 60 fps canvas on a phone.
 */
class PeaksPyramid private constructor(
    private val samples: FloatArray,
    private val baseBlock: Int,
    /** levels[l] is interleaved [min, max] pairs at block size baseBlock << l. */
    private val levels: List<FloatArray>,
) {

    val frameCount: Int get() = samples.size

    /** One drawable column: the exact min/max over its frame range. */
    data class Column(val min: Float, val max: Float)

    companion object {
        const val DEFAULT_BASE_BLOCK = 256

        fun build(mono: FloatArray, baseBlock: Int = DEFAULT_BASE_BLOCK): PeaksPyramid {
            require(baseBlock > 0) { "baseBlock must be positive: $baseBlock" }
            val levels = mutableListOf<FloatArray>()
            if (mono.isNotEmpty()) {
                var blocks = (mono.size + baseBlock - 1) / baseBlock
                val level0 = FloatArray(blocks * 2)
                for (b in 0 until blocks) {
                    var lo = Float.MAX_VALUE
                    var hi = -Float.MAX_VALUE
                    val from = b * baseBlock
                    val to = min(mono.size, from + baseBlock)
                    for (i in from until to) {
                        val s = mono[i]
                        if (s < lo) lo = s
                        if (s > hi) hi = s
                    }
                    level0[b * 2] = lo
                    level0[b * 2 + 1] = hi
                }
                levels += level0
                // Halve until one block covers everything.
                while (blocks > 1) {
                    val prev = levels.last()
                    blocks = (blocks + 1) / 2
                    val next = FloatArray(blocks * 2)
                    for (b in 0 until blocks) {
                        val a = b * 2
                        val lo0 = prev[a * 2]
                        val hi0 = prev[a * 2 + 1]
                        val hasPair = (a + 1) * 2 < prev.size
                        next[b * 2] = if (hasPair) min(lo0, prev[(a + 1) * 2]) else lo0
                        next[b * 2 + 1] = if (hasPair) max(hi0, prev[(a + 1) * 2 + 1]) else hi0
                    }
                    levels += next
                }
            }
            return PeaksPyramid(mono, baseBlock, levels)
        }

        /** Mixes a snip to mono first — the editor draws one lane. */
        fun fromSnip(snip: Snip, baseBlock: Int = DEFAULT_BASE_BLOCK): PeaksPyramid =
            build(if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples, baseBlock)
    }

    /**
     * [count] columns spanning frames `[startFrame, endFrame)`, edges
     * clamped to the buffer. A column whose range holds no frames (past
     * the clamp, or a sub-frame zoom) comes back as (0, 0) — silence,
     * which is what off-tape space looks like on a real deck.
     */
    fun columns(startFrame: Int, endFrame: Int, count: Int): List<Column> {
        require(count > 0) { "count must be positive: $count" }
        require(endFrame >= startFrame) { "endFrame $endFrame before startFrame $startFrame" }
        val span = (endFrame - startFrame).toDouble()
        return (0 until count).map { c ->
            val from = startFrame + (span * c / count).toInt()
            val to = startFrame + (span * (c + 1) / count).toInt()
            rangeMinMax(max(0, from), min(samples.size, to))
        }
    }

    /**
     * Same answer as [columns], written into [out] as interleaved
     * `[min0, max0, min1, max1, ...]` pairs instead of a boxed
     * `List<Column>` — for hot per-frame draw paths (TAPE's waveform
     * Canvas redraws on every position change during playback/scrub) where
     * a fresh `List` + lambda + boxed `Column` per call is real per-frame
     * GC churn. [out] must be at least `count * 2` floats; the caller owns
     * (and grows, if needed) that buffer.
     */
    fun columnsInto(startFrame: Int, endFrame: Int, count: Int, out: FloatArray) {
        require(count > 0) { "count must be positive: $count" }
        require(endFrame >= startFrame) { "endFrame $endFrame before startFrame $startFrame" }
        require(out.size >= count * 2) { "out must hold at least ${count * 2} floats, has ${out.size}" }
        val span = (endFrame - startFrame).toDouble()
        for (c in 0 until count) {
            val from = startFrame + (span * c / count).toInt()
            val to = startFrame + (span * (c + 1) / count).toInt()
            val col = rangeMinMax(max(0, from), min(samples.size, to))
            out[c * 2] = col.min
            out[c * 2 + 1] = col.max
        }
    }

    /** Exact min/max over `[from, to)`; (0,0) when the range is empty. */
    fun rangeMinMax(from: Int, to: Int): Column {
        if (to <= from || samples.isEmpty()) return Column(0f, 0f)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE

        var i = from
        // Ragged head up to the first block boundary, from raw samples.
        val firstBlock = (from + baseBlock - 1) / baseBlock
        val headEnd = min(to, firstBlock * baseBlock)
        while (i < headEnd) {
            val s = samples[i]
            if (s < lo) lo = s
            if (s > hi) hi = s
            i++
        }

        // Whole blocks via the pyramid, climbing to coarser levels while
        // the remaining run covers them.
        var block = i / baseBlock
        val lastFullBlock = to / baseBlock
        while (block < lastFullBlock) {
            // Find the coarsest level whose block at this position fits.
            var level = 0
            while (level + 1 < levels.size) {
                val step = 2 shl level // blocks-of-base per next-level block
                if (block % step == 0 && block + step <= lastFullBlock) level++ else break
            }
            val levelBlock = block shr level
            val arr = levels[level]
            val l = arr[levelBlock * 2]
            val h = arr[levelBlock * 2 + 1]
            if (l < lo) lo = l
            if (h > hi) hi = h
            block += 1 shl level
        }
        i = max(i, lastFullBlock * baseBlock)

        // Ragged tail.
        while (i < to) {
            val s = samples[i]
            if (s < lo) lo = s
            if (s > hi) hi = s
            i++
        }
        return Column(lo, hi)
    }
}
