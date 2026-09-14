package com.snipsnap.loop

/**
 * What a long-press on a square writes.
 *
 * The grid has two gestures and four things a hit can now say, so the
 * gesture is the same and the *brush* is what changes — one chip above
 * the strip, cycled like every other chip on the screen, saying what the
 * next long-press will do. The alternative was a per-hit inspector, which
 * means a modal over the grid the player is trying to hear.
 *
 * [WEIGHT] is first and is what a long-press did before any of this, so
 * the screen behaves exactly as it always has until the chip is touched.
 */
enum class OrbitBrush(
    /** The chip's word for it. */
    val label: String,
) {
    WEIGHT("WEIGHT"),
    CHANCE("CHANCE"),
    EVERY("EVERY"),
    RATCHET("RATCHET");

    /** The brush after this one, wrapping: what one tap on the chip does. */
    val next: OrbitBrush get() = entries[(ordinal + 1) % entries.size]

    /**
     * What a screen reader says a long-press will do.
     *
     * A square's long-press used to be labelled "ACCENT" whatever it did,
     * which was true when weight was all it wrote and became a lie the
     * moment the chip could change it — TalkBack announced ACCENT while
     * the press was about to move a chance. The label follows the brush
     * for the same reason the chip exists.
     */
    val action: String get() = "CHANGE $label"
}

/**
 * The ladders a long-press climbs, and the words a square says.
 *
 * Ladders rather than a free number for the same reason the ring's steps
 * and the set's swing are ladders: a rung per tap on a phone, and every
 * rung a value a player would actually choose. The numbers in between are
 * reachable through the file, which is where a set that wants 37% belongs.
 */
object OrbitBrushes {

    /** Certain, then three-quarters, half, a quarter, and round again. */
    val CHANCES: List<Int> = listOf(OrbitHit.ALWAYS, 75, 50, 25)

    /**
     * The conditionals worth a rung, as (everyLaps, onLap).
     *
     * Every lap, then both halves of a two, then the first and last lap of
     * a four — the two ends of the bar-of-four figure, which is what a
     * conditional is nearly always reached for: the hit that only lands on
     * the way in, and the one that only lands on the turnaround.
     */
    val CONDITIONS: List<Pair<Int, Int>> = listOf(1 to 0, 2 to 0, 2 to 1, 4 to 0, 4 to 3)

    /** One strike, then two, three, four, and round again. */
    val RATCHETS: List<Int> = listOf(OrbitHit.ONCE, 2, 3, 4)

    /**
     * [hit] with [brush]'s property moved one rung on.
     *
     * A value off the ladder — from a file, or from a rung this build no
     * longer offers — climbs to the first rung rather than refusing or
     * sticking, so a set is never uneditable on the screen that made it.
     */
    fun cycle(hit: OrbitHit, brush: OrbitBrush): OrbitHit = when (brush) {
        OrbitBrush.WEIGHT -> hit.copy(velocity = OrbitPatterns.nextVelocity(hit.velocity))
        OrbitBrush.CHANCE -> hit.copy(chance = CHANCES.after(hit.chance))
        OrbitBrush.EVERY -> CONDITIONS.after(hit.everyLaps to hit.onLap)
            .let { (every, on) -> hit.copy(everyLaps = every, onLap = on) }
        OrbitBrush.RATCHET -> hit.copy(ratchet = RATCHETS.after(hit.ratchet))
    }

    /**
     * [hit] carrying whatever [brush] names, taken from [from].
     *
     * A square can hold more than one hit — an import puts a pickup and a
     * downbeat on the same step of the same pad — and the square is drawn
     * at the loudest of them. So a long-press cycles the loudest and every
     * hit under it adopts the answer: the cell shows one thing and one
     * thing is what changes.
     */
    fun adopt(hit: OrbitHit, from: OrbitHit, brush: OrbitBrush): OrbitHit = when (brush) {
        OrbitBrush.WEIGHT -> hit.copy(velocity = from.velocity)
        OrbitBrush.CHANCE -> hit.copy(chance = from.chance)
        OrbitBrush.EVERY -> hit.copy(everyLaps = from.everyLaps, onLap = from.onLap)
        OrbitBrush.RATCHET -> hit.copy(ratchet = from.ratchet)
    }

    private fun <T> List<T>.after(value: T): T {
        val at = indexOf(value)
        return if (at < 0) first() else this[(at + 1) % size]
    }

    /**
     * What [hit] says beyond its weight — "50%", "2:4", "×3", or the
     * several of them together — or empty where it says nothing.
     *
     * The square is one cell of a grid and cannot draw four numbers, so
     * this is what the cell's accessibility state and the strip's readout
     * both say. A hit with nothing to add reads exactly as it did before,
     * which is most hits.
     */
    fun mark(hit: OrbitHit): String = buildList {
        if (hit.chance != OrbitHit.ALWAYS) add("${hit.chance}%")
        if (hit.everyLaps != OrbitHit.EVERY_LAP) add("${hit.onLap + 1}:${hit.everyLaps}")
        if (hit.ratcheted) add("×${hit.ratchet}")
    }.joinToString(" ")
}
