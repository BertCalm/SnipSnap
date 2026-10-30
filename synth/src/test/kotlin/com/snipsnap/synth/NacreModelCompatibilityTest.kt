package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Published fixtures remain independent of the renderer and its current audition helper. */
class NacreModelCompatibilityTest {
    private fun reference(name: String): Map<String, JsonValue> {
        val text = checkNotNull(javaClass.getResourceAsStream("/$name")) { "Missing published reference $name" }
            .bufferedReader().use { it.readText() }
        return Json.parse(text).obj()
    }

    @Test
    fun `both published legacy recipes retain exact prelevel samples events and production audio`() {
        for (model in listOf(1, 2)) {
            val reference = reference("nacre-model$model-reference.json")
            val patch = NacrePatch.fromJsonValue(reference.getValue("recipe")) as NacrePatch
            assertEquals(model, patch.model)
            val probe = NacreModels.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed, patch.model)
            assertEquals(reference.getValue("prelevelSamplesSha256").str(), samplesHash(probe.raw),
                "model $model raw audio changed from its published audition")
            assertEquals(reference.getValue("nativeSamplesSha256").str(), samplesHash(probe.snip.samples),
                "model $model conditioning or leveling changed from its published audition")
            assertEquals(reference.getValue("nativeSamplesSha256").str(), samplesHash(patch.render().samples),
                "a saved model $model recipe was routed through the current engine")
            assertEquals(model.toDouble(), probe.diagnostics.getValue("modelVersion"))
            assertEquals(events(reference), probe.events, "model $model event conversion changed the scheduled duet")
        }
    }

    @Test
    fun `all three models survive ordinary patch pad recipe editing and native velocity replay`() {
        for (model in listOf(1, 2, 3)) {
            val patch = NacrePatch("Saved model $model", NacreVoice.PEARL,
                mapOf("DUET" to .65f, "CURRENT" to 0f), midi = 43, velocity = .65f,
                seed = Long.MAX_VALUE, model = model)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val controls = patch.macros + ("TOUCH" to .8f)
            assertEquals(patch.copy(name = "Edited", macros = controls), Patches.edited(patch, "Edited", controls))
            val recipe = PadRecipe(patch)
            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, restored)
            assertContentEquals(patch.render().samples, restored.render().samples,
                "ordinary pad recipe changed saved model, defaults, seed or velocity: $model")
            assertContentEquals(patch.copy(velocity = .25f).render().samples,
                Velocity.atVelocity(patch, .25f).samples, "velocity changed the saved model or seed: $model")
        }
        assertEquals(3, Nacre.MODEL_VERSION)
        assertEquals(3, NacrePatch("New", NacreVoice.PEARL, emptyMap()).model)
    }

    @Test
    fun `accepted return endpoints replay directly from production model three recipes`() {
        val reference = reference("nacre-presence-reference.json")
        for (value in reference.getValue("endpoints").arr()) {
            val endpoint = value.obj()
            val id = endpoint.getValue("id").str()
            val patch = NacrePatch("Accepted $id", NacreVoice.PEARL,
                endpoint.getValue("macros").obj().mapValues { it.value.num().toFloat() },
                midi = endpoint.getValue("midi").int(), velocity = endpoint.getValue("velocity").num().toFloat(),
                seed = endpoint.getValue("seed").str().toLong(), model = 3)
            val restored = NacrePatch.fromJsonText(patch.toJsonText())
            val probe = NacreModels.inspect(restored.voice, restored.macros, restored.midi,
                restored.velocity, restored.seed, restored.model)
            assertEquals(endpoint.getValue("prelevelSamplesSha256").str(), samplesHash(probe.raw),
                "$id changed the accepted source/return replacement or its Float arithmetic")
            assertEquals(endpoint.getValue("returnSamplesSha256").str(), samplesHash(probe.stems.getValue("venue")),
                "$id changed the accepted path geometry, aperture or fixed return calibration")
            val replay = restored.render()
            assertContentEquals(probe.snip.samples, replay.samples, "$id diagnostic and ordinary render differ")
            assertEquals(endpoint.getValue("wavSha256").str(), pcm24Hash(replay),
                "$id ordinary patch replay needs an unsaved audition gain or postmix operation")
            assertEquals(3.0, probe.diagnostics.getValue("modelVersion"))
        }
    }

    @Test
    fun `model specific defaults stay frozen and partial legacy patches retain them`() {
        val legacyDefaults = mapOf("TOUCH" to .30f, "DUET" to .15f, "CURRENT" to .20f,
            "VENUE" to .15f, "BLOOM" to .35f, "HOLD" to 0f)
        for (model in listOf(1, 2)) {
            assertEquals(legacyDefaults, NacreModels.defaults(NacreVoice.PEARL, model),
                "model $model inherited another model's control defaults")
            val partial = NacrePatch("Partial legacy $model", NacreVoice.PEARL,
                mapOf("DUET" to .7f), model = model)
            val expected = when (model) {
                1 -> NacreModel1.render(partial.voice, legacyDefaults + partial.macros,
                    partial.midi, partial.velocity, partial.seed)
                else -> NacreModel2.render(partial.voice, legacyDefaults + partial.macros,
                    partial.midi, partial.velocity, partial.seed)
            }
            assertContentEquals(expected.samples, partial.render().samples,
                "partial model $model recipe did not use its frozen defaults")
        }
        assertEquals(NacreModel1.macrosFor(NacreVoice.PEARL), NacreModels.macrosFor(NacreVoice.PEARL, 1))
        assertEquals(NacreModel2.macrosFor(NacreVoice.PEARL), NacreModels.macrosFor(NacreVoice.PEARL, 2))
        assertEquals(Nacre.macrosFor(NacreVoice.PEARL), NacreModels.macrosFor(NacreVoice.PEARL, 3))
        assertEquals(Nacre.defaults(), NacreModels.defaults(NacreVoice.PEARL, 3))
        for (model in listOf(1, 2)) for (midi in listOf(28, 72)) {
            val patch = NacrePatch("Legacy range $model", NacreVoice.PEARL, emptyMap(), midi = midi, model = model)
            assertEquals(patch, NacrePatch.fromJsonText(patch.toJsonText()),
                "a current playable-range recommendation rejected saved model $model at MIDI $midi")
        }
    }

    @Test
    fun `model one diagnostic options preserve unplucked upper harp cross send and venue bypass`() {
        val partial = NacrePatch("Partial legacy", NacreVoice.PEARL, mapOf("DUET" to .7f), model = 1)
        val options = Nacre.Options(muteUpperPlucks = true, disableCrossHarp = true, bypassVenue = true)
        val probe = NacreModels.inspect(partial.voice, partial.macros, partial.midi,
            partial.velocity, partial.seed, partial.model, options)
        assertTrue(probe.events.filter { it.performer == "upper" }.all { it.strength == 0.0 })
        assertEquals(0.0, probe.diagnostics.getValue("maxCrossHarpSend"))
        assertTrue(probe.stems.getValue("venue").all { it == 0f })
    }

    @Test
    fun `model two diagnostic options retain their original timing muting and current semantics`() {
        val macros = mapOf("TOUCH" to .8f, "DUET" to .7f, "CURRENT" to .65f)
        val actual = NacreModels.inspect(macros = macros, model = 2,
            options = Nacre.Options(muteBassPlucks = true, disableCrossHarp = true,
                postCurrentOnly = true, bypassVenue = true, lockStrumTiming = true))
        val expected = NacreModel2.inspect(macros = macros,
            options = NacreModel2.Options(muteBassPlucks = true, disableCrossHarp = true,
                postCurrentOnly = true, bypassVenue = true, lockStrumTiming = true))
        assertContentEquals(expected.raw, actual.raw, "model 2 diagnostic dispatch changed its raw gesture")
        assertContentEquals(expected.snip.samples, actual.snip.samples)
        assertEquals(expected.events.map {
            Nacre.Event(it.performer, it.stringId, it.timeSeconds, it.frequencyHz, it.strength, it.direction)
        }, actual.events)
        for ((name, samples) in expected.stems) assertContentEquals(samples, actual.stems.getValue(name), name)
        assertTrue(actual.events.filter { it.performer == "bass" }.all { it.strength == 0.0 })
        assertEquals(0.0, actual.diagnostics.getValue("maxCrossHarpSend"))
    }

    @Test
    fun `model validation keeps all three versions available and refuses unsupported versions`() {
        for (model in listOf(1, 2, 3)) assertTrue(NacreModels.supports(model), "lost saved model $model")
        for (model in listOf(Int.MIN_VALUE, -1, 0, 4, Int.MAX_VALUE)) {
            assertFalse(NacreModels.supports(model), "accepted unsupported model $model")
        }
        assertFailsWith<IllegalArgumentException> {
            NacreModels.inspect(model = 1, options = Nacre.Options(lockStrumTiming = true))
        }
    }

    private fun events(reference: Map<String, JsonValue>): List<Nacre.Event> =
        reference.getValue("events").arr().map { value ->
            val event = value.obj()
            Nacre.Event(event.getValue("performer").str(), event.getValue("stringId").int(),
                event.getValue("timeSeconds").num(), event.getValue("frequencyHz").num(),
                event.getValue("strength").num(), event.getValue("direction").str())
        }

    private fun pcm24Hash(snip: Snip): String {
        val output = ByteArrayOutputStream()
        WavWriter.write(output, snip, WavWriter.BitDepth.PCM_24)
        return bytesHash(output.toByteArray())
    }

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return bytesHash(bytes.array())
    }

    private fun bytesHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
