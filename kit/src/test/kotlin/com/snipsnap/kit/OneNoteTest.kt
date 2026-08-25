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

    private fun tone(hz: Double): Snip {
        val n = rate
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            out[i] = ((0.5 * sin(2 * PI * hz * t) + 0.15 * sin(2 * PI * hz * 2 * t)) *
                Math.exp(-2.0 * t)).toFloat()
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `multisample - zones tile at the midpoints with real roots`() {
        // 110 / 220 / 440 Hz = A2(45) / A3(57) / A4(69), given scrambled.
        val result = OneNote.multiProgram(
            "Multi Bass",
            listOf("high.wav" to tone(440.0), "low.wav" to tone(110.0), "mid.wav" to tone(220.0)),
        )
        assertEquals(listOf(45, 57, 69), result.zones.map { it.rootMidi })
        val kgs = result.program.keygroups
        assertEquals(0, kgs[0].lowNote)
        assertEquals(51, kgs[0].highNote, "boundary at the midpoint of A2 and A3")
        assertEquals(52, kgs[1].lowNote)
        assertEquals(63, kgs[1].highNote)
        assertEquals(64, kgs[2].lowNote)
        assertEquals(127, kgs[2].highNote)
        kgs.forEach { assertTrue(it.rootNote in it.lowNote..it.highNote) }

        // Zones tile without gaps or overlaps.
        kgs.zipWithNext().forEach { (a, b) -> assertEquals(a.highNote + 1, b.lowNote) }
    }

    @Test
    fun `multisample export lands every zone's WAV and both programs`() {
        val card = File(temp, "multi-card")
        val result = OneNote.multiExport(
            "Multi Bass",
            listOf("a.wav" to tone(110.0), "b.wav" to tone(220.0)),
            card,
        )
        assertEquals(2, result.zones.size)
        val dataDir = File(card, "Multi Bass_[TrackData]")
        result.zones.forEach { assertTrue(File(dataDir, "${it.sampleStem}.wav").isFile, it.sampleStem) }
        assertTrue(File(card, "Multi Bass.xty").isFile)
        assertTrue(File(dataDir, "Multi Bass.xpm").isFile)
    }

    @Test
    fun `multisample refusals carry the offending labels`() {
        val rng = Random(9)
        val noise = Snip(FloatArray(rate) { (rng.nextFloat() * 2 - 1) * 0.5f }, 1, rate)
        val bad = assertFailsWith<IllegalArgumentException> {
            OneNote.multiProgram("X", listOf("good.wav" to tone(220.0), "hiss.wav" to noise))
        }
        assertTrue("hiss.wav" in bad.message!!)

        val dupe = assertFailsWith<IllegalArgumentException> {
            OneNote.multiProgram("X", listOf("one.wav" to tone(220.0), "two.wav" to tone(220.5)))
        }
        assertTrue("one.wav" in dupe.message!! && "two.wav" in dupe.message!!)
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
