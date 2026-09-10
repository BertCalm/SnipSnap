package com.snipsnap.loop

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit

/**
 * A first set of rings from a kit, so the screen has something turning the
 * moment it opens instead of an empty circle and a manual.
 *
 * Four rings that demonstrate both modes at once:
 *
 *  - FLOOR, 16 steps, same speed: kick and snare as a plain bar — the still
 *    centre the others drift against.
 *  - HATS, 12 steps, same speed: closed hat every other step. Twelve against
 *    sixteen meet every 48 steps — three bars — and in between the hat's
 *    accents walk across the beat.
 *  - PERC, 20 steps, same speed: four hits spread round a five-beat ring, so
 *    the top of the ring lands on a different 16th each bar for five bars.
 *  - THREE, 3 steps, same lap: an open hat (or a tonal pad) three times per
 *    bar — a triplet against FLOOR's quarters, back on the downbeat every
 *    bar. The polyrhythm ring.
 *
 * Pads are chosen by class with the kit's first pad as the fallback, so any
 * kit with at least one pad gets a set, and a mis-classed kit still plays.
 */
object OrbitPresets {

    fun fromKit(kitFolder: String, kit: Kit, bpm: Float, sampleRate: Int): OrbitSet {
        require(kit.pads.isNotEmpty()) { "kit '${kit.name}' has no pads to put on a ring" }
        val fallback = kit.pads.minBy { it.slot }.slot
        fun slot(vararg classes: DrumClass): Int {
            for (c in classes) kit.pads.firstOrNull { it.drumClass == c }?.let { return it.slot }
            return fallback
        }

        val kick = slot(DrumClass.KICK, DrumClass.TOM)
        val snare = slot(DrumClass.SNARE, DrumClass.CLAP)
        val hat = slot(DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.PERC)
        val perc = slot(DrumClass.PERC, DrumClass.CLAP, DrumClass.TOM)
        val three = slot(DrumClass.HAT_OPEN, DrumClass.TONAL, DrumClass.PERC)

        val floor = Orbit(
            name = "FLOOR",
            steps = 16,
            content = PatternOrbit(
                kitFolder,
                listOf(
                    OrbitHit(0, kick, 0.95f),
                    OrbitHit(4, snare, 0.85f),
                    OrbitHit(8, kick, 0.9f),
                    OrbitHit(10, kick, 0.7f),
                    OrbitHit(12, snare, 0.9f),
                ),
            ),
        )
        val hats = Orbit(
            name = "HATS",
            steps = 12,
            content = PatternOrbit(
                kitFolder,
                (0 until 12 step 2).map { OrbitHit(it, hat, if (it % 4 == 0) 0.55f else 0.35f) },
            ),
        )
        val percs = Orbit(
            name = "PERC",
            steps = 20,
            content = PatternOrbit(
                kitFolder,
                listOf(OrbitHit(0, perc, 0.6f), OrbitHit(6, perc, 0.45f), OrbitHit(12, perc, 0.5f), OrbitHit(17, perc, 0.4f)),
            ),
            pan = 0.3f,
        )
        val triplet = Orbit(
            name = "THREE",
            steps = 3,
            mode = OrbitMode.SAME_LAP,
            content = PatternOrbit(
                kitFolder,
                listOf(OrbitHit(0, three, 0.5f), OrbitHit(1, three, 0.4f), OrbitHit(2, three, 0.4f)),
            ),
            pan = -0.3f,
        )
        return OrbitSet(listOf(floor, hats, percs, triplet), bpm, sampleRate)
    }

    /** A fresh, empty pattern ring — what ADD RING makes before the user puts hits on it. */
    fun emptyPattern(kitFolder: String, name: String, steps: Int = OrbitSet.DEFAULT_LAP_STEPS): Orbit =
        Orbit(name = name, steps = steps, content = PatternOrbit(kitFolder, emptyList()))

    /** A ring carrying [sampleFile], sized to the snip's own length at the set's tempo. */
    fun snipRing(set: OrbitSet, name: String, sampleFile: String, frameCount: Int): Orbit =
        Orbit(name = name, steps = OrbitClock.naturalSteps(set, frameCount), content = SnipOrbit(sampleFile))
}
