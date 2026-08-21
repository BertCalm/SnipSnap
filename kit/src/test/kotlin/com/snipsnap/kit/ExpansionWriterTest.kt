package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpansionWriterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("expansion").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun buildKit(dir: File): Kit {
        dir.mkdirs()
        val snip = Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / 20.0)).toFloat() }, 1, 44_100)
        WavWriter.write(File(dir, "A01_Kick_01.wav"), Cleanup.process(snip))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), Cleanup.process(snip))
        val kit = Kit(
            "Test Pack Kit",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    private val meta = ExpansionMeta(
        title = "Test Pack",
        manufacturer = "SnipSnap",
        version = "1.0.0",
        identifier = "app.snipsnap.testpack",
        description = "Two pads & a promise",
    )

    @Test
    fun `writes the documented expansion layout`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        val drive = File(temp, "drive")

        val result = ExpansionWriter.write(kit, kitDir, drive, meta, artworkPng = byteArrayOf(1, 2, 3))

        assertEquals(File(drive, "Expansions/Test Pack"), result.directory)
        assertTrue(File(result.directory, "Expansion.xml").isFile)
        assertTrue(File(result.directory, "Programs/Test Pack Kit.xpm").isFile)
        assertTrue(File(result.directory, "Samples/A01_Kick_01.wav").isFile)
        assertTrue(File(result.directory, "Samples/A02_Snare_01.wav").isFile)
        assertEquals(File(result.directory, "TestPack.png"), result.artwork)
        assertTrue(result.artwork!!.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `the xml matches the XO_OX toolchain schema, escaped and byte-stable`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        val drive = File(temp, "drive")
        val spicy = meta.copy(description = "Kicks & snares <loud>")

        val result = ExpansionWriter.write(kit, kitDir, drive, spicy, overwrite = true)
        val first = result.xml.readText()
        // The lowercase schema, as shipped by the reference packager.
        assertTrue(first.contains("<expansion version=\"2.0.0.0\" buildVersion=\"2.10.0.0\">"))
        assertTrue("<local/>" in first)
        assertTrue("<title>Test Pack</title>" in first)
        assertTrue("<manufacturer>SnipSnap</manufacturer>" in first)
        assertTrue("<version>1.0.0.0</version>" in first, "version is four-part")
        assertTrue("<identifier>app.snipsnap.testpack</identifier>" in first)
        assertTrue("<type>instrument</type>" in first)
        assertTrue("<priority>50</priority>" in first)
        assertTrue("<description>Kicks &amp; snares &lt;loud&gt;</description>" in first)
        assertTrue("<separator>-</separator>" in first)
        assertTrue("<img>" !in first, "no artwork given, no img element")

        // The plain-text manifest rides alongside for older firmware.
        val manifest = result.manifest.readText()
        assertTrue("Name=Test Pack" in manifest)
        assertTrue("Version=1.0.0" in manifest)
        assertTrue("Author=SnipSnap" in manifest)

        val second = ExpansionWriter.write(kit, kitDir, drive, spicy, overwrite = true).xml.readText()
        assertEquals(first, second)
    }

    @Test
    fun `meta validation catches the classic mistakes`() {
        // Spaces in the identifier - the docs call this out explicitly.
        assertFailsWith<IllegalArgumentException> { meta.copy(identifier = "snip snap pack") }
        // A bare word is not reverse-domain.
        assertFailsWith<IllegalArgumentException> { meta.copy(identifier = "snipsnap") }
        // Version is dotted digits.
        assertFailsWith<IllegalArgumentException> { meta.copy(version = "one") }
        assertFailsWith<IllegalArgumentException> { meta.copy(version = "1.0-beta") }
        // Titles become folder names on a FAT card.
        assertFailsWith<IllegalArgumentException> { meta.copy(title = "Bad:Title") }
        // Four-part padding.
        assertEquals("1.0.0.0", meta.versionFourPart)
        assertEquals("2.1.0.0", meta.copy(version = "2.1").versionFourPart)
    }

    @Test
    fun `a blocked kit never reaches the card`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        File(kitDir, "A01_Kick_01.wav").delete() // preflight FAIL: missing sample
        assertFailsWith<ExportBlockedException> {
            ExpansionWriter.write(kit, kitDir, File(temp, "drive"), meta)
        }
    }

    @Test
    fun `existing pack is not clobbered without overwrite`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        val drive = File(temp, "drive")
        ExpansionWriter.write(kit, kitDir, drive, meta)
        assertFailsWith<java.io.IOException> { ExpansionWriter.write(kit, kitDir, drive, meta) }
        ExpansionWriter.write(kit, kitDir, drive, meta, overwrite = true)
    }
}
