package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pins the published first audition, rather than comparing two routes through today's engine. */
class NacreModelCompatibilityTest {
    private fun reference(): Map<String, JsonValue> {
        val text = checkNotNull(javaClass.getResourceAsStream("/nacre-model1-reference.json"))
            .bufferedReader().use { it.readText() }
        return Json.parse(text).obj()
    }

    @Test
    fun `published model one recipe retains its exact prelevel samples events and production audio`() {
        val reference = reference()
        val patch = NacrePatch.fromJsonValue(reference.getValue("recipe")) as NacrePatch
        assertEquals(1, patch.model)
        val probe = NacreModels.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed, patch.model)
        assertEquals(reference.getValue("prelevelSamplesSha256").str(), samplesHash(probe.raw),
            "model 1 raw audio changed from the published audition")
        assertEquals(reference.getValue("nativeSamplesSha256").str(), samplesHash(probe.snip.samples),
            "model 1 conditioning or leveling changed from the published audition")
        assertEquals(reference.getValue("nativeSamplesSha256").str(), samplesHash(patch.render().samples),
            "a saved model 1 recipe was routed through the revised engine")
        assertEquals(1.0, probe.diagnostics.getValue("modelVersion"))
        val expectedEvents = reference.getValue("events").arr().map { value ->
            val event = value.obj()
            Nacre.Event(event.getValue("performer").str(), event.getValue("stringId").int(),
                event.getValue("timeSeconds").num(), event.getValue("frequencyHz").num(),
                event.getValue("strength").num(), event.getValue("direction").str())
        }
        assertEquals(expectedEvents, probe.events, "legacy event conversion changed the scheduled duet")
    }

    @Test
    fun `both supported models survive serialization renaming control edits and native velocity rendering`() {
        for (model in listOf(1, Nacre.MODEL_VERSION).distinct()) {
            val patch = NacrePatch("Saved model $model", NacreVoice.PEARL,
                mapOf("DUET" to .65f, "CURRENT" to 0f), midi = 43, velocity = .65f,
                seed = Long.MAX_VALUE, model = model)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val controls = patch.macros + ("TOUCH" to .8f)
            assertEquals(patch.copy(name = "Edited", macros = controls), Patches.edited(patch, "Edited", controls))
            assertContentEquals(patch.copy(velocity = .25f).render().samples,
                Velocity.atVelocity(patch, .25f).samples, "velocity changed the saved model or seed: $model")
        }
        assertEquals(Nacre.MODEL_VERSION, NacrePatch("New", NacreVoice.PEARL, emptyMap()).model)
    }

    @Test
    fun `partial model one recipes use frozen defaults and preserve diagnostic option semantics`() {
        val partial = NacrePatch("Partial legacy", NacreVoice.PEARL, mapOf("DUET" to .7f), model = 1)
        assertEquals(NacreModel1.defaults(), NacreModels.defaults(partial.voice, partial.model))
        assertEquals(NacreModel1.macrosFor(partial.voice), NacreModels.macrosFor(partial.voice, partial.model))
        assertContentEquals(NacreModel1.render(partial.voice, partial.macros, partial.midi,
            partial.velocity, partial.seed).samples, partial.render().samples)
        val options = Nacre.Options(muteUpperPlucks = true, disableCrossHarp = true, bypassVenue = true)
        val probe = NacreModels.inspect(partial.voice, partial.macros, partial.midi,
            partial.velocity, partial.seed, partial.model, options)
        assertTrue(probe.events.filter { it.performer == "upper" }.all { it.strength == 0.0 })
        assertEquals(0.0, probe.diagnostics.getValue("maxCrossHarpSend"))
        assertTrue(probe.stems.getValue("venue").all { it == 0f })
    }

    @Test
    fun `model validation keeps the published model available and refuses unsupported versions`() {
        assertTrue(NacreModels.supports(1))
        assertTrue(NacreModels.supports(Nacre.MODEL_VERSION))
        for (model in listOf(Int.MIN_VALUE, -1, 0, Nacre.MODEL_VERSION + 1, Int.MAX_VALUE)) {
            assertFalse(NacreModels.supports(model), "accepted unsupported model $model")
        }
        assertFailsWith<IllegalArgumentException> {
            NacreModels.inspect(model = 1, options = Nacre.Options(lockStrumTiming = true))
        }
    }

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
