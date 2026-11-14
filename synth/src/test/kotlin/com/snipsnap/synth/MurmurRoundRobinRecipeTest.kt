package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class MurmurRoundRobinRecipeTest {
    @Test fun canonicalSelectedTakesRoundTrip() {
        for (source in MurmurKeptSources.all()) for (take in 0 until MurmurRoundRobinRecipe.TAKE_COUNT) {
            val patch = source.factoryPatch()
            val recipe = MurmurRoundRobinRecipe(patch, take)
            assertEquals(source.id, recipe.selectionId)
            assertEquals(patch, recipe.patch())
            assertEquals(recipe, MurmurRoundRobinRecipe.fromJsonText(recipe.toJsonText()))
            assertEquals(recipe.toJsonValue(), MurmurRoundRobinRecipe.fromJsonValue(recipe.toJsonValue()).toJsonValue())
            assertEquals(patch.toJsonText(), recipe.patch().toJsonText())
        }
    }

    @Test fun deepSourceOwnership() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        val original = source.toJsonText()
        val recipe = MurmurRoundRobinRecipe(source, 1)
        (source.macros as MutableMap<String, Float>)["BREATH"] = 0f
        (source.config.ratios as MutableList<Double>)[1] = 8.0
        (source.config.targets.first().centers as MutableList<Double>)[0] = 500.0
        assertEquals(original, recipe.patch().toJsonText())
        val read = recipe.patch()
        (read.macros as MutableMap<String, Float>)["MOUTH"] = 0f
        (read.config.targets as MutableList<MurmurFormantTarget>).clear()
        assertEquals(original, recipe.patch().toJsonText())
        (recipe.toJsonValue().entries as MutableMap<String, JsonValue>).clear()
        assertEquals(original, recipe.patch().toJsonText())
    }

    @Test fun unsupportedSourcesAndTakeBoundsAreRefused() {
        val patch = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        for (take in listOf(-1, 4, Int.MIN_VALUE, Int.MAX_VALUE)) assertFails { MurmurRoundRobinRecipe(patch, take) }
        assertFails { MurmurRoundRobinRecipe(patch.copy(velocity = 0f), 1) }
        assertFails { MurmurRoundRobinRecipe(patch.copy(macros = patch.macros + ("BREATH" to 0f)), 1) }
        assertFails { MurmurRoundRobinRecipe(patch.copy(macros = patch.macros - "HOLD"), 1) }
        assertFails { MurmurRoundRobinRecipe(patch.copy(macros = patch.macros + ("EDGE" to .1f)), 1) }
        for (model in 1..3) assertFails {
            MurmurRoundRobinRecipe(patch.copy(config = Murmur.configurationFor(MurmurVoice.PLUCK, model)), 1)
        }
        assertFails {
            val steady = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()
            MurmurRoundRobinRecipe(steady.copy(midi = 60), 1)
        }
    }

    @Test fun strictCodecRejectsLossyAndUnknownValues() {
        val original = MurmurRoundRobinRecipe(MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch(), 1)
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(original.toJsonValue().entries + (key to value))
        for (take in listOf(-1.0, 4.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY))
            assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("take", JsonValue.Num(take))) }
        assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("policy", JsonValue.Str("unknown"))) }
        assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("selectionId", JsonValue.Str(MurmurKeptSources.HELD_WIRE))) }
        assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("extra", JsonValue.Null)) }
        val source = original.patch().toJsonValue().entries
        assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("sourcePatch", JsonValue.Obj(source + ("unknown" to JsonValue.Num(1.0))))) }
        assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("sourcePatch", JsonValue.Obj(source - "config"))) }
        val macros = source.getValue("macros").obj()
        val value = macros.getValue("BREATH").num()
        val lossy = Math.nextUp(value)
        assertEquals(value.toFloat(), lossy.toFloat())
        val sourceWithLoss = JsonValue.Obj(source + ("macros" to JsonValue.Obj(macros + ("BREATH" to JsonValue.Num(lossy)))))
        assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("sourcePatch", sourceWithLoss)) }
        val config = source.getValue("config").obj()
        assertFails { MurmurRoundRobinRecipe.fromJsonValue(changed("sourcePatch", JsonValue.Obj(source + ("config" to JsonValue.Obj(config + ("unknown" to JsonValue.Null)))))) }
    }

    @Test fun zeroTakeRetainsThreeAcceptedRawHashes() {
        val rows = MurmurKeptSources.all().map { source ->
            val patch = source.originalPatch()
            val probe = Murmur.probeRoundRobin(patch, 0)
            val hash = floatHash(probe.samples)
            assertEquals(source.reference.nativePcmSha256, hash, source.id)
            assertTrue(probe.samples.all { it.isFinite() })
            JsonValue.Obj(linkedMapOf(
                "selectionId" to JsonValue.Str(source.id), "plane" to JsonValue.Str("RAW_NATIVE"),
                "take" to JsonValue.Num(0.0), "sourcePatch" to patch.toJsonValue(),
                "rawFloatSha256" to JsonValue.Str(hash), "frames" to JsonValue.Num(probe.samples.size.toDouble()),
                "rootHz" to JsonValue.Num(probe.rootHz), "seamError" to JsonValue.Num(probe.seamError),
            ))
        }
        record("zero-take", JsonValue.Arr(rows))
    }

    @Test fun legacyModelTwoHeldCanaryIsUnchanged() {
        // Independent fixture from unchanged engine84471534a, already retained by MurmurRenderCompatibilityTest.
        val macros = Murmur.defaults(MurmurVoice.KEY) + mapOf("MOTION" to 1f, "HOLD" to 1f)
        val samples = Murmur.render(MurmurVoice.KEY, macros, midi = 60, velocity = .83f,
            config = Murmur.configurationFor(MurmurVoice.KEY, model = 2)).samples
        val hash = floatHash(samples)
        assertEquals("9db1b2be7c1691335f8d7d91e5d659d4a5fd971396d51e33c0d8f779863808f7", hash)
        record("legacy-model2", JsonValue.Obj(linkedMapOf("plane" to JsonValue.Str("HOUSE_FINAL_FLOAT"),
            "floatSha256" to JsonValue.Str(hash), "frames" to JsonValue.Num(samples.size.toDouble()))))
    }

    @Test fun finiteTakesVaryNativelyAndReplay() {
        val patch = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        val recipes = (0 until MurmurRoundRobinRecipe.TAKE_COUNT).map { MurmurRoundRobinRecipe(patch, it) }
        val rendered = recipes.map { it.render() }
        val hashes = rendered.map { floatHash(it.samples) }
        assertEquals(4, hashes.toSet().size)
        assertEquals(1, rendered.map { it.frameCount }.toSet().size)
        for (snip in rendered) {
            assertEquals(1, snip.channels); assertEquals(Dsp.RATE, snip.sampleRate)
            assertTrue(snip.samples.all { it.isFinite() }); assertTrue(snip.peak() in .01f..1f)
            assertTrue(abs(snip.samples.sumOf { it.toDouble() } / snip.frameCount) < .005)
            assertEquals(0f, snip.samples.last())
        }
        val residuals = mutableListOf<JsonValue>()
        for (i in rendered.indices) for (j in i + 1 until rendered.size) {
            val difference = gainRemovedResidual(rendered[i].samples, rendered[j].samples)
            // Numerical distinctness, not an audibility threshold or a listening verdict.
            assertTrue(difference > 1e-6, "native takes $i/$j collapsed to output gain: $difference")
            residuals += JsonValue.Obj(linkedMapOf("a" to JsonValue.Num(i.toDouble()),
                "b" to JsonValue.Num(j.toDouble()), "gainRemovedResidual" to JsonValue.Num(difference)))
        }
        val replay = MurmurRoundRobinRecipe.fromJsonText(recipes[2].toJsonText()).render()
        assertContentEquals(rendered[2].samples, replay.samples)
        record("finite-takes", JsonValue.Obj(linkedMapOf(
            "plane" to JsonValue.Str("HOUSE_FINAL_FLOAT"),
            "recipes" to JsonValue.Arr(recipes.map { it.toJsonValue() }),
            "floatSha256" to JsonValue.Arr(hashes.map { JsonValue.Str(it) }),
            "frames" to JsonValue.Num(rendered.first().frameCount.toDouble()),
            "peaks" to JsonValue.Arr(rendered.map { JsonValue.Num(it.peak().toDouble()) }),
            "pairwiseResiduals" to JsonValue.Arr(residuals),
            "serializedTake2ReplayExact" to JsonValue.Bool(true),
        )))
    }

    @Test fun heldTakeRetainsPeriodicNativeSeam() {
        val patch = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch()
        val recipe = MurmurRoundRobinRecipe(patch, 1)
        val probe = Murmur.probeRoundRobin(recipe.patch(), recipe.take)
        assertEquals(probe.samples.size, probe.previousCycle.size)
        assertTrue(probe.samples.isNotEmpty() && probe.samples.all { it.isFinite() } && probe.previousCycle.all { it.isFinite() })
        val independentlyMeasured = Keys.seamError(probe.previousCycle + probe.samples, probe.previousCycle.size)
        assertEquals(probe.seamError, independentlyMeasured)
        assertTrue(independentlyMeasured < Keys.MAX_SEAM_ERROR)
        assertTrue(probe.rootHz.isFinite() && probe.rootHz > 0.0)
        assertTrue(floatHash(probe.samples) != MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).reference.nativePcmSha256)
        record("held-take", JsonValue.Obj(linkedMapOf("recipe" to recipe.toJsonValue(),
            "plane" to JsonValue.Str("RAW_NATIVE"), "rawFloatSha256" to JsonValue.Str(floatHash(probe.samples)),
            "previousFloatSha256" to JsonValue.Str(floatHash(probe.previousCycle)),
            "frames" to JsonValue.Num(probe.samples.size.toDouble()), "rootHz" to JsonValue.Num(probe.rootHz),
            "seamError" to JsonValue.Num(independentlyMeasured))))
    }

    private fun floatHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun gainRemovedResidual(reference: FloatArray, candidate: FloatArray): Double {
        assertEquals(reference.size, candidate.size)
        var rr = 0.0; var cc = 0.0; var rc = 0.0
        for (i in reference.indices) { val r = reference[i].toDouble(); val c = candidate[i].toDouble(); rr += r * r; cc += c * c; rc += r * c }
        require(rr > 0.0 && cc > 0.0)
        val gain = rc / rr
        var error = 0.0
        for (i in reference.indices) { val delta = candidate[i] - gain * reference[i]; error += delta * delta }
        return sqrt(error / cc)
    }

    private fun record(name: String, value: JsonValue) {
        val root = System.getProperty("snipsnap.murmur.rr.measurements") ?: return
        val directory = Path.of(root); Files.createDirectories(directory)
        val path = directory.resolve("$name.json")
        check(!Files.exists(path)) { "Do not overwrite a retained MURMUR measurement: $path" }
        Files.writeString(path, Json.write(value) + "\n")
    }
}
