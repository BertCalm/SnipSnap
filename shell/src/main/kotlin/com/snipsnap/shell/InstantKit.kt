package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import java.io.File

/**
 * INSTANT KIT (F2.2) — the one tap: a fresh capture chopped with the
 * defaults and sent to the grid without a review. Exactly what CHOP does
 * when nothing on it is touched — [ChopReviewModel.chop] by hits, then
 * [ChopReviewModel.sendToGrid] with the classifier's own placements and
 * chokes, then [KitBuilderModel.fromChop] — so the kit is the one the
 * review would have made, and CHOP can still open it later to argue
 * with the chips. Honest refusal when the capture holds no hit.
 */
object InstantKit {

    data class Result(
        val kit: Kit,
        val kitDir: File,
        val sliceCount: Int,
        val chokeSet: Boolean,
    )

    /** The part of a tape a COMMIT or a whole deck names, mono, as CHOP would take it. */
    fun slice(source: Snip, range: IntRange): Snip {
        val mono = if (source.channels == 1) source else Cleanup.toMono(source)
        val start = range.first.coerceIn(0, mono.frameCount)
        val end = (range.last + 1).coerceIn(start, mono.frameCount)
        require(end > start) { "nothing selected to chop" }
        return Snip(mono.samples.copyOfRange(start, end), 1, mono.sampleRate)
    }

    /** Chop [source] with the defaults and land it as the kit [name] in [kitDir]. */
    fun build(source: Snip, name: String, kitDir: File): Result {
        require(source.frameCount > 0) { "nothing to chop" }
        val review = ChopReviewModel.chop(source)
        require(review.sliceCount > 0) { "no hits found to chop - trim closer to the sound, or use CHOP's grid" }
        val send = review.sendToGrid()
        val builder = KitBuilderModel.fromChop(name, send.arranged, kitDir)
        return Result(builder.kit, kitDir, send.sliceCount, send.chokeSet)
    }
}
