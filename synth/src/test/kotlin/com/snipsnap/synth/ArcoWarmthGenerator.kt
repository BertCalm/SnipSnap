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
 * candidate and carries each clip's absolute band deltas, loudness rise and peak; the page is blind on purpose.
 *
 * The owner heard R1d's page and wrote "dull and muffled rather than full and resonant", and asked for **warmth and weight** at the top of the knob, saying louder is fine. R1d levelled every clip to one
 * loudness, so a low-mid boost ducked the top. This page builds each candidate **on top of THE PLAIN ONE at the plain's own level** ([ArcoWarmthCandidates]: the plain's boxed string through a shelf and a bell,
 * the plain's gain, not a new one) and does **not** level the clips: one gain per group, the one that puts THE PLAIN ONE at the re-listen page's level, is applied to every clip of the group, so a clip that adds
 * sound is louder and the key says by how much. Nobody has listened to any of these.
 *
 * Three groups, every knob but TUNE at its default and every clip dry and mono:
 *  - ERHU C5 (its default note), eight clips: THE PLAIN ONE and seven lettered clips C D E F G H J: an exact repeat of the plain, the LOUDER-ONLY control, WEIGHT, WARMTH, BOTH small, BOTH large and BOTH small plus bloom.
 *  - CELLO C3 (its default note), the same eight clips, letters K L M N P Q R.
 *  - CELLO F#2 (a low note, where the box matters most), four clips: THE PLAIN ONE and three lettered ones, S T U: LOUDER-ONLY, BOTH small, BOTH large.
 * **No letter is used twice on the page** (I and O are skipped: they read as 1 and 0). The brief's letters were six per eight-clip group, which cannot name seven lettered clips; this page uses seven.
 *
 * **The shuffle is fixed and hard-coded**, a pure function with no seed: [Spec.order] lists, for each group, the clip behind each letter in the letters' order. ERHU C D E F G H J: BOTH large, WARMTH, REPEAT, LOUDER-ONLY, BLOOM, WEIGHT,
 * BOTH small. CELLO K L M N P Q R: LOUDER-ONLY, BOTH small, WEIGHT, REPEAT, BOTH large, BLOOM, WARMTH. CELLO S T U: BOTH small, BOTH large, LOUDER-ONLY. The generator checks by itself ([checkShuffles]) that the repeat is never first or last,
 * that no group is in order of strength (up or down) and that no design sits in the same slot in two groups; checked by hand against the three orders above as well.
 *
 * **The in-run checks throw**, and a re-run starts by emptying the output folder and deleting the key, so a failed check never leaves a page of an earlier run behind (the checks that need the written files run last, so a failure
 * there leaves a partial folder: do not publish after a failure): the ruler's own controls; the helper's proof (no shaping is [Arco.render] at BODY 0.5 to the sample on all ten grid notes) and its three controls that must fail (a 0.01 dB shaping,
 * a render at BODY 0.75, a gain 0.001 percent off); the repeat is bit-identical to the plain, rendered again from the bow up (and so are its two files); every candidate is finite and the plain's length; the page's clips that would need the engine's 0.99 ceiling (a peak of 0.95 or more with the string at the plain's level) are
 * exactly [EXPECTED_HEADROOM_CLIPS] (the page and the ruler are rendered without the ceiling, see [ENGINE_CEILING]; every grid clip past 0.95 is printed and reported, not thrown, since a fixed rung cannot fit every plain's peak); no two clips but the repeat and the plain are the same; the LOUDER-ONLY gain is BOTH small's finished [Loudness.of] rise on every grid note; and the brief's targets on the whole
 * grid: the top bands (1k-3k and 3k-8k) within -0.5 dB of the plain for WEIGHT, WARMTH and BOTH (and BLOOM), the shaping's low band where the filters say ([ArcoWarmthMeasure.predicted]) and about the rung (BOTH and BLOOM on every note, a single
 * element on the page's notes; a single element on the grid's other notes is reported). The table is printed on `ARCO warmth` lines before the checks that can fail on it.
 *
 * **The bars, what R1e saw and why they can fail.** Top bands: the lowest delta was -0.0 dB (WEIGHT at C2, 3k-8k), the bar is -0.5; with the engine's ceiling left on (the first run) BOTH-LARGE at C2 read -3.1 and threw. Filters: the largest
 * measured-minus-predicted band was 0.01 dB, the bar is [PRED_BAR]; with the ceiling on it was 3.2 dB (C2 BOTH-LARGE, 80-300). Rung: BOTH small read +3.9 to +4.2 (CELLO, 80-300) and +4.0 to +4.5 (ERHU, 300-1k) against nominal +4.0 and a corridor of plus and
 * minus [LIFT_BAND_DB]; BOTH large +6.8 to +7.3 and +6.9 to +7.7 against +7.0; a single element on the page's notes +1.9 to +3.1 (WEIGHT and WARMTH, nominal +3.5, lowest allowed half of it). The flat gain's [Loudness.of] rise was within 0.000 dB of BOTH small's (bar
 * [LOUD_MATCH_BAR]). A fixed corner cannot follow the pitch, so a single element falls short on CELLO C4 (WEIGHT +0.9, WARMTH +1.6) and on ERHU D4, F5 and A5 (WEIGHT +1.5, +1.2, +0.6): reported, not thrown, and the generator's PRED agrees to 0.01 dB that it is the filter and not the helper.
 */
object ArcoWarmthGenerator {

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val clips: List<Clip>)
    private class Section(val id: String, val display: String, val body: String, val readout: List<String>, val groups: List<Group>)
    private class Block(val hd: String, val paragraphs: List<String>)

    private val CELLO = ArcoVoice.CELLO
    private val ERHU = ArcoVoice.ERHU

    /**
     * One group of the page: the [voice] at [tune], its clip ids starting [prefix] (no `#` in a file name), the [letters] of its lettered clips with [order] saying which clip each letter is (the fixed shuffle).
     * THE PLAIN ONE is always the first clip and is not lettered.
     */
    private class Spec(
        val voice: ArcoVoice, val tune: Float, val note: String, val prefix: String, val label: String, val letters: String, val order: List<Kind>,
    )

    private fun defaultTune(voice: ArcoVoice) = Arco.defaults(voice).getValue("TUNE")

    /** The page's groups, in the page's order: ERHU's, then CELLO's two. The shuffles are the ones the class KDoc documents. */
    private val SPECS = listOf(
        Spec(ERHU, defaultTune(ERHU), "C5", "c5", "ERHU, C5 (THE DEFAULT NOTE)", "CDEFGHJ", listOf(Kind.BOTH_LARGE, Kind.WARMTH, Kind.REPEAT, Kind.LOUD, Kind.BLOOM, Kind.WEIGHT, Kind.BOTH_SMALL)),
        Spec(CELLO, defaultTune(CELLO), "C3", "c3", "CELLO, C3 (THE DEFAULT NOTE)", "KLMNPQR", listOf(Kind.LOUD, Kind.BOTH_SMALL, Kind.WEIGHT, Kind.REPEAT, Kind.BOTH_LARGE, Kind.BLOOM, Kind.WARMTH)),
        Spec(CELLO, ArcoBodyMeasure.tuneOf(CELLO, 6), "F#2", "f2", "CELLO, F#2 (A LOW NOTE)", "STU", listOf(Kind.BOTH_SMALL, Kind.BOTH_LARGE, Kind.LOUD)),
    )

    private val FULL_SET = setOf(Kind.REPEAT, Kind.LOUD, Kind.WEIGHT, Kind.WARMTH, Kind.BOTH_SMALL, Kind.BOTH_LARGE, Kind.BLOOM)
    private val SHORT_SET = setOf(Kind.LOUD, Kind.BOTH_SMALL, Kind.BOTH_LARGE)

    /** How strong a clip is, for the "no group in order of strength" check: the repeat nothing, the flat gain as loud as BOTH small, the single elements, BOTH small and bloom, BOTH large. */
    private fun strength(kind: Kind): Int = when (kind) {
        Kind.REPEAT -> 0
        Kind.LOUD -> 1
        Kind.WEIGHT, Kind.WARMTH -> 2
        Kind.BOTH_SMALL, Kind.BLOOM -> 3
        Kind.BOTH_LARGE -> 4
        else -> error("$kind is not a lettered clip")
    }

    private const val NOTE_CHECKED_PEAK = 0.95f
    private const val ABSENT = -1

    /** The loudness every clip of a group is scaled by relative to THE PLAIN ONE: [AuditionLevel]'s own level for the plain (its 0.03), the same gain on every clip of the group. The generator checks the plain times it is [AuditionLevel.level]'s. */
    private const val AUDITION_LEVEL = 0.03f

    /** The bar of "the helper's LOUDER-ONLY clip is as loud as BOTH small by the engine's meter", in dB. [Loudness.of] is linear in the gain, so only float rounding is left. */
    private const val LOUD_MATCH_BAR = 0.01

    /** The bar of "the measured band is where the filters say it is" ([ArcoWarmthMeasure.predicted]), in dB: the vibrato moves a partial 0.6 percent and the shaping is a smooth filter; R1e saw 0.01 at most. */
    private const val PRED_BAR = 0.1

    /**
     * The page and the ruler render without the engine's 0.99 ceiling ([ArcoWarmthCandidates.render]'s `engineCeiling`): where a candidate would need it ([ArcoWarmthCandidates.Rendered.ceilingWouldAct]) the ceiling would scale the
     * whole clip down and duck the string, so the clip would test the limiter and not the warmth. Every such clip is printed with the dB the ceiling would take off, and is in the key.
     */
    private const val ENGINE_CEILING = false

    /**
     * The page clips that need the engine's ceiling, "group prefix/candidate": R1e saw one, F#2's BOTH-LARGE (the plain's peak there is 0.625, +6 dB per element lifts the peak by x2.07 to 1.29, and the ceiling would take 2.3 dB off the whole clip).
     * The generator holds the page to exactly this set: a clip joining it, or this one leaving it, throws.
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
            val r = ArcoWarmthCandidates.render(note, kind, ENGINE_CEILING)
            rendered[kind] = r
            check(finite(r.snip.samples)) { "$where ${kind.key}: a sample is not finite" }
            check(r.snip.samples.size == plain.size) { "$where ${kind.key}: ${r.snip.samples.size} samples, THE PLAIN ONE ${plain.size}" }
            readings += ArcoWarmthMeasure.Reading(
                voice, step, kind, ArcoWarmthMeasure.compare(r.snip.samples, base), ArcoWarmthMeasure.predicted(base, voice, kind), base.peak, r.ceilingWouldAct, r.duckDb,
            )
        }
        val gainDb = ArcoWarmthCandidates.louderGainDb(note, rendered.getValue(Kind.BOTH_SMALL))
        val loud = ArcoWarmthCandidates.renderLouder(note, gainDb, ENGINE_CEILING)
        check(finite(loud.snip.samples) && loud.snip.samples.size == plain.size) { "$where LOUDER-ONLY: not finite or not the plain's length" }
        readings += ArcoWarmthMeasure.Reading(voice, step, Kind.LOUD, ArcoWarmthMeasure.compare(loud.snip.samples, base), null, base.peak, loud.ceilingWouldAct, loud.duckDb, gainDb)
        return readings
    }

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

    /** The rung's nominal rise at the loudest partials, in dB, the sum where the two skirts overlap: +4.0 small and +7.0 large (the physics scout's), +3.5 for an element alone. */
    private fun nominalDb(kind: Kind): Double = when (kind) {
        Kind.WEIGHT, Kind.WARMTH -> ArcoWarmthCandidates.SMALL_DB.toDouble()
        Kind.BOTH_SMALL, Kind.BLOOM -> 4.0
        Kind.BOTH_LARGE -> 7.0
        else -> error("$kind has no rung")
    }

    /**
     * The corridor of "about what the rung says" (guesses, listening): BOTH small, BOTH large and BLOOM within [LIFT_BAND_DB] of their nominal rise either way; a single element from [LIFT_SINGLE_LOW_SHARE] of its nominal rise
     * (its skirt, not its centre, lands on most partials) to [LIFT_BAND_DB] above it.
     */
    private const val LIFT_BAND_DB = 1.5
    private const val LIFT_SINGLE_LOW_SHARE = 0.5

    /** The page's notes as (voice, grid step): the rung of a single element must hold there (it throws); on the grid's other notes a shortfall is reported (a fixed corner cannot follow the pitch). */
    private val PAGE_NOTES: Set<Pair<ArcoVoice, Int>> = SPECS.map { it.voice to Arco.semitoneFor(it.voice, it.tune) }.toSet()

    /** What the targets found: [problems] throw, [reported] are facts the lead must read (the engine's headroom, the single elements on grid notes the page does not play). */
    private class Findings(val problems: MutableList<String> = ArrayList(), val reported: MutableList<String> = ArrayList())

    /**
     * The brief's targets over the whole grid, printed with their worst value. Thrown (in [Findings.problems]): the top bands within [ArcoWarmthMeasure.TOP_FLOOR_DB] of the plain and the shaping where the filters say, for WEIGHT, WARMTH,
     * BOTH and BLOOM on every note; the rung corridor ([primaryBand]) for BOTH and BLOOM on every note and for WEIGHT and WARMTH on the page's notes; the flat gain is BOTH small's loudness. Reported only ([Findings.reported]): the single
     * elements' rung on the grid's other notes, and every clip whose peak, with the string at the plain's level, passes [NOTE_CHECKED_PEAK] (the engine's ceiling would act or is close).
     */
    private fun checkTargets(readings: List<ArcoWarmthMeasure.Reading>): Findings {
        val found = Findings()
        val problems = found.problems
        val m = ArcoWarmthMeasure
        for (voice in ArcoVoice.entries) for (kind in ArcoWarmthCandidates.SHAPED) {
            val rs = readings.filter { it.voice == voice && it.kind == kind }
            val tag = "${voice.name.take(1)} ${kind.key}"
            // 1. the top is not ducked
            var worstTop = Double.MAX_VALUE
            var worstTopAt = ""
            for (r in rs) for (b in m.TOP_BANDS) r.numbers.bands[b]?.let { if (it < worstTop) { worstTop = it; worstTopAt = "${r.name} ${m.BAND_LABELS[b]}" } }
            val topOk = worstTop >= m.TOP_FLOOR_DB
            println("ARCO warmth TARGET top bands $tag: lowest delta ${m.s1(worstTop)} dB at $worstTopAt (bar ${m.f1(m.TOP_FLOOR_DB)}): ${if (topOk) "OK" else "MISS"}")
            if (!topOk) problems += "$tag: a top band is ${m.f2(worstTop)} dB at $worstTopAt, under ${m.f1(m.TOP_FLOOR_DB)}"
            // 2. the shaping is where the filters say
            var worstPred = 0.0
            var worstPredAt = ""
            for (r in rs) for (b in m.BANDS_HZ.indices) {
                val meas = r.numbers.bands[b] ?: continue
                val pred = r.predicted!![b] ?: continue
                if (abs(meas - pred) > worstPred) { worstPred = abs(meas - pred); worstPredAt = "${r.name} ${m.BAND_LABELS[b]} measured ${m.s1(meas)} predicted ${m.s1(pred)}" }
            }
            val predOk = worstPred <= PRED_BAR
            println("ARCO warmth TARGET filters $tag: largest measured-minus-predicted ${m.f2(worstPred)} dB at $worstPredAt (bar ${m.f2(PRED_BAR)}): ${if (predOk) "OK" else "MISS"}")
            if (!predOk) problems += "$tag: measured differs from the filters' prediction by ${m.f2(worstPred)} dB at $worstPredAt"
            // 3. the rung
            val band = primaryBand(voice, kind)
            val single = kind == Kind.WEIGHT || kind == Kind.WARMTH
            val lo = if (single) LIFT_SINGLE_LOW_SHARE * nominalDb(kind) else nominalDb(kind) - LIFT_BAND_DB
            val hi = nominalDb(kind) + LIFT_BAND_DB
            val lifts = rs.map { it to it.numbers.bands[band] }.filter { it.second != null }.map { it.first to it.second!! }
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
        // 4. the flat-gain control is BOTH small's loudness by the engine's meter; the peaks
        for (voice in ArcoVoice.entries) for (step in ArcoBodyMeasure.GRID.getValue(voice)) {
            val both = readings.first { it.voice == voice && it.step == step && it.kind == Kind.BOTH_SMALL }
            val loud = readings.first { it.voice == voice && it.step == step && it.kind == Kind.LOUD }
            val gap = abs(loud.numbers.loudRise - both.numbers.loudRise)
            println(
                "ARCO warmth TARGET louder-only ${voice.name.take(1)} ${both.name}: flat gain ${m.s2(loud.louderGainDb!!)} dB; Loudness.of rise ${m.s2(loud.numbers.loudRise)} vs BOTH small ${m.s2(both.numbers.loudRise)} (gap ${m.f3(gap)}, bar ${m.f2(LOUD_MATCH_BAR)}); " +
                    "RMS rise ${m.s2(loud.numbers.rmsRise)} vs ${m.s2(both.numbers.rmsRise)}: ${if (gap <= LOUD_MATCH_BAR) "OK" else "MISS"}",
            )
            if (gap > LOUD_MATCH_BAR) problems += "${voice.name.take(1)} ${both.name}: the flat gain is ${m.f3(gap)} dB off BOTH small's loudness rise"
        }
        for (r in readings) if (!r.numbers.finite()) problems += "${r.voice.name.take(1)} ${r.kind.key} ${r.name}: a ruler number is not finite"
        // 5. the headroom: what lift the plain's peak allows, and every clip that passes the bar with the string at the plain's level (reported, not thrown: the engine step has to give the headroom or back the rung off)
        for (voice in ArcoVoice.entries) for (step in ArcoBodyMeasure.GRID.getValue(voice)) {
            val rs = readings.filter { it.voice == voice && it.step == step }
            val plainPeak = rs.first().plainPeak.toDouble()
            fun rise(kind: Kind) = rs.first { it.kind == kind }.let { 20.0 * log10(it.numbers.peak / plainPeak) }
            println(
                "ARCO warmth HEADROOM ${voice.name.take(1)} ${rs.first().name}: the plain's peak ${m.f3(plainPeak)} allows ${m.s1(20.0 * log10(NOTE_CHECKED_PEAK / plainPeak))} dB before ${m.f2(NOTE_CHECKED_PEAK.toDouble())} " +
                    "(${m.s1(20.0 * log10(ArcoWarmthCandidates.CEILING / plainPeak))} before the ceiling); the peak rises by BOTH small ${m.s1(rise(Kind.BOTH_SMALL))}, BOTH large ${m.s1(rise(Kind.BOTH_LARGE))}, louder-only ${m.s1(rise(Kind.LOUD))} dB",
            )
        }
        for (r in readings) if (r.numbers.peak >= NOTE_CHECKED_PEAK) {
            val text = "${r.voice.name.take(1)} ${r.kind.key} ${r.name}: peak ${m.f3(r.numbers.peak.toDouble())} with the string at the plain's level (plain ${m.f3(r.plainPeak.toDouble())}), " +
                if (r.ceilingWouldAct) "the engine's ceiling WOULD ACT and take ${m.f1(-r.duckDb)} dB off the whole clip" else "the ceiling would not act (under ${m.f2(ArcoWarmthCandidates.CEILING.toDouble())}) but the bar is ${m.f2(NOTE_CHECKED_PEAK.toDouble())}"
            println("ARCO warmth TARGET peak $text: MISS (reported)")
            found.reported += text
        }
        println("ARCO warmth TARGET peak over the ${readings.size} grid renders: largest peak ${m.f3(readings.maxOf { it.numbers.peak }.toDouble())} (bar ${m.f2(NOTE_CHECKED_PEAK.toDouble())}); the plain's largest ${m.f3(readings.maxOf { it.plainPeak }.toDouble())}")
        return found
    }

    // ---- the page's clips ---------------------------------------------------------------

    private class Built(val id: String, val name: String, val kind: Kind?, val rendered: ArcoWarmthCandidates.Rendered, val reading: ArcoWarmthMeasure.Numbers?, val predicted: Array<Double?>?)
    private class BuiltGroup(val spec: Spec, val note: String, val gain: Float, val clips: List<Built>, val louderGainDb: Double)

    /** The shuffles are what the class KDoc says: a permutation of the group's clips, every letter its own across the page, the repeat neither first nor last, no group in order of strength, no clip in the same slot in two groups. */
    private fun checkShuffles() {
        val allLetters = SPECS.joinToString("") { it.letters }
        check(allLetters.toSet().size == allLetters.length && 'I' !in allLetters && 'O' !in allLetters) { "the letters are not unique across the page (or use I or O): $allLetters" }
        for (spec in SPECS) {
            val label = spec.label
            check(spec.letters.length == spec.order.size) { "$label: letters ${spec.letters} do not match the shuffle" }
            val wanted = if (spec.order.size == SHORT_SET.size) SHORT_SET else FULL_SET
            check(spec.order.size == wanted.size && spec.order.toSet() == wanted) { "$label: the shuffle ${spec.order} is not the clips the group holds ($wanted)" }
            val at = spec.order.indexOf(Kind.REPEAT)
            check(at == ABSENT || (at > 0 && at < spec.order.size - 1)) { "$label: the repeat is first or last" }
            val ranks = spec.order.map { strength(it) }
            check(!ranks.zipWithNext().all { (a, b) -> a <= b } && !ranks.zipWithNext().all { (a, b) -> a >= b }) { "$label: the group is in order of strength ($ranks)" }
        }
        for (i in SPECS.indices) for (j in i + 1 until SPECS.size) {
            for (slot in 0 until minOf(SPECS[i].order.size, SPECS[j].order.size)) {
                check(SPECS[i].order[slot] != SPECS[j].order[slot]) { "${SPECS[i].label} and ${SPECS[j].label} both put ${SPECS[i].order[slot]} in slot $slot" }
            }
        }
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

        val both = ArcoWarmthCandidates.render(note, Kind.BOTH_SMALL, ENGINE_CEILING)
        val gainDb = ArcoWarmthCandidates.louderGainDb(note, both)
        val clips = ArrayList<Built>()
        clips += Built("${spec.prefix}_plain", "THE PLAIN ONE", Kind.PLAIN, plain, null, null)
        for ((i, kind) in spec.order.withIndex()) {
            val rendered = when (kind) {
                Kind.REPEAT -> repeat
                Kind.LOUD -> ArcoWarmthCandidates.renderLouder(note, gainDb, ENGINE_CEILING)
                else -> ArcoWarmthCandidates.render(note, kind, ENGINE_CEILING)
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
     * Every clip is finite and THE PLAIN ONE's length; the clips that need the engine's ceiling (a peak of [NOTE_CHECKED_PEAK] or more with the string at the plain's level) are exactly [EXPECTED_HEADROOM_CLIPS];
     * no two clips are the same except the repeat and THE PLAIN ONE. The peak at the page's level is checked where the files are written.
     */
    private fun checkClips(groups: List<BuiltGroup>) {
        val needing = ArrayList<String>()
        for (g in groups) {
            val plain = g.clips.first { it.kind == Kind.PLAIN }.rendered.snip.samples
            for (c in g.clips) {
                val where = "${g.spec.label} ${c.id}"
                val x = c.rendered.snip.samples
                check(finite(x)) { "$where has a sample that is not finite" }
                check(x.size == plain.size) { "$where is ${x.size} samples, THE PLAIN ONE ${plain.size}" }
                if (c.rendered.peak >= NOTE_CHECKED_PEAK) {
                    needing += "${g.spec.prefix}/${c.kind!!.key}"
                    println(
                        "ARCO warmth HEADROOM page clip ${g.spec.prefix}/${c.id} (${c.kind.key}): peak ${ArcoWarmthMeasure.f3(c.rendered.peak.toDouble())} with the string at the plain's level, the engine's ceiling would " +
                            "${if (c.rendered.ceilingWouldAct) "act and take ${ArcoWarmthMeasure.f1(-c.rendered.duckDb)} dB off the whole clip" else "not act"}; rendered without it",
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
        }
        check(needing.toSet() == EXPECTED_HEADROOM_CLIPS) { "the page clips that need the engine's ceiling are $needing, not the expected $EXPECTED_HEADROOM_CLIPS" }
    }

    // ---- main ---------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/arco-warmth")
        // The key is a sibling of the published folder, so publishing the folder whole cannot publish it. A re-run starts from nothing: no clip, manifest or page of an earlier run survives a failed check.
        val keyFile = File(root.absoluteFile.parentFile, root.name + "-key.json")
        root.deleteRecursively()
        keyFile.delete()
        root.mkdirs()

        for (spec in SPECS) check(noteOf(spec.voice, spec.tune) == spec.note) { "${spec.label} is on ${noteOf(spec.voice, spec.tune)}" }
        checkShuffles()

        // The ruler's own controls, on CELLO C3's plain.
        val c3 = ArcoBodyMeasure.viaArco(CELLO, 12, 0.5f)
        val failures = ArcoWarmthMeasure.controlsFailures(c3, ArcoBodyMeasure.f0Of(CELLO, 12))
        println("ARCO warmth ruler controls on CELLO C3: ${if (failures.isEmpty()) "all hold (self 0, times 2 reads +6.0206 everywhere and doubles the peak, the ideal shelf reads 0 0 +6.0206 +6.0206)" else failures.joinToString("; ")}")
        check(failures.isEmpty()) { "the ruler's controls fail: $failures" }

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
            check(gridReading.loudRise == c.reading!!.loudRise && gridReading.peak == c.reading.peak) { "${g.spec.label} ${c.id}: the page's clip is not the grid's render" }
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

        val pageText = (ArcoWarmthGenerator::class.java.getResourceAsStream("/audition/arco-warmth.html")
            ?: error("the warmth page is missing from synth/src/test/resources/audition/")).use { it.readBytes() }.toString(Charsets.UTF_8)
        if (pageText.length < 20_000) println("ARCO warmth WARNING: the page is only ${pageText.length} characters; the real page is far longer, so this is the placeholder: do not publish this folder")

        File(root, "manifest.json").writeText(
            "{\"surfaceAfter\":null,\"voices\": [\n" + sections.joinToString(",\n") { sectionJson(it) } + "\n],\"loops\": []," +
                "\"intro\":[" + introBlocks().joinToString(",") { blockJson(it) } + "],\"facts\":{}}\n",
        )
        keyFile.writeText(keyJson(groups))
        File(root, "index.html").writeText(pageText)
        println("ARCO warmth the key is ${keyFile.absolutePath}, beside the folder and not in it: letter to candidate, setting, absolute band deltas, loudness rise and peak")
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath} (publish that folder whole; the key is not in it)")
    }

    // ---- the manifest's words ------------------------------------------------------------

    private const val DOT = "·"

    private fun sectionOf(voice: ArcoVoice, groups: List<BuiltGroup>): Section {
        val notes = groups.joinToString(" $DOT ") { it.note }
        val letters = groups.joinToString(" $DOT ") { "${it.note}: THE PLAIN ONE, CLIPS ${it.spec.letters.toList().joinToString(" ")}" }
        return Section(
            id = voice.name, display = voice.name, body = "Does BODY at the top add warmth and weight? Nobody has heard these yet.",
            readout = listOf("$notes $DOT EVERY OTHER KNOB AT ITS DEFAULT", letters.uppercase()),
            groups = groups.map { g -> Group(g.spec.label, g.clips.map { c -> Clip(c.id, c.name, if (c.kind == Kind.PLAIN) PLAIN_DESC else LETTER_DESC) }) },
        )
    }

    private const val PLAIN_DESC = "the default: BODY half way. Every other clip is judged against this one"
    private const val LETTER_DESC = "unlabelled on purpose"

    /**
     * The manifest's intro. The page carries its own cards for what it is, what you said, the clips, the four answers and how to listen; this adds only what depends on how the clips were made, so it
     * is one card: plain words, no design names and no numbers. It says the clips are not level on purpose and that this is not a mistake (some of them add sound, so some are louder than THE PLAIN ONE;
     * it does not say which, or that one is only louder, because the page is blind), that nobody has listened, and headphones. How much louder each clip is lives in the key, not here.
     */
    private fun introBlocks(): List<Block> = listOf(
        Block(
            "LEVEL AND HEADPHONES",
            listOf(
                "These clips are not level on purpose. A body adds sound, so some of them are louder than THE PLAIN ONE, and that is part of what is being tried. Nothing was turned down to match. Keep your volume at one setting for the whole page.",
                "Please use headphones. Nobody has listened to any of these yet, so some may sound worse than THE PLAIN ONE.",
            ),
        ),
    )

    private fun sectionJson(s: Section): String {
        val g = s.groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { clip -> "[${q(clip.id)},${q(clip.name)},${q(clip.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":true,\"clips\":[$c]}"
        }
        val r = s.readout.joinToString(",") { q(it) }
        return "{\"id\":${q(s.id)},\"display\":${q(s.display)},\"body\":${q(s.body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    private fun blockJson(b: Block) = "{\"hd\":${q(b.hd)},\"p\":[" + b.paragraphs.joinToString(",") { q(it) } + "]}"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun num(v: Double?): String = if (v == null) "null" else "%.3f".format(Locale.ROOT, v)

    private fun bandsJson(a: Array<Double?>?): String =
        if (a == null) "null" else "{" + ArcoWarmthMeasure.BAND_LABELS.indices.joinToString(",") { "\"${ArcoWarmthMeasure.BAND_LABELS[it]}\":${num(a[it])}" } + "}"

    private fun bandsJson(a: DoubleArray): String = bandsJson(Array(a.size) { a[it] })

    /**
     * The key, for the lead and not for the page: each clip's candidate, its setting, and against THE PLAIN ONE of its group its absolute harmonic band deltas (dB, 80-300 300-1k 1k-3k 3k-8k, null where the band
     * holds no harmonic), the same on all the spectrum, the fundamental's, the whole-clip RMS rise, the engine's Loudness.of rise, the finished peak (before the page's gain) and the peak at the page's level; for the
     * flat-gain clip, the gain; for every shaped clip, the shaping's own predicted band deltas; and whether the engine's 0.99 ceiling would act on the clip (they are rendered without it) and how many dB it would take off the whole clip.
     * The page's gain is the same for every clip of a group.
     */
    private fun keyJson(groups: List<BuiltGroup>): String {
        val rows = groups.joinToString(",\n") { g ->
            val clips = g.clips.joinToString(",\n") { c ->
                val kind = c.kind!!
                val n = c.reading
                val peakPage = c.rendered.snip.samples.maxOf { abs(it) } * g.gain
                val core = "\"id\":${q(c.id)},\"name\":${q(c.name)},\"is\":${q(kind.key)},\"setting\":${q(ArcoWarmthCandidates.settingOf(kind, g.spec.voice))}"
                if (n == null) {
                    "    {$core,\"bandsDbVsPlain\":null,\"rmsRiseDb\":0.000,\"loudnessRiseDb\":0.000,\"peakFinished\":${num(c.rendered.peak.toDouble())},\"peakAtPageLevel\":${num(peakPage.toDouble())}}"
                } else {
                    "    {$core,\"bandsDbVsPlain\":${bandsJson(n.bands)},\"predictedByFiltersDb\":${bandsJson(c.predicted)},\"allEnergyBandsDbVsPlain\":${bandsJson(n.all)}," +
                        "\"fundamentalDbVsPlain\":${num(n.fundamental)},\"rmsRiseDb\":${num(n.rmsRise)},\"loudnessRiseDb\":${num(n.loudRise)}," +
                        (if (kind == Kind.LOUD) "\"flatGainDb\":${num(g.louderGainDb)}," else "") +
                        "\"peakFinished\":${num(c.rendered.peak.toDouble())},\"peakAtPageLevel\":${num(peakPage.toDouble())}," +
                        "\"engineCeilingWouldAct\":${c.rendered.ceilingWouldAct},\"engineCeilingWouldTakeOffDb\":${num(c.rendered.duckDb)}}"
                }
            }
            "  {\"voice\":${q(g.spec.voice.name)},\"note\":${q(g.note)},\"label\":${q(g.spec.label)},\"pageGainDb\":${num(20.0 * log10(g.gain.toDouble()))},\"clips\":[\n$clips\n  ]}"
        }
        return "{\"note\":${q("the key to the warmth page: do not publish. The clips are NOT level on purpose: one gain per group (pageGainDb) is applied to every clip of the group, so a clip's level against THE PLAIN ONE is its rmsRiseDb and loudnessRiseDb. bandsDbVsPlain are absolute harmonic-energy deltas in dB (80-300, 300-1k, 1k-3k, 3k-8k Hz) over the steady window 0.4 to 1.0 s, no loudness taken off; null where the band holds no harmonic. predictedByFiltersDb is what the shaping alone does to the plain's own partials. A ruler, not audibility.")},\"groups\":[\n$rows\n]}\n"
    }
}
