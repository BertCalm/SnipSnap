package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SfzImporterTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("sfz-in").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100
    private val segFrames = 4_410

    @Test
    fun `our own sfz round-trips - identity, layers, chain and grid come home`() {
        val dir = File(temp, "src")
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), Snip(FloatArray(segFrames) { 0.4f }, 1, rate))
        WavWriter.write(File(dir, "A02_Soft_01.wav"), Snip(FloatArray(segFrames) { 0.2f }, 1, rate))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), Snip(FloatArray(segFrames) { 0.4f }, 1, rate))
        WavWriter.write(
            File(dir, "A03_Grid_01.wav"),
            Snip(FloatArray(segFrames * 4) { 0.3f }, 1, rate),
        )
        WavWriter.write(
            File(dir, "A04_Chain_01.wav"),
            Snip(FloatArray(segFrames * 3) { 0.3f }, 1, rate),
        )
        val kit = Kit(
            "Round Trip",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Kick_01.wav",
                    level = 0.5f, pan = 0.25f, tuneCoarse = -2, tuneFine = 30, muteGroup = 3,
                    attack = 0.1f, cutoff = 0.5f, resonance = 0.25f, humanize = 0.5f,
                ),
                KitPad(
                    slot = 2, sampleFile = "A02_Snare_01.wav",
                    velocityLayers = listOf(
                        KitLayer("A02_Soft_01.wav", 0, 63),
                        KitLayer("A02_Snare_01.wav", 64, 127),
                    ),
                ),
                KitPad(
                    slot = 3, sampleFile = "A03_Grid_01.wav",
                    chain = ChainInfo(
                        // The robin shape: zones tile the slices, 2 x 2.
                        boundaries = (0 until 4).map { it.toLong() * segFrames },
                        cycle = 2,
                        zones = listOf(
                            ChainZone(0, 63, baseSlice = 0, cycle = 2),
                            ChainZone(64, 127, baseSlice = 2, cycle = 2),
                        ),
                    ),
                ),
                KitPad(
                    slot = 4, sampleFile = "A04_Chain_01.wav", oneShot = false,
                    chain = ChainInfo((0 until 3).map { it.toLong() * segFrames }, cycle = 3),
                ),
            ),
        )
        KitStore.save(kit, dir)
        val sfz = SfzWriter.write(kit, dir, File(temp, "sfz-out"))

        val r = SfzImporter.import(sfz, File(temp, "back"))
        assertEquals(emptyList(), r.skipped, "our own output imports clean")
        val back = r.kit
        assertEquals(4, back.pads.size)

        // Identity fields survive (level within the dB text rounding).
        val kick = back.pad(1)!!
        assertTrue(abs(kick.level - 0.5f) < 1e-3f, "level back: ${kick.level}")
        assertEquals(0.25f, kick.pan)
        assertEquals(-2, kick.tuneCoarse)
        assertEquals(30, kick.tuneFine)
        assertEquals(3, kick.muteGroup)
        assertTrue(abs(kick.attack!! - 0.1f) < 1e-3f)
        assertTrue(abs(kick.cutoff!! - 0.5f) < 1e-3f)
        assertTrue(abs(kick.resonance!! - 0.25f) < 1e-3f)
        assertTrue(abs(kick.humanize!! - 0.5f) < 1e-3f)
        assertEquals("Round Trip.sfz", kick.source["importedFrom"])

        // Layers come back soft-first with their ranges.
        val snare = back.pad(2)!!
        assertEquals(listOf(0 to 63, 64 to 127), snare.velocityLayers.map { it.velStart to it.velEnd })

        // The grid comes home whole: boundaries, zones, anchors, cycles.
        assertEquals(kit.pad(3)!!.chain, back.pad(3)!!.chain)
        // And the single-zone chain, with its note-off obedience.
        assertEquals(kit.pad(4)!!.chain, back.pad(4)!!.chain)
        assertEquals(false, back.pad(4)!!.oneShot)
        assertTrue(File(r.directory, back.pad(3)!!.sampleFile).isFile, "samples travel home")
    }

    @Test
    fun `a foreign sfz imports with the format's own courtesy`() {
        val dir = File(temp, "foreign")
        dir.mkdirs()
        File(dir, "Samples").mkdirs()
        WavWriter.write(File(dir, "Samples/kick drum 1.wav"), Snip(FloatArray(segFrames) { 0.4f }, 1, rate))
        WavWriter.write(File(dir, "Samples/snare.wav"), Snip(FloatArray(segFrames) { 0.3f }, 1, rate))
        // Header sharing lines, inheritance, spaced sample paths, unknown
        // opcodes, a comment, a multi-key region to skip.
        File(dir, "Foreign Kit.sfz").writeText(
            """
            // a hand-written kit
            <control> default_path=Samples/
            <global> ampeg_release=0.5 some_unknown=thing
            <group> volume=-6 lokey=36 hikey=36 pitch_keycenter=36
            <region> sample=kick drum 1.wav
            <group> key=38 pan=40
            <region> sample=snare.wav
            <group>
            <region> sample=snare.wav lokey=50 hikey=62
            """.trimIndent(),
        )

        val r = SfzImporter.import(File(dir, "Foreign Kit.sfz"), File(temp, "foreign-out"))
        assertEquals(2, r.kit.pads.size, "two playable pads")
        assertTrue(abs(r.kit.pad(1)!!.level - 0.707946f * 0.5012f) < 1e-3f, "-6 dB carried")
        assertEquals(0.7f, r.kit.pad(3)!!.pan, "pan 40 -> 0.7")
        assertTrue(
            File(r.directory, r.kit.pad(1)!!.sampleFile).isFile,
            "the sample lands under its (MPC-safe) stem: ${r.kit.pad(1)!!.sampleFile}",
        )
        assertEquals(1, r.skipped.size, "the multi-key region is named, not fatal")

        // Garbage refuses honestly.
        val junk = File(temp, "junk.sfz").apply { writeText("this is not an instrument") }
        assertFailsWith<java.io.IOException> { SfzImporter.import(junk, File(temp, "junk-out")) }
    }
}
