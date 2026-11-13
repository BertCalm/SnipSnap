package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.kit.KitStore
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.*

class TempestKitTest {
    @Test fun `sixteen dry recipes retain their exact notes audio and measured classes`() {
        val pads = SynthKits.tempestTuned()
        assertEquals(16, pads.size)
        val notes = listOf(28, 31, 33, 35, 38, 40, 43, 45)
        for ((i, nullable) in pads.withIndex()) {
            val pad = assertNotNull(nullable)
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val patch = assertIs<TempestPatch>(recipe.patch)
            assertEquals(2, patch.model)
            assertEquals(0f, patch.macros["HOLD"])
            assertNull(recipe.fx)
            assertNull(recipe.board)
            assertTrue(pad.oneShot && pad.softVariants.isEmpty())
            assertContentEquals(pad.snip.samples, recipe.render().samples)
            assertEquals(Classifier.classify(pad.snip).drumClass, pad.drumClass)
            if (i < 8) {
                assertEquals(TempestVoice.KICK, patch.voice)
                assertEquals(notes[i], Tempest.midiFor(patch.macros.getValue("TUNE")))
                assertEquals(notes[i].toString(), pad.source["rootMidi"])
            }
        }
        assertEquals(listOf("RUST", "BREAK", "SHARD", "WIRE", "GRAIN", "LOW", "BURR", "TRAIL"),
            pads.drop(8).map { it!!.displayName })
    }

    @Test fun `both MPC formats export verified samples and exact editable recipes`() {
        val root = kotlin.io.path.createTempDirectory("tempest-kit-test").toFile()
        try {
            val folder = File(root, "kit")
            val archive = TempestKitGenerator.generate(folder, fullKit = false)
            assertTrue(archive.length() > 0)
            val sidecar = folder.walkTopDown().single { it.name == "kit.json" }.parentFile
            val kit = KitStore.load(sidecar)
            assertEquals((1..16).toList(), kit.pads.map { it.slot }.sorted())
            assertEquals(1, folder.walkTopDown().count { it.extension == "xtd" })
            assertEquals(1, folder.walkTopDown().count { it.extension == "xpm" })
            assertEquals(16, folder.walkTopDown().count { it.extension == "wav" })
            for (pad in kit.pads) {
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                val expected = File(root, "replay.wav")
                WavWriter.write(expected, recipe.render(), WavWriter.BitDepth.PCM_24)
                assertContentEquals(expected.readBytes(), File(sidecar, pad.sampleFile).readBytes(), pad.displayName)
                assertTrue(pad.oneShot && !pad.loop && pad.velocityLayers.isEmpty())
            }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(16, entries.count { it.name.endsWith(".wav") })
                assertTrue(entries.all { it.name.startsWith("SnipSnap Tempest Kit/") && ".." !in it.name })
                assertTrue(entries.any { it.name.endsWith("SHA256SUMS.txt") })
            }
        } finally { root.deleteRecursively() }
    }
}
