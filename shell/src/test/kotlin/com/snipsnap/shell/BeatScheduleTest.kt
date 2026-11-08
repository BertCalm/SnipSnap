package com.snipsnap.shell

import com.snipsnap.kit.GrooveEdit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.util.Random
import kotlin.math.abs
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
            BeatSchedule.forEachNoteDue(clip, from, to, total, passes.toLong()) { _, pos -> out += passes * total + pos }
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
        BeatSchedule.forEachNoteDue(c, from, to, total, 0L) { _, pos -> seen += pos }
        assertEquals(listOf(32.0), seen, "the downbeat is reported unwrapped, one loop on")
        assertEquals(0f, BeatSchedule.wrapPos(32.0, total))
        cursor.advance(to, 0f)
        // The next frame wraps; the cursor goes with it and does not hand step 0 over again.
        val np2 = total + 0.1f
        val to2 = cursor.end(np, np2, BeatSchedule.aheadSteps(true, sps))
        val again = ArrayList<Double>()
        BeatSchedule.forEachNoteDue(c, cursor.start(np), to2, total, 0L) { _, pos -> again += pos }
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

    // ---- BOUNCE's frames -----------------------------------------------------------

    @Test
    fun aBouncePrintsExactlyPassesTimesTheLoopStartingOnTheDownbeatFrame() {
        // 120 BPM, 32 steps: 4 s exactly, a whole number of frames at either rate.
        for ((rate, loop) in listOf(44_100 to 176_400L, 48_000 to 192_000L)) {
            val s = BeatClock.stepsPerSecond(120f)
            for (passes in 1..4) {
                val plan = BeatSchedule.bouncePlan(1_234_567L, passes, 32f, s, rate)
                assertEquals(1_234_567L, plan.startFrame)
                assertEquals(1_234_567L + passes * loop, plan.stopFrame)
                assertEquals(passes * loop, plan.frames)
            }
        }
        // A slower tempo, 90 BPM: 32 steps at 6 steps/s is 5.333 s, 235200 frames at 44.1 kHz and 256000 at 48 kHz.
        val s90 = BeatClock.stepsPerSecond(90f)
        assertEquals(235_200L, BeatSchedule.bounceFrames(1, 32f, s90, 44_100))
        assertEquals(256_000L, BeatSchedule.bounceFrames(1, 32f, s90, 48_000))
        // The rate is the engine's, not a constant: 96 kHz is double 48 kHz, and a clip of 48 steps scales with its length.
        assertEquals(512_000L, BeatSchedule.bounceFrames(1, 32f, s90, 96_000))
        assertEquals(384_000L, BeatSchedule.bounceFrames(1, 48f, s90, 48_000))
    }

    @Test
    fun aLoopThatIsNotAWholeNumberOfFramesIsRoundedOnceOverThePrintNotPerPass() {
        // 97 BPM, 32 steps, 44.1 kHz: one loop is 218226.80... frames. Rounding each pass would put eight passes
        // at 8 * 218227 = 1745816, two frames past the music (1745814.43); rounding the whole print does not drift.
        val s = BeatClock.stepsPerSecond(97f)
        val exactPerPass = 32.0 / s * 44_100
        for (passes in 1..16) {
            val frames = BeatSchedule.bounceFrames(passes, 32f, s, 44_100)
            assertTrue(abs(frames - passes * exactPerPass) <= 0.5 + 1e-6, "passes=$passes frames=$frames exact=${passes * exactPerPass}")
        }
        assertEquals(218_227L, BeatSchedule.bounceFrames(1, 32f, s, 44_100)) // one pass: the nearest whole frame
        assertEquals(1_745_814L, BeatSchedule.bounceFrames(8, 32f, s, 44_100))
        assertTrue(8 * BeatSchedule.bounceFrames(1, 32f, s, 44_100) != BeatSchedule.bounceFrames(8, 32f, s, 44_100))
    }

    @Test
    fun theStopFrameIsTheNextPassesDownbeatNotAFrameDriftedFromIt() {
        // Two tempos, two rates, a print of several passes: the stop frame is the start plus the true length of the
        // passes, to the nearest frame, so a following bounce started on the next downbeat abuts this one.
        for (bpm in listOf(93f, 101f)) for (rate in listOf(44_100, 48_000)) {
            val s = BeatClock.stepsPerSecond(bpm)
            val trueLoop = 32.0 / s * rate
            val plan = BeatSchedule.bouncePlan(500L, 8, 32f, s, rate)
            assertEquals(500L, plan.startFrame)
            assertTrue(abs((plan.stopFrame - 500L) - 8 * trueLoop) <= 0.5 + 1e-6)
        }
    }

    @Test
    fun theDownbeatIsInExactlyOneWindowAndItsFrameIsTheFirstNotesOwn() {
        // The loop's downbeat is the step position `total` unwrapped, in the window that reaches it and in no other.
        val total = 32f
        assertTrue(BeatSchedule.downbeatInWindow(31.0, 32.0, total)) // (from, to]: the end is in
        assertTrue(BeatSchedule.downbeatInWindow(31.5, 33.0, total))
        assertTrue(!BeatSchedule.downbeatInWindow(30.0, 31.9, total))
        assertTrue(!BeatSchedule.downbeatInWindow(32.0, 33.0, total)) // open at the start
        assertTrue(!BeatSchedule.downbeatInWindow(0.0, 1.0, total)) // the next pass's windows, wrapped, start low
        // And it is the note at step 0's own due time: both come from dueNanos at `total`.
        val clip = clip(0)
        val from = 31.0
        val to = 32.5
        var noteDue = -1.0
        BeatSchedule.forEachNoteDue(clip, from, to, total) { _, pos -> noteDue = pos }
        assertEquals(total.toDouble(), noteDue)
        val sps2 = BeatClock.stepsPerSecond(97f)
        assertEquals(
            BeatSchedule.dueNanos(1_000L, 30.25f, noteDue, sps2),
            BeatSchedule.dueNanos(1_000L, 30.25f, total.toDouble(), sps2),
        )
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
            BeatSchedule.forEachNoteDue(rolled, from, to, total, 0L) { _, pos ->
                positions += pos
                frames += audio.frameAt(BeatSchedule.dueNanos(now, np, pos, sps))
            }
            cursor.advance(to, if (wrapped) total else 0f)
            lastPos = BeatClock.wrap(np, total)
        }
        assertEquals(listOf(4.0, 4.25, 4.5, 4.75), positions.take(4))
        assertEquals(4, frames.take(4).toSet().size, "four hits, four frames")
        val gaps = frames.take(4).zipWithNext { a, b -> b - a }
        // 60 pulses at 120 BPM is 31.25 ms: 1500 frames at 48 kHz, to within the clock's rounding.
        for (g in gaps) assertTrue(g in 1499L..1501L, "gap $g frames")
        assertEquals(frames.take(4).sorted(), frames.take(4), "in order")
        assertEquals(4, positions.size, "each hit was handed over exactly once, across window boundaries and the loop's wrap")
    }
}
