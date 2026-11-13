package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class MurmurRoundRobinContrastRecipeTest {
    private fun finite() = MurmurKeptSources.all().filter { it.factoryPatch().macros.getValue("HOLD") == 0f }

    @Test fun explicitV2PolicyRoundTripsEveryFiniteTake() {
        assertEquals(2, finite().size)
        for (source in finite()) for (take in 0..3) {
            val recipe = MurmurRoundRobinContrastRecipe(source.factoryPatch(), take)
            assertEquals(source.id, recipe.selectionId)
            assertEquals(recipe, MurmurRoundRobinContrastRecipe.fromJsonText(recipe.toJsonText()))
            assertEquals(recipe.toJsonValue(), MurmurRoundRobinContrastRecipe.fromJsonValue(recipe.toJsonValue()).toJsonValue())
            assertEquals("NATIVE_OPERATOR_CONTACT_V2", recipe.toJsonValue().obj().getValue("policy").str())
        }
    }

    @Test fun strictV2CodecAndUnsupportedHeldSourcesAreRefused() {
        val recipe = MurmurRoundRobinContrastRecipe(finite().first().factoryPatch(), 1)
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(recipe.toJsonValue().entries + (key to value))
        for (take in listOf(-1.0, 4.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY))
            assertFails { MurmurRoundRobinContrastRecipe.fromJsonValue(changed("take", JsonValue.Num(take))) }
        assertFails { MurmurRoundRobinContrastRecipe.fromJsonValue(changed("version", JsonValue.Num(1.0))) }
        assertFails { MurmurRoundRobinContrastRecipe.fromJsonValue(changed("version", JsonValue.Num(2.5))) }
        assertFails { MurmurRoundRobinContrastRecipe.fromJsonValue(changed("policy", JsonValue.Str(MurmurRoundRobinRecipe.POLICY))) }
        assertFails { MurmurRoundRobinContrastRecipe.fromJsonValue(changed("extra", JsonValue.Null)) }
        assertFails { MurmurRoundRobinContrastRecipe.fromJsonValue(JsonValue.Obj(recipe.toJsonValue().entries - "sourcePatch")) }
        assertFails { MurmurRoundRobinContrastRecipe.fromJsonValue(changed("selectionId", JsonValue.Str("unknown"))) }
        assertFails { MurmurRoundRobinRecipe.fromJsonText(recipe.toJsonText()) }
        val old = MurmurRoundRobinRecipe(recipe.patch(), 1)
        assertFails { MurmurRoundRobinContrastRecipe.fromJsonText(old.toJsonText()) }
        val held = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch()
        for (take in 0..3) assertFails { MurmurRoundRobinContrastRecipe(held, take) }
        assertFails { MurmurRoundRobinContrastRecipe(recipe.patch().copy(macros = recipe.patch().macros - "HOLD"), 1) }
    }

    @Test fun completePatchIsDefensivelyOwned() {
        val source = finite().first().factoryPatch()
        val before = source.toJsonText()
        val recipe = MurmurRoundRobinContrastRecipe(source, 1)
        (source.macros as MutableMap<String, Float>)["BREATH"] = 0f
        (source.config.ratios as MutableList<Double>)[1] = 8.0
        assertEquals(before, recipe.patch().toJsonText())
        (recipe.patch().config.targets.first().centers as MutableList<Double>)[0] = 100.0
        (recipe.toJsonValue().entries as MutableMap<String, JsonValue>).clear()
        assertEquals(before, recipe.patch().toJsonText())
    }

    @Test fun finiteTakeZeroRetainsTheExactOrdinaryAndV1Outputs() {
        for (source in finite()) {
            val patch = source.factoryPatch()
            val current = MurmurRoundRobinContrastRecipe(patch, 0).render().samples
            assertContentEquals(MurmurRoundRobinRecipe(patch, 0).render().samples, current, source.id)
            assertContentEquals(patch.render().samples, current, source.id)
        }
    }

    @Test fun v1CodecAndLegacyHeldCanaryRemainUnchanged() {
        val patch = finite().first().factoryPatch()
        val old = MurmurRoundRobinRecipe(patch, 2)
        val json = old.toJsonText()
        assertEquals("NATIVE_BREATH_CONTACT_V1", old.toJsonValue().obj().getValue("policy").str())
        assertEquals(1, old.toJsonValue().obj().getValue("version").int())
        assertEquals(json, MurmurRoundRobinRecipe.fromJsonText(json).toJsonText())
        val macros = Murmur.defaults(MurmurVoice.KEY) + mapOf("MOTION" to 1f, "HOLD" to 1f)
        val snip = Murmur.render(MurmurVoice.KEY, macros, midi = 60, velocity = .83f,
            config = Murmur.configurationFor(MurmurVoice.KEY, model = 2))
        val bytes = java.nio.ByteBuffer.allocate(snip.samples.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        snip.samples.forEach { bytes.putFloat(it) }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
        assertEquals("9db1b2be7c1691335f8d7d91e5d659d4a5fd971396d51e33c0d8f779863808f7", hash)
    }

    @Test fun allFourV1FiniteOutputsRetainTheirPreviouslyQualifiedHashes() {
        // Complete retained source from the independent V1 finite-takes proof, not a
        // reconstruction from knob labels. It pins the changed native core's old branch.
        val patch = MurmurPatch.fromJsonText("""{"engine":"MURMUR","version":1,"name":"HOLLOW WIRE NOTE","voice":"PLUCK","macros":{"MOUTH":0.3499999940395355,"EDGE":0.550000011920929,"BREATH":0.10000000149011612,"MOTION":0.3499999940395355,"BODY":0.44999998807907104,"HOLD":0},"midi":60,"velocity":1,"config":{"routing":"PAIRS","ratios":[1,2,2.006,3],"trajectory":"ATTACK","direction":1,"referenceSeconds":0.48,"attack":0.002,"decay":0.24,"sustain":0.08,"release":0.35,"targets":[{"name":"pluck Closed hum","centers":[283.5,840,1680.0000000000002,2860.0000000000005,3800],"widths":[103.49999999999999,186.29999999999998,254.14999999999998,322,488.74999999999994],"gains":[1.1700000000000002,0.7200000000000001,0.325,0.18000000000000002,0.065],"sourceBalance":0.05000000000000001},{"name":"pluck Rounded","centers":[346.5,787.5,1568.0000000000002,2585,3500],"widths":[124.19999999999999,165.6,234.6,322,439.87499999999994],"gains":[1.105,0.81,0.39,0.18000000000000002,0.0975],"sourceBalance":0.098},{"name":"pluck Open","centers":[798,1312.5,2744.0000000000005,3630.0000000000005,4400],"widths":[196.64999999999998,206.99999999999997,293.25,349.59999999999997,508.29999999999995],"gains":[0.8450000000000001,1.26,0.8450000000000001,0.36000000000000004,0.0975],"sourceBalance":0.23000000000000004},{"name":"pluck Narrow","centers":[378,2152.5,3192.0000000000005,4180,4800],"widths":[113.85,238.04999999999998,342.125,386.4,566.9499999999999],"gains":[0.7150000000000001,1.215,0.9750000000000001,0.27,0.078],"sourceBalance":0.13}],"model":4,"body":"HOLLOW_WIRE_V1"}}""")
        assertEquals(MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch(), patch)
        val expected = listOf("6d3a0aae6c66b24e4a89d6e8ee2cafc1d8962618a8fc92b0bbbdc3f36a11c3c9",
            "26ed227cc3452e98614e2601e7f6a75ce8be3a25177b5b82e990476cf68b32ca",
            "43f8d899a95b4bf88188bbb8d83ffdf2f9eb473b48269225d8193a81da7ce001",
            "5ae17e1e7e4179c89b27df0d6a768db5014ea88091943255ac13b1441357c8ed")
        for (take in 0..3) assertEquals(expected[take], floatHash(MurmurRoundRobinRecipe(patch, take).render().samples))
    }

    @Test fun finiteNativeContrastExceedsThePreviousQuietSeedOnlyDifferenceAndReplays() {
        val patch = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()
        val recipes = (0..3).map { MurmurRoundRobinContrastRecipe(patch, it) }
        val rendered = recipes.map { it.render() }
        assertEquals(1, rendered.map { it.frameCount }.toSet().size)
        for (snip in rendered) {
            assertEquals(Dsp.RATE, snip.sampleRate)
            assertEquals(1, snip.channels)
            assertTrue(snip.samples.all { it.isFinite() })
            assertTrue(snip.peak() in .01f..1f)
            assertTrue(abs(snip.samples.sumOf { it.toDouble() } / snip.frameCount) < .005)
            assertTrue(snip.samples.last() == 0f)
        }
        val pitches = rendered.map { snip ->
            val pitch = requireNotNull(Pitch.detect(snip)) { "No periodic C2 body detected" }
            val expected = 440.0 * Math.pow(2.0, (patch.midi - 69) / 12.0)
            val cents = 1200.0 * ln(pitch.hz / expected) / ln(2.0)
            JsonValue.Obj(linkedMapOf("hz" to JsonValue.Num(pitch.hz.toDouble()),
                "confidence" to JsonValue.Num(pitch.confidence.toDouble()), "cents" to JsonValue.Num(cents)))
        }
        val pairs = mutableListOf<JsonValue>()
        for (i in 0..3) for (j in i + 1..3) {
            val residual = gainRemovedResidual(rendered[i].samples, rendered[j].samples)
            // Engineering contrast floor above the previous .0078-.0089 residual.
            // This is not a psychoacoustic threshold or owner listening approval.

            pairs += JsonValue.Obj(linkedMapOf("a" to JsonValue.Num(i.toDouble()), "b" to JsonValue.Num(j.toDouble()),
                "gainRemovedResidual" to JsonValue.Num(residual)))
        }
        assertContentEquals(rendered[2].samples, MurmurRoundRobinContrastRecipe.fromJsonText(recipes[2].toJsonText()).render().samples)
        record(JsonValue.Obj(linkedMapOf("sourcePatch" to patch.toJsonValue(), "policy" to JsonValue.Str(MurmurRoundRobinContrastRecipe.POLICY),
            "recipes" to JsonValue.Arr(recipes.map { it.toJsonValue() }), "pairs" to JsonValue.Arr(pairs), "pitches" to JsonValue.Arr(pitches),
            "frames" to JsonValue.Num(rendered.first().frameCount.toDouble()), "serializedTake2ReplayExact" to JsonValue.Bool(true),
            "heardByOwner" to JsonValue.Bool(false))))
        for (row in pairs) assertTrue(row.obj().getValue("gainRemovedResidual").num() > .025,
            "contrast remains below engineering contrast floor: $row")
        for (row in pitches) {
            assertTrue(abs(row.obj().getValue("cents").num()) <= 50.0, "C2 root moved: $row")
            assertTrue(row.obj().getValue("confidence").num() >= .3, "C2 root confidence too low: $row")
        }
    }

    private fun floatHash(samples: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(samples.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun gainRemovedResidual(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        val aa = a.sumOf { it.toDouble() * it }; val bb = b.sumOf { it.toDouble() * it }
        val ab = a.indices.sumOf { a[it].toDouble() * b[it] }
        val gain = ab / aa.coerceAtLeast(1e-30)
        return sqrt(a.indices.sumOf { val d = b[it] - gain * a[it]; d * d } / bb.coerceAtLeast(1e-30))
    }

    private fun record(value: JsonValue) {
        System.getProperty("snipsnap.murmur.rr.contrast.measurements")?.let {
            val path = Path.of(it); Files.createDirectories(path)
            Files.writeString(path.resolve("contrast-c2.json"), Json.write(value))
        }
    }
}
