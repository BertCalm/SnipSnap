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

    /**
     * The longest bounce the renderer can even count, in frames.
     *
     * Not [Int.MAX_VALUE]: `OrbitEngine.render` takes an `Int`, allocates
     * the turn INTERLEAVED (`frames * 2` floats) and rounds up to whole
     * blocks (`frames + blockFrames - 1`), so the ceiling is half of an
     * `Int`, less one block. A turn between the two passed the first cut
     * of this guard and then overflowed inside the renderer instead — 64
     * bars at 3 MHz and 40 BPM is 1,152,000,000 frames, which fits an
     * `Int` and doubles straight past it.
     */
    val MAX_BOUNCE_FRAMES: Long = ((Int.MAX_VALUE - OrbitEngine.DEFAULT_BLOCK_FRAMES) / 2).toLong()

    /** The clip's bar is always 16 sixteenths: [Mpc3Clip] has bars but no time signature. */
    const val CLIP_BAR_STEPS = 16

    /** Every ORBIT clip's name starts with this, so saving replaces the last one and the GROOVE screen can tell it apart. */
    const val NAME_PREFIX = "ORBIT"

    /** The clip's name for [set]: the ratio says what the rings are. */
    fun nameFor(set: OrbitSet): String = "$NAME_PREFIX ${OrbitClock.ratioLabel(set).replace(" : ", ":")}".trim()

    /**
     * The RINGS' CYCLE in the clip's own 4/4 bars, rounded up: a 3/4
     * set's four-bar cycle is 48 steps, which the clip calls three.
     *
     * That is the clip's length only for a set with NO arrangement. An
     * arranged set writes one clip per section, each [sectionSteps] long,
     * and its bounce is one turn of the transport
     * ([OrbitClock.transportSteps]) - neither of which the cycle measures,
     * since an arranged set may never reach the rings' meeting at all.
     * The name kept its old promise for a while after that stopped being
     * true, which is what this paragraph is for.
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

    /**
     * The pulses a clip of [steps] of the set's 16ths actually writes.
     *
     * The STEPS themselves, which is the window [clip]'s walk is bounded
     * by. [barsFor] is the container it is written into and is a different
     * number whenever the set's bar is not sixteen: a 3/4 set's twelve
     * steps are written into a bar of sixteen, and the four steps of
     * padding are silence the writer never reaches. Asking the preflight
     * over the padded length counted a hit the writer leaves out - a
     * false two-kit refusal one way round, and a clip declared to sound
     * and then written empty the other.
     *
     * Every question about what a clip contains is asked over this one
     * number, because a preflight measuring a different window from the
     * writer's is the whole of that class of defect.
     */
    fun clipPulses(steps: Long): Long = steps * Mpc3Clip.PULSES_PER_16TH

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
     * words. This is the ceiling both ways out share, though they are no
     * longer the same length: the bounce is one turn of the TRANSPORT
     * (the plan, where there is one) and a clip is one cycle of notes, or
     * one section of them. Neither has anywhere to put more than
     * [MAX_BARS], and both are measured here.
     *
     * Deliberately not the place for "there are no notes in this": a set
     * of snip rings has nothing to clip and is still perfectly good to
     * bounce, so that question belongs to [clipRefusal] alone.
     */
    fun refusal(set: OrbitSet): String? {
        // What every clip that would be WRITTEN has to fit in - shared,
        // because a bounce of a turn containing a section too long to clip
        // is no use either.
        barCap(set)?.let { return it }
        // Then the BOUNCE's own ceiling, which is a different length from
        // a clip's the moment there is an arrangement: a bounce is one
        // turn of the TRANSPORT, and an arranged set's turn is its plan
        // rather than the rings' meeting. The first cut of sections
        // relaxed this for arranged sets altogether, which left
        // `bounceToTape` rendering `cycleFrames` with nothing to stop it -
        // minutes of audio for a three-bar plan on coprime rings, through
        // a `Long` the renderer's `Int` cannot hold.
        //
        // A CLIP does not share this one: eight sections of 32 bars are
        // eight legal sequences and one bounce far too long, and refusing
        // the clip for the bounce's reason refused an arrangement that
        // exports perfectly.
        val bars = barsFor(OrbitClock.transportSteps(set))
        if (bars <= MAX_BARS) return frameCap(set)
        // Only an ARRANGED set reaches this: a set without one turns on
        // the rings' cycle, which is also the clip it would write, so
        // [barCap] has already answered - in the rings' own words, which
        // are not these. An arranged set's turn is its plan, and the
        // player shortens a section: the rings never meet at all by
        // design, so telling them to shorten a ring would be doubly wrong.
        val unit = if (countsDifferently(set)) "BARS OF 4/4" else "BARS"
        return "THE PLAN RUNS $bars $unit — A BOUNCE STOPS AT $MAX_BARS. SHORTEN A SECTION."
    }

    /**
     * Why some clip this set would write cannot be written at its length,
     * or null. The ceiling a CLIP answers to, and the only part of
     * [refusal] a clip shares.
     *
     * An arranged set is asked section by section, because each section is
     * a clip of its own: a section's length is capped in the SET's bars
     * and a clip counts in bars of sixteen, so 64 bars of 8/4 is 128 of
     * the clip's, twice what one holds. Without this the preflight said
     * yes and `Mpc3Clip` threw "bars out of range: 128" at the caller,
     * which is a preflight that makes the caller catch anyway.
     *
     * A set with no arrangement is one clip and the rings' cycle is its
     * length, so that is what it is asked.
     */
    private fun barCap(set: OrbitSet): String? {
        if (set.sections.isEmpty()) {
            val bars = bars(set)
            if (bars <= MAX_BARS) return null
            val unit = if (countsDifferently(set)) "BARS OF 4/4" else "BARS"
            // A ring's length is no longer the only thing that makes a
            // cycle long: a hit on one lap in four does not repeat until
            // the fourth lap ([OrbitClock.turnSteps]), so telling a player
            // to shorten a ring when what did it was a conditional sends
            // them to the wrong chip. A conditional on a hit that never
            // sounds stretched nothing, so naming it would send them to
            // undo the one thing that is not the cause.
            val conditional = set.orbits.any { o ->
                (o.content as? PatternOrbit)?.hits?.any {
                    it.everyLaps != OrbitHit.EVERY_LAP && !it.neverSounds
                } == true
            }
            val fix = if (conditional) "SHORTEN A RING, OR TAKE A CONDITIONAL OFF A HIT." else "SHORTEN A RING."
            return "THE RINGS MEET EVERY $bars $unit — A CLIP STOPS AT $MAX_BARS. $fix"
        }
        set.sections.indices.forEach { index ->
            // A break writes no clip, so there is no clip of its length to
            // be too long: a 64-bar 8/4 break beside a short playable
            // section refused an export in which every clip written fits.
            // The BOUNCE still counts it, through the plan's own ceiling
            // in [refusal] - a break is silence and silence has a length.
            if (set.sections[index].plays.isEmpty()) return@forEach
            val sectionBars = barsFor(sectionSteps(set, index))
            if (sectionBars > MAX_BARS) {
                return "SECTION ${set.sections[index].name} IS $sectionBars BARS OF 4/4 — A CLIP STOPS AT $MAX_BARS. SHORTEN IT."
            }
        }
        return null
    }

    /**
     * Why one turn is too many FRAMES to bounce, or null.
     *
     * [MAX_BARS] is a musical ceiling and not a bound on frames: a set's
     * `sampleRate` is only required to be positive, so a perfectly legal
     * 64-bar turn at 6 MHz and 40 BPM is 2,304,000,000 frames - and
     * `bounceToTape` narrows that to the `Int` [OrbitEngine.render]
     * counts in, where it wraps NEGATIVE. Verified: `refusal` answered
     * null and the conversion gave -1,990,967,296.
     *
     * The bounce's alone. A clip is written in bars and never reaches a
     * frame, so this would refuse a clip for a number it does not use.
     */
    private fun frameCap(set: OrbitSet): String? {
        val frames = OrbitClock.transportFrames(set)
        if (frames <= MAX_BOUNCE_FRAMES) return null
        return "ONE TURN IS $frames FRAMES AT ${set.sampleRate} Hz — MORE THAN ONE BOUNCE HOLDS. " +
            "LOWER THE RATE, OR SHORTEN IT."
    }

    /**
     * Null when [set] would write a clip worth writing, else why not: the
     * shared ceiling first, then the reasons peculiar to a clip. A caller
     * that only wants audio wants [refusal] instead.
     */
    fun clipRefusal(set: OrbitSet): String? {
        // [barCap] rather than [refusal]: what a clip has to fit in, not
        // what a bounce has to.
        barCap(set)?.let { return it }
        // Asked of each section's own clip, because that is what gets
        // written. Over the whole set it answered two different questions
        // wrongly: an arrangement that puts kit A in one section and kit B
        // in another is two clips, each on one program, and was refused as
        // a two-kit clip; while an arrangement of nothing but breaks
        // passed and then failed inside `save` on a bare `require`.
        if (set.sections.isNotEmpty()) {
            for (index in set.sections.indices) {
                sectionRefusal(set, index)?.let { return it }
            }
            if (set.sections.all { it.plays.isEmpty() }) {
                return "EVERY SECTION HERE IS A BREAK. GIVE ONE A RING TO PLAY."
            }
            return null
        }
        return oneProgram(set) ?: noNotes(set)
    }

    /**
     * Why [index]'s section cannot be written, or null when it can.
     *
     * The one place that answers this, because [clipRefusal] and [clips]
     * have to answer it the same way: the preflight that says a set may
     * leave the screen and the writer that then writes it are the same
     * question asked twice, and when they disagreed the writer won
     * silently — a section the preflight would have named was dropped
     * between the screen and `groove.json`.
     *
     * A BREAK answers null: it writes nothing by design, and there is
     * nothing for the hardware to flip to for silence.
     */
    private fun sectionRefusal(set: OrbitSet, index: Int): String? {
        val section = set.sections[index]
        if (section.plays.isEmpty()) return null
        val sub = sectionSet(set, index)
        // Over the section's own WINDOW, because that is what gets
        // written. Asked of the rings' metadata instead, a one-bar section
        // holding a 64-step kitB ring whose only hit is step 63 was
        // refused as a two-kit clip - while the clip it would have written
        // has kitA's note and nothing of kitB's in it at all.
        oneProgram(sub, clipPulses(sectionSteps(set, index)))
            ?.let { return "SECTION ${section.name}: $it" }
        noNotes(sub)?.let { return "SECTION ${section.name}: $it" }
        // And the question `noNotes` cannot ask, because it knows the
        // rings but not the window they are being cut to.
        if (!sectionSounds(set, index)) {
            return "SECTION ${section.name} IS SILENT — ITS RINGS HAVE NO HIT IN ITS " +
                "${section.bars} BAR${if (section.bars == 1) "" else "S"}. " +
                "LENGTHEN IT, OR GIVE IT A RING THAT PLAYS."
        }
        return null
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
    private fun oneProgram(set: OrbitSet, limitPulses: Long? = null): String? {
        // Rings that actually put a note in the clip - engaged, a
        // pattern, and played onto. A hit-less ring contributes nothing
        // whatever kit it names, so counting it as a kit refuses a set
        // that would have exported fine, and with nothing played anywhere
        // it answers "two kits" where the truth is "no hits yet".
        //
        // [limitPulses] narrows "played onto" to the hits that SOUND
        // inside a window - what a section writes, rather than what its
        // rings contain.
        val sounding = set.orbits.associateWith { sounds(set, it, limitPulses) }
        val playing = set.orbits.filter {
            it.engaged && it.content is PatternOrbit && sounding.getValue(it).isNotEmpty()
        }
        val kits = playing.map { (it.content as PatternOrbit).kit }.distinct()
        if (kits.size > 1) {
            return "THESE RINGS PLAY ${kits.size} KITS — ${kits.joinToString(" AND ") { it.uppercase() }}. " +
                "ONE CLIP RIDES ONE PROGRAM. CLIP THEM A KIT AT A TIME."
        }
        val tooHigh = playing
            .flatMap { sounding.getValue(it) }
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
     * [ring]'s hits that would be written: all of them with no window, and
     * the ones that fire inside [limitPulses] when there is one. Empty for
     * a ring that is not a pattern at all.
     */
    private fun sounds(set: OrbitSet, ring: Orbit, limitPulses: Long?): List<OrbitHit> {
        val content = ring.content as? PatternOrbit ?: return emptyList()
        if (limitPulses == null) return content.hits
        return OrbitClock.pulseFirings(set, ring, limitPulses).map { it.hit }.distinct()
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
     * [steps] of [set] as a clip — its cycle by default, or a section's
     * own length. Only engaged pattern rings contribute;
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
        // The window this clip writes, in pulses. The clip is musical
        // time, so it is counted in the unit it is written in rather than
        // converted out of frames: a frame is the finer unit at any rate
        // worth playing at, but `sampleRate` is only required to be
        // positive, and with fewer than 960 frames to a beat — a rate
        // below 16 × BPM hertz — the conversion moves a note off its pulse.
        val limit = clipPulses(steps)
        // Over that window: a ring whose only hit falls outside it puts no
        // note in the file, so it names no kit here and its pad is not
        // this clip's to hold.
        oneProgram(set, limit)?.let { throw IllegalArgumentException(it) }
        noNotes(set)?.let { throw IllegalArgumentException(it) }
        val notes = ArrayList<Mpc3Note>()
        for (ring in set.orbits) {
            if (!ring.engaged || ring.content !is PatternOrbit) continue
            // No second filter after this one: `pulseFirings` widens its
            // lap walk and then filters every strike back into `0 until
            // limit` itself, so nothing it returns is outside the window.
            for (firing in OrbitClock.pulseFirings(set, ring, limit)) {
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
     * Whether [index]'s section would write any note at all.
     *
     * Asked of the section's own WINDOW rather than of its rings' metadata,
     * which is not the same question: a 64-step ring whose only hit is on
     * step 63, chosen for a one-bar section, has hits by any reading of the
     * ring and none at all inside the sixteen steps the section writes.
     * `noNotes` passed it and an empty clip went into `groove.json` -
     * the one thing `noNotes` exists to prevent.
     */
    fun sectionSounds(set: OrbitSet, index: Int): Boolean {
        if (set.sections[index].plays.isEmpty()) return false
        val sub = sectionSet(set, index)
        val limit = clipPulses(sectionSteps(set, index))
        return sub.orbits.any { ring ->
            ring.engaged && ring.content is PatternOrbit &&
                OrbitClock.pulseFirings(sub, ring, limit).isNotEmpty()
        }
    }

    /**
     * One clip per section — or the single whole-set clip when there is no
     * arrangement, which is byte for byte what [clip] wrote before
     * sections existed.
     *
     * A set with ONE section is not that case and is not byte-identical:
     * its clip takes the section's name, deliberately, because on the
     * hardware that name is the only thing left saying which sequence is
     * which. The music is the same; the label is the player's.
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
            // A break is a section that plays NO RINGS, and it is the only
            // one skipped without a word.
            if (set.sections[index].plays.isEmpty()) return@mapNotNull null
            // Every other section either writes its clip or says why not,
            // here rather than only in the preflight: [save] calls this
            // and not [clipRefusal], so a section skipped here was written
            // out of the arrangement in silence - a named section whose
            // rings are all snips, or muted, or whose only hit falls
            // outside its own window, gone between the screen and
            // `groove.json`, with the sections either side of it stored as
            // though that were the whole plan.
            sectionRefusal(set, index)?.let { throw IllegalArgumentException(it) }
            clip(
                sectionSet(set, index),
                name = "$NAME_PREFIX ${set.sections[index].name}",
                steps = sectionSteps(set, index),
            )
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
