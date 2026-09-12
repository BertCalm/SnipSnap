package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.mpc3.Mpc3Clip
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sections: "these rings for eight bars, then those".
 *
 * A set looped forever. The grid has had `Arrangement` since the
 * beginning and the groove side has `BeatTape.arrange`; rings had
 * neither, so there was no way to say the sentence above.
 *
 * **A section restarts its rings**, and that is the claim to hold onto,
 * because both of the row's own exit criteria rest on it. An arrangement
 * has to *repeat*, and a section's exported clip has to be true every
 * time the hardware flips to it — neither survives the other reading, a
 * section as a mute lane over a set that keeps turning underneath, where
 * the rings would be somewhere else on the second pass.
 */
class OrbitSectionTest {

    private val rate = 48_000
    private val bpm = 120f
    private val step = 6_000 // frames per 16th at 120 BPM, 48 kHz
    private val bar = 16L * step // one reference bar of 16 steps

    private fun ring(name: String, slot: Int, vararg steps: Int) =
        Orbit(name, 16, PatternOrbit("kit", steps.map { OrbitHit(it, slot) }))

    private fun set(vararg sections: OrbitSection) = OrbitSet(
        listOf(ring("A", 1, 0), ring("B", 2, 8)),
        bpm,
        rate,
        sections = sections.toList(),
    )

    /** A pad that holds a constant 0.5, so "is it sounding?" reads off the buffer. */
    private class Sustain : SampleSource {
        override fun loop(sampleFile: String): Snip? = null
        override fun pad(kit: String, slot: Int): Snip? =
            if (slot in 1..9) Snip(FloatArray(48_000) { 0.5f }, 1, 48_000) else null
    }

    /**
     * A pad that holds slot/10, so the buffer says *which* ring struck.
     *
     * [Sustain] answers "did anything sound"; a section test usually needs
     * "did the right thing sound", and the two rings are otherwise
     * indistinguishable in the mix.
     */
    private class Graded : SampleSource {
        override fun loop(sampleFile: String): Snip? = null
        override fun pad(kit: String, slot: Int): Snip? =
            if (slot in 1..9) Snip(FloatArray(48_000) { slot / 10f }, 1, 48_000) else null
    }

    // ---- a set with no arrangement is untouched ----

    @Test
    fun `no sections means the clock hands back the frame it was given`() {
        val plain = OrbitSet(listOf(ring("A", 1, 0)), bpm, rate)
        assertEquals(OrbitClock.NO_SECTION, OrbitClock.sectionAt(plain, 0L))
        assertEquals(OrbitClock.NO_SECTION, OrbitClock.sectionAt(plain, 999_999L))
        assertEquals(999_999L, OrbitClock.localFrame(plain, 999_999L))
        assertEquals(OrbitClock.NO_BOUNDARY, OrbitClock.nextBoundary(plain, 0L))
        assertEquals(0L, OrbitClock.arrangementFrames(plain))
        // And every ring plays, since there is no section to say otherwise.
        assertTrue(OrbitClock.playsAt(plain, 0, 12_345L))
    }

    // ---- the arrangement ----

    @Test
    fun `each section lasts its own length, and the arrangement comes round`() {
        val s = set(OrbitSection("A", 2, setOf(0)), OrbitSection("B", 1, setOf(1)))
        assertEquals(3 * bar, OrbitClock.arrangementFrames(s))
        // Section 0 for two bars, section 1 for one, then round again.
        assertEquals(0, OrbitClock.sectionAt(s, 0L))
        assertEquals(0, OrbitClock.sectionAt(s, 2 * bar - 1))
        assertEquals(1, OrbitClock.sectionAt(s, 2 * bar))
        assertEquals(1, OrbitClock.sectionAt(s, 3 * bar - 1))
        assertEquals(0, OrbitClock.sectionAt(s, 3 * bar), "the arrangement must repeat")
        assertEquals(1, OrbitClock.sectionAt(s, 5 * bar))
    }

    @Test
    fun `a section restarts its rings - that is the whole of what it does to the clock`() {
        val s = set(OrbitSection("A", 2, setOf(0)), OrbitSection("B", 1, setOf(1)))
        // Halfway through section 0.
        assertEquals(bar, OrbitClock.localFrame(s, bar))
        // The instant section 1 begins, its rings are back on their downbeat.
        assertEquals(0L, OrbitClock.localFrame(s, 2 * bar))
        assertEquals(0.0, OrbitClock.phase(s, s.orbits[1], OrbitClock.localFrame(s, 2 * bar)))
        // And on the arrangement's second pass, section 0 begins where it
        // began the first time — which is what "repeats" means.
        assertEquals(0L, OrbitClock.localFrame(s, 3 * bar))
    }

    @Test
    fun `a section says which rings play, and a mute still wins over it`() {
        val s = set(OrbitSection("A", 1, setOf(0)), OrbitSection("B", 1, setOf(0, 1)))
        assertTrue(OrbitClock.playsAt(s, 0, 0L))
        assertTrue(!OrbitClock.playsAt(s, 1, 0L), "ring B is not in section A")
        assertTrue(OrbitClock.playsAt(s, 1, bar))
        // `engaged` is the player's mute and is a separate question the
        // engine asks alongside this one; the clock does not conflate them.
        assertTrue(OrbitClock.playsAt(s.copy(orbits = s.orbits.map { it.copy(engaged = false) }), 0, 0L))
    }

    @Test
    fun `a break is a section that plays nothing`() {
        val s = set(OrbitSection("IN", 1, setOf(0)), OrbitSection("DROP", 1, emptySet()))
        assertTrue(!OrbitClock.playsAt(s, 0, bar))
        assertTrue(!OrbitClock.playsAt(s, 1, bar))
        assertTrue(OrbitClock.playsAt(s, 0, 0L))
    }

    @Test
    fun `the next boundary is where the block has to be cut`() {
        val s = set(OrbitSection("A", 2, setOf(0)), OrbitSection("B", 1, setOf(1)))
        assertEquals(2 * bar, OrbitClock.nextBoundary(s, 0L))
        assertEquals(2 * bar, OrbitClock.nextBoundary(s, 2 * bar - 1))
        assertEquals(3 * bar, OrbitClock.nextBoundary(s, 2 * bar))
        // Never at or before where it was asked, or the engine would spin.
        for (at in longArrayOf(0, 1, bar, 2 * bar, 3 * bar - 1, 7 * bar + 13)) {
            assertTrue(OrbitClock.nextBoundary(s, at) > at, "boundary did not advance at $at")
        }
    }

    // ---- the engine ----

    @Test
    fun `the engine plays each section's rings and only those`() {
        val s = set(OrbitSection("A", 1, setOf(0)), OrbitSection("B", 1, setOf(1)))
        val out = OrbitEngine.render(s, OrbitBank.prepare(s, Sustain()), (2 * bar).toInt()).samples
        // Ring A hits step 0 of section 0; ring B hits step 8 of section 1.
        assertTrue(out[(step / 2) * 2] > 0f, "section A's ring did not sound")
        // Step 8 of section 0 belongs to ring B, which section A excludes.
        val intoSectionAStep8 = (8 * step + step / 2) * 2
        assertEquals(0f, out[intoSectionAStep8], "ring B sounded inside section A")
        // In section 1, ring B's step-8 hit does sound — counted from the
        // section's own start, which is the restart this feature is.
        val intoSectionBStep8 = ((bar + 8 * step + step / 2) * 2).toInt()
        assertTrue(out[intoSectionBStep8] > 0f, "section B's ring did not sound")
    }

    @Test
    fun `a block straddling a boundary is cut, and the new section's ring is the one heard`() {
        // A bar is 96,000 frames and a block is 2,048, so the boundary
        // falls squarely inside a block (96000 / 2048 = 46.875). If the
        // engine attributed that whole block to the section it *started*
        // in, the new section's downbeat would be missed and the old
        // section's ring would strike instead — which is why the two
        // sections here play DIFFERENT rings on different pads, and why
        // the test reads the audio rather than re-deriving the arithmetic
        // it is meant to be checking.
        // Both rings strike their own downbeat, so the question the audio
        // answers is only "which section owned the boundary".
        val s = OrbitSet(
            listOf(ring("A", 1, 0), ring("B", 2, 0)),
            bpm,
            rate,
            sections = listOf(OrbitSection("A", 1, setOf(0)), OrbitSection("B", 1, setOf(1))),
        )
        val out = OrbitEngine.render(s, OrbitBank.prepare(s, Graded()), (2 * bar).toInt()).samples
        // Pad 1 opens the arrangement; pad 2 opens the second section, on
        // the boundary itself.
        assertEquals(0.1f, out[(step / 2) * 2], 1e-4f, "section A's own pad did not open the arrangement")
        assertEquals(0.2f, out[(bar + step / 2).toInt() * 2], 1e-4f, "the boundary block kept playing the section before it")
    }

    // ---- the export ----

    @Test
    fun `a one-section set exports exactly what a no-section set does`() {
        // The byte-identity the row asks for, stated as the clip itself:
        // the same rings with an arrangement naming all of them for the
        // cycle's length write the same notes as the same rings with none.
        val plain = OrbitSet(listOf(ring("A", 1, 0, 4), ring("B", 2, 8)), bpm, rate)
        val bars = OrbitClip.bars(plain)
        val one = plain.copy(sections = listOf(OrbitSection("ALL", bars, setOf(0, 1))))
        val a = OrbitClip.clips(plain).single()
        val b = OrbitClip.clips(one).single()
        assertEquals(a.bars, b.bars)
        assertEquals(a.notes, b.notes)
    }

    @Test
    fun `each section becomes its own clip, named and as long as it is`() {
        val s = set(OrbitSection("INTRO", 2, setOf(0)), OrbitSection("DROP", 4, setOf(0, 1)))
        val clips = OrbitClip.clips(s)
        assertEquals(listOf("ORBIT INTRO", "ORBIT DROP"), clips.map { it.name })
        assertEquals(2, clips[0].bars)
        assertEquals(4, clips[1].bars)
        // INTRO plays only ring A (one hit a bar); DROP plays both.
        assertEquals(2, clips[0].notes.size)
        assertEquals(8, clips[1].notes.size)
    }

    @Test
    fun `a section counts in the set's own bar, not the clip's`() {
        // A 3/4 set: eight of its bars is 96 sixteenths, which the clip's
        // bar of sixteen calls six.
        val threeFour = OrbitSet(
            listOf(ring("A", 1, 0)),
            bpm,
            rate,
            lapSteps = 12,
            sections = listOf(OrbitSection("A", 8, setOf(0))),
        )
        assertEquals(96L, OrbitClip.sectionSteps(threeFour, 0))
        assertEquals(6, OrbitClip.clips(threeFour).single().bars)
    }

    @Test
    fun `a break writes no clip, because there is nothing to flip to for silence`() {
        val s = set(OrbitSection("IN", 2, setOf(0)), OrbitSection("DROP", 1, emptySet()))
        assertEquals(listOf("ORBIT IN"), OrbitClip.clips(s).map { it.name })
    }

    @Test
    fun `the clips are appended, so an arrangement never becomes the kit's base`() {
        val dir = Files.createTempDirectory("orbit-sections").toFile()
        val base = Mpc3Clip("Break", 2, listOf(com.snipsnap.mpc3.Mpc3Note(36, 0, 0.9f)))
        com.snipsnap.kit.GrooveStore.save(dir, listOf(base))
        val s = set(OrbitSection("INTRO", 1, setOf(0)), OrbitSection("DROP", 1, setOf(0, 1)))
        OrbitClip.save(dir, s)
        val stored = com.snipsnap.kit.GrooveStore.load(dir)
        assertEquals("Break", stored.first().name, "the kit's base must stay the kit's base")
        assertEquals(listOf("Break", "ORBIT INTRO", "ORBIT DROP"), stored.map { it.name })
        // And saving again replaces every previous ORBIT clip, not just one.
        OrbitClip.save(dir, set(OrbitSection("ONE", 1, setOf(0))))
        assertEquals(listOf("Break", "ORBIT ONE"), com.snipsnap.kit.GrooveStore.load(dir).map { it.name })
    }

    // ---- a snip ring restarts with its section too ----

    @Test
    fun `a snip ring restarts with its section rather than playing on through it`() {
        // A 20-step snip ring against 16-step sections: the ring's period
        // does not divide the section, so "restarted" and "carried on" are
        // different samples at the boundary and the buffer can tell them
        // apart. A taped loop still playing the section before would be
        // the one thing on screen that ignored the arrangement.
        val ramp = Snip(FloatArray(20 * step * 2) { (it / 2) / (20f * step) }, 2, rate)
        val s = OrbitSet(
            listOf(Orbit("tape", 20, SnipOrbit("a.wav"))),
            bpm,
            rate,
            sections = listOf(OrbitSection("A", 1, setOf(0)), OrbitSection("B", 1, setOf(0))),
        )
        val bank = OrbitBank.prepare(s, object : SampleSource {
            override fun loop(sampleFile: String): Snip = ramp
            override fun pad(kit: String, slot: Int): Snip? = null
        })
        val out = OrbitEngine.render(s, bank, (2 * bar).toInt()).samples
        // The instant section B begins, the tape is back at its head.
        assertEquals(0f, out[bar.toInt() * 2], 1e-3f, "the snip played on through the section change")
        // And it was genuinely somewhere else a frame earlier, so this is
        // a restart rather than a ring that happened to line up.
        assertTrue(out[(bar - 1).toInt() * 2] > 0.5f, "the snip was not mid-loop before the boundary")
    }

    // ---- an edit that moves a ring moves what the sections point at ----

    @Test
    fun `deleting a ring re-points the arrangement at the rings that are left`() {
        val three = OrbitSet(
            listOf(ring("A", 1, 0), ring("B", 2, 4), ring("C", 3, 8)),
            bpm,
            rate,
            sections = listOf(OrbitSection("X", 1, setOf(0, 2)), OrbitSection("Y", 1, setOf(1, 2))),
        )
        // Drop the middle ring: index 2 becomes index 1, and index 1 goes.
        val after = three.withoutOrbit(1)
        assertEquals(listOf("A", "C"), after.orbits.map { it.name })
        assertEquals(setOf(0, 1), after.sections[0].plays, "C must still play in X")
        assertEquals(setOf(1), after.sections[1].plays, "Y kept B, which is gone, and C, which moved")
        // Dropping the last ring leaves a section that plays nothing,
        // which is a break - not a set that refuses to exist.
        assertEquals(setOf(0), three.withoutOrbit(2).sections[0].plays)
    }

    @Test
    fun `a duplicated ring plays wherever the ring it came from plays`() {
        val two = OrbitSet(
            listOf(ring("A", 1, 0), ring("B", 2, 4)),
            bpm,
            rate,
            sections = listOf(OrbitSection("X", 1, setOf(0)), OrbitSection("Y", 1, setOf(1))),
        )
        val after = two.withOrbitAfter(0, ring("A2", 4, 2))
        assertEquals(listOf("A", "A2", "B"), after.orbits.map { it.name })
        assertEquals(setOf(0, 1), after.sections[0].plays, "the copy joins the section its original is in")
        assertEquals(setOf(2), after.sections[1].plays, "B moved from 1 to 2")
    }

    @Test
    fun `a section too long for a clip is refused in words, not thrown at the caller`() {
        // 64 bars of 8/4 is 2,048 sixteenths, which the clip's bar of
        // sixteen calls 128 — twice the ceiling. `refusal` used to answer
        // null here and `Mpc3Clip` then threw "bars out of range: 128".
        val long = OrbitSet(
            listOf(ring("A", 1, 0)),
            bpm,
            rate,
            lapSteps = 32,
            sections = listOf(OrbitSection("LONG", 64, setOf(0))),
        )
        val why = OrbitClip.refusal(long)
        assertTrue(why != null && why.contains("LONG") && why.contains("128"), "said: $why")
        // Halve it and the same set is fine.
        val ok = long.copy(sections = listOf(OrbitSection("LONG", 32, setOf(0))))
        assertEquals(null, OrbitClip.refusal(ok))
        assertEquals(64, OrbitClip.clips(ok).single().bars)
    }

    @Test
    fun `an arrangement is capped per section, not by the rings' own cycle`() {
        // Rings that meet only after 80 bars: with no arrangement that is
        // a refusal, and with one it is not, because what leaves the
        // screen is each section rather than the cycle.
        val coprime = OrbitSet(
            listOf(
                Orbit("A", 64, PatternOrbit("kit", listOf(OrbitHit(0, 1)))),
                Orbit("B", 63, PatternOrbit("kit", listOf(OrbitHit(0, 2)))),
                Orbit("C", 61, PatternOrbit("kit", listOf(OrbitHit(0, 3)))),
            ),
            bpm,
            rate,
        )
        assertTrue(OrbitClip.refusal(coprime) != null, "the bare cycle must still refuse")
        val arranged = coprime.copy(sections = listOf(OrbitSection("A", 4, setOf(0, 1, 2))))
        assertEquals(null, OrbitClip.refusal(arranged))
        assertEquals(4, OrbitClip.clips(arranged).single().bars)
    }

    @Test
    fun `the bar readout counts round the plan, not round a meeting the plan never reaches`() {
        // Rings of 16 and 20 meet after five bars; an arrangement of
        // 2 + 1 bars comes round after three and never gets there.
        val plain = OrbitSet(listOf(ring("A", 1, 0), Orbit("B", 20, PatternOrbit("kit", listOf(OrbitHit(0, 2))))), bpm, rate)
        assertEquals(5, OrbitClock.transportBars(plain))
        val arranged = plain.copy(sections = listOf(OrbitSection("A", 2, setOf(0)), OrbitSection("B", 1, setOf(1))))
        assertEquals(3, OrbitClock.transportBars(arranged))
        assertEquals(1, OrbitClock.transportBar(arranged, 0L))
        assertEquals(2, OrbitClock.transportBar(arranged, bar))
        assertEquals(3, OrbitClock.transportBar(arranged, 2 * bar))
        assertEquals(1, OrbitClock.transportBar(arranged, 3 * bar), "it must come back to bar 1 with the plan")
        // And a set with no arrangement counts as it always did.
        assertEquals(1, OrbitClock.transportBar(plain, 0L))
        assertEquals(3, OrbitClock.transportBar(plain, 2 * bar))
    }

    // ---- the file ----

    @Test
    fun `sections round-trip through orbits json`() {
        val s = set(OrbitSection("INTRO", 2, setOf(0)), OrbitSection("DROP", 4, setOf(0, 1)), OrbitSection("BREAK", 1, emptySet()))
        val dir = Files.createTempDirectory("orbit-sec-json").toFile()
        OrbitStore.save(s, dir)
        assertEquals(7, OrbitStore.VERSION)
        assertEquals(s.sections, OrbitStore.load(dir).sections)
        // A set with no arrangement writes no `sections` key at all.
        val plain = OrbitSet(listOf(ring("A", 1, 0)), bpm, rate)
        val plainDir = Files.createTempDirectory("orbit-sec-none").toFile()
        OrbitStore.save(plain, plainDir)
        assertTrue(!java.io.File(plainDir, OrbitStore.FILE_NAME).readText().contains("sections"))
        assertEquals(emptyList(), OrbitStore.load(plainDir).sections)
    }

    @Test
    fun `a version 6 file still loads, with no arrangement`() {
        val dir = Files.createTempDirectory("orbit-v6").toFile()
        java.io.File(dir, OrbitStore.FILE_NAME).writeText(
            """
            {"version":6,"bpm":90.0,"lapSteps":16,"swing":50,"sampleRate":48000,"seed":3,"orbits":[
              {"name":"R","steps":16,"span":"FREE","voice":[1],"engaged":true,"level":1.0,"pan":0.0,
               "content":{"type":"pattern","kit":"kit","hits":[{"step":0,"slot":1,"velocity":1.0}]}}
            ]}
            """.trimIndent(),
        )
        val back = OrbitStore.load(dir)
        assertEquals(emptyList(), back.sections)
        assertEquals(3, back.seed)
    }

    // ---- what a set refuses to be ----

    @Test
    fun `an arrangement cannot name a ring that is not there`() {
        val two = listOf(ring("A", 1, 0), ring("B", 2, 8))
        assertFailsWith<IllegalArgumentException> {
            OrbitSet(two, bpm, rate, sections = listOf(OrbitSection("X", 1, setOf(0, 2))))
        }
        assertFailsWith<IllegalArgumentException> { OrbitSection("X", 0, setOf(0)) }
        assertFailsWith<IllegalArgumentException> { OrbitSection("", 1, setOf(0)) }
        assertFailsWith<IllegalArgumentException> { OrbitSection("X", OrbitClip.MAX_BARS + 1, setOf(0)) }
        assertFailsWith<IllegalArgumentException> {
            OrbitSet(two, bpm, rate, sections = (0..OrbitSection.MAX_SECTIONS).map { OrbitSection("S$it", 1, setOf(0)) })
        }
    }
}
