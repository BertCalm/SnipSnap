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
 * for two things: a bijective pad map (note 35 had no pad at all, and the
 * old clamp collapsed all thirty-seven slots from 92 to 128 onto note 127,
 * so no inverse could say which pad it meant — `slotFor(127)` is 92 now),
 * and a per-hit lean, without which a note off the 16th grid could only be
 * rounded onto it and lost.
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

    @Test
    fun `the lean is measured from where the hit fires, so a swung set does not push it twice`() {
        // The import's promise is that material arrives where it was
        // recorded. `stepPulses` adds the set's swing to every odd step, so
        // an inverse that ignored swing was exact only into a straight set:
        // joining a swung one moved the source groove by the whole push.
        val notes = listOf(
            Mpc3Note(36, 0, 1f),
            Mpc3Note(36, s16 + 37, 0.8f),
            Mpc3Note(38, 3 * s16, 0.7f),
        )
        for (swing in OrbitSet.SWING_CHOICES) {
            val imported = OrbitImport.rings(clip(1, *notes.toTypedArray()), "break", kit(1, 3), bpm, rate, swing = swing)
            assertEquals(swing, imported.set.swing)
            assertEquals(
                notes.map { it.timePulses }.sorted(),
                OrbitClip.clip(imported.set).notes.map { it.timePulses }.sorted(),
                "at swing $swing the notes must land where the clip put them",
            )
        }
    }

    @Test
    fun `two notes the clip holds on one pad at one pulse become one hit, and are counted`() {
        // A clip may legally hold them; `OrbitClip.clip` keeps the louder on
        // the way out. Carrying both in would put two hits where only one
        // can come back, so the round trip would lose a note this import
        // had promised to carry. Deduped on the way in by the export's own
        // rule, and reported rather than dropped in silence.
        val dup = clip(
            1,
            Mpc3Note(36, 0, 0.4f),
            Mpc3Note(36, 0, 0.9f),
            Mpc3Note(38, s16, 0.5f),
        )
        val imported = OrbitImport.rings(dup, "break", kit(1, 3), bpm, rate)
        assertEquals(1, imported.collisions)
        assertTrue(!imported.complete, "a collision is something the player should be told about")
        assertEquals(listOf(0.9f), hitsOf(imported.set).map { it.velocity }, "the louder one survives, as on export")
        assertEquals(2, OrbitClip.clip(imported.set).notes.size, "and what comes back is what went in")
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

    @Test
    fun `a pickup and the downbeat it wraps onto share a step, and that is counted`() {
        // Ordinary material makes this: a note just before the bar line is
        // a pickup, which rounds FORWARD onto step 0 where the downbeat
        // already sits. Both sound and both export — their leans differ —
        // but the step grid draws one square per (step, pad), so the
        // import says how many squares hold more than one thing rather
        // than letting the player find out by tapping one.
        val imported = OrbitImport.rings(
            clip(1, Mpc3Note(36, 16 * s16 - 96, 0.7f), Mpc3Note(36, 0, 1f)),
            "break",
            kit(1),
            bpm,
            rate,
        )
        val hits = hitsOf(imported.set)
        assertEquals(2, hits.size, "nothing is merged: they are different moments")
        assertEquals(1, hits.map { it.step to it.slot }.distinct().size, "on one square")
        assertEquals(1, imported.crowded)
        assertTrue(!imported.complete, "a square holding two is worth saying")

        // And both still reach the clip, on the pulses they came in on.
        assertEquals(listOf(0L, 16 * s16 - 96), OrbitClip.clip(imported.set).notes.map { it.timePulses }.sorted())
    }

    @Test
    fun `a lean reaches a whole 16th where the destination swings, and never more`() {
        // The bound is not half a 16th: half the grid (120) plus the widest
        // push (`swingPush(75)`, also 120) is a full `MAX_OFFSET`, and a
        // note at pulse 120 into a swing-75 set hits it exactly. Swept
        // across every pulse of a bar and every swing the panel offers, so
        // a change that pushed it past the bound refuses rather than
        // silently clamping.
        var widest = 0L
        for (swing in OrbitSet.SWING_CHOICES) {
            for (t in 0 until Mpc3Clip.PULSES_PER_BAR) {
                val one = OrbitImport.rings(clip(1, Mpc3Note(36, t, 1f)), "break", kit(1), bpm, rate, swing = swing)
                val off = hitsOf(one.set).single().offset
                if (kotlin.math.abs(off) > kotlin.math.abs(widest)) widest = off
            }
        }
        assertEquals(-OrbitHit.MAX_OFFSET, widest, "the widest lean is a whole 16th early")
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
    fun `a note longer than a hit may sound refuses in the preflight, not from inside`() {
        // `Mpc3Clip` puts no ceiling on a note's length and `OrbitHit` does,
        // so without this `refusal` answered null and `rings` threw from
        // `hitFor` — a caller that checked first still got an exception,
        // which is the one thing a preflight exists to prevent.
        val long = Mpc3Clip("L", 1, listOf(Mpc3Note(36, 0, 1f, lengthPulses = OrbitHit.MAX_LENGTH + 1)))
        val why = OrbitImport.refusal(long)
        assertTrue(why != null && "note" in why, why ?: "no refusal")
        assertFailsWith<IllegalArgumentException> { OrbitImport.rings(long, "break", kit(1), bpm, rate) }

        // And exactly MAX_LENGTH is allowed through, so the ceiling is a
        // ceiling rather than one short of it.
        val atMax = Mpc3Clip("M", 1, listOf(Mpc3Note(36, 0, 1f, lengthPulses = OrbitHit.MAX_LENGTH)))
        assertEquals(null, OrbitImport.refusal(atMax))
        assertEquals(
            OrbitHit.MAX_LENGTH,
            (OrbitImport.rings(atMax, "break", kit(1), bpm, rate).set.orbits[0].content as PatternOrbit)
                .hits.single().length,
        )
    }

    @Test
    fun `a clip with no notes refuses by name`() {
        val empty = Mpc3Clip("Silence", 1, emptyList())
        assertTrue(OrbitImport.refusal(empty)!!.contains("no notes"), OrbitImport.refusal(empty)!!)
    }
}
