package com.snipsnap.kit

import com.snipsnap.kit.GrooveEdit.Lane
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** BEAT step 4: the roll is a pure function of clip, pad, step and pass. */
class StepChanceTest {

    private val lane = Lane.SNARE

    private fun clipWith(chance: Int, roll: Int = 1, step: Int = 4): Mpc3Clip {
        var c = GrooveEdit.toggleStep(Mpc3Clip("Kit E", 1, emptyList()), lane, step)
        c = StepShape.setRoll(c, lane, step, roll)
        return StepShape.setChance(c, lane, step, chance)
    }

    @Test
    fun `the same arguments give the same answer`() {
        for (pass in 0L..50L) {
            assertEquals(
                StepChance.sounds("Kit E", 38, 4, pass, 50),
                StepChance.sounds("Kit E", 38, 4, pass, 50),
            )
        }
    }

    @Test
    fun `100 always sounds and 0 never does, whatever the pass`() {
        for (pass in 0L..200L) {
            assertTrue(StepChance.sounds("Kit E", 38, 4, pass, 100))
            assertFalse(StepChance.sounds("Kit E", 38, 4, pass, 0))
        }
    }

    @Test
    fun `50 percent sounds about half the passes`() {
        for (step in listOf(0, 4, 9)) {
            val heard = (0L until 1000L).count { StepChance.sounds("Kit E", 38, step, it, 50) }
            assertTrue(heard in 400..600, "step $step sounded $heard of 1000")
        }
        val quarter = (0L until 1000L).count { StepChance.sounds("Kit E", 38, 4, it, 25) }
        assertTrue(quarter in 190..310, "25% sounded $quarter of 1000")
    }

    @Test
    fun `a step with no chance resolves to the very same clip`() {
        val c = clipWith(100)
        assertSame(c, StepChance.resolve(c, 3L))
    }

    @Test
    fun `every hit of a x4 roll sounds or falls silent together on every pass`() {
        val c = clipWith(50, roll = 4)
        assertEquals(4, c.notes.size)
        var heard = 0
        for (pass in 0L until 200L) {
            val n = StepChance.resolve(c, pass).notes.size
            assertTrue(n == 0 || n == 4, "pass $pass left $n of 4 hits")
            if (n == 4) heard++
        }
        assertTrue(heard in 1..199, "it both sounds and falls silent over 200 passes: $heard")
    }

    @Test
    fun `a nudge does not change the outcome`() {
        val c = clipWith(50)
        val moved = StepShape.nudge(c, lane, 4, StepShape.NUDGE_PULSES)
        assertTrue(moved != c, "the nudge moved it")
        for (pass in 0L until 200L) {
            assertEquals(StepChance.resolve(c, pass).notes.isNotEmpty(), StepChance.resolve(moved, pass).notes.isNotEmpty(), "pass $pass")
        }
    }

    @Test
    fun `a resolved note has its chance reset and is not rolled twice`() {
        val c = clipWith(50)
        val pass = (0L until 100L).first { StepChance.resolve(c, it).notes.isNotEmpty() }
        val once = StepChance.resolve(c, pass)
        assertEquals(listOf(Mpc3Note.ALWAYS), once.notes.map { it.chance })
        assertSame(once, StepChance.resolve(once, pass + 1))
    }

    @Test
    fun `an explicit clip id pins the roll across a rename`() {
        val c = clipWith(50)
        val renamed = c.copy(name = "01 intro")
        for (pass in 0L until 50L) {
            assertEquals(StepChance.resolve(c, pass).notes, StepChance.resolve(renamed, pass, clipId = c.name).notes, "pass $pass")
        }
    }

    @Test
    fun `BeatTape's rotation rolls each lap of a clip on its own pass and drops a step at 0`() {
        assertTrue(BeatTape.arrange(listOf(clipWith(0)), 4, "tape").notes.isEmpty())
        assertEquals(4, BeatTape.arrange(listOf(clipWith(100)), 4, "tape").notes.size)
        val half = clipWith(50)
        val laps = BeatTape.arrange(listOf(half), 40, "tape").notes.map { it.timePulses / Mpc3Clip.PULSES_PER_BAR }.toSet()
        val want = (0 until 40).filter { StepChance.resolve(half, it.toLong()).notes.isNotEmpty() }.map { it.toLong() }.toSet()
        assertEquals(want, laps)
    }
}
