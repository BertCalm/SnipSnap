package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Renders the ARCO R1d BODY listening page under testkit/arco-body/ (gitignored): `manifest.json`, which the page builds itself from, the clips, `key.json` and a copy of
 * the page from the test resources. Run via `./gradlew :synth:generateArcoBody`, then publish the folder WITHOUT `key.json`: it says which letter is which design, and the page is blind on purpose.
 *
 * The owner heard R1c's BODY 1 (the box ringing 1.75 times the string) and wrote "Body doesn't seem to do anything" and "Body still seems a little light". R1d built three designs for
 * a box a held bowed note can hear and measured them with a ruler ([ArcoBodyMeasure]); nobody can listen but the owner, so the engine change waits for the owner's ears. This page
 * plays [ArcoBodyCandidates]'s candidates beside the default and beside R1c's BODY 1, blind, and asks for MORE BOX, SAME or LESS BOX under each. The page is test-side only: `Arco` is not changed.
 * Nothing here has been heard by anyone yet. The page says so.
 *
 * Three groups, every knob but TUNE at its default and every clip dry and mono, each rendered by [Arco.render]'s own path ([ArcoBodyCandidates.renderThrough]: the bow, the box, [Arco.finish],
 * so no clip is levelled by hand) and then through [AuditionLevel.level], the re-listen page's own level (one loudness for every clip, as on the last page):
 *  - ERHU C5 (its default note), eight clips: THE PLAIN ONE (BODY 0.5, the default), WHAT YOU HEARD LAST TIME (R1c's BODY 1), and six lettered clips C to H, unlabelled on purpose: the
 *    formant bed at two strengths, the broad box at two amounts, the louder-box control, and an exact repeat of THE PLAIN ONE (a catch: if the owner hears the repeat as different,
 *    "different" answers are partly noise).
 *  - CELLO C3 (its default note), the same eight clips, letters J to P (I and O are skipped, as they read as 1 and 0).
 *  - CELLO C4 (an octave up, where a phone plays the fundamental), four clips: THE PLAIN ONE and three lettered ones, Q to S: the bed at full strength, the broad box at its larger amount,
 *    the louder-box control. No repeat and no BEFORE here. **No letter is used twice on the page**, so one letter in an answer or a notes box names one clip.
 *
 * **The shuffle is fixed and hard-coded**, a pure function with no seed: [Spec.order] lists, for each group, the clip behind each letter in the letters' order. ERHU C5, letters C to
 * H: broad 6, bed 0.62, REPEAT, louder, broad 2, bed 1. CELLO C3, J K L M N P: louder, broad 2, bed 1, REPEAT, bed 0.62, broad 6. CELLO C4, Q R S: broad 6, louder, bed 1. It is chosen so
 * that no group is in order of strength or of design, the repeat is never first or last, and the same design does not sit in the same place in two groups. `key.json` is the key: letter to
 * design, setting and D (the clip's own note, and the candidate's median over the ruler's ten-note grid). The CELLO C4 group has three lettered clips and no repeat.
 *
 * **The in-run checks throw** (nothing is written when one fails): the page's render path is [Arco.render] to the sample for THE PLAIN ONE (BODY 0.5) and WHAT YOU HEARD LAST TIME (BODY 1)
 * at every clip's note and at the ruler's whole grid; the repeat is bit-identical to THE PLAIN ONE (and so are its two files); every candidate is finite, under a peak of 0.95 and the
 * length of THE PLAIN ONE; no two clips but the repeat and THE PLAIN ONE are the same; and **each candidate's median D over the ten-note grid is the competition's within 0.05 dB**
 * (A 0.62 5.98, A 1.0 8.66, B 2.0 6.09, B 6.0 8.46, the louder box 7.92, R1c's own BODY 1 2.617: [ArcoBodyMeasure]'s baseline). The D table is printed on `ARCO` lines.
 */
object ArcoBodyGenerator {

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val clips: List<Clip>)
    private class Section(val id: String, val display: String, val body: String, val readout: List<String>, val groups: List<Group>)
    private class Block(val hd: String, val paragraphs: List<String>)

    /** What a clip is. The two labelled ones, the five new candidates and the repeat of THE PLAIN ONE. */
    private enum class Kind { PLAIN, LAST, A62, A100, B2, B6, LOUD, REPEAT }

    private fun candidateOf(kind: Kind): ArcoBodyCandidates.Candidate? = when (kind) {
        Kind.A62 -> ArcoBodyCandidates.BED_62
        Kind.A100 -> ArcoBodyCandidates.BED_100
        Kind.B2 -> ArcoBodyCandidates.BROAD_2
        Kind.B6 -> ArcoBodyCandidates.BROAD_6
        Kind.LOUD -> ArcoBodyCandidates.LOUDER
        else -> null
    }

    private val CELLO = ArcoVoice.CELLO
    private val ERHU = ArcoVoice.ERHU

    /**
     * One group of the page: the [voice] at [tune], under [section], its clip ids starting [prefix], and the [letters] of its unlabelled clips with [order] saying which clip each letter
     * is (the fixed shuffle). [withLast] puts WHAT YOU HEARD LAST TIME in the group; the repeat is whichever of [order] is [Kind.REPEAT].
     */
    private class Spec(
        val voice: ArcoVoice, val tune: Float, val note: String, val label: String, val letters: String, val order: List<Kind>, val withLast: Boolean,
    ) {
        /** The clip ids start with the note's name, lower case: `c5_plain`, `c5_c`. */
        val prefix: String get() = note.lowercase()
    }

    /** The default note of a voice: ERHU C5, CELLO C3. */
    private fun defaultTune(voice: ArcoVoice) = Arco.defaults(voice).getValue("TUNE")

    /** CELLO C4, the top of CELLO's travel: TUNE 1. */
    private val CELLO_C4_TUNE = ArcoBodyMeasure.tuneOf(CELLO, 24)

    /** The page's groups, in the page's order: ERHU's, then CELLO's two. The shuffles are the ones the class KDoc documents. */
    private val SPECS = listOf(
        Spec(ERHU, defaultTune(ERHU), "C5", "ERHU, C5 (THE DEFAULT NOTE)", "CDEFGH", listOf(Kind.B6, Kind.A62, Kind.REPEAT, Kind.LOUD, Kind.B2, Kind.A100), withLast = true),
        Spec(CELLO, defaultTune(CELLO), "C3", "CELLO, C3 (THE DEFAULT NOTE)", "JKLMNP", listOf(Kind.LOUD, Kind.B2, Kind.A100, Kind.REPEAT, Kind.A62, Kind.B6), withLast = true),
        Spec(CELLO, CELLO_C4_TUNE, "C4", "CELLO, C4 (ONE OCTAVE UP)", "QRS", listOf(Kind.B6, Kind.LOUD, Kind.A100), withLast = false),
    )

    private const val NOTE_CHECKED_PEAK = 0.95f
    private const val D_BAR = 0.05

    /** R1c's own BODY 1 against the default, the ruler's baseline median over its ten notes ([ArcoBodyMeasure]'s README: 2.617). */
    private const val R1C_MEDIAN_D = 2.617
    private const val R1C_KEY = "R1C"

    private fun noteOf(voice: ArcoVoice, tune: Float) = ArcoBodyMeasure.noteName(voice, Arco.semitoneFor(voice, tune))

    private fun same(a: Snip, b: Snip) = a.samples.contentEquals(b.samples)

    private fun peakOf(s: Snip): Float {
        var p = 0f
        for (v in s.samples) p = max(p, abs(v))
        return p
    }

    private fun macrosAt(tune: Float, body: Float? = null): Map<String, Float> = if (body == null) mapOf("TUNE" to tune) else mapOf("TUNE" to tune, "BODY" to body)

    // ---- the D table: the ruler, over the grid -------------------------------------------

    /** One cell of the D table: a candidate at one note of the ruler's grid. */
    private class Reading(val voice: ArcoVoice, val step: Int, val d: Double, val peak: Float)

    private fun checkFinite(s: Snip, what: String) {
        for (v in s.samples) check(v.isFinite()) { "$what has a sample that is not finite" }
    }

    /**
     * Every candidate (and R1c's BODY 1) at every note of [ArcoBodyMeasure.GRID], D against THE PLAIN ONE of the same note by the ruler's own
     * [ArcoBodyMeasure.colour] on the ruler's own window. THE PLAIN ONE and R1c's BODY 1 are held to [ArcoBodyMeasure.viaArco] (the engine's own render, which is what the
     * ruler reads) sample for sample at every note, so a D here is a D of the engine's note; every candidate is finite and the length of THE PLAIN ONE.
     */
    private fun gridReadings(): Map<String, List<Reading>> {
        val notes = ArcoBodyMeasure.GRID.flatMap { (voice, steps) -> steps.map { voice to it } }
        val perNote = notes.parallelStream().map { (voice, step) ->
            val macros = macrosAt(ArcoBodyMeasure.tuneOf(voice, step))
            val f0 = ArcoBodyMeasure.f0Of(voice, step)
            val plain = ArcoBodyCandidates.renderThrough(voice, macros, ArcoBodyCandidates::plain)
            check(plain.samples.contentEquals(ArcoBodyMeasure.viaArco(voice, step, 0.5f))) { "THE PLAIN ONE at $voice step $step is not Arco.render at BODY 0.5" }
            val out = LinkedHashMap<String, Reading>()
            fun read(key: String, x: Snip) {
                checkFinite(x, "$key at $voice step $step")
                check(x.samples.size == plain.samples.size) { "$key at $voice step $step is ${x.samples.size} samples, THE PLAIN ONE ${plain.samples.size}" }
                out[key] = Reading(voice, step, ArcoBodyMeasure.colour(x.samples, plain.samples, f0).d, peakOf(x))
            }
            val last = ArcoBodyCandidates.renderThrough(voice, macros, ArcoBodyCandidates::last)
            check(last.samples.contentEquals(ArcoBodyMeasure.viaArco(voice, step, 1f))) { "R1c's BODY 1 at $voice step $step is not Arco.render at BODY 1" }
            read(R1C_KEY, last)
            for (c in ArcoBodyCandidates.ALL) read(c.key, ArcoBodyCandidates.renderThrough(voice, macros, c.box))
            out
        }.toList()
        val keys = listOf(R1C_KEY) + ArcoBodyCandidates.ALL.map { it.key }
        return keys.associateWith { key -> perNote.map { it.getValue(key) } }
    }

    /** Prints the D table on `ARCO` lines, holds each median to the competition's within [D_BAR] and returns the medians by key. */
    private fun checkMedians(grid: Map<String, List<Reading>>): Map<String, Double> {
        println(
            "ARCO body D(candidate at BODY 1 vs THE PLAIN ONE, BODY .5), dB, the ruler's window ${f2(ArcoBodyMeasure.WINDOW_FROM)}-${f2(ArcoBodyMeasure.WINDOW_TO)} s, " +
                "ten notes: CELLO C2 F#2 C3 F#3 C4, ERHU D4 G4 C5 F5 A5. The competition's number is each design's own measurement; this code must match it within ${f2(D_BAR)} dB.",
        )
        val medians = LinkedHashMap<String, Double>()
        val problems = ArrayList<String>()
        for ((key, readings) in grid) {
            val median = ArcoBodyMeasure.median(readings.map { it.d })
            medians[key] = median
            val candidate = ArcoBodyCandidates.ALL.firstOrNull { it.key == key }
            val competition = candidate?.competitionD ?: R1C_MEDIAN_D
            val name = candidate?.let { "${it.design}, ${it.setting}" } ?: "R1c's own BODY 1 (WHAT YOU HEARD LAST TIME)"
            val row = readings.joinToString(" ") { "${ArcoBodyMeasure.noteName(it.voice, it.step)} ${f2(it.d)}" }
            val peak = readings.maxOf { it.peak }
            println("ARCO body D $key ($name): $row | median ${f3(median)}, finished peak at most ${f3(peak)}")
            val off = median - competition
            println("ARCO body median $key: ours ${f3(median)}, competition ${f3(competition)}, off ${(if (off >= 0) "+" else "") + f3(off)} (bar ${f2(D_BAR)}): ${if (abs(off) <= D_BAR) "OK" else "MISMATCH"}")
            if (abs(off) > D_BAR) problems += "$key median D ${f3(median)} against the competition's ${f3(competition)}"
        }
        check(problems.isEmpty()) { "this page's candidates are not the competition's designs: $problems" }
        return medians
    }

    // ---- the page's clips ---------------------------------------------------------------

    private class Built(val id: String, val name: String, val desc: String, val kind: Kind, val snip: Snip)
    private class BuiltGroup(val spec: Spec, val note: String, val clips: List<Built>)

    /** The shuffle is a permutation of exactly the clips the group holds (the five new ones and the repeat; or the three of the short group) and every letter is its own. */
    private fun checkSpec(spec: Spec) {
        check(spec.letters.length == spec.order.size && spec.letters.toSet().size == spec.letters.length) { "${spec.label}: letters ${spec.letters} do not match the shuffle" }
        val wanted = if (spec.withLast) Kind.entries.filter { it != Kind.PLAIN && it != Kind.LAST } else listOf(Kind.A100, Kind.B6, Kind.LOUD)
        check(spec.order.size == wanted.size && spec.order.toSet() == wanted.toSet()) { "${spec.label}: the shuffle ${spec.order} is not the clips the group holds ($wanted)" }
    }

    private fun build(spec: Spec): BuiltGroup {
        checkSpec(spec)
        val voice = spec.voice
        val macros = macrosAt(spec.tune)
        val plain = ArcoBodyCandidates.renderThrough(voice, macros, ArcoBodyCandidates::plain)
        val last = ArcoBodyCandidates.renderThrough(voice, macros, ArcoBodyCandidates::last)
        val repeat = ArcoBodyCandidates.renderThrough(voice, macros, ArcoBodyCandidates::plain)
        // The page's render path is the engine's, to the sample, for THE PLAIN ONE and WHAT YOU HEARD LAST TIME at this note (and at the voice's no-macro default where the note is its default).
        check(same(plain, Arco.render(voice, macrosAt(spec.tune, 0.5f)))) { "${spec.label}: THE PLAIN ONE is not Arco.render at BODY 0.5" }
        check(same(last, Arco.render(voice, macrosAt(spec.tune, 1f)))) { "${spec.label}: WHAT YOU HEARD LAST TIME is not Arco.render at BODY 1" }
        if (spec.tune == defaultTune(voice)) check(same(plain, Arco.render(voice))) { "${spec.label}: THE PLAIN ONE is not the engine's default render" }
        check(same(repeat, plain)) { "${spec.label}: the repeat is not bit-identical to THE PLAIN ONE" }
        check(!same(last, plain)) { "${spec.label}: WHAT YOU HEARD LAST TIME is THE PLAIN ONE" }
        // Design A holds R1c's default box, so its bed rides on exactly THE PLAIN ONE's string.
        val m = Arco.settled(macros, voice)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = Arco.bow(voice, Arco.frequencyFor(voice, m.getValue("TUNE")), m, rate)
        check(ArcoBodyCandidates.heldBox(raw, voice, rate).contentEquals(ArcoBodyCandidates.plain(raw, voice, rate))) { "${spec.label}: design A's held box is not R1c's default box" }

        val clips = ArrayList<Built>()
        clips += Built("${spec.prefix}_plain", "THE PLAIN ONE", "the default: BODY half way. The other clips are judged against this one", Kind.PLAIN, plain)
        if (spec.withLast) clips += Built("${spec.prefix}_last", "WHAT YOU HEARD LAST TIME", "BODY at the top, as it was on the last page", Kind.LAST, last)
        for ((i, kind) in spec.order.withIndex()) {
            val snip = candidateOf(kind)?.let { ArcoBodyCandidates.renderThrough(voice, macros, it.box) } ?: repeat
            val letter = spec.letters[i]
            clips += Built("${spec.prefix}_${letter.lowercaseChar()}", "CLIP $letter", "new, unlabelled on purpose", kind, snip)
        }
        return BuiltGroup(spec, noteOf(voice, spec.tune), clips)
    }

    /** Every clip is finite, under [NOTE_CHECKED_PEAK] before the page's gain and THE PLAIN ONE's length; no two clips are the same except the repeat and THE PLAIN ONE. */
    private fun checkClips(groups: List<BuiltGroup>) {
        for (g in groups) {
            val plain = g.clips.first { it.kind == Kind.PLAIN }.snip
            for (c in g.clips) {
                val where = "${g.spec.label} ${c.id}"
                checkFinite(c.snip, where)
                check(peakOf(c.snip) < NOTE_CHECKED_PEAK) { "$where peaks at ${f3(peakOf(c.snip))}, over ${f2(NOTE_CHECKED_PEAK)}" }
                check(c.snip.samples.size == plain.samples.size) { "$where is ${c.snip.samples.size} samples, THE PLAIN ONE ${plain.samples.size}" }
            }
            for (i in g.clips.indices) for (j in i + 1 until g.clips.size) {
                val a = g.clips[i]
                val b = g.clips[j]
                val pair = setOf(a.kind, b.kind)
                val identical = same(a.snip, b.snip)
                if (pair == setOf(Kind.PLAIN, Kind.REPEAT)) check(identical) { "${g.spec.label}: ${a.id} and ${b.id} (the repeat) differ" }
                else check(!identical) { "${g.spec.label}: ${a.id} and ${b.id} are the same clip" }
            }
        }
    }

    // ---- main ---------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/arco-body")
        root.mkdirs()

        check(ArcoBodyCandidates.R1C_BODY_KNEE == Arco.BODY_KNEE && ArcoBodyCandidates.R1C_BODY_TOP == Arco.BODY_TOP) {
            "the engine's box curve is no longer R1c's (knee ${Arco.BODY_KNEE}, top ${Arco.BODY_TOP}): this page's WHAT YOU HEARD LAST TIME is not what the owner heard"
        }
        for (spec in SPECS) check(noteOf(spec.voice, spec.tune) == spec.note) { "${spec.label} is on ${noteOf(spec.voice, spec.tune)}" }

        val grid = gridReadings()
        val medians = checkMedians(grid)

        val groups = SPECS.map { build(it) }
        checkClips(groups)

        var count = 0
        fun write(dir: String, clip: Built) {
            val snip = clip.snip
            val levelled = AuditionLevel.level(snip)
            WavWriter.write(File(File(root, dir), "${clip.id}.wav"), levelled, WavWriter.BitDepth.PCM_16)
            var at = 0
            for (i in snip.samples.indices) if (abs(snip.samples[i]) > abs(snip.samples[at])) at = i
            val gain = levelled.samples[at] / snip.samples[at]
            val peak = abs(levelled.samples[at])
            check(peak in 0.001f..0.999f) { "$dir/${clip.id} has a peak of $peak" }
            println(
                "ARCO body clip $dir/${clip.id} (${clip.name}): ${f2(snip.samples.size.toFloat() / Dsp.RATE)} s, loudness ${f3(Loudness.of(snip))} before levelling " +
                    "(gain ${f2(gain)}), ${f3(Loudness.of(levelled))} after, peak ${f3(peak)}",
            )
            count++
        }
        val sections = ArrayList<Section>()
        for (voice in listOf(ERHU, CELLO)) {
            val mine = groups.filter { it.spec.voice == voice }
            for (g in mine) for (c in g.clips) write(voice.name, c)
            sections += sectionOf(voice, mine)
        }

        // The manifest is the only place the clip list lives; every clip in it must be a file on disk, and the repeat's file is THE PLAIN ONE's, byte for byte.
        for (section in sections) for (group in section.groups) for (clip in group.clips) {
            check(File(File(root, section.id), clip.id + ".wav").isFile) { "the manifest lists ${section.id}/${clip.id}.wav and it was not written" }
        }
        check(count == sections.sumOf { s -> s.groups.sumOf { it.clips.size } }) { "the clips written are not the clips listed" }
        for (g in groups.filter { g -> g.clips.any { it.kind == Kind.REPEAT } }) {
            val dir = File(root, g.spec.voice.name)
            val plainBytes = File(dir, g.clips.first { it.kind == Kind.PLAIN }.id + ".wav").readBytes()
            val repeatBytes = File(dir, g.clips.first { it.kind == Kind.REPEAT }.id + ".wav").readBytes()
            check(plainBytes.contentEquals(repeatBytes)) { "${g.spec.label}: the repeat's file is not THE PLAIN ONE's, byte for byte" }
        }

        val pageText = (ArcoBodyGenerator::class.java.getResourceAsStream("/audition/arco-body.html")
            ?: error("the BODY page is missing from synth/src/test/resources/audition/")).use { it.readBytes() }.toString(Charsets.UTF_8)

        File(root, "manifest.json").writeText(
            "{\"surfaceAfter\":null,\"voices\": [\n" + sections.joinToString(",\n") { sectionJson(it) } + "\n],\"loops\": []," +
                "\"intro\":[" + introBlocks().joinToString(",") { blockJson(it) } + "],\"facts\":{}}\n",
        )
        File(root, "key.json").writeText(keyJson(groups, grid, medians))
        File(root, "index.html").writeText(pageText)
        println("ARCO body key.json is in ${root.absolutePath}: do not publish it (letter to design, setting and D)")
        println("wrote $count clips + manifest.json + key.json + index.html under ${root.absolutePath}")
    }

    // ---- the manifest's words ------------------------------------------------------------

    private const val DOT = "·"

    private fun sectionOf(voice: ArcoVoice, groups: List<BuiltGroup>): Section {
        val notes = groups.joinToString(" $DOT ") { it.note }
        val letters = groups.joinToString(" $DOT ") { "${it.note}: THE PLAIN ONE, ${if (it.spec.withLast) "LAST TIME, " else ""}CLIPS ${it.spec.letters.first()} TO ${it.spec.letters.last()}" }
        return Section(
            id = voice.name, display = voice.name, body = "BODY at the top of the knob: is there a box under the note?",
            readout = listOf("$notes $DOT EVERY OTHER KNOB AT ITS DEFAULT", letters.uppercase()),
            groups = groups.map { g -> Group(g.spec.label, g.clips.map { Clip(it.id, it.name, it.desc) }) },
        )
    }

    /**
     * The manifest's intro cards. The page carries its own cards for what it is, the clips and how to listen (WHAT YOU SAID, THE CLIPS, HOW TO LISTEN, with the honest sentence that nobody has listened),
     * so this adds only the one thing the page does not know, which is the speaker: plain words, no design names, no numbers of the ruler.
     */
    private fun introBlocks(): List<Block> = listOf(
        Block(
            "SPEAKER OR HEADPHONES",
            listOf(
                "Most phone speakers play very little below about 300 Hz, and CELLO C3's lowest notes sit under that, so its clips may sound thin on a speaker. The CELLO C4 clips are an octave higher for that reason. " +
                    "Say in the last box whether you used the speaker or headphones.",
            ),
        ),
    )

    private fun f2(v: Float) = "%.2f".format(Locale.ROOT, v)
    private fun f2(v: Double) = "%.2f".format(Locale.ROOT, v)
    private fun f3(v: Float) = "%.3f".format(Locale.ROOT, v)
    private fun f3(v: Double) = "%.3f".format(Locale.ROOT, v)

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

    /**
     * The key, for the lead and not for the page: each lettered clip's design, its setting, its D at its own note (the ruler's, against THE PLAIN ONE) and the candidate's
     * median over the ten-note grid. The labelled clips and the repeat are in it too.
     */
    private fun keyJson(groups: List<BuiltGroup>, grid: Map<String, List<Reading>>, medians: Map<String, Double>): String {
        val rows = groups.joinToString(",\n") { g ->
            val step = Arco.semitoneFor(g.spec.voice, g.spec.tune)
            val clips = g.clips.joinToString(",\n") { c ->
                val candidate = candidateOf(c.kind)
                val key = when (c.kind) {
                    Kind.LAST -> R1C_KEY
                    else -> candidate?.key
                }
                val design = when (c.kind) {
                    Kind.PLAIN -> "R1c at the default BODY 0.5 (THE PLAIN ONE)"
                    Kind.LAST -> "R1c at BODY 1 (WHAT YOU HEARD LAST TIME)"
                    Kind.REPEAT -> "an exact repeat of THE PLAIN ONE"
                    else -> "${candidate!!.design}, ${candidate.setting}"
                }
                val d = key?.let { k -> grid.getValue(k).firstOrNull { it.voice == g.spec.voice && it.step == step }?.d }
                "    {\"id\":${q(c.id)},\"name\":${q(c.name)},\"design\":${q(design)},\"dAtThisNote\":${d?.let { f3(it) } ?: "null"},\"dMedianTenNotes\":${key?.let { f3(medians.getValue(it)) } ?: "null"}}"
            }
            "  {\"voice\":${q(g.spec.voice.name)},\"note\":${q(g.note)},\"label\":${q(g.spec.label)},\"clips\":[\n$clips\n  ]}"
        }
        return "{\"note\":${q("the key to the BODY page: do not publish. D is the ruler's colour distance in dB of BODY 1 against the default, a ruler and not audibility.")},\"groups\":[\n$rows\n]}\n"
    }
}
