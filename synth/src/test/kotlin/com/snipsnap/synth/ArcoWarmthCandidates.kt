package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * The candidates of R1e's warmth page ([ArcoWarmthGenerator]) and the helper that renders them: a test-side emulation of what the engine step would do in
 * [Arco.finish] for BODY above the knee, so the owner can hear it before `src/main` changes. Nothing in `src/main` is touched.
 *
 * **Why they exist.** On R1d's page every candidate was DIFFERENT and none was more box: "dull and muffled rather than full and resonant". The owner asked for
 * **warmth and weight** at the top of the knob and said louder is fine. R1d's page levelled every clip to one loudness, so a low-mid boost ducked the top of the note
 * (every candidate cut the content above 3 kHz by 7 to 20 dB against THE PLAIN ONE, and R1c's own BODY 1 by 2 to 5 dB): a duller note. A body adds sound; it does not trade
 * the string's top end away.
 *
 * **The additive rule, and the helper.** [Note] renders THE PLAIN ONE exactly as [Arco.render] does at BODY 0.5 ([Arco.bow], [Arco.withBody], then the output chain) and keeps the
 * gain [Dsp.levelTo] applied to the conditioned plain (`gain`, the engine's loudness target over the plain's own [Loudness.of]). [render] then builds a candidate as the
 * plain's boxed string through the shaping (series [Dsp.Biquad]s at the raw rate, in place), the same conditioning, **the plain's gain, not a new levelTo**, the engine's own
 * 0.99 ceiling ([Dsp.limitPeak]) and [Dsp.fadeTail]. So the string part of a candidate is at the plain's level and the shaping is added to it. A candidate with no shaping is
 * [Arco.render] at BODY 0.5 to the sample ([ArcoWarmthGenerator] proves it on all ten grid notes and runs three controls that must fail). [condition] is a verbatim copy of
 * [Arco]'s private one, so the proof is also what fails the day the engine's output chain changes.
 *
 * **What the ceiling does.** If the added energy pushes a sample past 0.99 the engine's ceiling would act ([Rendered.ceilingWouldAct]) and, since [Dsp.limitPeak] scales the whole clip, the string is no longer at the plain's level:
 * the clip is ducked by [Rendered.duckDb] and the page would be testing the limiter, not the warmth. [render] therefore takes `engineCeiling`: true is the engine's own path (the ceiling then acts and [Rendered.ceilingActed] says so),
 * false renders the candidate as it would sound if the engine had the headroom; either way [Rendered.peakBeforeCeiling] is the peak the string at the plain's level needs. [ArcoWarmthGenerator] renders its page and its ruler with
 * `engineCeiling` false, prints every clip that would need the ceiling and by how much; a design that needs the ceiling has to be backed off or given headroom by the engine step. The plain itself must not be ceiling-limited
 * (the gain would no longer be one number); [note] throws if it is.
 *
 * **The numbers** are the physics scout's, copied: the rung is +3.5 dB per element (small) and +6.0 dB per element (large), so that where the two skirts overlap the sum at the loudest
 * partials is about +4.0 and +7.0 dB (elements at +4 and +7 would sum to about +4.6 and +8.3 on the cello's lowest partials, the boomy zone). CELLO: WEIGHT a low shelf at 200 Hz, WARMTH a bell at 300 Hz, Q 0.8.
 * ERHU: WEIGHT a low shelf at 600 Hz (a shelf at 450 Hz lifts C5's fundamental only +1.4 dB, under the 2 to 3 dB a listener needs), WARMTH a bell at 800 Hz, Q 1.0 (on the 650 to 820 Hz box region of one laser study).
 * BLOOM is a high shelf at 3500 Hz, +1.5 dB, on the small rung only. Every corner, centre and Q here is a guess (listening): the region is supported, the exact numbers are not.
 */
internal object ArcoWarmthCandidates {

    // ---- the shaping ----------------------------------------------------------------------------

    /** The three kinds of stage a shaping is made of: [Dsp.Biquad]'s own `lowShelf`, `peaking` and `highShelf`. */
    enum class Part { LOW_SHELF, PEAKING, HIGH_SHELF }

    /** One stage: [hz] is the corner (shelf) or centre (bell), [db] the boost, [q] the bell's Q (unused by a shelf: [Dsp.Biquad]'s shelves are S = 1). */
    class Stage(val part: Part, val hz: Float, val db: Float, val q: Float = 0f) {
        fun words(): String = when (part) {
            Part.LOW_SHELF -> "low shelf at ${f1(hz)} Hz, ${s1(db)} dB"
            Part.PEAKING -> "bell at ${f1(hz)} Hz, Q ${f1(q)}, ${s1(db)} dB"
            Part.HIGH_SHELF -> "high shelf at ${f1(hz)} Hz, ${s1(db)} dB"
        }
    }

    private fun f1(v: Float) = "%.1f".format(Locale.ROOT, v)
    private fun s1(v: Float) = "%+.1f".format(Locale.ROOT, v)

    /** The rung, per element, in dB: small and large (the physics scout's: the sum at the loudest partials is about +4.0 and +7.0). */
    const val SMALL_DB = 3.5f
    const val LARGE_DB = 6.0f

    /** BLOOM, on the small rung of both voices: a high shelf's corner and boost (guesses, listening: supported only as "a little sparkle kept"). */
    const val BLOOM_HZ = 3500f
    const val BLOOM_DB = 1.5f

    /** CELLO's corners (guesses, listening): the shelf, the bell and the bell's Q. */
    private const val CELLO_WEIGHT_HZ = 200f
    private const val CELLO_WARMTH_HZ = 300f
    private const val CELLO_WARMTH_Q = 0.8f

    /** ERHU's corners (guesses, listening). */
    private const val ERHU_WEIGHT_HZ = 600f
    private const val ERHU_WARMTH_HZ = 800f
    private const val ERHU_WARMTH_Q = 1.0f

    /** WEIGHT: the low shelf lifting the lowest partials, [db] dB. */
    fun weight(voice: ArcoVoice, db: Float): Stage = when (voice) {
        ArcoVoice.CELLO -> Stage(Part.LOW_SHELF, CELLO_WEIGHT_HZ, db)
        ArcoVoice.ERHU -> Stage(Part.LOW_SHELF, ERHU_WEIGHT_HZ, db)
    }

    /** WARMTH: the broad bell, [db] dB. */
    fun warmth(voice: ArcoVoice, db: Float): Stage = when (voice) {
        ArcoVoice.CELLO -> Stage(Part.PEAKING, CELLO_WARMTH_HZ, db, CELLO_WARMTH_Q)
        ArcoVoice.ERHU -> Stage(Part.PEAKING, ERHU_WARMTH_HZ, db, ERHU_WARMTH_Q)
    }

    /** BLOOM's high shelf. */
    fun bloom(): Stage = Stage(Part.HIGH_SHELF, BLOOM_HZ, BLOOM_DB)

    /** What a clip of the page is. [PLAIN] and [REPEAT] have no shaping, [LOUD] is the flat gain, the rest are the shapings below. */
    enum class Kind(val key: String) {
        PLAIN("PLAIN"), REPEAT("REPEAT"), LOUD("LOUDER-ONLY"),
        WEIGHT("WEIGHT"), WARMTH("WARMTH"), BOTH_SMALL("BOTH-SMALL"), BOTH_LARGE("BOTH-LARGE"), BLOOM("BOTH-SMALL+BLOOM"),
    }

    /** The five shaped candidates, in the order the tables list them. */
    val SHAPED: List<Kind> = listOf(Kind.WEIGHT, Kind.WARMTH, Kind.BOTH_SMALL, Kind.BOTH_LARGE, Kind.BLOOM)

    /** The shaping of a shaped [kind] for [voice]: stages in series, in this order. */
    fun shapeOf(kind: Kind, voice: ArcoVoice): List<Stage> = when (kind) {
        Kind.WEIGHT -> listOf(weight(voice, SMALL_DB))
        Kind.WARMTH -> listOf(warmth(voice, SMALL_DB))
        Kind.BOTH_SMALL -> listOf(weight(voice, SMALL_DB), warmth(voice, SMALL_DB))
        Kind.BOTH_LARGE -> listOf(weight(voice, LARGE_DB), warmth(voice, LARGE_DB))
        Kind.BLOOM -> listOf(weight(voice, SMALL_DB), warmth(voice, SMALL_DB), bloom())
        else -> error("$kind has no shaping")
    }

    /** What a shaped kind is, in words for the key (never on the page). */
    fun settingOf(kind: Kind, voice: ArcoVoice): String = when (kind) {
        Kind.PLAIN -> "the default, BODY 0.5 (the engine's own render)"
        Kind.REPEAT -> "an exact repeat of the plain, rendered again"
        Kind.LOUD -> "the plain with one flat gain, the finished loudness rise of BOTH-SMALL"
        else -> shapeOf(kind, voice).joinToString("; ") { it.words() }
    }

    /** Runs [shape] over [buf] in place: one [Dsp.Biquad] per stage, in order, at [rate]. */
    fun applyShape(buf: FloatArray, shape: List<Stage>, rate: Int) {
        for (stage in shape) {
            val f = Dsp.Biquad()
            when (stage.part) {
                Part.LOW_SHELF -> f.lowShelf(stage.hz, stage.db, rate)
                Part.PEAKING -> f.peaking(stage.hz, stage.db, stage.q, rate)
                Part.HIGH_SHELF -> f.highShelf(stage.hz, stage.db, rate)
            }
            for (i in buf.indices) buf[i] = f.process(buf[i])
        }
    }

    // ---- the engine's output chain, split open --------------------------------------------------

    /** [Dsp.levelTo]'s default ceiling, the engine's own: [Arco.finish] calls it with the default. */
    const val CEILING = 0.99f

    /** [Arco]'s private `condition`, verbatim: the band limit, the decimation, the mean off and the 20 Hz high-pass. In place on [raw] (the band limit is), so pass a copy. */
    private fun condition(raw: FloatArray, rate: Int): FloatArray {
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, Dsp.RATE)
        var mean = 0.0
        for (v in out) mean += v
        val m = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) {
            val x = out[i] - m
            out[i] = x - hp.lp(x, Arco.OUTPUT_DC_HZ)
        }
        return out
    }

    private fun peakOf(x: FloatArray): Float {
        var p = 0f
        for (v in x) p = maxOf(p, abs(v))
        return p
    }

    /** [Loudness.of] of a finished mono clip: the engine's own meter. */
    fun loudnessOf(x: FloatArray): Float = Loudness.of(Snip(x, channels = 1, sampleRate = Dsp.RATE))

    /**
     * One note rendered once: the plain's boxed string at the raw rate (the raw bow through R1c's box at the default BODY), the plain's [gain] (what [Dsp.levelTo] multiplied the conditioned plain by)
     * and THE PLAIN ONE's finished render. [boxed] is never changed: every render works on a copy.
     */
    class Note(val voice: ArcoVoice, val tune: Float, val rate: Int, val boxed: FloatArray, val gain: Float) {
        /** THE PLAIN ONE: [render] with no shaping, which is [Arco.render] at BODY 0.5 to the sample. */
        val plain: Rendered by lazy { render(this, emptyList()) }
    }

    /**
     * A finished candidate and the ceiling: [peakBeforeCeiling] is the largest sample after the plain's gain (and any flat gain), [ceilingApplied] whether this render ran the engine's ceiling at all.
     * [ceilingWouldAct] is whether [CEILING] is below that peak (the engine would cut the whole clip), [ceilingActed] whether it did here, [duckDb] how many dB the engine's ceiling would take off the whole clip (0 if it would not act).
     */
    class Rendered(val snip: Snip, val peakBeforeCeiling: Float, val ceilingApplied: Boolean) {
        val ceilingWouldAct: Boolean get() = peakBeforeCeiling > CEILING
        val ceilingActed: Boolean get() = ceilingApplied && ceilingWouldAct
        val duckDb: Double get() = if (ceilingWouldAct) 20.0 * log10(CEILING.toDouble() / peakBeforeCeiling) else 0.0
        val peak: Float get() = peakOf(snip.samples)
    }

    /**
     * The note at [tune], every other knob at its default (so BODY 0.5), bowed once. The plain must not be ceiling-limited: if the engine's 0.99 acted on it its level would be a gain
     * and a clip, not a gain, and "the plain's gain" would not be one number.
     */
    fun note(voice: ArcoVoice, tune: Float): Note {
        val m = Arco.settled(mapOf("TUNE" to tune), voice)
        require(!Arco.isLoop(m.getValue("HOLD"))) { "the page plays one-shots" }
        require(m.getValue("BODY") == Arco.DEFAULT_BODY) { "the plain is the default BODY: ${m.getValue("BODY")}" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = Arco.bow(voice, Arco.frequencyFor(voice, m.getValue("TUNE")), m, rate)
        val boxed = Arco.withBody(raw, voice, m.getValue("BODY"), rate)
        val c = condition(boxed.copyOf(), rate)
        val measured = loudnessOf(c.copyOf())
        check(measured > 1e-6f) { "$voice at TUNE $tune: the plain is silent" }
        val gain = Dsp.MELODIC_LOUDNESS_TARGET / measured
        val scaled = FloatArray(c.size) { c[it] * gain }
        check(peakOf(scaled) <= CEILING) { "$voice at TUNE $tune: the plain is ceiling-limited (peak ${peakOf(scaled)} at the engine's gain)" }
        return Note(voice, tune, rate, boxed, gain)
    }

    /**
     * A candidate: the plain's boxed string through [shape] (series, in place on a copy), the conditioning, the plain's gain, then [flat] (the LOUDER-ONLY control's one gain, 1 for every other),
     * the engine's ceiling (only if [engineCeiling]) and the fade. No shaping and [flat] 1 is THE PLAIN ONE, [Arco.render] at BODY 0.5, to the sample (the plain never reaches the ceiling, so [engineCeiling] does not matter to it).
     */
    fun render(note: Note, shape: List<Stage>, flat: Float = 1f, engineCeiling: Boolean = true): Rendered {
        val work = note.boxed.copyOf()
        applyShape(work, shape, note.rate)
        val c = condition(work, note.rate)
        for (i in c.indices) c[i] *= note.gain
        if (flat != 1f) for (i in c.indices) c[i] *= flat
        val before = peakOf(c)
        if (engineCeiling) Dsp.limitPeak(c, CEILING)
        Dsp.fadeTail(c)
        return Rendered(Snip(c, channels = 1, sampleRate = Dsp.RATE), before, engineCeiling)
    }

    /** [render] of a shaped [kind] for the note's voice. */
    fun render(note: Note, kind: Kind, engineCeiling: Boolean = true): Rendered = render(note, shapeOf(kind, note.voice), engineCeiling = engineCeiling)

    /**
     * The LOUDER-ONLY control's gain in dB for this note: the finished [Loudness.of] rise of BOTH-SMALL over THE PLAIN ONE, the engine's own meter. (The whole-clip RMS rise is a little different:
     * [ArcoWarmthGenerator] prints both and reports the gap.)
     */
    fun louderGainDb(note: Note, bothSmall: Rendered): Double =
        20.0 * log10(loudnessOf(bothSmall.snip.samples).toDouble() / loudnessOf(note.plain.snip.samples).toDouble())

    /** The LOUDER-ONLY render: THE PLAIN ONE times one flat gain of [gainDb]. */
    fun renderLouder(note: Note, gainDb: Double, engineCeiling: Boolean = true): Rendered =
        render(note, emptyList(), flat = 10.0.pow(gainDb / 20.0).toFloat(), engineCeiling = engineCeiling)
}
