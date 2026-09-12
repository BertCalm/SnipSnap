package com.snipsnap.shell

import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transients
import com.snipsnap.kit.KitPad
import kotlin.math.abs

/**
 * CATCH A HIT (`docs/CATCH.md`): the pad grid is the chopper.
 *
 * The tape loops, the 4×4 grid sits under it, and holding a pad as a hit
 * goes by cuts that hit onto the pad. A press is always late — reaction
 * time is 80 to 150 ms on a good day — so the cut never starts where the
 * finger landed: it starts at the strongest hit in the [LOOK_BACK_SEC]
 * before the press (or the [LOOK_AHEAD_SEC] after it, for a finger that
 * knows the loop and jumps the gun), which is where the ear heard it. A
 * tap takes the whole hit, as INSTANT KIT would cut it; a hold ends the
 * cut where the finger lifts, or at the next hit, or at the end of the
 * loop, whichever comes first. Holding the same pad on the next pass
 * replaces what it caught.
 *
 * Pure: frames in, cuts out. The screen owns the clock (the deck's
 * position at the touch's own timestamp) and the landing goes through
 * the same door every capture uses ([land] → `KitBuilderModel.assign`),
 * tagged with RE-TRIM's keys, so a caught pad can go back to its tape.
 */
class CatchModel(
    /** The tape, mono, at the deck's own rate — for the zero crossings a hold's end snaps to. */
    private val tape: Snip,
    /** The part of the tape that loops; a catch never reaches outside it. */
    val region: IntRange,
    /** Every hit the detector heard on the tape, in time order ([hitsOf]). */
    val hits: List<Hit>,
    /** Slots that already had a pad when CATCH began: those are never replaced. */
    val taken: Set<Int> = emptySet(),
) {
    init {
        require(tape.channels == 1) { "the tape is mono here" }
        require(region.first >= 0 && region.last < tape.frameCount) { "region $region is off the tape (${tape.frameCount} frames)" }
    }

    /** A hit on the tape: INSTANT KIT's cut of it, and how strongly it announced itself. */
    data class Hit(val range: IntRange, val strength: Float)

    /**
     * What a pad caught: [range] in tape frames; [hit] the index of the
     * hit it snapped to, null when the pad was held between hits and
     * took the tape from the press itself; [held] false for a tap that
     * took the whole hit.
     */
    data class Caught(val slot: Int, val range: IntRange, val hit: Int?, val held: Boolean)

    private val rate = tape.sampleRate
    private val lookBack = (LOOK_BACK_SEC * rate).toInt()
    private val lookAhead = (LOOK_AHEAD_SEC * rate).toInt()
    private val tapFrames = (TAP_SEC * rate).toInt()
    private val minFrames = (MIN_SEC * rate).toInt()

    /** Slot → the press's frame and loop pass. */
    private val pressed = HashMap<Int, Pair<Int, Int>>()
    private val landed = LinkedHashMap<Int, Caught>()

    /** Everything caught so far, in the order it was first caught. */
    val caught: List<Caught> get() = landed.values.toList()

    /** What [slot] holds, or null. */
    fun caughtOn(slot: Int): Caught? = landed[slot]

    /**
     * Finger down on [slot] at tape frame [atFrame], on loop pass [pass]
     * (the screen counts wraps; any consistent count will do). False when
     * the slot had a pad before CATCH began: that press is refused rather
     * than wiping the user's own pad, and no release will land anything.
     */
    fun press(slot: Int, atFrame: Int, pass: Int = 0): Boolean {
        if (slot in taken) return false
        pressed[slot] = atFrame to pass
        return true
    }

    /**
     * Finger up on [slot] at tape frame [atFrame]: the cut, landed and
     * returned, or null when there was no accepted press, or the pad was
     * tapped with no hit near — nothing went by, and a tap has no length
     * of its own to take.
     */
    fun release(slot: Int, atFrame: Int, pass: Int = 0): Caught? {
        val (at, pressPass) = pressed.remove(slot) ?: return null
        val hitIndex = hitFor(at)
        val hit = hitIndex?.let { hits[it] }
        // The loop wrapped under the finger: a later pass, or (with no
        // pass count kept) a frame before the press's own.
        val wrapped = pass != pressPass || atFrame < at
        val tapped = !wrapped && atFrame - at < tapFrames
        val start = hit?.range?.first ?: Transients.zeroCrossingBefore(tape, at.coerceIn(region)).coerceIn(region)
        val regionEnd = region.last + 1
        val hitEnd = hit?.let { (it.range.last + 1).coerceAtMost(regionEnd) }
        val nextHit = hitIndex?.let { hits.getOrNull(it + 1)?.range?.first }
            ?: hits.firstOrNull { it.range.first > start }?.range?.first
        val end = when {
            wrapped -> regionEnd
            tapped -> hitEnd ?: return null
            else -> {
                val lifted = Transients.zeroCrossingBefore(tape, atFrame.coerceIn(region)).coerceAtMost(regionEnd)
                val held = minOf(lifted, nextHit ?: regionEnd, regionEnd)
                // A hold shorter than the hit's own attack is a tap that
                // took a while: the whole hit, not a scrap of it.
                if (held - start < minFrames) hitEnd ?: return null else held
            }
        }
        if (end - start < minFrames) return null
        val result = Caught(slot, start until end, hitIndex, !tapped && !wrapped)
        // Overwriting keeps the slot's place: `caught` stays in first-catch
        // order, so a replaced first catch is still the first.
        landed[slot] = result
        return result
    }

    /** Forget what [slot] caught (the pad on the grid is the screen's to clear). */
    fun forget(slot: Int) {
        landed.remove(slot)
        pressed.remove(slot)
    }

    /**
     * The hit a press at [pressFrame] meant: the strongest whose cut
     * starts inside the window, the nearest on a tie; only hits inside
     * the loop count, since nothing outside it went by. Null when the
     * window is empty.
     */
    fun hitFor(pressFrame: Int): Int? {
        val from = pressFrame - lookBack
        val to = pressFrame + lookAhead
        var best = -1
        for ((i, h) in hits.withIndex()) {
            val s = h.range.first
            if (s !in region || s < from || s > to) continue
            if (best < 0) { best = i; continue }
            val b = hits[best]
            val better = h.strength > b.strength ||
                (h.strength == b.strength && abs(s - pressFrame) < abs(b.range.first - pressFrame))
            if (better) best = i
        }
        return best.takeIf { it >= 0 }
    }

    companion object {
        /** How far before the press a hit may sit and still be the one meant: reaction time, generously. */
        const val LOOK_BACK_SEC = 0.15f

        /** How far after the press: a finger that knows the loop and jumps the gun. */
        const val LOOK_AHEAD_SEC = 0.03f

        /** A press-and-release shorter than this is a tap: the whole hit, not a scrap. */
        const val TAP_SEC = 0.10f

        /** Nothing shorter than this lands: a pad of two frames is a click, not a hit. */
        const val MIN_SEC = 0.02f

        /** Provenance: the door a caught pad came through. */
        const val ORIGIN = "catch"

        /**
         * Every hit on [tape], as INSTANT KIT would cut them, up to two
         * banks' worth — the cap CHOP's AUTO uses, not the sixteen a
         * chop lands, since a catch picks by ear and the quiet hits are
         * often the ones wanted. Detected, not cleaned: only the ranges
         * are kept.
         */
        fun hitsOf(tape: Snip): List<Hit> =
            Chopper.byTransients(tape, maxSlices = Chopper.AUTO_MAX, cleanup = null)
                .map { Hit(it.sourceFrame until it.sourceFrame + it.snip.frameCount, it.onset?.strength ?: 0f) }

        /**
         * [caught] landed on [builder]'s pad through the assign door: [cut]
         * is `Retrim.cut` of the caught range (the caller cuts it, off the
         * main thread, from the tape it already holds), classed by ear,
         * named by class, and tagged with RE-TRIM's keys for [tapeName]
         * plus `origin=catch`. A null [tapeName] is a tape that isn't on
         * the SNIPS shelf (a kit sample TAPE fell back to): tagged with
         * the origin alone, since RE-TRIM could not open it anyway. The
         * builder is not saved here: the caller owns the write.
         *
         * Null, and nothing written, when the slot holds a pad that is not
         * a catch: the press was accepted against a snapshot of the kit,
         * and the write is the moment to look again — a pad another door
         * put there since is the user's, never replaced. An earlier catch
         * on the slot (this pass or the last) is what "the next pass
         * replaces" means, and is.
         */
        fun land(builder: KitBuilderModel, tapeName: String?, cut: Snip, caught: Caught): KitPad? {
            val there = builder.pad(caught.slot)
            if (there != null && there.source["origin"] != ORIGIN) return null
            val cls = Classifier.classify(cut).drumClass
            val source = LinkedHashMap<String, String>()
            if (tapeName != null) source += Retrim.tag(tapeName, caught.range.first, caught.range.first + cut.frameCount)
            source["origin"] = ORIGIN
            return builder.assign(caught.slot, cut, cls, source = source)
        }
    }
}
