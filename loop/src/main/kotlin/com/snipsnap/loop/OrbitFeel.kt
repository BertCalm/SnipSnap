package com.snipsnap.loop

import com.snipsnap.kit.GrooveFeel
import com.snipsnap.mpc3.Mpc3Clip
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Pocket for rings — the feel stack, reaching the side of the bridge it
 * never could.
 *
 * `GrooveVariations` and `GrooveFeel` have always worked on an
 * [com.snipsnap.mpc3.Mpc3Clip]: a note list with an absolute time on every
 * note, so a donor's lean or a seeded jitter has somewhere to land. A ring
 * had one number for the whole set — `swing` — which can only push the odd
 * 16th of a pair by one amount. A template is sixteen different amounts and
 * a humanised take is one per hit, so neither could be said in a ring's
 * vocabulary at all.
 *
 * [OrbitHit.offset] is that vocabulary, and this is what writes it. Every
 * operation here is pure: a set in, a set out, the hits' steps untouched.
 * What moves is where each hit sits *against* its step, which is the
 * difference between a pattern and a performance.
 */
object OrbitFeel {

    /**
     * [set] with [template]'s pocket laid onto every pattern ring.
     *
     * The template resolves per 16th of a 4/4 bar, so a hit takes the
     * offset of the position it falls on — which is a question about where
     * the hit lands in the bar, not which step of its ring it is. A
     * three-step ring across a bar lands on positions 0, 5 and 11, and
     * takes those three offsets — which is how the polyrhythmic rings ORBIT
     * exists for get a pocket at all.
     *
     * A position the donor never played contributes nothing, exactly as
     * `GrooveFeel.apply` leaves such a position straight. Existing offsets
     * are replaced rather than added to: a feel is a pocket, and laying a
     * second one over the first would compound two drummers into neither.
     */
    fun apply(set: OrbitSet, template: GrooveFeel.Template): OrbitSet =
        mapHits(set) { orbit, hit ->
            val offset = template.offsets[positionOf(set, orbit, hit)]
            hit.copy(offset = clamp(offset ?: 0L))
        }

    /**
     * [set] with every hit nudged off its step by up to [amount] of half a
     * 16th, from [seed].
     *
     * The same shape as `GrooveVariations.humanize`, and seeded the same
     * way for the same reason: a take that re-renders differently every
     * time is not a take, it is a slot machine. Iteration order is the
     * set's own — rings in order, hits in order — so one seed gives one
     * result for one set.
     */
    fun humanize(set: OrbitSet, amount: Float, seed: Int): OrbitSet {
        require(amount in 0f..1f) { "amount wants 0..1, got $amount" }
        val rnd = Random(seed)
        val span = Mpc3Clip.PULSES_PER_16TH / 2f * amount
        return mapHits(set) { _, hit ->
            hit.copy(offset = clamp(((rnd.nextFloat() * 2f - 1f) * span).roundToLong()))
        }
    }

    /** [set] with every hit put back on its step. */
    fun straighten(set: OrbitSet): OrbitSet = mapHits(set) { _, hit -> hit.copy(offset = 0L) }

    /**
     * Which 16th of a 4/4 bar [hit] falls on.
     *
     * From the step's place in frames rather than its index, because a
     * ring's step is only a 16th when its steps fill its laps: step 1 of a
     * three-step ring across a bar is the sixth 16th, not the second.
     */
    fun positionOf(set: OrbitSet, orbit: Orbit, hit: OrbitHit): Int {
        val sixteenth = OrbitClock.stepFrames(set).toDouble()
        if (sixteenth <= 0.0) return 0
        // The step's grid place, deliberately not `stepOffset`: that folds
        // the set's swing in, and a swung hit still belongs to its own
        // 16th. Swing displaces a hit; it does not renumber it.
        val grid = hit.step * OrbitClock.ringStepFrames(set, orbit)
        // Half-up, ties toward positive infinity — which is what
        // `GrooveFeel` does in integer pulses as `(t + s16 / 2) / s16`.
        // The two must land on the same position or one donor gives a clip
        // and a ring different pockets; the tie case is tested.
        val sixteenths = (grid / sixteenth).roundToLong()
        return Math.floorMod(sixteenths, GrooveFeel.POSITIONS.toLong()).toInt()
    }

    private fun clamp(pulses: Long): Long =
        pulses.coerceIn(-OrbitHit.MAX_OFFSET, OrbitHit.MAX_OFFSET)

    private fun mapHits(set: OrbitSet, f: (Orbit, OrbitHit) -> OrbitHit): OrbitSet =
        set.copy(
            orbits = set.orbits.map { orbit ->
                val content = orbit.content
                if (content !is PatternOrbit) orbit
                else orbit.copy(content = content.copy(hits = content.hits.map { f(orbit, it) }))
            },
        )
}
