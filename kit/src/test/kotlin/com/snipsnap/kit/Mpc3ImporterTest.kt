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
    fun `a payload sample name that traverses is refused, nothing escapes`() {
        // Export a real .xtd, then rewrite its payload so a sample file name
        // tries to climb out of the data folder - the shape a hostile file
        // would carry.
        val kitDir = File(temp, "trav-src").apply { mkdirs() }
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), tone(6))
        val kit = Kit("Trav", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav")))
        KitStore.save(kit, kitDir)
        val card = File(temp, "trav-card")
        Mpc3Exporter.exportTrack(kit, kitDir, card)

        val xtd = File(card, "Trav.xtd")
        val container = com.snipsnap.mpc3.Acvs.read(xtd)
        val hostilePayload = container.payloadText.replace(
            "A01_Kick_01.wav", "../../../../pwned.wav",
        )
        xtd.writeBytes(com.snipsnap.mpc3.Acvs.write(container.header, hostilePayload))
        val canary = File(temp, "pwned.wav").also { it.delete() }

        // The traversal is flattened to its basename ("pwned.wav") - which is
        // then missing from the data folder, so the import refuses by name -
        // and crucially, nothing is ever written outside the destination.
        val err = assertFailsWith<IllegalArgumentException> { Mpc3Importer.import(xtd, File(temp, "trav-in")) }
        assertTrue("pwned.wav" in err.message!! && "missing" in err.message!!, err.message!!)
        assertTrue(!canary.exists(), "nothing written outside the destination")
    }

    @Test
    fun `a legit shared-pool path is flattened and imports`() {
        // Commercial tracks reference "../Samples/Kick.wav" - honest, not
        // hostile. Flattened to the basename, resolved in the data folder.
        val kitDir = File(temp, "pool-src").apply { mkdirs() }
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), tone(6))
        val kit = Kit("Pool", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav")))
        KitStore.save(kit, kitDir)
        val card = File(temp, "pool-card")
        Mpc3Exporter.exportTrack(kit, kitDir, card)

        val xtd = File(card, "Pool.xtd")
        val container = com.snipsnap.mpc3.Acvs.read(xtd)
        val patched = container.payloadText.replace("A01_Kick_01.wav", "../Samples/A01_Kick_01.wav")
        xtd.writeBytes(com.snipsnap.mpc3.Acvs.write(container.header, patched))

        val result = Mpc3Importer.import(xtd, File(temp, "pool-in"))
        assertEquals("A01_Kick_01.wav", result.kit.pad(1)!!.sampleFile)
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
    fun `a whole project imports every drum track as its own kit`() {
        // Stage two kits and a keygroup into one project, our own writer's shape.
        val srcA = File(temp, "pa").apply { mkdirs() }
        WavWriter.write(File(srcA, "A01_Kick_01.wav"), tone(11))
        val kitA = Kit("Drums A", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", colorHex = "#e8542e")))
        KitStore.save(kitA, srcA)

        val srcB = File(temp, "pb").apply { mkdirs() }
        WavWriter.write(File(srcB, "A02_Snare_01.wav"), tone(12))
        val kitB = Kit("Drums B", listOf(KitPad(slot = 2, sampleFile = "A02_Snare_01.wav")))
        KitStore.save(kitB, srcB)

        val card = File(temp, "proj-card").apply { mkdirs() }
        val dataDir = File(card, "Session_[ProjectData]")
        val progA = Mpc3Exporter.stageTrack(kitA, srcA, dataDir)
        val progB = Mpc3Exporter.stageTrack(kitB, srcB, dataDir)
        WavWriter.write(File(dataDir, "Note_A2.wav"), tone(13))
        val keys = KeygroupProgram(
            "Keys",
            listOf(Keygroup(0, 127, 45, listOf(VelocityLayer("Note_A2", 4410, 0, 127)))),
        )
        com.snipsnap.mpc3.Mpc3ProjectWriter().writeTo(
            card, "Session",
            listOf(
                com.snipsnap.mpc3.Mpc3ProjectTrack.Drum(progA),
                com.snipsnap.mpc3.Mpc3ProjectTrack.Keys(keys),
                com.snipsnap.mpc3.Mpc3ProjectTrack.Drum(progB),
            ),
        )

        val result = Mpc3Importer.importProject(File(card, "Session.xpj"), File(temp, "proj-in"))
        assertEquals(listOf("Drums A", "Drums B"), result.kits.map { it.trackName })
        assertEquals("#e8542e", result.kits[0].kit.pad(1)?.colorHex)
        assertEquals(2, result.kits[1].kit.pads.single().slot)
        result.kits.forEach { assertTrue(File(it.directory, "kit.json").isFile) }
        // The keygroup and the infra tracks are skipped and named, not lost.
        assertTrue(result.skipped.keys.any { "Keys" in it }, "${result.skipped}")
        assertTrue(result.skipped.getValue("Keys").contains("keygroup"))
    }

    @Test
    fun `commercial project files parse to named refusals, not crashes`() {
        val golden = File("../reference/golden/mpc3-project")
        // Both generations use .xpj; only ACVS containers are ours to read.
        val xpjs = golden.listFiles { f: File -> f.extension == "xpj" }?.sortedBy { it.name }
            ?.filter { com.snipsnap.mpc3.MpcFormats.detect(it) == com.snipsnap.mpc3.MpcFormat.MPC3_ACVS }
            ?.take(8) ?: emptyList()
        if (xpjs.isEmpty()) {
            println("no golden .xpj files - skipping")
            return
        }
        for (file in xpjs) {
            try {
                val r = Mpc3Importer.importProject(file, File(temp, "gp-${file.nameWithoutExtension}"))
                assertTrue(r.kits.isNotEmpty(), file.name)
            } catch (e: IllegalArgumentException) {
                // No _[ProjectData]/ ships in the repo: every track resolves
                // to a *named* refusal, which proves the whole parse worked.
                assertTrue(
                    "missing" in e.message!! || "keygroup" in e.message!! ||
                        "no drum" in e.message!! || "no pads" in e.message!! ||
                        "no instrument" in e.message!!,
                    "${file.name}: unexpected refusal: ${e.message}",
                )
            }
        }
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
