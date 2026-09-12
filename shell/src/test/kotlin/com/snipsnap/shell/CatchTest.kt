package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transients
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatchTest {

    private val rate = 44_100

    /** Kick, closed hat, snare, open hat on half-second steps, three seconds long. */
    private fun breakSnip(): Snip {
        val step = rate / 2
        val hits = listOf(1 to DrumSynth.kick(), 2 to DrumSynth.closedHat(), 3 to DrumSynth.snare(), 4 to DrumSynth.openHat())
        val total = FloatArray(step * 6)
        for ((s, hit) in hits) {
            val at = s * step
            for (i in hit.samples.indices) if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
        }
        return Snip(total, 1, rate)
    }

    private fun sec(s: Double): Int = (s * rate).toInt()

    private fun whole(tape: Snip, hits: List<CatchModel.Hit> = CatchModel.hitsOf(tape), taken: Set<Int> = emptySet()) =
        CatchModel(tape, 0 until tape.frameCount, hits, taken)

    @Test
    fun `a tap after a hit catches the whole hit, cut as INSTANT KIT would cut it`() {
        val tape = breakSnip()
        val hits = CatchModel.hitsOf(tape)
        assertEquals(4, hits.size, "four hits heard")
        val m = whole(tape, hits)
        val kick = hits[0].range.first
        assertTrue(kick in sec(0.49)..sec(0.52), "the kick's cut starts on the kick, got $kick")
        assertTrue(m.press(1, kick + sec(0.10)))
        val c = assertNotNull(m.release(1, kick + sec(0.15)))
        assertEquals(hits[0].range, c.range, "the whole kick, as INSTANT KIT cuts it")
        assertEquals(0, c.hit)
        assertFalse(c.held)
        assertEquals(c.range.last - c.range.first + 1, Retrim.cut(tape, c.range).frameCount, "the cut is the range, no trim")
    }

    @Test
    fun `a hold ends where the finger lifts, on a zero crossing, and never past the next hit`() {
        val tape = breakSnip()
        val hits = CatchModel.hitsOf(tape)
        val m = whole(tape, hits)
        val kick = hits[0].range.first
        m.press(1, kick + sec(0.10))
        val lifted = kick + sec(0.30)
        val c = assertNotNull(m.release(1, lifted))
        assertTrue(c.held)
        assertEquals(kick, c.range.first)
        assertEquals(Transients.zeroCrossingBefore(tape, lifted), c.range.last + 1, "ends on the zero crossing before the lift")
        assertTrue(c.range.last + 1 <= lifted && c.range.last + 1 > lifted - 200)

        m.press(2, kick + sec(0.10))
        val past = assertNotNull(m.release(2, hits[2].range.first + sec(0.20)))
        assertEquals(hits[1].range.first, past.range.last + 1, "a hold through the hat stops at the hat")
        assertEquals(0, past.hit)
    }

    @Test
    fun `between hits a hold takes the tape from the press and a tap takes nothing`() {
        val tape = breakSnip()
        val hits = CatchModel.hitsOf(tape)
        val m = whole(tape, hits)
        val between = sec(1.35) // the hat is 350 ms back, the snare 150 ms ahead: neither is meant
        assertNull(m.hitFor(between))
        m.press(1, between)
        assertNull(m.release(1, between + sec(0.05)), "a tap on nothing lands nothing")
        m.press(1, between)
        val c = assertNotNull(m.release(1, sec(1.47)), "a 120 ms hold is a hold")
        assertNull(c.hit)
        assertEquals(Transients.zeroCrossingBefore(tape, between), c.range.first)
        assertEquals(Transients.zeroCrossingBefore(tape, sec(1.47)), c.range.last + 1)
        m.press(1, between)
        val toSnare = assertNotNull(m.release(1, sec(1.60)))
        assertEquals(hits[2].range.first, toSnare.range.last + 1, "a hold across the snare stops where the snare starts")
    }

    @Test
    fun `the strongest hit in the window wins, the nearest on a tie, and only hits inside the loop count`() {
        val tape = Snip(FloatArray(rate * 3) { sin(2 * PI * 220 * it / rate).toFloat() }, 1, rate)
        val quiet = CatchModel.Hit(1000 until 2000, 10f)
        val loud = CatchModel.Hit(4000 until 6000, 30f)
        val m = CatchModel(tape, 0 until tape.frameCount, listOf(quiet, loud))
        assertEquals(1, m.hitFor(6000), "both inside 150 ms: the louder one")
        val even = CatchModel(tape, 0 until tape.frameCount, listOf(quiet, loud.copy(strength = 10f)))
        assertEquals(1, even.hitFor(6000), "a tie goes to the nearer")
        assertEquals(0, even.hitFor(1500), "only the first is in reach here")
        assertNull(m.hitFor(6000 + (CatchModel.LOOK_BACK_SEC * rate).toInt() + 10), "past the window")
        assertEquals(1, m.hitFor(4000 - (CatchModel.LOOK_AHEAD_SEC * rate).toInt() + 1), "a little early still counts")
        val late = CatchModel(tape, 3000 until tape.frameCount, listOf(quiet, loud))
        assertEquals(1, late.hitFor(3000), "the quiet hit is outside the loop; the loud one just ahead is in it")
        val early = CatchModel(tape, 0 until 3500, listOf(quiet, loud))
        assertEquals(0, early.hitFor(4500), "the loud hit lies outside the loop; only the quiet one went by")
    }

    @Test
    fun `the loop bounds every cut, and a hold across the wrap runs to the loop's end`() {
        val tape = breakSnip()
        val hits = CatchModel.hitsOf(tape)
        val region = sec(0.4) until sec(1.2)
        val m = CatchModel(tape, region, hits)
        val kick = hits[0].range.first
        m.press(1, kick + sec(0.10))
        val wrapped = assertNotNull(m.release(1, sec(0.45)))
        assertEquals(kick until sec(1.2), wrapped.range, "held past OUT and round again: to the end of the loop")
        m.press(3, kick + sec(0.10), pass = 4)
        val passed = assertNotNull(m.release(3, kick + sec(0.30), pass = 5), "a later pass is a wrap even when the frame is later too")
        assertEquals(kick until sec(1.2), passed.range)
        m.press(4, kick + sec(0.10), pass = 4)
        val same = assertNotNull(m.release(4, kick + sec(0.30), pass = 4))
        assertTrue(same.range.last + 1 < sec(1.2), "the same pass is an ordinary hold")
        val hat = hits[1].range.first
        m.press(2, hat + sec(0.05))
        val tapped = assertNotNull(m.release(2, hat + sec(0.10)))
        assertEquals(hat until sec(1.2), tapped.range, "the hat's own cut runs to the snare, but the loop ends first")
        assertFalse(tapped.held)
    }

    @Test
    fun `a second pass replaces, a taken pad refuses, and a catch lands through the assign door with RE-TRIM's keys`() {
        val tape = breakSnip()
        val hits = CatchModel.hitsOf(tape)
        val m = whole(tape, hits, taken = setOf(2))
        val kick = hits[0].range.first
        m.press(1, kick + sec(0.10)); m.release(1, kick + sec(0.15))
        m.press(1, kick + sec(0.10))
        val second = assertNotNull(m.release(1, kick + sec(0.30)))
        assertEquals(second, m.caughtOn(1), "the second pass replaced the first")
        assertEquals(1, m.caught.size)
        val hat = hits[1].range.first
        m.press(5, hat + sec(0.05)); m.release(5, hat + sec(0.08))
        m.press(1, kick + sec(0.10)); m.release(1, kick + sec(0.12))
        assertEquals(listOf(1, 5), m.caught.map { it.slot }, "replacing the first catch keeps it first")
        assertFalse(m.press(2, kick + sec(0.10)), "A02 had a pad before CATCH began")
        assertNull(m.release(2, kick + sec(0.30)))
        assertNull(m.release(3, kick + sec(0.30)), "no press, no catch")

        val dir = java.nio.file.Files.createTempDirectory("catch").toFile()
        try {
            val builder = KitBuilderModel.create("Caught", dir)
            val pad = assertNotNull(CatchModel.land(builder, "break.wav", Retrim.cut(tape, second.range), second))
            assertEquals(1, pad.slot)
            assertEquals(DrumClass.KICK, pad.drumClass, "classed by ear")
            assertEquals("break.wav", pad.source[Retrim.FILE_KEY])
            assertEquals(Retrim.Cut(second.range.first, second.range.last + 1), Retrim.cutOf(pad), "RE-TRIM can open on the catch")
            assertEquals(CatchModel.ORIGIN, pad.source["origin"])
            assertEquals(second.range.last - second.range.first + 1, com.snipsnap.audio.WavReader.read(java.io.File(dir, pad.sampleFile)).frameCount)
            assertNotNull(builder.pad(1))
            // The next pass replaces a catch; a pad another door put there
            // since the press is the user's, and the write looks again.
            val again = m.caughtOn(1)!!
            assertNotNull(CatchModel.land(builder, "break.wav", Retrim.cut(tape, again.range), again), "a catch replaces a catch")
            builder.assign(6, DrumSynth.snare(), DrumClass.SNARE)
            val onto6 = m.caughtOn(5)!!.copy(slot = 6)
            assertNull(CatchModel.land(builder, "break.wav", Retrim.cut(tape, onto6.range), onto6), "never over a pad that isn't a catch")
            assertEquals(DrumClass.SNARE, builder.pad(6)!!.drumClass, "and it is untouched")
        } finally {
            dir.deleteRecursively()
        }
    }
}
