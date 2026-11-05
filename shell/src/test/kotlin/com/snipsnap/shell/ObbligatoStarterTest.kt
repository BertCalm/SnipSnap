package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.ObbligatoKits
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A menu-created duet must retain the same playable and editable kit after reopening. */
class ObbligatoStarterTest {
    @Test
    fun `Fresh Tape keeps the approved duet rows native velocity zones and regenerable audio`() {
        val starter = assertNotNull(StarterKits.byId("obbligato"))
        assertEquals("OBBLIGATO", starter.displayName)
        assertFalse(starter.seeded)
        val directory = createTempDirectory("obbligato-starter").toFile()
        try {
            val created = starter.render("Obbligato proof", directory, seed = 17)
            val reopened = KitBuilderModel.open(directory).kit
            assertEquals(created, reopened, "opening the newly created kit changed its saved metadata")
            assertEquals("C minor pentatonic", reopened.key?.label)
            assertEquals((1..16).toList(), reopened.pads.map { it.slot })
            val findings = Preflight.check(reopened, directory)
            assertFalse(findings.blocked(), "the menu-created kit fails preflight: $findings")

            val expected = ObbligatoKits.patches()
            val samples = linkedSetOf<String>()
            for (pad in reopened.pads) {
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                val patch = assertIs<ObbligatoPatch>(recipe.patch)
                assertEquals(expected[pad.slot - 1], patch,
                    "pad ${pad.slot} lost its relationship, note, strike, seed or accepted renderer")
                assertEquals(10, patch.model)
                assertNull(recipe.fx, "the approved duet is dry")
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot && !pad.loop, "a finite duet must not turn into a sustain loop")
                assertEquals(0, pad.muteGroup)
                assertEquals(0, pad.tuneCoarse)
                assertEquals(0, pad.tuneFine)
                assertEquals(listOf(1 to 41, 42 to 84, 85 to 127),
                    pad.velocityLayers.map { it.velStart to it.velEnd })
                assertEquals(pad.sampleFile, pad.velocityLayers.last().sampleFile)
                assertEquals(listOf("0.3", "0.6", "0.8"),
                    listOf("velocitySoft", "velocityMid", "velocityMain").map { pad.source[it] })
                assertEquals("peak-match-main", pad.source["velocityNormalization"])
                assertEquals("10", pad.source["model"])

                val main = WavReader.read(File(directory, pad.sampleFile))
                for (layer in pad.velocityLayers) {
                    samples += layer.sampleFile
                    val file = File(directory, layer.sampleFile)
                    assertTrue(file.isFile, "pad ${pad.slot} has a missing native velocity sample")
                    val info = WavInfo.read(file)
                    val audio = WavReader.read(file)
                    assertEquals(44_100, info.sampleRate)
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(1, info.channels)
                    assertEquals(main.frameCount.toLong(), info.frameCount)
                    assertTrue(audio.samples.all { it.isFinite() && it in -1f..1f })
                    assertEquals(0f, audio.samples.last(), "a native zone truncates its finite ending")
                    assertTrue(abs(audio.peak() - main.peak()) < 2e-6f,
                        "pad ${pad.slot} loses its host-velocity peak matching after reopening")
                }

                // Rebuild one saved main strike per relationship, through the recipe
                // loaded from disk, rather than merely checking the menu's patch list.
                if (pad.slot in listOf(1, 5, 9, 13)) {
                    val regenerated = recipe.render()
                    assertEquals(regenerated.frameCount, main.frameCount)
                    assertTrue(main.samples.indices.all {
                        abs(regenerated.samples[it] - main.samples[it]) < 2.4e-7f
                    }, "pad ${pad.slot} no longer regenerates within PCM24 quantization")
                }
            }
            assertEquals(48, samples.size)
            assertEquals(samples, directory.listFiles().orEmpty()
                .filter { it.extension == "wav" }.map { it.name }.toSet(),
                "the menu-created kit has missing or unreferenced velocity samples")
        } finally {
            directory.deleteRecursively()
        }
    }
}
