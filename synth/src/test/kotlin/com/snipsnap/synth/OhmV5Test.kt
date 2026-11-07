package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OhmV5Test {
    @Test
    fun `reply and ring are explicit finite capabilities without changing the accepted default`() {
        assertEquals(5, Ohm.MODEL_VERSION)
        assertEquals(2, Ohm.DEFAULT_MODEL)
        assertEquals(4, Ohm.SOURCE_MODEL)
        assertEquals(OhmV2.defaults(), Ohm.defaults())
        assertEquals(2, OhmPatch("Existing entry", emptyMap()).model)
        for (model in 1..5) {
            assertTrue(Ohm.supportsModel(model))
            assertEquals(model in 3..4, Ohm.supportsSources(model), "model$model sources")
            assertEquals(model in 3..4, Ohm.supportsHeld(model), "model$model HOLD")
        }
        for (model in listOf(0, 6)) {
            assertFalse(Ohm.supportsModel(model))
            assertFalse(Ohm.supportsSources(model))
            assertFalse(Ohm.supportsHeld(model))
        }
        val approved = mapOf("TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f,
            "MEDDLING" to .9f, "COMMUNE" to 1f)
        assertEquals(approved, Ohm.defaults(5))
        assertEquals(approved.keys, Ohm.macrosFor(5).map { it.name }.toSet())
        val patch = Ohm.replyRingPatch()
        assertEquals("Natural Replies", patch.name)
        assertEquals(approved, patch.macros)
        assertEquals(5, patch.model)
        assertEquals(60, patch.midi)
        assertEquals(.8f, patch.velocity)
        assertEquals(731, patch.seed)
        assertEquals(OhmGesture.STEADY, patch.gesture)
        assertEquals(OhmOptions(), patch.options)
        assertEquals(.5f, patch.reply)
        assertEquals(.5f, patch.ring)
        assertFalse(Ohm.isHeld(patch))
        assertEquals(DrumClass.TONAL, Ohm.drumClassFor(patch))
        assertFailsWith<IllegalArgumentException> { Ohm.renderHeld(patch) }
        assertFailsWith<IllegalArgumentException> { Ohm.renderHeldRaw(patch) }
    }

    /**
     * These hashes come from the delivered, approved study ZIP, not from model5 itself:
     * SnipSnap-OHM-Reply-Ring-e902689ab.zip, study commit e902689ab9661bf70518569978de6589e6cec18c.
     * The archived manifest records rawExportGain1, MIDI60, velocity.8, seed731 and the exact
     * helper macro map. Whole PCM16 WAV hashes pin timing, damping, seed and finishing together.
     */
    @Test
    fun `all five approved study points retain their published raw PCM16 bytes`() {
        data class Golden(val name: String, val reply: Float, val ring: Float, val hash: String)
        val points = listOf(
            Golden("tight", 0f, .5f, "b3855a97979c103b07c8af98b942e95b0a4cbcdeb92ad51885d0ea0388068159"),
            Golden("natural", .5f, .5f, "d98e18a084a54c2872f2aa25f62a3c7e521966b3bb1569e8aa76b07c9443db66"),
            Golden("spaced", 1f, .5f, "a569bb694fa0614b3c7c03d1adfe817e6bdbd65b72a63462dd521e649c253678"),
            Golden("short", .5f, 0f, "099912875a308257d2f64b74384e22e37b36e9f3d3827a86f2a43c484fa2cac8"),
            Golden("long", .5f, 1f, "d4b7b397b2f9c9612762b0e75cc15ecbaf7e235580a077828f79675390a1a3ab"),
        )
        for (point in points) {
            val p = Ohm.replyRingPatch(point.name).copy(reply = point.reply, ring = point.ring)
            val raw = Ohm.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture, p.model,
                p.options, p.reply, p.ring)
            assertEquals(1, raw.channels)
            assertEquals(44_100, raw.sampleRate)
            assertEquals(7 * 44_100, raw.frameCount)
            assertTrue(raw.samples.all { it.isFinite() })
            assertTrue(raw.samples.maxOf { abs(it) } < .95f, "${point.name} raw export clips")
            val bytes = ByteArrayOutputStream().also {
                WavWriter.write(it, raw, WavWriter.BitDepth.PCM_16)
            }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(point.hash, hash, "approved ${point.name} model5 changed")
        }
    }

    @Test
    fun `the accepted model two solo survives before its original endpoint fade`() {
        val p = Ohm.replyRingPatch().copy(macros = Ohm.defaults(5) + ("MEDDLING" to 0f))
        val current = Ohm.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture, p.model,
            p.options, 0f, 1f)
        val accepted = OhmV2.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture)
        val prefix = (4.2 * 44_100).toInt()
        assertContentEquals(accepted.samples.copyOf(prefix), current.samples.copyOf(prefix))
    }

    @Test
    fun `finite output uses the existing melodic level and velocity stage deterministically`() {
        val p = Ohm.replyRingPatch()
        val expected = Ohm.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture, p.model,
            p.options, p.reply, p.ring)
        Dsp.levelTo(expected.samples, 44_100, Dsp.MELODIC_LOUDNESS_TARGET)
        val gain = p.velocity.pow(.8f)
        for (i in expected.samples.indices) expected.samples[i] *= gain
        // Saved keygroup note-off release is metadata for the finite source, not a waveform edit.
        val actual = p.copy(releaseSeconds = .15f).render()
        assertContentEquals(expected.samples, actual.samples)
        // Existing facade signatures remain callable and choose the natural reply/ring midpoint.
        assertContentEquals(actual.samples,
            Ohm.render(p.macros, p.midi, p.velocity, p.seed, p.gesture, p.model).samples)
        assertContentEquals(actual.samples,
            Ohm.render(p.macros, p.midi, p.velocity, p.seed, p.gesture, p.model, p.options).samples)
    }

    @Test
    fun `facade refuses unsupported combinations before they can become a different instrument`() {
        val p = Ohm.replyRingPatch()
        fun refuse(macros: Map<String, Float> = p.macros, gesture: OhmGesture = p.gesture,
                   options: OhmOptions = p.options, reply: Float = p.reply, ring: Float = p.ring) {
            assertFailsWith<IllegalArgumentException> {
                Ohm.renderRaw(macros, p.midi, p.velocity, p.seed, gesture, p.model, options, reply, ring)
            }
            assertFailsWith<IllegalArgumentException> {
                Ohm.render(macros, p.midi, p.velocity, p.seed, gesture, p.model, options, reply, ring)
            }
        }
        refuse(gesture = OhmGesture.GATHER)
        for (hold in listOf(0f, 1f)) refuse(macros = p.macros + ("HOLD" to hold))
        for (option in listOf(
            OhmOptions(exciter = OhmExciter.BOW), OhmOptions(elder = .1f),
            OhmOptions(grains = .1f), OhmOptions(freeze = .1f),
            OhmOptions(fmIndex = 0f), OhmOptions(grainSeconds = .02f),
            OhmOptions(capture = OhmCapture(floatArrayOf(.1f, -.1f), "Dormant contact")),
        )) refuse(options = option)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f)) {
            refuse(reply = bad)
            refuse(ring = bad)
        }
        for (model in 1..4) {
            assertFailsWith<IllegalArgumentException>("model$model must not silently ignore REPLY") {
                Ohm.renderRaw(Ohm.defaults(model), p.midi, p.velocity, p.seed, p.gesture, model,
                    OhmOptions(), .2f, .5f)
            }
            assertFailsWith<IllegalArgumentException>("model$model must not silently ignore RING") {
                Ohm.render(Ohm.defaults(model), p.midi, p.velocity, p.seed, p.gesture, model,
                    OhmOptions(), .5f, .8f)
            }
        }
    }
}
