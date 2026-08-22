package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XpnPackagerTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("xpn").toFile()

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
            "Zip Kit",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    private val meta = ExpansionMeta(
        title = "Zip Pack",
        identifier = "app.snipsnap.zippack",
        description = "One kit, one file",
    )

    @Test
    fun `Expansion xml sits at the archive root and samples are bare names`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        val out = File(temp, "ZipPack.xpn")
        XpnPackager.write(kit, kitDir, out, meta, artworkPng = byteArrayOf(9, 9))

        ZipFile(out).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            // The one invariant every real archive shares: root Expansion.xml.
            assertTrue("Expansion.xml" in names)
            assertTrue(names.none { it.startsWith("Expansions/") }, "nothing nests under Expansions/ in an archive")
            assertTrue("Programs/Zip Kit.xpm" in names)
            assertTrue("Samples/Zip Kit/A01_Kick_01.wav" in names)
            assertTrue("Samples/Zip Kit/A02_Snare_01.wav" in names)
            assertTrue("artwork.png" in names)

            val xpm = zip.getInputStream(zip.getEntry("Programs/Zip Kit.xpm")).readBytes().decodeToString()
            // No real program carries paths — bare SampleNames, empty
            // SampleFile, no File element; the MPC resolves by search.
            assertTrue("<SampleName>A01_Kick_01</SampleName>" in xpm)
            assertTrue("<SampleFile></SampleFile>" in xpm)
            assertTrue("<File>" !in xpm)

            val xml = zip.getInputStream(zip.getEntry("Expansion.xml")).readBytes().decodeToString()
            assertTrue("<img>artwork.png</img>" in xml)
        }
    }

    @Test
    fun `class colours travel in the ProgramPads blob`() {
        val kitDir = File(temp, "colorkit")
        kitDir.mkdirs()
        val snip = Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / 20.0)).toFloat() }, 1, 44_100)
        WavWriter.write(File(kitDir, "A01_Kick_01.wav"), Cleanup.process(snip))
        val kit = Kit(
            "Color Kit",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK, colorHex = "#e8542e"),
            ),
        )
        KitStore.save(kit, kitDir)
        val out = XpnPackager.write(kit, kitDir, File(temp, "ColorPack.xpn"), meta)
        ZipFile(out).use { zip ->
            val xpm = zip.getInputStream(zip.getEntry("Programs/Color Kit.xpm")).readBytes().decodeToString()
            // Kick red #e8542e = 15225902 packed, pad A01 = value0, and the
            // per-pad switch flips off Universal.
            assertTrue("&quot;value0&quot;: false" in xpm)
            assertTrue("&quot;value0&quot;: 15225902" in xpm)
        }
    }

    @Test
    fun `output is byte-stable`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        val a = XpnPackager.write(kit, kitDir, File(temp, "a.xpn"), meta).readBytes()
        val b = XpnPackager.write(kit, kitDir, File(temp, "b.xpn"), meta).readBytes()
        assertTrue(a.contentEquals(b), "same kit must zip to the same bytes")
    }

    @Test
    fun `preview uses the real packs' Previews convention`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        val preview = Snip(FloatArray(8820) { i -> (0.4 * Math.sin(i / 15.0)).toFloat() }, 1, 44_100)
        val out = XpnPackager.write(kit, kitDir, File(temp, "p.xpn"), meta, preview = preview)
        ZipFile(out).use { zip ->
            assertEquals(
                true,
                zip.getEntry("[Previews]/Zip Kit.xpm.wav") != null,
                "previews live in [Previews]/, named for the program plus its extension",
            )
        }
    }
}
