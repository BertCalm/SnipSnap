package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Halobar
import com.snipsnap.synth.HalobarPatch
import com.snipsnap.synth.HalobarPresets
import com.snipsnap.synth.HalobarVoice
import com.snipsnap.synth.PadRecipe
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class HalobarStarterTest {
    @Test fun `the three voice starter preserves its key recipes and one shot delivery through both MPC exports`() {
        val root=createTempDirectory("halobar-starter").toFile()
        try {
            val starter=assertNotNull(StarterKits.byId("halobar"))
            val dir=root.resolve("source")
            val kit=starter.render("Halobar Check",dir)
            assertEquals(KeySpec.parse("Cminpent"),kit.key)
            assertEquals(kit,KitStore.load(dir))
            assertEquals(16,kit.pads.size)
            assertFalse(Preflight.check(kit,dir).blocked())
            val voices=kit.pads.map { pad ->
                val recipe=PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                val patch=assertIs<HalobarPatch>(recipe.patch)
                assertEquals(1,patch.model)
                assertNull(recipe.fx)
                assertTrue(pad.oneShot)
                assertEquals(Halobar.drumClassFor(patch.voice,patch.macros),pad.drumClass)
                val audio=WavReader.read(dir.resolve(pad.sampleFile))
                assertEquals(1,audio.channels)
                assertEquals(44100,audio.sampleRate)
                patch.voice
            }.toSet()
            assertEquals(HalobarPresets.acceptedVoices.toSet(),voices)
            for(format in listOf(ExportFormat.PROGRAM_FOLDER,ExportFormat.MPC3_TRACK)) {
                val result=Exporters.export(format,kit,dir,root.resolve(format.id))
                val findings=ExportReadBack.verify(kit,result)
                assertFalse(findings.blocked(),"$format: ${findings.joinToString { it.message }}")
            }
            val model=KitBuilderModel.open(dir)
            for(pad in listOf(kit.pads.first(),kit.pads.last())) {
                val original=dir.resolve(pad.sampleFile).readBytes()
                val replay=RecipeReplay.apply(model,pad.slot,assertNotNull(pad.recipe),pad.displayName)
                assertContentEquals(original,dir.resolve(replay.pad.sampleFile).readBytes())
            }
        } finally { root.deleteRecursively() }
    }
}
