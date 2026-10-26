package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StandaloneExportSidecarTest {
    private val temp = Files.createTempDirectory("export-sidecar").toFile()

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    @Test
    fun `both standalone formats retain recipes with actual sanitized sample and layer names`() {
        val source = File(temp, "source").also { it.mkdirs() }
        val names = listOf("Kíck?soft.wav", "Kíck?hard.wav", "Open?hat.wav")
        names.forEachIndexed { index, name ->
            WavWriter.write(File(source, name), Snip(FloatArray(4410) { i ->
                ((index + 1) * .1 * Math.sin(i / 20.0)).toFloat()
            }, 1, 44100))
        }
        // The kit layer stores typed recipe JSON opaquely, including formats
        // introduced by a newer engine. It must carry the complete object.
        val recipe = JsonValue.Obj(linkedMapOf("futureRecipe" to JsonValue.Str("keep this history")))
        val kit = Kit("Editable Export", listOf(
            KitPad(1, names[1], "Saved kick", DrumClass.KICK, "#e8542e", level = 1f,
                tuneCoarse = -2, source = mapOf("file" to "capture.wav", "rawSha256" to "old take"), recipe = recipe,
                velocityLayers = listOf(KitLayer(names[0], 1, 63, "DRY"), KitLayer(names[1], 64, 127, "ECHO"))),
            KitPad(3, names[2], "Saved open hat", DrumClass.HAT_OPEN, level = .8f, muteGroup = 1,
                recipe = recipe, attack = .1f, decay = .2f, cutoff = .7f, humanize = .15f),
        ), tempoBpm = 104f, wear = WearLedger(mileage = 17.0))

        for (native in listOf(false, true)) {
            val dest = File(temp, if (native) "native" else "compatibility")
            val result = if (native) Mpc3Exporter.exportTrack(kit, source, dest, mpc2Twin = true)
                else KitExporter.exportProgramFolder(kit, source, dest)
            val samples = if (native) File(dest, Mpc3TrackWriter.trackDataDirName(kit.name)) else result.directory
            val restored = KitStore.load(samples)
            val expectedNames = mapOf(1 to "K_ck_hard.wav", 3 to "Open_hat.wav")
            val expected = kit.copy(pads = kit.pads.map { pad ->
                val sample = expectedNames.getValue(pad.slot)
                pad.copy(sampleFile = sample,
                    velocityLayers = if (pad.slot == 1) listOf(
                        KitLayer("K_ck_soft.wav", 1, 63, "DRY"), KitLayer("K_ck_hard.wav", 64, 127, "ECHO"),
                    ) else emptyList(),
                    source = pad.source + ("rawSha256" to hash(File(samples, sample))),
                )
            })
            assertEquals(expected, restored)
            assertEquals(3, result.samples.size, "sidecar is metadata, not an audio sample")
            for ((before, after) in kit.pads.zip(restored.pads)) {
                assertContentEquals(File(source, before.sampleFile).readBytes(), File(samples, after.sampleFile).readBytes())
                assertEquals(recipe, after.recipe)
                assertEquals(hash(File(samples, after.sampleFile)), after.source["rawSha256"])
                before.velocityLayers.zip(after.velocityLayers).forEach { (oldLayer, newLayer) ->
                    assertContentEquals(File(source, oldLayer.sampleFile).readBytes(), File(samples, newLayer.sampleFile).readBytes())
                }
            }

            // A source hash can describe a previous take after editing. Export
            // always fingerprints the current copied file and never mutates the
            // caller's saved kit or encodes its audio again.
            assertEquals("old take", kit.pad(1)!!.source["rawSha256"])
            assertNotEquals("old take", restored.pad(1)!!.source["rawSha256"])
            assertTrue(File(samples, "${kit.name}.xpm").isFile)
        }
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
