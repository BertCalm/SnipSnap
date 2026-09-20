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

    /** The step editor's five lanes, fixed order per the GROOVE screen. */
    enum class Lane { KICK, SNARE, HAT_CLOSED, HAT_OPEN, PERC }

    /**
     * Lane → 1-based pad slot, the finger-drumming convention from
     * `docs/KIT_BEST_PRACTICES.md` "Pad layout" and `AutoPlace`: kick A01,
     * snare A02, closed hat A03, open hat A04. PERC takes `AutoPlace`'s
     * first preference for `DrumClass.PERC`, which is slot 12 — NOT slot 6.
     * (The brief guessed 6, but `AutoPlace.PREFERENCES` reserves 6 as
     * CLAP's first choice; handing it to PERC would collide with where a
     * clap sample actually auto-places. Verified against the real table,
     * not assumed — reality wins.)
     */
    val LANE_SLOT: Map<Lane, Int> = mapOf(
        Lane.KICK to 1,
        Lane.SNARE to 2,
        Lane.HAT_CLOSED to 3,
        Lane.HAT_OPEN to 4,
        Lane.PERC to 12,
    )

    /** Which note plays a lane's pad — [Mpc3Note.noteFor], the one map. */
    fun noteFor(lane: Lane): Int = Mpc3Note.noteFor(LANE_SLOT.getValue(lane))

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

    /** The naming convention that makes E findable among stored clips. */
    const val NAME_SUFFIX = " E"

    /**
     * How many user programs one kit can hold.
     *
     * A cap rather than no limit because the screen reaches them by
     * cycling one segment: the number of taps to get somewhere IS the
     * count, so an unbounded list is a control that gets worse the more
     * you use it. Eight is half a Pocket Operator's sixteen and well past
     * what the ARRANGE grammar can place — it is a number to raise when
     * something actually presses on it, not a law.
     */
    const val MAX_USER_PROGRAMS = 8

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
        require(index in 0 until MAX_USER_PROGRAMS) { "user program index out of range: $index" }
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
    fun progIndexOf(clip: Mpc3Clip): Int? {
        val match = E_SUFFIX.find(clip.name) ?: return null
        val digits = match.value.removePrefix(NAME_SUFFIX)
        return if (digits.isEmpty()) 0 else digits.toInt() - 1
    }

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
     * The lowest slot this kit has not filled, or null when it is full.
     * Lowest rather than next-after-the-highest so a discarded program
     * leaves a hole that the next fork reuses, instead of the numbering
     * climbing past [MAX_USER_PROGRAMS] with slots standing empty.
     */
    fun freeSlot(kitDir: File): Int? {
        val taken = loadAll(kitDir).mapNotNull { progIndexOf(it) }.toSet()
        return (0 until MAX_USER_PROGRAMS).firstOrNull { it !in taken }
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
        val e = quantized(source, progEName(baseName, index))
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
        val slot = freeSlot(kitDir) ?: return null
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
     * the hole — [freeSlot] fills it on the next fork, so your remaining
     * programs do not renumber themselves under you.
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
     * velocity; on → off drops every note at that exact (lane, step)
     * address, so a stray duplicate never survives one tap. The result is
     * always time-sorted, so repeated toggles round-trip byte-for-byte.
     */
    fun toggleStep(clip: Mpc3Clip, lane: Lane, step: Int): Mpc3Clip {
        val steps = stepsInClip(clip)
        require(step in 0 until steps) { "step out of range for a ${clip.bars}-bar clip: $step" }
        val note = noteFor(lane)
        val pulses = step * STEP_PULSES
        val without = clip.notes.filterNot { it.note == note && it.timePulses == pulses }
        val notes = if (without.size == clip.notes.size) {
            without + Mpc3Note(note, pulses, velocityFor(lane))
        } else {
            without
        }
        return clip.copy(notes = notes.sortedBy { it.timePulses })
    }

    /**
     * Wipes [bar] (0-based) across all five editor lanes — CLEAR BAR is a
     * whole-bar action, not a per-lane one, but it's scoped to the five
     * lanes the step editor actually draws: a note that rides some other
     * MIDI note number (never placed by this editor, and unreachable by any
     * of its cells) survives the wipe untouched, same as every other bar.
     */
    fun clearBar(clip: Mpc3Clip, bar: Int): Mpc3Clip {
        require(bar in 0 until clip.bars) { "bar out of range for a ${clip.bars}-bar clip: $bar" }
        val from = bar * clip.pulsesPerBar
        val until = from + clip.pulsesPerBar
        val laneNotes = Lane.entries.mapTo(HashSet()) { noteFor(it) }
        return clip.copy(notes = clip.notes.filterNot { it.timePulses in from until until && it.note in laneNotes })
    }
}
