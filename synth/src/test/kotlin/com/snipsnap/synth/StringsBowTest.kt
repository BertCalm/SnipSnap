package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Strings.Bow], the bowed string, on its own: the two [Strings.Loop]s with the friction junction between
 * them, before any voice, macro or body is put round it. Every number printed here (the lines that start
 * `BOW`) was measured on the built bow, not carried over from the Phase-0 spike, and every bound sits past
 * what was measured, narrowly where the measure is steady and with room where it is a draw of the string's
 * own motion (the margin is stated at the assert where it is wide), so a change that makes the bow worse
 * fails next to the number it drifted from.
 *
 * The raw wave is measured at the rate the bow runs at ([BowMeter.RATE]); pitch is by autocorrelation, the
 * way the tuning share was pinned. The speaking test plays only at pressures inside measured single-slip
 * cells (STK's own default, 0.5, is two slips a period at A3); the in-tune test measures pitch only, in
 * cells that include two- and three-slip ones (65 Hz, 220 Hz and 880 Hz at 0.5), because a string slipping
 * twice a period is still at its note.
 */
class StringsBowTest {

    private val rate = BowMeter.RATE
    private val beta = 0.133f

    private fun f(v: Double, digits: Int = 2) = "%.${digits}f".format(java.util.Locale.ROOT, v)

    /** The last 0.3 s of a 1.5 s render: the string has long since settled. */
    private fun steady(run: BowMeter.Run, f0: Float): Triple<Double, Double, Int> {
        val from = (1.2 * rate).toInt()
        val len = (0.3 * rate).toInt()
        val (hz, peak) = BowMeter.pitch(run.out, from, len, f0)
        return Triple(hz, peak, from + len)
    }

    // ---------------------------------------------------------------- it speaks

    @Test
    fun `a bow at a pressure of 0_7 or more speaks in Helmholtz motion, and so does 0_5 at C3`() {
        for ((f0, pressure) in listOf(130.81f to 0.5f, 130.81f to 0.7f, 130.81f to 0.9f, 220f to 0.7f, 220f to 0.9f)) {
            val run = BowMeter.play(f0, beta, pressure, 0.5f, 1.5f)
            val (hz, peak, end) = steady(run, f0)
            val period = rate / hz
            val slips = BowMeter.slipsPerPeriod(run.bowPoint, end, period, run.vMax)
            val h = BowMeter.harmonics(run.out, (1.2 * rate).toInt(), (0.3 * rate).toInt(), hz)
            val (riseToFall, slowFraction) = BowMeter.sawtooth(run.out, end, period)
            println("BOW speaks $f0 Hz p $pressure: ${f(hz)} Hz ${f(BowMeter.cents(hz, f0.toDouble()), 1)} c ac ${f(peak)} slips ${f(slips, 1)} h2..h4 ${h.slice(1..3).joinToString("/") { f(it, 1) }} dB rise/fall ${f(riseToFall)} slow ${f(slowFraction)}")
            assertEquals(1.0, slips, 0.05, "$f0 Hz at pressure $pressure is not one slip a period")
            assertTrue(peak >= 0.9, "$f0 Hz at pressure $pressure does not repeat: autocorrelation $peak")
            assertTrue(h[1] in -6.5..-4.8 && h[2] in -10.0..-8.2 && h[3] in -12.4..-10.6, "the harmonics are not a sawtooth's (-6, -9.5, -12 dB): ${h.slice(1..3)}")
            assertTrue(riseToFall < 0.25 && slowFraction > 0.9, "the wave is not a sawtooth: its fastest rise is $riseToFall of its fastest fall, slow for $slowFraction of the period")
        }
    }

    @Test
    fun `STK's default pressure is not one slip at A3, so a speaking test must not use it`() {
        val run = BowMeter.play(220f, beta, 0.5f, 0.5f, 1.5f)
        val (hz, _, end) = steady(run, 220f)
        val slips = BowMeter.slipsPerPeriod(run.bowPoint, end, rate / hz, run.vMax)
        println("BOW pressure 0.5 at 220 Hz: slips ${f(slips, 1)}")
        assertEquals(2.0, slips, 0.05)
    }

    @Test
    fun `lifted from the start the string is exact silence`() {
        val run = BowMeter.play(130.81f, beta, 0.9f, 0.5f, 0.5f, liftAtStart = true)
        assertTrue(run.out.all { it == 0f } && run.bowPoint.all { it == 0f }, "a bow that never touched the string made sound")
    }

    // ---------------------------------------------------------------- it is in tune

    @Test
    fun `in tune within 5 cents at five roots at the pinned share`() {
        println("BOW cents at the pinned share, p 0.5 / 0.9:")
        var worst = 0.0
        for (f0 in listOf(65.41f, 130.81f, 220f, 440f, 880f)) {
            val row = listOf(0.5f, 0.9f).map { pressure ->
                val run = BowMeter.play(f0, beta, pressure, 0.5f, 1.5f)
                val (hz, peak, _) = steady(run, f0)
                assertTrue(peak >= 0.9, "$f0 Hz at pressure $pressure does not repeat: autocorrelation $peak")
                BowMeter.cents(hz, f0.toDouble())
            }
            println("BOW   $f0 Hz: ${row.joinToString(" / ") { f(it, 1) }} c")
            for (c in row) worst = maxOf(worst, abs(c))
            assertTrue(row.all { abs(it) <= 5.0 }, "$f0 Hz is ${row.map { f(it, 1) }} cents from its note")
        }
        println("BOW   worst ${f(worst, 1)} c")
    }

    @Test
    fun `without the share's correction the top notes are sharp`() {
        // tune takes the whole of the bridge filter's phase delay at the fundamental out of the loop, but a bowed string's
        // period is set by the Helmholtz corner, which arrives earlier: a full share is a loop one or two samples short.
        for (pressure in listOf(0.5f, 0.9f)) {
            val run = BowMeter.play(880f, beta, pressure, 0.5f, 1.5f, share = 1f)
            val cents = BowMeter.cents(steady(run, 880f).first, 880.0)
            println("BOW share 1.0 at 880 Hz, p $pressure: ${f(cents, 1)} c")
            assertTrue(cents > 5.0, "a full share should read sharp at 880 Hz (it does not, at pressure $pressure): $cents c")
        }
    }

    @Test
    fun `the cents-by-share table is printed, and the CELLO share does not hold an ERHU's top note`() {
        // The class KDoc quotes this table. The CELLO range's columns are held by the in-tune test above; here the table is
        // printed so it can be re-derived, and the one claim made about its last column is asserted: at D6 the share that holds
        // 65 to 880 Hz does not hold the note (+6.8 and +2.4 cents), while 0.8 does (+2.4 and -1.7), so an ERHU pins its own.
        val roots = listOf(65.41f, 130.81f, 220f, 440f, 880f, 1174.66f)
        val cents = HashMap<Pair<Float, Float>, List<Double>>()
        println("BOW cents by share, p 0.5 / 0.9, at ${roots.joinToString(" ")} Hz:")
        for (share in listOf(1f, 0.85f, 0.8f)) {
            val row = roots.map { f0 ->
                val c = listOf(0.5f, 0.9f).map { pressure ->
                    BowMeter.cents(steady(BowMeter.play(f0, beta, pressure, 0.5f, 1.5f, share = share), f0).first, f0.toDouble())
                }
                cents[share to f0] = c
                c.joinToString("/") { f(it, 1) }
            }
            println("BOW   share $share: ${row.joinToString("  ")}")
        }
        val top = cents.getValue(0.85f to 1174.66f)
        assertTrue(top[0] > 5.0, "the CELLO share should not hold D6 at pressure 0.5 (it reads ${top[0]} cents)")
        assertTrue(cents.getValue(0.8f to 1174.66f).all { abs(it) <= 5.0 }, "a share of 0.8 should hold D6: ${cents.getValue(0.8f to 1174.66f)}")
    }

    /** A bow built at [builtAt] and sounding at pressure 0.9; [step] is called each sample with the bow and the sample index (to retune it). */
    private fun played(builtAt: Float, seconds: Float, step: (Strings.Bow, Int) -> Unit): FloatArray {
        val bow = Strings.Bow(builtAt, beta, rate = rate)
        val slope = 5f - 4f * 0.9f
        val attack = (0.020 * rate).toInt()
        return FloatArray((seconds * rate).toInt()) { i ->
            step(bow, i)
            bow.next(0.13f * (if (i < attack) i.toFloat() / attack else 1f), slope)
        }
    }

    private fun steepestStep(x: FloatArray, fromSeconds: Double, toSeconds: Double): Float {
        var m = 0f
        for (i in (fromSeconds * rate).toInt() until (toSeconds * rate).toInt()) m = maxOf(m, abs(x[i] - x[i - 1]))
        return m
    }

    @Test
    fun `a retune the size of a vibrato does not click, and a glide lands in tune`() {
        // A delay line that changes length jumps in the waveform it reads, so an instant retune of a few semitones clicks
        // (measured: 30 times the steepest step of the steady note); what retune is for is vibrato's few cents and a glide's
        // small steps every 64 samples, and those are what is held here.
        val hop = 64
        val centsStep = played(130.81f, 2f) { bow, i -> if (i == rate) bow.retune(130.81f * 1.0058f) }
        val stepBefore = steepestStep(centsStep, 0.9, 1.0)
        val stepAfter = steepestStep(centsStep, 1.0, 1.01)
        val glideLen = (0.3 * rate).toInt()
        val ratio = 130.81 / 110.0
        val glide = played(110f, 2f) { bow, i ->
            if (i >= rate && i < rate + glideLen && (i - rate) % hop == 0) bow.retune((110.0 * ratio.pow((i - rate).toDouble() / glideLen)).toFloat())
            if (i == rate + glideLen) bow.retune(130.81f)
        }
        // A sawtooth's flyback is steeper the higher it plays, so the fair reference for a glide is the steadier of its two ends.
        val glideReference = maxOf(steepestStep(glide, 0.9, 1.0), steepestStep(glide, 1.6, 1.9))
        val glideDuring = steepestStep(glide, 1.0, 1.3)
        val hz = BowMeter.pitch(glide, (1.6 * rate).toInt(), (0.3 * rate).toInt(), 130.81f).first
        val cents = BowMeter.cents(hz, 130.81)
        println("BOW retune: a 10 cent step, steepest step before ${f(stepBefore.toDouble(), 4)} after ${f(stepAfter.toDouble(), 4)}; a glide 110 -> 130.81 Hz over 0.3 s in $hop-sample hops: steadier end ${f(glideReference.toDouble(), 4)} during ${f(glideDuring.toDouble(), 4)}, lands ${f(hz)} Hz ${f(cents, 1)} c")
        assertTrue(stepAfter <= 2f * stepBefore, "a 10 cent retune clicked: ${stepAfter} after, ${stepBefore} before")
        assertTrue(glideDuring <= 2f * glideReference, "the glide clicked: ${glideDuring} during, ${glideReference} at its steadier end")
        assertTrue(abs(cents) <= 5.0, "the glide landed $cents cents from its note")
    }

    // ---------------------------------------------------------------- it lifts, and it rings

    private fun onePoleMagnitude(f0: Float, hz: Float): Double {
        val a = 1.0 - exp(-2.0 * PI * min(hz, rate * 0.45f) / rate)
        val r = 1.0 - a
        val w = 2.0 * PI * f0 / rate
        return a / sqrt(1 - 2 * r * cos(w) + r * r)
    }

    private fun dbPerPeriod(x: FloatArray, f0: Float, from: Double, to: Double): Double {
        val window = (0.020 * rate).toInt()
        val a = BowMeter.rms(x, (from * rate).toInt(), (from * rate).toInt() + window)
        val b = BowMeter.rms(x, (to * rate).toInt(), (to * rate).toInt() + window)
        return 20.0 * log10(b / a) / ((to - from) * f0)
    }

    private fun secondsToMinus60(x: FloatArray, from: Double): Double {
        val window = (0.02 * rate).toInt()
        val ref = BowMeter.rms(x, (from * rate).toInt(), (from * rate).toInt() + window)
        var i = (from * rate).toInt()
        while (i < x.size - window && BowMeter.rms(x, i, i + window) > ref * 0.001) i += (0.01 * rate).toInt()
        return i / rate.toDouble() - from
    }

    @Test
    fun `a lifted bow rings down at the formula's slope, and a bow left resting rings much longer`() {
        val f0 = 130.81f
        val lifted = BowMeter.play(f0, beta, 0.9f, 0.5f, 3f, releaseAt = 1f)
        // long enough for the resting string to reach -60 dB inside the buffer: a cut-off crossing would be the buffer's length, not a ring time
        val resting = BowMeter.play(f0, beta, 0.9f, 0.5f, 8f, releaseAt = 1f, noLift = true)
        val formula = 20.0 * log10(Strings.Bow.REFLECTION * onePoleMagnitude(f0, Strings.Bow.BRIDGE_HZ))
        val liftedSlope = dbPerPeriod(lifted.out, f0, 1.2, 1.8)
        val restingSlope = dbPerPeriod(resting.out, f0, 1.2, 1.8)
        val liftedT60 = secondsToMinus60(lifted.out, 1.15)
        val restingT60 = secondsToMinus60(resting.out, 1.15)
        println("BOW ring-down at 130.81 Hz: lifted ${f(liftedSlope, 3)} dB per period (formula ${f(formula, 3)}), -60 dB in ${f(liftedT60)} s; resting bow ${f(restingSlope, 3)} dB per period, ${f(restingT60)} s")
        assertEquals(formula, liftedSlope, abs(formula) * 0.10, "a lifted string does not ring down at the loop's own loss")
        assertTrue(restingT60 < 8.0 - 1.15 - 0.1, "the resting string never reached -60 dB inside the buffer, so its ring time is not measured: $restingT60 s")
        assertTrue(restingT60 > 1.5 * liftedT60, "a bow left resting should keep ringing: ${restingT60} s against ${liftedT60} s lifted")
    }

    @Test
    fun `gain scales the bridge's loss and nothing else, and gain 1 restores it exactly`() {
        fun ring(scale: Float?): FloatArray {
            val bow = Strings.Bow(130.81f, beta, rate = rate)
            val out = FloatArray((2.5 * rate).toInt())
            val slope = 5f - 4f * 0.9f
            val attack = (0.020 * rate).toInt()
            val liftAt = rate
            for (i in out.indices) {
                val env = if (i < attack) i.toFloat() / attack else 1f
                if (i == liftAt) {
                    bow.lift()
                    if (scale != null) bow.gain(scale)
                }
                out[i] = bow.next(if (i < liftAt) 0.13f * env else 0f, slope)
            }
            return out
        }
        val plain = ring(null)
        val damped = ring(0.9f)
        val restored = FloatArray(plain.size).also {
            val bow = Strings.Bow(130.81f, beta, rate = rate)
            bow.gain(0.9f)
            bow.gain(1f)
            val slope = 5f - 4f * 0.9f
            val attack = (0.020 * rate).toInt()
            for (i in it.indices) {
                val env = if (i < attack) i.toFloat() / attack else 1f
                if (i == rate) bow.lift()
                it[i] = bow.next(if (i < rate) 0.13f * env else 0f, slope)
            }
        }
        // The wave that leaves the bow toward the bridge is the nut's, for about 0.87 of a period after the lift; a gain on the bridge
        // cannot touch it yet, and a gain that landed on the nut would change it from the first sample.
        assertContentEquals(plain.copyOfRange(rate, rate + 1000), damped.copyOfRange(rate, rate + 1000), "gain reached the nut segment: the first 1000 samples after the lift moved")
        val extra = dbPerPeriod(damped, 130.81f, 1.3, 1.9) - dbPerPeriod(plain, 130.81f, 1.3, 1.9)
        println("BOW gain 0.9: ${f(extra, 3)} dB per period more than the string's own (want ${f(20.0 * log10(0.9), 3)})")
        assertEquals(20.0 * log10(0.9), extra, 0.15, "gain(0.9) should cost the bridge 0.9 of its reflection a period")
        assertContentEquals(plain, restored, "gain(1) after a gain(0.9) is not the untouched bow")
    }

    // ---------------------------------------------------------------- it is bounded

    @Test
    fun `bounded, finite, DC-free and under the ceiling across pressure, amplitude, bow position and pitch`() {
        var peak = 0f
        var peakAt = ""
        var bowPointPeak = 0f
        var worstMean = 0.0
        var cells = 0
        val started = System.nanoTime()
        fun cell(f0: Float, pressure: Float, amplitude: Float, b: Float) {
            val run = BowMeter.play(f0, b, pressure, amplitude, 1.5f)
            assertTrue(run.out.all { it.isFinite() } && run.bowPoint.all { it.isFinite() }, "non-finite at $f0 Hz, p $pressure, amplitude $amplitude, beta $b")
            val p = BowMeter.maxAbs(run.out)
            if (p > peak) {
                peak = p
                peakAt = "$f0 Hz p $pressure amplitude $amplitude beta $b"
            }
            bowPointPeak = maxOf(bowPointPeak, BowMeter.maxAbs(run.bowPoint))
            worstMean = maxOf(worstMean, abs(BowMeter.mean(run.out, (0.5 * rate).toInt(), run.out.size)))
            cells++
        }
        for (pressure in listOf(0.3f, 0.7f, 1f)) for (amplitude in listOf(0.2f, 0.5f, 1f)) for (b in listOf(0.08f, 0.133f, 0.3f)) cell(130.81f, pressure, amplitude, b)
        for (f0 in listOf(65.41f, 880f)) for (b in listOf(0.133f, 0.3f)) cell(f0, 1f, 1f, b)
        val seconds = (System.nanoTime() - started) / 1e9
        println("BOW bounded, $cells cells: raw peak ${f(peak.toDouble(), 3)} ($peakAt), string velocity under the bow ${f(bowPointPeak.toDouble(), 3)}, worst mean ${f(worstMean, 4)}; ${f(seconds / (cells * 1.5) * 1000, 1)} ms per rendered second")
        assertTrue(peak < Strings.Bow.RAW_PEAK_CEILING, "a raw peak of $peak ($peakAt) is over the ceiling ${Strings.Bow.RAW_PEAK_CEILING}")
        assertTrue(worstMean < 0.008, "the string is not zero-mean: $worstMean")
    }

    // ---------------------------------------------------------------- it is built at the top of a range, or refused

    private fun bridgeDelay(f0: Float, hz: Float): Double {
        val a = 1.0 - exp(-2.0 * PI * min(hz, rate * 0.45f) / rate)
        val r = 1.0 - a
        val w = 2.0 * PI * f0 / rate
        return atan2(r * sin(w), 1.0 - r * cos(w)) / w
    }

    @Test
    fun `the bow position has a floor at the top of a range, and a bow under it is refused naming the cause`() {
        val top = 1174.66f
        val period = (rate / top).toDouble()
        val floor = (Strings.MIN_LOOP_SAMPLES + 0.5 + bridgeDelay(top, Strings.Bow.BRIDGE_HZ)) / period
        println("BOW position floor at ${top} Hz, corner ${Strings.Bow.BRIDGE_HZ} Hz: ${f(floor, 4)}")
        Strings.Bow(top, 0.075f, rate = rate)
        val e = assertFailsWith<IllegalArgumentException> { Strings.Bow(top, 0.070f, rate = rate) }
        assertTrue("Karplus-Strong minimum" in (e.message ?: ""), "the refusal does not name the cause: ${e.message}")
        assertTrue(floor > 0.070 && floor < 0.075, "the floor arithmetic moved: $floor")
        // a lower corner raises the floor: at 1500 Hz a bow at 0.133 still builds, at 1000 Hz it does not
        Strings.Bow(top, 0.133f, bridgeHz = 1500f, rate = rate)
        assertFailsWith<IllegalArgumentException> { Strings.Bow(top, 0.133f, bridgeHz = 1000f, rate = rate) }
    }

    @Test
    fun `a bow refuses a position or a share that is not a fraction`() {
        // the message is the bow's own: tune would refuse a bad position too, but under a name that blames a round trip
        for (bad in listOf(0f, 1f, -0.1f, 1.5f, Float.NaN)) {
            val e = assertFailsWith<IllegalArgumentException>("beta $bad") { Strings.Bow(130.81f, bad, rate = rate) }
            assertTrue("beta must be in (0, 1)" in (e.message ?: ""), "beta $bad was refused by something other than the bow's own guard: ${e.message}")
        }
        for (bad in listOf(-0.1f, 1.1f, Float.NaN)) {
            val e = assertFailsWith<IllegalArgumentException>("share $bad") { Strings.Bow(130.81f, beta, share = bad, rate = rate) }
            assertTrue("share is a fraction" in (e.message ?: ""), "share $bad was refused by something other than the bow's own guard: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- the table and the repeat

    @Test
    fun `the reflection table is STK's - a plateau at the top, free slip at the floor, and one clamp that can move`() {
        val slope = 3f
        assertEquals(Strings.Bow.RHO_MAX, Strings.Bow.rho(0f, slope))
        assertEquals(Strings.Bow.RHO_MAX, Strings.Bow.rho(0.08f, slope))
        assertEquals(Strings.Bow.RHO_MIN, Strings.Bow.rho(0.85f, slope))
        assertEquals(Strings.Bow.RHO_MIN, Strings.Bow.rho(-0.85f, slope))
        var last = 1f
        for (k in 0..40) {
            val r = Strings.Bow.rho(k * 0.025f, slope)
            assertTrue(r <= last, "the table rises with the speed difference at ${k * 0.025f}")
            last = r
        }
        assertTrue(Strings.Bow.rho(0f, slope, rhoMax = 1f) > Strings.Bow.RHO_MAX, "rhoMax is not a runtime knob")
        // two points of STK's formula, one on each side of zero (the offset 0.001 makes the table slightly asymmetric)
        assertEquals(0.133939f, Strings.Bow.rho(0.3f, 3f), 1e-4f)
        assertEquals(0.135902f, Strings.Bow.rho(-0.3f, 3f), 1e-4f)
        // pressure 0 is slope 5 and still has a plateau: only lifting stops the bow
        assertEquals(Strings.Bow.RHO_MAX, Strings.Bow.rho(0f, 5f))
    }

    @Test
    fun `two bows render the same bits and share no state`() {
        val a = BowMeter.play(196f, beta, 0.8f, 0.5f, 0.6f)
        val b = BowMeter.play(196f, beta, 0.8f, 0.5f, 0.6f)
        assertContentEquals(a.out, b.out)
        assertContentEquals(a.bowPoint, b.bowPoint)
    }
}
