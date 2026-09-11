package com.snipsnap.loop

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.math.ceil

/**
 * A set of rings as one MPC clip: every ring's firings over one full cycle,
 * flattened onto the 960-PPQ grid the native export already carries, so a
 * polymeter rides to the MPC inside the kit's `groove.json` like any other
 * groove. The cycle is the clip's length — 16 against 20 is a five-bar
 * clip — which is why a long cycle is refused rather than truncated: a
 * clip that stops before the rings meet is a different piece of music.
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
    fun bars(set: OrbitSet): Int =
        ceil(OrbitClock.cycleSteps(set).toDouble() / CLIP_BAR_STEPS).toInt().coerceAtLeast(1)

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
     * Null when one cycle of [set] can leave the screen at all, else the
     * refusal in words. This is the ceiling both ways out share — the
     * bounce is one cycle of audio and the clip is one cycle of notes, and
     * neither has anywhere to put more than [MAX_BARS] of it.
     *
     * Deliberately not the place for "there are no notes in this": a set
     * of snip rings has nothing to clip and is still perfectly good to
     * bounce, so that question belongs to [clipRefusal] alone.
     */
    fun refusal(set: OrbitSet): String? {
        val bars = bars(set)
        if (bars <= MAX_BARS) return null
        val unit = if (countsDifferently(set)) "BARS OF 4/4" else "BARS"
        return "THE RINGS MEET EVERY $bars $unit — A CLIP STOPS AT $MAX_BARS. SHORTEN A RING."
    }

    /**
     * Null when [set] would write a clip worth writing, else why not: the
     * shared ceiling first, then the reasons peculiar to a clip. A caller
     * that only wants audio wants [refusal] instead.
     */
    fun clipRefusal(set: OrbitSet): String? = refusal(set) ?: noNotes(set)

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
     * snip rings are audio and have no notes. Pad A0N plays note 35+N, the
     * writer's chromatic map, as the GROOVE step editor already does.
     */
    fun clip(set: OrbitSet, name: String = nameFor(set)): Mpc3Clip {
        clipRefusal(set)?.let { throw IllegalArgumentException(it) }
        val bars = bars(set)
        val stepFrames = OrbitClock.stepFrames(set).toDouble()
        val cycle = OrbitClock.cycleFrames(set)
        val limit = bars * Mpc3Clip.PULSES_PER_BAR
        val notes = ArrayList<Mpc3Note>()
        for (ring in set.orbits) {
            if (!ring.engaged || ring.content !is PatternOrbit) continue
            for (firing in OrbitClock.firings(set, ring, 0, cycle)) {
                val pulses = Math.round(firing.frame / stepFrames * Mpc3Clip.PULSES_PER_16TH)
                if (pulses >= limit) continue
                notes += Mpc3Note(
                    note = noteFor(firing.hit.slot),
                    timePulses = pulses,
                    velocity = firing.hit.velocity,
                )
            }
        }
        return Mpc3Clip(name = name, bars = bars, notes = GrooveEdit.dedupeLouder(notes))
    }

    /** The writer's chromatic map, as [Mpc3Note] documents it: pad A0N plays note 36+N−1. */
    fun noteFor(slot: Int): Int = (35 + slot).coerceIn(0, 127)

    /** Whether [clip] is an ORBIT clip — by its name, the same way PROG E is found. */
    fun isOrbit(clip: Mpc3Clip): Boolean = clip.name.startsWith(NAME_PREFIX)

    /**
     * Write [set]'s clip into the kit's grooves, replacing the last ORBIT
     * clip and leaving every other stored clip — the captured base, the
     * variations, PROG E — untouched. Returns the clip written.
     */
    fun save(kitDir: File, set: OrbitSet): Mpc3Clip {
        val clip = clip(set)
        val others = GrooveStore.load(kitDir).filterNot { isOrbit(it) }
        GrooveStore.save(kitDir, others + clip)
        return clip
    }
}
