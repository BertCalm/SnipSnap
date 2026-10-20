package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared native hit selection and JVM playback must hear the same layer at each MIDI boundary. */
class InstrumentVelocityLayersTest {
    private val layers = listOf(
        InstrumentStore.Layer("soft.wav", 6, 2, 1, 42),
        InstrumentStore.Layer("mid.wav", 8, 3, 43, 84),
        InstrumentStore.Layer("hard.wav", 10, 4, 85, 127),
    )
    private val instrument = InstrumentStore.Instrument("Three layers", .002f,
        listOf(InstrumentStore.Zone(48, 72, 60, "hard.wav", 10, 4, layers)))
    private val samples = layers.mapIndexed { index, layer ->
        layer.sample to FloatArray(layer.frameCount.toInt()) { (index + 1) / 10f }
    }.toMap()

    @Test
    fun `MIDI boundaries select the same sample loop and gain for native commands and JVM playback`() {
        for ((midiVelocity, layerIndex) in listOf(1 to 0, 42 to 0, 43 to 1, 84 to 1, 85 to 2, 127 to 2)) {
            val velocity = midiVelocity / 127f
            val hit = KeyHit.resolve(instrument, 60, velocity) { samples[it]?.size?.toLong() }!!
            val expectedLayer = layers[layerIndex]
            assertEquals(expectedLayer.sample, hit.sampleFile, "velocity $midiVelocity")
            assertEquals(expectedLayer.loopStartFrame, hit.loopStartFrame)
            assertEquals(expectedLayer.frameCount, hit.frames)
            assertEquals(1.0, hit.pitchRatio)
            assertEquals(velocity * InstrumentEngine.VOICE_LEVEL, hit.gain)
            val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, samples), 44_100)
            assertTrue(player.noteOn(60, velocity))
            val out = FloatArray(40)
            player.render(out, 20) // every short loop wraps repeatedly
            val expected = (layerIndex + 1) / 10f * hit.gain
            assertTrue(out.all { kotlin.math.abs(it - expected) < 1e-6f }, "velocity $midiVelocity played the wrong PCM")
            assertEquals(listOf(60), player.activeNotes)
            player.noteOff(60)
            val released = FloatArray(256)
            player.render(released, 128)
            assertTrue(player.activeNotes.isEmpty())
            assertTrue(released.takeLast(20).all { it == 0f })
        }
    }

    @Test
    fun `phone touch positions can reach all three BELLOWS velocity layers`() {
        val heard = listOf(0f, .5f, 1f).map { y ->
            KeyHit.resolve(instrument, 60, PadHit.velocityAt(y, 1f)) { samples[it]?.size?.toLong() }!!.sampleFile
        }
        assertEquals(listOf("soft.wav", "mid.wav", "hard.wav"), heard)
    }

    @Test
    fun `an unloaded soft file or a deliberate velocity gap stays silent`() {
        val missing = samples - "soft.wav"
        assertNull(KeyHit.resolve(instrument, 60, .25f) { missing[it]?.size?.toLong() })
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, missing), 44_100)
        assertFalse(player.noteOn(60, .25f), "missing soft PCM must not fall back to the hard sample")
        val gap = instrument.copy(zones = listOf(instrument.zones.single().copy(layers = listOf(layers[0], layers[2]))))
        assertNull(KeyHit.resolve(gap, 60, 64 / 127f) { samples[it]?.size?.toLong() })
        assertEquals(listOf("soft.wav", "mid.wav", "hard.wav"), instrument.sampleFiles)
    }
}
