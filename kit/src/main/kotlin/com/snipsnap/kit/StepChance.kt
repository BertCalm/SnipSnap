package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note

/**
 * BEAT step 4: whether a step with a chance sounds on a given pass.
 *
 * **Deterministic.** The answer is a pure function of the clip, the pad, the
 * step and the pass, mixed the way ORBIT's `OrbitClock.roll` mixes its own
 * (a polynomial over a seed, `kotlin.random.Random` for the draw, because its
 * generator is specified: the same arguments give the same answer on the JVM,
 * on the device and in a test). So a bounce, a share and a live listen of the
 * same pass agree, and a test can pin it.
 *
 * **Per step, not per hit.** The key is the step's cell ([GrooveEdit.cellOf]),
 * never the pulse: every hit of a roll sounds or falls silent together, and a
 * nudge, which only moves pulses, never re-rolls the step.
 *
 * **Who rolls.** Whoever turns a clip into sound or into notes for one pass
 * calls [resolve] with that pass's index, BEFORE the clip is tiled, renamed
 * or offset (the key includes [clipId], and a rename would re-roll it). The
 * MPC files are the exception: see [ChanceExport].
 */
object StepChance {

    /** Fixed so the same beat rolls the same way on every build. */
    private const val SEED = 0x5EED_C4A2L

    /**
     * Does the step [step] of [note] in clip [clipId] sound on [pass] at [chance] percent?
     * [clipId] is the clip's program name, which is monotonic and stable (not
     * its display name, which the person can change).
     */
    fun sounds(clipId: String, note: Int, step: Int, pass: Long, chance: Int): Boolean {
        if (chance >= Mpc3Note.ALWAYS) return true
        if (chance <= 0) return false
        var h = SEED
        h = h * 31 + clipId.hashCode()
        h = h * 31 + note
        h = h * 31 + step
        h = h * 31 + pass
        return kotlin.random.Random(h).nextInt(Mpc3Note.ALWAYS) < chance
    }

    /** [note] as [sounds] reads it, for a note of [clipId] on [pass]. */
    fun sounds(clipId: String, note: Mpc3Note, pass: Long): Boolean =
        sounds(clipId, note.note, GrooveEdit.cellOf(note.timePulses), pass, note.chance)

    /**
     * [clip] with only the notes that sound on [pass], each with its chance
     * reset to 100 (it has been rolled; rolling it again would roll twice).
     * The same clip, equal, when nothing in it has a chance under 100.
     *
     * A step's hits all carry the step's chance, but the first note of a
     * (note, cell) group decides, so a clip some other path left with
     * unequal chances in one step still sounds or falls silent as a unit.
     */
    fun resolve(clip: Mpc3Clip, pass: Long, clipId: String = clip.name): Mpc3Clip {
        if (clip.notes.none { it.chance < Mpc3Note.ALWAYS }) return clip
        val decided = HashMap<Pair<Int, Int>, Boolean>()
        val kept = clip.notes.filter { n ->
            val key = n.note to GrooveEdit.cellOf(n.timePulses)
            decided.getOrPut(key) { sounds(clipId, n, pass) }
        }.map { if (it.chance < Mpc3Note.ALWAYS) it.copy(chance = Mpc3Note.ALWAYS) else it }
        return clip.copy(notes = kept)
    }

    /** True when any note of [clip] is chancy. */
    fun hasChance(clip: Mpc3Clip): Boolean = clip.notes.any { it.chance < Mpc3Note.ALWAYS }
}

/**
 * What an MPC file (`.xtd` track, `.xpj` project) does with a chance.
 *
 * The MPC 3 has a per-note `probability` (docs/MPC3_FORMAT.md); the corpus
 * only ever shows 100, so that 50 means "half the time" is inferred and has
 * NOT been read on hardware. One switch, [MODE]:
 *
 * - [Mode.WRITE] (default): every hit is written and `probability` is the
 *   note's chance, so the MPC rolls it. Song sections are therefore not
 *   pre-resolved, and each repeat of a section is the same notes.
 * - [Mode.BAKE]: the file holds only the hits that sound, resolved here
 *   (pass 0 for a clip, per repeat for a song section), with probability
 *   100. Use this if hardware turns out not to honour `probability`.
 *
 * The WAV mixdown of a song is always resolved per repeat, in either mode.
 */
object ChanceExport {
    enum class Mode { WRITE, BAKE }

    /** The owner's answer is a one-line change here. */
    val MODE: Mode = Mode.WRITE

    /** [clip] as an MPC file should hold it under [mode] (single pass: pass 0). */
    fun forMpc(clip: Mpc3Clip, mode: Mode = MODE): Mpc3Clip =
        if (mode == Mode.BAKE) StepChance.resolve(clip, 0L) else clip
}
