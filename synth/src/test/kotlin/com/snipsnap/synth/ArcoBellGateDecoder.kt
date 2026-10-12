package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.ArcoBellGateGenerator.Comparison
import com.snipsnap.synth.ArcoBellGateGenerator.Role
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * Reads what the owner saved on the ARCO R1j bell gate page and applies the FINAL reading rules, which the lead pre-registered before anyone listened (the review's "STRICT + P2 gate + keep veto" family): the reading of a
 * comparison from its two orders (X-STRICT, X-LEAN, T-STRICT, T-LEAN, TIE, CONFLICT, MISSING), P1 (is the page readable), the identical pair as a noise sign, P2 (is C2's lift heard at all: a gate), P3 (the counts) and the
 * build map with its three case lines. Run `./gradlew :synth:decodeArcoBellGate -PgateVerdicts=<dir or file>[,<dir or file>...]` (it reads `testkit/arco-bell-gate-key.json` and prints one report per verdict path), or
 * `ArcoBellGateDecoder <key file> <verdict path>...`. Written and run on made-up verdict files before the page is published. The decoder prints facts and the rules' outcomes; it never says how a sound sounds, and nothing
 * it prints is a listening claim: it is the owner's taps, mapped back through the private key to what each clip was. Every printed fact is labelled MEASURED (a number from the key or from the page's own play counts),
 * HEARD (the owner's recorded word: the page's own words inside double quotes, the owner's free text as a JSON-quoted string) or INFERRED (the decoder's reading, never a rule, never used by the build map). Printed
 * lines start `ARCO bell decode`; each report ends with ONE line starting `ARCO bell decode RESULT:` that names the case line that applied (or the ending, or why none did).
 *
 * The rules are printed before any answer is read, word for word as the lead wrote them (section A of the fix brief; the same text is in the list [RULES] and here). Nothing is argued with afterwards.
 *  - **Reading a comparison.** Each candidate (MORE BELL, BELL ONLY, MORE SHELF) is asked against TODAY in both orders at C2 (and at F#2, if the owner did that section). "X over T" for one comparison reads: **X-STRICT** if the candidate was chosen in both orders; **X-LEAN** if chosen in one order and "I can't tell" in the other; **T-STRICT** if TODAY was chosen in both orders; **T-LEAN** if TODAY once and "I can't tell" once; **TIE** if "I can't tell" in both; **CONFLICT** if the candidate was chosen in one order and TODAY in the other (that is a position habit, not hearing); **MISSING** if an order has no counted answer (a pair answer with no recorded PLAY A, THEN B run is not counted). PLAIN vs TODAY at C2 reads the same way with THE PLAIN ONE in the place of the candidate.
 *  - **P1 (the page is readable).** The device question is answered, and at C2 fewer than 3 of the four non-identical comparisons are CONFLICT. Otherwise the page is VOID: re-run it, read nothing. P1 catches only a strong position habit; noise is the job of the build rule below, not of P1. Before P1: if no document can be read, or it is not the page's, or it holds no answer, the result is NOT A VOID but a different ending (see E1).
 *  - **The identical pair is never a gate.** "I can't tell" is the clean answer; A or B on it is printed as NOISE SIGN beside the verdicts and repeated on the RESULT line.
 *  - **P2 (is C2's lift heard at all).** PLAIN vs TODAY at C2 reads **heard** only if TODAY was chosen in at least one order and THE PLAIN ONE in neither (T-STRICT or T-LEAN from the plain's point of view). If it reads TIE, CONFLICT or MISSING: C2's lift was not heard here, no candidate can be read against it: the result is **LEAVE C2 AS IT IS (C2's lift was not heard here: listen again on other speakers before reading this as a result)**. If THE PLAIN ONE was chosen in at least one order and TODAY in neither: a louder clip was called the less full one, so either "fuller" does not follow level for this owner or the answers are noise: the result is **ASK THE OWNER**. Never a build.
 *  - **P3 (direction).** Print each candidate's counts at C2 and at F#2: chosen, TODAY chosen, "can't tell", and the per-note reading above.
 *  - **Build map (printed as written; exactly one line applies and the decoder prints it with the evidence that led to it).** A candidate **QUALIFIES** only if it is X-STRICT at C2 (chosen over TODAY in both C2 orders) and, if F#2 was answered, its F#2 reading is not T-STRICT, T-LEAN or CONFLICT (TIE, X-LEAN, X-STRICT or not answered all pass). X-LEAN at C2 never qualifies; it is printed as "weak lean, not enough". Let QS be the candidates that qualify. With P2 heard:
 *  - 1. QS empty: **LEAVE C2 AS IT IS** (record the result; TODAY stays).
 *  - 2. QS has one candidate: if the C2 keep names that candidate's version, or says "I can't say", or is unanswered: **BUILD that direction** (MORE BELL = the engine's shelf set to half the bell on the notes whose cap binds; BELL ONLY = the bell alone there; MORE SHELF = the shelf alone there), at the capped notes only; print what F#2 said. If the C2 keep names anything else (TODAY's version, THE PLAIN ONE, another candidate, "none, the plain one is better", "all of them sound too thick or boomy"): **ASK THE OWNER** (print the keep answer).
 *  - 3. QS has two or three candidates: if the C2 keep names one of them, **BUILD that**; otherwise **ASK THE OWNER**.
 *  - The F#2 keep is printed only. A keep answer alone never triggers a build.
 *  - **The RESULT line carries** P2's reading, the C2 keep answer, NOISE SIGN yes or no, and each qualifying or leaning candidate's counts at C2 and F#2, for example: `CASE LINE 2 applied: BUILD BELL ONLY (C2 2 of 2 over TODAY, F#2 1 of 2 over TODAY, 0 TODAY; P2 heard; C2 keep BELL ONLY; identical pair: can't tell)`.
 *
 * What the rules do not say, and the decoder therefore fixes here (printed as ADDITION lines with the rules; R1h's decoder behaved the same way: a missing or wrong field is reported as such and never guessed).
 *  - A pair answer counts only if the page recorded that widget's own PLAY A, THEN B run to its end at least once and a play of both its clips (R1h's gate). Otherwise it is IGNORED and listed, and its comparison has an order with no counted answer.
 *  - A comparison with an order that has no counted answer reads MISSING. MISSING is not X-STRICT, X-LEAN, T-STRICT, T-LEAN, TIE or CONFLICT, and it is never guessed.
 *  - P1 is read as: VOID if the device question is not answered or 3 or more of the four non-identical C2 comparisons are CONFLICT; READABLE if the device is answered and CONFLICT plus MISSING among those four are at most 2; otherwise UNDECIDED (answers are missing and the missing ones could still void the page). On a VOID page nothing after P1 is read; on an UNDECIDED page the result is NO CASE LINE APPLIES YET.
 *  - The endings before P1 (the rules' "see E1"), tried in this order, none of them a VOID: (1) NO DOCUMENT READ when no document could be read (a folder with no file of the expected name is read only if it holds exactly one .json file; a UTF-8 byte order mark is stripped before parsing; an unreadable file is listed; the folder's file names are listed); (2) DOC SHAPE NOT RECOGNISED, top-level keys: [...] when the document holds none of the keys q_*, plays or savedAt; (3) NO ANSWERS SAVED: N runs recorded, no answer filled: the owner probably did not tap SAVE VERDICT; their answers stay on their phone, ask them to open the page and tap SAVE VERDICT, when every question of the page is blank (absent, null or blank text); N is the sum of the PLAY A, THEN B runs the doc records and may be 0. PAGE VOID stays for a device that is truly unanswered inside a document that has other answers.
 *  - A path that does not exist prints NO DOCUMENT READ (path not found) as that path's result, and the run goes on to the next path; the build does not fail.
 *  - The order of reading after P1 is: P2 first (not heard: LEAVE C2 AS IT IS; the plain chosen: ASK THE OWNER), then the build map. A page whose P2 reads heard but in which a candidate has an order with no counted answer at C2 has no case line yet: NO CASE LINE APPLIES YET, with the answers still needed. P2 with an order that has no counted answer reads MISSING and leaves, as the rules say.
 *  - F#2 for a candidate: a reading of MISSING (an order has no counted answer, or neither has) passes the F#2 test, as "not answered" does; the counts at F#2 are printed so that a single answer can be seen. The F#2 section is optional.
 *  - A C2 keep with no counted answer names nothing: in case line 2 that is allowed, in case line 3 it gives ASK THE OWNER. Keep answers are accepted if the value is a clip of that widget, none, thick or cant; anything else is IGNORED and listed, and so counts as unanswered. A keep answer needs no play count.
 *  - In the RESULT line a candidate's counts read "C2 a of 2 over TODAY, b TODAY, c can't tell" (the answers out of the two orders, with "d not answered" added when an order has no counted answer) and the same for F#2; NOISE SIGN is yes when the identical pair was answered A or B, and no when it was answered "I can't tell" or not at all.
 *  - Every printed fact is labelled MEASURED, HEARD or INFERRED. The owner's recorded words are printed inside double quotes (the page's own words), and the owner's free note, file names and other text are printed as JSON-quoted strings (escaped, inside double quotes); those are the only places a sound word may appear. The INFERRED ruler line says which rulers (rms, Loudness.of, A-weighted) the owner's pick goes with or against; it is printed only when P1 reads READABLE (never on an UNDECIDED or VOID page), it is not a rule and the build map never reads it.
 *
 * The saved doc ([ArcoBellGateGenerator]'s contract; the page writes it through R1e's saving logic under the db path `verdicts/arco_r1j_overall`; all values are strings or numbers): `{q_device, q_pair_1 .. q_pair_15,
 * q_keep_C2, q_keep_F2, q_note, plays, savedAt}`. `q_device` is one of the six device answers; each q_pair is `a`, `b` or `cant` (`a` and `b` are the first and second clip of the widget, as the key lists them); each
 * q_keep is the id of a clip of that widget, `none`, `thick` or `cant`; `plays` is `{<clip id>: <starts>, <pair widget id>: <PLAY A, THEN B runs that reached the end>}`. A field that is empty or absent is
 * unanswered; the F#2 fields are optional. The page may keep other bookkeeping beside these; the decoder reads only the fields above. The verdict path is a folder holding `arco_r1j_overall.json` (also `overall.json`,
 * `verdicts_arco_r1j_overall.json` or `verdicts` and then `arco_r1j_overall.json`; if none of these is there and the folder holds exactly one `.json` file, that one is read; the file is the doc, or `{"data": <the doc>}` as a
 * store export may wrap it), or one file with the doc itself, `{"data": <the doc>}`, `{"overall": <the doc>}` or `{"verdicts/arco_r1j_overall": <the doc>}`. A UTF-8 byte order mark at the start of a file is stripped. A file that
 * is empty or cut off (not valid JSON) is listed as UNREADABLE with its name; it never stops the report.
 *
 * Self-check. Before it reads any real file the decoder runs itself on invented answers (SELFCHECK lines): the nine ways two orders can read, every case line (and the ways case lines 2 and 3 ask the owner), the P2 gate
 * (not heard, the plain chosen, heard), the F#2 test, the void page, the incomplete page, the endings before P1 (no document, shape not recognised, no answers saved, path not found), the identical pair, the play gate, the loader
 * forms, the words law on everything it prints (the owner's free text included), all 6561 ways to answer the eight C2 pair widgets (no build without P2 heard, a candidate chosen over TODAY in both C2 orders and three decisive
 * answers; no single decisive answer builds), and the page's words against `testkit/arco-bell-gate/manifest.json` when that file is there. A SELFCHECK control calls the same check with a wrong expectation and requires it
 * to throw. A SELFCHECK failure stops the run before any real file is read. R1h's decoder had no test class, so none is added: this is the same proof, inside the main.
 */
object ArcoBellGateDecoder {

    private val G = ArcoBellGateGenerator

    /** Every printed line starts with this. */
    private const val P = "ARCO bell decode"

    /** P1: the page is VOID when MORE than this many of the four non-identical C2 comparisons are CONFLICT (3 or more are a strong position habit). A rule fixed before anyone listened. Not a listening value. */
    private const val P1_MAX_CONFLICTS = 2

    /** Two rulers are called level in a comparison when they differ by no more than this many dB (the INFERRED ruler line only, never a rule). Not a listening value. */
    private const val RULER_LEVEL_DB = 0.05

    /** The made-up answers of the self-check count each clip as played this many times. Not a listening value. */
    private const val SELF_PLAYS = 2

    /** How many of a folder's file names are listed on the NO DOCUMENT READ ending. Not a listening value. */
    private const val MAX_LISTED = 30

    /** The lead's final reading rules (section A of the fix brief), word for word: the text the decoder prints before it reads any answer. */
    internal val RULES: List<String> = listOf(
        "**Reading a comparison.** Each candidate (MORE BELL, BELL ONLY, MORE SHELF) is asked against TODAY in both orders at C2 (and at F#2, if the owner did that section). \"X over T\" for one comparison reads: **X-STRICT** if the candidate was chosen in both orders; **X-LEAN** if chosen in one order and \"I can't tell\" in the other; **T-STRICT** if TODAY was chosen in both orders; **T-LEAN** if TODAY once and \"I can't tell\" once; **TIE** if \"I can't tell\" in both; **CONFLICT** if the candidate was chosen in one order and TODAY in the other (that is a position habit, not hearing); **MISSING** if an order has no counted answer (a pair answer with no recorded PLAY A, THEN B run is not counted). PLAIN vs TODAY at C2 reads the same way with THE PLAIN ONE in the place of the candidate.",
        "**P1 (the page is readable).** The device question is answered, and at C2 fewer than 3 of the four non-identical comparisons are CONFLICT. Otherwise the page is VOID: re-run it, read nothing. P1 catches only a strong position habit; noise is the job of the build rule below, not of P1. Before P1: if no document can be read, or it is not the page's, or it holds no answer, the result is NOT A VOID but a different ending (see E1).",
        "**The identical pair is never a gate.** \"I can't tell\" is the clean answer; A or B on it is printed as NOISE SIGN beside the verdicts and repeated on the RESULT line.",
        "**P2 (is C2's lift heard at all).** PLAIN vs TODAY at C2 reads **heard** only if TODAY was chosen in at least one order and THE PLAIN ONE in neither (T-STRICT or T-LEAN from the plain's point of view). If it reads TIE, CONFLICT or MISSING: C2's lift was not heard here, no candidate can be read against it: the result is **LEAVE C2 AS IT IS (C2's lift was not heard here: listen again on other speakers before reading this as a result)**. If THE PLAIN ONE was chosen in at least one order and TODAY in neither: a louder clip was called the less full one, so either \"fuller\" does not follow level for this owner or the answers are noise: the result is **ASK THE OWNER**. Never a build.",
        "**P3 (direction).** Print each candidate's counts at C2 and at F#2: chosen, TODAY chosen, \"can't tell\", and the per-note reading above.",
        "**Build map (printed as written; exactly one line applies and the decoder prints it with the evidence that led to it).** A candidate **QUALIFIES** only if it is X-STRICT at C2 (chosen over TODAY in both C2 orders) and, if F#2 was answered, its F#2 reading is not T-STRICT, T-LEAN or CONFLICT (TIE, X-LEAN, X-STRICT or not answered all pass). X-LEAN at C2 never qualifies; it is printed as \"weak lean, not enough\". Let QS be the candidates that qualify. With P2 heard:",
        "1. QS empty: **LEAVE C2 AS IT IS** (record the result; TODAY stays).",
        "2. QS has one candidate: if the C2 keep names that candidate's version, or says \"I can't say\", or is unanswered: **BUILD that direction** (MORE BELL = the engine's shelf set to half the bell on the notes whose cap binds; BELL ONLY = the bell alone there; MORE SHELF = the shelf alone there), at the capped notes only; print what F#2 said. If the C2 keep names anything else (TODAY's version, THE PLAIN ONE, another candidate, \"none, the plain one is better\", \"all of them sound too thick or boomy\"): **ASK THE OWNER** (print the keep answer).",
        "3. QS has two or three candidates: if the C2 keep names one of them, **BUILD that**; otherwise **ASK THE OWNER**.",
        "The F#2 keep is printed only. A keep answer alone never triggers a build.",
        "**The RESULT line carries** P2's reading, the C2 keep answer, NOISE SIGN yes or no, and each qualifying or leaning candidate's counts at C2 and F#2, for example: `CASE LINE 2 applied: BUILD BELL ONLY (C2 2 of 2 over TODAY, F#2 1 of 2 over TODAY, 0 TODAY; P2 heard; C2 keep BELL ONLY; identical pair: can't tell)`.",
    )

    /** The decoder's own rules for what the final rules do not say, printed with the rules (see the class KDoc). */
    internal val ADDITIONS: List<String> = listOf(
        "A pair answer counts only if the page recorded that widget's own PLAY A, THEN B run to its end at least once and a play of both its clips (R1h's gate). Otherwise it is IGNORED and listed, and its comparison has an order with no counted answer.",
        "A comparison with an order that has no counted answer reads MISSING. MISSING is not X-STRICT, X-LEAN, T-STRICT, T-LEAN, TIE or CONFLICT, and it is never guessed.",
        "P1 is read as: VOID if the device question is not answered or 3 or more of the four non-identical C2 comparisons are CONFLICT; READABLE if the device is answered and CONFLICT plus MISSING among those four are at most 2; otherwise UNDECIDED (answers are missing and the missing ones could still void the page). On a VOID page nothing after P1 is read; on an UNDECIDED page the result is NO CASE LINE APPLIES YET.",
        "The endings before P1 (the rules' \"see E1\"), tried in this order, none of them a VOID: (1) NO DOCUMENT READ when no document could be read (a folder with no file of the expected name is read only if it holds exactly one .json file; a UTF-8 byte order mark is stripped before parsing; an unreadable file is listed; the folder's file names are listed); (2) DOC SHAPE NOT RECOGNISED, top-level keys: [...] when the document holds none of the keys q_*, plays or savedAt; (3) NO ANSWERS SAVED: N runs recorded, no answer filled: the owner probably did not tap SAVE VERDICT; their answers stay on their phone, ask them to open the page and tap SAVE VERDICT, when every question of the page is blank (absent, null or blank text); N is the sum of the PLAY A, THEN B runs the doc records and may be 0. PAGE VOID stays for a device that is truly unanswered inside a document that has other answers.",
        "A path that does not exist prints NO DOCUMENT READ (path not found) as that path's result, and the run goes on to the next path; the build does not fail.",
        "The order of reading after P1 is: P2 first (not heard: LEAVE C2 AS IT IS; the plain chosen: ASK THE OWNER), then the build map. A page whose P2 reads heard but in which a candidate has an order with no counted answer at C2 has no case line yet: NO CASE LINE APPLIES YET, with the answers still needed. P2 with an order that has no counted answer reads MISSING and leaves, as the rules say.",
        "F#2 for a candidate: a reading of MISSING (an order has no counted answer, or neither has) passes the F#2 test, as \"not answered\" does; the counts at F#2 are printed so that a single answer can be seen. The F#2 section is optional.",
        "A C2 keep with no counted answer names nothing: in case line 2 that is allowed, in case line 3 it gives ASK THE OWNER. Keep answers are accepted if the value is a clip of that widget, none, thick or cant; anything else is IGNORED and listed, and so counts as unanswered. A keep answer needs no play count.",
        "In the RESULT line a candidate's counts read \"C2 a of 2 over TODAY, b TODAY, c can't tell\" (the answers out of the two orders, with \"d not answered\" added when an order has no counted answer) and the same for F#2; NOISE SIGN is yes when the identical pair was answered A or B, and no when it was answered \"I can't tell\" or not at all.",
        "Every printed fact is labelled MEASURED, HEARD or INFERRED. The owner's recorded words are printed inside double quotes (the page's own words), and the owner's free note, file names and other text are printed as JSON-quoted strings (escaped, inside double quotes); those are the only places a sound word may appear. The INFERRED ruler line says which rulers (rms, Loudness.of, A-weighted) the owner's pick goes with or against; it is printed only when P1 reads READABLE (never on an UNDECIDED or VOID page), it is not a rule and the build map never reads it.",
    )

    // ---- the page's words (the owner's recorded words, as the manifest shows them) -------------------------

    /** The three pair answers, in the page's words. The self-check holds these to the generated manifest when it is there. */
    internal val PAIR_WORDS: Map<String, String> = mapOf(G.PAIR_A to "A SOUNDS FULLER", G.PAIR_B to "B SOUNDS FULLER", G.PAIR_CANT to "I CAN'T TELL")

    /** The keep answers besides a clip, in the page's words. */
    internal val KEEP_WORDS: Map<String, String> = mapOf(G.KEEP_NONE to "NONE, THE PLAIN ONE IS BETTER", G.KEEP_THICK to "ALL OF THEM SOUND TOO THICK OR BOOMY", G.KEEP_CANT to "I CAN'T SAY")

    /** The six device answers, in the page's words. */
    internal val DEVICE_WORDS: Map<String, String> = mapOf(
        G.DEVICE_HEADPHONES to "HEADPHONES", G.DEVICE_EARBUDS to "EARBUDS", G.DEVICE_PHONE to "PHONE SPEAKER", G.DEVICE_LAPTOP to "LAPTOP SPEAKER",
        G.DEVICE_LOW to "A SPEAKER THAT GOES LOW", G.DEVICE_OTHER to "SOMETHING ELSE",
    )

    /** What each direction the build map can name means for the engine (the rules' parentheses). */
    private val DIRECTION_TEXT: Map<String, String> = mapOf(
        Role.MORE_BELL.key to "the engine's shelf set to half the bell on the notes whose cap binds",
        Role.BELL_ONLY.key to "the bell alone there",
        Role.MORE_SHELF.key to "the shelf alone there",
    )

    /** The three candidates, in the order the rules list them. */
    private val CANDIDATES: List<Role> = listOf(Role.MORE_BELL, Role.BELL_ONLY, Role.MORE_SHELF)

    // ---- the key -----------------------------------------------------------------------------------------

    /** What the key says a clip is: its group, letter (null for THE PLAIN ONE), role ([Role.key]), lift pair and its rises over the plain (MEASURED, absolute, not level-matched), and its file's hash. */
    internal class KeyClip(
        val id: String, val group: String, val letter: String?, val role: String, val shelfDb: Double?, val bellDb: Double?,
        val rmsRise: Double, val loudRise: Double, val aRise: Double, val sha256: String,
    )

    internal class KeyPair(val id: String, val group: String, val comparison: String, val a: String, val aIs: String, val b: String, val bIs: String)

    internal class KeepClip(val id: String, val letter: String, val role: String)

    internal class KeyKeep(val id: String, val group: String, val plain: String, val clips: List<KeepClip>)

    /** The key, parsed: the groups in the page's order, their note names, every clip by id, the 15 pair widgets and the two keep widgets. */
    internal class Key(val groups: List<String>, val notes: Map<String, String>, val clips: Map<String, KeyClip>, val pairs: List<KeyPair>, val keeps: List<KeyKeep>) {
        fun clipOfRole(group: String, role: Role): KeyClip = clips.values.first { it.group == group && it.role == role.key }

        fun keepOf(group: String): KeyKeep = keeps.first { it.group == group }

        /** The widgets of one comparison in one group, in the page's order. */
        fun widgets(group: String, c: Comparison): List<KeyPair> = pairs.filter { it.group == group && it.comparison == c.key }
    }

    private fun numOf(v: JsonValue?): Double? = (v as? JsonValue.Num)?.value

    private fun strOf(v: JsonValue?): String? = (v as? JsonValue.Str)?.value

    internal fun loadKey(file: File): Key {
        check(file.isFile) { "the key ${file.path} is missing: run :synth:generateArcoBellGate first (-PclipsOnly is enough)" }
        val root = Json.parse(file.readText(Charsets.UTF_8)).obj()
        check(root.getValue("phase").str() == G.PHASE) { "the key is for phase ${root.getValue("phase").str()}, not ${G.PHASE}" }
        val groups = ArrayList<String>()
        val notes = LinkedHashMap<String, String>()
        val clips = LinkedHashMap<String, KeyClip>()
        for (g in root.getValue("groups").arr()) {
            val go = g.obj()
            val prefix = go.getValue("prefix").str()
            groups += prefix
            notes[prefix] = go.getValue("note").str()
            for (c in go.getValue("clips").arr()) {
                val co = c.obj()
                val id = co.getValue("id").str()
                clips[id] = KeyClip(
                    id, prefix, strOf(co["letter"]), co.getValue("is").str(), numOf(co["shelfDb"]), numOf(co["bellDb"]),
                    co.getValue("rmsRiseDb").num(), co.getValue("loudnessRiseDb").num(), co.getValue("aWeightedRiseDb").num(), co.getValue("sha256").str(),
                )
            }
        }
        val pairs = root.getValue("pairs").arr().map {
            val o = it.obj()
            KeyPair(o.getValue("id").str(), o.getValue("group").str(), o.getValue("comparison").str(), o.getValue("a").str(), o.getValue("aIs").str(), o.getValue("b").str(), o.getValue("bIs").str())
        }
        val keeps = root.getValue("keep").arr().map {
            val o = it.obj()
            KeyKeep(
                o.getValue("id").str(), o.getValue("group").str(), o.getValue("plain").str(),
                o.getValue("clips").arr().map { c -> val co = c.obj(); KeepClip(co.getValue("id").str(), co.getValue("letter").str(), co.getValue("is").str()) },
            )
        }
        return Key(groups, notes, clips, pairs, keeps).also { checkKey(it) }
    }

    /** The key is the one this decoder was written for: a key that is not stops the run, because nothing it prints could be trusted. */
    private fun checkKey(k: Key) {
        check(k.groups == listOf(G.G_C2, G.G_F2)) { "the key's groups are ${k.groups}, not ${listOf(G.G_C2, G.G_F2)}" }
        check(k.pairs.map { it.id } == G.Q_PAIRS) { "the key's widgets are ${k.pairs.map { it.id }}, not ${G.Q_PAIRS}" }
        check(k.keeps.map { it.id } == listOf(G.Q_KEEP_C2, G.Q_KEEP_F2) && k.keeps.map { it.group } == k.groups) { "the key's keep widgets are ${k.keeps.map { it.id }}, not the C2 and F#2 ones" }
        check(k.clips.values.count { it.group == G.G_C2 } == 6 && k.clips.values.count { it.group == G.G_F2 } == 5) { "the key does not list six C2 clips and five F#2 clips" }
        val roles = Role.entries.map { it.key }.toSet()
        for (c in k.clips.values) check(c.role in roles) { "${c.id}: the key says it is ${c.role}, which is no role" }
        for (p in k.pairs) {
            val a = k.clips[p.a]
            val b = k.clips[p.b]
            check(a != null && b != null && a.group == p.group && b.group == p.group) { "${p.id}: plays a clip the key does not list in its group" }
            check(a.role == p.aIs && b.role == p.bIs) { "${p.id}: the key's roles for its clips disagree with its own list" }
        }
        for (group in k.groups) for (c in Comparison.entries.filter { it != Comparison.IDENTICAL }) {
            val ws = k.widgets(group, c)
            if (group == G.G_F2 && c == Comparison.VS_PLAIN) {
                check(ws.isEmpty()) { "F#2 has a PLAIN comparison, which the page does not ask" }
                continue
            }
            check(ws.size == 2) { "$group ${c.key}: ${ws.size} widgets, not two" }
            check(ws.all { setOf(it.aIs, it.bIs) == setOf(c.candidate.key, Role.TODAY.key) }) { "$group ${c.key}: a widget is not TODAY against ${c.candidate.key}" }
            check(ws[0].aIs != ws[1].aIs) { "$group ${c.key}: asked in the same order twice" }
        }
        val same = k.widgets(G.G_C2, Comparison.IDENTICAL)
        check(same.size == 1 && setOf(same[0].aIs, same[0].bIs) == setOf(Role.TODAY_COPY.key, Role.TODAY.key)) { "the key's identical pair is not TODAY-COPY against TODAY" }
        check(k.clips.getValue(same[0].a).sha256 == k.clips.getValue(same[0].b).sha256) { "the key's identical pair has two different hashes" }
        for (kp in k.keeps) {
            check(kp.plain in k.clips && kp.clips.all { it.id in k.clips && k.clips.getValue(it.id).role == it.role }) { "${kp.id}: the key's keep widget disagrees with its clips" }
            check(kp.clips.map { it.role }.toSet() == ArcoBellGateGenerator.LIFTED.map { it.key }.toSet()) { "${kp.id}: the keep widget does not hold the four versions" }
        }
    }

    // ---- the verdict docs ----------------------------------------------------------------------------------

    /**
     * What was found under one verdict path: the doc (null when none could be read), the files that were unreadable (each already as a JSON-quoted name with its reason), the folder's entries when the path was a folder (a
     * sub-folder ends in a slash), the file the doc came from and, when that file did not have the expected name, why it was chosen, and [pathMissing] when the path does not exist at all.
     */
    internal class Saved(
        val doc: Map<String, JsonValue>?, val unreadable: List<String> = emptyList(), val folderFiles: List<String>? = null, val readFrom: String? = null, val how: String? = null,
        val pathMissing: Boolean = false,
    )

    private fun looksLikeDoc(o: Map<String, JsonValue>): Boolean = o.keys.any { it == "savedAt" || it == G.PLAYS || it.startsWith("q_") }

    /** [s] as a JSON string literal (quote, backslash and control characters escaped, other characters as themselves): the only way text the owner or a file supplied is printed, so no quote or line break in it can break a printed line. */
    internal fun jq(s: String): String = Json.write(JsonValue.Str(s))

    /** The doc inside a parsed file: the doc itself, `{"data": doc}`, `{"overall": doc}` or `{"verdicts/arco_r1j_overall": doc}`; null when the value is not a JSON object. */
    internal fun docFromValue(root: JsonValue): Map<String, JsonValue>? {
        val o = (root as? JsonValue.Obj)?.entries ?: return null
        val named = o[G.OVERALL_KEY] ?: o["${G.DOC_PREFIX}${G.OVERALL_KEY}"]
        val chosen = if (named is JsonValue.Obj && !looksLikeDoc(o)) named.entries else o
        val inner = chosen["data"]
        return if (inner is JsonValue.Obj && !looksLikeDoc(chosen)) inner.entries else chosen
    }

    /** Parses [text] (a UTF-8 byte order mark is stripped first); a cut-off or empty file is listed in [unreadable] under [name] and read as nothing answered. */
    internal fun readDoc(text: String, name: String, unreadable: MutableList<String>): Map<String, JsonValue>? {
        val root = try {
            Json.parse(text.removePrefix("\uFEFF"))
        } catch (e: JsonException) {
            unreadable += "${jq(name)} (${jq(e.message?.take(80) ?: "not valid JSON")})"
            return null
        }
        val doc = docFromValue(root)
        if (doc == null) unreadable += "${jq(name)} (not a JSON object)"
        return doc
    }

    private fun readFile(file: File, unreadable: MutableList<String>): Map<String, JsonValue>? {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            unreadable += "${jq(file.name)} (could not be read: ${e.javaClass.simpleName})"
            return null
        }
        return readDoc(text, file.name, unreadable)
    }

    /**
     * Which file of a folder is the doc, from the folder's file names (a file of a `verdicts` sub-folder is listed as `verdicts/<name>`): the first of the expected names that is there; else the only `.json` file at the top
     * of the folder, with the reason it was chosen; else none.
     */
    internal fun chooseFile(names: List<String>): Pair<String?, String?> {
        val bare = G.DOC_PREFIX.removePrefix("verdicts/") + G.OVERALL_KEY + ".json"
        val expected = listOf(bare, "${G.OVERALL_KEY}.json", "verdicts_$bare", "verdicts/$bare")
        expected.firstOrNull { it in names }?.let { return it to null }
        val jsons = names.filter { '/' !in it && it.endsWith(".json", ignoreCase = true) }
        return if (jsons.size == 1) jsons.single() to "the only .json file in the folder: none has the expected name" else null to null
    }

    /** Reads the saved doc under [path]. */
    internal fun loadVerdict(path: File): Saved {
        if (!path.exists()) return Saved(null, pathMissing = true)
        val unreadable = ArrayList<String>()
        if (path.isDirectory) {
            val entries = (path.listFiles() ?: emptyArray()).sortedBy { it.name }
            val shown = entries.map { if (it.isDirectory) it.name + "/" else it.name }
            val inVerdicts = File(path, "verdicts").listFiles()?.filter { it.isFile }?.map { "verdicts/${it.name}" } ?: emptyList()
            val (chosen, how) = chooseFile(entries.filter { it.isFile }.map { it.name } + inVerdicts)
            val doc = chosen?.let { readFile(File(path, it), unreadable) }
            return Saved(doc, unreadable, shown, chosen, how)
        }
        val doc = readFile(path, unreadable)
        return Saved(doc, unreadable, null, path.name)
    }

    // ---- what counts as an answer ------------------------------------------------------------------------

    /**
     * What the owner's answers amount to, after the gates: the device (null if unanswered or not one of the six), the accepted pair answers by widget id, the play counts of the clips and, apart from them, the PLAY A, THEN B
     * runs of each widget, the accepted keep answers by keep id, the note, when it was saved, and what was ignored and why.
     */
    internal class Answers(
        val device: String?, val pairs: Map<String, String>, val plays: Map<String, Int>, val runs: Map<String, Int>, val keep: Map<String, String>,
        val note: String?, val savedAt: String?, val ignored: List<String>,
    )

    private fun kindOf(v: JsonValue): String = v::class.simpleName ?: "something else"

    /** The text of field [name], trimmed; null when it is absent, null or empty (unanswered); a field of the wrong type is listed in [ignored] and read as unanswered. */
    private fun textOf(doc: Map<String, JsonValue>, name: String, ignored: MutableList<String>): String? {
        val v = doc[name] ?: return null
        return when (v) {
            is JsonValue.Str -> v.value.trim().ifEmpty { null }
            is JsonValue.Null -> null
            else -> {
                ignored += "$name: expected text, found ${kindOf(v)}: read as unanswered"
                null
            }
        }
    }

    internal fun answersOf(key: Key, saved: Saved): Answers {
        val doc = saved.doc ?: emptyMap()
        val ignored = ArrayList<String>()
        val plays = HashMap<String, Int>()
        val runs = HashMap<String, Int>()
        val pairIds = key.pairs.map { it.id }.toSet()
        when (val p = doc[G.PLAYS]) {
            null, is JsonValue.Null -> Unit
            is JsonValue.Obj -> for ((id, n) in p.entries) {
                val isClip = id in key.clips
                if (!isClip && id !in pairIds) { ignored += "plays of ${jq(id)}: not a clip or a pair widget of the key"; continue }
                val count = numOf(n)
                if (count == null || !count.isFinite() || count < 0) { ignored += "plays of $id: not a count (${(n as? JsonValue.Num)?.value ?: kindOf(n)})"; continue }
                if (isClip) plays[id] = count.toInt() else runs[id] = count.toInt()
            }
            else -> ignored += "${G.PLAYS}: expected an object of counts, found ${kindOf(p)}: read as no plays recorded"
        }
        val device = textOf(doc, G.Q_DEVICE, ignored)?.let {
            if (it in DEVICE_WORDS) it else { ignored += "${G.Q_DEVICE}: ${jq(it)} is not one of ${DEVICE_WORDS.keys}"; null }
        }
        val pairs = LinkedHashMap<String, String>()
        for (p in key.pairs) {
            val a = textOf(doc, p.id, ignored) ?: continue
            when {
                a !in PAIR_WORDS -> ignored += "${p.id}: ${jq(a)} is not a, b or cant"
                (plays[p.a] ?: 0) < 1 || (plays[p.b] ?: 0) < 1 -> ignored += "${p.id}: answered without both clips played, so it does not count"
                (runs[p.id] ?: 0) < 1 -> ignored += "${p.id}: answered without its own PLAY A, THEN B run to the end, so it does not count"
                else -> pairs[p.id] = a
            }
        }
        val keep = LinkedHashMap<String, String>()
        for (k in key.keeps) {
            val a = textOf(doc, k.id, ignored) ?: continue
            if (a in KEEP_WORDS || k.clips.any { it.id == a }) keep[k.id] = a else ignored += "${k.id}: ${jq(a)} is not one of its clips or a keep answer the page offers"
        }
        return Answers(device, pairs, plays, runs, keep, textOf(doc, G.Q_NOTE, ignored), textOf(doc, "savedAt", ignored), ignored)
    }

    /** Every question the page saves: the device, the 15 pairs, the two keeps and the note. */
    private fun questionIds(key: Key): List<String> = listOf(G.Q_DEVICE) + key.pairs.map { it.id } + key.keeps.map { it.id } + G.Q_NOTE

    /** Whether [v] holds no answer: absent, null, or text of blank characters. A value of another type is not blank (the page did not write it; it is reported as ignored later). */
    private fun isBlank(v: JsonValue?): Boolean = v == null || v is JsonValue.Null || (v is JsonValue.Str && v.value.isBlank())

    /** The PLAY A, THEN B runs that reached their end, summed over the widgets: the counts the page saved under the pair widgets' ids. */
    private fun runsRecorded(key: Key, doc: Map<String, JsonValue>): Int {
        val plays = doc[G.PLAYS] as? JsonValue.Obj ?: return 0
        val ids = key.pairs.map { it.id }.toSet()
        return plays.entries.filter { it.key in ids }.values.sumOf { v -> (numOf(v)?.takeIf { it.isFinite() && it > 0 } ?: 0.0).toInt() }
    }

    // ---- the reading ---------------------------------------------------------------------------------------

    /** What the owner chose in one widget of a comparison: the candidate (X), TODAY (T), or neither (can't tell). */
    internal enum class Pk { X, T, CANT }

    /** The reading of a comparison: the rules' seven, MISSING being an order with no counted answer (never guessed). [label] is how it prints. */
    internal enum class Vd(val label: String) {
        X_STRICT("X-STRICT"), X_LEAN("X-LEAN"), T_STRICT("T-STRICT"), T_LEAN("T-LEAN"), TIE("TIE"), CONFLICT("CONFLICT"), MISSING("MISSING"),
    }

    /** The rules' reading of a comparison from its two orders (null is an order with no counted answer). */
    internal fun verdictOf(first: Pk?, second: Pk?): Vd = when {
        first == null || second == null -> Vd.MISSING
        (first == Pk.X && second == Pk.T) || (first == Pk.T && second == Pk.X) -> Vd.CONFLICT
        first == Pk.CANT && second == Pk.CANT -> Vd.TIE
        first == Pk.X && second == Pk.X -> Vd.X_STRICT
        first == Pk.X || second == Pk.X -> Vd.X_LEAN
        first == Pk.T && second == Pk.T -> Vd.T_STRICT
        else -> Vd.T_LEAN
    }

    /** One widget as the owner answered it: the counted answer (null if none), what it picked and which role that is. */
    internal class OrderRead(val pair: KeyPair, val answer: String?, val pick: Pk?, val pickedRole: String?)

    /** One comparison of one group: the candidate against TODAY, its two orders and its reading. */
    internal class Cmp(val group: String, val comparison: Comparison, val orders: List<OrderRead>, val verdict: Vd)

    /** The counts of a comparison's two orders: the candidate chosen, TODAY chosen, can't tell, no counted answer. */
    private class Counts(val chosen: Int, val today: Int, val cant: Int, val none: Int)

    private fun countsOf(c: Cmp) = Counts(c.orders.count { it.pick == Pk.X }, c.orders.count { it.pick == Pk.T }, c.orders.count { it.pick == Pk.CANT }, c.orders.count { it.pick == null })

    /** The counts as the RESULT line writes them: "2 of 2 over TODAY, 0 TODAY, 0 can't tell" (and ", 1 not answered" when an order has no counted answer). */
    private fun countsShort(c: Cmp): String {
        val n = countsOf(c)
        return "${n.chosen} of ${c.orders.size} over TODAY, ${n.today} TODAY, ${n.cant} can't tell" + if (n.none > 0) ", ${n.none} not answered" else ""
    }

    /** The counts as the P3 line writes them. */
    private fun countsLong(c: Cmp): String {
        val n = countsOf(c)
        return "chosen ${n.chosen}, TODAY chosen ${n.today}, can't tell ${n.cant}, not answered ${n.none}"
    }

    private fun roleOfAnswer(p: KeyPair, answer: String?): String? = when (answer) {
        G.PAIR_A -> p.aIs
        G.PAIR_B -> p.bIs
        else -> null
    }

    private fun readOrder(p: KeyPair, candidate: Role, a: Answers): OrderRead {
        val answer = a.pairs[p.id]
        val role = roleOfAnswer(p, answer)
        val pick = when {
            answer == null -> null
            answer == G.PAIR_CANT -> Pk.CANT
            role == candidate.key -> Pk.X
            role == Role.TODAY.key -> Pk.T
            else -> error("${p.id}: the key's roles ${p.aIs} and ${p.bIs} are not ${candidate.key} and TODAY")
        }
        return OrderRead(p, answer, pick, role)
    }

    private fun comparisonsOf(key: Key, a: Answers, group: String): List<Cmp> =
        Comparison.entries.filter { it != Comparison.IDENTICAL }.mapNotNull { c ->
            val ws = key.widgets(group, c)
            if (ws.isEmpty()) null
            else {
                val orders = ws.map { readOrder(it, c.candidate, a) }
                Cmp(group, c, orders, verdictOf(orders[0].pick, orders[1].pick))
            }
        }

    /** Why a comparison read as it did. */
    private fun reasonOf(c: Cmp): String = when (c.verdict) {
        Vd.X_STRICT -> "X chosen in both orders"
        Vd.X_LEAN -> "X chosen in one order, can't tell in the other"
        Vd.T_STRICT -> "TODAY chosen in both orders"
        Vd.T_LEAN -> "TODAY chosen in one order, can't tell in the other"
        Vd.TIE -> "can't tell in both orders"
        Vd.CONFLICT -> "X chosen in one order and T in the other: a position habit, not hearing"
        Vd.MISSING -> "an order has no counted answer: not guessed"
    }

    /** One candidate with its C2 comparison and its F#2 comparison (the F#2 section is optional, so the latter may read MISSING). */
    private class Cand(val role: Role, val c2: Cmp, val f2: Cmp) {
        /** The F#2 test: a reading of T-STRICT, T-LEAN or CONFLICT keeps the candidate out; TIE, X-LEAN, X-STRICT and not answered (MISSING) pass. */
        val f2Veto: Boolean get() = f2.verdict == Vd.T_STRICT || f2.verdict == Vd.T_LEAN || f2.verdict == Vd.CONFLICT

        /** The candidate QUALIFIES: X-STRICT at C2 and not kept out at F#2. */
        val qualifies: Boolean get() = c2.verdict == Vd.X_STRICT && !f2Veto
    }

    /** What P2 reads from PLAIN vs TODAY at C2 (the plain in the place of the candidate). */
    internal enum class P2Read { HEARD, NOT_HEARD, PLAIN_CHOSEN }

    /** Which page state P1 gives: see ADDITIONS. */
    internal enum class State { READABLE, VOID, UNDECIDED }

    /**
     * How a report ends: the path does not exist, no document was read, the document's shape is not the page's, the document holds no answer, the page is void, no case line can apply yet (answers missing), P2 sends it
     * to LEAVE or to ASK, or one of the build map's case lines (2 and 3 either build or ask).
     */
    internal enum class Outcome { NOT_FOUND, NO_DOCUMENT, SHAPE, NO_ANSWERS, VOID, INCOMPLETE, P2_LEAVE, P2_ASK, CASE_1, CASE_2_BUILD, CASE_2_ASK, CASE_3_BUILD, CASE_3_ASK }

    /**
     * A decoded report: the printed lines (all but the last), the one RESULT line and its [body] (the line without the source), how it ended, P1's state and P2's reading (null when not read), every comparison's reading by
     * `<group>/<comparison key>` (empty before P1), QS (the candidates that qualify; empty when not read) and the role the build map names to build (null if none).
     */
    internal class Report(
        val source: String, val lines: List<String>, val result: String, val body: String, val outcome: Outcome, val p1: State?, val p2: P2Read?, val verdicts: Map<String, Vd>, val qs: List<String>, val built: String?,
    ) {
        fun print() {
            for (l in lines) println(l)
            println(result)
        }
    }

    // ---- printing helpers ------------------------------------------------------------------------------------

    private fun nice(role: String) = role.replace('-', ' ')

    /** The last part of a verdict path, so the RESULT line stays short (the full path is in the report's header line). */
    private fun shortName(source: String): String = File(source).name.ifEmpty { source }

    private fun signed(x: Double) = "%+.2f".format(Locale.ROOT, x)

    private fun dbText(x: Double) = "%.2f dB".format(Locale.ROOT, x)

    private fun clipText(c: KeyClip): String = if (c.letter == null) "${c.role} (${c.id})" else "${c.role} = CLIP ${c.letter} (${c.id})"

    private fun liftText(c: KeyClip): String = if (c.shelfDb == null || c.bellDb == null) "no lift pair" else "shelf ${dbText(c.shelfDb)} / bell ${dbText(c.bellDb)}"

    private fun risesText(c: KeyClip): String = "${c.role} rms ${signed(c.rmsRise)} dB, Loudness.of ${signed(c.loudRise)} dB, A-weighted ${signed(c.aRise)} dB"

    /** The page's own words for an answer, inside double quotes, with the HEARD label. */
    private fun owner(words: Map<String, String>, answer: String): String = "\"${words.getValue(answer)}\" (HEARD)"

    private fun labelsLine(): String =
        "$P LABELS: MEASURED is a number from the key or the page's play counts; HEARD is the owner's recorded word, inside double quotes (the page's own words) or as a JSON-quoted string (the owner's own text); " +
            "INFERRED is a reading by the decoder, never a rule and never used by the build map. Nobody on the team has listened."

    private fun filesText(names: List<String>): String {
        val shown = names.take(MAX_LISTED).joinToString(", ") { jq(it) }
        return "[$shown${if (names.size > MAX_LISTED) ", ... ${names.size - MAX_LISTED} more" else ""}]"
    }

    /** The comparison's lines: both orders with the owner's answers and the measured rises of A and B, then the reading, the measured gap and, on a READABLE page, the INFERRED ruler line. */
    private fun comparisonLines(key: Key, a: Answers, c: Cmp, readable: Boolean): List<String> {
        val out = ArrayList<String>()
        val note = key.notes.getValue(c.group)
        val x = key.clipOfRole(c.group, c.comparison.candidate)
        val t = key.clipOfRole(c.group, Role.TODAY)
        val xText = if (x.role == Role.PLAIN.key) "THE PLAIN ONE (${x.id})" else "${x.role} (${x.id}; ${liftText(x)})"
        out += "$P COMPARISON $note ${nice(c.comparison.key)} vs TODAY: X = $xText, T = TODAY (${t.id}; ${liftText(t)})"
        c.orders.forEachIndexed { i, o ->
            val p = o.pair
            val ca = key.clips.getValue(p.a)
            val cb = key.clips.getValue(p.b)
            val said = when {
                o.answer == null -> "NO COUNTED ANSWER (unanswered, or ignored: see IGNORED above)"
                o.pick == Pk.CANT -> "owner answered ${owner(PAIR_WORDS, o.answer)}: no pick"
                else -> "owner answered ${owner(PAIR_WORDS, o.answer)}: picked ${if (o.pick == Pk.X) "X (${o.pickedRole})" else "T (TODAY)"}"
            }
            out += "$P   order ${i + 1}, ${p.id}: A = ${clipText(ca)}, B = ${clipText(cb)}: $said; PLAY A, THEN B ran to its end ${a.runs[p.id] ?: 0} time(s)"
            out += "$P     MEASURED rises over THE PLAIN ONE, A: ${risesText(ca)}; B: ${risesText(cb)}"
        }
        val tail = if (readable) "" else " [NOT TO BE READ: see P1]"
        out += "$P   READING $note ${nice(c.comparison.key)} vs TODAY: ${c.verdict.label} (${reasonOf(c)})$tail"
        val dr = x.rmsRise - t.rmsRise
        val da = x.aRise - t.aRise
        val dl = x.loudRise - t.loudRise
        out += "$P     MEASURED X minus T: rms ${signed(dr)} dB, Loudness.of ${signed(dl)} dB, A-weighted ${signed(da)} dB"
        val side = when (c.verdict) {
            Vd.X_STRICT, Vd.X_LEAN -> true
            Vd.T_STRICT, Vd.T_LEAN -> false
            else -> null
        }
        if (readable && side != null) {
            fun goes(d: Double): String = if (abs(d) <= RULER_LEVEL_DB) "level" else if (side == (d > 0)) "with" else "against"
            out += "$P     INFERRED ruler read (not a rule, not used by the build map): the owner's pick goes ${goes(dr)} the rms ruler, ${goes(dl)} the Loudness.of ruler and ${goes(da)} the A-weighted ruler"
        }
        return out
    }

    private fun identicalLines(key: Key, a: Answers): List<String> {
        val p = key.pairs.first { it.comparison == Comparison.IDENTICAL.key }
        val ca = key.clips.getValue(p.a)
        val cb = key.clips.getValue(p.b)
        val answer = a.pairs[p.id]
        val out = ArrayList<String>()
        out += "$P IDENTICAL PAIR ${key.notes.getValue(p.group)} ${p.id}: A = ${clipText(ca)}, B = ${clipText(cb)}; MEASURED: the key lists one SHA-256 for both (${ca.sha256.take(12)}...), so they are the same file under two names"
        out += when {
            answer == null -> "$P   NO COUNTED ANSWER (unanswered, or ignored: see IGNORED above); PLAY A, THEN B ran to its end ${a.runs[p.id] ?: 0} time(s)"
            answer == G.PAIR_CANT -> "$P   owner answered ${owner(PAIR_WORDS, answer)}: the clean answer; PLAY A, THEN B ran to its end ${a.runs[p.id] ?: 0} time(s)"
            else -> "$P   owner answered ${owner(PAIR_WORDS, answer)} on two files that are the same file: NOISE SIGN. Read every reading with it in mind; PLAY A, THEN B ran to its end ${a.runs[p.id] ?: 0} time(s)"
        }
        return out
    }

    private fun keepRoleOf(k: KeyKeep, answer: String?): String? = k.clips.firstOrNull { it.id == answer }?.role

    private fun keepLines(key: Key, a: Answers, k: KeyKeep): List<String> {
        val out = ArrayList<String>()
        val answer = a.keep[k.id]
        val note = key.notes.getValue(k.group)
        val chosen = k.clips.firstOrNull { it.id == answer }
        val said = when {
            answer == null -> "NO COUNTED ANSWER (unanswered, or ignored: see IGNORED above)"
            chosen != null -> "owner answered CLIP ${chosen.letter} (${chosen.id}) (HEARD) -> ${chosen.role}; the page recorded ${a.plays[chosen.id] ?: 0} play(s) of that clip"
            else -> "owner answered ${owner(KEEP_WORDS, answer)}"
        }
        out += "$P KEEP $note ${k.id}: $said"
        out += "$P   MEASURED rises over THE PLAIN ONE, the versions in the order the page showed them (the page also showed THE PLAIN ONE (${k.plain}) for reference):"
        for (kc in k.clips) {
            val c = key.clips.getValue(kc.id)
            out += "$P     CLIP ${kc.letter} ${kc.role} (${kc.id}): ${liftText(c)}; rms ${signed(c.rmsRise)} dB, Loudness.of ${signed(c.loudRise)} dB, A-weighted ${signed(c.aRise)} dB; plays ${a.plays[kc.id] ?: 0}${if (kc.id == answer) "   <-- the owner's answer" else ""}"
        }
        return out
    }

    private fun playLines(key: Key, a: Answers): List<String> {
        val out = ArrayList<String>()
        val perGroup = key.groups.joinToString("; ") { g -> "${key.notes.getValue(g)}: " + key.clips.values.filter { it.group == g }.joinToString(", ") { "${it.id} ${a.plays[it.id] ?: 0}" } }
        out += "$P PLAYS (MEASURED by the page, a count and not a hearing), starts per clip: $perGroup"
        out += "$P PLAYS PLAY A, THEN B runs that reached the end, per widget: ${key.pairs.joinToString(", ") { "${it.id} ${a.runs[it.id] ?: 0}" }}"
        val never = key.clips.values.filter { (a.plays[it.id] ?: 0) < 1 }.map { it.id }
        out += "$P PLAYS clips never started: ${never.joinToString(", ").ifEmpty { "none" }}"
        return out
    }

    private fun deviceNote(device: String?): String? = when (device) {
        G.DEVICE_PHONE, G.DEVICE_LAPTOP -> "INFERRED (not a rule): a small speaker plays little under about 300 Hz, and C2's fundamental is 65 Hz, so the low part of a lift may not be reproduced on this device; read the readings with that in mind"
        else -> null
    }

    /** The F#2 reading of a candidate in words. */
    private fun f2Says(c: Cand): String = when (c.f2.verdict) {
        Vd.X_STRICT -> "X-STRICT (F#2 agrees)"
        Vd.X_LEAN -> "X-LEAN (F#2 leans the same way)"
        Vd.TIE -> "TIE (F#2 neither agrees nor contradicts)"
        Vd.MISSING -> if (countsOf(c.f2).none == c.f2.orders.size) "not answered" else "MISSING (an order has no counted answer; ${countsLong(c.f2)})"
        else -> "${c.f2.verdict.label} (F#2 contradicts: the candidate does not qualify)"
    }

    /** A candidate in the RESULT line: its counts at C2 and at F#2, and a tag when it leans only or is kept out at F#2. */
    private fun candResult(c: Cand): String {
        val n = countsOf(c.f2)
        val f2 = if (n.none == c.f2.orders.size) "not answered" else countsShort(c.f2) + when (c.f2.verdict) {
            Vd.X_STRICT, Vd.X_LEAN -> " (F#2 agrees)"
            Vd.TIE -> " (F#2 TIE)"
            Vd.MISSING -> " (F#2 MISSING)"
            else -> " (F#2 contradicts)"
        }
        val tag = when {
            c.c2.verdict == Vd.X_LEAN -> " [weak lean, not enough]"
            c.f2Veto -> " [F#2 contradicts: does not qualify]"
            else -> ""
        }
        return "${nice(c.role.key)} C2 ${countsShort(c.c2)}; F#2 $f2$tag"
    }

    private fun keepResultText(k: KeyKeep, answer: String?): String = when {
        answer == null -> "unanswered"
        answer in KEEP_WORDS -> owner(KEEP_WORDS, answer)
        else -> k.clips.first { it.id == answer }.let { "${nice(it.role)} (CLIP ${it.letter})" }
    }

    private fun keepReason(answer: String?, role: String?): String = when {
        answer == null -> "no C2 keep answer was counted, so it names nothing"
        answer == G.KEEP_NONE -> "it names THE PLAIN ONE (${owner(KEEP_WORDS, answer)})"
        answer == G.KEEP_THICK -> "it is ${owner(KEEP_WORDS, answer)}"
        answer == G.KEEP_CANT -> "it is ${owner(KEEP_WORDS, answer)}"
        role == Role.TODAY.key -> "it names TODAY's version"
        else -> "it names ${nice(role ?: "?")}"
    }

    private fun noiseText(identical: String?): String = when (identical) {
        null -> "NOISE SIGN no (identical pair: not answered)"
        G.PAIR_CANT -> "NOISE SIGN no (identical pair: can't tell)"
        else -> "NOISE SIGN yes (identical pair: ${identical.uppercase(Locale.ROOT)} chosen)"
    }

    // ---- the endings before P1 -------------------------------------------------------------------------------

    private fun ending(source: String, lines: List<String>, body: String, outcome: Outcome): Report =
        Report(source, lines + labelsLine(), "$P RESULT: ${shortName(source)}: $body", body, outcome, null, null, emptyMap(), emptyList(), null)

    /** The three endings that are not a void page (no document read, shape not recognised, no answers saved) and the path that does not exist; null when the document is the page's and holds an answer. */
    private fun earlyEnding(key: Key, saved: Saved, source: String): Report? {
        val head = "$P ===== $source ====="
        if (saved.pathMissing) return ending(source, listOf(head, "$P path found: NO"), "NO DOCUMENT READ (path not found)", Outcome.NOT_FOUND)
        val doc = saved.doc
        if (doc == null) {
            val lines = ArrayList<String>()
            lines += head
            lines += "$P doc found: NO (no document was read; read as nothing answered)"
            for (u in saved.unreadable) lines += "$P UNREADABLE: $u: read as nothing answered"
            saved.folderFiles?.let { lines += "$P FOLDER FILES: ${filesText(it)}" }
            val why = when {
                saved.unreadable.isNotEmpty() -> "unreadable: ${saved.unreadable.joinToString("; ")}"
                saved.folderFiles != null -> "the folder has no file with the expected name and does not hold exactly one .json file"
                else -> "no verdict document was given"
            }
            val files = saved.folderFiles?.let { "; folder files: ${filesText(it)}" } ?: ""
            lines += "$P ENDING (before P1, not a void): NO DOCUMENT READ: nothing is read and nothing is decided; look at the folder's files"
            return ending(source, lines, "NO DOCUMENT READ ($why$files)", Outcome.NO_DOCUMENT)
        }
        val fromText = saved.readFrom?.let { ", read from ${jq(it)}${saved.how?.let { h -> " ($h)" } ?: ""}" } ?: ""
        if (!looksLikeDoc(doc)) {
            val keys = doc.keys.toList()
            val lines = listOf(
                head, "$P doc found: yes$fromText, but it is not the page's doc",
                "$P ENDING (before P1, not a void): DOC SHAPE NOT RECOGNISED: it holds none of the keys q_*, plays or savedAt; top-level keys: ${filesText(keys)}",
            )
            return ending(source, lines, "DOC SHAPE NOT RECOGNISED, top-level keys: ${filesText(keys)}", Outcome.SHAPE)
        }
        if (questionIds(key).all { isBlank(doc[it]) }) {
            val a = answersOf(key, saved)
            val runs = runsRecorded(key, doc)
            val lines = ArrayList<String>()
            lines += head
            lines += "$P doc found: yes (${G.DOC_PREFIX}${G.OVERALL_KEY}$fromText)"
            lines += "$P saved at: ${a.savedAt?.let { jq(it) } ?: "not recorded"}"
            for (i in a.ignored) lines += "$P IGNORED: $i"
            lines += playLines(key, a)
            lines += "$P ENDING (before P1, not a void): NO ANSWERS SAVED: every question of the page is blank in the doc; $runs runs recorded"
            val body = "NO ANSWERS SAVED: $runs runs recorded, no answer filled: the owner probably did not tap SAVE VERDICT; their answers stay on their phone, ask them to open the page and tap SAVE VERDICT"
            return ending(source, lines, body, Outcome.NO_ANSWERS)
        }
        return null
    }

    // ---- decode --------------------------------------------------------------------------------------------

    internal fun decode(key: Key, saved: Saved, source: String): Report {
        earlyEnding(key, saved, source)?.let { return it }
        val a = answersOf(key, saved)
        val c2 = G.G_C2
        val f2 = G.G_F2
        val cmps = key.groups.associateWith { comparisonsOf(key, a, it) }
        fun candidateCmp(g: String, r: Role): Cmp = cmps.getValue(g).first { it.comparison.candidate == r }
        val verdicts = LinkedHashMap<String, Vd>()
        for ((g, list) in cmps) for (c in list) verdicts["$g/${c.comparison.key}"] = c.verdict
        val identical = key.widgets(c2, Comparison.IDENTICAL).single()
        val sameAnswer = a.pairs[identical.id]

        // P1 (ADDITIONS): the four non-identical comparisons at C2 and the device
        val four = cmps.getValue(c2)
        val conflicts = four.count { it.verdict == Vd.CONFLICT }
        val unknown = four.count { it.verdict == Vd.MISSING }
        val state = when {
            a.device == null || conflicts > P1_MAX_CONFLICTS -> State.VOID
            conflicts + unknown <= P1_MAX_CONFLICTS -> State.READABLE
            else -> State.UNDECIDED
        }
        val readable = state == State.READABLE

        // P2: PLAIN vs TODAY at C2, THE PLAIN ONE in the place of the candidate
        val plainVerdict = four.first { it.comparison == Comparison.VS_PLAIN }.verdict
        val p2: P2Read? = if (state == State.VOID) null else when (plainVerdict) {
            Vd.T_STRICT, Vd.T_LEAN -> P2Read.HEARD
            Vd.X_STRICT, Vd.X_LEAN -> P2Read.PLAIN_CHOSEN
            else -> P2Read.NOT_HEARD
        }

        // the build map
        val cands = CANDIDATES.map { Cand(it, candidateCmp(c2, it), candidateCmp(f2, it)) }
        val qs = cands.filter { it.qualifies }
        val keepC2 = key.keepOf(c2)
        val keepAnswer = a.keep[keepC2.id]
        val keepRole = keepRoleOf(keepC2, keepAnswer)
        val outcome = when {
            state == State.VOID -> Outcome.VOID
            state == State.UNDECIDED -> Outcome.INCOMPLETE
            p2 == P2Read.NOT_HEARD -> Outcome.P2_LEAVE
            p2 == P2Read.PLAIN_CHOSEN -> Outcome.P2_ASK
            cands.any { it.c2.verdict == Vd.MISSING } -> Outcome.INCOMPLETE
            qs.isEmpty() -> Outcome.CASE_1
            qs.size == 1 -> if (keepAnswer == null || keepAnswer == G.KEEP_CANT || keepRole == qs.single().role.key) Outcome.CASE_2_BUILD else Outcome.CASE_2_ASK
            keepRole != null && qs.any { it.role.key == keepRole } -> Outcome.CASE_3_BUILD
            else -> Outcome.CASE_3_ASK
        }
        val built = when (outcome) {
            Outcome.CASE_2_BUILD -> qs.single().role.key
            Outcome.CASE_3_BUILD -> keepRole
            else -> null
        }

        val out = ArrayList<String>()
        out += "$P ===== $source ====="
        out += "$P doc found: yes (${G.DOC_PREFIX}${G.OVERALL_KEY}${saved.readFrom?.let { ", read from ${jq(it)}${saved.how?.let { h -> " ($h)" } ?: ""}" } ?: ""})"
        for (u in saved.unreadable) out += "$P UNREADABLE: $u: read as nothing answered"
        out += "$P saved at: ${a.savedAt?.let { jq(it) } ?: "not recorded"}"
        out += "$P DEVICE: ${a.device?.let { owner(DEVICE_WORDS, it) } ?: "NOT ANSWERED (a required question)"}"
        val c2Pairs = key.pairs.filter { it.group == c2 }
        val f2Pairs = key.pairs.filter { it.group == f2 }
        val required = listOf(G.Q_DEVICE) + c2Pairs.map { it.id } + keepC2.id
        val unanswered = required.filter { id -> if (id == G.Q_DEVICE) a.device == null else id !in a.pairs && id !in a.keep }
        out += "$P ANSWERED: device ${if (a.device != null) "yes" else "no"}, C2 pair widgets counted ${c2Pairs.count { it.id in a.pairs }} of ${c2Pairs.size}, C2 keep ${if (keepC2.id in a.keep) "yes" else "no"}; required items unanswered: ${unanswered.size} of ${required.size}" +
            if (unanswered.isEmpty()) "" else " (${unanswered.joinToString(", ")})"
        out += "$P ANSWERED (the F#2 section is optional): pair widgets counted ${f2Pairs.count { it.id in a.pairs }} of ${f2Pairs.size}, F#2 keep ${if (key.keepOf(f2).id in a.keep) "yes" else "no"}"
        for (i in a.ignored) out += "$P IGNORED: $i"
        a.note?.let { out += "$P NOTE (HEARD, the owner's own text, as a JSON-quoted string): ${jq(it)}" }
        out += playLines(key, a)

        for (g in key.groups) {
            out += "$P ----- ${key.notes.getValue(g)}${if (g == f2) " (optional section)" else ""}: each comparison with the owner's two answers -----"
            for (c in cmps.getValue(g)) out += comparisonLines(key, a, c, readable)
            if (g == c2) out += identicalLines(key, a)
        }
        out += "$P ----- the keep answers -----"
        for (k in key.keeps) out += keepLines(key, a, k)

        // the lines of P1, P2, P3, the keep notes and the build map
        out += "$P ----- P1, P2, P3 and the build map -----"
        val fourText = four.joinToString(", ") { "${nice(it.comparison.key)} ${it.verdict.label}" }
        val deviceText = if (a.device == null) "the device question is NOT ANSWERED" else "the device question is answered"
        out += "$P P1 $state: $deviceText; at C2 the four non-identical comparisons read $fourText; CONFLICT $conflicts, MISSING $unknown (the bar: fewer than ${P1_MAX_CONFLICTS + 1} CONFLICT; P1 catches only a strong position habit)" + when (state) {
            State.VOID -> ". THE PAGE IS VOID: re-run it, read nothing, and say so." + if (a.device == null) " The only way to read it is to get the device answer from the owner." else ""
            State.UNDECIDED -> ". Answers are missing and the missing ones could still void the page: not read yet."
            State.READABLE -> ""
        }
        out += "$P identical pair ${identical.id} (never a gate; reported apart): " + when (sameAnswer) {
            null -> "no counted answer"
            G.PAIR_CANT -> "\"${PAIR_WORDS.getValue(sameAnswer)}\" (HEARD): the clean answer"
            else -> "\"${PAIR_WORDS.getValue(sameAnswer)}\" (HEARD): NOISE SIGN, printed beside the readings and on the RESULT line"
        }
        if (state == State.VOID) {
            out += "$P P2 NOT READ (the page is void)"
            out += "$P P3 NOT READ (the page is void)"
            out += "$P BUILD MAP NOT READ (the page is void)"
        } else {
            val notYet = if (readable) "" else " [P1 is UNDECIDED: not applied yet]"
            out += "$P " + when (p2) {
                P2Read.HEARD -> "P2 HEARD: PLAIN vs TODAY at C2 reads ${plainVerdict.label}: TODAY was chosen in at least one order and THE PLAIN ONE in neither (HEARD); C2's lift is heard here$notYet"
                P2Read.NOT_HEARD -> "P2 NOT HEARD: PLAIN vs TODAY at C2 reads ${plainVerdict.label}: C2's lift was not heard here, no candidate can be read against it$notYet"
                else -> "P2 THE PLAIN ONE CHOSEN: PLAIN vs TODAY at C2 reads ${plainVerdict.label}: THE PLAIN ONE was chosen in at least one order and TODAY in neither; this is not \"not heard\": " +
                    "either \"fuller\" does not follow level for this owner or the answers are noise; never a build$notYet"
            }
            for (g in key.groups) {
                out += "$P P3 ${key.notes.getValue(g)}: " + CANDIDATES.joinToString("; ") { r -> val c = candidateCmp(g, r); "${nice(r.key)} ${c.verdict.label} (${countsLong(c)})" }
            }
            // the keep notes (the F#2 keep is printed only)
            for (k in key.keeps) {
                val answer = a.keep[k.id]
                val role = keepRoleOf(k, answer)
                val shown = key.notes.getValue(k.group)
                val line = when {
                    role == Role.TODAY.key -> "names TODAY's version, which is no candidate: nothing to compare with the pairs"
                    role == null -> "names no clip (${if (answer == null) "no counted answer" else owner(KEEP_WORDS, answer)}): nothing to compare with the pairs"
                    else -> {
                        val vd = cmps.getValue(k.group).first { it.comparison.candidate.key == role }.verdict
                        when (vd) {
                            Vd.T_STRICT, Vd.T_LEAN -> "names ${nice(role)}, whose $shown reading is ${vd.label}: keep says the opposite of the pairs"
                            Vd.X_STRICT, Vd.X_LEAN -> "names ${nice(role)}, whose $shown reading is ${vd.label}: it goes with the pairs"
                            else -> "names ${nice(role)}, whose $shown reading is ${vd.label}"
                        }
                    }
                }
                out += "$P KEEP NOTE $shown (printed beside the readings${if (k.group == f2) "; the F#2 keep is printed only" else ""}): $line"
            }
            for (c in cands) {
                val why = when {
                    c.c2.verdict == Vd.X_STRICT && c.f2Veto -> "does NOT qualify: X-STRICT at C2 but its F#2 reading is ${c.f2.verdict.label} (T-STRICT, T-LEAN and CONFLICT at F#2 keep a candidate out)"
                    c.c2.verdict == Vd.X_STRICT -> "QUALIFIES: X-STRICT at C2 (chosen over TODAY in both C2 orders) and its F#2 reading is ${f2Says(c)}: not T-STRICT, T-LEAN or CONFLICT"
                    c.c2.verdict == Vd.X_LEAN -> "weak lean, not enough: X-LEAN at C2 never qualifies"
                    else -> "does not qualify: ${c.c2.verdict.label} at C2"
                }
                out += "$P BUILD MAP ${nice(c.role.key)}: C2 ${countsShort(c.c2)}; F#2 ${countsLong(c.f2)}: $why"
            }
            out += "$P BUILD MAP: QS = ${if (state == State.READABLE) qs.joinToString(", ") { nice(it.role.key) }.ifEmpty { "(empty)" } else "not known yet"}"
            val keepNow = keepResultText(keepC2, keepAnswer)
            when (outcome) {
                Outcome.INCOMPLETE -> {
                    val needed = listOfNotNull(if (a.device == null) G.Q_DEVICE else null) + four.flatMap { c -> c.orders.filter { it.pick == null }.map { "${it.pair.id} (C2 ${nice(c.comparison.key)} vs TODAY)" } }
                    out += "$P BUILD MAP: NO CASE LINE APPLIES YET. Answers still needed: ${needed.joinToString(", ")}. Ask the owner again; nothing is guessed."
                }
                Outcome.P2_LEAVE -> out += "$P P2 GATE applies: LEAVE C2 AS IT IS (C2's lift was not heard here: listen again on other speakers before reading this as a result). No candidate is read against it."
                Outcome.P2_ASK -> out += "$P P2 GATE applies: ASK THE OWNER (THE PLAIN ONE was chosen over TODAY in at least one order and TODAY in neither). Never a build."
                Outcome.CASE_1 -> out += "$P BUILD MAP line 1 applies: LEAVE C2 AS IT IS (record the result; TODAY stays). QS is empty."
                Outcome.CASE_2_BUILD -> {
                    val r = qs.single()
                    out += "$P BUILD MAP line 2 applies: BUILD ${nice(r.role.key)} (${DIRECTION_TEXT.getValue(r.role.key)}), at the capped notes only. QS has one candidate: ${nice(r.role.key)}; the C2 keep: $keepNow"
                    out += "$P BUILD MAP line 2: F#2 said for ${nice(r.role.key)}: ${f2Says(r)}"
                }
                Outcome.CASE_2_ASK -> {
                    val r = qs.single()
                    out += "$P BUILD MAP line 2 applies: ASK THE OWNER. QS has one candidate: ${nice(r.role.key)}; the C2 keep names something else: ${keepReason(keepAnswer, keepRole)}"
                    out += "$P BUILD MAP line 2: F#2 said for ${nice(r.role.key)}: ${f2Says(r)}"
                }
                Outcome.CASE_3_BUILD -> {
                    val r = Role.entries.first { it.key == keepRole }
                    out += "$P BUILD MAP line 3 applies: BUILD ${nice(r.key)} (${DIRECTION_TEXT.getValue(r.key)}), at the capped notes only. QS has ${qs.size} candidates (${qs.joinToString(", ") { nice(it.role.key) }}); the C2 keep names ${nice(r.key)}"
                    out += "$P BUILD MAP line 3: F#2 said for ${nice(r.key)}: ${f2Says(qs.first { it.role == r })} (printed only)"
                }
                Outcome.CASE_3_ASK -> out += "$P BUILD MAP line 3 applies: ASK THE OWNER. QS has ${qs.size} candidates (${qs.joinToString(", ") { nice(it.role.key) }}); the C2 keep does not name one of them: ${keepReason(keepAnswer, keepRole)}"
                else -> Unit
            }
        }
        deviceNote(a.device)?.let { out += "$P DEVICE NOTE: $it" }
        out += labelsLine()

        val p2Result = when (p2) {
            P2Read.HEARD -> "P2 heard"
            P2Read.NOT_HEARD -> "P2 not heard (PLAIN vs TODAY reads ${plainVerdict.label})"
            P2Read.PLAIN_CHOSEN -> "P2 THE PLAIN ONE chosen (PLAIN vs TODAY reads ${plainVerdict.label})"
            null -> "P2 not read"
        }
        val tail = "$p2Result; C2 keep ${keepResultText(keepC2, keepAnswer)}; ${noiseText(sameAnswer)}"
        val inPlay = cands.filter { it.c2.verdict == Vd.X_STRICT || it.c2.verdict == Vd.X_LEAN }
        val playText = if (inPlay.isEmpty()) "no candidate chosen over TODAY at C2" else inPlay.joinToString("; ") { candResult(it) }
        val qsText = if (qs.isEmpty()) "QS empty" else "QS = ${qs.joinToString(", ") { nice(it.role.key) }}"
        val body = when (outcome) {
            Outcome.VOID -> "PAGE VOID (P1): " + listOfNotNull(
                if (a.device == null) "the device question is not answered" else null,
                if (conflicts > P1_MAX_CONFLICTS) "$conflicts of the four non-identical C2 comparisons are CONFLICT" else null,
            ).joinToString(" and ") + "; no case line applies, nothing is read"
            Outcome.INCOMPLETE -> "NO CASE LINE APPLIES YET (answers missing, see above); nothing is guessed"
            Outcome.P2_LEAVE -> "P2 GATE applied: LEAVE C2 AS IT IS (C2's lift was not heard here: listen again on other speakers before reading this as a result; $playText; $tail)"
            Outcome.P2_ASK -> "P2 GATE applied: ASK THE OWNER (THE PLAIN ONE was chosen over TODAY in at least one order and TODAY in neither: never a build; $playText; $tail)"
            Outcome.CASE_1 -> "CASE LINE 1 applied: LEAVE C2 AS IT IS ($qsText; $playText; $tail)"
            Outcome.CASE_2_BUILD -> "CASE LINE 2 applied: BUILD ${nice(built!!)} at the capped notes only ($playText; $tail)"
            Outcome.CASE_2_ASK -> "CASE LINE 2 applied: ASK THE OWNER ($playText; the C2 keep names something else; $tail)"
            Outcome.CASE_3_BUILD -> "CASE LINE 3 applied: BUILD ${nice(built!!)} at the capped notes only ($qsText; $playText; the C2 keep names ${nice(built)}; $tail)"
            Outcome.CASE_3_ASK -> "CASE LINE 3 applied: ASK THE OWNER ($qsText; $playText; the C2 keep does not name one of them; $tail)"
            Outcome.NOT_FOUND, Outcome.NO_DOCUMENT, Outcome.SHAPE, Outcome.NO_ANSWERS -> error("an ending before P1 cannot get here")
        }
        return Report(source, out, "$P RESULT: ${shortName(source)}: $body", body, outcome, state, p2, verdicts, qs.map { it.role.key }, built)
    }

    // ---- the rules, printed first ----------------------------------------------------------------------------

    internal fun rulesLines(): List<String> = buildList {
        add("$P RULES, the lead's final reading rules (section A of the fix brief), pre-written before anyone listened; printed before any answer is read; applied as written; nothing is argued with afterwards:")
        for (r in RULES) add("$P RULE $r")
        add("$P ADDITIONS for what the rules do not say (a missing or wrong field is reported as such and never guessed):")
        for (r in ADDITIONS) add("$P ADDITION $r")
    }

    // ---- the self-check: the decoder run on invented answers before it reads a real file ---------------------

    /** Runs [body], which must throw an IllegalStateException (a failed check): the control broke the rule the check keeps, and the check said so. */
    private fun mustThrow(what: String, body: () -> Unit) {
        val threw = try {
            body()
            false
        } catch (e: IllegalStateException) {
            true
        }
        check(threw) { "SELFCHECK CONTROL FAILED TO FAIL: $what" }
        println("$P SELFCHECK CONTROL $what: the check threw, as it must")
    }

    /** Runs [body], which must NOT throw (a positive control: the check does not cry wolf on what is allowed). */
    private fun mustHold(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: IllegalStateException) {
            error("SELFCHECK POSITIVE CONTROL FAILED: $what: the check threw on what it must allow: ${e.message}")
        }
        println("$P SELFCHECK CONTROL $what: the check held, as it must")
    }

    /**
     * Invented answers. [c2] and [f2] give each comparison's two orders as two letters in the page's order of the two widgets: X (the candidate), T (TODAY), C (can't tell), - (no answer). [all] answers every pair widget
     * with that raw answer (a position habit); [blanks] writes every question as an empty string, the way the page does before SAVE VERDICT.
     */
    private class Script(
        val device: String? = G.DEVICE_HEADPHONES, val c2: Map<Comparison, String> = emptyMap(), val f2: Map<Comparison, String> = emptyMap(), val ident: String? = G.PAIR_CANT,
        val keepC2: String? = null, val keepF2: String? = null, val plays: Boolean = true, val noRun: Set<String> = emptySet(), val extra: Map<String, JsonValue> = emptyMap(),
        val all: String? = null, val blanks: Boolean = false,
    )

    private fun c2All(plain: String, bell: String, only: String, shelf: String): Map<Comparison, String> =
        mapOf(Comparison.VS_PLAIN to plain, Comparison.MORE_BELL to bell, Comparison.BELL_ONLY to only, Comparison.MORE_SHELF to shelf)

    private fun f2All(bell: String, only: String, shelf: String): Map<Comparison, String> =
        mapOf(Comparison.MORE_BELL to bell, Comparison.BELL_ONLY to only, Comparison.MORE_SHELF to shelf)

    private fun sideOf(w: KeyPair, role: Role): String = if (w.aIs == role.key) G.PAIR_A else G.PAIR_B

    private fun keepValue(k: KeyKeep, v: String): String = if (v in KEEP_WORDS) v else k.clips.first { it.role == v }.id

    /** The doc the page would have saved for [s]: every clip played [SELF_PLAYS] times and every widget run once, unless the script says otherwise. */
    private fun invent(key: Key, s: Script): Map<String, JsonValue> {
        val doc = LinkedHashMap<String, JsonValue>()
        if (s.blanks) for (id in questionIds(key)) doc[id] = JsonValue.Str("")
        s.device?.let { doc[G.Q_DEVICE] = JsonValue.Str(it) }
        s.all?.let { raw -> for (p in key.pairs) doc[p.id] = JsonValue.Str(raw) }
        for ((group, codes) in listOf(G.G_C2 to s.c2, G.G_F2 to s.f2)) for ((c, code) in codes) {
            val ws = key.widgets(group, c)
            check(code.length == 2 && ws.size == 2) { "the invented answer \"$code\" does not fit $group ${c.key}" }
            for ((i, w) in ws.withIndex()) {
                val answer = when (code[i]) {
                    'X' -> sideOf(w, c.candidate)
                    'T' -> sideOf(w, Role.TODAY)
                    'C' -> G.PAIR_CANT
                    '-' -> null
                    else -> error("the invented answer \"$code\" has a letter that is none of X, T, C and -")
                }
                answer?.let { doc[w.id] = JsonValue.Str(it) }
            }
        }
        s.ident?.let { doc[key.widgets(G.G_C2, Comparison.IDENTICAL).single().id] = JsonValue.Str(it) }
        s.keepC2?.let { doc[G.Q_KEEP_C2] = JsonValue.Str(keepValue(key.keepOf(G.G_C2), it)) }
        s.keepF2?.let { doc[G.Q_KEEP_F2] = JsonValue.Str(keepValue(key.keepOf(G.G_F2), it)) }
        if (s.plays) {
            val plays = LinkedHashMap<String, JsonValue>()
            for (c in key.clips.values) plays[c.id] = JsonValue.Num(SELF_PLAYS.toDouble())
            for (p in key.pairs) if (p.id !in s.noRun) plays[p.id] = JsonValue.Num(1.0)
            doc[G.PLAYS] = JsonValue.Obj(plays)
        }
        doc["savedAt"] = JsonValue.Str("2026-10-11T00:00:00.000Z")
        doc.putAll(s.extra)
        return doc
    }

    private val SOUND_WORDS = Regex("\\b(full\\w*|thick\\w*|dull\\w*|boom\\w*|warm\\w*|bright\\w*|muddy|boxy|hollow|rich\\w*|heavy|deep\\w*|round\\w*|tinny|harsh)\\b", RegexOption.IGNORE_CASE)

    /** A quoted piece of text: from a double quote to the next one that is not escaped by a backslash (so a JSON-quoted owner text is one piece, whatever quotes it holds). */
    private val QUOTED = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")

    /** The decoder never says how a sound sounds except in the owner's recorded words, which it prints inside double quotes: outside them none of the sound words may stand. */
    private fun guardWords(lines: List<String>) {
        for (l in lines) {
            val m = SOUND_WORDS.find(l.replace(QUOTED, ""))
            check(m == null) { "a report line says \"${m?.value}\" outside the owner's quoted words: $l" }
        }
    }

    private fun checkTable(rows: List<Triple<Pk?, Pk?, Vd>>) {
        for ((f, s, want) in rows) check(verdictOf(f, s) == want) { "two orders ($f, $s) read ${verdictOf(f, s)}, the rules say $want" }
    }

    /** The decoded report ended as wanted: [outcome], the role built, and every text of [say] stands in a printed line. */
    private fun expect(name: String, r: Report, outcome: Outcome, built: String? = null, say: List<String> = emptyList()) {
        check(r.outcome == outcome) { "SELFCHECK $name: ended ${r.outcome}, wanted $outcome (${r.body})" }
        check(r.built == built) { "SELFCHECK $name: built ${r.built}, wanted $built" }
        for (s in say) check(r.lines.any { s in it } || s in r.result) { "SELFCHECK $name: no printed line says \"$s\"" }
        println("$P SELFCHECK $name: ${r.outcome}${r.built?.let { " $it" } ?: ""} OK")
    }

    private fun selfCheck(key: Key, manifest: File, absentPath: File) {
        // the nine readings of two orders, and the orders with no answer
        val table = ArrayList<Triple<Pk?, Pk?, Vd>>()
        table += Triple(Pk.X, Pk.X, Vd.X_STRICT)
        table += Triple(Pk.X, Pk.CANT, Vd.X_LEAN)
        table += Triple(Pk.CANT, Pk.X, Vd.X_LEAN)
        table += Triple(Pk.T, Pk.T, Vd.T_STRICT)
        table += Triple(Pk.T, Pk.CANT, Vd.T_LEAN)
        table += Triple(Pk.CANT, Pk.T, Vd.T_LEAN)
        table += Triple(Pk.CANT, Pk.CANT, Vd.TIE)
        table += Triple(Pk.X, Pk.T, Vd.CONFLICT)
        table += Triple(Pk.T, Pk.X, Vd.CONFLICT)
        for (p in Pk.entries) {
            table += Triple(p, null, Vd.MISSING)
            table += Triple(null, p, Vd.MISSING)
        }
        table += Triple(null, null, Vd.MISSING)
        checkTable(table)
        println("$P SELFCHECK the reading of a comparison from its two orders: the nine readings and the orders with no answer: OK")
        mustThrow("the reading table with X and T read as a TIE") { checkTable(listOf(Triple(Pk.X, Pk.T, Vd.TIE))) }
        mustThrow("the reading table with X-LEAN read as X-STRICT") { checkTable(listOf(Triple(Pk.X, Pk.CANT, Vd.X_STRICT))) }
        mustThrow("the reading table with T-LEAN read as T-STRICT") { checkTable(listOf(Triple(Pk.CANT, Pk.T, Vd.T_STRICT))) }

        // the page's words, held to the generated manifest when it is there
        if (manifest.isFile) {
            val q = Json.parse(manifest.readText(Charsets.UTF_8)).obj().getValue("questions").obj()
            fun options(v: JsonValue): Map<String, String> = v.arr().associate { val o = it.obj(); o.getValue("v").str() to o.getValue("t").str() }
            check(options(q.getValue("pair").obj().getValue("options")) == PAIR_WORDS) { "the decoder's pair words are not the manifest's" }
            check(options(q.getValue("device").obj().getValue("options")) == DEVICE_WORDS) { "the decoder's device words are not the manifest's" }
            for (k in q.getValue("keep").arr()) check(options(k.obj().getValue("extra")) == KEEP_WORDS) { "the decoder's keep words are not the manifest's" }
            println("$P SELFCHECK the page's words (pair, device, keep) are the manifest's: OK")
            mustThrow("the pair words against a manifest with one word changed") { check(options(q.getValue("pair").obj().getValue("options")) == PAIR_WORDS + (G.PAIR_A to "A SOUNDS LOUDER")) { "words differ" } }
        } else {
            println("$P SELFCHECK the page's words were NOT checked against the manifest (${manifest.path} not found)")
        }

        // quoting, the loader forms, the byte order mark and the choice of a file in a folder
        check(jq("a\"b\\c\nd") == "\"a\\\"b\\\\c\\nd\"") { "jq does not escape a quote, a backslash and a line break" }
        check(Json.parse(jq("\u00e9 \"x\" \\ \n\t")) == JsonValue.Str("\u00e9 \"x\" \\ \n\t")) { "jq does not round-trip" }
        println("$P SELFCHECK text the owner or a file supplies is printed JSON-quoted (quote, backslash and line break escaped, other characters as themselves): OK")
        mustThrow("the quoting check on a quote left bare") { check(jq("a\"b") == "\"a\"b\"") { "jq left a quote bare" } }
        val doc = "{\"q_device\":\"headphones\",\"savedAt\":\"x\"}"
        val forms = listOf(
            "the doc itself" to doc, "a data wrapper" to "{\"data\":$doc}", "an overall wrapper" to "{\"${G.OVERALL_KEY}\":$doc}",
            "a store path wrapper" to "{\"${G.DOC_PREFIX}${G.OVERALL_KEY}\":$doc}", "a doc after a byte order mark" to "\uFEFF$doc",
        )
        for ((name, text) in forms) {
            val unreadable = ArrayList<String>()
            val d = readDoc(text, name, unreadable)
            check(d?.get(G.Q_DEVICE) == JsonValue.Str("headphones") && unreadable.isEmpty()) { "the loader did not find the doc in $name" }
        }
        check(runCatching { Json.parse("\uFEFF$doc") }.isFailure) { "the JSON parser reads a byte order mark by itself, so the strip in the loader would be dead code" }
        val cut = ArrayList<String>()
        check(readDoc("{\"q_device\":", "cut off", cut) == null && cut.size == 1) { "a cut-off file is not listed as unreadable" }
        val notObject = ArrayList<String>()
        check(readDoc("[1]", "an array", notObject) == null && notObject.size == 1) { "a file that is not an object is not listed as unreadable" }
        val emptyDoc = ArrayList<String>()
        check(readDoc("{}", "empty", emptyDoc)?.isEmpty() == true && emptyDoc.isEmpty()) { "an empty doc is not read as an empty doc" }
        println("$P SELFCHECK the loader: the doc, a data wrapper, an overall wrapper, a store path wrapper, a doc after a byte order mark (which the parser itself refuses); a cut-off file and a non-object are listed UNREADABLE: OK")
        mustThrow("the loader check on a doc with another device") { check(readDoc(doc.replace("headphones", "earbuds"), "other device", ArrayList())?.get(G.Q_DEVICE) == JsonValue.Str("headphones")) { "the device is not the one the check wants" } }
        val bare = "arco_r1j_overall.json"
        check(chooseFile(listOf("x.json", bare)).first == bare && chooseFile(listOf("overall.json")).first == "overall.json" && chooseFile(listOf("verdicts/$bare")).first == "verdicts/$bare") { "the expected file names are not found" }
        check(chooseFile(listOf("notes.txt", "arco_r1j_overall (1).json")).first == "arco_r1j_overall (1).json" && chooseFile(listOf("arco_r1j_overall (1).json")).second != null) { "the only .json file is not read, or not said why" }
        check(chooseFile(listOf("a.json", "b.json")).first == null && chooseFile(emptyList()).first == null && chooseFile(listOf("notes.txt")).first == null && chooseFile(listOf("verdicts/other.json")).first == null) { "a folder without the expected name and without exactly one .json file gave a file" }
        println("$P SELFCHECK a folder: the expected names win; with none of them the only .json file is read (and the reason is said); two .json files, none, or a sub-folder file of another name read nothing: OK")
        mustThrow("the folder check with two .json files expecting one") { check(chooseFile(listOf("a.json", "b.json")).first != null) { "two .json files gave no file" } }

        // the case lines and the other endings, on invented answers
        val reports = LinkedHashMap<String, Report>()
        fun run(name: String, s: Script): Report = decode(key, Saved(invent(key, s)), name).also { reports[name] = it }
        fun runSaved(name: String, saved: Saved): Report = decode(key, saved, name).also { reports[name] = it }
        val bell = Role.MORE_BELL.key
        val only = Role.BELL_ONLY.key
        val shelf = Role.MORE_SHELF.key
        val today = Role.TODAY.key
        val heard = c2All("TT", "XX", "CC", "TT")

        // P2 heard, one candidate chosen over TODAY in both C2 orders: case line 2 and the keep veto
        val one = run("one X-STRICT, clean", Script(c2 = heard, f2 = f2All("XX", "CC", "TT"), keepC2 = bell, keepF2 = bell))
        expect("one X-STRICT, clean", one, Outcome.CASE_2_BUILD, bell, listOf("BUILD MORE BELL", "F#2 agrees", "P2 HEARD", "CASE LINE 2 applied", "P2 heard", "C2 keep MORE BELL (CLIP ${key.keepOf(G.G_C2).clips.first { it.role == bell }.letter})", "NOISE SIGN no (identical pair: can't tell)", "C2 2 of 2 over TODAY, 0 TODAY, 0 can't tell"))
        check(one.lines.any { "INFERRED ruler read" in it }) { "SELFCHECK: a READABLE page printed no ruler read" }
        expect("TODAY heard once and can't tell once is heard", run("TODAY heard once and can't tell once is heard", Script(c2 = c2All("TC", "XX", "CC", "TT"), keepC2 = bell)), Outcome.CASE_2_BUILD, bell, listOf("P2 HEARD"))
        expect("case 2, the keep says can't say", run("case 2, the keep says can't say", Script(c2 = heard, keepC2 = G.KEEP_CANT)), Outcome.CASE_2_BUILD, bell, listOf("I CAN'T SAY"))
        expect("case 2, no keep answer", run("case 2, no keep answer", Script(c2 = heard)), Outcome.CASE_2_BUILD, bell, listOf("C2 keep unanswered"))
        expect("case 2, a keep that is no clip of its widget is unanswered", run("case 2, a keep that is no clip of its widget is unanswered", Script(c2 = heard, extra = mapOf(G.Q_KEEP_C2 to JsonValue.Str("c2_zz")))), Outcome.CASE_2_BUILD, bell, listOf("IGNORED: ${G.Q_KEEP_C2}"))
        val vetoToday = run("case 2, the keep veto: TODAY's version", Script(c2 = heard, keepC2 = today))
        expect("case 2, the keep veto: TODAY's version", vetoToday, Outcome.CASE_2_ASK, null, listOf("ASK THE OWNER", "CASE LINE 2 applied", "it names TODAY's version"))
        expect("case 2, the keep veto: none, the plain one is better", run("case 2, the keep veto: none, the plain one is better", Script(c2 = heard, keepC2 = G.KEEP_NONE)), Outcome.CASE_2_ASK, null, listOf("it names THE PLAIN ONE", "NONE, THE PLAIN ONE IS BETTER"))
        expect("case 2, the keep veto: all four refused", run("case 2, the keep veto: all four refused", Script(c2 = heard, keepC2 = G.KEEP_THICK)), Outcome.CASE_2_ASK, null, listOf("ALL OF THEM SOUND TOO THICK OR BOOMY"))
        expect("case 2, the keep veto: another candidate", run("case 2, the keep veto: another candidate", Script(c2 = heard, keepC2 = shelf)), Outcome.CASE_2_ASK, null, listOf("it names MORE SHELF", "keep says the opposite of the pairs"))

        // the P2 gate
        val tie = run("all TIE: P2 not heard", Script(c2 = c2All("CC", "CC", "CC", "CC"), f2 = f2All("CC", "CC", "CC"), keepC2 = G.KEEP_CANT, keepF2 = G.KEEP_CANT))
        expect("all TIE: P2 not heard", tie, Outcome.P2_LEAVE, null, listOf("LEAVE C2 AS IT IS", "P2 NOT HEARD", "P2 GATE applied", "C2's lift was not heard here: listen again on other speakers before reading this as a result", "P2 not heard (PLAIN vs TODAY reads TIE)"))
        expect("P2 CONFLICT leaves, whatever the candidates did", run("P2 CONFLICT leaves, whatever the candidates did", Script(c2 = c2All("XT", "XX", "CC", "TT"), keepC2 = bell)), Outcome.P2_LEAVE, null, listOf("reads CONFLICT"))
        expect("P2 MISSING leaves, as the rules say", run("P2 MISSING leaves, as the rules say", Script(c2 = c2All("--", "XX", "CC", "TT"), keepC2 = bell)), Outcome.P2_LEAVE, null, listOf("reads MISSING"))
        val plainPicked = run("P2 the plain chosen asks, never a build", Script(c2 = c2All("XX", "XX", "CC", "TT"), keepC2 = bell))
        expect("P2 the plain chosen asks, never a build", plainPicked, Outcome.P2_ASK, null, listOf("ASK THE OWNER", "P2 GATE applied", "never a build", "P2 THE PLAIN ONE chosen (PLAIN vs TODAY reads X-STRICT)"))
        expect("P2 the plain chosen once and can't tell once also asks", run("P2 the plain chosen once and can't tell once also asks", Script(c2 = c2All("XC", "XX", "XX", "XX"), keepC2 = bell)), Outcome.P2_ASK, null, listOf("reads X-LEAN"))

        // QS empty, the lean that never qualifies, and the lone answers
        val none = run("QS empty, the lift heard", Script(c2 = c2All("TT", "CC", "CC", "CC"), f2 = f2All("CC", "CC", "CC"), keepC2 = G.KEEP_CANT))
        expect("QS empty, the lift heard", none, Outcome.CASE_1, null, listOf("CASE LINE 1 applied", "LEAVE C2 AS IT IS", "P2 heard", "QS empty", "no candidate chosen over TODAY at C2"))
        expect("X-LEAN at C2 does not qualify", run("X-LEAN at C2 does not qualify", Script(c2 = c2All("TT", "XC", "CC", "CC"), keepC2 = bell)), Outcome.CASE_1, null, listOf("weak lean, not enough", "[weak lean, not enough]", "C2 1 of 2 over TODAY"))
        expect("the lone answer: 17 can't tell and one X, the lift not heard", run("the lone answer: 17 can't tell and one X, the lift not heard", Script(c2 = c2All("CC", "XC", "CC", "CC"), f2 = f2All("CC", "CC", "CC"), keepC2 = G.KEEP_CANT, keepF2 = G.KEEP_CANT)), Outcome.P2_LEAVE, null, listOf("weak lean, not enough"))
        expect("the lone answer with the lift heard (T once)", run("the lone answer with the lift heard (T once)", Script(c2 = c2All("TC", "CC", "CC", "CC"), f2 = f2All("CC", "CC", "CC"), keepC2 = G.KEEP_CANT, keepF2 = G.KEEP_CANT)), Outcome.CASE_1)

        // the F#2 test
        val f2Against = run("F#2 contradicts (T-STRICT)", Script(c2 = heard, f2 = f2All("TT", "CC", "TT"), keepC2 = bell))
        expect("F#2 contradicts (T-STRICT)", f2Against, Outcome.CASE_1, null, listOf("does NOT qualify", "[F#2 contradicts: does not qualify]", "QS empty"))
        expect("F#2 contradicts (T-LEAN)", run("F#2 contradicts (T-LEAN)", Script(c2 = heard, f2 = f2All("TC", "CC", "TT"), keepC2 = bell)), Outcome.CASE_1, null, listOf("T-LEAN"))
        expect("F#2 contradicts (CONFLICT)", run("F#2 contradicts (CONFLICT)", Script(c2 = heard, f2 = f2All("XT", "CC", "TT"), keepC2 = bell)), Outcome.CASE_1, null, listOf("CONFLICT"))
        expect("F#2 TIE passes", run("F#2 TIE passes", Script(c2 = heard, f2 = f2All("CC", "CC", "CC"), keepC2 = bell)), Outcome.CASE_2_BUILD, bell, listOf("F#2 TIE"))
        expect("F#2 X-LEAN passes", run("F#2 X-LEAN passes", Script(c2 = heard, f2 = f2All("XC", "CC", "CC"), keepC2 = bell)), Outcome.CASE_2_BUILD, bell, listOf("F#2 agrees"))
        val noF2 = run("F#2 not answered passes", Script(c2 = heard, keepC2 = bell))
        expect("F#2 not answered passes", noF2, Outcome.CASE_2_BUILD, bell, listOf("F#2 not answered"))
        expect("F#2 one order only passes (MISSING)", run("F#2 one order only passes (MISSING)", Script(c2 = heard, f2 = f2All("T-", "CC", "TT"), keepC2 = bell)), Outcome.CASE_2_BUILD, bell, listOf("F#2 MISSING"))
        expect("two X-STRICT at C2, one kept out at F#2: one qualifies", run("two X-STRICT at C2, one kept out at F#2: one qualifies", Script(c2 = c2All("TT", "XX", "XX", "TT"), f2 = f2All("CC", "TT", "TT"), keepC2 = bell)), Outcome.CASE_2_BUILD, bell, listOf("BELL ONLY C2 2 of 2", "[F#2 contradicts: does not qualify]"))

        // case line 3
        val xTwo = c2All("TT", "XX", "XX", "TT")
        val build = run("two qualify, the keep names one", Script(c2 = xTwo, f2 = f2All("CC", "XX", "TT"), keepC2 = only, keepF2 = G.KEEP_THICK))
        expect("two qualify, the keep names one", build, Outcome.CASE_3_BUILD, only, listOf("BUILD BELL ONLY", "CASE LINE 3 applied", "QS = MORE BELL, BELL ONLY"))
        expect("two qualify, the keep names the other", run("two qualify, the keep names the other", Script(c2 = xTwo, keepC2 = bell)), Outcome.CASE_3_BUILD, bell)
        expect("three qualify, the keep names the third", run("three qualify, the keep names the third", Script(c2 = c2All("TT", "XX", "XX", "XX"), keepC2 = shelf)), Outcome.CASE_3_BUILD, shelf, listOf("QS has 3 candidates"))
        val ask = run("two qualify, the keep names TODAY's version", Script(c2 = xTwo, keepC2 = today))
        expect("two qualify, the keep names TODAY's version", ask, Outcome.CASE_3_ASK, null, listOf("ASK THE OWNER", "it names TODAY's version", "CASE LINE 3 applied"))
        expect("two qualify, the keep says none", run("two qualify, the keep says none", Script(c2 = xTwo, keepC2 = G.KEEP_NONE)), Outcome.CASE_3_ASK, null, listOf("it names THE PLAIN ONE"))
        expect("two qualify, the keep refuses all four", run("two qualify, the keep refuses all four", Script(c2 = xTwo, keepC2 = G.KEEP_THICK)), Outcome.CASE_3_ASK, null, listOf("ALL OF THEM SOUND TOO THICK OR BOOMY"))
        expect("two qualify, the keep says can't say", run("two qualify, the keep says can't say", Script(c2 = xTwo, keepC2 = G.KEEP_CANT)), Outcome.CASE_3_ASK, null, listOf("I CAN'T SAY"))
        expect("two qualify, no keep answer", run("two qualify, no keep answer", Script(c2 = xTwo)), Outcome.CASE_3_ASK, null, listOf("no C2 keep answer was counted"))
        expect("two qualify, the keep names a version outside QS", run("two qualify, the keep names a version outside QS", Script(c2 = xTwo, keepC2 = shelf, keepF2 = shelf, f2 = f2All("CC", "CC", "TT"))), Outcome.CASE_3_ASK, null, listOf("it names MORE SHELF", "keep says the opposite of the pairs"))

        // a clean owner who hears MORE SHELF and keeps it
        val cleanShelf = run("a clean MORE SHELF owner", Script(c2 = c2All("TT", "TT", "TT", "XX"), f2 = f2All("TT", "TT", "XX"), keepC2 = shelf, keepF2 = shelf))
        expect("a clean MORE SHELF owner", cleanShelf, Outcome.CASE_2_BUILD, shelf, listOf("BUILD MORE SHELF", "F#2 agrees", "the shelf alone there"))

        // P1: the void page (3 or more CONFLICT of the four), and what does not void it
        val void = run("void, three CONFLICT at C2", Script(c2 = c2All("TT", "XT", "TX", "XT"), ident = G.PAIR_A))
        expect("void, three CONFLICT at C2", void, Outcome.VOID, null, listOf("THE PAGE IS VOID", "NOISE SIGN", "PAGE VOID (P1)", "BUILD MAP NOT READ", "3 of the four non-identical C2 comparisons are CONFLICT"))
        check(void.lines.none { "INFERRED ruler read" in it }) { "SELFCHECK: a void page printed a ruler read, which is a reading of it" }
        expect("void, always A", run("void, always A", Script(all = G.PAIR_A)), Outcome.VOID, null, listOf("4 of the four non-identical C2 comparisons are CONFLICT"))
        expect("void, always B", run("void, always B", Script(all = G.PAIR_B)), Outcome.VOID, null, listOf("4 of the four non-identical C2 comparisons are CONFLICT"))
        expect("void, no device answer and three CONFLICT", run("void, no device answer and three CONFLICT", Script(device = null, c2 = c2All("TT", "XT", "TX", "XT"))), Outcome.VOID, null, listOf("the device question is not answered and 3 of the four non-identical C2 comparisons are CONFLICT"))
        expect("void, no device answer, other answers saved", run("void, no device answer, other answers saved", Script(device = null, c2 = heard)), Outcome.VOID, null, listOf("NOT ANSWERED", "get the device answer from the owner"))
        expect("void, a device that is none of the six", run("void, a device that is none of the six", Script(extra = mapOf(G.Q_DEVICE to JsonValue.Str("tablet")))), Outcome.VOID, null, listOf("IGNORED: ${G.Q_DEVICE}"))
        expect("a note alone is not NO ANSWERS: the device is missing", run("a note alone is not NO ANSWERS: the device is missing", Script(device = null, extra = mapOf(G.Q_NOTE to JsonValue.Str("only a note")))), Outcome.VOID, null, listOf("NOT ANSWERED"))
        expect("two CONFLICT do not void the page", run("two CONFLICT do not void the page", Script(c2 = c2All("TT", "XT", "TX", "CC"))), Outcome.CASE_1, null, listOf("P1 READABLE"))
        expect("one CONFLICT on the plain comparison", run("one CONFLICT on the plain comparison", Script(c2 = c2All("XT", "XX", "CC", "CC"))), Outcome.P2_LEAVE, null, listOf("P1 READABLE", "reads CONFLICT"))

        // the incomplete page
        val incomplete = run("incomplete, a candidate order missing", Script(c2 = c2All("TT", "X-", "CC", "TT")))
        expect("incomplete, a candidate order missing", incomplete, Outcome.INCOMPLETE, null, listOf("NO CASE LINE APPLIES YET", "q_pair_"))
        val undecided = run("incomplete, P1 undecided", Script(c2 = c2All("--", "XX", "--", "--")))
        expect("incomplete, P1 undecided", undecided, Outcome.INCOMPLETE, null, listOf("P1 UNDECIDED"))
        check(undecided.lines.none { "INFERRED ruler read" in it }) { "SELFCHECK: an UNDECIDED page printed a ruler read" }
        expect("incomplete, two CONFLICT and one MISSING", run("incomplete, two CONFLICT and one MISSING", Script(c2 = c2All("TT", "XT", "TX", "X-"))), Outcome.INCOMPLETE, null, listOf("P1 UNDECIDED"))
        val noRun = key.widgets(G.G_C2, Comparison.MORE_BELL)[0].id
        expect("the play gate, one widget with no run", run("the play gate, one widget with no run", Script(c2 = heard, noRun = setOf(noRun))), Outcome.INCOMPLETE, null, listOf("IGNORED: $noRun"))
        expect("the play gate, no plays at all", run("the play gate, no plays at all", Script(c2 = heard, f2 = f2All("XX", "CC", "TT"), plays = false)), Outcome.INCOMPLETE, null, listOf("P1 UNDECIDED"))
        val wrongType = key.widgets(G.G_C2, Comparison.MORE_BELL)[1].id
        expect("a field of the wrong type is reported", run("a field of the wrong type is reported", Script(c2 = heard, extra = mapOf(wrongType to JsonValue.Num(3.0)))), Outcome.INCOMPLETE, null, listOf("IGNORED: $wrongType: expected text"))

        // the identical pair, the device note, bookkeeping
        val noisy = run("the identical pair answered A", Script(c2 = heard, ident = G.PAIR_A, keepC2 = bell))
        expect("the identical pair answered A", noisy, Outcome.CASE_2_BUILD, bell, listOf("NOISE SIGN yes (identical pair: A chosen)"))
        check(one.lines.none { "NOISE SIGN" in it } && one.lines.any { "the clean answer" in it }) { "SELFCHECK: the clean identical answer was printed as a noise sign" }
        expect("the identical pair not answered", run("the identical pair not answered", Script(c2 = heard, ident = null, keepC2 = bell)), Outcome.CASE_2_BUILD, bell, listOf("NOISE SIGN no (identical pair: not answered)"))
        expect("extra bookkeeping is tolerated", run("extra bookkeeping is tolerated", Script(c2 = heard, keepC2 = bell, extra = mapOf("pairsStarted" to JsonValue.Obj(emptyMap())))), Outcome.CASE_2_BUILD, bell)
        check(reports.getValue("extra bookkeeping is tolerated").lines.none { it.startsWith("$P IGNORED") }) { "SELFCHECK: an unknown field was reported as ignored" }
        expect("a phone speaker is noted", run("a phone speaker is noted", Script(device = G.DEVICE_PHONE, c2 = heard, keepC2 = bell)), Outcome.CASE_2_BUILD, bell, listOf("DEVICE NOTE"))

        // the owner's free note is printed JSON-quoted, whatever it holds
        val noteText = "Das C klingt \"full\" and \"thick\", \\ boomy\nsecond line \u00e9 \u2014 \u201cvoller\u201d"
        val withNote = run("the note is JSON-quoted", Script(c2 = heard, keepC2 = bell, extra = mapOf(G.Q_NOTE to JsonValue.Str(noteText))))
        expect("the note is JSON-quoted", withNote, Outcome.CASE_2_BUILD, bell)
        val noteLine = withNote.lines.single { "NOTE (HEARD" in it }
        check(noteLine.endsWith(": " + jq(noteText)) && "(HEARD, the owner's own text, as a JSON-quoted string)" in noteLine && "\n" !in noteLine) { "SELFCHECK: the note is not printed as a JSON-quoted string under a HEARD label: $noteLine" }
        check(Json.parse(noteLine.substringAfter("string): ")) == JsonValue.Str(noteText)) { "SELFCHECK: the printed note does not parse back to the owner's text" }

        // the endings before P1
        expect("no document at all", runSaved("no document at all", Saved(null)), Outcome.NO_DOCUMENT, null, listOf("NO DOCUMENT READ (no verdict document was given)"))
        val twoJson = runSaved("a folder with two .json files and none of the expected name", Saved(null, folderFiles = listOf("a.json", "b \"x\".json", "notes.txt", "sub/")))
        expect("a folder with two .json files and none of the expected name", twoJson, Outcome.NO_DOCUMENT, null, listOf("NO DOCUMENT READ", "folder files: [\"a.json\", \"b \\\"x\\\".json\", \"notes.txt\", \"sub/\"]"))
        val unreadable = ArrayList<String>()
        readDoc("{\"q_device\":", "arco_r1j_overall.json", unreadable)
        expect("a cut-off file", runSaved("a cut-off file", Saved(null, unreadable, listOf("arco_r1j_overall.json"))), Outcome.NO_DOCUMENT, null, listOf("NO DOCUMENT READ (unreadable: \"arco_r1j_overall.json\"", "UNREADABLE"))
        expect("a path that does not exist", runSaved("a path that does not exist", Saved(null, pathMissing = true)), Outcome.NOT_FOUND, null, listOf("NO DOCUMENT READ (path not found)"))
        val absent = loadVerdict(absentPath)
        check(absent.pathMissing && absent.doc == null) { "SELFCHECK: a path that does not exist was not reported as such: ${absentPath.path}" }
        expect("the loader on a path that does not exist", runSaved("the loader on a path that does not exist", absent), Outcome.NOT_FOUND, null, listOf("NO DOCUMENT READ (path not found)"))
        val wrapped = readDoc("{\"doc\":{\"q_device\":\"headphones\"},\"x y\":1}", "wrapped", ArrayList())
        expect("an unknown wrapper", runSaved("an unknown wrapper", Saved(wrapped, readFrom = "wrapped.json")), Outcome.SHAPE, null, listOf("DOC SHAPE NOT RECOGNISED, top-level keys: [\"doc\", \"x y\"]"))
        expect("an empty doc", runSaved("an empty doc", Saved(emptyMap())), Outcome.SHAPE, null, listOf("DOC SHAPE NOT RECOGNISED, top-level keys: []"))
        val playsOnly = run("plays only, no SAVE (the questions absent)", Script(device = null, ident = null))
        expect("plays only, no SAVE (the questions absent)", playsOnly, Outcome.NO_ANSWERS, null, listOf("NO ANSWERS SAVED: 15 runs recorded, no answer filled: the owner probably did not tap SAVE VERDICT; their answers stay on their phone, ask them to open the page and tap SAVE VERDICT"))
        expect("plays only, no SAVE (blank strings as the page writes them)", run("plays only, no SAVE (blank strings as the page writes them)", Script(device = null, ident = null, blanks = true)), Outcome.NO_ANSWERS, null, listOf("NO ANSWERS SAVED: 15 runs recorded"))
        expect("nothing answered and nothing played", run("nothing answered and nothing played", Script(device = null, ident = null, plays = false)), Outcome.NO_ANSWERS, null, listOf("NO ANSWERS SAVED: 0 runs recorded"))
        check(playsOnly.lines.none { "PAGE VOID" in it } && playsOnly.result.contains("ask them to open the page and tap SAVE VERDICT") && !playsOnly.result.contains("get the device answer")) { "SELFCHECK: a plays-only doc was called a void page or told to get the device answer" }

        // every ending is reached, and the RESULT lines (the source taken off) are all different
        val firstOf = Outcome.entries.associateWith { o -> reports.values.firstOrNull { it.outcome == o } }
        check(firstOf.values.none { it == null }) { "SELFCHECK: an ending was not reached: ${firstOf.filterValues { it == null }.keys}" }
        val bodies = firstOf.values.map { it!!.body }
        check(bodies.toSet().size == bodies.size) { "SELFCHECK: two endings print the same RESULT line: $bodies" }
        println("$P SELFCHECK all ${Outcome.entries.size} endings (${Outcome.entries.joinToString(", ")}) are reached and each prints a different RESULT line: OK")
        for ((name, r) in reports) {
            check(r.lines.none { it.startsWith("$P RESULT:") } && r.result.startsWith("$P RESULT: ${shortName(name)}: ")) { "SELFCHECK $name: the report does not have exactly one RESULT line, last" }
            if (r.built != null) {
                check(r.p1 == State.READABLE && r.p2 == P2Read.HEARD) { "SELFCHECK $name: built although P1 was not READABLE or P2 not heard" }
                check(r.verdicts["${G.G_C2}/${r.built}"] == Vd.X_STRICT) { "SELFCHECK $name: built a candidate that is not X-STRICT at C2" }
                check(r.verdicts["${G.G_F2}/${r.built}"] !in setOf(Vd.T_STRICT, Vd.T_LEAN, Vd.CONFLICT)) { "SELFCHECK $name: built a candidate that F#2 contradicts" }
            }
            if (r.p1 != State.READABLE) check(r.lines.none { "INFERRED ruler read" in it }) { "SELFCHECK $name: a page that is not READABLE printed a ruler read" }
        }
        guardWords(reports.values.flatMap { it.lines + it.result })
        println("$P SELFCHECK ${reports.size} invented reports each end in ONE RESULT line; a build always has P1 READABLE, P2 heard, X-STRICT at C2 and no contradiction at F#2; no ruler read outside a READABLE page; none says a sound word outside quotes: OK")
        mustThrow("the words law on a line that describes a sound") { guardWords(listOf("$P the sound is full")) }
        mustHold("the words law on the page's own quoted word") { guardWords(listOf("$P owner answered \"A SOUNDS FULLER\" (HEARD)")) }
        mustHold("the words law on the owner's text with quotes and sound words in it") { guardWords(withNote.lines) }
        mustThrow("the words law on the owner's text printed bare") { guardWords(listOf("$P NOTE $noteText")) }
        mustThrow("an ending that is not the one wanted") { expect("control", one, Outcome.CASE_1) }
        mustThrow("a build that is not the one wanted") { expect("control", build, Outcome.CASE_3_BUILD, bell) }
        mustThrow("a plays-only doc expected to be a void page") { expect("control", playsOnly, Outcome.VOID) }

        // every way to answer the eight C2 pair widgets: nothing builds that the rules do not allow, and a single decisive answer never builds
        val letters = "XTC"
        var builds = 0
        var voids = 0
        for (code in 0 until 6561) {
            var n = code
            val l = CharArray(8) { val d = n % 3; n /= 3; letters[d] }
            val r = decode(key, Saved(invent(key, Script(c2 = c2All("${l[0]}${l[1]}", "${l[2]}${l[3]}", "${l[4]}${l[5]}", "${l[6]}${l[7]}"), keepC2 = G.KEEP_CANT))), "grid")
            val decisive = l.count { it != 'C' }
            check(r.outcome != Outcome.INCOMPLETE) { "SELFCHECK grid ${String(l)}: a complete page ended INCOMPLETE" }
            check((r.outcome == Outcome.VOID) == (r.verdicts.entries.count { it.key.startsWith("${G.G_C2}/") && it.value == Vd.CONFLICT } >= 3)) { "SELFCHECK grid ${String(l)}: P1 is not void exactly at 3 or more CONFLICT" }
            if (r.outcome == Outcome.VOID) voids++
            if (r.built != null) {
                builds++
                check(decisive >= 3) { "SELFCHECK grid ${String(l)}: built on $decisive decisive answers" }
                check(r.p2 == P2Read.HEARD && r.verdicts["${G.G_C2}/${r.built}"] == Vd.X_STRICT) { "SELFCHECK grid ${String(l)}: built without P2 heard and X-STRICT at C2" }
            }
            if (decisive <= 1) check(r.built == null) { "SELFCHECK grid ${String(l)}: a single decisive answer built ${r.built}" }
        }
        println("$P SELFCHECK all 6561 ways to answer the eight C2 pair widgets (each X, T or can't tell; F#2 not answered; keep I CAN'T SAY): a build always needs P2 heard, X-STRICT at C2 and three decisive answers; no single decisive answer builds; void exactly at 3 or more CONFLICT ($voids void, $builds build; this grid is not the guessing rate: the F#2 test and a random keep are not in it): OK")
    }

    // ---- main ------------------------------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        check(args.isNotEmpty()) { "usage: ArcoBellGateDecoder <key file> [<verdict folder or file>...] (gradle: :synth:decodeArcoBellGate -PgateVerdicts=<path>[,<path>...])" }
        val keyFile = File(args[0])
        val key = loadKey(keyFile)
        for (l in rulesLines()) println(l)
        selfCheck(key, File(keyFile.absoluteFile.parentFile, "arco-bell-gate/manifest.json"), File(keyFile.absoluteFile.parentFile, "arco-bell-gate-no-such-verdict-path"))
        if (args.size == 1) println("$P no verdict path given: only the rules and the self-check were run")
        for (path in args.drop(1)) decode(key, loadVerdict(File(path)), path).print()
    }
}
