package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthKitTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("synthkit").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `the melodic kit is sixteen tonal pads`() {
        val kit = SynthKits.melodic()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null && it.drumClass == DrumClass.TONAL }, "every pad is a note")
    }

    @Test
    fun `the pluck side ascends and the root is the lowest note`() {
        // Root bottom-left, ascending - the SCALE layout convention, measured
        // off the actual renders by autocorrelation.
        val kit = SynthKits.melodic()
        val pitches = (0 until 12).map { TestPitch.estimate(kit[it]!!.snip) }
        for (i in 1 until 12) {
            assertTrue(
                pitches[i] > pitches[i - 1] * 1.02f,
                "pad ${i + 1} (${pitches[i]} Hz) should sit above pad $i (${pitches[i - 1]} Hz)",
            )
        }
    }

    @Test
    fun `the chip kit is sixteen crunched pads that keep their identities`() {
        val kit = SynthKits.chip()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null }, "no empty pads in the chip kit")
        // The whole point of the kit: the converter grunge is character,
        // not identity - the drums still classify as themselves.
        assertEquals(DrumClass.KICK, com.snipsnap.audio.Classifier.classify(kit[0]!!.snip).drumClass)
        assertEquals(DrumClass.SNARE, com.snipsnap.audio.Classifier.classify(kit[1]!!.snip).drumClass)
        // And the chip notes ascend like the melodic kit's plucks do.
        val pitches = (6 until 16).map { TestPitch.estimate(kit[it]!!.snip, fromSec = 0.03f, windowSec = 0.15f) }
        for (i in 1 until pitches.size) {
            assertTrue(
                pitches[i] > pitches[i - 1] * 1.02f,
                "chip pad ${i + 7} (${pitches[i]} Hz) should sit above pad ${i + 6} (${pitches[i - 1]} Hz)",
            )
        }
    }

    @Test
    fun `melodic kit to sd card, end to end`() {
        val kitDir = File(temp, "kit")
        val kit = KitAssembler.assembleArranged("Synth Melodic", SynthKits.melodic(), kitDir)

        assertEquals(16, kit.pads.size)
        val findings = Preflight.check(kit, kitDir)
        assertTrue(!findings.blocked(), "melodic kit must pass preflight: $findings")

        val result = KitExporter.exportProgramFolder(kit, kitDir, File(temp, "sd"))
        assertEquals(16, result.samples.size)
        for (wav in result.samples) {
            assertEquals(44_100, WavInfo.read(wav).sampleRate)
        }
        val xml = result.program.readText()
        assertTrue("<SampleName>A01_Tonal_01</SampleName>" in xml)
        assertTrue("<SampleName>A16_Tonal_16</SampleName>" in xml)
    }
}
