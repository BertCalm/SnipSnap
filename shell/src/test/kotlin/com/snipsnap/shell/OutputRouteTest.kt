package com.snipsnap.shell

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.LiveRecord
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputRouteTest {

    @Test
    fun `an A2DP sink is Bluetooth on any API, a BLE headset only from API 31`() {
        assertTrue(OutputRoute.isBluetooth(listOf(OutputRoute.TYPE_BLUETOOTH_A2DP), sdk = 26))
        assertFalse(OutputRoute.isBluetooth(listOf(OutputRoute.TYPE_BLE_HEADSET), sdk = 30))
        assertTrue(OutputRoute.isBluetooth(listOf(OutputRoute.TYPE_BLE_HEADSET), sdk = 31))
        assertFalse(OutputRoute.isBluetooth(emptyList(), sdk = 34))
    }

    @Test
    fun `the route is the first of Bluetooth, wired, USB, else the speaker`() {
        val speaker = 2 // TYPE_BUILTIN_SPEAKER
        assertEquals(MidiReport.Route.SPEAKER, OutputRoute.classify(listOf(speaker), 34))
        assertEquals(MidiReport.Route.WIRED, OutputRoute.classify(listOf(speaker, OutputRoute.TYPE_WIRED_HEADPHONES), 34))
        assertEquals(MidiReport.Route.USB, OutputRoute.classify(listOf(speaker, OutputRoute.TYPE_USB_HEADSET), 34))
        assertEquals(
            MidiReport.Route.BLUETOOTH,
            OutputRoute.classify(listOf(speaker, OutputRoute.TYPE_WIRED_HEADSET, OutputRoute.TYPE_BLUETOOTH_A2DP), 34),
        )
    }

    @Test
    fun `the player's offset wins, then the measured figure, then the Bluetooth default`() {
        assertEquals(60, OutputRoute.compensationMs(manualMs = 60, measured = 25.0, bluetooth = true))
        assertEquals(0, OutputRoute.compensationMs(manualMs = 0, measured = 25.0, bluetooth = true), "a set 0 is a choice, not AUTO")
        assertEquals(25, OutputRoute.compensationMs(null, 25.4, bluetooth = false))
        assertEquals(0, OutputRoute.compensationMs(null, null, bluetooth = false))
        assertEquals(200, OutputRoute.compensationMs(null, null, bluetooth = true))
        assertEquals(200, OutputRoute.compensationMs(null, 40.0, bluetooth = true), "an A2DP timestamp is a floor, not the answer")
        assertEquals(260, OutputRoute.compensationMs(null, 260.0, bluetooth = true))
    }

    @Test
    fun `a compensation is clamped to 0 through 400 ms`() {
        assertEquals(400, OutputRoute.compensationMs(900, null, bluetooth = false))
        assertEquals(0, OutputRoute.compensationMs(-5, null, bluetooth = false))
        assertEquals(400, OutputRoute.compensationMs(null, 1_000.0, bluetooth = true))
        assertEquals(OutputRoute.hitNanos(10_000, 0), OutputRoute.hitNanos(10_000, -50))
        assertEquals(10_000_000_000L - 400_000_000L, OutputRoute.hitNanos(10_000, 5_000))
    }

    @Test
    fun `measured latency is the queued frames less the time since the hardware presented`() {
        // 48 kHz: 9600 frames queued is 200 ms; the presented frame was stamped 20 ms ago -> 180 ms.
        val ms = OutputRoute.measuredMs(
            framesRendered = 100_000, presentedFrame = 90_400,
            presentedNanos = 1_000_000_000L, nowNanos = 1_020_000_000L, rate = 48_000,
        )
        assertEquals(180.0, ms!!, 1e-6)
    }

    @Test
    fun `an unfilled or absurd timestamp is no measurement`() {
        assertNull(OutputRoute.measuredMs(100, 100, 1_000L, 2_000L, 48_000), "zero queued is an unfilled field")
        assertNull(OutputRoute.measuredMs(100, 200, 1_000L, 2_000L, 48_000), "negative")
        assertNull(OutputRoute.measuredMs(100_000, 0, 1_000L, 2_000L, 48_000), "over 400 ms")
        assertNull(OutputRoute.measuredMs(100_000, 90_000, 0L, 2_000L, 48_000), "no timestamp yet")
        assertNull(OutputRoute.measuredMs(100_000, 90_000, 1_000L, 2_000L, 0), "no rate")
    }

    /**
     * RECORD's clock stamps a step when it is issued; the player taps when they hear it, L later. The
     * recorded step is where the needle was at the stamp, wrapped into the loop.
     */
    private fun recordedStep(latencyMs: Int, tapMs: Long, compensate: Boolean, bpm: Float = 120f): Int {
        val stepsPerSecond = BeatClock.stepsPerSecond(bpm)
        // The last frame anchored at 1 s, the needle 0.2 steps past the beat the player is about to tap.
        val anchorNanos = 1_000_000_000L
        val anchorPos = 8.0f
        // Heard at tapMs + L, so stamped then: the raw uptime the touch carries.
        val uptime = tapMs + latencyMs
        val hit = if (compensate) OutputRoute.hitNanos(uptime, latencyMs) else uptime * 1_000_000L
        val pos = BeatClock.posAtHit(anchorPos, anchorNanos, hit, stepsPerSecond)
        val seconds = pos / stepsPerSecond
        val pulses = LiveRecord.wrapped(LiveRecord.pulsesFor(seconds, bpm), bars = 2)
        return Math.round(pulses.toDouble() / GrooveEdit.STEP_PULSES).toInt() % (2 * GrooveEdit.STEPS_PER_BAR)
    }

    @Test
    fun `a take at 200 ms of output latency lands on the same step as one at 0`() {
        // Tap on the issue time of step 8 plus a quarter step (31 ms) of honest slop: 1 s + 31 ms.
        val tap = 1_031L
        val reference = recordedStep(0, tap, compensate = true)
        assertEquals(8, reference)
        for (l in listOf(25, 100, 200, 250, 400)) {
            assertEquals(reference, recordedStep(l, tap, compensate = true), "L=$l ms")
        }
    }

    @Test
    fun `without the correction a Bluetooth take lands a step or more late`() {
        // Same tap, L = 200 ms: 1.6 steps late at 120 BPM, which quantises a step or two on.
        val late = recordedStep(200, 1_031L, compensate = false)
        assertTrue(late >= 9, "uncorrected it lands at $late; the correction exists for this")
    }

    @Test
    fun `the count-in gate sees the compensated time, so a tap on the click is not dropped on Bluetooth`() {
        val anchorNanos = 1_000_000_000L
        val l = 200
        // A tap on the downbeat, heard 200 ms after it was issued.
        val raw = anchorNanos + l * 1_000_000L
        val gateOpen = anchorNanos - BeatClock.STEP_MAX_NANOS
        assertTrue(OutputRoute.hitNanos(raw / 1_000_000L, l) >= gateOpen)
        // A tap a whole beat earlier (a click earlier in the count-in) is still refused.
        val earlier = anchorNanos - 500_000_000L + l * 1_000_000L
        assertTrue(OutputRoute.hitNanos(earlier / 1_000_000L, l) < gateOpen)
    }

    @Test
    fun `playback honours a note's stored velocity, and a ghost stays a ghost`() {
        val s16 = GrooveEdit.STEP_PULSES
        val clip = Mpc3Clip(
            name = "t", bars = 1,
            notes = listOf(
                Mpc3Note(Mpc3Note.noteFor(1), 0, velocity = 1.0f, lengthPulses = s16),
                Mpc3Note(Mpc3Note.noteFor(2), 4 * s16, velocity = 0.6f, lengthPulses = s16),
                Mpc3Note(Mpc3Note.noteFor(3), 8 * s16, velocity = 0.2f, lengthPulses = s16),
            ),
        )
        val played = mutableListOf<Pair<Int, Float>>()
        BeatClock.forEachHitCrossed(clip, lastPos = -0.5f, np = 9f, totalSteps = 16f) { slot, v -> played += slot to v }
        assertEquals(listOf(1 to 1.0f, 2 to 0.6f, 3 to 0.2f), played)
    }
}
