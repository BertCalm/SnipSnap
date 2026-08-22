package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mpc3ExporterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mpc3exp").toFile()

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
            "MPC3 Kit",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK, colorHex = "#e8542e"),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE, muteGroup = 3),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `exports the xtd beside its TrackData folder and the reader accepts it`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        val dest = File(temp, "out")
        val result = Mpc3Exporter.exportTrack(kit, kitDir, dest)

        assertEquals("MPC3 Kit.xtd", result.program.name)
        val dataDir = File(dest, "MPC3 Kit_[TrackData]")
        assertTrue(dataDir.isDirectory, "WAVs live in the sibling _[TrackData] folder")
        assertEquals(
            setOf("A01_Kick_01.wav", "A02_Snare_01.wav"),
            dataDir.listFiles()!!.map { it.name }.toSet(),
        )

        val read = Mpc3Project.read(result.program)
        assertTrue(read.isTrack)
        assertEquals(listOf("MPC3 Kit"), read.trackNames)
        assertEquals(1, read.drumPrograms().size)
        // The payload references exactly the copied files.
        assertTrue("\"path\": \"A01_Kick_01.wav\"" in read.container.payloadText)
        // Class colour rides along as a plain packed int (0xE8542E).
        assertTrue("\"value0\": ${0xE8542E}" in read.container.payloadText)
    }

    @Test
    fun `preflight failures block the export`() {
        val kitDir = File(temp, "kit")
        val kit = buildKit(kitDir)
        File(kitDir, "A02_Snare_01.wav").delete()
        try {
            Mpc3Exporter.exportTrack(kit, kitDir, File(temp, "out"))
            throw AssertionError("expected ExportBlockedException")
        } catch (expected: ExportBlockedException) {
            assertTrue(expected.findings.any { it.severity == Severity.FAIL })
        }
    }
}
