package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.NacrePatch
import com.snipsnap.synth.NacrePresets
import com.snipsnap.synth.NacreVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NacreStarterIntegrationTest {
    @Test
    fun `fresh tape offers a fixed harp bank with three complete touch zones on every pad`() {
        val root = createTempDirectory("nacre-fresh-tape").toFile()
        try {
            val dir = File(root, "kit")
            val starter = assertNotNull(StarterKits.byId("nacre"))
            assertEquals("NACRE", starter.displayName)
            assertFalse(starter.seeded)
            assertEquals(KeySpec.parse("Cminpent"), starter.key)
            val kit = starter.render("Nacre Touch Test", dir, seed = -99)
            assertEquals(kit, KitStore.load(dir), "the saved kit must retain every layer and source field")
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertEquals(starter.key, kit.key)
            assertFalse(Preflight.check(kit, dir).blocked())

            // The starter is the model-3 harp bank; the opt-in model-4 CONTACT presets add no starter.
            val factory = NacrePresets.all().filter { it.model == 3 }
            val patches = kit.pads.map { pad ->
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                val patch = assertIs<NacrePatch>(recipe.patch)
                val start = factory.single { it.voice == patch.voice && it.macros == patch.macros }
                assertEquals(start.copy(name = patch.name, midi = patch.midi, velocity = 1f), patch)
                assertEquals(NacreVoice.PEARL, patch.voice)
                assertEquals(3, patch.model)
                assertEquals(7L, patch.seed, "a fixed starter keeps its authored seed, independent of the menu seed")
                assertEquals(0f, patch.macros.getValue("HOLD"))
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertFalse(pad.loop)
                assertEquals(0, pad.muteGroup)
                assertEquals(0, pad.tuneCoarse)
                assertEquals(0, pad.tuneFine)
                assertNull(pad.chain)
                assertNull(recipe.fx)
                assertNull(recipe.board)
                assertNull(recipe.pedals)
                assertNull(recipe.treatment)
                assertNull(recipe.amount)
                assertNull(recipe.terminalFadeMs)
                assertEquals(listOf(1 to 41, 42 to 84, 85 to 127),
                    pad.velocityLayers.map { it.velStart to it.velEnd })
                assertEquals(pad.sampleFile, pad.velocityLayers.last().sampleFile)
                assertEquals(3, pad.velocityLayers.map { it.sampleFile }.toSet().size)
                val framesByFile = linkedMapOf<String, Long>()
                for (layer in pad.velocityLayers) {
                    val info = WavInfo.read(File(dir, layer.sampleFile))
                    assertEquals(44_100, info.sampleRate)
                    assertEquals(1, info.channels)
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(264_600L, info.frameCount, "every touch zone retains its complete finite ending")
                    framesByFile[layer.sampleFile] = info.frameCount
                }
                // MIDI zero is handled as note-off upstream and belongs to no sample zone.
                assertTrue(pad.velocityLayers.none { 0 in it.velStart..it.velEnd })
                for (midiVelocity in 1..127) {
                    val zone = when {
                        midiVelocity <= 41 -> 0
                        midiVelocity <= 84 -> 1
                        else -> 2
                    }
                    val expectedFile = pad.velocityLayers[zone].sampleFile
                    val hit = assertNotNull(PadHit.resolve(pad, midiVelocity / 127f, 0) { framesByFile[it] })
                    assertEquals(expectedFile, hit.sampleFile,
                        "pad ${pad.slot} at MIDI velocity $midiVelocity selected the wrong native touch")
                    assertEquals(0L, hit.startFrame)
                    assertEquals(framesByFile.getValue(expectedFile), hit.endFrameExclusive)
                    assertEquals(1.0, hit.pitchRatio)
                    assertEquals(-1L, hit.loopStartFrame)
                }
                patch
            }
            assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
            assertTrue(patches.take(8).all { it.macros == factory.first().macros })
            assertEquals(factory.map { it.macros }.toSet(), patches.map { it.macros }.toSet())

            // The main zone retains the ordinary saved hard-strike performance.
            // Softer zones have their own recorded excitation and sampling contract.
            val first = kit.pads.first()
            val replay = WavWriter.write(File(root, "replay.wav"),
                PadRecipe.fromJsonValue(assertNotNull(first.recipe)).render())
            assertContentEquals(File(dir, first.sampleFile).readBytes(), replay.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
