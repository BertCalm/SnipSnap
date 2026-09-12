package com.snipsnap.shell

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transients
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * HUM THE CHOP (`docs/CHOP_CONTROLS.md` §10): you beatbox the pattern you
 * want over the break, and your mouth decides both where the cuts fall
 * and what they are called.
 *
 * The tape plays in your headphones while the mic records the hum; this
 * reads the two against each other. Every sound the mouth made is an
 * onset in the hum. Each is matched to the nearest hit on the tape
 * within [MATCH_SEC] — after the hum's own lag is taken out: the whole
 * hum is late by the same amount (the phone's output latency, the ear,
 * the mouth), so the median offset between the mouth's onsets and their
 * nearest hits, over those within [LAG_MAX_SEC], is the lag, and it is
 * removed before matching rather than guessed at. Hits the mouth landed
 * on are the cuts, INSTANT KIT's own cut of each; hits it did not are
 * not kept; a mouth sound that landed on no hit is counted as missed
 * and says so. Two mouth sounds on one hit: the nearer keeps it.
 *
 * What the mouth said is the classifier's reading of the mouth sound
 * itself (the hum from that onset to the next, at most [MOUTH_MAX_SEC]):
 * a "boom" reads as kick-like, a "tss" as hat-like. Where it is unsure
 * (under [ChopReviewModel.NOT_SURE_BELOW], or not a drum at all), the
 * tape slice's own classification stands. Pure: two snips in, cuts out;
 * the screen owns the mic and the deck.
 */
object Hum {

    /** A mouth sound matches the nearest hit within this, once the lag is out. */
    const val MATCH_SEC = 0.12f

    /** Offsets further than this from the nearest hit take no part in the lag estimate. */
    const val LAG_MAX_SEC = 0.20f

    /** A mouth sound is at most this long for the classifier: the next onset ends it sooner. */
    const val MOUTH_MAX_SEC = 0.30f

    /** Under this many frames a mouth sound is a click, not a word: the tape's class stands. */
    private const val MOUTH_MIN_SEC = 0.02f

    /**
     * One cut the mouth chose: [hit] indexes the tape's hits, [range] is
     * that hit's own cut, [mouth] what the mouth said (null: the tape's
     * own word stands), [at] where the mouth's onset sits on the tape
     * with the lag removed.
     */
    data class Cut(val hit: Int, val range: IntRange, val mouth: DrumClass?, val at: Int)

    /**
     * The hum read against the tape: the [cuts] in tape order, how many
     * mouth sounds [missed] every hit, the [lagFrames] taken out, how
     * many hits the tape had ([hitsHeard]) and the mouth's whole
     * [pattern] on the tape, lag removed — the beat you sang, for a
     * later READ AS GROOVE of it.
     */
    data class Reading(val cuts: List<Cut>, val missed: Int, val lagFrames: Int, val hitsHeard: Int, val pattern: List<Int>) {
        /** The chop mode that cuts exactly these, with the mouth's words as the chips. */
        fun mode(): ChopReviewModel.ChopMode.Hummed =
            ChopReviewModel.ChopMode.Hummed(cuts.map { it.range }, cuts.map { it.mouth })
    }

    /**
     * [hum] read against [tape], both mono; the hum's first frame sits at
     * tape frame [offsetFrames] (zero when they started together — the
     * mic ring keeps only its last minute, so a longer hum's window
     * starts later), at its own rate. [hits] are the tape's hits as
     * CATCH hears them (every one, not a chop's sixteen); [sure] is the
     * confidence a mouth sound needs to name a chip.
     */
    fun read(
        tape: Snip,
        hum: Snip,
        hits: List<CatchModel.Hit> = CatchModel.hitsOf(tape),
        sure: Float = ChopReviewModel.NOT_SURE_BELOW,
        offsetFrames: Int = 0,
    ): Reading {
        require(tape.channels == 1 && hum.channels == 1) { "the tape and the hum are mono here" }
        require(offsetFrames >= 0) { "the hum's window starts on the tape, not before it: $offsetFrames" }
        val onsets = Transients.detect(hum)
        val scale = tape.sampleRate.toDouble() / hum.sampleRate
        val mouthAt = onsets.map { (it.frame * scale).roundToInt() + offsetFrames }
        val starts = hits.map { it.range.first }
        val matchFrames = (MATCH_SEC * tape.sampleRate).toInt()
        val lagMax = (LAG_MAX_SEC * tape.sampleRate).toInt()
        fun nearest(t: Int): Int? = starts.indices.minByOrNull { abs(starts[it] - t) }

        val lags = mouthAt.mapNotNull { t -> nearest(t)?.let { i -> (t - starts[i]).takeIf { abs(it) <= lagMax } } }.sorted()
        val lag = if (lags.isEmpty()) 0 else lags[lags.size / 2]

        // hit → (the mouth sound that has it, how far off it was)
        val claimed = HashMap<Int, Pair<Int, Int>>()
        var missed = 0
        for ((m, t0) in mouthAt.withIndex()) {
            val t = t0 - lag
            val i = nearest(t)
            val d = i?.let { abs(starts[it] - t) }
            if (i == null || d == null || d > matchFrames) {
                missed++
                continue
            }
            val prev = claimed[i]
            if (prev == null || d < prev.second) {
                if (prev != null) missed++
                claimed[i] = m to d
            } else {
                missed++
            }
        }
        val cuts = claimed.entries.sortedBy { it.key }.map { (i, mc) ->
            Cut(i, hits[i].range, mouthClass(hum, onsets.map { it.frame }, mc.first, sure), mouthAt[mc.first] - lag)
        }
        return Reading(cuts, missed, lag, hits.size, mouthAt.map { it - lag })
    }

    /**
     * What the mouth said at onset [m] of [hum], or null when it wasn't
     * sure, or not a drum: UNKNOWN, LOOP and TONAL (a held vowel is a note,
     * not a hit) all leave the tape's own word standing.
     */
    private fun mouthClass(hum: Snip, onsets: List<Int>, m: Int, sure: Float): DrumClass? {
        val start = onsets[m]
        val cap = start + (MOUTH_MAX_SEC * hum.sampleRate).toInt()
        val end = minOf(onsets.getOrNull(m + 1) ?: hum.frameCount, cap, hum.frameCount)
        if (end - start < (MOUTH_MIN_SEC * hum.sampleRate).toInt()) return null
        val piece = Snip(hum.samples.copyOfRange(start, end), 1, hum.sampleRate)
        val heard = Classifier.classify(piece)
        return heard.drumClass.takeIf {
            it != DrumClass.UNKNOWN && it != DrumClass.LOOP && it != DrumClass.TONAL && heard.confidence >= sure
        }
    }
}
