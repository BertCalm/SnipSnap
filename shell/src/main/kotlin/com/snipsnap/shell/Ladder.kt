package com.snipsnap.shell

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Features
import com.snipsnap.audio.Similar
import com.snipsnap.audio.Slice
import com.snipsnap.audio.Snip
import com.snipsnap.audio.TempoEstimate
import kotlin.math.abs

/**
 * THE ZOOM LADDER (`docs/CHOP_CONTROLS.md` §11): "how many slices" is the
 * wrong question. A four-bar break has a natural set of answers — one
 * phrase, four bars, sixteen beats, sixty-four sixteenths — and the
 * ladder makes those the choices: a pad is a phrase at the top, a
 * sixteenth at the bottom, and every rung is musical.
 *
 * The rungs above HIT need three things the tempo estimate does not
 * give. **The beat**, fitted to the hits the way ON THE GRID fits its
 * step: the estimate is a seed, the pulse of this take is the hits.
 * **The one**: the estimator has a period but no phase, and captures
 * rarely start on the downbeat, so of the four beats the first bar
 * could start on, the one is the beat whose lines carry the most
 * weight — the loudest peak just after each line, summed down the tape
 * — since the kick on the one is what a break leans on. It can be
 * wrong (a half-time feel, a snare-led break), so it is nudged by a beat
 * at a time. **The phrase**: the period at which the bars rhyme —
 * each bar's features against the bar two, four or eight later, the
 * smallest period that rhymes as well as any longer one — so an ABAB
 * break phrases at two bars and an ABCD at four.
 *
 * Pure: a source, its tempo and its hits in, a [Pulse] and cut frames
 * out. The chop model cuts and names the slices.
 */
object Ladder {

    /** The rungs above HIT, bottom to top. [perBeat] divides a beat; [beats] spans beats (PHRASE spans the phrase). */
    enum class Rung(val label: String, val perBeat: Int, val beats: Int) {
        SIXTEENTH("16TH", 4, 1),
        BEAT("BEAT", 1, 1),
        BAR("BAR", 1, 4),
        PHRASE("PHRASE", 1, 0),
    }

    const val BEATS_PER_BAR = 4

    /** A line's weight is the loudest sample from this much of a beat before it (the detector's backoff) to [ONE_LOOK_AFTER] after. */
    const val ONE_LOOK_BEFORE = 0.05

    /** ...to this much of a beat after the line: the attack of whatever sits on it. */
    const val ONE_LOOK_AFTER = 0.25

    /** The bar periods a phrase can be. */
    val PHRASE_PERIODS = listOf(2, 4, 8)

    /** A period rhymes "as well as any longer one" when its mean distance is within this of the best. */
    const val RHYME_SLACK = 1.2f

    /** A bar's rhythm is read as this many steps of energy — its sixteenths. */
    const val PROFILE_STEPS = 16

    /** How much the bars' spectral features weigh beside their rhythm in the rhyme distance. */
    const val FEATURE_WEIGHT = 0.5f

    /** A last slice shorter than this much of a span joins the one before it rather than being a pad of scraps. */
    const val TAIL_MIN = 0.5

    /**
     * The take's pulse: [bpm] as seeded, [beatFrames] as fitted to the
     * hits, [anchor] the first hit's cut, [one] which beat from the
     * anchor the downbeat sits on (0..3), [phraseBars] how many bars a
     * phrase runs.
     */
    data class Pulse(val bpm: Float, val beatFrames: Double, val anchor: Int, val one: Int, val phraseBars: Int) {
        /** The downbeat, with [nudge] beats added (the ◀ ▶ on the bench; wraps within the bar). */
        fun downbeat(nudge: Int = 0): Int = anchor + Math.round(Math.floorMod(one + nudge, BEATS_PER_BAR) * beatFrames).toInt()

        /** The bar, in frames. */
        val barFrames: Double get() = beatFrames * BEATS_PER_BAR

        /** How many whole bars run from the downbeat to [frameCount]. */
        fun wholeBars(frameCount: Int, nudge: Int = 0): Int = ((frameCount - downbeat(nudge)) / barFrames).toInt().coerceAtLeast(0)
    }

    /**
     * [source]'s pulse from [tempo] and [hits] (INSTANT KIT's cuts of it,
     * every one — `CatchModel.hitsOf`'s list, as slices). Null with no
     * hits: there is nothing to anchor on.
     */
    fun hear(source: Snip, tempo: TempoEstimate, hits: List<Slice>): Pulse? {
        if (hits.isEmpty()) return null
        val seed = 60.0 / tempo.bpm * source.sampleRate
        val anchor = hits.first().sourceFrame
        // Fitted on the sixteenth, not the beat: hits sit between beats
        // (the hats, the snare's and), and fitting them to beat lines
        // would pull the beat toward whichever side they fall on.
        val beat = ChopReviewModel.fitStep(anchor, hits.map { it.sourceFrame }, seed / Rung.SIXTEENTH.perBeat) * Rung.SIXTEENTH.perBeat
        val one = theOne(source, anchor, beat)
        val phrase = phrase(source, anchor + Math.round(one * beat).toInt(), beat)
        return Pulse(tempo.bpm, beat, anchor, one, phrase)
    }

    /**
     * Of the four beats the first bar could start on, the one whose
     * lines carry the most weight down the tape — the loudest sample in
     * the window round each line, summed — the earliest on a tie.
     */
    fun theOne(source: Snip, anchor: Int, beat: Double): Int {
        val before = (beat * ONE_LOOK_BEFORE).toInt()
        val after = (beat * ONE_LOOK_AFTER).toInt()
        var best = 0
        var bestScore = -1f
        for (k in 0 until BEATS_PER_BAR) {
            var score = 0f
            var line = anchor + k * beat
            while (line < source.frameCount) {
                val at = Math.round(line).toInt()
                score += peak(source, at - before, at + after)
                line += beat * BEATS_PER_BAR
            }
            if (score > bestScore) {
                bestScore = score
                best = k
            }
        }
        return best
    }

    /**
     * How many bars a phrase runs from [downbeat]: the smallest of
     * [PHRASE_PERIODS] whose bars rhyme as well as any longer period's
     * ([RHYME_SLACK]), measured as the mean feature distance between
     * each bar and the one a period later, over periods the tape holds
     * at least twice. Fewer than four whole bars is one phrase of them.
     */
    fun phrase(source: Snip, downbeat: Int, beat: Double): Int {
        val bar = beat * BEATS_PER_BAR
        val bars = ((source.frameCount - downbeat) / bar).toInt()
        val periods = PHRASE_PERIODS.filter { bars >= 2 * it }
        if (periods.isEmpty()) return bars.coerceAtLeast(1)
        // A bar is its rhythm first — energy per sixteenth, against the
        // bar's loudest — and its colour second: two bars of the same kit
        // with the snare on a different step sound alike to the spectral
        // features and nothing alike to the ear.
        val features = ArrayList<Features>(bars)
        val profiles = ArrayList<FloatArray>(bars)
        for (b in 0 until bars) {
            val from = downbeat + Math.round(b * bar).toInt()
            val to = (downbeat + Math.round((b + 1) * bar).toInt()).coerceAtMost(source.frameCount)
            features += FeatureExtractor.extract(Snip(source.samples.copyOfRange(from, to), 1, source.sampleRate))
            profiles += profile(source, from, to)
        }
        val distance = periods.associateWith { p ->
            var acc = 0f
            var n = 0
            for (i in 0 until bars - p) {
                acc += rhythmDistance(profiles[i], profiles[i + p]) + FEATURE_WEIGHT * Similar.distance(features[i], features[i + p])
                n++
            }
            acc / n
        }
        val best = distance.values.min()
        return periods.first { distance.getValue(it) <= best * RHYME_SLACK }
    }

    /**
     * The cut frames for [rung] on [pulse], from the downbeat ([nudge]
     * beats moved), every span of the rung until the source ends — the
     * last slice runs to the end, whole or not — at most [cap] of them.
     * What lies before the downbeat is the pickup, and no pad.
     */
    fun cuts(source: Snip, pulse: Pulse, rung: Rung, nudge: Int = 0, cap: Int = ChopReviewModel.MAX_HITS): List<Int> {
        val span = when (rung) {
            Rung.SIXTEENTH -> pulse.beatFrames / rung.perBeat
            Rung.BEAT -> pulse.beatFrames
            Rung.BAR -> pulse.barFrames
            Rung.PHRASE -> pulse.barFrames * pulse.phraseBars
        }
        val start = pulse.downbeat(nudge)
        val out = ArrayList<Int>()
        var k = 0
        while (out.size < cap) {
            val at = start + Math.round(k * span).toInt()
            if (at >= source.frameCount) break
            out += at
            k++
        }
        // The detector's cut sits a hair before each attack, so the tape's
        // end can leave a sliver past the last whole span: a last slice
        // shorter than half a span is no pad, and joins the one before.
        if (out.size > 1 && source.frameCount - out.last() < span * TAIL_MIN) out.removeAt(out.size - 1)
        return out
    }

    /** [PROFILE_STEPS] steps of RMS energy over [from] until [to], scaled to the loudest step. */
    private fun profile(source: Snip, from: Int, to: Int): FloatArray {
        val out = FloatArray(PROFILE_STEPS)
        val len = (to - from).coerceAtLeast(1)
        for (s in 0 until PROFILE_STEPS) {
            val a = from + s * len / PROFILE_STEPS
            val b = from + (s + 1) * len / PROFILE_STEPS
            var acc = 0.0
            for (i in a until b) acc += source.samples[i].toDouble() * source.samples[i]
            out[s] = kotlin.math.sqrt(acc / (b - a).coerceAtLeast(1)).toFloat()
        }
        val top = out.max()
        if (top > 0f) for (s in out.indices) out[s] /= top
        return out
    }

    /** Root mean square difference of two profiles, 0 for the same rhythm. */
    private fun rhythmDistance(a: FloatArray, b: FloatArray): Float {
        var acc = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            acc += d * d
        }
        return kotlin.math.sqrt(acc / a.size)
    }

    /** The name of slice [index] (0-based) on [rung]: `BAR 3`, `16TH 12`. */
    fun name(rung: Rung, index: Int): String = "${rung.label} ${index + 1}"

    private fun peak(source: Snip, from: Int, to: Int): Float {
        var peak = 0f
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(source.frameCount)) {
            val a = abs(source.samples[i])
            if (a > peak) peak = a
        }
        return peak
    }
}
