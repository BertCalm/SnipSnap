package com.snipsnap.loop

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
     * [skipped] is never silent and never fatal: a note whose pad the kit
     * does not have is left out and named, the same policy [OrbitBank]
     * already applies at play time when a `(kit, slot)` is missing.
     */
    data class Imported(val set: OrbitSet, val skipped: List<Skipped>, val shared: List<Int> = emptyList()) {
        /** Whether anything was left behind — worth a line on screen when true. */
        val complete: Boolean get() = skipped.isEmpty()
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
        return null
    }

    /**
     * [clip] as rings on [kit], at [bpm] and [sampleRate].
     *
     * The set is straight — `swing` stays at [OrbitSet.STRAIGHT_SWING] —
     * because the clip's feel is already in its notes. Reading a swing
     * percent back out of the pulses and then applying it again would
     * push every offbeat twice, and a donor's pocket is sixteen numbers
     * anyway, which is what the per-hit lean is for.
     */
    fun rings(
        clip: Mpc3Clip,
        kitFolder: String,
        kit: Kit,
        bpm: Float,
        sampleRate: Int,
        maxRings: Int = OrbitSet.MAX_ORBITS,
    ): Imported {
        refusal(clip)?.let { throw IllegalArgumentException(it) }
        require(maxRings in 1..OrbitSet.MAX_ORBITS) { "a set holds 1..${OrbitSet.MAX_ORBITS} rings, asked for $maxRings" }
        val steps = clip.bars * OrbitClip.CLIP_BAR_STEPS
        val have = kit.pads.map { it.slot }.toSet()

        // Every note placed, then split by the pad it names. A note the kit
        // has no pad for is counted rather than carried: a ring naming a
        // slot that is not there would draw a lane that can never sound.
        val wanted = LinkedHashMap<Int, MutableList<OrbitHit>>()
        val missing = LinkedHashMap<Int, Int>()
        for (note in clip.notes) {
            val slot = Mpc3Note.slotFor(note.note)
            if (slot !in have) {
                missing[note.note] = (missing[note.note] ?: 0) + 1
                continue
            }
            wanted.getOrPut(slot) { ArrayList() } += hitFor(note, slot, steps)
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
            set = OrbitSet(rings, bpm, sampleRate, lapSteps = OrbitClip.CLIP_BAR_STEPS),
            skipped = skipped,
            shared = sharing,
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
     * into rather than the one before. The lean is then whatever is left,
     * always within half a 16th and so always inside [OrbitHit.MAX_OFFSET].
     */
    private fun hitFor(note: Mpc3Note, slot: Int, steps: Int): OrbitHit {
        val nearest = Math.round(note.timePulses.toDouble() / Mpc3Clip.PULSES_PER_16TH)
        val offset = note.timePulses - nearest * Mpc3Clip.PULSES_PER_16TH
        return OrbitHit(
            step = Math.floorMod(nearest, steps.toLong()).toInt(),
            slot = slot,
            velocity = note.velocity,
            offset = offset,
        )
    }

    private fun ring(name: String, steps: Int, kitFolder: String, voice: List<Int>, hits: List<OrbitHit>) =
        Orbit(name = name, steps = steps, content = PatternOrbit(kitFolder, hits), voice = voice)

    /** A pad's own name where the kit gives one, else the slot — a ring's row has to say something. */
    private fun name(kit: Kit, slot: Int): String =
        kit.pads.firstOrNull { it.slot == slot }?.displayName?.uppercase()?.take(8)?.ifBlank { null }
            ?: "PAD $slot"

    private fun sharedName(kit: Kit, slots: List<Int>): String =
        if (slots.size == 1) name(kit, slots.single()) else "+${slots.size} PADS"
}
