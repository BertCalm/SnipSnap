package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecentSamplerWriterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("ds").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100
    private val segFrames = 4_410

    @Test
    fun `plain kits stay plain and chains cycle - the dspreset carries it all`() {
        val dir = File(temp, "kit")
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), Snip(FloatArray(segFrames) { 0.4f }, 1, rate))
        WavWriter.write(
            File(dir, "A02_Chain_01.wav"),
            Snip(FloatArray(segFrames * 6) { 0.3f }, 1, rate),
        )
        WavWriter.write(File(dir, "A03_Soft_01.wav"), Snip(FloatArray(segFrames) { 0.2f }, 1, rate))
        WavWriter.write(File(dir, "A03_Hat_01.wav"), Snip(FloatArray(segFrames) { 0.4f }, 1, rate))
        val kit = Kit(
            "DS Kit",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK,
                    level = 0.5f, pan = 0.75f, tuneCoarse = 1, tuneFine = -50,
                ),
                KitPad(
                    slot = 2, sampleFile = "A02_Chain_01.wav", drumClass = DrumClass.SNARE,
                    chain = ChainInfo(
                        boundaries = (0 until 6).map { it.toLong() * segFrames },
                        cycle = 2,
                        zones = listOf(
                            ChainZone(0, 63, baseSlice = 0, cycle = 2),
                            ChainZone(64, 127, baseSlice = 4, cycle = 2),
                        ),
                    ),
                ),
                KitPad(
                    slot = 3, sampleFile = "A03_Hat_01.wav", drumClass = DrumClass.HAT_CLOSED,
                    muteGroup = 2,
                    velocityLayers = listOf(
                        KitLayer("A03_Soft_01.wav", 0, 63),
                        KitLayer("A03_Hat_01.wav", 64, 127),
                    ),
                ),
            ),
        )
        KitStore.save(kit, dir)

        val preset = DecentSamplerWriter.write(kit, dir, File(temp, "out"))
        assertEquals("DS Kit.dspreset", preset.name)
        assertTrue(File(preset.parentFile, "Samples/A02_Chain_01.wav").isFile)
        val text = preset.readText()

        // The plain kick: identity carried, no seq anything.
        assertContains(text, "volume=\"-3.021dB\"")
        assertContains(text, "pan=\"50\"")
        assertContains(text, "groupTuning=\"0.5\"")
        assertContains(text, "rootNote=\"36\" loNote=\"36\" hiNote=\"36\"")

        // The grid: one group per zone, seq positions windowing the chain.
        assertContains(text, "seqMode=\"round_robin\" seqLength=\"2\"")
        assertContains(
            text,
            "loVel=\"0\" hiVel=\"63\" start=\"0\" end=\"${segFrames - 1}\" seqPosition=\"1\"",
        )
        assertContains(
            text,
            "loVel=\"64\" hiVel=\"127\" start=\"${4 * segFrames}\" end=\"${5 * segFrames - 1}\" seqPosition=\"1\"",
        )
        assertContains(
            text,
            "loVel=\"64\" hiVel=\"127\" start=\"${5 * segFrames}\" end=\"${6 * segFrames - 1}\" seqPosition=\"2\"",
        )

        // The layered hat: velocity ranges + the tag choke.
        assertContains(text, "path=\"Samples/A03_Soft_01.wav\"")
        assertContains(text, "tags=\"choke2\" silencedByTags=\"choke2\" silencingMode=\"fast\"")

        // Plain pads carry no seq attributes at all.
        val kickGroup = text.substringBefore("</group>")
        assertTrue("seq" !in kickGroup, "a plain pad stays plain")

        assertEquals(
            text,
            DecentSamplerWriter.write(kit, dir, File(temp, "out2")).readText(),
            "deterministic",
        )

        // The export fan-out speaks it.
        val o = Exporters.export(ExportFormat.DECENT_SAMPLER, kit, dir, File(temp, "fan"), overwrite = true)
        assertTrue(o.primary.isFile && o.primary.extension == "dspreset")
        assertTrue(File(o.companion, "A01_Kick_01.wav").isFile)
    }
}
