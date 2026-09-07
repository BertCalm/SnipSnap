package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackBuilderTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("pack").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun buildKit(name: String, hz: Double): File {
        val dir = File(temp, "kits/$name").apply { mkdirs() }
        val tone = Snip(
            FloatArray(3_000) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() },
            1, 44_100,
        )
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone)
        KitStore.save(
            Kit(name, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK))),
            dir,
        )
        return dir
    }

    private val meta = ExpansionMeta(
        title = "Crate Vol 1",
        identifier = "app.snipsnap.cratevol1",
        description = "Made with SnipSnap.",
    )

    @Test
    fun `three kits land under one tile, colliding stems kept apart`() {
        val kits = listOf(buildKit("Alpha", 90.0), buildKit("Bravo", 200.0), buildKit("Charlie", 400.0))
        val result = PackBuilder.build(kits, File(temp, "card"), meta, artworkPng = byteArrayOf(1, 2, 3))

        assertEquals(listOf("Alpha", "Bravo", "Charlie"), result.packed)
        val dest = result.directory
        assertEquals("Crate Vol 1", dest.name)
        assertEquals(
            setOf("Alpha.xpm", "Bravo.xpm", "Charlie.xpm"),
            File(dest, "Programs").list()!!.toSet(),
        )
        // Every kit's A01_Kick_01 survives in its own subfolder.
        for (name in listOf("Alpha", "Bravo", "Charlie")) {
            assertTrue(File(dest, "Samples/$name/A01_Kick_01.wav").isFile, name)
            assertTrue(File(dest, "[Previews]/$name.xpm.wav").length() > 1_000, "$name preview")
        }
        assertTrue(File(dest, ExpansionWriter.XML_NAME).isFile)
        assertTrue(File(dest, ExpansionWriter.MANIFEST_NAME).isFile)
        assertTrue(File(dest, meta.artworkFileName).isFile)
    }

    @Test
    fun `a blocked kit is skipped and named, an all-blocked pack refuses`() {
        val ok = buildKit("Fine", 200.0)
        val broken = buildKit("Broken", 300.0)
        File(broken, "A01_Kick_01.wav").delete()

        val result = PackBuilder.build(listOf(ok, broken), File(temp, "card2"), meta)
        assertEquals(listOf("Fine"), result.packed)
        assertEquals(1, result.skipped.size)
        assertEquals("Broken", result.skipped[0].first)

        assertFailsWith<java.io.IOException> {
            PackBuilder.build(listOf(broken), File(temp, "card3"), meta)
        }
    }

    @Test
    fun `the xpn twin is deterministic, root-invariant, manifest-free, and round-trips`() {
        val kits = listOf(buildKit("Delta", 150.0), buildKit("Echo", 500.0))
        val a = PackBuilder.build(kits, File(temp, "z1"), meta, asXpn = true).xpn!!
        val b = PackBuilder.build(kits, File(temp, "z2"), meta, asXpn = true).xpn!!
        assertTrue(a.readBytes().contentEquals(b.readBytes()), "same kits, same archive bytes")

        ZipFile(a).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(ExpansionWriter.XML_NAME in names, "the one structural invariant")
            assertTrue(names.none { it.endsWith(ExpansionWriter.MANIFEST_NAME) }, "no archive carries a manifest")
        }

        // The receive half: every program becomes its own kit again.
        val back = XpnImporter.importAll(a, File(temp, "back"))
        assertEquals(setOf("Delta", "Echo"), back.kits.map { it.kit.name }.toSet())
        assertTrue(back.skipped.isEmpty())
        assertTrue(
            File(back.kits.first { it.kit.name == "Delta" }.directory, "A01_Kick_01.wav").readBytes()
                .contentEquals(File(kits[0], "A01_Kick_01.wav").readBytes()),
            "audio byte-identical through the round-trip",
        )
    }
}
