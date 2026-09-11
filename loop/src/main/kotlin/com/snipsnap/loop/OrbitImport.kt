package com.snipsnap.loop

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.Kit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note

/**
 * Clips → rings, the direction that did not exist.
 *
 * [OrbitClip] has always run one way. A set became a clip; nothing became
 * a set. So a captured break, an imported `.mid`, PROG E and every groove
 * the chop pipeline makes were material ORBIT could not play — three
 * sequencers in one app, one of them unreachable from the other two.
 *
 * The arithmetic is the easy half and is exact, which it could not have
 * been before [OrbitHit.offset] existed: a note's pulse becomes the 16th
 * it is nearest plus its lean off it, so a clip goes to rings and back
 * with every note on the pulse it started on — not merely within a 16th.
 * A note in the last half-16th of the last bar rounds *forward* to step 0
 * and carries a negative lean, which is the pickup [OrbitClock.firings]
 * already knows how to sound on the lap before.
 *
 * The real decision is how one clip becomes several rings, and it is
 * per-pad: a pad is a voice, a voice is a circle. That is what
 * [OrbitPresets.fromKit] builds and what the screen draws. Every ring
 * comes out the clip's own length, so an imported break first plays back
 * as itself; what makes it an ORBIT set rather than a clip in a circle is
 * the player then re-lengthing a ring and hearing it drift. Handing
 * someone a set that already drifts would be answering a question they
 * did not ask, of material they have not heard yet.
 */
object OrbitImport {

    /**
     * A set built from [clip], and what could not come with it.
     *
     * [skipped] is never silent: a note whose pad the kit does not have is
     * left out and named, the same policy [OrbitBank] already applies at
     * play time when a `(kit, slot)` is missing. It is not fatal either,
     * *unless* it is everything — a clip naming no pad the kit has has
     * nothing to open, and [rings] refuses rather than hand back an empty
     * set, so a caller holding an [Imported] knows at least one pad came.
     */
    data class Imported(
        val set: OrbitSet,
        val skipped: List<Skipped>,
        val shared: List<Int> = emptyList(),
        /** Notes the clip held twice on one pad at one pulse, which only one of could ever sound. */
        val collisions: Int = 0,
        /**
         * Cells where one pad has more than one hit on a single step.
         *
         * Ordinary material makes these: a note just before the downbeat
         * is a pickup, which rounds forward onto step 0 where the downbeat
         * already is. Both sound and both export, because their leans
         * differ — but the step grid draws one square per (step, pad), so
         * it can only show that *something* is there.
         */
        val crowded: Int = 0,
    ) {
        /** Whether every note arrived — nothing skipped, nothing merged, nothing sharing a square. */
        val complete: Boolean get() = skipped.isEmpty() && collisions == 0 && crowded == 0
    }

    /** A pad the clip asked for that the kit does not have, and how many notes wanted it. */
    data class Skipped(val note: Int, val slot: Int, val notes: Int)

    /**
     * Why [clip] cannot become a set of rings, or null when it can.
     *
     * Only two things genuinely do not fit, and neither can be worked
     * around by dropping material — which is the one outcome an import
     * must never choose.
     */
    fun refusal(clip: Mpc3Clip): String? {
        if (clip.notes.isEmpty()) return "'${clip.name}' has no notes to put on a ring"
        val steps = clip.bars * OrbitClip.CLIP_BAR_STEPS
        if (steps > Orbit.MAX_STEPS) {
            return "'${clip.name}' is ${clip.bars} bars, and a ring holds " +
                "${Orbit.MAX_STEPS / OrbitClip.CLIP_BAR_STEPS}"
        }
        // `Mpc3Clip` puts no ceiling on a note's length, and `OrbitHit` does.
        // Without this the refusal answered null and `rings` threw from
        // inside `hitFor` instead — so a caller that checked first still
        // got an exception, which is the one thing a preflight is for.
        val longest = clip.notes.maxOf { it.lengthPulses }
        if (longest > OrbitHit.MAX_LENGTH) {
            return "'${clip.name}' holds a note ${longest / Mpc3Clip.PULSES_PER_BAR} bars long, " +
                "and a hit may sound for ${OrbitHit.MAX_LENGTH / Mpc3Clip.PULSES_PER_BAR}"
        }
        return null
    }

    /**
     * [clip] as rings on [kit], at [bpm] and [sampleRate].
     *
     * [swing] is the swing of the set these rings are *going into*, and
     * the default is straight because a clip on its own is not going
     * anywhere else. It is not read out of the clip: the feel is already
     * in the notes, and inferring a percent to re-apply would push every
     * offbeat twice.
     *
     * It matters because `OrbitClock.stepPulses` adds the set's push to
     * every odd step, so a lean measured from the bare grid would arrive
     * on top of it. Measuring from the firing pulse instead is what lets
     * the same clip land where it was recorded whatever the destination
     * swings — and joining a swung set is the ordinary case, since the
     * screen passes the set the player is already listening to.
     */
    fun rings(
        clip: Mpc3Clip,
        kitFolder: String,
        kit: Kit,
        bpm: Float,
        sampleRate: Int,
        maxRings: Int = OrbitSet.MAX_ORBITS,
        swing: Int = OrbitSet.STRAIGHT_SWING,
    ): Imported {
        refusal(clip)?.let { throw IllegalArgumentException(it) }
        require(maxRings in 1..OrbitSet.MAX_ORBITS) { "a set holds 1..${OrbitSet.MAX_ORBITS} rings, asked for $maxRings" }
        val steps = clip.bars * OrbitClip.CLIP_BAR_STEPS
        val have = kit.pads.map { it.slot }.toSet()

        // The export's own collision rule, not a second copy of it: a clip
        // may legally hold two notes on one pad at one pulse, and
        // `OrbitClip.clip` keeps the louder on the way out. Keeping both
        // here would put two hits where only one can return, so the round
        // trip would quietly lose a note that the import had promised to
        // carry. Deduped on the way in, and counted, because a drop nobody
        // is told about is the thing this import must never do.
        val notes = GrooveEdit.dedupeLouder(clip.notes)
        val collisions = clip.notes.size - notes.size

        // Every note placed, then split by the pad it names. A note the kit
        // has no pad for is counted rather than carried: a ring naming a
        // slot that is not there would draw a lane that can never sound.
        val wanted = LinkedHashMap<Int, MutableList<OrbitHit>>()
        val missing = LinkedHashMap<Int, Int>()
        for (note in notes) {
            val slot = Mpc3Note.slotFor(note.note)
            if (slot !in have) {
                missing[note.note] = (missing[note.note] ?: 0) + 1
                continue
            }
            wanted.getOrPut(slot) { ArrayList() } += hitFor(note, slot, steps, swing)
        }
        val skipped = missing.entries.map { (note, count) -> Skipped(note, Mpc3Note.slotFor(note), count) }
        if (wanted.isEmpty()) {
            throw IllegalArgumentException(
                "'${clip.name}' names ${skipped.size} pad(s) that '${kit.name}' does not have, and nothing it does",
            )
        }

        val (own, sharing) = split(wanted, maxRings)
        val rings = ArrayList<Orbit>()
        for (slot in own) rings += ring(name(kit, slot), steps, kitFolder, listOf(slot), wanted.getValue(slot))
        if (sharing.isNotEmpty()) {
            rings += ring(
                name = sharedName(kit, sharing),
                steps = steps,
                kitFolder = kitFolder,
                voice = sharing,
                hits = sharing.flatMap { wanted.getValue(it) }.sortedWith(compareBy({ it.step }, { it.slot })),
            )
        }
        return Imported(
            set = OrbitSet(rings, bpm, sampleRate, lapSteps = OrbitClip.CLIP_BAR_STEPS, swing = swing),
            skipped = skipped,
            shared = sharing,
            collisions = collisions,
            // Counted after the rings are built, off the hits themselves,
            // rather than guessed from the notes: two notes a whole 16th
            // apart can still land on one step once a pickup wraps, and
            // only the placed hits know that.
            crowded = rings.sumOf { ring ->
                (ring.content as PatternOrbit).hits
                    .groupingBy { it.step to it.slot }
                    .eachCount()
                    .count { it.value > 1 }
            },
        )
    }

    /**
     * Which pads get a circle to themselves.
     *
     * A set holds [OrbitSet.MAX_ORBITS] rings and a kit holds far more
     * pads, so a busy break can name more voices than there are circles —
     * and fewer still when the import is joining rings already on screen,
     * which is why the budget is the caller's to say.
     * The row this implements said refuse; refusing loses the whole break
     * over its ninth pad, and an ordinary kit has a kick, a snare, two
     * hats, a clap, a rim and two toms before anything unusual happens.
     *
     * So the surplus shares the last ring instead. Nothing is dropped —
     * a ring's [Orbit.voice] holds as many pads as it likes, which is how
     * `OrbitPresets.bassRing` puts a whole scale on one circle — and the
     * player can pull a pad back out onto its own. The pads with the most
     * hits keep their own circle, because those are the ones worth
     * re-lengthing, and re-lengthing is the whole point of a ring.
     */
    private fun split(wanted: Map<Int, List<OrbitHit>>, maxRings: Int): Pair<List<Int>, List<Int>> {
        val slots = wanted.keys.sortedWith(compareByDescending<Int> { wanted.getValue(it).size }.thenBy { it })
        if (slots.size <= maxRings) return slots.sorted() to emptyList()
        val own = slots.take(maxRings - 1)
        return own.sorted() to slots.drop(maxRings - 1).sorted()
    }

    /**
     * [note] as a hit on [slot] of a ring [steps] long.
     *
     * The step is the 16th the note is *nearest*, not the one it is past,
     * so a note dragged a little early belongs to the beat it is leaning
     * into rather than the one before.
     *
     * The lean is whatever is left over — within half a 16th of the grid,
     * plus the set's own push where that step is swung, so up to a whole
     * 16th either way. That is exactly [OrbitHit.MAX_OFFSET] and never
     * more: half a 16th is 120 pulses, the widest push is
     * `swingPush(MAX_SWING)` at 120, and a note at pulse 120 into a
     * swing-75 set reaches the bound precisely.
     */
    private fun hitFor(note: Mpc3Note, slot: Int, steps: Int, swing: Int): OrbitHit {
        val nearest = Math.round(note.timePulses.toDouble() / Mpc3Clip.PULSES_PER_16TH)
        val step = Math.floorMod(nearest, steps.toLong()).toInt()
        // The lean is measured from where the hit will actually fire, which
        // includes the set's swing — `stepPulses` adds it, so the inverse
        // has to take it off. Without this the import is exact only into a
        // straight set: join a swung one and every odd step gets the set's
        // push on top of the note's own recorded time, moving material that
        // was supposed to arrive untouched.
        val fires = nearest * Mpc3Clip.PULSES_PER_16TH + swingAt(step, swing)
        return OrbitHit(
            step = step,
            slot = slot,
            velocity = note.velocity,
            offset = note.timePulses - fires,
            // The clip's own length, carried rather than discarded.
            //
            // A 16th comes in as WHOLE_SAMPLE, because 240 is `Mpc3Note`'s
            // default and it has no presence bit: a note that says 240 and
            // a note that says nothing are the same bytes, so a deliberate
            // one-16th gate cannot be told from an unspecified length and
            // does not survive a rings -> clip -> rings trip as a gate.
            // The other reading is worse by far — treating every bare note
            // as gated would gate every drum in every imported break at a
            // 16th, which is exactly the sound `WHOLE_SAMPLE` exists to
            // avoid. Clip -> rings -> clip is unaffected either way: the
            // export writes 240 for an ungated hit, so the number returns.
            length = if (note.lengthPulses == Mpc3Clip.PULSES_PER_16TH) OrbitHit.WHOLE_SAMPLE else note.lengthPulses,
        )
    }

    /**
     * The set's swing on [step], in pulses — [OrbitClock.swingPulses] for a
     * ring this import builds.
     *
     * It need not ask `stepIsSixteenth`: every ring here is FREE, so its
     * period in 16ths is its own step count and its step is a 16th by
     * construction. Worth stating rather than relying on, since the day a
     * ring here is spanned that stops being true.
     */
    private fun swingAt(step: Int, swing: Int): Long =
        if (step % 2 == 0 || swing == OrbitSet.STRAIGHT_SWING) 0L else Mpc3Clip.swingPush(swing)

    private fun ring(name: String, steps: Int, kitFolder: String, voice: List<Int>, hits: List<OrbitHit>) =
        Orbit(name = name, steps = steps, content = PatternOrbit(kitFolder, hits), voice = voice)

    /** A pad's own name where the kit gives one, else the slot — a ring's row has to say something. */
    private fun name(kit: Kit, slot: Int): String =
        kit.pads.firstOrNull { it.slot == slot }?.displayName?.uppercase()?.take(8)?.ifBlank { null }
            ?: "PAD $slot"

    private fun sharedName(kit: Kit, slots: List<Int>): String =
        if (slots.size == 1) name(kit, slots.single()) else "+${slots.size} PADS"
}
