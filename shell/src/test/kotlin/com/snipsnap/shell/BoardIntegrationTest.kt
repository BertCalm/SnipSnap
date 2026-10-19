package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.KitExporter
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Pluck
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckVoice
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoardIntegrationTest {
    private val temp = java.nio.file.Files.createTempDirectory("wide-integration").toFile()

    @AfterTest
    fun cleanup() { temp.deleteRecursively() }

    @Test
    fun `copy WIDE uses the destination source and its recipe and the grid names it`() {
        val model = KitBuilderModel.create("Boards", File(temp, "kit"))
        val dry = Pluck.render(PluckVoice.NYLON)
        model.assign(1, dry, DrumClass.TONAL)
        model.assign(2, dry, DrumClass.TONAL)
        val base = PadRecipe(
            patch = PluckPatch("Source kept", PluckVoice.NYLON, Pluck.defaults(PluckVoice.NYLON)),
            fx = FxChain(eq = mapOf("MID" to .55f)),
        ).toJsonValue()
        model.update(2) { it.copy(recipe = base) }
        val source = BoardSheet.apply(model, 1, .7f)
        val sourceRecipe = assertNotNull(source.recipe)
        assertEquals("WIDE 70%", KitDiff.recipeName(sourceRecipe))
        assertEquals("WIDE · 70%", PadSheetBoxes.summaries(source, null)[PadSheetBoxes.Box.TREATMENT])
        assertTrue(PadSheetBoxes.isTouched(source))
        assertIs<RecipeReplay.Plan.Board>(RecipeReplay.plan(sourceRecipe))
        val destination = RecipeReplay.apply(model, 2, sourceRecipe, "NYLON 02")
        assertEquals(.7f, BoardSheet.read(destination.pad.recipe)?.amount)
        assertTrue("ORIGINAL KEPT" in destination.toast)
        BoardSheet.remove(model, 2)
        assertEquals(base, model.pad(2)!!.recipe)
    }

    @Test
    fun `saved wide kit exports its stereo WAV without requiring board support on MPC`() {
        val model = KitBuilderModel.create("Export Board", File(temp, "kit"))
        model.assign(1, Pluck.render(PluckVoice.NYLON), DrumClass.TONAL)
        val kept = BoardSheet.apply(model, 1, 1f)
        model.save()
        val live = File(model.kitDir, kept.sampleFile)
        assertEquals(2, WavReader.read(live).channels)
        val output = KitExporter.exportProgramFolder(model.kit, model.kitDir, File(temp, "export")).directory
        val exported = output.walkTopDown().firstOrNull { it.isFile && it.name == kept.sampleFile }
        assertNotNull(exported)
        assertTrue(live.readBytes().contentEquals(exported.readBytes()))
        assertTrue(output.walkTopDown().any { it.extension == "xpm" })
        // The ordinary kit sidecar still stores the opaque compound recipe.
        val saved = Json.parse(File(model.kitDir, "kit.json").readText())
        assertTrue(Json.write(saved).contains("boardPad"))
    }
}
