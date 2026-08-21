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
        version = 1,
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
    fun `the xml carries every field, escaped, and is byte-stable`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        val drive = File(temp, "drive")
        val spicy = meta.copy(description = "Kicks & snares <loud>")

        val first = ExpansionWriter.write(kit, kitDir, drive, spicy, overwrite = true).xml.readText()
        assertTrue("<Title>Test Pack</Title>" in first)
        assertTrue("<Manufacturer>SnipSnap</Manufacturer>" in first)
        assertTrue("<Version>1</Version>" in first)
        assertTrue("<Identifier>app.snipsnap.testpack</Identifier>" in first)
        assertTrue("<Description>Kicks &amp; snares &lt;loud&gt;</Description>" in first)
        assertTrue("<Img>" !in first, "no artwork given, no Img element")

        val second = ExpansionWriter.write(kit, kitDir, drive, spicy, overwrite = true).xml.readText()
        assertEquals(first, second)
    }

    @Test
    fun `meta validation catches the classic mistakes`() {
        // Spaces in the identifier - the docs call this out explicitly.
        assertFailsWith<IllegalArgumentException> { meta.copy(identifier = "snip snap pack") }
        // A bare word is not reverse-domain.
        assertFailsWith<IllegalArgumentException> { meta.copy(identifier = "snipsnap") }
        // Version is a single digit.
        assertFailsWith<IllegalArgumentException> { meta.copy(version = 10) }
        assertFailsWith<IllegalArgumentException> { meta.copy(version = 0) }
        // Titles become folder names on a FAT card.
        assertFailsWith<IllegalArgumentException> { meta.copy(title = "Bad:Title") }
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
