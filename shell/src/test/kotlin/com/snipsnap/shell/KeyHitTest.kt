package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyHitTest {

    private val instrument = InstrumentStore.Instrument(
        "Seed", release = 0.25f,
        zones = listOf(
            InstrumentStore.Zone(36, 47, 40, "lo.wav", 4_000, loopStartFrame = 1_000),
            InstrumentStore.Zone(48, 59, 52, "hi.wav", 3_000, loopStartFrame = 0),
            InstrumentStore.Zone(60, 71, 64, "top.wav", 2_000, loopStartFrame = 1_999),
        ),
    )
    private val frames = mapOf("lo.wav" to 4_000L, "hi.wav" to 3_000L, "top.wav" to 2_000L)
    private val framesOf: (String) -> Long? = { frames[it] }

    private fun near(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-6, "expected $expected, got $actual")

    @Test
    fun `the root plays at speed one and the ratio follows the semitones, in the zone that covers the note`() {
        val root = KeyHit.resolve(instrument, 40, 1f, framesOf)!!
        assertEquals("lo.wav", root.sampleFile)
        near(1.0, root.pitchRatio)
        near(Math.pow(2.0, 7 / 12.0), KeyHit.resolve(instrument, 47, 1f, framesOf)!!.pitchRatio)
        near(Math.pow(2.0, -4 / 12.0), KeyHit.resolve(instrument, 36, 1f, framesOf)!!.pitchRatio)
        assertEquals("hi.wav", KeyHit.resolve(instrument, 50, 1f, framesOf)!!.sampleFile)
        near(Math.pow(2.0, -2 / 12.0), KeyHit.resolve(instrument, 50, 1f, framesOf)!!.pitchRatio)
    }

    @Test
    fun `the loop and the gain follow the zone and the velocity`() {
        val lo = KeyHit.resolve(instrument, 40, 0.5f, framesOf)!!
        assertTrue(lo.loops)
        assertEquals(1_000L, lo.loopStartFrame)
        near((0.5f * InstrumentEngine.VOICE_LEVEL).toDouble(), lo.gain.toDouble())
        val hi = KeyHit.resolve(instrument, 52, 1f, framesOf)!!
        assertFalse(hi.loops)
        // A loop start at the last frame is no loop (the JVM engine's rule).
        assertFalse(KeyHit.resolve(instrument, 64, 1f, framesOf)!!.loops)
    }

    @Test
    fun `a note no zone covers, or a sample that never loaded, is silence`() {
        assertNull(KeyHit.resolve(instrument, 20, 1f, framesOf))
        assertNull(KeyHit.resolve(instrument, 90, 1f, framesOf))
        assertNull(KeyHit.resolve(instrument, 40, 1f) { null })
        assertNull(KeyHit.resolve(instrument, 40, 1f) { 0L })
        assertFailsWith<IllegalArgumentException> { KeyHit.resolve(instrument, 40, 2f, framesOf) }
    }

    @Test
    fun `the release is milliseconds with a floor`() {
        near(250.0, KeyHit.releaseMs(instrument).toDouble())
        near(1.0, KeyHit.releaseMs(instrument.copy(release = 0f)).toDouble())
    }
}
