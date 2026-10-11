package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.ArcoBellGateGenerator.Comparison
import com.snipsnap.synth.ArcoBellGateGenerator.Role
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Reads what the owner saved on the ARCO R1j bell gate page and applies the reading rules that the page brief wrote down before anyone listened: the verdict of a comparison from its two orders, P1 (is the page
 * readable), P2 (is the engine's C2 lift heard at all), P3 (the direction, C2 then F#2), the identical pair as a noise sign, and the build map with its three case lines and the keep notes. Run
 * `./gradlew :synth:decodeArcoBellGate -PgateVerdicts=<dir or file>[,<dir or file>...]` (it reads `testkit/arco-bell-gate-key.json` and prints one report per verdict path), or
 * `ArcoBellGateDecoder <key file> <verdict path>...`. Written and run on made-up verdict files before the page is published. The decoder prints facts and the rules' outcomes; it never says how a sound sounds, and nothing
 * it prints is a listening claim: it is the owner's taps, mapped back through the private key to what each clip was. Every printed fact is labelled MEASURED (a number from the key or from the page's own play counts),
 * HEARD (the owner's recorded word, always inside double quotes) or INFERRED (the decoder's reading, never a rule, never used by the build map). Printed lines start `ARCO bell decode`; each report ends with ONE line
 * starting `ARCO bell decode RESULT:` that names the case line that applied (or says why none did).
 *
 * The rules are printed before any answer is read, word for word as the brief has them (the same text is in the list [RULES] and here). Nothing is argued with afterwards.
 *  - Reading a comparison "X vs T" (T = TODAY) from its two orders: the verdict is **X** if X was chosen in both orders, or in one order with "can't tell" in the other; **T** if T was chosen in both orders, or in one with "can't tell" in the other; **TIE** if "can't tell" in both; **CONFLICT** if X was chosen in one order and T in the other (that is a position habit, not a hearing). The PLAIN vs TODAY comparison reads the same way with PLAIN as X (so verdict "T" means the engine's lift is heard as fuller than the plain at C2).
 *  - **P1 (the page is readable):** the device question is answered, and at C2 at most 1 of the four non-identical comparisons is CONFLICT. Otherwise the page is VOID: report it, read nothing, and say so. The identical pair is reported separately: "can't tell" is the clean answer; A or B on it is printed as a noise sign (one pair cannot void a page, but it is printed beside the verdicts).
 *  - **P2 (is C2 lift heard at all):** PLAIN vs TODAY at C2: verdict T = yes, TIE or X(plain) = the engine's C2 lift is not heard on this device (then every comparison below is among sounds close to the plain, read with that in mind).
 *  - **P3 (direction, C2 first, then F#2):** for each of MORE BELL, BELL ONLY, MORE SHELF the verdict against TODAY.
 *  - **Build map (printed as written):**
 *  - Let XS = the candidates (MORE BELL, BELL ONLY, MORE SHELF) whose C2 verdict against TODAY is X. A candidate with verdict T, TIE or CONFLICT is not in XS (CONFLICT is only limited by P1). Exactly one line applies, and the decoder prints it with the verdicts that led to it; no case is left to judgement afterwards:
 *  - 1. XS empty: **LEAVE C2 AS IT IS** (record the result; TODAY stays).
 *  - 2. XS has one candidate: **BUILD that direction** (MORE BELL = the engine's shelf set to half the bell on the notes whose cap binds; BELL ONLY = bell only there; MORE SHELF = shelf only there), at the capped notes only; print what F#2 said for the same candidate (X = agrees, TIE, T = disagrees: then add "F#2 disagrees: read with care").
 *  - 3. XS has two or three candidates: the C2 keep answer decides among them if it names one of them (**BUILD that**); if it names TODAY, THE PLAIN, "too thick" or "can't say", or a candidate outside XS: **ASK THE OWNER** (print the candidates and the keep answer).
 *  - The keep answers are printed beside the verdicts with each version's measured rms and A-weighted rise. A keep answer alone never triggers a build; if it names a candidate whose verdict is T it is printed as "keep says the opposite of the pairs" for the owner. The F#2 keep is printed only.
 *
 * What the brief does not say, and the decoder therefore fixes here (printed as ADDITION lines with the rules; R1h's decoder behaved the same way: a missing or wrong field is reported as such and never guessed).
 *  - A pair answer counts only if the page recorded that widget's own PLAY A, THEN B run to its end at least once and a play of both its clips (R1h's gate). Otherwise it is IGNORED and listed, and its comparison has an order with no counted answer.
 *  - A comparison with an order that has no counted answer has the verdict MISSING. MISSING is not X, T, TIE or CONFLICT, and it is never guessed.
 *  - P1 is read as: VOID if the device question is not answered or two or more of the four non-identical C2 comparisons are CONFLICT; READABLE if the device is answered and CONFLICT plus MISSING among those four are at most 1; otherwise UNDECIDED (answers are missing and the missing ones could still void the page). On a VOID page nothing after P1 is read.
 *  - P2 with a CONFLICT or a MISSING verdict is NOT ANSWERED: neither yes nor no.
 *  - A case line of the build map is applied only when P1 is READABLE and the C2 verdicts of all three candidates are known. Otherwise the result is NO CASE LINE APPLIES YET, with the answers still needed. In case line 3 a C2 keep with no counted answer names nothing, so the line is ASK THE OWNER.
 *  - F#2 for the same candidate: a CONFLICT is neither agree nor disagree and a MISSING is not answered; both are printed as such. The brief asks for the F#2 line under case line 2; under case line 3 it is printed too, as information only.
 *  - Keep answers are accepted if the value is a clip of that widget, none, thick or cant; anything else is IGNORED and listed. A keep answer needs no play count; the plays of the clip it names are printed.
 *  - Every printed fact is labelled MEASURED, HEARD or INFERRED. The owner's recorded words are printed inside double quotes and are the only place a sound word may appear. The INFERRED ruler line says which rulers (rms, Loudness.of, A-weighted) the owner's pick goes with or against; it is printed only on a readable page, it is not a rule and the build map never reads it.
 *
 * The saved doc ([ArcoBellGateGenerator]'s contract; the page writes it through R1e's saving logic under the db path `verdicts/arco_r1j_overall`; all values are strings or numbers): `{q_device, q_pair_1 .. q_pair_15,
 * q_keep_C2, q_keep_F2, q_note, plays, savedAt}`. `q_device` is one of the six device answers; each q_pair is `a`, `b` or `cant` (`a` and `b` are the first and second clip of the widget, as the key lists them); each
 * q_keep is the id of a clip of that widget, `none`, `thick` or `cant`; `plays` is `{<clip id>: <starts>, <pair widget id>: <PLAY A, THEN B runs that reached the end>}`. A field that is empty or absent is unanswered. The page may
 * keep other bookkeeping beside these; the decoder reads only the fields above. The verdict path is a folder holding `arco_r1j_overall.json` (also `overall.json`, `verdicts_arco_r1j_overall.json` or
 * `verdicts` and then `arco_r1j_overall.json`; the file is the doc, or `{"data": <the doc>}` as a store export may wrap it), or one file with the doc itself, `{"data": <the doc>}`, `{"overall": <the doc>}` or
 * `{"verdicts/arco_r1j_overall": <the doc>}`. A missing doc is read as nothing answered, and said so. A file that is empty or cut off (not valid JSON) is read the same way and listed as UNREADABLE with its name; it never stops the report.
 *
 * Self-check. Before it reads any real file the decoder runs itself on invented answers (SELFCHECK lines): the nine ways two orders can read, every case line 1, 2 and 3 (and the ways case line 3 asks the owner), the void
 * page, the incomplete page, the identical pair, the play gate, the loader forms, the words law on everything it prints, and the page's words against `testkit/arco-bell-gate/manifest.json` when that file is there. A SELFCHECK
 * control calls the same check with a wrong expectation and requires it to throw. A SELFCHECK failure stops the run before any real file is read. R1h's decoder had no test class, so none is added: this is the same
 * proof, inside the main.
 */
object ArcoBellGateDecoder {

    private val G = ArcoBellGateGenerator

    /** Every printed line starts with this. */
    private const val P = "ARCO bell decode"

    /** P1: at most this many of the four non-identical C2 comparisons may be CONFLICT. A rule of the page brief, fixed before anyone listened. Not a listening value. */
    private const val P1_MAX_CONFLICTS = 1

    /** Two rulers are called level in a comparison when they differ by no more than this many dB (the INFERRED ruler line only, never a rule). Not a listening value. */
    private const val RULER_LEVEL_DB = 0.05

    /** The made-up answers of the self-check count each clip as played this many times. Not a listening value. */
    private const val SELF_PLAYS = 2

    /** The brief's reading rules, word for word: the text the decoder prints before it reads any answer. */
    internal val RULES: List<String> = listOf(
        "Reading a comparison \"X vs T\" (T = TODAY) from its two orders: the verdict is **X** if X was chosen in both orders, or in one order with \"can't tell\" in the other; **T** if T was chosen in both orders, or in one with \"can't tell\" in the other; **TIE** if \"can't tell\" in both; **CONFLICT** if X was chosen in one order and T in the other (that is a position habit, not a hearing). The PLAIN vs TODAY comparison reads the same way with PLAIN as X (so verdict \"T\" means the engine's lift is heard as fuller than the plain at C2).",
        "**P1 (the page is readable):** the device question is answered, and at C2 at most 1 of the four non-identical comparisons is CONFLICT. Otherwise the page is VOID: report it, read nothing, and say so. The identical pair is reported separately: \"can't tell\" is the clean answer; A or B on it is printed as a noise sign (one pair cannot void a page, but it is printed beside the verdicts).",
        "**P2 (is C2 lift heard at all):** PLAIN vs TODAY at C2: verdict T = yes, TIE or X(plain) = the engine's C2 lift is not heard on this device (then every comparison below is among sounds close to the plain, read with that in mind).",
        "**P3 (direction, C2 first, then F#2):** for each of MORE BELL, BELL ONLY, MORE SHELF the verdict against TODAY.",
        "**Build map (printed as written):**",
        "Let XS = the candidates (MORE BELL, BELL ONLY, MORE SHELF) whose C2 verdict against TODAY is X. A candidate with verdict T, TIE or CONFLICT is not in XS (CONFLICT is only limited by P1). Exactly one line applies, and the decoder prints it with the verdicts that led to it; no case is left to judgement afterwards:",
        "1. XS empty: **LEAVE C2 AS IT IS** (record the result; TODAY stays).",
        "2. XS has one candidate: **BUILD that direction** (MORE BELL = the engine's shelf set to half the bell on the notes whose cap binds; BELL ONLY = bell only there; MORE SHELF = shelf only there), at the capped notes only; print what F#2 said for the same candidate (X = agrees, TIE, T = disagrees: then add \"F#2 disagrees: read with care\").",
        "3. XS has two or three candidates: the C2 keep answer decides among them if it names one of them (**BUILD that**); if it names TODAY, THE PLAIN, \"too thick\" or \"can't say\", or a candidate outside XS: **ASK THE OWNER** (print the candidates and the keep answer).",
        "The keep answers are printed beside the verdicts with each version's measured rms and A-weighted rise. A keep answer alone never triggers a build; if it names a candidate whose verdict is T it is printed as \"keep says the opposite of the pairs\" for the owner. The F#2 keep is printed only.",
    )


    /** The decoder's own rules for what the brief does not say, printed with the rules (see the class KDoc). */
    internal val ADDITIONS: List<String> = listOf(
        "A pair answer counts only if the page recorded that widget's own PLAY A, THEN B run to its end at least once and a play of both its clips (R1h's gate). Otherwise it is IGNORED and listed, and its comparison has an order with no counted answer.",
        "A comparison with an order that has no counted answer has the verdict MISSING. MISSING is not X, T, TIE or CONFLICT, and it is never guessed.",
        "P1 is read as: VOID if the device question is not answered or two or more of the four non-identical C2 comparisons are CONFLICT; READABLE if the device is answered and CONFLICT plus MISSING among those four are at most 1; otherwise UNDECIDED (answers are missing and the missing ones could still void the page). On a VOID page nothing after P1 is read.",
        "P2 with a CONFLICT or a MISSING verdict is NOT ANSWERED: neither yes nor no.",
        "A case line of the build map is applied only when P1 is READABLE and the C2 verdicts of all three candidates are known. Otherwise the result is NO CASE LINE APPLIES YET, with the answers still needed. In case line 3 a C2 keep with no counted answer names nothing, so the line is ASK THE OWNER.",
        "F#2 for the same candidate: a CONFLICT is neither agree nor disagree and a MISSING is not answered; both are printed as such. The brief asks for the F#2 line under case line 2; under case line 3 it is printed too, as information only.",
        "Keep answers are accepted if the value is a clip of that widget, none, thick or cant; anything else is IGNORED and listed. A keep answer needs no play count; the plays of the clip it names are printed.",
        "Every printed fact is labelled MEASURED, HEARD or INFERRED. The owner's recorded words are printed inside double quotes and are the only place a sound word may appear. The INFERRED ruler line says which rulers (rms, Loudness.of, A-weighted) the owner's pick goes with or against; it is printed only on a readable page, it is not a rule and the build map never reads it.",
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

    /** What each direction the build map can name means for the engine (the brief's parentheses). */
    private val DIRECTION_TEXT: Map<String, String> = mapOf(
        Role.MORE_BELL.key to "the engine's shelf set to half the bell on the notes whose cap binds",
        Role.BELL_ONLY.key to "bell only there",
        Role.MORE_SHELF.key to "shelf only there",
    )

    /** The three candidates, in the order the brief lists them. */
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

    /** The saved doc, or null when none was found or none could be read, and the files that were unreadable. */
    internal class Saved(val doc: Map<String, JsonValue>?, val unreadable: List<String> = emptyList())

    private fun looksLikeDoc(o: Map<String, JsonValue>): Boolean = o.keys.any { it == "savedAt" || it == G.PLAYS || it.startsWith("q_") }

    /** The doc inside a parsed file: the doc itself, `{"data": doc}`, `{"overall": doc}` or `{"verdicts/arco_r1j_overall": doc}`; null when the value is not a JSON object. */
    internal fun docFromValue(root: JsonValue): Map<String, JsonValue>? {
        val o = (root as? JsonValue.Obj)?.entries ?: return null
        val named = o[G.OVERALL_KEY] ?: o["${G.DOC_PREFIX}${G.OVERALL_KEY}"]
        val chosen = if (named is JsonValue.Obj && !looksLikeDoc(o)) named.entries else o
        val inner = chosen["data"]
        return if (inner is JsonValue.Obj && !looksLikeDoc(chosen)) inner.entries else chosen
    }

    /** Parses [text]; a cut-off or empty file is listed in [unreadable] under [name] and read as nothing answered. */
    internal fun readDoc(text: String, name: String, unreadable: MutableList<String>): Map<String, JsonValue>? {
        val root = try {
            Json.parse(text)
        } catch (e: JsonException) {
            unreadable += "$name (${e.message?.take(80) ?: "not valid JSON"})"
            return null
        }
        val doc = docFromValue(root)
        if (doc == null) unreadable += "$name (not a JSON object)"
        return doc
    }

    /** Reads the saved doc under [path]. */
    internal fun loadVerdict(path: File): Saved {
        val unreadable = ArrayList<String>()
        val file: File? = if (path.isDirectory) {
            val bare = G.DOC_PREFIX.removePrefix("verdicts/") + G.OVERALL_KEY + ".json"
            val names = listOf(bare, "${G.OVERALL_KEY}.json", "verdicts_$bare", "verdicts/$bare")
            names.map { File(path, it) }.firstOrNull { it.isFile }
        } else {
            check(path.isFile) { "the verdict path ${path.path} is neither a folder nor a file" }
            path
        }
        val doc = file?.let { readDoc(it.readText(Charsets.UTF_8), it.path, unreadable) }
        return Saved(doc, unreadable)
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
                if (!isClip && id !in pairIds) { ignored += "plays of $id: not a clip or a pair widget of the key"; continue }
                val count = numOf(n)
                if (count == null || !count.isFinite() || count < 0) { ignored += "plays of $id: not a count (${(n as? JsonValue.Num)?.value ?: kindOf(n)})"; continue }
                if (isClip) plays[id] = count.toInt() else runs[id] = count.toInt()
            }
            else -> ignored += "${G.PLAYS}: expected an object of counts, found ${kindOf(p)}: read as no plays recorded"
        }
        val device = textOf(doc, G.Q_DEVICE, ignored)?.let {
            if (it in DEVICE_WORDS) it else { ignored += "${G.Q_DEVICE}: \"$it\" is not one of ${DEVICE_WORDS.keys}"; null }
        }
        val pairs = LinkedHashMap<String, String>()
        for (p in key.pairs) {
            val a = textOf(doc, p.id, ignored) ?: continue
            when {
                a !in PAIR_WORDS -> ignored += "${p.id}: \"$a\" is not a, b or cant"
                (plays[p.a] ?: 0) < 1 || (plays[p.b] ?: 0) < 1 -> ignored += "${p.id}: answered without both clips played, so it does not count"
                (runs[p.id] ?: 0) < 1 -> ignored += "${p.id}: answered without its own PLAY A, THEN B run to the end, so it does not count"
                else -> pairs[p.id] = a
            }
        }
        val keep = LinkedHashMap<String, String>()
        for (k in key.keeps) {
            val a = textOf(doc, k.id, ignored) ?: continue
            if (a in KEEP_WORDS || k.clips.any { it.id == a }) keep[k.id] = a else ignored += "${k.id}: \"$a\" is not one of its clips or a keep answer the page offers"
        }
        return Answers(device, pairs, plays, runs, keep, textOf(doc, G.Q_NOTE, ignored), textOf(doc, "savedAt", ignored), ignored)
    }

    // ---- the reading ---------------------------------------------------------------------------------------

    /** What the owner chose in one widget of a comparison: the candidate (X), TODAY (T), or neither (can't tell). */
    internal enum class Pk { X, T, CANT }

    /** The verdict of a comparison: the brief's four, and MISSING for an order with no counted answer (the decoder's own, never guessed). */
    internal enum class Vd { X, T, TIE, CONFLICT, MISSING }

    /** The brief's reading of a comparison from its two orders (null is an order with no counted answer). */
    internal fun verdictOf(first: Pk?, second: Pk?): Vd = when {
        first == null || second == null -> Vd.MISSING
        (first == Pk.X && second == Pk.T) || (first == Pk.T && second == Pk.X) -> Vd.CONFLICT
        first == Pk.CANT && second == Pk.CANT -> Vd.TIE
        first == Pk.X || second == Pk.X -> Vd.X
        else -> Vd.T
    }

    /** One widget as the owner answered it: the counted answer (null if none), what it picked and which role that is. */
    internal class OrderRead(val pair: KeyPair, val answer: String?, val pick: Pk?, val pickedRole: String?)

    /** One comparison of one group: the candidate against TODAY, its two orders and its verdict. */
    internal class Cmp(val group: String, val comparison: Comparison, val orders: List<OrderRead>, val verdict: Vd)

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
    private fun reasonOf(c: Cmp): String {
        val picks = c.orders.map { it.pick }
        return when (c.verdict) {
            Vd.X -> if (picks.all { it == Pk.X }) "X chosen in both orders" else "X chosen in one order, can't tell in the other"
            Vd.T -> if (picks.all { it == Pk.T }) "TODAY chosen in both orders" else "TODAY chosen in one order, can't tell in the other"
            Vd.TIE -> "can't tell in both orders"
            Vd.CONFLICT -> "X chosen in one order and T in the other: a position habit, not a hearing"
            Vd.MISSING -> "an order has no counted answer: not guessed"
        }
    }

    /** Which page state P1 gives: see ADDITIONS. */
    internal enum class State { READABLE, VOID, UNDECIDED }

    /** How a report ends: the page is void, no case line can apply yet (answers missing), or one of the build map's case lines (3 either builds or asks). */
    internal enum class Outcome { VOID, INCOMPLETE, CASE_1, CASE_2, CASE_3_BUILD, CASE_3_ASK }

    /**
     * A decoded report: the printed lines (all but the last), the one RESULT line and its [body] (the line without the source), how it ended, P1's state, P2's verdict (null when not read), every comparison's verdict by
     * `<group>/<comparison key>`, XS (the candidates whose C2 verdict is X; empty when not read) and the role the build map names to build (null if none).
     */
    internal class Report(
        val source: String, val lines: List<String>, val result: String, val body: String, val outcome: Outcome, val p1: State, val p2: Vd?, val verdicts: Map<String, Vd>, val xs: List<String>, val built: String?,
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

    private fun owner(words: Map<String, String>, answer: String): String = "\"${words.getValue(answer)}\" (HEARD)"

    /** The comparison's lines: both orders with the owner's answers and the measured rises of A and B, then the verdict, the measured gap and the INFERRED ruler line. */
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
        out += "$P   VERDICT $note ${nice(c.comparison.key)} vs TODAY: ${c.verdict} (${reasonOf(c)})$tail"
        val dr = x.rmsRise - t.rmsRise
        val da = x.aRise - t.aRise
        val dl = x.loudRise - t.loudRise
        out += "$P     MEASURED X minus T: rms ${signed(dr)} dB, Loudness.of ${signed(dl)} dB, A-weighted ${signed(da)} dB"
        if (readable && (c.verdict == Vd.X || c.verdict == Vd.T)) {
            fun goes(d: Double): String = if (abs(d) <= RULER_LEVEL_DB) "level" else if ((c.verdict == Vd.X) == (d > 0)) "with" else "against"
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
            else -> "$P   owner answered ${owner(PAIR_WORDS, answer)} on two files that are the same file: NOISE SIGN. One pair cannot void a page, but read every verdict with it in mind; PLAY A, THEN B ran to its end ${a.runs[p.id] ?: 0} time(s)"
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
        G.DEVICE_PHONE, G.DEVICE_LAPTOP -> "INFERRED (not a rule): a small speaker plays little under about 300 Hz, and C2's fundamental is 65 Hz, so the low part of a lift may not be reproduced on this device; read the verdicts with that in mind"
        else -> null
    }

    // ---- decode --------------------------------------------------------------------------------------------

    internal fun decode(key: Key, saved: Saved, source: String): Report {
        val a = answersOf(key, saved)
        val c2 = G.G_C2
        val f2 = G.G_F2
        val cmps = key.groups.associateWith { comparisonsOf(key, a, it) }
        fun cmp(g: String, c: Comparison): Cmp = cmps.getValue(g).first { it.comparison == c }
        fun candidateCmp(g: String, r: Role): Cmp = cmps.getValue(g).first { it.comparison.candidate == r }
        val verdicts = LinkedHashMap<String, Vd>()
        for ((g, list) in cmps) for (c in list) verdicts["$g/${c.comparison.key}"] = c.verdict
        val identical = key.widgets(c2, Comparison.IDENTICAL).single()

        // P1 (ADDITIONS 3): the four non-identical comparisons at C2 and the device
        val four = cmps.getValue(c2)
        val conflicts = four.count { it.verdict == Vd.CONFLICT }
        val unknown = four.count { it.verdict == Vd.MISSING }
        val state = when {
            a.device == null || conflicts > P1_MAX_CONFLICTS -> State.VOID
            conflicts + unknown <= P1_MAX_CONFLICTS -> State.READABLE
            else -> State.UNDECIDED
        }
        val readable = state != State.VOID

        // P2
        val plainCmp = cmp(c2, Comparison.VS_PLAIN)
        val p2: Vd? = if (state == State.VOID) null else plainCmp.verdict

        // the build map
        fun v2(r: Role) = candidateCmp(c2, r).verdict
        fun f2Says(role: Role): String = when (candidateCmp(f2, role).verdict) {
            Vd.X -> "X (F#2 agrees)"
            Vd.TIE -> "TIE"
            Vd.T -> "T (F#2 disagrees: read with care)"
            Vd.CONFLICT -> "CONFLICT (a position habit: neither agrees nor disagrees)"
            Vd.MISSING -> "not answered (an order has no counted answer)"
        }
        val allKnown = CANDIDATES.none { v2(it) == Vd.MISSING }
        val xs = if (state == State.READABLE && allKnown) CANDIDATES.filter { v2(it) == Vd.X } else emptyList()
        val keepC2 = key.keepOf(c2)
        val keepAnswer = a.keep[keepC2.id]
        val keepRole = keepRoleOf(keepC2, keepAnswer)
        val outcome = when {
            state == State.VOID -> Outcome.VOID
            state == State.UNDECIDED || !allKnown -> Outcome.INCOMPLETE
            xs.isEmpty() -> Outcome.CASE_1
            xs.size == 1 -> Outcome.CASE_2
            keepRole != null && CANDIDATES.any { it.key == keepRole && it in xs } -> Outcome.CASE_3_BUILD
            else -> Outcome.CASE_3_ASK
        }
        val built = when (outcome) {
            Outcome.CASE_2 -> xs.single().key
            Outcome.CASE_3_BUILD -> keepRole
            else -> null
        }

        val out = ArrayList<String>()
        out += "$P ===== $source ====="
        out += "$P doc found: ${if (saved.doc != null) "yes (${G.DOC_PREFIX}${G.OVERALL_KEY})" else "NO: NOT FOUND OR UNREADABLE (read as nothing answered)"}"
        for (u in saved.unreadable) out += "$P UNREADABLE: $u: read as nothing answered"
        out += "$P saved at: ${a.savedAt ?: "not recorded"}"
        out += "$P DEVICE: ${a.device?.let { owner(DEVICE_WORDS, it) } ?: "NOT ANSWERED (a required question)"}"
        val required = 1 + key.pairs.size + key.keeps.size
        val unanswered = listOfNotNull(if (a.device == null) G.Q_DEVICE else null) + key.pairs.filter { it.id !in a.pairs }.map { it.id } + key.keeps.filter { it.id !in a.keep }.map { it.id }
        out += "$P ANSWERED: ${a.pairs.size} of ${key.pairs.size} pair widgets counted, keep ${a.keep.size} of ${key.keeps.size}, device ${if (a.device != null) "yes" else "no"}; required items unanswered: ${unanswered.size} of $required" +
            if (unanswered.isEmpty()) "" else " (${unanswered.joinToString(", ")})"
        for (i in a.ignored) out += "$P IGNORED: $i"
        a.note?.let { out += "$P NOTE (HEARD, the owner's own text): ${it.replace("\n", " ")}" }
        out += playLines(key, a)

        for (g in key.groups) {
            out += "$P ----- ${key.notes.getValue(g)}: each comparison with the owner's two answers -----"
            for (c in cmps.getValue(g)) out += comparisonLines(key, a, c, readable)
            if (g == c2) out += identicalLines(key, a)
        }
        out += "$P ----- the keep answers -----"
        for (k in key.keeps) out += keepLines(key, a, k)

        // the verdict lines
        out += "$P ----- the verdict lines -----"
        val fourText = four.joinToString(", ") { "${nice(it.comparison.key)} ${it.verdict}" }
        val deviceText = if (a.device == null) "the device question is NOT ANSWERED" else "the device question is answered"
        out += "$P P1 $state: $deviceText; at C2 the four non-identical comparisons read $fourText; CONFLICT $conflicts, MISSING $unknown (the bar: at most $P1_MAX_CONFLICTS CONFLICT)" + when (state) {
            State.VOID -> ". THE PAGE IS VOID: report it, read nothing, and say so." + if (a.device == null) " The only way to read it is to get the device answer from the owner." else " Re-run the page."
            State.UNDECIDED -> ". Answers are missing and the missing ones could still void the page: not read yet."
            State.READABLE -> ""
        }
        val sameAnswer = a.pairs[identical.id]
        out += "$P P1 identical pair ${identical.id} (reported apart, it cannot void a page): " + when (sameAnswer) {
            null -> "no counted answer"
            G.PAIR_CANT -> "\"${PAIR_WORDS.getValue(sameAnswer)}\" (HEARD): the clean answer"
            else -> "\"${PAIR_WORDS.getValue(sameAnswer)}\" (HEARD): NOISE SIGN, printed beside the verdicts"
        }
        if (state == State.VOID) {
            out += "$P P2 NOT READ (the page is void)"
            out += "$P P3 NOT READ (the page is void)"
            out += "$P BUILD MAP NOT READ (the page is void)"
        } else {
            val p2Text = when (p2) {
                Vd.T -> "YES: PLAIN vs TODAY at C2 reads T: TODAY was chosen over THE PLAIN ONE (HEARD); the engine's C2 lift is heard on this device"
                Vd.TIE, Vd.X -> "NO: PLAIN vs TODAY at C2 reads $p2: the engine's C2 lift is not heard on this device; every comparison below is among sounds close to the plain, read with that in mind"
                Vd.CONFLICT -> "NOT ANSWERED: PLAIN vs TODAY at C2 reads CONFLICT (a position habit, not a hearing): neither yes nor no"
                else -> "NOT ANSWERED: PLAIN vs TODAY at C2 has an order with no counted answer"
            }
            out += "$P P2 $p2Text"
            for (g in key.groups) {
                out += "$P P3 ${key.notes.getValue(g)}: " + CANDIDATES.joinToString(", ") { r -> "${nice(r.key)} ${candidateCmp(g, r).verdict}" }
            }
            // the keep notes (the F#2 keep is printed only)
            for (k in key.keeps) {
                val answer = a.keep[k.id]
                val role = keepRoleOf(k, answer)
                val shown = key.notes.getValue(k.group)
                val line = when {
                    role == Role.TODAY.key -> "names TODAY, which is no candidate: nothing to compare with the pairs"
                    role == null -> "names no clip (${if (answer == null) "no counted answer" else owner(KEEP_WORDS, answer)}): nothing to compare with the pairs"
                    else -> {
                        val vd = cmps.getValue(k.group).first { it.comparison.candidate.key == role }.verdict
                        when (vd) {
                            Vd.T -> "names ${nice(role)}, whose $shown verdict is T: keep says the opposite of the pairs"
                            Vd.X -> "names ${nice(role)}, whose $shown verdict is X: it goes with the pairs"
                            else -> "names ${nice(role)}, whose $shown verdict is $vd"
                        }
                    }
                }
                out += "$P KEEP NOTE $shown (printed beside the verdicts${if (k.group == f2) "; the F#2 keep is printed only" else ""}): $line"
            }
            out += "$P BUILD MAP: XS = ${if (state == State.READABLE && allKnown) xs.joinToString(", ") { nice(it.key) }.ifEmpty { "(empty)" } else "not known yet"} " +
                "from the C2 verdicts ${CANDIDATES.joinToString(", ") { "${nice(it.key)} ${v2(it)}" }}"
            if (p2 == Vd.TIE || p2 == Vd.X) out += "$P BUILD MAP NOTE: P2 says the engine's C2 lift is not heard on this device, so read the line below with that in mind"
            when (outcome) {
                Outcome.INCOMPLETE -> {
                    val needed = listOfNotNull(if (a.device == null) G.Q_DEVICE else null) + cmps.getValue(c2).flatMap { c -> c.orders.filter { it.pick == null }.map { "${it.pair.id} (C2 ${nice(c.comparison.key)} vs TODAY)" } }
                    out += "$P BUILD MAP: NO CASE LINE APPLIES YET. Answers still needed: ${needed.joinToString(", ")}. Ask the owner again; nothing is guessed."
                }
                Outcome.CASE_1 -> out += "$P BUILD MAP line 1 applies: LEAVE C2 AS IT IS (record the result; TODAY stays). XS is empty: " + CANDIDATES.joinToString(", ") { "${nice(it.key)} ${v2(it)}" }
                Outcome.CASE_2 -> {
                    val r = xs.single()
                    out += "$P BUILD MAP line 2 applies: BUILD ${nice(r.key)} (${DIRECTION_TEXT.getValue(r.key)}), at the capped notes only. XS has one candidate: ${nice(r.key)} (C2 verdict X)"
                    out += "$P BUILD MAP line 2: F#2 said for ${nice(r.key)}: ${f2Says(r)}"
                }
                Outcome.CASE_3_BUILD -> {
                    val r = Role.entries.first { it.key == keepRole }
                    out += "$P BUILD MAP line 3 applies: BUILD ${nice(r.key)} (${DIRECTION_TEXT.getValue(r.key)}), at the capped notes only. XS has ${xs.size} candidates (${xs.joinToString(", ") { nice(it.key) }}); the C2 keep answer names ${nice(r.key)}"
                    out += "$P BUILD MAP line 3: F#2 said for ${nice(r.key)}: ${f2Says(r)} (printed only; the brief asks for it under line 2)"
                }
                Outcome.CASE_3_ASK -> {
                    val reason = when {
                        keepAnswer == null -> "no C2 keep answer was counted, so it names nothing"
                        keepAnswer == G.KEEP_NONE -> "it names THE PLAIN (${owner(KEEP_WORDS, keepAnswer)})"
                        keepAnswer == G.KEEP_THICK -> "it is ${owner(KEEP_WORDS, keepAnswer)}"
                        keepAnswer == G.KEEP_CANT -> "it is ${owner(KEEP_WORDS, keepAnswer)}"
                        keepRole == Role.TODAY.key -> "it names TODAY"
                        else -> "it names ${nice(keepRole ?: "?")}, a version outside XS"
                    }
                    out += "$P BUILD MAP line 3 applies: ASK THE OWNER. XS has ${xs.size} candidates (${xs.joinToString(", ") { nice(it.key) }}); the C2 keep answer: $reason"
                }
                Outcome.VOID -> Unit
            }
        }
        deviceNote(a.device)?.let { out += "$P DEVICE NOTE: $it" }
        out += "$P LABELS: MEASURED is a number from the key or the page's play counts; HEARD is the owner's recorded word, in double quotes; INFERRED is a reading by the decoder, never a rule and never used by the build map. Nobody on the team has listened."

        val xsText = if (xs.isEmpty()) "XS empty" else "XS = ${xs.joinToString(", ") { nice(it.key) }}"
        val body = when (outcome) {
            Outcome.VOID -> "PAGE VOID (P1): " + listOfNotNull(
                if (a.device == null) "the device question is not answered" else null,
                if (conflicts > P1_MAX_CONFLICTS) "$conflicts of the four non-identical C2 comparisons are CONFLICT" else null,
            ).joinToString(" and ") + "; no case line applies, nothing is read"
            Outcome.INCOMPLETE -> "NO CASE LINE APPLIES YET (answers missing, see above); nothing is guessed"
            Outcome.CASE_1 -> "CASE LINE 1 applied: LEAVE C2 AS IT IS ($xsText; C2 verdicts ${CANDIDATES.joinToString(", ") { "${nice(it.key)} ${v2(it)}" }})"
            Outcome.CASE_2 -> "CASE LINE 2 applied: BUILD ${nice(built!!)} at the capped notes only ($xsText; F#2 said ${f2Says(xs.single())})"
            Outcome.CASE_3_BUILD -> "CASE LINE 3 applied: BUILD ${nice(built!!)} at the capped notes only ($xsText; the C2 keep names ${nice(built)})"
            Outcome.CASE_3_ASK -> "CASE LINE 3 applied: ASK THE OWNER ($xsText; the C2 keep answer does not name one of them)"
        }
        return Report(source, out, "$P RESULT: ${shortName(source)}: $body", body, outcome, state, p2, verdicts, xs.map { it.key }, built)
    }

    // ---- the rules, printed first ----------------------------------------------------------------------------

    internal fun rulesLines(): List<String> = buildList {
        add("$P RULES, pre-written in the page brief before anyone listened; printed before any answer is read; applied as written; nothing is argued with afterwards:")
        for (r in RULES) add("$P RULE $r")
        add("$P ADDITIONS for what the brief does not say (a missing or wrong field is reported as such and never guessed):")
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

    /** Invented answers. [c2] and [f2] give each comparison's two orders as two letters in the page's order of the two widgets: X (the candidate), T (TODAY), C (can't tell), - (no answer). */
    private class Script(
        val device: String? = G.DEVICE_HEADPHONES, val c2: Map<Comparison, String> = emptyMap(), val f2: Map<Comparison, String> = emptyMap(), val ident: String? = G.PAIR_CANT,
        val keepC2: String? = null, val keepF2: String? = null, val plays: Boolean = true, val noRun: Set<String> = emptySet(), val extra: Map<String, JsonValue> = emptyMap(),
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
        s.device?.let { doc[G.Q_DEVICE] = JsonValue.Str(it) }
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

    /** The decoder never says how a sound sounds except in the owner's recorded words, which it prints inside double quotes: outside them none of the sound words may stand. */
    private fun guardWords(lines: List<String>) {
        for (l in lines) {
            val m = SOUND_WORDS.find(l.replace(Regex("\"[^\"]*\""), ""))
            check(m == null) { "a report line says \"${m?.value}\" outside the owner's quoted words: $l" }
        }
    }

    private fun checkTable(rows: List<Triple<Pk?, Pk?, Vd>>) {
        for ((f, s, want) in rows) check(verdictOf(f, s) == want) { "two orders ($f, $s) read ${verdictOf(f, s)}, the brief says $want" }
    }

    /** The decoded report ended as wanted: [outcome], the role built, and every text of [say] stands in a printed line. */
    private fun expect(name: String, r: Report, outcome: Outcome, built: String? = null, say: List<String> = emptyList()) {
        check(r.outcome == outcome) { "SELFCHECK $name: ended ${r.outcome}, wanted $outcome (${r.body})" }
        check(r.built == built) { "SELFCHECK $name: built ${r.built}, wanted $built" }
        for (s in say) check(r.lines.any { s in it } || s in r.result) { "SELFCHECK $name: no printed line says \"$s\"" }
        println("$P SELFCHECK $name: ${r.outcome}${r.built?.let { " $it" } ?: ""} OK")
    }

    private fun selfCheck(key: Key, manifest: File) {
        // the nine readings of two orders, and the orders with no answer
        val table = ArrayList<Triple<Pk?, Pk?, Vd>>()
        table += Triple(Pk.X, Pk.X, Vd.X)
        table += Triple(Pk.X, Pk.CANT, Vd.X)
        table += Triple(Pk.CANT, Pk.X, Vd.X)
        table += Triple(Pk.T, Pk.T, Vd.T)
        table += Triple(Pk.T, Pk.CANT, Vd.T)
        table += Triple(Pk.CANT, Pk.T, Vd.T)
        table += Triple(Pk.CANT, Pk.CANT, Vd.TIE)
        table += Triple(Pk.X, Pk.T, Vd.CONFLICT)
        table += Triple(Pk.T, Pk.X, Vd.CONFLICT)
        for (p in Pk.entries) {
            table += Triple(p, null, Vd.MISSING)
            table += Triple(null, p, Vd.MISSING)
        }
        table += Triple(null, null, Vd.MISSING)
        checkTable(table)
        println("$P SELFCHECK the verdict of a comparison from its two orders: the nine readings and the orders with no answer: OK")
        mustThrow("the verdict table with X and T read as a TIE") { checkTable(listOf(Triple(Pk.X, Pk.T, Vd.TIE))) }

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

        // the loader forms
        val doc = "{\"q_device\":\"headphones\",\"savedAt\":\"x\"}"
        val forms = listOf(
            "the doc itself" to doc, "a data wrapper" to "{\"data\":$doc}", "an overall wrapper" to "{\"${G.OVERALL_KEY}\":$doc}",
            "a store path wrapper" to "{\"${G.DOC_PREFIX}${G.OVERALL_KEY}\":$doc}",
        )
        for ((name, text) in forms) {
            val unreadable = ArrayList<String>()
            val d = readDoc(text, name, unreadable)
            check(d?.get(G.Q_DEVICE) == JsonValue.Str("headphones") && unreadable.isEmpty()) { "the loader did not find the doc in $name" }
        }
        val cut = ArrayList<String>()
        check(readDoc("{\"q_device\":", "cut off", cut) == null && cut.size == 1) { "a cut-off file is not listed as unreadable" }
        val notObject = ArrayList<String>()
        check(readDoc("[1]", "an array", notObject) == null && notObject.size == 1) { "a file that is not an object is not listed as unreadable" }
        val emptyDoc = ArrayList<String>()
        check(readDoc("{}", "empty", emptyDoc)?.isEmpty() == true && emptyDoc.isEmpty()) { "an empty doc is not read as nothing answered" }
        println("$P SELFCHECK the loader: the doc, a data wrapper, an overall wrapper, a store path wrapper; a cut-off file and a non-object are listed UNREADABLE; an empty doc is nothing answered: OK")
        mustThrow("the loader check on a doc with another device") { check(readDoc(doc.replace("headphones", "earbuds"), "other device", ArrayList())?.get(G.Q_DEVICE) == JsonValue.Str("headphones")) { "the device is not the one the check wants" } }

        // the case lines and the other endings, on invented answers
        val reports = LinkedHashMap<String, Report>()
        fun run(name: String, s: Script): Report = decode(key, Saved(invent(key, s)), name).also { reports[name] = it }
        val bell = Role.MORE_BELL.key
        val only = Role.BELL_ONLY.key
        val shelf = Role.MORE_SHELF.key
        val xTwo = c2All("TC", "XX", "CX", "TT")

        val one = run("one X, clean", Script(c2 = c2All("TT", "XX", "CC", "TT"), f2 = f2All("XX", "CC", "TT"), keepC2 = bell, keepF2 = bell))
        expect("one X, clean", one, Outcome.CASE_2, bell, listOf("BUILD MORE BELL", "F#2 agrees", "P2 YES", "CASE LINE 2 applied"))
        val tie = run("all TIE", Script(c2 = c2All("CC", "CC", "CC", "CC"), f2 = f2All("CC", "CC", "CC"), keepC2 = G.KEEP_CANT, keepF2 = G.KEEP_CANT))
        expect("all TIE", tie, Outcome.CASE_1, null, listOf("LEAVE C2 AS IT IS", "P2 NO", "CASE LINE 1 applied"))
        val build = run("two X, the keep names one", Script(c2 = xTwo, f2 = f2All("CC", "XX", "TT"), keepC2 = only, keepF2 = G.KEEP_THICK))
        expect("two X, the keep names one", build, Outcome.CASE_3_BUILD, only, listOf("BUILD BELL ONLY", "CASE LINE 3 applied"))
        expect("two X, the keep names the other", run("two X, the keep names the other", Script(c2 = xTwo, keepC2 = bell)), Outcome.CASE_3_BUILD, bell)
        expect("three X, the keep names the third", run("three X, the keep names the third", Script(c2 = c2All("TT", "XX", "XX", "XC"), keepC2 = shelf)), Outcome.CASE_3_BUILD, shelf, listOf("XS has 3 candidates"))
        val askBase = Script(c2 = xTwo)
        val ask = run("two X, the keep names TODAY", Script(c2 = xTwo, keepC2 = Role.TODAY.key))
        expect("two X, the keep names TODAY", ask, Outcome.CASE_3_ASK, null, listOf("ASK THE OWNER", "it names TODAY", "CASE LINE 3 applied"))
        expect("two X, the keep says none", run("two X, the keep says none", Script(c2 = xTwo, keepC2 = G.KEEP_NONE)), Outcome.CASE_3_ASK, null, listOf("it names THE PLAIN"))
        expect("two X, the keep refuses all four", run("two X, the keep refuses all four", Script(c2 = xTwo, keepC2 = G.KEEP_THICK)), Outcome.CASE_3_ASK, null, listOf("ALL OF THEM SOUND TOO THICK OR BOOMY"))
        expect("two X, the keep says can't say", run("two X, the keep says can't say", Script(c2 = xTwo, keepC2 = G.KEEP_CANT)), Outcome.CASE_3_ASK, null, listOf("I CAN'T SAY"))
        expect("two X, no keep answer", run("two X, no keep answer", askBase), Outcome.CASE_3_ASK, null, listOf("no C2 keep answer was counted"))
        expect(
            "two X, the keep names a version outside XS", run("two X, the keep names a version outside XS", Script(c2 = xTwo, keepC2 = shelf, keepF2 = shelf, f2 = f2All("CC", "CC", "TT"))),
            Outcome.CASE_3_ASK, null, listOf("a version outside XS", "keep says the opposite of the pairs"),
        )
        val badKeep = run("two X, a keep that is no clip of its widget", Script(c2 = xTwo, extra = mapOf(G.Q_KEEP_C2 to JsonValue.Str("c2_zz"))))
        expect("two X, a keep that is no clip of its widget", badKeep, Outcome.CASE_3_ASK, null, listOf("IGNORED: ${G.Q_KEEP_C2}"))

        val void = run("void, two CONFLICT at C2", Script(c2 = c2All("TT", "XT", "TX", "XX"), ident = G.PAIR_A))
        expect("void, two CONFLICT at C2", void, Outcome.VOID, null, listOf("THE PAGE IS VOID", "NOISE SIGN", "PAGE VOID (P1)", "BUILD MAP NOT READ"))
        check(void.lines.none { "INFERRED ruler read" in it }) { "SELFCHECK: a void page printed a ruler read, which is a reading of it" }
        expect(
            "void, no device answer and two CONFLICT", run("void, no device answer and two CONFLICT", Script(device = null, c2 = c2All("TT", "XT", "TX", "XX"))),
            Outcome.VOID, null, listOf("the device question is not answered and 2 of the four non-identical C2 comparisons are CONFLICT"),
        )
        expect("void, no device answer", run("void, no device answer", Script(device = null, c2 = c2All("TT", "XX", "CC", "TT"))), Outcome.VOID, null, listOf("NOT ANSWERED"))
        expect("void, a device that is none of the six", run("void, a device that is none of the six", Script(extra = mapOf(G.Q_DEVICE to JsonValue.Str("tablet")))), Outcome.VOID, null, listOf("IGNORED: ${G.Q_DEVICE}"))
        expect("void, no doc at all", decode(key, Saved(null), "void, no doc at all").also { reports["void, no doc at all"] = it }, Outcome.VOID, null, listOf("NOT FOUND OR UNREADABLE"))
        expect("one CONFLICT does not void the page", run("one CONFLICT does not void the page", Script(c2 = c2All("TT", "XT", "CC", "CC"))), Outcome.CASE_1, null, listOf("P1 READABLE"))
        expect("one CONFLICT on the plain comparison", run("one CONFLICT on the plain comparison", Script(c2 = c2All("XT", "XX", "CC", "CC"))), Outcome.CASE_2, bell, listOf("P2 NOT ANSWERED"))
        expect("an unanswered plain comparison, rest known", run("an unanswered plain comparison, rest known", Script(c2 = c2All("--", "XX", "CC", "TT"))), Outcome.CASE_2, bell, listOf("P2 NOT ANSWERED"))
        val incomplete = run("incomplete, a candidate order missing", Script(c2 = c2All("TT", "X-", "CC", "TT")))
        expect("incomplete, a candidate order missing", incomplete, Outcome.INCOMPLETE, null, listOf("NO CASE LINE APPLIES YET", "q_pair_"))
        expect("incomplete, P1 undecided", run("incomplete, P1 undecided", Script(c2 = c2All("--", "XX", "CC", "--"))), Outcome.INCOMPLETE, null, listOf("P1 UNDECIDED"))
        val noRun = key.widgets(G.G_C2, Comparison.MORE_BELL)[0].id
        expect("the play gate, one widget with no run", run("the play gate, one widget with no run", Script(c2 = c2All("TT", "XX", "CC", "TT"), noRun = setOf(noRun))), Outcome.INCOMPLETE, null, listOf("IGNORED: $noRun"))
        expect("the play gate, no plays at all", run("the play gate, no plays at all", Script(c2 = c2All("TT", "XX", "CC", "TT"), f2 = f2All("XX", "CC", "TT"), plays = false)), Outcome.INCOMPLETE, null, listOf("P1 UNDECIDED"))
        val noisy = run("the identical pair answered A", Script(c2 = c2All("TT", "XX", "CC", "TT"), ident = G.PAIR_A))
        expect("the identical pair answered A", noisy, Outcome.CASE_2, bell, listOf("NOISE SIGN"))
        check(one.lines.none { "NOISE SIGN" in it } && one.lines.any { "the clean answer" in it }) { "SELFCHECK: the clean identical answer was printed as a noise sign" }
        expect("F#2 disagrees", run("F#2 disagrees", Script(c2 = c2All("TT", "XX", "CC", "TT"), f2 = f2All("TT", "CC", "TT"))), Outcome.CASE_2, bell, listOf("F#2 disagrees: read with care"))
        expect("the engine's lift not heard", run("the engine's lift not heard", Script(c2 = c2All("XX", "XX", "CC", "TT"))), Outcome.CASE_2, bell, listOf("P2 NO", "not heard on this device"))
        expect("extra bookkeeping is tolerated", run("extra bookkeeping is tolerated", Script(c2 = c2All("TT", "XX", "CC", "TT"), extra = mapOf("pairsStarted" to JsonValue.Obj(emptyMap())))), Outcome.CASE_2, bell)
        check(reports.getValue("extra bookkeeping is tolerated").lines.none { it.startsWith("$P IGNORED") }) { "SELFCHECK: an unknown field was reported as ignored" }
        val wrongType = key.widgets(G.G_C2, Comparison.MORE_BELL)[1].id
        expect("a field of the wrong type is reported", run("a field of the wrong type is reported", Script(c2 = c2All("TT", "XX", "CC", "TT"), extra = mapOf(wrongType to JsonValue.Num(3.0)))), Outcome.INCOMPLETE, null, listOf("IGNORED: $wrongType: expected text"))
        expect("a phone speaker is noted", run("a phone speaker is noted", Script(device = G.DEVICE_PHONE, c2 = c2All("TT", "XX", "CC", "TT"))), Outcome.CASE_2, bell, listOf("DEVICE NOTE"))

        // every case line is reached, and the RESULT lines (the source taken off) are all different
        val bodies = listOf(one, tie, build, ask, void, incomplete).map { it.body }
        check(bodies.toSet().size == bodies.size) { "SELFCHECK: two endings print the same RESULT line: $bodies" }
        check(setOf(one.outcome, tie.outcome, build.outcome, ask.outcome) == setOf(Outcome.CASE_2, Outcome.CASE_1, Outcome.CASE_3_BUILD, Outcome.CASE_3_ASK)) { "SELFCHECK: a case line was not reached" }
        println("$P SELFCHECK case lines 1, 2 and 3 (build and ask), the void page and the incomplete page each print a different RESULT line: OK")
        for ((name, r) in reports) {
            check(r.lines.none { it.startsWith("$P RESULT:") } && r.result.startsWith("$P RESULT: ${shortName(name)}: ")) { "SELFCHECK $name: the report does not have exactly one RESULT line, last" }
        }
        guardWords(reports.values.flatMap { it.lines + it.result })
        println("$P SELFCHECK ${reports.size} invented reports each end in ONE RESULT line, and none says a sound word outside the owner's quoted words: OK")
        mustThrow("the words law on a line that describes a sound") { guardWords(listOf("$P the sound is full")) }
        mustHold("the words law on the owner's own quoted word") { guardWords(listOf("$P owner answered \"A SOUNDS FULLER\" (HEARD)")) }
        mustThrow("an ending that is not the one wanted") { expect("control", one, Outcome.CASE_1) }
        mustThrow("a build that is not the one wanted") { expect("control", build, Outcome.CASE_3_BUILD, bell) }
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

    // ---- main ------------------------------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        check(args.isNotEmpty()) { "usage: ArcoBellGateDecoder <key file> [<verdict folder or file>...] (gradle: :synth:decodeArcoBellGate -PgateVerdicts=<path>[,<path>...])" }
        val keyFile = File(args[0])
        val key = loadKey(keyFile)
        for (l in rulesLines()) println(l)
        selfCheck(key, File(keyFile.absoluteFile.parentFile, "arco-bell-gate/manifest.json"))
        if (args.size == 1) println("$P no verdict path given: only the rules and the self-check were run")
        for (path in args.drop(1)) decode(key, loadVerdict(File(path)), path).print()
    }
}
