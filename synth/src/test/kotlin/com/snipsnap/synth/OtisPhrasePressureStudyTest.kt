package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A separate source experiment, not a migration of the accepted SQUEEZE renderer. */
class OtisPhrasePressureStudyTest {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE
    private val study by lazy { OtisPhrasePressureStudy.render(rate = rate) }
    private val plain = OtisAirSettings(musette = 0f, bellowsPulse = 0f)
    private val spectralStudy by lazy { OtisPhrasePressureStudy.render(settings = plain, rate = rate) }

    @Test
    fun `shared demand softens pressure during overlap and refills while the first key stays down`() {
        val trace = study.trace
        assertTrue(trace.controlRateHz in 990.0..1010.0, "study does not use its declared roughly1kHz clock")
        assertTrue(trace.pressure.all { it.isFinite() && it in OtisPhrasePressureStudy.PRESSURE_FLOOR..1.0 })
        assertTrue(trace.demand.all { it.isFinite() && it >= 0.0 })
        assertEquals(1.0, trace.pressureAt(.35), "one reference voice already loses its pressure")
        val crowded = trace.pressureAt(.90)
        val recovered = trace.pressureAt(1.65)
        assertTrue(crowded in .73.. .80, "second key does not consume available pressure: $crowded")
        assertTrue(recovered > .95 && recovered > crowded + .15,
            "pressure did not refill under the still-held C4: $crowded -> $recovered")
        val lastMatching = (.42 * rate).toInt()
        assertContentEquals(study.independent.copyOfRange(0, lastMatching), study.shared.copyOfRange(0, lastMatching),
            "the independent and shared sources differ before a second key draws air")
    }

    @Test
    fun `zero coupling and one reference voice are exact accepted-source bypasses`() {
        val off = OtisPhrasePressureStudy.render(strength = 0f, rate = rate)
        assertContentEquals(off.independent, off.shared, "zero coupling changed accepted source samples")
        assertTrue(off.trace.pressure.all { it == 1.0 })
        val one = OtisPhrasePressureStudy.render(notes = listOf(OtisPrototypeNote(60,
            gateSeconds = 1.8, velocity = .8f)), rate = rate)
        assertContentEquals(one.independent, one.shared, "a lone reference voice is not an exact bypass")
        assertTrue(one.trace.pressure.all { it == 1.0 })
    }

    @Test
    fun `upper reed harmonics change relative to the fundamental after RMS matching`() {
        val result = spectralStudy
        val independent = Dsp.decimate(result.independent, Dsp.RATE)
        val shared = Dsp.decimate(result.shared, Dsp.RATE)
        val root = 440 * 2.0.pow((60 - 69) / 12.0)
        fun upperShare(x: FloatArray): Double {
            val level = rms(x, .70, 1.0)
            val matched = FloatArray(x.size) { (x[it] / level).toFloat() }
            return (3..9 step 2).sumOf { power(matched, root * it, .70, 1.0) } /
                power(matched, root, .70, 1.0)
        }
        val a = upperShare(independent)
        val b = upperShare(shared)
        assertTrue(a > .05 && b < .70 * a, "the shared player is only a gain effect: upper/root $a -> $b")
        assertTrue(rms(shared, .70, 1.0) > .4 * rms(independent, .70, 1.0), "pressure collapses the source into silence")
    }

    @Test
    fun `source pressure does not retune either held pitch`() {
        val independent = Dsp.decimate(spectralStudy.independent, Dsp.RATE)
        val shared = Dsp.decimate(spectralStudy.shared, Dsp.RATE)
        for (midi in listOf(60, 63)) {
            val root = 440 * 2.0.pow((midi - 69) / 12.0)
            val a = measuredCents(independent, root)
            val b = measuredCents(shared, root)
            assertTrue(abs(a) <= 2 && abs(b) <= 2 && abs(a - b) <= 1,
                "available pressure retunes MIDI$midi: $a -> $b cents")
        }
    }

    @Test
    fun `seeded experiment reproduces finite audio and retains natural quiet tails`() {
        val repeated = OtisPhrasePressureStudy.render(rate = rate)
        assertContentEquals(study.independent, repeated.independent)
        assertContentEquals(study.shared, repeated.shared)
        assertContentEquals(study.trace.pressure, repeated.trace.pressure)
        for (buffer in listOf(study.independent, study.shared)) {
            assertTrue(buffer.all { it.isFinite() && abs(it) < 1.5f }, "two-key source requires a clipping guard")
            assertTrue(buffer.takeLast(256).all { abs(it) < 1e-6 }, "source release was audibly cut")
        }
        val silentNotes = OtisPhrasePressureStudy.notes().map { it.copy(velocity = 0f) }
        val silent = OtisPhrasePressureStudy.render(notes = silentNotes, rate = rate)
        assertTrue(silent.shared.all { it == 0f } && silent.trace.pressure.all { it == 1.0 })
    }

    @Test
    fun `bounded control state converges with twice its update rate and many keys cannot exhaust it`() {
        val faster = OtisPhrasePressureStudy.pressureTrace(study.notes, study.settings,
            study.shared.size, study.strength, rate, 2000)
        val errors = (0..2600).map { abs(study.trace.pressureAt(it / 1000.0) - faster.pressureAt(it / 1000.0)) }
        assertTrue(errors.max() < .005, "pressure trajectory depends on coarse numerical timing: ${errors.max()}")
        val dense = (0 until 32).map { OtisPrototypeNote(48 + it, gateSeconds = 1.0, velocity = 1f) }
        val trace = OtisPhrasePressureStudy.pressureTrace(dense, OtisAirSettings(), (2 * rate), 1f, rate, 1000)
        assertTrue(trace.pressure.min() in OtisPhrasePressureStudy.PRESSURE_FLOOR.. .401,
            "high demand crossed the bounded player floor: ${trace.pressure.min()}")
        assertTrue(trace.pressure.all { it.isFinite() && it in OtisPhrasePressureStudy.PRESSURE_FLOOR..1.0 })
    }

    @Test
    fun `manifest pins experiment controls and invalid strength is refused`() {
        val saved = study.manifest()
        assertEquals("snipsnap-otis-phrase-pressure-study", saved["format"])
        assertEquals(1, saved["experimentVersion"])
        assertEquals(study.settings.asMap(), saved["air"])
        assertEquals(study.rate, saved["rate"])
        assertEquals(2, (saved["notes"] as List<*>).size)
        assertFailsWith<IllegalArgumentException> { OtisPhrasePressureStudy.render(strength = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { OtisPhrasePressureStudy.render(strength = 1.01f) }
    }

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val first = (from * Dsp.RATE).toInt()
        val end = (to * Dsp.RATE).toInt()
        return sqrt((first until end).sumOf { x[it].toDouble() * x[it] } / (end - first))
    }

    private fun power(x: FloatArray, hz: Double, from: Double, to: Double): Double {
        val first = (from * Dsp.RATE).toInt()
        val count = ((to - from) * Dsp.RATE).toInt()
        var re = 0.0
        var im = 0.0
        for (index in 0 until count) {
            val v = x[first + index] * (.5 - .5 * cos(2 * PI * index / (count - 1)))
            val phase = 2 * PI * hz * index / Dsp.RATE
            re += v * cos(phase)
            im += v * sin(phase)
        }
        return re * re + im * im
    }

    private fun measuredCents(x: FloatArray, root: Double): Int = (-10..10).maxBy {
        power(x, root * 2.0.pow(it / 1200.0), .70, 1.0)
    }
}
