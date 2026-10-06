package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Renders the ARCO R1h BODY gate: the blind listening page for the new BODY (a low lift on top of the plain note above the middle of the knob, [Arco.finished]). Output under `testkit/arco-body-gate/` (gitignored):
 * `manifest.json` (the page builds itself from it), the clips in one folder per voice, and, in a full run, a copy of the page from the test resources (`audition/arco-body-gate.html`). The key
 * (`testkit/arco-body-gate-key.json`, a sibling of the folder and never in it) says which letter is which clip and carries each clip's numbers; [ArcoBodyGateDecoder] reads it with the owner's saved answers.
 * Run `./gradlew :synth:generateArcoBodyGate` (add `-PclipsOnly` for the development mode, `--clips-only` on the command line: clips, manifest and key, no `index.html`). The full run refuses a page that is missing or is
 * the one-line placeholder, before it writes anything. Nobody has listened to any of this: every number here is a measurement, never a description of a sound.
 *
 * What the page tests. BODY above the middle used to be re-levelled away (R1c, R1d: every clip was made as loud as the plain, so a boost to the low end read as a cut at the top and the owner heard "dull and muffled").
 * Above the middle BODY now adds a low shelf and a broad bell on the plain's own gain, and the top is not turned down. The page asks whether the owner hears that as fuller, and whether it is more than just louder.
 *
 * The 24 clips, four groups (the group is a voice at one note, every other knob at its default). Every clip is [Arco.render] (or, for the derived ones, one of its renders) times ONE gain per group: the gain that puts THE PLAIN ONE
 * at [AuditionLevel]'s loudness (its 0.03), the same on every clip of the group (R1e's rule, never [AuditionLevel] per clip, which would level the whole design away). So a clip that adds sound is louder than the plain, on purpose.
 *  - CELLO C3 (the default note), 10 clips: THE PLAIN ONE (BODY 0.5) and nine lettered ones: BODY 0.6, 0.75, 0.9 and 1.0; LOUDER-ONLY on rms (the plain times one flat gain equal to BODY 1's whole-clip rms rise); LOUDER-ONLY on A-weighting
 *    (the same, on BODY 1's A-weighted rise over the steady window); a hidden REPEAT (the plain rendered again from the bow up, bit for bit); OLD-WAY (BODY 1 times one gain to the plain's [Loudness.of], R1c's reading); and the
 *    R1D-GRADE control (the plain through a high shelf of -12 dB at 2 kHz, times one gain to the plain's [Loudness.of]: a loss at the top that the page must be able to see).
 *  - ERHU C5 (the default note), 7 clips: THE PLAIN ONE, BODY 0.75, BODY 1.0, LOUDER-ONLY rms, LOUDER-ONLY A-weighted, REPEAT, OLD-WAY.
 *  - CELLO C2 (the lowest note, where the engine's peak cap acts), 4 clips: THE PLAIN ONE, BODY 0.75, BODY 1.0 (the same samples as BODY 0.75 by design: a free noise check), LOUDER-ONLY rms.
 *  - ERHU A5 (a high note, never heard above C5), 3 clips: THE PLAIN ONE, BODY 1.0, LOUDER-ONLY rms.
 * Each flat gain is the plain times one constant and is held to its target to [TWIN_BAR_DB] (0.1 dB) or the generator throws. OLD-WAY and R1D-GRADE are one relevelling gain on their source, to the plain's Loudness.of.
 *
 * The letters and the shuffle are fixed and hard-coded (no seed). Letters start at C (A and B are the pair answers), skip I and O, and run in the order of the page: CELLO C3 C to L, CELLO C2 M N P, ERHU C5 Q to V, ERHU A5 W X.
 * [SPECS] says, group by group, which clip is behind each letter. The generator proves the rules ([guardShuffles]) and shows each guard throwing on a control that breaks it: the repeat is never first or last; a louder-only clip
 * (and OLD-WAY, which is made from BODY 1) is never next to BODY 1; no group is in order of strength (two fixed rankings of the kinds, and the measured rms rise); the BODY clips as shown (the plain first) are not in order of BODY;
 * no kind is in the same slot of two groups; letters are unique across the page. Two groups are too small for some rules and say so: C2 has three lettered clips of which two are the same samples, so the measured ranking is a tie there;
 * A5 has two lettered clips, which are next to each other whichever way round, and any order of two is an order. Those exemptions are printed on the SHUFFLE lines, not hidden.
 *
 * The manifest, which is the contract the page builder reads (JSON, UTF-8; every text in it is what the page shows; no number, no `box`, no EQ words, nothing on direction: [guardWords] throws on one):
 *  - `phase` "arco_r1h", `storagePrefix` "arco-body-gate:" and `docs`: `{prefix: "verdicts/arco_r1h_", voiceKeys: ["CELLO","ERHU"], overallKey: "overall"}`. The page's db docs are at `docs.prefix + key`.
 *  - `intro`: the honesty text, a list of paragraphs: the clips are NOT level on purpose, nobody has listened, some may sound worse, headphones please.
 *  - `voices`: `[{id, display, body, readout: [text], groups: [{label, key: true, note, prefix, clips: [[id, name, desc]]}]}]`, CELLO then ERHU, each with its two groups in the page's order (CELLO: C3, C2; ERHU: C5, A5). A clip is
 *    `[id, name, desc]` as on R1e's page: THE PLAIN ONE is first in its group, its id ends in `_plain`, and it takes NO chip; the others are `CLIP <letter>`, in the group's shuffled order. The file is `<voice id>/<clip id>.wav`.
 *  - `questions` (all wording lives here): `required` (the ids whose answers the counter counts: the device question and the four pairs), `counter` and `save` (texts with `{n}` for the count: `some`, `one`, `none`; the page shows
 *    the counter and the save button says how many are unanswered; saving with some unanswered is allowed; `counter.unsent` is what the strip says when every required question is answered but the answers have not been sent, which
 *    only SAVE VERDICT does; `counter.clips` and `save.clips` are the same kind of text for the lettered clips that have no chip yet, with `{n}` and, on the counter, `{total}`: a number only, it says nothing about which clip),
 *    `device` (`{id, first, required, text, options: [{v, t}]}`: the first question, required), `chips` (`{gate: "played", text,
 *    lockedText with {letter}, options: [{v, t, hint}]}`: a chip row is disabled until that clip's play count is at least one and says why, with the clip's letter beside it; none on THE PLAIN ONE), `pair` (`{gate: "pairRun", text,
 *    lockedText, options: [{v, t}]}`, the one wording shared by the four forced pairs) and `pairs` (`[{id, voice, group, note, a, b}]`, four widgets in the page's order: `a` is the clip played first and `b` the
 *    second; each of the two notes C3 and C5 is asked in both orders and the two widgets of a note are not next to each other; a widget shows only A and B, never a clip letter, and unlocks only after its own PLAY A, THEN B has
 *    run to its end (the page counts that run in the plays of the widget's voice, under the widget's id)), `order` (`{id, gate: "allPlayed", voice, group, text,
 *    lockedText, clips: [the five C3 BODY-ladder clip ids, in the order to show them, which is not in order of BODY], sameId, sameText, sameNone: {v, t}, samePick, answerFormat}`: at C3 only, put the five in order, fullest last, and say
 *    whether any two sound the same), `keep` (`[{id, voice, group, text, clips: [ids of the voice's BODY-ladder clips, THE PLAIN ONE first then the lettered ones in the group's order], extra: [{v, t}]}]`, CELLO then ERHU: which one
 *    would you keep at the top of the knob) and `note` (`{id: "q_note", text}`, the one free note). Every answer goes through the page's saving logic; the shapes are in [ArcoBodyGateDecoder]'s KDoc.
 *  - `loops` `[]` and `facts` `{}` (kept as R1e's page reads them) and `surfaceAfter` null.
 *
 * The key (never published, never in the published folder: a check throws if it is): per group, the voice, note, prefix, the page gain, `letterIs` and `clips` with id, letter, `is` (PLAIN, REPEAT, BODY, LOUDER-RMS, LOUDER-A, OLD-WAY or
 * R1D-GRADE), `body`, the file and its SHA-256, the length, the rises over the plain (rms, [Loudness.of], A-weighted steady and whole clip), the four absolute band deltas, the finished peak, the peak and the loudness at the
 * page's gain, the lift the engine asked for, capped and delivered, and what the flat gain or the relevelling gain was. Then `pairs`, `order` and `keep` with the same ids as the manifest and what each clip is. The decoder reads only the key and the verdict docs.
 *
 * In-run checks that throw (a re-run starts by emptying the folder and deleting the key, so a failed check never leaves a page of an earlier run behind; the page's own length is checked first; the checks that need the written
 * files run last, so a failure there leaves a partial folder: do not publish after a failure). Each guard is a function that throws on bad input, and a control (`ARCO gate CONTROL` lines) calls the very same guard with the rule broken and
 * requires it to throw: a twin off its target, a clip over 0.95 at the page gain, any shuffle rule, a repeat that is not bit-identical, THE PLAIN ONE of a default-note group (C3 and ERHU C5 alike: the note is compared, not the TUNE float) that is not the engine's default render, a group label that disagrees with the default note, the R1D-GRADE control out of its bands, OLD-WAY at the wrong level or reading wrong, a ladder
 * that is not one, identical clips that should differ (and the C2 pair that must be identical), a gain off by 0.001 percent, a clip file that is not the clip times the gain, a tampered hash, wording that breaks the words law, a
 * placeholder page, a page with the old round's paths or a leaked clip kind, and a manifest with a number in it.
 */
object ArcoBodyGateGenerator {

    // ---- the contract the page and the decoder share ----------------------------------------------------

    /** The round's name: the db docs are `verdicts/arco_r1h_<key>`, the local-storage keys `arco-body-gate:arco_r1h:<key>`. New for this page: R1e's were arco_r1e and arco-warmth:. */
    internal const val PHASE = "arco_r1h"
    internal const val DOC_PREFIX = "verdicts/arco_r1h_"
    internal const val STORAGE_PREFIX = "arco-body-gate:"
    internal const val PAGE_RESOURCE = "/audition/arco-body-gate.html"

    /** The keys of the page's db docs: one per voice (the chips, plays and a note) and `overall` (the questions). */
    internal const val OVERALL_KEY = "overall"

    /** The overall doc's fields. Every value is a string (the page's saving logic keeps strings). */
    internal const val Q_DEVICE = "q_device"
    internal const val Q_NOTE = "q_note"
    internal const val Q_ORDER_C3 = "q_order_c3"
    internal const val Q_SAME_C3 = "q_same_c3"
    internal const val Q_KEEP_CELLO = "q_keep_CELLO"
    internal const val Q_KEEP_ERHU = "q_keep_ERHU"
    internal val Q_PAIRS: List<String> = listOf("q_pair_1", "q_pair_2", "q_pair_3", "q_pair_4")

    /** The chip answers a voice doc's `reactions` hold, by clip id. */
    internal const val CHIP_SAME = "same"
    internal const val CHIP_FULLER = "fuller"
    internal const val CHIP_DULLER = "duller"
    internal const val CHIP_TOO_MUCH = "toomuch"
    internal const val CHIP_DIFF = "diff"
    internal const val CHIP_CANT = "cant"

    /** One chip: the stored value, the words on the button and a few words under it. */
    internal class Chip(val v: String, val text: String, val hint: String)

    /** The six chips, R1e's words plus TOO MUCH. No WARMER and no JUST LOUDER (both lean). */
    internal val CHIPS: List<Chip> = listOf(
        Chip(CHIP_SAME, "SAME AS THE PLAIN", ""),
        Chip(CHIP_FULLER, "FULLER", ""),
        Chip(CHIP_DULLER, "DULLER", ""),
        Chip(CHIP_TOO_MUCH, "TOO MUCH", "boomy or thick"),
        Chip(CHIP_DIFF, "DIFFERENT", "some other way"),
        Chip(CHIP_CANT, "CAN'T SAY", ""),
    )

    /** The pair answers: the first clip, the second, or can't tell. */
    internal const val PAIR_A = "a"
    internal const val PAIR_B = "b"
    internal const val PAIR_CANT = "cant"

    /** The device answers. */
    internal const val DEVICE_HEADPHONES = "headphones"
    internal const val DEVICE_PHONE = "phone"
    internal const val DEVICE_OTHER = "other"

    /** The keep answers besides a clip id. */
    internal const val KEEP_NONE = "none"
    internal const val KEEP_CANT = "cant"

    /** The group prefixes, which are also the first part of every clip id. */
    internal const val G_C3 = "c3"
    internal const val G_C2 = "c2"
    internal const val G_C5 = "c5"
    internal const val G_A5 = "a5"

    // ---- the clips' kinds -------------------------------------------------------------------------------

    /**
     * What a clip is. [key] is what the key file says (BODY clips share one key and differ by [body]). PLAIN and the BODY kinds are [Arco.render] at that BODY; REPEAT is the plain again; the rest are derived from a render
     * by one gain (the two LOUDER kinds, OLD-WAY) or one high shelf and one gain (R1D-GRADE).
     */
    internal enum class Kind(val key: String, val body: Float?) {
        PLAIN("PLAIN", Arco.DEFAULT_BODY),
        REPEAT("REPEAT", null),
        B060("BODY", 0.6f),
        B075("BODY", 0.75f),
        B090("BODY", 0.9f),
        B100("BODY", 1.0f),
        LOUD_RMS("LOUDER-RMS", null),
        LOUD_A("LOUDER-A", null),
        OLD_WAY("OLD-WAY", null),
        DULL("R1D-GRADE", null),
        ;

        val isBody: Boolean get() = key == "BODY"
    }

    /**
     * One group of the page: the [voice] at TUNE [step], its clip ids starting [prefix] (no `#` in a file name), the [letters] of its lettered clips and [order], the fixed shuffle: the clip behind each letter, in the
     * letters' order. THE PLAIN ONE is always the first clip and is not lettered.
     */
    internal data class Spec(
        val voice: ArcoVoice, val step: Int, val note: String, val prefix: String, val label: String, val letters: String, val order: List<Kind>,
    ) {
        fun letterOf(kind: Kind): Char = letters[order.indexOf(kind)]

        fun idOf(kind: Kind): String = if (kind == Kind.PLAIN) "${prefix}_plain" else "${prefix}_${letterOf(kind).lowercaseChar()}"

        /** The clips as the page shows them: THE PLAIN ONE, then the lettered ones. */
        val shown: List<Kind> get() = listOf(Kind.PLAIN) + order
    }

    private val CELLO = ArcoVoice.CELLO
    private val ERHU = ArcoVoice.ERHU

    /** The kinds each group holds. */
    private val EXPECTED: Map<String, Set<Kind>> = mapOf(
        G_C3 to setOf(Kind.B060, Kind.B075, Kind.B090, Kind.B100, Kind.LOUD_RMS, Kind.LOUD_A, Kind.REPEAT, Kind.OLD_WAY, Kind.DULL),
        G_C2 to setOf(Kind.B075, Kind.B100, Kind.LOUD_RMS),
        G_C5 to setOf(Kind.B075, Kind.B100, Kind.LOUD_RMS, Kind.LOUD_A, Kind.REPEAT, Kind.OLD_WAY),
        G_A5 to setOf(Kind.B100, Kind.LOUD_RMS),
    )

    /**
     * The page's groups in the page's order, and the fixed shuffle (see the class KDoc). CELLO C3 (C D E F G H J K L): R1D-GRADE, LOUDER-RMS, BODY 0.75, REPEAT, BODY 1.0, BODY 0.6, LOUDER-A, OLD-WAY, BODY 0.9. CELLO C2 (M N P):
     * BODY 1.0, BODY 0.75, LOUDER-RMS. ERHU C5 (Q R S T U V): LOUDER-A, REPEAT, BODY 1.0, BODY 0.75, LOUDER-RMS, OLD-WAY. ERHU A5 (W X): LOUDER-RMS, BODY 1.0. Chosen by hand against the rules; [guardShuffles] proves them.
     */
    internal val SPECS: List<Spec> = listOf(
        Spec(
            CELLO, 12, "C3", G_C3, "CELLO, C3 (THE DEFAULT NOTE)", "CDEFGHJKL",
            listOf(Kind.DULL, Kind.LOUD_RMS, Kind.B075, Kind.REPEAT, Kind.B100, Kind.B060, Kind.LOUD_A, Kind.OLD_WAY, Kind.B090),
        ),
        Spec(CELLO, 0, "C2", G_C2, "CELLO, C2 (THE LOWEST NOTE)", "MNP", listOf(Kind.B100, Kind.B075, Kind.LOUD_RMS)),
        Spec(
            ERHU, 10, "C5", G_C5, "ERHU, C5 (THE DEFAULT NOTE)", "QRSTUV",
            listOf(Kind.LOUD_A, Kind.REPEAT, Kind.B100, Kind.B075, Kind.LOUD_RMS, Kind.OLD_WAY),
        ),
        Spec(ERHU, 19, "A5", G_A5, "ERHU, A5 (A HIGH NOTE)", "WX", listOf(Kind.LOUD_RMS, Kind.B100)),
    )

    /** One forced pair: the widget's id, its group and the two kinds in the order they are played. */
    internal class PairSpec(val id: String, val group: String, val first: Kind, val second: Kind)

    /** The four forced pairs in the page's order: BODY 1 against the rms-matched louder-only clip at C3 and at C5, each in both orders, the two of a note apart. */
    internal val PAIRS: List<PairSpec> = listOf(
        PairSpec(Q_PAIRS[0], G_C3, Kind.B100, Kind.LOUD_RMS),
        PairSpec(Q_PAIRS[1], G_C5, Kind.LOUD_RMS, Kind.B100),
        PairSpec(Q_PAIRS[2], G_C3, Kind.LOUD_RMS, Kind.B100),
        PairSpec(Q_PAIRS[3], G_C5, Kind.B100, Kind.LOUD_RMS),
    )

    /** The five clips of the C3 ordering question, in the order shown: the plain and the four BODY clips, not in order of BODY. */
    internal val ORDER_SHOWN: List<Kind> = listOf(Kind.B090, Kind.PLAIN, Kind.B100, Kind.B060, Kind.B075)

    // ---- the bars ---------------------------------------------------------------------------------------

    /** The loudness every clip of a group is scaled by relative to THE PLAIN ONE: [AuditionLevel]'s own level for the plain (its 0.03), the same gain on every clip of the group. */
    private const val AUDITION_LEVEL = 0.03f

    /** No clip may peak over this at the page's gain (the engine's finished-peak bar is the same number). Not a listening value. */
    private const val PAGE_PEAK_BAR = 0.95f

    /** A flat gain must reach its target (BODY 1's rms rise, or its A-weighted rise) within this many dB. A flat gain moves every frequency by the same amount, so R1e saw 0.000001 dB. The brief says 0.1. */
    private const val TWIN_BAR_DB = 0.1

    /** The relevelled clips (OLD-WAY, R1D-GRADE) must have the plain's [Loudness.of] to this share. A gain set to the ratio of the two loudnesses reaches it to a few millionths. */
    private const val LEVEL_BAR = 1e-3

    /** The R1D-GRADE control: a high shelf of this many dB at this corner (R1d's 13 clips cut 3-8 kHz by 7.3 to 19.4 dB and were all DIFFERENT), then one gain to the plain's loudness. Listening values. */
    private const val DULL_SHELF_HZ = 2000f
    private const val DULL_SHELF_DB = -12f

    /** The R1D-GRADE control's bands against the plain: 3-8k between these (dB, R1d's range) and 80-300 between these (the relevelling lifts the low end a little). Never loosened. */
    private const val DULL_TOP_MIN_DB = -20.0
    private const val DULL_TOP_MAX_DB = -7.3
    private const val DULL_LOW_MIN_DB = -3.0
    private const val DULL_LOW_MAX_DB = 2.0

    /** OLD-WAY (BODY 1 relevelled to the plain's loudness) must read R1c's loss at the top: 3-8k at most this many dB under the plain (R1f saw -6.3 at C3 and -6.0 at C5; ArcoBodyLiftTest pins under -5.3 and -5.0). */
    private const val OLD_WAY_TOP_MAX_DB = -5.0

    /** What a BODY clip must show against the plain, in the engine's own bars (ArcoBodyLiftTest): the upper bands within this of the plain, and the whole clip at least this much louder (BODY 0.75 and up). */
    private const val TOP_FLOOR_DB = -0.5
    private const val RISE_MIN_DB = 2.8

    /** Steps of the BODY ladder must each rise by at least this many dB of rms, so the ordering question has a ladder to put in order. */
    private const val LADDER_STEP_MIN_DB = 0.3

    /** The page resource must be the real page: the placeholder is one line. R1e's page is about 48000 characters; the bar is 20000. */
    private const val MIN_PAGE_CHARS = 20_000

    /** What the R1f design predicted for each flat gain (dB), printed against what was measured (not a bar: the flat gain follows what BODY 1 measures). */
    private val PREDICTED_RMS_DB = mapOf(G_C3 to 6.48, G_C5 to 6.38, G_C2 to 3.02, G_A5 to 6.16)
    private val PREDICTED_A_DB = mapOf(G_C3 to 4.23, G_C5 to 5.74)

    private fun defaultTune(voice: ArcoVoice) = Arco.defaults(voice).getValue("TUNE")

    /**
     * Whether TUNE [step] is the note the engine plays at its default TUNE. Compared by the note (the semitone TUNE snaps to), not by the TUNE float: ERHU C5 is step 10 whose TUNE is 0.5263 where the default is 0.5, and
     * both are C5, so a float comparison skipped the default-render check for the group labelled the default note.
     */
    internal fun isDefaultNote(voice: ArcoVoice, step: Int): Boolean = Arco.semitoneFor(voice, defaultTune(voice)) == step

    private fun noteOf(voice: ArcoVoice, step: Int) = ArcoBodyMeasure.noteName(voice, step)

    private fun finite(x: FloatArray) = x.all { it.isFinite() }

    private fun f4(v: Double) = "%.4f".format(Locale.ROOT, v)

    private fun peakOf(x: FloatArray): Float {
        var p = 0f
        for (v in x) p = maxOf(p, abs(v))
        return p
    }

    private fun rmsDbRise(x: FloatArray, plain: FloatArray): Double = 20.0 * log10(ArcoWarmthMeasure.rmsOf(x) / ArcoWarmthMeasure.rmsOf(plain))

    private fun levelled(x: FloatArray, g: Float) = FloatArray(x.size) { x[it] * g }

    /** [x] with one sample moved one float step (a render that is one ulp off). */
    private fun ulp3(x: FloatArray): FloatArray = x.copyOf().also { it[it.size / 3] = Math.nextUp(it[it.size / 3]) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    // ---- the guards: each throws, and each is shown throwing on a control ---------------------------------

    /**
     * Runs [body], which must throw an [IllegalStateException] (a guard's `check`) whose message holds [expect] (so it is the guard the control aims at that threw, not another), and prints the message: the control broke
     * the rule the guard keeps, and the guard said so.
     */
    private fun mustThrow(what: String, expect: String = "", body: () -> Unit) {
        val message = try {
            body()
            null
        } catch (e: IllegalStateException) {
            e.message ?: "(no message)"
        }
        check(message != null) { "CONTROL FAILED TO FAIL: $what: the guard did not throw" }
        check(expect in message) { "CONTROL THREW THE WRONG THING: $what: wanted a message with \"$expect\", got: $message" }
        println("ARCO gate CONTROL $what: the guard THREW, as it must: ${message.take(170)}")
    }

    /** Runs [body], which must NOT throw (a positive control: the guard does not cry wolf on what is allowed). */
    private fun mustHold(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: IllegalStateException) {
            error("POSITIVE CONTROL FAILED: $what: the guard threw on what it must allow: ${e.message}")
        }
        println("ARCO gate CONTROL $what: the guard held, as it must")
    }

    private fun inOrder(ranks: List<Int>): Boolean = ranks.zipWithNext().all { (a, b) -> a <= b } || ranks.zipWithNext().all { (a, b) -> a >= b }

    /** How much BODY lift a kind holds by design, for the "no group in order of strength" check: the flat gains and the repeat hold none, BODY 1 and OLD-WAY (its shape) the most. */
    private fun strengthByShape(kind: Kind): Int = when (kind) {
        Kind.PLAIN, Kind.REPEAT, Kind.DULL, Kind.LOUD_RMS, Kind.LOUD_A -> 0
        Kind.B060 -> 1
        Kind.B075 -> 2
        Kind.B090 -> 3
        Kind.B100, Kind.OLD_WAY -> 4
    }

    /** How loud a kind is meant to be over the plain (R1f's predictions at C3 and C5): the plain, the repeat and the two relevelled clips none, BODY 0.6 a little, BODY 1 and its rms-matched twin the most. */
    private fun strengthByLoudness(kind: Kind): Int = when (kind) {
        Kind.PLAIN, Kind.REPEAT, Kind.DULL, Kind.OLD_WAY -> 0
        Kind.B060 -> 1
        Kind.B075 -> 3
        Kind.LOUD_A -> 4
        Kind.B090 -> 5
        Kind.B100, Kind.LOUD_RMS -> 6
    }

    /** Letters unique across the page, from C, no I or O, in the order of the page. */
    private fun guardLetters(specs: List<Spec>) {
        val all = specs.joinToString("") { it.letters }
        val alphabet = ('C'..'Z').filter { it != 'I' && it != 'O' }.joinToString("")
        check(all.toSet().size == all.length) { "the letters are not unique across the page: $all" }
        check('I' !in all && 'O' !in all) { "the letters use I or O, which read as 1 and 0: $all" }
        check(all == alphabet.take(all.length)) { "the letters are not C onward in order of the page: $all" }
    }

    /** A group holds the kinds it should, once each, one letter each. */
    private fun guardKinds(s: Spec) {
        check(s.letters.length == s.order.size) { "${s.label}: letters ${s.letters} do not match the shuffle" }
        check(s.order.toSet().size == s.order.size) { "${s.label}: a clip is in the shuffle twice: ${s.order}" }
        val wanted = EXPECTED.getValue(s.prefix)
        check(s.order.toSet() == wanted) { "${s.label}: the shuffle ${s.order} is not the clips the group holds ($wanted)" }
    }

    /** The repeat is neither first nor last (a repeat first would be heard right after the plain). */
    private fun guardRepeatSlot(s: Spec) {
        val at = s.order.indexOf(Kind.REPEAT)
        check(at == -1 || (at > 0 && at < s.order.size - 1)) { "${s.label}: the repeat is first or last (slot $at of ${s.order.size})" }
    }

    /** In a group of three or more lettered clips no louder-only clip and not OLD-WAY (which is made from BODY 1) is next to BODY 1. Returns what it checked, for the print. */
    private fun guardAdjacency(s: Spec): String {
        if (s.order.size < 3) return ""
        val top = s.order.indexOf(Kind.B100)
        val notes = ArrayList<String>()
        for (k in listOf(Kind.LOUD_RMS, Kind.LOUD_A, Kind.OLD_WAY)) {
            val i = s.order.indexOf(k)
            if (i == -1) continue
            check(abs(i - top) >= 2) { "${s.label}: ${k.key} is next to BODY 1 (slots $i and $top)" }
            notes += "${k.key} ${abs(i - top)} from BODY 1"
        }
        return notes.joinToString(", ")
    }

    /**
     * No group of three or more lettered clips is in order of strength, by shape and by loudness (two fixed rankings of the kinds, ties count as in order): the lettered clips alone when there are four or more of them, and the
     * group as shown, the plain first, from three. Two lettered clips cannot be out of order.
     */
    private fun guardStrength(s: Spec) {
        if (s.order.size < 3) return
        val rankings = listOf("by shape" to { k: Kind -> strengthByShape(k) }, "by loudness" to { k: Kind -> strengthByLoudness(k) })
        for ((name, rank) in rankings) {
            val seqs = if (s.order.size >= 4) listOf("the lettered clips" to s.order, "the group as shown" to s.shown) else listOf("the group as shown" to s.shown)
            for ((what, seq) in seqs) check(!inOrder(seq.map(rank))) { "${s.label}: $what are in order of strength $name (${seq.map(rank)})" }
        }
    }

    /** The BODY clips as shown (the plain first) are not in order of BODY, from three of them. */
    private fun guardLadderShown(s: Spec) {
        val bodies = s.shown.filter { it.isBody || it == Kind.PLAIN }.map { Math.round(it.body!! * 100f) }
        if (bodies.size >= 3) check(!inOrder(bodies)) { "${s.label}: the BODY clips as shown are in order of BODY (${bodies.map { it / 100.0 }})" }
    }

    /** No kind sits in the same slot of two groups. */
    private fun guardSlots(specs: List<Spec>) {
        for (i in specs.indices) for (j in i + 1 until specs.size) {
            for (slot in 0 until minOf(specs[i].order.size, specs[j].order.size)) {
                check(specs[i].order[slot] != specs[j].order[slot]) { "${specs[i].label} and ${specs[j].label} both put ${specs[i].order[slot]} in slot $slot" }
            }
        }
    }

    /** Every shuffle rule on [specs] in turn, with a printed line per group saying what held and what is exempt. */
    private fun guardShuffles(specs: List<Spec>, print: Boolean = true) {
        guardLetters(specs)
        for (s in specs) {
            guardKinds(s)
            guardRepeatSlot(s)
            val adjacency = guardAdjacency(s)
            guardStrength(s)
            guardLadderShown(s)
            val exempt = ArrayList<String>()
            if (s.order.size < 3) exempt += "adjacency and order of strength exempt: two lettered clips are next to each other whichever way round, and any order of two is an order"
            if (s.prefix == G_C2) exempt += "the measured rms ranking is a tie here (BODY 0.75 and BODY 1.0 are the same samples by design), so only the two fixed rankings are held"
            if (print) {
                val at = s.order.indexOf(Kind.REPEAT)
                println(
                    "ARCO gate SHUFFLE ${s.label}: ${s.letters.toList().zip(s.order).joinToString(" ") { "${it.first}=${kindText(it.second)}" }}; repeat at slot " +
                        "${if (at == -1) "none" else "$at of 0..${s.order.size - 1}"}; ${adjacency.ifEmpty { "no twin rule" }}; ranks as shown by shape ${s.shown.map { strengthByShape(it) }} " +
                        "by loudness ${s.shown.map { strengthByLoudness(it) }}${if (exempt.isEmpty()) "" else "; EXEMPT: ${exempt.joinToString("; ")}"}: OK",
                )
            }
        }
        guardSlots(specs)
        if (print) println("ARCO gate SHUFFLE no kind is in the same slot in two groups (${specs.size} groups, every slot compared): OK")
    }

    /** The widgets that ask which of two is the bigger instrument: four, BODY 1 against LOUDER-RMS, at C3 and at C5 each in both orders, the two of a note not next to each other. */
    private fun guardPairs(pairs: List<PairSpec>, specs: List<Spec>, print: Boolean = true) {
        check(pairs.size == 4) { "there are ${pairs.size} pair widgets, not four" }
        check(pairs.map { it.id } == Q_PAIRS) { "the pair ids are ${pairs.map { it.id }}, not $Q_PAIRS" }
        for (g in listOf(G_C3, G_C5)) {
            val mine = pairs.filter { it.group == g }
            check(mine.size == 2) { "group $g has ${mine.size} pair widgets, not two" }
            check(mine.all { setOf(it.first, it.second) == setOf(Kind.B100, Kind.LOUD_RMS) }) { "a pair of group $g is not BODY 1 against LOUDER-RMS" }
            check(mine[0].first != mine[1].first) { "group $g asks its pair in the same order twice" }
            check(specs.first { it.prefix == g }.order.containsAll(listOf(Kind.B100, Kind.LOUD_RMS))) { "group $g does not hold both clips of its pair" }
        }
        check(pairs.zipWithNext().none { (a, b) -> a.group == b.group }) { "two pair widgets of one note are next to each other" }
        if (print) println("ARCO gate PAIRS ${pairs.joinToString("; ") { "${it.id} ${it.group}: ${it.first.key} then ${it.second.key}" }}: both orders at both notes, the two of a note apart: OK")
    }

    /** The five clips of the C3 ordering question are the plain and the four BODY clips, once each, and are shown out of order of BODY. */
    private fun guardOrderShown(shown: List<Kind>) {
        check(shown.toSet() == setOf(Kind.PLAIN, Kind.B060, Kind.B075, Kind.B090, Kind.B100) && shown.size == 5) { "the ordering question's clips are $shown" }
        check(!inOrder(shown.map { Math.round(it.body!! * 100f) })) { "the ordering question's clips are shown in order of BODY: $shown" }
    }

    /** The page gain is the one that puts THE PLAIN ONE at [AuditionLevel]'s loudness: the plain times it is [AuditionLevel.level]'s output on the plain. */
    private fun guardGain(label: String, plain: FloatArray, gain: Float) {
        val wanted = AuditionLevel.level(Snip(plain.copyOf(), channels = 1, sampleRate = Dsp.RATE)).samples
        check(wanted.size == plain.size && plain.indices.all { plain[it] * gain == wanted[it] }) { "$label: the page's gain is not AuditionLevel's gain for THE PLAIN ONE" }
    }

    /** No clip peaks over [PAGE_PEAK_BAR] at the page's gain, and none is silent. */
    private fun guardPeaks(label: String, clips: List<Pair<String, FloatArray>>, gain: Float) {
        for ((id, x) in clips) {
            val peak = peakOf(levelled(x, gain))
            check(peak <= PAGE_PEAK_BAR) { "$label $id peaks at ${ArcoWarmthMeasure.f3(peak.toDouble())} at the page's gain, over ${ArcoWarmthMeasure.f2(PAGE_PEAK_BAR.toDouble())}" }
            check(peak >= 0.001f) { "$label $id is silent at the page's gain (peak $peak)" }
        }
    }

    /** A group on the engine's default note labels itself so, and any other does not. */
    private fun guardDefaultNote(s: Spec) {
        check(isDefaultNote(s.voice, s.step) == ("DEFAULT NOTE" in s.label)) { "${s.label}: the label and the engine's default note disagree (the default note is step ${Arco.semitoneFor(s.voice, defaultTune(s.voice))}, this is step ${s.step})" }
    }

    /** THE PLAIN ONE of a group on the engine's default note is the engine's default render, bit for bit: it is what the owner's default knob plays. [engineDefault] is that render, made for every such group. */
    private fun guardPlainIsDefault(label: String, plain: FloatArray, engineDefault: FloatArray?) {
        check(engineDefault != null) { "$label: the group is on the engine's default note but no default render was made to compare with" }
        check(plain.contentEquals(engineDefault)) { "$label: THE PLAIN ONE is not the engine's default render" }
    }

    /** The repeat is the plain rendered again from the bow up, bit for bit. */
    private fun guardRepeat(label: String, plain: FloatArray, repeat: FloatArray) {
        check(repeat.size == plain.size && repeat.indices.all { repeat[it].toRawBits() == plain[it].toRawBits() }) { "$label: the repeat is not bit-identical to THE PLAIN ONE rendered again from the bow up" }
    }

    /**
     * A louder-only clip is the plain times ONE constant [flat] (every sample, exactly), and its rise over the plain, by rms ([byEar] false) or by the A-weighted rise over the steady window ([byEar] true), is within
     * [TWIN_BAR_DB] of [targetDb], BODY 1's own rise of the same kind.
     */
    private fun guardTwin(label: String, plain: FloatArray, twin: FloatArray, flat: Float, targetDb: Double, byEar: Boolean) {
        check(twin.size == plain.size) { "$label: ${twin.size} samples, THE PLAIN ONE ${plain.size}" }
        for (i in plain.indices) check(twin[i] == plain[i] * flat) { "$label: sample $i is not the plain times one flat gain" }
        val rise = if (byEar) ArcoWarmthMeasure.aRiseDb(twin, plain) else rmsDbRise(twin, plain)
        check(abs(rise - targetDb) <= TWIN_BAR_DB) {
            "$label: the flat clip's ${if (byEar) "A-weighted" else "rms"} rise is ${ArcoWarmthMeasure.s2(rise)} dB, BODY 1's ${ArcoWarmthMeasure.s2(targetDb)} (bar ${ArcoWarmthMeasure.f2(TWIN_BAR_DB)})"
        }
    }

    /** A relevelled clip is [source] times ONE gain [k] (every sample) and has the plain's [Loudness.of] to [LEVEL_BAR]. */
    private fun guardLevelled(label: String, source: FloatArray, out: FloatArray, k: Float, plainLoudness: Float) {
        check(out.size == source.size) { "$label: ${out.size} samples, its source ${source.size}" }
        for (i in source.indices) check(out[i] == source[i] * k) { "$label: sample $i is not its source times one gain" }
        val loud = ArcoLiftRig.loudnessOf(out)
        check(abs(loud - plainLoudness) / plainLoudness <= LEVEL_BAR) { "$label: Loudness.of is ${f4(loud.toDouble())}, the plain's ${f4(plainLoudness.toDouble())}" }
    }

    /** The R1D-GRADE control sees a real loss at the top and keeps the low end where it was: 3-8k in [DULL_TOP_MIN_DB], [DULL_TOP_MAX_DB]; 80-300 in [DULL_LOW_MIN_DB], [DULL_LOW_MAX_DB]. */
    private fun guardDull(label: String, bands: Array<Double?>) {
        val low = bands[0] ?: error("$label: no harmonic in 80-300")
        val top = bands[3] ?: error("$label: no harmonic in 3-8k")
        check(low >= DULL_LOW_MIN_DB && low <= DULL_LOW_MAX_DB) { "$label: 80-300 is ${ArcoWarmthMeasure.s2(low)} dB, outside $DULL_LOW_MIN_DB to $DULL_LOW_MAX_DB" }
        check(top <= DULL_TOP_MAX_DB && top >= DULL_TOP_MIN_DB) { "$label: 3-8k is ${ArcoWarmthMeasure.s2(top)} dB, outside $DULL_TOP_MIN_DB to $DULL_TOP_MAX_DB" }
    }

    /** OLD-WAY reads R1c's loss: 3-8k at most [OLD_WAY_TOP_MAX_DB] against the plain. */
    private fun guardOldWayReads(label: String, bands: Array<Double?>) {
        val top = bands[3] ?: error("$label: no harmonic in 3-8k")
        check(top <= OLD_WAY_TOP_MAX_DB) { "$label: 3-8k is ${ArcoWarmthMeasure.s2(top)} dB against the plain, not under ${ArcoWarmthMeasure.s2(OLD_WAY_TOP_MAX_DB)}: it does not read as R1c's relevelled clip" }
    }

    /** What a BODY clip of the engine must show: the two upper bands not under [TOP_FLOOR_DB] against the plain and, from BODY 0.75, the whole clip at least [RISE_MIN_DB] louder (ArcoBodyLiftTest's own bars, at the page's notes). */
    private fun guardEngineBody(label: String, n: ArcoWarmthMeasure.Numbers, body: Float) {
        for (b in ArcoWarmthMeasure.TOP_BANDS) {
            val v = n.bands[b] ?: error("$label: no harmonic in ${ArcoWarmthMeasure.BAND_LABELS[b]}")
            check(v >= TOP_FLOOR_DB) { "$label: ${ArcoWarmthMeasure.BAND_LABELS[b]} is ${ArcoWarmthMeasure.s2(v)} dB against the plain, under ${ArcoWarmthMeasure.s2(TOP_FLOOR_DB)}: the top is turned down" }
        }
        if (body >= 0.75f) check(n.rmsRise >= RISE_MIN_DB) { "$label: the whole clip is only ${ArcoWarmthMeasure.s2(n.rmsRise)} dB louder than the plain, under +$RISE_MIN_DB: there is no lift" }
    }

    /** The BODY ladder (the plain, then rising BODY) rises by at least [LADDER_STEP_MIN_DB] of rms at every step. [rises] are (name, rms rise over the plain), in order of BODY. */
    private fun guardLadder(label: String, rises: List<Pair<String, Double>>) {
        for ((a, b) in rises.zipWithNext()) {
            check(b.second - a.second >= LADDER_STEP_MIN_DB) { "$label: ${b.first} is ${ArcoWarmthMeasure.s2(b.second - a.second)} dB over ${a.first}, under $LADDER_STEP_MIN_DB: the BODY ladder is not a ladder" }
        }
    }

    /** One clip of a built group, as the identity guard and the key see it. */
    internal class Built(
        val id: String, val name: String, val kind: Kind, val letter: Char?, val samples: FloatArray,
        val numbers: ArcoWarmthMeasure.Numbers?, val lift: Arco.Lift?, val flat: Float? = null, val relevel: Float? = null,
    )

    /** A group built: the plain and its reading, the page's gain, the clips as shown (the plain first) and the numbers the flat gains were set from. */
    internal class BuiltGroup(
        val spec: Spec, val plain: FloatArray, val base: ArcoWarmthMeasure.Base, val gain: Float, val clips: List<Built>,
        val targetRmsDb: Double, val targetADb: Double, val plainLoudness: Float, val engineDefault: FloatArray? = null,
    ) {
        fun clip(kind: Kind): Built = clips.first { it.kind == kind }
        fun with(newClips: List<Built>) = BuiltGroup(spec, plain, base, gain, newClips, targetRmsDb, targetADb, plainLoudness, engineDefault)
    }

    /** No two clips of a group are the same samples, except the plain and its repeat, which must be, and, at C2, BODY 0.75 and BODY 1.0, which must be (the engine's cap makes them one sound there: a free noise check). */
    private fun guardIdentity(g: BuiltGroup) {
        for (i in g.clips.indices) for (j in i + 1 until g.clips.size) {
            val a = g.clips[i]
            val b = g.clips[j]
            val identical = a.samples.contentEquals(b.samples)
            val pair = setOf(a.kind, b.kind)
            if (pair == setOf(Kind.PLAIN, Kind.REPEAT)) check(identical) { "${g.spec.label}: ${a.id} and ${b.id} (the repeat) differ" }
            else if (g.spec.prefix == G_C2 && pair == setOf(Kind.B075, Kind.B100)) check(identical) { "${g.spec.label}: ${a.id} and ${b.id} are not the same samples, and at C2 the engine's cap must make them one sound" }
            else check(!identical) { "${g.spec.label}: ${a.id} (${a.kind.key}) and ${b.id} (${b.kind.key}) are the same clip" }
        }
    }

    /** At four or more lettered clips the measured whole-clip rms rise (rounded to 0.1 dB, so a tie is a tie) must not put the group in order, by lettered clips or as shown. */
    private fun guardMeasuredOrder(g: BuiltGroup) {
        if (g.spec.order.size < 4) return
        val rises = g.clips.map { Math.round((it.numbers?.rmsRise ?: 0.0) * 10.0).toInt() }
        check(!inOrder(rises.drop(1))) { "${g.spec.label}: the lettered clips are in order of measured rms rise $rises" }
        check(!inOrder(rises)) { "${g.spec.label}: the group as shown is in order of measured rms rise $rises" }
    }

    /** What a printed word or number on the page may not be: no number (a note name such as C3 is the one allowed), no `box`, no EQ words, nothing about how many dB, no expected direction, nothing that names a clip's kind. */
    private val FORBIDDEN_WORDS = Regex(
        "\\b(box|eq|warm\\w*|dbs?|decibels?|shelf|shelves|bell|bells|boost\\w*|lift\\w*|gain|repeat\\w*|twins?|controls?|old[- ]way|louder-only|filters?|resonan\\w*|treble|bass)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val NOTE_NAME = Regex("\\b[A-G]#?[0-9]\\b")

    private fun guardWords(texts: List<String>) {
        for (t in texts) {
            val stripped = NOTE_NAME.replace(t, "")
            check(stripped.none { it in '0'..'9' }) { "the page text has a number: \"$t\"" }
            val m = FORBIDDEN_WORDS.find(stripped)
            check(m == null) { "the page text has the word \"${m?.value}\": \"$t\"" }
        }
    }

    /** What the page text may not contain: a kind of clip, in any case. */
    private val LEAKS = listOf("louder-only", "louder-rms", "louder-a", "old-way", "r1d-grade", "oldway")

    /** The page: the real one (long enough), carrying this round's own paths and none of an earlier round's, and not naming a kind of clip. */
    private fun guardPage(text: String) {
        check(text.length >= MIN_PAGE_CHARS) { "the page is only ${text.length} characters (under $MIN_PAGE_CHARS): it is the placeholder, not the real page, and nothing was written" }
        for (must in listOf(PHASE, STORAGE_PREFIX, "manifest.json")) check(must in text) { "the page does not say \"$must\"" }
        for (stale in listOf("arco_r1e", "arco-warmth:", "arco_r1d", "arco_r1c")) check(stale !in text) { "the page still says \"$stale\", an earlier round's path" }
        for (leak in LEAKS) check(!text.contains(leak, ignoreCase = true)) { "the page names a kind of clip (\"$leak\"): it must stay blind" }
    }

    /** The manifest has no decimal number and none of the key's words. The page is published with it. */
    private fun guardBlind(manifest: String) {
        check(!Regex("[0-9]\\.[0-9]").containsMatchIn(manifest)) { "the manifest has a decimal number" }
        for (leak in LEAKS + listOf("sha256", "r1d", "repeat")) check(!manifest.contains(leak, ignoreCase = true)) { "the manifest names \"$leak\"" }
    }

    private fun guardJson(label: String, text: String) {
        try {
            Json.parse(text)
        } catch (e: JsonException) {
            error("$label is not valid JSON: ${e.message}")
        }
    }

    /** The key's hash of each file is the file's. */
    private fun guardHashes(expected: Map<String, String>, actual: Map<String, String>) {
        for ((path, h) in expected) check(actual[path] == h) { "$path: the key's hash is not the file's" }
        check(expected.keys == actual.keys) { "the files are not the key's list: ${(expected.keys - actual.keys) + (actual.keys - expected.keys)}" }
    }

    /** A written clip is the clip times the page's gain to a 16-bit step or two. */
    private fun guardWritten(file: File, expected: FloatArray) {
        val back = WavReader.read(file)
        check(back.channels == 1 && back.sampleRate == Dsp.RATE) { "${file.name}: ${back.channels} channels at ${back.sampleRate} Hz" }
        check(back.samples.size == expected.size) { "${file.name}: ${back.samples.size} samples, the clip ${expected.size}" }
        var worst = 0f
        for (i in expected.indices) worst = maxOf(worst, abs(back.samples[i] - expected[i]))
        check(worst <= 2f / 32768f) { "${file.name}: the file differs from the clip times the page's gain by $worst (a 16-bit step is ${1f / 32768f})" }
    }

    // ---- building a group ---------------------------------------------------------------------------------

    private class Made(val samples: FloatArray, val flat: Float?, val relevel: Float?)

    /** [x] through one RBJ high shelf of [db] at [hz] at the house rate: the R1D-GRADE control's shaping (the plain is already finished, so this is the last stage). */
    private fun highShelved(x: FloatArray, hz: Float, db: Float): FloatArray {
        val f = Dsp.Biquad()
        f.highShelf(hz, db, Dsp.RATE)
        return FloatArray(x.size) { f.process(x[it]) }
    }

    private fun flatOf(plain: FloatArray, db: Double): Made {
        val flat = 10.0.pow(db / 20.0).toFloat()
        return Made(FloatArray(plain.size) { plain[it] * flat }, flat, null)
    }

    private fun relevelled(source: FloatArray, plainLoudness: Float): Made {
        val k = plainLoudness / ArcoLiftRig.loudnessOf(source)
        return Made(FloatArray(source.size) { source[it] * k }, null, k)
    }

    /**
     * One group's clips. Everything comes from [Arco.render] (the plain at BODY 0.5, the four BODY clips) or from the plain or BODY 1 by one gain (and one high shelf for R1D-GRADE); the repeat is the plain bowed and finished
     * again by hand ([Arco.finished] of a fresh [Arco.bow]). No guard runs here, so the table can be printed before one can throw.
     */
    private fun build(spec: Spec): BuiltGroup {
        val voice = spec.voice
        val step = spec.step
        val f0 = ArcoBodyMeasure.f0Of(voice, step)
        fun macros(body: Float) = ArcoLiftRig.macros(voice, step, body)
        fun render(body: Float): FloatArray = Arco.render(voice, macros(body)).samples

        val plain = render(Arco.DEFAULT_BODY)
        val engineDefault: FloatArray? = if (isDefaultNote(voice, step)) Arco.render(voice).samples else null
        val plainMacros = macros(Arco.DEFAULT_BODY)
        val settledBody = Arco.settled(plainMacros, voice).getValue("BODY")
        val repeat = Arco.finished(ArcoLiftRig.rawOf(voice, plainMacros), voice, settledBody, ArcoLiftRig.RAW_RATE)

        val base = ArcoWarmthMeasure.Base(plain, f0)
        val bodies = HashMap<Kind, FloatArray>()
        val lifts = HashMap<Kind, Arco.Lift>()
        for (kind in spec.order) if (kind.isBody) {
            val body = kind.body!!
            val x = render(body)
            val measured = ArcoLiftRig.measured(voice, step, body)
            check(measured.samples.contentEquals(x)) { "${spec.label} ${kind.key} $body: the engine's measured finish is not Arco.render's clip" }
            bodies[kind] = x
            lifts[kind] = measured.lift
        }
        val top = bodies.getValue(Kind.B100)
        val targetRms = rmsDbRise(top, plain)
        val targetA = ArcoWarmthMeasure.aRiseDb(top, plain)
        val plainLoudness = ArcoLiftRig.loudnessOf(plain)

        val clips = ArrayList<Built>()
        clips += Built(spec.idOf(Kind.PLAIN), "THE PLAIN ONE", Kind.PLAIN, null, plain, null, null)
        for ((i, kind) in spec.order.withIndex()) {
            val made: Made = when (kind) {
                Kind.REPEAT -> Made(repeat, null, null)
                Kind.B060, Kind.B075, Kind.B090, Kind.B100 -> Made(bodies.getValue(kind), null, null)
                Kind.LOUD_RMS -> flatOf(plain, targetRms)
                Kind.LOUD_A -> flatOf(plain, targetA)
                Kind.OLD_WAY -> relevelled(top, plainLoudness)
                Kind.DULL -> relevelled(highShelved(plain, DULL_SHELF_HZ, DULL_SHELF_DB), plainLoudness)
                Kind.PLAIN -> error("the plain is not a lettered clip")
            }
            clips += Built(
                spec.idOf(kind), "CLIP ${spec.letters[i]}", kind, spec.letters[i], made.samples, ArcoWarmthMeasure.compare(made.samples, base), lifts[kind], made.flat, made.relevel,
            )
        }
        val gain = AUDITION_LEVEL / plainLoudness.coerceAtLeast(1e-9f)
        return BuiltGroup(spec, plain, base, gain, clips, targetRms, targetA, plainLoudness, engineDefault)
    }

    private fun bandsText(a: Array<Double?>): String = ArcoWarmthMeasure.BAND_LABELS.indices.joinToString(" ") { "${ArcoWarmthMeasure.BAND_LABELS[it]} ${a[it]?.let { v -> ArcoWarmthMeasure.s1(v) } ?: "."}" }

    private fun kindText(k: Kind) = if (k.isBody) "BODY ${k.body}" else k.key

    /** The group's table on `ARCO gate` lines, printed before any guard that can fail on it. */
    private fun printGroup(g: BuiltGroup) {
        val s = g.spec
        println(
            "ARCO gate GROUP ${s.label}: ${g.clips.size} clips, the plain's Loudness.of ${f4(g.plainLoudness.toDouble())}, the page's gain x${f4(g.gain.toDouble())} " +
                "(${ArcoWarmthMeasure.s2(20.0 * log10(g.gain.toDouble()))} dB) puts it at ${f4(AUDITION_LEVEL.toDouble())}; flat gains from BODY 1: rms ${ArcoWarmthMeasure.s2(g.targetRmsDb)} dB " +
                "(R1f predicted ${PREDICTED_RMS_DB[s.prefix]?.let { ArcoWarmthMeasure.s2(it) } ?: "n/a"}), A-weighted ${ArcoWarmthMeasure.s2(g.targetADb)} dB (R1f predicted ${PREDICTED_A_DB[s.prefix]?.let { ArcoWarmthMeasure.s2(it) } ?: "n/a"})",
        )
        println("ARCO gate TABLE ${s.prefix}: id letter kind | bands vs the plain ${ArcoWarmthMeasure.BAND_LABELS.joinToString(" ")} dB | rms, Loudness.of, A-weighted rise dB | peak finished, at the page's gain | the engine's lift asked / cap / delivered dB per element")
        for (c in g.clips) {
            val n = c.numbers
            val lift = c.lift?.let { " | lift ${ArcoWarmthMeasure.f2(it.asked.toDouble())} / ${ArcoWarmthMeasure.f2(it.cap.toDouble())} / ${ArcoWarmthMeasure.f2(it.delivered.toDouble())}${if (it.delivered < it.asked) " (cap acted)" else ""}" } ?: ""
            val extra = (c.flat?.let { " | flat gain ${ArcoWarmthMeasure.s2(20.0 * log10(it.toDouble()))} dB" } ?: "") + (c.relevel?.let { " | relevelling gain ${ArcoWarmthMeasure.s2(20.0 * log10(it.toDouble()))} dB" } ?: "")
            println(
                "ARCO gate TABLE ${s.prefix}: ${c.id.padEnd(9)} ${(c.letter?.toString() ?: "-")} ${kindText(c.kind).padEnd(10)} | ${n?.let { bandsText(it.bands) } ?: "reference"} | " +
                    "${n?.let { "${ArcoWarmthMeasure.s2(it.rmsRise)} ${ArcoWarmthMeasure.s2(it.loudRise)} ${ArcoWarmthMeasure.s2(it.aRise)}" } ?: "+0.00 +0.00 +0.00"} | " +
                    "${ArcoWarmthMeasure.f3(peakOf(c.samples).toDouble())}, ${ArcoWarmthMeasure.f3(peakOf(levelled(c.samples, g.gain)).toDouble())}$lift$extra",
            )
        }
    }

    /** Every guard on a built group, on its real numbers. */
    private fun checkGroup(g: BuiltGroup) {
        val s = g.spec
        val label = s.label
        for (c in g.clips) {
            check(finite(c.samples)) { "$label ${c.id}: a sample is not finite" }
            check(c.samples.size == g.plain.size) { "$label ${c.id}: ${c.samples.size} samples, THE PLAIN ONE ${g.plain.size}" }
            c.numbers?.let { check(it.finite()) { "$label ${c.id}: a ruler number is not finite" } }
        }
        guardDefaultNote(s)
        if (isDefaultNote(s.voice, s.step)) guardPlainIsDefault(label, g.plain, g.engineDefault)
        guardGain(label, g.plain, g.gain)
        guardPeaks(label, g.clips.map { it.id to it.samples }, g.gain)
        g.clips.firstOrNull { it.kind == Kind.REPEAT }?.let { guardRepeat(label, g.plain, it.samples) }
        g.clips.firstOrNull { it.kind == Kind.LOUD_RMS }?.let { guardTwin("$label LOUDER-RMS", g.plain, it.samples, it.flat!!, g.targetRmsDb, byEar = false) }
        g.clips.firstOrNull { it.kind == Kind.LOUD_A }?.let { guardTwin("$label LOUDER-A", g.plain, it.samples, it.flat!!, g.targetADb, byEar = true) }
        g.clips.firstOrNull { it.kind == Kind.OLD_WAY }?.let {
            guardLevelled("$label OLD-WAY", g.clip(Kind.B100).samples, it.samples, it.relevel!!, g.plainLoudness)
            guardOldWayReads("$label OLD-WAY", it.numbers!!.bands)
        }
        g.clips.firstOrNull { it.kind == Kind.DULL }?.let {
            guardLevelled("$label R1D-GRADE", highShelved(g.plain, DULL_SHELF_HZ, DULL_SHELF_DB), it.samples, it.relevel!!, g.plainLoudness)
            guardDull("$label R1D-GRADE", it.numbers!!.bands)
        }
        for (c in g.clips) if (c.kind.isBody && c.kind != Kind.B060) guardEngineBody("$label ${kindText(c.kind)}", c.numbers!!, c.kind.body!!)
        val ladder = ArrayList<Pair<String, Double>>()
        ladder += "THE PLAIN ONE" to 0.0
        for (k in listOf(Kind.B060, Kind.B075, Kind.B090, Kind.B100)) g.clips.firstOrNull { it.kind == k }?.let { ladder += kindText(k) to it.numbers!!.rmsRise }
        if (s.prefix != G_C2 && s.prefix != G_A5) guardLadder(label, ladder)
        guardIdentity(g)
        guardMeasuredOrder(g)
    }

    // ---- the controls: every guard shown to throw ------------------------------------------------------

    /** Each guard, called with the rule it keeps broken on purpose, on this run's real clips: it must throw, and with the message of that guard. The ones that must not throw are shown holding. */
    private fun runControls(groups: List<BuiltGroup>) {
        val c3 = groups.first { it.spec.prefix == G_C3 }
        val c5 = groups.first { it.spec.prefix == G_C5 }
        val c2 = groups.first { it.spec.prefix == G_C2 }
        val plain = c3.plain
        val top = c3.clip(Kind.B100)
        val loudRms = c3.clip(Kind.LOUD_RMS)
        val loudA = c3.clip(Kind.LOUD_A)
        val flatRms = loudRms.flat!!
        val flatA = loudA.flat!!

        // the shuffle rules, one broken at a time, on the sub-guard that keeps each rule
        val specs = SPECS
        val s3 = specs[0]
        val s2 = specs[1]
        val s5 = specs[2]
        val sA5 = specs[3]
        mustHold("shuffle: the page's own shuffle, every rule") { guardShuffles(specs, print = false) }
        mustThrow("shuffle: the repeat first", "first or last") { guardRepeatSlot(s3.copy(order = listOf(Kind.REPEAT) + s3.order.filter { it != Kind.REPEAT })) }
        mustThrow("shuffle: the repeat last", "first or last") { guardRepeatSlot(s5.copy(order = s5.order.filter { it != Kind.REPEAT } + Kind.REPEAT)) }
        mustThrow("shuffle: a louder-only clip next to BODY 1", "is next to BODY 1") { guardAdjacency(s5.copy(order = listOf(Kind.LOUD_A, Kind.REPEAT, Kind.B100, Kind.LOUD_RMS, Kind.B075, Kind.OLD_WAY))) }
        mustThrow("shuffle: OLD-WAY next to BODY 1", "OLD-WAY is next to BODY 1") { guardAdjacency(s5.copy(order = listOf(Kind.LOUD_A, Kind.REPEAT, Kind.B100, Kind.OLD_WAY, Kind.LOUD_RMS, Kind.B075))) }
        mustThrow("shuffle: a group in order of strength (the kinds sorted by shape)", "in order of strength by shape") { guardStrength(s3.copy(order = s3.order.sortedBy { strengthByShape(it) })) }
        mustThrow("shuffle: a group in order of strength (the kinds sorted down by loudness)", "in order of strength by loudness") { guardStrength(s5.copy(order = s5.order.sortedByDescending { strengthByLoudness(it) })) }
        mustThrow("shuffle: the BODY clips in order of BODY", "in order of BODY") {
            val ladder = listOf(Kind.B060, Kind.B075, Kind.B090, Kind.B100)
            val rest = listOf(Kind.DULL, Kind.LOUD_RMS, Kind.REPEAT, Kind.LOUD_A, Kind.OLD_WAY)
            guardLadderShown(s3.copy(order = listOf(rest[0], ladder[0], rest[1], ladder[1], rest[2], ladder[2], rest[3], ladder[3], rest[4])))
        }
        mustThrow("shuffle: the same kind in the same slot of two groups", "in slot") { guardSlots(listOf(s3, s2, s5, sA5.copy(order = listOf(Kind.B100, Kind.LOUD_RMS)))) }
        mustThrow("shuffle: letters repeated across the page", "not unique") { guardLetters(listOf(s3, s2.copy(letters = "CNP"), s5, sA5)) }
        mustThrow("shuffle: a letter that reads as 1 or 0", "I or O") { guardLetters(listOf(s3.copy(letters = "CDEFGHIKL"), s2, s5, sA5)) }
        mustThrow("shuffle: letters out of the page's order", "in order of the page") { guardLetters(listOf(s3, s2, sA5.copy(letters = "WX"), s5)) }
        mustThrow("shuffle: a clip in the shuffle twice", "twice") { guardKinds(s3.copy(order = s3.order.dropLast(1) + Kind.B075)) }
        mustThrow("shuffle: a group that is not the clips it should hold", "not the clips the group holds") { guardKinds(s5.copy(order = s5.order.dropLast(1) + Kind.DULL)) }
        mustHold("pairs: the page's own") { guardPairs(PAIRS, specs, print = false) }
        mustThrow("pairs: a note asked in one order twice", "same order twice") { guardPairs(listOf(PAIRS[0], PAIRS[1], PairSpec(Q_PAIRS[2], G_C3, Kind.B100, Kind.LOUD_RMS), PAIRS[3]), specs, print = false) }
        mustThrow("pairs: the two widgets of a note next to each other", "next to each other") { guardPairs(listOf(PAIRS[0], PairSpec(Q_PAIRS[1], G_C3, Kind.LOUD_RMS, Kind.B100), PairSpec(Q_PAIRS[2], G_C5, Kind.LOUD_RMS, Kind.B100), PairSpec(Q_PAIRS[3], G_C5, Kind.B100, Kind.LOUD_RMS)), specs, print = false) }
        mustHold("order question: the page's own") { guardOrderShown(ORDER_SHOWN) }
        mustThrow("order question: the five shown in order of BODY", "in order of BODY") { guardOrderShown(listOf(Kind.PLAIN, Kind.B060, Kind.B075, Kind.B090, Kind.B100)) }

        // the flat gains
        val offRms = flatRms * 10.0.pow(0.3 / 20.0).toFloat()
        val offA = flatA * 10.0.pow(-0.3 / 20.0).toFloat()
        mustHold("louder-only (rms): the real clip") { guardTwin("C3 LOUDER-RMS", plain, loudRms.samples, flatRms, c3.targetRmsDb, byEar = false) }
        mustHold("louder-only (A-weighted): the real clip") { guardTwin("C3 LOUDER-A", plain, loudA.samples, flatA, c3.targetADb, byEar = true) }
        mustThrow("louder-only (rms): a twin 0.3 dB off its target", "rise is") { guardTwin("C3 LOUDER-RMS off", plain, FloatArray(plain.size) { plain[it] * offRms }, offRms, c3.targetRmsDb, byEar = false) }
        mustThrow("louder-only (A-weighted): a twin 0.3 dB off its target", "rise is") { guardTwin("C3 LOUDER-A off", plain, FloatArray(plain.size) { plain[it] * offA }, offA, c3.targetADb, byEar = true) }
        mustThrow("louder-only: a twin that is not the plain times one constant", "is not the plain times one flat gain") {
            val bad = loudRms.samples.copyOf().also { it[it.size / 2] = Math.nextUp(it[it.size / 2]) }
            guardTwin("C3 LOUDER-RMS one sample", plain, bad, flatRms, c3.targetRmsDb, byEar = false)
        }
        mustThrow("louder-only: the A-weighted twin held to the rms target (the two differ by over 0.1 dB)", "rise is") {
            guardTwin("C3 LOUDER-A as rms", plain, loudA.samples, flatA, c3.targetRmsDb, byEar = false)
        }

        // the engine's default render and the default note (ERHU C5's TUNE float is not the default's, its note is)
        mustHold("default render: THE PLAIN ONE of C3 and of C5 is the engine's default render") {
            guardPlainIsDefault("C3", c3.plain, c3.engineDefault)
            guardPlainIsDefault("C5", c5.plain, c5.engineDefault)
        }
        mustThrow("default render: ERHU C5's plain replaced by a BODY 1 render", "not the engine's default render") { guardPlainIsDefault("C5", c5.clip(Kind.B100).samples, c5.engineDefault) }
        mustThrow("default render: CELLO C3's plain one float step off", "not the engine's default render") { guardPlainIsDefault("C3", ulp3(c3.plain), c3.engineDefault) }
        mustThrow("default render: a default-note group with no default render made", "no default render") { guardPlainIsDefault("C5", c5.plain, null) }
        mustHold("default note: the page's own four groups carry the right labels, and ERHU C5 is the default note though its TUNE float is not the default's") {
            for (sp in specs) guardDefaultNote(sp)
            check(ArcoBodyMeasure.tuneOf(ERHU, s5.step) != defaultTune(ERHU) && isDefaultNote(ERHU, s5.step)) { "ERHU C5 is no longer the case the guard was fixed for" }
        }
        mustThrow("default note: C2 labelled the default note", "disagree") { guardDefaultNote(s2.copy(label = "CELLO, C2 (THE DEFAULT NOTE)")) }
        mustThrow("default note: ERHU C5 not labelled the default note", "disagree") { guardDefaultNote(s5.copy(label = "ERHU, C5")) }

        // the page gain and the peak bar
        mustHold("gain: the page's own") { guardGain("C3", plain, c3.gain) }
        mustThrow("gain: 0.001 percent off", "not AuditionLevel's gain") { guardGain("C3", plain, c3.gain * 1.00001f) }
        mustHold("peak: every C3 clip at the page's gain") { guardPeaks("C3", c3.clips.map { it.id to it.samples }, c3.gain) }
        mustThrow("peak: a clip over 0.95 at the page's gain (the gain times 40)", "at the page's gain, over") { guardPeaks("C3", c3.clips.map { it.id to it.samples }, c3.gain * 40f) }
        mustThrow("peak: a silent clip", "is silent") { guardPeaks("C3", listOf("silent" to FloatArray(plain.size)), c3.gain) }

        // the repeat
        val ulp = ulp3(plain)
        mustHold("repeat: the real repeat") { guardRepeat("C3", plain, c3.clip(Kind.REPEAT).samples) }
        mustThrow("repeat: one sample one float step off", "bit-identical") { guardRepeat("C3", plain, ulp) }
        mustThrow("repeat: a different render (BODY 0.6)", "bit-identical") { guardRepeat("C3", plain, c3.clip(Kind.B060).samples) }

        // the R1D-GRADE control and OLD-WAY
        val dull = c3.clip(Kind.DULL)
        fun dullAt(db: Float, extra: Float = 1f): Array<Double?> {
            val m = relevelled(highShelved(plain, DULL_SHELF_HZ, db), c3.plainLoudness)
            return ArcoWarmthMeasure.compare(FloatArray(m.samples.size) { m.samples[it] * extra }, c3.base).bands
        }
        mustHold("R1D-GRADE: the real control") { guardDull("C3", dull.numbers!!.bands) }
        mustThrow("R1D-GRADE: a shelf of -2 dB (3-8k not 7.3 dB under)", "3-8k") { guardDull("C3 weak", dullAt(-2f)) }
        mustThrow("R1D-GRADE: a shelf of -40 dB (3-8k over 20 dB under)") { guardDull("C3 strong", dullAt(-40f)) }
        mustThrow("R1D-GRADE: 80-300 out of its band (the control 6 dB too loud)", "80-300") { guardDull("C3 loud", dullAt(DULL_SHELF_DB, 2f)) }
        mustThrow("R1D-GRADE: not relevelled to the plain's loudness", "Loudness.of") {
            val src = highShelved(plain, DULL_SHELF_HZ, DULL_SHELF_DB)
            guardLevelled("C3 R1D-GRADE unlevelled", src, src, 1f, c3.plainLoudness)
        }
        mustHold("OLD-WAY: the real clip is BODY 1 times one gain to the plain's loudness") { guardLevelled("C3 OLD-WAY", top.samples, c3.clip(Kind.OLD_WAY).samples, c3.clip(Kind.OLD_WAY).relevel!!, c3.plainLoudness) }
        mustThrow("OLD-WAY: BODY 1 left at its own level", "Loudness.of") { guardLevelled("C3 OLD-WAY", top.samples, top.samples, 1f, c3.plainLoudness) }
        mustThrow("OLD-WAY: a relevelled clip that is not BODY 1 times one gain", "is not its source times one gain") {
            val bad = c3.clip(Kind.OLD_WAY).samples.copyOf().also { it[it.size / 2] = Math.nextUp(it[it.size / 2]) }
            guardLevelled("C3 OLD-WAY one sample", top.samples, bad, c3.clip(Kind.OLD_WAY).relevel!!, c3.plainLoudness)
        }
        mustHold("OLD-WAY: the real clip reads R1c's loss") { guardOldWayReads("C3 OLD-WAY", c3.clip(Kind.OLD_WAY).numbers!!.bands) }
        mustThrow("OLD-WAY: reading BODY 1's own numbers (no loss at the top)", "does not read as R1c") { guardOldWayReads("C3 OLD-WAY", top.numbers!!.bands) }

        // the engine's own bars at the page's notes
        mustHold("engine: BODY 1 at C3 keeps the top and lifts") { guardEngineBody("C3 BODY 1", top.numbers!!, 1f) }
        mustThrow("engine: a relevelled BODY 1 (OLD-WAY) turns the top down", "the top is turned down") { guardEngineBody("C3 OLD-WAY", c3.clip(Kind.OLD_WAY).numbers!!, 1f) }
        mustThrow("engine: a BODY 0.6 clip asked to be a BODY 0.75 clip (too little lift)", "there is no lift") { guardEngineBody("C3 BODY 0.6", c3.clip(Kind.B060).numbers!!, 0.75f) }
        mustHold("ladder: C3's own") { guardLadder("C3", listOf("plain" to 0.0) + listOf(Kind.B060, Kind.B075, Kind.B090, Kind.B100).map { kindText(it) to c3.clip(it).numbers!!.rmsRise }) }
        mustThrow("ladder: BODY 0.9 and BODY 1 swapped", "not a ladder") {
            guardLadder("C3", listOf("plain" to 0.0) + listOf(Kind.B060, Kind.B075, Kind.B100, Kind.B090).map { kindText(it) to c3.clip(it).numbers!!.rmsRise })
        }

        // the identity of clips
        mustHold("identity: C3 as built") { guardIdentity(c3) }
        mustHold("identity: C5 as built") { guardIdentity(c5) }
        mustHold("identity: C2 as built (BODY 0.75 and BODY 1.0 are one sound)") { guardIdentity(c2) }
        mustThrow("identity: a louder-only clip that is the plain", "are the same clip") {
            guardIdentity(c3.with(c3.clips.map { if (it.kind == Kind.LOUD_RMS) Built(it.id, it.name, it.kind, it.letter, plain.copyOf(), it.numbers, it.lift, it.flat) else it }))
        }
        mustThrow("identity: C2's BODY 0.75 that is not BODY 1.0", "one sound") {
            guardIdentity(c2.with(c2.clips.map { if (it.kind == Kind.B075) Built(it.id, it.name, it.kind, it.letter, plain.copyOf(), it.numbers, it.lift) else it }))
        }
        mustThrow("identity: the repeat that is not the plain", "(the repeat) differ") {
            guardIdentity(c3.with(c3.clips.map { if (it.kind == Kind.REPEAT) Built(it.id, it.name, it.kind, it.letter, ulp, it.numbers, it.lift) else it }))
        }
        mustHold("measured order: C3 and C5 as built") { guardMeasuredOrder(c3); guardMeasuredOrder(c5) }
        mustThrow("measured order: C3 sorted by its measured rms rise", "in order of measured rms rise") {
            guardMeasuredOrder(c3.with(listOf(c3.clips.first()) + c3.clips.drop(1).sortedBy { it.numbers!!.rmsRise }))
        }

        // the words, the page, the manifest
        mustHold("words: a note name and a plain sentence") { guardWords(listOf("CELLO, C3 (THE DEFAULT NOTE)", "SAME AS THE PLAIN")) }
        mustThrow("words: a number", "has a number") { guardWords(listOf("BODY 0.75 against BODY 1")) }
        mustThrow("words: the word box", "\"box\"") { guardWords(listOf("the box rings")) }
        mustThrow("words: EQ", "\"EQ\"") { guardWords(listOf("an EQ move")) }
        mustThrow("words: warmer", "\"warmer\"") { guardWords(listOf("which sounds warmer")) }
        mustThrow("words: dB", "\"dB\"") { guardWords(listOf("it is louder by a few dB")) }
        mustThrow("words: the name of a kind", "\"repeat\"") { guardWords(listOf("the repeat of the plain")) }
        val longPage = "arco_r1h arco-body-gate: manifest.json " + "x".repeat(MIN_PAGE_CHARS)
        mustHold("page: a long page with this round's paths") { guardPage(longPage) }
        mustThrow("page: the one-line placeholder", "placeholder") { guardPage("<!doctype html><title>placeholder</title>") }
        mustThrow("page: an earlier round's path", "an earlier round's path") { guardPage("$longPage arco_r1e") }
        mustThrow("page: a leaked kind of clip", "kind of clip") { guardPage("$longPage LOUDER-ONLY") }
        mustThrow("page: no manifest", "does not say") { guardPage("arco_r1h arco-body-gate: " + "x".repeat(MIN_PAGE_CHARS)) }
        mustThrow("manifest: a decimal number", "decimal number") { guardBlind("{\"x\":\"0.75\"}") }
        mustThrow("manifest: a hash", "names") { guardBlind("{\"sha256\":\"abc\"}") }
        mustThrow("json: a manifest that does not parse", "not valid JSON") { guardJson("manifest", "{\"voices\":[") }
        mustHold("hashes: the same lists") { guardHashes(mapOf("a" to "1"), mapOf("a" to "1")) }
        mustThrow("hashes: a tampered hash", "hash is not the file's") { guardHashes(mapOf("a" to "1"), mapOf("a" to "2")) }
        mustThrow("hashes: a file the key does not list", "not the key's list") { guardHashes(mapOf("a" to "1"), mapOf("a" to "1", "b" to "2")) }
    }

    // ---- main ---------------------------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        val clipsOnly = "--clips-only" in args
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/arco-body-gate")
        check(root.name.startsWith("arco-body-gate")) { "the output folder is ${root.path}: it is emptied first, so it must be a folder named arco-body-gate" }
        // The key is a sibling of the published folder, so publishing the folder whole cannot publish it. A re-run starts from nothing: no clip, manifest or page of an earlier run survives a failed check.
        val keyFile = File(root.absoluteFile.parentFile, root.name + "-key.json")
        check(!keyFile.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "the key is inside the published folder" }
        root.deleteRecursively()
        keyFile.delete()

        // The page must be the real one: a placeholder is one line. This throws before any file is written (the folder is already empty). The development mode skips the page.
        val pageText: String? = if (clipsOnly) null else {
            val text = (ArcoBodyGateGenerator::class.java.getResourceAsStream(PAGE_RESOURCE)
                ?: error("the gate page is missing from synth/src/test/resources/audition/ (arco-body-gate.html), and nothing was written")).use { it.readBytes() }.toString(Charsets.UTF_8)
            guardPage(text)
            text
        }
        root.mkdirs()
        println("ARCO gate mode: ${if (clipsOnly) "--clips-only (clips, manifest and key; no index.html)" else "full run (the page is checked and copied)"}")

        for (spec in SPECS) {
            check(noteOf(spec.voice, spec.step) == spec.note) { "${spec.label} is on ${noteOf(spec.voice, spec.step)}" }
            check(Arco.semitoneFor(spec.voice, ArcoBodyMeasure.tuneOf(spec.voice, spec.step)) == spec.step) { "${spec.label}: the TUNE step does not round-trip" }
        }
        guardShuffles(SPECS)
        guardPairs(PAIRS, SPECS)
        guardOrderShown(ORDER_SHOWN)

        val groups = SPECS.parallelStream().map { build(it) }.toList()
        for (g in groups) printGroup(g)
        for (g in groups) checkGroup(g)

        // The ruler's own controls, on CELLO C3's plain.
        val c3 = groups.first { it.spec.prefix == G_C3 }
        val failures = ArcoWarmthMeasure.controlsFailures(c3.plain, ArcoBodyMeasure.f0Of(CELLO, c3.spec.step))
        println("ARCO gate ruler controls on CELLO C3: ${if (failures.isEmpty()) "all hold (self 0, times 2 reads +6.0206, the ideal shelf, the A-weighting against the standard's table)" else failures.joinToString("; ")}")
        check(failures.isEmpty()) { "the ruler's controls fail: $failures" }

        runControls(groups)

        // ---- write ----
        val hashes = LinkedHashMap<String, String>()
        var count = 0
        for (g in groups) for (c in g.clips) {
            val x = levelled(c.samples, g.gain)
            val file = File(File(root, g.spec.voice.name), "${c.id}.wav")
            WavWriter.write(file, Snip(x, channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_16)
            guardWritten(file, x)
            hashes["${g.spec.voice.name}/${c.id}.wav"] = sha256(file.readBytes())
            count++
        }
        check(count == 24) { "the page has $count clips, not 24" }
        val onDisk = HashMap<String, String>()
        for (voice in listOf(CELLO, ERHU)) for (f in File(root, voice.name).listFiles()!!.sortedBy { it.name }) onDisk["${voice.name}/${f.name}"] = sha256(f.readBytes())
        guardHashes(hashes, onDisk)
        // The repeat's file is THE PLAIN ONE's, byte for byte; at C2 BODY 0.75 and BODY 1.0 are the same file; no other two files are the same.
        for (g in groups) {
            val dir = File(root, g.spec.voice.name)
            val bytes = g.clips.associate { it.kind to File(dir, it.id + ".wav").readBytes() }
            g.clips.firstOrNull { it.kind == Kind.REPEAT }?.let { check(bytes.getValue(Kind.PLAIN).contentEquals(bytes.getValue(Kind.REPEAT))) { "${g.spec.label}: the repeat's file is not THE PLAIN ONE's, byte for byte" } }
            if (g.spec.prefix == G_C2) check(bytes.getValue(Kind.B075).contentEquals(bytes.getValue(Kind.B100))) { "${g.spec.label}: BODY 0.75 and BODY 1.0 are not the same file" }
        }
        val distinct = hashes.values.toSet().size
        println("ARCO gate files: $count clips written, $distinct distinct (the repeat is the plain's file, and at C2 BODY 0.75 and BODY 1.0 are one file)")
        check(distinct == count - 3) { "$distinct distinct files among $count clips, expected ${count - 3}: the two repeats and C2's pair are the only copies" }

        mustThrow("written: a file that is not the clip times the gain (the clip 0.1 percent louder)") {
            val g = c3
            guardWritten(File(File(root, g.spec.voice.name), "${g.clip(Kind.B100).id}.wav"), levelled(g.clip(Kind.B100).samples, g.gain * 1.001f))
        }

        val visible = ArrayList<String>()
        val manifest = manifestJson(groups, visible)
        guardWords(visible)
        guardBlind(manifest)
        guardJson("manifest.json", manifest)
        val key = keyJson(groups, hashes)
        guardJson("the key", key)
        File(root, "manifest.json").writeText(manifest)
        keyFile.writeText(key)
        if (pageText != null) File(root, "index.html").writeText(pageText)

        for (g in groups) println("ARCO gate KEY ${g.spec.label}: ${g.spec.letters.toList().zip(g.spec.order).joinToString(" ") { "${it.first}=${kindText(it.second)}" }}")
        for ((path, h) in hashes) println("ARCO gate HASH $path $h")
        println("ARCO gate KEY pairs: ${PAIRS.joinToString("; ") { p -> val s = SPECS.first { it.prefix == p.group }; "${p.id} ${p.group}: ${s.letterOf(p.first)} (${p.first.key}) then ${s.letterOf(p.second)} (${p.second.key})" }}")
        println("ARCO gate the key is ${keyFile.absolutePath}, beside the folder and not in it")
        println("wrote $count clips + manifest.json${if (pageText != null) " + index.html" else " (no index.html: --clips-only)"} under ${root.absolutePath} (publish that folder whole; the key is not in it)")
    }

    // ---- the manifest ------------------------------------------------------------------------------------

    private const val DOT = "·"

    private val INTRO = listOf(
        "Headphones, please.",
        "The clips are not the same loudness, on purpose. THE PLAIN ONE sets the level for its note, and some clips add sound to it, so they are louder than it is.",
        "Nobody has listened to these clips yet. Some of them may sound worse than THE PLAIN ONE. If one does, say so.",
        "Every clip has a button that plays THE PLAIN ONE and then the clip. You can play any clip as often as you like.",
        "You can only answer about a clip once you have played it.",
        "Your answers to the questions under the clips stay on this phone until you tap SAVE VERDICT at the very bottom of the page. Tap it when you are done.",
    )
    private const val VOICE_BODY = "Two notes. For each note, play THE PLAIN ONE first, then the clips under it."
    private const val PLAIN_DESC = "the reference: how it sounds with nothing changed. Every other clip in this group is judged against this one"
    /** A lettered clip carries no line under its name (the page's guide card already says they are unlabelled on purpose, and a line repeated under all 20 only lengthens the page). */
    private const val LETTER_DESC = ""
    private const val DEVICE_TEXT = "First question: what are you listening on?"
    private const val CHIP_TEXT = "Compared with THE PLAIN ONE, this clip sounds:"
    private const val CHIP_LOCKED = "Play CLIP {letter} first, then tap how it sounds."
    private const val PAIR_TEXT = "Which of these two sounds more like a bigger instrument, and not just a louder one?"
    private const val PAIR_LOCKED = "Tap PLAY A, THEN B and let it finish, then answer."
    private const val ORDER_TEXT = "Put these five in order, with the fullest one last."
    private const val ORDER_LOCKED = "Play all five clips first."
    private const val SAME_TEXT = "Do any two of them sound the same?"
    private const val SAME_NONE = "NO, ALL FIVE ARE DIFFERENT"
    private const val SAME_PICK = "Tick the clips that sound the same as each other."
    private const val NOTE_TEXT = "Anything else you noticed? Say it in your own words."
    private val KEEP_TEXT = mapOf(
        "CELLO" to "If you had to keep one of these at the very top of the BODY knob for the CELLO, which would it be?",
        "ERHU" to "If you had to keep one of these at the very top of the BODY knob for the ERHU, which would it be?",
    )

    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private fun arr(items: List<String>) = items.joinToString(",", "[", "]")

    private fun obj(vararg kv: Pair<String, String>) = kv.joinToString(",", "{", "}") { "${q(it.first)}:${it.second}" }

    private fun num(v: Double?, digits: Int = 3): String = if (v == null) "null" else "%.${digits}f".format(Locale.ROOT, v).let { if (it.trimStart('-').all { c -> c == '0' || c == '.' }) it.trimStart('-') else it }

    /** The group's clip ids of the BODY ladder as the page shows them: THE PLAIN ONE, then the lettered BODY clips in the group's order. */
    private fun ladderIds(spec: Spec): List<String> = listOf(spec.idOf(Kind.PLAIN)) + spec.order.filter { it.isBody }.map { spec.idOf(it) }

    private fun manifestJson(groups: List<BuiltGroup>, visible: MutableList<String>): String {
        fun v(s: String): String { visible += s; return q(s) }
        fun options(items: List<Pair<String, String>>) = arr(items.map { obj("v" to q(it.first), "t" to v(it.second)) })
        val voices = listOf(CELLO, ERHU).map { voice ->
            val mine = groups.filter { it.spec.voice == voice }
            val notes = mine.joinToString(" $DOT ") { it.spec.note }
            val letters = mine.joinToString(" $DOT ") { "${it.spec.note}: THE PLAIN ONE, CLIPS ${it.spec.letters.toList().joinToString(" ")}" }
            val groupJson = mine.map { g ->
                val clips = g.clips.map { c -> "[${q(c.id)},${v(c.name)},${v(if (c.kind == Kind.PLAIN) PLAIN_DESC else LETTER_DESC)}]" }
                obj("label" to v(g.spec.label), "key" to "true", "note" to q(g.spec.note), "prefix" to q(g.spec.prefix), "clips" to arr(clips))
            }
            obj(
                "id" to q(voice.name), "display" to q(voice.name), "body" to v(VOICE_BODY),
                "readout" to arr(listOf(v("$notes $DOT EVERY OTHER KNOB AT ITS DEFAULT"), v(letters.uppercase()))), "groups" to arr(groupJson),
            )
        }
        fun spec(prefix: String) = SPECS.first { it.prefix == prefix }
        val pairs = PAIRS.map { p ->
            val s = spec(p.group)
            obj(
                "id" to q(p.id), "voice" to q(s.voice.name), "group" to q(p.group), "note" to q(s.note),
                "a" to q(s.idOf(p.first)), "b" to q(s.idOf(p.second)),
            )
        }
        val c3 = spec(G_C3)
        val keeps = listOf(CELLO to G_C3, ERHU to G_C5).map { (voice, g) ->
            obj(
                "id" to q(if (voice == CELLO) Q_KEEP_CELLO else Q_KEEP_ERHU), "voice" to q(voice.name), "group" to q(g), "text" to v(KEEP_TEXT.getValue(voice.name)),
                "clips" to arr(ladderIds(spec(g)).map { q(it) }), "extra" to options(listOf(KEEP_NONE to "NONE OF THESE", KEEP_CANT to "CAN'T SAY")),
            )
        }
        val questions = obj(
            "required" to arr((listOf(Q_DEVICE) + Q_PAIRS).map { q(it) }),
            "counter" to obj(
                "some" to v("{n} REQUIRED QUESTIONS UNANSWERED"), "one" to v("ONE REQUIRED QUESTION UNANSWERED"), "none" to v("ALL REQUIRED QUESTIONS ANSWERED"),
                "unsent" to v("ALL ANSWERED. NOW TAP SAVE VERDICT AT THE BOTTOM"),
                "clips" to obj("some" to v("{n} OF {total} CLIPS HAVE NO ANSWER"), "one" to v("ONE OF {total} CLIPS HAS NO ANSWER"), "none" to v("EVERY CLIP HAS AN ANSWER")),
            ),
            "save" to obj(
                "some" to v("SAVE VERDICT $DOT {n} UNANSWERED"), "one" to v("SAVE VERDICT $DOT ONE UNANSWERED"), "none" to v("SAVE VERDICT"),
                "clips" to obj("some" to v("{n} CLIPS HAVE NO ANSWER"), "one" to v("ONE CLIP HAS NO ANSWER"), "none" to v("EVERY CLIP HAS AN ANSWER")),
            ),
            "device" to obj(
                "id" to q(Q_DEVICE), "first" to "true", "required" to "true", "text" to v(DEVICE_TEXT),
                "options" to options(listOf(DEVICE_HEADPHONES to "HEADPHONES", DEVICE_PHONE to "PHONE SPEAKER", DEVICE_OTHER to "SOMETHING ELSE")),
            ),
            "chips" to obj(
                "gate" to q("played"), "text" to v(CHIP_TEXT), "lockedText" to v(CHIP_LOCKED),
                "options" to arr(CHIPS.map { obj("v" to q(it.v), "t" to v(it.text), "hint" to v(it.hint)) }),
            ),
            "pair" to obj("gate" to q("pairRun"), "text" to v(PAIR_TEXT), "lockedText" to v(PAIR_LOCKED), "options" to options(listOf(PAIR_A to "A", PAIR_B to "B", PAIR_CANT to "CAN'T TELL"))),
            "pairs" to arr(pairs),
            "order" to obj(
                "id" to q(Q_ORDER_C3), "gate" to q("allPlayed"), "voice" to q(c3.voice.name), "group" to q(G_C3), "text" to v(ORDER_TEXT), "lockedText" to v(ORDER_LOCKED),
                "clips" to arr(ORDER_SHOWN.map { q(c3.idOf(it)) }), "sameId" to q(Q_SAME_C3), "sameText" to v(SAME_TEXT),
                "sameNone" to obj("v" to q("none"), "t" to v(SAME_NONE)), "samePick" to v(SAME_PICK),
                "answerFormat" to q("${Q_ORDER_C3}: the five clip ids joined by commas, least full first and fullest last. ${Q_SAME_C3}: none, or groups of clip ids joined by + and the groups joined by |"),
            ),
            "keep" to arr(keeps),
            "note" to obj("id" to q(Q_NOTE), "text" to v(NOTE_TEXT)),
        )
        return "{\"surfaceAfter\":null,\"phase\":${q(PHASE)},\"storagePrefix\":${q(STORAGE_PREFIX)},\"docs\":${obj("prefix" to q(DOC_PREFIX), "voiceKeys" to arr(listOf(q("CELLO"), q("ERHU"))), "overallKey" to q(OVERALL_KEY))}," +
            "\"intro\":${arr(INTRO.map { v(it) })},\n\"voices\": [\n${voices.joinToString(",\n")}\n],\"loops\": [],\n\"questions\":$questions,\n\"facts\":{}}\n"
    }

    // ---- the key -----------------------------------------------------------------------------------------

    private fun bandsJson(a: Array<Double?>?): String =
        if (a == null) "null" else "{" + ArcoWarmthMeasure.BAND_LABELS.indices.joinToString(",") { "\"${ArcoWarmthMeasure.BAND_LABELS[it]}\":${num(a[it])}" } + "}"

    /**
     * The key, for the lead and not for the page. Per group: the letters' meaning and every clip with its kind (`is`: PLAIN, REPEAT, BODY, LOUDER-RMS, LOUDER-A, OLD-WAY or R1D-GRADE), `body`, file and SHA-256,
     * the rises over the plain (`rmsRiseDb` the whole clip's RMS, `loudnessRiseDb` the engine meter's [Loudness.of], `aWeightedRiseDb` the ear's weighting over the steady window 0.4 to 1.0 s, `aWeightedRiseWholeClipDb`),
     * `bandsDbVsPlain` (absolute harmonic energy per band, 80-300 300-1k 1k-3k 3k-8k Hz, null where a band holds no harmonic), the peaks, the loudness at the page's gain, the engine's lift (`liftAsked`, `liftCap`,
     * `liftDelivered`, dB per element) for the BODY clips, and the flat gain or the relevelling gain that made a derived clip. Then the `pairs`, `order` and `keep` widgets by clip id, with what each clip is, in the manifest's order.
     */
    private fun keyJson(groups: List<BuiltGroup>, hashes: Map<String, String>): String {
        val rows = groups.joinToString(",\n") { g ->
            val s = g.spec
            val clips = g.clips.joinToString(",\n") { c ->
                val n = c.numbers
                val x = levelled(c.samples, g.gain)
                val core = "\"id\":${q(c.id)},\"letter\":${c.letter?.let { q(it.toString()) } ?: "null"},\"name\":${q(c.name)},\"is\":${q(c.kind.key)},\"body\":${num(c.kind.body?.toDouble())}," +
                    "\"file\":${q("${s.voice.name}/${c.id}.wav")},\"sha256\":${q(hashes.getValue("${s.voice.name}/${c.id}.wav"))},\"samples\":${c.samples.size}"
                val nums = if (n == null) "\"rmsRiseDb\":0.000,\"loudnessRiseDb\":0.000,\"aWeightedRiseDb\":0.000,\"aWeightedRiseWholeClipDb\":0.000,\"bandsDbVsPlain\":null"
                else "\"rmsRiseDb\":${num(n.rmsRise)},\"loudnessRiseDb\":${num(n.loudRise)},\"aWeightedRiseDb\":${num(n.aRise)},\"aWeightedRiseWholeClipDb\":${num(n.aRiseWhole)},\"bandsDbVsPlain\":${bandsJson(n.bands)}"
                val lift = c.lift?.let { ",\"liftAsked\":${num(it.asked.toDouble())},\"liftCap\":${num(it.cap.toDouble())},\"liftDelivered\":${num(it.delivered.toDouble())},\"liftCapActed\":${it.delivered < it.asked}" } ?: ""
                val made = (c.flat?.let { ",\"flatGainDb\":${num(20.0 * log10(it.toDouble()))}" } ?: "") + (c.relevel?.let { ",\"relevelGainDb\":${num(20.0 * log10(it.toDouble()))}" } ?: "")
                "    {$core,$nums,\"peakFinished\":${num(peakOf(c.samples).toDouble(), 4)},\"peakAtPageLevel\":${num(peakOf(x).toDouble(), 4)},\"loudnessAtPageLevel\":${num(ArcoLiftRig.loudnessOf(x).toDouble(), 5)}$lift$made}"
            }
            val map = s.letters.toList().zip(s.order).joinToString(",") { "\"${it.first}\":${q(kindText(it.second))}" }
            "  {\"voice\":${q(s.voice.name)},\"note\":${q(s.note)},\"prefix\":${q(s.prefix)},\"label\":${q(s.label)},\"pageGain\":${num(g.gain.toDouble(), 6)},\"pageGainDb\":${num(20.0 * log10(g.gain.toDouble()))}," +
                "\"plainLoudness\":${num(g.plainLoudness.toDouble(), 5)},\"flatRmsRiseDb\":${num(g.targetRmsDb)},\"flatARiseDb\":${num(g.targetADb)},\"letterIs\":{$map},\"clips\":[\n$clips\n  ]}"
        }
        fun spec(prefix: String) = SPECS.first { it.prefix == prefix }
        fun kindJson(k: Kind) = "\"is\":${q(k.key)},\"body\":${num(k.body?.toDouble())}"
        val pairs = PAIRS.joinToString(",") { p ->
            val s = spec(p.group)
            "{\"id\":${q(p.id)},\"group\":${q(p.group)},\"a\":${q(s.idOf(p.first))},\"aIs\":${q(p.first.key)},\"b\":${q(s.idOf(p.second))},\"bIs\":${q(p.second.key)}}"
        }
        fun clipRefs(spec: Spec, kinds: List<Kind>) = arr(kinds.map { "{\"id\":${q(spec.idOf(it))},${kindJson(it)}}" })
        val c3 = spec(G_C3)
        val order = "{\"id\":${q(Q_ORDER_C3)},\"sameId\":${q(Q_SAME_C3)},\"group\":${q(G_C3)},\"clips\":${clipRefs(c3, ORDER_SHOWN)}}"
        val keep = listOf(CELLO to G_C3, ERHU to G_C5).joinToString(",") { (voice, g) ->
            val s = spec(g)
            "{\"id\":${q(if (voice == CELLO) Q_KEEP_CELLO else Q_KEEP_ERHU)},\"voice\":${q(voice.name)},\"group\":${q(g)},\"clips\":${clipRefs(s, listOf(Kind.PLAIN) + s.order.filter { it.isBody })}}"
        }
        return "{\"note\":${q("the key to the ARCO R1h body gate page: do not publish. The clips are NOT level on purpose: one gain per group (pageGain) is applied to every clip of the group, so a clip's level against THE PLAIN ONE is its rmsRiseDb, loudnessRiseDb (the engine's Loudness.of) and aWeightedRiseDb (the ear's weighting, steady window). bandsDbVsPlain are absolute harmonic-energy deltas in dB (80-300, 300-1k, 1k-3k, 3k-8k Hz), no loudness taken off; null where the band holds no harmonic. Every clip is Arco.render times the group's gain; LOUDER-RMS and LOUDER-A are the plain times one flat gain (flatGainDb) equal to BODY 1's rms rise and A-weighted rise; OLD-WAY is BODY 1 times one gain to the plain's Loudness.of; R1D-GRADE is the plain through a high shelf of -12 dB at 2 kHz times one gain to the plain's Loudness.of. letterIs, pairs, order and keep say which letter is which. A ruler, not audibility; nobody has listened.")}," +
            "\"phase\":${q(PHASE)},\"docPrefix\":${q(DOC_PREFIX)},\"pairs\":[$pairs],\"order\":$order,\"keep\":[$keep],\"groups\":[\n$rows\n]}\n"
    }
}
