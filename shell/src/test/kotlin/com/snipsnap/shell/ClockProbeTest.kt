package com.snipsnap.shell

import com.snipsnap.shell.ClockProbe.Sample
import com.snipsnap.shell.ClockProbe.Verdict
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spec's tests ([docs/MIDI_CLOCK_PROBE_SPEC_2026_10.md] 2.2) on synthetic streams.
 * Every expected number comes from the spec or from the generator's own parameters.
 */
class ClockProbeTest {

    private val stepNanos = 16_700_000L // one display frame, ~60 Hz
    private val hwEpochNanos = 7_000_000_000_000L // the hardware clock is not the wall clock

    /**
     * An honest stream. The hardware position is quantised to [burst] frames and trails
     * the written count by [lagMs]; each (position, time) pair is exact, as a real
     * getTimestamp's is. The hardware clock starts at [hwStartMs]: before that the read
     * either fails ([failBeforeStart]) or reports position 0.
     * Reads in [freezeAtMs, freezeAtMs + freezeMs) repeat the last pair before them (a lying
     * position), reads in [failFromMs, failToMs) fail. [wallJitterMs] moves wallNanos alone.
     */
    private fun stream(
        rate: Int,
        durationMs: Long,
        burst: Int = 192,
        lagMs: Long = 20,
        hwStartMs: Long = lagMs,
        failBeforeStart: Boolean = false,
        freezeAtMs: Long = -1,
        freezeMs: Long = 0,
        failFromMs: Long = -1,
        failToMs: Long = -1,
        hwRatePpm: Double = 0.0,
        wallJitterMs: Double = 0.0,
        seed: Int = 1,
    ): List<Sample> {
        val rnd = Random(seed)
        val hwRate = rate * (1.0 + hwRatePpm / 1e6)
        val n = (durationMs * 1_000_000L / stepNanos).toInt()
        fun pairAt(tNanos: Long): Pair<Long, Long> {
            val th = (tNanos - hwStartMs * 1_000_000L).coerceAtLeast(0L)
            val pos = (th * hwRate / 1e9).toLong() / burst * burst
            return pos to hwEpochNanos + (pos / hwRate * 1e9).toLong()
        }
        return (0..n).map { i ->
            val t = i * stepNanos
            val tMs = t / 1_000_000L
            val frozen = freezeAtMs >= 0 && tMs >= freezeAtMs && tMs < freezeAtMs + freezeMs
            val failed = (failBeforeStart && tMs < hwStartMs) || (tMs >= failFromMs && tMs < failToMs)
            val (pos, ns) = if (frozen) pairAt(freezeAtMs * 1_000_000L - stepNanos) else pairAt(t)
            val jitter = if (wallJitterMs == 0.0) 0L else ((rnd.nextDouble() * 2 - 1) * wallJitterMs * 1e6).toLong()
            Sample(
                wallNanos = 1_000_000_000L + t + jitter,
                framesWritten = t * rate / 1_000_000_000L,
                hwFrame = if (failed) null else pos,
                hwNanos = if (failed) null else ns,
            )
        }
    }

    private fun assertNear(expected: Double, actual: Double?, tol: Double, what: String) {
        assertNotNull(actual, what)
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected +- $tol, got $actual")
    }

    @Test
    fun `an honest 48 kHz stream tracks`() {
        val r = ClockProbe.analyze(stream(48_000, 10_000), 48_000)
        assertEquals(Verdict.TRACKED, r.verdict)
        assertTrue(abs(r.rateErrorPpm!!) < 50, "rate error ${r.rateErrorPpm}")
        assertTrue(r.startupGapMs!! < 50, "startup gap ${r.startupGapMs}")
        assertEquals(0.0, r.longestStallMs)
        assertEquals(r.samples, r.validSamples)
    }

    @Test
    fun `a position fixed at 0 for the first 800 ms is a slow start and still reports its rate`() {
        val r = ClockProbe.analyze(stream(48_000, 10_000, hwStartMs = 800), 48_000)
        assertEquals(Verdict.SLOW_START, r.verdict)
        assertNear(800.0, r.startupGapMs, 50.0, "startup gap")
        assertNotNull(r.rateErrorPpm)
        assertNotNull(r.lagMs)
    }

    @Test
    fun `failed reads for the first 800 ms are the same slow start`() {
        val r = ClockProbe.analyze(stream(48_000, 10_000, hwStartMs = 800, failBeforeStart = true), 48_000)
        assertEquals(Verdict.SLOW_START, r.verdict)
        assertNear(800.0, r.startupGapMs, 50.0, "startup gap")
        assertTrue(r.validSamples < r.samples)
        assertNotNull(r.rateErrorPpm)
    }

    @Test
    fun `honest for 2 s then frozen for 500 ms is a stall at 2000`() {
        val r = ClockProbe.analyze(stream(48_000, 8_000, freezeAtMs = 2_000, freezeMs = 500), 48_000)
        assertEquals(Verdict.STALLED, r.verdict)
        assertNear(500.0, r.longestStallMs, 40.0, "longest stall")
        assertNear(2_000.0, r.stallAtMs, 40.0, "stall at")
        assertNotNull(r.rateErrorPpm)
        assertNotNull(r.lagMs)
    }

    @Test
    fun `a failed-read span after start-up counts as a stall too`() {
        val r = ClockProbe.analyze(stream(48_000, 8_000, failFromMs = 2_000, failToMs = 2_500), 48_000)
        assertEquals(Verdict.STALLED, r.verdict)
        assertNear(500.0, r.longestStallMs, 40.0, "longest stall")
    }

    @Test
    fun `a 150 ms freeze after start-up is below the floor and tracks`() {
        val r = ClockProbe.analyze(stream(48_000, 8_000, freezeAtMs = 2_000, freezeMs = 150), 48_000)
        assertEquals(Verdict.TRACKED, r.verdict)
        assertTrue(r.longestStallMs < ClockProbe.STALL_FLOOR_MS, "stall ${r.longestStallMs}")
    }

    @Test
    fun `every read failed is unavailable`() {
        val r = ClockProbe.analyze(stream(48_000, 5_000, failFromMs = 0, failToMs = 99_999), 48_000)
        assertEquals(Verdict.UNAVAILABLE, r.verdict)
        assertEquals(0, r.validSamples)
        assertNull(r.startupGapMs)
        assertNull(r.rateErrorPpm)
        assertNull(r.lagMs)
        assertEquals(Verdict.UNAVAILABLE, ClockProbe.analyze(emptyList(), 48_000).verdict)
    }

    @Test
    fun `1_5 s after first movement is too short`() {
        val lag = 20L
        val r = ClockProbe.analyze(stream(48_000, lag + 1_500, lagMs = lag), 48_000)
        assertEquals(Verdict.TOO_SHORT, r.verdict)
        assertNull(r.rateErrorPpm)
    }

    @Test
    fun `a 44_1 kHz stream tracks, no rate is hardcoded`() {
        val r = ClockProbe.analyze(stream(44_100, 10_000, burst = 128), 44_100)
        assertEquals(Verdict.TRACKED, r.verdict)
        assertTrue(abs(r.rateErrorPpm!!) < 50, "rate error ${r.rateErrorPpm}")
        // the same samples judged against a different rate are visibly off
        val wrong = ClockProbe.analyze(stream(44_100, 10_000, burst = 128), 48_000)
        assertTrue(abs(wrong.rateErrorPpm!!) > 50_000, "rate error ${wrong.rateErrorPpm}")
    }

    @Test
    fun `8 ms of wall jitter alone leaves the rate unchanged to 1 ppm`() {
        val clean = ClockProbe.analyze(stream(48_000, 10_000), 48_000)
        val jittered = ClockProbe.analyze(stream(48_000, 10_000, wallJitterMs = 8.0), 48_000)
        assertEquals(Verdict.TRACKED, jittered.verdict)
        assertNear(clean.rateErrorPpm!!, jittered.rateErrorPpm, 1.0, "rate error")
    }

    @Test
    fun `a clock that runs fast or slow reads as that many ppm`() {
        val r = ClockProbe.analyze(stream(48_000, 10_000, hwRatePpm = 200.0), 48_000)
        assertNear(200.0, r.rateErrorPpm, 2.0, "rate error")
        val slow = ClockProbe.analyze(stream(48_000, 10_000, hwRatePpm = -300.0), 48_000)
        assertNear(-300.0, slow.rateErrorPpm, 3.0, "rate error")
    }

    @Test
    fun `lag is the buffer the generator put in`() {
        val r = ClockProbe.analyze(stream(48_000, 10_000, lagMs = 40), 48_000)
        assertNear(40.0, r.lagMs, 8.0, "lag")
    }

    @Test
    fun `a position that never moves is not a divide by zero`() {
        val frozen = (0..300).map { i -> Sample(i * stepNanos, i * 800L, 0L, hwEpochNanos) }
        val r = ClockProbe.analyze(frozen, 48_000)
        assertEquals(Verdict.TOO_SHORT, r.verdict)
        assertNull(r.startupGapMs)
        assertNull(r.rateErrorPpm)
    }

    // ---- describe: one line per verdict, built from Copy ----

    @Test
    fun `describe says tracked with rate and lag`() {
        val r = ClockProbe.analyze(stream(48_000, 10_000, hwRatePpm = 200.0, lagMs = 40), 48_000)
        val line = ClockProbe.describe(r)
        assertTrue(line.startsWith(Copy.CLOCK_TRACKED), line)
        assertTrue(line.contains(Copy.clockRate(Math.round(r.rateErrorPpm!!))), line)
        assertTrue(line.contains(Copy.clockBehind(Math.round(r.lagMs!!))), line)
    }

    @Test
    fun `describe says how long the start stood still, then the rate`() {
        val r = ClockProbe.analyze(stream(48_000, 10_000, hwStartMs = 800), 48_000)
        val line = ClockProbe.describe(r)
        assertTrue(line.startsWith(Copy.clockStoodStill(Math.round(r.startupGapMs!!))), line)
        assertTrue(line.contains(Copy.clockRate(Math.round(r.rateErrorPpm!!))), line)
    }

    @Test
    fun `describe says where and how long the position stopped`() {
        val r = ClockProbe.analyze(stream(48_000, 8_000, freezeAtMs = 2_000, freezeMs = 500), 48_000)
        val line = ClockProbe.describe(r)
        assertTrue(line.startsWith(Copy.clockStopped(Math.round(r.longestStallMs), Math.round(r.stallAtMs!! / 100.0) / 10.0)), line)
    }

    @Test
    fun `describe says unavailable and too short`() {
        val none = ClockProbe.analyze(stream(48_000, 5_000, failFromMs = 0, failToMs = 99_999), 48_000)
        assertEquals(Copy.CLOCK_UNAVAILABLE, ClockProbe.describe(none))
        val short = ClockProbe.analyze(stream(48_000, 1_520), 48_000)
        assertEquals(Copy.CLOCK_TOO_SHORT, ClockProbe.describe(short))
    }
}
