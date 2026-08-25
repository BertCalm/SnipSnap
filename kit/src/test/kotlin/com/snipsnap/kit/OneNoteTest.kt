package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.mpc3.MpcFormat
import com.snipsnap.mpc3.MpcFormats
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OneNoteTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("onenote").toFile()
    private val rate = 44_100

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** A clean decaying 220 Hz note — A3, MIDI 57 — with a few harmonics. */
    private fun note220(): Snip {
        val n = rate
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = Math.exp(-2.0 * t).toFloat()
            out[i] = (0.5 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * 440 * t) +
                0.08 * sin(2 * PI * 660 * t)).toFloat() * env
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `a pitched note becomes a full-range instrument rooted where it sounds`() {
        val result = OneNote.program("Test Bass", note220())
        assertEquals(57, result.rootMidi, "220 Hz is A3")
        assertEquals("A3", result.rootName)
        assertTrue(result.confidence >= OneNote.MIN_CONFIDENCE)

        val kg = result.program.keygroups.single()
        assertEquals(0, kg.lowNote)
        assertEquals(127, kg.highNote)
        assertEquals(57, kg.rootNote)
        assertEquals(result.sampleStem, kg.layers.single().sampleName)
        assertEquals(note220().frameCount.toLong(), kg.layers.single().frameCount)
    }

    @Test
    fun `export lands the dual-generation layout and both readers accept it`() {
        val card = File(temp, "card")
        val result = OneNote.export("Test Bass", note220(), card)

        val xty = File(card, "Test Bass.xty")
        val dataDir = File(card, "Test Bass_[TrackData]")
        assertTrue(xty.isFile)
        assertTrue(File(dataDir, "${result.sampleStem}.wav").isFile)
        assertTrue(File(dataDir, "Test Bass.xpm").isFile, "the MPC 2 twin sits beside the sample")

        assertEquals(MpcFormat.MPC3_ACVS, MpcFormats.detect(xty))
        assertEquals(MpcFormat.MPC2_XML, MpcFormats.detect(File(dataDir, "Test Bass.xpm")))
        val read = Mpc3Project.read(xty)
        assertTrue(read.isTrack)
        assertTrue("keygroup" in read.describe())

        // Overwrite discipline matches every other exporter.
        assertFailsWith<java.io.IOException> { OneNote.export("Test Bass", note220(), card) }
        OneNote.export("Test Bass", note220(), card, overwrite = true)
    }

    @Test
    fun `unpitched material is refused, never guessed at`() {
        val rng = Random(3)
        val noise = Snip(FloatArray(rate) { (rng.nextFloat() * 2 - 1) * 0.5f }, 1, rate)
        val err = assertFailsWith<IllegalArgumentException> { OneNote.program("Noise", noise) }
        assertTrue("pitch" in err.message!!.lowercase())
        assertFailsWith<IllegalArgumentException> { OneNote.program("bad:name", note220()) }
    }
}
