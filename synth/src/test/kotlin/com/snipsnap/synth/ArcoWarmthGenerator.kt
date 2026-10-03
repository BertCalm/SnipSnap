package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.ArcoWarmthCandidates.Kind
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/**
 * Renders the ARCO R1e warmth listening page under testkit/arco-warmth/ (gitignored): `manifest.json`, which the page builds itself from, the clips and a copy of the page from the test
 * resources. Run via `./gradlew :synth:generateArcoWarmth`, then publish the folder whole. The key (`testkit/arco-warmth-key.json`, a sibling of the folder and never in it) says which letter is which
 * candidate and carries each clip's absolute band deltas, its three loudness rises (the engine meter's, the whole-clip RMS and the A-weighted) and peak; the page is blind on purpose.
 *
 * The owner heard R1d's page and wrote "dull and muffled rather than full and resonant", and asked for **warmth and weight** at the top of the knob, saying louder is fine. R1d levelled every clip to one
 * loudness, so a low-mid boost ducked the top. This page builds each candidate **on top of THE PLAIN ONE at the plain's own level** ([ArcoWarmthCandidates]: the plain's boxed string through a shelf and a bell,
 * the plain's gain, not a new one) and does **not** level the clips: one gain per group, the one that puts THE PLAIN ONE at the re-listen page's level, is applied to every clip of the group, so a clip that adds
 * sound is louder and the key says by how much. Nobody has listened to any of these.
 *
 * Three groups, every knob but TUNE at its default and every clip dry and mono:
 *  - ERHU C5 (its default note), nine clips: THE PLAIN ONE and eight lettered clips C D E F G H J K: an exact repeat of the plain, the LOUDER-ONLY control, WEIGHT, WARMTH, BOTH-HALF, BOTH-SMALL, BOTH-LARGE and BLOOM
 *    (BOTH-SMALL plus a high shelf).
 *  - CELLO C3 (its default note), the same nine clips, letters L M N P Q R S T.
 *  - CELLO F#2 (a low note, where the box matters most), four clips: THE PLAIN ONE and three lettered ones, U V W: LOUDER-ONLY, BOTH-SMALL and BOTH-LARGE **with the engine's ceiling** (the clip the engine could actually make, the
 *    string ducked about 2.2 dB; every other clip is rendered without the ceiling).
 * **No letter is used twice on the page** (I and O are skipped: they read as 1 and 0): C to W in order of the page.
 *
 * **LOUDER-ONLY is matched by the ear's weighting.** Its flat gain is BOTH-SMALL's A-weighted rise over THE PLAIN ONE, measured on the finished clips ([ArcoWarmthMeasure.AWeighting], the steady window), not the engine meter's: matched on
 * [Loudness.of] (a gentle 120 Hz low cut) it was 1.2 to 1.9 dB louder than BOTH-SMALL by ear on CELLO. The generator proves the match on every grid note (the flat clip's A-weighted rise is BOTH-SMALL's to [LOUD_MATCH_BAR]) and
 * prints how far apart the two clips' [Loudness.of] rises are then; the key carries both, and the whole-clip RMS rise.
 *
 * **The shuffle is fixed and hard-coded**, a pure function with no seed: [Spec.order] lists, for each group, the clip behind each letter in the letters' order.
 * ERHU C D E F G H J K: BLOOM, WEIGHT, BOTH-SMALL, BOTH-LARGE, REPEAT, WARMTH, BOTH-HALF, LOUDER-ONLY.
 * CELLO C3 L M N P Q R S T: WARMTH, BOTH-HALF, WEIGHT, LOUDER-ONLY, BOTH-LARGE, REPEAT, BOTH-SMALL, BLOOM.
 * CELLO F#2 U V W: BOTH-SMALL, BOTH-LARGE, LOUDER-ONLY.
 * The generator checks by itself ([checkShuffles]) that the repeat is never first or last, that no group is in order of strength (up or down, under two rankings), that no kind sits in the same slot in two groups, and that the
 * louder-only twin of BOTH-SMALL is not next to it in any group (slots 7 and 2, 3 and 6, 0 and 2); checked by hand against the three orders above as well.
 *
 * **The pairs.** The manifest's `pairs` field has one entry per group that holds both twins: `{"voice","a","b"}` with `a` and `b` the two letters, in a fixed order that does not say which is which (a is BOTH-SMALL in ERHU C5 and
 * CELLO F#2, LOUDER-ONLY in CELLO C3): ERHU E and K, CELLO P and S, CELLO U and W. The page asks which of the two sounds fuller; the key records which letter is which.
 *
 * **The in-run checks throw**, and a re-run starts by emptying the output folder and deleting the key, so a failed check never leaves a page of an earlier run behind (the page's own length is checked first, before any file is
 * written; the checks that need the written files run last, so a failure there leaves a partial folder: do not publish after a failure): the page resource is not the placeholder (at least [MIN_PAGE_CHARS] characters); the ruler's own
 * controls (including the A-weighting's against the standard's table); [ArcoWarmthCandidates.ceilingFailures], which ties the helper's ceiling to the engine's; the helper's proof (no shaping is [Arco.render] at BODY 0.5 to the sample on
 * all ten grid notes) and its three controls that must fail (a 0.01 dB shaping, a render at BODY 0.75, a gain 0.001 percent off); the repeat is bit-identical to the plain, rendered again from the bow up (and so are its two files);
 * every candidate is finite and the plain's length; the page's clips whose string at the plain's level peaks at 0.95 or more are exactly [EXPECTED_HEADROOM_CLIPS] and the clips the engine's ceiling acted on are exactly those; no two clips
 * but the repeat and the plain are the same; the LOUDER-ONLY clip's A-weighted rise is BOTH-SMALL's on every grid note; BLOOM reads at least [BLOOM_OVER_SMALL_MIN_DB] dB over BOTH-SMALL in 3k-8k on every note; and the brief's targets on the
 * whole grid: the top bands (1k-3k and 3k-8k) within -0.5 dB of the plain for WEIGHT, WARMTH, BOTH-HALF, BOTH-SMALL and BOTH-LARGE (and never below the plain for BLOOM), the shaping's low band where the filters say
 * ([ArcoWarmthMeasure.predicted]) and about the rung. The one clip the ceiling acted on (F#2's BOTH-LARGE) is exempt from the top-band target as heard and its band deltas are reported; the same target and the filters' prediction are
 * held on it with the ceiling's flat cut put back. The table is printed on `ARCO warmth` lines before the checks that can fail on it.
 *
 * **The bars, what R1e saw and why they can fail.** Top bands: the lowest delta of any clip but the ceilinged one and BLOOM was -0.0 dB (WEIGHT at C2, 3k-8k), the bar is -0.5; BLOOM's lowest was +0.7 (CELLO F#3, 1k-3k) and +1.7 (ERHU A5, 1k-3k), the
 * bar is [BLOOM_TOP_FLOOR_DB]; the ceilinged F#2 BOTH-LARGE reads -1.7 and -2.1 as heard (exempt) and +0.5 and +0.1 with the ceiling's cut put back (held to the bar). Filters: the largest measured-minus-predicted band was 0.01 dB, the bar is [PRED_BAR]; the
 * ceilinged clip's 80-300 reads +4.6 as heard against +6.8 predicted, so without the cut put back that bar would throw. Rung: BOTH-HALF +2.0 to +2.1 (CELLO, 80-300) and +2.0 to +2.2 (ERHU, 300-1k) against nominal +2.0 and a corridor of plus and minus [LIFT_HALF_BAND_DB];
 * BOTH-SMALL +3.9 to +4.2 and +4.0 to +4.5 against +4.0 and plus and minus [LIFT_BAND_DB]; BOTH-LARGE +6.8 to +7.3 and +6.9 to +7.7 against +7.0 (F#2's value with the ceiling's cut put back); a single element on the page's notes +1.9 to +2.6 (WEIGHT and WARMTH,
 * nominal +3.5, lowest allowed half of it). A fixed corner cannot follow the pitch, so a single element falls short on CELLO C4 (WEIGHT +0.9, WARMTH +1.6) and on ERHU D4, F5 and A5 (WEIGHT +1.5, +1.2, +0.6): reported, not thrown, and the generator's PRED agrees to 0.01 dB
 * that it is the filter and not the helper. BLOOM reads +2.35 to +2.45 dB (CELLO) and +2.34 to +2.58 (ERHU) over BOTH-SMALL in 3k-8k against the bar of +[BLOOM_OVER_SMALL_MIN_DB]; the control that must fail, the first page's BLOOM (3500 Hz, +1.5 dB), reads +0.77 to +0.85 and +0.75 to +0.96
 * on the same notes, under the bar. LOUDER-ONLY: the flat clip's A-weighted rise is BOTH-SMALL's to 0.000001 dB on every grid note (bar [LOUD_MATCH_BAR]; the whole-clip A-weighted rises differ by up to 0.10 dB); matched on [Loudness.of] instead (the first page's way) it would be 0.90 to 1.39 dB
 * (CELLO) and 0.17 to 0.42 dB (ERHU) off, which is what the generator's control requires to be over ten times the bar, so the bar can fail. With the match on the ear's weighting the two clips' [Loudness.of] rises are -1.39 to -0.90 dB apart on CELLO and -0.42 to -0.17 on ERHU (the flat clip is quieter on the engine's meter).
 */
object ArcoWarmthGenerator {

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val clips: List<Clip>)
    private class Section(val id: String, val display: String, val body: String, val readout: List<String>, val groups: List<Group>)

    private val CELLO = ArcoVoice.CELLO
    private val ERHU = ArcoVoice.ERHU

    /**
     * One group of the page: the [voice] at [tune], its clip ids starting [prefix] (no `#` in a file name), the [letters] of its lettered clips with [order] saying which clip each letter is (the fixed shuffle), and
     * [pairFirst], which of the two twins (BOTH-SMALL or LOUDER-ONLY) is `a` in the manifest's pair (fixed here, alternating, so the order of a pair does not say which is which).
     * THE PLAIN ONE is always the first clip and is not lettered.
     */
    private class Spec(
        val voice: ArcoVoice, val tune: Float, val note: String, val prefix: String, val label: String, val letters: String, val order: List<Kind>, val pairFirst: Kind,
    ) {
        fun letterOf(kind: Kind): Char = letters[order.indexOf(kind)]
        val hasPair: Boolean get() = Kind.LOUD in order && Kind.BOTH_SMALL in order
        val pairSecond: Kind get() = if (pairFirst == Kind.BOTH_SMALL) Kind.LOUD else Kind.BOTH_SMALL
    }

    private fun defaultTune(voice: ArcoVoice) = Arco.defaults(voice).getValue("TUNE")

    /** CELLO F#2's TUNE step: the one clip the engine's ceiling is run on is BOTH-LARGE here. */
    private const val F2_STEP = 6

    /** The page's groups, in the page's order: ERHU's, then CELLO's two. The shuffles are the ones the class KDoc documents. */
    private val SPECS = listOf(
        Spec(
            ERHU, defaultTune(ERHU), "C5", "c5", "ERHU, C5 (THE DEFAULT NOTE)", "CDEFGHJK",
            listOf(Kind.BLOOM, Kind.WEIGHT, Kind.BOTH_SMALL, Kind.BOTH_LARGE, Kind.REPEAT, Kind.WARMTH, Kind.BOTH_HALF, Kind.LOUD), Kind.BOTH_SMALL,
        ),
        Spec(
            CELLO, defaultTune(CELLO), "C3", "c3", "CELLO, C3 (THE DEFAULT NOTE)", "LMNPQRST",
            listOf(Kind.WARMTH, Kind.BOTH_HALF, Kind.WEIGHT, Kind.LOUD, Kind.BOTH_LARGE, Kind.REPEAT, Kind.BOTH_SMALL, Kind.BLOOM), Kind.LOUD,
        ),
        Spec(
            CELLO, ArcoBodyMeasure.tuneOf(CELLO, F2_STEP), "F#2", "f2", "CELLO, F#2 (A LOW NOTE)", "UVW",
            listOf(Kind.BOTH_SMALL, Kind.BOTH_LARGE, Kind.LOUD), Kind.BOTH_SMALL,
        ),
    )

    private val FULL_SET = setOf(Kind.REPEAT, Kind.LOUD, Kind.WEIGHT, Kind.WARMTH, Kind.BOTH_HALF, Kind.BOTH_SMALL, Kind.BOTH_LARGE, Kind.BLOOM)
    private val SHORT_SET = setOf(Kind.LOUD, Kind.BOTH_SMALL, Kind.BOTH_LARGE)

    /**
     * How strong a clip is, for the "no group in order of strength" check, under two rankings (a group in order under either fails). By dose: the repeat nothing, the flat gain, the single elements, BOTH-HALF, BOTH-SMALL,
     * BLOOM (BOTH-SMALL plus sparkle), BOTH-LARGE. By what is heard: the single elements and BOTH-HALF below the clips A-weighted-matched to BOTH-SMALL (the flat gain, BOTH-SMALL, BLOOM, all tied), BOTH-LARGE above.
     */
    private fun strengthByDose(kind: Kind): Int = when (kind) {
        Kind.REPEAT -> 0
        Kind.LOUD -> 1
        Kind.WEIGHT, Kind.WARMTH -> 2
        Kind.BOTH_HALF -> 3
        Kind.BOTH_SMALL -> 4
        Kind.BLOOM -> 5
        Kind.BOTH_LARGE -> 6
        else -> error("$kind is not a lettered clip")
    }

    private fun strengthByEar(kind: Kind): Int = when (kind) {
        Kind.REPEAT -> 0
        Kind.WEIGHT, Kind.WARMTH -> 1
        Kind.BOTH_HALF -> 2
        Kind.LOUD, Kind.BOTH_SMALL, Kind.BLOOM -> 3
        Kind.BOTH_LARGE -> 4
        else -> error("$kind is not a lettered clip")
    }

    private const val NOTE_CHECKED_PEAK = 0.95f
    private const val ABSENT = -1

    /** The page resource must be the real page: the placeholder is one line. R1e's page is about 39000 characters; the bar is 20000. */
    private const val MIN_PAGE_CHARS = 20_000

    /** The loudness every clip of a group is scaled by relative to THE PLAIN ONE: [AuditionLevel]'s own level for the plain (its 0.03), the same gain on every clip of the group. The generator checks the plain times it is [AuditionLevel.level]'s. */
    private const val AUDITION_LEVEL = 0.03f

    /** The bar of "the LOUDER-ONLY clip's A-weighted rise is BOTH-SMALL's", in dB. A flat gain moves every frequency by the same amount, so only float rounding is left (R1e saw at most 0.000001 dB). */
    private const val LOUD_MATCH_BAR = 0.01

    /** The bar of "the measured band is where the filters say it is" ([ArcoWarmthMeasure.predicted]), in dB: the vibrato moves a partial 0.6 percent and the shaping is a smooth filter; R1e saw 0.01 at most. */
    private const val PRED_BAR = 0.1

    /** BLOOM must read at least this many dB over BOTH-SMALL in 3k-8k on every note (the first R1e page, a 3500 Hz shelf of +1.5 dB, read only +0.75 to +0.96 on the grid: too little to tell). R1e saw +2.34 to +2.58, and +0.75 to +0.96 for the first page's BLOOM (the control that must fail). */
    private const val BLOOM_OVER_SMALL_MIN_DB = 1.5

    /** BLOOM's top bands may not read below the plain at all (the brief): a small allowance for what the ruler can resolve (its PRED agreement is 0.01 dB). */
    private const val BLOOM_TOP_FLOOR_DB = -0.05

    /**
     * The clips rendered with the engine's 0.99 ceiling: the one clip the engine could actually make is F#2's BOTH-LARGE (the string at the plain's level peaks at 1.277, x2.04 the plain's 0.625, so the ceiling takes 2.2 dB off the whole
     * clip). Every other clip, on the page and on the grid, is rendered without it: where one would need it ([ArcoWarmthCandidates.Rendered.ceilingWouldAct]) it is printed with the dB the ceiling would take off, and is in the key.
     */
    private fun engineCeilingFor(voice: ArcoVoice, step: Int, kind: Kind): Boolean = voice == CELLO && step == F2_STEP && kind == Kind.BOTH_LARGE

    /**
     * The page clips whose string at the plain's level peaks at [NOTE_CHECKED_PEAK] or more, "group prefix/candidate": R1e saw exactly one, F#2's BOTH-LARGE (the plain's peak there is 0.625, +6 dB per element lifts the peak
     * by x2.04 to 1.28, and the ceiling takes 2.2 dB off the whole clip), and it is the one clip rendered with the ceiling ([engineCeilingFor]). The generator holds the page to exactly this set, and the clips the ceiling acted on
     * to exactly it: a clip joining it, or this one leaving it, throws.
     */
    private val EXPECTED_HEADROOM_CLIPS = setOf("f2/BOTH-LARGE")

    private fun noteOf(voice: ArcoVoice, tune: Float) = ArcoBodyMeasure.noteName(voice, Arco.semitoneFor(voice, tune))

    private fun same(a: FloatArray, b: FloatArray) = a.contentEquals(b)

    private fun finite(x: FloatArray) = x.all { it.isFinite() }

    private fun macrosAt(tune: Float, body: Float? = null): Map<String, Float> = if (body == null) mapOf("TUNE" to tune) else mapOf("TUNE" to tune, "BODY" to body)

    // ---- the grid: the helper's proof, the candidates and the ruler ---------------------------------

    /** One grid note's work: the readings of every shaped candidate and of the flat-gain control, and the flat gain. */
    private fun gridNote(voice: ArcoVoice, step: Int): List<ArcoWarmthMeasure.Reading> {
        val tune = ArcoBodyMeasure.tuneOf(voice, step)
        val f0 = ArcoBodyMeasure.f0Of(voice, step)
        val where = "$voice ${ArcoBodyMeasure.noteName(voice, step)}"
        val note = ArcoWarmthCandidates.note(voice, tune)
        val plain = note.plain.snip.samples

        // The helper's proof: no shaping is Arco.render at BODY 0.5, to the sample (ArcoBodyMeasure.viaArco is Arco.render at the defaults, TUNE and BODY).
        check(same(plain, ArcoBodyMeasure.viaArco(voice, step, 0.5f))) { "$where: the helper's plain is not Arco.render at BODY 0.5" }
        // Three controls that must fail: the same comparison against something that is not the plain.
        val tiny = ArcoWarmthCandidates.render(note, listOf(ArcoWarmthCandidates.Stage(ArcoWarmthCandidates.Part.LOW_SHELF, 200f, 0.01f))).snip.samples
        check(!same(tiny, ArcoBodyMeasure.viaArco(voice, step, 0.5f))) { "$where: control failed to fail: a 0.01 dB shelf is bit-identical to the plain, so the proof cannot see a shaping" }
        check(!same(plain, ArcoBodyMeasure.viaArco(voice, step, 0.75f))) { "$where: control failed to fail: the helper's plain is bit-identical to Arco.render at BODY 0.75" }
        val offGain = ArcoWarmthCandidates.Note(voice, tune, note.rate, note.boxed, note.gain * 1.00001f)
        check(!same(ArcoWarmthCandidates.render(offGain, emptyList()).snip.samples, ArcoBodyMeasure.viaArco(voice, step, 0.5f))) {
            "$where: control failed to fail: a gain 0.001 percent off is bit-identical to the plain, so the proof cannot see the gain"
        }

        val base = ArcoWarmthMeasure.Base(plain, f0)
        val readings = ArrayList<ArcoWarmthMeasure.Reading>()
        val rendered = HashMap<Kind, ArcoWarmthCandidates.Rendered>()
        for (kind in ArcoWarmthCandidates.SHAPED) {
            val r = ArcoWarmthCandidates.render(note, kind, engineCeilingFor(voice, step, kind))
            rendered[kind] = r
            check(finite(r.snip.samples)) { "$where ${kind.key}: a sample is not finite" }
            check(r.snip.samples.size == plain.size) { "$where ${kind.key}: ${r.snip.samples.size} samples, THE PLAIN ONE ${plain.size}" }
            readings += ArcoWarmthMeasure.Reading(voice, step, kind, ArcoWarmthMeasure.compare(r.snip.samples, base), ArcoWarmthMeasure.predicted(base, voice, kind), base.peak, r)
        }
        // A control that must fail: the first page's BLOOM (a 3500 Hz shelf of +1.5 dB on BOTH-SMALL) must read under the bar over BOTH-SMALL in 3k-8k, so the bar can tell a real test of sparkle from an inert one.
        val smallReading = readings.first { it.kind == Kind.BOTH_SMALL }
        val firstBloom = ArcoWarmthCandidates.render(
            note, ArcoWarmthCandidates.shapeOf(Kind.BOTH_SMALL, voice) + ArcoWarmthCandidates.Stage(ArcoWarmthCandidates.Part.HIGH_SHELF, 3500f, 1.5f), engineCeiling = false,
        )
        val top = ArcoWarmthMeasure.BANDS_HZ.size - 1
        firstBloomOver["$voice $step"] = ArcoWarmthMeasure.compare(firstBloom.snip.samples, base).bands[top]!! - smallReading.numbers.bands[top]!!
        val gainDb = ArcoWarmthCandidates.louderGainDb(note, rendered.getValue(Kind.BOTH_SMALL))
        val loud = ArcoWarmthCandidates.renderLouder(note, gainDb, engineCeilingFor(voice, step, Kind.LOUD))
        check(finite(loud.snip.samples) && loud.snip.samples.size == plain.size) { "$where LOUDER-ONLY: not finite or not the plain's length" }
        readings += ArcoWarmthMeasure.Reading(voice, step, Kind.LOUD, ArcoWarmthMeasure.compare(loud.snip.samples, base), null, base.peak, loud, gainDb)
        return readings
    }

    /** The first page's BLOOM over BOTH-SMALL in 3k-8k, per "voice step", filled by [gridNote]: the control that the bloom bar can fail. */
    private val firstBloomOver = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private fun gridReadings(): List<ArcoWarmthMeasure.Reading> {
        val notes = ArcoBodyMeasure.GRID.flatMap { (voice, steps) -> steps.map { voice to it } }
        return notes.parallelStream().map { (voice, step) -> gridNote(voice, step) }.toList().flatten()
    }

    /** What each candidate's "low band lifts about what the rung says" is read in: the band that holds the lowest partials the shaping is built for. */
    private fun primaryBand(voice: ArcoVoice, kind: Kind): Int = when {
        voice == ERHU -> 1
        kind == Kind.WARMTH -> 1
        else -> 0
    }

    /** The rung's nominal rise at the loudest partials, in dB, the sum where the two skirts overlap: +2.0 half, +4.0 small, +7.0 large (the physics scout's), +3.5 for an element alone. */
    private fun nominalDb(kind: Kind): Double = when (kind) {
        Kind.WEIGHT, Kind.WARMTH -> ArcoWarmthCandidates.SMALL_DB.toDouble()
        Kind.BOTH_HALF -> 2.0
        Kind.BOTH_SMALL, Kind.BLOOM -> 4.0
        Kind.BOTH_LARGE -> 7.0
        else -> error("$kind has no rung")
    }

    /**
     * The corridor of "about what the rung says" (guesses, listening): BOTH-SMALL, BOTH-LARGE and BLOOM within [LIFT_BAND_DB] of their nominal rise either way, BOTH-HALF within [LIFT_HALF_BAND_DB] (its nominal is
     * half as big); a single element from [LIFT_SINGLE_LOW_SHARE] of its nominal rise (its skirt, not its centre, lands on most partials) to [LIFT_BAND_DB] above it.
     */
    private const val LIFT_BAND_DB = 1.5
    private const val LIFT_HALF_BAND_DB = 1.0
    private const val LIFT_SINGLE_LOW_SHARE = 0.5

    /** The page's notes as (voice, grid step): the rung of a single element must hold there (it throws); on the grid's other notes a shortfall is reported (a fixed corner cannot follow the pitch). */
    private val PAGE_NOTES: Set<Pair<ArcoVoice, Int>> = SPECS.map { it.voice to Arco.semitoneFor(it.voice, it.tune) }.toSet()

    /** What the targets found: [problems] throw, [reported] are facts the lead must read (the engine's headroom, the single elements on grid notes the page does not play). */
    private class Findings(val problems: MutableList<String> = ArrayList(), val reported: MutableList<String> = ArrayList())

    private fun bandsText(a: Array<Double?>, add: Double = 0.0): String =
        ArcoWarmthMeasure.BAND_LABELS.indices.joinToString(" ") { "${ArcoWarmthMeasure.BAND_LABELS[it]} ${a[it]?.let { v -> ArcoWarmthMeasure.s1(v + add) } ?: "."}" }

    /**
     * The brief's targets over the whole grid, printed with their worst value. Thrown (in [Findings.problems]): the top bands within [ArcoWarmthMeasure.TOP_FLOOR_DB] of the plain (BLOOM within [BLOOM_TOP_FLOOR_DB]) and the shaping
     * where the filters say, for WEIGHT, WARMTH, BOTH-HALF, BOTH-SMALL, BOTH-LARGE and BLOOM on every note; the rung corridor ([primaryBand]) for the BOTHs and BLOOM on every note and for WEIGHT and WARMTH on the page's notes; BLOOM
     * over BOTH-SMALL in 3k-8k; the flat gain is BOTH-SMALL's A-weighted rise. The clip the ceiling acted on is exempt from the top-band target as heard (its deltas are reported) and held to it with the ceiling's cut put back, as the
     * filters' prediction and the rung are. Reported only ([Findings.reported]): the single elements' rung on the grid's other notes, and every clip whose peak, with the string at the plain's level, passes [NOTE_CHECKED_PEAK].
     */
    private fun checkTargets(readings: List<ArcoWarmthMeasure.Reading>): Findings {
        val found = Findings()
        val problems = found.problems
        val m = ArcoWarmthMeasure
        for (voice in ArcoVoice.entries) for (kind in ArcoWarmthCandidates.SHAPED) {
            val rs = readings.filter { it.voice == voice && it.kind == kind }
            val tag = "${voice.name.take(1)} ${kind.key}"
            // 1. the top is not ducked (on the clip with the ceiling's cut put back, where the ceiling acted)
            val floor = if (kind == Kind.BLOOM) BLOOM_TOP_FLOOR_DB else m.TOP_FLOOR_DB
            var worstTop = Double.MAX_VALUE
            var worstTopAt = ""
            for (r in rs) for (b in m.TOP_BANDS) r.numbers.bands[b]?.let { v ->
                val back = v + r.ceilingBackDb
                if (back < worstTop) { worstTop = back; worstTopAt = "${r.name} ${m.BAND_LABELS[b]}" }
            }
            val topOk = worstTop >= floor
            println("ARCO warmth TARGET top bands $tag: lowest delta ${m.s1(worstTop)} dB at $worstTopAt (bar ${m.f2(floor)}): ${if (topOk) "OK" else "MISS"}")
            if (!topOk) problems += "$tag: a top band is ${m.f2(worstTop)} dB at $worstTopAt, under ${m.f2(floor)}"
            for (r in rs.filter { it.ceilingActed }) {
                val text = "$tag ${r.name}: the engine's ceiling ACTED (the string needed a peak of ${m.f3(r.peakBefore.toDouble())}; the whole clip is ${m.s1(r.duckDb)} dB): its top-band target is EXEMPT as heard. " +
                    "Band deltas as heard: ${bandsText(r.numbers.bands)} dB; with the ceiling's cut put back: ${bandsText(r.numbers.bands, r.ceilingBackDb)} dB; " +
                    "A ${m.s2(r.numbers.aRise)}, Loudness.of ${m.s2(r.numbers.loudRise)}, RMS ${m.s2(r.numbers.rmsRise)} dB"
                println("ARCO warmth TARGET ceilinged $text")
                found.reported += text
            }
            // 2. the shaping is where the filters say (the ceiling's flat cut put back)
            var worstPred = 0.0
            var worstPredAt = ""
            for (r in rs) for (b in m.BANDS_HZ.indices) {
                val meas = (r.numbers.bands[b] ?: continue) + r.ceilingBackDb
                val pred = r.predicted!![b] ?: continue
                if (abs(meas - pred) > worstPred) { worstPred = abs(meas - pred); worstPredAt = "${r.name} ${m.BAND_LABELS[b]} measured ${m.s1(meas)} predicted ${m.s1(pred)}" }
            }
            val predOk = worstPred <= PRED_BAR
            println("ARCO warmth TARGET filters $tag: largest measured-minus-predicted ${m.f2(worstPred)} dB at $worstPredAt (bar ${m.f2(PRED_BAR)}): ${if (predOk) "OK" else "MISS"}")
            if (!predOk) problems += "$tag: measured differs from the filters' prediction by ${m.f2(worstPred)} dB at $worstPredAt"
            // 3. the rung
            val band = primaryBand(voice, kind)
            val single = kind == Kind.WEIGHT || kind == Kind.WARMTH
            val half = if (kind == Kind.BOTH_HALF) LIFT_HALF_BAND_DB else LIFT_BAND_DB
            val lo = if (single) LIFT_SINGLE_LOW_SHARE * nominalDb(kind) else nominalDb(kind) - half
            val hi = nominalDb(kind) + half
            val lifts = rs.map { it to it.numbers.bands[band] }.filter { it.second != null }.map { it.first to it.second!! + it.first.ceilingBackDb }
            val minLift = lifts.minByOrNull { it.second }!!
            val maxLift = lifts.maxByOrNull { it.second }!!
            val outside = lifts.filter { it.second < lo || it.second > hi }
            val thrown = outside.filter { !single || (voice to it.first.step) in PAGE_NOTES }
            val onlyReported = outside - thrown.toSet()
            println(
                "ARCO warmth TARGET rung $tag: ${m.BAND_LABELS[band]} Hz band lifts ${m.s1(minLift.second)} (${minLift.first.name}) to ${m.s1(maxLift.second)} (${maxLift.first.name}) dB; " +
                    "corridor ${m.f1(lo)} to ${m.f1(hi)} (nominal ${m.s1(nominalDb(kind))}): ${if (outside.isEmpty()) "OK" else if (thrown.isEmpty()) "SHORT on grid-only notes, reported" else "MISS"}" +
                    (if (outside.isEmpty()) "" else "; outside: ${outside.joinToString(", ") { "${it.first.name} ${m.s1(it.second)}" }}"),
            )
            if (thrown.isNotEmpty()) problems += "$tag: the ${m.BAND_LABELS[band]} band lifts ${thrown.joinToString(", ") { "${m.f2(it.second)} at ${it.first.name}" }}, outside ${m.f1(lo)} to ${m.f1(hi)}"
            if (onlyReported.isNotEmpty()) found.reported += "$tag: the ${m.BAND_LABELS[band]} band lifts only ${onlyReported.joinToString(", ") { "${m.s1(it.second)} at ${it.first.name}" }} (corridor ${m.f1(lo)} to ${m.f1(hi)}); the corner is fixed, the pitch is not"
        }
        // 3b. BLOOM is a real test of sparkle: 3k-8k at least BLOOM_OVER_SMALL_MIN_DB over BOTH-SMALL on every note
        for (voice in ArcoVoice.entries) {
            val top = m.BANDS_HZ.size - 1
            val rows = ArcoBodyMeasure.GRID.getValue(voice).map { step ->
                val bloom = readings.first { it.voice == voice && it.step == step && it.kind == Kind.BLOOM }
                val small = readings.first { it.voice == voice && it.step == step && it.kind == Kind.BOTH_SMALL }
                Triple(bloom.name, bloom.numbers.bands[top]!! - small.numbers.bands[top]!!, bloom.numbers.bands[top - 1]!! - small.numbers.bands[top - 1]!!)
            }
            val worst = rows.minByOrNull { it.second }!!
            val best = rows.maxByOrNull { it.second }!!
            val ok = worst.second >= BLOOM_OVER_SMALL_MIN_DB
            println(
                "ARCO warmth TARGET bloom ${voice.name.take(1)}: 3k-8k over BOTH-SMALL ${rows.joinToString(" ") { "${it.first} ${m.s2(it.second)}" }} dB (lowest ${m.s2(worst.second)} at ${worst.first}, highest ${m.s2(best.second)}; " +
                    "bar +${m.f2(BLOOM_OVER_SMALL_MIN_DB)}): ${if (ok) "OK" else "MISS"}; 1k-3k over BOTH-SMALL ${rows.joinToString(" ") { "${it.first} ${m.s2(it.third)}" }} dB",
            )
            if (!ok) problems += "${voice.name.take(1)} BLOOM: 3k-8k is only ${m.f2(worst.second)} dB over BOTH-SMALL at ${worst.first}, under ${m.f2(BLOOM_OVER_SMALL_MIN_DB)}"
            val first = ArcoBodyMeasure.GRID.getValue(voice).map { firstBloomOver.getValue("$voice $it") }
            val firstFails = first.all { it < BLOOM_OVER_SMALL_MIN_DB }
            println(
                "ARCO warmth TARGET bloom ${voice.name.take(1)} control: the first page's BLOOM (a 3500 Hz shelf of +1.5 dB) reads ${m.s2(first.min())} to ${m.s2(first.max())} dB over BOTH-SMALL in 3k-8k on the same notes, " +
                    "under the bar +${m.f2(BLOOM_OVER_SMALL_MIN_DB)} as it must be: ${if (firstFails) "OK" else "MISS"}",
            )
            if (!firstFails) problems += "${voice.name.take(1)} BLOOM control: the first page's BLOOM reads ${m.s2(first.max())} dB over BOTH-SMALL, not under the bar: the bloom bar cannot fail"
        }
        // 4. the flat-gain control is BOTH-SMALL's A-weighted rise; the engine meter's rises then are this far apart; the peaks
        var worstMatch = 0.0
        for (voice in ArcoVoice.entries) {
            val loudGaps = ArrayList<Double>()
            val rmsGaps = ArrayList<Double>()
            val oldWayGaps = ArrayList<Double>()
            for (step in ArcoBodyMeasure.GRID.getValue(voice)) {
                val both = readings.first { it.voice == voice && it.step == step && it.kind == Kind.BOTH_SMALL }
                val loud = readings.first { it.voice == voice && it.step == step && it.kind == Kind.LOUD }
                val gap = abs(loud.numbers.aRise - both.numbers.aRise)
                worstMatch = maxOf(worstMatch, gap)
                val gapWhole = abs(loud.numbers.aRiseWhole - both.numbers.aRiseWhole)
                loudGaps += loud.numbers.loudRise - both.numbers.loudRise
                rmsGaps += loud.numbers.rmsRise - both.numbers.rmsRise
                println(
                    "ARCO warmth TARGET louder-only ${voice.name.take(1)} ${both.name}: flat gain ${m.s2(loud.louderGainDb!!)} dB; A-weighted rise LOUD ${m.s2(loud.numbers.aRise)} vs BOTH-SMALL ${m.s2(both.numbers.aRise)} " +
                        "(gap ${m.f3(gap)}, bar ${m.f2(LOUD_MATCH_BAR)}; whole clip ${m.s2(loud.numbers.aRiseWhole)} vs ${m.s2(both.numbers.aRiseWhole)}, gap ${m.f3(gapWhole)}); " +
                        "Loudness.of rise ${m.s2(loud.numbers.loudRise)} vs ${m.s2(both.numbers.loudRise)} (LOUD minus BOTH-SMALL ${m.s2(loudGaps.last())}); RMS rise ${m.s2(loud.numbers.rmsRise)} vs ${m.s2(both.numbers.rmsRise)}: " +
                        if (gap <= LOUD_MATCH_BAR) "OK" else "MISS",
                )
                if (gap > LOUD_MATCH_BAR) problems += "${voice.name.take(1)} ${both.name}: the flat gain's A-weighted rise is ${m.f3(gap)} dB off BOTH-SMALL's"
                // A control that must fail: a flat gain matched on Loudness.of (the first page's way) is a flat clip whose A-weighted rise is that gain, so it would miss BOTH-SMALL's by this much: far over the bar.
                val oldWay = both.numbers.loudRise - both.numbers.aRise
                oldWayGaps += oldWay
                if (abs(oldWay) < 10 * LOUD_MATCH_BAR) problems += "${voice.name.take(1)} ${both.name}: matched on Loudness.of the flat clip would be only ${m.f3(abs(oldWay))} dB off BOTH-SMALL's A-weighted rise: the match bar cannot show the defect it exists to remove"
            }
            println(
                "ARCO warmth TARGET louder-only ${voice.name.take(1)} SUMMARY: with the flat gain matched on the A-weighted rise, LOUD minus BOTH-SMALL is ${m.s2(loudGaps.min())} to ${m.s2(loudGaps.max())} dB on Loudness.of and " +
                    "${m.s2(rmsGaps.min())} to ${m.s2(rmsGaps.max())} dB on the whole-clip RMS, over the five notes; the control: matched on Loudness.of instead (the first page's way) the flat clip would be " +
                    "${m.f2(oldWayGaps.minOf { abs(it) })} to ${m.f2(oldWayGaps.maxOf { abs(it) })} dB off BOTH-SMALL's A-weighted rise (bar ${m.f2(LOUD_MATCH_BAR)})",
            )
        }
        println("ARCO warmth TARGET louder-only: the largest A-weighted gap between the flat clip and BOTH-SMALL over the ten grid notes is ${"%.6f".format(Locale.ROOT, worstMatch)} dB (bar ${m.f2(LOUD_MATCH_BAR)})")
        for (r in readings) if (!r.numbers.finite()) problems += "${r.voice.name.take(1)} ${r.kind.key} ${r.name}: a ruler number is not finite"
        // 5. the headroom: what lift the plain's peak allows, and every clip that passes the bar with the string at the plain's level (reported, not thrown: the engine step has to give the headroom or back the rung off)
        for (voice in ArcoVoice.entries) for (step in ArcoBodyMeasure.GRID.getValue(voice)) {
            val rs = readings.filter { it.voice == voice && it.step == step }
            val plainPeak = rs.first().plainPeak.toDouble()
            fun rise(kind: Kind) = rs.first { it.kind == kind }.let { 20.0 * log10(it.peakBefore / plainPeak) }
            println(
                "ARCO warmth HEADROOM ${voice.name.take(1)} ${rs.first().name}: the plain's peak ${m.f3(plainPeak)} allows ${m.s1(20.0 * log10(NOTE_CHECKED_PEAK / plainPeak))} dB before ${m.f2(NOTE_CHECKED_PEAK.toDouble())} " +
                    "(${m.s1(20.0 * log10(ArcoWarmthCandidates.CEILING / plainPeak))} before the ceiling); the peak (the string at the plain's level) rises by BOTH-HALF ${m.s1(rise(Kind.BOTH_HALF))}, BOTH-SMALL ${m.s1(rise(Kind.BOTH_SMALL))}, " +
                    "BOTH-LARGE ${m.s1(rise(Kind.BOTH_LARGE))}, BLOOM ${m.s1(rise(Kind.BLOOM))}, louder-only ${m.s1(rise(Kind.LOUD))} dB",
            )
        }
        for (r in readings) if (r.peakBefore >= NOTE_CHECKED_PEAK) {
            val text = "${r.voice.name.take(1)} ${r.kind.key} ${r.name}: peak ${m.f3(r.peakBefore.toDouble())} with the string at the plain's level (plain ${m.f3(r.plainPeak.toDouble())}), " +
                if (r.ceilingActed) "the engine's ceiling ACTED (this clip is rendered with it) and took ${m.f1(r.ceilingBackDb)} dB off the whole clip"
                else if (r.ceilingWouldAct) "the engine's ceiling WOULD ACT and take ${m.f1(-r.duckDb)} dB off the whole clip (rendered without it)"
                else "the ceiling would not act (under ${m.f2(ArcoWarmthCandidates.CEILING.toDouble())}) but the bar is ${m.f2(NOTE_CHECKED_PEAK.toDouble())}"
            println("ARCO warmth TARGET peak $text: MISS (reported)")
            found.reported += text
        }
        println("ARCO warmth TARGET peak over the ${readings.size} grid renders: largest peak before any ceiling ${m.f3(readings.maxOf { it.peakBefore }.toDouble())} (bar ${m.f2(NOTE_CHECKED_PEAK.toDouble())}); the plain's largest ${m.f3(readings.maxOf { it.plainPeak }.toDouble())}")
        return found
    }

    // ---- the page's clips ---------------------------------------------------------------

    private class Built(val id: String, val name: String, val kind: Kind?, val rendered: ArcoWarmthCandidates.Rendered, val reading: ArcoWarmthMeasure.Numbers?, val predicted: Array<Double?>?)
    private class BuiltGroup(val spec: Spec, val note: String, val gain: Float, val clips: List<Built>, val louderGainDb: Double)

    /**
     * The shuffles are what the class KDoc says: a permutation of the group's clips, every letter its own across the page (C to W in order, I and O skipped), the repeat neither first nor last, no group in order of strength under
     * either ranking, no clip in the same slot in two groups, the LOUDER-ONLY twin of BOTH-SMALL at least two slots from it, and the pairs a fixed order that alternates which twin is `a`. Prints each group's order and what held.
     */
    private fun checkShuffles() {
        val allLetters = SPECS.joinToString("") { it.letters }
        val alphabet = ('C'..'Z').filter { it != 'I' && it != 'O' }.joinToString("")
        check(allLetters.toSet().size == allLetters.length && 'I' !in allLetters && 'O' !in allLetters) { "the letters are not unique across the page (or use I or O): $allLetters" }
        check(allLetters == alphabet.take(allLetters.length)) { "the letters are not C to W in order of the page: $allLetters" }
        for (spec in SPECS) {
            val label = spec.label
            check(spec.letters.length == spec.order.size) { "$label: letters ${spec.letters} do not match the shuffle" }
            val wanted = if (spec.order.size == SHORT_SET.size) SHORT_SET else FULL_SET
            check(spec.order.size == wanted.size && spec.order.toSet() == wanted) { "$label: the shuffle ${spec.order} is not the clips the group holds ($wanted)" }
            val at = spec.order.indexOf(Kind.REPEAT)
            check(at == ABSENT || (at > 0 && at < spec.order.size - 1)) { "$label: the repeat is first or last" }
            val dose = spec.order.map { strengthByDose(it) }
            val ear = spec.order.map { strengthByEar(it) }
            for ((name, ranks) in listOf("by dose" to dose, "by ear" to ear)) {
                check(!ranks.zipWithNext().all { (a, b) -> a <= b } && !ranks.zipWithNext().all { (a, b) -> a >= b }) { "$label: the group is in order of strength $name ($ranks)" }
            }
            val twinGap = abs(spec.order.indexOf(Kind.LOUD) - spec.order.indexOf(Kind.BOTH_SMALL))
            check(twinGap >= 2) { "$label: LOUDER-ONLY and BOTH-SMALL are adjacent (slots ${spec.order.indexOf(Kind.LOUD)} and ${spec.order.indexOf(Kind.BOTH_SMALL)})" }
            check(spec.pairFirst == Kind.BOTH_SMALL || spec.pairFirst == Kind.LOUD) { "$label: the pair's first is ${spec.pairFirst}" }
            println(
                "ARCO warmth SHUFFLE $label: ${spec.letters.toList().zip(spec.order).joinToString(" ") { "${it.first}=${it.second.key}" }}; repeat at slot ${if (at == ABSENT) "none" else "$at of 0..${spec.order.size - 1}"}; " +
                    "ranks by dose $dose, by ear $ear (neither monotone); LOUDER-ONLY at slot ${spec.order.indexOf(Kind.LOUD)}, BOTH-SMALL at slot ${spec.order.indexOf(Kind.BOTH_SMALL)} (${twinGap} apart): OK",
            )
        }
        for (i in SPECS.indices) for (j in i + 1 until SPECS.size) {
            for (slot in 0 until minOf(SPECS[i].order.size, SPECS[j].order.size)) {
                check(SPECS[i].order[slot] != SPECS[j].order[slot]) { "${SPECS[i].label} and ${SPECS[j].label} both put ${SPECS[i].order[slot]} in slot $slot" }
            }
        }
        println("ARCO warmth SHUFFLE no kind is in the same slot in two groups (${SPECS.size} groups, every slot compared): OK")
        val pairSpecs = SPECS.filter { it.hasPair }
        check(pairSpecs.size == SPECS.size) { "every group holds both twins, so every group has a pair" }
        check(pairSpecs.zipWithNext().all { (x, y) -> x.pairFirst != y.pairFirst }) { "the pairs do not alternate which twin is a: ${pairSpecs.map { it.pairFirst }}" }
        println("ARCO warmth PAIRS ${pairSpecs.joinToString("; ") { "${it.voice.name} ${it.note}: a=${it.letterOf(it.pairFirst)} (${it.pairFirst.key}) b=${it.letterOf(it.pairSecond)} (${it.pairSecond.key})" }}; the first twin alternates: OK")
    }

    private fun build(spec: Spec): BuiltGroup {
        val voice = spec.voice
        val macros = macrosAt(spec.tune)
        val step = Arco.semitoneFor(voice, spec.tune)
        val note = ArcoWarmthCandidates.note(voice, spec.tune)
        val plain = note.plain
        val base = ArcoWarmthMeasure.Base(plain.snip.samples, ArcoBodyMeasure.f0Of(voice, step))
        // THE PLAIN ONE is the engine's own at BODY 0.5, at this note and, where the note is the voice's default, at the no-macro default render.
        check(same(plain.snip.samples, Arco.render(voice, macrosAt(spec.tune, 0.5f)).samples)) { "${spec.label}: THE PLAIN ONE is not Arco.render at BODY 0.5" }
        if (spec.tune == defaultTune(voice)) check(same(plain.snip.samples, Arco.render(voice).samples)) { "${spec.label}: THE PLAIN ONE is not the engine's default render" }
        // The repeat is the plain rendered again from the bow up: it must be bit-identical.
        val repeat = ArcoWarmthCandidates.note(voice, spec.tune).plain
        check(same(repeat.snip.samples, plain.snip.samples)) { "${spec.label}: the repeat is not bit-identical to THE PLAIN ONE" }

        val both = ArcoWarmthCandidates.render(note, Kind.BOTH_SMALL, engineCeilingFor(voice, step, Kind.BOTH_SMALL))
        val gainDb = ArcoWarmthCandidates.louderGainDb(note, both)
        val clips = ArrayList<Built>()
        clips += Built("${spec.prefix}_plain", "THE PLAIN ONE", Kind.PLAIN, plain, null, null)
        for ((i, kind) in spec.order.withIndex()) {
            val rendered = when (kind) {
                Kind.REPEAT -> repeat
                Kind.LOUD -> ArcoWarmthCandidates.renderLouder(note, gainDb, engineCeilingFor(voice, step, kind))
                else -> ArcoWarmthCandidates.render(note, kind, engineCeilingFor(voice, step, kind))
            }
            val predicted = if (kind in ArcoWarmthCandidates.SHAPED) ArcoWarmthMeasure.predicted(base, voice, kind) else null
            clips += Built("${spec.prefix}_${spec.letters[i].lowercaseChar()}", "CLIP ${spec.letters[i]}", kind, rendered, ArcoWarmthMeasure.compare(rendered.snip.samples, base), predicted)
        }
        // The page's gain: the one that puts THE PLAIN ONE at AuditionLevel's loudness, the same on every clip, so no clip is levelled to the plain.
        val gain = AUDITION_LEVEL / Loudness.of(plain.snip).coerceAtLeast(1e-9f)
        check(same(FloatArray(plain.snip.samples.size) { plain.snip.samples[it] * gain }, AuditionLevel.level(plain.snip).samples)) { "${spec.label}: the page's gain is not AuditionLevel's gain for THE PLAIN ONE" }
        return BuiltGroup(spec, noteOf(voice, spec.tune), gain, clips, gainDb)
    }

    /**
     * Every clip is finite and THE PLAIN ONE's length; the clips whose string at the plain's level peaks at [NOTE_CHECKED_PEAK] or more are exactly [EXPECTED_HEADROOM_CLIPS], and the clips the engine's ceiling acted on are exactly
     * those; no two clips are the same except the repeat and THE PLAIN ONE; the LOUDER-ONLY clip's A-weighted rise is BOTH-SMALL's. The peak at the page's level is checked where the files are written.
     */
    private fun checkClips(groups: List<BuiltGroup>) {
        val needing = ArrayList<String>()
        val acted = ArrayList<String>()
        for (g in groups) {
            val plain = g.clips.first { it.kind == Kind.PLAIN }.rendered.snip.samples
            for (c in g.clips) {
                val where = "${g.spec.label} ${c.id}"
                val x = c.rendered.snip.samples
                check(finite(x)) { "$where has a sample that is not finite" }
                check(x.size == plain.size) { "$where is ${x.size} samples, THE PLAIN ONE ${plain.size}" }
                if (c.rendered.ceilingActed) acted += "${g.spec.prefix}/${c.kind!!.key}"
                if (c.rendered.peakBeforeCeiling >= NOTE_CHECKED_PEAK) {
                    needing += "${g.spec.prefix}/${c.kind!!.key}"
                    println(
                        "ARCO warmth HEADROOM page clip ${g.spec.prefix}/${c.id} (${c.kind.key}): peak ${ArcoWarmthMeasure.f3(c.rendered.peakBeforeCeiling.toDouble())} with the string at the plain's level, the engine's ceiling " +
                            if (c.rendered.ceilingActed) "ACTED and took ${ArcoWarmthMeasure.f1(c.rendered.tookOffDb)} dB off the whole clip (this clip is rendered with it: the clip the engine could make)"
                            else if (c.rendered.ceilingWouldAct) "would act and take ${ArcoWarmthMeasure.f1(-c.rendered.duckDb)} dB off the whole clip; rendered without it" else "would not act; rendered without it",
                    )
                }
            }
            for (i in g.clips.indices) for (j in i + 1 until g.clips.size) {
                val a = g.clips[i]
                val b = g.clips[j]
                val identical = same(a.rendered.snip.samples, b.rendered.snip.samples)
                if (setOf(a.kind, b.kind) == setOf(Kind.PLAIN, Kind.REPEAT)) check(identical) { "${g.spec.label}: ${a.id} and ${b.id} (the repeat) differ" }
                else check(!identical) { "${g.spec.label}: ${a.id} and ${b.id} are the same clip" }
            }
            val small = g.clips.firstOrNull { it.kind == Kind.BOTH_SMALL }
            val loud = g.clips.firstOrNull { it.kind == Kind.LOUD }
            if (small != null && loud != null) {
                val gap = abs(loud.reading!!.aRise - small.reading!!.aRise)
                println(
                    "ARCO warmth LOUD MATCH page ${g.spec.label}: A-weighted rise LOUD ${ArcoWarmthMeasure.s2(loud.reading.aRise)} vs BOTH-SMALL ${ArcoWarmthMeasure.s2(small.reading.aRise)} (gap ${ArcoWarmthMeasure.f3(gap)}, bar ${ArcoWarmthMeasure.f2(LOUD_MATCH_BAR)}); " +
                        "Loudness.of rise ${ArcoWarmthMeasure.s2(loud.reading.loudRise)} vs ${ArcoWarmthMeasure.s2(small.reading.loudRise)}; RMS rise ${ArcoWarmthMeasure.s2(loud.reading.rmsRise)} vs ${ArcoWarmthMeasure.s2(small.reading.rmsRise)}",
                )
                check(gap <= LOUD_MATCH_BAR) { "${g.spec.label}: the flat clip's A-weighted rise is ${ArcoWarmthMeasure.f3(gap)} dB off BOTH-SMALL's" }
            }
        }
        check(needing.toSet() == EXPECTED_HEADROOM_CLIPS) { "the page clips whose string needs the engine's ceiling are $needing, not the expected $EXPECTED_HEADROOM_CLIPS" }
        check(acted.toSet() == EXPECTED_HEADROOM_CLIPS) { "the page clips the engine's ceiling acted on are $acted, not the expected $EXPECTED_HEADROOM_CLIPS" }
    }

    // ---- main ---------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/arco-warmth")
        // The key is a sibling of the published folder, so publishing the folder whole cannot publish it. A re-run starts from nothing: no clip, manifest or page of an earlier run survives a failed check.
        val keyFile = File(root.absoluteFile.parentFile, root.name + "-key.json")
        root.deleteRecursively()
        keyFile.delete()

        // The page must be the real one: a placeholder is one line. This throws before any file is written (the folder is already empty).
        val pageText = (ArcoWarmthGenerator::class.java.getResourceAsStream("/audition/arco-warmth.html")
            ?: error("the warmth page is missing from synth/src/test/resources/audition/")).use { it.readBytes() }.toString(Charsets.UTF_8)
        check(pageText.length >= MIN_PAGE_CHARS) { "the page is only ${pageText.length} characters (under $MIN_PAGE_CHARS): it is the placeholder, not the real page, and nothing was written" }
        root.mkdirs()

        for (spec in SPECS) check(noteOf(spec.voice, spec.tune) == spec.note) { "${spec.label} is on ${noteOf(spec.voice, spec.tune)}" }
        checkShuffles()

        // The ruler's own controls, on CELLO C3's plain.
        val c3 = ArcoBodyMeasure.viaArco(CELLO, 12, 0.5f)
        val failures = ArcoWarmthMeasure.controlsFailures(c3, ArcoBodyMeasure.f0Of(CELLO, 12))
        println(
            "ARCO warmth ruler controls on CELLO C3: ${if (failures.isEmpty()) "all hold (self 0, times 2 reads +6.0206 everywhere and doubles the peak, the ideal shelf reads 0 0 +6.0206 +6.0206, " +
                "the A-weighting reads the standard's table and a 125 Hz sine at -16.1 dB)" else failures.joinToString("; ")}",
        )
        check(failures.isEmpty()) { "the ruler's controls fail: $failures" }
        val ceilingFailures = ArcoWarmthCandidates.ceilingFailures()
        println(
            "ARCO warmth ceiling control: ${if (ceilingFailures.isEmpty()) "CEILING ${ArcoWarmthCandidates.CEILING} is the engine's: a hot buffer through Dsp.levelTo and through Arco.finish equals the helper's gain then limitPeak(CEILING) (and a 0.98 ceiling is told apart)" else ceilingFailures.joinToString("; ")}",
        )
        check(ceilingFailures.isEmpty()) { "the ceiling control fails: $ceilingFailures" }

        val grid = gridReadings()
        println("ARCO warmth helper: no shaping is Arco.render at BODY 0.5 to the sample on all ${grid.map { it.voice to it.step }.distinct().size} grid notes; the three controls (0.01 dB shaping, BODY 0.75, a gain 0.001 percent off) all failed to match, as they must")
        ArcoWarmthMeasure.print(grid)
        ArcoWarmthMeasure.printSummary(grid)
        val found = checkTargets(grid)
        println("ARCO warmth TARGETS thrown: ${if (found.problems.isEmpty()) "none: every thrown target holds" else "${found.problems.size} MISSES: ${found.problems.joinToString(" | ")}"}")
        println("ARCO warmth TARGETS reported, not thrown (${found.reported.size}):${found.reported.joinToString("") { "\nARCO warmth   - $it" }}")
        check(found.problems.isEmpty()) { "the brief's targets are missed: ${found.problems}" }

        val groups = SPECS.map { build(it) }
        checkClips(groups)
        // The page's own readings are the grid's, to the bit (same renders, same ruler): the key is the grid's numbers for these three notes.
        for (g in groups) for (c in g.clips) {
            val kind = c.kind ?: continue
            if (kind == Kind.PLAIN || kind == Kind.REPEAT) continue
            val step = Arco.semitoneFor(g.spec.voice, g.spec.tune)
            val gridReading = grid.first { it.voice == g.spec.voice && it.step == step && it.kind == kind }.numbers
            check(gridReading.loudRise == c.reading!!.loudRise && gridReading.aRise == c.reading.aRise && gridReading.peak == c.reading.peak) { "${g.spec.label} ${c.id}: the page's clip is not the grid's render" }
        }

        var count = 0
        fun write(dir: String, g: BuiltGroup, clip: Built) {
            val x = clip.rendered.snip.samples
            val levelled = FloatArray(x.size) { x[it] * g.gain }
            val peak = levelled.maxOf { abs(it) }
            check(peak in 0.001f..0.99f) { "$dir/${clip.id} has a peak of $peak at the page's gain" }
            WavWriter.write(File(File(root, dir), "${clip.id}.wav"), Snip(levelled, channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_16)
            println(
                "ARCO warmth clip $dir/${clip.id} (${clip.name}, ${clip.kind?.key}): ${ArcoWarmthMeasure.f2(x.size.toDouble() / Dsp.RATE)} s, loudness ${ArcoWarmthMeasure.f3(Loudness.of(clip.rendered.snip).toDouble())} before the page's gain " +
                    "(gain ${ArcoWarmthMeasure.f2(g.gain.toDouble())}), ${ArcoWarmthMeasure.f3(Loudness.of(Snip(levelled, channels = 1, sampleRate = Dsp.RATE)).toDouble())} after, peak ${ArcoWarmthMeasure.f3(peak.toDouble())}",
            )
            count++
        }
        val sections = ArrayList<Section>()
        for (voice in listOf(ERHU, CELLO)) {
            val mine = groups.filter { it.spec.voice == voice }
            for (g in mine) for (c in g.clips) write(voice.name, g, c)
            sections += sectionOf(voice, mine)
        }

        // The manifest is the only place the clip list lives; every clip in it must be a file on disk, and the repeat's file is THE PLAIN ONE's, byte for byte.
        for (section in sections) for (group in section.groups) for (clip in group.clips) {
            check(File(File(root, section.id), clip.id + ".wav").isFile) { "the manifest lists ${section.id}/${clip.id}.wav and it was not written" }
        }
        check(count == sections.sumOf { s -> s.groups.sumOf { it.clips.size } }) { "the clips written are not the clips listed" }
        for (g in groups) {
            val dir = File(root, g.spec.voice.name)
            val repeat = g.clips.firstOrNull { it.kind == Kind.REPEAT } ?: continue
            val plainBytes = File(dir, g.clips.first { it.kind == Kind.PLAIN }.id + ".wav").readBytes()
            check(plainBytes.contentEquals(File(dir, repeat.id + ".wav").readBytes())) { "${g.spec.label}: the repeat's file is not THE PLAIN ONE's, byte for byte" }
        }

        File(root, "manifest.json").writeText(
            "{\"surfaceAfter\":null,\"voices\": [\n" + sections.joinToString(",\n") { sectionJson(it) } + "\n],\"loops\": []," +
                "\"intro\":[],\"pairs\":[" + groups.joinToString(",") { pairJson(it.spec) } + "],\"facts\":{}}\n",
        )
        keyFile.writeText(keyJson(groups))
        File(root, "index.html").writeText(pageText)
        for (g in groups) println("ARCO warmth KEY ${g.spec.label}: ${g.spec.letters.toList().zip(g.spec.order).joinToString(" ") { "${it.first}=${it.second.key}" }}")
        println("ARCO warmth the key is ${keyFile.absolutePath}, beside the folder and not in it: letter to candidate, setting, absolute band deltas, the three loudness rises, peak, the ceiling's flags and the pairs")
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath} (publish that folder whole; the key is not in it)")
    }

    // ---- the manifest's words ------------------------------------------------------------

    private const val DOT = "·"

    private fun sectionOf(voice: ArcoVoice, groups: List<BuiltGroup>): Section {
        val notes = groups.joinToString(" $DOT ") { it.note }
        val letters = groups.joinToString(" $DOT ") { "${it.note}: THE PLAIN ONE, CLIPS ${it.spec.letters.toList().joinToString(" ")}" }
        return Section(
            id = voice.name, display = voice.name, body = "Warmth and weight, on ${groups.joinToString(" and ") { it.note }}.",
            readout = listOf("$notes $DOT EVERY OTHER KNOB AT ITS DEFAULT", letters.uppercase()),
            groups = groups.map { g -> Group(g.spec.label, g.clips.map { c -> Clip(c.id, c.name, if (c.kind == Kind.PLAIN) PLAIN_DESC else LETTER_DESC) }) },
        )
    }

    private const val PLAIN_DESC = "the default: BODY half way. Every other clip is judged against this one"
    private const val LETTER_DESC = "unlabelled on purpose"

    private fun sectionJson(s: Section): String {
        val g = s.groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { clip -> "[${q(clip.id)},${q(clip.name)},${q(clip.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":true,\"clips\":[$c]}"
        }
        val r = s.readout.joinToString(",") { q(it) }
        return "{\"id\":${q(s.id)},\"display\":${q(s.display)},\"body\":${q(s.body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    /** One entry of the manifest's `pairs`: the voice and the two twins' letters in the group's fixed order (`a` is the group's [Spec.pairFirst]); nothing in it says which twin is which. */
    private fun pairJson(spec: Spec): String =
        "{\"voice\":${q(spec.voice.name)},\"a\":${q(spec.letterOf(spec.pairFirst).toString())},\"b\":${q(spec.letterOf(spec.pairSecond).toString())}}"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun num(v: Double?): String = if (v == null) "null" else "%.3f".format(Locale.ROOT, v).let { if (it == "-0.000") "0.000" else it }

    private fun bandsJson(a: Array<Double?>?): String =
        if (a == null) "null" else "{" + ArcoWarmthMeasure.BAND_LABELS.indices.joinToString(",") { "\"${ArcoWarmthMeasure.BAND_LABELS[it]}\":${num(a[it])}" } + "}"

    private fun bandsJson(a: DoubleArray): String = bandsJson(Array(a.size) { a[it] })

    /**
     * The key, for the lead and not for the page: each clip's candidate, its setting, and against THE PLAIN ONE of its group its absolute harmonic band deltas (dB, 80-300 300-1k 1k-3k 3k-8k, null where the band
     * holds no harmonic), the same on all the spectrum, the fundamental's, the three loudness rises (the engine meter's Loudness.of, the whole-clip RMS and the A-weighted over the steady window, plus the A-weighted over the whole
     * clip), the finished peak (before the page's gain) and the peak at the page's level; for the flat-gain clip, the gain and how it compares with BOTH-SMALL's rises; for every shaped clip, the shaping's own predicted band deltas;
     * and the engine's 0.99 ceiling: the string's peak before it, whether it was applied to the clip (only F#2's BOTH-LARGE), whether it acted and how many dB it took off the whole clip, and whether it would act (and by how much)
     * on a clip rendered without it; for the clip it acted on, its band deltas with the ceiling's cut put back. Per group: the letters, which letter is which, and the pair (a, b). The page's gain is the same for every clip of a group.
     */
    private fun keyJson(groups: List<BuiltGroup>): String {
        val rows = groups.joinToString(",\n") { g ->
            val small = g.clips.firstOrNull { it.kind == Kind.BOTH_SMALL }
            val clips = g.clips.joinToString(",\n") { c ->
                val kind = c.kind!!
                val n = c.reading
                val r = c.rendered
                val peakPage = r.snip.samples.maxOf { abs(it) } * g.gain
                val core = "\"id\":${q(c.id)},\"name\":${q(c.name)},\"is\":${q(kind.key)},\"setting\":${q(ArcoWarmthCandidates.settingOf(kind, g.spec.voice))}"
                val ceiling = "\"peakBeforeCeiling\":${num(r.peakBeforeCeiling.toDouble())},\"ceilingApplied\":${r.ceilingApplied},\"ceilingActed\":${r.ceilingActed},\"ceilingTookOffDb\":${num(r.tookOffDb)}," +
                    "\"engineCeilingWouldAct\":${r.ceilingWouldAct},\"engineCeilingWouldTakeOffDb\":${num(-r.duckDb)}"
                if (n == null) {
                    "    {$core,\"bandsDbVsPlain\":null,\"rmsRiseDb\":0.000,\"loudnessRiseDb\":0.000,\"aWeightedRiseDb\":0.000,\"aWeightedRiseWholeClipDb\":0.000,\"peakFinished\":${num(r.peak.toDouble())},\"peakAtPageLevel\":${num(peakPage.toDouble())},$ceiling}"
                } else {
                    "    {$core,\"bandsDbVsPlain\":${bandsJson(n.bands)},\"predictedByFiltersDb\":${bandsJson(c.predicted)},\"allEnergyBandsDbVsPlain\":${bandsJson(n.all)}," +
                        "\"fundamentalDbVsPlain\":${num(n.fundamental)},\"rmsRiseDb\":${num(n.rmsRise)},\"loudnessRiseDb\":${num(n.loudRise)},\"aWeightedRiseDb\":${num(n.aRise)},\"aWeightedRiseWholeClipDb\":${num(n.aRiseWhole)}," +
                        (if (kind == Kind.LOUD) "\"flatGainDb\":${num(g.louderGainDb)},\"flatGainMatchedTo\":\"BOTH-SMALL's A-weighted rise over the steady window\"," +
                            (small?.reading?.let { s -> "\"aWeightedRiseMinusBothSmallDb\":${num(n.aRise - s.aRise)},\"loudnessRiseMinusBothSmallDb\":${num(n.loudRise - s.loudRise)},\"rmsRiseMinusBothSmallDb\":${num(n.rmsRise - s.rmsRise)}," } ?: "")
                        else "") +
                        (if (r.ceilingActed) "\"bandsDbVsPlainIfTheCeilingHadNotActed\":${bandsJson(Array(n.bands.size) { b -> n.bands[b]?.plus(r.tookOffDb) })}," else "") +
                        "\"peakFinished\":${num(r.peak.toDouble())},\"peakAtPageLevel\":${num(peakPage.toDouble())},$ceiling}"
                }
            }
            val map = g.spec.letters.toList().zip(g.spec.order).joinToString(",") { "\"${it.first}\":${q(it.second.key)}" }
            "  {\"voice\":${q(g.spec.voice.name)},\"note\":${q(g.note)},\"label\":${q(g.spec.label)},\"pageGainDb\":${num(20.0 * log10(g.gain.toDouble()))},\"letterIs\":{$map},\"clips\":[\n$clips\n  ]}"
        }
        val pairs = groups.joinToString(",") { g ->
            val s = g.spec
            "{\"voice\":${q(s.voice.name)},\"note\":${q(g.note)},\"a\":${q(s.letterOf(s.pairFirst).toString())},\"aIs\":${q(s.pairFirst.key)},\"b\":${q(s.letterOf(s.pairSecond).toString())},\"bIs\":${q(s.pairSecond.key)}}"
        }
        return "{\"note\":${q("the key to the warmth page: do not publish. The clips are NOT level on purpose: one gain per group (pageGainDb) is applied to every clip of the group, so a clip's level against THE PLAIN ONE is its rmsRiseDb, loudnessRiseDb (the engine's Loudness.of: a gentle 120 Hz low cut, then the loudest 200 ms) and aWeightedRiseDb (the ear's weighting, over the steady window 0.4 to 1.0 s; the LOUDER-ONLY clip is matched to BOTH-SMALL on this one). bandsDbVsPlain are absolute harmonic-energy deltas in dB (80-300, 300-1k, 1k-3k, 3k-8k Hz) over the same window, no loudness taken off; null where the band holds no harmonic. predictedByFiltersDb is what the shaping alone does to the plain's own partials. The engine's 0.99 ceiling is applied to one clip only, F#2's BOTH-LARGE (the clip the engine could make: its string at the plain's level peaks over the ceiling and the ceiling took ceilingTookOffDb off the whole clip); every other clip is rendered without it, and engineCeilingWouldAct says where it would act (THE PLAIN ONE and its repeat are the engine's own render, which always runs the ceiling and never needs it: ceilingApplied is true and ceilingActed false). letterIs and pairs say which letter is which. A ruler, not audibility.")},\"pairs\":[$pairs],\"groups\":[\n$rows\n]}\n"
    }
}
