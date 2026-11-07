package com.snipsnap.shell

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The window arithmetic behind playing steps on the audio clock: every step handed over once, none missed. */
class BeatScheduleTest {

    private val step = GrooveEdit.STEP_PULSES
    private val sps = BeatClock.stepsPerSecond(120f)

    private fun clip(vararg steps: Int) = Mpc3Clip("T", bars = 2, notes = steps.map { Mpc3Note(note = 36, timePulses = it * step, velocity = 1f) })

    /**
     * Runs frames of the given gaps through the cursor, as BeatPlayer's `dispatch` does, and returns the
     * unwrapped position of every note handed over, in the order handed over.
     */
    private fun run(clip: Mpc3Clip, gapsNanos: List<Long>, schedulingAt: (Int) -> Boolean): List<Double> {
        val total = GrooveEdit.stepsInClip(clip).toFloat()
        val cursor = BeatSchedule.Cursor()
        var lastPos = 0f
        var passes = 0
        val out = ArrayList<Double>()
        for ((i, gap) in gapsNanos.withIndex()) {
            var np = BeatClock.advance(lastPos, BeatClock.dtNanos(gap, 0L), sps)
            val wrapped = BeatClock.wrapped(np, total)
            val from = cursor.start(lastPos)
            val to = cursor.end(lastPos, np, BeatSchedule.aheadSteps(schedulingAt(i), sps))
            BeatSchedule.forEachNoteDue(clip, from, to, total) { _, pos -> out += passes * total + pos }
            cursor.advance(to, if (wrapped) total else 0f)
            if (wrapped) passes++
            np = BeatClock.wrap(np, total)
            lastPos = np
        }
        return out
    }

    private fun expected(clip: Mpc3Clip, upToSteps: Double): List<Double> {
        val total = GrooveEdit.stepsInClip(clip).toDouble()
        val out = ArrayList<Double>()
        var pass = 0
        while (pass * total < upToSteps) {
            for (n in clip.notes.sortedBy { it.timePulses }) {
                val p = pass * total + BeatClock.stepOf(n).toDouble()
                if (p > 0.0 && p <= upToSteps) out += p
            }
            pass++
        }
        return out
    }

    @Test
    fun `a step is due when the needle reaches it, in nanoseconds from the frame`() {
        // At 120 BPM a step is 125 ms. The needle is at 3.0; a step at 3.5 is 62.5 ms on, one in the next pass is further.
        assertEquals(1_062_500_000L, BeatSchedule.dueNanos(1_000_000_000L, 3f, 3.5, sps))
        assertEquals(1_000_000_000L + 125_000_000L * 30, BeatSchedule.dueNanos(1_000_000_000L, 2f, 32.0, sps))
        assertTrue(BeatSchedule.dueNanos(1_000_000_000L, 5f, 4.5, sps) < 1_000_000_000L, "a step behind the needle is already due")
    }

    @Test
    fun `the lookahead is a window of steps past the needle, and nothing when scheduling is off`() {
        assertEquals(0.32, BeatSchedule.aheadSteps(true, sps), 1e-9)
        assertEquals(0.0, BeatSchedule.aheadSteps(false, sps))
    }

    @Test
    fun `every note is handed over once and none is missed, across jitter, drops and the loop's end`() {
        val c = clip(0, 1, 2, 5, 8, 13, 16, 17, 24, 31)
        val rnd = Random(3)
        // 400 frames: mostly 16.7 ms with jitter, a few 33 ms drops and a 100 ms stall (the clock's own cap).
        val gaps = List(400) { i ->
            when {
                i % 97 == 50 -> 100_000_000L
                i % 31 == 7 -> 33_333_333L
                else -> 16_666_667L + ((rnd.nextDouble() - 0.5) * 4e6).toLong()
            }
        }
        for (always in listOf(true, false)) {
            val got = run(c, gaps) { always }
            val travelled = gaps.sumOf { BeatClock.dtNanos(it, 0L) } / 1e9 * sps
            // Scheduled windows reach a lookahead past the needle; compare what both had reached.
            val reach = travelled + if (always) BeatSchedule.aheadSteps(true, sps) else 0.0
            assertEquals(expected(c, reach), got, "scheduling=$always")
        }
    }

    @Test
    fun `turning scheduling on and off mid-run neither doubles nor loses a note`() {
        val c = clip(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30)
        val gaps = List(300) { 16_666_667L }
        // Off, then on, then off again: the windows are empty while the cursor is ahead of the needle.
        val got = run(c, gaps) { i -> i in 80..160 }
        // Float accumulation can leave the needle a hair short of a note sitting exactly on the last frame.
        val travelled = gaps.size * 16_666_667L / 1e9 * sps - 0.01
        val sorted = got.sorted()
        assertEquals(got.size, got.toSet().size, "no note twice")
        assertEquals(expected(c, travelled), sorted.filter { it <= travelled }, "every note the needle passed was handed over")
    }

    @Test
    fun `a note at the top of the loop is handed over on the frame before the wrap`() {
        val c = clip(0, 8)
        val total = 32f
        val cursor = BeatSchedule.Cursor()
        // The needle 0.1 step from the top; a lookahead of 0.32 step reaches the next pass's step 0 (at 32 + 0).
        val lastPos = total - 0.2f
        val np = total - 0.1f
        val from = cursor.start(lastPos)
        val to = cursor.end(lastPos, np, BeatSchedule.aheadSteps(true, sps))
        val seen = ArrayList<Double>()
        BeatSchedule.forEachNoteDue(c, from, to, total) { _, pos -> seen += pos }
        assertEquals(listOf(32.0), seen, "the downbeat is reported unwrapped, one loop on")
        assertEquals(0f, BeatSchedule.wrapPos(32.0, total))
        cursor.advance(to, 0f)
        // The next frame wraps; the cursor goes with it and does not hand step 0 over again.
        val np2 = total + 0.1f
        val to2 = cursor.end(np, np2, BeatSchedule.aheadSteps(true, sps))
        val again = ArrayList<Double>()
        BeatSchedule.forEachNoteDue(c, cursor.start(np), to2, total) { _, pos -> again += pos }
        assertTrue(again.isEmpty(), "already handed over: $again")
    }

    @Test
    fun `a stop or a seek starts the next window at the needle`() {
        val cursor = BeatSchedule.Cursor()
        cursor.advance(10.3, 0f)
        assertEquals(10.3, cursor.start(9.9f), 1e-9)
        cursor.reset()
        assertEquals(9.9f.toDouble(), cursor.start(9.9f), 1e-9)
    }

    @Test
    fun `when scheduling goes off the cursor comes back to the needle`() {
        val cursor = BeatSchedule.Cursor()
        cursor.advance(10.3, 0f)
        assertTrue(cursor.leadOver(10.0f) > 0.0)
        cursor.rewindTo(10.0f)
        assertEquals(10.0, cursor.start(9.9f), 1e-6)
        assertEquals(0.0, cursor.leadOver(10.0f), 1e-6)
    }

    @Test
    fun `the metronome walks the same windows as the notes, with the bar's downbeat accented`() {
        val seen = ArrayList<Pair<Boolean, Double>>()
        BeatSchedule.forEachBeatDue(32f, 11.0, 17.2, 16) { accent, pos -> seen += accent to pos }
        assertEquals(listOf(false to 12.0, true to 16.0), seen)
        val wrap = ArrayList<Pair<Boolean, Double>>()
        BeatSchedule.forEachBeatDue(32f, 31.0, 32.3, 16) { accent, pos -> wrap += accent to pos }
        assertEquals(listOf(true to 32.0), wrap, "the top of the loop is the next pass's downbeat")
    }

    @Test
    fun `a x4 roll at 120 BPM is four distinct engine frames on the audio clock path, none merged and none dropped`() {
        val lane = GrooveEdit.Lane.HAT_CLOSED
        val base = GrooveEdit.toggleStep(Mpc3Clip("Kit E", 1, emptyList()), lane, 4)
        val rolled = com.snipsnap.kit.StepShape.setRoll(base, lane, 4, 4)
        assertEquals(4, rolled.notes.size)
        val total = GrooveEdit.stepsInClip(rolled).toFloat()
        val rate = 48_000
        // A steady stream: the engine's counter read against the frame clock, as BeatPlayer feeds it.
        val audio = BeatSchedule.AudioClock()
        audio.reset(rate)
        for (i in 0 until 20) audio.observe(framesRendered = (i * 16_666_667L * rate / 1_000_000_000L), nanos = i * 16_666_667L)
        assertTrue(audio.ready)

        val cursor = BeatSchedule.Cursor()
        val frames = ArrayList<Long>()
        val positions = ArrayList<Double>()
        var lastPos = 0f
        var now = 0L
        // 130 frames of 60 Hz: the roll's hits fall in different windows, and the loop wraps (at 2 s) before the end.
        for (i in 0 until 130) {
            now += 16_666_667L
            val np = BeatClock.advance(lastPos, BeatClock.dtNanos(16_666_667L, 0L), sps)
            val wrapped = BeatClock.wrapped(np, total)
            val from = cursor.start(lastPos)
            val to = cursor.end(lastPos, np, BeatSchedule.aheadSteps(true, sps))
            BeatSchedule.forEachNoteDue(rolled, from, to, total) { _, pos ->
                positions += pos
                frames += audio.frameAt(BeatSchedule.dueNanos(now, np, pos, sps))
            }
            cursor.advance(to, if (wrapped) total else 0f)
            lastPos = BeatClock.wrap(np, total)
        }
                assertEquals(listOf(4.0, 4.125, 4.25, 4.375), positions.take(4))
        assertEquals(4, frames.take(4).toSet().size, "four hits, four frames")
        val gaps = frames.take(4).zipWithNext { a, b -> b - a }
        // 30 pulses at 120 BPM is 15.625 ms: 750 frames at 48 kHz, to within the clock's rounding.
        for (g in gaps) assertTrue(g in 749L..751L, "gap $g frames")
        assertEquals(frames.take(4).sorted(), frames.take(4), "in order")
        assertEquals(4, positions.size, "each hit was handed over exactly once, across window boundaries and the loop's wrap")
    }
}
