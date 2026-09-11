package com.snipsnap.loop

import com.snipsnap.kit.GrooveStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrbitClipTest {

    private fun pattern(name: String, steps: Int, slot: Int, hits: List<Int>, span: OrbitSpan = OrbitSpan.FREE, engaged: Boolean = true) =
        Orbit(name, steps, PatternOrbit("kit", hits.map { OrbitHit(it, slot, 0.8f) }), span = span, engaged = engaged)

    private fun set(vararg orbits: Orbit) = OrbitSet(orbits.toList(), 120f, 48_000)

    @Test
    fun `one cycle of 16 against 20 is a five-bar clip with every firing on the pulse grid`() {
        val s = set(pattern("four", 16, 1, listOf(0)), pattern("five", 20, 2, listOf(0, 10)))
        val clip = OrbitClip.clip(s)
        assertEquals(5, clip.bars)
        assertEquals("ORBIT 4:5", clip.name)
        val fours = clip.notes.filter { it.note == 36 }.map { it.timePulses }
        val fives = clip.notes.filter { it.note == 37 }.map { it.timePulses }
        assertEquals((0 until 5).map { it * Mpc3Clip.PULSES_PER_BAR }, fours)
        val fiveBeats = 20 * Mpc3Clip.PULSES_PER_16TH
        assertEquals((0 until 4).flatMap { listOf(it * fiveBeats, it * fiveBeats + 10 * Mpc3Clip.PULSES_PER_16TH) }, fives)
    }

    @Test
    fun `a locked triplet lands on thirds of the bar`() {
        val s = set(pattern("three", 3, 4, listOf(0, 1, 2), span = OrbitSpan.ONE))
        val clip = OrbitClip.clip(s)
        assertEquals(1, clip.bars)
        assertEquals(listOf(0L, 1280L, 2560L), clip.notes.map { it.timePulses })
        assertEquals(listOf(39, 39, 39), clip.notes.map { it.note })
    }

    @Test
    fun `a triplet across two bars lands on thirds of two bars`() {
        val s = set(pattern("slow", 3, 4, listOf(0, 1, 2), span = OrbitSpan.TWO))
        val clip = OrbitClip.clip(s)
        assertEquals(2, clip.bars)
        val twoBars = 2 * Mpc3Clip.PULSES_PER_BAR
        assertEquals(listOf(0L, Math.round(twoBars / 3.0), Math.round(2 * twoBars / 3.0)), clip.notes.map { it.timePulses })
    }

    @Test
    fun `the clip counts bars of sixteen whatever the set's bar`() {
        // A 3/4 set: a free 16 against the 12-step bar meets every 48 steps, four bars of twelve — three of the clip's.
        val waltz = OrbitSet(listOf(pattern("four", 16, 1, listOf(0))), 120f, 48_000, lapSteps = 12)
        assertEquals(3, OrbitClip.bars(waltz))
        assertTrue(OrbitClip.countsDifferently(waltz))
        assertEquals(3, OrbitClip.clip(waltz).bars)
        // A 5/4 set with one free 20: twenty steps need a second bar of sixteen.
        val five = OrbitSet(listOf(pattern("five", 20, 1, listOf(0, 10))), 120f, 48_000, lapSteps = 20)
        assertEquals(2, OrbitClip.bars(five))
        assertEquals(listOf(0L, 10L * Mpc3Clip.PULSES_PER_16TH), OrbitClip.clip(five).notes.map { it.timePulses })
        // The default bar counts as it always did.
        assertTrue(!OrbitClip.countsDifferently(set(pattern("a", 16, 1, listOf(0)))))
    }

    @Test
    fun `swing rides into the clip as late pulses on the odd 16ths`() {
        val s = OrbitSet(listOf(pattern("hats", 16, 3, listOf(0, 1))), 120f, 48_000, swing = 66)
        val pulses = OrbitClip.clip(s).notes.map { it.timePulses }
        assertEquals(0L, pulses[0])
        assertTrue(pulses[1] > Mpc3Clip.PULSES_PER_16TH && pulses[1] < 2 * Mpc3Clip.PULSES_PER_16TH, "odd 16th late but before the next: ${pulses[1]}")
        assertEquals(Math.round(1.32 * Mpc3Clip.PULSES_PER_16TH), pulses[1])
    }

    @Test
    fun `muted rings and snip rings leave no notes`() {
        val s = set(
            pattern("off", 16, 1, listOf(0), engaged = false),
            Orbit("tape", 16, SnipOrbit("a.wav")),
            pattern("on", 16, 2, listOf(4)),
        )
        assertEquals(listOf(37), OrbitClip.clip(s).notes.map { it.note })
    }

    @Test
    fun `a cycle past 64 bars is refused in words, with the number`() {
        val s = set(pattern("a", 16, 1, listOf(0)), pattern("b", 17, 2, listOf(0)), pattern("c", 19, 3, listOf(0)))
        val refusal = OrbitClip.refusal(s)
        assertTrue(refusal != null && "323" in refusal, "expected the cycle length in the refusal: $refusal")
        val e = runCatching { OrbitClip.clip(s) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun `each shape that would clip nothing refuses, and says which shape it is`() {
        // Four ways to reach a clip with no notes in it. They are four
        // different mistakes, so each gets its own sentence rather than
        // one refusal standing in for all of them.
        val shapes = listOf(
            "no rings at all" to OrbitSet(emptyList(), 120f, 48_000) to "NO RINGS",
            "every ring a snip" to set(Orbit("tape", 16, SnipOrbit("a.wav"))) to "SNIP",
            "every pattern muted" to set(pattern("off", 16, 1, listOf(0), engaged = false)) to "MUTED",
            // What `+ PAD RING` makes before a step is tapped.
            "a ring with no hits" to set(pattern("new", 16, 1, emptyList())) to "HIT",
        )
        val seen = mutableSetOf<String>()
        for ((labelled, expect) in shapes) {
            val (label, s) = labelled
            val refusal = assertNotNull(OrbitClip.clipRefusal(s), "$label should refuse the clip")
            assertTrue(expect in refusal, "$label should say \"$expect\": $refusal")
            assertTrue(seen.add(refusal), "$label repeats an earlier refusal: $refusal")
            assertFailsWith<IllegalArgumentException>("$label should throw from clip()") { OrbitClip.clip(s) }
            // None of these is a reason the AUDIO cannot leave: every one
            // of them still bounces.
            assertEquals(null, OrbitClip.refusal(s), "$label should not block the bounce")
        }
    }

    @Test
    fun `the reason names what is actually wrong, even when two things are`() {
        // A muted ring that HAS hits, beside an engaged ring that has
        // none. Asking "do the engaged rings have hits?" answers no and
        // says NO RING HAS A HIT ON IT YET - while a ring plainly has one.
        // The true reason the clip is empty is that the ring with the hits
        // is muted, and that is the one to say.
        val mixed = set(
            pattern("played", 16, 1, listOf(0, 8), engaged = false),
            pattern("new", 16, 2, emptyList()),
        )
        val refusal = assertNotNull(OrbitClip.clipRefusal(mixed))
        assertTrue("MUTED" in refusal, "the muted ring is why it is empty: $refusal")
        assertTrue("HIT ON IT YET" !in refusal, "a ring does have a hit: $refusal")
        assertEquals(null, OrbitClip.refusal(mixed), "still bounceable")

        // And the other way round: nothing anywhere has a hit, engaged or
        // not, so "nothing played yet" is the honest answer.
        val nothingPlayed = set(
            pattern("new", 16, 1, emptyList(), engaged = false),
            pattern("newer", 16, 2, emptyList()),
        )
        assertTrue("HIT ON IT YET" in assertNotNull(OrbitClip.clipRefusal(nothingPlayed)))
    }

    @Test
    fun `a set with nothing to clip still has something to bounce`() {
        // The clip refusal and the shared one are different questions and
        // must not be asked through the same door: OrbitScreen hides BOTH
        // ways out when refusal() is non-null, so folding "no notes here"
        // into it told a snip-only set to BOUNCE IT INSTEAD while hiding
        // the bounce button. Snips are audio; OrbitEngine renders them.
        val snipsOnly = set(Orbit("tape", 16, SnipOrbit("a.wav")), Orbit("more", 20, SnipOrbit("b.wav")))
        assertEquals(null, OrbitClip.refusal(snipsOnly), "audio can still leave")
        assertTrue(OrbitClip.clipRefusal(snipsOnly)!!.contains("SNIP"), "but the clip cannot, and says why")

        // The ceiling is the one reason that stops both, so it has to
        // survive in each.
        val tooLong = set(pattern("a", 16, 1, listOf(0)), pattern("b", 17, 2, listOf(0)), pattern("c", 19, 3, listOf(0)))
        val shared = assertNotNull(OrbitClip.refusal(tooLong))
        assertTrue("323" in shared, "the ceiling still names the cycle: $shared")
        assertEquals(shared, OrbitClip.clipRefusal(tooLong), "the ceiling reaches the clip unchanged")
    }

    @Test
    fun `a set that clips keeps clipping, and names the snip rings it leaves behind`() {
        // A snip beside rings that do play is not a refusal - the clip
        // carries what it can and the count is what the screen reports.
        val mixed = set(
            Orbit("tape", 16, SnipOrbit("a.wav")),
            Orbit("more tape", 16, SnipOrbit("b.wav")),
            pattern("on", 16, 2, listOf(4)),
        )
        assertEquals(null, OrbitClip.refusal(mixed))
        assertEquals(null, OrbitClip.clipRefusal(mixed))
        assertEquals(listOf(37), OrbitClip.clip(mixed).notes.map { it.note })
        assertEquals(listOf("tape", "more tape"), OrbitClip.snipRings(mixed))
        assertEquals(emptyList(), OrbitClip.snipRings(set(pattern("on", 16, 2, listOf(4)))))
    }

    @Test
    fun `refusing and clipping agree - a set that passes always carries a note`() {
        // The invariant the refusal exists to hold: never a silent empty
        // clip saved into groove.json, where it would become the kit's
        // base and read as a kit whose beat is nothing.
        val sets = listOf(
            OrbitSet(emptyList(), 120f, 48_000),
            set(Orbit("tape", 16, SnipOrbit("a.wav"))),
            set(pattern("off", 16, 1, listOf(0), engaged = false)),
            set(pattern("new", 16, 1, emptyList())),
            set(pattern("on", 16, 1, listOf(0))),
            set(pattern("new", 16, 1, emptyList()), pattern("on", 16, 2, listOf(4))),
            set(pattern("off", 16, 1, listOf(0), engaged = false), pattern("on", 16, 2, listOf(4))),
            set(pattern("four", 16, 1, listOf(0)), pattern("five", 20, 2, listOf(0, 10))),
        )
        for (s in sets) {
            if (OrbitClip.clipRefusal(s) == null) {
                assertTrue(OrbitClip.clip(s).notes.isNotEmpty(), "passed refusal but clipped nothing")
            }
        }
    }

    @Test
    fun `saving replaces the last ORBIT clip and keeps every other groove`() {
        val dir = Files.createTempDirectory("orbitclip").toFile()
        val captured = Mpc3Clip("Break", 2, listOf(Mpc3Note(36, 0, 0.9f)))
        val progE = Mpc3Clip("Break E", 2, listOf(Mpc3Note(38, 240, 0.7f)))
        GrooveStore.save(dir, listOf(captured, progE, Mpc3Clip("ORBIT 4:5", 5, emptyList())))

        val written = OrbitClip.save(dir, set(pattern("four", 16, 1, listOf(0))))
        val stored = GrooveStore.load(dir)
        assertEquals(listOf("Break", "Break E", "ORBIT 1"), stored.map { it.name })
        assertEquals(written, stored.last())
        assertEquals(1, stored.count { OrbitClip.isOrbit(it) })
    }
}
