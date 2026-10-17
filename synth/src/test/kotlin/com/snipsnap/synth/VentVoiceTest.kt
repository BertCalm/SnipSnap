package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VentVoiceTest {
    private val voices = listOf(VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME)

    @Test
    fun `clean voices share controls and have strictly separate model recipes`() {
        val expected = mapOf("CHAOS" to .65f, "LEASH" to .55f, "VIEW" to .5f,
            "DAMP" to .25f, "PUSH" to .6f, "LENGTH" to .74f)
        for (voice in voices) {
            assertEquals(expected, Vent.defaultsFor(voice))
            assertEquals(2, Vent.defaultModelFor(voice))
            assertTrue(Vent.supportsVoiceModel(voice, 2))
            assertFalse(Vent.supportsVoiceModel(voice, 1))
            assertFailsWith<IllegalArgumentException> { Vent.render(voice, model = 1) }
        }
        for (voice in listOf(VentVoice.ROUND, VentVoice.WIRE, VentVoice.TEAR)) {
            assertEquals(1, Vent.defaultModelFor(voice))
            assertFailsWith<IllegalArgumentException> { Vent.render(voice, model = 2) }
        }
        assertEquals(1, Vent.MODEL)
        assertTrue(Vent.supportsModel(1) && Vent.supportsModel(2))
        assertFalse(Vent.supportsModel(3))
    }

    @Test
    fun `clean projection is linear and moving view exposes actual height`() {
        for (voice in voices) for (view in listOf(0.0, .5, 1.0)) {
            val p = VentLinearProjection.forVoice(voice, view, .35)
            assertEquals(1.0, p.x*p.x+p.y*p.y+p.z*p.z, 1e-12)
            val a = p.readout(1.2, -2.3, 4.5)
            val b = p.readout(-3.4, 2.2, 5.6)
            assertEquals(a+b, p.readout(-2.2, -.1, 10.1), 1e-14)
            assertEquals(a*20, p.readout(24.0, -46.0, 90.0), 1e-14)
            assertEquals(VentLinearProjection.GAIN*p.z, p.readout(0.0, 0.0, 60.0), 1e-14)
        }
        val start = VentLinearProjection.forVoice(VentVoice.COIL, .5, 0.0)
        val end = VentLinearProjection.forVoice(VentVoice.COIL, .5, 1.0)
        assertTrue(end.z > start.z*4, "COIL motion should expose height, not only rotate X/Y")
        assertTrue(start.distanceTo(end) > 1.0)

        val normal = Vent.renderDetailed(VentVoice.COIL, velocity = .8f, seed = 17)
        val changed = Vent.renderDetailed(VentVoice.COIL, mapOf("VIEW" to 1f), velocity = .8f, seed = 17)
        assertEquals(normal.trace, changed.trace, "VIEW must only change the linear readout")
        assertTrue(normalizedDistance(normal.raw, changed.raw) > .1, "VIEW only changes gain")
        assertTrue(normal.diagnostics.projectionSpan > 1.0)
    }

    @Test
    fun `push and velocity change the actual orbit and zero push removes added gesture`() {
        val autonomous = ArrayList<VentRender>()
        for (voice in voices) {
            val full = Vent.renderDetailed(voice, velocity = .8f, seed = 17)
            val replay = Vent.renderDetailed(voice, velocity = .8f, seed = 17)
            val noPush = Vent.renderDetailed(voice, mapOf("PUSH" to 0f), velocity = .8f, seed = 17)
            val soft = Vent.renderDetailed(voice, velocity = .25f, seed = 17)
            val noPushSoft = Vent.renderDetailed(voice, mapOf("PUSH" to 0f), velocity = .25f, seed = 17)
            autonomous.add(noPush)
            assertContentEquals(full.raw, replay.raw)
            assertContentEquals(full.snip.samples, replay.snip.samples)
            assertEquals(full.trace, replay.trace)
            assertEquals(full.diagnostics, replay.diagnostics)
            assertEquals("clean-linear", full.diagnostics.signalPath)
            assertEquals(0.0, noPush.diagnostics.externalForceIntegral)
            assertTrue(full.diagnostics.externalForceIntegral > soft.diagnostics.externalForceIntegral)
            assertFalse(full.trace == noPush.trace, "$voice PUSH has no trajectory effect")
            assertFalse(full.trace == soft.trace, "$voice velocity has no trajectory effect")
            assertEquals(noPush.trace, noPushSoft.trace, "$voice hides velocity dynamics outside the gesture")
            assertTrue(normalizedDistance(full.raw, noPush.raw) > .005, "$voice PUSH only changes gain")
            assertTrue(normalizedDistance(full.raw, soft.raw) > .005, "$voice velocity only changes gain")
        }
        assertEquals(autonomous[0].trace, autonomous[1].trace)
        assertEquals(autonomous[0].trace, autonomous[2].trace)
    }

    @Test
    fun `voices differ in time spectral shape at identical controls and coil changes overtone balance`() {
        val hz = Keys.midiHz(60).toDouble()
        val renders = voices.associateWith { Vent.renderDetailed(it, velocity = .8f, seed = 17) }
        val frames = renders.mapValues { CorollaTimbreMetrics.frames(it.value.raw, hz, .08, 2.0) }
        for (i in voices.indices) for (j in i+1 until voices.size) {
            val distance = CorollaTimbreMetrics.distance(frames.getValue(voices[i]), frames.getValue(voices[j]))
            // Unit-power spectral frames remove gain and waveform phase as explanations.
            // This is an engineering distinction gate; musical identity still needs listening.
            assertTrue(distance > .015, "${voices[i]}/${voices[j]} spectral distinction collapsed ($distance)")
        }
        val coil = renders.getValue(VentVoice.COIL)
        val early = CorollaTimbreMetrics.frames(coil.raw, hz, .15, .75)
        val late = CorollaTimbreMetrics.frames(coil.raw, hz, 1.7, 2.3)
        assertTrue(CorollaTimbreMetrics.distance(early, late) > .015,
            "COIL projection motion changes only amplitude/phase")
    }

    @Test
    fun `long high register motion remains bounded with fully open readout`() {
        for (voice in voices) {
            val result = Vent.renderDetailed(voice, mapOf("CHAOS" to 1f, "LEASH" to 0f,
                "VIEW" to 1f, "DAMP" to 0f, "PUSH" to 1f, "LENGTH" to 1f), midi = 84, seed = 173)
            assertEquals(8*44_100, result.raw.size)
            assertEquals(0, result.diagnostics.integrationFailures)
            assertEquals(0, result.diagnostics.leashResets)
            assertTrue(result.diagnostics.modelTimeStep in 0.0.. .02)
            assertTrue(result.diagnostics.rawRms > .001)
            assertTrue(result.raw.all { it.isFinite() && abs(it) < 1f })
            assertTrue(result.snip.samples.all { it.isFinite() && abs(it) < 1f })
        }
        assertEquals((.18*44_100).toInt(), Vent.render(VentVoice.PULSE, mapOf("LENGTH" to 0f)).frameCount)
    }

    @Test
    fun `clean cancellation and invalid controls are rejected before audio`() {
        for (velocity in listOf(-.1f, 1.1f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException> { Vent.render(VentVoice.PULSE, velocity = velocity) }
        }
        assertFailsWith<IllegalArgumentException> { Vent.render(VentVoice.COIL, mapOf("VIEW" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { Vent.render(VentVoice.PLUME, midi = 85) }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { Vent.render(VentVoice.COIL) }
        } finally {
            Thread.interrupted()
        }
    }

    private fun normalizedDistance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        fun rms(x: FloatArray) = sqrt((0 until n).sumOf { x[it].toDouble()*x[it] }/n).coerceAtLeast(1e-12)
        val ra = rms(a); val rb = rms(b)
        return sqrt((0 until n).sumOf { val d = a[it]/ra-b[it]/rb; d*d }/n)
    }
}
