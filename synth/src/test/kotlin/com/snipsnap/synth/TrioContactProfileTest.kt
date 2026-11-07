package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Numerical source/score contracts. A listening comparison decides the contact character. */
class TrioContactProfileTest {
    private val config = TrioConfig(seed = 47010, model = 6)
    private val macros = Trio.defaults(TrioVoice.ENSEMBLE) + mapOf(
        "DRAMA" to 1f, "SIDES" to (1f / 6f), "TOUCH" to 1f,
    )
    private val companions = setOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)

    private fun response(kernel: FloatArray, hz: Double, rate: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (i in kernel.indices) {
            val angle = 2.0 * PI * hz * i / rate
            real += kernel[i] * cos(angle)
            imaginary -= kernel[i] * sin(angle)
        }
        return sqrt(real * real + imaginary * imaginary)
    }

    private fun assertProbeEquals(expected: TrioProbe, actual: TrioProbe) {
        assertEquals(expected.events, actual.events)
        assertEquals(expected.actors.keys, actual.actors.keys)
        assertContentEquals(expected.samples, actual.samples)
        for (actor in expected.actors.keys)
            assertContentEquals(expected.actors.getValue(actor), actual.actors.getValue(actor), actor.name)
    }

    @Test
    fun `the contact is a bounded opposing FIR timed by frequency rather than buffer length`() {
        assertEquals(0f, TrioContactProfile.amount(.8f))
        assertEquals(0f, TrioContactProfile.amount(0f))
        assertEquals(.4f, TrioContactProfile.amount(.9f), 1e-6f)
        assertEquals(.8f, TrioContactProfile.amount(1f))
        val source = FloatArray(600).also { it[0] = 1f }
        assertSame(source, TrioContactProfile.shape(source, 350f, 176400, 0f))
        for (hz in listOf(343.7f, 685.1f)) {
            val delay = (176400.0 / (3.0 * hz)).roundToInt()
            val shaped = TrioContactProfile.shape(source, hz, 176400, .8f)
            assertEquals(source.size + delay, shaped.size)
            assertEquals(1f, shaped[0])
            assertEquals(-.8f, shaped[delay])
            assertEquals(2, shaped.count { it != 0f })
            for (step in 0..120) {
                val gain = response(shaped, step * 176400.0 / 240, 176400)
                assertTrue(gain in .199999..1.800001, "frequency response exceeds the two-tap bound: $gain")
            }
        }
        assertContentEquals(FloatArray(600).also { it[0] = 1f }, source, "contact mutated a reusable exciter")
        assertContentEquals(floatArrayOf(1f, -.8f), TrioContactProfile.shape(floatArrayOf(1f), 100000f, 44100, .8f))
    }

    @Test
    fun `the appended tail retains the full convolution and a zero mean excitation`() {
        val source = floatArrayOf(.5f, -.25f, -.25f)
        val shaped = TrioContactProfile.shape(source, 4000f, 48000, .8f)
        assertContentEquals(floatArrayOf(.5f, -.25f, -.25f, 0f, -.4f, .2f, .2f), shaped)
        assertTrue(abs(shaped.sumOf { it.toDouble() }) < 1e-7, "truncating the delayed tail leaves a DC impulse")
        val biased = floatArrayOf(.5f, .25f, -.125f)
        val complete = TrioContactProfile.shape(biased, 4000f, 48000, .8f)
        assertEquals((1.0 - .8f) * biased.sumOf { it.toDouble() }, complete.sumOf { it.toDouble() }, 1e-7)
        assertContentEquals(floatArrayOf(.5f, -.25f, -.25f), source)
    }

    @Test
    fun `the independent harmonic response predicts a hollow upper touch without level compensation`() {
        // An exact 168-sample delay makes the intended root/third relation unambiguous.
        val rate = 176400
        val root = 350.0
        for ((touch, expectedDb) in listOf(.9f to -6.3682, 1f to -17.8533)) {
            val kernel = TrioContactProfile.shape(floatArrayOf(1f), root.toFloat(), rate, TrioContactProfile.amount(touch))
            val rootGain = response(kernel, root, rate)
            val thirdGain = response(kernel, 3.0 * root, rate)
            val relativeDb = 20.0 * log10(thirdGain / rootGain)
            assertEquals(expectedDb, relativeDb, .01, "TOUCH $touch third/root dB")
            assertTrue(rootGain in 1.0..1.8, "contact compensation boosted the root beyond the FIR budget")
        }
    }

    @Test
    fun `lower touch bypass preserves raw mix actor taps and every gesture bit exactly`() {
        for (touch in listOf(.55f, .8f)) {
            val controls = macros + ("TOUCH" to touch)
            assertProbeEquals(Trio.renderProbe(TrioVoice.ENSEMBLE, controls, config = config),
                TrioContactProfile.render(controls, config = config))
        }
    }

    @Test
    fun `all six transformed sources keep the score and cannot contaminate ordinary model six replay`() {
        val score = Trio.score(TrioVoice.ENSEMBLE, macros, 60, 1f, config)
        val courses = score.courses.toList()
        val ordinary = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = config)
        val candidate = TrioContactProfile.render(macros, config = config)
        assertEquals(TrioActor.entries.toSet(), candidate.actors.keys)
        assertEquals(ordinary.events, candidate.events, "source contact must not change gesture times or energy")
        assertSame(score, Trio.score(TrioVoice.ENSEMBLE, macros, 60, 1f, config))
        assertEquals(courses, score.courses, "rendering mutated the cached score")
        for (actor in TrioActor.entries) {
            assertEquals(ordinary.actors.getValue(actor).size, candidate.actors.getValue(actor).size)
            assertFalse(ordinary.actors.getValue(actor).contentEquals(candidate.actors.getValue(actor)), "$actor bypassed contact")
        }
        for (actor in companions) assertEquals(1, candidate.events.count { it.actor == actor }, "$actor must remain a single hit")
        val summedTaps = FloatArray(candidate.samples.size)
        for (tap in candidate.actors.values) for (i in summedTaps.indices) summedTaps[i] += tap[i]
        assertContentEquals(candidate.samples, summedTaps, "diagnostic taps must sum to the raw mix in actor order")
        // Neither candidate output ownership nor another cached score may leak into a normal render.
        candidate.samples.fill(123f)
        candidate.actors.values.forEach { it.fill(-123f) }
        Trio.score(TrioVoice.ENSEMBLE, macros + ("TOUCH" to .9f), 72, .5f, config.copy(seed = -91827))
        assertProbeEquals(ordinary, Trio.renderProbe(TrioVoice.ENSEMBLE, macros, config = config))
    }

    @Test
    fun `maximum contact stays finite with raw headroom at register and damping corners`() {
        val dense = config.copy(tempoBpm = 120f, beats = 4, press = 1f, tremoloHz = 5f, strumMs = 30f)
        for (midi in listOf(48, 84)) for (damp in listOf(0f, 1f)) {
            val probe = TrioContactProfile.render(macros + mapOf("DAMP" to damp, "MOTION" to 1f,
                "SIDES" to (2f / 3f), "SPACE" to 1f), midi, config = dense)
            assertEquals((3.5f * Dsp.RATE).toInt(), probe.samples.size)
            assertTrue(probe.samples.all { it.isFinite() && abs(it) < .99f }, "MIDI $midi / DAMP $damp raw headroom")
            assertTrue(abs(probe.samples.average()) < 1e-3, "MIDI $midi / DAMP $damp DC")
            assertTrue(probe.samples.any { abs(it) > 1e-5f }, "MIDI $midi / DAMP $damp silent")
            for (actor in companions) assertEquals(1, probe.events.count { it.actor == actor })
            for ((actor, tap) in probe.actors) {
                assertEquals(probe.samples.size, tap.size, actor.name)
                assertTrue(tap.all { it.isFinite() }, actor.name)
            }
        }
    }

    @Test
    fun `invalid contact inputs are refused before a nonfinite burst reaches a string`() {
        assertFailsWith<IllegalArgumentException> { TrioContactProfile.amount(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { TrioContactProfile.shape(floatArrayOf(1f), 0f, 44100, .8f) }
        assertFailsWith<IllegalArgumentException> { TrioContactProfile.shape(floatArrayOf(1f), 440f, 0, .8f) }
        assertFailsWith<IllegalArgumentException> { TrioContactProfile.shape(floatArrayOf(1f), 440f, 44100, 1f) }
        assertFailsWith<IllegalArgumentException> { TrioContactProfile.shape(floatArrayOf(Float.NaN), 440f, 44100, .8f) }
    }
}
