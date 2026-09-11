package com.snipsnap.loop

import com.snipsnap.kit.GrooveFeel
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Per-hit time on a ring — the pocket the feel stack could not reach.
 *
 * A set had one `swing` for all of it, and swing can only push the odd
 * 16th of a pair by one amount. A donor's feel is sixteen amounts; a
 * humanised take is one per hit. `OrbitHit` carried `(step, slot,
 * velocity)` and had nowhere to put either, so a ring could be swung and
 * nothing else, while the same material as a clip could be swung,
 * humanised, quantised and given a stolen pocket.
 */
class OrbitFeelTest {

    private val rate = 48_000
    private val bpm = 120f
    private val step = 6_000L // a 16th at 120 BPM, 48 kHz
    private val s16 = Mpc3Clip.PULSES_PER_16TH

    private fun ring(steps: Int, span: OrbitSpan = OrbitSpan.FREE, vararg hits: OrbitHit) =
        Orbit("r", steps, PatternOrbit("kit", hits.toList()), span = span)

    private fun set(vararg orbits: Orbit, swing: Int = OrbitSet.STRAIGHT_SWING) =
        OrbitSet(orbits.toList(), bpm, rate, swing = swing)

    // ---- the offset itself ----

    @Test
    fun `a hit leans off its step, in both directions`() {
        val late = set(ring(16, hits = arrayOf(OrbitHit(4, 1, offset = 60))))
        val early = set(ring(16, hits = arrayOf(OrbitHit(4, 1, offset = -60))))
        val straight = set(ring(16, hits = arrayOf(OrbitHit(4, 1))))

        fun firstFrame(s: OrbitSet) = OrbitClock.firings(s, s.orbits[0], 0, 16 * step).first().frame

        assertEquals(4 * step, firstFrame(straight))
        // 60 pulses is a quarter of a 16th: 1500 frames at this tempo.
        assertEquals(4 * step + 1_500, firstFrame(late))
        assertEquals(4 * step - 1_500, firstFrame(early))
    }

    @Test
    fun `a lean past a 16th is refused rather than silently folded onto another step`() {
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, offset = OrbitHit.MAX_OFFSET + 1) }
        assertFailsWith<IllegalArgumentException> { OrbitHit(0, 1, offset = -OrbitHit.MAX_OFFSET - 1) }
        // The boundary itself is fine.
        OrbitHit(0, 1, offset = OrbitHit.MAX_OFFSET)
        OrbitHit(0, 1, offset = -OrbitHit.MAX_OFFSET)
    }

    @Test
    fun `a hit dragged before the downbeat sounds at the end of the lap before`() {
        // There is nowhere earlier than step 0 on this lap, so the pickup
        // belongs to the turn before - which is what a pickup is.
        val s = set(ring(16, hits = arrayOf(OrbitHit(0, 1, offset = -120))))
        val frames = OrbitClock.firings(s, s.orbits[0], 0, 32 * step).map { it.frame }
        val lap = 16 * step
        assertEquals(listOf(lap - 3_000, 2 * lap - 3_000), frames)
    }

    @Test
    fun `the lean reaches the exported clip, in pulses, unrounded away`() {
        val s = set(ring(16, hits = arrayOf(OrbitHit(0, 1), OrbitHit(4, 1, offset = 37))))
        assertEquals(listOf(0L, 4 * s16 + 37), OrbitClip.clip(s).notes.map { it.timePulses })
    }

    // ---- a donor's feel, onto rings ----

    /** A donor that leans progressively later across the bar, as a real one does. */
    private fun donor(): GrooveFeel.Template {
        val notes = (0 until GrooveFeel.POSITIONS).map { pos ->
            Mpc3Note(36, pos * s16 + pos * 2L, 0.8f)
        }
        return GrooveFeel.extract(Mpc3Clip("Donor", 1, notes))
    }

    @Test
    fun `a template's sixteen offsets reach a ring's hits`() {
        val t = donor()
        val hits = (0 until 16).map { OrbitHit(it, 1) }.toTypedArray()
        val feeling = OrbitFeel.apply(set(ring(16, hits = hits)), t)

        val got = (feeling.orbits[0].content as PatternOrbit).hits.map { it.offset }
        assertEquals((0 until 16).map { it * 2L }, got, "each step takes its own position's lean")
        // And the export carries all sixteen, not one number for the set.
        assertEquals(
            (0 until 16).map { it * s16 + it * 2L },
            OrbitClip.clip(feeling).notes.map { it.timePulses },
        )
    }

    @Test
    fun `a position the donor never played leaves the hit straight`() {
        // A donor with only the downbeat: position 0 has a lean, the
        // other fifteen are silent and contribute nothing.
        val sparse = GrooveFeel.extract(Mpc3Clip("Sparse", 1, listOf(Mpc3Note(36, 9, 0.8f))))
        val hits = arrayOf(OrbitHit(0, 1), OrbitHit(3, 1), OrbitHit(7, 1))
        val feeling = OrbitFeel.apply(set(ring(16, hits = hits)), sparse)
        assertEquals(listOf(9L, 0L, 0L), (feeling.orbits[0].content as PatternOrbit).hits.map { it.offset })
    }

    @Test
    fun `a spanned ring takes the feel of the sixteenths it actually lands on`() {
        // Three steps across one bar: 0, 5.33 and 10.67 sixteenths, which
        // round to positions 0, 5 and 11. This is the ring ORBIT exists
        // for, and it could not be given a pocket at all before.
        val three = ring(3, OrbitSpan.ONE, OrbitHit(0, 1), OrbitHit(1, 1), OrbitHit(2, 1))
        val s = set(three)
        assertEquals(0, OrbitFeel.positionOf(s, three, OrbitHit(0, 1)))
        assertEquals(5, OrbitFeel.positionOf(s, three, OrbitHit(1, 1)))
        assertEquals(11, OrbitFeel.positionOf(s, three, OrbitHit(2, 1)))

        val feeling = OrbitFeel.apply(s, donor())
        assertEquals(listOf(0L, 10L, 22L), (feeling.orbits[0].content as PatternOrbit).hits.map { it.offset })
    }

    @Test
    fun `swing does not renumber a hit's position`() {
        // The set is swung, so step 1 sounds late - but it is still the
        // second 16th and takes the second 16th's lean, not the third's.
        //
        // At the top of the ladder the push is half a step, so a position
        // read off the SOUNDING frame rounds 1.5 up to 2 and the hit takes
        // the wrong sixteenth's pocket. Milder swings hide it: 66 pushes
        // to 1.32, which rounds back to 1 whether or not the swing was
        // counted, and proves nothing.
        val r = ring(16, hits = arrayOf(OrbitHit(1, 1)))
        for (swing in listOf(50, 66, 75)) {
            val swung = set(r, swing = swing)
            assertEquals(1, OrbitFeel.positionOf(swung, r, OrbitHit(1, 1)), "at swing $swing")
        }

        // And the pocket really is the second position's, not the third's.
        val feeling = OrbitFeel.apply(set(r, swing = 75), donor())
        assertEquals(2L, (feeling.orbits[0].content as PatternOrbit).hits.single().offset)
    }

    // ---- the ring the screen draws is the ring you hear ----

    @Test
    fun `the flare follows the hit's lean, not its step`() {
        // The dot's position and the strike flare both read the firing
        // time. They used to sum the step's place and the swing and stop
        // there, so a hit given a pocket sounded late while its dot sat on
        // the grid - the engine and the screen disagreeing about one hit.
        val leaning = OrbitHit(4, 1, offset = 60)
        val r = ring(16, hits = arrayOf(leaning))
        val s = set(r)
        val sounds = OrbitClock.firings(s, r, 0, 16 * step).first().frame

        assertEquals(sounds, OrbitClock.firingOffset(s, r, leaning), "the ring is drawn where the hit fires")
        assertEquals(0L, OrbitClock.framesSinceFiring(s, r, leaning, sounds), "the flare strikes as it sounds")
        // And on the step's old place it is nearly a whole lap stale.
        assertTrue(OrbitClock.framesSinceFiring(s, r, leaning, 4 * step) > 0, "not still flaring on the grid")
    }

    @Test
    fun `a ring and a clip agree on the pulse at every swing the panel offers`() {
        // The contract the shared push exists for, across the whole ladder
        // rather than at one percent. Each path reaches the pulse its own
        // way - the ring through frames and a rounding, the clip through
        // pulses directly - so a regression in either rounding puts them
        // back a pulse apart without any single-path test noticing.
        for (pct in OrbitSet.SWING_CHOICES) {
            val r = ring(16, hits = arrayOf(OrbitHit(0, 1), OrbitHit(1, 1)))
            val ringPulses = OrbitClip.clip(set(r, swing = pct)).notes.map { it.timePulses }
            val flat = Mpc3Clip("G", 1, listOf(Mpc3Note(36, 0, 1f), Mpc3Note(36, s16, 1f)))
            val clipPulses = com.snipsnap.kit.GrooveVariations.swing(flat, pct).notes.map { it.timePulses }
            assertEquals(clipPulses, ringPulses, "a ring and a clip swung to $pct must write the same pulse")
            assertEquals(listOf(0L, s16 + Mpc3Clip.swingPush(pct)), ringPulses, "and it is the shared push, at $pct")
        }
    }

    // ---- humanize ----

    @Test
    fun `a humanized set re-renders identically for one seed, and differently for another`() {
        val hits = (0 until 8).map { OrbitHit(it * 2, 1) }.toTypedArray()
        val plain = set(ring(16, hits = hits))

        val a = OrbitFeel.humanize(plain, 0.5f, seed = 7)
        val b = OrbitFeel.humanize(plain, 0.5f, seed = 7)
        val c = OrbitFeel.humanize(plain, 0.5f, seed = 8)

        fun offsets(s: OrbitSet) = (s.orbits[0].content as PatternOrbit).hits.map { it.offset }
        assertEquals(offsets(a), offsets(b), "one seed, one take")
        assertTrue(offsets(a) != offsets(c), "a different seed is a different take")
        assertEquals(OrbitClip.clip(a).notes, OrbitClip.clip(b).notes, "and it exports the same file")
    }

    @Test
    fun `humanize stays inside half a sixteenth of the step, and zero means straight`() {
        val hits = (0 until 16).map { OrbitHit(it, 1) }.toTypedArray()
        val plain = set(ring(16, hits = hits))
        val loose = OrbitFeel.humanize(plain, 1f, seed = 3)
        val offsets = (loose.orbits[0].content as PatternOrbit).hits.map { it.offset }
        assertTrue(offsets.all { it in -(s16 / 2)..(s16 / 2) }, "a full amount is half a 16th: $offsets")
        assertTrue(offsets.any { it != 0L }, "something actually moved")

        val none = OrbitFeel.humanize(plain, 0f, seed = 3)
        assertTrue((none.orbits[0].content as PatternOrbit).hits.all { it.offset == 0L })
        assertFailsWith<IllegalArgumentException> { OrbitFeel.humanize(plain, 1.5f, seed = 1) }
    }

    @Test
    fun `straighten puts every hit back on its step`() {
        val hits = (0 until 8).map { OrbitHit(it, 1, offset = 40) }.toTypedArray()
        val back = OrbitFeel.straighten(set(ring(16, hits = hits)))
        assertTrue((back.orbits[0].content as PatternOrbit).hits.all { it.offset == 0L })
    }

    @Test
    fun `a snip ring is carried through untouched`() {
        val s = OrbitSet(
            listOf(
                ring(16, hits = arrayOf(OrbitHit(0, 1))),
                Orbit("snip", 16, SnipOrbit("loop.wav")),
            ),
            bpm,
            rate,
        )
        val out = OrbitFeel.humanize(s, 1f, seed = 5)
        assertEquals(s.orbits[1], out.orbits[1], "a snip has no hits to lean")
    }

    // ---- it survives the file ----

    @Test
    fun `an offset round-trips through orbits json, and an older file still loads`() {
        val dir = Files.createTempDirectory("orbit-feel").toFile().also { it.deleteOnExit() }
        val s = set(ring(16, hits = arrayOf(OrbitHit(0, 1), OrbitHit(4, 1, offset = -31))))
        OrbitStore.save(s, dir)
        val back = OrbitStore.load(dir)
        assertEquals(listOf(0L, -31L), (back!!.orbits[0].content as PatternOrbit).hits.map { it.offset })

        // A straight hit writes no offset key at all, so its shape is the
        // one every previous build wrote. The file is not byte-identical —
        // `version` itself goes 3 to 4 — but nothing about the hit changes.
        val straightDir = Files.createTempDirectory("orbit-straight").toFile().also { it.deleteOnExit() }
        OrbitStore.save(set(ring(16, hits = arrayOf(OrbitHit(0, 1)))), straightDir)
        val text = java.io.File(straightDir, OrbitStore.FILE_NAME).readText()
        assertTrue("offset" !in text, "a straight hit says nothing about lean:\n$text")
    }
}
