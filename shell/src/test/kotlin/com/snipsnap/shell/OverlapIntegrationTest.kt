package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Overlap
import com.snipsnap.synth.OverlapMusicalBank
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.OverlapTopology
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real shelf and pad-writing paths used by SYNTH, including old saved models. */
class OverlapIntegrationTest {
    @Test
    fun `editing saving and promoting a partial old recipe keeps its model note and velocity`() {
        val shelf = createTempDirectory("overlap-shelf").toFile()
        try {
            for (model in listOf(1, 2)) {
                val source = OverlapPatch("MY WEAVE", OverlapTopology.TREFOIL,
                    mapOf("KNOT" to .5f), midi = 41, velocity = .37f, model = model)
                val filled = Patches.edited(source, source.name, Overlap.defaults(source.topology, source.model) + source.macros)
                assertTrue(source.render().samples.contentEquals(filled.render().samples), "filling missing controls must use the saved model's defaults")
                val edited = assertIs<OverlapPatch>(Patches.edited(source, source.name,
                    filled.macros + ("GLOW" to .31f))).copy(midi = 43, velocity = .29f)
                UserPresets.save(shelf, edited, model.toLong())
                val saved = UserPresets.read(shelf).single()
                assertEquals(edited, saved.patch)
                assertEquals(edited, UserPresets.forVoice(UserPresets.read(shelf), "OVERLAP", "TREFOIL").single().patch)
                val line = UserPresets.rosterLine(saved.patch)
                val literal = line.removePrefix("OverlapPatch.fromJsonText(").removeSuffix("),")
                val promoted = OverlapPatch.fromJsonText(Json.parse(literal).str())
                assertEquals(edited, promoted, "promotion must reconstruct all non-macro state")
                assertTrue(edited.render().samples.contentEquals(promoted.render().samples))
                assertEquals("OverlapFactoryPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
                assertEquals("OverlapTopology", UserPresets.voiceEnum("OVERLAP"))
            }
        } finally { shelf.deleteRecursively() }
    }

    @Test
    fun `an edited sound survives send to pad reopen replay and program export`() {
        val temp = createTempDirectory("overlap-pad").toFile()
        try {
            val directory = File(temp, "kit")
            val model = KitBuilderModel.create("Overlap Edit", directory)
            val base = OverlapMusicalBank.all().first { it.id == "common-tide" }.patch
            val edited = assertIs<OverlapPatch>(Patches.edited(base, "MY TIDE",
                base.macros + ("PULSE" to .52f))).copy(midi = 55, velocity = .43f)
            val recipe = PadRecipe(edited)
            val native = recipe.render()
            model.assign(1, native, Overlap.drumClassFor(edited.topology, edited.macros), edited.name)
            model.update(1) { it.copy(recipe = recipe.toJsonValue()) }
            model.save()
            val reopened = KitBuilderModel.open(directory)
            val pad = requireNotNull(reopened.pad(1))
            val stored = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            assertEquals(edited, stored.patch)
            assertNull(stored.fx)
            assertEquals(DrumClass.TONAL, pad.drumClass)
            assertTrue(pad.oneShot, "finite HOLD must not become a held-loop pad")
            val expected = File(temp, "expected.wav")
            WavWriter.write(expected, native)
            assertTrue(expected.readBytes().contentEquals(File(directory, pad.sampleFile).readBytes()), "SEND TO PAD must keep native level and complete tail")
            reopened.assign(2, OverlapMusicalBank.all().first().patch.render(), DrumClass.TONAL, "Replace This")
            val replay = RecipeReplay.apply(reopened, 2, requireNotNull(pad.recipe), "Tide Replay")
            assertTrue(File(directory, pad.sampleFile).readBytes().contentEquals(File(directory, replay.pad.sampleFile).readBytes()))
            assertFalse(Preflight.check(reopened.kit, directory).blocked())
            val exported = KitExporter.exportProgramFolder(reopened.kit, directory, File(temp, "card"))
            assertTrue(File(directory, pad.sampleFile).readBytes().contentEquals(File(exported.directory, pad.sampleFile).readBytes()))
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `fresh tape renders a fixed dry overlap starter with every routing family`() {
        val temp = createTempDirectory("overlap-starter").toFile()
        try {
            val starter = requireNotNull(StarterKits.byId("overlap"))
            assertFalse(starter.seeded)
            assertNull(starter.key, "reference MIDI must not promise a detected harmonic key")
            val kit = starter.render("Overlap Starter", temp)
            assertEquals(16, kit.pads.size)
            val patches = kit.pads.map { pad ->
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                assertNull(recipe.fx)
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertIs<OverlapPatch>(recipe.patch)
            }
            assertEquals(OverlapTopology.entries.toSet(), patches.map { it.topology }.toSet())
            assertFalse(Preflight.check(kit, temp).blocked())
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun `scramble remains deterministic bounded and editable without changing the saved model`() {
        for (topology in OverlapTopology.entries) {
            val macros = Overlap.scramble(topology, Random(83))
            assertEquals(macros, Overlap.scramble(topology, Random(83)))
            assertEquals(Overlap.defaults(topology).keys, macros.keys)
            assertTrue(macros.values.all { it.isFinite() && it in 0f..1f })
            val legacy = OverlapPatch("OLD WEAVE", topology, emptyMap(), model = 1)
            val edited = assertIs<OverlapPatch>(Patches.edited(legacy, legacy.name, macros))
            assertEquals(1, edited.model)
            assertEquals(DrumClass.TONAL, Overlap.drumClassFor(topology, mapOf("HOLD" to 1f)))
        }
    }
}
