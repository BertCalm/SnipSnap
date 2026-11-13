package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AlloyMaterialResponseTest {
    private val fixtures = File("src/test/resources/alloy-material-body")
    private val mapped = AlloyMaterialPresets.all().associateBy { it.name }

    @Test
    fun `all approved snapshots match resolved fields and public native PCM fingerprints`() {
        val temp = createTempDirectory("alloy-material").toFile()
        try {
            val clips = Json.parse(File(fixtures, "fixtures.json").readText()).obj().getValue("clips").arr()
            assertEquals(36, clips.size)
            for (value in clips) {
                val row = value.obj()
                val expected = AlloyPatch.fromJsonText(File(fixtures, row.getValue("patchPath").str()).readText())
                val reference = row.getValue("reference").bool()
                val patch = if (reference) expected else mapped.getValue(row.getValue("preset").str()).copy(
                    macros = expected.macros, midi = expected.midi, velocity = expected.velocity,
                )
                assertEquals(expected.config, patch.effectiveConfig(), row.getValue("id").str())
                val replay = PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch as AlloyPatch
                assertEquals(patch, replay)
                val wav = File(temp, "render.wav")
                WavWriter.write(wav, replay.render(), WavWriter.BitDepth.PCM_24)
                val hash = MessageDigest.getInstance("SHA-256").digest(wav.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(row.getValue("native").obj().getValue("wavSha256").str(), hash, row.getValue("id").str())
            }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `repeated edits always resolve from the immutable original and upper half returns it directly`() {
        for (source in mapped.values) {
            var edited = source
            for (material in listOf(0f, .25f, .1f, .5f, .75f, 1f, .25f, 0f)) {
                edited = Patches.edited(edited, "Renamed", edited.macros + ("MATERIAL" to material)) as AlloyPatch
                assertEquals(source.config, edited.config)
                assertEquals(source.materialResponse, edited.materialResponse)
                assertEquals(source.copy(macros = edited.macros).effectiveConfig(), edited.effectiveConfig())
                if (material >= .5f) assertSame(edited.config, edited.effectiveConfig())
            }
        }
        assertEquals(.4f, AlloyMaterialPresets.longPrism.copy(macros = AlloyMaterialPresets.longPrism.macros + ("MATERIAL" to .25f)).effectiveConfig().operators[0].gain)
    }

    @Test
    fun `legacy absent and null response leave complete and missing config recipes unchanged`() {
        for (voice in AlloyVoice.entries) {
            val old = AlloyPresets.forVoice(voice).first()
            assertNull(old.materialResponse)
            assertSame(old.config, old.effectiveConfig())
            for (nulls in listOf(false, true)) {
                val fields = LinkedHashMap(old.toJsonValue().entries)
                if (nulls) fields["materialResponse"] = JsonValue.Null
                assertEquals(old, AlloyPatch.fromJsonValue(JsonValue.Obj(fields)))
                if (nulls) fields["config"] = JsonValue.Null else fields.remove("config")
                val minimal = AlloyPatch.fromJsonValue(JsonValue.Obj(fields)) as AlloyPatch
                assertNull(minimal.materialResponse)
                assertEquals(Alloy.configFor(voice), minimal.config)
            }
        }
        assertTrue(AlloyPresets.all().none { it.materialResponse != null })
        assertTrue(AlloyPresets.heldReferences().none { it.materialResponse != null })
    }

    @Test
    fun `native velocity retains optional response and exact anchor`() {
        val patch = AlloyMaterialPresets.longPrism.copy(macros = AlloyMaterialPresets.longPrism.macros + ("MATERIAL" to .25f))
        assertContentEquals(patch.copy(velocity = .35f).render().samples, Velocity.atVelocity(patch, .35f).samples)
    }

    @Test
    fun `invalid descriptor data is refused rather than silently becoming a legacy patch`() {
        val patch = AlloyMaterialPresets.roundedPlug
        val descriptor = patch.materialResponse!!.toJsonValue()
        for ((key, value) in listOf("version" to JsonValue.Num(2.0), "originalWeightPower" to JsonValue.Num(0.0), "cutoffRatio" to JsonValue.Num(Double.NaN), "woodGains" to JsonValue.Arr(emptyList()))) {
            val body = JsonValue.Obj(descriptor.entries + (key to value))
            assertFailsWith<JsonException>(key) { AlloyPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries + ("materialResponse" to body))) }
        }
        assertFailsWith<JsonException> { AlloyPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries + ("materialResponse" to JsonValue.Obj(emptyMap())))) }
    }
}
