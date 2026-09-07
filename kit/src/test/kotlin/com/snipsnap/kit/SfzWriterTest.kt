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

class SfzWriterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("sfz").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100
    private val segFrames = 4_410

    private fun tone(frames: Int, amp: Float = 0.4f): Snip =
        Snip(FloatArray(frames) { amp }, 1, rate)

    @Test
    fun `pads, layers, chokes and shape land in their own opcodes`() {
        val dir = File(temp, "kit")
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(segFrames))
        WavWriter.write(File(dir, "A03_Hat_01.wav"), tone(segFrames))
        WavWriter.write(File(dir, "A02_Snare_soft.wav"), tone(segFrames, 0.2f))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(segFrames))
        val kit = Kit(
            "Escape Kit",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK,
                    level = 0.5f, pan = 0.25f, tuneCoarse = -2, tuneFine = 30,
                    attack = 0.1f, decay = 0.5f, cutoff = 0.5f, resonance = 0.25f, humanize = 0.5f,
                ),
                KitPad(
                    slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE,
                    velocityLayers = listOf(
                        KitLayer("A02_Snare_soft.wav", 0, 63),
                        KitLayer("A02_Snare_01.wav", 64, 127),
                    ),
                ),
                KitPad(
                    slot = 3, sampleFile = "A03_Hat_01.wav", drumClass = DrumClass.HAT_CLOSED,
                    muteGroup = 1, oneShot = false,
                ),
            ),
        )
        KitStore.save(kit, dir)

        val sfz = SfzWriter.write(kit, dir, File(temp, "out"))
        assertEquals("Escape Kit.sfz", sfz.name)
        assertTrue(File(sfz.parentFile, "Samples/A01_Kick_01.wav").isFile, "samples travel")
        val text = sfz.readText()

        // The shaped kick, key 36, everything in its own opcode.
        assertContains(text, "key=36")
        assertContains(text, "volume=-3.021", message = "level 0.5 in dB re the MPC's unity")
        assertContains(text, "pan=-50")
        assertContains(text, "transpose=-2")
        assertContains(text, "tune=30")
        assertContains(text, "ampeg_attack=0.04")
        assertContains(text, "ampeg_sustain=0")
        assertContains(text, "fil_type=lpf_2p")
        assertContains(text, "cutoff=632.456", message = "cutoff 0.5 -> 20*10^1.5 Hz")
        assertContains(text, "resonance=3")
        assertContains(text, "pitch_random=2.5")
        assertContains(text, "amp_random=0.75")

        // The layered snare: two regions with velocity ranges.
        assertContains(text, "sample=A02_Snare_soft.wav lovel=0 hivel=63")
        assertContains(text, "sample=A02_Snare_01.wav lovel=64 hivel=127")

        // The hat: choke group and note-off obedience.
        assertContains(text, "group=1")
        assertContains(text, "off_by=1")
        assertContains(text, "loop_mode=no_loop")
        assertContains(text, "loop_mode=one_shot")

        assertEquals(text, SfzWriter.write(kit, dir, File(temp, "out2")).readText(), "deterministic")
    }

    @Test
    fun `chains and grids cycle for real - seq positions over one WAV`() {
        val dir = File(temp, "grid")
        dir.mkdirs()
        val samples = FloatArray(segFrames * 6)
        for (seg in 0 until 6) {
            for (i in 0 until segFrames) samples[seg * segFrames + i] = 0.1f + seg * 0.1f
        }
        WavWriter.write(File(dir, "A01_Grid_01.wav"), Snip(samples, 1, rate))
        WavWriter.write(File(dir, "A02_Chain_01.wav"), Snip(samples.copyOf(segFrames * 3), 1, rate))
        val kit = Kit(
            "Grid SFZ",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Grid_01.wav", drumClass = DrumClass.SNARE,
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
                    slot = 2, sampleFile = "A02_Chain_01.wav", drumClass = DrumClass.KICK,
                    chain = ChainInfo((0 until 3).map { it.toLong() * segFrames }, cycle = 3),
                ),
            ),
        )
        KitStore.save(kit, dir)
        val text = SfzWriter.write(kit, dir, File(temp, "grid-out")).readText()

        // Grid: 2 zones x 2 takes = 4 regions, windows into the one WAV.
        assertContains(
            text,
            "sample=A01_Grid_01.wav lovel=0 hivel=63 seq_length=2 seq_position=1 offset=0 end=${segFrames - 1}",
        )
        assertContains(
            text,
            "sample=A01_Grid_01.wav lovel=0 hivel=63 seq_length=2 seq_position=2 " +
                "offset=$segFrames end=${2 * segFrames - 1}",
        )
        assertContains(
            text,
            "sample=A01_Grid_01.wav lovel=64 hivel=127 seq_length=2 seq_position=1 " +
                "offset=${4 * segFrames} end=${5 * segFrames - 1}",
        )
        assertContains(
            text,
            "sample=A01_Grid_01.wav lovel=64 hivel=127 seq_length=2 seq_position=2 " +
                "offset=${5 * segFrames} end=${6 * segFrames - 1}",
        )
        // Single-zone chain: 3 seq positions, full velocity.
        assertContains(
            text,
            "sample=A02_Chain_01.wav seq_length=3 seq_position=3 " +
                "offset=${2 * segFrames} end=${3 * segFrames - 1}",
        )

        // And the export fan-out speaks it.
        val o = Exporters.export(ExportFormat.SFZ, kit, dir, File(temp, "fan"), overwrite = true)
        assertTrue(o.primary.isFile && o.primary.extension == "sfz")
        assertTrue(File(o.companion, "A01_Grid_01.wav").isFile)
    }
}
