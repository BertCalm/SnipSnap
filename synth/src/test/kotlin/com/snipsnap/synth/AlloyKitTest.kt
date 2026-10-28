package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

class AlloyKitTest {
    @Test
    fun `sixteen dry hybrid pads carry exact roots and regenerated material recipes into MPC export`() {
        val dir = createTempDirectory("alloy-kit").toFile()
        try {
            val pads = SynthKits.alloy()
            assertEquals(16, pads.size)
            val patches = pads.mapIndexed { i, nullable ->
                val pad = nullable ?: error("empty pad ${i + 1}")
                val recipe = PadRecipe.fromJsonValue(pad.recipe ?: error("missing recipe ${i + 1}"))
                val patch = assertIs<AlloyPatch>(recipe.patch)
                assertNull(recipe.fx, "Alloy identity must remain dry")
                assertContentEquals(pad.snip.samples, recipe.render().samples, "pad ${i + 1} lost its generated configuration")
                assertEquals(if (i >= 14) DrumClass.LOOP else DrumClass.TONAL, pad.drumClass)
                patch
            }
            assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
            assertTrue(patches.take(8).all { it.voice == AlloyVoice.KEY })
            val key = AlloyPresets.forVoice(AlloyVoice.KEY).single { it.name == "Long Prism" }
            assertEquals(patches.take(8).mapIndexed { i, patch ->
                key.copy(name = "Alloy ${i + 1}", midi = patch.midi)
            }, patches.take(8), "the scale must retain the approved ringing KEY configuration")
            assertEquals(AlloyVoice.entries.toSet(), patches.drop(8).take(6).map { it.voice }.toSet())
            val roleNames = listOf("Hollow Rail", "Reed Switch", "Open Chime",
                "Bright Stamp", "Beating Glass", "Velvet Rise")
            assertEquals(roleNames.map { name -> AlloyPresets.all().single { it.name == name } },
                patches.drop(8).take(6), "the starter kit must expose the approved authored roles")
            assertEquals(listOf(AlloyVoice.STRING, AlloyVoice.SOFT), patches.takeLast(2).map { it.voice })
            assertEquals(listOf("Held Filament", "Endless Ember"), patches.takeLast(2).map { it.name })
            assertEquals(listOf("Held Filament", "Endless Ember").map { name ->
                AlloyPresets.heldReferences().single { it.name == name }
            }, patches.takeLast(2), "the existing held utility pads must keep their complete recipes")
            val kitDir = File(dir, "kit")
            val kit = KitAssembler.assembleArranged("Alloy Audition", pads, kitDir)
            assertTrue(!Preflight.check(kit, kitDir).blocked())
            val saved = KitStore.load(kitDir)
            assertEquals(kit, saved, "native kit.json roundtrip")
            val result = KitExporter.exportProgramFolder(kit, kitDir, File(dir, "mpc"))
            assertEquals(16, result.samples.size)
            for (wav in result.samples) {
                val info = WavInfo.read(wav)
                assertEquals(44_100, info.sampleRate)
                assertEquals(1, info.channels)
            }
            val program = result.program.readText()
            assertTrue("<SampleName>A01_Tonal_01</SampleName>" in program)
            assertTrue("<SampleName>A16_Loop_02</SampleName>" in program)
        } finally {
            dir.deleteRecursively()
        }
    }
}
