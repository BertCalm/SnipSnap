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
 * false renders the candidate as it would sound if the engine had the headroom; either way [Rendered.peakBeforeCeiling] is the peak the string at the plain's level needs. [ArcoWarmthGenerator] renders every grid and page clip without the ceiling
 * except **one**: CELLO F#2's BOTH-LARGE, which it renders **with** it, because that is the clip the engine could actually make (the string at the plain's level peaks at 1.277, x2.04 the plain's 0.625, so the ceiling takes
 * 2.2 dB off the whole clip and the string is ducked by that much). It prints every other clip that would need the ceiling and by how much; a design that needs the ceiling has to be backed off or given headroom by the engine step.
 * The plain itself must not be ceiling-limited (the gain would no longer be one number); [note] throws if it is. [CEILING] is [Dsp.levelTo]'s unnamed default argument copied, and [ceilingFailures] is the control that ties it
 * to the engine: a hot buffer through [Dsp.levelTo] and through [Arco.finish] must equal the same buffer through the gain then [Dsp.limitPeak] at [CEILING], and a ceiling 0.01 off must be told apart.
 *
 * **The engine step (read this before it starts).** (1) The bit-identity proof above runs at generation time only (`:synth:generateArcoWarmth`, which is not in CI: nothing here is a test), so it does not guard the engine;
 * the engine step should delete these three files, or replace them with a test that compares the engine's BODY-above-the-knee render to this helper. (2) The engine step will meet [ArcoTest]'s finished-peak bar of 0.95
 * (`finishedPeakBar`, over the BODY grid) at CELLO C2: the shaped string at the plain's level peaks above it (BOTH-SMALL 1.023 and BOTH-LARGE 1.429 there, the plain 0.656), so the step must back the rung off on the low CELLO notes,
 * give headroom, or accept a ceiling duck on them; it must never loosen the bar.
 *
 * **The numbers** are the physics scout's, copied: the rung is +3.5 dB per element (small), +6.0 dB per element (large) and +1.75 dB per element (half), so that where the two skirts overlap the sum at the loudest
 * partials is about +4.0, +7.0 and +2.0 dB (elements at +4 and +7 would sum to about +4.6 and +8.3 on the cello's lowest partials, the boomy zone). CELLO: WEIGHT a low shelf at 200 Hz, WARMTH a bell at 300 Hz, Q 0.8.
 * ERHU: WEIGHT a low shelf at 600 Hz (a shelf at 450 Hz lifts C5's fundamental only +1.4 dB, under the 2 to 3 dB a listener needs), WARMTH a bell at 800 Hz, Q 1.0 (on the 650 to 820 Hz box region of one laser study).
 * BLOOM is BOTH-SMALL plus a high shelf at 2500 Hz, +3.0 dB (the first R1e page had 3500 Hz and +1.5 dB, which read only +0.75 (ERHU C5) and +0.85 (CELLO C3), +0.75 to +0.96 over the grid, dB over BOTH-SMALL in 3 to 8 kHz: too little to test whether a little sparkle matters; this one is built so
 * that band reads at least +1.5 dB over BOTH-SMALL, and the generator throws if it does not). Every corner, centre and Q here is a guess (listening): the region is supported, the exact numbers are not.
 *
 * **LOUDER-ONLY is matched to the ear's weighting, not to the engine's meter.** [Loudness.of] is a gentle low cut (one pole at 120 Hz, taking out 0.7 of the low-pass) and then the loudest 200 ms RMS, so it barely discounts the 130 to 300 Hz region that
 * WEIGHT and WARMTH boost: matched on it, the flat gain read 1.2 to 1.9 dB louder than BOTH-SMALL by ear on CELLO (it was about as loud as BOTH-LARGE). [louderGainDb] therefore takes BOTH-SMALL's **A-weighted** rise
 * ([ArcoWarmthMeasure.aRiseDb]: the standard analogue prototype through the bilinear transform, run over the finished clips, RMS over the steady part), and the flat gain is that rise. The engine meter's rise for the same two clips is then
 * not equal, and the generator prints the gap; the key carries the [Loudness.of] rise, the whole-clip RMS rise and the A-weighted rise of every clip.
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

    /** The rung, per element, in dB: small and large (the physics scout's: the sum at the loudest partials is about +4.0 and +7.0), and half of the small (about +2.0). */
    const val SMALL_DB = 3.5f
    const val LARGE_DB = 6.0f
    const val HALF_DB = SMALL_DB / 2f

    /**
     * BLOOM, on the small rung of both voices: a high shelf's corner and boost (guesses, listening: supported only as "a little sparkle added"). The shelf is [Dsp.Biquad]'s S = 1 one, which reaches +0.88 dB
     * of its +3.0 at 2 kHz, +1.5 at the corner, +2.0 at 3 kHz, +2.6 at 4 kHz and +2.97 at 8 kHz, so the 3 to 8 kHz harmonics read about +2 to +3 dB over BOTH-SMALL.
     */
    const val BLOOM_HZ = 2500f
    const val BLOOM_DB = 3.0f

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
        WEIGHT("WEIGHT"), WARMTH("WARMTH"), BOTH_HALF("BOTH-HALF"), BOTH_SMALL("BOTH-SMALL"), BOTH_LARGE("BOTH-LARGE"), BLOOM("BOTH-SMALL+BLOOM"),
    }

    /** The six shaped candidates, in the order the tables list them. */
    val SHAPED: List<Kind> = listOf(Kind.WEIGHT, Kind.WARMTH, Kind.BOTH_HALF, Kind.BOTH_SMALL, Kind.BOTH_LARGE, Kind.BLOOM)

    /** The shaping of a shaped [kind] for [voice]: stages in series, in this order. */
    fun shapeOf(kind: Kind, voice: ArcoVoice): List<Stage> = when (kind) {
        Kind.WEIGHT -> listOf(weight(voice, SMALL_DB))
        Kind.WARMTH -> listOf(warmth(voice, SMALL_DB))
        Kind.BOTH_HALF -> listOf(weight(voice, HALF_DB), warmth(voice, HALF_DB))
        Kind.BOTH_SMALL -> listOf(weight(voice, SMALL_DB), warmth(voice, SMALL_DB))
        Kind.BOTH_LARGE -> listOf(weight(voice, LARGE_DB), warmth(voice, LARGE_DB))
        Kind.BLOOM -> listOf(weight(voice, SMALL_DB), warmth(voice, SMALL_DB), bloom())
        else -> error("$kind has no shaping")
    }

    /** What a shaped kind is, in words for the key (never on the page). */
    fun settingOf(kind: Kind, voice: ArcoVoice): String = when (kind) {
        Kind.PLAIN -> "the default, BODY 0.5 (the engine's own render)"
        Kind.REPEAT -> "an exact repeat of the plain, rendered again"
        Kind.LOUD -> "the plain with one flat gain, BOTH-SMALL's A-weighted loudness rise on the finished clip"
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

    /** [Loudness.of] of a finished mono clip: the engine's own meter (a gentle 120 Hz low cut, then the loudest 200 ms RMS). */
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
     * [ceilingWouldAct] is whether [CEILING] is below that peak (the engine would cut the whole clip), [ceilingActed] whether it did here, [duckDb] the dB (negative) the engine's ceiling would take off the whole clip
     * (0 if it would not act) and [tookOffDb] the same as a positive number, 0 unless the ceiling actually acted on this render.
     */
    class Rendered(val snip: Snip, val peakBeforeCeiling: Float, val ceilingApplied: Boolean) {
        val ceilingWouldAct: Boolean get() = peakBeforeCeiling > CEILING
        val ceilingActed: Boolean get() = ceilingApplied && ceilingWouldAct
        val duckDb: Double get() = if (ceilingWouldAct) 20.0 * log10(CEILING.toDouble() / peakBeforeCeiling) else 0.0
        val tookOffDb: Double get() = if (ceilingActed) -duckDb else 0.0
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
     * The LOUDER-ONLY control's gain in dB for this note: the **A-weighted** rise of BOTH-SMALL over THE PLAIN ONE on the finished clips ([ArcoWarmthMeasure.aRiseDb], steady part), not the engine meter's: [Loudness.of] ignores
     * too little of the 130 to 300 Hz region the shaping boosts. A flat gain moves every frequency by the same amount, so the flat clip's A-weighted rise is exactly this gain and equals BOTH-SMALL's; the generator proves it on the
     * rendered clips to [ArcoWarmthGenerator]'s bar and prints how far apart the [Loudness.of] rises are.
     */
    fun louderGainDb(note: Note, bothSmall: Rendered): Double = ArcoWarmthMeasure.aRiseDb(bothSmall.snip.samples, note.plain.snip.samples)

    /** The LOUDER-ONLY render: THE PLAIN ONE times one flat gain of [gainDb]. */
    fun renderLouder(note: Note, gainDb: Double, engineCeiling: Boolean = true): Rendered =
        render(note, emptyList(), flat = 10.0.pow(gainDb / 20.0).toFloat(), engineCeiling = engineCeiling)

    // ---- the control that ties CEILING to the engine -------------------------------------------

    /** A hot clip: a sparse spike train on a faint bed, deterministic, whose crest factor (about 8) is high enough that the engine's loudness target needs a gain that takes the peak past [CEILING]. [period] is in samples. */
    private fun hotBuffer(n: Int, period: Int, rate: Int): FloatArray {
        val buf = FloatArray(n) { 0.02f * kotlin.math.sin(2.0 * Math.PI * 220.0 * it / rate).toFloat() }
        var i = period / 2
        while (i < n) { buf[i] += 0.5f; i += period }
        return buf
    }

    /**
     * What is wrong with [CEILING] as a copy of the engine's (empty: nothing). Two hot buffers, each of which must need the ceiling to act (the control checks the peak it needs): one at [Dsp.RATE] through [Dsp.levelTo]
     * (its default ceiling) must equal the same buffer through the gain `target / Loudness.of` then [Dsp.limitPeak] at [CEILING], to the sample; the other, at the raw rate, through [Arco.finish] must equal [condition], that gain,
     * [Dsp.limitPeak] at [CEILING] and [Dsp.fadeTail]. The control that must fail: the same chain with a ceiling of 0.98 must NOT equal the engine's. A [CEILING] the engine changed (or a [condition] that drifted) fails the first two.
     */
    fun ceilingFailures(): List<String> {
        val out = ArrayList<String>()
        val target = Dsp.MELODIC_LOUDNESS_TARGET
        val hot = hotBuffer(Dsp.RATE, 64, Dsp.RATE)
        val measured = loudnessOf(hot.copyOf())
        val needs = peakOf(hot) * target / measured
        if (needs <= CEILING * 1.05f) out += "the hot buffer needs a peak of $needs at the engine's gain: it does not exercise the ceiling (want over ${CEILING * 1.05f})"
        val viaEngine = hot.copyOf()
        Dsp.levelTo(viaEngine, Dsp.RATE, target)
        val viaHelper = hot.copyOf()
        for (i in viaHelper.indices) viaHelper[i] *= target / measured
        Dsp.limitPeak(viaHelper, CEILING)
        if (!viaEngine.contentEquals(viaHelper)) out += "Dsp.levelTo's default ceiling is not CEILING $CEILING: the same buffer through the gain and limitPeak(CEILING) differs"
        if (abs(peakOf(viaEngine) - CEILING) > 1e-6f) out += "the ceiling did not land on CEILING: peak ${peakOf(viaEngine)}"
        val wrong = hot.copyOf()
        for (i in wrong.indices) wrong[i] *= target / measured
        Dsp.limitPeak(wrong, 0.98f)
        if (wrong.contentEquals(viaEngine)) out += "control failed to fail: a ceiling of 0.98 is bit-identical to the engine's, so this control cannot see a ceiling 0.01 off"

        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val rawHot = hotBuffer(rate, 64 * Dsp.OVERSAMPLE, rate)
        val finished = Arco.finish(rawHot.copyOf(), rate)
        val c = condition(rawHot.copyOf(), rate)
        val gain = target / loudnessOf(c.copyOf())
        for (i in c.indices) c[i] *= gain
        val rawNeeds = peakOf(c)
        if (rawNeeds <= CEILING * 1.05f) out += "the hot raw buffer needs a peak of $rawNeeds at the engine's gain: it does not exercise the ceiling in Arco.finish"
        Dsp.limitPeak(c, CEILING)
        Dsp.fadeTail(c)
        if (!finished.contentEquals(c)) out += "Arco.finish is not the helper's condition, gain, limitPeak(CEILING), fadeTail on a hot buffer"
        val wrongFinish = condition(rawHot.copyOf(), rate)
        for (i in wrongFinish.indices) wrongFinish[i] *= gain
        Dsp.limitPeak(wrongFinish, 0.98f)
        Dsp.fadeTail(wrongFinish)
        if (finished.contentEquals(wrongFinish)) out += "control failed to fail: Arco.finish equals the helper's chain with a ceiling of 0.98"
        return out
    }
}
