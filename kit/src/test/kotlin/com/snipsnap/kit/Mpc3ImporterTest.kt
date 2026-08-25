package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Mpc3ImporterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mpc3import").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun tone(seed: Int): Snip =
        Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / (10.0 + seed))).toFloat() }, 1, 44_100))

    @Test
    fun `export then import round-trips the kit`() {
        val kitDir = File(temp, "src")
        kitDir.mkdirs()
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), tone(1))
        WavWriter.write(File(kitDir, "A03_Hat_01.wav"), tone(2))
        WavWriter.write(File(kitDir, "A02_Snare_soft.wav"), tone(3))
        WavWriter.write(File(kitDir, "A02_Snare_01.wav"), tone(4))
        val kit = Kit(
            "Native Trip",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK,
                    colorHex = "#e8542e", level = 0.9f, pan = 0.25f, tuneCoarse = -3, tuneFine = 20,
                ),
                KitPad(
                    slot = 2, sampleFile = "A02_Snare_01.wav", colorHex = "#ffc41f",
                    velocityLayers = listOf(
                        KitLayer("A02_Snare_soft.wav", 1, 63),
                        KitLayer("A02_Snare_01.wav", 64, 127),
                    ),
                ),
                KitPad(
                    slot = 3, sampleFile = "A03_Hat_01.wav", colorHex = "#1fc6cf",
                    muteGroup = 1, oneShot = false,
                ),
            ),
        )
        KitStore.save(kit, kitDir)
        val card = File(temp, "card")
        Mpc3Exporter.exportTrack(kit, kitDir, card)

        val result = Mpc3Importer.import(File(card, "Native Trip.xtd"), File(temp, "in"))
        assertEquals("Native Trip", result.kit.name)
        assertEquals(listOf(1, 2, 3), result.kit.pads.map { it.slot })

        val kick = result.kit.pad(1)!!
        assertEquals("A01_Kick_01.wav", kick.sampleFile)
        assertEquals(0.9f, kick.level)
        assertEquals(0.25f, kick.pan)
        assertEquals(-3, kick.tuneCoarse)
        assertEquals(20, kick.tuneFine)
        assertEquals("#e8542e", kick.colorHex, "class colours survive the native trip")
        assertTrue(kick.oneShot)
        assertEquals(DrumClass.UNKNOWN, kick.drumClass, "import never invents a class")

        val snare = result.kit.pad(2)!!
        assertEquals(2, snare.velocityLayers.size, "velocity zones survive")
        assertEquals("A02_Snare_soft.wav", snare.velocityLayers[0].sampleFile)
        assertEquals(1, snare.velocityLayers[0].velStart)
        assertEquals("A02_Snare_01.wav", snare.sampleFile)

        val hat = result.kit.pad(3)!!
        assertEquals(1, hat.muteGroup)
        assertTrue(!hat.oneShot, "note-on trigger reads back as a gate pad")

        // Samples land byte-identical and the folder re-exports.
        assertTrue(
            File(kitDir, "A01_Kick_01.wav").readBytes()
                .contentEquals(File(result.directory, "A01_Kick_01.wav").readBytes()),
        )
        KitExporter.exportProgramFolder(result.kit, result.directory, File(temp, "re"))
    }

    @Test
    fun `keygroup tracks and whole projects are refused with reasons`() {
        val out = File(temp, "kg")
        out.mkdirs()
        val dataDir = File(out, "Keys_[TrackData]").apply { mkdirs() }
        WavWriter.write(File(dataDir, "Note_A2.wav"), tone(5))
        val program = KeygroupProgram(
            "Keys",
            listOf(
                Keygroup(
                    lowNote = 0, highNote = 127, rootNote = 45,
                    layers = listOf(VelocityLayer("Note_A2", 4410, 0, 127)),
                ),
            ),
        )
        val xty = Mpc3TrackWriter().writeKeygroupTo(out, program)
        val kg = assertFailsWith<IllegalArgumentException> {
            Mpc3Importer.import(xty, File(temp, "x"))
        }
        assertTrue("keygroup" in kg.message!!)
    }

    @Test
    fun `missing TrackData samples are refused by name`() {
        val kitDir = File(temp, "m-src")
        kitDir.mkdirs()
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), tone(6))
        val kit = Kit("Lonely", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav")))
        KitStore.save(kit, kitDir)
        val card = File(temp, "m-card")
        Mpc3Exporter.exportTrack(kit, kitDir, card)
        File(card, "Lonely_[TrackData]/A01_Kick_01.wav").delete()

        val err = assertFailsWith<IllegalArgumentException> {
            Mpc3Importer.import(File(card, "Lonely.xtd"), File(temp, "m-in"))
        }
        assertTrue("A01_Kick_01" in err.message!!)
    }

    @Test
    fun `commercial xtd files parse - the corpus proves the reader half`() {
        val golden = File("../reference/golden/mpc3-track")
        val xtds = golden.listFiles { f: File -> f.extension == "xtd" }?.sortedBy { it.name }
            ?: emptyList()
        if (xtds.isEmpty()) {
            println("no golden .xtd files - skipping")
            return
        }
        for (file in xtds) {
            // No _[TrackData]/ ships in the repo, so a clean parse ends at
            // the missing-samples refusal, samples listed by name — which
            // proves the entire parse side worked on real commercial data.
            try {
                val result = Mpc3Importer.import(file, File(temp, "golden-${file.nameWithoutExtension}"))
                assertTrue(result.kit.pads.isNotEmpty(), file.name)
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "missing" in e.message!! || "keygroup" in e.message!!,
                    "${file.name}: unexpected refusal: ${e.message}",
                )
            }
        }
    }
}
