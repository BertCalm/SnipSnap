package com.snipsnap.shell

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two rules that keep a progress bar honest, pinned where the JVM suite
 * can reach them: a job shorter than [BusyLatch.SHOW_AFTER_MS] never flashes
 * anything, and a job that did show stays up for [BusyLatch.MIN_VISIBLE_MS]
 * even if it finishes a moment later. Times are plain milliseconds on one
 * clock; the latch never reads a clock of its own.
 */
class BusyTest {

    @Test
    fun `a job shorter than the show delay never shows`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(150)
        for (t in listOf(0L, 100L, 150L, 200L, 1000L)) {
            assertFalse(latch.visible(t), "a 150 ms job must never flash anything, but it was visible at $t")
        }
    }

    @Test
    fun `a job that stops one millisecond before the show delay never shows`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(199)
        assertFalse(latch.visible(199), "stopped before it ever showed")
        assertFalse(latch.visible(200), "the show moment arrives after the stop, so there is nothing to show")
        assertNull(latch.nextChangeAt(199), "an invisible, finished job has no change to wait for")
    }

    @Test
    fun `a job shows from 200 ms`() {
        val latch = BusyLatch()
        latch.start(0)
        assertFalse(latch.visible(199), "one millisecond early")
        assertTrue(latch.visible(200), "on the show delay")
    }

    @Test
    fun `once shown it stays at least 500 ms`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(250)
        assertTrue(latch.visible(699), "shown at 200, so it lingers to 700 even though the job ended at 250")
        assertFalse(latch.visible(700), "the 500 ms minimum is over")
    }

    @Test
    fun `a job that stops exactly on the show delay still shows and lingers`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(200)
        assertTrue(latch.visible(200), "the stop is not before the show moment, so it shows")
        assertTrue(latch.visible(699), "and then lingers the full minimum")
        assertFalse(latch.visible(700), "shown from 200, so it is gone at 700")
    }

    @Test
    fun `a long job hides when it stops`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(2000)
        assertTrue(latch.visible(1999), "still running")
        assertFalse(latch.visible(2000), "it has already been up longer than the minimum, so it goes at once")
    }

    @Test
    fun `a restart while lingering stays visible`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(250)
        latch.start(400)
        assertTrue(latch.visible(400), "the bar was still up when the next job began, so it must not blink off")
        assertTrue(latch.visible(5000), "and the new job holds it for as long as it runs")
    }

    @Test
    fun `a restart after it has gone starts a fresh delay`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(250)
        latch.start(1000)
        assertFalse(latch.visible(1000), "gone at 700, so the new job begins hidden")
        assertFalse(latch.visible(1199), "one millisecond before its own show delay")
        assertTrue(latch.visible(1200), "and shows 200 ms after its own start")
    }

    @Test
    fun `a quick job after a quick job still never shows`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(150)
        latch.start(160)
        latch.stop(300)
        for (t in listOf(160L, 300L, 360L, 1000L)) {
            assertFalse(latch.visible(t), "two short jobs in a row must not add up to a flash, but it was visible at $t")
        }
    }

    @Test
    fun `nextChangeAt says when to look again`() {
        val fresh = BusyLatch()
        assertNull(fresh.nextChangeAt(0), "nothing started, nothing to wait for")

        val latch = BusyLatch()
        latch.start(0)
        assertEquals(200L, latch.nextChangeAt(0), "the bar is due at the show delay")
        assertNull(latch.nextChangeAt(300), "shown and still running: only stop() changes it now")
        latch.stop(250)
        assertEquals(700L, latch.nextChangeAt(300), "stopped at 250 but shown from 200, so it goes at 700")
        assertNull(latch.nextChangeAt(700), "gone, nothing further to wait for")
    }

    @Test
    fun `start is idempotent`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.start(150)
        assertTrue(latch.visible(200), "the second start must not reset the clock, or the show delay would slip to 350")
    }

    @Test
    fun `stop with nothing running changes nothing`() {
        val latch = BusyLatch()
        latch.stop(100)
        assertFalse(latch.visible(100), "a stop with nothing started shows nothing")
        assertNull(latch.nextChangeAt(100), "and schedules nothing")

        latch.start(0)
        latch.stop(2000)
        latch.stop(5000)
        assertFalse(latch.visible(2000), "a second stop must not move the end later")
    }

    @Test
    fun `fraction`() {
        assertEquals(0.3125f, Busy("X", 5, 16).fraction, "5 of 16")
        assertEquals(1f, Busy("X", 20, 16).fraction, "a count past the total clamps to full")
        assertEquals(0f, Busy("X", -3, 16).fraction, "a negative count clamps to empty")
        assertNull(Busy("X").fraction, "no total means indeterminate")
        assertNull(Busy("X", 1, 0).fraction, "a total of zero would divide by zero, so it is indeterminate")
        assertNull(Busy("X", 1, -4).fraction, "a negative total is not a total")
    }

    @Test
    fun `a placeholder tile swings between about nothing and 0 point 18 of the way to the ink`() {
        val all = (0 until PlaceholderPulse.TILES).flatMap { tile ->
            (0..100).map { step -> PlaceholderPulse.amount(step / 100f, tile) }
        }
        assertTrue(all.min() >= -1e-6f, "never below the raised fill: ${all.min()}")
        assertTrue(all.max() <= 0.18f + 1e-6f, "never past 0.18 of the way to the ink: ${all.max()}")
        assertTrue(all.min() < 0.005f, "it goes down to about nothing: ${all.min()}")
        assertTrue(all.max() > 0.175f, "and up to about 0.18: ${all.max()}")
    }

    @Test
    fun `the wave crosses the grid in reading order`() {
        // Tile i runs the same breath a sixteenth of a loop behind tile i-1, so
        // the pulse reads as a wave travelling over the pads and not as one
        // lamp: at one phase the sixteen tiles are sixteen different shades.
        val phase = 0.3f
        for (tile in 0 until PlaceholderPulse.TILES) {
            val expected = 0.09f + 0.09f * sin(2.0 * PI * (phase - tile / 16.0)).toFloat()
            assertEquals(expected, PlaceholderPulse.amount(phase, tile), 1e-5f, "tile $tile at phase $phase")
        }
        val shades = (0 until PlaceholderPulse.TILES).map { PlaceholderPulse.amount(phase, it) }
        assertTrue(shades.toSet().size > 8, "the grid holds many shades at one instant: $shades")
        // And one full loop later it is where it was: the repeat has no jump.
        for (tile in 0 until PlaceholderPulse.TILES) {
            assertTrue(abs(PlaceholderPulse.amount(0f, tile) - PlaceholderPulse.amount(1f, tile)) < 1e-5f, "tile $tile loops")
        }
    }

    @Test
    fun `a still placeholder sits in the middle of the swing`() {
        assertEquals(0.09f, PlaceholderPulse.STILL, "what reduced motion holds")
    }

    @Test
    fun `copy`() {
        assertEquals("LOADING FACTORY…", Copy.loadingBusy("FACTORY"))
        assertEquals("5 OF 16", Copy.busyCount(5, 16))
    }
}
