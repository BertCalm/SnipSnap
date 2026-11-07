package com.snipsnap.shell

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How late and how uneven the beat's steps land, before and after they are played on the audio clock.
 *
 * A deterministic model of the two clocks the beat sits between, run through the real code for the
 * new path ([BeatSchedule]) and through a faithful copy of the old dispatch for the old one:
 *
 *  - the screen: a vsync every 1/60 s, each frame's callback running a little after it (usually
 *    0.5-3 ms, sometimes 6-12 ms), with two frames dropped and one callback 30 ms late;
 *  - the audio stream: a callback every burst (192 and 480 frames at 48 kHz here), itself a quarter
 *    millisecond ragged, whose frame counter [BeatSchedule.AudioClock] reads like `framesRendered()`;
 *  - the engine: a command pushed before a callback is taken at the top of it; an `atFrame` becomes
 *    an offset from that block's first frame, exactly as `PadEngine::takeCommands` does it.
 *
 * What is simulated is the arithmetic and the timing model above. What is not: a real device's callback
 * jitter, its counter's behaviour on MMAP or over A2DP, or the main thread's. The numbers say the
 * scheduling is sound; they are not a measurement of any phone.
 *
 * Error is measured in the stream's own timeline: frame `f` is the audio that was `f - burst` frames into
 * the counter's line, so a hit ideally due at time T and started on frame f is `line(f) - T` late. The
 * *bias* is the mean of that and moves a whole pattern together (it is latency, which RECORD already
 * measures); the *jitter* is its spread around the mean and is what a bounce prints.
 */
class BeatTimingSimulationTest {

    private val sampleRate = 48_000
    private val bpm = 120f
    private val stepsPerSecond = BeatClock.stepsPerSecond(bpm)
    private val vsyncNanos = 1_000_000_000.0 / 60.0

    /** A two-bar pattern of straight-eighth hats and a kick and snare, the off-16ths pushed late by 20% of a step: swing, in pulses. */
    private fun swungClip(): Mpc3Clip {
        val step = GrooveEdit.STEP_PULSES
        val swing = (step * 0.20).toLong()
        val notes = ArrayList<Mpc3Note>()
        for (s in 0 until 32) {
            val t = s * step + if (s % 2 == 1) swing else 0L
            notes += Mpc3Note(note = 42, timePulses = t, velocity = 0.8f)
        }
        for (bar in 0 until 2) {
            notes += Mpc3Note(note = 36, timePulses = (bar * 16 + 0) * step, velocity = 1f)
            notes += Mpc3Note(note = 36, timePulses = (bar * 16 + 10) * step, velocity = 1f)
            notes += Mpc3Note(note = 38, timePulses = (bar * 16 + 4) * step, velocity = 1f)
            notes += Mpc3Note(note = 38, timePulses = (bar * 16 + 12) * step, velocity = 1f)
        }
        return Mpc3Clip("T", bars = 2, notes = notes.sortedBy { it.timePulses })
    }

    private class Result(val name: String, val errorsMs: DoubleArray, val ioiErrorsMs: DoubleArray, val late: Int) {
        val mean: Double get() = errorsMs.average()
        val std: Double get() = sqrt(errorsMs.sumOf { (it - mean) * (it - mean) } / errorsMs.size)
        val maxDev: Double get() = errorsMs.maxOf { abs(it - mean) }
        val maxIoi: Double get() = ioiErrorsMs.maxOf { abs(it) }
        val ioiStd: Double get() = sqrt(ioiErrorsMs.sumOf { it * it } / ioiErrorsMs.size)
        fun line(): String =
            "%-34s hits=%4d  bias %6.2f ms  jitter std %5.2f ms  max|dev| %5.2f ms  IOI std %5.2f ms  max|IOI err| %5.2f ms  late %d".format(
                name, errorsMs.size, mean, std, maxDev, ioiStd, maxIoi, late,
            )
    }

    /** One run of the model; [scheduled] picks the new dispatch or the old one. */
    private fun simulate(name: String, burst: Int, scheduled: Boolean, seed: Long, seconds: Int = 24): Result {
        val rnd = Random(seed)
        val clip = swungClip()
        val totalSteps = GrooveEdit.stepsInClip(clip).toFloat()
        val barSteps = GrooveEdit.stepsPerBar(clip)
        val burstNanos = burst * 1e9 / sampleRate

        // The audio callbacks: where each runs, a little ragged. Callback k renders frames [k*burst, (k+1)*burst).
        val callbackCount = (seconds + 2) * sampleRate / burst
        val callbackAt = LongArray(callbackCount)
        val phase = 1_234_567.0
        for (k in 0 until callbackCount) {
            callbackAt[k] = (phase + k * burstNanos + (rnd.nextDouble() - 0.5) * 0.5e6).toLong()
        }
        // The line the counter sits on: after callback k (at its time) the counter reads (k+1)*burst.
        // Frame f is therefore, in wall time, line(f) = phase + (f - burst) / rate.
        fun lineNanos(frame: Long): Double = phase + (frame - burst) * 1e9 / sampleRate
        fun framesRenderedAt(nanos: Long): Long {
            // The last callback that has finished by `nanos` (0.05 ms to finish).
            var lo = 0
            var hi = callbackCount - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (callbackAt[mid] + 50_000L <= nanos) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            return if (found < 0) 0L else (found + 1).toLong() * burst
        }
        fun callbackTaking(pushedAt: Long): Int {
            var lo = 0
            var hi = callbackCount - 1
            var ans = callbackCount - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (callbackAt[mid] >= pushedAt) { ans = mid; hi = mid - 1 } else lo = mid + 1
            }
            return ans
        }

        // The frames: vsync times, the instant each callback actually runs, two dropped, one very late.
        val frames = ArrayList<LongArray>() // [vsync, wall]
        var j = 0
        val dropped = setOf(260, 700)
        var v = 2_000_000_000.0
        while (v < 2_000_000_000.0 + seconds * 1e9) {
            if (j !in dropped) {
                val delay = when {
                    j == 400 -> 30e6
                    rnd.nextDouble() < 0.05 -> 6e6 + rnd.nextDouble() * 6e6
                    else -> 0.5e6 + rnd.nextDouble() * 2.5e6
                }
                frames += longArrayOf(v.toLong(), (v + delay).toLong())
            }
            v += vsyncNanos
            j++
        }

        val clock = BeatSchedule.AudioClock()
        clock.reset(sampleRate)
        val cursor = BeatSchedule.Cursor()
        var lastPos = 0f
        var lastNanos = frames[0][0]
        val startNanos = lastNanos
        val errors = ArrayList<Double>()
        val ideals = ArrayList<Double>()
        val actuals = ArrayList<Double>()
        var late = 0
        var passes = 0L
        val seen = HashMap<Pair<Long, Int>, Int>()

        for ((idx, fr) in frames.withIndex()) {
            if (idx == 0) continue
            val vsync = fr[0]
            val wall = fr[1]
            val dt = BeatClock.dtNanos(vsync, lastNanos)
            lastNanos = vsync
            var np = BeatClock.advance(lastPos, dt, stepsPerSecond)
            val wrapped = BeatClock.wrapped(np, totalSteps)
            clock.observe(framesRenderedAt(wall), wall)
            val scheduling = scheduled && clock.ready
            val from = cursor.start(lastPos)
            val to = cursor.end(lastPos, np, BeatSchedule.aheadSteps(scheduling, stepsPerSecond))

            // The ideal time of a step: the loop started at `startNanos`, and a step is `1/sps` s.
            fun idealNanos(stepPos: Double, passesSoFar: Long): Double =
                startNanos + (passesSoFar * totalSteps + stepPos) / stepsPerSecond * 1e9

            val hits = ArrayList<DoubleArray>() // [stepPos unwrapped, noteIndex]
            if (scheduled) {
                BeatSchedule.forEachNoteDue(clip, from, to, totalSteps) { n, pos ->
                    hits += doubleArrayOf(pos, clip.notes.indexOf(n).toDouble())
                }
            } else {
                // The old dispatch, as it was: `crossed` over (lastPos, np].
                for ((i, n) in clip.notes.withIndex()) {
                    if (BeatClock.crossed(BeatClock.stepOf(n), lastPos, np, totalSteps)) {
                        val p = BeatClock.stepOf(n).toDouble()
                        hits += doubleArrayOf(if (p > lastPos && p <= np) p else p + totalSteps, i.toDouble())
                    }
                }
            }
            val k = callbackTaking(wall)
            for (h in hits) {
                // `h[0]` is in the needle's coordinates for this frame's pass, past the loop's end for the next pass's notes.
                val ideal = idealNanos(h[0], passes)
                val frame: Long
                if (scheduling) {
                    val due = BeatSchedule.dueNanos(vsync, np, h[0], stepsPerSecond)
                    val at = clock.frameAt(due)
                    val blockStart = k.toLong() * burst
                    frame = max(at, blockStart)
                    if (at < blockStart) late++
                } else {
                    frame = k.toLong() * burst
                }
                errors += (lineNanos(frame) - ideal) / 1e6
                ideals += ideal
                actuals += lineNanos(frame)
                val key = Pair(Math.round(ideal / 1e3), h[1].toInt())
                seen.merge(key, 1, Int::plus)
            }

            cursor.advance(to, if (wrapped) totalSteps else 0f)
            if (wrapped) passes++
            np = BeatClock.wrap(np, totalSteps)
            lastPos = np
        }

        // Every hit exactly once, however frames dropped or ran late.
        assertTrue(seen.values.all { it == 1 }, "$name: a hit sounded twice: ${seen.filterValues { it > 1 }.keys.take(3)}")

        // The new path is up to a lookahead past the last frame when the run ends; the old one never is. Compare what both reached.
        val horizon = frames.last()[0].toDouble()
        val keep = errors.indices.filter { ideals[it] <= horizon }
        val errs = keep.map { errors[it] }
        val idl = keep.map { ideals[it] }
        val act = keep.map { actuals[it] }

        // Inter-onset error: the gap between consecutive hits as sounded, less the gap as written.
        val order = errs.indices.sortedBy { idl[it] }
        val ioi = DoubleArray(order.size - 1) { i ->
            val a = order[i]
            val b = order[i + 1]
            ((act[b] - act[a]) - (idl[b] - idl[a])) / 1e6
        }
        return Result(name, errs.toDoubleArray(), ioi, late)
    }

    @Test
    fun `scheduling on the audio clock removes the frame jitter, and no hit is dropped or doubled`() {
        val rows = ArrayList<String>()
        for (burst in intArrayOf(192, 480)) {
            val before = simulate("old, burst $burst", burst, scheduled = false, seed = 7)
            val after = simulate("new, burst $burst", burst, scheduled = true, seed = 7)
            rows += before.line()
            rows += after.line()

            assertEquals(before.errorsMs.size, after.errorsMs.size, "both paths sound every hit")
            // Sub-millisecond, where the old path is several milliseconds ragged. The worst single hit is the
            // run's first, played before the clock has seen more than a few readings (about 1 ms early).
            assertTrue(after.std < 0.5, "new jitter std ${after.std} ms at burst $burst")
            assertTrue(after.maxDev < 1.5, "new worst deviation ${after.maxDev} ms at burst $burst")
            assertTrue(after.maxIoi < 1.5, "new worst inter-onset error ${after.maxIoi} ms at burst $burst")
            assertTrue(before.std > 3.0 * after.std, "the old path should be much more ragged: ${before.std} vs ${after.std}")
        }
        println("BEAT TIMING (simulated: 60 Hz vsync with jank, 120 BPM, 20% swing, 48 kHz)")
        rows.forEach { println(it) }
    }

    @Test
    fun `a stream restart drops the clock's history and it is ready again after a few frames`() {
        val clock = BeatSchedule.AudioClock()
        clock.reset(48_000)
        var n = 1_000_000_000L
        var frames = 0L
        repeat(10) { clock.observe(frames, n); n += 16_666_667L; frames += 800 }
        assertTrue(clock.ready)
        val before = clock.frameAt(n)
        // The stream was down for two seconds and the counter did not move while it was.
        n += 2_000_000_000L
        clock.observe(frames, n)
        assertTrue(!clock.ready, "one sample after a jump is not a line")
        repeat(5) { n += 16_666_667L; frames += 800; clock.observe(frames, n) }
        assertTrue(clock.ready)
        val after = clock.frameAt(n)
        assertTrue(abs((after - frames) - 0L) < 1_000, "the new line sits on the counter, not 2 s ahead of it ($after vs $frames)")
        assertTrue(before < after)
    }

    @Test
    fun `the clock maps a time to the frame the counter will be on`() {
        val clock = BeatSchedule.AudioClock()
        clock.reset(48_000)
        var n = 5_000_000_000L
        // A counter that moves in bursts of 240: it reads the last burst finished.
        repeat(40) {
            val exact = (n - 5_000_000_000L) * 48_000L / 1_000_000_000L
            clock.observe(exact - exact % 240, n)
            n += 16_666_667L
        }
        val target = n + 30_000_000L
        val want = (target - 5_000_000_000L) * 48_000L / 1_000_000_000L
        assertTrue(abs(clock.frameAt(target) - want) <= 240, "within a burst of the true frame")
    }
}
