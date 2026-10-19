package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VentIsolationTest {
    @Test
    fun `projection preserves linear state scaling without Z compression or saturation`() {
        // Independently specified contributions at the experiment's reference coordinate scales.
        assertEquals(.1625, VentIsolationSpec.readout(12.0, 0.0, 0.0), 1e-15)
        assertEquals(.0375, VentIsolationSpec.readout(0.0, 12.0, 0.0), 1e-15)
        assertEquals(.075, VentIsolationSpec.readout(0.0, 0.0, 60.0), 1e-15)
        assertEquals(.75, VentIsolationSpec.readout(0.0, 0.0, 600.0), 1e-15)
        assertEquals(2.75, VentIsolationSpec.readout(120.0, 120.0, 600.0), 1e-15)
        assertEquals(-.275, VentIsolationSpec.readout(-12.0, -12.0, -60.0), 1e-15)
        val a = VentIsolationSpec.readout(1.2, -2.3, 4.5)
        val b = VentIsolationSpec.readout(-3.4, 2.2, 5.6)
        assertEquals(a+b, VentIsolationSpec.readout(-2.2, -.1, 10.1), 1e-15)
    }

    @Test
    fun `four eight second conditions are deterministic bounded and preserve shared conditioning`() {
        for (chaos in listOf(.15f, .8f)) {
            var anchored: VentRender? = null
            for (leash in listOf(.95f, 0f)) {
                val first = Vent.renderIsolation(chaos, leash)
                val replay = Vent.renderIsolation(chaos, leash)
                val label = "CHAOS$chaos LEASH$leash"
                assertContentEquals(first.raw, replay.raw, "$label raw replay")
                assertContentEquals(first.snip.samples, replay.snip.samples, "$label normal-level replay")
                assertEquals(first.trace, replay.trace, "$label trajectory replay")
                assertEquals(first.diagnostics, replay.diagnostics)
                assertEquals(8*44_100, first.raw.size, label)
                assertEquals(first.raw.size, first.snip.frameCount)
                assertEquals(1, first.snip.channels)
                assertEquals(44_100, first.snip.sampleRate)
                assertEquals("isolation-linear", first.diagnostics.signalPath)
                assertEquals(0, first.diagnostics.integrationFailures, label)
                assertTrue(first.diagnostics.maxState in .1..256.0, label)
                assertTrue(first.diagnostics.modelTimeStep in 0.0.. .02, label)
                assertTrue(first.diagnostics.rawPeak in .001.. .99, label)
                assertTrue(first.diagnostics.rawRms > .001, label)
                assertTrue(first.raw.all { it.isFinite() && abs(it) < 1f }, label)
                assertTrue(first.snip.samples.all { it.isFinite() && abs(it) < 1f }, label)
                if (leash > 0f) {
                    assertTrue(first.diagnostics.leashResets > 0, label)
                    anchored = first
                } else {
                    assertEquals(0, first.diagnostics.leashResets, label)
                    val strong = checkNotNull(anchored)
                    assertFalse(strong.trace == first.trace, "$label LEASH did not alter the actual orbit")
                    val distance = normalizedDistance(strong.raw, first.raw)
                    // Low CHAOS is near periodic already: anchoring can have only a subtle effect.
                    // Verify a gain-independent change there, without demanding an audible minimum.
                    assertTrue(distance > 0.0, "$label LEASH has no gain-independent effect ($distance)")
                    if (chaos == .8f) assertTrue(distance > .1,
                        "$label high-CHAOS LEASH shape difference unexpectedly collapsed ($distance)")
                }

                // Same seeded orbit, calibration and X-only correction as the production model.
                // Readout/filter/leveling affect only audio, not these actual state coordinates.
                val production = Vent.renderDetailed(VentVoice.ROUND,
                    mapOf("CHAOS" to chaos, "LEASH" to leash, "PUSH" to 0f, "LENGTH" to 0f),
                    midi = 60, velocity = .8f, seed = 17)
                assertEquals(production.trace, first.trace.take(production.trace.size), label)
                assertEquals(production.diagnostics.measuredOrbitPeriod, first.diagnostics.measuredOrbitPeriod)
                assertEquals(production.diagnostics.modelTimeStep, first.diagnostics.modelTimeStep)
                assertEquals("voice-shaped", production.diagnostics.signalPath)
            }
        }
    }

    @Test
    fun `experiment preserves published model one render exactly`() {
        // Existing first-audition ROUND Anchored native float samples, little-endian raw bits.
        // The fixture is embedded here so this protection has no generated-asset dependency.
        val rendered = Vent.render(VentVoice.ROUND,
            mapOf("CHAOS" to .15f, "LEASH" to .95f, "LENGTH" to .7f),
            midi = 60, velocity = .8f, seed = 17, model = 1)
        val bytes = ByteBuffer.allocate(rendered.samples.size*4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in rendered.samples) bytes.putInt(sample.toRawBits())
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
        assertEquals("f6ed6dbd27a0eac0ffaeef8a82aff03e9084c11690766279a7f64422258fe579", hash)
    }

    @Test
    fun `invalid experiment inputs and cancelled renders fail before producing clips`() {
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Vent.renderIsolation(value, 0f) }
            assertFailsWith<IllegalArgumentException> { Vent.renderIsolation(.15f, value) }
        }
        for (seconds in listOf(0.0, -.1, 30.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Vent.renderIsolation(.15f, 0f, seconds = seconds) }
        }
        assertFailsWith<IllegalArgumentException> { Vent.renderIsolation(.15f, 0f, midi = 35) }
        assertFailsWith<IllegalArgumentException> { Vent.renderIsolation(.15f, 0f, velocity = 1.1f) }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { Vent.renderIsolation(.15f, 0f) }
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
