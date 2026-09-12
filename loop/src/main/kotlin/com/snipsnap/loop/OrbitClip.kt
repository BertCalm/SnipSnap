package com.snipsnap.loop

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3ProjectWriter
import java.io.File
import kotlin.math.ceil

/**
 * A set of rings as MPC clips: every ring's firings flattened onto the
 * 960-PPQ grid the native export already carries, so a polymeter rides to
 * the MPC inside the kit's `groove.json` like any other groove.
 *
 * A set with no arrangement is one clip, and the rings' cycle is its
 * length — 16 against 20 is a five-bar clip — which is why a long cycle
 * is refused rather than truncated: a clip that stops before the rings
 * meet is a different piece of music. A set WITH an arrangement is one
 * clip per section, each as long as its section says and capped on its
 * own; the cycle is then not what leaves the screen, so it does not bind.
 */
object OrbitClip {

    /** MPC clips top out at 64 bars, and so does the bounce. */
    const val MAX_BARS = 64

    /** The clip's bar is always 16 sixteenths: [Mpc3Clip] has bars but no time signature. */
    const val CLIP_BAR_STEPS = 16

    /** Every ORBIT clip's name starts with this, so saving replaces the last one and the GROOVE screen can tell it apart. */
    const val NAME_PREFIX = "ORBIT"

    /** The clip's name for [set]: the ratio says what the rings are. */
    fun nameFor(set: OrbitSet): String = "$NAME_PREFIX ${OrbitClock.ratioLabel(set).replace(" : ", ":")}".trim()

    /**
     * How many bars the clip (and the bounce) would be: the cycle in 4/4
     * bars, rounded up. Counted in the clip's own bar, not the set's: a
     * 3/4 set's four-bar cycle is 48 steps, which the clip calls three.
     */
    fun bars(set: OrbitSet): Int = barsFor(OrbitClock.cycleSteps(set))

    /**
     * [steps] of the set's own 16ths as whole clip bars, rounded up.
     *
     * Counted in the clip's bar of sixteen, not the set's: a 3/4 set's
     * eight-bar section is 96 sixteenths, which the clip calls six.
     */
    fun barsFor(steps: Long): Int =
        ceil(steps.toDouble() / CLIP_BAR_STEPS).toInt().coerceAtLeast(1)

    /** Whether the clip's bar count differs from the set's, so the screen can say so. */
    fun countsDifferently(set: OrbitSet): Boolean = set.lapSteps != CLIP_BAR_STEPS

    /**
     * The rings carrying audio rather than notes. A clip has no way to
     * hold one — a snip is a waveform, not a pulse and a velocity — so an
     * export leaves them behind, and the screen says how many rather than
     * letting the player discover it on the hardware.
     */
    fun snipRings(set: OrbitSet): List<String> =
        set.orbits.filter { it.content is SnipOrbit }.map { it.name }

    /**
     * Null when [set] can leave the screen at all, else the refusal in
     * words. This is the ceiling both ways out share — the bounce is one
     * cycle of audio and the clip is one cycle of notes (or, with an
     * arrangement, one section of them), and neither has anywhere to put
     * more than [MAX_BARS] of it.
     *
     * Deliberately not the place for "there are no notes in this": a set
     * of snip rings has nothing to clip and is still perfectly good to
     * bounce, so that question belongs to [clipRefusal] alone.
     */
    fun refusal(set: OrbitSet): String? {
        // A section is a clip of its own and has its own ceiling, which is
        // not the rings' cycle. A section's length is capped in the set's
        // bars, and the clip counts in bars of sixteen - so 64 bars of 8/4
        // is 128 of the clip's, twice what one holds. Without this the
        // preflight said yes and `Mpc3Clip` threw "bars out of range: 128"
        // at the caller, which is a preflight that makes the caller catch
        // anyway.
        set.sections.indices.forEach { index ->
            val sectionBars = barsFor(sectionSteps(set, index))
            if (sectionBars > MAX_BARS) {
                return "SECTION ${set.sections[index].name} IS $sectionBars BARS OF 4/4 — A CLIP STOPS AT $MAX_BARS. SHORTEN IT."
            }
        }
        // Then the BOUNCE's ceiling, which is a different length from a
        // clip's the moment there is an arrangement: a bounce is one turn
        // of the TRANSPORT, and an arranged set's turn is its plan rather
        // than the rings' meeting. The first cut of sections relaxed this
        // for arranged sets altogether, which left `bounceToTape`
        // rendering `cycleFrames` with nothing to stop it - minutes of
        // audio for a three-bar plan on coprime rings, through a `Long`
        // the renderer's `Int` cannot hold.
        val bars = barsFor(OrbitClock.transportSteps(set))
        if (bars <= MAX_BARS) return null
        val unit = if (countsDifferently(set)) "BARS OF 4/4" else "BARS"
        // A ring's length is no longer the only thing that makes a cycle
        // long: a hit on one lap in four does not repeat until the fourth
        // lap, so it multiplies its ring's contribution
        // ([OrbitClock.turnSteps]). Telling a player to shorten a ring when
        // what did it was a conditional sends them to the wrong chip.
        val conditional = set.orbits.any { o ->
            (o.content as? PatternOrbit)?.hits?.any {
                // A conditional on a hit that never sounds stretched
                // nothing ([OrbitClock.turnSteps] leaves it out), so
                // naming it here would send the player to undo the one
                // thing that is not the cause.
                it.everyLaps != OrbitHit.EVERY_LAP && !it.neverSounds
            } == true
        }
        // An arranged set's turn is the plan, so the sentence about the
        // rings meeting is not the one to say: the player shortens a
        // section, and the rings never meet at all by design.
        if (set.sections.isNotEmpty()) {
            return "THE PLAN RUNS $bars $unit — A BOUNCE STOPS AT $MAX_BARS. SHORTEN A SECTION."
        }
        val fix = if (conditional) "SHORTEN A RING, OR TAKE A CONDITIONAL OFF A HIT." else "SHORTEN A RING."
        return "THE RINGS MEET EVERY $bars $unit — A CLIP STOPS AT $MAX_BARS. $fix"
    }

    /**
     * Null when [set] would write a clip worth writing, else why not: the
     * shared ceiling first, then the reasons peculiar to a clip. A caller
     * that only wants audio wants [refusal] instead.
     */
    fun clipRefusal(set: OrbitSet): String? {
        refusal(set)?.let { return it }
        // Asked of each section's own clip, because that is what gets
        // written. Over the whole set it answered two different questions
        // wrongly: an arrangement that puts kit A in one section and kit B
        // in another is two clips, each on one program, and was refused as
        // a two-kit clip; while an arrangement of nothing but breaks
        // passed and then failed inside `save` on a bare `require`.
        if (set.sections.isNotEmpty()) {
            for (index in set.sections.indices) {
                if (set.sections[index].plays.isEmpty()) continue
                val sub = sectionSet(set, index)
                val why = oneProgram(sub) ?: noNotes(sub) ?: continue
                return "SECTION ${set.sections[index].name}: $why"
            }
            if (set.sections.all { it.plays.isEmpty() }) {
                return "EVERY SECTION HERE IS A BREAK. GIVE ONE A RING TO PLAY."
            }
            return null
        }
        return oneProgram(set) ?: noNotes(set)
    }

    /**
     * Why [set]'s rings cannot share one clip, or null when they can.
     *
     * A clip rides a track and a track carries one program, so every note
     * in it addresses that program's pads. Rings on two kits have no
     * shared pad numbering: `noteFor` maps slot to note with no idea which
     * kit the slot belongs to, so kitA's pad 1 and kitB's pad 1 both
     * become note 36 and `dedupeLouder` keeps whichever was louder. The
     * quieter kit loses the hit with nothing said.
     *
     * `save` writes into one kit's `groove.json`, so there is no reading
     * of a two-kit set that a single clip could honour. It refuses and
     * names them rather than picking one.
     *
     * The pad ceiling is the same question at the other end: a slot past
     * [Mpc3Note.PAD_SLOTS] names a pad no program can hold, so
     * [Mpc3Note.noteFor] has nothing to map it to.
     */
    private fun oneProgram(set: OrbitSet): String? {
        // Rings that actually put a note in the clip - engaged, a
        // pattern, and played onto. A hit-less ring contributes nothing
        // whatever kit it names, so counting it as a kit refuses a set
        // that would have exported fine, and with nothing played anywhere
        // it answers "two kits" where the truth is "no hits yet".
        val playing = set.orbits.filter {
            it.engaged && it.content is PatternOrbit && (it.content as PatternOrbit).hits.isNotEmpty()
        }
        val kits = playing.map { (it.content as PatternOrbit).kit }.distinct()
        if (kits.size > 1) {
            return "THESE RINGS PLAY ${kits.size} KITS — ${kits.joinToString(" AND ") { it.uppercase() }}. " +
                "ONE CLIP RIDES ONE PROGRAM. CLIP THEM A KIT AT A TIME."
        }
        val tooHigh = playing
            .flatMap { (it.content as PatternOrbit).hits }
            .map { it.slot }
            .filter { it > Mpc3Note.PAD_SLOTS }
            .distinct()
            .sorted()
        if (tooHigh.isNotEmpty()) {
            return "PAD ${tooHigh.joinToString(", ")} IS PAST ${Mpc3Note.PAD_SLOTS} — NO PROGRAM HOLDS IT."
        }
        return null
    }

    /**
     * Why [set] would write a clip with no notes in it at all, or null
     * when at least one note would land.
     *
     * Four shapes arrive here and they are four different mistakes — an
     * empty screen, a set that is all snips, everything muted, and rings
     * that exist but have not been played onto yet — so each says its own
     * thing instead of one refusal standing in for all of them. The last
     * is the one worth the separate sentence: `+ PAD RING` makes an
     * engaged ring with no hits, so the shape a player reaches first by
     * simply making a ring and tapping CLIP is also the shape a single
     * "nothing to clip" would explain worst.
     *
     * Silence here would not be a harmless no-op, which is why this
     * guard exists rather than a shrug: a note-less clip would save into
     * `groove.json` like any other, and on a kit with no other groove it
     * would become the base — so the kit's beat, everywhere that reads
     * one, would be nothing at all. Refusing here closes that door for
     * ORBIT; it stays open for a `groove.json` written before this guard,
     * and for `GrooveEdit.startEmpty`, which stores a note-less base
     * deliberately. Readers still have to cope, which is why
     * `Arranger.arrange` refuses one by name.
     */
    private fun noNotes(set: OrbitSet): String? {
        if (set.orbits.isEmpty()) return "THERE ARE NO RINGS TO CLIP."
        val patterns = set.orbits.filter { it.content is PatternOrbit }
        if (patterns.isEmpty()) {
            return "EVERY RING HERE IS A SNIP. A SNIP IS AUDIO, NOT NOTES — BOUNCE IT INSTEAD."
        }
        // Order matters, and not for tidiness. Asking "are the engaged
        // rings empty?" first answers yes for a set whose only played ring
        // happens to be muted, and then says no ring has a hit on it while
        // one plainly does. So ask what exists before asking what is
        // audible: a set with no hits anywhere has not been played yet, and
        // a set whose every played ring is muted has a mute to undo.
        val played = patterns.filter { (it.content as PatternOrbit).hits.isNotEmpty() }
        if (played.isEmpty()) return "NO RING HAS A HIT ON IT YET. TAP A STEP FIRST."
        if (played.none { it.engaged }) {
            return "EVERY RING WITH A HIT ON IT IS MUTED. ENGAGE ONE TO CLIP IT."
        }
        return null
    }

    /**
     * One cycle of [set] as a clip. Only engaged pattern rings contribute;
     * snip rings are audio and have no notes. Which note plays which pad
     * is [Mpc3Note.noteFor] — the writer's own map, wrapping at 128, so
     * pad 93 is note 0 rather than another copy of pad 92's.
     */
    fun clip(
        set: OrbitSet,
        name: String = nameFor(set),
        /**
         * How many 16ths the clip holds. The rings' own cycle by default;
         * a section passes its own length instead, so the clip is as long
         * as the section is rather than as long as the rings take to meet.
         */
        steps: Long = OrbitClock.cycleSteps(set),
    ): Mpc3Clip {
        // Asked of the length actually being written, not of the set's
        // cycle. A section is a short clip cut out of rings that may take
        // eighty bars to meet, and checking the cycle here refused the
        // very sets an arrangement exists to make writable.
        val bars = barsFor(steps)
        if (bars > MAX_BARS) {
            throw IllegalArgumentException("$bars BARS OF 4/4 — A CLIP STOPS AT $MAX_BARS.")
        }
        oneProgram(set)?.let { throw IllegalArgumentException(it) }
        noNotes(set)?.let { throw IllegalArgumentException(it) }
        val limit = bars * Mpc3Clip.PULSES_PER_BAR
        // The whole cycle in pulses. The clip is musical time, so it is
        // counted in the unit it is written in rather than converted out
        // of frames: a frame is the finer unit at any rate worth playing
        // at, but `sampleRate` is only required to be positive, and with
        // fewer than 960 frames to a beat — a rate below 16 × BPM hertz —
        // the conversion moves a note off its pulse.
        val cycle = steps * Mpc3Clip.PULSES_PER_16TH
        val notes = ArrayList<Mpc3Note>()
        for (ring in set.orbits) {
            if (!ring.engaged || ring.content !is PatternOrbit) continue
            for (firing in OrbitClock.pulseFirings(set, ring, cycle)) {
                if (firing.pulses >= limit) continue
                notes += Mpc3Note(
                    note = noteFor(firing.hit.slot),
                    timePulses = firing.pulses,
                    velocity = firing.hit.velocity,
                    // A gated hit says how long it sounds; a one-shot says
                    // a 16th, which is what every note said before lengths
                    // existed and what the hardware ignores on a pad whose
                    // `triggerMode` is One Shot — "the whole sample fires
                    // and ends", per the corpus in docs/MPC3_FORMAT.md.
                    // Writing the sample's real length instead would need
                    // the kit, which a clip built from a set alone has not
                    // got — and on the pads where the number IS read, the
                    // hit now carries one rather than defaulting.
                    lengthPulses = if (firing.hit.gated) firing.hit.length else Mpc3Clip.PULSES_PER_16TH,
                )
            }
        }
        return Mpc3Clip(name = name, bars = bars, notes = GrooveEdit.dedupeLouder(notes))
    }

    /**
     * Which note plays a ring's pad — [Mpc3Note.noteFor], not a second
     * copy of it. This used to clamp `35 + slot` into `0..127`, which sent
     * every slot from 92 up to note 127: two pads of one kit struck
     * together exported as one note, and the MPC played pad 92 for both.
     */
    fun noteFor(slot: Int): Int = Mpc3Note.noteFor(slot)

    /** Whether [clip] is an ORBIT clip — by its name, the same way PROG E is found. */
    fun isOrbit(clip: Mpc3Clip): Boolean = clip.name.startsWith(NAME_PREFIX)

    /**
     * The set as one section hears it: the rings that section plays, and
     * no arrangement of its own.
     *
     * The rings are not copied or altered — a section chooses among them,
     * it does not own them. Dropping the arrangement is what makes the
     * result clippable at all: a sub-set that still carried sections would
     * ask this same question again, one section further down.
     */
    fun sectionSet(set: OrbitSet, index: Int): OrbitSet {
        val section = set.sections[index]
        return set.copy(
            orbits = set.orbits.filterIndexed { i, _ -> i in section.plays },
            sections = emptyList(),
        )
    }

    /** How many of the set's own 16ths [index]'s section lasts. */
    fun sectionSteps(set: OrbitSet, index: Int): Long =
        set.sections[index].bars.toLong() * set.lapSteps

    /**
     * One clip per section — or the single whole-set clip when there is no
     * arrangement, byte for byte what [clip] wrote before sections existed.
     *
     * Each becomes its own sequence on the hardware, because
     * `ExportFormats` already turns every stored groove into one: "the
     * pattern flip on the hardware's switcher". The missing piece was
     * never the export, it was having more than one clip to give it.
     *
     * **What does not ride along is the order.** `Mpc3Song`'s step schema
     * — which sequence, how many repeats — has never been captured from
     * the corpus, so `Mpc3ProjectWriter` refuses a non-empty song and the
     * sections arrive as sequences a player flips by hand rather than as
     * an arrangement that plays itself. That is a limit of what has been
     * verified, not a choice, and it is why the sections keep their names:
     * the name is the only thing left telling the player what order they
     * were in.
     *
     * A section that plays no rings writes no clip. A break is a real
     * section and sounds like one here, but there is nothing for the
     * hardware to flip *to* for silence, and a note-less clip is the one
     * thing [noNotes] exists to keep out of `groove.json`.
     */
    fun clips(set: OrbitSet): List<Mpc3Clip> {
        if (set.sections.isEmpty()) return listOf(clip(set))
        return set.sections.indices.mapNotNull { index ->
            val sub = sectionSet(set, index)
            // A break is a section that plays NO RINGS. Skipping on
            // `noNotes` instead swallowed a named section whose rings are
            // all snips, or hitless, or muted - dropped from the
            // arrangement in silence, though the player named it and can
            // see it on screen. Those reach [clip] and throw, and
            // [clipRefusal] has already said which section and why.
            if (set.sections[index].plays.isEmpty()) return@mapNotNull null
            clip(sub, name = "$NAME_PREFIX ${set.sections[index].name}", steps = sectionSteps(set, index))
        }
    }

    /**
     * Write [set]'s clips into the kit's grooves, replacing every previous
     * ORBIT clip and leaving every other stored clip — the captured base,
     * the variations, PROG E — untouched. Returns the clips written.
     *
     * Appended rather than prepended, which matters more now that there
     * may be several: a kit's *base* is `groove.json`'s first clip to
     * `KitPreview`, `PackBuilder` and the exporters alike, and an
     * arrangement must not quietly become it.
     */
    fun save(kitDir: File, set: OrbitSet): List<Mpc3Clip> {
        val clips = clips(set)
        require(clips.isNotEmpty()) { "NO SECTION HERE PLAYS A RING. GIVE ONE A RING TO PLAY." }
        val others = GrooveStore.load(kitDir).filterNot { isOrbit(it) }
        // The promise this feature makes is one sequence per section, and
        // the export keeps it only as far as the hardware's list goes:
        // `ExportFormats` takes the kit's grooves `.take(MAX_SEQUENCES)`,
        // so past thirty-two the later ones are written to `groove.json`
        // and then silently left out of the `.xpj`. A promise that stops
        // being true at a number nobody is told is worse than a refusal.
        val room = Mpc3ProjectWriter.MAX_SEQUENCES
        require(others.size + clips.size <= room) {
            "THIS KIT WOULD HOLD ${others.size + clips.size} GROOVES AND A PROJECT CARRIES $room. " +
                "CLEAR SOME GROOVES, OR USE FEWER SECTIONS."
        }
        GrooveStore.save(kitDir, others + clips)
        return clips
    }
}
