package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sampler sheet rides every zone WAV a package writes: root from the program, loop when the zone has one. */
class OneNoteSmplTest {

    private val rate = 44_100
    private val temp: File = java.nio.file.Files.createTempDirectory("onenote-smpl").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** A genuinely sustained 220 Hz note (slow decay, light noise) — A3, MIDI 57. */
    private fun heldNote(): Snip {
        val rng = Random(6)
        val n = rate * 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = (if (t < 0.01) t / 0.01 else Math.exp(-0.25 * (t - 0.01))).toFloat()
            out[i] = ((0.5 * sin(2 * PI * 220 * t) + 0.15 * sin(2 * PI * 440 * t)).toFloat() +
                (rng.nextFloat() * 2 - 1) * 0.002f) * env
        }
        return Snip(out, 1, rate)
    }

    /** A clean decaying 220 Hz pluck: pitched, but honestly without sustain. */
    private fun pluck(): Snip {
        val n = rate
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = Math.exp(-2.0 * t).toFloat()
            out[i] = (0.5 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * 440 * t)).toFloat() * env
        }
        return Snip(out, 1, rate)
    }

    @Test
    fun `a looped zone's WAV carries its root and its loop, a pluck's carries the root alone`() {
        val card = File(temp, "card")
        val multi = OneNote.multiExport("Held Keys", listOf("held.wav" to heldNote()), card, sustainLoop = true)
        val zone = multi.zones.single()
        assertTrue(zone.loopStartFrame > 0, "the held note loops")
        val file = File(card, "Held Keys_[TrackData]/${zone.sampleStem}.wav")
        val sheet = assertNotNull(WavReader.readSmpl(file), "the zone WAV carries a sampler sheet")
        assertEquals(zone.rootMidi, sheet.rootNote)
        val loop = assertNotNull(sheet.loop)
        assertEquals(zone.loopStartFrame, loop.startFrame)
        assertEquals(multi.samples.getValue(zone.sampleStem).frameCount.toLong(), loop.endFrameExclusive, "loops to the end")
        // The audio is what it always was.
        assertEquals(multi.samples.getValue(zone.sampleStem).frameCount, WavReader.read(file).frameCount)

        val single = OneNote.export("Pluck", pluck(), File(temp, "pluck"), sustainLoop = true)
        assertEquals(0L, single.loopStartFrame, "a pluck has no sustain to loop")
        val pluckFile = File(temp, "pluck/Pluck_[TrackData]/${single.sampleStem}.wav")
        val pluckSheet = assertNotNull(WavReader.readSmpl(pluckFile))
        assertEquals(single.rootMidi, pluckSheet.rootNote)
        assertNull(pluckSheet.loop, "no loop is written for a note that plays unlooped")
    }
}
