package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Z-task 2: the pure arithmetic that turns pad hits into notes. */
class LiveRecordTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("liverecord").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    // ---- pulsesFor ----------------------------------------------------

    @Test
    fun `pulsesFor at a known bpm matches a hand-computed value`() {
        // 120 bpm, one bar in (2s = one bar of 4/4 at 120): 3840 pulses.
        assertEquals(3840L, LiveRecord.pulsesFor(2.0, 120f))
        // Half a second at 120 bpm = one beat = 960 pulses.
        assertEquals(960L, LiveRecord.pulsesFor(0.5, 120f))
    }

    @Test
    fun `tempo changes the pulse mapping for the same elapsed time`() {
        // 500ms at 90 bpm vs 140 bpm land at different pulses.
        assertEquals(720L, LiveRecord.pulsesFor(0.5, 90f))
        assertEquals(1120L, LiveRecord.pulsesFor(0.5, 140f))
    }

    @Test
    fun `a hit exactly on a step lands on that pulse`() {
        // 240 pulses is exactly a 16th at 960 PPQ; a hit timed to land
        // there does, with no snapping.
        val elapsed = 240.0 / 960.0 * (60.0 / 120.0) // seconds for 240 pulses at 120bpm
        assertEquals(240L, LiveRecord.pulsesFor(elapsed, 120f))
    }

    @Test
    fun `a hit between steps keeps its true position, not snapped`() {
        // 100 pulses at 120bpm is off the 240-pulse 16th grid entirely.
        val elapsed = 100.0 / 960.0 * (60.0 / 120.0)
        val pulses = LiveRecord.pulsesFor(elapsed, 120f)
        assertEquals(100L, pulses)
        assertTrue(pulses % Mpc3Clip.PULSES_PER_16TH != 0L, "must not land on the 16th grid")
    }

    // ---- wrapped --------------------------------------------------------

    @Test
    fun `wrapped is a no-op for an in-range value`() {
        assertEquals(100L, LiveRecord.wrapped(100L, bars = 1))
        assertEquals(3839L, LiveRecord.wrapped(3839L, bars = 1))
    }

    @Test
    fun `wrapped sends a hit a few ms past the loop end to the top`() {
        // 120bpm, 1 bar: limit = 3840 pulses. A hit at 2.003s (3ms past
        // the 2.0s loop end) is 6 pulses past the boundary.
        val raw = LiveRecord.pulsesFor(2.003, 120f)
        assertEquals(3846L, raw, "a few ms past the loop end is a real, expected true-time value")

        // Unwrapped, that value fails Mpc3Note's own require - proving
        // this is the real boundary Mpc3Clip enforces, not a mock.
        assertFailsWith<IllegalArgumentException> {
            Mpc3Clip("x", 1, listOf(Mpc3Note(36, raw, 0.9f)))
        }

        // Wrapped, it lands at the top of the loop and constructs cleanly.
        assertEquals(6L, LiveRecord.wrapped(raw, bars = 1))
        Mpc3Clip("x", 1, listOf(Mpc3Note(36, LiveRecord.wrapped(raw, bars = 1), 0.9f)))
    }

    @Test
    fun `wrapped at the exact loop boundary lands on pulse zero`() {
        // The off-by-one: timePulses == limit wraps to 0, matching
        // GrooveEdit.quantized's own step-index-modulo at a step boundary
        // - and musically, the loop end IS the next pass's downbeat.
        val limit = 1 * Mpc3Clip.PULSES_PER_BAR
        assertEquals(0L, LiveRecord.wrapped(limit, bars = 1))
    }

    @Test
    fun `wrapped guards a negative input even though the caller shouldn't produce one`() {
        assertEquals(0L, LiveRecord.wrapped(-Mpc3Clip.PULSES_PER_BAR, bars = 1))
        assertTrue(LiveRecord.wrapped(-1L, bars = 2) >= 0L)
    }

    @Test
    fun `wrapped refuses a non-positive bar count rather than dividing by zero`() {
        assertFailsWith<IllegalArgumentException> { LiveRecord.wrapped(0L, bars = 0) }
    }

    // ---- Take -------------------------------------------------------------

    @Test
    fun `velocity is carried through to the note, not flattened`() {
        val take = LiveRecord.Take(bars = 1)
        take.add(note = 40, elapsedSeconds = 0.0, bpm = 120f, velocity = 0.37f)
        assertEquals(0.37f, take.notes().single().velocity)
    }

    @Test
    fun `same-position collisions within a take keep the louder note`() {
        val take = LiveRecord.Take(bars = 1)
        take.add(note = 36, elapsedSeconds = 0.0, bpm = 120f, velocity = 0.4f)
        take.add(note = 36, elapsedSeconds = 0.0, bpm = 120f, velocity = 0.9f)
        val notes = take.notes()
        assertEquals(1, notes.size)
        assertEquals(0.9f, notes.single().velocity)
    }

    @Test
    fun `Take notes are time-sorted`() {
        val take = LiveRecord.Take(bars = 1)
        take.add(note = 36, elapsedSeconds = 0.5, bpm = 120f, velocity = 0.5f)
        take.add(note = 38, elapsedSeconds = 0.0, bpm = 120f, velocity = 0.5f)
        assertEquals(listOf(0L, 960L), take.notes().map { it.timePulses })
    }

    // ---- toClip -------------------------------------------------------------

    @Test
    fun `toClip with no existing base is the take alone`() {
        val take = LiveRecord.Take(bars = 2)
        take.add(note = 36, elapsedSeconds = 0.0, bpm = 120f, velocity = 0.8f)
        val clip = LiveRecord.toClip(take, "Take One", existing = null)
        assertEquals("Take One", clip.name)
        assertEquals(2, clip.bars)
        assertEquals(1, clip.notes.size)
    }

    @Test
    fun `toClip overdub adds the take onto the existing base`() {
        val existing = Mpc3Clip("Base Groove", 1, listOf(Mpc3Note(36, 0L, 0.5f)))
        val take = LiveRecord.Take(bars = 1)
        take.add(note = 38, elapsedSeconds = 0.5, bpm = 120f, velocity = 0.7f)
        val merged = LiveRecord.toClip(take, "Base Groove", existing)
        assertEquals(1, merged.bars, "bars come from the existing base")
        assertEquals(setOf(0L to 36, 960L to 38), merged.notes.map { it.timePulses to it.note }.toSet())
    }

    @Test
    fun `toClip overdub collision keeps the louder note`() {
        val existing = Mpc3Clip("Base Groove", 1, listOf(Mpc3Note(36, 0L, 0.3f)))
        val take = LiveRecord.Take(bars = 1)
        take.add(note = 36, elapsedSeconds = 0.0, bpm = 120f, velocity = 0.95f)
        val merged = LiveRecord.toClip(take, "Base Groove", existing)
        assertEquals(1, merged.notes.size)
        assertEquals(0.95f, merged.notes.single().velocity)
    }

    @Test
    fun `toClip refuses a take recorded against a different bar count than the base`() {
        val existing = Mpc3Clip("Base Groove", 2, listOf(Mpc3Note(36, 0L, 0.5f)))
        val take = LiveRecord.Take(bars = 1)
        take.add(note = 38, elapsedSeconds = 0.0, bpm = 120f, velocity = 0.7f)
        assertFailsWith<IllegalArgumentException> { LiveRecord.toClip(take, "Base Groove", existing) }
    }

    // ---- land ---------------------------------------------------------------

    @Test
    fun `land refuses an empty-note clip with a message, not a crash`() {
        // Mpc3Clip itself allows zero notes; landing one is never intended.
        val silent = Mpc3Clip("Silent", 1, emptyList())
        val ex = assertFailsWith<IllegalArgumentException> { LiveRecord.land(temp, silent) }
        assertTrue(ex.message!!.isNotBlank())
    }

    @Test
    fun `land regenerates the standard variations and keeps E untouched`() {
        val e = Mpc3Clip(GrooveEdit.progEName("Take One"), 1, listOf(Mpc3Note(38, 480L, 0.6f)))
        GrooveEdit.save(temp, e)

        val take = Mpc3Clip("Take One", 1, listOf(Mpc3Note(36, 0L, 0.8f)))
        LiveRecord.land(temp, take)

        val stored = GrooveStore.load(temp)
        assertEquals(5, stored.size, "the standard four plus the untouched E")
        assertEquals(take, stored.first { !GrooveEdit.isProgE(it) && it.name == "Take One" })
        assertEquals(e, stored.first { GrooveEdit.isProgE(it) })
    }

    // ---- undo -----------------------------------------------------------

    @Test
    fun `undo restores an existing base`() {
        val original = Mpc3Clip("Base Groove", 1, listOf(Mpc3Note(36, 0L, 0.5f)))
        GrooveStore.save(temp, GrooveVariations.standard(original))

        // A take lands on top.
        val overdubbed = Mpc3Clip("Base Groove", 1, listOf(Mpc3Note(36, 0L, 0.5f), Mpc3Note(38, 480L, 0.9f)))
        LiveRecord.land(temp, overdubbed)
        assertTrue(GrooveStore.load(temp).any { it.notes.any { n -> n.note == 38 } })

        LiveRecord.undo(temp, original)
        val stored = GrooveStore.load(temp)
        assertEquals(original, stored.first { !GrooveEdit.isProgE(it) })
        assertTrue(stored.none { it.notes.any { n -> n.note == 38 } })
    }

    @Test
    fun `undo from-scratch with E restores to E-only`() {
        val e = Mpc3Clip(GrooveEdit.progEName("Fresh Take"), 1, listOf(Mpc3Note(38, 480L, 0.6f)))
        GrooveEdit.save(temp, e)
        assertEquals(1, GrooveStore.load(temp).size, "E alone before the take")

        val take = Mpc3Clip("Fresh Take", 1, listOf(Mpc3Note(36, 0L, 0.8f)))
        LiveRecord.land(temp, take)
        assertEquals(5, GrooveStore.load(temp).size, "the take landed alongside E")

        LiveRecord.undo(temp, preTake = null)
        val stored = GrooveStore.load(temp)
        assertEquals(listOf(e), stored, "back to E alone, nothing else")
    }

    @Test
    fun `undo from-scratch with nothing restores to no groove file at all`() {
        assertTrue(GrooveStore.load(temp).isEmpty())

        val take = Mpc3Clip("Brand New", 1, listOf(Mpc3Note(36, 0L, 0.8f)))
        LiveRecord.land(temp, take)
        assertTrue(File(temp, GrooveStore.FILE_NAME).isFile)

        LiveRecord.undo(temp, preTake = null)
        assertTrue(GrooveStore.load(temp).isEmpty())
        assertTrue(!File(temp, GrooveStore.FILE_NAME).isFile, "no groove.json survives")
    }
}
