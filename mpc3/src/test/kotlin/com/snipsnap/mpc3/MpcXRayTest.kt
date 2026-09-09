package com.snipsnap.mpc3

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpcXRayTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mpcxray").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun golden(name: String) = File("../reference/golden/$name")
        .also { assertTrue(it.exists(), "reference corpus missing: ${it.absolutePath}") }

    private fun acvsFile(name: String, objectType: String, payload: String): File {
        val bytes = Acvs.write(AcvsHeader("3.7.0.56", objectType, "json", "Linux"), payload)
        return File(temp, name).apply { writeBytes(bytes) }
    }

    private val drumTrackJson = """
        {"data": {"version": 5, "name": "Funk Kit", "program": {"type": 0,
            "drum": {"instruments": [
                {"layersv": [{"sampleFile": "Kick.wav", "velocityStart": 0, "velocityEnd": 127}],
                 "mixable": {"volume": 0.8, "pan": 0.5}, "coarseTune": -2, "fineTune": 10, "whichMuteGroup": 0},
                {"layersv": []}
            ]}}}}
    """.trimIndent()

    private val keygroupTrackJson = """
        {"data": {"version": 5, "name": "Deep Bass", "program": {"type": 1,
            "drum": {"instruments": [
                {"layersv": [{"sampleName": "Bass_C3"}], "mixable": {"volume": 0.9, "pan": 0.5}}
            ]}}}}
    """.trimIndent()

    @Test
    fun `an ACVS drum track reads its pads, empty slots kept and counted`() {
        val f = acvsFile("Funk Kit.xtd", "SerialisableTrackData", drumTrackJson)
        val reading = MpcXRay.read(f)
        assertTrue("ACVS SerialisableTrackData" in reading.kindLabel, reading.kindLabel)
        assertNull(reading.unreadable)
        val program = reading.programs.single()
        assertEquals("Funk Kit", program.trackName)
        assertTrue(!program.isKeygroup)
        assertEquals(2, program.pads.size, "the empty second slot is kept, not dropped")

        val kick = program.pads[0]
        assertEquals(1, kick.slot)
        assertTrue(kick.hasSample)
        assertEquals("Kick.wav", kick.layers.single().sampleName)
        assertEquals(0.8f, kick.level)
        assertEquals(-2, kick.tuneCoarse)
        assertEquals(10, kick.tuneFine)

        val empty = program.pads[1]
        assertEquals(2, empty.slot)
        assertTrue(!empty.hasSample)
        assertTrue(empty.layers.isEmpty())
        assertNull(empty.level, "an empty slot's fields are null, never a guessed default")
    }

    @Test
    fun `an ACVS keygroup track reads isKeygroup true, zones from the same instrument list`() {
        val f = acvsFile("Deep Bass.xty", "SerialisableTrackData", keygroupTrackJson)
        val program = MpcXRay.read(f).programs.single()
        assertTrue(program.isKeygroup)
        assertEquals("Bass_C3", program.pads.single().layers.single().sampleName)
    }

    @Test
    fun `a field this class doesn't know about is counted, not silently absorbed`() {
        val withExtra = drumTrackJson.replace(
            "\"coarseTune\": -2,",
            "\"coarseTune\": -2, \"aNewSynthField\": 42,",
        )
        val plain = MpcXRay.read(acvsFile("plain.xtd", "SerialisableTrackData", drumTrackJson))
        val extra = MpcXRay.read(acvsFile("extra.xtd", "SerialisableTrackData", withExtra))
        assertEquals(plain.unlabeledFieldCount + 1, extra.unlabeledFieldCount)
        assertTrue(extra.rawTree != null)
    }

    @Test
    fun `real golden ACVS files - a drum kit and a keygroup instrument - both read without a defect`() {
        val drum = MpcXRay.read(golden("mpc3-track/Acoustic-Kit-BFD Funk Kit 95.xtd"))
        assertNull(drum.unreadable, drum.unreadable)
        val drumProgram = drum.programs.single()
        assertTrue(!drumProgram.isKeygroup)
        assertTrue(drumProgram.pads.isNotEmpty())
        assertTrue(drumProgram.pads.any { it.hasSample }, "a real drum kit has at least one loaded pad")

        val kg = MpcXRay.read(golden("mpc3-track/Inst-Bass-NI Bass Artisan.xty"))
        assertNull(kg.unreadable, kg.unreadable)
        val kgProgram = kg.programs.single()
        assertTrue(kgProgram.isKeygroup)
        assertTrue(kgProgram.pads.isNotEmpty(), "zones read the same way drum pads do")
    }

    @Test
    fun `real golden xpm files read as XML, drum and keygroup told apart by the type attribute`() {
        val drum = MpcXRay.read(golden("drum/hiphop-Drum-kit-Mck4 01.xpm"))
        assertEquals("MPC 2 (XML)", drum.kindLabel)
        assertTrue(!drum.programs.single().isKeygroup)
        assertTrue(drum.programs.single().pads.any { it.hasSample })

        val kg = MpcXRay.read(golden("keygroup/Bass-TAB Deep Resonance.xpm"))
        assertTrue(kg.programs.single().isKeygroup)
    }

    @Test
    fun `a xpn pack (a zip of xpm programs) reads every program inside, Programs first`() {
        val xpn = File(temp, "Pack.xpn")
        val program1 = golden("drum/hiphop-Drum-kit-Mck4 01.xpm").readBytes()
        val program2 = golden("keygroup/Bass-TAB Deep Resonance.xpm").readBytes()
        ZipOutputStream(xpn.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Expansion.xml"))
            zip.write("<xml/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Programs/Drum.xpm"))
            zip.write(program1)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Programs/Bass.xpm"))
            zip.write(program2)
            zip.closeEntry()
        }
        val reading = MpcXRay.read(xpn)
        assertTrue(".xpn pack" in reading.kindLabel, reading.kindLabel)
        assertEquals(2, reading.programs.size)
        assertTrue(reading.programs.any { !it.isKeygroup } && reading.programs.any { it.isKeygroup })
    }

    @Test
    fun `a zip with no xpm inside, garbage bytes, and bare JSON all render a readout, never a throw`() {
        val emptyZip = File(temp, "Empty.zip")
        ZipOutputStream(emptyZip.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        val zipReading = MpcXRay.read(emptyZip)
        assertTrue("no .xpm" in zipReading.kindLabel, zipReading.kindLabel)
        assertTrue(zipReading.unreadable!!.contains("no .xpm"))

        val garbage = File(temp, "garbage.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val garbageReading = MpcXRay.read(garbage)
        assertEquals("not recognized", garbageReading.kindLabel)
        assertTrue(garbageReading.unreadable != null)

        val json = File(temp, "kit.json").apply { writeText("""{"name": "Funk", "pads": []}""") }
        val jsonReading = MpcXRay.read(json)
        assertEquals("JSON", jsonReading.kindLabel)
        assertTrue(jsonReading.rawTree != null)
        assertNull(jsonReading.unreadable)
    }

    @Test
    fun `a corrupt gzip is refused in words, never a crash`() {
        val bad = File(temp, "bad.xtd").apply { writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0)) }
        val reading = MpcXRay.read(bad)
        assertTrue("ACVS" in reading.kindLabel)
        assertTrue(reading.unreadable != null)
        assertTrue(reading.programs.isEmpty())
    }

    @Test
    fun `a file past the size ceiling is refused in words, never read`() {
        val huge = File(temp, "huge.xtd")
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(MpcXRay.MAX_FILE_BYTES + 1) }
        val reading = MpcXRay.read(huge)
        assertEquals("not recognized", reading.kindLabel)
        assertTrue(reading.unreadable!!.contains("too large"), reading.unreadable)
    }

    @Test
    fun `the one throw left is a missing file`() {
        assertFailsWith<IllegalArgumentException> { MpcXRay.read(File(temp, "nowhere.xtd")) }
    }
}
