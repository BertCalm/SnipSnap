package com.snipsnap.kit

/**
 * The names a song section can point its clip at. A section keeps a NAME,
 * not the notes: the clip is derived again each time the song opens, so an
 * edit made on BEAT flows into the song, and a name that no longer
 * resolves (a program deleted since) is refused by name instead of
 * playing something nobody chose.
 *
 * The six derived names are the ones `Arranger` has always laid out;
 * `yours:<id>` is one of the user's own programs, `<id>` being the
 * program's own number ([GrooveEdit.progIndexOf]), which stays put when a
 * different program is deleted.
 */
object ClipRef {
    const val CAPTURED = "captured"
    const val TIGHT = "tight"
    const val HALF = "half"
    const val SPARSE = "sparse"
    const val FILL = "fill"
    const val GHOSTED = "ghosted"
    private const val YOURS_PREFIX = "yours:"

    /** The derived names, in the order a picker lists them. */
    val DERIVED = listOf(CAPTURED, TIGHT, HALF, SPARSE, GHOSTED, FILL)

    fun yours(id: Int): String = "$YOURS_PREFIX$id"

    /** The program number a `yours:<id>` names, or null for any other reference. */
    fun yoursId(ref: String): Int? =
        if (ref.startsWith(YOURS_PREFIX)) ref.removePrefix(YOURS_PREFIX).toIntOrNull()?.takeIf { it >= 0 } else null
}

/**
 * What a section's name meant before a section kept its own [PlanSection.bass]
 * and [PlanSection.pullUp]: the arranger's draft names them this way, and a
 * song kept before those fields existed is read through it.
 */
object SectionNames {
    const val THE_TURN = "the turn"
    private val BASS_UNDER = setOf("theme", "variation", "reprise")

    fun bassUnder(name: String): Boolean = name in BASS_UNDER

    fun pullsUpInto(name: String): Boolean = name == THE_TURN
}

/**
 * One section of a kept song: its [name], the clip it plays by name
 * ([clip], see [ClipRef]), how many times it plays through ([repeats]),
 * whether REROLL leaves it alone ([locked]), and the [seed] its clip was
 * drawn with. The seed is per section so a locked section keeps the take
 * it was locked on while the rest are redrawn.
 *
 * [bass] is whether the kit's Answer bassline plays under this section,
 * and [pullUp] whether the record is pulled up over the last beat before
 * it (the spinback into the turn). Both belong to the section, not to its
 * name: a copy, a move or a different name keeps them. Left out, they are
 * what the name has always meant ([SectionNames]), so a song kept before
 * they existed sounds as it did.
 *
 * [drop] is the other way round: it sits on the section that goes quiet,
 * and empties that section's OWN last bar down to the kick (or the bed,
 * when [bass] is on). A pull-up sits on the section pulled INTO. The
 * boundary between sections i and i+1 is rewritten by `sections[i].drop`
 * and by `sections[i+1].pullUp`, so a plan edit turns one off when it
 * turns the other on ([SongPlan.withDrop], [SongPlan.withPullUp]). Off
 * unless asked for.
 *
 * [mutes] are the lanes this section leaves out: a [padLane] key for each
 * drum pad whose hits are not played in it, and [BED_LANE] for the bass
 * bed. The key is the pad's SLOT, not its name or its class: a kit can
 * have two kick pads or rename one, and a swapped clip changes which
 * lanes a section plays but not what a slot is. A mute names a lane the
 * section may not play (any more); it then does nothing. Empty unless
 * asked for, and left out of the file when empty.
 */
data class PlanSection(
    val name: String,
    val clip: String,
    val repeats: Int = 1,
    val locked: Boolean = false,
    val seed: Int = 0,
    val bass: Boolean = SectionNames.bassUnder(name),
    val pullUp: Boolean = SectionNames.pullsUpInto(name),
    val drop: Boolean = false,
    val mutes: Set<String> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "a section needs a name" }
        require(clip.isNotBlank()) { "a section needs a clip" }
        require(repeats in 1..SongPlan.MAX_REPEATS) { "repeats out of range: $repeats" }
    }

    companion object {
        /** The mute key of the bass bed. */
        const val BED_LANE = "bed"

        /** The mute key of the drum pad in 1-based [slot] (`Mpc3Note.slotFor` of its notes). */
        fun padLane(slot: Int): String = "pad:$slot"
    }
}

/**
 * The song a person keeps for one kit: an ordered list of sections with
 * repeat counts, plus the [seed] of the last REROLL. Pure data and pure
 * edits, each returning a new plan (or null when the edit does not apply:
 * a move off the end, a delete of the last section, a section past
 * [MAX_SECTIONS]); [SongPlanStore] keeps it on disk.
 */
data class SongPlan(val seed: Int, val sections: List<PlanSection>) {
    init {
        require(sections.isNotEmpty()) { "a song has at least one section" }
        require(sections.size <= MAX_SECTIONS) { "a song has at most $MAX_SECTIONS sections, got ${sections.size}" }
    }

    /** Section [i] moved by [delta] places, or null when that is off the list. */
    fun moved(i: Int, delta: Int): SongPlan? {
        val to = i + delta
        if (i !in sections.indices || to !in sections.indices || delta == 0) return null
        val next = sections.toMutableList()
        next.add(to, next.removeAt(i))
        return copy(sections = next)
    }

    fun withRepeats(i: Int, repeats: Int): SongPlan? = replaced(i) { it.copy(repeats = repeats.coerceIn(1, MAX_REPEATS)) }

    fun withClip(i: Int, ref: String): SongPlan? = replaced(i) { it.copy(clip = ref) }

    fun withLock(i: Int, locked: Boolean): SongPlan? = replaced(i) { it.copy(locked = locked) }

    /** A copy of section [i] straight after it, or null at [MAX_SECTIONS]. A copy is unlocked: it is the one to vary. */
    fun duplicated(i: Int): SongPlan? {
        val s = sections.getOrNull(i) ?: return null
        if (sections.size >= MAX_SECTIONS) return null
        return copy(sections = sections.toMutableList().also { it.add(i + 1, s.copy(locked = false)) })
    }

    /** Without section [i], or null for the last one left. */
    fun without(i: Int): SongPlan? {
        if (i !in sections.indices || sections.size == 1) return null
        return copy(sections = sections.filterIndexed { at, _ -> at != i })
    }

    /** A new section at the end playing [ref] once, or null at [MAX_SECTIONS]. It carries the bass (a body section, unless the person turns it off) and no pull-up. */
    fun added(ref: String): SongPlan? {
        if (sections.size >= MAX_SECTIONS) return null
        return copy(sections = sections + PlanSection("section ${sections.size + 1}", ref, seed = seed, bass = true, pullUp = false))
    }

    fun withBass(i: Int, on: Boolean): SongPlan? = replaced(i) { it.copy(bass = on) }

    /** Turning a pull-up on turns off the DROP of the section before it: they would both rewrite the same boundary. */
    fun withPullUp(i: Int, on: Boolean): SongPlan? {
        val next = replaced(i) { it.copy(pullUp = on) } ?: return null
        if (!on || i == 0 || !sections[i - 1].drop) return next
        return next.copy(sections = next.sections.toMutableList().also { it[i - 1] = it[i - 1].copy(drop = false) })
    }

    /** Turning a DROP on turns off the pull-up of the section after it, the other rewrite of the same boundary. The last section has none after it. */
    fun withDrop(i: Int, on: Boolean): SongPlan? {
        val next = replaced(i) { it.copy(drop = on) } ?: return null
        if (!on || !(sections.getOrNull(i + 1)?.pullUp ?: false)) return next
        return next.copy(sections = next.sections.toMutableList().also { it[i + 1] = it[i + 1].copy(pullUp = false) })
    }

    /** Lane [lane] of section [i] left out ([on]) or played again; see [PlanSection.mutes]. */
    fun withMute(i: Int, lane: String, on: Boolean): SongPlan? {
        require(lane.isNotBlank()) { "a mute names a lane" }
        return replaced(i) { it.copy(mutes = if (on) it.mutes + lane else it.mutes - lane) }
    }

    /** Every unlocked section redrawn with [newSeed]; locked ones keep the seed they have. */
    fun rerolled(newSeed: Int): SongPlan =
        copy(seed = newSeed, sections = sections.map { if (it.locked) it else it.copy(seed = newSeed) })

    private fun replaced(i: Int, change: (PlanSection) -> PlanSection): SongPlan? {
        val s = sections.getOrNull(i) ?: return null
        return copy(sections = sections.toMutableList().also { it[i] = change(s) })
    }

    companion object {
        /** A section plays through at most this many times (the writer's 64-bar sequence is the other ceiling, see `Arranger.maxRepeats`). */
        const val MAX_REPEATS = 16

        /** The MPC project holds 32 sequences and every section is one. */
        const val MAX_SECTIONS = 32
    }
}
