package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * TAPE SPLICE: literal cut-and-tape-together, not a DSP blend. [join]
 * takes the head of one take up to one chosen frame and the tail of
 * another take from that same frame, and butts them together — a hard
 * cut by default. Only when the raw seam would actually click does it
 * bake in a few milliseconds of physical tape overlap (a short
 * crossfade, framed as the splice tape itself, never as the blend this
 * app avoids everywhere else) — the same "score the seam, fade only when
 * it isn't clean" precedent [LoopCut.sustainLoop] set for a wrapped loop
 * point, applied here to a concatenation instead of a wrap.
 *
 * Not [com.snipsnap.audio.Snip]'s only "splice": `:shell`'s `Mutate.Mode.SPLICE`
 * is a different, older move — it always crossfades one pad's own
 * transient into an unrelated crate parent's body. This one never
 * crossfades unless the raw cut demands it, and it only ever joins two
 * takes of the *same* pad's own history.
 */
object TapeSplice {

    /** Crossfade ceiling — a splice overlap, not a blend. */
    private const val MAX_FADE_MS = 12f

    /** Window either side of the seam used to judge "typical" per-frame movement. */
    private const val LOCAL_WINDOW = 64

    /** A raw jump beyond this many multiples of the locally-typical step clicks, and gets faded. */
    const val CLEAN_JUMP = 3f

    data class Spliced(
        val snip: Snip,
        /** Frame in [snip] where the join actually lands — less than the requested head length when a crossfade shortened it. */
        val joinFrame: Int,
        /** The raw seam's jump, normalized by locally-typical sample-to-sample movement; 0 is silent-clean, higher clicks harder. */
        val jumpError: Float,
        val crossfaded: Boolean,
    )

    /**
     * [head]'s first [headFrames] frames, joined to [tail]'s frames from
     * [tailFrames] onward. [head] and [tail] must share sample rate and
     * channel count — two takes of the same pad always do; a caller
     * handing this two unrelated files is refused rather than silently
     * resampled or channel-folded, matching every other rewrite door's
     * contract in this codebase.
     */
    fun join(head: Snip, headFrames: Int, tail: Snip, tailFrames: Int): Spliced {
        require(head.sampleRate == tail.sampleRate) {
            "head is ${head.sampleRate} Hz, tail is ${tail.sampleRate} Hz - splice refuses to silently resample"
        }
        require(head.channels == tail.channels) {
            "head is ${head.channels}ch, tail is ${tail.channels}ch - splice refuses to silently fold channels"
        }
        require(headFrames in 0..head.frameCount) {
            "headFrames $headFrames out of range for a ${head.frameCount}-frame head"
        }
        require(tailFrames in 0..tail.frameCount) {
            "tailFrames $tailFrames out of range for a ${tail.frameCount}-frame tail"
        }
        val ch = head.channels
        val rate = head.sampleRate
        val tailKept = tail.frameCount - tailFrames

        val jump = seamJump(head, headFrames, tail, tailFrames)
        val maxFadeFrames = (MAX_FADE_MS / 1000f * rate).toInt().coerceAtLeast(1)
        val fadeFrames = min(maxFadeFrames, min(headFrames, tailKept))
        val crossfade = jump > CLEAN_JUMP && fadeFrames > 0

        if (!crossfade) {
            val out = FloatArray((headFrames + tailKept) * ch)
            System.arraycopy(head.samples, 0, out, 0, headFrames * ch)
            System.arraycopy(tail.samples, tailFrames * ch, out, headFrames * ch, tailKept * ch)
            return Spliced(Snip(out, ch, rate), headFrames, jump, false)
        }

        // A short physical overlap: the last `fadeFrames` of the head bleed
        // into the first `fadeFrames` of the tail — the join shortens by
        // the overlap, the same way two pieces of tape actually laid over
        // each other would, mirroring LoopCut's own seam bake.
        val outFrames = headFrames + tailKept - fadeFrames
        val out = FloatArray(outFrames * ch)
        System.arraycopy(head.samples, 0, out, 0, (headFrames - fadeFrames) * ch)
        for (i in 0 until fadeFrames) {
            val t = (i + 1).toFloat() / fadeFrames
            for (c in 0 until ch) {
                val a = head.samples[(headFrames - fadeFrames + i) * ch + c]
                val b = tail.samples[(tailFrames + i) * ch + c]
                out[(headFrames - fadeFrames + i) * ch + c] = a * (1 - t) + b * t
            }
        }
        System.arraycopy(
            tail.samples,
            (tailFrames + fadeFrames) * ch,
            out,
            headFrames * ch,
            (tailKept - fadeFrames) * ch,
        )
        return Spliced(Snip(out, ch, rate), headFrames - fadeFrames, jump, true)
    }

    /**
     * Discontinuity right at the seam, mono-folded, normalized by how much
     * the head typically moves per frame just before it — [LoopCut]'s own
     * `wrapError` idea, applied to a concatenation instead of a wrap.
     * 0 when there's nothing on one side of the seam to click against.
     */
    private fun seamJump(head: Snip, headFrames: Int, tail: Snip, tailFrames: Int): Float {
        if (headFrames == 0 || tailFrames >= tail.frameCount) return 0f
        val ch = head.channels
        fun monoAt(snip: Snip, frame: Int): Float {
            var sum = 0f
            for (c in 0 until ch) sum += snip.samples[frame * ch + c]
            return sum / ch
        }
        val last = monoAt(head, headFrames - 1)
        val first = monoAt(tail, tailFrames)
        val jump = abs(first - last)
        if (jump <= 0f) return 0f

        val window = min(LOCAL_WINDOW, headFrames - 1)
        if (window <= 0) return Float.MAX_VALUE
        var typical = 0.0
        for (i in (headFrames - 1 - window) until (headFrames - 1)) {
            val d = monoAt(head, i + 1) - monoAt(head, i)
            typical += d.toDouble() * d
        }
        val typicalRms = sqrt(typical / window)
        if (typicalRms <= 1e-9) return Float.MAX_VALUE
        return (jump / typicalRms).toFloat()
    }
}
