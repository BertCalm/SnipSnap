package com.snipsnap.loop

import com.snipsnap.mpc3.Mpc3Clip

import kotlin.math.abs
import kotlin.math.ceil
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
    /**
     * How long this hit sounds, in pulses — or [WHOLE_SAMPLE] for all of it.
     *
     * Zero is not "no time", it is "however long the sample is", which is
     * what every hit meant before this field existed and what a drum
     * almost always wants: a kick is over when the kick is over, and
     * gating it at a 16th would be a new and worse sound.
     *
     * Whose length it is, is the caller's: the engine gates *any* positive
     * length on *any* pad, and this deliberately cannot consult the pad's
     * trigger mode. `KitPad.oneShot` is a boolean over what the corpus
     * records as three states — One Shot, Note Off, Note On
     * (docs/MPC3_FORMAT.md) — so nothing here can tell whether a given pad
     * reads a note's length at all, and gating on that guess would stop
     * voices live that the hardware would play out. A length is most
     * useful on a pad that holds; it is not restricted to one.
     *
     * In pulses for the reason [offset] is: a set outlives the device it
     * was made on, and a length in frames would mean a different note at
     * a different sample rate.
     */
    val length: Long = WHOLE_SAMPLE,
    /**
     * How often this hit sounds when its lap comes round, as a percent —
     * [ALWAYS] for every time.
     *
     * The roll is not random at play time. It is a pure function of the
     * set's [OrbitSet.seed], the ring, the hit and the lap number
     * ([OrbitClock.sounds]), so the same set renders to the same audio
     * twice and the engine and the export agree lap for lap. A live
     * `Random` would give neither: a bounce would differ from what was
     * heard, and a rewind would play a different take of the same set.
     *
     * Percent rather than a 0..1 float because it is stored, and a
     * percent is exact in JSON where 0.15 is not.
     */
    val chance: Int = ALWAYS,
    /**
     * One lap in this many carries the hit — [EVERY_LAP] for all of them.
     *
     * Laps of this ring, not of the set's bar: on a free 20-step ring
     * against a 16-step one, "every other lap" is every other turn of the
     * twenty, which is where the figure a player hears actually lives.
     *
     * Which of the laps is [onLap], so 1-in-4 is four different hits, not
     * one. The pair is the sequencer staple written as two numbers because
     * that is what it is — the Elektron writes it `1:4`.
     */
    val everyLaps: Int = EVERY_LAP,
    /** Which lap of [everyLaps] this hit takes, 0-based and less than it. */
    val onLap: Int = 0,
    /**
     * How many times the hit strikes across its step — [ONCE] for a plain
     * hit, 2 for a 32nd pair on a 16th step, and so on.
     *
     * Across *its own* step rather than a 16th, so a 3-step ring spanning
     * a bar ratchets into thirds of a bar. The ring's step is the unit the
     * player is looking at; a 16th would be a second grid nothing draws.
     *
     * Every strike carries the hit's own [velocity]. A ramp across the
     * roll is a good sound and a bad field: it would be a second velocity
     * the hit does not show and the grid cannot draw, and the player who
     * wants one can put the strikes on their own steps.
     */
    val ratchet: Int = ONCE,
) {
    init {
        require(step >= 0) { "step must not be negative: $step" }
        require(slot >= 1) { "slot is 1-based: $slot" }
        require(velocity in 0f..1f) { "velocity out of range: $velocity" }
        require(offset in -MAX_OFFSET..MAX_OFFSET) { "offset out of range: $offset" }
        require(length in 0L..MAX_LENGTH) { "length out of range: $length" }
        require(chance in 0..ALWAYS) { "chance is a percent: $chance" }
        require(everyLaps in EVERY_LAP..MAX_EVERY) { "everyLaps must be $EVERY_LAP..$MAX_EVERY: $everyLaps" }
        require(onLap in 0 until everyLaps) { "onLap must be 0 until $everyLaps: $onLap" }
        require(ratchet in ONCE..MAX_RATCHET) { "ratchet must be $ONCE..$MAX_RATCHET: $ratchet" }
    }

    /** Whether this hit stops when it is told to rather than when the sample runs out. */
    val gated: Boolean get() = length > WHOLE_SAMPLE

    /**
     * Whether this hit sounds on every lap it comes round on.
     *
     * The two ways out of certainty are one question wherever the answer
     * only matters as "can I skip asking": a hit at [ALWAYS] on
     * [EVERY_LAP] needs no seed, no lap number and no roll.
     */
    val certain: Boolean get() = chance >= ALWAYS && everyLaps == EVERY_LAP

    /** Whether this hit strikes more than once across its step. */
    val ratcheted: Boolean get() = ratchet > ONCE

    /**
     * Whether this hit can ever sound at all.
     *
     * A [chance] of zero is a hit that is on the ring and silent — kept
     * rather than lifted, which is what a player does to try a bar without
     * it. It costs nothing to play, but it must not cost anything to
     * *reason* about either: it lengthens no cycle and explains no refusal,
     * because nothing it does can be heard.
     */
    val neverSounds: Boolean get() = chance <= 0

    /**
     * Whether the set's seed decides anything about this hit.
     *
     * Narrower than `!`[certain]: a hit at [ALWAYS] on one lap in two is
     * uncertain in the sense that it does not sound every lap, and yet no
     * seed in the world changes which laps those are. So is a hit at zero.
     * Only a chance strictly between the two is actually rolled, and only
     * those make a set a *take*.
     */
    val rolled: Boolean get() = chance in 1 until ALWAYS

    companion object {
        /**
         * A 16th either way. Past that a hit reads as belonging to a
         * different step, and the step is what the ring draws - a pocket
         * that deep is a hit somewhere else, entered somewhere else.
         */
        const val MAX_OFFSET: Long = Mpc3Clip.PULSES_PER_16TH

        /**
         * A [length] of zero: play the sample out.
         *
         * Named rather than written as 0 because "no length" and "every
         * length there is" are opposite readings of the same number, and
         * the second one is meant.
         */
        const val WHOLE_SAMPLE: Long = 0L

        /**
         * The longest a hit may sound: the 64 bars both outputs already
         * cap a cycle at, in pulses.
         *
         * A ceiling rather than none, because `orbits.json` carries numbers
         * as JSON and a JSON number is a `Double` — past 2^53 a length
         * would round on the way out and read back as a different note,
         * which is the one thing a file must not do quietly. 64 bars is
         * nowhere near that, and it is the repo's own number rather than an
         * invented one: nothing longer can be exported anyway.
         */
        const val MAX_LENGTH: Long = OrbitClip.MAX_BARS * Mpc3Clip.PULSES_PER_BAR

        /** A [chance] of 100: the hit sounds every time its lap comes round. */
        const val ALWAYS: Int = 100

        /** An [everyLaps] of 1: every lap is this hit's lap. */
        const val EVERY_LAP: Int = 1

        /** A [ratchet] of 1: one strike, which is what a hit is. */
        const val ONCE: Int = 1

        /**
         * The longest a conditional may count.
         *
         * A ceiling because [OrbitClock.cycleSteps] multiplies by it: a
         * hit on one lap in N makes its ring N turns long before the set
         * repeats, and the export writes that whole cycle. Eight is the
         * sequencer convention (the Elektron counts to 8) and keeps the
         * worst case — eight rings all coprime and all conditional — a
         * number the refusal can still say out loud.
         */
        const val MAX_EVERY: Int = 8

        /**
         * The most strikes one step may hold.
         *
         * Eight across a 16th is 128th notes, which at 120 BPM is 15 ms
         * apart — already past where a drum reads as a roll rather than a
         * pitch, and far past what the export's 960 PPQ can place evenly
         * on the slower rings.
         */
        const val MAX_RATCHET: Int = 8
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
 * One stretch of an arrangement: which rings play, and for how long.
 *
 * A set loops forever, and a section is the only way to say "these rings
 * for eight bars, then those". The grid has had this as [Arrangement]
 * since the beginning and the groove side has `BeatTape.arrange`; rings
 * had neither.
 *
 * **A section restarts its rings.** That is the decision the whole row
 * turns on, and it follows from what a section has to be rather than from
 * taste. The alternative reading — a section as a mute lane over a set
 * that keeps turning underneath — fails both things a section is for: the
 * arrangement would only repeat after the sections and the rings' own
 * cycle agreed, which is rarely; and the clip exported for a section
 * would be true on its first pass and a lie on its second, since the
 * rings would be somewhere else by then. A hardware sequence is
 * self-contained and loops. So is this.
 *
 * The drift that makes ORBIT ORBIT is unhurt: inside a section the rings
 * are the same pure function of the same one number they always were.
 * What a section changes is where that number is counted from.
 */
data class OrbitSection(
    val name: String,
    /** How long, in the set's own reference bars ([OrbitSet.lapSteps] of them). */
    val bars: Int,
    /**
     * Which of [OrbitSet.orbits] play here, by index.
     *
     * Empty is a real answer and means silence — a break is a section
     * like any other, and a player who wants one should not have to
     * delete their rings to get it. It is deliberately not read as "all
     * of them": a default that quietly means the opposite of what it says
     * is how a break becomes a full bar on stage.
     */
    val plays: Set<Int>,
) {
    init {
        require(name.isNotBlank()) { "section name must not be blank" }
        require(bars in 1..OrbitClip.MAX_BARS) { "section bars must be 1..${OrbitClip.MAX_BARS}: $bars" }
        for (index in plays) require(index >= 0) { "section '$name' names ring $index" }
    }

    companion object {
        /**
         * The most sections one set may hold.
         *
         * Every section becomes its own clip and every clip its own
         * sequence on the hardware, so the real ceiling is
         * `Mpc3ProjectWriter.MAX_SEQUENCES` (32). Eight is well under it,
         * matches [OrbitSet.MAX_ORBITS], and is as much arrangement as a
         * phone screen can show without a second page.
         */
        const val MAX_SECTIONS = 8
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
    /**
     * The one number every [OrbitHit.chance] on this set rolls against.
     *
     * A set with a seed is a take: the same seed gives the same audio
     * every time it is rendered, bounced or exported, and a new one gives
     * a different arrangement of the same material without a hit moving.
     * That is the whole reason a seed is stored rather than a `Random`
     * kept alive somewhere — a live generator makes a bounce that does not
     * match what was heard, and there is nothing to write in the file.
     */
    val seed: Int = DEFAULT_SEED,
    /**
     * The arrangement, or empty for none.
     *
     * Empty is what every set was before sections existed and is what
     * most sets stay: every engaged ring, turning forever, counted from
     * the start of play. Nothing about such a set is touched by any of
     * this — [OrbitClock.localFrame] hands back the frame it was given,
     * and the clip written from it is the clip that was written before.
     */
    val sections: List<OrbitSection> = emptyList(),
) {
    init {
        require(orbits.size <= MAX_ORBITS) { "at most $MAX_ORBITS rings, got ${orbits.size}" }
        require(sections.size <= OrbitSection.MAX_SECTIONS) {
            "at most ${OrbitSection.MAX_SECTIONS} sections, got ${sections.size}"
        }
        // The name is not decoration: the arrangement's ORDER does not
        // reach the hardware, so once the clips are sequences the name is
        // the only thing saying which section is which. Two called the
        // same thing is the arrangement becoming unreadable at exactly the
        // point the player has left the app - so it is refused here, where
        // a file and a caller both have to come through, rather than only
        // in the screen that happens to make them.
        require(sections.map { it.name }.distinct().size == sections.size) {
            "two sections share a name: ${sections.map { it.name }}"
        }
        // Checked here rather than on the section, because a section alone
        // cannot know how many rings there are - and an index past the end
        // is the one way an arrangement can silently stop matching its set.
        for (section in sections) {
            for (index in section.plays) {
                require(index < orbits.size) {
                    "section '${section.name}' plays ring $index of ${orbits.size}"
                }
            }
        }
        require(bpm in MIN_BPM..MAX_BPM) { "bpm out of range: $bpm" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(lapSteps in 1..Orbit.MAX_STEPS) { "lapSteps must be 1..${Orbit.MAX_STEPS}: $lapSteps" }
        require(swing in STRAIGHT_SWING..MAX_SWING) { "swing must be $STRAIGHT_SWING..$MAX_SWING: $swing" }
    }

    /**
     * This set with ring [index] gone, and the arrangement re-pointed at
     * the rings that are left.
     *
     * A section names its rings by index, so removing one from the middle
     * silently re-points every section past it at the wrong ring — or,
     * where the last ring went, at nothing, which the set's own guard
     * turns into a refusal the player did not ask for. The re-pointing
     * belongs with the removal rather than at the call site, because
     * there is no correct way to do one without the other.
     */
    fun withoutOrbit(index: Int): OrbitSet = copy(
        orbits = orbits.filterIndexed { i, _ -> i != index },
        sections = sections.map { section ->
            section.copy(
                plays = section.plays.mapNotNull {
                    when {
                        it == index -> null
                        it > index -> it - 1
                        else -> it
                    }
                }.toSet(),
            )
        },
    )

    /**
     * This set with [ring] inserted just after ring [index], and the
     * arrangement re-pointed — the new ring playing wherever the one it
     * was copied from plays.
     *
     * Inheriting the original's membership rather than starting out of
     * every section: a duplicated ring is a variation of the one beside
     * it, and a copy that appeared in no section would be silent
     * everywhere on a set that has an arrangement, which reads as the
     * duplicate having failed.
     */
    fun withOrbitAfter(index: Int, ring: Orbit): OrbitSet = copy(
        orbits = orbits.take(index + 1) + ring + orbits.drop(index + 1),
        sections = sections.map { section ->
            section.copy(
                plays = section.plays.map { if (it > index) it + 1 else it }.toSet() +
                    if (index in section.plays) setOf(index + 1) else emptySet(),
            )
        },
    )

    companion object {
        const val MAX_ORBITS = 8
        const val DEFAULT_LAP_STEPS = 16

        /** The seed a set has until someone rolls a new one. Any number does; this one is a number. */
        const val DEFAULT_SEED = 1

        // The MPC's scale, not a second copy of it: a ring's swing percent
        // means what the exported clip's does, so it is defined where the
        // rest of the format's arithmetic lives.
        const val STRAIGHT_SWING = Mpc3Clip.STRAIGHT_SWING
        const val MAX_SWING = Mpc3Clip.MAX_SWING

        /** The swings worth a chip: the MPC's own ladder, straight to dotted. */
        val SWING_CHOICES: List<Int> = listOf(STRAIGHT_SWING, 54, 58, 62, 66, 71, MAX_SWING)

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
 *
 * An arrangement changes where the number is counted from and nothing
 * else: [localFrame] turns the transport's frame into the current
 * section's, and everything below is handed that instead. A set with no
 * sections gets its own frame back, which is why none of this shows up in
 * how such a set behaves.
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
     * [orbit]'s length in pulses — the same lap [periodFrames] measures,
     * counted in the unit a clip is written in.
     *
     * There is no sample rate in this, and that is the whole point of it
     * existing. A clip is musical time: the same set must export the same
     * pulses whatever rate it happens to be playing at.
     */
    fun periodPulses(set: OrbitSet, orbit: Orbit): Long =
        periodSteps(set, orbit).toLong() * Mpc3Clip.PULSES_PER_16TH

    /** Pulses from one of [orbit]'s steps to the next. Fractional for a spanned ring whose steps don't divide its laps. */
    fun ringStepPulses(set: OrbitSet, orbit: Orbit): Double =
        periodPulses(set, orbit).toDouble() / orbit.steps

    /**
     * The pulse, within a lap, on which [step] fires — [stepOffset]'s
     * answer in the export's own unit.
     *
     * The swing here is `Mpc3Clip.swingPush` itself rather than the frame
     * ratio rounded on the way out. Those agree at every rate anyone
     * plays at, but not at every rate the type accepts: a set is valid at
     * any positive `sampleRate`, and once a frame is coarser than a pulse
     * — fewer than 960 frames in a beat, which is a rate below 16 × BPM
     * hertz — rounding to a frame and back moves the note. At 120 BPM and
     * 832 Hz there are 416 frames to a beat against 960 pulses, so a step
     * is 104 frames but 240 pulses, and swing 58 came out a pulse late.
     */
    fun stepPulses(set: OrbitSet, orbit: Orbit, step: Int): Long =
        (step * ringStepPulses(set, orbit)).roundToLong() + swingPulses(set, orbit, step)

    /** How late [step] fires for the set's swing, in pulses — [swingFrames]'s counterpart, gated identically. */
    fun swingPulses(set: OrbitSet, orbit: Orbit, step: Int): Long {
        if (step % 2 == 0 || set.swing == OrbitSet.STRAIGHT_SWING) return 0L
        if (!stepIsSixteenth(set, orbit)) return 0L
        return Mpc3Clip.swingPush(set.swing)
    }

    /**
     * Where [hit] falls in a clip: its step's pulse plus its own lean.
     *
     * [firingOffset]'s counterpart, and simpler than it for one reason —
     * [OrbitHit.offset] is already pulses, so the export adds it and is
     * done. Reaching this through frames converted it out of the unit it
     * was stored in and back again, rounding twice to arrive where it
     * started.
     */
    fun firingPulses(set: OrbitSet, orbit: Orbit, hit: OrbitHit): Long =
        stepPulses(set, orbit, hit.step) + hit.offset

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
        // The exact ratio rather than the export's rounded pulse, because
        // this is the engine's answer and a frame is the finer unit at any
        // rate worth playing at. The export does not come through here at
        // all: it asks [swingPulses], which is `Mpc3Clip.swingPush` itself.
        return (set.swing - OrbitSet.STRAIGHT_SWING) / 50.0 * stepFrames(set)
    }

    /**
     * Whether [orbit]'s step is one 16th of the set's tempo — every free
     * ring's is, and a spanned ring's when its steps fill its laps.
     *
     * Asked of the ring's own arithmetic rather than of a frame distance,
     * because "is this a 16th" is a question about the music and has no
     * business consulting the sample rate. A half-frame tolerance answers
     * it wrongly wherever a step is only a frame or two long: at 1 Hz a
     * 15-step bar-locked ring has steps 1.07 frames apart against a 16th
     * of 1, which came within tolerance, and the export swung the odd
     * steps of a ring that has no pairs of 16ths to swing.
     */
    fun stepIsSixteenth(set: OrbitSet, orbit: Orbit): Boolean =
        periodSteps(set, orbit) == orbit.steps

    // ---- the arrangement ----
    //
    // Everything below turns one absolute frame into "which section, and
    // how far into it". The rest of this object never learns that sections
    // exist: it is handed [localFrame]'s answer and goes on being the same
    // pure function of one number it always was. A set with no sections
    // gets its own frame back from every one of these, which is why such a
    // set behaves exactly as it did before any of this.

    /** How long [section] lasts, in frames. */
    fun sectionFrames(set: OrbitSet, section: OrbitSection): Long =
        section.bars.toLong() * lapFrames(set)

    /** How long the whole arrangement lasts before it comes round, or 0 with no sections. */
    fun arrangementFrames(set: OrbitSet): Long =
        set.sections.sumOf { sectionFrames(set, it) }

    /**
     * Which section is playing at [frame], or -1 when the set has no
     * arrangement.
     *
     * The arrangement repeats, so this wraps: a two-section set is section
     * 0, then 1, then 0 again, for as long as it runs.
     */
    fun sectionAt(set: OrbitSet, frame: Long): Int {
        val total = arrangementFrames(set)
        if (total <= 0L) return NO_SECTION
        var at = Math.floorMod(frame, total)
        for ((index, section) in set.sections.withIndex()) {
            val length = sectionFrames(set, section)
            if (at < length) return index
            at -= length
        }
        // Unreachable while the lengths sum to `total`; the last section is
        // the honest answer if rounding ever made them not.
        return set.sections.size - 1
    }

    /**
     * [frame] counted from the start of the section it is in — or [frame]
     * itself when the set has no arrangement.
     *
     * This is the whole of what a section does to the clock. Every ring's
     * phase, every firing and every flare is a function of *this* number
     * rather than of the transport's, which is what makes a section
     * self-contained and repeatable.
     */
    fun localFrame(set: OrbitSet, frame: Long): Long {
        val total = arrangementFrames(set)
        if (total <= 0L) return frame
        var at = Math.floorMod(frame, total)
        for (section in set.sections) {
            val length = sectionFrames(set, section)
            if (at < length) return at
            at -= length
        }
        return at
    }

    /**
     * The next frame at or after [frame] on which the section changes, or
     * [NO_BOUNDARY] when the set has no arrangement.
     *
     * The engine needs this because a block is a slice of wall-clock time
     * and a section boundary does not wait for one to end. A block that
     * straddles a boundary is played as two pieces, each with its own
     * local frame, rather than as one piece belonging to whichever section
     * happened to own its first sample.
     *
     * **Always strictly after [frame]**, and the engine's loop depends on
     * it: that loop walks `at = nextBoundary(at)` until the block is
     * spent, so an answer at or behind where it was asked is not a wrong
     * block but a block that never ends — silence, and a wedged device.
     * It holds by construction, since [localFrame] is always less than the
     * section's own length, and `the next boundary is where the block has
     * to be cut` asserts it at the awkward frames rather than trusting the
     * arithmetic. A revert of [localFrame] during review broke exactly
     * this and hung the test run, which is how the invariant got written
     * down.
     */
    fun nextBoundary(set: OrbitSet, frame: Long): Long {
        val total = arrangementFrames(set)
        if (total <= 0L) return NO_BOUNDARY
        val index = sectionAt(set, frame)
        val local = localFrame(set, frame)
        return frame + (sectionFrames(set, set.sections[index]) - local)
    }

    /**
     * Whether the ring at [index] sounds at [frame].
     *
     * Two different questions, both answered here so no caller has to ask
     * only one of them: a set with no arrangement plays every ring it has,
     * and a set with one plays the rings its current section names. The
     * ring's own [Orbit.engaged] is separate and is the player's mute — a
     * muted ring stays muted whatever a section says.
     */
    fun playsAt(set: OrbitSet, index: Int, frame: Long): Boolean {
        val section = sectionAt(set, frame)
        return section == NO_SECTION || index in set.sections[section].plays
    }

    /**
     * How many of the set's own bars one turn of the transport takes: the
     * arrangement's length where there is one, else the rings' cycle.
     *
     * What a "BAR 7 / 15" readout counts against. With an arrangement the
     * rings' cycle is the wrong number — the sections may never reach the
     * meeting it names, so the readout would climb towards a total the
     * player never arrives at.
     */
    fun transportBars(set: OrbitSet): Int {
        if (set.sections.isEmpty()) return Math.ceil(cycleBars(set)).toInt().coerceAtLeast(1)
        return (transportSteps(set) / set.lapSteps).toInt().coerceAtLeast(1)
    }

    /**
     * Which of [transportBars] the transport is on at [frame], 1-based.
     *
     * Counted round the arrangement where there is one, so it comes back
     * to bar 1 when the plan does rather than when the rings happen to.
     */
    fun transportBar(set: OrbitSet, frame: Long): Int {
        val lap = lapFrames(set)
        if (lap <= 0L) return 1
        val total = transportBars(set)
        val arrangement = arrangementFrames(set)
        val at = if (arrangement > 0L) Math.floorMod(frame, arrangement) else frame
        return (Math.floorMod(at / lap, total.toLong()) + 1).toInt()
    }

    /**
     * How many of the set's own 16ths one turn of the transport takes: the
     * arrangement's length where there is one, else the rings' cycle.
     *
     * What a **bounce** is long, and what its ceiling is measured against.
     * An arranged set never reaches the rings' meeting — that is what
     * arranging it did — so rendering `cycleSteps` of it would be minutes
     * of audio for a three-bar plan, and on coprime rings a number that
     * does not fit the `Int` the renderer takes.
     */
    fun transportSteps(set: OrbitSet): Long {
        if (set.sections.isEmpty()) return cycleSteps(set)
        return set.sections.sumOf { it.bars.toLong() } * set.lapSteps
    }

    /** [transportSteps] in frames: exactly how long a bounce of [set] is. */
    fun transportFrames(set: OrbitSet): Long = transportSteps(set) * stepFrames(set)

    /** [sectionAt]'s answer for a set with no arrangement. */
    const val NO_SECTION = -1

    /** [nextBoundary]'s answer for a set with no arrangement: never. */
    const val NO_BOUNDARY = Long.MAX_VALUE

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
     * How many 16ths before every ring is saying the same thing again.
     *
     * The least common multiple of the ring *turns* — [turnSteps], which is
     * a ring's length stretched by the conditionals on it, since a hit on
     * one lap in four does not come back until the fourth lap. A bar-locked
     * ring counts as one bar long whatever its step count; it comes round
     * every bar by definition. Grows fast with coprime rings: 16, 20 and 24
     * meet again after 240 steps (15 bars), but 16, 17 and 19 need 5,168
     * steps (323 bars). Show this number, as the loop grid's bounce does.
     */
    fun cycleSteps(set: OrbitSet): Long =
        set.orbits.fold(set.lapSteps.toLong()) { acc, o -> lcm(acc, turnSteps(set, o)) }

    /**
     * How many 16ths before [orbit] itself repeats: its lap, stretched by
     * the conditionals on it.
     *
     * A lap is how long the ring takes to come round; this is how long it
     * takes to *say the same thing again*, which is what a cycle is made
     * of. A hit on one lap in four does not repeat until the fourth lap,
     * so a 16-step ring carrying one is 64 steps long as far as the set's
     * realignment is concerned, and the clip that writes one cycle has to
     * be four times as long to hold it.
     *
     * [OrbitHit.chance] is deliberately not in this, with one exception at
     * each end. A *rolled* hit never repeats - that is what rolling it is
     * for - so there is no cycle length that would hold it, and the export
     * writes the rolls of the first cycle rather than pretending to a
     * period it has not got. A hit that [OrbitHit.neverSounds] is left out
     * entirely: a silenced hit carrying a 1-in-8 made a one-bar ring claim
     * an eight-lap cycle, which is eight bars of clip and a refusal, over
     * a hit nothing can hear.
     */
    fun turnSteps(set: OrbitSet, orbit: Orbit): Long {
        val period = periodSteps(set, orbit).toLong()
        val content = orbit.content as? PatternOrbit ?: return period
        return period * content.hits
            .filter { !it.neverSounds }
            .fold(1L) { acc, h -> lcm(acc, h.everyLaps.toLong()) }
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
     * Every *strike* of [orbit] that sounds in [from] inclusive to [until]
     * exclusive — what the engine schedules for one block, and what a test
     * asserts against. Frames are absolute, since the start of play.
     *
     * One entry per strike, not per hit per lap, and neither number is
     * fixed: a ratcheted hit contributes [OrbitHit.ratchet] entries across
     * its own step, and a hit whose condition or roll comes up short on a
     * lap contributes none for it ([sounds]). A hit appears as many times
     * as it is heard, which is the only count a caller starting voices can
     * use.
     */
    fun firings(set: OrbitSet, orbit: Orbit, from: Long, until: Long): List<Firing> {
        val content = orbit.content as? PatternOrbit ?: return emptyList()
        if (until <= from) return emptyList()
        val period = periodFrames(set, orbit)
        val step = ringStepFrames(set, orbit)
        val out = ArrayList<Firing>()
        for (hit in content.hits) {
            // The step's place on the ring is what the lap is counted from;
            // the hit's own lean is added to the answer. A hit dragged
            // before step 0 has nowhere earlier to go on its lap, so it
            // sounds at the end of the previous one - which is what a
            // pickup before the downbeat is, and it still belongs to the
            // lap it leans into. Walking the step rather than the leaned
            // position is what keeps [reachOf]'s widening bounded.
            val lean = offsetFrames(set, hit)
            val reach = reachOf(lean, step)
            eachLap(period, stepOffset(set, orbit, hit.step), from - reach, until + reach) { lap, at ->
                if (!sounds(set, orbit, hit, lap)) return@eachLap
                for (k in 0 until hit.ratchet) {
                    val frame = at + lean + strike(step, hit.ratchet, k)
                    if (frame in from until until) out.add(Firing(hit, frame))
                }
            }
        }
        out.sortBy { it.frame }
        return out
    }

    /**
     * Every (hit, pulse) of [orbit] that falls before [untilPulses] — what
     * the export writes, as [firings] is what the engine plays.
     *
     * The same walk as [firings] over the same laps, in the other unit. It
     * is not a conversion of that one: the clip never visits the frame
     * domain, so no rate can round a note off its pulse.
     */
    fun pulseFirings(set: OrbitSet, orbit: Orbit, untilPulses: Long): List<PulseFiring> {
        val content = orbit.content as? PatternOrbit ?: return emptyList()
        if (untilPulses <= 0L) return emptyList()
        val period = periodPulses(set, orbit)
        val step = ringStepPulses(set, orbit)
        val out = ArrayList<PulseFiring>()
        for (hit in content.hits) {
            val lean = hit.offset
            val reach = reachOf(lean, step)
            eachLap(period, stepPulses(set, orbit, hit.step), -reach, untilPulses + reach) { lap, at ->
                if (!sounds(set, orbit, hit, lap)) return@eachLap
                for (k in 0 until hit.ratchet) {
                    val pulses = at + lean + strike(step, hit.ratchet, k)
                    if (pulses in 0L until untilPulses) out.add(PulseFiring(hit, pulses))
                }
            }
        }
        out.sortBy { it.pulses }
        return out
    }

    /**
     * How far either side of its step one hit can reach: its lean, plus
     * the step a ratchet strikes across, plus one for the rounding.
     *
     * The lap walk is widened by this and every strike it produces is then
     * filtered against the window. Widening alone would emit a strike
     * twice, once from each of two neighbouring blocks; filtering alone
     * would miss the strike of a lap that starts before the block it lands
     * in. Together they are exactly-once, and the filter is what makes it
     * so - the widening only has to be generous enough.
     */
    private fun reachOf(lean: Long, step: Double): Long = abs(lean) + ceil(step).toLong() + 1

    /**
     * Where strike [k] of a [ratchet] falls after the hit, in the same
     * unit [step] is given in.
     *
     * Evenly across the step, first strike on the beat: a ratchet of two
     * is the hit and its halfway echo, not two echoes. The last strike is
     * one interval short of the next step, so a ratchet never lands on
     * top of the hit that follows it.
     */
    private fun strike(step: Double, ratchet: Int, k: Int): Long =
        if (k == 0) 0L else Math.round(k * step / ratchet)

    /**
     * Whether [hit] sounds on [lap] of [orbit] - its condition, then its
     * roll.
     *
     * The lap is the ring's own turn count, and the engine and the export
     * ask this with the same number for the same turn. That much is by
     * construction rather than by care: each walk indexes its own copies
     * algebraically, so turn N is lap N in both, whatever the units.
     *
     * What the step-based walk buys is the widening. A lean and a ratchet
     * both reach outside the lap a hit belongs to, so the walk has to be
     * widened past the block and every strike filtered back into it - and
     * that is only bounded if the position it walks is inside the lap to
     * begin with. A step's place is always in `[0, period)`; where a hit
     * finally sounds is not.
     */
    fun sounds(set: OrbitSet, orbit: Orbit, hit: OrbitHit, lap: Long): Boolean {
        if (hit.certain) return true
        if (Math.floorMod(lap, hit.everyLaps.toLong()) != hit.onLap.toLong()) return false
        if (hit.chance >= OrbitHit.ALWAYS) return true
        if (hit.chance <= 0) return false
        return roll(set, orbit, hit, lap) < hit.chance
    }

    /**
     * [hit]'s roll on [lap], 0 until [OrbitHit.ALWAYS] - a number, not a
     * random one.
     *
     * Everything that identifies the hit goes into the seed, so two hits
     * that differ at all roll differently: the ring's name, length and
     * span, and the hit's step, pad and lean. Two rings that are identical
     * in all of those do roll identically, which is the honest answer -
     * they fire at the same instants on the same pads, so a set cannot
     * hear the difference anyway.
     *
     * Deliberately not the ring's index in the set: reordering the rings
     * on screen would re-roll every conditional in the set, and moving a
     * row is not meant to be a new take. `kotlin.random.Random` because
     * its generator is specified rather than the platform's - the same
     * seed gives the same number on the JVM, on the device, and in a test.
     */
    fun roll(set: OrbitSet, orbit: Orbit, hit: OrbitHit, lap: Long): Int {
        var h = set.seed.toLong()
        h = h * 31 + orbit.name.hashCode()
        h = h * 31 + orbit.steps
        h = h * 31 + orbit.span.ordinal
        h = h * 31 + hit.step
        h = h * 31 + hit.slot
        h = h * 31 + hit.offset
        h = h * 31 + lap
        return kotlin.random.Random(h).nextInt(OrbitHit.ALWAYS)
    }

    /**
     * Every copy of one hit in [from] until [until], a lap apart.
     *
     * One walk for both units, because two of them is how the engine and
     * the export come to disagree about which laps a hit lands on — the
     * defect this wave keeps finding in a different costume.
     */
    private inline fun eachLap(period: Long, offset: Long, from: Long, until: Long, emit: (Long, Long) -> Unit) {
        // First lap whose copy of this hit lands at or after `from`.
        var lap = Math.floorDiv(from - offset, period)
        if (lap * period + offset < from) lap++
        var at = lap * period + offset
        while (at < until) {
            emit(lap, at)
            lap++
            at += period
        }
    }

    /**
     * The frame within a lap on which [hit] actually sounds: its step's
     * grid place, the set's swing, and the hit's own lean.
     *
     * Everything that answers "when does this hit happen" goes through
     * here — the engine, the export, and the ring the screen draws. They
     * were three separate sums of the same parts, and a hit given a pocket
     * sounded late while its dot stayed on the grid.
     */
    fun firingOffset(set: OrbitSet, orbit: Orbit, hit: OrbitHit): Long =
        stepOffset(set, orbit, hit.step) + offsetFrames(set, hit)

    /**
     * [hit]'s own [OrbitHit.offset] in frames at the set's tempo — pulses
     * are what a set stores, frames are what the engine counts.
     */
    fun offsetFrames(set: OrbitSet, hit: OrbitHit): Long = framesForPulses(set, hit.offset)

    /**
     * [pulses] of musical time as frames at [set]'s tempo.
     *
     * The one conversion between the two units, because a lean and a note
     * length are the same question asked twice and two answers to it drift.
     *
     * Rounds the magnitude and puts the sign back: `Math.round` breaks ties
     * toward positive infinity, so a value landing on half a frame rounds
     * out late and back early — at 8 kHz a +3 pulse lean was 13 frames and
     * a −3 was 12, an equal pair that was not a pair, and it biased a
     * humanised take late with every tie in one direction.
     */
    fun framesForPulses(set: OrbitSet, pulses: Long): Long {
        if (pulses == 0L) return 0L
        val frames = Math.round(abs(pulses).toDouble() / Mpc3Clip.PULSES_PER_16TH * stepFrames(set))
        return if (pulses < 0L) -frames else frames
    }

    data class Firing(val hit: OrbitHit, val frame: Long)

    data class PulseFiring(val hit: OrbitHit, val pulses: Long)

    /**
     * Frames since [orbit]'s [hit] last *struck*, at or before [frame] —
     * what the screen's strike flare fades on — or [NEVER] where it has
     * not struck within the laps its condition repeats over.
     *
     * Never negative: a hit the needle has not reached yet this lap counts
     * from its strike on the lap before, which at frame 0 means "a whole
     * lap ago" rather than "about to fire".
     *
     * Two things make this a search rather than a remainder, and both are
     * the flare telling the truth. A hit that did not sound must not flare,
     * or a 1-in-4 flashes four times for every time it is heard; its
     * condition repeats every `everyLaps` laps, so looking that far back
     * either finds the strike or there was not one. And a *ratcheted* hit
     * strikes several times across its step, so the flare has to fade from
     * the latest of them: anchored to the first, a slow ring's fourth
     * strike was heard against a dot that had already gone out.
     *
     * One path rather than a remainder with a search behind it, because
     * for a plain hit the search's first answer *is* that remainder, and a
     * second copy of it is how the two come to disagree.
     */
    fun framesSinceFiring(set: OrbitSet, orbit: Orbit, hit: OrbitHit, frame: Long): Long {
        val period = periodFrames(set, orbit)
        val at = firingOffset(set, orbit, hit)
        val step = ringStepFrames(set, orbit)
        var lap = Math.floorDiv(frame - at, period)
        val floor = lap - hit.everyLaps
        while (lap >= floor) {
            if (sounds(set, orbit, hit, lap)) {
                val base = lap * period + at
                // Latest first: the later strikes of this lap may still be
                // ahead of the needle, and the first one never is.
                for (k in hit.ratchet - 1 downTo 0) {
                    val struck = base + strike(step, hit.ratchet, k)
                    if (struck <= frame) return frame - struck
                }
            }
            lap--
        }
        return NEVER
    }

    /**
     * [framesSinceFiring]'s answer for a hit that has not sounded lately:
     * longer ago than any flare lasts.
     *
     * A number rather than a null because every caller divides it by a
     * fade length and clamps, and "so long ago it is nothing" is the true
     * answer for a hit whose roll came up short.
     */
    const val NEVER: Long = Long.MAX_VALUE

    private fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun Double.roundToLong(): Long = Math.round(this)
}
