package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File

/**
 * PROG E — the user's own step-edit of a groove, the fifth program in the
 * GROOVE screen's A–E cycle. Where A–D are pure functions of the captured
 * clip ([GrooveVariations] — reseeded or recomputed live, never stored),
 * E is a fork: cloned once from whichever program was on screen when EDIT
 * STEPS was tapped, quantized to the step grid, then mutated in place by
 * the step editor. Re-entering the editor edits the SAME E; only an
 * explicit re-fork discards it. A–D never mutate and E never feeds back
 * into them.
 *
 * Storage: the settled decision is that E rides as a fifth [Mpc3Clip] in
 * the existing [GrooveStore] sidecar rather than a new file or field —
 * `groove.json` already holds a clip list (native kits ship four; this
 * makes five). E is findable among the stored clips by a name suffix, not
 * a flag, so no schema change was needed.
 */
object GrooveEdit {

    /**
     * The step editor's seven lanes, fixed order per the GROOVE screen. The
     * lanes are rows; which pad a row plays is per pattern ([setLanePad],
     * stored by [GrooveStore.saveLanePads]) and starts at [LANE_SLOT].
     */
    enum class Lane { KICK, SNARE, CLAP, HAT_CLOSED, HAT_OPEN, TOM, PERC }

    /**
     * The DEFAULT lane → 1-based pad slot. Lanes are reassignable per
     * pattern now ([setLanePad]); this is where every pattern starts and
     * what a file with no lane map gets. The promise below — each lane's
     * note lands where a same-class sample would auto-place — holds for
     * THIS map and for no other: a reassigned lane plays the pad the
     * player chose, which is the point of choosing.
     *
     * The convention itself: the finger-drumming convention from
     * `docs/KIT_BEST_PRACTICES.md` "Pad layout" and `AutoPlace`: kick A01,
     * snare A02, closed hat A03, open hat A04. CLAP and TOM take
     * `AutoPlace.PREFERENCES`'s own first choices for their classes — slot
     * 6 and slot 9 respectively — so each lane's note lands exactly where a
     * same-class sample would auto-place, no separate mapping to keep in
     * sync. PERC takes `AutoPlace`'s first preference for `DrumClass.PERC`,
     * which is slot 12 — NOT slot 6, since slot 6 is CLAP's own slot here,
     * not PERC's. (The brief guessed 6, but `AutoPlace.PREFERENCES`
     * reserves 6 as CLAP's first choice; handing it to PERC would collide
     * with where a clap sample actually auto-places. Verified against the
     * real table, not assumed — reality wins.)
     */
    val LANE_SLOT: Map<Lane, Int> = mapOf(
        Lane.KICK to 1,
        Lane.SNARE to 2,
        Lane.CLAP to 6,
        Lane.HAT_CLOSED to 3,
        Lane.HAT_OPEN to 4,
        Lane.TOM to 9,
        Lane.PERC to 12,
    )

    /** [LANE_SLOT] in [Lane] order — the lane map of a pattern nobody has reassigned. */
    val DEFAULT_LANE_PADS: List<Int> = Lane.entries.map { LANE_SLOT.getValue(it) }

    /** Pads a lane can be given: bank A, the sixteen on the screen. */
    const val PAD_COUNT = 16

    /** A lane map is one pad per lane, each of the sixteen at most once. */
    fun isValidLanePads(pads: List<Int>): Boolean =
        pads.size == Lane.entries.size && pads.all { it in 1..PAD_COUNT } && pads.toSet().size == pads.size

    /**
     * [lane] plays [slot] in [pads]. A pad that is already some other
     * lane's swaps places with it (that lane takes [lane]'s old pad), so
     * two rows never edit the same cell. Notes are not touched here: the
     * ones on the pad a lane leaves are still in the clip, now on no lane,
     * and [offLaneNotes] reads them.
     */
    fun setLanePad(pads: List<Int>, lane: Lane, slot: Int): List<Int> {
        require(isValidLanePads(pads)) { "not a lane map: $pads" }
        require(slot in 1..PAD_COUNT) { "pad out of range: $slot" }
        val old = pads[lane.ordinal]
        if (old == slot) return pads
        return pads.mapIndexed { i, s ->
            when {
                i == lane.ordinal -> slot
                s == slot -> old
                else -> s
            }
        }
    }

    /** Which note plays a lane's pad — [Mpc3Note.noteFor], the one map. */
    fun noteFor(lane: Lane, pads: List<Int> = DEFAULT_LANE_PADS): Int = Mpc3Note.noteFor(pads[lane.ordinal])

    /**
     * The notes of [clip] that no lane in [pads] plays: every pad the lane
     * map does not reach. The editor draws them as an extra row so they
     * stay visible, and nothing here ever removes one.
     */
    fun offLaneNotes(clip: Mpc3Clip, pads: List<Int> = DEFAULT_LANE_PADS): List<Mpc3Note> {
        val on = Lane.entries.mapTo(HashSet()) { noteFor(it, pads) }
        return clip.notes.filter { it.note !in on }
    }

    /** Cells per bar in the step editor, for a 4/4 bar. See [stepsPerBar]. */
    const val STEPS_PER_BAR = 16

    /**
     * Cells per bar for [clip] — sixteen for a 4/4 one, twelve for a 3/4.
     *
     * The editor's grid is a 16th either way ([STEP_PULSES]); what changes
     * is how many of them a bar holds. Asked of the clip rather than
     * assumed, because an ORBIT clip declares its own bar and a cell index
     * computed against sixteen would address the wrong beat in it.
     */
    fun stepsPerBar(clip: Mpc3Clip): Int = (clip.pulsesPerBar / STEP_PULSES).toInt()

    /**
     * Cells in the whole of [clip] — its loop length on the editor's grid,
     * and the length GROOVE's clock wraps at. `bars × STEPS_PER_BAR` is
     * this number only for a 4/4 clip; a two-bar 3/4 clip is 24, and a
     * screen that looped it at 32 played eight steps of silence on the
     * end of every pass (September wiring review, finding 2).
     */
    fun stepsInClip(clip: Mpc3Clip): Int = (clip.lengthPulses / STEP_PULSES).toInt()

    /**
     * The meter as a player reads it — `3/4`, `5/4` — for a readout. Every
     * bar this app can make is whole quarters ([Mpc3Clip.pulsesPerBar]),
     * so the denominator is always 4; ORBIT's own label agrees
     * (`OrbitSet.meterLabel`). Null for 4/4: the ordinary case carries
     * no label, the way a 4/4 `groove.json` carries no key, so the one
     * time a meter is drawn it is because it is not what a player would
     * assume.
     */
    fun meterLabel(clip: Mpc3Clip): String? =
        if (clip.pulsesPerBar == Mpc3Clip.PULSES_PER_BAR) null else "${clip.beatsPerBar}/4"

    /** One step's width in pulses — a 16th, matching the editor's grid. */
    val STEP_PULSES: Long = Mpc3Clip.PULSES_PER_16TH

    /** New steps land here; HAT_CLOSED is quieter — the handoff says so. */
    const val VELOCITY_DEFAULT = 0.9f
    const val VELOCITY_HAT_CLOSED = 0.6f

    private fun velocityFor(lane: Lane): Float =
        if (lane == Lane.HAT_CLOSED) VELOCITY_HAT_CLOSED else VELOCITY_DEFAULT

    /**
     * The four velocities a step can be set to. Chosen so what the editor
     * and the live take already write reads as a level: a new step's 0.9
     * and the loud hits of a played take are HARD, the closed hat's 0.6 is
     * MED, and a ghost note (0.32, [GrooveVariations]) is SOFT. [levelOf]
     * names any stored velocity by its nearest level, so a played-in 0.7
     * shows as MED or HARD and nothing is rewritten until the player sets it.
     */
    enum class VelocityLevel(val velocity: Float) {
        SOFT(0.35f), MED(0.6f), HARD(0.85f), ACCENT(1.0f),
    }

    /** The level nearest [velocity]; ties go to the louder. */
    fun levelOf(velocity: Float): VelocityLevel =
        VelocityLevel.entries.minWith(
            compareBy<VelocityLevel> { kotlin.math.abs(it.velocity - velocity) }.thenByDescending { it.velocity },
        )

    /**
     * The editor cell a note at [pulses] is drawn in and answers to: the
     * step whose straight position it is nearest, a tie going to the
     * earlier one. A hand-placed note is on a step's own pulse; a baked
     * swing ([bakeSwing]) pushes an "and" step late by at most half a step
     * ([Mpc3Clip.swingPush] at 75%), which is the tie, so it stays in the
     * cell it was baked from. The cell for step `s` therefore owns pulses
     * `s*240 - 119 .. s*240 + 120`.
     */
    fun cellOf(pulses: Long): Int = ((pulses + STEP_PULSES / 2 - 1) / STEP_PULSES).toInt()

    /** The velocity of the note at ([lane], [step]) in [pads], or null when there is none. */
    fun velocityAt(clip: Mpc3Clip, lane: Lane, step: Int, pads: List<Int> = DEFAULT_LANE_PADS): Float? {
        val note = noteFor(lane, pads)
        return clip.notes.filter { it.note == note && cellOf(it.timePulses) == step }.maxOfOrNull { it.velocity }
    }

    /**
     * Sets the velocity of the note at ([lane], [step]). Returns [clip]
     * unchanged when there is no note there: setting a level never adds a
     * hit (a tap does that, [toggleStep]) and never touches any other
     * note, its length or its time. [velocity] is clamped to 0.01..1.
     */
    fun setStepVelocity(clip: Mpc3Clip, lane: Lane, step: Int, velocity: Float, pads: List<Int> = DEFAULT_LANE_PADS): Mpc3Clip {
        val steps = stepsInClip(clip)
        require(step in 0 until steps) { "step out of range for a ${clip.bars}-bar clip: $step" }
        val note = noteFor(lane, pads)
        if (clip.notes.none { it.note == note && cellOf(it.timePulses) == step }) return clip
        val v = velocity.coerceIn(0.01f, 1f)
        return clip.copy(notes = clip.notes.map { if (it.note == note && cellOf(it.timePulses) == step) it.copy(velocity = v) else it })
    }


    /**
     * Bakes swing into [clip]: every note goes back to its straight 16th
     * ([cellOf], wrapping past the loop point like [quantized]) and every
     * "and" step (odd step index, the same rule as
     * [GrooveVariations.swing]) is pushed late by [Mpc3Clip.swingPush] at
     * [percent]. Velocities and lengths are untouched.
     *
     * **A second apply replaces the first, it never stacks**: the result
     * is a function of each note's cell and [percent] alone, so applying
     * the same percent twice is the same clip, applying another percent
     * re-bakes from the straight positions, and 50 straightens it. A
     * straight clip gives exactly the notes [GrooveVariations.swing]
     * gives, which is what SONG, SHARE and the .xpj read from here.
     *
     * Two notes landing on one (note, pulse) keep the louder ([dedupeLouder]).
     */
    fun bakeSwing(clip: Mpc3Clip, percent: Int): Mpc3Clip {
        val push = Mpc3Clip.swingPush(percent)
        val steps = stepsInClip(clip)
        val limit = clip.lengthPulses
        val moved = clip.notes.map { n ->
            val step = cellOf(n.timePulses) % steps
            val straight = step * STEP_PULSES
            n.copy(timePulses = if (step % 2 == 1) (straight + push).coerceAtMost(limit - 1) else straight)
        }
        return clip.copy(notes = dedupeLouder(moved))
    }

    /** The naming convention that makes E findable among stored clips. */
    const val NAME_SUFFIX = " E"

    /**
     * How many user programs one kit can hold.
     *
     * A cap rather than no limit because every program is a sequence in
     * the MPC project the kit exports (a project holds 32) and a SONG
     * names them one by one. It was eight while nothing pressed on it;
     * SONG does, and 32 is where the hardware stops anyway. A program's
     * number is never reused ([nextProgramId]), so the numbers on disk
     * can climb past this: the cap counts the programs held, not the
     * highest number given out.
     */
    const val MAX_USER_PROGRAMS = 32

    /**
     * How many programs YOURS still steps through with a tap on the live
     * segment. Past this, the same tap opens the list: the number of taps
     * to reach a program IS its place in the cycle, and nobody taps
     * thirty times.
     */
    const val CYCLE_UP_TO = 4

    /** The longest a display name can be, so it fits the sub-line and a SONG row. */
    const val NAME_MAX = 12

    /**
     * The name of user program [index] (0-based), derived from the
     * captured clip's own name.
     *
     * **Slot 0 is `"<base> E"`, exactly as it always was**, and the rest
     * are `"<base> E2"`, `"<base> E3"` and so on. That asymmetry is
     * deliberate and is the whole migration story: `ConventionTest` and
     * `Personality` both already record that `" E"` is a marker in
     * `groove.json` rather than a name anybody reads, and "not renameable
     * without migrating every kit already on disk". So it is not renamed.
     * Every kit that already carries one keeps it, and it is user program
     * one; nothing has to be rewritten on first read.
     *
     * The number the screen shows is [index] + 1, and is not this. What a
     * program is called on disk and what it is called in the hand have
     * been separate since J18 — that is why the screen could be renamed to
     * YOURS without touching a stored clip.
     */
    fun progEName(baseName: String, index: Int = 0): String {
        require(index >= 0) { "user program index out of range: $index" }
        return if (index == 0) "$baseName$NAME_SUFFIX" else "$baseName$NAME_SUFFIX${index + 1}"
    }

    /**
     * `" E"` at the end, optionally carrying a slot number.
     *
     * Anchored at the end so a clip merely containing the letter — a
     * capture named "Kit Everything" — is not claimed: after `" E"` there
     * may be digits and then nothing else.
     */
    private val E_SUFFIX = Regex(""" E\d*$""")

    /** Whether [clip] is (named as) one of the user's own programs. */
    fun isProgE(clip: Mpc3Clip): Boolean = E_SUFFIX.containsMatchIn(clip.name)

    /**
     * Which slot [clip] is, 0-based, or null when it is not a user
     * program at all. `"<base> E"` is 0 — the bare suffix predates the
     * numbering and means slot one, not slot none.
     */
    fun progIndexOf(clip: Mpc3Clip): Int? = progIndexOfName(clip.name)

    /** [progIndexOf] from the stored name alone. */
    fun progIndexOfName(clipName: String): Int? {
        val match = E_SUFFIX.find(clipName) ?: return null
        val digits = match.value.removePrefix(NAME_SUFFIX)
        return if (digits.isEmpty()) 0 else (digits.toIntOrNull() ?: return null) - 1
    }

    // ---- Display names -------------------------------------------------

    /** What a program is called until its owner names it: the number in the hand, which is [progIndexOfName] + 1. */
    fun defaultLabel(id: Int): String = "YOURS ${id + 1}"

    /** The name [clipName] shows in the hand: its own if it was given one, else [defaultLabel]. Null for a clip that is not a program of yours. */
    fun labelOf(clipName: String, names: Map<String, String>): String? =
        progIndexOfName(clipName)?.let { names[clipName] ?: defaultLabel(it) }

    /** Display names by program number — what SONG reads, because a section names `yours:<number>`. */
    fun namesById(kitDir: File): Map<Int, String> =
        GrooveStore.loadNames(kitDir).mapNotNull { (clipName, n) -> progIndexOfName(clipName)?.let { it to n } }.toMap()

    private val NAME_PUNCT = "-'.&!#+/"

    /**
     * Tidies what was typed into something storable: upper case, letters,
     * digits, spaces and a little punctuation, runs of space closed up,
     * at most [NAME_MAX]. Empty means "no name": back to the default.
     */
    fun cleanName(raw: String): String {
        val kept = raw.uppercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() || it == ' ' || it in NAME_PUNCT }
        return kept.trim().replace(Regex(" +"), " ").take(NAME_MAX).trim()
    }

    /** The verdict on a typed name. [Ok] carries the tidied name to store. */
    sealed interface NameCheck {
        data class Ok(val name: String) : NameCheck

        /** Blank: the program goes back to [defaultLabel]. */
        data object Default : NameCheck

        /** Another program of yours already shows it, or it is one of SONG's own clip words. */
        data object Taken : NameCheck
    }

    private val DEFAULT_LABEL_SHAPE = Regex("""YOURS \d+""")

    private val RESERVED_LABELS: Set<String> = ClipRef.DERIVED.mapTo(HashSet()) { it.uppercase(java.util.Locale.ROOT) }

    /** Judges [raw] as the name of the program stored as [clipName]. Pure on the kit's names; reads nothing from disk. */
    fun checkName(raw: String, clipName: String, programNames: List<String>, names: Map<String, String>): NameCheck {
        val clean = cleanName(raw)
        if (clean.isEmpty()) return NameCheck.Default
        val own = progIndexOfName(clipName)?.let { defaultLabel(it) }
        if (clean == own) return NameCheck.Default
        // SONG's own words, and the default "YOURS n" of any program but this one:
        // a name that reads as a number would point at the wrong pattern.
        if (clean in RESERVED_LABELS || DEFAULT_LABEL_SHAPE.matches(clean)) return NameCheck.Taken
        val taken = programNames.filter { it != clipName }.mapNotNull { labelOf(it, names) }
        return if (clean in taken) NameCheck.Taken else NameCheck.Ok(clean)
    }

    /**
     * Names (or un-names) [clip]. Only the `names` entry moves: the clip's
     * disk name, its notes and its number are what they were, so nothing a
     * SONG section or an export reads is touched. [NameCheck.Taken] writes nothing.
     */
    fun rename(kitDir: File, clip: Mpc3Clip, raw: String): NameCheck {
        require(isProgE(clip)) { "only a program of yours can be named: ${clip.name}" }
        val names = GrooveStore.loadNames(kitDir)
        val verdict = checkName(raw, clip.name, loadAll(kitDir).map { it.name }, names)
        when (verdict) {
            is NameCheck.Ok -> GrooveStore.saveNames(kitDir, names + (clip.name to verdict.name))
            NameCheck.Default -> if (clip.name in names) GrooveStore.saveNames(kitDir, names - clip.name)
            NameCheck.Taken -> Unit
        }
        return verdict
    }

    /**
     * The clips an `.xpj` can carry (it holds [com.snipsnap.mpc3.Mpc3ProjectWriter.MAX_SEQUENCES]
     * sequences), in the order they should be cut: the captured base,
     * then every program of yours in number order, then the derived
     * variations. A plain `take` in stored order puts yours last, so with
     * a full kit the cap would drop your own patterns before it dropped
     * a derived SWING.
     */
    fun forSequences(stored: List<Mpc3Clip>, max: Int): List<Mpc3Clip> {
        val yours = stored.filter { isProgE(it) }.sortedBy { progIndexOf(it) ?: 0 }
        val rest = stored.filter { !isProgE(it) }
        return (rest.take(1) + yours + rest.drop(1)).take(max)
    }

    /** What [forSequences] left behind: the labels of the stored clips that did not make [kept], programs of yours by their display name. */
    fun leftOut(stored: List<Mpc3Clip>, kept: List<Mpc3Clip>, names: Map<String, String>): List<String> {
        val keptNames = kept.mapTo(HashSet()) { it.name }
        return stored.filter { it.name !in keptNames }.map { labelOf(it.name, names) ?: it.name }
    }

    /** The export line for [labels] left out of a project. */
    fun leftOutMessage(labels: List<String>): String =
        "a project holds ${com.snipsnap.mpc3.Mpc3ProjectWriter.MAX_SEQUENCES} sequences — left out: ${labels.joinToString(", ")}"

    /**
     * Clone [source]'s notes, snapped to the 16th grid — the fork's core
     * transform, pure and file-free so it's independently testable.
     * Snapping the time is what drops the source's feel (swing push, the
     * FEEL axis's lean, humanize jitter) by construction — there's no
     * separate offset field to strip.
     *
     * These clips loop, so a note that rounds past the last step doesn't
     * clamp to the tail (that would produce an off-grid ghost no step
     * address can reach — clamping `limit-1` in a 240-pulse grid is never
     * itself a multiple of 240). It WRAPS to the downbeat of the next
     * pass: snap to the step INDEX first, then take that index modulo the
     * clip's step count. A capture's last 120 pulses, or a swing push at
     * the high end (percent 75 can shove a step-31 hit straight past the
     * loop point), both land here.
     *
     * Wrapping — or even plain snapping, for two off-grid hits a half-step
     * apart — can put two source notes on the same (note, step) address.
     * One note per address survives: the louder one.
     */
    fun quantized(source: Mpc3Clip, name: String): Mpc3Clip {
        val grid = STEP_PULSES
        val stepsInClip = source.lengthPulses / grid
        val snapped = source.notes.map { n ->
            val step = ((n.timePulses + grid / 2) / grid) % stepsInClip
            n.copy(timePulses = step * grid)
        }
        return Mpc3Clip(name = name, bars = source.bars, notes = dedupeLouder(snapped), pulsesPerBar = source.pulsesPerBar)
    }

    /**
     * One note per (note, timePulses) address survives: the louder one,
     * time-sorted. The codebase's single collision rule — [quantized]'s own
     * fork collisions (two off-grid hits snapping onto the same step) and
     * `LiveRecord`'s overdub collisions (two takes landing on the exact same
     * pulse) both resolve here, not by a second copy of the rule.
     */
    fun dedupeLouder(notes: List<Mpc3Note>): List<Mpc3Note> = notes
        .groupBy { it.note to it.timePulses }
        .values
        .map { collision -> collision.maxBy { it.velocity } }
        .sortedBy { it.timePulses }

    /**
     * GROOVE's STEPS with nothing recorded: an empty one-bar base named
     * after the kit, stored with its standard variations the way a landed
     * take is ([LiveRecord.land]), so every path that reads a base finds
     * one — and an empty E forked from it, so the step editor opens on a
     * blank bar. An E already stored (a take undone back to nothing) is
     * kept. Returns the base and the E.
     */
    fun startEmpty(kitDir: File, kitName: String, bars: Int = 1): Pair<Mpc3Clip, Mpc3Clip> {
        require(bars >= 1) { "bars must be positive: $bars" }
        val base = Mpc3Clip(name = kitName.ifBlank { "Groove" }, bars = bars, notes = emptyList())
        // Every user program rides along, not just the first: re-saving
        // the standard variations rewrites the whole sidecar, so anything
        // left out of this list is deleted.
        val existing = loadAll(kitDir)
        GrooveStore.save(kitDir, GrooveVariations.standard(base) + existing)
        val e = fork(kitDir, base)
        return base to e
    }

    /**
     * Every user program this kit holds, in slot order — the list the
     * screen cycles and [Arranger] picks from. Empty when no fork has
     * happened yet.
     *
     * Sorted by [progIndexOf] rather than by the order they sit in the
     * sidecar: a caller that says "your second program" has to mean the
     * same clip whatever order the last save happened to leave.
     */
    fun loadAll(kitDir: File): List<Mpc3Clip> =
        GrooveStore.load(kitDir).filter { isProgE(it) }.sortedBy { progIndexOf(it) ?: 0 }

    /** The first user program, or null when no fork has happened yet. */
    fun load(kitDir: File): Mpc3Clip? = loadAll(kitDir).firstOrNull()

    /** The selector shows YOURS only when at least one exists. */
    fun hasUserProgram(kitDir: File): Boolean = loadAll(kitDir).isNotEmpty()

    /**
     * Persist an edited user program, replacing **the one of the same
     * name** and no other. Every other stored clip — the captured base,
     * any native-export variations already sitting in the sidecar, and
     * the user's other programs — rides along untouched.
     *
     * By name, not by `isProgE`: that predicate used to identify exactly
     * one clip, so filtering all of them out and appending was the same
     * thing. With more than one it is not, and saving an edit to your
     * second program would have deleted your first.
     */
    fun save(kitDir: File, e: Mpc3Clip): File {
        require(isProgE(e)) { "clip name doesn't carry a user program's \"$NAME_SUFFIX\" suffix: ${e.name}" }
        val others = GrooveStore.load(kitDir).filterNot { it.name == e.name }
        return GrooveStore.save(kitDir, others + e)
    }

    /**
     * The program number a NEW program takes, or null when the kit already
     * holds [MAX_USER_PROGRAMS].
     *
     * One past the highest number this kit has ever given out
     * ([GrooveStore.NEXT_PROG_KEY], or the highest it holds, whichever is
     * more), never the lowest hole. A discarded program's number stays
     * retired: a SONG section points at `yours:<number>`, and a number
     * handed to a different pattern would make that section quietly play
     * something nobody chose.
     */
    fun nextProgramId(kitDir: File): Int? {
        val held = loadAll(kitDir)
        if (held.size >= MAX_USER_PROGRAMS) return null
        val highest = (held.mapNotNull { progIndexOf(it) }.maxOrNull() ?: -1) + 1
        return maxOf(GrooveStore.loadNextProg(kitDir), highest)
    }

    /**
     * Fork [source] (any of A–D as computed by [GrooveVariations], or an
     * existing E) into PROG E and persist it. When E already exists this
     * is a no-op UNLESS [replace] is asked for — re-entering the editor
     * edits the same E; only an explicit re-fork discards it.
     *
     * E's name always derives from the kit's captured base clip (the
     * first non-E clip already in the sidecar), never from [source]'s own
     * name — so forking from B ("... Swing 60") or C ("... Half") still
     * yields the same stable "<base> E", no matter which program was on
     * screen when EDIT STEPS was tapped.
     */
    fun fork(kitDir: File, source: Mpc3Clip, replace: Boolean = false, index: Int = 0): Mpc3Clip {
        val stored = GrooveStore.load(kitDir)
        val existing = stored.firstOrNull { isProgE(it) && progIndexOf(it) == index }
        if (existing != null && !replace) return existing

        val baseName = baseNameFor(stored, source)
        // A number this kit has already given out and since lost is not
        // handed out again: asking for it makes a NEW program. Only a number
        // past the high-water mark (or a kit that never kept one) is taken
        // as asked.
        val slot = if (existing != null || index >= GrooveStore.loadNextProg(kitDir)) index else nextProgramId(kitDir) ?: index
        val e = quantized(source, progEName(baseName, slot))
        // REPLACE starts the program over, lanes included: a fresh take's
        // kick is on the default kick pad, and a lane map left over from
        // the program it replaced would draw it on a row nobody asked for.
        GrooveStore.loadLanePads(kitDir).let { m -> if (e.name in m) GrooveStore.saveLanePads(kitDir, m - e.name) }
        save(kitDir, e)
        return e
    }

    /**
     * Fork [source] into the next free slot, leaving every program
     * already stored alone — the cycler's "and another one". Null when
     * the kit already holds [MAX_USER_PROGRAMS].
     *
     * Separate from [fork] rather than a flag on it because the two
     * answer different questions. [fork] is EDIT STEPS: "let me at the
     * one I am looking at", and its early return handing back an
     * existing program is the feature. This one is "give me a new one",
     * where handing back an existing program would be the bug.
     */
    fun forkNew(kitDir: File, source: Mpc3Clip): Mpc3Clip? {
        val slot = nextProgramId(kitDir) ?: return null
        val stored = GrooveStore.load(kitDir)
        val e = quantized(source, progEName(baseNameFor(stored, source), slot))
        save(kitDir, e)
        return e
    }

    /**
     * The captured clip's name, which every user program is named after
     * however many there are and whichever one was forked from — so
     * forking from SWING, or from your own third program, still yields a
     * name derived from the take.
     */
    private fun baseNameFor(stored: List<Mpc3Clip>, source: Mpc3Clip): String =
        stored.firstOrNull { !isProgE(it) }?.name
            ?: source.name.replace(E_SUFFIX, "")

    /** Discards every user program; every other stored clip stays. False when there was none to discard. */
    fun deleteProgE(kitDir: File): Boolean = deleteProgE(kitDir) { true }

    /**
     * Discards the user programs [which] selects, by slot index. Every
     * other stored clip stays, and the numbering is not closed up behind
     * the hole, and nothing fills it later ([nextProgramId]): your remaining
     * programs do not renumber themselves under you, and a SONG section that
     * named the deleted one cannot find a stranger under its number.
     */
    fun deleteProgE(kitDir: File, which: (Int) -> Boolean): Boolean {
        val stored = GrooveStore.load(kitDir)
        val kept = stored.filterNot { isProgE(it) && which(progIndexOf(it) ?: 0) }
        if (kept.size == stored.size) return false
        if (kept.isEmpty()) GrooveStore.delete(kitDir) else GrooveStore.save(kitDir, kept)
        return true
    }

    /**
     * Toggle a step on/off for (lane, step); returns a NEW clip, matching
     * the codebase's immutable style. Off → on lands a note at the lane's
     * velocity; on → off drops every note in that (lane, step)
     * cell (a swung note counts as in its own cell, [cellOf]), so a stray
     * duplicate never survives one tap. A new step lands on the straight
     * pulse, swung or not: [bakeSwing] again to swing it. The result is
     * always time-sorted, so repeated toggles round-trip byte-for-byte.
     */
    fun toggleStep(clip: Mpc3Clip, lane: Lane, step: Int, pads: List<Int> = DEFAULT_LANE_PADS): Mpc3Clip {
        val steps = stepsInClip(clip)
        require(step in 0 until steps) { "step out of range for a ${clip.bars}-bar clip: $step" }
        val note = noteFor(lane, pads)
        val pulses = step * STEP_PULSES
        val without = clip.notes.filterNot { it.note == note && cellOf(it.timePulses) == step }
        val notes = if (without.size == clip.notes.size) {
            without + Mpc3Note(note, pulses, velocityFor(lane))
        } else {
            without
        }
        return clip.copy(notes = notes.sortedBy { it.timePulses })
    }

    /**
     * Wipes [bar] (0-based) across all seven editor lanes (the pads [pads] gives them) — CLEAR BAR is a
     * whole-bar action, not a per-lane one, but it's scoped to the seven
     * lanes the step editor actually draws: a note that rides some other
     * MIDI note number (a pad no lane plays, whether it was never given one or
     * its lane was reassigned, drawn on the OTHER row and not editable there) survives the wipe untouched, same as every other bar.
     */
    fun clearBar(clip: Mpc3Clip, bar: Int, pads: List<Int> = DEFAULT_LANE_PADS): Mpc3Clip {
        require(bar in 0 until clip.bars) { "bar out of range for a ${clip.bars}-bar clip: $bar" }
        val from = bar * clip.pulsesPerBar
        val until = from + clip.pulsesPerBar
        val laneNotes = Lane.entries.mapTo(HashSet()) { noteFor(it, pads) }
        return clip.copy(notes = clip.notes.filterNot { it.timePulses in from until until && it.note in laneNotes })
    }
}
