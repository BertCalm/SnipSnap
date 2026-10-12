package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.synth.ArcoBellCandidates.Point
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/**
 * Renders the ARCO R1j bell gate: the short listening page for two low CELLO notes (C2 and F#2), where the engine's peak ceiling stops the BODY lift short. Output under `testkit/arco-bell-gate/` (gitignored):
 * `manifest.json` (the page builds itself from it), the clips in one folder, `CELLO/<clip id>.wav`, and, in a full run, a copy of the page from the test resources (`audition/arco-bell-gate.html`). The key
 * (`testkit/arco-bell-gate-key.json`, a sibling of the folder and never in it) says which clip is which and carries each clip's numbers; [ArcoBellGateDecoder] reads it with the owner's saved answers.
 * Run `./gradlew :synth:generateArcoBellGate` (add `-PclipsOnly` for the development mode, `--clips-only` on the command line: clips, manifest and key, no `index.html`). The full run refuses a page that is missing or is
 * the one-line placeholder, before it writes anything. Nobody has listened to any of this: every number here is a measurement, never a description of a sound. Printed lines start `ARCO bell`.
 *
 * What the page tests. At the lowest notes the engine's finished-peak cap (0.94) stops the lift before BODY reaches the top of the knob (CELLO C2 at BODY 0.70, F#2 at about BODY 0.90). The cap can be spent in different ways at
 * the SAME peak: shelf and bell equal (what the engine does), more bell and less shelf, bell only, shelf only. The scout measured them (R1j, `probe-table1.txt` and `probe-table1c.txt`) and the two rulers disagree (the
 * A-weighted rise likes the bell, the rms rise likes the shelf), so the owner chose to listen first. The page is built on PAIRED comparisons in both orders, a hidden identical pair as the noise check, and a keep question per note.
 * No engine change is made by this round.
 *
 * The clips (CELLO, every knob at its default, [ArcoLiftRig.macros]). A lifted clip is [Arco.conditionedWithBody] at BODY 1 (the box is frozen above the knee) through the lift pair at its shelf dB and bell dB, in Double (the helper's
 * copy of the engine's lift section, [ArcoBellCandidates]), times [Arco.plainGain], then [Dsp.fadeTail]; every clip is then times ONE page gain per note, the gain that puts THE PLAIN ONE of that note at [AuditionLevel]'s
 * loudness (its 0.03), the same on every clip of the note (R1e and R1h's rule, never [AuditionLevel] per clip). So a version that adds sound is louder than the plain, on purpose, and the page says so.
 *  - THE PLAIN ONE: [Arco.render] at BODY 0.5 (the generator builds it by the engine's finishing function and holds it equal to [Arco.render], sample for sample).
 *  - TODAY: the engine at BODY 1.0, shelf = bell = the note's cap (the generator rebuilds it with the helper at the engine's delivered lift, [Arco.finishedMeasured], and holds it equal to [Arco.render] at BODY 1.0 to 0 samples;
 *    the helper's own equal-split frontier must agree with the engine's cap to 1e-4 dB).
 *  - MORE BELL: the frontier at shelf = 0.5 x bell. BELL ONLY: shelf 0. MORE SHELF: bell 0, the shelf as large as the limit allows. Each frontier is the largest dB (searched up to 12, 20 bisection steps) whose finished peak
 *    at the plain's gain is at most `max(PEAK_CAP, the plain's own peak at its gain)`; the value used is one that was tested. The scout's tables are the prediction: the generator prints the achieved numbers beside them
 *    and throws when a version's rms or A-weighted rise is more than 0.05 dB off the table (a miss means a bug, not a re-aim).
 *  - TODAY-COPY, C2 only: the same samples as TODAY under a second file name (bit-identical files, the same SHA-256): the hidden identical pair.
 * C2 holds six clips (the plain, the four versions, the copy), F#2 five. 11 files in all, 10 distinct.
 *
 * The letters and the shuffles are fixed and hard-coded (no seed). Clip ids are `<group>_plain` and `<group>_<letter>`, group `c2` (C2) or `fs2` (F#2); the letters are neutral, start at C, skip A, B, I and O, and run in the order
 * of the page: the C2 keep widget C D E F, the hidden copy G, the F#2 keep widget H J K L. [SPECS] says which role is behind each letter, [PAIRS] the 15 widgets in the page's order. The generator proves every rule
 * ([guardLetters], [guardKeep], [guardPairs] and the guards they call) and shows each guard throwing on a control that breaks it. The 15 widgets: C2 nine (PLAIN against TODAY, MORE BELL against TODAY, BELL ONLY against TODAY,
 * MORE SHELF against TODAY, each in both orders, and ONE widget TODAY against TODAY-COPY), F#2 six (the last three in both orders). Rules: the two orders of one comparison have at least two other widgets between them; no two
 * adjacent widgets are the same comparison; the identical pair is neither first nor last in the C2 section; TODAY is A in exactly half of each section's non-identical widgets and never on the same side in more than two
 * widgets running; the widgets are neither in the order of the comparisons as listed above nor in order of strength (the candidate's rms rise: by the designed ranking and by the measured numbers); widget ids, clip ids,
 * letters and file names are unique. The two keep widgets list the four versions in a shuffled order: not in order of strength (designed, measured rms, measured A-weighted), not in the listed order, and no role in the same slot
 * of both widgets. At F#2 six widgets cannot be spread any other way than first-half order repeated in the second half (three comparisons, two orders each, a gap of two): that is why the F#2 half repeats C2's first three.
 *
 * THE CONTRACT. W2 (the decoder) and W3 (the page) read this; [ArcoBodyGateGenerator] is the model and the names are its where the idea is the same.
 *
 * The manifest (JSON, UTF-8; every text in it is what the page shows; no number, no `box`, no EQ words, nothing on direction: [guardWords] throws on one):
 *  - `phase` "arco_r1j", `storagePrefix` "arco-bell-gate:" and `docs`: `{prefix: "verdicts/arco_r1j_", overallKey: "overall"}`. There is ONE db doc, at `docs.prefix + docs.overallKey`, and its local copy is under
 *    `arco-bell-gate:arco_r1j:overall`. The R1h voice docs (chips, reactions) are gone: there are no chips.
 *  - `intro`: the honesty text, three short paragraphs: headphones, where to say what else is used, and one steady volume for the whole page; two low cello notes in several versions, nobody has listened, some may sound better than
 *    THE PLAIN ONE and some worse, where to write anything odd; the clips are not level on purpose, choose what sounds fuller whether or not it is louder, and where to tap SAVE VERDICT. How a pair is answered is not in the intro:
 *    it is said on the pair (`pair.lockedText`) and in the opening text of the first section (`body`).
 *  - `groups`: `[{id, voice, note, label, body, clips: [[id, name, desc]]}]`, C2 then F#2, in the page's order. `voice` is "CELLO" and the file of a clip is `<voice>/<id>.wav`. `body` is the text that opens the section.
 *    A clip is `[id, name, desc]` as on R1e's page: THE PLAIN ONE is first, its id ends in `_plain`; then the four versions as `CLIP <letter>` in the keep widget's shuffled order; at C2 the hidden copy last (`CLIP G`).
 *    The page never shows a clip's id or name outside a keep widget; the pair widgets play clips by id and show only A and B. These are the only clips the page plays.
 *  - `questions` (all wording lives here): `required` (the ids the counter counts: the device question, the nine C2 pairs and the C2 keep, 11 items; the F#2 section is OPTIONAL: its six pairs and its keep stay on the page,
 *    are answered if the owner wishes and are not counted, so the page marks an item that is not in `required` as optional; [guardRequired] proves the list), `counter` and `save` (texts with `{n}` for the count: `some`, `one`, `none`; the page
 *    shows the counter and the save button says how many are unanswered; saving with some unanswered is allowed; `counter.unsent` is what the strip says when every required question is answered but the answers have not
 *    been sent, which only SAVE VERDICT does), `device` (`{id, first, required, text, options: [{v, t}]}`: the first question, required, a single choice of six), `pair` (`{gate: "pairRun", text, lockedText, options: [{v, t}]}`,
 *    the one wording shared by the 15 widgets) and `pairs` (`[{id, voice, group, note, a, b}]`, 15 widgets in the page's order, the section of a widget being its `group`: `a` is the clip played first and `b` the second;
 *    a widget shows only A and B, never a clip letter, and unlocks its answer only after its own PLAY A, THEN B has run to its end; A or B may then be played alone), `keep` (`[{id, voice, group, text, clips, extra: [{v, t}]}]`:
 *    the id is [Q_KEEP_C2] or [Q_KEEP_F2], placed at the end of its section; `clips` is THE PLAIN ONE first (the reference) and then the four versions in the shuffled order, each shown as its letter with a play button; `extra`
 *    are the answers besides a letter) and `note` (`{id: "q_note", text}`, the one free note, optional). Every answer goes through the page's saving logic. There is no `voices`, `chips`, `order`, `loops`, `facts` or
 *    `surfaceAfter` any more.
 *
 * The saved doc, `verdicts/arco_r1j_overall`: `{q_device, q_pair_1 .. q_pair_15, q_keep_C2, q_keep_F2, q_note, plays, savedAt}`; every answer is a string, `plays` is an object of numbers, and a field that is empty or absent is
 * unanswered (the F#2 fields, `q_pair_10 .. q_pair_15` and `q_keep_F2`, are optional and may stay empty). The values: [Q_DEVICE] one of [DEVICE_HEADPHONES], [DEVICE_EARBUDS], [DEVICE_PHONE], [DEVICE_LAPTOP], [DEVICE_LOW], [DEVICE_OTHER]; each of [Q_PAIRS]: [PAIR_A], [PAIR_B] or [PAIR_CANT] (`a` and `b` are the
 * first and second clip of the widget, as the manifest and the key list them); [Q_KEEP_C2] and [Q_KEEP_F2]: the id of the chosen clip (one of the four in that widget), [KEEP_NONE] (the plain one is better), [KEEP_THICK]
 * (all of them too thick or boomy) or [KEEP_CANT]; [Q_NOTE]: the owner's text. `plays` has exactly R1h's shape (its voice docs held the same map): `{<clip id>: <how many times that clip was started>, <pair widget id>: <how
 * many times that widget's own PLAY A, THEN B ran to its end>}`. A PLAY A, THEN B run adds one to the plays of A's clip and of B's clip when each starts and one to the widget's id when it ends; a play of A or B alone adds
 * one to that clip; a play of a clip in a keep widget adds one to that clip. Plays are not answers and are kept apart from them, so the decoder can say how many times each widget was run and which clips were heard.
 * The page may keep other bookkeeping beside these; the decoder reads only the fields above.
 *
 * The key (never published, never in the published folder: a check throws if it is): `phase`, `docPrefix`, `overallKey`, then `pairs` (`[{id, group, comparison, a, aIs, b, bIs}]`, `comparison` one of PLAIN, MORE-BELL,
 * BELL-ONLY, MORE-SHELF, IDENTICAL; `aIs` and `bIs` a role: PLAIN, TODAY, TODAY-COPY, MORE-BELL, BELL-ONLY, MORE-SHELF), `keep` (`[{id, group, plain, clips: [{id, letter, is}]}]`, the letter-to-role map of each keep widget in
 * its shown order) and `groups` (`[{voice, note, prefix, label, step, pageGain, pageGainDb, plainLoudness, plainPeak, limit, engine: {asked, cap, delivered, halvings}, letterIs, clips}]`). A clip is `{id, letter, shownInKeep,
 * name, is, shelfDb, bellDb, ratio, file, sha256, samples, rmsRiseDb, loudnessRiseDb, aWeightedRiseDb, aWeightedRiseWholeClipDb, bandsDbVsPlain, peakFinished, peakAtPageLevel, loudnessAtPageLevel, scoutRmsRiseDb,
 * scoutARiseDb}`. `letter` is the letter in the clip's id (null for THE PLAIN ONE; the hidden copy has G and `shownInKeep` false). `ratio` is the shelf dB over the bell dB (null for THE PLAIN ONE, which has no lift pair, and for MORE SHELF, whose bell is 0); `shelfDb` and `bellDb` are the dB per element; the rises are over THE PLAIN ONE
 * of the note, absolute, not level-matched (rms the whole clip, [Loudness.of] the engine's meter, A-weighted over the steady window 0.4 to 1.0 s and over the whole clip); `bandsDbVsPlain` are the absolute harmonic-energy deltas
 * in 80-300, 300-1k, 1k-3k and 3k-8k Hz (null where a band holds no harmonic); the peaks are the finished clip's and its peak at the page's gain. The decoder reads only the key and the verdict doc.
 *
 * In-run checks that throw (a re-run starts by emptying the folder and deleting the key, so a failed check never leaves a page of an earlier run behind; the page's own length is checked first; the checks that need the
 * written files run last, so a failure there leaves a partial folder: do not publish after a failure). Each guard is a function that throws on bad input, and a control (`ARCO bell CONTROL` lines) calls the very same guard
 * with the rule broken and requires it to throw (and the right guard to be the one that threw), or, for a positive control, requires it not to: the copy of the lift section against the engine's (and one with the bell 0.5 dB
 * off); the helper's equal split against the engine's cap; the cap acting and no halving at BODY 1; a frontier point over the limit, at the search ceiling or short of the frontier; a rise off the scout's table by more than
 * 0.05 dB; THE PLAIN ONE not [Arco.render] at BODY 0.5; TODAY not [Arco.render] at BODY 1.0; TODAY-COPY not bit-identical to TODAY; a page gain off by 0.001 percent; a clip over 0.95 at the page's gain; a version that does
 * not lift or turns the top down; identical clips that should differ; each shuffle rule; a clip file that is not the clip times the gain; a tampered hash; the words law (extended with shelf, bell and bigger); the required list (an F#2 item counted, a C2 item or the device question missing, an item twice); the one-line
 * placeholder page, a page with an earlier round's paths or a leaked name; a manifest with a number in it; the key inside the published folder. The run is deterministic: two runs write identical bytes of every file and the key.
 */
object ArcoBellGateGenerator {

    // ---- the contract the page and the decoder share ----------------------------------------------------

    /** The round's name: the db doc is `verdicts/arco_r1j_overall`, the local-storage key `arco-bell-gate:arco_r1j:overall`. */
    internal const val PHASE = "arco_r1j"
    internal const val DOC_PREFIX = "verdicts/arco_r1j_"
    internal const val STORAGE_PREFIX = "arco-bell-gate:"
    internal const val PAGE_RESOURCE = "/audition/arco-bell-gate.html"

    /** The one doc's key, and the field of it that holds the plays. */
    internal const val OVERALL_KEY = "overall"
    internal const val PLAYS = "plays"

    /** The overall doc's fields. Every answer is a string (the page's saving logic keeps strings). */
    internal const val Q_DEVICE = "q_device"
    internal const val Q_NOTE = "q_note"
    internal const val Q_KEEP_C2 = "q_keep_C2"
    internal const val Q_KEEP_F2 = "q_keep_F2"
    internal val Q_PAIRS: List<String> = (1..15).map { "q_pair_$it" }

    /** The pair answers: the first clip, the second, or can't tell. */
    internal const val PAIR_A = "a"
    internal const val PAIR_B = "b"
    internal const val PAIR_CANT = "cant"

    /** The device answers, in the order the question lists them. */
    internal const val DEVICE_HEADPHONES = "headphones"
    internal const val DEVICE_EARBUDS = "earbuds"
    internal const val DEVICE_PHONE = "phone"
    internal const val DEVICE_LAPTOP = "laptop"
    internal const val DEVICE_LOW = "low"
    internal const val DEVICE_OTHER = "other"

    /** The keep answers besides a clip id: the plain one is better, all of them too thick or boomy, can't say. */
    internal const val KEEP_NONE = "none"
    internal const val KEEP_THICK = "thick"
    internal const val KEEP_CANT = "cant"

    /** The two groups' prefixes, which are also the first part of every clip id (no `#` in a file name, so F#2 is `fs2`), and the one folder the clips are in. */
    internal const val G_C2 = "c2"
    internal const val G_F2 = "fs2"
    internal const val VOICE_FOLDER = "CELLO"

    // ---- the roles, the groups and the widgets ----------------------------------------------------------

    /** What a clip is. [key] is what the key file says. */
    internal enum class Role(val key: String) {
        PLAIN("PLAIN"),
        TODAY("TODAY"),
        TODAY_COPY("TODAY-COPY"),
        MORE_BELL("MORE-BELL"),
        BELL_ONLY("BELL-ONLY"),
        MORE_SHELF("MORE-SHELF"),
    }

    /** The four versions that add sound, in the order they are listed in the brief (TODAY first). */
    internal val LIFTED: List<Role> = listOf(Role.TODAY, Role.MORE_BELL, Role.BELL_ONLY, Role.MORE_SHELF)

    /**
     * How strong a role is meant to be, by the rms rise the scout measured at both notes (R1j's tables: BELL ONLY the least, then MORE BELL, then the engine, then MORE SHELF; the plain adds nothing). Fixed here so the
     * shuffle rules do not depend on this run's numbers; [guardPairMeasured] and [guardKeepMeasured] check the measured numbers too.
     */
    private fun designedRank(role: Role): Int = when (role) {
        Role.PLAIN -> 0
        Role.BELL_ONLY -> 1
        Role.MORE_BELL -> 2
        Role.TODAY, Role.TODAY_COPY -> 3
        Role.MORE_SHELF -> 4
    }

    /** One group (a note) of the page. [keepOrder] is the fixed shuffle of the four versions in its keep widget, [keepLetters] their letters in that order and [copyLetter] the id letter of the hidden copy, if the note has one. */
    internal data class Spec(
        val step: Int, val note: String, val prefix: String, val label: String, val body: String, val keepId: String,
        val keepOrder: List<Role>, val keepLetters: String, val copyLetter: Char?,
    ) {
        /** The roles the group holds, in the order of its clip list: the plain, the four versions as shuffled, the copy. */
        val roles: List<Role> get() = listOf(Role.PLAIN) + keepOrder + (if (copyLetter != null) listOf(Role.TODAY_COPY) else emptyList())

        /** The letters of the group in the order of the page: the keep widget's, then the hidden copy's. */
        val letters: String get() = keepLetters + (copyLetter?.toString() ?: "")

        fun letterOf(role: Role): Char? = when (role) {
            Role.PLAIN -> null
            Role.TODAY_COPY -> copyLetter
            else -> keepOrder.indexOf(role).takeIf { it >= 0 }?.let { keepLetters[it] }
        }

        fun idOf(role: Role): String = if (role == Role.PLAIN) "${prefix}_plain" else "${prefix}_${(letterOf(role) ?: error("$prefix holds no $role")).lowercaseChar()}"
    }

    /**
     * The page's groups in the page's order. C2 (the keep widget shows MORE BELL, MORE SHELF, TODAY, BELL ONLY as C D E F; the hidden copy is G) and F#2 (MORE SHELF, TODAY, BELL ONLY, MORE BELL as H J K L). Chosen by hand against
     * the rules; [guardKeep] and [guardLetters] prove them.
     */
    internal val SPECS: List<Spec> = listOf(
        Spec(
            0, "C2", G_C2, "CELLO, C2 (THE LOWEST NOTE)", "The lowest note. Nine pairs. In each, tap PLAY A, THEN B and let it finish, then answer. After the pairs, one question to finish this note.", Q_KEEP_C2,
            listOf(Role.MORE_BELL, Role.MORE_SHELF, Role.TODAY, Role.BELL_ONLY), "CDEF", 'G',
        ),
        Spec(
            6, "F#2", G_F2, "CELLO, F#2 (A SECOND LOW NOTE)", "A second low note. Optional: only if you still have the patience. Six pairs, then one question to finish this note.", Q_KEEP_F2,
            listOf(Role.MORE_SHELF, Role.TODAY, Role.BELL_ONLY, Role.MORE_BELL), "HJKL", null,
        ),
    )

    /** What a widget compares TODAY with, in the order they are listed in the brief. [candidate] is the clip set against TODAY. */
    internal enum class Comparison(val key: String, val candidate: Role) {
        VS_PLAIN("PLAIN", Role.PLAIN),
        MORE_BELL("MORE-BELL", Role.MORE_BELL),
        BELL_ONLY("BELL-ONLY", Role.BELL_ONLY),
        MORE_SHELF("MORE-SHELF", Role.MORE_SHELF),
        IDENTICAL("IDENTICAL", Role.TODAY_COPY),
    }

    /** One widget: its id, its group, what it compares and the two roles in the order they are played (`first` is A). */
    internal class PairSpec(val id: String, val group: String, val comparison: Comparison, val first: Role, val second: Role) {
        /** Whether TODAY is the first clip (A). */
        val todayIsA: Boolean get() = first == Role.TODAY
    }

    private fun pair(n: Int, group: String, comparison: Comparison, first: Role, second: Role) = PairSpec(Q_PAIRS[n - 1], group, comparison, first, second)

    /**
     * The 15 widgets in the page's order. C2: MORE BELL (TODAY first), MORE SHELF (version first), BELL ONLY (TODAY first), PLAIN (plain first), the identical pair (the copy first), MORE SHELF (TODAY first), MORE BELL
     * (version first), PLAIN (TODAY first), BELL ONLY (version first). F#2: MORE BELL, MORE SHELF, BELL ONLY with TODAY first, the same three again with the version first (the only spread six widgets allow). Chosen by hand
     * against the rules; [guardPairs] proves them.
     */
    internal val PAIRS: List<PairSpec> = listOf(
        pair(1, G_C2, Comparison.MORE_BELL, Role.TODAY, Role.MORE_BELL),
        pair(2, G_C2, Comparison.MORE_SHELF, Role.MORE_SHELF, Role.TODAY),
        pair(3, G_C2, Comparison.BELL_ONLY, Role.TODAY, Role.BELL_ONLY),
        pair(4, G_C2, Comparison.VS_PLAIN, Role.PLAIN, Role.TODAY),
        pair(5, G_C2, Comparison.IDENTICAL, Role.TODAY_COPY, Role.TODAY),
        pair(6, G_C2, Comparison.MORE_SHELF, Role.TODAY, Role.MORE_SHELF),
        pair(7, G_C2, Comparison.MORE_BELL, Role.MORE_BELL, Role.TODAY),
        pair(8, G_C2, Comparison.VS_PLAIN, Role.TODAY, Role.PLAIN),
        pair(9, G_C2, Comparison.BELL_ONLY, Role.BELL_ONLY, Role.TODAY),
        pair(10, G_F2, Comparison.MORE_BELL, Role.TODAY, Role.MORE_BELL),
        pair(11, G_F2, Comparison.MORE_SHELF, Role.MORE_SHELF, Role.TODAY),
        pair(12, G_F2, Comparison.BELL_ONLY, Role.TODAY, Role.BELL_ONLY),
        pair(13, G_F2, Comparison.MORE_BELL, Role.MORE_BELL, Role.TODAY),
        pair(14, G_F2, Comparison.MORE_SHELF, Role.TODAY, Role.MORE_SHELF),
        pair(15, G_F2, Comparison.BELL_ONLY, Role.BELL_ONLY, Role.TODAY),
    )

    /**
     * The ids the counter counts (the manifest's `required`): the device question, the nine C2 pairs and the C2 keep, 11 items. The F#2 section (six pairs and its keep) is optional: it stays on the page and is not counted.
     */
    internal fun requiredIds(): List<String> = listOf(Q_DEVICE) + PAIRS.filter { it.group == G_C2 }.map { it.id } + SPECS.first { it.prefix == G_C2 }.keepId

    // ---- the scout's table ------------------------------------------------------------------------------

    /** What the scout's table says a version is: its shelf and bell dB and its rms and A-weighted rise over the plain (R1j's `probe-table1.txt` and `probe-table1c.txt`, to the two decimals they print). */
    internal class Expect(val shelfDb: Double, val bellDb: Double, val rmsRise: Double, val aRise: Double)

    /** The scout's numbers for each version of each group. TODAY is the engine's (2.86 is the table's two decimals of the engine's 2.8590 at C2 and 3.6310 at F#2). */
    internal val SCOUT: Map<String, Map<Role, Expect>> = mapOf(
        G_C2 to mapOf(
            Role.TODAY to Expect(2.86, 2.86, 3.02, 2.24),
            Role.MORE_BELL to Expect(1.83, 3.67, 2.61, 2.57),
            Role.BELL_ONLY to Expect(0.00, 4.91, 1.99, 3.09),
            Role.MORE_SHELF to Expect(5.87, 0.00, 4.55, 1.22),
        ),
        G_F2 to mapOf(
            Role.TODAY to Expect(3.63, 3.63, 3.79, 2.30),
            Role.MORE_BELL to Expect(2.29, 4.57, 3.14, 2.54),
            Role.BELL_ONLY to Expect(0.00, 6.01, 2.25, 2.97),
            Role.MORE_SHELF to Expect(8.03, 0.00, 6.41, 1.77),
        ),
    )

    // ---- the bars ---------------------------------------------------------------------------------------

    /** The loudness every clip of a note is scaled by relative to THE PLAIN ONE: [AuditionLevel]'s own level for the plain (its 0.03), the same gain on every clip of the note. */
    private const val AUDITION_LEVEL = 0.03f

    /** No clip may peak over this at the page's gain (the engine's finished-peak bar is the same number). Not a listening value. */
    private const val PAGE_PEAK_BAR = 0.95f

    /** The copy of the lift section must be off [Arco.lifted] by no more than this share of the peak (R1j saw 0.00e+00 at C2 and F#2 for 1, 3, 6, 9 and 12 dB). Not a listening value. */
    private const val LIFT_COPY_BAR = 1e-6

    /** The helper's equal-split frontier must agree with the engine's cap to this many dB (R1j saw 5.7e-6 where the engine's cap is under 6). Not a listening value. */
    private const val ENGINE_CAP_BAR_DB = 1e-4

    /** A version's rms and A-weighted rise must be within this of the scout's table (the brief's bar), and its shelf and bell dB within the second of these (the table prints two decimals). Not listening values. */
    private const val SCOUT_RISE_BAR_DB = 0.05
    private const val SCOUT_DB_BAR = 0.02

    /** A frontier point's peak must be at least this share of the limit, or it is short of the frontier (the bisection ends within 12 dB over 2 to the 20th of it). Not a listening value. */
    private const val FRONTIER_SHARE = 0.999f

    /** What a version must show against the plain: the two upper bands not under this many dB (treble untouched), and the whole clip at least [RISE_MIN_DB] louder. Not listening values. */
    private const val TOP_FLOOR_DB = -0.5
    private const val RISE_MIN_DB = 1.0

    /** The measured rises are compared in tenths of a dB when the shuffle rules ask whether a list is in order (a tie is a tie). */
    private const val ORDER_TENTHS = 10.0

    /** The page resource must be the real page: the placeholder is one line. R1h's page is about 48000 characters; the bar is 20000. */
    private const val MIN_PAGE_CHARS = 20_000

    private val CELLO = ArcoVoice.CELLO

    private fun finite(x: FloatArray) = x.all { it.isFinite() }

    private fun f4(v: Double) = "%.4f".format(Locale.ROOT, v)

    private fun e2(v: Double) = "%.2e".format(Locale.ROOT, v)

    private fun levelled(x: FloatArray, g: Float) = FloatArray(x.size) { x[it] * g }

    /** [x] with one sample moved one float step (a render that is one ulp off). */
    private fun ulp3(x: FloatArray): FloatArray = x.copyOf().also { it[it.size / 3] = Math.nextUp(it[it.size / 3]) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun tenths(v: Double): Int = Math.round(v * ORDER_TENTHS).toInt()

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
        println("ARCO bell CONTROL $what: the guard THREW, as it must: ${message.take(170)}")
    }

    /** Runs [body], which must NOT throw (a positive control: the guard does not cry wolf on what is allowed). */
    private fun mustHold(what: String, body: () -> Unit) {
        try {
            body()
        } catch (e: IllegalStateException) {
            error("POSITIVE CONTROL FAILED: $what: the guard threw on what it must allow: ${e.message}")
        }
        println("ARCO bell CONTROL $what: the guard held, as it must")
    }

    private fun inOrder(ranks: List<Int>): Boolean = ranks.zipWithNext().all { (a, b) -> a <= b } || ranks.zipWithNext().all { (a, b) -> a >= b }

    /** The copy of the lift section agrees with [Arco.lifted] (shelf dB = bell dB) to [LIFT_COPY_BAR] of the peak. */
    private fun guardLiftCopy(label: String, rel: Double) {
        check(rel <= LIFT_COPY_BAR) { "$label: the copy of the lift section is off Arco.lifted by ${e2(rel)} of the peak (bar ${e2(LIFT_COPY_BAR)})" }
    }

    /** The helper's equal-split frontier is the engine's cap to [ENGINE_CAP_BAR_DB]. */
    private fun guardEngineCap(label: String, helperDb: Double, engineCap: Float) {
        check(abs(helperDb - engineCap.toDouble()) <= ENGINE_CAP_BAR_DB) {
            "$label: the helper's equal-split frontier ${f4(helperDb)} dB is not the engine's cap ${f4(engineCap.toDouble())} dB to ${e2(ENGINE_CAP_BAR_DB)}"
        }
    }

    /** At the page's notes the engine's cap acts at BODY 1 (the lift delivered is the cap, under what BODY asks) and no halving was needed: TODAY is a capped note, which is what the page is about. */
    private fun guardEngineLift(label: String, lift: Arco.Lift) {
        check(lift.halvings == 0) { "$label: the engine halved its delivered lift ${lift.halvings} times" }
        check(lift.delivered < lift.asked) { "$label: the engine's cap does not act at BODY 1 (asked ${f4(lift.asked.toDouble())}, delivered ${f4(lift.delivered.toDouble())}): the note is not a capped one" }
        check(lift.delivered == lift.cap) { "$label: the engine delivered ${f4(lift.delivered.toDouble())} dB, not its cap ${f4(lift.cap.toDouble())}" }
    }

    /** A frontier point: the search ended at the peak and not at its ceiling, the tested peak and the finished clip's are at most the limit, and the point is on the frontier (not far short of it). */
    private fun guardFrontier(label: String, p: Point, finishedPeak: Float, limit: Float) {
        check(!p.limited) { "$label: the search stopped at its ${ArcoBellCandidates.SEARCH_TOP_DB} dB ceiling, not at the peak limit" }
        check(p.peak <= limit) { "$label: the frontier point peaks at ${f4(p.peak.toDouble())}, over the limit ${f4(limit.toDouble())}" }
        check(finishedPeak <= limit) { "$label: the finished clip peaks at ${f4(finishedPeak.toDouble())}, over the limit ${f4(limit.toDouble())}" }
        check(p.peak >= limit * FRONTIER_SHARE) { "$label: the frontier point peaks at ${f4(p.peak.toDouble())}, short of the limit ${f4(limit.toDouble())}: it is not on the frontier" }
    }

    /** A version against the scout's table: rms and A-weighted rise within [SCOUT_RISE_BAR_DB], shelf and bell dB within [SCOUT_DB_BAR]. */
    private fun guardScout(label: String, rmsRise: Double, aRise: Double, shelfDb: Double, bellDb: Double, want: Expect) {
        check(abs(rmsRise - want.rmsRise) <= SCOUT_RISE_BAR_DB) { "$label: the rms rise is ${ArcoWarmthMeasure.s2(rmsRise)} dB, the scout's table ${ArcoWarmthMeasure.s2(want.rmsRise)} (bar ${ArcoWarmthMeasure.f2(SCOUT_RISE_BAR_DB)})" }
        check(abs(aRise - want.aRise) <= SCOUT_RISE_BAR_DB) { "$label: the A-weighted rise is ${ArcoWarmthMeasure.s2(aRise)} dB, the scout's table ${ArcoWarmthMeasure.s2(want.aRise)} (bar ${ArcoWarmthMeasure.f2(SCOUT_RISE_BAR_DB)})" }
        check(abs(shelfDb - want.shelfDb) <= SCOUT_DB_BAR && abs(bellDb - want.bellDb) <= SCOUT_DB_BAR) {
            "$label: shelf ${f4(shelfDb)} / bell ${f4(bellDb)} dB is not the scout's table ${ArcoWarmthMeasure.f2(want.shelfDb)} / ${ArcoWarmthMeasure.f2(want.bellDb)} (bar ${ArcoWarmthMeasure.f2(SCOUT_DB_BAR)})"
        }
    }

    /** [got] is [want] to the sample, bit for bit; [what] says what [want] is. */
    private fun guardSame(label: String, got: FloatArray, want: FloatArray, what: String) {
        check(got.size == want.size && got.indices.all { got[it].toRawBits() == want[it].toRawBits() }) { "$label: not $what, sample for sample" }
    }

    /** A version that adds sound: the two upper bands not under [TOP_FLOOR_DB] against the plain (treble untouched) and the whole clip at least [RISE_MIN_DB] louder than the plain. */
    private fun guardLifted(label: String, n: ArcoWarmthMeasure.Numbers) {
        for (b in ArcoWarmthMeasure.TOP_BANDS) {
            val v = n.bands[b] ?: error("$label: no harmonic in ${ArcoWarmthMeasure.BAND_LABELS[b]}")
            check(v >= TOP_FLOOR_DB) { "$label: ${ArcoWarmthMeasure.BAND_LABELS[b]} is ${ArcoWarmthMeasure.s2(v)} dB against the plain, under ${ArcoWarmthMeasure.s2(TOP_FLOOR_DB)}: the top is turned down" }
        }
        check(n.rmsRise >= RISE_MIN_DB) { "$label: the whole clip is only ${ArcoWarmthMeasure.s2(n.rmsRise)} dB louder than the plain, under +$RISE_MIN_DB: there is no lift" }
    }

    /** The page gain is the one that puts THE PLAIN ONE at [AuditionLevel]'s loudness: the plain times it is [AuditionLevel.level]'s output on the plain. */
    private fun guardGain(label: String, plain: FloatArray, gain: Float) {
        val wanted = AuditionLevel.level(Snip(plain.copyOf(), channels = 1, sampleRate = Dsp.RATE)).samples
        check(wanted.size == plain.size && plain.indices.all { plain[it] * gain == wanted[it] }) { "$label: the page's gain is not AuditionLevel's gain for THE PLAIN ONE" }
    }

    /** No clip peaks over [PAGE_PEAK_BAR] at the page's gain, and none is silent. */
    private fun guardPeaks(label: String, clips: List<Pair<String, FloatArray>>, gain: Float) {
        for ((id, x) in clips) {
            val peak = ArcoBellCandidates.peakOf(x, gain)
            check(peak <= PAGE_PEAK_BAR) { "$label $id peaks at ${ArcoWarmthMeasure.f3(peak.toDouble())} at the page's gain, over ${ArcoWarmthMeasure.f2(PAGE_PEAK_BAR.toDouble())}" }
            check(peak >= 0.001f) { "$label $id is silent at the page's gain (peak $peak)" }
        }
    }

    /** One clip of a built group, as the identity guard and the key see it. [letter] is the letter in the clip's id (null for THE PLAIN ONE); [point] the lift pair behind a version (null for THE PLAIN ONE). */
    internal class Built(val id: String, val name: String, val role: Role, val letter: Char?, val samples: FloatArray, val point: Point?, val numbers: ArcoWarmthMeasure.Numbers?)

    /** A group built: the note, THE PLAIN ONE and its reading, the engine's own renders it is held to, the engine's lift, the page's gain and the clips in the order of [Spec.roles]. */
    internal class BuiltGroup(
        val spec: Spec, val note: ArcoBellCandidates.Note, val plain: FloatArray, val plainRender: FloatArray, val engineTop: FloatArray, val lift: Arco.Lift, val equalSplitBell: Double,
        val base: ArcoWarmthMeasure.Base, val gain: Float, val plainLoudness: Float, val clips: List<Built>,
    ) {
        fun clip(role: Role): Built = clips.first { it.role == role }

        fun with(newClips: List<Built>) = BuiltGroup(spec, note, plain, plainRender, engineTop, lift, equalSplitBell, base, gain, plainLoudness, newClips)
    }

    /** No two clips of a group are the same samples, except TODAY and its copy, which must be. */
    private fun guardIdentity(g: BuiltGroup) {
        for (i in g.clips.indices) for (j in i + 1 until g.clips.size) {
            val a = g.clips[i]
            val b = g.clips[j]
            val identical = a.samples.contentEquals(b.samples)
            if (setOf(a.role, b.role) == setOf(Role.TODAY, Role.TODAY_COPY)) check(identical) { "${g.spec.label}: ${a.id} and ${b.id} (TODAY and its copy) differ" }
            else check(!identical) { "${g.spec.label}: ${a.id} (${a.role.key}) and ${b.id} (${b.role.key}) are the same clip" }
        }
    }

    /** What a printed word or number on the page may not be: no number (a note name such as C2 is the one allowed), no `box`, no EQ words, nothing about how many dB, no expected direction, nothing that names a clip's kind. */
    private val FORBIDDEN_WORDS = Regex(
        "\\b(box|eq|warm\\w*|dbs?|decibels?|shelf|shelves|bell|bells|bigger|boost\\w*|lift\\w*|gain|repeat\\w*|twins?|controls?|old[- ]way|louder-only|filters?|resonan\\w*|treble|bass|identical|cop(y|ies)|today)\\b",
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

    /** What the page and the manifest may not contain: the name of a role, in any case. */
    private val LEAKS = listOf("more-bell", "bell-only", "more-shelf", "today-copy", "morebell", "bellonly", "moreshelf", "todaycopy")

    /** The page: the real one (long enough), carrying this round's own paths and none of an earlier round's, and not naming a role. */
    private fun guardPage(text: String) {
        check(text.length >= MIN_PAGE_CHARS) { "the page is only ${text.length} characters (under $MIN_PAGE_CHARS): it is the placeholder, not the real page, and nothing was written" }
        for (must in listOf(PHASE, STORAGE_PREFIX, "manifest.json")) check(must in text) { "the page does not say \"$must\"" }
        for (stale in listOf("arco_r1h", "arco-body-gate:", "arco_r1e", "arco-warmth:", "arco_r1d", "arco_r1c")) check(stale !in text) { "the page still says \"$stale\", an earlier round's path" }
        for (leak in LEAKS) check(!text.contains(leak, ignoreCase = true)) { "the page names a kind of clip (\"$leak\"): it must stay blind" }
    }

    /**
     * The manifest has no decimal number and none of the key's words. The page is published with it. The one place a forbidden word may stand is the storage prefix, which is the contract's own name for the page's local
     * storage (`arco-bell-gate:`) and is not text the page shows; it is taken out before the words are looked for.
     */
    private fun guardBlind(manifest: String) {
        check(!Regex("[0-9]\\.[0-9]").containsMatchIn(manifest)) { "the manifest has a decimal number" }
        val shown = manifest.replace(STORAGE_PREFIX, "")
        for (leak in LEAKS + listOf("sha256", "shelf", "bell", "today", "identical")) check(!shown.contains(leak, ignoreCase = true)) { "the manifest names \"$leak\"" }
    }

    /**
     * The manifest's `required` list is the device question, every C2 pair and the C2 keep, each once, and nothing from the F#2 section (optional: it stays on the page and is not counted). [required] is read back from the
     * built manifest, so it is the list the page will count.
     */
    private fun guardRequired(required: List<String>, pairs: List<PairSpec>, specs: List<Spec>) {
        check(required.toSet().size == required.size) { "the required list names an item twice: $required" }
        val optional = pairs.filter { it.group != G_C2 }.map { it.id } + specs.filter { it.prefix != G_C2 }.map { it.keepId }
        val counted = optional.filter { it in required }
        check(counted.isEmpty()) { "the required list counts an optional F#2 item: $counted" }
        val want = listOf(Q_DEVICE) + pairs.filter { it.group == G_C2 }.map { it.id } + specs.first { it.prefix == G_C2 }.keepId
        check(required == want) { "the required list is not the device question, the C2 pairs and the C2 keep (${want.size} items): $required" }
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

    /** The key is a sibling of the published folder: publishing the folder whole cannot publish it. */
    private fun guardKeyPlace(root: File, key: File) {
        check(!key.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "the key is inside the published folder" }
    }

    // ---- the shuffle rules ----------------------------------------------------------------------------------

    /** Letters unique across the page, from C, no A, B, I or O, in the order of the page (each group's keep letters, then its hidden copy's). */
    private fun guardLetters(specs: List<Spec>) {
        val all = specs.joinToString("") { it.letters }
        val alphabet = ('A'..'Z').filter { it !in "ABIO" }.joinToString("")
        check(all.toSet().size == all.length) { "the letters are not unique across the page: $all" }
        check(all.none { it in "ABIO" }) { "the letters use A, B, I or O: $all" }
        check(all == alphabet.take(all.length)) { "the letters are not C onward in order of the page: $all" }
    }

    /** A keep widget holds the four versions once each, one letter each. */
    private fun guardKeepRoles(s: Spec) {
        check(s.keepLetters.length == s.keepOrder.size) { "${s.label}: letters ${s.keepLetters} do not match the shuffle" }
        check(s.keepOrder.toSet().size == s.keepOrder.size) { "${s.label}: a version is in the keep shuffle twice: ${s.keepOrder}" }
        check(s.keepOrder.toSet() == LIFTED.toSet()) { "${s.label}: the keep shuffle ${s.keepOrder} is not the four versions" }
    }

    /** A keep widget is not in order of strength (by the designed ranking) and not in the listed order, either way round. */
    private fun guardKeepDesigned(s: Spec) {
        check(!inOrder(s.keepOrder.map { designedRank(it) })) { "${s.label}: the keep widget is in order of strength (${s.keepOrder.map { designedRank(it) }})" }
        check(!inOrder(s.keepOrder.map { LIFTED.indexOf(it) })) { "${s.label}: the keep widget is in the listed order (${s.keepOrder.map { LIFTED.indexOf(it) }})" }
    }

    /** A keep widget is not in order of the measured rms rise, nor of the measured A-weighted rise (tenths of a dB, so a tie is a tie). */
    private fun guardKeepMeasured(s: Spec, rms: Map<Role, Double>, a: Map<Role, Double>) {
        check(!inOrder(s.keepOrder.map { tenths(rms.getValue(it)) })) { "${s.label}: the keep widget is in order of measured rms rise" }
        check(!inOrder(s.keepOrder.map { tenths(a.getValue(it)) })) { "${s.label}: the keep widget is in order of measured A-weighted rise" }
    }

    /** No version sits in the same slot of the two keep widgets. */
    private fun guardKeepSlots(specs: List<Spec>) {
        for (i in specs.indices) for (j in i + 1 until specs.size) {
            for (slot in 0 until minOf(specs[i].keepOrder.size, specs[j].keepOrder.size)) {
                check(specs[i].keepOrder[slot] != specs[j].keepOrder[slot]) { "${specs[i].label} and ${specs[j].label} both put ${specs[i].keepOrder[slot]} in slot $slot of the keep widget" }
            }
        }
    }

    private fun guardKeep(specs: List<Spec>, rms: Map<String, Map<Role, Double>>, a: Map<String, Map<Role, Double>>) {
        guardLetters(specs)
        for (s in specs) {
            guardKeepRoles(s)
            guardKeepDesigned(s)
            guardKeepMeasured(s, rms.getValue(s.prefix), a.getValue(s.prefix))
        }
        guardKeepSlots(specs)
    }

    private fun mine(pairs: List<PairSpec>, group: String) = pairs.filter { it.group == group }

    /**
     * The widgets are the right widgets: 15 with the page's ids; C2 nine (the four comparisons in both orders and one identical pair), F#2 six (the three versions in both orders); every widget plays clips its group holds;
     * a comparison is TODAY against its candidate and is asked once with TODAY first and once with it second; the identical pair is TODAY and its copy.
     */
    private fun guardPairShape(pairs: List<PairSpec>, specs: List<Spec>) {
        check(pairs.size == Q_PAIRS.size) { "there are ${pairs.size} widgets, not ${Q_PAIRS.size}" }
        check(pairs.map { it.id } == Q_PAIRS) { "the widget ids are ${pairs.map { it.id }}, not $Q_PAIRS" }
        for (s in specs) for (p in mine(pairs, s.prefix)) check(p.first in s.roles && p.second in s.roles) { "${p.id}: plays a clip that ${s.label} does not hold" }
        val c2 = mine(pairs, G_C2)
        val f2 = mine(pairs, G_F2)
        check(c2.size == 9 && f2.size == 6) { "C2 has ${c2.size} widgets and F#2 ${f2.size}, not nine and six" }
        check(c2.count { it.comparison == Comparison.IDENTICAL } == 1 && f2.none { it.comparison == Comparison.IDENTICAL }) { "the identical pair is not one widget, in C2 only" }
        for ((group, comparisons) in listOf(G_C2 to Comparison.entries.filter { it != Comparison.IDENTICAL }, G_F2 to listOf(Comparison.MORE_BELL, Comparison.BELL_ONLY, Comparison.MORE_SHELF))) {
            for (c in comparisons) {
                val both = mine(pairs, group).filter { it.comparison == c }
                check(both.size == 2) { "$group ${c.key}: ${both.size} widgets, not two (one in each order)" }
                check(both.all { setOf(it.first, it.second) == setOf(c.candidate, Role.TODAY) }) { "$group ${c.key}: a widget is not TODAY against ${c.candidate.key}" }
                check(both[0].first != both[1].first) { "$group ${c.key}: asked in the same order twice" }
            }
        }
        val same = c2.first { it.comparison == Comparison.IDENTICAL }
        check(setOf(same.first, same.second) == setOf(Role.TODAY, Role.TODAY_COPY)) { "the identical pair is not TODAY against its copy" }
    }

    /** No two adjacent widgets are the same comparison, and the two orders of a comparison have at least two other widgets between them. */
    private fun guardPairApart(pairs: List<PairSpec>) {
        check(pairs.zipWithNext().none { (a, b) -> a.group == b.group && a.comparison == b.comparison }) { "two adjacent widgets are the same comparison" }
        for (i in pairs.indices) for (j in i + 1 until pairs.size) {
            if (pairs[i].group == pairs[j].group && pairs[i].comparison == pairs[j].comparison) {
                check(j - i >= 3) { "${pairs[i].id} and ${pairs[j].id}: the two orders of ${pairs[i].comparison.key} are only ${j - i - 1} widgets apart, not two" }
            }
        }
    }

    /** The identical pair is neither first nor last in the C2 section. */
    private fun guardIdenticalSlot(pairs: List<PairSpec>) {
        val c2 = mine(pairs, G_C2)
        val at = c2.indexOfFirst { it.comparison == Comparison.IDENTICAL }
        check(at > 0 && at < c2.size - 1) { "the identical pair is first or last in the C2 section (slot $at of ${c2.size})" }
    }

    /** TODAY is A in exactly half of each section's non-identical widgets, and never on the same side in more than two widgets running (the identical pair counts as TODAY on its own side). */
    private fun guardTodaySide(pairs: List<PairSpec>) {
        for (group in listOf(G_C2, G_F2)) {
            val real = mine(pairs, group).filter { it.comparison != Comparison.IDENTICAL }
            check(real.count { it.todayIsA } * 2 == real.size) { "$group: TODAY is A in ${real.count { it.todayIsA }} of ${real.size} non-identical widgets, not half" }
            var run = 1
            for ((a, b) in mine(pairs, group).zipWithNext()) {
                run = if (a.todayIsA == b.todayIsA) run + 1 else 1
                check(run <= 2) { "$group: TODAY is on the same side in $run widgets running (at ${b.id})" }
            }
        }
    }

    /** The widgets of a section are not in the order of the comparisons as listed in the brief, either way round. */
    private fun guardPairListing(pairs: List<PairSpec>) {
        for (group in listOf(G_C2, G_F2)) {
            val ranks = mine(pairs, group).map { it.comparison.ordinal }
            check(!inOrder(ranks)) { "$group: the widgets are in the order of the comparisons as listed ($ranks)" }
        }
    }

    /** The widgets of a section are not in order of strength by the designed ranking of the candidate. */
    private fun guardPairDesigned(pairs: List<PairSpec>) {
        for (group in listOf(G_C2, G_F2)) {
            val ranks = mine(pairs, group).map { designedRank(it.comparison.candidate) }
            check(!inOrder(ranks)) { "$group: the widgets are in order of designed strength ($ranks)" }
        }
    }

    /** The widgets of a section are not in order of the measured rms rise of the candidate (tenths of a dB). */
    private fun guardPairMeasured(pairs: List<PairSpec>, rms: Map<String, Map<Role, Double>>) {
        for (group in listOf(G_C2, G_F2)) {
            val ranks = mine(pairs, group).map { tenths(rms.getValue(group).getValue(it.comparison.candidate)) }
            check(!inOrder(ranks)) { "$group: the widgets are in order of measured rms rise of the candidate ($ranks)" }
        }
    }

    /** Widget ids, clip ids, letters and file names are unique across the page. */
    private fun guardUnique(pairs: List<PairSpec>, specs: List<Spec>) {
        check(pairs.map { it.id }.toSet().size == pairs.size) { "the widget ids are not unique" }
        val ids = specs.flatMap { s -> s.roles.map { s.idOf(it) } }
        check(ids.toSet().size == ids.size) { "the clip ids are not unique: $ids" }
        val files = ids.map { "$VOICE_FOLDER/$it.wav" }
        check(files.toSet().size == files.size) { "the file names are not unique" }
        val letters = specs.joinToString("") { it.letters }
        check(letters.toSet().size == letters.length) { "the letters are not unique across the page: $letters" }
    }

    private fun guardPairs(pairs: List<PairSpec>, specs: List<Spec>, rms: Map<String, Map<Role, Double>>) {
        guardPairShape(pairs, specs)
        guardPairApart(pairs)
        guardIdenticalSlot(pairs)
        guardTodaySide(pairs)
        guardPairListing(pairs)
        guardPairDesigned(pairs)
        guardPairMeasured(pairs, rms)
        guardUnique(pairs, specs)
    }

    // ---- building a group ---------------------------------------------------------------------------------

    /**
     * One group's clips. THE PLAIN ONE is the engine's finishing function at BODY 0.5 and TODAY is the helper's rebuild at the engine's delivered lift, each held to [Arco.render] by [checkGroup]; the three other versions
     * are the frontier searches. No guard runs here, so the table can be printed before one can throw.
     */
    private fun build(spec: Spec): BuiltGroup {
        val note = ArcoBellCandidates.Note(CELLO, spec.step)
        val plain = ArcoLiftRig.measured(CELLO, spec.step, Arco.DEFAULT_BODY).samples
        val plainRender = ArcoBodyMeasure.viaArco(CELLO, spec.step, Arco.DEFAULT_BODY)
        val engineTop = ArcoBodyMeasure.viaArco(CELLO, spec.step, 1f)
        val lift = ArcoLiftRig.measured(CELLO, spec.step, 1f).lift
        val delivered = lift.delivered.toDouble()
        val equalSplit = ArcoBellCandidates.frontier(note, 1.0)
        val points = mapOf(
            Role.TODAY to Point(delivered, delivered, note.peakAt(delivered, delivered), false),
            Role.MORE_BELL to ArcoBellCandidates.frontier(note, 0.5),
            Role.BELL_ONLY to ArcoBellCandidates.frontier(note, 0.0),
            Role.MORE_SHELF to ArcoBellCandidates.shelfOnly(note),
        )
        val base = ArcoWarmthMeasure.Base(plain, ArcoBodyMeasure.f0Of(CELLO, spec.step))
        val today = note.clip(delivered, delivered)
        val clips = ArrayList<Built>()
        for (role in spec.roles) {
            val id = spec.idOf(role)
            val letter = spec.letterOf(role)
            val built = when (role) {
                Role.PLAIN -> Built(id, "THE PLAIN ONE", role, null, plain, null, null)
                Role.TODAY_COPY -> Built(id, "CLIP $letter", role, letter, today.copyOf(), points.getValue(Role.TODAY), ArcoWarmthMeasure.compare(today, base))
                Role.TODAY -> Built(id, "CLIP $letter", role, letter, today, points.getValue(role), ArcoWarmthMeasure.compare(today, base))
                else -> {
                    val p = points.getValue(role)
                    val x = note.clip(p.shelfDb, p.bellDb)
                    Built(id, "CLIP $letter", role, letter, x, p, ArcoWarmthMeasure.compare(x, base))
                }
            }
            clips += built
        }
        val plainLoudness = ArcoLiftRig.loudnessOf(plain)
        val gain = AUDITION_LEVEL / plainLoudness.coerceAtLeast(1e-9f)
        return BuiltGroup(spec, note, plain, plainRender, engineTop, lift, equalSplit.bellDb, base, gain, plainLoudness, clips)
    }

    private fun bandsText(a: Array<Double?>): String = ArcoWarmthMeasure.BAND_LABELS.indices.joinToString(" ") { "${ArcoWarmthMeasure.BAND_LABELS[it]} ${a[it]?.let { v -> ArcoWarmthMeasure.s1(v) } ?: "."}" }

    private fun dbOf(g: Float): Double = 20.0 * log10(g.toDouble())

    /** The group's table on `ARCO bell` lines, printed before any guard that can fail on it: the achieved numbers beside the scout's. */
    private fun printGroup(g: BuiltGroup) {
        val s = g.spec
        println(
            "ARCO bell GROUP ${s.label}: step ${s.step}, f0 ${ArcoWarmthMeasure.f1(ArcoBodyMeasure.f0Of(CELLO, s.step))} Hz, the plain's gain x${f4(g.note.gain.toDouble())}, the plain's peak at its gain ${ArcoWarmthMeasure.f3(g.note.plainPeak.toDouble())}, " +
                "the limit ${ArcoWarmthMeasure.f3(g.note.limit.toDouble())}; the engine at BODY 1 asked ${ArcoWarmthMeasure.f2(g.lift.asked.toDouble())}, cap ${f4(g.lift.cap.toDouble())}, delivered ${f4(g.lift.delivered.toDouble())} dB per element " +
                "(halvings ${g.lift.halvings}); the helper's equal-split frontier ${f4(g.equalSplitBell)} dB (differs from the engine's cap by ${e2(abs(g.equalSplitBell - g.lift.cap.toDouble()))}); " +
                "the page gain x${f4(g.gain.toDouble())} (${ArcoWarmthMeasure.s2(dbOf(g.gain))} dB) puts THE PLAIN ONE at Loudness.of ${f4(AUDITION_LEVEL.toDouble())} (it is ${f4(g.plainLoudness.toDouble())} at the plain's gain)",
        )
        println(
            "ARCO bell TABLE ${s.prefix}: id letter role | shelf dB, bell dB (scout) | bands vs the plain ${ArcoWarmthMeasure.BAND_LABELS.joinToString(" ")} dB | rms, Loudness.of, A-weighted rise dB (scout rms, A) | " +
                "peak tested, finished, at the page's gain",
        )
        for (c in g.clips) {
            val n = c.numbers
            val want = SCOUT.getValue(s.prefix)[if (c.role == Role.TODAY_COPY) Role.TODAY else c.role]
            val p = c.point
            println(
                "ARCO bell TABLE ${s.prefix}: ${c.id.padEnd(7)} ${(c.letter?.toString() ?: "-")} ${c.role.key.padEnd(10)} | " +
                    "${p?.let { "${f4(it.shelfDb)}, ${f4(it.bellDb)}" } ?: "no lift pair"}${want?.let { " (${ArcoWarmthMeasure.f2(it.shelfDb)}, ${ArcoWarmthMeasure.f2(it.bellDb)})" } ?: ""} | ${n?.let { bandsText(it.bands) } ?: "reference"} | " +
                    "${n?.let { "${ArcoWarmthMeasure.s2(it.rmsRise)} ${ArcoWarmthMeasure.s2(it.loudRise)} ${ArcoWarmthMeasure.s2(it.aRise)}" } ?: "+0.00 +0.00 +0.00"}${want?.let { " (${ArcoWarmthMeasure.s2(it.rmsRise)}, ${ArcoWarmthMeasure.s2(it.aRise)})" } ?: ""} | " +
                    "${p?.let { ArcoWarmthMeasure.f3(it.peak.toDouble()) } ?: "-"}, ${ArcoWarmthMeasure.f3(ArcoBellCandidates.peakOf(c.samples).toDouble())}, ${ArcoWarmthMeasure.f3(ArcoBellCandidates.peakOf(c.samples, g.gain).toDouble())}",
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
        guardSame("$label THE PLAIN ONE", g.plain, g.plainRender, "Arco.render at BODY 0.5")
        guardSame("$label TODAY", g.clip(Role.TODAY).samples, g.engineTop, "Arco.render at BODY 1.0")
        g.clips.firstOrNull { it.role == Role.TODAY_COPY }?.let { guardSame("$label TODAY-COPY", it.samples, g.clip(Role.TODAY).samples, "bit-identical to TODAY") }
        guardEngineLift(label, g.lift)
        guardEngineCap(label, g.equalSplitBell, g.lift.cap)
        guardGain(label, g.plain, g.gain)
        guardPeaks(label, g.clips.map { it.id to it.samples }, g.gain)
        for (role in LIFTED) {
            val c = g.clip(role)
            val p = c.point!!
            val n = c.numbers!!
            val name = "$label ${role.key}"
            if (role != Role.TODAY) guardFrontier(name, p, ArcoBellCandidates.peakOf(c.samples), g.note.limit)
            else check(ArcoBellCandidates.peakOf(c.samples) <= g.note.limit) { "$name: the finished clip peaks over the limit" }
            guardScout(name, n.rmsRise, n.aRise, p.shelfDb, p.bellDb, SCOUT.getValue(s.prefix).getValue(role))
            guardLifted(name, n)
        }
        guardIdentity(g)
    }

    private fun risesOf(groups: List<BuiltGroup>, pick: (ArcoWarmthMeasure.Numbers) -> Double): Map<String, Map<Role, Double>> =
        groups.associate { g -> g.spec.prefix to g.clips.associate { it.role to (it.numbers?.let(pick) ?: 0.0) } }

    // ---- the controls: every guard shown to throw ------------------------------------------------------

    /** Each guard, called with the rule it keeps broken on purpose, on this run's real clips: it must throw, and with the message of that guard. The ones that must not throw are shown holding. */
    private fun runControls(groups: List<BuiltGroup>, rms: Map<String, Map<Role, Double>>, a: Map<String, Map<Role, Double>>, root: File) {
        val c2 = groups.first { it.spec.prefix == G_C2 }
        val f2 = groups.first { it.spec.prefix == G_F2 }
        val today = c2.clip(Role.TODAY)
        val plain = c2.plain
        val limit = c2.note.limit
        val bell = c2.clip(Role.MORE_BELL)
        val shelfOnly = c2.clip(Role.MORE_SHELF)

        // the copy of the lift section against the engine's
        mustHold("lift copy: shelf = bell = 6 dB at C2 against Arco.lifted") { guardLiftCopy("C2 6 dB", ArcoBellCandidates.liftedDifference(c2.note.conditioned, CELLO, 6f)) }
        mustHold("lift copy: shelf = bell = 3 dB at F#2 against Arco.lifted") { guardLiftCopy("F#2 3 dB", ArcoBellCandidates.liftedDifference(f2.note.conditioned, CELLO, 3f)) }
        mustThrow("lift copy: the bell 0.5 dB too high at C2", "copy of the lift section") { guardLiftCopy("C2 bell 0.5 dB high", ArcoBellCandidates.liftedDifference(c2.note.conditioned, CELLO, 6f, 0.5)) }

        // the engine's cap
        mustHold("engine cap: the helper's equal split is the engine's cap at C2") { guardEngineCap("C2", c2.equalSplitBell, c2.lift.cap) }
        mustThrow("engine cap: the helper 0.001 dB off", "is not the engine's cap") { guardEngineCap("C2 off", c2.equalSplitBell + 0.001, c2.lift.cap) }
        mustHold("engine lift: the cap acts at BODY 1 at C2 and F#2, no halving") { guardEngineLift("C2", c2.lift); guardEngineLift("F#2", f2.lift) }
        mustThrow("engine lift: a note where the cap does not act", "does not act") { guardEngineLift("free", Arco.Lift(6f, 6f, 6f, 0)) }
        mustThrow("engine lift: a delivered lift that was halved", "halved") { guardEngineLift("halved", Arco.Lift(6f, 2.8f, 1.4f, 1)) }

        // the frontier
        val real = bell.point!!
        val finished = ArcoBellCandidates.peakOf(bell.samples)
        mustHold("frontier: the real MORE BELL point at C2") { guardFrontier("C2 MORE-BELL", real, finished, limit) }
        mustThrow("frontier: a point over the limit", "over the limit") { guardFrontier("C2 over", Point(real.shelfDb, real.bellDb, limit + 0.01f, false), finished, limit) }
        mustThrow("frontier: a finished clip over the limit", "finished clip peaks") { guardFrontier("C2 finished over", real, limit + 0.01f, limit) }
        mustThrow("frontier: a search that stopped at its ceiling", "ceiling") { guardFrontier("C2 ceiling", Point(real.shelfDb, real.bellDb, real.peak, true), finished, limit) }
        mustThrow("frontier: a point far short of the limit", "not on the frontier") { guardFrontier("C2 short", Point(real.shelfDb, real.bellDb, limit * 0.9f, false), finished, limit) }
        mustHold("frontier: the real MORE SHELF point at C2 (bell 0)") { guardFrontier("C2 MORE-SHELF", shelfOnly.point!!, ArcoBellCandidates.peakOf(shelfOnly.samples), limit) }

        // the scout's table
        val want = SCOUT.getValue(G_C2).getValue(Role.MORE_BELL)
        val bn = bell.numbers!!
        mustHold("scout: the real MORE BELL at C2") { guardScout("C2 MORE-BELL", bn.rmsRise, bn.aRise, real.shelfDb, real.bellDb, want) }
        mustHold("scout: a rise 0.04 dB off is inside the bar of 0.05") { guardScout("C2 near", bn.rmsRise + 0.04, bn.aRise - 0.04, real.shelfDb, real.bellDb, want) }
        mustThrow("scout: the rms rise 0.06 dB off", "rms rise") { guardScout("C2 rms", bn.rmsRise + 0.06, bn.aRise, real.shelfDb, real.bellDb, want) }
        mustThrow("scout: the A-weighted rise 0.06 dB off", "A-weighted rise") { guardScout("C2 A", bn.rmsRise, bn.aRise - 0.06, real.shelfDb, real.bellDb, want) }
        mustThrow("scout: the bell 0.05 dB off", "scout's table") { guardScout("C2 bell", bn.rmsRise, bn.aRise, real.shelfDb, real.bellDb + 0.05, want) }

        // THE PLAIN ONE, TODAY and the copy
        mustHold("plain: THE PLAIN ONE is Arco.render at BODY 0.5") { guardSame("C2 THE PLAIN ONE", c2.plain, c2.plainRender, "Arco.render at BODY 0.5") }
        mustThrow("plain: one float step off", "Arco.render at BODY 0.5") { guardSame("C2 THE PLAIN ONE", ulp3(c2.plain), c2.plainRender, "Arco.render at BODY 0.5") }
        mustThrow("plain: a lifted clip standing in for it", "Arco.render at BODY 0.5") { guardSame("C2 THE PLAIN ONE", today.samples, c2.plainRender, "Arco.render at BODY 0.5") }
        mustHold("today: TODAY is Arco.render at BODY 1.0") { guardSame("C2 TODAY", today.samples, c2.engineTop, "Arco.render at BODY 1.0") }
        mustThrow("today: one float step off", "Arco.render at BODY 1.0") { guardSame("C2 TODAY", ulp3(today.samples), c2.engineTop, "Arco.render at BODY 1.0") }
        mustThrow("today: rebuilt 0.001 dB above the engine's delivered lift", "Arco.render at BODY 1.0") {
            val d = c2.lift.delivered.toDouble() + 0.001
            guardSame("C2 TODAY", c2.note.clip(d, d), c2.engineTop, "Arco.render at BODY 1.0")
        }
        val copy = c2.clip(Role.TODAY_COPY)
        mustHold("copy: TODAY-COPY is bit-identical to TODAY") { guardSame("C2 TODAY-COPY", copy.samples, today.samples, "bit-identical to TODAY") }
        mustThrow("copy: one float step off", "bit-identical to TODAY") { guardSame("C2 TODAY-COPY", ulp3(copy.samples), today.samples, "bit-identical to TODAY") }
        mustThrow("copy: a different version standing in for it", "bit-identical to TODAY") { guardSame("C2 TODAY-COPY", bell.samples, today.samples, "bit-identical to TODAY") }

        // the page gain and the peak bar
        mustHold("gain: the page's own") { guardGain("C2", plain, c2.gain) }
        mustThrow("gain: 0.001 percent off", "not AuditionLevel's gain") { guardGain("C2", plain, c2.gain * 1.00001f) }
        mustHold("peak: every C2 clip at the page's gain") { guardPeaks("C2", c2.clips.map { it.id to it.samples }, c2.gain) }
        mustThrow("peak: a clip over 0.95 at the page's gain (the gain times 40)", "at the page's gain, over") { guardPeaks("C2", c2.clips.map { it.id to it.samples }, c2.gain * 40f) }
        mustThrow("peak: a silent clip", "is silent") { guardPeaks("C2", listOf("silent" to FloatArray(plain.size)), c2.gain) }

        // a version that adds sound and keeps the top
        mustHold("lifted: MORE BELL at C2 keeps the top and lifts") { guardLifted("C2 MORE-BELL", bn) }
        val flat = ArcoWarmthMeasure.compare(c2.plain, c2.base)
        mustThrow("lifted: the plain itself (no lift)", "there is no lift") { guardLifted("C2 plain", flat) }
        val dull = ArcoWarmthMeasure.compare(highShelved(today.samples), c2.base)
        mustThrow("lifted: a version with the top turned down", "the top is turned down") { guardLifted("C2 dull", dull) }

        // identity
        mustHold("identity: C2 as built (TODAY and its copy are one sound, no other two are)") { guardIdentity(c2) }
        mustHold("identity: F#2 as built") { guardIdentity(f2) }
        mustThrow("identity: a version that is the plain", "are the same clip") {
            guardIdentity(c2.with(c2.clips.map { if (it.role == Role.BELL_ONLY) Built(it.id, it.name, it.role, it.letter, plain.copyOf(), it.point, it.numbers) else it }))
        }
        mustThrow("identity: the copy that is not TODAY", "differ") {
            guardIdentity(c2.with(c2.clips.map { if (it.role == Role.TODAY_COPY) Built(it.id, it.name, it.role, it.letter, ulp3(today.samples), it.point, it.numbers) else it }))
        }

        // the shuffle rules, one broken at a time
        val specs = SPECS
        val s2 = specs[0]
        val sf = specs[1]
        mustHold("shuffle: the page's own keep widgets and letters, every rule") { guardKeep(specs, rms, a) }
        mustThrow("shuffle: letters repeated across the page", "not unique") { guardLetters(listOf(s2, sf.copy(keepLetters = "HJKC"))) }
        mustThrow("shuffle: a letter that reads as A, B, I or O", "A, B, I or O") { guardLetters(listOf(s2, sf.copy(keepLetters = "HIKL"))) }
        mustThrow("shuffle: letters out of the page's order", "in order of the page") { guardLetters(listOf(sf.copy(keepLetters = "CDEF", copyLetter = null), s2.copy(keepLetters = "HJKL", copyLetter = 'M'))) }
        mustThrow("shuffle: a version in the keep shuffle twice", "twice") { guardKeepRoles(s2.copy(keepOrder = s2.keepOrder.dropLast(1) + Role.MORE_BELL)) }
        mustThrow("shuffle: a keep widget that is not the four versions", "not the four versions") { guardKeepRoles(sf.copy(keepOrder = sf.keepOrder.dropLast(1) + Role.PLAIN)) }
        mustThrow("shuffle: a keep widget in order of designed strength", "in order of strength") { guardKeepDesigned(s2.copy(keepOrder = s2.keepOrder.sortedBy { designedRank(it) })) }
        mustThrow("shuffle: a keep widget in order of designed strength, down", "in order of strength") { guardKeepDesigned(sf.copy(keepOrder = sf.keepOrder.sortedByDescending { designedRank(it) })) }
        mustThrow("shuffle: a keep widget in the listed order", "listed order") { guardKeepDesigned(s2.copy(keepOrder = LIFTED)) }
        val rmsC2 = rms.getValue(G_C2)
        val aC2 = a.getValue(G_C2)
        // The A-weighted ranking of the four versions is the exact reverse of the rms one at both notes, so an order sorted by A is also in order of rms and the rms check would throw first: that control is given an rms table that is not in order.
        val rmsMixed = mapOf(Role.MORE_SHELF to 3.0, Role.TODAY to 1.0, Role.MORE_BELL to 2.0, Role.BELL_ONLY to 0.0)
        mustHold("shuffle: the page's own keep widget at C2 against the measured rms and A-weighted rises") { guardKeepMeasured(s2, rmsC2, aC2) }
        mustThrow("shuffle: a keep widget sorted by measured rms rise", "measured rms rise") { guardKeepMeasured(s2.copy(keepOrder = s2.keepOrder.sortedBy { rmsC2.getValue(it) }), rmsC2, aC2) }
        mustThrow("shuffle: a keep widget sorted by measured A-weighted rise", "measured A-weighted rise") { guardKeepMeasured(s2.copy(keepOrder = s2.keepOrder.sortedBy { aC2.getValue(it) }), rmsMixed, aC2) }
        mustThrow("shuffle: the same version in the same slot of both keep widgets", "slot") { guardKeepSlots(listOf(s2, sf.copy(keepOrder = listOf(s2.keepOrder[0]) + sf.keepOrder.filter { it != s2.keepOrder[0] }))) }

        val pairs = PAIRS
        fun flip(p: PairSpec) = PairSpec(p.id, p.group, p.comparison, p.second, p.first)
        fun flipped(vararg n: Int): List<PairSpec> = pairs.map { p -> if (n.any { Q_PAIRS[it - 1] == p.id }) flip(p) else p }
        fun moved(from: Int, to: Int): List<PairSpec> = pairs.toMutableList().also { val x = it.removeAt(from - 1); it.add(to - 1, x) }
        fun bySection(order: Comparator<PairSpec>): List<PairSpec> = listOf(G_C2, G_F2).flatMap { g -> pairs.filter { it.group == g }.sortedWith(order) }
        mustHold("widgets: the page's own, every rule") { guardPairs(pairs, specs, rms) }
        mustThrow("widgets: a comparison asked in one order twice (widget 7 turned round)", "same order twice") { guardPairShape(flipped(7), specs) }
        mustThrow("widgets: 14 of the 15", "not 15") { guardPairShape(pairs.dropLast(1), specs) }
        mustThrow("widgets: a widget that plays a clip its group does not hold (the copy at F#2)", "does not hold") {
            guardPairShape(pairs.map { if (it.id == Q_PAIRS[9]) PairSpec(it.id, it.group, it.comparison, Role.TODAY_COPY, Role.MORE_BELL) else it }, specs)
        }
        mustThrow("widgets: the two orders of a comparison only one widget apart (widget 7 moved to the third place)", "widgets apart") { guardPairApart(moved(7, 3)) }
        mustThrow("widgets: two adjacent widgets that are the same comparison (widget 7 moved to the second place)", "adjacent") { guardPairApart(moved(7, 2)) }
        mustThrow("widgets: the identical pair first in the C2 section", "first or last") { guardIdenticalSlot(moved(5, 1)) }
        mustThrow("widgets: the identical pair last in the C2 section", "first or last") { guardIdenticalSlot(moved(5, 9)) }
        mustThrow("widgets: TODAY is A in more than half of a section (widget 2 turned round)", "not half") { guardTodaySide(flipped(2)) }
        mustThrow("widgets: TODAY on the same side in three widgets running (widgets 1, 3, 4 and 7 turned round: still half)", "same side") { guardTodaySide(flipped(1, 3, 4, 7)) }
        mustThrow("widgets: in the order of the comparisons as listed", "as listed") { guardPairListing(bySection(compareBy<PairSpec> { it.comparison.ordinal })) }
        mustThrow("widgets: in the order of the comparisons as listed, reversed", "as listed") { guardPairListing(bySection(compareByDescending<PairSpec> { it.comparison.ordinal })) }
        mustThrow("widgets: in order of designed strength", "designed strength") { guardPairDesigned(bySection(compareBy<PairSpec> { designedRank(it.comparison.candidate) })) }
        mustThrow("widgets: in order of designed strength, reversed", "designed strength") { guardPairDesigned(bySection(compareByDescending<PairSpec> { designedRank(it.comparison.candidate) })) }
        mustThrow("widgets: in order of the measured rms rise of the candidate", "measured rms rise") {
            guardPairMeasured(bySection(compareBy<PairSpec> { rms.getValue(it.group).getValue(it.comparison.candidate) }), rms)
        }
        mustThrow("widgets: a widget id twice", "widget ids are not unique") { guardUnique(pairs.map { if (it.id == Q_PAIRS[1]) PairSpec(Q_PAIRS[0], it.group, it.comparison, it.first, it.second) else it }, specs) }
        mustThrow("widgets: a clip id twice (two groups with one prefix)", "clip ids are not unique") { guardUnique(pairs, listOf(s2, sf.copy(prefix = G_C2, keepLetters = "CDEF"))) }

        // the words, the page, the manifest, the key, the hashes
        mustHold("words: a note name and a plain sentence") { guardWords(listOf("CELLO, F#2 (A SECOND LOW NOTE)", "Which of these two sounds fuller?")) }
        mustThrow("words: a number", "has a number") { guardWords(listOf("pair 3 of 9")) }
        mustThrow("words: the word box", "\"box\"") { guardWords(listOf("the box rings")) }
        mustThrow("words: EQ", "\"EQ\"") { guardWords(listOf("an EQ move")) }
        mustThrow("words: warmer", "\"warmer\"") { guardWords(listOf("which sounds warmer")) }
        mustThrow("words: dB", "\"dB\"") { guardWords(listOf("it is louder by a few dB")) }
        mustThrow("words: shelf", "\"shelf\"") { guardWords(listOf("more shelf")) }
        mustThrow("words: bell", "\"bell\"") { guardWords(listOf("a broad bell")) }
        mustThrow("words: bigger", "\"bigger\"") { guardWords(listOf("which sounds like a bigger instrument")) }
        mustThrow("words: the name of a kind", "\"repeat\"") { guardWords(listOf("the repeat of the plain")) }
        mustThrow("words: the hidden pair", "\"identical\"") { guardWords(listOf("one pair is identical")) }
        val longPage = "arco_r1j arco-bell-gate: manifest.json " + "x".repeat(MIN_PAGE_CHARS)
        mustHold("page: a long page with this round's paths") { guardPage(longPage) }
        mustThrow("page: the one-line placeholder", "placeholder") { guardPage("<!doctype html><title>placeholder</title>") }
        mustThrow("page: an earlier round's path", "an earlier round's path") { guardPage("$longPage arco_r1h") }
        mustThrow("page: a leaked name of a version", "kind of clip") { guardPage("$longPage MORE-BELL") }
        mustThrow("page: no manifest", "does not say") { guardPage("arco_r1j arco-bell-gate: " + "x".repeat(MIN_PAGE_CHARS)) }
        val req = requiredIds()
        mustHold("required: the device question, the nine C2 pairs and the C2 keep (11 items)") { guardRequired(req, pairs, specs) }
        mustThrow("required: the earlier list of 18 (F#2 counted)", "optional F#2 item") { guardRequired(listOf(Q_DEVICE) + Q_PAIRS + specs.map { it.keepId }, pairs, specs) }
        mustThrow("required: the F#2 keep counted", "optional F#2 item") { guardRequired(req + Q_KEEP_F2, pairs, specs) }
        mustThrow("required: an F#2 pair counted", "optional F#2 item") { guardRequired(req + Q_PAIRS[9], pairs, specs) }
        mustThrow("required: a C2 pair missing", "is not the device question") { guardRequired(req - Q_PAIRS[3], pairs, specs) }
        mustThrow("required: the C2 keep missing", "is not the device question") { guardRequired(req - Q_KEEP_C2, pairs, specs) }
        mustThrow("required: the device question missing", "is not the device question") { guardRequired(req - Q_DEVICE, pairs, specs) }
        mustThrow("required: an item twice", "twice") { guardRequired(req + Q_PAIRS[0], pairs, specs) }
        mustThrow("manifest: a decimal number", "decimal number") { guardBlind("{\"x\":\"0.75\"}") }
        mustThrow("manifest: a hash", "names") { guardBlind("{\"sha256\":\"abc\"}") }
        mustThrow("manifest: a role", "names") { guardBlind("{\"x\":\"bell-only\"}") }
        mustThrow("manifest: the word bell outside the storage prefix", "names") { guardBlind("{\"storagePrefix\":\"$STORAGE_PREFIX\",\"x\":\"a bell\"}") }
        mustHold("manifest: the storage prefix alone") { guardBlind("{\"storagePrefix\":\"$STORAGE_PREFIX\"}") }
        mustThrow("json: a manifest that does not parse", "not valid JSON") { guardJson("manifest", "{\"groups\":[") }
        mustHold("key: beside the published folder") { guardKeyPlace(root, File(root.absoluteFile.parentFile, root.name + "-key.json")) }
        mustThrow("key: inside the published folder", "inside the published folder") { guardKeyPlace(root, File(root, "arco-bell-gate-key.json")) }
        mustThrow("key: in a folder of the published folder", "inside the published folder") { guardKeyPlace(root, File(File(root, VOICE_FOLDER), "key.json")) }
        mustHold("hashes: the same lists") { guardHashes(mapOf("a" to "1"), mapOf("a" to "1")) }
        mustThrow("hashes: a tampered hash", "hash is not the file's") { guardHashes(mapOf("a" to "1"), mapOf("a" to "2")) }
        mustThrow("hashes: a file the key does not list", "not the key's list") { guardHashes(mapOf("a" to "1"), mapOf("a" to "1", "b" to "2")) }
    }

    /** [x] through one RBJ high shelf of -12 dB at 2 kHz at the house rate: a clip whose top is turned down, for the control that must see a loss at the top. */
    private fun highShelved(x: FloatArray): FloatArray {
        val f = Dsp.Biquad()
        f.highShelf(2000f, -12f, Dsp.RATE)
        return FloatArray(x.size) { f.process(x[it]) }
    }

    // ---- main ---------------------------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        val clipsOnly = "--clips-only" in args
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/arco-bell-gate")
        check(root.name == "arco-bell-gate") { "the output folder is ${root.path}: it is emptied first, so it must be a folder named arco-bell-gate" }
        // The key is a sibling of the published folder, so publishing the folder whole cannot publish it. A re-run starts from nothing: no clip, manifest or page of an earlier run survives a failed check.
        val keyFile = File(root.absoluteFile.parentFile, root.name + "-key.json")
        guardKeyPlace(root, keyFile)
        root.deleteRecursively()
        keyFile.delete()

        // The page must be the real one: a placeholder is one line. This throws before any file is written (the folder is already empty). The development mode skips the page.
        val pageText: String? = if (clipsOnly) null else {
            val text = (ArcoBellGateGenerator::class.java.getResourceAsStream(PAGE_RESOURCE)
                ?: error("the gate page is missing from synth/src/test/resources/audition/ (arco-bell-gate.html), and nothing was written")).use { it.readBytes() }.toString(Charsets.UTF_8)
            guardPage(text)
            text
        }
        root.mkdirs()
        println("ARCO bell mode: ${if (clipsOnly) "--clips-only (clips, manifest and key; no index.html)" else "full run (the page is checked and copied)"}")

        for (spec in SPECS) {
            check(ArcoBodyMeasure.noteName(CELLO, spec.step) == spec.note) { "${spec.label} is on ${ArcoBodyMeasure.noteName(CELLO, spec.step)}" }
            check(Arco.semitoneFor(CELLO, ArcoBodyMeasure.tuneOf(CELLO, spec.step)) == spec.step) { "${spec.label}: the TUNE step does not round-trip" }
        }

        val groups = SPECS.parallelStream().map { build(it) }.toList()
        for (g in groups) printGroup(g)
        for (g in groups) checkGroup(g)

        // The copy of the lift section against the engine's, at both notes and five sizes (the controls show the same check failing on a bell 0.5 dB off).
        var worstCopy = 0.0
        var leastControl = Double.MAX_VALUE
        for (g in groups) for (d in listOf(1f, 3f, 6f, 9f, 12f)) {
            val rel = ArcoBellCandidates.liftedDifference(g.note.conditioned, CELLO, d)
            val wrong = ArcoBellCandidates.liftedDifference(g.note.conditioned, CELLO, d, 0.5)
            guardLiftCopy("${g.note.name} $d dB", rel)
            worstCopy = maxOf(worstCopy, rel)
            leastControl = minOf(leastControl, wrong)
        }
        println("ARCO bell PROOF: the copy of the lift section (shelf = bell = d for 1, 3, 6, 9, 12 dB at C2 and F#2) differs from Arco.lifted by at most ${e2(worstCopy)} of the peak (bar ${e2(LIFT_COPY_BAR)}); the control with the bell 0.5 dB high differs by at least ${e2(leastControl)}")
        check(leastControl > 1e-4) { "the control (a bell 0.5 dB high) is not told apart from the copy (${e2(leastControl)})" }

        // The ruler's own controls, on CELLO C3's plain (as R1h's: the ideal shelf at 1 kHz needs a note with no partial straddling it).
        val rulerStep = 12
        val rulerFailures = ArcoWarmthMeasure.controlsFailures(ArcoLiftRig.render(CELLO, rulerStep, Arco.DEFAULT_BODY), ArcoBodyMeasure.f0Of(CELLO, rulerStep))
        println("ARCO bell ruler controls on CELLO C3: ${if (rulerFailures.isEmpty()) "all hold (self 0, times 2 reads +6.0206, the ideal shelf, the A-weighting against the standard's table)" else rulerFailures.joinToString("; ")}")
        check(rulerFailures.isEmpty()) { "the ruler's controls fail: $rulerFailures" }

        val rms = risesOf(groups) { it.rmsRise }
        val aRise = risesOf(groups) { it.aRise }
        guardKeep(SPECS, rms, aRise)
        guardPairs(PAIRS, SPECS, rms)
        for (s in SPECS) {
            println(
                "ARCO bell SHUFFLE ${s.label}: keep widget ${s.keepLetters.toList().zip(s.keepOrder).joinToString(" ") { "${it.first}=${it.second.key}" }}${s.copyLetter?.let { " (hidden ${it}=TODAY-COPY)" } ?: ""}; " +
                    "designed ranks ${s.keepOrder.map { designedRank(it) }}, listed order ${s.keepOrder.map { LIFTED.indexOf(it) }}, measured rms ${s.keepOrder.map { tenths(rms.getValue(s.prefix).getValue(it)) }} tenths of a dB, " +
                    "measured A-weighted ${s.keepOrder.map { tenths(aRise.getValue(s.prefix).getValue(it)) }}: none in order: OK",
            )
        }
        println("ARCO bell SHUFFLE no version is in the same slot of both keep widgets; the letters ${SPECS.joinToString("") { it.letters }} are unique, skip A, B, I and O, and run in the order of the page: OK")
        for (s in SPECS) {
            val ws = mine(PAIRS, s.prefix)
            println(
                "ARCO bell WIDGETS ${s.label}: ${ws.size} widgets in order ${ws.joinToString(" ") { "${it.id.removePrefix("q_pair_")}:${it.comparison.key}" }}; comparisons as listed ${ws.map { it.comparison.ordinal }}, designed strength ${ws.map { designedRank(it.comparison.candidate) }}, " +
                    "measured rms of the candidate ${ws.map { tenths(rms.getValue(s.prefix).getValue(it.comparison.candidate)) }} tenths of a dB: none in order; TODAY is A in ${ws.filter { it.comparison != Comparison.IDENTICAL }.count { it.todayIsA }} of " +
                    "${ws.count { it.comparison != Comparison.IDENTICAL }} non-identical widgets, side by widget ${ws.joinToString("") { if (it.todayIsA) "A" else "B" }}: OK",
            )
        }
        println("ARCO bell WIDGETS the two orders of a comparison are at least two widgets apart, no two adjacent widgets are the same comparison, the identical pair is neither first nor last in C2, ids and files are unique: OK")

        runControls(groups, rms, aRise, root)

        // ---- write ----
        val hashes = LinkedHashMap<String, String>()
        var count = 0
        for (g in groups) for (c in g.clips) {
            val x = levelled(c.samples, g.gain)
            val file = File(File(root, VOICE_FOLDER), "${c.id}.wav")
            WavWriter.write(file, Snip(x, channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_16)
            guardWritten(file, x)
            hashes["$VOICE_FOLDER/${c.id}.wav"] = sha256(file.readBytes())
            count++
        }
        check(count == groups.sumOf { it.spec.roles.size } && count == 11) { "the page has $count clips, not 11" }
        val onDisk = HashMap<String, String>()
        for (f in File(root, VOICE_FOLDER).listFiles()!!.sortedBy { it.name }) onDisk["$VOICE_FOLDER/${f.name}"] = sha256(f.readBytes())
        guardHashes(hashes, onDisk)
        // The copy's file is TODAY's, byte for byte; no other two files are the same.
        val c2 = groups.first { it.spec.prefix == G_C2 }
        check(hashes.getValue("$VOICE_FOLDER/${c2.clip(Role.TODAY).id}.wav") == hashes.getValue("$VOICE_FOLDER/${c2.clip(Role.TODAY_COPY).id}.wav")) { "TODAY-COPY's file is not TODAY's, byte for byte" }
        val distinct = hashes.values.toSet().size
        println("ARCO bell files: $count clips written, $distinct distinct (TODAY-COPY is TODAY's file under a second name)")
        check(distinct == count - 1) { "$distinct distinct files among $count clips, expected ${count - 1}: the copy is the only repeat" }

        mustThrow("written: a file that is not the clip times the gain (the clip 0.1 percent louder)", "differs from the clip times the page's gain") {
            val t = c2.clip(Role.TODAY)
            guardWritten(File(File(root, VOICE_FOLDER), "${t.id}.wav"), levelled(t.samples, c2.gain * 1.001f))
        }

        val visible = ArrayList<String>()
        val manifest = manifestJson(groups, visible)
        guardWords(visible)
        guardBlind(manifest)
        guardJson("manifest.json", manifest)
        val required = Json.parse(manifest).obj().getValue("questions").obj().getValue("required").arr().map { it.str() }
        guardRequired(required, PAIRS, SPECS)
        println("ARCO bell REQUIRED the counter counts ${required.size} items (the device question, the C2 pairs ${PAIRS.count { it.group == G_C2 }} and the C2 keep); the F#2 section is optional (${PAIRS.count { it.group != G_C2 }} pairs and its keep, not counted): OK")
        val key = keyJson(groups, hashes)
        guardJson("the key", key)
        File(root, "manifest.json").writeText(manifest)
        keyFile.writeText(key)
        if (pageText != null) File(root, "index.html").writeText(pageText)

        for (g in groups) {
            val s = g.spec
            println("ARCO bell KEY ${s.label}: ${s.keepLetters.toList().zip(s.keepOrder).joinToString(" ") { "${it.first}=${it.second.key}" }}${s.copyLetter?.let { " (${it}=TODAY-COPY, not in the keep widget)" } ?: ""}")
        }
        for (p in PAIRS) {
            val s = SPECS.first { it.prefix == p.group }
            println("ARCO bell KEY ${p.id} ${p.group} ${p.comparison.key}: A=${p.first.key} (${s.idOf(p.first)}) B=${p.second.key} (${s.idOf(p.second)})")
        }
        for ((path, h) in hashes) println("ARCO bell HASH $path $h")
        val fingerprint = sha256((hashes.entries.joinToString("\n") { "${it.key} ${it.value}" } + "\nmanifest ${sha256(manifest.toByteArray(Charsets.UTF_8))}\nkey ${sha256(key.toByteArray(Charsets.UTF_8))}\n").toByteArray(Charsets.UTF_8))
        println("ARCO bell FINGERPRINT $fingerprint (the clips' hashes, the manifest's and the key's: two runs must print the same)")
        println("ARCO bell the key is ${keyFile.absolutePath}, beside the folder and not in it")
        println("wrote $count clips + manifest.json${if (pageText != null) " + index.html" else " (no index.html: --clips-only)"} under ${root.absolutePath} (publish that folder whole; the key is not in it)")
    }

    // ---- the manifest ------------------------------------------------------------------------------------

    private const val DOT = "·"

    private val INTRO = listOf(
        "Headphones, please. No headphones? Say what you use in the first question. Set your volume once, so THE PLAIN ONE is easy to hear, and leave it there for the whole page.",
        "Two low cello notes, each in several versions. Nobody has listened to them yet: some may sound better than THE PLAIN ONE (nothing added) and some worse. Anything odd, write it in the last field.",
        "The clips are not the same loudness, on purpose: the other versions add sound, so they are louder than THE PLAIN ONE. Choose what sounds fuller to you, whether or not it is louder. Tap SAVE VERDICT at the very bottom when you are done.",
    )
    private const val PLAIN_DESC = "the reference: how the note sounds with nothing added"
    private const val DEVICE_TEXT = "First question: what are you listening on?"
    private const val PAIR_TEXT = "Which of these two sounds fuller?"
    private const val PAIR_LOCKED = "Tap PLAY A, THEN B and let it finish, then answer."
    private const val KEEP_TEXT = "Which one would you keep for this note? Tap the triangle to hear a clip, and its name to keep it."
    private const val NOTE_TEXT = "Anything else you noticed? Say it in your own words."

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

    private fun manifestJson(groups: List<BuiltGroup>, visible: MutableList<String>): String {
        fun v(s: String): String { visible += s; return q(s) }
        fun options(items: List<Pair<String, String>>) = arr(items.map { obj("v" to q(it.first), "t" to v(it.second)) })
        val groupJson = groups.map { g ->
            val s = g.spec
            val clips = s.roles.map { r -> val c = g.clip(r); "[${q(c.id)},${v(c.name)},${v(if (r == Role.PLAIN) PLAIN_DESC else "")}]" }
            obj("id" to q(s.prefix), "voice" to q(VOICE_FOLDER), "note" to q(s.note), "label" to v(s.label), "body" to v(s.body), "clips" to arr(clips))
        }
        val pairs = PAIRS.map { p ->
            val s = SPECS.first { it.prefix == p.group }
            obj("id" to q(p.id), "voice" to q(VOICE_FOLDER), "group" to q(p.group), "note" to q(s.note), "a" to q(s.idOf(p.first)), "b" to q(s.idOf(p.second)))
        }
        val keeps = SPECS.map { s ->
            obj(
                "id" to q(s.keepId), "voice" to q(VOICE_FOLDER), "group" to q(s.prefix), "text" to v(KEEP_TEXT),
                "clips" to arr((listOf(Role.PLAIN) + s.keepOrder).map { q(s.idOf(it)) }),
                "extra" to options(listOf(KEEP_NONE to "NONE, THE PLAIN ONE IS BETTER", KEEP_THICK to "ALL OF THEM SOUND TOO THICK OR BOOMY", KEEP_CANT to "I CAN'T SAY")),
            )
        }
        val questions = obj(
            "required" to arr(requiredIds().map { q(it) }),
            "counter" to obj(
                "some" to v("{n} REQUIRED QUESTIONS UNANSWERED"), "one" to v("ONE REQUIRED QUESTION UNANSWERED"), "none" to v("ALL REQUIRED QUESTIONS ANSWERED"),
                "unsent" to v("ALL ANSWERED. NOW TAP SAVE VERDICT AT THE BOTTOM"),
            ),
            "save" to obj("some" to v("SAVE VERDICT $DOT {n} UNANSWERED"), "one" to v("SAVE VERDICT $DOT ONE UNANSWERED"), "none" to v("SAVE VERDICT")),
            "device" to obj(
                "id" to q(Q_DEVICE), "first" to "true", "required" to "true", "text" to v(DEVICE_TEXT),
                "options" to options(
                    listOf(
                        DEVICE_HEADPHONES to "HEADPHONES", DEVICE_EARBUDS to "EARBUDS", DEVICE_PHONE to "PHONE SPEAKER", DEVICE_LAPTOP to "LAPTOP SPEAKER",
                        DEVICE_LOW to "A SPEAKER THAT GOES LOW", DEVICE_OTHER to "SOMETHING ELSE",
                    ),
                ),
            ),
            "pair" to obj(
                "gate" to q("pairRun"), "text" to v(PAIR_TEXT), "lockedText" to v(PAIR_LOCKED),
                "options" to options(listOf(PAIR_A to "A SOUNDS FULLER", PAIR_B to "B SOUNDS FULLER", PAIR_CANT to "I CAN'T TELL")),
            ),
            "pairs" to arr(pairs),
            "keep" to arr(keeps),
            "note" to obj("id" to q(Q_NOTE), "text" to v(NOTE_TEXT)),
        )
        return "{\"phase\":${q(PHASE)},\"storagePrefix\":${q(STORAGE_PREFIX)},\"docs\":${obj("prefix" to q(DOC_PREFIX), "overallKey" to q(OVERALL_KEY))}," +
            "\"intro\":${arr(INTRO.map { v(it) })},\n\"groups\": [\n${groupJson.joinToString(",\n")}\n],\n\"questions\":$questions}\n"
    }

    // ---- the key -----------------------------------------------------------------------------------------

    private fun bandsJson(a: Array<Double?>?): String =
        if (a == null) "null" else "{" + ArcoWarmthMeasure.BAND_LABELS.indices.joinToString(",") { "\"${ArcoWarmthMeasure.BAND_LABELS[it]}\":${num(a[it])}" } + "}"

    /** The key, for the lead and not for the page: see the class KDoc for the fields. */
    private fun keyJson(groups: List<BuiltGroup>, hashes: Map<String, String>): String {
        val rows = groups.joinToString(",\n") { g ->
            val s = g.spec
            val clips = g.clips.joinToString(",\n") { c ->
                val n = c.numbers
                val p = c.point
                val x = levelled(c.samples, g.gain)
                val file = "$VOICE_FOLDER/${c.id}.wav"
                val want = SCOUT.getValue(s.prefix)[if (c.role == Role.TODAY_COPY) Role.TODAY else c.role]
                val ratio = if (p == null || p.bellDb <= 0.0) "null" else num(p.shelfDb / p.bellDb, 4)
                val core = "\"id\":${q(c.id)},\"letter\":${c.letter?.let { q(it.toString()) } ?: "null"},\"shownInKeep\":${c.role != Role.PLAIN && c.role != Role.TODAY_COPY},\"name\":${q(c.name)},\"is\":${q(c.role.key)}," +
                    "\"shelfDb\":${num(p?.shelfDb, 4)},\"bellDb\":${num(p?.bellDb, 4)},\"ratio\":$ratio,\"file\":${q(file)},\"sha256\":${q(hashes.getValue(file))},\"samples\":${c.samples.size}"
                val nums = if (n == null) "\"rmsRiseDb\":0.000,\"loudnessRiseDb\":0.000,\"aWeightedRiseDb\":0.000,\"aWeightedRiseWholeClipDb\":0.000,\"bandsDbVsPlain\":null"
                else "\"rmsRiseDb\":${num(n.rmsRise)},\"loudnessRiseDb\":${num(n.loudRise)},\"aWeightedRiseDb\":${num(n.aRise)},\"aWeightedRiseWholeClipDb\":${num(n.aRiseWhole)},\"bandsDbVsPlain\":${bandsJson(n.bands)}"
                "    {$core,$nums,\"peakFinished\":${num(ArcoBellCandidates.peakOf(c.samples).toDouble(), 4)},\"peakAtPageLevel\":${num(ArcoBellCandidates.peakOf(x).toDouble(), 4)}," +
                    "\"loudnessAtPageLevel\":${num(ArcoLiftRig.loudnessOf(x).toDouble(), 5)},\"scoutRmsRiseDb\":${num(want?.rmsRise)},\"scoutARiseDb\":${num(want?.aRise)}}"
            }
            val map = s.keepLetters.toList().zip(s.keepOrder).joinToString(",") { "\"${it.first}\":${q(it.second.key)}" }
            "  {\"voice\":${q(CELLO.name)},\"note\":${q(s.note)},\"prefix\":${q(s.prefix)},\"label\":${q(s.label)},\"step\":${s.step},\"pageGain\":${num(g.gain.toDouble(), 6)},\"pageGainDb\":${num(dbOf(g.gain))}," +
                "\"plainLoudness\":${num(g.plainLoudness.toDouble(), 5)},\"plainPeak\":${num(g.note.plainPeak.toDouble(), 4)},\"limit\":${num(g.note.limit.toDouble(), 4)}," +
                "\"engine\":{\"asked\":${num(g.lift.asked.toDouble(), 4)},\"cap\":${num(g.lift.cap.toDouble(), 4)},\"delivered\":${num(g.lift.delivered.toDouble(), 4)},\"halvings\":${g.lift.halvings}}," +
                "\"letterIs\":{$map},\"clips\":[\n$clips\n  ]}"
        }
        val pairs = PAIRS.joinToString(",") { p ->
            val s = SPECS.first { it.prefix == p.group }
            "{\"id\":${q(p.id)},\"group\":${q(p.group)},\"comparison\":${q(p.comparison.key)},\"a\":${q(s.idOf(p.first))},\"aIs\":${q(p.first.key)},\"b\":${q(s.idOf(p.second))},\"bIs\":${q(p.second.key)}}"
        }
        val keep = SPECS.joinToString(",") { s ->
            val shown = s.keepOrder.joinToString(",") { r -> "{\"id\":${q(s.idOf(r))},\"letter\":${q(s.letterOf(r).toString())},\"is\":${q(r.key)}}" }
            "{\"id\":${q(s.keepId)},\"group\":${q(s.prefix)},\"plain\":${q(s.idOf(Role.PLAIN))},\"clips\":[$shown]}"
        }
        return "{\"note\":${q("the key to the ARCO R1j bell gate page: do not publish. The clips are NOT level on purpose: one gain per note (pageGain) is applied to every clip of the note, so a clip's level against THE PLAIN ONE is its rmsRiseDb, loudnessRiseDb (the engine's Loudness.of) and aWeightedRiseDb (the ear's weighting, steady window). bandsDbVsPlain are absolute harmonic-energy deltas in dB (80-300, 300-1k, 1k-3k, 3k-8k Hz), no loudness taken off; null where the band holds no harmonic. Every clip is a finished ARCO note at the plain's own gain times the note's page gain: THE PLAIN ONE is Arco.render at BODY 0.5, TODAY is Arco.render at BODY 1.0 (shelf = bell = the note's cap), MORE-BELL has the shelf at half the bell, BELL-ONLY no shelf, MORE-SHELF no bell, each the largest dB that keeps the finished peak at the note's limit; TODAY-COPY is the same samples as TODAY under a second file name. shelfDb and bellDb are dB per element, ratio is shelf over bell. A ruler, not audibility; nobody has listened.")}," +
            "\"phase\":${q(PHASE)},\"docPrefix\":${q(DOC_PREFIX)},\"overallKey\":${q(OVERALL_KEY)},\"pairs\":[$pairs],\"keep\":[$keep],\"groups\":[\n$rows\n]}\n"
    }
}
