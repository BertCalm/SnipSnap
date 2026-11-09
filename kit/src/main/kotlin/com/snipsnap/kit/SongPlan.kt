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
 */
data class PlanSection(
    val name: String,
    val clip: String,
    val repeats: Int = 1,
    val locked: Boolean = false,
    val seed: Int = 0,
    val bass: Boolean = SectionNames.bassUnder(name),
    val pullUp: Boolean = SectionNames.pullsUpInto(name),
) {
    init {
        require(name.isNotBlank()) { "a section needs a name" }
        require(clip.isNotBlank()) { "a section needs a clip" }
        require(repeats in 1..SongPlan.MAX_REPEATS) { "repeats out of range: $repeats" }
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

    fun withPullUp(i: Int, on: Boolean): SongPlan? = replaced(i) { it.copy(pullUp = on) }

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
