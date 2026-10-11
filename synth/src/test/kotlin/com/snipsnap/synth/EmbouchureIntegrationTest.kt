package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Severity
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbouchureIntegrationTest {
    @Test
    fun `saved driver gesture note velocity and model regenerate exact audio through the dispatcher`() {
        for (voice in EmbouchureVoice.entries) for (model in listOf(1, Embouchure.MODEL_VERSION)) {
            val patch = EmbouchurePatch("Saved effort", voice, Embouchure.defaults(voice) + mapOf(
                "TUNE" to 0f, "EFFORT" to .62f, "REED" to .71f, "SHAPE" to .19f,
                "GRAIN" to .64f, "LENGTH" to .23f), midi = Embouchure.rootMidi(voice) + 7,
                velocity = .37f, model = model, gesture = EmbouchureGesture.PRESSURE_RELEASE)
            val restored = assertIs<EmbouchurePatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, restored)
            val fields = patch.toJsonValue().entries
            assertTrue(listOf("model", "midi", "velocity", "driver", "gesture").all { it in fields })
            assertEquals(JsonValue.Str(patch.driver.name), fields["driver"])
            assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            assertContentEquals(patch.render().samples, restored.render().samples, "$voice saved model changed the rendered note")
            val edited = assertIs<EmbouchurePatch>(Patches.edited(restored, "Edited effort", restored.macros + ("EFFORT" to .41f)))
            assertEquals(patch.midi, edited.midi)
            assertEquals(patch.velocity, edited.velocity)
            assertEquals(patch.gesture, edited.gesture)
            assertEquals(patch.model, edited.model)
        }
    }

    @Test
    fun `incompatible saved driver gesture and renderer models are refused`() {
        val patch = EmbouchurePresets.all().first()
        for ((field, value) in listOf(
            "driver" to JsonValue.Str("NOT_A_DRIVER"),
            "driver" to JsonValue.Str("LIP"), // valid driver, incompatible with the saved REED voice
            "gesture" to JsonValue.Str("NOT_A_GESTURE"),
            "model" to JsonValue.Num(99.0),
        )) {
            val changed = JsonValue.Obj(patch.toJsonValue().entries + (field to value))
            assertFailsWith<JsonException>("unknown $field must not silently select another model") { Patches.fromJsonValue(changed) }
        }
        val baseline = EmbouchurePatch("Initial defaults", EmbouchureVoice.REED, Embouchure.defaults(EmbouchureVoice.REED))
        val older = JsonValue.Obj(baseline.toJsonValue().entries - setOf("midi", "velocity", "model", "driver", "gesture"))
        assertEquals(baseline.copy(model = 1), Patches.fromJsonValue(older), "omitted new fields retain the initial renderer contract")
        val nullModel = JsonValue.Obj(baseline.toJsonValue().entries + ("model" to JsonValue.Null))
        assertEquals(baseline.copy(model = 1), Patches.fromJsonValue(nullModel), "a null model retains the initial renderer contract")
        assertFailsWith<IllegalArgumentException> { baseline.copy(velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { baseline.copy(midi = Embouchure.midiRange(baseline.voice).last + 1) }
    }

    @Test
    fun `the native velocity layer route retains gesture and controls`() {
        for (model in listOf(1, Embouchure.MODEL_VERSION)) {
            val patch = EmbouchurePresets.all().first { it.name == "Short Overblow" }.copy(model = model)
            assertContentEquals(patch.copy(velocity = .3f).render().samples, Velocity.atVelocity(patch, .3f).samples,
                "model=$model native velocity must play the saved pressure gesture rather than substitute a low-pass treatment")
            val restored = assertIs<EmbouchurePatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch.macros, restored.macros)
            assertEquals(model, restored.model)
            val variants = Velocity.variantsAt(patch.render(), patch)
            assertEquals(2, variants.size)
            assertTrue(variants.all { it.sampleRate == Dsp.RATE && it.channels == 1 && it.samples.all { sample -> sample.isFinite() } })
            assertTrue(!variants[0].samples.contentEquals(variants[1].samples), "velocity zones share the same take")
        }
    }

    @Test
    fun `all sixteen dry kit recipes survive disk and both MPC program formats`() {
        val root = java.nio.file.Files.createTempDirectory("embouchure-integration").toFile()
        try {
            val arranged = SynthKits.embouchure()
            assertEquals(16, arranged.size)
            assertTrue(arranged.all { it != null && it.recipe != null })
            val patches = arranged.mapIndexed { index, pad ->
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad!!.recipe))
                assertNull(recipe.fx, "pad ${index + 1} must show the dry engine identity")
                assertIs<EmbouchurePatch>(recipe.patch)
            }
            assertEquals(EmbouchureVoice.entries.toSet(), patches.map { it.voice }.toSet())
            assertEquals(listOf(0, 3, 5, 7, 10, 12, 15, 17), patches.take(8).map { it.midi - Embouchure.rootMidi(it.voice) })
            assertTrue(patches.take(8).all { it.macros.getValue("TUNE") == 0f })
            assertTrue(arranged.take(15).all { it!!.drumClass == DrumClass.TONAL })
            assertEquals(DrumClass.LOOP, arranged.last()!!.drumClass)
            assertEquals("Held Effort", patches.last().name)

            val work = File(root, "work")
            val kit = KitAssembler.assembleArranged("Embouchure Acceptance", arranged, work)
            val loaded = KitStore.load(work)
            assertEquals(kit.pads, loaded.pads)
            for (index in listOf(0, 15)) {
                val recipe = PadRecipe.fromJsonValue(requireNotNull(loaded.pads[index].recipe))
                assertContentEquals(arranged[index]!!.snip.samples, recipe.render().samples, "pad ${index + 1} cannot regenerate from kit.json")
            }
            for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.MPC3_TRACK)) {
                val destination = File(root, "export-${format.id}")
                val exported = Exporters.export(format, loaded, work, destination, overwrite = true)
                assertTrue(exported.primary.isFile, "$format has no program")
                val findings = ExportReadBack.verify(loaded, exported)
                assertTrue(findings.none { it.severity == Severity.FAIL }, "$format: ${findings.joinToString { it.message }}")
                assertEquals(17, findings.count { it.severity == Severity.OK }, "$format must read all sixteen pads")
                val wavs = destination.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }.toList()
                assertEquals(16, wavs.size, "$format sample references must resolve to sixteen WAVs")
                for (wav in wavs) {
                    val info = WavInfo.read(wav)
                    assertEquals(Dsp.RATE, info.sampleRate)
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(1, info.channels)
                    assertTrue(info.frameCount > 0)
                    assertTrue(WavReader.read(wav).samples.all { it.isFinite() && it in -1f..1f })
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
