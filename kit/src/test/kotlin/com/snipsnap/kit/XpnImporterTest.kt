package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XpnImporterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("xpnimport").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun tone(seed: Int): Snip =
        Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / (10.0 + seed))).toFloat() }, 1, 44_100))

    private fun buildKit(dir: File): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(1))
        WavWriter.write(File(dir, "A03_Hat_01.wav"), tone(2))
        WavWriter.write(File(dir, "A02_Snare_soft.wav"), tone(3))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(4))
        val kit = Kit(
            "Round Trip",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK,
                    level = 0.9f, pan = 0.25f, tuneCoarse = -2, tuneFine = 30, oneShot = true,
                ),
                KitPad(
                    slot = 2, sampleFile = "A02_Snare_01.wav",
                    velocityLayers = listOf(
                        KitLayer("A02_Snare_soft.wav", 1, 63),
                        KitLayer("A02_Snare_01.wav", 64, 127),
                    ),
                ),
                KitPad(slot = 3, sampleFile = "A03_Hat_01.wav", muteGroup = 1, oneShot = false),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `pack then import round-trips the kit`() {
        val kitDir = File(temp, "src")
        val kit = buildKit(kitDir)
        val xpn = File(temp, "Round Trip.xpn")
        XpnPackager.write(kit, kitDir, xpn, Exporters.defaultMeta(kit))

        val result = XpnImporter.import(xpn, File(temp, "in"))
        assertEquals("Round Trip", result.kit.name)
        assertEquals(listOf(1, 2, 3), result.kit.pads.map { it.slot })

        val kick = result.kit.pad(1)!!
        assertEquals("A01_Kick_01.wav", kick.sampleFile)
        assertEquals(0.9f, kick.level)
        assertEquals(0.25f, kick.pan)
        assertEquals(-2, kick.tuneCoarse)
        assertEquals(30, kick.tuneFine)
        assertTrue(kick.oneShot)
        assertEquals(DrumClass.UNKNOWN, kick.drumClass, "import never invents a class")

        val snare = result.kit.pad(2)!!
        assertEquals(2, snare.velocityLayers.size)
        assertEquals("A02_Snare_soft.wav", snare.velocityLayers[0].sampleFile)
        assertEquals(1, snare.velocityLayers[0].velStart)
        assertEquals("A02_Snare_01.wav", snare.sampleFile)

        val hat = result.kit.pad(3)!!
        assertEquals(1, hat.muteGroup)
        assertTrue(!hat.oneShot)

        // Sample bytes survived the zip untouched.
        for (f in listOf("A01_Kick_01.wav", "A02_Snare_soft.wav", "A02_Snare_01.wav", "A03_Hat_01.wav")) {
            assertTrue(
                File(kitDir, f).readBytes().contentEquals(File(result.directory, f).readBytes()),
                "$f changed in transit",
            )
        }

        // The landed folder is a working kit: it re-exports.
        val out = KitExporter.exportProgramFolder(result.kit, result.directory, File(temp, "reexport"))
        assertTrue(out.program.isFile)

        // Second import without overwrite refuses; with it, succeeds.
        assertFailsWith<java.io.IOException> { XpnImporter.import(xpn, File(temp, "in")) }
        XpnImporter.import(xpn, File(temp, "in"), overwrite = true)
    }

    /** A vendor-shaped archive: 1-based instruments, deep folders, root XML. */
    private fun foreignArchive(file: File): File {
        val wavA = File(temp, "Boom.wav").also { WavWriter.write(it, tone(5)) }
        val wavB = File(temp, "Tick.wav").also { WavWriter.write(it, tone(6)) }
        val program = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPCVObject>
              <Program type="Drum">
                <ProgramName>Foreign Kit</ProgramName>
                <Instruments>
                  <Instrument number="1">
                    <Volume>0.800000</Volume>
                    <Pan>0.500000</Pan>
                    <TuneCoarse>0</TuneCoarse>
                    <TuneFine>0</TuneFine>
                    <MuteGroup>0</MuteGroup>
                    <OneShot>True</OneShot>
                    <Layers>
                      <Layer number="1">
                        <VelStart>0</VelStart>
                        <VelEnd>127</VelEnd>
                        <SampleName>Boom</SampleName>
                      </Layer>
                    </Layers>
                  </Instrument>
                  <Instrument number="2">
                    <Volume>0.600000</Volume>
                    <MuteGroup>2</MuteGroup>
                    <OneShot>False</OneShot>
                    <Layers>
                      <Layer number="1">
                        <VelStart>0</VelStart>
                        <VelEnd>127</VelEnd>
                        <SampleName>Tick</SampleName>
                      </Layer>
                    </Layers>
                  </Instrument>
                  <Instrument number="3">
                    <Layers>
                      <Layer number="1"><SampleName></SampleName></Layer>
                    </Layers>
                  </Instrument>
                </Instruments>
              </Program>
            </MPCVObject>
        """.trimIndent()

        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("Expansion.xml", "<Expansion/>".toByteArray())
            put("Foreign Kit.xpm", program.toByteArray())
            put("Data/Samples/Deep/Boom.wav", wavA.readBytes())
            put("Data/Samples/Deeper/Still/Tick.wav", wavB.readBytes())
            put("[Previews]/Foreign Kit.xpm.wav", wavA.readBytes()) // must be ignored
        }
        return file
    }

    @Test
    fun `a vendor-shaped archive imports - 1-based numbering, deep folders`() {
        val xpn = foreignArchive(File(temp, "Foreign.xpn"))
        val result = XpnImporter.import(xpn, File(temp, "foreign-in"))

        assertEquals("Foreign Kit", result.kit.name)
        // No number-0 instrument: 1-based, so instrument 1 is pad slot 1.
        assertEquals(listOf(1, 2), result.kit.pads.map { it.slot })
        assertEquals("Boom.wav", result.kit.pad(1)?.sampleFile)
        assertEquals(0.8f, result.kit.pad(1)!!.level)
        assertEquals(2, result.kit.pad(2)?.muteGroup)
        assertTrue(!result.kit.pad(2)!!.oneShot)
        assertTrue(File(result.directory, "Tick.wav").isFile, "deep-foldered sample found by bare name")
    }

    @Test
    fun `keygroup programs and missing samples are refused with reasons`() {
        val kgXpn = File(temp, "Keys.xpn")
        ZipOutputStream(kgXpn.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Programs/Keys.xpm"))
            zip.write("<MPCVObject><Program type=\"Keygroup\"><ProgramName>Keys</ProgramName></Program></MPCVObject>".toByteArray())
            zip.closeEntry()
        }
        val kg = assertFailsWith<IllegalArgumentException> { XpnImporter.import(kgXpn, File(temp, "x")) }
        assertTrue("keygroup" in kg.message!!)

        val missing = File(temp, "Missing.xpn")
        ZipOutputStream(missing.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Kit.xpm"))
            zip.write(
                """<Program type="Drum"><ProgramName>Kit</ProgramName>
                   <Instrument number="1"><Layers><Layer number="1">
                   <VelStart>0</VelStart><VelEnd>127</VelEnd>
                   <SampleName>Ghost</SampleName></Layer></Layers></Instrument></Program>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        val err = assertFailsWith<IllegalArgumentException> { XpnImporter.import(missing, File(temp, "y")) }
        assertTrue("Ghost" in err.message!!)
    }
}
