package com.snipsnap.shell

/**
 * Bench S1: does the phone's reported playback position tell the truth?
 * Pure analysis of samples taken once per display frame; the reading itself
 * is elsewhere. Spec: docs/MIDI_CLOCK_PROBE_SPEC_2026_10.md section 2.2.
 */
object ClockProbe {

    /** Proposed. A stall needs the audio written to advance by more than this while the position does not. */
    const val MOVING_AUDIO_MS = 100L

    /** Proposed. Stalls shorter than this are ignored; at or above it the verdict is STALLED. */
    const val STALL_FLOOR_MS = 200L

    /** Proposed. A start-up gap longer than this is SLOW_START. */
    const val SLOW_START_MS = 250L

    /** Proposed. Less than this much run after the first movement is TOO_SHORT, and no rate is fitted. */
    const val MIN_TRACKED_MS = 2_000L

    /**
     * Proposed. The least the position must move to count as moving at all, in ms of audio at
     * the stream's rate. A healthy 48 kHz stream moves about 800 frames per display-frame read
     * and 4800 per 100 ms, so "moved at most this much while at least [MOVING_AUDIO_MS] was
     * written" is a stall whatever the device's burst size. It is the floor under the burst
     * [analyze] infers.
     */
    const val BURST_FLOOR_MS = 10L

    enum class Verdict { UNAVAILABLE, TOO_SHORT, STALLED, SLOW_START, TRACKED }

    data class Sample(
        val wallNanos: Long,        // System.nanoTime() when the main thread read it
        val framesWritten: Long,    // PadEngine.framesRendered()
        val hwFrame: Long?,         // getTimestamp position, null when the call failed
        val hwNanos: Long?,         // getTimestamp time, null when the call failed
    )

    data class Result(
        val verdict: Verdict,
        val samples: Int,
        val validSamples: Int,
        val startupGapMs: Double?,
        val longestStallMs: Double,
        val stallAtMs: Double?,
        val rateErrorPpm: Double?,
        val lagMs: Double?,
    )

    private const val NANOS_PER_MS = 1_000_000.0

    /**
     * Judge a run of samples against [sampleRate] (frames per second of the stream).
     *
     * "One burst" for the stall rule is **inferred**, not a parameter: the smallest positive
     * step between consecutive distinct hardware positions after the first movement, but never
     * less than [BURST_FLOOR_MS] of audio at [sampleRate] (one odd tiny step must not set a
     * threshold a creeping stall can beat). A position that moves by no more than that
     * between two reads has not really moved.
     *
     * Sign of `rateErrorPpm`: positive when the hardware clock runs fast against [sampleRate]
     * (it counts more frames per second than the stream's rate), negative when slow.
     * Throws [IllegalArgumentException] if [sampleRate] is not positive.
     * Choices the spec leaves open: stalls are timed on `wallNanos` (failed reads have no
     * hardware time); the lag and rate are taken over the samples from the first movement on.
     *
     * Verdict order: UNAVAILABLE, never-moved-STALLED, TOO_SHORT, STALLED, SLOW_START, TRACKED.
     * The second is the controller's addition to the spec: a valid position that never moves
     * while at least [STALL_FLOOR_MS] of audio is written is STALLED, with `startupGapMs` null,
     * `longestStallMs` the span from the first written frame to the last sample, and no rate or
     * lag. Never moved with less audio than that stays TOO_SHORT.
     */
    fun analyze(samples: List<Sample>, sampleRate: Int): Result {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        val n = samples.size
        val validSamples = samples.count { it.hwFrame != null }
        if (validSamples == 0) {
            return Result(Verdict.UNAVAILABLE, n, validSamples, null, 0.0, null, null, null)
        }

        // First movement: first sample whose position differs from the first valid one.
        val firstValid = samples.indexOfFirst { it.hwFrame != null }
        val firstHw = samples[firstValid].hwFrame!!
        val move = (firstValid + 1 until n).firstOrNull { val h = samples[it].hwFrame; h != null && h != firstHw }

        val firstWritten = samples.indexOfFirst { it.framesWritten > 0 }
        val startupGapMs = if (move != null && firstWritten >= 0) {
            maxOf(0L, samples[move].wallNanos - samples[firstWritten].wallNanos) / NANOS_PER_MS
        } else null

        if (move == null) {
            // Never moved. If audio was written for a stall's worth of time, that is the worst
            // lie S1 exists to catch, not a run to repeat.
            val writtenFrames = samples.last().framesWritten - (if (firstWritten >= 0) samples[firstWritten].framesWritten else 0L)
            if (firstWritten >= 0 && writtenFrames >= STALL_FLOOR_MS * sampleRate / 1000.0) {
                val spanMs = (samples.last().wallNanos - samples[firstWritten].wallNanos) / NANOS_PER_MS
                val at = (samples[firstWritten].wallNanos - samples[0].wallNanos) / NANOS_PER_MS
                return Result(Verdict.STALLED, n, validSamples, null, spanMs, at, null, null)
            }
            return Result(Verdict.TOO_SHORT, n, validSamples, null, 0.0, null, null, null)
        }

        val after = samples.subList(move, n)
        val spanMs = (after.last().wallNanos - after.first().wallNanos) / NANOS_PER_MS

        // Stalls. A failed read holds the last good position: it counts as not moving.
        val held = LongArray(after.size)
        var last = firstHw
        for (k in after.indices) {
            after[k].hwFrame?.let { last = it }
            held[k] = last
        }
        var inferred = Long.MAX_VALUE
        for (k in 1 until held.size) {
            val step = held[k] - held[k - 1]
            if (step in 1 until inferred) inferred = step
        }
        val burstFloor = BURST_FLOOR_MS * sampleRate / 1000
        val burst = if (inferred == Long.MAX_VALUE) burstFloor else maxOf(inferred, burstFloor)
        val movingFrames = MOVING_AUDIO_MS * sampleRate / 1000.0
        var longestStall = 0.0
        var stallAt: Double? = null
        var i = 0
        while (i < after.size - 1) {
            var j = i
            while (j + 1 < after.size && held[j + 1] - held[i] <= burst) j++
            // The stall ends where the position moves again, or with the run if it never does.
            val end = if (j + 1 < after.size) j + 1 else j
            val written = after[end].framesWritten - after[i].framesWritten
            val ms = (after[end].wallNanos - after[i].wallNanos) / NANOS_PER_MS
            if (j > i && written > movingFrames && ms >= STALL_FLOOR_MS && ms > longestStall) {
                longestStall = ms
                stallAt = (after[i].wallNanos - samples[0].wallNanos) / NANOS_PER_MS
            }
            i = if (j > i) j else i + 1
        }

        // Lag: framesWritten - hwFrame in ms, context only.
        val lags = after.filter { it.hwFrame != null }.map { it.framesWritten - it.hwFrame!! }
        val lagMs = if (lags.isEmpty()) null else lags.average() * 1000.0 / sampleRate

        // Rate: least-squares slope of hwFrame on hwNanos (the hardware's own clock).
        val pairs = after.filter { it.hwFrame != null && it.hwNanos != null }
        var rateErrorPpm: Double? = null
        if (pairs.size >= 2) {
            val x0 = pairs.first().hwNanos!!
            val xs = DoubleArray(pairs.size) { (pairs[it].hwNanos!! - x0) / 1e9 }
            val ys = DoubleArray(pairs.size) { (pairs[it].hwFrame!! - pairs[0].hwFrame!!).toDouble() }
            val hwSpanMs = (pairs.last().hwNanos!! - x0) / NANOS_PER_MS
            val mx = xs.average()
            val my = ys.average()
            var sxx = 0.0
            var sxy = 0.0
            for (k in xs.indices) {
                sxx += (xs[k] - mx) * (xs[k] - mx)
                sxy += (xs[k] - mx) * (ys[k] - my)
            }
            if (hwSpanMs >= MIN_TRACKED_MS && sxx > 0.0) {
                rateErrorPpm = (sxy / sxx / sampleRate - 1.0) * 1e6
            }
        }

        val verdict = when {
            spanMs < MIN_TRACKED_MS -> Verdict.TOO_SHORT
            longestStall >= STALL_FLOOR_MS -> Verdict.STALLED
            startupGapMs != null && startupGapMs > SLOW_START_MS -> Verdict.SLOW_START
            else -> Verdict.TRACKED
        }
        return Result(verdict, n, validSamples, startupGapMs, longestStall, stallAt, rateErrorPpm, lagMs)
    }

    /** The one screen line for [result], built from [Copy]. */
    fun describe(result: Result): String = when (result.verdict) {
        Verdict.UNAVAILABLE -> Copy.CLOCK_UNAVAILABLE
        Verdict.TOO_SHORT -> Copy.CLOCK_TOO_SHORT
        Verdict.STALLED -> if (result.startupGapMs == null) {
            Copy.clockNeverMoved(Math.round(result.longestStallMs / 100.0) / 10.0)
        } else listOfNotNull(
            Copy.clockStopped(
                Math.round(result.longestStallMs),
                Math.round((result.stallAtMs ?: 0.0) / 100.0) / 10.0,
                result.startupGapMs?.takeIf { it > SLOW_START_MS }?.let { Math.round(it) },
            ),
            rateSentence(result),
        ).joinToString(" ")
        Verdict.SLOW_START -> listOfNotNull(
            Copy.clockStoodStill(Math.round(result.startupGapMs ?: 0.0)),
            rateSentence(result),
        ).joinToString(" ")
        Verdict.TRACKED -> listOfNotNull(
            Copy.CLOCK_TRACKED,
            rateSentence(result),
            result.lagMs?.let { if (it >= 0.0) Copy.clockBehind(Math.round(it)) else Copy.clockAhead(Math.round(-it)) },
        ).joinToString(" ")
    }

    private fun rateSentence(result: Result): String? =
        result.rateErrorPpm?.let { Copy.clockRate(Math.round(it)) }
}
