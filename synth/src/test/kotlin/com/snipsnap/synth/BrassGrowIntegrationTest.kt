package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Severity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Saved model selection must survive the same paths used by SYNTH and SEND TO PAD. */
class BrassGrowIntegrationTest {
    private fun patch(grow: Float = .75f, held: Boolean = false) = EmbouchurePatch(
        "My brass", EmbouchureVoice.BRASS,
        Embouchure.defaults(EmbouchureVoice.BRASS, Embouchure.BRASS_GROW_MODEL) +
            mapOf("TUNE" to 0f, "GROW" to grow, "HOLD" to if (held) 1f else 0f),
        midi = 60, velocity = .75f, model = Embouchure.BRASS_GROW_MODEL)

    @Test
    fun `grow is an explicit saved brass mode and ordinary recipes remain separate`() {
        val original = patch()
        assertEquals(setOf("TUNE", "GROW", "LENGTH", "HOLD"), original.macros.keys)
        assertFalse("GROW" in Embouchure.defaults(EmbouchureVoice.BRASS))
        val restored = assertIs<EmbouchurePatch>(Patches.fromJsonText(original.toJsonText()))
        assertEquals(original, restored)
        assertEquals(original, PadRecipe.fromJsonText(PadRecipe(original).toJsonText()).patch)
        val edited = assertIs<EmbouchurePatch>(Patches.edited(restored, "Opening brass", restored.macros + ("GROW" to .25f)))
        assertEquals(Embouchure.BRASS_GROW_MODEL, edited.model)
        assertEquals(.25f, edited.macros.getValue("GROW"))
        assertEquals(original.midi, edited.midi)
        assertEquals(original.velocity, edited.velocity)
        assertEquals(original.gesture, edited.gesture)
        assertContentEquals(original.render().samples, restored.render().samples)

        for (voice in EmbouchureVoice.entries.filter { it != EmbouchureVoice.BRASS }) {
            assertFailsWith<IllegalArgumentException> { original.copy(voice = voice) }
            val invalid = JsonValue.Obj(original.toJsonValue().entries + ("voice" to JsonValue.Str(voice.name)))
            assertFailsWith<JsonException> { Patches.fromJsonValue(invalid) }
        }
        assertFailsWith<IllegalArgumentException> {
            EmbouchurePatch("Old mode", EmbouchureVoice.BRASS, Embouchure.defaults(EmbouchureVoice.BRASS) + ("GROW" to .5f), model = 2)
        }
        assertFailsWith<IllegalArgumentException> { original.withMacros(original.macros + ("EFFORT" to .5f)) }
        assertFailsWith<IllegalArgumentException> { original.withMacros(original.macros + ("GROW" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { original.copy(midi = 73) }
        assertFailsWith<IllegalArgumentException> { original.copy(velocity = Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `velocity route retains the saved grow source instead of substituting filtered audio`() {
        val original = patch(.25f)
        assertContentEquals(original.copy(velocity = .3f).render().samples, Velocity.atVelocity(original, .3f).samples)
        assertEquals(.25f, original.macros.getValue("GROW"))
        assertEquals(Embouchure.BRASS_GROW_MODEL, original.model)
    }

    @Test
    fun `finite and held grow pads retain exact recipes on disk and in both export formats`() {
        val root = kotlin.io.path.createTempDirectory("brass-grow-integration").toFile()
        try {
            val patches = listOf(patch(1f), patch(1f, held = true))
            val rendered = patches.map { p -> ArrangedPad(p.render(), Embouchure.drumClassFor(p.voice, p.macros),
                recipe = PadRecipe(p).toJsonValue(), displayName = p.name) }
            assertEquals(listOf(DrumClass.TONAL, DrumClass.LOOP), rendered.map { it.drumClass })
            val work = File(root, "work")
            val kit = KitAssembler.assembleArranged("Brass GROW test", rendered, work)
            val loaded = KitStore.load(work)
            assertEquals(kit.pads, loaded.pads)
            for (index in patches.indices) {
                val recipe = PadRecipe.fromJsonValue(requireNotNull(loaded.pads[index].recipe))
                assertEquals(patches[index], recipe.patch)
                assertContentEquals(rendered[index].snip.samples, recipe.render().samples)
            }
            for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.MPC3_TRACK)) {
                val exported = Exporters.export(format, loaded, work, File(root, format.id), overwrite = true)
                val findings = ExportReadBack.verify(loaded, exported)
                // Main's contract: a program folder's primary is the folder, the MPC3 track's is its file.
                assertTrue(if (format == ExportFormat.PROGRAM_FOLDER) exported.primary.isDirectory else exported.primary.isFile)
                assertTrue(findings.none { it.severity == Severity.FAIL }, "$format: ${findings.joinToString { it.message }}")
                assertEquals(3, findings.count { it.severity == Severity.OK }, "$format must read both pads and the program")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
