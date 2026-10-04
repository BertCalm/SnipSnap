package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.ArcoBodyGateGenerator.Kind
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Reads what the owner saved on the ARCO R1h BODY gate page and prints the pass rule (P1 to P5), the reading rules and the fix map, as R1f's design wrote them before anyone listened. Run
 * `./gradlew :synth:decodeArcoBodyGate -PgateVerdicts=<dir or file>[,<dir or file>...]` (it reads `testkit/arco-body-gate-key.json` and prints one report per verdict path), or
 * `ArcoBodyGateDecoder <key file> <verdict path>...`. Written and run on made-up verdict files before the page is published. The decoder prints facts and the rules' outcomes; it never says how anything sounds, and
 * nothing it prints is a listening claim: it is the owner's taps, mapped back through the private key to what each clip was.
 *
 * The saved docs (the page writes them through R1e's saving logic, under the db paths `verdicts/arco_r1h_<key>`; all values are strings or numbers, so the page's own local copy and store merge keep working). The names
 * and values are [ArcoBodyGateGenerator]'s constants, which the manifest also carries, so the page and this reader agree:
 *  - `verdicts/arco_r1h_CELLO` and `verdicts/arco_r1h_ERHU`, one per voice: `{reactions: {<clip id>: "same"|"fuller"|"duller"|"toomuch"|"diff"|"cant"}, plays: {<clip id>: <play count>}, note: "<text>" (optional), savedAt: "<time>"}`.
 *    `reactions` has the chip answers, never one for a clip whose id ends in `_plain`; `plays` counts every play of every clip of the voice (the reference too), kept apart from the answers, and, under the id of a pair
 *    widget (`q_pair_1` and so on, in the doc of the voice the widget belongs to), how many times that widget's own PLAY A, THEN B ran to its end.
 *  - `verdicts/arco_r1h_overall`: `{q_device: "headphones"|"phone"|"other", q_pair_1 .. q_pair_4: "a"|"b"|"cant", q_order_c3: "<five clip ids joined by commas, least full first, fullest last>",
 *    q_same_c3: "none" or "<clip id>+<clip id>[+<clip id>]|<clip id>+<clip id>" (tie groups: ids joined by +, groups joined by |; empty means not answered), q_keep_CELLO and q_keep_ERHU: "<clip id>"|"none"|"cant",
 *    q_note: "<text>", savedAt: "<time>"}`. A field that is empty or absent is unanswered. `a` and `b` are the first and second clip of the widget, as the manifest and the key list them.
 * The verdict path is a folder holding `arco_r1h_CELLO.json`, `arco_r1h_ERHU.json` and `arco_r1h_overall.json` (also `CELLO.json` and so on; each file is the doc, or `{"data": <the doc>}` as a store export may wrap it),
 * or one file with `{"CELLO": <doc>, "ERHU": <doc>, "overall": <doc>}` (the keys may also be `verdicts/arco_r1h_CELLO` and so on). A missing doc is read as nothing answered, and said so. A file that is empty or cut off
 * (not valid JSON) is read the same way and listed as UNREADABLE with its name; it never stops the report.
 *
 * What counts as an answer. A chip counts only if its clip has at least one recorded play (the page does not allow otherwise; a doc that has one anyway is a defect and the chip is IGNORED and listed). A pair
 * counts only if both its clips have a play and its own PLAY A, THEN B has run to its end at least once; the ordering only if all five clips have a play. A chip on THE PLAIN ONE, on an unknown clip or with an unknown value is
 * ignored too. An unanswered item never passes a rule: it makes the rule MISSING, and the report lists, in plain words, which answers to ask the owner for again (a missing answer is not a fail).
 *
 * The rules, as written in R1f's LISTENING GATE (a rule is PASS, FAIL, MISSING (some answer it needs is absent and nothing present fails it) or, for P1 and P3, VOID):
 *  - P1: both hidden repeats (C3 and C5) are SAME and the two identical C2 clips (BODY 0.75 and 1.0 are the same samples there) got the same chip. Otherwise the page is VOID and is re-run. CAN'T SAY on a repeat or on
 *    a C2 clip is not an answer (the page's words: it means the clip could not be listened to properly), so it makes P1 MISSING and the owner is asked again; it is not a VOID.
 *  - P2: BODY 1 is FULLER at C3 and at C5, and at one or more of C2 and A5.
 *  - P3: no DULLER and no TOO MUCH on BODY 0.75 or BODY 1 at C3 or at C5. If P5 shows the page could not see a top loss (the R1D-GRADE control called SAME), every "not duller" answer is void, so a P3 that would pass is VOID.
 *  - P4: at C3 the owner's order has no inversion among 0.5, 0.75 and 1.0 (a clip placed fuller than a higher BODY one, unless the owner said the two sound the same), and BODY 1 is ranked fullest or tied with 0.9 (it
 *    is last, or it is the same as 0.9 and that class holds the last place). A missing answer to the "sound the same" question is read as none. If the owner said THE PLAIN ONE and BODY 1 sound the same, the order
 *    says nothing about a heard ladder, so P4 is not read (MISSING, and the owner is asked again), whatever the rest of the order is.
 *  - P5: the R1D-GRADE control is called DULLER or DIFFERENT, and neither OLD-WAY clip is called FULLER. DIFFERENT passes (as written) but is printed as WEAK, and so is DIFFERENT given to the flat louder-only clips.
 * The reading rules (not pass or fail): where BODY 1 beats the rms-matched louder-only clip in both orders at both C3 and C5, shape was heard beyond energy; at one note, partly; at neither, BODY above the middle is a level step
 * with a tilt (acceptable, since louder is fine, but to be called that, and not argued) when BODY 1 or a louder-only clip was called FULLER, and "nothing was heard at these levels, or the answers do not separate them" when none
 * was (the same line would otherwise read a flat page as a level step); the A-weighted louder-only clip called FULLER like BODY 1 at the same note says only that the owner follows loudness; can't tell is not a pass. Every
 * reading line says NOT TO BE READ when P1 voids the page and says what is missing when an answer it needs is absent. Beside the pair reading the decoder prints the MEASURED ear-weighted gap between the rms-matched louder-only
 * clip and BODY 1 (at C3 the louder-only clip is the louder by the ear, which the question's wording pushes against, so a BODY win there is the weaker evidence) and the chance rate of each outcome for a guesser (INFERRED arithmetic).
 * The fix map, one constant each: TOO MUCH on a BODY clip at C3 or C5, scale both rungs by 5/6; SAME at BODY 0.75 on both default notes, raise H from 3.5 to 4.0; SAME or can't say at C2, more bell and less shelf on the
 * lowest CELLO notes, or R1c's box at the plain's gain there; any DULLER on BODY 1 stops the build. Whatever comes back is reported as it is. A fix-map line that is TRIGGERED is also put on the GATE line, so a
 * P1 to P5 that all hold can never print as a clean PASS beside a fix map that stops the build.
 */
object ArcoBodyGateDecoder {

    private val G = ArcoBodyGateGenerator

    /** What the key says a clip is: its group (c3, c2, c5, a5), voice, letter, kind ([ArcoBodyGateGenerator.Kind.key]) and, for a BODY clip, its BODY, and its rms and ear-weighted rises over the plain (the pair reading's gap line). */
    internal class KeyClip(val id: String, val group: String, val voice: String, val letter: String?, val kind: String, val body: Double?, val rmsRise: Double? = null, val aRise: Double? = null)

    internal class KeyPair(val id: String, val group: String, val a: String, val aIs: String, val b: String, val bIs: String)

    internal class KeyKeep(val id: String, val voice: String, val clips: List<String>)

    /** The key, parsed: every clip by id, the four pair widgets, the ordering question's five clips and the keep questions. */
    internal class Key(val clips: Map<String, KeyClip>, val pairs: List<KeyPair>, val orderIds: List<String>, val keeps: List<KeyKeep>)

    /** The saved docs: one map per voice (CELLO, ERHU) and the overall one; a doc that was not found, or could not be read, is an empty map and is listed. */
    internal class Verdict(
        val voices: Map<String, Map<String, JsonValue>>, val overall: Map<String, JsonValue>, val found: List<String>, val missingDocs: List<String>,
        val unreadable: List<String> = emptyList(),
    )

    internal enum class Outcome { PASS, FAIL, MISSING, VOID }

    internal class Rule(val name: String, val outcome: Outcome, val headline: String, val details: List<String>)

    /** One line of the fix map: what it says to do, whether the answers trigger it, and why. [triggered] null is unknown (answers missing). [stopsBuild] marks the line the spec says stops the build. */
    internal class Fix(val name: String, val triggered: Boolean?, val action: String, val why: String, val stopsBuild: Boolean = false)

    /**
     * What the owner's answers amount to, after the gate on plays: accepted chips by clip id, the play counts (clips, and apart from them the PLAY A, THEN B runs of each pair widget), accepted pair answers by widget id,
     * the order (least full first) and the tie classes, the keep answers, and what was ignored and why.
     */
    internal class Answers(
        val chips: Map<String, String>, val plays: Map<String, Int>, val pairPlays: Map<String, Int>, val device: String?, val pairs: Map<String, String>, val order: List<String>?, val same: List<Set<String>>?,
        val sameAnswered: Boolean, val keep: Map<String, String>, val notes: List<String>, val ignored: List<String>,
    )

    internal class Report(
        val source: String, val answers: Answers, val rules: List<Rule>, val gate: String, val reading: List<String>, val fixes: List<Fix>, val table: List<String>, val head: List<String>,
        val missing: List<String> = emptyList(),
    ) {
        fun rule(name: String): Rule = rules.first { it.name == name }

        fun lines(): List<String> = head + table + rules.flatMap { listOf("ARCO gate decode ${it.name} ${it.outcome}: ${it.headline}") + it.details.map { d -> "ARCO gate decode     $d" } } +
            listOf(
                if (missing.isEmpty()) "ARCO gate decode MISSING ANSWERS the rules need: none"
                else "ARCO gate decode MISSING ANSWERS the rules need (a missing answer is not a fail: ask the owner again for these):",
            ) + missing.map { "ARCO gate decode     $it" } +
            listOf("ARCO gate decode GATE: $gate") + reading.map { "ARCO gate decode READING $it" } + fixes.map { "ARCO gate decode FIX MAP ${it.name}: ${triggeredText(it)}; the fix: ${it.action}; why: ${it.why}" } +
            "ARCO gate decode FIX MAP: whatever comes back is reported as it is; nothing here is argued with."

        private fun triggeredText(f: Fix) = when (f.triggered) { true -> "TRIGGERED"; false -> "not triggered"; null -> "UNKNOWN (answers missing)" }

        fun print() { for (l in lines()) println(l) }
    }

    // ---- the key -------------------------------------------------------------------------------------------

    private fun bodyOf(v: JsonValue?): Double? = (v as? JsonValue.Num)?.value

    internal fun loadKey(file: File): Key {
        check(file.isFile) { "the key ${file.path} is missing: run :synth:generateArcoBodyGate first" }
        val root = Json.parse(file.readText(Charsets.UTF_8)).obj()
        val clips = LinkedHashMap<String, KeyClip>()
        for (g in root.getValue("groups").arr()) {
            val go = g.obj()
            val prefix = go.getValue("prefix").str()
            val voice = go.getValue("voice").str()
            for (c in go.getValue("clips").arr()) {
                val co = c.obj()
                val id = co.getValue("id").str()
                clips[id] = KeyClip(id, prefix, voice, (co["letter"] as? JsonValue.Str)?.value, co.getValue("is").str(), bodyOf(co["body"]), bodyOf(co["rmsRiseDb"]), bodyOf(co["aWeightedRiseDb"]))
            }
        }
        val pairs = root.getValue("pairs").arr().map {
            val o = it.obj()
            KeyPair(o.getValue("id").str(), o.getValue("group").str(), o.getValue("a").str(), o.getValue("aIs").str(), o.getValue("b").str(), o.getValue("bIs").str())
        }
        val order = root.getValue("order").obj()
        val keeps = root.getValue("keep").arr().map {
            val o = it.obj()
            KeyKeep(o.getValue("id").str(), o.getValue("voice").str(), o.getValue("clips").arr().map { c -> c.obj().getValue("id").str() })
        }
        check(clips.size == 24) { "the key lists ${clips.size} clips, not 24" }
        return Key(clips, pairs, order.getValue("clips").arr().map { it.obj().getValue("id").str() }, keeps)
    }

    // ---- the verdict docs ------------------------------------------------------------------------------------

    private fun docOf(v: JsonValue?): Map<String, JsonValue>? {
        val o = (v as? JsonValue.Obj)?.entries ?: return null
        val inner = o["data"]
        return if (inner is JsonValue.Obj && "savedAt" !in o && "reactions" !in o) inner.entries else o
    }

    /** Reads the saved docs under [path]. A file that is empty or cut off is read as nothing answered and listed in [Verdict.unreadable]; it never stops the report. */
    internal fun loadVerdict(path: File): Verdict {
        val keys = listOf("CELLO", "ERHU", ArcoBodyGateGenerator.OVERALL_KEY)
        val docs = HashMap<String, Map<String, JsonValue>>()
        val unreadable = ArrayList<String>()
        fun parse(f: File): JsonValue? = try {
            Json.parse(f.readText(Charsets.UTF_8))
        } catch (e: JsonException) {
            unreadable += "${f.path} (${e.message?.take(80) ?: "not valid JSON"})"
            null
        }
        if (path.isDirectory) {
            for (k in keys) {
                val names = listOf("arco_r1h_$k.json", "$k.json", "verdicts_arco_r1h_$k.json")
                val f = names.map { File(path, it) }.firstOrNull { it.isFile } ?: File(path, "verdicts/arco_r1h_$k.json").takeIf { it.isFile }
                if (f != null) parse(f)?.let { doc -> docOf(doc)?.let { docs[k] = it } }
            }
        } else {
            check(path.isFile) { "the verdict path ${path.path} is neither a folder nor a file" }
            val root = parse(path)
            if (root != null) {
                val o = (root as? JsonValue.Obj)?.entries
                if (o == null) unreadable += "${path.path} (not a JSON object)"
                else for (k in keys) docOf(o[k] ?: o["${ArcoBodyGateGenerator.DOC_PREFIX}$k"])?.let { docs[k] = it }
            }
        }
        return Verdict(
            mapOf("CELLO" to (docs["CELLO"] ?: emptyMap()), "ERHU" to (docs["ERHU"] ?: emptyMap())), docs[ArcoBodyGateGenerator.OVERALL_KEY] ?: emptyMap(),
            keys.filter { it in docs }, keys.filter { it !in docs }, unreadable,
        )
    }

    // ---- what counts as an answer ---------------------------------------------------------------------------

    private fun str(v: JsonValue?): String? = (v as? JsonValue.Str)?.value?.trim()?.takeIf { it.isNotEmpty() }

    internal fun answersOf(key: Key, v: Verdict): Answers {
        val ignored = ArrayList<String>()
        val plays = HashMap<String, Int>()
        val pairPlays = HashMap<String, Int>()
        val pairById = key.pairs.associateBy { it.id }
        for ((voice, doc) in v.voices) {
            val p = (doc["plays"] as? JsonValue.Obj)?.entries ?: continue
            for ((id, n) in p) {
                val c = key.clips[id]
                val pr = pairById[id]
                if (c == null && pr == null) { ignored += "plays of $id: not a clip or a pair widget of the key"; continue }
                val owner = c?.voice ?: key.clips.getValue(pr!!.a).voice
                if (owner != voice) { ignored += "plays of $id are in the $voice doc but it belongs to ${owner}'s"; continue }
                val count = (n as? JsonValue.Num)?.value
                if (count != null && count.isFinite() && count >= 0) {
                    if (c != null) plays[id] = maxOf(plays[id] ?: 0, count.toInt()) else pairPlays[id] = maxOf(pairPlays[id] ?: 0, count.toInt())
                }
            }
        }
        val chipValues = G.CHIPS.map { it.v }.toSet()
        val chips = LinkedHashMap<String, String>()
        for ((voice, doc) in v.voices) {
            val r = (doc["reactions"] as? JsonValue.Obj)?.entries ?: continue
            for ((id, value) in r) {
                val c = key.clips[id]
                val a = str(value)
                when {
                    c == null -> ignored += "chip on $id: not a clip of the key"
                    c.voice != voice -> ignored += "chip on $id is in the $voice doc but the clip is ${c.voice}'s"
                    c.kind == Kind.PLAIN.key -> ignored += "chip on $id: THE PLAIN ONE takes no chip"
                    a == null || a !in chipValues -> ignored += "chip on $id: \"${(value as? JsonValue.Str)?.value ?: value}\" is not one of $chipValues"
                    (plays[id] ?: 0) < 1 -> ignored += "chip on $id (${label(key, id)}): no recorded play, so it does not count"
                    else -> chips[id] = a
                }
            }
        }
        val o = v.overall
        val device = str(o[G.Q_DEVICE])?.takeIf { it in setOf(G.DEVICE_HEADPHONES, G.DEVICE_PHONE, G.DEVICE_OTHER) }
        val pairs = LinkedHashMap<String, String>()
        for (p in key.pairs) {
            val a = str(o[p.id]) ?: continue
            when {
                a !in setOf(G.PAIR_A, G.PAIR_B, G.PAIR_CANT) -> ignored += "${p.id}: \"$a\" is not a, b or cant"
                (plays[p.a] ?: 0) < 1 || (plays[p.b] ?: 0) < 1 -> ignored += "${p.id}: answered without both clips played, so it does not count"
                (pairPlays[p.id] ?: 0) < 1 -> ignored += "${p.id}: answered without its own PLAY A, THEN B run to the end, so it does not count"
                else -> pairs[p.id] = a
            }
        }
        var order: List<String>? = null
        str(o[G.Q_ORDER_C3])?.let { text ->
            val ids = text.split(",").map { it.trim() }
            when {
                ids.toSet() != key.orderIds.toSet() || ids.size != key.orderIds.size -> ignored += "${G.Q_ORDER_C3}: not the five clips once each: $ids"
                key.orderIds.any { (plays[it] ?: 0) < 1 } -> ignored += "${G.Q_ORDER_C3}: answered without all five clips played, so it does not count"
                else -> order = ids
            }
        }
        var same: List<Set<String>>? = null
        val sameText = str(o[G.Q_SAME_C3])
        if (sameText != null) {
            if (sameText == "none") same = emptyList()
            else {
                val groups = sameText.split("|").map { g -> g.split("+").map { it.trim() }.toSet() }
                if (groups.all { g -> g.size >= 2 && g.all { it in key.orderIds } }) same = groups else ignored += "${G.Q_SAME_C3}: not none or groups of two or more of the five clips: \"$sameText\""
            }
        }
        val keep = LinkedHashMap<String, String>()
        for (k in key.keeps) {
            val a = str(o[k.id]) ?: continue
            if (a == G.KEEP_NONE || a == G.KEEP_CANT || a in k.clips) keep[k.id] = a else ignored += "${k.id}: \"$a\" is not one of its clips, none or cant"
        }
        val notes = ArrayList<String>()
        for ((voice, doc) in v.voices) str(doc["note"])?.let { notes += "$voice note: $it" }
        str(o[G.Q_NOTE])?.let { notes += "overall note: $it" }
        return Answers(chips, plays, pairPlays, device, pairs, order, same, sameText != null && same != null, keep, notes, ignored)
    }

    // ---- the rules ---------------------------------------------------------------------------------------------

    private enum class Tri { T, F, U }

    private fun all(vararg t: Tri): Tri = if (t.any { it == Tri.F }) Tri.F else if (t.any { it == Tri.U }) Tri.U else Tri.T

    private fun any(vararg t: Tri): Tri = if (t.any { it == Tri.T }) Tri.T else if (t.any { it == Tri.U }) Tri.U else Tri.F

    private fun neg(t: Tri): Tri = when (t) { Tri.T -> Tri.F; Tri.F -> Tri.T; Tri.U -> Tri.U }

    private fun word(t: Tri) = when (t) { Tri.T -> "holds"; Tri.F -> "fails"; Tri.U -> "unanswered" }

    private fun tri(b: Boolean?): Tri = when (b) { true -> Tri.T; false -> Tri.F; null -> Tri.U }

    private fun outcome(t: Tri) = when (t) { Tri.T -> Outcome.PASS; Tri.F -> Outcome.FAIL; Tri.U -> Outcome.MISSING }

    private fun clipId(key: Key, group: String, kind: Kind): String? =
        key.clips.values.firstOrNull { it.group == group && it.kind == kind.key && (kind.body == null || it.body != null && abs(it.body - kind.body.toDouble()) < 1e-3) }?.id

    private fun idOrNull(key: Key, group: String, kind: Kind): String = clipId(key, group, kind) ?: error("the key has no ${kind.key} clip in group $group")

    /** BODY as the key says it: 0.5, 0.6, 0.75, 0.9, 1.0. */
    private fun bodyText(body: Double?): String = if (body == null) "?" else "%.2f".format(Locale.ROOT, body).let { if (it.endsWith("0")) it.dropLast(1) else it }

    /** What a clip is, in the key's words: BODY 0.75, REPEAT, OLD-WAY and so on. */
    private fun what(c: KeyClip): String = if (c.kind == Kind.B100.key) "BODY ${bodyText(c.body)}" else c.kind

    private fun short(key: Key, id: String): String = what(key.clips.getValue(id))

    private fun label(key: Key, id: String): String {
        val c = key.clips.getValue(id)
        return "CLIP ${c.letter ?: "-"} = ${what(c)} at ${c.group.uppercase()}"
    }

    private fun chipText(v: String?) = if (v == null) "no answer" else G.CHIPS.firstOrNull { it.v == v }?.text ?: v

    private fun chipLine(key: Key, a: Answers, id: String) = "${label(key, id)} (${id}): ${chipText(a.chips[id])}, plays ${a.plays[id] ?: 0}"

    private fun signed(x: Double?): String = if (x == null) "unknown" else "%+.2f dB".format(Locale.ROOT, x)

    internal fun decode(key: Key, v: Verdict, source: String): Report {
        val a = answersOf(key, v)
        fun id(group: String, kind: Kind) = idOrNull(key, group, kind)
        fun chip(group: String, kind: Kind) = a.chips[id(group, kind)]
        fun isChip(group: String, kind: Kind, vararg values: String): Tri = chip(group, kind)?.let { tri(it in values) } ?: Tri.U
        fun who(group: String, kind: Kind) = label(key, id(group, kind))
        val c3 = G.G_C3
        val c2 = G.G_C2
        val c5 = G.G_C5
        val a5 = G.G_A5
        val plainIds = key.clips.values.filter { it.kind == Kind.PLAIN.key }.map { it.id }
        val chipIds = key.clips.keys - plainIds.toSet()
        val missing = ArrayList<String>()

        // P1. CAN'T SAY is not an answer on a repeat or on a C2 clip (the page's words: the clip could not be listened to properly): it asks again, it does not void the page.
        fun repeatTri(g: String): Tri = when (chip(g, Kind.REPEAT)) { null, G.CHIP_CANT -> Tri.U; G.CHIP_SAME -> Tri.T; else -> Tri.F }
        val rep3 = repeatTri(c3)
        val rep5 = repeatTri(c5)
        val c2a = chip(c2, Kind.B075)
        val c2b = chip(c2, Kind.B100)
        val c2same = if (c2a == null || c2b == null || c2a == G.CHIP_CANT || c2b == G.CHIP_CANT) Tri.U else tri(c2a == c2b)
        val p1t = all(rep3, rep5, c2same)
        fun cantNote(c: String?) = if (c == G.CHIP_CANT) " (CAN'T SAY is not an answer here: ask again)" else ""
        for ((g, t) in listOf(c3 to rep3, c5 to rep5)) if (t == Tri.U) missing += "P1: ${who(g, Kind.REPEAT)}: ${if (chip(g, Kind.REPEAT) == null) "no answer" else "CAN'T SAY"} (the hidden repeat needs a real answer)"
        if (c2same == Tri.U) missing += "P1: the two identical C2 clips (${who(c2, Kind.B075)} and ${who(c2, Kind.B100)}): ${chipText(c2a)} and ${chipText(c2b)} (each needs a real answer, not CAN'T SAY)"
        val p1 = Rule(
            "P1", if (p1t == Tri.F) Outcome.VOID else outcome(p1t),
            "both hidden repeats SAME, and the two identical C2 clips got the same chip" + if (p1t == Tri.F) ": THE PAGE IS VOID, re-run it" else "",
            listOf(
                "C3 repeat: ${chipLine(key, a, id(c3, Kind.REPEAT))} -> ${word(rep3)}${cantNote(chip(c3, Kind.REPEAT))}",
                "C5 repeat: ${chipLine(key, a, id(c5, Kind.REPEAT))} -> ${word(rep5)}${cantNote(chip(c5, Kind.REPEAT))}",
                "C2 BODY 0.75 and BODY 1.0 (the same samples): ${chipText(c2a)} and ${chipText(c2b)} -> ${word(c2same)}${if (c2a == G.CHIP_CANT || c2b == G.CHIP_CANT) " (CAN'T SAY is not an answer here: ask again)" else ""}",
            ),
        )

        // P2
        val f3 = isChip(c3, Kind.B100, G.CHIP_FULLER)
        val f5 = isChip(c5, Kind.B100, G.CHIP_FULLER)
        val f2 = isChip(c2, Kind.B100, G.CHIP_FULLER)
        val fa5 = isChip(a5, Kind.B100, G.CHIP_FULLER)
        val p2t = all(f3, f5, any(f2, fa5))
        if (chip(c3, Kind.B100) == null) missing += "P2: ${who(c3, Kind.B100)}: no answer"
        if (chip(c5, Kind.B100) == null) missing += "P2: ${who(c5, Kind.B100)}: no answer"
        if (any(f2, fa5) != Tri.T) {
            val open = listOfNotNull(if (chip(c2, Kind.B100) == null) who(c2, Kind.B100) else null, if (chip(a5, Kind.B100) == null) who(a5, Kind.B100) else null)
            if (open.isNotEmpty()) missing += "P2: ${open.joinToString(" and ")}: no answer (BODY 1 must be FULLER at one or more of C2 and A5)"
        }
        fun tooMuchNote(g: String) = if (chip(g, Kind.B100) == G.CHIP_TOO_MUCH) " (TOO MUCH is not FULLER, so P2 reads it as not fuller; it can mean more weight that was too much, which this answer cannot tell from not heard)" else ""
        val p2 = Rule(
            "P2", outcome(p2t), "BODY 1 is FULLER at C3 and at C5, and at one or more of C2 and A5",
            listOf(
                "C3 BODY 1: ${chipText(chip(c3, Kind.B100))} -> ${word(f3)}${tooMuchNote(c3)}", "C5 BODY 1: ${chipText(chip(c5, Kind.B100))} -> ${word(f5)}${tooMuchNote(c5)}",
                "C2 BODY 1: ${chipText(chip(c2, Kind.B100))} -> ${word(f2)}; A5 BODY 1: ${chipText(chip(a5, Kind.B100))} -> ${word(fa5)}",
            ),
        )

        // P5 (before P3, which P5 can void)
        val dullT = isChip(c3, Kind.DULL, G.CHIP_DULLER, G.CHIP_DIFF)
        val dullChip = chip(c3, Kind.DULL)
        val ow3 = chip(c3, Kind.OLD_WAY)
        val ow5 = chip(c5, Kind.OLD_WAY)
        val owT = all(ow3?.let { tri(it != G.CHIP_FULLER) } ?: Tri.U, ow5?.let { tri(it != G.CHIP_FULLER) } ?: Tri.U)
        val p5t = all(dullT, owT)
        if (dullChip == null || dullChip == G.CHIP_CANT) missing += "P5: ${who(c3, Kind.DULL)}: ${if (dullChip == null) "no answer" else "CAN'T SAY"} (the top-loss control needs a real answer)"
        if (ow3 == null) missing += "P5: ${who(c3, Kind.OLD_WAY)}: no answer"
        if (ow5 == null) missing += "P5: ${who(c5, Kind.OLD_WAY)}: no answer"
        val flatChips = listOf(c3 to Kind.LOUD_RMS, c3 to Kind.LOUD_A, c5 to Kind.LOUD_RMS, c5 to Kind.LOUD_A)
        val flatDiff = flatChips.filter { (g, k) -> chip(g, k) == G.CHIP_DIFF }
        val weak = p5t == Tri.T && (dullChip == G.CHIP_DIFF || flatDiff.isNotEmpty())
        val p5notes = ArrayList<String>()
        p5notes += "the R1D-GRADE control (C3): ${chipText(dullChip)} -> ${word(dullT)}" + when (dullChip) {
            G.CHIP_SAME -> ": SAME, so the page cannot see a top loss and every 'not duller' answer is void"
            G.CHIP_CANT -> ": can't say, which is not a reading of a top loss"
            else -> ""
        }
        p5notes += "OLD-WAY at C3: ${chipText(ow3)}; at C5: ${chipText(ow5)} -> ${word(owT)}" + if (ow3 == G.CHIP_FULLER || ow5 == G.CHIP_FULLER) ": called FULLER, so the level step was never the problem and this reading is wrong" else ""
        if (weak) {
            p5notes += "WEAK: " + listOfNotNull(
                if (dullChip == G.CHIP_DIFF) "the control was called DIFFERENT, not DULLER (as written this passes, but it shows only that a change was heard, not that a top loss was)" else null,
                if (flatDiff.isNotEmpty()) "DIFFERENT was also given to the flat louder-only clips (${flatDiff.joinToString(", ") { (g, k) -> "${g.uppercase()} ${k.key}" }}), so DIFFERENT may mean any change at all" else null,
            ).joinToString("; ")
        }
        val p5 = Rule("P5", outcome(p5t), "the R1D-GRADE control is called DULLER or DIFFERENT, and neither OLD-WAY clip is called FULLER${if (weak) " [WEAK]" else ""}", p5notes)
        val blind = dullChip == G.CHIP_SAME

        // P3
        val p3clips = listOf(c3 to Kind.B075, c3 to Kind.B100, c5 to Kind.B075, c5 to Kind.B100)
        val p3each = p3clips.map { (g, k) -> neg(isChip(g, k, G.CHIP_DULLER, G.CHIP_TOO_MUCH)) }
        val p3t = all(*p3each.toTypedArray())
        val p3out = if (p3t == Tri.T && blind) Outcome.VOID else outcome(p3t)
        for ((g, k) in p3clips) if (chip(g, k) == null) missing += "P3: ${who(g, k)}: no answer"
        val p3 = Rule(
            "P3", p3out, "no DULLER and no TOO MUCH on BODY 0.75 or BODY 1 at C3 or C5" + if (p3out == Outcome.VOID) ": VOID, because the page could not see a top loss (P5)" else "",
            p3clips.map { (g, k) -> "${g.uppercase()} ${short(key, id(g, k))}: ${chipText(chip(g, k))}" } + "NOTE: only C3 has a top-loss control (R1D-GRADE), so 'not DULLER' at C5 rests on the C3 control",
        )

        // P4
        val p4 = p4(key, a, missing)

        // the pair widgets feed the reading rules, not P1 to P5: a pair with no counted answer is listed too
        for (pr in key.pairs) if (pr.id !in a.pairs) missing += "READING: ${pr.id} (${pr.group.uppercase()}, ${pr.aIs} then ${pr.bIs}): no counted answer (it needs an answer given after its own PLAY A, THEN B ran to the end)"
        val readingNeeds = if (missing.any { it.startsWith("READING") }) " The shape-versus-level reading needs pair answers that are missing (see above), so it is not read." else ""

        val rules = listOf(p1, p2, p3, p4, p5)
        val fixes = fixMap(key, a)
        val stops = fixes.filter { it.triggered == true && it.stopsBuild }
        val others = fixes.filter { it.triggered == true && !it.stopsBuild }
        val unknownFix = fixes.filter { it.triggered == null }
        fun fixText(f: Fix) = "${f.name} (${f.why})"
        val gate = when {
            p1.outcome == Outcome.VOID -> "PAGE VOID: a hidden repeat was not SAME or the two identical C2 clips got different chips. Re-run the page. P2 to P5 are printed for the record and are not to be read."
            rules.all { it.outcome == Outcome.PASS } -> when {
                stops.isNotEmpty() -> "NOT A PASS: P1 to P5 all hold, BUT THE FIX MAP STOPS THE BUILD: ${stops.joinToString("; ") { fixText(it) }}." +
                    (if (others.isNotEmpty()) " The fix map is also triggered for: ${others.joinToString("; ") { fixText(it) }}." else "") + " Report both, as they come." + readingNeeds
                others.isNotEmpty() -> "PASS: P1 to P5 all hold, BUT the fix map is triggered: ${others.joinToString("; ") { fixText(it) }}. Read the rules as written and report both." + readingNeeds
                else -> "PASS: P1 to P5 all hold. Read them as written, with the reading rules and the fix map below." +
                    (if (unknownFix.isNotEmpty()) " Fix map lines not known yet (answers missing): ${unknownFix.joinToString(", ") { it.name }}." else "") + readingNeeds
            }
            else -> "NOT PASSED: " + rules.filter { it.outcome != Outcome.PASS }.joinToString("; ") { "${it.name} ${it.outcome}" } +
                (if (rules.any { it.outcome == Outcome.MISSING }) ". Some answers are missing or cannot be read, so this is incomplete and not a reading: it is not a fail. Ask the owner again for the answers listed above" else ". Report it as it is; the fix map below says which constant each failure points at") +
                (if (stops.isNotEmpty()) ". THE FIX MAP ALSO STOPS THE BUILD: ${stops.joinToString("; ") { fixText(it) }}" else "") +
                (if (others.isNotEmpty()) ". The fix map is also triggered for: ${others.joinToString("; ") { fixText(it) }}" else "") + "." + readingNeeds
        }

        val reading = readingRules(key, a, p1.outcome == Outcome.VOID)

        val head = ArrayList<String>()
        head += "ARCO gate decode ===== $source ====="
        head += "ARCO gate decode docs found: ${v.found.joinToString(", ").ifEmpty { "none" }}${if (v.missingDocs.isEmpty()) "" else "; NOT FOUND OR UNREADABLE (read as nothing answered): ${v.missingDocs.joinToString(", ")}"}"
        for (u in v.unreadable) head += "ARCO gate decode UNREADABLE: $u: read as nothing answered"
        head += "ARCO gate decode device: ${a.device ?: "NOT ANSWERED (a required question)"}" + if (a.device == G.DEVICE_PHONE) " (INFERRED: a phone speaker plays little under about 300 Hz, so these answers are about the middle and the loudness, not the low end)" else ""
        val pairsLeft = key.pairs.count { it.id !in a.pairs }
        head += "ARCO gate decode answered: ${a.chips.size} of ${chipIds.size} chips, ${a.pairs.size} of ${key.pairs.size} pair widgets, ordering ${if (a.order != null) "yes" else "no"}, keep ${a.keep.size} of ${key.keeps.size}; " +
            "required items unanswered: ${pairsLeft + if (a.device == null) 1 else 0} (the device question and the four pairs)"
        for (i in a.ignored) head += "ARCO gate decode IGNORED: $i"
        for (n in a.notes) head += "ARCO gate decode ${n.replace("\n", " ")}"
        val table = ArrayList<String>()
        table += "ARCO gate decode CLIPS (the owner's chip per clip, mapped through the key):"
        for (c in key.clips.values) {
            table += "ARCO gate decode   ${c.group.uppercase()} ${c.id.padEnd(9)} ${(c.letter ?: "-")} ${what(c).padEnd(11)} chip ${chipText(a.chips[c.id]).padEnd(18)} plays ${a.plays[c.id] ?: 0}"
        }
        return Report(source, a, rules, gate, reading, fixes, table, head, missing)
    }

    private fun p4(key: Key, a: Answers, missing: MutableList<String>): Rule {
        val c3 = G.G_C3
        val plain = idOrNull(key, c3, Kind.PLAIN)
        val b075 = idOrNull(key, c3, Kind.B075)
        val b090 = idOrNull(key, c3, Kind.B090)
        val b100 = idOrNull(key, c3, Kind.B100)
        val head = "at C3 the order has no inversion among 0.5, 0.75 and 1.0, and BODY 1 is fullest or tied with 0.9"
        val order = a.order ?: run {
            missing += "P4: the C3 ordering: no answer (it needs all five clips played and the order saved)"
            return Rule("P4", Outcome.MISSING, head, listOf("no ordering answer (needs all five clips played and the order saved)"))
        }
        val ties = a.same ?: emptyList()
        fun same(x: String, y: String) = x == y || ties.any { x in it && y in it }
        val inversions = listOf(plain to b075, plain to b100, b075 to b100).filter { (lo, hi) -> order.indexOf(lo) > order.indexOf(hi) && !same(lo, hi) }
        val last = order.last()
        val fullest = last == b100 || (same(b100, b090) && same(last, b100))
        val vacuous = same(plain, b100)
        val ok = inversions.isEmpty() && fullest
        val details = ArrayList<String>()
        details += "the owner's order, least full to fullest: ${order.joinToString(" < ") { short(key, it) + " (" + (key.clips.getValue(it).letter ?: "plain") + ")" }}"
        details += "the clips said to sound the same: ${if (!a.sameAnswered) "no answer, read as none" else if (ties.isEmpty()) "none" else ties.joinToString("; ") { g -> g.joinToString(" = ") { short(key, it) } }}"
        details += "inversions among 0.5, 0.75 and 1.0: ${if (inversions.isEmpty()) "none" else inversions.joinToString("; ") { (lo, hi) -> "${short(key, lo)} placed fuller than ${short(key, hi)}" }}"
        details += "BODY 1 fullest or tied with 0.9: ${if (fullest) "yes" else "no (the fullest place is ${short(key, last)})"}"
        if (vacuous) {
            details += "NOT READ: the owner said THE PLAIN ONE and BODY 1 sound the same, so no step between 0.5 and 1.0 was heard and the order says nothing about a heard ladder (a pass here would be empty)"
            missing += "P4: the C3 ordering says THE PLAIN ONE and BODY 1 sound the same, so it cannot show a heard ladder: ask again if the question is to be read"
            return Rule("P4", Outcome.MISSING, "$head: NOT READ (the plain and BODY 1 were called the same)", details)
        }
        return Rule("P4", if (ok) Outcome.PASS else Outcome.FAIL, head, details)
    }

    /** Who won a note's two pair widgets: BODY, LOUD (the rms-matched louder-only clip), SPLIT (the two orders disagree), CANT (a can't tell) or MISSING. */
    private fun pairResult(key: Key, a: Answers, group: String): String {
        val mine = key.pairs.filter { it.group == group }
        val wins = mine.map { p ->
            when (a.pairs[p.id]) {
                G.PAIR_A -> p.aIs
                G.PAIR_B -> p.bIs
                G.PAIR_CANT -> "CANT"
                else -> null
            }
        }
        return when {
            wins.any { it == null } -> "MISSING"
            wins.any { it == "CANT" } -> "CANT"
            wins.all { it == Kind.B100.key } -> "BODY"
            wins.all { it == Kind.LOUD_RMS.key } -> "LOUD"
            else -> "SPLIT"
        }
    }

    /** At [group], how much louder by the ear's weighting (A-weighted rise over the plain, MEASURED, from the key) the rms-matched louder-only clip is than BODY 1: positive means the louder-only clip is the louder by ear. */
    private fun earGap(key: Key, group: String): Double? {
        val loud = clipId(key, group, Kind.LOUD_RMS)?.let { key.clips[it] }?.aRise
        val top = clipId(key, group, Kind.B100)?.let { key.clips[it] }?.aRise
        return if (loud != null && top != null) loud - top else null
    }

    private fun readingRules(key: Key, a: Answers, void: Boolean): List<String> {
        val out = ArrayList<String>()
        val pre = if (void) "NOT TO BE READ (P1 says the page is void): " else ""
        val c3 = G.G_C3
        val c5 = G.G_C5
        val r3 = pairResult(key, a, c3)
        val r5 = pairResult(key, a, c5)
        fun words(r: String) = when (r) {
            "BODY" -> "BODY 1 beat the louder-only clip in both orders"
            "LOUD" -> "the louder-only clip beat BODY 1 in both orders"
            "SPLIT" -> "the two orders disagree (an answer that follows the order, not the clip)"
            "CANT" -> "can't tell (not a pass)"
            else -> "unanswered"
        }
        out += "${pre}pairs: C3: ${words(r3)}; C5: ${words(r5)}"
        val gap3 = earGap(key, c3)
        val gap5 = earGap(key, c5)
        out += "${pre}ear-weighted gap, the rms-matched louder-only clip minus BODY 1 (MEASURED, from the key): C3 ${signed(gap3)}, C5 ${signed(gap5)}. The question asks for 'not just louder', so where this gap is large a BODY win is the " +
            "weaker evidence (an owner who steers away from the louder clip picks BODY 1 whatever the shape); a win for the louder-only clip is not weakened by it."
        val beatNotes = listOf(c3 to r3, c5 to r5).filter { it.second == "BODY" }.map { it.first }
        val missingPair = listOf(r3, r5).any { it == "MISSING" }
        val bodyChips = listOf(c3, c5).map { a.chips[idOrNull(key, it, Kind.B100)] }
        val flatKinds = listOf(c3 to Kind.LOUD_RMS, c3 to Kind.LOUD_A, c5 to Kind.LOUD_RMS, c5 to Kind.LOUD_A)
        val flatChips = flatKinds.map { (g, k) -> a.chips[idOrNull(key, g, k)] }
        val seen = bodyChips + flatChips
        val fullerSeen = seen.any { it == G.CHIP_FULLER }
        val allAnswered = seen.all { it != null }
        val quietOnly = seen.all { it == G.CHIP_SAME || it == G.CHIP_CANT }
        val louderWon = r3 == "LOUD" || r5 == "LOUD"
        val chance = "Chance, for a guesser who never says CAN'T TELL (INFERRED arithmetic, not a test): BODY in both orders at a given note 1 time in 4, at one or more of the two notes 7 times in 16 (44 percent), at both 1 in 16; " +
            "if CAN'T TELL is one of three equal answers: 1 in 9, 21 percent, 1 in 81."
        when {
            beatNotes.size == 2 -> {
                out += "${pre}BODY 1 beat the louder-only clip (both orders) at both C3 and C5: shape was heard beyond energy."
                out += "${pre}$chance"
            }
            beatNotes.size == 1 -> {
                val n = beatNotes[0]
                val gapHere = if (n == c3) gap3 else gap5
                out += "${pre}BODY 1 beat the louder-only clip (both orders) at one of C3 and C5 (${n.uppercase()}): shape was heard beyond energy at one note, partly." +
                    if (gapHere != null && gapHere >= 1.0) " At ${n.uppercase()} the louder-only clip is ${signed(gapHere)} louder by the ear, so this win is the weaker kind." else ""
                out += "${pre}$chance"
            }
            missingPair -> out += "${pre}BODY 1 did not beat the louder-only clip at the notes answered, and some pair answers are missing: not read."
            fullerSeen -> out += "${pre}BODY 1 did not beat the louder-only clip (both orders) at either C3 or C5: BODY above the middle is a level step with a tilt. With 'louder is fine' that is acceptable, but call it that, and do not argue it." +
                if (!louderWon) " (Here the louder-only clip did not win either: the pair answers were can't tell or the two orders disagreed, so say that too.)" else ""
            !allAnswered -> out += "${pre}BODY 1 did not beat the louder-only clip, and the chips that say whether any step was heard (BODY 1 and the four louder-only clips, at C3 and C5) are not all answered: not read."
            quietOnly -> out += "${pre}Nothing was heard at these levels, or the answers do not separate them: BODY 1 and every louder-only clip at C3 and C5 were called SAME or CAN'T SAY, and the pair answers did not separate the clips. " +
                "That is not a level step with a tilt. The fix map's SAME lines then point at the listener, the device or the page, not at H."
            else -> out += "${pre}BODY 1 did not beat the louder-only clip, and neither BODY 1 nor a louder-only clip was called FULLER, but some were called something else (see the chips): that is not a 'level step with a tilt' reading; read the chips."
        }
        val loudA = listOf(c3, c5).mapNotNull { g -> a.chips[idOrNull(key, g, Kind.LOUD_A)]?.let { g.uppercase() to it } }
        out += "louder-only clips by chip: A-weighted ${loudA.joinToString(", ") { "${it.first} ${chipText(it.second)}" }.ifEmpty { "no answers" }}; rms-matched C3 ${chipText(a.chips[idOrNull(key, c3, Kind.LOUD_RMS)])}, C5 ${chipText(a.chips[idOrNull(key, c5, Kind.LOUD_RMS)])}"
        val loudAFuller = listOf(c3, c5).filter { a.chips[idOrNull(key, it, Kind.LOUD_A)] == G.CHIP_FULLER }
        if (loudAFuller.isNotEmpty()) {
            out += "the A-weighted louder-only clip was called FULLER at ${loudAFuller.joinToString(" and ") { it.uppercase() }} (" +
                loudAFuller.joinToString("; ") { g -> val b = a.chips[idOrNull(key, g, Kind.B100)]; if (b == G.CHIP_FULLER) "like BODY 1 at ${g.uppercase()}" else "BODY 1 at ${g.uppercase()} was ${chipText(b)}" } +
                "): that says only that the owner follows loudness."
        }
        val cant = a.chips.count { it.value == G.CHIP_CANT } + a.pairs.count { it.value == G.PAIR_CANT }
        out += "can't say or can't tell: $cant answers (none of them is a pass)"
        a.keep.forEach { (id, v) -> out += "keep at the top of the knob ($id): ${if (v == G.KEEP_NONE || v == G.KEEP_CANT) v else label(key, v)}" }
        out += "CAVEAT: the ordering and keep questions list the BODY clips of a note together, so the owner could tell which clips are one family; the pair widgets show no letters. Weigh the pair answers with that in mind."
        return out
    }

    private fun fixMap(key: Key, a: Answers): List<Fix> {
        val h = Arco.LIFT_HALF_DB
        val t = Arco.LIFT_TOP_DB
        val read = listOf(G.G_C3 to listOf(Kind.B075, Kind.B100), G.G_C5 to listOf(Kind.B075, Kind.B100))
        val extra = listOf(G.G_C3 to listOf(Kind.B060, Kind.B090))
        fun expand(l: List<Pair<String, List<Kind>>>) = l.flatMap { (g, ks) -> ks.map { g to it } }
        fun clipName(g: String, k: Kind) = "${g.uppercase()} ${if (k.isBody) "BODY ${bodyText(k.body?.toDouble())}" else k.key}"
        val tooMuch = (expand(read) + expand(extra)).filter { (g, k) -> a.chips[idOrNull(key, g, k)] == G.CHIP_TOO_MUCH }
        val tooMuchUnknown = expand(read).any { (g, k) -> a.chips[idOrNull(key, g, k)] == null }
        val tooMuchNotes = tooMuch.map { it.first }.distinct().map { g ->
            val rms = a.chips[idOrNull(key, g, Kind.LOUD_RMS)]
            val aw = a.chips[idOrNull(key, g, Kind.LOUD_A)]
            "at ${g.uppercase()} the louder-only clips were: rms-matched ${chipText(rms)}, A-weighted ${chipText(aw)}" +
                if (rms == G.CHIP_TOO_MUCH || aw == G.CHIP_TOO_MUCH) " (a flat louder-only clip was called TOO MUCH too: that points at level, not at the shape)" else ""
        }
        val f = ArrayList<Fix>()
        f += Fix(
            "TOO MUCH", if (tooMuch.isNotEmpty()) true else if (tooMuchUnknown) null else false,
            "scale both rungs by 5/6 (H ${"%.1f".format(Locale.ROOT, h)} becomes ${"%.1f".format(Locale.ROOT, h * 5f / 6f)}, T ${"%.1f".format(Locale.ROOT, t)} becomes ${"%.1f".format(Locale.ROOT, t * 5f / 6f)})",
            if (tooMuch.isEmpty()) "no BODY clip at C3 or C5 was called TOO MUCH${if (tooMuchUnknown) " (a clip P3 reads is unanswered)" else ""}"
            else "TOO MUCH on ${tooMuch.joinToString(", ") { (g, k) -> clipName(g, k) + if (expand(read).none { it.first == g && it.second == k }) " (an extra clip, which P3 does not read)" else "" }}; ${tooMuchNotes.joinToString("; ")}",
        )
        val s3 = a.chips[idOrNull(key, G.G_C3, Kind.B075)]
        val s5 = a.chips[idOrNull(key, G.G_C5, Kind.B075)]
        f += Fix(
            "SAME at BODY 0.75", if (s3 != null && s3 != G.CHIP_SAME || s5 != null && s5 != G.CHIP_SAME) false else if (s3 == null || s5 == null) null else true,
            "raise H from ${"%.1f".format(Locale.ROOT, h)} to 4.0 (T stays ${"%.1f".format(Locale.ROOT, t)}; 3T > 4H still holds: ${"%.0f".format(Locale.ROOT, 3f * t)} > ${"%.0f".format(Locale.ROOT, 16f)})",
            "BODY 0.75 at C3: ${chipText(s3)}; at C5: ${chipText(s5)} (SAME on both default notes triggers it)",
        )
        val c2 = a.chips[idOrNull(key, G.G_C2, Kind.B100)]
        f += Fix(
            "SAME or not clear at C2", c2?.let { it == G.CHIP_SAME || it == G.CHIP_CANT },
            "try more bell and less shelf on the lowest CELLO notes, or R1c's box at the plain's gain on those notes",
            "C2 BODY 1 (the same samples as BODY 0.75 there): ${chipText(c2)}",
        )
        val duller = listOf(G.G_C3, G.G_C2, G.G_C5, G.G_A5).filter { a.chips[idOrNull(key, it, Kind.B100)] == G.CHIP_DULLER }
        val dullerUnknown = listOf(G.G_C3, G.G_C2, G.G_C5, G.G_A5).any { a.chips[idOrNull(key, it, Kind.B100)] == null }
        f += Fix(
            "DULLER on BODY 1", if (duller.isNotEmpty()) true else if (dullerUnknown) null else false, "STOP THE BUILD (any DULLER on BODY 1 stops it)",
            if (duller.isEmpty()) "no BODY 1 clip was called DULLER${if (dullerUnknown) " (some are unanswered)" else ""}" else "DULLER on BODY 1 at ${duller.joinToString(", ") { it.uppercase() }}",
            stopsBuild = true,
        )
        return f
    }

    // ---- main ----------------------------------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size >= 2) { "usage: ArcoBodyGateDecoder <key file> <verdict folder or file>... (gradle: :synth:decodeArcoBodyGate -PgateVerdicts=<path>[,<path>...])" }
        val key = loadKey(File(args[0]))
        for (path in args.drop(1)) decode(key, loadVerdict(File(path)), path).print()
    }
}
