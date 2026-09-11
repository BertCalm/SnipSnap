package com.snipsnap.loop

import com.snipsnap.mpc3.Mpc3Clip

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** One hit on a pattern ring: which step of the ring, which pad, how hard. */
data class OrbitHit(
    /** 0-based, less than the ring's [Orbit.steps]. */
    val step: Int,
    /** Pad slot in the referenced kit, 1-based, matching KitPad.slot. */
    val slot: Int,
    val velocity: Float = 1f,
    /**
     * Pulses this hit lands late (+) or early (−) of its step.
     *
     * Where a pocket lives. The set's `swing` is one number for the whole
     * set and can only push the odd 16th of a pair; a feel template is
     * sixteen different numbers, a humanised take is one per hit, and
     * neither has anywhere to go without this. In pulses rather than
     * frames because a set outlives the device it was made on: frames
     * would shift the whole pocket when the same file opened at a
     * different sample rate.
     */
    val offset: Long = 0L,
) {
    init {
        require(step >= 0) { "step must not be negative: $step" }
        require(slot >= 1) { "slot is 1-based: $slot" }
        require(velocity in 0f..1f) { "velocity out of range: $velocity" }
        require(offset in -MAX_OFFSET..MAX_OFFSET) { "offset out of range: $offset" }
    }

    companion object {
        /**
         * A 16th either way. Past that a hit reads as belonging to a
         * different step, and the step is what the ring draws - a pocket
         * that deep is a hit somewhere else, entered somewhere else.
         */
        const val MAX_OFFSET: Long = Mpc3Clip.PULSES_PER_16TH
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

/**
 * How many laps of the set's bar one turn of a ring takes — or none, when
 * the ring is as long as its own steps.
 *
 * A ring alone is just a circle; "one bar of 8/4" and "two bars of 4/4"
 * are the same circle. The difference appears only against the shared
 * lap, so a span is measured in laps: a 3-step ring spanning [TWO] is
 * three hits across two bars. [FREE] is the polymeter case (its own steps
 * in 16ths); the rest are polyrhythms against the bar.
 */
enum class OrbitSpan(
    /** Laps per turn, or null for a free ring. */
    val laps: Double?,
    /** The chip's word for it. */
    val label: String,
) {
    FREE(null, "FREE"),
    HALF(0.5, "½ BAR"),
    ONE(1.0, "1 BAR"),
    TWO(2.0, "2 BARS"),
    FOUR(4.0, "4 BARS");

    /** The span after this one, wrapping: what one tap on the chip does. */
    val next: OrbitSpan get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The stored name back to a span; anything unknown is [FREE], never a refusal. */
        fun fromName(name: String?): OrbitSpan = entries.firstOrNull { it.name == name } ?: FREE
    }
}

/**
 * One ring: how many steps round it, how long it is, what it plays.
 *
 * Picture a bar of tape cut and taped end to end into a circle, then a
 * longer strip taped into a bigger circle around it, one needle-speed
 * driving both. A ring's *length* is its circumference in time:
 *
 *  - A free ring ([span] [OrbitSpan.FREE]) is as long as its steps: 16
 *    steps is a bar, 20 steps is five beats. The needle covers the same
 *    16ths per second on every free ring, so a bigger ring takes longer to
 *    come round — 16 against 20 is 4/4 against 5/4, a *polymeter*, meeting
 *    again only after 80 steps, five bars.
 *  - A spanned ring is a set number of laps long (½, 1, 2 or 4 bars)
 *    whatever its steps, so a 3-step ring spanning one bar plays a triplet
 *    against a 4-step ring's quarters — 3-against-4 inside one bar, a
 *    *polyrhythm* — and a 3-step ring spanning two bars is three hits
 *    across two bars.
 *
 * All of it is one formula ([OrbitClock.periodFrames]); the span only
 * changes what a ring's period is.
 *
 * A pattern ring also has a *voice*, [voice]: the pads it may play, in the
 * order they are shown when the ring is unrolled to edit it. A drum ring's
 * voice is one pad. A bass ring's voice is the kit's tonal pads, low to
 * high, and every hit names which of them it plays — a melody is one ring,
 * not one ring per note. Leave [voice] empty and it is the distinct pads
 * the hits already name ([pads]); a pattern ring always plays at least one.
 */
data class Orbit(
    val name: String,
    /** Circumference in 16ths, 1..[MAX_STEPS]. */
    val steps: Int,
    val content: OrbitContent,
    /** How many laps one turn takes; [OrbitSpan.FREE] means as long as its steps. */
    val span: OrbitSpan = OrbitSpan.FREE,
    /** Pad slots this ring may play, in unrolled-strip order. Empty = the pads its hits name. */
    val voice: List<Int> = emptyList(),
    val engaged: Boolean = true,
    val level: Float = 1f,
    val pan: Float = 0f,
) {
    init {
        require(steps in 1..MAX_STEPS) { "steps must be 1..$MAX_STEPS: $steps" }
        require(level >= 0f) { "level must not be negative: $level" }
        require(pan in -1f..1f) { "pan out of range: $pan" }
        require(voice.size == voice.toSet().size) { "ring '$name' names a pad twice in its voice: $voice" }
        for (slot in voice) require(slot >= 1) { "ring '$name' voice slot is 1-based: $slot" }
        when (content) {
            is PatternOrbit -> for (hit in content.hits) {
                require(hit.step < steps) { "ring '$name' has a hit on step ${hit.step} but only $steps steps" }
                require(voice.isEmpty() || hit.slot in voice) {
                    "ring '$name' has a hit on pad ${hit.slot}, which is not in its voice $voice"
                }
            }
            is SnipOrbit -> require(voice.isEmpty()) { "ring '$name' is a snip ring and has no voice" }
        }
    }

    /**
     * The pads this ring plays, in strip order: [voice] when set, else the
     * distinct pads its hits name in slot order, else pad 1 for an empty
     * pattern ring so there is always a row to put a hit on. Empty only for
     * a snip ring.
     */
    val pads: List<Int>
        get() = when {
            voice.isNotEmpty() -> voice
            content is PatternOrbit -> content.hits.map { it.slot }.distinct().sorted().ifEmpty { listOf(1) }
            else -> emptyList()
        }

    companion object {
        const val MAX_STEPS = 64
    }
}

/**
 * The whole set of rings and the one clock they share.
 *
 * [lapSteps] is the reference bar: the lap every spanned ring is measured
 * in, and the unit the cycle length is reported in. Sixteen — one 4/4 bar
 * of 16ths — matches every other grid in the app; 12 is 3/4, 20 is 5/4.
 * Change the bar and every spanned ring re-periods together, which is the
 * "4/4 × 2 = 8/4" observation: a change to the reference, not to a ring.
 * Free rings do not care.
 */
data class OrbitSet(
    val orbits: List<Orbit>,
    val bpm: Float,
    val sampleRate: Int,
    val lapSteps: Int = DEFAULT_LAP_STEPS,
    /**
     * Swing as the MPC counts it, [STRAIGHT_SWING] to [MAX_SWING]: the share
     * of each pair of 16ths the first one takes. 50 is straight; 66 is a
     * triplet feel; 75 pushes every offbeat 16th to the dotted-8th. It
     * moves the odd steps of any ring whose step is a 16th ([OrbitClock.swingFrames]).
     */
    val swing: Int = STRAIGHT_SWING,
) {
    init {
        require(orbits.size <= MAX_ORBITS) { "at most $MAX_ORBITS rings, got ${orbits.size}" }
        require(bpm in MIN_BPM..MAX_BPM) { "bpm out of range: $bpm" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(lapSteps in 1..Orbit.MAX_STEPS) { "lapSteps must be 1..${Orbit.MAX_STEPS}: $lapSteps" }
        require(swing in STRAIGHT_SWING..MAX_SWING) { "swing must be $STRAIGHT_SWING..$MAX_SWING: $swing" }
    }

    companion object {
        const val MAX_ORBITS = 8
        const val DEFAULT_LAP_STEPS = 16

        // The MPC's scale, not a second copy of it: a ring's swing percent
        // means what the exported clip's does, so it is defined where the
        // rest of the format's arithmetic lives.
        const val STRAIGHT_SWING = Mpc3Clip.STRAIGHT_SWING
        const val MAX_SWING = Mpc3Clip.MAX_SWING

        /** The swings worth a chip: the MPC's own ladder, straight to dotted. */
        val SWING_CHOICES: List<Int> = listOf(50, 54, 58, 62, 66, 71, 75)

        /** The bars worth a chip: the common meters, all even so a half-bar span stays whole. */
        val BAR_CHOICES: List<Int> = listOf(12, 16, 20, 24, 32)

        /** A bar length as a meter — 12 is "3/4", 16 "4/4", 32 "8/4"; anything not in quarters says its 16ths. */
        fun meterLabel(lapSteps: Int): String = if (lapSteps % 4 == 0) "${lapSteps / 4}/4" else "$lapSteps/16"
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

    /** Frames in one reference bar — the lap every spanned ring is measured in. */
    fun lapFrames(set: OrbitSet): Long = set.lapSteps.toLong() * stepFrames(set)

    /** [orbit]'s length in 16ths: its steps when free, else its span's laps of the bar. */
    fun periodSteps(set: OrbitSet, orbit: Orbit): Int {
        val laps = orbit.span.laps ?: return orbit.steps
        return (set.lapSteps * laps).roundToInt().coerceAtLeast(1)
    }

    /** How many whole laps [orbit] spans, for the lap ticks: 0 for a free ring or one under a lap. */
    fun spannedLaps(orbit: Orbit): Int {
        val laps = orbit.span.laps ?: return 0
        return if (laps >= 1.0) laps.roundToInt() else 0
    }

    /** Frames in one lap of [orbit]: the ring's circumference in time. */
    fun periodFrames(set: OrbitSet, orbit: Orbit): Long =
        periodSteps(set, orbit).toLong() * stepFrames(set)

    /**
     * [orbit]'s length in words — "5 BEATS", "1 BAR", "2 BARS", "3 16THS" —
     * so a ring's row can say what it is without a mode to explain. A
     * spanned ring says its span ("½ BAR"), whatever the bar's length.
     */
    fun lengthLabel(set: OrbitSet, orbit: Orbit): String {
        if (orbit.span != OrbitSpan.FREE) return orbit.span.label
        val sixteenths = periodSteps(set, orbit)
        val bar = set.lapSteps
        return when {
            sixteenths % bar == 0 -> (sixteenths / bar).let { if (it == 1) "1 BAR" else "$it BARS" }
            bar % 4 == 0 && sixteenths % (bar / 4) == 0 -> "${sixteenths / (bar / 4)} BEATS"
            else -> "$sixteenths 16THS"
        }
    }

    /**
     * The rings' lengths against each other, reduced: 16, 20 and a locked
     * 3-step ring (a bar, 16) read "4 : 5" — distinct lengths in 16ths,
     * shortest first, divided by what they share. Empty with no rings.
     */
    fun ratioLabel(set: OrbitSet): String {
        val lengths = set.orbits.map { periodSteps(set, it).toLong() }.distinct().sorted()
        if (lengths.isEmpty()) return ""
        val g = lengths.fold(0L) { acc, n -> gcd(acc, n) }
        return lengths.joinToString(" : ") { (it / g).toString() }
    }

    /**
     * How far round [orbit] the needle travels in one 16th, as a fraction
     * of its lap — the comet tail's length. A fast inner ring wears a long
     * tail, a slow outer ring a short one, which is the difference the
     * screen exists to show.
     */
    fun tailSweep(set: OrbitSet, orbit: Orbit): Double = 1.0 / periodSteps(set, orbit)

    /** Frames from one of [orbit]'s steps to the next. Fractional for a locked ring whose steps don't divide the bar. */
    fun ringStepFrames(set: OrbitSet, orbit: Orbit): Double =
        periodFrames(set, orbit).toDouble() / orbit.steps

    /** The frame, within a lap, on which [step] fires — its grid place plus the swing, if any. */
    fun stepOffset(set: OrbitSet, orbit: Orbit, step: Int): Long =
        (step * ringStepFrames(set, orbit) + swingFrames(set, orbit, step)).roundToLong()

    /**
     * How late [step] fires for the set's swing: nothing on an even step,
     * nothing on a ring whose step is not a 16th (a 3-step ring across a
     * bar has no offbeat 16ths to push), else (swing − 50) / 50 of a step
     * — the MPC's own arithmetic, where 66 makes the pair a triplet.
     */
    fun swingFrames(set: OrbitSet, orbit: Orbit, step: Int): Double {
        if (step % 2 == 0 || set.swing == OrbitSet.STRAIGHT_SWING) return 0.0
        if (!stepIsSixteenth(set, orbit)) return 0.0
        // The exact ratio, deliberately not the export's rounded pulse: a
        // frame is finer than a pulse, and the two still agree where it
        // counts, because `clip` rounds frames to pulses on the way out.
        // Verified across the whole 50..75 ladder.
        return (set.swing - OrbitSet.STRAIGHT_SWING) / 50.0 * stepFrames(set)
    }

    /** Whether [orbit]'s step is one 16th of the set's tempo — every free ring's is, and a spanned ring's when its steps fill its laps. */
    fun stepIsSixteenth(set: OrbitSet, orbit: Orbit): Boolean =
        abs(ringStepFrames(set, orbit) - stepFrames(set)) < 0.5

    /** How far round the ring the playhead is at [frame], 0 inclusive to 1 exclusive. */
    fun phase(set: OrbitSet, orbit: Orbit, frame: Long): Double {
        val period = periodFrames(set, orbit)
        return Math.floorMod(frame, period).toDouble() / period
    }

    /** Which of [orbit]'s steps the playhead is on at [frame]. */
    fun stepAt(set: OrbitSet, orbit: Orbit, frame: Long): Int =
        floor(phase(set, orbit, frame) * orbit.steps).toInt().coerceIn(0, orbit.steps - 1)

    /**
     * The step nearest [frame] on [orbit] — the one a pad tap at that
     * moment meant, a late tap rounding back and an early one forward, the
     * last half-step of the ring rounding to step 0 of the next turn.
     */
    fun nearestStep(set: OrbitSet, orbit: Orbit, frame: Long): Int =
        Math.round(phase(set, orbit, frame) * orbit.steps).toInt() % orbit.steps

    /**
     * How many 16ths before every ring is back on its downbeat together.
     *
     * The least common multiple of the ring lengths, where a bar-locked ring
     * counts as one bar long whatever its step count — it comes round every
     * bar by definition. Grows fast with coprime rings: 16, 20 and 24 meet
     * again after 240 steps (15 bars), but 16, 17 and 19 need 5,168 steps
     * (323 bars). Show this number, as the loop grid's bounce does.
     */
    fun cycleSteps(set: OrbitSet): Long =
        set.orbits.fold(set.lapSteps.toLong()) { acc, o -> lcm(acc, periodSteps(set, o).toLong()) }

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
            // The step's place on the ring, then the hit's own lean off it.
            // A hit dragged before step 0 has nowhere earlier to go on this
            // lap, so it sounds at the end of the previous one - which is
            // what a pickup before the downbeat is.
            val offset = stepOffset(set, orbit, hit.step) + offsetFrames(set, hit)
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

    /**
     * [hit]'s own [OrbitHit.offset] in frames at the set's tempo — pulses
     * are what a set stores, frames are what the engine counts.
     */
    fun offsetFrames(set: OrbitSet, hit: OrbitHit): Long =
        if (hit.offset == 0L) 0L
        else Math.round(hit.offset.toDouble() / Mpc3Clip.PULSES_PER_16TH * stepFrames(set))

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
