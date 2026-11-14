package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.*
import com.snipsnap.synth.*
import java.nio.file.Files
import kotlin.test.*

class HalobarRobinTest {
    @Test fun `all six pad voices keep velocity grids through saving replay and export`() {
        val root = Files.createTempDirectory("halobar-robin").toFile()
        try {
            for (voice in HalobarVoice.entries) {
                val patch = HalobarPatch("Hit", voice, mapOf("SPACE" to 0f, "HOLD" to 0f))
                val recipe = PadRecipe(patch).toJsonValue()
                val model = KitBuilderModel.create("H${voice.ordinal}", root.resolve("v${voice.ordinal}"))
                model.assign(1, patch.render(), Halobar.drumClassFor(voice, patch.macros))
                model.update(1) { it.copy(recipe = recipe) }
                val pad = Robin.apply(model, 1, takes = 3, seed = 7, zones = 2)
                model.save()
                val reopened = KitBuilderModel.open(model.kitDir)
                assertEquals(pad.chain, reopened.pad(1)!!.chain)
                val pcm = WavReader.read(model.kitDir.resolve(pad.sampleFile))
                val chain = assertNotNull(pad.chain)
                assertEquals(6, chain.sliceCount)
                assertEquals(2, chain.zones!!.size)
                fun slice(i: Int): FloatArray {
                    val w=chain.window(i,pcm.frameCount.toLong())
                    return pcm.samples.copyOfRange(w.first.toInt(),(w.last+1).toInt())
                }
                assertFalse(slice(0).contentEquals(slice(1)))
                assertFalse(slice(1).contentEquals(slice(2)))
                val original = model.kitDir.resolve(pad.sampleFile).readBytes()
                Robin.undo(reopened, 1)
                RecipeReplay.apply(reopened, 1, recipe, "Hit")
                Robin.apply(reopened, 1, takes = 3, seed = 7, zones = 2)
                assertContentEquals(original, model.kitDir.resolve(pad.sampleFile).readBytes())
                for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.MPC3_TRACK)) {
                    val exported = Exporters.export(format, reopened.kit, reopened.kitDir,
                        root.resolve("export-${voice.ordinal}-${format.id}"))
                    assertFalse(ExportReadBack.verify(reopened.kit, exported).blocked())
                }
            }
        } finally { root.deleteRecursively() }
    }
}
