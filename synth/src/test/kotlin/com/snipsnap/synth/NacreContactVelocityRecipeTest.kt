package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Source/ownership/replay contracts; fresh native soft/medium listening remains separate. */
class NacreContactVelocityRecipeTest {
    private fun hard() = NacrePresets.all().first { it.model == 4 }
    private fun bank() = NacreContactVelocityRecipes.recipeFor(hard())
    private fun replacement(layer: NacreContactVelocityLayerRecipe, name: String = layer.name,
        start: Int = layer.velocityStart, end: Int = layer.velocityEnd, gain: Float = layer.samplingGain,
        patch: NacrePatch = layer.patch, recipe: PadRecipe = PadRecipe(patch = patch)) =
        NacreContactVelocityLayerRecipe(name, start, end, gain, recipe)
    private fun changedLayer(value: JsonValue, index: Int, edit: (Map<String, JsonValue>) -> Map<String, JsonValue>): JsonValue {
        val obj = value.obj()
        return JsonValue.Obj(obj + ("layers" to JsonValue.Arr(obj.getValue("layers").arr().mapIndexed { n, layer ->
            if (n == index) JsonValue.Obj(edit(layer.obj())) else layer
        })))
    }
    private fun changedPatch(value: JsonValue, edit: (Map<String, JsonValue>) -> Map<String, JsonValue>): JsonValue =
        changedLayer(value, 0) { layer ->
            val recipe = layer.getValue("recipe").obj()
            layer + ("recipe" to JsonValue.Obj(recipe + ("patch" to JsonValue.Obj(edit(recipe.getValue("patch").obj())))))
        }
    private fun bytes(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putInt(it.toRawBits()) } }.array()
    private fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }

    private fun pcm24Data(wav: ByteArray): ByteArray {
        require(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF" && String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
        var offset = 12
        while (offset + 8 <= wav.size) {
            val tag = String(wav, offset, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            require(size <= Int.MAX_VALUE && offset.toLong() + 8 + size <= wav.size)
            val end = offset + 8 + size.toInt()
            if (tag == "data") return wav.copyOfRange(offset + 8, end)
            offset = end + (size and 1L).toInt()
        }
        error("House WAV has no PCM data chunk")
    }

    @Test
    fun modelFourPolicyKeepsAcceptedHardAndAuthorsCanonicalNativeTouchLayers() {
        for (hard in NacrePresets.all().filter { it.model == 4 }) {
            val bank = NacreContactVelocityRecipes.recipeFor(hard)
            assertEquals(hard, bank.hardPatch)
            assertEquals(1f, bank.layers.last().samplingGain)
            for ((index, layer) in bank.layers.withIndex()) {
                val velocity = listOf(.30f, .65f, 1f)[index]
                val touch = listOf(.05f, .18f, .30f)[index]
                assertEquals(hard.copy(velocity = velocity, macros = hard.macros + ("TOUCH" to touch)), layer.patch)
                assertEquals(NacreVelocityKits.samplingGain(velocity, touch), layer.samplingGain)
                assertEquals(hard.macros - "TOUCH", layer.patch.macros - "TOUCH")
                assertEquals(PadRecipe(patch = layer.patch), layer.recipe)
                assertEquals(listOf(1..41, 42..84, 85..127)[index], layer.velocityStart..layer.velocityEnd)
            }
            assertEquals(0, bank.layers.count { 0 in it.velocityStart..it.velocityEnd })
            for (velocity in 1..127) assertEquals(1, bank.layers.count { velocity in it.velocityStart..it.velocityEnd })
        }
    }

    @Test
    fun newPolicyRoundTripsCompleteOwnedSourcesAndRemainsSeparateFromV1() {
        val hard = hard().copy(name = "Long saved contact", midi = 60, seed = Long.MIN_VALUE + 17)
        val bank = NacreContactVelocityRecipes.recipeFor(hard)
        val restored = NacreContactVelocityRecipe.fromJsonText(bank.toJsonText())
        assertEquals(bank, restored); assertEquals(hard, restored.hardPatch)
        assertTrue(hard.seed.toString() in bank.toJsonText())
        assertEquals("native-velocity-touch-contact-model4-v1", NacreContactVelocityRecipe.POLICY)
        assertEquals("nacreContactVelocityLayers", NacreContactVelocityRecipe.SOURCE_KEY)
        assertEquals("native-velocity-and-touch-effort-v1", NacreVelocityRecipe.POLICY)
        assertEquals("nacreVelocityLayers", NacreVelocityRecipe.SOURCE_KEY)
        val oldHard = NacrePresets.all().first()
        val old = NacreVelocityKits.recipeFor(oldHard)
        assertEquals(oldHard, old.hardPatch)
        assertEquals(old, NacreVelocityRecipe.fromJsonText(old.toJsonText()))
        assertFails { NacreVelocityRecipe.fromJsonText(bank.toJsonText()) }
        assertFails { NacreContactVelocityRecipe.fromJsonText(old.toJsonText()) }
        for (layer in restored.layers) assertEquals(layer.recipe, PadRecipe.fromJsonText(layer.recipe.toJsonText()))
        // A fixed saved v1 document catches decoder/serializer drift independently of writer roundtrip.
        val fixture = """{"schemaVersion":1,"engine":"NACRE","policy":"native-velocity-touch-contact-model4-v1","policyVersion":1,"layers":[{"name":"Soft","velocityStart":1,"velocityEnd":41,"samplingGain":5.802050590515137,"recipe":{"recipe":2,"patch":{"engine":"NACRE","version":1,"name":"Saved contact fixture","voice":"PEARL","macros":{"TOUCH":0.05000000074505806,"DUET":0.15000000596046448,"CURRENT":0.20000000298023224,"VENUE":0.0,"BLOOM":0.15000000596046448,"HOLD":0.0,"CONTACT":0.3499999940395355},"model":4,"midi":48,"velocity":0.30000001192092896,"seed":"7"},"alias":false}},{"name":"Medium","velocityStart":42,"velocityEnd":84,"samplingGain":1.8642224073410034,"recipe":{"recipe":2,"patch":{"engine":"NACRE","version":1,"name":"Saved contact fixture","voice":"PEARL","macros":{"TOUCH":0.18000000715255737,"DUET":0.15000000596046448,"CURRENT":0.20000000298023224,"VENUE":0.0,"BLOOM":0.15000000596046448,"HOLD":0.0,"CONTACT":0.3499999940395355},"model":4,"midi":48,"velocity":0.6499999761581421,"seed":"7"},"alias":false}},{"name":"Hard","velocityStart":85,"velocityEnd":127,"samplingGain":1.0,"recipe":{"recipe":2,"patch":{"engine":"NACRE","version":1,"name":"Saved contact fixture","voice":"PEARL","macros":{"TOUCH":0.30000001192092896,"DUET":0.15000000596046448,"CURRENT":0.20000000298023224,"VENUE":0.0,"BLOOM":0.15000000596046448,"HOLD":0.0,"CONTACT":0.3499999940395355},"model":4,"midi":48,"velocity":1.0,"seed":"7"},"alias":false}}]}"""
        val saved = NacreContactVelocityRecipe.fromJsonText(fixture)
        assertEquals(NacrePresets.all().first { it.model == 4 }.copy(name = "Saved contact fixture"), saved.hardPatch)
        assertEquals(Json.parse(fixture), saved.toJsonValue())

    }

    @Test
    fun callerMutationCannotChangeLayerOrBankSources() {
        val macros = hard().macros.toMutableMap()
        val bank = NacreContactVelocityRecipes.recipeFor(hard().copy(macros = macros))
        val before = bank.toJsonText()
        macros["CONTACT"] = .99f; macros["TOUCH"] = .9f
        assertEquals(before, bank.toJsonText())
        assertEquals(.35f, bank.hardPatch.macros.getValue("CONTACT"))
        assertFailsWith<UnsupportedOperationException> { (bank.hardPatch.macros as MutableMap<String, Float>)["CONTACT"] = 0f }
        assertFailsWith<UnsupportedOperationException> { (bank.layers as MutableList<NacreContactVelocityLayerRecipe>).clear() }
        val supplied = bank.layers.toMutableList()
        val copied = NacreContactVelocityRecipe(supplied)
        supplied.clear(); assertEquals(bank, copied)
        val layerMacros = bank.layers.first().patch.macros.toMutableMap()
        val layer = replacement(bank.layers.first(), patch = bank.layers.first().patch.copy(macros = layerMacros))
        layerMacros.clear(); assertEquals(bank.layers.first(), layer)
    }

    @Test
    fun constructorsRejectMixedSourcesWindowsGainsAndUnsupportedReferences() {
        val bank = bank()
        fun refuse(index: Int = 0, edit: (NacreContactVelocityLayerRecipe) -> NacreContactVelocityLayerRecipe) {
            assertFails { NacreContactVelocityRecipe(bank.layers.mapIndexed { n, layer -> if (n == index) edit(layer) else layer }) }
        }
        refuse { replacement(it, patch = it.patch.copy(midi = 49)) }
        refuse { replacement(it, patch = it.patch.copy(seed = it.patch.seed + 1)) }
        refuse { replacement(it, patch = it.patch.copy(name = "another source")) }
        refuse { replacement(it, patch = it.patch.copy(macros = it.patch.macros + ("CONTACT" to .8f))) }
        refuse { replacement(it, patch = it.patch.copy(macros = it.patch.macros + ("BLOOM" to .9f))) }
        refuse { replacement(it, patch = it.patch.copy(macros = it.patch.macros + ("TOUCH" to .1f))) }
        refuse { replacement(it, patch = it.patch.copy(velocity = .31f)) }
        refuse { replacement(it, start = 2) }; refuse { replacement(it, end = 42) }
        refuse { replacement(it, gain = Math.nextUp(it.samplingGain)) }; refuse { replacement(it, name = "quiet") }
        refuse(2) { replacement(it, gain = 1.0001f) }
        assertFails { NacreContactVelocityRecipe(bank.layers.reversed()) }
        assertFails { NacreContactVelocityRecipe(bank.layers.dropLast(1)) }
        assertFails { NacreContactVelocityRecipes.recipeFor(hard().copy(velocity = .65f)) }
        assertFails { NacreContactVelocityRecipes.recipeFor(hard().copy(macros = hard().macros + ("TOUCH" to .31f))) }
        assertFails { NacreContactVelocityRecipes.recipeFor(hard().copy(model = 3, macros = hard().macros - "CONTACT")) }
        assertFails { NacreContactVelocityRecipes.recipeFor(hard().copy(macros = hard().macros - "CONTACT")) }
        val mutable = hard().macros.toMutableMap(); val supplied = hard().copy(macros = mutable)
        for ((key, value) in listOf("HOLD" to .1f, "CONTACT" to Float.NaN, "UNKNOWN" to .3f)) {
            mutable.clear(); mutable.putAll(hard().macros); mutable[key] = value
            assertFails { NacreContactVelocityRecipes.recipeFor(supplied) }
        }
        assertFails { replacement(bank.layers.first(), recipe = PadRecipe(patch = bank.layers.first().patch, alias = true)) }
    }

    @Test
    fun jsonRejectsUnknownAndImpreciseSourceOrPolicyFields() {
        val value = bank().toJsonValue()
        val bad = mutableListOf<JsonValue>(
            JsonValue.Obj(value.entries + ("unknown" to JsonValue.Bool(true))),
            JsonValue.Obj(value.entries + ("schemaVersion" to JsonValue.Num(2.0))),
            JsonValue.Obj(value.entries + ("policyVersion" to JsonValue.Num(2.0))),
            JsonValue.Obj(value.entries + ("policy" to JsonValue.Str(NacreVelocityRecipe.POLICY))),
            changedLayer(value, 0) { it + ("unknown" to JsonValue.Num(0.0)) },
            changedLayer(value, 0) { it + ("samplingGain" to JsonValue.Num(Math.nextUp(it.getValue("samplingGain").num()))) },
            changedLayer(value, 0) { it + ("recipe" to JsonValue.Obj(it.getValue("recipe").obj() + ("unknown" to JsonValue.Bool(true)))) },
            changedPatch(value) { it + ("unknown" to JsonValue.Bool(true)) },
            changedPatch(value) { it + ("velocity" to JsonValue.Num(Math.nextUp(it.getValue("velocity").num()))) },
            changedPatch(value) { it + ("seed" to JsonValue.Str("0007")) },
        )
        for (key in listOf("CONTACT", "TOUCH")) bad += changedPatch(value) { patch ->
            val macros = patch.getValue("macros").obj()
            patch + ("macros" to JsonValue.Obj(macros + (key to JsonValue.Num(Math.nextUp(macros.getValue(key).num())))))
        }
        for (candidate in bad) assertFails { NacreContactVelocityRecipe.fromJsonValue(candidate) }
        assertFails { NacreContactVelocityRecipe.fromJsonText("{\"schemaVersion\":1,\"schemaVersion\":1}") }
    }

    @Test
    fun fullLayerReplayOwnsBuffersAndPreservesHardRawBits() {
        val bank = bank(); val samples = FloatArray(NacreContactVelocityRecipes.FRAME_COUNT) { .05f }
        samples[0] = -0f; samples[1] = Float.fromBits(1)
        val native = Snip(samples, 1, Dsp.RATE); val before = bytes(samples)
        val calls = mutableListOf<NacrePatch>()
        val result = bank.renderAllLayers { calls += it; native }
        assertEquals(bank.layers.map { it.patch }, calls)
        assertContentEquals(before, bytes(result.last().samples)); assertContentEquals(before, bytes(native.samples))
        assertTrue(result.none { it.samples === native.samples })
        assertTrue(result[0].samples !== result[1].samples && result[1].samples !== result[2].samples)
        for (index in 0..1) assertEquals(.05f * bank.layers[index].samplingGain, result[index].samples[2])
        result[0].samples[2] = .7f
        assertEquals(.05f, native.samples[2]); assertEquals(.05f, result.last().samples[2])
    }

    @Test
    fun replayRejectsMalformedNonfiniteAndOverCeilingAudio() {
        val bank = bank(); val size = NacreContactVelocityRecipes.FRAME_COUNT
        val malformed = listOf(Snip(FloatArray(size * 2), 2, Dsp.RATE),
            Snip(FloatArray(size), 1, 22_050), Snip(FloatArray(size - 1), 1, Dsp.RATE))
        for (snip in malformed) assertFails { bank.layers.last().render { snip } }
        for (sample in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE)) {
            val bad = Snip(FloatArray(size).also { it[size / 2] = sample }, 1, Dsp.RATE)
            assertFails { bank.layers.first().render { bad } }
        }
        val hot = Snip(FloatArray(size) { .2f }, 1, Dsp.RATE)
        assertFails { bank.layers.first().render { hot } }
        assertTrue(hot.samples.all { it == .2f }, "a refusal must not turn into normalization")
    }

    private data class Capture(val file: String, val data: ByteArray, val metadata: JsonValue.Obj)
    private fun scalarResidual(source: FloatArray, hard: FloatArray): Double {
        var aa = 0.0; var bb = 0.0; var ab = 0.0
        for (i in source.indices) { val a = source[i].toDouble(); val b = hard[i].toDouble(); aa += a*a; bb += b*b; ab += a*b }
        require(aa > 0.0 && bb > 0.0)
        return sqrt(max(0.0, aa - ab * ab / bb) / aa)
    }

    @Test
    fun nativeContactVelocityLayersMatchHardWitnessesAndCaptureQualifiedBuffers() {
        val expected = mapOf(.35f to "65403955e4250f620f05a2b33ea56093a58e685743da3680001bd8ae38ccb018",
            .8f to "48304c74c17949b3ea375803d2cec3bd11220f7edc93ed9ed167554ca01ef73c")
        val captures = mutableListOf<Capture>()
        var nativeRenderInvocations = 0
        for ((contact, witness) in expected) {
            val hard = NacrePresets.all().single { it.model == 4 && it.macros["CONTACT"] == contact }
            assertEquals(48, hard.midi); assertEquals(7L, hard.seed)
            val bank = NacreContactVelocityRecipes.recipeFor(hard); val natives = mutableListOf<Snip>()
            val output = bank.renderAllLayers { patch -> nativeRenderInvocations++; patch.render().also { natives += it } }
            assertEquals(3, natives.size)
            assertEquals(witness, hash(bytes(natives.last().samples)), "accepted hard source changed")
            assertEquals(witness, hash(bytes(output.last().samples)), "hard delivery changed accepted Float bits")
            for ((index, layer) in bank.layers.withIndex()) {
                val samples = if (index == 2) natives[index].samples else
                    FloatArray(natives[index].samples.size) { natives[index].samples[it] * layer.samplingGain }
                assertContentEquals(bytes(samples), bytes(output[index].samples))
                assertEquals(264_600, output[index].frameCount)
                assertTrue(output[index].samples.all { it.isFinite() })
                assertTrue(output[index].peak() in .001f.. .950001f)
                val residual = if (index == 2) 0.0 else scalarResidual(output[index].samples, output.last().samples)
                assertTrue(residual.isFinite() && residual in 0.0..1.0)
                // Fresh model-4 measurements are evidence, not borrowed model-3 articulation thresholds.
                val id = if (contact == .35f) "contact-035" else "contact-080"
                val file = "$id-${layer.name.lowercase(java.util.Locale.ROOT)}.f32le"; val data = bytes(output[index].samples)
                val patch = layer.patch
                val wav = ByteArrayOutputStream().also { WavWriter.write(it, output[index], WavWriter.BitDepth.PCM_24) }.toByteArray()
                val pcm = pcm24Data(wav)
                assertEquals(output[index].frameCount * 3, pcm.size)
                if (index == 2) assertEquals(if (contact == .35f)
                    "7f9f1bcd12b0e7ab6e9139fd32fd203d06ff5f63e5f6098c0a0da9ca4b1a7d81" else
                    "6d6f561a3213f049b261a67f2433f74b2bbf4465c9ce4712bdd1c983b3a384df", hash(wav))
                captures += Capture(file, data, JsonValue.Obj(linkedMapOf(
                    "caseId" to JsonValue.Str(id), "contact" to JsonValue.Num(contact.toDouble()),
                    "layer" to JsonValue.Str(layer.name), "sourceVelocity" to JsonValue.Num(patch.velocity.toDouble()),
                    "sourceTouch" to JsonValue.Num(patch.macros.getValue("TOUCH").toDouble()),
                    "velocityStart" to JsonValue.Num(layer.velocityStart.toDouble()), "velocityEnd" to JsonValue.Num(layer.velocityEnd.toDouble()),
                    "samplingGain" to JsonValue.Num(layer.samplingGain.toDouble()),
                    "midi" to JsonValue.Num(patch.midi.toDouble()), "seed" to JsonValue.Str(patch.seed.toString()),
                    "model" to JsonValue.Num(patch.model.toDouble()), "sampleRate" to JsonValue.Num(output[index].sampleRate.toDouble()),
                    "channels" to JsonValue.Num(output[index].channels.toDouble()), "frameCount" to JsonValue.Num(output[index].frameCount.toDouble()),
                    "nativeFloatSha256" to JsonValue.Str(hash(bytes(natives[index].samples))),
                    "deliveredFloatSha256" to JsonValue.Str(hash(data)), "file" to JsonValue.Str(file),
                    "bytes" to JsonValue.Num(data.size.toDouble()), "hardExpectedFloatSha256" to JsonValue.Str(witness),
                    "houseWavSha256" to JsonValue.Str(hash(wav)),
                    "housePcm24DataSha256" to JsonValue.Str(hash(pcm)),
                    "gainRemovedResidualToHard" to JsonValue.Num(residual), "fullPadRecipe" to layer.recipe.toJsonValue(),
                )))
            }
        }
        // Actual serialized-policy native replay must reproduce the first Soft buffer; this is
        // a functional determinism check, never an extra pass for export/capture.
        val reopened = NacreContactVelocityRecipe.fromJsonText(bank().toJsonText())
        val repeated = reopened.layers.first().render { patch -> nativeRenderInvocations++; patch.render() }
        assertContentEquals(captures.first().data, bytes(repeated.samples))
        assertEquals(7, nativeRenderInvocations)
        assertEquals(6, captures.size)
        val directory = System.getProperty("snipsnap.nacreContactVelocity.captureDir") ?: return
        val source = System.getProperty("snipsnap.nacreContactVelocity.sourceCommit")
        require(source != null && Regex("[0-9a-f]{40}").matches(source)) { "capture needs the actual source commit" }
        val root = Paths.get(directory)
        require(root.isAbsolute) { "capture output must be an absolute freshly granted directory" }
        Files.createDirectory(root) // Never replace a prior successful or failed capture.
        for (capture in captures) Files.write(root.resolve(capture.file), capture.data, StandardOpenOption.CREATE_NEW)
        val metadata = JsonValue.Obj(linkedMapOf("sourceCommit" to JsonValue.Str(source),
            "engine" to JsonValue.Str(NacrePatch.ENGINE), "policy" to JsonValue.Str(NacreContactVelocityRecipe.POLICY),
            "policyVersion" to JsonValue.Num(NacreContactVelocityRecipe.POLICY_VERSION.toDouble()),
            "schemaVersion" to JsonValue.Num(NacreContactVelocityRecipe.SCHEMA_VERSION.toDouble()),
            "nativeRenderInvocations" to JsonValue.Num(nativeRenderInvocations.toDouble()),
            "repeatDeterminism" to JsonValue.Obj(linkedMapOf("caseId" to JsonValue.Str("contact-035"),
                "layer" to JsonValue.Str("Soft"), "deliveredFloatSha256" to JsonValue.Str(hash(bytes(repeated.samples))),
                "capturedFirstEquivalent" to JsonValue.Bool(true))),
            "sampleFormat" to JsonValue.Str("float32le"), "captures" to JsonValue.Arr(captures.map { it.metadata })))
        Files.write(root.resolve("native-velocity-captures.json"), Json.write(metadata).toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW)
    }
}
