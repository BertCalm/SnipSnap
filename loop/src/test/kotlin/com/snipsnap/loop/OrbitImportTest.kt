package com.snipsnap.loop

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Clips → rings, the direction that did not exist.
 *
 * `OrbitClip` ran one way, so a captured break, a `.mid` and PROG E were
 * all material the live engine could not play. The arithmetic had to wait
 * for two things: a bijective pad map (note 35 had no pad before, and note
 * 127 had ninety-seven), and a per-hit lean, without which a note off the
 * 16th grid could only be rounded onto it and lost.
 */
class OrbitImportTest {

    private val bpm = 120f
    private val rate = 48_000
    private val s16 = Mpc3Clip.PULSES_PER_16TH

    /** A kit with pads on every slot the tests reach for, named so rings can be named. */
    private fun kit(vararg slots: Int) = Kit(
        "Break Kit",
        slots.map { KitPad(slot = it, sampleFile = "pad$it.wav", displayName = "PAD$it") },
    )

    private fun clip(bars: Int = 1, vararg notes: Mpc3Note) = Mpc3Clip("Break", bars, notes.toList())

    private fun hitsOf(set: OrbitSet, ring: Int = 0) = (set.orbits[ring].content as PatternOrbit).hits

    // ---- the arithmetic ----

    @Test
    fun `a clip goes to rings and back on the same pulse, not merely the same 16th`() {
        // The row asked for a round trip "within a 16th", which is what it
        // would have been before `OrbitHit.offset` existed. It is exact:
        // every note here is deliberately off the grid, and every one comes
        // back on the pulse it left on.
        val notes = listOf(
            Mpc3Note(36, 0, 1f),
            Mpc3Note(38, 4 * s16 + 37, 0.8f),
            Mpc3Note(42, 2 * s16 - 11, 0.4f),
            Mpc3Note(42, 6 * s16 + 119, 0.35f),
            Mpc3Note(46, 14 * s16 + 3, 0.6f),
        )
        val imported = OrbitImport.rings(clip(1, *notes.toTypedArray()), "break", kit(1, 3, 7, 11), bpm, rate)
        assertTrue(imported.complete, "every pad is in the kit: ${imported.skipped}")

        val back = OrbitClip.clip(imported.set)
        assertEquals(
            notes.map { Triple(it.note, it.timePulses, it.velocity) }.sortedBy { it.second },
            back.notes.map { Triple(it.note, it.timePulses, it.velocity) }.sortedBy { it.second },
            "one clip in, the same clip out",
        )
    }

    @Test
    fun `a note in the last half-16th becomes a pickup rather than a step off the end`() {
        // 15.6 sixteenths of a 16-step bar rounds FORWARD to 16, which is
        // no step at all. It becomes step 0 leaning early, which is what a
        // pickup before the downbeat is — and `firings` already sounds such
        // a hit at the end of the lap before, so it still round-trips.
        val late = Mpc3Note(36, 16 * s16 - 96, 0.9f)
        val imported = OrbitImport.rings(clip(1, late), "break", kit(1), bpm, rate)

        val hit = hitsOf(imported.set).single()
        assertEquals(0, hit.step, "it belongs to the downbeat it is leaning into")
        assertEquals(-96L, hit.offset, "and arrives early by what is left")

        assertEquals(listOf(late.timePulses), OrbitClip.clip(imported.set).notes.map { it.timePulses })
    }

    @Test
    fun `every note in 0 to 127 lands on the pad slotFor names`() {
        // Note 35 is the case that used to be impossible: the inverse was
        // written by hand as `note - 35`, so GM's Acoustic Bass Drum asked
        // for slot 0 and `OrbitHit` refused it. The upper banks are the
        // other end of the same break.
        for (note in 0..127) {
            val slot = Mpc3Note.slotFor(note)
            val imported = OrbitImport.rings(clip(1, Mpc3Note(note, 0, 0.7f)), "break", kit(slot), bpm, rate)
            assertEquals(slot, hitsOf(imported.set).single().slot, "note $note")
            assertEquals(note, OrbitClip.clip(imported.set).notes.single().note, "note $note round-trips")
        }
    }

    // ---- one clip, several rings ----

    @Test
    fun `each pad gets its own ring, named after the pad`() {
        val imported = OrbitImport.rings(
            clip(
                1,
                Mpc3Note(36, 0, 1f),
                Mpc3Note(38, 4 * s16, 0.8f),
                Mpc3Note(42, 2 * s16, 0.4f),
            ),
            "break",
            kit(1, 3, 7),
            bpm,
            rate,
        )
        assertEquals(3, imported.set.orbits.size, "three pads, three circles")
        assertEquals(listOf("PAD1", "PAD3", "PAD7"), imported.set.orbits.map { it.name })
        assertEquals(listOf(listOf(1), listOf(3), listOf(7)), imported.set.orbits.map { it.voice })
    }

    @Test
    fun `every ring comes out the clip's own length, so it first plays back as itself`() {
        val two = clip(2, Mpc3Note(36, 0, 1f), Mpc3Note(38, 20 * s16, 0.8f))
        val imported = OrbitImport.rings(two, "break", kit(1, 3), bpm, rate)
        assertTrue(imported.set.orbits.all { it.steps == 32 }, "two bars is 32 sixteenths")
        assertEquals(OrbitSpan.FREE, imported.set.orbits[0].span)
        assertEquals(OrbitClip.CLIP_BAR_STEPS, imported.set.lapSteps)
        assertEquals(OrbitSet.STRAIGHT_SWING, imported.set.swing, "the feel is in the notes, not a set-level swing")
    }

    @Test
    fun `more pads than there are rings share the last circle rather than losing a pad`() {
        // Nine pads, eight rings. The row said refuse; refusing loses a
        // whole break over its ninth pad, and a kick, snare, two hats, a
        // clap, a rim and two toms is eight before anything unusual.
        val notes = ArrayList<Mpc3Note>()
        for (pad in 1..9) {
            // Pad 1 is busiest, pad 9 quietest, so the ranking is unambiguous.
            repeat(10 - pad) { i -> notes += Mpc3Note(Mpc3Note.noteFor(pad), i * s16, 0.6f) }
        }
        val imported = OrbitImport.rings(clip(1, *notes.toTypedArray()), "break", kit(*(1..9).toList().toIntArray()), bpm, rate)

        assertEquals(OrbitSet.MAX_ORBITS, imported.set.orbits.size)
        assertEquals(listOf(8, 9), imported.shared, "the two quietest pads share")
        assertEquals("+2 PADS", imported.set.orbits.last().name)
        assertEquals(listOf(8, 9), imported.set.orbits.last().voice)

        // Nothing is dropped: every note still exports.
        assertEquals(notes.size, OrbitClip.clip(imported.set).notes.size)
        assertTrue(imported.complete, "sharing a circle is not skipping")
    }

    @Test
    fun `a smaller budget shares sooner, because an import may be joining rings already on screen`() {
        // Three pads into two circles: the busiest keeps its own, the other
        // two share. The set's eight is the ceiling, not the budget — what
        // is left after the rings already on screen is.
        val notes = ArrayList<Mpc3Note>()
        repeat(4) { i -> notes += Mpc3Note(Mpc3Note.noteFor(1), i * 4L * s16, 0.9f) }
        notes += Mpc3Note(Mpc3Note.noteFor(2), 4 * s16, 0.8f)
        notes += Mpc3Note(Mpc3Note.noteFor(3), 12 * s16, 0.7f)

        val imported = OrbitImport.rings(clip(1, *notes.toTypedArray()), "break", kit(1, 2, 3), bpm, rate, maxRings = 2)
        assertEquals(2, imported.set.orbits.size)
        assertEquals(listOf(2, 3), imported.shared)
        assertEquals(notes.size, OrbitClip.clip(imported.set).notes.size, "still nothing dropped")

        assertFailsWith<IllegalArgumentException> {
            OrbitImport.rings(clip(1, *notes.toTypedArray()), "break", kit(1, 2, 3), bpm, rate, maxRings = 0)
        }
    }

    // ---- what cannot come ----

    @Test
    fun `a note whose pad the kit lacks is left out and named, and the rest still opens`() {
        val imported = OrbitImport.rings(
            clip(
                1,
                Mpc3Note(36, 0, 1f),
                Mpc3Note(38, 4 * s16, 0.8f),
                Mpc3Note(38, 12 * s16, 0.7f),
            ),
            "break",
            kit(1),
            bpm,
            rate,
        )
        assertEquals(1, imported.set.orbits.size, "the pad the kit has still opens")
        assertTrue(!imported.complete)
        assertEquals(listOf(OrbitImport.Skipped(note = 38, slot = 3, notes = 2)), imported.skipped)
    }

    @Test
    fun `a clip naming no pad the kit has refuses rather than opening empty`() {
        val e = assertFailsWith<IllegalArgumentException> {
            OrbitImport.rings(clip(1, Mpc3Note(38, 0, 1f)), "break", kit(1), bpm, rate)
        }
        assertTrue("does not have" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `a clip longer than a ring refuses with its length rather than truncating`() {
        val long = Mpc3Clip("Long", 5, listOf(Mpc3Note(36, 0, 1f)))
        assertTrue(OrbitImport.refusal(long)!!.contains("5 bars"), OrbitImport.refusal(long)!!)
        assertFailsWith<IllegalArgumentException> { OrbitImport.rings(long, "break", kit(1), bpm, rate) }

        // Four bars is exactly a ring, and must not refuse.
        val four = Mpc3Clip("Four", 4, listOf(Mpc3Note(36, 0, 1f)))
        assertEquals(null, OrbitImport.refusal(four))
        assertEquals(Orbit.MAX_STEPS, OrbitImport.rings(four, "break", kit(1), bpm, rate).set.orbits.single().steps)
    }

    @Test
    fun `a clip with no notes refuses by name`() {
        val empty = Mpc3Clip("Silence", 1, emptyList())
        assertTrue(OrbitImport.refusal(empty)!!.contains("no notes"), OrbitImport.refusal(empty)!!)
    }
}
