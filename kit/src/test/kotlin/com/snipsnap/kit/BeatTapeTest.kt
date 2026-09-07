package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeatTapeTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("beattape").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun tone(seconds: Float, hz: Double = 220.0, amp: Float = 0.5f): Snip {
        val n = (seconds * BeatTape.RATE).toInt()
        return Snip(
            FloatArray(n) { i -> (amp * Math.sin(2.0 * Math.PI * hz * i / BeatTape.RATE)).toFloat() },
            1, BeatTape.RATE,
        )
    }

    private fun buildKit(dir: File, name: String, bpm: Float): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(0.3f, 110.0))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(0.1f, 900.0))
        val kit = Kit(
            name,
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE),
            ),
            tempoBpm = bpm,
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `the tape is deterministic with sample-accurate track starts`() {
        val a = File(temp, "KitA").also { buildKit(it, "Kit A", 92f) }
        val b = File(temp, "KitB").also { buildKit(it, "Kit B", 120f) }

        val tape = BeatTape.render(listOf(a, b), barsPerKit = 4)
        val again = BeatTape.render(listOf(a, b), barsPerKit = 4)
        assertTrue(tape.audio.samples.contentEquals(again.audio.samples), "same kits, same tape")

        assertEquals(2, tape.tracks.size)
        assertEquals(listOf("Kit A", "Kit B"), tape.tracks.map { it.name })
        assertEquals(0, tape.tracks[0].startFrame)
        assertEquals(
            tape.tracks[0].lengthFrames, tape.tracks[1].startFrame,
            "track 2 starts exactly where track 1's segment ends",
        )
        assertEquals(
            tape.tracks.sumOf { it.lengthFrames }, tape.audio.frameCount,
            "the tape is exactly its segments, no gaps, no overlap",
        )
        assertEquals("tape stop", tape.tracks[0].transition)
        assertEquals(null, tape.tracks[1].transition, "the last track rings out")

        // Three kits alternate the transitions.
        val c = File(temp, "KitC").also { buildKit(it, "Kit C", 100f) }
        val three = BeatTape.render(listOf(a, b, c), barsPerKit = 2)
        assertEquals(listOf("tape stop", "pull-up", null), three.tracks.map { it.transition })
    }

    @Test
    fun `pattern rotation lays the kit's clips bar after bar`() {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val verse = Mpc3Clip("Verse", 2, listOf(Mpc3Note(36, 0, 0.9f)))
        val chorus = Mpc3Clip("Chorus", 2, listOf(Mpc3Note(38, 4 * s16, 0.8f)))
        val arranged = BeatTape.arrange(listOf(verse, chorus), bars = 8, name = "Side A")

        assertEquals(8, arranged.bars)
        // verse(0-2) chorus(2-4) verse(4-6) chorus(6-8): two notes each.
        val kicks = arranged.notes.filter { it.note == 36 }.map { it.timePulses }
        val snares = arranged.notes.filter { it.note == 38 }.map { it.timePulses }
        assertEquals(listOf(0L, 4 * Mpc3Clip.PULSES_PER_BAR), kicks)
        assertEquals(
            listOf(2 * Mpc3Clip.PULSES_PER_BAR + 4 * s16, 6 * Mpc3Clip.PULSES_PER_BAR + 4 * s16),
            snares,
        )
        assertTrue(arranged.notes.all { it.timePulses < 8 * Mpc3Clip.PULSES_PER_BAR })
    }

    @Test
    fun `the tape stop slows the reel to silence and the pull-up spins back`() {
        val steady = tone(2f, 440.0, 0.6f)

        val stopped = BeatTape.tapeStop(steady, seconds = 0.8f)
        assertEquals(steady.frameCount, stopped.frameCount, "a tape stop replaces the tail in place")
        val n = (0.8f * BeatTape.RATE).toInt()
        val rampStart = steady.frameCount - n
        fun crossings(s: Snip, from: Int, len: Int): Int {
            var count = 0
            for (i in from + 1 until from + len) {
                if (s.samples[i - 1] < 0 != s.samples[i] < 0) count++
            }
            return count
        }
        val early = crossings(stopped, rampStart, n / 4)
        val late = crossings(stopped, rampStart + 3 * n / 4, n / 4)
        assertTrue(late < early / 2, "the pitch falls as the reel drags: $early -> $late crossings")
        assertTrue(abs(stopped.samples[stopped.samples.size - 1]) < 1e-3f, "it dies to silence")

        val spin = BeatTape.pullUp(steady, seconds = 0.6f)
        assertEquals((0.6f * BeatTape.RATE).toInt(), spin.frameCount, "the spinback has a fixed length")
        assertTrue(spin.samples.any { abs(it) > 0.05f }, "the rewind is audible")
        fun meanAbs(s: Snip, from: Int, len: Int): Float {
            var acc = 0f
            for (i in from until from + len) acc += abs(s.samples[i])
            return acc / len
        }
        val tenth = spin.frameCount / 10
        assertTrue(
            meanAbs(spin, spin.frameCount - tenth, tenth) < meanAbs(spin, 0, tenth),
            "the spinback falls away before the next beat drops",
        )
    }
}
