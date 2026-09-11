package com.snipsnap.shell

import com.snipsnap.audio.BreakFinder
import com.snipsnap.audio.Snip

/**
 * DIG on the deck (wave ZZ): the break inside a whole song, found and
 * handed back as the deck's own IN and OUT, so INSTANT KIT is the next
 * tap. The finding is `BreakFinder`'s; this only turns its best
 * candidate into frames at the tape's rate, or null when no break is
 * heard — a refusal the deck says in words, never an empty selection.
 */
object Dig {

    data class Found(val startFrame: Int, val endFrame: Int, val startSec: Float, val endSec: Float, val score: Float)

    fun best(snip: Snip): Found? {
        if (snip.frameCount == 0) return null
        val c = BreakFinder.find(snip).firstOrNull() ?: return null
        val start = (c.startSec * snip.sampleRate).toInt().coerceIn(0, snip.frameCount - 1)
        val end = (c.endSec * snip.sampleRate).toInt().coerceIn(start + 1, snip.frameCount)
        return Found(start, end, c.startSec, c.endSec, c.score)
    }

    /** `m:ss` for a toast. */
    fun stamp(sec: Float): String {
        val s = sec.toInt()
        return "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
    }
}
