package com.snipsnap.loop

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit

/**
 * A first set of rings from a kit, so the screen has something turning the
 * moment it opens instead of an empty circle and a manual.
 *
 * Every ring is one instrument, and the set follows what the kit has:
 *
 *  - KICK, 16 steps: the floor — one, the and of two, three.
 *  - SNARE, 16 steps: the backbeat. With the kick, the still centre the
 *    other rings drift against.
 *  - HATS, 12 steps: a closed hat every other step. Twelve against sixteen
 *    meet every 48 steps — three bars — and in between the accents walk
 *    across the beat.
 *  - PERC, 20 steps: four hits round a five-beat ring, so the top of the
 *    ring lands on a different 16th each bar for five bars.
 *  - THREE, 3 steps, locked to the bar: an open hat three times a bar — a
 *    triplet against the kick's quarters, back on the downbeat every bar.
 *  - BASS, 20 steps, when the kit has three or more tonal pads: a walking
 *    line over the tonal pads low to high, one ring for the whole melody.
 *
 * A class the kit lacks gets no ring; a kit with nothing recognisable gets
 * one 16-step ring on its first pad, so any kit with a pad plays.
 */
object OrbitPresets {

    fun fromKit(kitFolder: String, kit: Kit, bpm: Float, sampleRate: Int): OrbitSet {
        require(kit.pads.isNotEmpty()) { "kit '${kit.name}' has no pads to put on a ring" }
        fun first(vararg classes: DrumClass): Int? {
            for (c in classes) kit.pads.filter { it.drumClass == c }.minByOrNull { it.slot }?.let { return it.slot }
            return null
        }
        fun ring(name: String, steps: Int, slot: Int, hits: List<Pair<Int, Float>>, lock: Boolean = false, pan: Float = 0f) =
            Orbit(
                name = name,
                steps = steps,
                content = PatternOrbit(kitFolder, hits.map { (step, vel) -> OrbitHit(step, slot, vel) }),
                lockToBar = lock,
                voice = listOf(slot),
                pan = pan,
            )

        val rings = ArrayList<Orbit>()
        first(DrumClass.KICK, DrumClass.TOM)?.let { kick ->
            rings += ring("KICK", 16, kick, listOf(0 to 0.95f, 8 to 0.9f, 10 to 0.7f))
        }
        first(DrumClass.SNARE, DrumClass.CLAP)?.let { snare ->
            rings += ring("SNARE", 16, snare, listOf(4 to 0.85f, 12 to 0.9f))
        }
        first(DrumClass.HAT_CLOSED)?.let { hat ->
            rings += ring("HATS", 12, hat, (0 until 12 step 2).map { it to if (it % 4 == 0) 0.55f else 0.35f })
        }
        first(DrumClass.PERC, DrumClass.CLAP)?.let { perc ->
            rings += ring("PERC", 20, perc, listOf(0 to 0.6f, 6 to 0.45f, 12 to 0.5f, 17 to 0.4f), pan = 0.3f)
        }
        first(DrumClass.HAT_OPEN)?.let { open ->
            rings += ring("THREE", 3, open, listOf(0 to 0.5f, 1 to 0.4f, 2 to 0.4f), lock = true, pan = -0.3f)
        }
        val tonal = kit.pads.filter { it.drumClass == DrumClass.TONAL }.map { it.slot }.sorted()
        if (tonal.size >= MELODIC_MIN_PADS) rings += bassRing(kitFolder, tonal)

        if (rings.isEmpty()) {
            val only = kit.pads.minBy { it.slot }.slot
            rings += ring(kit.pads.minBy { it.slot }.displayName.uppercase().take(8), 16, only, listOf(0 to 0.9f, 8 to 0.8f))
        }
        return OrbitSet(rings.take(OrbitSet.MAX_ORBITS), bpm, sampleRate)
    }

    /**
     * A walking bass line over [tonalSlots] (low to high) on a 20-step ring:
     * root on the downbeat, up to the third, back, up to the fifth-ish, with
     * a pickup into the wrap — five beats that lean into the drums' four.
     */
    fun bassRing(kitFolder: String, tonalSlots: List<Int>): Orbit {
        require(tonalSlots.size >= MELODIC_MIN_PADS) { "a bass ring wants at least $MELODIC_MIN_PADS tonal pads: two notes is not a line" }
        fun note(i: Int) = tonalSlots[i.coerceIn(0, tonalSlots.size - 1)]
        val line = listOf(
            OrbitHit(0, note(0), 0.9f),
            OrbitHit(3, note(0), 0.6f),
            OrbitHit(6, note(2), 0.7f),
            OrbitHit(10, note(0), 0.85f),
            OrbitHit(13, note(1), 0.6f),
            OrbitHit(16, note(4), 0.7f),
            OrbitHit(18, note(3), 0.5f),
        )
        return Orbit(name = "BASS", steps = 20, content = PatternOrbit(kitFolder, line), voice = tonalSlots)
    }

    /** A fresh, empty pattern ring on [pads] — what + PAD RING makes before the user puts hits on it. */
    fun emptyPattern(kitFolder: String, name: String, pads: List<Int>, steps: Int = OrbitSet.DEFAULT_LAP_STEPS): Orbit =
        Orbit(name = name, steps = steps, content = PatternOrbit(kitFolder, emptyList()), voice = pads)

    /** A ring carrying [sampleFile], sized to the snip's own length at the set's tempo. */
    fun snipRing(set: OrbitSet, name: String, sampleFile: String, frameCount: Int): Orbit =
        Orbit(name = name, steps = OrbitClock.naturalSteps(set, frameCount), content = SnipOrbit(sampleFile))

    /** Fewer tonal pads than this and a kit gets no BASS ring: two notes is not a line. */
    const val MELODIC_MIN_PADS = 3
}
