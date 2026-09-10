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

    /** Every ORBIT clip's name starts with this, so saving replaces the last one and the GROOVE screen can tell it apart. */
    const val NAME_PREFIX = "ORBIT"

    /** The clip's name for [set]: the ratio says what the rings are. */
    fun nameFor(set: OrbitSet): String = "$NAME_PREFIX ${OrbitClock.ratioLabel(set).replace(" : ", ":")}".trim()

    /** How many bars the clip (and the bounce) would be: the cycle, rounded up. */
    fun bars(set: OrbitSet): Int = ceil(OrbitClock.cycleBars(set)).toInt().coerceAtLeast(1)

    /** Null when [set] fits, else the refusal in words. */
    fun refusal(set: OrbitSet): String? {
        val bars = bars(set)
        return if (bars > MAX_BARS) "THE RINGS MEET EVERY $bars BARS — A CLIP STOPS AT $MAX_BARS. SHORTEN A RING." else null
    }

    /**
     * One cycle of [set] as a clip. Only engaged pattern rings contribute;
     * snip rings are audio and have no notes. Pad A0N plays note 35+N, the
     * writer's chromatic map, as the GROOVE step editor already does.
     */
    fun clip(set: OrbitSet, name: String = nameFor(set)): Mpc3Clip {
        refusal(set)?.let { throw IllegalArgumentException(it) }
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
