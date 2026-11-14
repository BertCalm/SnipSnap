package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OsteriaKitTest {
    @Test
    fun `the playable bank retains note recipes and real excitation zones through both MPC exports`() {
        val temp = java.nio.file.Files.createTempDirectory("osteria-kit").toFile()
        try {
            val arranged = SynthKits.osteria()
            assertEquals(16, arranged.size)
            val patches = arranged.mapIndexed { index, nullable ->
                val pad = assertNotNull(nullable, "pad ${index + 1} is empty")
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                val patch = assertIs<OsteriaPatch>(recipe.patch)
                assertEquals(null, recipe.fx, "the source color should remain dry")
                assertEquals(2, patch.config.model, "legacy phrases must not enter the note bank")
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot, "the complete authored note and release should play once")
                assertEquals(patch.name, pad.displayName, "the rows must be named on the phone")
                assertEquals(if (index == 15) 1.0 else 3.0, patch.config.seconds)
                assertEquals((patch.config.seconds * 44100).toInt(), pad.snip.frameCount)
                assertEquals(2, pad.snip.channels)
                assertTrue(pad.snip.samples.all { it.isFinite() && kotlin.math.abs(it) < 1f })
                assertTrue(pad.snip.peak() > .001f, "pad ${index + 1} is silent")
                assertEquals(if (index < 12) 2 else 0, pad.softVariants.size)
                patch
            }
            val presetNames = listOf("Anchored Note", "Evolving Note", "Lingering Color")
            for ((row, presetName) in presetNames.withIndex()) {
                val preset = OsteriaPresets.all().first { it.name == presetName }
                for ((column, midi) in listOf(48, 51, 55, 60).withIndex()) {
                    val patch = patches[row * 4 + column]
                    assertEquals(midi, patch.midi)
                    assertEquals(1f, patch.velocity)
                    assertEquals(preset.macros, patch.macros)
                    assertEquals(preset.config.copy(seconds = 3.0), patch.config, "motion and articulation must survive transposition")
                }
            }
            assertEquals(listOf(.25f, .60f, 1f, .8f), patches.drop(12).map { it.velocity })
            assertTrue(patches.drop(12).all { it.midi == 48 })

            // A source-render comparison catches substituting gain-scaled hard samples
            // for true velocity excitation, even when the export's zone table is valid.
            val first = arranged.first()!!
            assertContentEquals(patches.first().copy(velocity = .25f).render().samples, first.softVariants[0].samples)
            assertContentEquals(patches.first().copy(velocity = .60f).render().samples, first.softVariants[1].samples)

            val kitDir = File(temp, "kit")
            val key = KeySpec.parse("Cminpent")
            val kit = KitAssembler.assembleArranged("SnipSnap Osteria Kit", arranged, kitDir, key)
            assertTrue(kit.pads.all { it.oneShot && it.muteGroup == 0 }, "pitched notes must not inherit drum choking")
            for ((index, pad) in kit.pads.withIndex()) {
                assertEquals(if (index < 12) 3 else 0, pad.velocityLayers.size)
                if (index < 12) {
                    assertEquals(listOf(1 to 41, 42 to 84, 85 to 127), pad.velocityLayers.map { it.velStart to it.velEnd })
                }
            }
            val exported = OsteriaKitGenerator.export(kit, kitDir, File(temp, "exports"))
            assertTrue(exported.program.isFile && exported.program.extension == "xtd")
            assertEquals(40, exported.samples.size, "sixteen main samples and twenty-four excitation variants")
            val dataDir = exported.samples.first().parentFile
            assertTrue(File(dataDir, "${kit.name}.xpm").isFile)
            assertEquals(kit, KitStore.load(dataDir), "the shipped native sidecar must retain every base recipe and sample zone")
            for (file in exported.samples) {
                val info = WavInfo.read(file)
                assertEquals(44100, info.sampleRate)
                assertEquals(2, info.channels)
                assertEquals(24, info.bitsPerSample)
                assertContentEquals(File(kitDir, file.name).readBytes(), file.readBytes(), "export altered ${file.name}")
            }
            val saved = PadRecipe.fromJsonValue(KitStore.load(dataDir).pads[8].recipe!!)
            assertContentEquals(arranged[8]!!.snip.samples, saved.render().samples, "saved memory, root and motion must rebuild the same sound")

            ZipFile(File(temp, "exports/SnipSnap-Osteria-MPC2.zip")).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(40, names.count { it.endsWith(".wav") })
                assertTrue(names.any { it.endsWith("/kit.json") })
                assertTrue(names.any { it.endsWith(".xpm") })
                assertFalse(names.any { it.endsWith(".xtd") })
            }
            ZipFile(File(temp, "exports/SnipSnap-Osteria-Kit.zip")).use { zip ->
                assertTrue(zip.entries().asSequence().any { it.name.endsWith(".xtd") })
            }
        } finally {
            temp.deleteRecursively()
        }
    }
}
