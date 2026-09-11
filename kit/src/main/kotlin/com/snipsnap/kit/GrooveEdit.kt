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

    /** Cells per bar in the step editor; a bar is [Mpc3Clip.PULSES_PER_BAR]. */
    const val STEPS_PER_BAR = 16

    /** One step's width in pulses — a 16th, matching the editor's grid. */
    val STEP_PULSES: Long = Mpc3Clip.PULSES_PER_16TH

    /** New steps land here; HAT_CLOSED is quieter — the handoff says so. */
    const val VELOCITY_DEFAULT = 0.9f
    const val VELOCITY_HAT_CLOSED = 0.6f

    private fun velocityFor(lane: Lane): Float =
        if (lane == Lane.HAT_CLOSED) VELOCITY_HAT_CLOSED else VELOCITY_DEFAULT

    /** The naming convention that makes E findable among stored clips. */
    const val NAME_SUFFIX = " E"

    /** PROG E's name, derived from the captured clip's own name. */
    fun progEName(baseName: String): String = "$baseName$NAME_SUFFIX"

    /** Whether [clip] is (named as) PROG E. */
    fun isProgE(clip: Mpc3Clip): Boolean = clip.name.endsWith(NAME_SUFFIX)

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
        val stepsInClip = source.bars * Mpc3Clip.PULSES_PER_BAR / grid
        val snapped = source.notes.map { n ->
            val step = ((n.timePulses + grid / 2) / grid) % stepsInClip
            n.copy(timePulses = step * grid)
        }
        return Mpc3Clip(name = name, bars = source.bars, notes = dedupeLouder(snapped))
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
        val existingE = load(kitDir)
        GrooveStore.save(kitDir, GrooveVariations.standard(base) + listOfNotNull(existingE))
        val e = fork(kitDir, base)
        return base to e
    }

    /** PROG E from a kit dir, or null when no fork has happened yet. */
    fun load(kitDir: File): Mpc3Clip? = GrooveStore.load(kitDir).firstOrNull { isProgE(it) }

    /** The A–E selector shows E only when it exists. */
    fun hasUserProgram(kitDir: File): Boolean = load(kitDir) != null

    /**
     * Persist an edited E, replacing whichever E was stored (if any).
     * Every other stored clip — the captured base, any native-export
     * variations already sitting in the sidecar — rides along untouched.
     */
    fun save(kitDir: File, e: Mpc3Clip): File {
        require(isProgE(e)) { "clip name doesn't carry PROG E's \"$NAME_SUFFIX\" suffix: ${e.name}" }
        val others = GrooveStore.load(kitDir).filterNot { isProgE(it) }
        return GrooveStore.save(kitDir, others + e)
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
    fun fork(kitDir: File, source: Mpc3Clip, replace: Boolean = false): Mpc3Clip {
        val stored = GrooveStore.load(kitDir)
        val existing = stored.firstOrNull { isProgE(it) }
        if (existing != null && !replace) return existing

        val baseName = stored.firstOrNull { !isProgE(it) }?.name ?: source.name.removeSuffix(NAME_SUFFIX)
        val e = quantized(source, progEName(baseName))
        save(kitDir, e)
        return e
    }

    /** Discards E; every other stored clip stays. False when there was none to discard. */
    fun deleteProgE(kitDir: File): Boolean {
        val stored = GrooveStore.load(kitDir)
        val kept = stored.filterNot { isProgE(it) }
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
        val stepsInClip = clip.bars * STEPS_PER_BAR
        require(step in 0 until stepsInClip) { "step out of range for a ${clip.bars}-bar clip: $step" }
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
        val from = bar * Mpc3Clip.PULSES_PER_BAR
        val until = from + Mpc3Clip.PULSES_PER_BAR
        val laneNotes = Lane.entries.mapTo(HashSet()) { noteFor(it) }
        return clip.copy(notes = clip.notes.filterNot { it.timePulses in from until until && it.note in laneNotes })
    }
}
