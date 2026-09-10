package com.snipsnap.loop

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How a ring's playhead moves against the others.
 *
 * Picture a bar of tape cut and taped end to end into a circle, then a longer
 * strip taped into a bigger circle around it. A single needle-speed drives
 * both, and the question is what "the same speed" means:
 *
 *  - [SAME_SPEED]: the needle covers the same number of 16ths per second on
 *    every ring, so a bigger ring takes longer to come round. A 16-step ring
 *    against a 20-step ring is 4/4 against 5/4 — a *polymeter* — and the two
 *    downbeats meet again only after 80 steps, five bars.
 *  - [SAME_LAP]: every ring completes one lap in the time of one
 *    [OrbitSet.lapSteps]-long bar, so a 3-step ring plays triplets against a
 *    4-step ring's quarters. That is 3-against-4 inside one bar — a
 *    *polyrhythm* — and the rings meet again every bar.
 *
 * Both are one formula ([OrbitClock.periodFrames]); the toggle only changes
 * what a ring's period is.
 */
enum class OrbitMode { SAME_SPEED, SAME_LAP }

/** One hit on a pattern ring: which step of the ring, which pad, how hard. */
data class OrbitHit(
    /** 0-based, less than the ring's [Orbit.steps]. */
    val step: Int,
    /** Pad slot in the referenced kit, 1-based, matching KitPad.slot. */
    val slot: Int,
    val velocity: Float = 1f,
) {
    init {
        require(step >= 0) { "step must not be negative: $step" }
        require(slot >= 1) { "slot is 1-based: $slot" }
        require(velocity in 0f..1f) { "velocity out of range: $velocity" }
    }
}

/** What a ring carries: pads fired on its steps, or one snip wrapped around it. */
sealed class OrbitContent

/** A step pattern against a kit: hits fire as the playhead passes their step. */
data class PatternOrbit(val kit: String, val hits: List<OrbitHit>) : OrbitContent() {
    init { require(kit.isNotBlank()) { "kit must not be blank" } }
}

/**
 * A captured snip taped end to end around the ring.
 *
 * The audio is fitted to the ring's period the way the loop grid fits a
 * loop to an interval (trim within tolerance, slice-and-retrigger beyond
 * it), so a snip on a 20-step ring wraps exactly at step 20 and comes
 * round again on the next lap. See [OrbitBank].
 */
data class SnipOrbit(val sampleFile: String) : OrbitContent() {
    init {
        require(sampleFile.isNotBlank()) { "sampleFile must not be blank" }
        require(!sampleFile.contains('/') && !sampleFile.contains('\\')) {
            "sampleFile must be a bare filename, was '$sampleFile'"
        }
    }
}

/** One ring: its circumference in steps, how it moves, and what it carries. */
data class Orbit(
    val name: String,
    /** Circumference in 16ths, 1..[MAX_STEPS]. */
    val steps: Int,
    val content: OrbitContent,
    val mode: OrbitMode = OrbitMode.SAME_SPEED,
    val engaged: Boolean = true,
    val level: Float = 1f,
    val pan: Float = 0f,
) {
    init {
        require(steps in 1..MAX_STEPS) { "steps must be 1..$MAX_STEPS: $steps" }
        require(level >= 0f) { "level must not be negative: $level" }
        require(pan in -1f..1f) { "pan out of range: $pan" }
        if (content is PatternOrbit) {
            for (hit in content.hits) {
                require(hit.step < steps) { "ring '$name' has a hit on step ${hit.step} but only $steps steps" }
            }
        }
    }

    companion object {
        const val MAX_STEPS = 64
    }
}

/**
 * The whole set of rings and the one clock they share.
 *
 * [lapSteps] is the reference bar: the circumference every [OrbitMode.SAME_LAP]
 * ring squeezes its steps into, and the unit the cycle length is reported in.
 * Sixteen — one 4/4 bar of 16ths — matches every other grid in the app.
 */
data class OrbitSet(
    val orbits: List<Orbit>,
    val bpm: Float,
    val sampleRate: Int,
    val lapSteps: Int = DEFAULT_LAP_STEPS,
) {
    init {
        require(orbits.size <= MAX_ORBITS) { "at most $MAX_ORBITS rings, got ${orbits.size}" }
        require(bpm in MIN_BPM..MAX_BPM) { "bpm out of range: $bpm" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(lapSteps in 1..Orbit.MAX_STEPS) { "lapSteps must be 1..${Orbit.MAX_STEPS}: $lapSteps" }
    }

    companion object {
        const val MAX_ORBITS = 8
        const val DEFAULT_LAP_STEPS = 16
        const val MIN_BPM = 40f
        const val MAX_BPM = 220f
    }
}

/**
 * The clock: where every ring's playhead is at a given frame.
 *
 * The whole transport is one number — the frame count since play started —
 * exactly as the loop grid's is one interval count. A ring's position is a
 * pure function of that number and its own period, so there are no per-ring
 * cursors, nothing to keep in sync, and nothing that can drift. The same
 * functions drive the audio engine, the ring drawing and the tests.
 */
object OrbitClock {

    /** Frames in one 16th at the set's tempo — the needle speed shared by every ring. */
    fun stepFrames(set: OrbitSet): Int =
        (60.0 / set.bpm / 4.0 * set.sampleRate).roundToInt().coerceAtLeast(1)

    /** Frames in one reference bar — the lap every SAME_LAP ring fits into. */
    fun lapFrames(set: OrbitSet): Long = set.lapSteps.toLong() * stepFrames(set)

    /** Frames in one lap of [orbit]: the ring's circumference in time. */
    fun periodFrames(set: OrbitSet, orbit: Orbit): Long = when (orbit.mode) {
        OrbitMode.SAME_SPEED -> orbit.steps.toLong() * stepFrames(set)
        OrbitMode.SAME_LAP -> lapFrames(set)
    }

    /** Frames from one of [orbit]'s steps to the next. Fractional for a SAME_LAP ring whose steps don't divide the lap. */
    fun ringStepFrames(set: OrbitSet, orbit: Orbit): Double =
        periodFrames(set, orbit).toDouble() / orbit.steps

    /** The frame, within a lap, on which [step] fires. */
    fun stepOffset(set: OrbitSet, orbit: Orbit, step: Int): Long =
        (step * ringStepFrames(set, orbit)).roundToLong()

    /** How far round the ring the playhead is at [frame], 0 inclusive to 1 exclusive. */
    fun phase(set: OrbitSet, orbit: Orbit, frame: Long): Double {
        val period = periodFrames(set, orbit)
        return Math.floorMod(frame, period).toDouble() / period
    }

    /** Which of [orbit]'s steps the playhead is on at [frame]. */
    fun stepAt(set: OrbitSet, orbit: Orbit, frame: Long): Int =
        floor(phase(set, orbit, frame) * orbit.steps).toInt().coerceIn(0, orbit.steps - 1)

    /**
     * How many 16ths before every ring is back on its downbeat together.
     *
     * The least common multiple of the ring lengths, where a SAME_LAP ring
     * counts as one lap long whatever its step count — it comes round every
     * bar by definition. Grows fast with coprime rings: 16, 20 and 24 meet
     * again after 240 steps (15 bars), but 16, 17 and 19 need 5,168 steps
     * (323 bars). Show this number, as the loop grid's bounce does.
     */
    fun cycleSteps(set: OrbitSet): Long =
        set.orbits.fold(set.lapSteps.toLong()) { acc, o ->
            lcm(acc, if (o.mode == OrbitMode.SAME_LAP) set.lapSteps.toLong() else o.steps.toLong())
        }

    /** [cycleSteps] in reference bars, so the readout can say "15 BARS". */
    fun cycleBars(set: OrbitSet): Double = cycleSteps(set).toDouble() / set.lapSteps

    /** Frames until every ring realigns. */
    fun cycleFrames(set: OrbitSet): Long = cycleSteps(set) * stepFrames(set)

    /**
     * The step count a snip of [frameCount] frames would wrap round at the
     * set's tempo — "tape this snip into a circle and count its 16ths". The
     * default circumference for a snip ring, so a five-beat capture lands on
     * a 20-step ring rather than being squeezed into a bar.
     */
    fun naturalSteps(set: OrbitSet, frameCount: Int): Int =
        (frameCount.toDouble() / stepFrames(set)).roundToInt().coerceIn(1, Orbit.MAX_STEPS)

    /**
     * Every (hit, frame) of [orbit] that fires in [from] inclusive to [until]
     * exclusive — what the engine schedules for one block, and what a test
     * asserts against. Frames are absolute, since the start of play.
     */
    fun firings(set: OrbitSet, orbit: Orbit, from: Long, until: Long): List<Firing> {
        val content = orbit.content as? PatternOrbit ?: return emptyList()
        if (until <= from) return emptyList()
        val period = periodFrames(set, orbit)
        val out = ArrayList<Firing>()
        for (hit in content.hits) {
            val offset = stepOffset(set, orbit, hit.step)
            // First lap whose copy of this hit lands at or after `from`.
            var lap = Math.floorDiv(from - offset, period)
            if (lap * period + offset < from) lap++
            var at = lap * period + offset
            while (at < until) {
                out.add(Firing(hit, at))
                at += period
            }
        }
        out.sortBy { it.frame }
        return out
    }

    data class Firing(val hit: OrbitHit, val frame: Long)

    /**
     * Frames since [orbit]'s [hit] last fired, at or before [frame] — what
     * the screen's strike flare fades on. Never negative: a hit the needle
     * has not reached yet this lap counts from its firing on the lap before,
     * which at frame 0 means "a whole lap ago" rather than "about to fire".
     */
    fun framesSinceFiring(set: OrbitSet, orbit: Orbit, hit: OrbitHit, frame: Long): Long {
        val period = periodFrames(set, orbit)
        val offset = stepOffset(set, orbit, hit.step)
        return Math.floorMod(frame - offset, period)
    }

    private fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun Double.roundToLong(): Long = Math.round(this)
}
