package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ARCO's physics: the bow wave itself, read at the rate the bow runs at (176.4 kHz) and before the box, the band
 * limit, the decimator and the level. A stuck, silent or scratching string must not be lifted to full scale by
 * [Dsp.levelTo] and pass for a note, so every claim about the string reads [Arco.bow]'s raw wave and the string's
 * velocity under the bow ([Strings.Bow.bowPoint], the tap the slip counter reads). The pitch claims are the one
 * exception: they read the finished 44.1 kHz render with a windowed FFT, which is what a player hears and what
 * [FineTuning] was built for, and are cross-checked by autocorrelation on the raw wave.
 *
 * Every number asserted here was measured on the built engine by R1b, or by R1c where the KDoc says so (R1c retuned the bite, the box and CELLO's vibrato; the figure is in the KDoc of the test that
 * holds it, "R1b saw X; the bound is Y") and every bound sits past the measurement with room, except where that KDoc says the margin is thin or none (the HOLD-0 stab count, the
 * default-note count, C3's onset, the overshoot row). "The interim bite" in this file is the 2.5 times, 120 ms, quarter-of-the-share setting of R1c's fix round (`INTERIM`), history. The measured tables are
 * printed on lines that start `ARCO`, so a change that moves a number can be read against the old one in the log.
 * A claim that could not fail proves nothing, so each test either carries a negative control (a cell, a bow or a
 * signal the same judgement must refuse) or states the margin that shows it can.
 */
class ArcoTest {

    private val rate = ArcoMeasure.RATE

    private fun f(v: Double, digits: Int = 2) = "%.${digits}f".format(java.util.Locale.ROOT, v)

    private fun name(voice: ArcoVoice, step: Int) = ArcoMeasure.noteName(voice, step)

    /** The machine has four cores and other tests share it: the big grids run across them. */
    private fun <T, R> List<T>.pmap(op: (T) -> R): List<R> = parallelStream().map { op(it) }.toList()

    /** Root, middle and top of a voice's TUNE: the three notes the claims that cannot afford every step are read at. */
    private fun spread(voice: ArcoVoice): List<Int> = ArcoMeasure.steps(voice).last.let { listOf(0, (it + 1) / 2, it) }

    // ---------------------------------------------------------------- 1. it speaks

    /** How long a bow stays on the string in the speaks table: long enough for the slowest lock, with 2.6 s of a settled note after it. */
    private val speaksBowOn = 3.5f

    /**
     * The longest a string may take to lock into one slip a period along GRIP's path at BOW 0.5, per voice. R1b saw CELLO
     * 0.868 s (D#2 at GRIP 1) and ERHU 0.242 s (D4 at GRIP 0.4); the bars are 1.2 s and 0.5 s. A cello's low strings are slow
     * to lock because a period is long and the bridge's loss per period is small: the friction has to beat it a hundred
     * times over before the scratch gives way to one slip.
     */
    private val lockBar = mapOf(ArcoVoice.CELLO to 1.2, ArcoVoice.ERHU to 0.5)

    /**
     * What it takes to count as speaking: a lock inside the voice's bar, a last slip within 1.5 periods of the end, and a wave
     * that repeats. The lock is the first slip after the last unclean gap ([ArcoMeasure.lockSeconds]), so a note that locks
     * inside the bar has one slip a period from there to the end of the bow-on, and a note whose last gap is unclean has no lock.
     */
    private fun speakingProblems(v: ArcoMeasure.Verdict, bar: Double): List<String> = buildList {
        if (v.lock < 0) add("never locks into one slip a period")
        else if (v.lock > bar) add("locks only after ${f(v.lock)} s (bar ${f(bar)})")
        if (v.tailPeriods > 1.5) add("the last slip is ${f(v.tailPeriods, 1)} periods before the end of the bow-on")
        if (v.peak <= 0.9) add("autocorrelation peak ${f(v.peak)}")
    }

    /**
     * Along the real GRIP path (0 to 1 in tenths, BOW 0.5, BODY 0, no vibrato) at every TUNE step of both voices, the string
     * locks into one slip a period and stays there to the end of a 3.5 s bow-on: 495 cells.
     *
     * R1b saw 0 of 495 fail. The lock times are printed per step; the worst are CELLO 0.868 s (D#2, GRIP 1) and ERHU
     * 0.242 s (D4, GRIP 0.4), the lowest autocorrelation peak 0.95 (CELLO) and 0.99 (ERHU), and in every cell the last
     * slip is within 1.00 periods of the end of the bow-on (a string that fell silent has a long one). The bars are in
     * [lockBar], the peak bar is the house's 0.9, and the silence bar is 1.5 periods. Slips are counted as the gaps between
     * them ([ArcoMeasure.lockSeconds]) and not over a fixed number of nominal periods, because a note a few cents off its
     * nominal pitch reads 9 or 11 in ten periods whatever the string does.
     *
     * The negative control is [a cell off the window is judged as not speaking]: the same judgement, applied to cells GRIP
     * never reaches, must say no.
     */
    @Test
    fun `along the whole of GRIP at every TUNE step the string locks into one slip a period`() {
        val started = System.nanoTime()
        for (voice in ArcoVoice.entries) {
            val steps = ArcoMeasure.steps(voice).toList()
            val rows = steps.pmap { step ->
                ArcoMeasure.GRIP_POINTS.map { grip ->
                    ArcoMeasure.verdict(ArcoMeasure.core(voice, step, bow = 0.5f, grip = grip, gateSeconds = speaksBowOn))
                }
            }
            println("ARCO lock ms at GRIP 0.0 0.1 ... 1.0 ($voice, BOW 0.5, bow on $speaksBowOn s):")
            val failures = ArrayList<String>()
            var worst = 0.0
            var worstAt = ""
            var minPeak = 1.0
            var maxTail = 0.0
            for ((i, step) in steps.withIndex()) {
                val row = rows[i]
                println("ARCO   ${name(voice, step).padEnd(3)} ${step.toString().padStart(2)}: " + row.joinToString(" ") { (it.lock * 1000).roundToInt().toString().padStart(4) })
                for ((g, v) in row.withIndex()) {
                    val label = "$voice ${name(voice, step)} GRIP ${ArcoMeasure.GRIP_POINTS[g]}"
                    if (v.lock > worst) {
                        worst = v.lock
                        worstAt = label
                    }
                    minPeak = min(minPeak, v.peak)
                    maxTail = max(maxTail, v.tailPeriods)
                    val problems = speakingProblems(v, lockBar.getValue(voice))
                    if (problems.isNotEmpty()) failures.add("$label: $problems")
                }
            }
            println("ARCO lock $voice: worst ${f(worst * 1000, 0)} ms at $worstAt; lowest autocorrelation peak ${f(minPeak)}; longest silence at the end ${f(maxTail, 2)} periods")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
        println("ARCO lock table took ${f((System.nanoTime() - started) / 1e9, 1)} s")
    }

    /**
     * The negative control for the speaks table: CELLO C2 at pressures and corners GRIP never travels. With the full
     * 3023.6 Hz corner the string slips three times a period at pressure 0.5 and 0.6 and never locks (R1b saw 3.5 and 3.2
     * slips a period over the bow-on), and locks only after 2.84 s at 0.7, 1.71 s at 0.8 and 3.48 s at 0.9, all past the
     * 1.2 s bar. (A dark 1000 Hz corner at pressure 0.91 takes 1.29 s, a bare miss that is printed and not asserted; 0.88
     * and 0.94 at 1000 Hz lock in 0.75 s and 1.07 s and are inside the bar.) The judgement that says "speaks" for
     * 495 cells also says "does not" for these five, so "0 of 495 failed" is not an empty sentence.
     */
    @Test
    fun `a cell off the window is judged as not speaking`() {
        val full = Strings.Bow.BRIDGE_HZ
        val cells = listOf(0.5f to full, 0.6f to full, 0.7f to full, 0.8f to full, 0.9f to full, 0.88f to 1000f, 0.91f to 1000f, 0.94f to 1000f)
        val asserted = 5
        val bar = lockBar.getValue(ArcoVoice.CELLO)
        val results = cells.pmap { (p, c) ->
            val core = ArcoMeasure.core(ArcoVoice.CELLO, 0, bow = 0.5f, grip = 0f, pressure = p, cornerHz = c, gateSeconds = speaksBowOn)
            val v = ArcoMeasure.verdict(core)
            val perPeriod = ArcoMeasure.slipTimes(core.bowPoint, core.holdN).size / (core.holdN.toDouble() / rate * core.hz)
            Triple(v, speakingProblems(v, bar), perPeriod)
        }
        for ((i, r) in results.withIndex()) {
            val (p, c) = cells[i]
            println("ARCO off-window CELLO C2 pressure $p corner ${f(c.toDouble(), 0)} Hz: lock ${f(r.first.lock)} s, ${f(r.third, 1)} slips a period over the bow-on, problems ${r.second}")
        }
        for (i in 0 until asserted) {
            assertTrue(results[i].second.isNotEmpty(), "pressure ${cells[i].first} at the full corner was judged to speak: it locks in ${results[i].first.lock} s")
        }
        assertTrue(results[0].third > 2.5 && results[1].third > 2.5, "pressure 0.5 and 0.6 at the full corner should slip about three times a period: ${results[0].third}, ${results[1].third}")
    }

    // ---------------------------------------------------------------- 2 and 3. in tune, and GRIP is not a pitch knob

    /**
     * How the settled pitch is read: a bow-on long enough to be well past the lock, and a window inside it. CELLO's slowest
     * lock in these cells (BOW 0, default and 1 at GRIP 0, default and 1) is 1.44 s (R1c saw G2 at BOW 1, GRIP 1, the bite's own; R1b's was 1.35 s at A2, BOW 0, GRIP 0, which has no bite) so its window starts
     * at 2.0 s (1.0 s long, in a 3.2 s bow-on); ERHU's is 0.42 s (A4, BOW 0, GRIP 0) so its window starts at 0.8 s (0.7 s long,
     * in 1.6 s). Each cell asserts the window starts at least 0.3 s past its own lock, so a slower engine fails here and
     * not in the cents; CELLO's has 0.26 s of room left (2.0 against 1.44 plus 0.3).
     */
    private val tunedBowOn = mapOf(ArcoVoice.CELLO to 3.2f, ArcoVoice.ERHU to 1.6f)
    private val tunedFrom = mapOf(ArcoVoice.CELLO to 2.0f, ArcoVoice.ERHU to 0.8f)
    private val tunedSpan = mapOf(ArcoVoice.CELLO to 1.0f, ArcoVoice.ERHU to 0.7f)

    /** The pitch read and the cell's lock time, at one TUNE step, GRIP and BOW (with GRIP's pressure replaced when [pressure] is given). */
    private fun tunedRead(voice: ArcoVoice, step: Int, grip: Float, bow: Float, pressure: Float? = null): Pair<ArcoMeasure.PitchRead, Double> {
        val core = ArcoMeasure.core(voice, step, bow = bow, grip = grip, pressure = pressure, gateSeconds = tunedBowOn.getValue(voice))
        val lock = ArcoMeasure.verdict(core).lock
        return ArcoMeasure.pitchOf(core, tunedFrom.getValue(voice), tunedSpan.getValue(voice)) to lock
    }

    /**
     * Every TUNE step of both voices (25 and 20), at GRIP 0 / default / 1 and BOW 0 / default / 1 (nine cells a step, 405 in
     * all, no vibrato, BODY 0) sounds within 5 cents of [Arco.frequencyFor]. The pitch is read the way the record asks: a
     * windowed FFT ([FineTuning.measuredHz], zero-padded to 65536 points) on the finished 44.1 kHz render, the raw wave
     * taken through [Arco.finish]. The autocorrelation of the raw wave ([BowMeter.pitch]) is the cross-check and is
     * printed beside it.
     *
     * R1c saw the FFT within 3.48 cents at CELLO (C4 at GRIP 0) and 3.10 cents at ERHU, autocorrelation within 3.40 and
     * 3.14, and the two measures within 0.46 cents of each other at CELLO and 0.14 at ERHU (R1b's engine read 3.40 and 0.47 at CELLO, and only the BOW 1 cells
     * differ between the two engines): so a 5 cent bar on each and a
     * 1 cent bar on their disagreement. BOW does not move the settled pitch (the three BOW columns in a row agree to within
     * a tenth of a cent): the stroke is over long before the window opens. The FFT bin on the finished
     * render is 0.67 Hz, 1 percent of C2, so its parabolic peak is good to a fraction of a cent, which is why the 1 cent
     * agreement holds even at the lowest note.
     */
    @Test
    fun `every TUNE step is in tune across GRIP and BOW`() {
        val started = System.nanoTime()
        val grips = listOf(0f, Arco.DEFAULT_GRIP, 1f)
        val bows = listOf(0f, Arco.DEFAULT_BOW, 1f)
        for (voice in ArcoVoice.entries) {
            val steps = ArcoMeasure.steps(voice).toList()
            val rows = steps.pmap { step -> grips.flatMap { g -> bows.map { b -> tunedRead(voice, step, g, b) } } }
            println("ARCO cents by FFT on the finished render ($voice), BOW 0 / 0.5 / 1 at GRIP 0, then at GRIP 0.6, then at GRIP 1; ac is the autocorrelation's worst:")
            var worstFft = 0.0
            var worstAc = 0.0
            var worstDiff = 0.0
            var worstGrip = 0.0
            var worstGripAt = ""
            val failures = ArrayList<String>()
            for ((i, step) in steps.withIndex()) {
                val row = rows[i]
                val fft = row.map { it.first.fftCents }
                val ac = row.map { it.first.acCents }
                val grip = fft[7] - fft[1]
                println("ARCO   ${name(voice, step).padEnd(3)} ${step.toString().padStart(2)}: fft " + fft.joinToString(" ") { f(it, 1).padStart(5) } + " | ac worst ${f(ac.maxOf { abs(it) }, 1)} | GRIP 1 minus GRIP 0 ${f(grip, 1)}")
                worstFft = max(worstFft, fft.maxOf { abs(it) })
                worstAc = max(worstAc, ac.maxOf { abs(it) })
                worstDiff = max(worstDiff, row.maxOf { abs(it.first.fftCents - it.first.acCents) })
                if (abs(grip) > worstGrip) {
                    worstGrip = abs(grip)
                    worstGripAt = "$voice ${name(voice, step)}"
                }
                for ((k, r) in row.withIndex()) {
                    val label = "$voice ${name(voice, step)} GRIP ${grips[k / 3]} BOW ${bows[k % 3]}"
                    if (abs(r.first.fftCents) > 5.0) failures.add("$label: ${f(r.first.fftCents, 1)} c by FFT")
                    if (abs(r.first.acCents) > 5.0) failures.add("$label: ${f(r.first.acCents, 1)} c by autocorrelation")
                    if (abs(r.first.fftCents - r.first.acCents) > 1.0) failures.add("$label: the FFT and the autocorrelation disagree by ${f(r.first.fftCents - r.first.acCents, 2)} c")
                    if (r.second < 0 || tunedFrom.getValue(voice) < r.second + 0.3) failures.add("$label: the window starts at ${tunedFrom.getValue(voice)} s but the lock is at ${f(r.second)} s")
                    if (r.first.acPeak <= 0.9) failures.add("$label: autocorrelation peak ${f(r.first.acPeak)}")
                }
            }
            println("ARCO cents $voice: longest lock ${f(rows.maxOf { r -> r.maxOf { it.second } })} s; worst FFT ${f(worstFft)}, worst autocorrelation ${f(worstAc)}, worst |FFT - autocorrelation| ${f(worstDiff)}; GRIP travel worst ${f(worstGrip)} at $worstGripAt")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
        println("ARCO tuning table took ${f((System.nanoTime() - started) / 1e9, 1)} s")
    }

    /**
     * GRIP is not a pitch knob: the note at GRIP 0 and at GRIP 1 is within 5 cents, with the corner moving under it (the
     * share of the bridge filter's delay that tunes the string is a share of the corner's own delay, so a fixed count would
     * not hold). Read at three TUNEs a voice, the worst two measured among them.
     *
     * R1b saw the travel (GRIP 1 minus GRIP 0, by FFT; autocorrelation within 0.11 cents of it) at CELLO C2 -0.08, C3 0.22
     * and C4 4.13 cents, and at ERHU D4 -0.55, F5 4.50 and A5 2.80 cents; the bar is the record's 5 cents, 0.5 cents above the
     * worst. Why ERHU's pressure floor is 0.94 and not 0.85 is this test's negative control: a high string's pitch rises
     * with pressure, so with the floor at 0.85 the same knob would have moved ERHU C#5 by 5.92, F5 by 6.60 (against 4.50 with
     * the shipped floor) and F#5 by 6.35 cents, measured here by handing [Arco.bow] a pressure of 0.85 at GRIP 0, and all
     * three are over the bar.
     */
    @Test
    fun `GRIP is not a pitch knob, and a lower ERHU pressure floor would have made it one`() {
        val cases = listOf(ArcoVoice.CELLO to listOf(0, 12, 24), ArcoVoice.ERHU to listOf(0, 15, 19))
        val failures = ArrayList<String>()
        for ((voice, steps) in cases) {
            val travels = steps.pmap { step ->
                val lo = tunedRead(voice, step, 0f, Arco.DEFAULT_BOW).first
                val hi = tunedRead(voice, step, 1f, Arco.DEFAULT_BOW).first
                Triple(step, hi.fftCents - lo.fftCents, hi.acCents - lo.acCents)
            }
            for ((step, fft, ac) in travels) {
                println("ARCO GRIP $voice ${name(voice, step)}: GRIP 1 minus GRIP 0 = ${f(fft)} c by FFT, ${f(ac)} c by autocorrelation")
                if (abs(fft) > 5.0) failures.add("$voice ${name(voice, step)}: GRIP moves the note by ${f(fft)} cents")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        val erhu = ArcoVoice.ERHU
        val control = listOf(11, 15, 16).pmap { step ->
            val lo = tunedRead(erhu, step, 0f, Arco.DEFAULT_BOW, pressure = 0.85f).first
            val hi = tunedRead(erhu, step, 1f, Arco.DEFAULT_BOW).first
            step to hi.fftCents - lo.fftCents
        }
        for ((step, travel) in control) println("ARCO GRIP control ERHU ${name(erhu, step)} with the pressure floor at 0.85: travel ${f(travel)} c")
        for ((step, travel) in control) assertTrue(travel > 5.0, "ERHU ${name(erhu, step)} with a 0.85 floor moves ${f(travel)} cents, which the 5 cent bar should refuse")
    }

    // ---------------------------------------------------------------- 4. lifted: silence, and the engine lifts

    /**
     * A bow lifted from the start is exact silence: the core's wave and its bow-point tap are all zeros, and so is the
     * finished render of it at BODY 0 and BODY 1 (nothing for [Dsp.levelTo] to lift: it returns a silent buffer untouched).
     * Checked at the root, middle and top of both voices. Silence has to be exact because the whole fuzz rests on it: a
     * render that is "almost" silent would be levelled to full scale and pass for a note.
     */
    @Test
    fun `a bow lifted from the start is exact silence, in the core and in the finished render`() {
        for (voice in ArcoVoice.entries) {
            for (step in spread(voice)) {
                for (body in listOf(0f, 1f)) {
                    val c = ArcoMeasure.core(voice, step, body = body, gateSeconds = 0.6f, lifted = true, vibrato = true)
                    assertTrue(c.out.all { it == 0f } && c.bowPoint.all { it == 0f }, "$voice ${name(voice, step)}: a bow that never touched the string made sound")
                    val done = ArcoMeasure.finished(c.out, voice, body)
                    assertTrue(done.isNotEmpty() && done.all { it == 0f }, "$voice ${name(voice, step)} BODY $body: the finished render of silence is not silence")
                }
            }
        }
    }

    /**
     * GRIP never reaches pressure 0, and pressure 0 is not bow-up. The reflection table is a plateau at slope 5, which is
     * what a pressure of 0 maps to ([Strings.Bow]'s KDoc), so a bow at pressure 0 still drives the string: only [Strings.Bow.lift]
     * lifts it. R1b saw a raw peak of 0.284 from CELLO C3 at pressure 0 (a quiet, scratchy note, but not silence); the
     * bar is "louder than 0.05". [Arco.pressureFor] is above 0 at every step of both voices at GRIP 0, 0.5 and 1 (the lowest is
     * [Arco.PRESSURE_LOW]), so the knob can never fall into the one place where the bow is not on the string.
     */
    @Test
    fun `GRIP never reaches pressure 0, and pressure 0 is still a bow`() {
        for (voice in ArcoVoice.entries) {
            for (step in ArcoMeasure.steps(voice)) {
                for (grip in listOf(0f, 0.5f, 1f)) {
                    assertTrue(Arco.pressureFor(voice, step, grip) >= Arco.PRESSURE_LOW, "$voice ${name(voice, step)} GRIP $grip is below the pressure floor")
                }
                assertTrue(Arco.gripFor(voice, step).pressureLow > 0f, "$voice ${name(voice, step)}: GRIP's low end is pressure 0")
            }
        }
        val rubbing = ArcoMeasure.core(ArcoVoice.CELLO, 12, pressure = 0f, gateSeconds = 0.6f)
        val peak = BowMeter.maxAbs(rubbing.out)
        println("ARCO pressure 0 is still a bow: raw peak ${f(peak.toDouble(), 3)}")
        assertTrue(peak > 0.05f, "a bow at pressure 0 made no sound (peak $peak): it would be a lifted bow")
    }

    /**
     * The engine's no-vibrato, no-bite note played on a bare [Strings.Bow] by hand, with the engine's own corner, share,
     * pressure, attack, release ramp and, when [stop] is true, its stop (the constant extra bridge loss that [Arco.stopScale]
     * works out for a tail of [stopN] samples), so the one thing that differs between a run with [lift] and a run without is
     * whether the bow is lifted at the end of the note. [stop] false leaves the string's own loss alone.
     */
    private fun byHand(voice: ArcoVoice, step: Int, grip: Float, holdSeconds: Float, stopN: Int, lift: Boolean, stop: Boolean): FloatArray {
        val hz = ArcoMeasure.hzOf(voice, step)
        val bow = Strings.Bow(hz, Arco.BETA, Arco.cornerFor(voice, step, grip), Arco.shareFor(voice), rate)
        val slope = 5f - 4f * Arco.pressureFor(voice, step, grip)
        val holdN = (holdSeconds * rate).toInt()
        val rampN = (Arco.RELEASE_RAMP_SECONDS * rate).toInt()
        val attackN = (min(Arco.attackSeconds(0.5f), Arco.ATTACK_HOLD_FRACTION * holdSeconds) * rate).toInt()
        val liftAt = holdN + rampN
        val out = FloatArray(liftAt + stopN)
        for (i in out.indices) {
            val up = if (i < attackN) i.toFloat() / attackN else 1f
            val release = when {
                i < holdN -> 1f
                i < liftAt -> 1f - (i - holdN).toFloat() / rampN
                else -> 0f
            }
            if (lift && i == liftAt) bow.lift()
            if (stop && i == liftAt) bow.gain(Arco.stopScale(hz, Arco.cornerFor(voice, step, grip), stopN.toFloat() / rate, rate))
            out[i] = bow.next(Arco.V_SUSTAIN * up * release, slope)
        }
        return out
    }

    /**
     * The engine lifts the bow at the end of the note, so its tail is the free string's and not a resting string's. Played
     * for 2 s at BOW 0.5 (no bite) at CELLO C3, C2 and ERHU G4, the engine's tail is read at 95 percent of its stop, in dB
     * against the level of the window that ends at the lift, beside the same note played by hand on a bare [Strings.Bow] that
     * rests on the string instead of lifting (same attack, release ramp and stop loss, so the lift is the one difference).
     * At C3 and G4 the free ring is under half of the 2 s hold, so the stop's release is the free ring itself and
     * [Arco.stopScale] adds no loss to speak of; at C2, whose free ring is 2.01 s, the stop adds loss.
     *
     * R1b saw the engine's tail at -62.3, -59.2 and -45.2 dB (C3, C2, G4) against -55.6, -44.8 and -23.3 dB for the resting bow,
     * and the engine's output bit for bit the lifted bow's by hand. The bars are the engine at or under -40 dB, and the resting
     * bow at least 5 dB above the engine. The room is thin in two cells: the engine's -45.2 dB at G4 is 5.2 dB under the first
     * bar, and the resting bow is 6.7 dB above the engine at C3 (14.4 dB at C2, 21.9 dB at G4), 1.7 dB over the second. The
     * levels are the AC level of a window of whole periods (the window's own mean taken out): a string's net displacement from
     * the onset rings on at the bridge's own 0.95 a period, -0.44 dB a period whatever the pitch, and is not the note.
     *
     * Why a lifted bow matters: left resting, the string is stopped at the bow and a stab's tail does not end where the
     * stop says it does. (StringsBowTest's 3.2 s resting ring at C3 is that net displacement's: with the mean taken out R1b
     * measured the resting string at 1.09 s against the lifted one's 0.89 s, bare, at the engine's corner.)
     */
    @Test
    fun `the engine lifts the bow, so its tail is the free string's and not a resting one's`() {
        for ((voice, step) in listOf(ArcoVoice.CELLO to 12, ArcoVoice.CELLO to 0, ArcoVoice.ERHU to 5)) {
            val hz = ArcoMeasure.hzOf(voice, step)
            val hold = 2.0f
            val c = ArcoMeasure.core(voice, step, bow = 0.5f, gateSeconds = hold)
            val w = ArcoMeasure.wholePeriods(hz)
            val resting = byHand(voice, step, Arco.DEFAULT_GRIP, hold, c.stopN, lift = false, stop = true)
            val liftedByHand = byHand(voice, step, Arco.DEFAULT_GRIP, hold, c.stopN, lift = true, stop = true)
            fun drop(x: FloatArray, at: Double): Double {
                val e = ArcoMeasure.Energy(x)
                return e.acDb(c.liftN + (at * c.stopN).toInt(), w) - e.acDb(c.liftN - w, w)
            }
            val engine = drop(c.out, 0.95)
            val rest = drop(resting, 0.95)
            println("ARCO lift $voice ${name(voice, step)}: at 95% of the stop the engine is at ${f(engine, 1)} dB, the lifted bow by hand ${f(drop(liftedByHand, 0.95), 1)} dB, a resting bow ${f(rest, 1)} dB; the engine equals the lifted bow bit for bit: ${c.out.contentEquals(liftedByHand)}")
            assertTrue(c.out.contentEquals(liftedByHand), "$voice ${name(voice, step)}: the engine's wave is not the lifted bow's played by hand from the same pressure, corner and stop")
            assertTrue(engine <= -40.0, "$voice ${name(voice, step)}: the engine's tail is still at $engine dB at 95 percent of its stop")
            assertTrue(rest - engine >= 5.0, "$voice ${name(voice, step)}: a resting bow's tail ($rest dB) is within 5 dB of the engine's ($engine dB), so this test cannot tell a lifted bow from a resting one")
        }
    }

    // ---------------------------------------------------------------- 5. the release

    /**
     * Seconds after [liftN] until a window of whole periods falls to a thousandth (-60 dB) of the one before the lift, the
     * windows' own mean taken out and stepped a millisecond; a tail that is still sounding at the end counts as the whole
     * tail plus a second.
     */
    private fun minus60(out: FloatArray, liftN: Int, hz: Float): Double {
        val e = ArcoMeasure.Energy(out)
        val w = ArcoMeasure.wholePeriods(hz)
        val ref = e.ac(liftN - (Arco.RELEASE_RAMP_SECONDS * rate).toInt() - w, w)
        var s = liftN
        val step = rate / 1000
        while (s < out.size) {
            if (e.ac(s, w) <= 0.001 * ref) return (s - liftN).toDouble() / rate
            s += step
        }
        return (out.size - liftN).toDouble() / rate + 1.0
    }

    /**
     * How slowly a tail falls, as the smallest ratio (over windows of whole periods, back to back from the lift, from where the
     * tail is 10 dB under the note's sustain until it is 30 dB under it: the lift itself lets the string's stored energy out and
     * the first windows can rise, and below 30 dB a window's own mean no longer takes out the net displacement that drains at the bridge's
     * 0.95 a period, -0.44 dB a period at every pitch, which at A5 is slower than the formula's -0.80 and reads as a ratio of 0.56) of its drop from one window to the next to the free ring's drop over the
     * same time ([Arco.freeRingSeconds]'s slope). A tail that falls at least as fast as the free string has every ratio
     * at 1 or more; one that rings on slower has a ratio under 1.
     */
    private fun slowestRatio(out: FloatArray, liftN: Int, hz: Float, freeRingSeconds: Float): Double {
        val e = ArcoMeasure.Energy(out)
        val w = ArcoMeasure.wholePeriods(hz)
        val ref = e.acDb(liftN - (Arco.RELEASE_RAMP_SECONDS * rate).toInt() - w, w)
        val formulaDrop = -60.0 / freeRingSeconds * w / rate
        var worst = Double.POSITIVE_INFINITY
        var k = 0
        while (liftN + (k + 2) * w <= out.size) {
            val a = e.acDb(liftN + k * w, w)
            val b = e.acDb(liftN + (k + 1) * w, w)
            if (a < ref - 10.0 && a > ref - 30.0) worst = min(worst, (b - a) / formulaDrop)
            k++
        }
        return worst
    }

    /**
     * A lifted bow rings down at [Arco.freeRingSeconds]'s slope: the string's own loss per period at the fundamental,
     * `20 log10(0.95 |H(f0)|)`, with H the bridge's one-pole at the engine's own corner. Measured on a bare [Strings.Bow]
     * lifted after a note (the first 5 percent of the formula's ring is skipped, then the fundamental's Hann-windowed
     * level is read 15 percent and 45 percent of the way through it), at six pitches (C2, C3, C4, D4, C5, A5) and at both ends of GRIP,
     * so the corner moves from 1000 Hz to the full 3023.6 Hz.
     *
     * R1b saw the slope within 1.2 percent of the formula in all 12 cells (CELLO C2 -0.464 against -0.464 dB a period, C4 at
     * GRIP 0 -0.724 against -0.733, ERHU A5 at GRIP 0 -0.840 against -0.846); the bar is 5 percent, half of StringsBowTest's
     * 10. The measure reads the fundamental on purpose: an RMS of the whole wave reads the string's net displacement from the
     * onset instead, which drains at the bridge's 0.95 a period (-0.44 dB a period at every pitch) and so reads 39 percent
     * too shallow at C4 (-0.445) and 48 percent at A5 (-0.442) at the dark corners (R1b measured it both ways). The negative
     * control is the formula at the wrong corner: the full 3023.6 Hz corner's slope at C4 and GRIP 0 is -0.478, 35 percent
     * off the -0.733 that the engine's own corner gives and the bow rings at.
     */
    @Test
    fun `a lifted bow rings down at the formula's slope`() {
        val failures = ArrayList<String>()
        for ((voice, step) in listOf(ArcoVoice.CELLO to 0, ArcoVoice.CELLO to 12, ArcoVoice.CELLO to 24, ArcoVoice.ERHU to 0, ArcoVoice.ERHU to 10, ArcoVoice.ERHU to 19)) {
            for (grip in listOf(0f, 1f)) {
                val hz = ArcoMeasure.hzOf(voice, step)
                val corner = Arco.cornerFor(voice, step, grip)
                val t60 = Arco.freeRingSeconds(hz, corner)
                val lift = 1.2f
                val run = BowMeter.play(hz, Arco.BETA, Arco.pressureFor(voice, step, grip), 0.5f, lift + 0.05f + t60 * 1.2f + 0.2f, releaseAt = lift, bridgeHz = corner, share = Arco.shareFor(voice))
                val w = ArcoMeasure.wholePeriods(hz, 0.02, 4)
                val start = ((lift + 0.05) * rate).toInt()
                val a = start + (0.15 * t60 * rate).toInt()
                val b = a + (0.30 * t60 * rate).toInt()
                val slope = (ArcoMeasure.fundamentalDb(run.out, b, w, hz) - ArcoMeasure.fundamentalDb(run.out, a, w, hz)) / ((b - a).toDouble() / rate * hz)
                val formula = -60.0 / (t60 * hz)
                val off = (slope / formula - 1) * 100
                println("ARCO ring $voice ${name(voice, step)} GRIP $grip corner ${f(corner.toDouble(), 0)} Hz: slope ${f(slope, 3)} dB/period, formula ${f(formula, 3)} (${f(off, 1)} %), -60 dB by the formula in ${f(t60.toDouble())} s")
                if (abs(off) > 5.0) failures.add("$voice ${name(voice, step)} GRIP $grip: the ring is ${f(off, 1)} percent off the formula")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        val c4 = ArcoMeasure.hzOf(ArcoVoice.CELLO, 24)
        val wrong = -60.0 / (Arco.freeRingSeconds(c4, Strings.Bow.BRIDGE_HZ) * c4)
        val right = -60.0 / (Arco.freeRingSeconds(c4, Arco.cornerFor(ArcoVoice.CELLO, 24, 0f)) * c4)
        println("ARCO ring control: C4 at GRIP 0 by the full corner's formula ${f(wrong, 3)}, by the engine's ${f(right, 3)} dB/period")
        assertTrue(abs(wrong / right - 1) > 0.2, "the formula at the wrong corner should be far from the right one: $wrong against $right")
    }

    /**
     * The stopped tail reaches -60 dB within its release plus 50 ms, and never later than a lifted string with no stop would, at HOLD 0,
     * 0.4 and 0.95 and three TUNEs a voice (root, middle, top), default GRIP and BOW, vibrato as the engine plays it, BODY 0. The level is
     * the AC level of a window of whole periods against the note's own level just before its bow's velocity ramps down (the level
     * after the ramp is lower: the resting bow has taken the string's energy, and the lift lets some of it back out).
     *
     * The stop is a constant extra loss at the bridge ([Arco.stopScale]) that makes the string fall 60 dB in exactly the release; the
     * free ring it replaces is [Arco.freeRingSeconds] long, so the stop only ever adds loss and the tail is never slower than
     * the string's own. Where the free ring is no longer than the release the stop adds nothing: 9 of the 18 cells here run with the
     * stop disengaged (CELLO C3 and C4 at HOLD 0.95, ERHU D4 and C5 at HOLD 0.4 and 0.95, ERHU A5 at all three), and in those the
     * engine's time is the string with no stop's to the millisecond; the other 9 are where the stop acts.
     *
     * R1b saw -60 dB reached in 0.63 to 0.89 of the release over the 18 cells: CELLO C4 at HOLD 0.95 (274 of 438 ms) the earliest, and
     * CELLO C2 at HOLD 0 (134 of 150 ms) the latest, 16 ms before the end of the release and the one cell at the edge (the free ring
     * being 2.01 s there), with at least 22 ms to spare in every other cell. It is under the whole release because the level it is read
     * against is the note's before its velocity ramps down, which is higher than the level the stop's own 60 dB runs from at the lift.
     * The bars are: reached by release plus 50 ms, and no later than the same note's lifted string with no stop at all (5 ms of room).
     * (Its first version read each window's slope against the formula's and asked for 0.9 of it;
     * on a window of whole periods that reads the net displacement's own -0.44 dB a period once the note's harmonics are gone, which is
     * slower than the formula above about 500 Hz, and ERHU A5 read 0.56. The ring-down test above reads the fundamental and is the
     * one that holds the formula to the string.)
     *
     * The negative control: a lifted string with no stop at CELLO C2 takes 1.76 s to reach -60 dB against the stop's 134 ms, so the
     * first bar can fail, and the engine's tail is compared with that string's in every cell.
     */
    @Test
    fun `the stopped tail reaches minus 60 dB in its release and is never later than a string with no stop`() {
        val failures = ArrayList<String>()
        var latest = Double.NEGATIVE_INFINITY
        var slowest = Double.POSITIVE_INFINITY
        for (voice in ArcoVoice.entries) {
            for (step in spread(voice)) {
                for (hold in listOf(0f, 0.4f, 0.95f)) {
                    val c = ArcoMeasure.core(voice, step, hold = hold, vibrato = true)
                    val release = c.stopN.toDouble() / rate
                    val t = minus60(c.out, c.liftN, c.hz)
                    val corner = Arco.cornerFor(voice, step, Arco.DEFAULT_GRIP)
                    val free = Arco.freeRingSeconds(c.hz, corner)
                    val ratio = slowestRatio(c.out, c.liftN, c.hz, free)
                    val bare = byHand(voice, step, Arco.DEFAULT_GRIP, c.holdN.toFloat() / rate, 6 * rate, lift = true, stop = false)
                    val tBare = minus60(bare, c.liftN, c.hz)
                    latest = max(latest, t - release)
                    slowest = min(slowest, ratio)
                    println("ARCO stop $voice ${name(voice, step)} HOLD $hold: release ${f(release * 1000, 0)} ms, -60 dB after ${f(t * 1000, 0)} ms (slack ${f((release - t) * 1000, 0)} ms), a string with no stop ${f(tBare * 1000, 0)} ms, windows fall at ${f(ratio)} of the formula's slope (printed, not held), free ring ${f(free.toDouble())} s")
                    if (t > release + 0.050) failures.add("$voice ${name(voice, step)} HOLD $hold: -60 dB after ${f(t * 1000, 0)} ms, release ${f(release * 1000, 0)} ms")
                    if (t > tBare + 0.005) failures.add("$voice ${name(voice, step)} HOLD $hold: the stop reaches -60 dB after ${f(t * 1000, 0)} ms, a string with no stop after ${f(tBare * 1000, 0)} ms")
                }
            }
        }
        println("ARCO stop: -60 dB at worst ${f(latest * 1000, 0)} ms after the release's end, slowest window ${f(slowest)} of the formula")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        val c = ArcoMeasure.core(ArcoVoice.CELLO, 0, hold = 0f)
        val bare = byHand(ArcoVoice.CELLO, 0, Arco.DEFAULT_GRIP, 0.3f, 4 * rate, lift = true, stop = false)
        val noStop = minus60(bare, c.liftN, c.hz)
        println("ARCO stop control: engine ${f(minus60(c.out, c.liftN, c.hz) * 1000, 0)} ms, a lifted string with no stop ${f(noStop * 1000, 0)} ms")
        assertTrue(noStop > c.stopN.toDouble() / rate + 0.050, "a string with no stop reached -60 dB in $noStop s, so the stop's bar cannot tell it from the engine")
    }

    // ---------------------------------------------------------------- 6. the series is a sawtooth

    /**
     * What makes a wave a bowed string's: the second harmonic 6 dB under the first, a series falling about 6 dB an octave, a
     * flyback several times as steep as the ramp, and a string that moves slowly for most of the period. The bands are
     * R1b's measurements with room, not the record's (see [the series is a sawtooth, at defaults and at GRIP 1]).
     */
    private fun sawProblems(h: DoubleArray, riseToFall: Double, slow: Double): List<String> = buildList {
        if (h[1] !in -6.5..-5.3) add("h2 is ${f(h[1], 1)} dB, not about -6")
        val slope = ArcoMeasure.octaveSlope(h)
        if (slope !in -10.0..-4.5) add("the series falls ${f(slope, 2)} dB an octave")
        if (riseToFall >= 0.35) add("the fastest rise is ${f(riseToFall)} of the fastest fall")
        if (slow <= 0.7) add("the wave is slow for only ${f(slow)} of the period")
    }

    /**
     * The series is a sawtooth, at defaults and at GRIP 1, at three TUNEs a voice, on the core (the last 0.4 s of a bow-on
     * of 2.5 s at CELLO, 1.5 s at ERHU, no vibrato): the second harmonic within a band of -6 dB against the first, the
     * least-squares slope of harmonics 1 to 8 in dB an octave ([ArcoMeasure.octaveSlope], the line through level against
     * log2 k), the fastest rise over the fastest fall ([BowMeter.sawtooth]) and the share of the period the string moves
     * at under a fifth of its steepest step.
     *
     * R1b saw, over the 12 cells (CELLO C2, C3, C4, ERHU D4, A#4, A5, each at GRIP 0.6 and 1): h2 between -5.8 and -5.9 dB,
     * so the band is -6.5 to -5.3; the slope between -4.93 (CELLO C3 at GRIP 1) and -9.51 (ERHU A5 at GRIP 0.6), so the band
     * is -10.0 to -4.5, wider than the record's -5 to -8; rise over fall between 0.19 and 0.29, so the bar is under 0.35;
     * and slow for between 0.77 (ERHU A5) and 0.97 of the period, so the bar is over 0.7, not the record's 0.9, because an
     * 880 Hz period is 200 samples and a fifth of the steepest step is a larger share of a coarser wave. Why the slope
     * leaves the record's band: the bow sits at 0.133 of the string, so harmonic 7.5 is a node, harmonic 7 stands above a
     * sawtooth's line (-10 to -17 dB against -16.9, at every note but A5) and harmonic 8 far under it (-18 to -38 dB against -18.1), which bends a
     * line fitted through all eight (the slope over harmonics 1 to 6 alone is -5.3 to -5.5 at every note but A5); and at
     * ERHU's top the bridge's corner is under the fourth harmonic, so everything above falls an octave faster (-7.5 to -8.1).
     *
     * The negative controls are signals the same judgement must refuse: a sine at 130.81 Hz (h2 at -118 dB, rise over fall
     * 1.0, slow for 0.13) and CELLO C2 at the full corner at pressure 0.5 and 0.7, the scratch of several slips a period (R1b
     * saw h2 at -3.5 and -4.2 dB, slope -2.1 and -3.3 dB an octave, rise over fall 1.76 and 1.48).
     */
    @Test
    fun `the series is a sawtooth, at defaults and at GRIP 1`() {
        val failures = ArrayList<String>()
        val cases = listOf(ArcoVoice.CELLO to listOf(0, 12, 24), ArcoVoice.ERHU to listOf(0, 8, 19))
        for ((voice, steps) in cases) {
            for (step in steps) {
                for (grip in listOf(Arco.DEFAULT_GRIP, 1f)) {
                    val bowOn = if (voice == ArcoVoice.CELLO) 2.5f else 1.5f
                    val c = ArcoMeasure.core(voice, step, grip = grip, gateSeconds = bowOn)
                    val len = (0.4 * rate).toInt()
                    val from = c.holdN - len
                    val hz = BowMeter.pitch(c.out, from, len, c.hz).first
                    val h = BowMeter.harmonics(c.out, from, len, hz, 8)
                    val (riseToFall, slow) = BowMeter.sawtooth(c.out, c.holdN, rate / hz)
                    println("ARCO saw $voice ${name(voice, step)} GRIP $grip: h2..h8 ${h.drop(1).joinToString("/") { f(it, 1) }} dB, slope h1..h8 ${f(ArcoMeasure.octaveSlope(h))} (h1..h6 ${f(ArcoMeasure.octaveSlope(h.copyOf(6)))}) dB/oct, rise/fall ${f(riseToFall, 3)}, slow ${f(slow, 3)}")
                    val problems = sawProblems(h, riseToFall, slow)
                    if (problems.isNotEmpty()) failures.add("$voice ${name(voice, step)} GRIP $grip: $problems")
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        val sine = FloatArray(rate) { kotlin.math.sin(2.0 * Math.PI * 130.81 * it / rate).toFloat() }
        val sineH = BowMeter.harmonics(sine, 0, rate / 2, 130.81)
        val (sineRise, sineSlow) = BowMeter.sawtooth(sine, rate / 2, rate / 130.81)
        assertTrue(sawProblems(sineH, sineRise, sineSlow).size >= 3, "a sine was judged a sawtooth")
        for (p in listOf(0.5f, 0.7f)) {
            val c = ArcoMeasure.core(ArcoVoice.CELLO, 0, pressure = p, cornerHz = Strings.Bow.BRIDGE_HZ, gateSeconds = 2.5f)
            val len = (0.4 * rate).toInt()
            val hz = BowMeter.pitch(c.out, c.holdN - len, len, c.hz).first
            val h = BowMeter.harmonics(c.out, c.holdN - len, len, hz, 8)
            val (riseToFall, slow) = BowMeter.sawtooth(c.out, c.holdN, rate / hz)
            val problems = sawProblems(h, riseToFall, slow)
            println("ARCO saw control CELLO C2 pressure $p at the full corner: h2 ${f(h[1], 1)} dB, slope ${f(ArcoMeasure.octaveSlope(h))}, rise/fall ${f(riseToFall, 3)}, slow ${f(slow, 3)}: $problems")
            assertTrue(problems.isNotEmpty(), "the three-slip scratch at pressure $p was judged a sawtooth")
        }
        assertEquals(-6.0206, ArcoMeasure.octaveSlope(DoubleArray(8) { -20 * log10(it + 1.0) }), 0.001)
    }

    // ---------------------------------------------------------------- 7. bounded, pinned, DC-free

    private class Cell(
        val label: String, val finite: Boolean, val rawPeak: Float, val rawMean: Double,
        val finPeak: Float, val finMean: Double,
    )

    /** The largest raw |mean| a note may have, per voice: R1b saw 0.0714 (CELLO) and 0.0130 (ERHU), both on the 0.3 s note at BOW 0 and GRIP 1. */
    private val rawMeanBar = mapOf(ArcoVoice.CELLO to 0.09, ArcoVoice.ERHU to 0.02)

    /** The finished peak must stay under this, clear of the 0.99 where [Dsp.levelTo] starts turning a peak down: a limited note sits at exactly 0.99, and fails. */
    private val finishedPeakBar = 0.95f

    /**
     * Bounded, pinned and DC-free over the grid: TUNE, BOW, GRIP and BODY each at 0, 0.5 and 1, HOLD at 0 and 0.5 (HOLD 1 is a
     * LOOP and has its own seam test), vibrato as the engine plays it: 162 renders a voice. On every one: nothing
     * non-finite (the raw wave, its tap or the finished render), the raw peak under [Strings.Bow.RAW_PEAK_CEILING], the
     * finished peak under [finishedPeakBar] and the finished mean under 0.05. The raw mean is held to a bound per voice and
     * explained by [the raw mean is the onset's displacement and drains away].
     *
     * R1c saw: no non-finite sample; the raw peak at most 0.737 at CELLO (C3, BOW 0.5, GRIP 1, BODY 0, HOLD 0.5: no bite there; the grid's cells at BOW 1 are lower) and 0.623 at ERHU (D4, BOW 0, GRIP 1, HOLD 0) against the ceiling 1.25, which has room because the overshoot's
     * worst corner (BOW 1 at the window's top) is inside the grid; the finished peak at most 0.840 (CELLO C2, BOW 0, GRIP 0, HOLD 0) and 0.761 (ERHU A5, BOW 0,
     * GRIP 0.5, BODY 1, HOLD 0), 0.11 under the 0.95 bar (R1c's louder box at BODY 1, 1.75 times the string, reads 0.761 in that ERHU cell, where R1b's box, only as loud as the string, read 0.763; the CELLO peak is at BODY 0). [Dsp.levelTo] sets the level by loudness and turns a peak down only
     * when it passes 0.99, so a note the limiter had acted on would sit at exactly 0.99 and fail the bar: that is what makes "the
     * limiter stays idle" a claim this test can fail, where a bar at 0.99 itself never could. The finished mean is at most 0.0042
     * and 0.0002 against 0.05. The raw mean reached 0.0714 at CELLO (C2, BOW 0, GRIP 1, HOLD 0) and 0.0130 at ERHU (D4, BOW 0,
     * GRIP 1, HOLD 0); the bars are 0.09 and 0.02, which leaves 0.0186 of room at CELLO and 0.0070 at ERHU.
     */
    @Test
    fun `bounded, finite, pinned and DC-free across the grid`() {
        val started = System.nanoTime()
        val levels = listOf(0f, 0.5f, 1f)
        for (voice in ArcoVoice.entries) {
            val cases = ArrayList<List<Float>>()
            for (t in levels) for (b in levels) for (g in levels) for (body in levels) for (h in listOf(0f, 0.5f)) cases.add(listOf(t, b, g, body, h))
            val cells = cases.pmap { (t, b, g, body, h) ->
                val step = Arco.semitoneFor(voice, t)
                val c = ArcoMeasure.core(voice, step, bow = b, grip = g, body = body, hold = h, vibrato = true)
                val done = ArcoMeasure.finished(c.out, voice, body)
                Cell(
                    "$voice TUNE $t(${name(voice, step)}) BOW $b GRIP $g BODY $body HOLD $h",
                    c.out.all { it.isFinite() } && c.bowPoint.all { it.isFinite() } && done.all { it.isFinite() },
                    BowMeter.maxAbs(c.out), BowMeter.mean(c.out, 0, c.out.size),
                    BowMeter.maxAbs(done), BowMeter.mean(done, 0, done.size),
                )
            }
            println("ARCO grid $voice: ${cells.size} cells")
            val byPeak = cells.maxByOrNull { it.rawPeak }!!
            val byMean = cells.maxByOrNull { abs(it.rawMean) }!!
            val byFinPeak = cells.maxByOrNull { it.finPeak }!!
            val byFinMean = cells.maxByOrNull { abs(it.finMean) }!!
            println("ARCO   raw peak ${f(byPeak.rawPeak.toDouble(), 3)} at ${byPeak.label}")
            println("ARCO   raw mean ${f(byMean.rawMean, 4)} at ${byMean.label}")
            println("ARCO   finished peak ${f(byFinPeak.finPeak.toDouble(), 3)} at ${byFinPeak.label}; finished mean ${f(byFinMean.finMean, 5)} at ${byFinMean.label}")
            val bad = cells.filter {
                !it.finite || it.rawPeak >= Strings.Bow.RAW_PEAK_CEILING || it.finPeak >= finishedPeakBar ||
                    abs(it.finMean) >= 0.05 || abs(it.rawMean) >= rawMeanBar.getValue(voice)
            }
            assertEquals(162, cells.size, "the grid is 3 x 3 x 3 x 3 x 2")
            assertTrue(bad.isEmpty(), bad.joinToString("\n") { it.label })
        }
        println("ARCO grid took ${f((System.nanoTime() - started) / 1e9, 1)} s")
    }

    /**
     * The raw mean is the onset's displacement, and it drains away. A string starts at rest and the bow's first sticks drag it
     * one way before the slips balance it, and a loop whose two ends both invert has no DC blocker, so that net
     * displacement drains only at the bridge's loss of 0.95 a period (-0.44 dB a period: 2 s at C2). A 0.3 s note at C2 is 20
     * periods long and is all onset, which is why it is the cell with the largest raw mean in the grid; the finished render
     * takes the mean out (it is the 0.0042 above) and the high-pass takes the rest.
     *
     * Read over a 3 s note at BOW 0 and GRIP 1 at each root, the mean of each half second: CELLO 0.0919, -0.0058, -0.0104,
     * -0.0015, 0.0024, -0.0020 and ERHU 0.0185, -0.0004, 0.0002, 0.0003, 0.0000, -0.0003. The bars: the first half second over
     * 0.05 (CELLO) and 0.01 (ERHU), and the mean over the last 1.5 s under 0.005 (CELLO) and 0.001 (ERHU), so that a
     * string that kept a DC offset (a bow that pushed on after the lift, say) would fail it.
     */
    @Test
    fun `the raw mean is the onset's displacement and drains away`() {
        val firstBar = mapOf(ArcoVoice.CELLO to 0.05, ArcoVoice.ERHU to 0.01)
        val lastBar = mapOf(ArcoVoice.CELLO to 0.005, ArcoVoice.ERHU to 0.001)
        for (voice in ArcoVoice.entries) {
            val c = ArcoMeasure.core(voice, 0, bow = 0f, grip = 1f, gateSeconds = 3f)
            val halves = (0..5).map { k -> BowMeter.mean(c.out, (k * 0.5 * rate).toInt(), ((k + 1) * 0.5 * rate).toInt()) }
            val late = BowMeter.mean(c.out, (1.5 * rate).toInt(), (3.0 * rate).toInt())
            println("ARCO raw mean of a 3 s $voice root note at BOW 0 GRIP 1, each half second: ${halves.joinToString(" ") { f(it, 4) }}; last 1.5 s ${f(late, 4)}")
            assertTrue(halves[0] > firstBar.getValue(voice), "$voice: the onset did not displace the string (${halves[0]})")
            assertTrue(abs(late) < lastBar.getValue(voice), "$voice: the mean has not drained by 1.5 s ($late)")
        }
    }

    // ---------------------------------------------------------------- 8. the entry rule

    /**
     * A [Strings.Bow] is built at every TUNE step of both voices, at GRIP 0, 0.5 and 1, at the note's own pitch with the engine's
     * own position ([Arco.BETA]), corner ([Arco.cornerFor]) and share, the way [Arco.bow] builds it: one construction, no retune
     * (the vibrato is a read-back delay of the finished wave, so the string is never moved off the note). 45 steps by 3 GRIPs =
     * 135 bows, none refused. It is the record's "a unit test, not a discovery": the bridge segment is [Arco.BETA] of a period and
     * has to be at least two samples long after the bridge filter's delay and half a sample, which a high note with a dark
     * corner cannot manage.
     *
     * R1b saw the least room at ERHU A5 at GRIP 0 (corner 2828 Hz): [Arco.BETA] 0.0750 over the position floor, that is 0.133
     * against a floor of 0.058; the bar is 0.05 of room. The negative control is the span the design asked for first: D6
     * (1174.66 Hz, the 24th semitone from ERHU's root) at CELLO's 1000 Hz corner is refused, naming the Karplus-Strong minimum
     * (R1b saw a loop of -0.717 samples). The 19-semitone span is what keeps ERHU clear of it: a 1000 Hz corner still builds at
     * every ERHU step up to A#5 and is refused only from B5 (987.8 Hz, step 21) up, so ERHU's top, A5, stops two semitones short.
     */
    @Test
    fun `a Bow is built at every TUNE step at the engine's constants, and refused where the old span went`() {
        var minMargin = 1.0
        var minAt = ""
        for (voice in ArcoVoice.entries) {
            for (step in ArcoMeasure.steps(voice)) {
                for (grip in listOf(0f, 0.5f, 1f)) {
                    val hz = ArcoMeasure.hzOf(voice, step)
                    val corner = Arco.cornerFor(voice, step, grip)
                    Strings.Bow(f = hz, beta = Arco.BETA, bridgeHz = corner, share = Arco.shareFor(voice), rate = rate) // throws if it cannot be built
                    val period = rate / hz.toDouble()
                    val a = 1.0 - kotlin.math.exp(-2.0 * Math.PI * min(corner, rate * 0.45f) / rate)
                    val r = 1.0 - a
                    val w = 2.0 * Math.PI * hz / rate
                    val delay = kotlin.math.atan2(r * kotlin.math.sin(w), 1.0 - r * kotlin.math.cos(w)) / w
                    val floor = (Strings.MIN_LOOP_SAMPLES + 0.5 + delay) / period
                    if (Arco.BETA - floor < minMargin) {
                        minMargin = Arco.BETA - floor
                        minAt = "$voice ${name(voice, step)} GRIP $grip corner ${f(corner.toDouble(), 0)} Hz"
                    }
                }
            }
        }
        println("ARCO entry: the least room of BETA over the bow-position floor is ${f(minMargin, 4)} at $minAt")
        assertTrue(minMargin > 0.05, "BETA has only $minMargin of room over the floor at $minAt")
        val e = assertFailsWith<IllegalArgumentException> { Strings.Bow(1174.66f, Arco.BETA, Arco.CELLO_CORNER_LOW_HZ, Arco.shareFor(ArcoVoice.ERHU), rate) }
        println("ARCO entry control: ${e.message?.take(100)}")
        assertTrue("Karplus-Strong minimum" in (e.message ?: ""), "the refusal does not name its cause: ${e.message}")
    }

    // ---------------------------------------------------------------- 9. the stroke

    /**
     * The stroke's overshoot row: BOW 1 (a 10 ms attack, velocity and pressure biting at the voice's own [Arco.overshootMax], 1.75 times at ERHU
     * and 3.0 times at CELLO, whose bite relaxes with a time constant of at least [Arco.BITE_SECONDS_CELLO] (a time constant, not the time the bite lasts: 37 percent of it is left at 60 ms) and lifts the pressure
     * by only [Arco.BITE_PRESSURE_CELLO] of its share, half of the way to the window's top at BOW 1, where ERHU's is all of the way, and relaxing to the sustain)
     * at every TUNE step of both voices, at GRIP 1 (the pressure is at the window's top already) and at the default GRIP 0.6 (where CELLO's bite lifts it half of the way there): the raw wave
     * finite and under [Strings.Bow.RAW_PEAK_CEILING], and the string still locking into one slip a period,
     * within the voice's bar from the speaks table plus 0.3 s: 1.5 s at CELLO, which is the design's 1.2 s and 0.3 s, and 0.8 s at ERHU.
     *
     * R1c saw 90 cells all finite; the raw peak at most 0.753 at CELLO (G#2, GRIP 1; R1b's 0.822 at F#2) and 0.600 at ERHU (D4, GRIP 1), against the
     * ceiling 1.25 (at the three notes: CELLO C2 0.633, C3 0.678, C4 0.668, R1b's 0.658, 0.673 and 0.611; ERHU D4 0.600, C5 0.585, A5 0.505, ERHU's unchanged); and the lock
     * at most 1.44 s at CELLO (G2, GRIP 1; against R1b's 1.12 s at F2 at GRIP 0.6, which R1c's bite locks at 0.49 s) and 0.17 s at ERHU (D4, GRIP 0.6; the bar 0.8 s). The CELLO bar is 1.5 s, so the bigger bite
     * has taken all but 0.06 s of the room the lock had here (the interim 2.5 times / 120 ms / quarter-share bite left 0.19 s; the earlier 2.75 times / 60 ms one 0.14 s); the lock-across-BOW test below keeps 0.53 s of its own. The CELLO peak is above the grid's
     * 0.737 in the bounded-and-pinned grid, which takes only three notes: the grid is not the engine's worst corner.
     */
    @Test
    fun `the stroke's overshoot row locks at every TUNE step`() {
        for (voice in ArcoVoice.entries) {
            val steps = ArcoMeasure.steps(voice).toList()
            val rows = steps.pmap { step ->
                listOf(Arco.DEFAULT_GRIP, 1f).map { grip ->
                    val c = ArcoMeasure.core(voice, step, bow = 1f, grip = grip, gateSeconds = 3f)
                    Triple(c.out.all { it.isFinite() }, BowMeter.maxAbs(c.out), ArcoMeasure.verdict(c))
                }
            }
            val failures = ArrayList<String>()
            var peak = 0f
            var peakAt = ""
            var slowest = 0.0
            var slowestAt = ""
            for ((i, step) in steps.withIndex()) {
                for ((g, r) in rows[i].withIndex()) {
                    val label = "$voice ${name(voice, step)} GRIP ${listOf(Arco.DEFAULT_GRIP, 1f)[g]}"
                    if (r.second > peak) {
                        peak = r.second
                        peakAt = label
                    }
                    if (r.third.lock > slowest) {
                        slowest = r.third.lock
                        slowestAt = label
                    }
                    if (!r.first) failures.add("$label: not finite")
                    if (r.second >= Strings.Bow.RAW_PEAK_CEILING) failures.add("$label: raw peak ${r.second}")
                    val problems = speakingProblems(r.third, lockBar.getValue(voice) + 0.3)
                    if (problems.isNotEmpty()) failures.add("$label: $problems")
                }
            }
            for (step in spread(voice)) {
                val r = rows[steps.indexOf(step)][1]
                println("ARCO overshoot $voice ${name(voice, step)} BOW 1 GRIP 1: raw peak ${f(r.second.toDouble(), 3)}, lock ${f(r.third.lock)} s")
            }
            println("ARCO overshoot $voice: raw peak at most ${f(peak.toDouble(), 3)} at $peakAt, lock at most ${f(slowest)} s at $slowestAt over ${steps.size * 2} cells (the bar is ${f(lockBar.getValue(voice) + 0.3)} s)")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
    }

    /**
     * The lock across BOW, every TUNE step, at GRIP 0, 0.6 and 1: the longest of the three, printed per step and BOW
     * 0 / 0.3 / 0.6 / 0.8 / 1 with the GRIP it fell at. This is wider than the speaks table's BOW 0.5, and the string is
     * slower there: R1c saw CELLO lock as late as 1.47 s (G2 at BOW 0.6, GRIP 1), 1.45 s (F#2, BOW 0.6, GRIP 1), 1.44 s (G2, BOW 1, GRIP 1), 1.42 s (G#2, BOW 1, GRIP 1)
     * and 1.35 s (A2, BOW 0, GRIP 0), against 0.87 s at BOW 0.5, and ERHU as late as 0.42 s (A4, BOW 0,
     * GRIP 0). The bars are 2.0 s (CELLO) and 0.6 s (ERHU): they hold today and they are not the speaks table's. They are the
     * measurement with room and not the design's bound; the 1.2 s the speaks table sets for BOW 0.5 does not hold across BOW.
     * R1b saw CELLO as late as 1.66 s (A2 at BOW 0.8, GRIP 1), 1.49 s (D#2, BOW 0.8, GRIP 1) and 1.38 s (D2, BOW 0.6, GRIP 1); R1c's bigger
     * CELLO bite moved the late notes about (those three cells now lock at 0.47 s, 0.47 s and 0.58 s, and the late ones are E2 to G2 at BOW 0.6 and F2 to G#2 at
     * BOW 1) and the worst is 1.47 s, which leaves 0.53 s under the 2.0 s bar where R1b's had 0.34 s (two earlier settings: 0.59 s at 2.5 times with a 120 ms time constant, 0.35 s at 2.75 times with a 60 ms one).
     *
     * A default HOLD is 0.85 s of bow, and some of these locks are later than that (each the latest of the three GRIPs): at BOW 0.6 the CELLO notes E2 to G2 lock at
     * 1.00 to 1.47 s (all at GRIP 1) and B2 at 1.06 s (GRIP 0.6), at BOW 1 F2 to G#2 at 1.18 to 1.44 s and A2 at 1.07 s (all at GRIP 1), at BOW 0 C#2, F#2, A2 and B2 at 0.92, 0.89, 1.35 and 1.03 s (GRIP 1, 0, 0 and 0),
     * and at BOW 0.3 F2 at 0.98 s (GRIP 0); none at BOW 0.8, whose latest is 0.78 s. A default-HOLD note at those settings is the scratch from end to end. At the default BOW 0.5 the slowest lock is 0.868 s (D#2, GRIP 1, in the speaks
     * table), 14 ms past a default HOLD's 0.854 s. (How many of the 25 steps lock in a default note at BOW 1 and the default GRIP, which is the same question at one setting, is the roster-bar test below.)
     */
    @Test
    fun `the lock across BOW is printed and bounded`() {
        val bows = listOf(0f, 0.3f, 0.6f, 0.8f, 1f)
        val grips = listOf(0f, Arco.DEFAULT_GRIP, 1f)
        val bar = mapOf(ArcoVoice.CELLO to 2.0, ArcoVoice.ERHU to 0.6)
        for (voice in ArcoVoice.entries) {
            val steps = ArcoMeasure.steps(voice).toList()
            val rows = steps.pmap { step ->
                bows.map { b -> grips.map { g -> ArcoMeasure.verdict(ArcoMeasure.core(voice, step, bow = b, grip = g, gateSeconds = 3.5f)).lock } }
            }
            println("ARCO lock ms across BOW $bows, max over GRIP $grips and the GRIP it fell at ($voice):")
            var worst = 0.0
            var worstAt = ""
            for ((i, step) in steps.withIndex()) {
                println("ARCO   ${name(voice, step).padEnd(3)} " + rows[i].joinToString("  ") { r ->
                    val m = r.max()
                    "${(m * 1000).roundToInt().toString().padStart(5)}@${grips[r.indexOf(m)]}"
                })
                for ((b, r) in rows[i].withIndex()) {
                    val m = if (r.any { it < 0 }) Double.POSITIVE_INFINITY else r.max()
                    if (m > worst) {
                        worst = m
                        worstAt = "${name(voice, step)} BOW ${bows[b]} GRIP ${grips[r.indexOf(r.max())]}"
                    }
                }
            }
            println("ARCO lock across BOW $voice: worst ${f(worst)} s at $worstAt")
            assertTrue(worst <= bar.getValue(voice), "$voice locks only after ${f(worst)} s at $worstAt (bar ${bar.getValue(voice)})")
        }
    }

    /**
     * The onset table, printed and not asserted: for BOW 0 / 0.5 / 0.6 / 0.8 / 1 at CELLO C2, C3, C4 and ERHU D4, G#4, A5
     * (default GRIP, BODY 0, no vibrato, a 3 s bow-on): the milliseconds until a sliding window of whole periods (at least
     * 10 ms, centred) holds 90 percent of the settled RMS (read from 2.0 to 2.8 s), and the milliseconds until the string
     * locks into one slip a period for good, each also in periods of the note.
     *
     * The one honest assertion: BOW 1's 10 ms attack reaches 90 percent sooner than BOW 0's 400 ms at every note sampled.
     * R1c saw (ms to 90 percent, BOW 0 against BOW 1) CELLO C2 704 and 269, C3 448 and 434, C4 359 and 123; ERHU D4 358 and
     * 161, G#4 361 and 95, A5 370 and 52 (R1b saw CELLO C2 704 and 422, C3 448 and 372, C4 359 and 177: ERHU's are as they were). BOW 1 is sooner by 435, 14 and 236 ms at the three CELLO notes (R1b's: 282, 76 and 182),
     * so **C3's margin is thin, 14 ms**, the least of the six notes and the one this bar has: a small change to the bite moves it across (the assertion failed there on an earlier 2.75 times / 60 ms bite, whose BOW 1 reached 90 percent at 600 ms,
     * 152 ms after BOW 0 did, which is why the bite's later searches read this table). It is not monotone in between (at C3 BOW 1's 434 ms is slower
     * than BOW 0.5's 298 ms and BOW 0.8's 272 ms, and its lock is at 478 ms), which is why only the two ends are asserted. It is not true of every CELLO step either: the probe over
     * all 25 steps at the default GRIP is printed (the `ARCO onset CELLO all steps` line) and reads BOW 1 no sooner than BOW 0 at F#2 (565 ms against 667), G2 (527 against 612) and G#2 (498 against 590), so the three notes asserted are the ones the claim is made for (the interim 2.5 times, 120 ms bite read ten: E2, F2, F#2, G2, G#2, A2, C#3, D3, D#3 and E3).
     */
    @Test
    fun `the onset table, and a stab starts sooner than a slow bow`() {
        val bows = listOf(0f, 0.5f, 0.6f, 0.8f, 1f)
        val problems = ArrayList<String>()
        for ((voice, steps) in listOf(ArcoVoice.CELLO to listOf(0, 12, 24), ArcoVoice.ERHU to listOf(0, 6, 19))) {
            for (step in steps) {
                val rows = bows.pmap { b ->
                    val c = ArcoMeasure.core(voice, step, bow = b, gateSeconds = 3f)
                    Triple(b, ArcoMeasure.msToFraction(c.out, c.hz, 2.0, 2.8), ArcoMeasure.verdict(c).lock * 1000)
                }
                val hz = ArcoMeasure.hzOf(voice, step)
                println("ARCO onset $voice ${name(voice, step)}: " + rows.joinToString("  ") { (b, ms90, lock) -> "BOW $b ${f(ms90, 0)} ms / ${f(lock, 0)} ms (${f(ms90 * hz / 1000, 0)} / ${f(lock * hz / 1000, 0)} periods)" })
                val slow = rows.first { it.first == 0f }.second
                val fast = rows.first { it.first == 1f }.second
                if (!(fast > 0 && slow > 0 && fast < slow)) problems += "$voice ${name(voice, step)}: BOW 1 reaches 90 percent at ${f(fast, 0)} ms and BOW 0 at ${f(slow, 0)} ms"
            }
        }
        val cello = ArcoVoice.CELLO
        val every = ArcoMeasure.steps(cello).toList().pmap { step ->
            val ends = listOf(0f, 1f).map { b -> ArcoMeasure.core(cello, step, bow = b, gateSeconds = 3f).let { ArcoMeasure.msToFraction(it.out, it.hz, 2.0, 2.8) } }
            step to ends
        }
        val notSooner = every.filter { (_, ends) -> !(ends[1] > 0 && ends[0] > 0 && ends[1] < ends[0]) }
        println("ARCO onset CELLO all steps, ms to 90 percent BOW 0 / BOW 1: " + every.joinToString(" ") { (step, ends) -> "${name(cello, step)}=${f(ends[0], 0)}/${f(ends[1], 0)}" })
        println("ARCO onset CELLO: BOW 1 is not sooner than BOW 0 at ${notSooner.size} of ${every.size} steps: ${notSooner.joinToString(" ") { name(cello, it.first) }}")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---------------------------------------------------------------- 9b. the bite is per voice (R1c)

    /**
     * The bite is per voice since R1c: the owner heard CELLO's BOW 1 as "nearly" and "not enough bite" and ERHU's as a yes, so ERHU's constants
     * are R1b's ([Arco.OVERSHOOT_MAX_ERHU] 1.75 times, [Arco.BITE_SECONDS_ERHU] 0, which leaves the bite relaxing on the attack's own time
     * constant, [Arco.BITE_PRESSURE_ERHU] 1, all of the bite's share in the pressure) and CELLO's are three numbers of its own: [Arco.OVERSHOOT_MAX_CELLO]
     * 3.0 times, [Arco.BITE_SECONDS_CELLO] 60 ms (the least time constant the bite relaxes with: it is not the time the bite takes to be gone, 37 percent of it is left at one time constant and
     * 5 percent at three) and [Arco.BITE_PRESSURE_CELLO] 0.5, half of the share in the pressure. R1c searched them
     * (a few hundred settings) because each of its earlier settings broke a bar of the roster, which is history and why these are asserted exactly: 3.0 times with a 60 ms time constant and all of the
     * share in the pressure never locked SHORT STAB in its stab, 2.75 times with a 60 ms one put C3's BOW 1 at 600 ms to reach 90 percent against BOW 0's 448 (the pressure's climb held the low notes in their scratch),
     * and 2.5 times with a 120 ms time constant and a quarter of the share in the pressure let only 18 of the 25 TUNE steps lock inside a default note at BOW 1 (R1b: 23) and left one scramble roll in 200 late at temperature 1.
     * [Arco.overshootFor] is 1 (no bite) at and under [Arco.OVERSHOOT_FROM], the voice's own maximum at BOW 1 and strictly climbing between them, read in
     * hundredths of BOW for both voices, and its third argument is the maximum the probe overrides replace. [Arco.bitePressure] and [Arco.biteSeconds] are
     * the voice's own, and the pressure's share is a share: between 0 and 1 for both.
     *
     * The reference is R1b's formula written out here, `1 + 0.75 * ((bow - 0.5) / 0.5)` held to 0..1: ERHU's map is it at every BOW in tenths
     * (R1c saw it exactly), and so is CELLO's when it is handed R1b's 1.75 as the maximum. That is the control that CELLO's own is not: at BOW 0.6 to
     * 1 it is above the reference by 0.25 to 1.25 times (R1c saw exactly that, 1.25 times at BOW 1 and a fifth of it at BOW 0.6), and the bar is 0.1, so the comparison can fail with 0.15 times to spare.
     * It is a margin of the control and holds no claim about the engine (the constants are asserted exactly above).
     */
    @Test
    fun `the bite is per voice, ERHU's is the one that shipped, and BOW maps onto it`() {
        assertEquals(0.5f, Arco.OVERSHOOT_FROM)
        assertEquals(1.75f, Arco.OVERSHOOT_MAX_ERHU, "ERHU's bite moved: the owner heard BOW 1 on ERHU as a yes at 1.75 times")
        assertEquals(0f, Arco.BITE_SECONDS_ERHU, "ERHU's bite got a minimum length: it is meant to relax on the attack's own time constant")
        assertEquals(1f, Arco.BITE_PRESSURE_ERHU, "ERHU's bite got a share of its own: it is meant to put all of it into the pressure, as R1b's did")
        assertEquals(3.0f, Arco.OVERSHOOT_MAX_CELLO, "CELLO's bite moved: 3.0 times, 60 ms, half in the pressure is the setting the roster's bars were searched for")
        assertEquals(0.06f, Arco.BITE_SECONDS_CELLO)
        assertEquals(0.5f, Arco.BITE_PRESSURE_CELLO)
        assertEquals(Arco.OVERSHOOT_MAX_ERHU, Arco.overshootMax(ArcoVoice.ERHU))
        assertEquals(Arco.OVERSHOOT_MAX_CELLO, Arco.overshootMax(ArcoVoice.CELLO))
        assertEquals(Arco.BITE_SECONDS_ERHU, Arco.biteSeconds(ArcoVoice.ERHU))
        assertEquals(Arco.BITE_SECONDS_CELLO, Arco.biteSeconds(ArcoVoice.CELLO))
        assertEquals(Arco.BITE_PRESSURE_ERHU, Arco.bitePressure(ArcoVoice.ERHU))
        assertEquals(Arco.BITE_PRESSURE_CELLO, Arco.bitePressure(ArcoVoice.CELLO))
        val hundredths = (0..100).map { it / 100f }
        for (voice in ArcoVoice.entries) {
            for (bow in hundredths.filter { it <= Arco.OVERSHOOT_FROM }) assertEquals(1f, Arco.overshootFor(voice, bow), "$voice BOW $bow: a bite below the point where it starts")
            assertEquals(Arco.overshootMax(voice), Arco.overshootFor(voice, 1f), "$voice: BOW 1 is not the voice's own maximum")
            assertTrue(Arco.bitePressure(voice) in 0f..1f, "$voice: the bite's pressure share ${Arco.bitePressure(voice)} is not a share")
            for ((a, b) in hundredths.filter { it >= Arco.OVERSHOOT_FROM }.zipWithNext()) {
                assertTrue(Arco.overshootFor(voice, b) > Arco.overshootFor(voice, a), "$voice: the bite does not climb from BOW $a to $b")
            }
        }
        fun r1b(bow: Float) = 1f + 0.75f * ((bow - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val tenths = (0..10).map { it / 10f }
        println("ARCO bite map at BOW 0 to 1 in tenths: ERHU ${tenths.joinToString(" ") { f(Arco.overshootFor(ArcoVoice.ERHU, it).toDouble()) }}, CELLO ${tenths.joinToString(" ") { f(Arco.overshootFor(ArcoVoice.CELLO, it).toDouble()) }}")
        for (bow in tenths) assertEquals(r1b(bow), Arco.overshootFor(ArcoVoice.ERHU, bow), 1e-6f, "ERHU BOW $bow is not R1b's bite")
        for (bow in tenths) assertEquals(r1b(bow), Arco.overshootFor(ArcoVoice.CELLO, bow, R1B_BITE), 1e-6f, "CELLO's map at R1b's maximum is not R1b's bite at BOW $bow")
        val above = tenths.filter { it > 0.5f }.map { Arco.overshootFor(ArcoVoice.CELLO, it) - r1b(it) }
        println("ARCO bite map: CELLO above R1b's at BOW 0.6 to 1 by ${above.joinToString(" ") { f(it.toDouble()) }} times")
        assertTrue(above.all { it > 0.1f }, "CELLO's bite is within 0.1 times of R1b's at some BOW over 0.5 ($above): the reference cannot tell the voices apart")
    }

    /**
     * A bite maximum of 1 is no bite at all, and the string stays finite: the bite's share is `(over - 1) / (max - 1)`, which at a maximum of 1 is 0/0, and [Arco.bow] guards it (a probe that asks for a maximum of
     * 1 or less gets a share of 0, so the pressure never sees a NaN). At every CELLO and ERHU step sampled (CELLO C2, C3, C4 and ERHU D4, G#4, D5, A5), at BOW 1 where the share would be 1, the raw string played with `overshootMax = 1f`
     * (and with the other two overrides set to something that would matter if there were a bite, 200 ms and the whole share) is finite and equal, to the bit, to the same stroke with `overshoot = 1f`, which is the no-bite render by another
     * road; `overshootMax = 0.5f`, below 1, is the same. [Arco.overshootFor] at a maximum of 1 is 1 at every BOW. The control is the voice's own bite at the same note, which must differ from the no-bite render (R1c saw it differ at all
     * seven), so equality is not what any two strokes give. (This is what the guard is for: it cannot be shown to fail here without taking it out of the engine, so the claim is the finite, equal render and the arithmetic of the share.)
     */
    @Test
    fun `a bite maximum of 1 is no bite, and renders finite`() {
        val failures = ArrayList<String>()
        var differs = 0
        var equal = 0
        for ((voice, steps) in listOf(ArcoVoice.CELLO to listOf(0, 12, 24), ArcoVoice.ERHU to listOf(0, 6, 12, 19))) {
            for (step in steps) {
                val hz = ArcoMeasure.hzOf(voice, step)
                val m = ArcoMeasure.macros(voice, step, bow = 1f)
                val none = Arco.bow(voice, hz, m, rate, overshoot = 1f, vibrato = false)
                for ((max, seconds, pressure) in listOf(Triple(1f, null, null), Triple(1f, 0.2f, 1f), Triple(0.5f, 0.06f, 0.5f))) {
                    val raw = Arco.bow(voice, hz, m, rate, overshootMax = max, biteSeconds = seconds, pressureBite = pressure, vibrato = false)
                    if (!raw.all { it.isFinite() }) failures.add("$voice ${name(voice, step)}: a bite maximum of $max (minimum $seconds, pressure $pressure) rendered a non-finite sample")
                    if (!raw.contentEquals(none)) failures.add("$voice ${name(voice, step)}: a bite maximum of $max (minimum $seconds, pressure $pressure) is not the no-bite render") else equal++
                }
                if (Arco.bow(voice, hz, m, rate, vibrato = false).contentEquals(none)) failures.add("$voice ${name(voice, step)}: the voice's own bite at BOW 1 is the no-bite render, so the equality above proves nothing") else differs++
            }
        }
        for (bow in (0..100).map { it / 100f }) for (voice in ArcoVoice.entries) {
            assertEquals(1f, Arco.overshootFor(voice, bow, 1f), "$voice BOW $bow: a maximum of 1 is a bite")
        }
        println("ARCO bite maximum 1: finite and equal to the no-bite render at $equal of 21 (note, override) cells; the voice's own bite differs from it at $differs of 7 notes")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * R1b's BOW 1 bite for both voices, as a multiple of the sustain velocity: the one constant R1b had, before R1c split it by voice. Handed to
     * [Arco.bow] as `overshootMax` with a `biteSeconds` of 0 and a `pressureBite` of [R1B_PRESSURE] it plays R1b's CELLO stroke
     * ([the bite overrides at 1 point 75 times, no minimum and all of the share in the pressure play R1b's CELLO stroke] holds that it does).
     */
    private val R1B_BITE = 1.75f

    /** R1b's bite put all of its share into the pressure's climb: the third override of R1b's CELLO stroke, which CELLO's own 0.5 is not. */
    private val R1B_PRESSURE = 1f

    /**
     * One raw CELLO stroke at BOW 1 (default GRIP and HOLD, BODY 0, no vibrato), played by the engine: at its defaults, or with the bite's probe overrides
     * ([overshoot] is the velocity's bite alone, [overshootMax], [biteSeconds] and [pressureBite] replace the voice's own maximum, minimum length and pressure share).
     */
    private fun celloStroke(step: Int, overshootMax: Float? = null, biteSeconds: Float? = null, overshoot: Float? = null, pressureBite: Float? = null): FloatArray {
        val voice = ArcoVoice.CELLO
        return Arco.bow(
            voice, ArcoMeasure.hzOf(voice, step), ArcoMeasure.macros(voice, step, bow = 1f), rate,
            overshoot = overshoot, overshootMax = overshootMax, biteSeconds = biteSeconds, pressureBite = pressureBite, vibrato = false,
        )
    }

    /** R1b's CELLO stroke at BOW 1 (see [R1B_BITE]): the three overrides together. */
    private fun r1bStroke(step: Int): FloatArray = celloStroke(step, R1B_BITE, 0f, pressureBite = R1B_PRESSURE)

    /**
     * The bite's gain in dB over each of [ArcoMeasure.BITE_WINDOWS] (0 to 50, 50 to 100, 100 to 200 and 200 to 400 ms): the RMS of the raw string
     * over the window against the same stroke with no bite (`overshoot = 1`), whole-window RMS, no sliding one.
     */
    private fun biteGains(stroke: FloatArray, plain: FloatArray): List<Double> = ArcoMeasure.BITE_WINDOWS.map { (a, b) -> ArcoMeasure.windowGainDb(stroke, plain, a, b) }

    private fun gainRow(g: List<Double>) = g.joinToString(" ") { f(it, 1).padStart(5) }

    /**
     * CELLO's bite is something the note carries into its first few periods, and R1b's was not. At CELLO F2, C3, G3 and C4 (TUNE steps 5, 12, 19
     * and 24; BOW 1, default GRIP, BODY 0, default HOLD, no vibrato) the raw string is read over 0 to 50, 50 to 100 and 100 to 200 ms against the
     * same stroke with no bite, and the best of the three windows is the bite's gain (the best window, not the first 200 ms as a whole: over the whole 200 ms the figures are smaller, and the windows are
     * printed). R1c saw the shipped bite (3.0 times, 60 ms, half of its share in the pressure, played by the
     * engine at its defaults) at 2.0, 2.9, 3.0 and 2.7 dB (F2, C3, G3, C4), and R1b's (1.75 times, relaxing on the 10 ms attack, the pressure biting with it: the engine's own stroke
     * with `overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f`) at 0.3, 0.5, 0.5 and 0.4 dB. The bars are at least 1.5 dB for the shipped bite (0.5 dB under the least of
     * them, at F2, so that is the margin this bar has) and at most 1.0 dB for R1b's (0.5 dB over the most of them): the control is that the same judgement refuses R1b's bite, and the two are at least
     * 1.7 dB apart at every note (2.0 against 0.3 at F2, the nearest; 2.9 against 0.5, 3.0 against 0.5 and 2.7 against 0.4 at the others), so the bars can fail both ways. The bite is mostly over by 200 ms: in the 200 to 400 ms
     * window the shipped stroke is at -0.7, -2.3, 0.7 and 0.2 dB (negative at F2 and C3, where R1b's is -1.3 and -1.9 dB too: the window is past the bite and the strokes' build-ups differ), printed and not asserted.
     *
     * That the override is R1b's stroke, and not an approximation of one, is [the bite overrides at 1 point 75 times, no minimum and all of the share in the pressure play R1b's CELLO stroke]'s.
     */
    @Test
    fun `CELLO's bite is heard in its first 200 ms and R1b's was not`() {
        val voice = ArcoVoice.CELLO
        val failures = ArrayList<String>()
        val rows = listOf(5, 12, 19, 24).pmap { step ->
            val plain = celloStroke(step, overshoot = 1f)
            step to listOf(biteGains(celloStroke(step), plain), biteGains(r1bStroke(step), plain))
        }
        for ((step, gains) in rows) {
            val (shipped, r1b) = gains
            println("ARCO bite CELLO ${name(voice, step)} dB over 0-50 / 50-100 / 100-200 / 200-400 ms: shipped ${gainRow(shipped)} | R1b's (overshootMax 1.75, 0 ms, pressureBite 1) ${gainRow(r1b)}")
            if (shipped.take(3).max() < 1.5) failures.add("${name(voice, step)}: the shipped bite's best window is ${f(shipped.take(3).max(), 1)} dB, under 1.5")
            if (r1b.take(3).max() > 1.0) failures.add("${name(voice, step)}: R1b's bite reaches ${f(r1b.take(3).max(), 1)} dB, over the 1.0 it must stay under to be the control")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * ERHU's BOW 1 stroke is R1b's, sample for sample (the owner heard it as a yes and it must not change), and CELLO's is not R1b's. ERHU's bite constants are R1b's
     * (the test above holds them), so for ERHU the bite overrides at R1b's 1.75 times, no minimum and all of the share in the pressure are what the defaults are: at ERHU D4, G#4, D5 and A5 (TUNE steps
     * 0, 6, 12 and 19) the engine's default render at BOW 1, vibrato and all, is bit for bit the render with `overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f` and the vibrato's shape
     * named [Arco.VIBRATO_PLAIN], and so is the raw string without vibrato (R1c saw all eight equal). The control is the same comparison at CELLO C2 and C3 (steps 0 and 12),
     * where the engine's default is not R1b's stroke: the two raw strings differ, so the comparison can tell.
     */
    @Test
    fun `ERHU's BOW 1 stroke is as R1b shipped it, sample for sample, and CELLO's is not`() {
        val erhu = ArcoVoice.ERHU
        for (step in listOf(0, 6, 12, 19)) {
            val hz = ArcoMeasure.hzOf(erhu, step)
            val m = ArcoMeasure.macros(erhu, step, bow = 1f)
            val shipped = Arco.bow(erhu, hz, m, rate)
            val probed = Arco.bow(erhu, hz, m, rate, overshootMax = R1B_BITE, biteSeconds = 0f, pressureBite = R1B_PRESSURE, vibratoShape = Arco.VIBRATO_PLAIN)
            val dry = Arco.bow(erhu, hz, m, rate, vibrato = false)
            val dryProbed = Arco.bow(erhu, hz, m, rate, overshootMax = R1B_BITE, biteSeconds = 0f, pressureBite = R1B_PRESSURE, vibrato = false)
            println("ARCO bite ERHU ${name(erhu, step)}: the default is the 1.75 times, 0 ms, plain-vibrato render: ${shipped.contentEquals(probed)}; the raw string is the same without the vibrato: ${dry.contentEquals(dryProbed)}")
            assertTrue(shipped.contentEquals(probed), "ERHU ${name(erhu, step)} at BOW 1 is not R1b's 1.75 times, 0 ms, plain-vibrato render")
            assertTrue(dry.contentEquals(dryProbed), "ERHU ${name(erhu, step)}: the raw string at BOW 1 is not R1b's 1.75 times, 0 ms stroke")
        }
        for (step in listOf(0, 12)) {
            val same = celloStroke(step).contentEquals(r1bStroke(step))
            println("ARCO bite CELLO ${name(ArcoVoice.CELLO, step)}: the default stroke at BOW 1 is the 1.75 times, 0 ms, all-in-the-pressure stroke: $same")
            assertTrue(!same, "CELLO ${name(ArcoVoice.CELLO, step)} at BOW 1 is still R1b's stroke: the control cannot tell the voices apart")
        }
    }

    /**
     * `overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f` is R1b's CELLO stroke, and nothing in this test needs R1b's code. Two things say so, and two controls say they can fail.
     *  - Below the bite the overrides touch nothing: at CELLO C2, C3 and C4 at BOW 0 and at BOW 0.5 (where the bite's share is nought, so no maximum can be heard) the raw string
     *    with R1b's three, with the shipped 3.0 times, 60 ms and a half and with 5 times, 200 ms and nine tenths is bit for bit the engine's default (R1c saw all 18 equal).
     *  - The record: R1b's own ArcoTest (commit a761c55, the engine as it was) recorded BOW 1 reaching 90 percent of the settled RMS at C2, C3 and C4 in 422, 372 and 177 ms (default
     *    GRIP, a 3 s bow-on), the overshoot row's raw peaks at GRIP 1 there at 0.658, 0.673 and 0.611, and the locks into one slip a period (to the hundredth of a second) at 1.12 s
     *    for F2 (BOW 1, the default GRIP, the overshoot row's slowest) and, in the lock-across-BOW cells (a 3.5 s bow-on), at 1.66 s for A2 (BOW 0.8, GRIP 1), 1.49 s for D#2
     *    (BOW 0.8, GRIP 1) and 1.38 s for D2 (BOW 0.6, GRIP 1). The override reads all ten (R1c saw the onsets to the millisecond, the peaks to 0.0005 and the locks to the hundredth), and
     *    the lock is chaotic in the stroke, so a stroke that differed from R1b's by one term would not land on them.
     *  - The first control is the shipped stroke, which reads none of the three onsets (R1c saw 269, 434 and 123 ms against the record's 422, 372 and 177) and whose bite at BOW 1 is not the override's (a 5 times and 200 ms
     *    override at C3, a bite that is there, is not the default's raw string).
     *  - The second is the recipe R1c's first fix round gave, the two overrides `overshootMax = 1.75f, biteSeconds = 0f` without the third: the pressure then takes CELLO's own 0.5 share, and
     *    it reads 421, 296 and 177 ms at the three onsets (the test fails when all three are inside 0.5 ms of the record). Only C3 tells it from R1b's (296 against 372: C2's 421 against 422 and C4's 177 against 177 are
     *    within a millisecond), so the control rests on one note of three: that is the margin it has, and the stroke's own sensitivity to the pressure's share, not a tolerance, sets it.
     */
    @Test
    fun `the bite overrides at 1 point 75 times, no minimum and all of the share in the pressure play R1b's CELLO stroke`() {
        val cello = ArcoVoice.CELLO
        val failures = ArrayList<String>()
        var equal = 0
        for (step in listOf(0, 12, 24)) for (bow in listOf(0f, 0.5f)) {
            val hz = ArcoMeasure.hzOf(cello, step)
            val m = ArcoMeasure.macros(cello, step, bow = bow)
            val base = Arco.bow(cello, hz, m, rate, vibrato = false)
            for ((max, seconds, pressure) in listOf(Triple(R1B_BITE, 0f, R1B_PRESSURE), Triple(Arco.OVERSHOOT_MAX_CELLO, Arco.BITE_SECONDS_CELLO, Arco.BITE_PRESSURE_CELLO), Triple(5f, 0.2f, 0.9f))) {
                if (Arco.bow(cello, hz, m, rate, overshootMax = max, biteSeconds = seconds, pressureBite = pressure, vibrato = false).contentEquals(base)) equal++
                else failures.add("${name(cello, step)} BOW $bow: $max times for $seconds s with $pressure of the share in the pressure is not the default render, though there is no bite to differ")
            }
        }
        println("ARCO bite override below the bite: $equal of 18 raw strings equal the default (CELLO C2, C3, C4 at BOW 0 and 0.5, three triples of overrides each)")
        assertTrue(!celloStroke(12, 5f, 0.2f, pressureBite = 0.9f).contentEquals(celloStroke(12)), "a 5 times, 200 ms bite at BOW 1 is the default: the comparison above cannot tell")

        val steps = listOf(0, 12, 24)
        val onsetRecord = listOf(422.0, 372.0, 177.0)
        val peakRecord = listOf(0.658, 0.673, 0.611)
        fun onset(step: Int, max: Float?, seconds: Float?, pressure: Float?): Double {
            val c = ArcoMeasure.core(cello, step, bow = 1f, gateSeconds = 3f, overshootMax = max, biteSeconds = seconds, pressureBite = pressure)
            return ArcoMeasure.msToFraction(c.out, c.hz, 2.0, 2.8)
        }
        val onsets = steps.pmap { onset(it, R1B_BITE, 0f, R1B_PRESSURE) }
        val shippedOnsets = steps.pmap { onset(it, null, null, null) }
        val twoOverrides = steps.pmap { onset(it, R1B_BITE, 0f, null) }
        val peaks = steps.pmap { BowMeter.maxAbs(ArcoMeasure.core(cello, it, bow = 1f, grip = 1f, gateSeconds = 3f, overshootMax = R1B_BITE, biteSeconds = 0f, pressureBite = R1B_PRESSURE).out).toDouble() }
        for ((i, step) in steps.withIndex()) {
            if (abs(onsets[i] - onsetRecord[i]) > 0.5) failures.add("${name(cello, step)}: BOW 1 reaches 90 percent at ${f(onsets[i], 1)} ms, R1b recorded ${onsetRecord[i].roundToInt()}")
            if (abs(peaks[i] - peakRecord[i]) > 0.0006) failures.add("${name(cello, step)}: the overshoot row's raw peak is ${f(peaks[i], 4)}, R1b recorded ${peakRecord[i]}")
        }
        println(
            "ARCO bite R1b's record at CELLO C2 / C3 / C4: onset ${onsets.joinToString(" ") { f(it, 0) }} ms (recorded ${onsetRecord.joinToString(" ") { f(it, 0) }}), " +
                "raw peak ${peaks.joinToString(" ") { f(it, 3) }} (recorded ${peakRecord.joinToString(" ") { f(it, 3) }}); the shipped stroke reads ${shippedOnsets.joinToString(" ") { f(it, 0) }} ms, " +
                "the two overrides without the third read ${twoOverrides.joinToString(" ") { f(it, 0) }} ms",
        )
        // the locks: (step, BOW), (GRIP, bow-on seconds) and the lock R1b recorded
        val lockRecord = listOf(Triple(5 to 1f, Arco.DEFAULT_GRIP to 3f, 1.12), Triple(9 to 0.8f, 1f to 3.5f, 1.66), Triple(3 to 0.8f, 1f to 3.5f, 1.49), Triple(2 to 0.6f, 1f to 3.5f, 1.38))
        val locks = lockRecord.pmap { (cell, how, _) ->
            fun lock(max: Float?, seconds: Float?, pressure: Float?) =
                ArcoMeasure.verdict(ArcoMeasure.core(cello, cell.first, bow = cell.second, grip = how.first, gateSeconds = how.second, overshootMax = max, biteSeconds = seconds, pressureBite = pressure)).lock
            lock(R1B_BITE, 0f, R1B_PRESSURE) to lock(null, null, null)
        }
        for ((i, rec) in lockRecord.withIndex()) {
            val label = "${name(cello, rec.first.first)} BOW ${rec.first.second} GRIP ${rec.second.first}"
            if (abs(locks[i].first - rec.third) > 0.0051) failures.add("$label: the lock is ${f(locks[i].first, 3)} s, R1b recorded ${rec.third}")
        }
        println("ARCO bite R1b's record, locks at F2 (BOW 1, GRIP 0.6) / A2 (BOW 0.8) / D#2 (BOW 0.8) / D2 (BOW 0.6), s: ${locks.joinToString(" ") { f(it.first) }} (recorded ${lockRecord.joinToString(" ") { f(it.third) }}); the shipped stroke reads ${locks.joinToString(" ") { f(it.second) }}")
        if (shippedOnsets.indices.all { abs(shippedOnsets[it] - onsetRecord[it]) <= 0.5 }) failures.add("the shipped stroke reads R1b's three onsets: the record cannot tell the two strokes apart")
        if (twoOverrides.indices.all { abs(twoOverrides[it] - onsetRecord[it]) <= 0.5 }) failures.add("the two overrides without the pressure's share read R1b's three onsets: the third override is not what makes the stroke R1b's")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---------------------------------------------------------------- 9c. the roster's bars the bite must keep (R1c)

    /** A bite's three numbers (maximum, least time constant, pressure share), null where the voice's own is meant. */
    private class Bite(val max: Float?, val seconds: Float?, val pressure: Float?)

    private val SHIPPED = Bite(null, null, null)

    /** R1b's CELLO stroke: [R1B_BITE], no minimum time constant and all of the share in the pressure. */
    private val R1B = Bite(R1B_BITE, 0f, R1B_PRESSURE)

    /**
     * The bite CELLO had in R1c's interim fix round, 2.5 times, 120 ms and a quarter of its share in the pressure: history, which let only 18 of the 25 default-note steps lock and is the
     * control of the count tests below.
     */
    private val INTERIM = Bite(2.5f, 0.12f, 0.25f)

    /** A bite far heavier than any the roster could take (5 times, a 200 ms time constant, nine tenths of the share in the pressure): the control of the stab tests below, a stroke that must fail what they hold the shipped bite to. */
    private val HEAVY = Bite(5f, 0.2f, 0.9f)

    /** No bite at all: a maximum of 1 (the guard's case, [a bite maximum of 1 is no bite, and renders finite]). Printed beside the others. */
    private val NONE = Bite(1f, null, null)

    /** When CELLO at [step] locks into one slip a period, in seconds (-1 never), on the raw core at BOW [bow], [grip] and [hold], no vibrato, played with [bite]. */
    private fun celloLock(step: Int, grip: Float, hold: Float, bite: Bite = SHIPPED, bow: Float = 1f): Double {
        val c = ArcoMeasure.core(ArcoVoice.CELLO, step, bow = bow, grip = grip, hold = hold, overshootMax = bite.max, biteSeconds = bite.seconds, pressureBite = bite.pressure)
        return ArcoMeasure.lockSeconds(c.bowPoint, c.holdN, c.hz)
    }

    /** True when a lock at [lock] seconds is a lock inside [bar] seconds. */
    private fun inside(lock: Double, bar: Double) = lock in 0.0..bar

    /**
     * What the roster asks of a note that is meant to speak: the string locks into one slip a period inside 0.85 of its bow-on ([ArcoPresetsTest] holds every preset that is not a scrape to it).
     * The same bar, read on the notes a player gets without choosing a preset: the default note at BOW 1 (GRIP 0.6, HOLD 0.4, so 0.854 s of bow and a bar of 0.726 s) at each of the 25 CELLO TUNE steps, no vibrato.
     * The bite is what moves it: the lock is chaotic in the stroke, and a stronger or longer bite can leave a low note in its scratch for the whole of a short bow. R1c saw 22 of the 25 steps lock
     * inside the bar with the shipped bite (the three that do not, F#2 at 0.82 s, G2 at 0.82 s and G#2 at 0.77 s, do lock inside the 0.854 s of bow, but not inside 0.85 of it; they are also the three steps where the onset table
     * finds BOW 1 no sooner than BOW 0); R1b's stroke read 23 (F2 and F#2 never lock in the bow-on) and the interim bite (2.5 times, 120 ms, a quarter of the share in the pressure) 18 (E2, D3 and D#3 never, and F2 to G#2 at 0.73 to 0.79 s),
     * and no bite at all 23. The bar is at least 21, one under the shipped 22 and two under R1b's 23, so a bite that costs a note more than the shipped one still passes and one that costs two fails. The control is the interim bite, which reads
     * 18 and must fail the same bar (3 under it), so the count can fail.
     */
    @Test
    fun `a default note at BOW 1 locks inside the roster's bar at 21 or more of the 25 CELLO steps`() {
        val steps = ArcoMeasure.steps(ArcoVoice.CELLO).toList()
        val bowOn = Arco.holdSeconds(Arco.DEFAULT_HOLD).toDouble()
        val bar = 0.85 * bowOn
        fun locks(bite: Bite) = steps.pmap { celloLock(it, Arco.DEFAULT_GRIP, Arco.DEFAULT_HOLD, bite) }
        val shipped = locks(SHIPPED)
        val r1b = locks(R1B)
        val interim = locks(INTERIM)
        val none = locks(NONE)
        fun late(l: List<Double>) = steps.indices.filter { !inside(l[it], bar) }
        println("ARCO default note at BOW 1 (GRIP ${Arco.DEFAULT_GRIP}, HOLD ${Arco.DEFAULT_HOLD}: bow-on ${f(bowOn, 3)} s, bar ${f(bar, 3)} s): ${steps.size - late(shipped).size} of ${steps.size} CELLO steps lock inside it; R1b's stroke ${steps.size - late(r1b).size}; the interim bite ${steps.size - late(interim).size}; no bite ${steps.size - late(none).size}")
        println("ARCO   not inside the bar, shipped: " + late(shipped).joinToString(" ") { "${name(ArcoVoice.CELLO, steps[it])}=${if (shipped[it] < 0) "never" else f(shipped[it])}" })
        println("ARCO   not inside the bar, R1b's stroke: " + late(r1b).joinToString(" ") { "${name(ArcoVoice.CELLO, steps[it])}=${if (r1b[it] < 0) "never" else f(r1b[it])}" })
        println("ARCO   not inside the bar, the interim bite: " + late(interim).joinToString(" ") { "${name(ArcoVoice.CELLO, steps[it])}=${if (interim[it] < 0) "never" else f(interim[it])}" })
        val have = steps.size - late(shipped).size
        assertTrue(have >= 21, "only $have of ${steps.size} CELLO steps lock inside ${f(bar, 3)} s at the default note and BOW 1: ${late(shipped).map { name(ArcoVoice.CELLO, steps[it]) }}")
        assertTrue(steps.size - late(interim).size < 21, "the interim bite reads ${steps.size - late(interim).size}, not under 21: the bar cannot tell it from the shipped bite")
    }

    /**
     * The stab figure the owner marked YES is at BOW 1, SHORT STAB's macros (GRIP 0.7, BODY 0.4, HOLD 0, so a 0.3 s bow and a bar of 0.255 s), on C3, E-flat3 and G3 (the audition's figure, C3 C3 E-flat3 G3 G3 E-flat3 C3, 0.3 s each;
     * MIDI 48, 51 and 55). R1c saw the lock of each, the shipped stroke against R1b's (`overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f`): G3 at 0.206 s and 0.214 s, C3 never and never, **E-flat3 never and 0.189 s**.
     * That last is a real loss and the test does not hide it or hold it: R1b's bite was small enough that E-flat3 locked at 0.189 s, and the shipped bite leaves it a scrape for the whole of the 0.3 s stab (so does the interim bite; R1c's
     * search of 80 settings had 19 that kept the other bars, and none of the 19 keeps it; a search this file does not reproduce, though the probe overrides it used are here). What is asserted is what survives: G3 locks inside the bar of 0.255 s,
     * 49 ms of room. The control is the same judgement on a bite far heavier than any the roster could take ([HEAVY], 5 times for 200 ms with nine tenths of the share in the pressure), which R1c saw leave G3 never locking and must fail
     * the assertion, so it can fail; the lock of G3 with no bite at all is printed (R1c saw it never lock either: R1b's bite is what let G3 lock in the stab, and the shipped one keeps that).
     */
    @Test
    fun `the stab figure at BOW 1 still locks G3 inside its stab, and E-flat3's loss is printed`() {
        val stab = ArcoPresets.forVoice(ArcoVoice.CELLO).first { it.name == "SHORT STAB" }.macros
        val grip = stab.getValue("GRIP")
        val hold = stab.getValue("HOLD")
        val bar = 0.85 * Arco.holdSeconds(hold).toDouble()
        val notes = listOf("C3" to 48, "E-flat3" to 51, "G3" to 55)
        fun lockOf(midi: Int, bite: Bite) = celloLock(midi - Arco.CELLO_ROOT_MIDI, grip, hold, bite)
        fun said(l: Double) = if (l < 0) "never" else "${f(l, 3)} s"
        for ((label, midi) in notes) {
            println(
                "ARCO stab figure $label (MIDI $midi) at SHORT STAB's macros with BOW 1, lock (the bar is ${f(bar, 3)} s): shipped ${said(lockOf(midi, SHIPPED))}, R1b's stroke ${said(lockOf(midi, R1B))}, " +
                    "the interim bite ${said(lockOf(midi, INTERIM))}, no bite ${said(lockOf(midi, NONE))}, the heavy bite ${said(lockOf(midi, HEAVY))}",
            )
        }
        val g3 = lockOf(55, SHIPPED)
        val heavy = lockOf(55, HEAVY)
        assertTrue(inside(g3, bar), "G3 at SHORT STAB's macros with BOW 1 locks at ${said(g3)}, not inside ${f(bar, 3)} s")
        assertTrue(!inside(heavy, bar), "G3 with the heavy bite locks at ${said(heavy)}, inside ${f(bar, 3)} s: the assertion cannot fail")
    }

    /**
     * The shortest stab, HOLD 0 (0.3 s of bow and a bar of 0.255 s), at GRIP 0.7 and BOW 1, at each of the 25 CELLO TUNE steps: how many lock inside the bar. R1c saw 6 of 25 with the shipped bite, 10 with R1b's stroke, 7 with the
     * interim bite and 6 with no bite at all: a stab at BOW 1 is a scrape on most of the low strings whatever the bite, and what the bigger bite costs against R1b's is four of the ten notes that did lock (a loss to the stab, as the
     * E-flat3 of the figure above is). The bar is at least 6, which is what R1c reads, so it has no margin and the figure may not fall by one more step; the lock is chaotic in the stroke and the count moves with the bite (R1c's probe of
     * 19 settings between 1.5 and 5 times read 5 to 12, and four heavier ones 0 to 3), which is the evidence that the bar can fail. The control is [HEAVY], which R1c saw read 0 of 25 and must fail the same bar.
     */
    @Test
    fun `a HOLD 0 stab at GRIP 0 point 7 and BOW 1 locks inside its bar at 6 or more of the 25 CELLO steps`() {
        val steps = ArcoMeasure.steps(ArcoVoice.CELLO).toList()
        val hold = 0f
        val grip = 0.7f
        val bar = 0.85 * Arco.holdSeconds(hold).toDouble()
        fun count(bite: Bite) = steps.pmap { celloLock(it, grip, hold, bite) }.count { inside(it, bar) }
        val shipped = count(SHIPPED)
        val r1b = count(R1B)
        val interim = count(INTERIM)
        val none = count(NONE)
        val heavy = count(HEAVY)
        println("ARCO HOLD 0 stab at GRIP $grip and BOW 1 (bar ${f(bar, 3)} s): $shipped of ${steps.size} CELLO steps lock inside it; R1b's stroke $r1b; the interim bite $interim; no bite $none; the heavy bite $heavy")
        assertTrue(shipped >= 6, "only $shipped of ${steps.size} CELLO steps lock inside ${f(bar, 3)} s at HOLD 0, GRIP $grip and BOW 1")
        assertTrue(heavy < 6, "the heavy bite reads $heavy of ${steps.size}, not under 6: the bar cannot fail")
    }

    // ---------------------------------------------------------------- 10. the constants

    /**
     * The constants are the ones that pass. Each value below is what the speaks table (495 cells at 0 failures), the tuning
     * tables and the GRIP travel were measured against, so changing one fails here, next to the number it drifted from,
     * and not three tests away. The window per TUNE step is printed (`ARCO window` lines).
     *
     *  - [Arco.BETA] 0.133, the bow's place: bridge-ward of the middle and under which the reflection table speaks once a
     *    period; 0.075 is the least D6 builds at.
     *  - [Arco.V_SUSTAIN] 0.13 and the share [Strings.Bow.SHARE] 0.85 for both voices (the 5 cent tuning tables were pinned at it).
     *  - CELLO's pressure floor [Arco.CELLO_PRESSURE_LOW_ROOT] 0.97 at C2 falling [Arco.CELLO_PRESSURE_LOW_FALL] 0.02 a semitone
     *    to [Arco.PRESSURE_LOW] 0.85 (reached at F#2, the sixth semitone), its corner [Arco.CELLO_CORNER_LOW_HZ] 1000 Hz at
     *    GRIP 0 and 1500 Hz at GRIP 1 at the root, rising to [Strings.Bow.BRIDGE_HZ] by the eighth semitone (G#2).
     *  - ERHU's pressure floor [Arco.ERHU_PRESSURE_LOW] 0.94 (the 4.50 cent GRIP travel at F5; 0.85 gives 6.60) and its corner from
     *    1500 Hz at D4 rising to 2828.3 Hz at A5 at GRIP 0, and the full 3023.6 Hz at GRIP 1 everywhere.
     *  - the spans: 24 semitones (C2 to C4) and 19 (D4 to A5; B5 at 21 is the last that speaks, two semitones of margin).
     */
    @Test
    fun `the voice constants are the ones that pass`() {
        for (voice in ArcoVoice.entries) {
            for (step in ArcoMeasure.steps(voice)) {
                val g = Arco.gripFor(voice, step)
                println("ARCO window $voice ${name(voice, step)}: pressure ${f(g.pressureLow.toDouble(), 3)}..${f(g.pressureHigh.toDouble(), 3)} corner ${f(g.cornerLow.toDouble(), 1)}..${f(g.cornerHigh.toDouble(), 1)} Hz")
            }
        }
        assertEquals(0.133f, Arco.BETA, "the bow's place moved: the speaks table and the position floor were measured at 0.133")
        assertEquals(0.13f, Arco.V_SUSTAIN, "the sustain velocity moved: every window was measured at 0.13")
        assertEquals(0.85f, Arco.shareFor(ArcoVoice.CELLO), "CELLO's share moved: the tuning tables were pinned at 0.85")
        assertEquals(0.85f, Arco.shareFor(ArcoVoice.ERHU), "ERHU's share moved: the tuning tables were pinned at 0.85")
        assertEquals(24, Arco.tuneSemitones(ArcoVoice.CELLO), "CELLO's span moved")
        assertEquals(19, Arco.tuneSemitones(ArcoVoice.ERHU), "ERHU's span moved: the design asked for 24 and the speaks test took it back to 19")
        assertEquals(36, Arco.rootMidi(ArcoVoice.CELLO))
        assertEquals(62, Arco.rootMidi(ArcoVoice.ERHU))
        assertEquals(0.85f, Arco.PRESSURE_LOW)
        assertEquals(1.0f, Arco.PRESSURE_HIGH)
        assertEquals(0.97f, Arco.CELLO_PRESSURE_LOW_ROOT, "CELLO's pressure floor at C2 moved: 0.88 to 0.94 is a scratch there")
        assertEquals(0.02f, Arco.CELLO_PRESSURE_LOW_FALL)
        assertEquals(0.94f, Arco.ERHU_PRESSURE_LOW, "ERHU's pressure floor moved: at 0.85 GRIP moves F5 by 6.6 cents")
        assertEquals(1000f, Arco.CELLO_CORNER_LOW_HZ, "CELLO's dark corner moved: at 65 Hz a brighter one locks late")
        assertEquals(1500f, Arco.CELLO_CORNER_HIGH_ROOT_HZ)
        assertEquals(8, Arco.CELLO_CORNER_RISE_SEMITONES)
        assertEquals(1500f, Arco.ERHU_CORNER_LOW_ROOT_HZ)
        assertEquals(21, Arco.ERHU_CORNER_RISE_SEMITONES)
        assertEquals(3023.6f, Strings.Bow.BRIDGE_HZ)
        // the window the constants make, at the notes the tables above were read at
        val c2 = Arco.gripFor(ArcoVoice.CELLO, 0)
        assertEquals(0.97f, c2.pressureLow, 1e-6f)
        assertEquals(1500f, c2.cornerHigh, 0.5f)
        assertEquals(0.85f, Arco.gripFor(ArcoVoice.CELLO, 6).pressureLow, 1e-6f, "the floor meets 0.85 at the sixth semitone")
        assertEquals(0.85f, Arco.gripFor(ArcoVoice.CELLO, 24).pressureLow, 1e-6f)
        assertEquals(Strings.Bow.BRIDGE_HZ, Arco.gripFor(ArcoVoice.CELLO, 8).cornerHigh, 0.5f, "the corner is full by G#2")
        assertEquals(Strings.Bow.BRIDGE_HZ, Arco.gripFor(ArcoVoice.CELLO, 24).cornerHigh, 0.5f)
        assertEquals(1000f, Arco.gripFor(ArcoVoice.CELLO, 24).cornerLow, 0.5f)
        assertEquals(0.94f, Arco.gripFor(ArcoVoice.ERHU, 0).pressureLow, 1e-6f)
        assertEquals(1500f, Arco.gripFor(ArcoVoice.ERHU, 0).cornerLow, 0.5f)
        assertEquals(2828.3f, Arco.gripFor(ArcoVoice.ERHU, 19).cornerLow, 1f, "ERHU's dark corner at A5")
        assertEquals(Strings.Bow.BRIDGE_HZ, Arco.gripFor(ArcoVoice.ERHU, 19).cornerHigh, 0.5f)
        // the shape: a floor that only falls, a dark corner that only rises, a ceiling that never moves
        for (voice in ArcoVoice.entries) {
            val steps = ArcoMeasure.steps(voice).toList()
            for ((a, b) in steps.zipWithNext()) {
                val ga = Arco.gripFor(voice, a)
                val gb = Arco.gripFor(voice, b)
                assertTrue(gb.pressureLow <= ga.pressureLow + 1e-6f, "$voice: the pressure floor rises from ${name(voice, a)} to ${name(voice, b)}")
                assertTrue(gb.cornerLow >= ga.cornerLow - 0.5f, "$voice: the dark corner falls from ${name(voice, a)} to ${name(voice, b)}")
                assertTrue(gb.cornerHigh >= ga.cornerHigh - 0.5f, "$voice: the bright corner falls from ${name(voice, a)} to ${name(voice, b)}")
            }
            for (step in steps) {
                val g = Arco.gripFor(voice, step)
                assertEquals(Arco.PRESSURE_HIGH, g.pressureHigh)
                assertTrue(g.pressureLow < g.pressureHigh && g.cornerLow <= g.cornerHigh, "$voice ${name(voice, step)}: the window is empty")
            }
        }
    }

    // ---------------------------------------------------------------- 11. a held note with vibrato

    /** The three TUNEs a held note is read at: root, middle and top, the engine's own TUNE 0, 0.5 and 1. */
    private fun heldCases() = ArcoVoice.entries.flatMap { v -> spread(v).map { v to it } }

    /**
     * A held 3 s note ([Arco.holdFor] of 3 s, the audition's clip) through the core with the engine's vibrato on, at root,
     * middle and top of both voices (CELLO C2, C3, C4; ERHU D4, C5, A5): everything finite, the raw peak under
     * [Strings.Bow.RAW_PEAK_CEILING], and the pitch excursion small: on 120 ms windows stepped 40 ms from 0.8 s to the end of the
     * bow-on, read by autocorrelation, the largest distance of any window's pitch from the mean of them.
     *
     * R1c saw the raw peak at most 0.652 (CELLO C2); the excursion 3.4 to 6.3 cents with the vibrato on (CELLO C2 6.3, C3 4.5,
     * C4 4.0; ERHU D4 3.4, C5 3.6, A5 3.5; R1b's plain vibrato read CELLO at 6.0, 4.4 and 3.7, and ERHU's is that same vibrato) and 0.1 to 0.7 cents with it
     * off, the same cells, which is the measure's own floor (at C2 a 120 ms window is only 8 periods). The bar is the record's 15 cents. ERHU's
     * vibrato is +-10 cents at 6.1 Hz and CELLO's drifts about that (its swings differ in height by up to x1.25 and its cycles in length by up to x1.10 from one to the next in the same direction, peaking at 12.75 cents,
     * ArcoProductTest's wander test reads it), and a 120 ms window is 0.73 of a vibrato cycle long, so it averages most of the swing away (to 0.32 of it): a bar
     * of 15 does not need that margin to be tight. The lower bar is that the vibrato is there at all: at least 2 cents, with the same
     * windows reading under 1.5 cents on a plain string (the negative control).
     */
    @Test
    fun `a held three second note with vibrato is finite, bounded and within 15 cents`() {
        for ((voice, step) in heldCases()) {
            val on = ArcoMeasure.core(voice, step, hold = Arco.holdFor(3f), vibrato = true)
            val off = ArcoMeasure.core(voice, step, hold = Arco.holdFor(3f), vibrato = false)
            fun excursion(c: ArcoMeasure.Core): Double {
                val winN = (0.12 * rate).toInt()
                val cents = ArrayList<Double>()
                var s = (0.8 * rate).toInt()
                while (s + winN <= c.holdN) {
                    cents.add(BowMeter.cents(BowMeter.pitch(c.out, s, winN, c.hz).first, c.hz.toDouble()))
                    s += (0.04 * rate).toInt()
                }
                val mean = cents.average()
                return cents.maxOf { abs(it - mean) }
            }
            val withVibrato = excursion(on)
            val plain = excursion(off)
            println("ARCO vibrato $voice ${name(voice, step)}: hold ${f(on.holdN.toDouble() / rate)} s, raw peak ${f(BowMeter.maxAbs(on.out).toDouble(), 3)}, excursion ${f(withVibrato, 1)} c with vibrato, ${f(plain, 1)} c without")
            assertTrue(on.out.all { it.isFinite() } && on.bowPoint.all { it.isFinite() }, "$voice ${name(voice, step)}: not finite")
            assertTrue(BowMeter.maxAbs(on.out) < Strings.Bow.RAW_PEAK_CEILING, "$voice ${name(voice, step)}: peak ${BowMeter.maxAbs(on.out)}")
            assertTrue(withVibrato <= 15.0, "$voice ${name(voice, step)}: the pitch wanders ${f(withVibrato, 1)} cents")
            assertTrue(withVibrato >= 2.0, "$voice ${name(voice, step)}: the vibrato is only ${f(withVibrato, 1)} cents, as if it were off")
            assertTrue(plain < 1.5, "$voice ${name(voice, step)}: a plain string reads ${f(plain, 1)} cents of excursion, so the measure cannot tell the vibrato")
        }
    }

    /**
     * A held 3 s note with the vibrato on keeps one slip a period: no gap between slips that is not one period long, after
     * 0.7 s, at root, middle and top of both voices, and the string's own velocity under the bow is bit for bit the plain
     * string's, because the vibrato is a read-back delay of the finished wave and the bow never feels it. The record asks for 0
     * unclean gaps. R1b's first vibrato (a retune of the bow every 64 samples) failed this at ERHU: 3 of 1207 slips at C5 and 4 of 2025
     * at A5, 37 gaps in the 24,746 slips of ERHU's 20 steps (CELLO had 1 in 8,337), each a slip that splits in two for a moment at a
     * particular phase of the swing; the plain string had none. The sweep over every step is printed (`ARCO vibrato sweep`).
     */
    @Test
    fun `a held three second note with vibrato keeps one slip a period, because the string never feels it`() {
        val failures = ArrayList<String>()
        for (voice in ArcoVoice.entries) {
            val steps = ArcoMeasure.steps(voice).toList()
            val rows = steps.pmap { step ->
                val c = ArcoMeasure.core(voice, step, hold = Arco.holdFor(3f), vibrato = true)
                val plain = ArcoMeasure.core(voice, step, hold = Arco.holdFor(3f), vibrato = false)
                require(c.bowPoint.contentEquals(plain.bowPoint)) { "$voice step $step: the vibrato changed the string's own velocity under the bow" }
                val slips = ArcoMeasure.slipTimes(c.bowPoint, c.holdN)
                val plainSlips = ArcoMeasure.slipTimes(plain.bowPoint, plain.holdN)
                Triple(ArcoMeasure.uncleanGaps(slips, c.hz, 0.7), ArcoMeasure.uncleanGaps(plainSlips, plain.hz, 0.7), slips.count { it / rate >= 0.7 })
            }
            println("ARCO vibrato sweep $voice, unclean gaps after 0.7 s with the vibrato (without it) of the slips: " +
                steps.indices.joinToString(" ") { "${name(voice, steps[it])}=${rows[it].first}(${rows[it].second})/${rows[it].third}" })
            for (step in spread(voice)) {
                val r = rows[steps.indexOf(step)]
                if (r.first != 0) failures.add("$voice ${name(voice, step)}: ${r.first} unclean gaps of ${r.third} slips after 0.7 s (${r.second} without the vibrato)")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
