package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R1j's helper for the ARCO bell gate ([ArcoBellGateGenerator]): the engine's BODY lift run with the low shelf and the broad bell at DIFFERENT dB, so that the peak ceiling of a low CELLO note can be spent on the bell
 * instead of the shelf (or on the shelf alone). Nothing here is in the engine and nothing in `src/main` is touched. Everything is built from the engine's own functions: the note is [Arco.conditionedWithBody] (the box is
 * frozen above the knee, so one conditioned note serves every BODY above 0.5), its gain is [Arco.plainGain], its limit is the engine's (`max(PEAK_CAP, the plain's own peak at its gain)`), and the shape is a Double copy of
 * the engine's private lift section ([Section]) with a shelf dB and a bell dB of their own.
 *
 * The copy is proved, not trusted. [liftedDifference] runs it at shelf dB = bell dB = d against [Arco.lifted] and returns the largest sample difference over the largest magnitude of the engine's output (R1j saw 0.00e+00
 * at C2 and F#2 for d of 1, 3, 6, 9 and 12 dB; the generator's bar is 1e-6), and with the bell moved 0.5 dB it must differ by far more (a control that can fail). The finished clip at the engine's own delivered lift is held
 * equal to [Arco.render] by the generator, sample for sample.
 *
 * The frontier. For a split (the shelf at `r` times the bell) the frontier is the largest bell dB up to [SEARCH_TOP_DB] whose finished peak at the plain's gain is at most the note's limit: the top is tried first (and a
 * point that fits there is flagged [Point.limited] and refused by the generator), then [SEARCH_STEPS] bisection steps in the style of the engine's cap search, and the point returned is always one that was itself tested.
 * `r = 1` is the engine's equal split, whose cap the engine finds on 0 to 6 dB by the same search; the generator asserts the two agree to 1e-4 dB.
 */
internal object ArcoBellCandidates {

    /**
     * The most dB, shelf or bell, the frontier search tries. Not a listening value: it is the search's ceiling, and a frontier that stops there is refused, because then the peak, and not the search, would not have set
     * the point. The engine searches 0 to 6; the scout's tables searched 0 to 12 and no point used for the page came near it.
     */
    const val SEARCH_TOP_DB = 12.0

    /** The bisection steps of a frontier search, [Arco.liftPlan]'s own number (its cap is found to 6 dB over 2 to the 20th). Not a listening value: the cost of the search. */
    const val SEARCH_STEPS = 20

    // ---- the engine's lift section, in Double ------------------------------------------------------------------

    /**
     * One RBJ cookbook section, line for line the engine's private lift section: the same formulas ([Dsp.Biquad.lowShelf]'s slope of 1, [Dsp.Biquad.peaking]'s Q), the same division by `a0`, the same direct form I
     * recursion, in Double. Only the shelf and the bell may now be given different dB.
     */
    private class Section private constructor(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        private val nb0 = b0 / a0
        private val nb1 = b1 / a0
        private val nb2 = b2 / a0
        private val na1 = a1 / a0
        private val na2 = a2 / a0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = nb0 * x + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            fun lowShelf(f0: Double, gainDb: Double, rate: Int): Section {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * f0 / rate
                val cw = cos(w0)
                val sw = sin(w0)
                val alpha = sw / 2.0 * sqrt(2.0)
                val sqA = sqrt(a)
                return Section(
                    a * ((a + 1) - (a - 1) * cw + 2 * sqA * alpha),
                    2 * a * ((a - 1) - (a + 1) * cw),
                    a * ((a + 1) - (a - 1) * cw - 2 * sqA * alpha),
                    (a + 1) + (a - 1) * cw + 2 * sqA * alpha,
                    -2 * ((a - 1) + (a + 1) * cw),
                    (a + 1) + (a - 1) * cw - 2 * sqA * alpha,
                )
            }

            fun peaking(f0: Double, gainDb: Double, q: Double, rate: Int): Section {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * f0 / rate
                val cw = cos(w0)
                val sw = sin(w0)
                val alpha = sw / (2.0 * q)
                return Section(
                    1 + alpha * a,
                    -2 * cw,
                    1 - alpha * a,
                    1 + alpha / a,
                    -2 * cw,
                    1 - alpha / a,
                )
            }
        }
    }

    /**
     * [src] through the shelf at [shelfDb] and then the bell at [bellDb] (the voice's corner, centre and Q), in Double from the sample in to the end of the bell and narrowed to Float once there, then times [gain] in Float, and the
     * largest magnitude of what comes out. With [out] given the samples are written there too, so the peak that was tested and the samples that ship come from the same arithmetic (the engine's way).
     */
    private fun liftPass(src: FloatArray, voice: ArcoVoice, shelfDb: Double, bellDb: Double, gain: Float, out: FloatArray?): Float {
        val shelf = Section.lowShelf(Arco.liftShelfHz(voice).toDouble(), shelfDb, Dsp.RATE)
        val bell = Section.peaking(Arco.liftBellHz(voice).toDouble(), bellDb, Arco.liftBellQ(voice).toDouble(), Dsp.RATE)
        var peak = 0f
        for (i in src.indices) {
            val v = bell.process(shelf.process(src[i].toDouble())).toFloat() * gain
            if (out != null) out[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }
        return peak
    }

    /** The largest `|x * gain|` in [x], read the way the engine reads a peak (one float multiply, then the magnitude). */
    fun peakOf(x: FloatArray, gain: Float = 1f): Float {
        var peak = 0f
        for (v in x) {
            val a = abs(v * gain)
            if (a > peak) peak = a
        }
        return peak
    }

    // ---- one note --------------------------------------------------------------------------------------------

    /**
     * One note of one voice at TUNE [step], every knob at its default: its conditioned string above the knee ([conditioned], the same note for every BODY above 0.5), the plain's own [gain], the plain's peak at that gain
     * ([plainPeak]) and the [limit] a finished note must meet, which is the engine's: `max(PEAK_CAP, plainPeak)`. A finished clip is [clip]: the conditioned note through the lift pair, times the plain's gain, then the 4 ms fade.
     */
    class Note(val voice: ArcoVoice, val step: Int) {
        val name: String = ArcoBodyMeasure.noteName(voice, step)
        val conditioned: FloatArray = Arco.conditionedWithBody(ArcoLiftRig.rawOf(voice, ArcoLiftRig.macros(voice, step, 1f)), voice, 1f, ArcoLiftRig.RAW_RATE)
        val gain: Float = Arco.plainGain(conditioned)
        val plainPeak: Float = peakOf(conditioned, gain)
        val limit: Float = max(Arco.PEAK_CAP, plainPeak)

        /** The largest sample of the finished note (before the fade) at the shelf and bell dB given. */
        fun peakAt(shelfDb: Double, bellDb: Double): Float = liftPass(conditioned, voice, shelfDb, bellDb, gain, null)

        /** The finished clip at the shelf and bell dB given, at the plain's gain (the page's own gain is applied later, to every clip of the note alike). */
        fun clip(shelfDb: Double, bellDb: Double): FloatArray {
            val out = FloatArray(conditioned.size)
            liftPass(conditioned, voice, shelfDb, bellDb, gain, out)
            Dsp.fadeTail(out)
            return out
        }
    }

    // ---- the frontier ----------------------------------------------------------------------------------------

    /** A point the search tested and kept: the [shelfDb] and [bellDb], the [peak] there (before the fade) and whether the search stopped at [SEARCH_TOP_DB] instead of at the limit. */
    class Point(val shelfDb: Double, val bellDb: Double, val peak: Float, val limited: Boolean)

    /** The largest `t` in 0 to [SEARCH_TOP_DB] with `peakAt(t) <= limit`: the top first, else [SEARCH_STEPS] bisection steps. Returns `t`, the peak at it and whether it is the top. */
    private fun largest(limit: Float, peakAt: (Double) -> Float): Triple<Double, Float, Boolean> {
        val top = peakAt(SEARCH_TOP_DB)
        if (top <= limit) return Triple(SEARCH_TOP_DB, top, true)
        var low = 0.0
        var high = SEARCH_TOP_DB
        repeat(SEARCH_STEPS) {
            val mid = (low + high) / 2.0
            if (peakAt(mid) <= limit) low = mid else high = mid
        }
        return Triple(low, peakAt(low), false)
    }

    /** The frontier with the shelf at [shelfPerBell] times the bell: 1 is the engine's equal split, 0.5 is MORE BELL, 0 is BELL ONLY. */
    fun frontier(note: Note, shelfPerBell: Double): Point {
        val (bell, peak, limited) = largest(note.limit) { note.peakAt(shelfPerBell * it, it) }
        return Point(shelfPerBell * bell, bell, peak, limited)
    }

    /** The frontier with the bell at 0 dB and the shelf as large as the limit allows: MORE SHELF. */
    fun shelfOnly(note: Note): Point {
        val (shelf, peak, limited) = largest(note.limit) { note.peakAt(it, 0.0) }
        return Point(shelf, 0.0, peak, limited)
    }

    // ---- the proof of the copy ---------------------------------------------------------------------------------

    /**
     * The largest sample difference between [Arco.lifted] of [conditioned] at [db] dB per element and this copy at shelf [db] and bell [db] plus [bellOffsetDb] (gain 1), over the largest magnitude of the engine's output.
     * With an offset of 0 the two must agree to the generator's bar; with an offset of 0.5 dB they must not (the generator's control).
     */
    fun liftedDifference(conditioned: FloatArray, voice: ArcoVoice, db: Float, bellOffsetDb: Double = 0.0): Double {
        val engine = Arco.lifted(conditioned, voice, db)
        val mine = FloatArray(conditioned.size)
        liftPass(conditioned, voice, db.toDouble(), db.toDouble() + bellOffsetDb, 1f, mine)
        var diff = 0.0
        for (i in engine.indices) diff = max(diff, abs(engine[i].toDouble() - mine[i]))
        return diff / max(ArcoLiftRig.peakOf(engine).toDouble(), 1e-30)
    }
}
