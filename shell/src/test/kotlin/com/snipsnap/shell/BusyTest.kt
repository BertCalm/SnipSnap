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
    fun `a discarded run shows nothing, now or later, and schedules nothing`() {
        // The job ran past the show delay but its bar was never put up (the
        // caller had not yet acted on the show moment when the job ended), so
        // the caller forgets the run: showing the bar after the job's result has
        // landed would be a loading screen over the thing it was loading.
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(230)
        assertTrue(latch.visible(230), "by the latch's rules alone, a job that ran 230 ms owes a bar")
        latch.discard()
        for (t in listOf(230L, 300L, 700L, 5000L)) assertFalse(latch.visible(t), "a discarded run is invisible at $t")
        assertNull(latch.nextChangeAt(230), "and has nothing to wake for")
    }

    @Test
    fun `a job after a discarded one gets a fresh delay of its own`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(230)
        latch.discard()

        latch.start(1000)
        assertFalse(latch.visible(1100), "the new job is inside its own 200 ms")
        assertFalse(latch.visible(1199))
        assertTrue(latch.visible(1200), "and shows at 200 ms from its own start, not the old one's")
        assertEquals(1200L, latch.nextChangeAt(1100))
    }

    @Test
    fun `discarding with nothing running changes nothing`() {
        val latch = BusyLatch()
        latch.discard()
        assertFalse(latch.visible(0))
        assertNull(latch.nextChangeAt(0))
        latch.start(10)
        assertTrue(latch.visible(210), "and the next job runs as normal")
    }

    // markShown: the minimum counts from the first frame that drew the bar,
    // which a caller under load can reach later than the scheduled show. The
    // latch is told when, on the same clock as everything else.

    @Test
    fun `a bar first drawn at 260 stays 500 ms from the draw`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.markShown(260)
        latch.stop(300)
        assertTrue(latch.visible(759), "drawn at 260, so it lingers to 760 even though the job ended at 300")
        assertFalse(latch.visible(760), "the 500 ms minimum, counted from the draw, is over")
        assertEquals(760L, latch.nextChangeAt(300), "and the caller is told to look again then, not at 700")
    }

    @Test
    fun `without markShown the minimum counts from the scheduled show as before`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(300)
        assertTrue(latch.visible(699), "scheduled to show at 200, so it lingers to 700")
        assertFalse(latch.visible(700), "the minimum counts from the scheduled show")
        assertEquals(700L, latch.nextChangeAt(300))
    }

    @Test
    fun `markShown after the job has stopped but while the bar lingers still moves the anchor`() {
        // The frame that draws the bar can land after the job's result has:
        // the job ends at 210, the bar's first frame is at 360, and the bar
        // must still be readable for 500 ms from that frame.
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(210)
        latch.markShown(360)
        assertTrue(latch.visible(859), "drawn at 360, so it lingers to 860")
        assertFalse(latch.visible(860))
    }

    @Test
    fun `markShown before the show delay is ignored`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.markShown(150)
        latch.stop(300)
        assertTrue(latch.visible(699), "nothing was up at 150, so the minimum still counts from the scheduled show")
        assertFalse(latch.visible(700), "and it is gone at 700, not 650")
    }

    @Test
    fun `markShown after the bar has gone is ignored`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.stop(250)
        latch.markShown(800)
        assertFalse(latch.visible(800), "gone at 700: a late mark must not bring a bar back")
        assertNull(latch.nextChangeAt(800), "and schedules nothing")
    }

    @Test
    fun `a second markShown does not move the anchor`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.markShown(260)
        latch.markShown(400)
        latch.stop(450)
        assertTrue(latch.visible(759), "the first drawn frame is the anchor")
        assertFalse(latch.visible(760), "and a later call does not push it out to 900")
    }

    @Test
    fun `a restart while lingering keeps the drawn anchor`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.markShown(260)
        latch.stop(300)
        latch.start(400)
        latch.stop(450)
        assertTrue(latch.visible(759), "the bar was up the whole time, so its run continues from the draw at 260")
        assertFalse(latch.visible(760))
    }

    @Test
    fun `a fresh run after a drawn one has an anchor of its own`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.markShown(260)
        latch.stop(300)
        latch.start(1000)
        latch.stop(1250)
        assertTrue(latch.visible(1699), "shown from 1200 by its own schedule, so it lingers to 1700, not to 1250")
        assertFalse(latch.visible(1700))
    }

    @Test
    fun `a job after a discarded drawn one has an anchor of its own`() {
        val latch = BusyLatch()
        latch.start(0)
        latch.markShown(260)
        latch.stop(300)
        latch.discard()
        latch.start(1000)
        latch.stop(1250)
        assertTrue(latch.visible(1699), "the discarded run's anchor is forgotten with the run")
        assertFalse(latch.visible(1700))
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
