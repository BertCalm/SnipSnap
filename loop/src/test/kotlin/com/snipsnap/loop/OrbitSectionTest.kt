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
    fun `a set with no arrangement exports byte-identically, name included`() {
        // This is the row's byte-identity criterion, and it is about a set
        // with NO arrangement - which is what every set was and what most
        // stay. Stated as the whole clip rather than as its notes, because
        // `Mpc3Clip.name` is serialised and is part of what "identical"
        // has to mean.
        val plain = OrbitSet(listOf(ring("A", 1, 0, 4), ring("B", 2, 8)), bpm, rate)
        assertEquals(OrbitClip.clip(plain), OrbitClip.clips(plain).single())
        // "1", not "1:1": both rings are sixteen steps, so `ratioLabel`
        // reduces the distinct lengths to one number.
        assertEquals("ORBIT 1", OrbitClip.clips(plain).single().name)
    }

    @Test
    fun `a one-section set writes the same notes, under the section's own name`() {
        // Deliberately NOT byte-identical, and the PR said so too loosely
        // before review caught it: a section has a name the player gave
        // it, and the clip is named after the section because on the
        // hardware that name is the only thing saying which is which.
        // What carries over unchanged is the music.
        val plain = OrbitSet(listOf(ring("A", 1, 0, 4), ring("B", 2, 8)), bpm, rate)
        val one = plain.copy(sections = listOf(OrbitSection("ALL", OrbitClip.bars(plain), setOf(0, 1))))
        val a = OrbitClip.clips(plain).single()
        val b = OrbitClip.clips(one).single()
        assertEquals(a.bars, b.bars)
        assertEquals(a.notes, b.notes)
        assertEquals("ORBIT ALL", b.name)
        assertTrue(a.name != b.name, "the section's name is the point of naming it")
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
        // The CLIP names the section, because a section IS a clip.
        val why = OrbitClip.clipRefusal(long)
        assertTrue(why != null && why.contains("LONG") && why.contains("128"), "said: $why")
        // The BOUNCE says the bounce's sentence about the same set: a
        // section too long to clip makes the plan holding it too long to
        // bounce, and a player who wants audio should not be told what a
        // clip stops at.
        val bounce = OrbitClip.refusal(long)
        assertTrue(bounce != null && bounce.contains("PLAN") && bounce.contains("128"), "said: $bounce")
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

    // ---- review round 1 ----

    @Test
    fun `a bounce is one turn of the plan, and the plan is what the ceiling measures`() {
        // Rings of 64, 63 and 61 meet after 246,078 sixteenths. With no
        // arrangement that is refused; with a three-bar plan the bounce is
        // three bars, and refusing it would refuse the very set an
        // arrangement exists to make renderable.
        val coprime = OrbitSet(
            listOf(
                Orbit("A", 64, PatternOrbit("kit", listOf(OrbitHit(0, 1)))),
                Orbit("B", 63, PatternOrbit("kit", listOf(OrbitHit(0, 2)))),
                Orbit("C", 61, PatternOrbit("kit", listOf(OrbitHit(0, 3)))),
            ),
            bpm,
            rate,
        )
        assertEquals(OrbitClock.cycleSteps(coprime), OrbitClock.transportSteps(coprime))
        assertTrue(OrbitClip.refusal(coprime) != null)
        val arranged = coprime.copy(sections = listOf(OrbitSection("A", 2, setOf(0)), OrbitSection("B", 1, setOf(1))))
        assertEquals(48L, OrbitClock.transportSteps(arranged), "three bars of sixteen")
        assertEquals(3 * bar, OrbitClock.transportFrames(arranged))
        assertEquals(null, OrbitClip.refusal(arranged))
        // And a plan longer than the ceiling is refused in the plan's own
        // words - the rings never meet at all here, so "shorten a ring"
        // would be the wrong instruction.
        val huge = coprime.copy(sections = listOf(OrbitSection("A", 40, setOf(0)), OrbitSection("B", 40, setOf(1))))
        val why = OrbitClip.refusal(huge)
        assertTrue(why != null && why.contains("PLAN") && why.contains("SECTION"), "said: $why")
    }

    @Test
    fun `the preflight asks each section, not the whole set`() {
        val kitA = Orbit("A", 16, PatternOrbit("kitA", listOf(OrbitHit(0, 1))))
        val kitB = Orbit("B", 16, PatternOrbit("kitB", listOf(OrbitHit(0, 2))))
        val twoKits = OrbitSet(listOf(kitA, kitB), bpm, rate)
        // Together in one clip they cannot go: one clip rides one program.
        assertTrue(OrbitClip.clipRefusal(twoKits)!!.contains("KITS"))
        // A section each, and they are two clips on one program apiece.
        val split = twoKits.copy(sections = listOf(OrbitSection("A", 1, setOf(0)), OrbitSection("B", 1, setOf(1))))
        assertEquals(null, OrbitClip.clipRefusal(split))
        assertEquals(listOf("ORBIT A", "ORBIT B"), OrbitClip.clips(split).map { it.name })
        // An arrangement of nothing but breaks is refused in words here,
        // rather than passing and failing on a bare `require` inside save.
        val allBreaks = twoKits.copy(sections = listOf(OrbitSection("A", 1, emptySet())))
        assertTrue(OrbitClip.clipRefusal(allBreaks)!!.contains("BREAK"))
    }

    @Test
    fun `a named section that cannot be written is named, not silently dropped`() {
        // Its ring is a snip: audio, which a note-only clip can never
        // carry. Skipping on "no notes" swallowed such a section whole.
        val s = OrbitSet(
            listOf(ring("A", 1, 0), Orbit("tape", 16, SnipOrbit("a.wav"))),
            bpm,
            rate,
            sections = listOf(OrbitSection("IN", 1, setOf(0)), OrbitSection("TAPE", 1, setOf(1))),
        )
        val why = OrbitClip.clipRefusal(s)
        assertTrue(why != null && why.contains("TAPE"), "said: $why")
        // A break, by contrast, is still skipped without a word.
        val withBreak = s.copy(sections = listOf(OrbitSection("IN", 1, setOf(0)), OrbitSection("DROP", 1, emptySet())))
        assertEquals(null, OrbitClip.clipRefusal(withBreak))
        assertEquals(listOf("ORBIT IN"), OrbitClip.clips(withBreak).map { it.name })
    }

    @Test
    fun `the kit refuses more grooves than a project can carry`() {
        val dir = Files.createTempDirectory("orbit-seq-cap").toFile()
        val room = com.snipsnap.mpc3.Mpc3ProjectWriter.MAX_SEQUENCES
        val others = (1..room - 1).map { Mpc3Clip("Groove $it", 1, listOf(com.snipsnap.mpc3.Mpc3Note(36, 0, 0.9f))) }
        com.snipsnap.kit.GrooveStore.save(dir, others)
        val two = set(OrbitSection("A", 1, setOf(0)), OrbitSection("B", 1, setOf(1)))
        // 31 + 2 is past the hardware's list, and the export would take the
        // first 32 and drop the rest without a word.
        val why = assertFailsWith<IllegalArgumentException> { OrbitClip.save(dir, two) }
        assertTrue(why.message!!.contains("$room"), "said: ${why.message}")
        // One section fits exactly, and is written.
        assertEquals(1, OrbitClip.save(dir, set(OrbitSection("A", 1, setOf(0)))).size)
    }

    // ---- review round 2 ----

    @Test
    fun `a voice does not ring on into a section that leaves its ring out`() {
        // The hit is on step 15, so it fires at frame 90,000 and its pad
        // runs 48,000 frames - alive well past the boundary at 96,000.
        // Sampled a thousand frames INSIDE the break, where the voice must
        // still be live for the question to mean anything: the first probe
        // I wrote sampled past the voice's own end and proved nothing.
        val r = Orbit("A", 16, PatternOrbit("kit", listOf(OrbitHit(15, 1))))
        val s = OrbitSet(
            listOf(r),
            bpm,
            rate,
            sections = listOf(OrbitSection("A", 1, setOf(0)), OrbitSection("DROP", 1, emptySet())),
        )
        val out = OrbitEngine.render(s, OrbitBank.prepare(s, Sustain()), (2 * bar).toInt()).samples
        assertTrue(out[(bar - 1_000L).toInt() * 2] > 0.4f, "the hit did not sound in its own section")
        assertEquals(0f, out[(bar + 1_000L).toInt() * 2], 1e-3f, "the break was audible")
    }

    @Test
    fun `a ring the next section still plays keeps its tail`() {
        // The other half of the same rule, and the reason this asks per
        // ring rather than silencing everything at every boundary.
        val r = Orbit("A", 16, PatternOrbit("kit", listOf(OrbitHit(15, 1))))
        val s = OrbitSet(
            listOf(r),
            bpm,
            rate,
            sections = listOf(OrbitSection("A", 1, setOf(0)), OrbitSection("B", 1, setOf(0))),
        )
        val out = OrbitEngine.render(s, OrbitBank.prepare(s, Sustain()), (2 * bar).toInt()).samples
        assertTrue(out[(bar + 1_000L).toInt() * 2] > 0.4f, "the tail was cut at a boundary its ring plays through")
    }

    @Test
    fun `a section whose rings have no hit in its window is refused, not written empty`() {
        // A 64-step ring whose only hit is step 63, cut to one bar: it has
        // hits by any reading of the ring, and none at all inside the
        // sixteen steps the section writes. `noNotes` passed it and an
        // empty clip went into groove.json.
        val late = Orbit("A", 64, PatternOrbit("kit", listOf(OrbitHit(63, 1))))
        val s = OrbitSet(listOf(late), bpm, rate, sections = listOf(OrbitSection("LATE", 1, setOf(0))))
        assertTrue(!OrbitClip.sectionSounds(s, 0))
        val why = OrbitClip.clipRefusal(s)
        assertTrue(why != null && why.contains("LATE") && why.contains("SILENT"), "said: $why")
        // Four bars reach the hit, and then it writes.
        val long = s.copy(sections = listOf(OrbitSection("LATE", 4, setOf(0))))
        assertTrue(OrbitClip.sectionSounds(long, 0))
        assertEquals(1, OrbitClip.clips(long).single().notes.size)
    }

    @Test
    fun `two sections may not share a name`() {
        // The order does not reach the hardware, so the name is the only
        // thing identifying a sequence once it is there.
        assertFailsWith<IllegalArgumentException> {
            OrbitSet(
                listOf(ring("A", 1, 0)),
                bpm,
                rate,
                sections = listOf(OrbitSection("A", 1, setOf(0)), OrbitSection("A", 1, setOf(0))),
            )
        }
    }

    @Test
    fun `at a section boundary every ring it plays really is on its downbeat`() {
        // I claimed in a commit message that the canvas's "the rings met"
        // flash needed no change, because it already reads the frame the
        // canvas is handed - the section's - so it fires at local zero.
        // Review read that as a lie the flash tells. This is the claim
        // made checkable rather than argued: under restart semantics a
        // section start IS a meeting, for every ring the section plays.
        val s = OrbitSet(
            listOf(ring("A", 1, 0), Orbit("B", 20, PatternOrbit("kit", listOf(OrbitHit(0, 2))))),
            bpm,
            rate,
            sections = listOf(OrbitSection("A", 2, setOf(0, 1)), OrbitSection("B", 1, setOf(0))),
        )
        for (boundary in longArrayOf(0, 2 * bar, 3 * bar, 5 * bar)) {
            val local = OrbitClock.localFrame(s, boundary)
            assertEquals(0L, local, "a section does not begin at its own frame zero")
            val here = OrbitClock.sectionAt(s, boundary)
            for (index in s.sections[here].plays) {
                assertEquals(
                    0.0,
                    OrbitClock.phase(s, s.orbits[index], local),
                    "ring $index is not on its downbeat at the start of section ${s.sections[here].name}",
                )
            }
        }
        // Rings of 16 and 20 do not meet within the first section at all,
        // so this is the restart doing it rather than a coincidence.
        assertEquals(80L, OrbitClock.cycleSteps(s.copy(sections = emptyList())))
    }

    // ---- the file ----

    @Test
    fun `sections round-trip through orbits json`() {
        val s = set(OrbitSection("INTRO", 2, setOf(0)), OrbitSection("DROP", 4, setOf(0, 1)), OrbitSection("BREAK", 1, emptySet()))
        val dir = Files.createTempDirectory("orbit-sec-json").toFile()
        OrbitStore.save(s, dir)
        // No version literal. I removed exactly this from a YYY7 test one
        // PR ago because a bump failed a test that has nothing to say
        // about versions, and then wrote a fresh one here. What versions
        // still LOAD is the real claim and has its own test below.
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

    // ---- review round 3 ----

    @Test
    fun `a hit in the same block as the boundary is ended by it, not carried past it`() {
        // The round-2 test of this rule passes with 2,048-frame blocks
        // because the hit at 90,000 and the boundary at 96,000 fall in
        // different ones, so the voice already exists when the boundary is
        // reached. Ten-thousand-frame blocks put them in the SAME block —
        // and the hits were started only once the whole block had been
        // walked, so the boundary asked a list the voice was not in yet
        // and the break was audible for the rest of the block.
        val r = Orbit("A", 16, PatternOrbit("kit", listOf(OrbitHit(15, 1))))
        val s = OrbitSet(
            listOf(r),
            bpm,
            rate,
            sections = listOf(OrbitSection("A", 1, setOf(0)), OrbitSection("DROP", 1, emptySet())),
        )
        val out = OrbitEngine.render(s, OrbitBank.prepare(s, Sustain()), (2 * bar).toInt(), blockFrames = 10_000).samples
        // Both sides of the boundary, and both inside the block that holds it.
        assertTrue(out[(bar - 1_000L).toInt() * 2] > 0.4f, "the hit did not sound in its own section")
        assertEquals(0f, out[(bar + 1_000L).toInt() * 2], 1e-3f, "the break was audible inside the boundary's own block")
    }

    @Test
    fun `a sounding voice still answers for its own ring after an edit moves the rings`() {
        // A ring's INDEX is only true of the set it came from. Ring B is
        // struck, ring A is then deleted while B's tail is still sounding,
        // and B — which every section here plays — is index 0 in the set
        // that arrives. A voice holding the old index 1 answered for a
        // ring the new set has not got, and its tail was cut at the next
        // boundary although nothing about it had changed.
        val a = ring("A", 1)
        val b = ring("B", 2, 15)
        val before = OrbitSet(
            listOf(a, b),
            bpm,
            rate,
            sections = listOf(OrbitSection("X", 1, setOf(0, 1)), OrbitSection("Y", 1, setOf(0, 1))),
        )
        val sink = object : AudioSink {
            override val sampleRate = rate
            override val channels = 2
            val written = ArrayList<FloatArray>()
            override fun write(block: FloatArray) { written.add(block.copyOf()) }
            override fun close() {}
        }
        val engine = OrbitEngine(before, OrbitBank.prepare(before, Graded()), sink, blockFrames = 1_000)
        // Up to 95,000: B's step-15 hit fired at 90,000 and is still sounding.
        engine.runFor(95)
        assertEquals(0.2f, sink.written[94][0], 1e-4f, "B did not sound in its own section")
        // Now drop ring A. B becomes index 0, and the sections follow it.
        val after = before.withoutOrbit(0)
        assertEquals(listOf("B"), after.orbits.map { it.name })
        engine.apply(OrbitEngine.Prepared(after, OrbitBank.prepare(after, Graded())))
        // Past the boundary at 96,000, into a section that still plays B.
        engine.runFor(3)
        assertEquals(0.2f, sink.written[97][0], 1e-4f, "B's tail was cut by a section that plays B")
    }

    @Test
    fun `a named section that cannot be written stops the save, not only the preflight`() {
        // `save` calls `clips` and not `clipRefusal`, so a section skipped
        // inside `clips` was written out of the arrangement in silence:
        // the sections either side of it went into groove.json as though
        // that were the whole plan.
        val dir = Files.createTempDirectory("orbit-partial").toFile()
        // Its ring is a snip — audio, which a note-only clip cannot carry.
        val tape = OrbitSet(
            listOf(ring("A", 1, 0), Orbit("tape", 16, SnipOrbit("a.wav"))),
            bpm,
            rate,
            sections = listOf(OrbitSection("IN", 1, setOf(0)), OrbitSection("TAPE", 1, setOf(1))),
        )
        assertTrue(OrbitClip.clipRefusal(tape)!!.contains("TAPE"))
        assertTrue(assertFailsWith<IllegalArgumentException> { OrbitClip.clips(tape) }.message!!.contains("TAPE"))
        assertFailsWith<IllegalArgumentException> { OrbitClip.save(dir, tape) }
        assertEquals(emptyList(), com.snipsnap.kit.GrooveStore.load(dir), "a partial arrangement was stored")
        // The same for a section whose only hit falls outside its window.
        val late = OrbitSet(
            listOf(ring("A", 1, 0), Orbit("L", 64, PatternOrbit("kit", listOf(OrbitHit(63, 2))))),
            bpm,
            rate,
            sections = listOf(OrbitSection("IN", 1, setOf(0)), OrbitSection("LATE", 1, setOf(1))),
        )
        assertTrue(assertFailsWith<IllegalArgumentException> { OrbitClip.clips(late) }.message!!.contains("LATE"))
        // A break is still the one section skipped without a word.
        val withBreak = tape.copy(sections = listOf(OrbitSection("IN", 1, setOf(0)), OrbitSection("DROP", 1, emptySet())))
        assertEquals(listOf("ORBIT IN"), OrbitClip.clips(withBreak).map { it.name })
    }

    // ---- review round 4 ----

    @Test
    fun `an arrangement clips even when its plan is far too long to bounce`() {
        // Eight sections of 32 bars: every clip that would be written is
        // half the ceiling, and the plan is 256 bars - four times it. The
        // clip preflight inherited the BOUNCE's ceiling and refused the
        // lot, so an arrangement that exports as eight perfectly legal
        // sequences could not be clipped at all.
        val s = OrbitSet(
            listOf(ring("A", 1, 0)),
            bpm,
            rate,
            sections = (1..8).map { OrbitSection("S$it", 32, setOf(0)) },
        )
        assertEquals(null, OrbitClip.clipRefusal(s))
        assertEquals(8, OrbitClip.clips(s).size)
        assertTrue(OrbitClip.clips(s).all { it.bars == 32 })
        // And the bounce still refuses it, in the plan's own words - which
        // is the ceiling that must not be relaxed.
        val why = OrbitClip.refusal(s)
        assertTrue(why != null && why.contains("PLAN") && why.contains("256"), "said: $why")
        // A section too long to clip is still refused to BOTH of them.
        val long = s.copy(sections = listOf(OrbitSection("LONG", 64, setOf(0))), lapSteps = 32)
        assertTrue(OrbitClip.clipRefusal(long)!!.contains("LONG"))
        assertTrue(OrbitClip.refusal(long)!!.contains("PLAN"), "the bounce says the bounce's sentence")
    }

    @Test
    fun `a section is judged by the notes it writes, not by the kits its rings name`() {
        // Ring B is on another kit and its only hit is step 63 of a
        // 64-step ring. A ONE-bar section never reaches it, so the clip
        // that section writes holds kitA's note and nothing of kitB's -
        // and it was refused as a two-kit clip on the strength of a hit
        // that is not in it.
        val a = ring("A", 1, 0)
        val b = Orbit("B", 64, PatternOrbit("kitB", listOf(OrbitHit(63, 2))))
        val one = OrbitSet(listOf(a, b), bpm, rate, sections = listOf(OrbitSection("X", 1, setOf(0, 1))))
        assertEquals(null, OrbitClip.clipRefusal(one))
        assertEquals(1, OrbitClip.clips(one).single().notes.size)
        // Four bars DO reach it, and then the two kits are a real refusal.
        val four = one.copy(sections = listOf(OrbitSection("X", 4, setOf(0, 1))))
        assertTrue(OrbitClip.clipRefusal(four)!!.contains("KITS"), "two kits in one clip must still refuse")
        // The same for a pad no program holds: outside the window it is
        // not written, inside it the clip cannot carry it.
        val high = Orbit("H", 64, PatternOrbit("kit", listOf(OrbitHit(63, com.snipsnap.mpc3.Mpc3Note.PAD_SLOTS + 1))))
        val pads = OrbitSet(listOf(a, high), bpm, rate, sections = listOf(OrbitSection("X", 1, setOf(0, 1))))
        assertEquals(null, OrbitClip.clipRefusal(pads))
        assertTrue(OrbitClip.clipRefusal(pads.copy(sections = listOf(OrbitSection("X", 4, setOf(0, 1)))))!!.contains("PAD"))
    }

    @Test
    fun `a turn too long in frames to bounce is refused, not narrowed into a negative`() {
        // 64 bars is a MUSICAL ceiling and says nothing about frames:
        // `sampleRate` is only required to be positive, so a legal 64-bar
        // turn at 6 MHz and 40 BPM is 2,304,000,000 frames, which the
        // bounce's Int conversion wrapped to -1,990,967,296.
        val fast = OrbitSet(
            listOf(ring("A", 1, 0)),
            OrbitSet.MIN_BPM,
            6_000_000,
            sections = listOf(OrbitSection("S", 64, setOf(0))),
        )
        val frames = OrbitClock.transportFrames(fast)
        assertTrue(frames > Int.MAX_VALUE, "the fixture must actually overflow: $frames")
        assertTrue(frames.toInt() < 0, "and it must wrap negative, which is the bug")
        val why = OrbitClip.refusal(fast)
        assertTrue(why != null && why.contains("FRAMES"), "said: $why")
        // And the half-an-Int case, which the first cut of this guard let
        // through: 64 bars at 3 MHz and 40 BPM is 1,152,000,000 frames -
        // inside an Int, and past it the moment the renderer doubles it
        // for the interleaved buffer it allocates.
        val half = fast.copy(sampleRate = 3_000_000)
        val halfFrames = OrbitClock.transportFrames(half)
        assertTrue(halfFrames < Int.MAX_VALUE, "the fixture must fit an Int: $halfFrames")
        assertTrue(halfFrames.toInt() * 2 < 0, "and must overflow when interleaved, which is the bug")
        assertTrue(OrbitClip.refusal(half) != null, "a turn the renderer cannot count must be refused")
        assertEquals(null, OrbitClip.clipRefusal(half))
        // A CLIP is written in bars and never reaches a frame, so it is
        // not refused for this.
        assertEquals(null, OrbitClip.clipRefusal(fast))
        // And an ordinary set is untouched.
        assertEquals(null, OrbitClip.refusal(set(OrbitSection("A", 1, setOf(0)))))
    }

    @Test
    fun `a break is not measured against a ceiling on clips it does not write`() {
        // A 64-bar break in 8/4 is 128 of the clip's bars - but a break
        // writes no clip, so there is no clip of that length to be too
        // long. Every clip this set does write is one bar.
        val s = OrbitSet(
            listOf(ring("A", 1, 0)),
            bpm,
            rate,
            lapSteps = 32,
            sections = listOf(OrbitSection("IN", 1, setOf(0)), OrbitSection("GAP", 64, emptySet())),
        )
        assertEquals(null, OrbitClip.clipRefusal(s))
        assertEquals(listOf("ORBIT IN"), OrbitClip.clips(s).map { it.name })
        // The BOUNCE still counts it, because silence has a length: the
        // plan is 65 of the set's bars, which is 130 of the clip's.
        assertTrue(OrbitClip.refusal(s)!!.contains("PLAN"), "said: ${OrbitClip.refusal(s)}")
        // And a PLAYABLE section that long is still refused to both.
        val playable = s.copy(sections = listOf(OrbitSection("LONG", 64, setOf(0))))
        assertTrue(OrbitClip.clipRefusal(playable)!!.contains("LONG"))
    }

    @Test
    fun `the window the preflight asks is the window the writer walks`() {
        // A 3/4 set: a one-bar section is twelve 16ths, written into a
        // clip bar of SIXTEEN. The four steps of padding are silence the
        // writer never reaches, and asking the preflight over the padded
        // length counted a hit that is not in the file.
        val a = Orbit("A", 12, PatternOrbit("kitA", listOf(OrbitHit(0, 1))))
        val late = Orbit("B", 16, PatternOrbit("kitB", listOf(OrbitHit(15, 2))))
        val s = OrbitSet(
            listOf(a, late),
            bpm,
            rate,
            lapSteps = 12,
            sections = listOf(OrbitSection("X", 1, setOf(0, 1))),
        )
        // Step 15 is pulse 3,600; the section writes 2,880. Counting it
        // refused this as a two-kit clip, for a note the clip has not got.
        assertEquals(2_880L, OrbitClip.clipPulses(OrbitClip.sectionSteps(s, 0)))
        assertEquals(null, OrbitClip.clipRefusal(s))
        assertEquals(1, OrbitClip.clips(s).single().notes.size)
        // The same number the other way about: a section whose ONLY ring
        // is that one sounds nothing, and counting the padding would have
        // declared it sounding and then written the clip empty.
        val only = s.copy(sections = listOf(OrbitSection("LATE", 1, setOf(1))))
        assertTrue(!OrbitClip.sectionSounds(only, 0))
        assertTrue(OrbitClip.clipRefusal(only)!!.contains("LATE"))
    }

    @Test
    fun `the writer refuses to write a clip with no notes in it, whoever asked`() {
        // `noNotes` knows the rings and not the window: a 64-step ring
        // whose only hit is step 63, clipped with steps = 16, passed it and
        // the PUBLIC api returned ORBIT X with no notes at all. `clips`
        // catches the same shape earlier and by section name; this is the
        // floor under it, asked of what was actually written.
        val late = OrbitSet(listOf(Orbit("A", 64, PatternOrbit("kit", listOf(OrbitHit(63, 1))))), bpm, rate)
        val why = assertFailsWith<IllegalArgumentException> { OrbitClip.clip(late, "ORBIT X", steps = 16) }
        assertTrue(why.message!!.contains("NO HIT LANDS"), "said: ${why.message}")
        // A window that reaches the hit writes it, as it always did.
        assertEquals(1, OrbitClip.clip(late, "ORBIT X", steps = 64).notes.size)
    }

    @Test
    fun `each way out says its own sentence about the same set`() {
        // The number is shared and the reader is not. A set whose rings
        // meet past the ceiling refuses both, and each says what IT stops
        // at rather than borrowing the other's words.
        val coprime = OrbitSet(
            listOf(
                Orbit("A", 64, PatternOrbit("kit", listOf(OrbitHit(0, 1)))),
                Orbit("B", 63, PatternOrbit("kit", listOf(OrbitHit(0, 2)))),
            ),
            bpm,
            rate,
        )
        // With no arrangement the two doors share the number AND the
        // sentence, and it names neither of them: both refuse, both for
        // the rings' meeting, so naming one would be wrong half the time.
        val shared = OrbitClip.refusal(coprime)
        assertEquals(shared, OrbitClip.clipRefusal(coprime))
        assertTrue(shared!!.contains("THE RINGS MEET") && shared.contains("ALL THAT GOES OUT"), "said: $shared")
        assertTrue(!shared.contains("A CLIP STOPS") && !shared.contains("A BOUNCE STOPS"))
        // An ARRANGED set's two ceilings are genuinely different lengths,
        // and then each says its own door: the section is the clip's, the
        // plan is the bounce's.
        val arranged = coprime.copy(
            lapSteps = 32,
            sections = listOf(OrbitSection("LONG", 64, setOf(0, 1))),
        )
        assertTrue(OrbitClip.clipRefusal(arranged)!!.contains("A CLIP STOPS"))
        assertTrue(OrbitClip.refusal(arranged)!!.contains("A BOUNCE STOPS"))
    }

    @Test
    fun `a solo hands back every ring it does not change, as itself`() {
        // The engine matches a sounding voice to its ring by identity, so
        // a set that copies every ring on every edit loses every voice
        // struck before it.
        val a = ring("A", 1, 0)
        val b = ring("B", 2, 8)
        val muted = ring("C", 3, 4).copy(engaged = false)
        val s = OrbitSet(listOf(a, b, muted), bpm, rate)
        val soloed = s.soloing(0)
        assertTrue(soloed.orbits[0] === a, "the soloed ring must be the same ring")
        assertTrue(soloed.orbits[2] === muted, "a ring already silent must be the same ring")
        assertTrue(soloed.orbits[1] !== b, "the ring the solo silences is the one that changes")
        assertTrue(!soloed.orbits[1].engaged)
        // No solo is the set itself.
        assertTrue(s.soloing(null) === s)
        // Soloing a muted ring leaves it muted - a solo does not unmute.
        assertTrue(!s.soloing(2).orbits[2].engaged)
    }

    @Test
    fun `a voice struck before a solo is still hushed by the section after it`() {
        // The whole chain: ring A is struck, the player solos A mid-tail,
        // and the next section leaves A out. With the solo copying every
        // ring, A's voice no longer matched any ring in the set that
        // arrived, so `hush` skipped it and the tail crossed the boundary.
        val a = Orbit("A", 16, PatternOrbit("kit", listOf(OrbitHit(15, 1))))
        val b = ring("B", 2, 0)
        val before = OrbitSet(
            listOf(a, b),
            bpm,
            rate,
            sections = listOf(OrbitSection("X", 1, setOf(0, 1)), OrbitSection("Y", 1, setOf(1))),
        )
        val sink = object : AudioSink {
            override val sampleRate = rate
            override val channels = 2
            val written = ArrayList<FloatArray>()
            override fun write(block: FloatArray) { written.add(block.copyOf()) }
            override fun close() {}
        }
        val engine = OrbitEngine(before, OrbitBank.prepare(before, Graded()), sink, blockFrames = 1_000)
        engine.runFor(95)
        assertEquals(0.1f, sink.written[94][0], 1e-4f, "A did not sound in its own section")
        val soloed = before.soloing(0)
        engine.apply(OrbitEngine.Prepared(soloed, OrbitBank.prepare(soloed, Graded())))
        engine.runFor(3)
        assertEquals(0f, sink.written[97][0], 1e-3f, "A's tail crossed into a section that leaves A out")
    }
}
