package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThumpKitTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("thumpkit").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `the factory kit lands on the conventional layout`() {
        val kit = ThumpKits.classic()
        assertEquals(16, kit.size)
        assertEquals(DrumClass.KICK, kit[0]?.second)
        assertEquals(DrumClass.SNARE, kit[1]?.second)
        assertEquals(DrumClass.HAT_CLOSED, kit[2]?.second)
        assertEquals(DrumClass.HAT_OPEN, kit[3]?.second)
        // S3: the top row is TINES metal - two engines, one kit.
        assertTrue((12..15).all { kit[it] != null }, "A13-A16 carry the TINES row")
    }

    @Test
    fun `synth kit to sd card, end to end`() {
        // The whole S1 promise in one test: THUMP renders a kit, the kit
        // pipeline assembles, preflights and exports it as a loadable MPC
        // program - synthesis and capture sharing one road.
        val kitDir = File(temp, "kit")
        val kit = KitAssembler.assemble("Thump Classic", ThumpKits.classic(), kitDir)

        assertEquals(16, kit.pads.size)
        assertEquals(kit, KitStore.load(kitDir))

        // Hats share the choke group, straight from the assembler.
        assertEquals(kit.pad(3)?.muteGroup, kit.pad(4)?.muteGroup)
        assertTrue(kit.pad(3)!!.muteGroup != 0)

        val findings = Preflight.check(kit, kitDir)
        assertTrue(!findings.blocked(), "factory synth kit must pass preflight: $findings")

        val result = KitExporter.exportProgramFolder(kit, kitDir, File(temp, "sd"))
        assertEquals(16, result.samples.size)
        for (wav in result.samples) {
            val info = WavInfo.read(wav)
            assertEquals(44_100, info.sampleRate)
            assertEquals(24, info.bitsPerSample)
        }
        val xml = result.program.readText()
        assertTrue("<SampleName>A01_Kick_01</SampleName>" in xml)
        assertTrue("<SampleName>A12_Snare_02</SampleName>" in xml)
        assertTrue("<SampleName>A16_Tonal_02</SampleName>" in xml, "the TINES bell exports on A16")
    }
}
