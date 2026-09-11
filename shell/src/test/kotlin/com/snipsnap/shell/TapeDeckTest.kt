package com.snipsnap.shell

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapeDeckTest {

    private val rate = 44_100

    /** Ten seconds of 220 Hz sine — plenty of zero crossings to snap to. */
    private fun deck(
        onsets: IntArray = intArrayOf(rate, rate * 3, rate * 5, rate * 7),
        seconds: Int = 10,
    ): TapeDeckModel {
        val tape = FloatArray(rate * seconds) { sin(2 * PI * 220 * it / rate).toFloat() }
        return TapeDeckModel(tape, rate, onsets)
    }

    @Test
    fun `play spins up toward normal speed and stop winds down`() {
        val d = deck()
        d.play()
        assertTrue(d.playing)
        d.step(rate * 4) // spin-up eases in; 4s is plenty
        assertTrue(abs(d.speed - 1.0) < 0.01, "speed ${d.speed} hasn't spun up")
        assertTrue(d.position > 0)

        d.stop()
        d.step(rate * 4)
        assertEquals(0.0, d.speed)
        assertFalse(d.playing)
    }

    @Test
    fun `play to the end stops with a HitEnd event`() {
        val d = deck(seconds = 2, onsets = IntArray(0))
        d.play()
        val events = mutableListOf<TapeDeckModel.Event>()
        repeat(5) { events += d.step(rate) }
        assertTrue(TapeDeckModel.Event.HitEnd in events)
        assertFalse(d.playing)
        assertEquals((rate * 2 - 2).toDouble(), d.position)
    }

    @Test
    fun `drag sticks the tape to the finger through the zoom`() {
        val d = deck()
        d.dragStart()
        assertEquals(TapeDeckModel.Mode.DRAG, d.mode)
        // Drag left 90px at zoom x1 (90 px/s) = one second forward.
        d.dragBy(dxPixels = -90.0, dtMillis = 100.0)
        d.step(rate * 3) // ease catches up
        assertTrue(abs(d.position - rate) < rate * 0.02, "position ${d.position} not ~1s")

        // At zoom x2 the same pixels cover half the tape-time.
        d.cycleZoom()
        assertEquals(180, d.pxPerSec)
        d.dragBy(-90.0, 100.0)
        d.step(rate * 3)
        assertTrue(abs(d.position - rate * 1.5) < rate * 0.03)
    }

    @Test
    fun `a slow release stops and snaps onto the nearest onset`() {
        val d = deck(onsets = intArrayOf(rate))
        d.dragStart()
        // Creep to just before the onset (within the 0.30s stop-snap).
        d.dragBy(-(rate * 0.85 / rate * 90), 1000.0) // 0.85s at zoom x1
        d.step(rate * 4)
        val snap = d.dragEnd()
        assertNotNull(snap, "release near an onset should snap")
        assertEquals(rate, snap.frame)
        assertEquals(TapeDeckModel.Mode.GLIDE, d.mode)
        d.step(rate * 6)
        assertEquals(rate.toDouble(), d.position)
        assertEquals(TapeDeckModel.Mode.IDLE, d.mode)
    }

    @Test
    fun `a flick coasts with friction then snaps when it dies`() {
        val d = deck(onsets = intArrayOf(rate * 2))
        d.dragStart()
        // A fast swipe: build velocity well past the flick threshold.
        repeat(6) { d.dragBy(-40.0, 16.0) }
        assertNull(d.dragEnd())
        assertEquals(TapeDeckModel.Mode.COAST, d.mode)
        assertTrue(abs(d.speed) > 0)

        val events = mutableListOf<TapeDeckModel.Event>()
        repeat(40) { events += d.step(rate) }
        // Coast always dies eventually; whether it snapped depends on where
        // it stopped, but the mode must resolve.
        assertTrue(d.mode == TapeDeckModel.Mode.IDLE || d.mode == TapeDeckModel.Mode.GLIDE)
    }

    @Test
    fun `select hands in a whole range - clamped, ordered, the head at IN, empty clears`() {
        val m = deck()
        m.select(m.lengthFrames / 2, 100)
        assertTrue(m.hasSelection)
        assertEquals(100, m.inFrame)
        assertEquals(m.lengthFrames / 2, m.outFrame)
        assertEquals(100.0, m.position)
        m.select(-50, m.lengthFrames + 999)
        assertEquals(0, m.inFrame)
        assertEquals(m.lengthFrames, m.outFrame)
        m.select(500, 500)
        assertTrue(!m.hasSelection, "an empty range is no selection")
    }

    @Test
    fun `select then commitSelection hands back exactly the range - RE-TRIM's IN and OUT land where the pad's cut was`() {
        val m = deck()
        m.select(52_920, 71_442)
        assertEquals(52_920 until 71_442, m.commitSelection())
        assertEquals("LEN 0.42s", m.lengthReadout)
        // A cut that runs past the tape's end (a file TAPE capped) clamps rather than errors.
        m.select(rate * 9, rate * 20)
        assertEquals(rate * 9 until m.lengthFrames, m.commitSelection())
    }

    @Test
    fun `selection set-out before set-in swaps instead of erroring`() {
        val d = deck(onsets = IntArray(0))
        d.snapToZero = false
        d.seekTo(rate * 4)
        d.step(rate * 20)
        d.setIn()
        assertEquals(rate * 4, d.inFrame)

        d.seekTo(rate * 2)
        d.step(rate * 20)
        d.setOut() // earlier than IN: swap
        assertEquals(rate * 2, d.inFrame)
        assertEquals(rate * 4, d.outFrame)
        assertTrue(d.hasSelection)
        assertEquals(rate * 2 until rate * 4, d.commitSelection())

        d.clearSelection()
        assertNull(d.commitSelection())
        assertEquals("LEN --.--", d.lengthReadout)
    }

    @Test
    fun `set-in past the out point clears the out point`() {
        val d = deck(onsets = IntArray(0))
        d.snapToZero = false
        d.seekTo(rate)
        d.step(rate * 20)
        d.setIn()
        d.seekTo(rate * 2)
        d.step(rate * 20)
        d.setOut()
        d.seekTo(rate * 3)
        d.step(rate * 20)
        d.setIn() // beyond the old out
        assertEquals(rate * 3, d.inFrame)
        assertEquals(-1, d.outFrame)
    }

    @Test
    fun `snapPoint prefers the onset then the zero crossing`() {
        val d = deck(onsets = intArrayOf(rate))
        // 0.05s from the onset — inside the 0.12s snap radius.
        val near = rate + (0.05 * rate).toInt()
        assertEquals(rate, d.snapPoint(near))

        // Onset snap off: lands on a zero crossing of the 220 Hz sine near
        // the requested frame (within half a period).
        d.snapToOnset = false
        val f = d.snapPoint(near)
        assertTrue(abs(f - near) <= rate / 220 / 2 + 1)
        // 220 Hz zero crossings sit at multiples of rate/440.
        val period = rate.toDouble() / 220
        val phase = (f % period) / period
        assertTrue(phase < 0.02 || abs(phase - 0.5) < 0.02 || phase > 0.98, "frame $f not at a zero")
    }

    @Test
    fun `loop preview wraps play inside the selection`() {
        val d = deck(onsets = IntArray(0))
        d.snapToZero = false
        d.snapToOnset = false
        d.seekTo(rate)
        d.step(rate * 20)
        d.setIn()
        d.seekTo(rate * 2)
        d.step(rate * 20)
        d.setOut()
        d.loopPreview = true

        d.seekTo(rate * 5) // outside the selection
        d.step(rate * 20)
        d.play()
        assertEquals(rate.toDouble(), d.position, "play from outside starts at IN")

        d.step((rate * 2.5).toInt()) // long enough to cross OUT at ~1x
        assertTrue(
            d.position >= d.inFrame && d.position < d.outFrame,
            "position ${d.position} escaped the loop [${d.inFrame}, ${d.outFrame})",
        )
        assertTrue(d.playing)
    }

    @Test
    fun `pencil rewind winds to the top and reports done`() {
        val d = deck()
        d.seekTo(rate * 6)
        d.step(rate * 30)
        assertTrue(d.pencilRewind())
        assertTrue(d.pencilActive)
        assertEquals(TapeDeckModel.PENCIL_SPEED, d.targetSpeed)

        val events = mutableListOf<TapeDeckModel.Event>()
        repeat(20) { events += d.step(rate) }
        assertTrue(TapeDeckModel.Event.PencilDone in events)
        assertFalse(d.pencilActive)
        assertEquals(0.0, d.position)
    }

    @Test
    fun `pencil refuses at the top of the tape`() {
        val d = deck()
        assertFalse(d.pencilRewind())
    }

    @Test
    fun `wind buttons ease to seven times speed and release stops`() {
        val d = deck()
        d.windStart(1)
        // Spin-up settles in ~4k frames; at 7x that stays well inside the tape.
        d.step(rate / 4)
        assertTrue(abs(d.speed - TapeDeckModel.WIND_SPEED) < 0.1, "speed ${d.speed}")
        d.windStop(1)
        assertEquals(0.0, d.targetSpeed)
        // Releasing a button that's no longer the target is a no-op.
        d.windStart(-1)
        d.windStop(1)
        assertEquals(-TapeDeckModel.WIND_SPEED, d.targetSpeed)
    }

    @Test
    fun `readouts - position, odometer, selection length`() {
        // A long tape at a low rate, so minute-scale readouts stay testable.
        val lowRate = 1000
        val d = TapeDeckModel(FloatArray(lowRate * 600), lowRate, IntArray(0))
        assertEquals("POS 0:00.00", d.positionReadout)
        d.seekTo((lowRate * 61.5).toInt())
        d.step(lowRate * 300)
        assertEquals("POS 1:01.50", d.positionReadout)

        d.toggleOdometer()
        // 61.5s of 600s tape → 61.5/600*999 = 102.
        assertEquals("CNT 102", d.positionReadout)
        d.toggleOdometer()

        d.snapToZero = false
        d.setIn()
        d.seekTo((lowRate * 62.74).toInt())
        d.step(lowRate * 300)
        d.setOut()
        assertEquals("LEN 1.24s", d.lengthReadout)
    }

    @Test
    fun `zoom ladder cycles x1 x2 x4`() {
        val d = deck()
        assertEquals("ZOOM x1", d.zoomLabel)
        d.cycleZoom(); assertEquals("ZOOM x2", d.zoomLabel)
        d.cycleZoom(); assertEquals("ZOOM x4", d.zoomLabel)
        d.cycleZoom(); assertEquals("ZOOM x1", d.zoomLabel)
    }
}
