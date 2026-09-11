package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kits that use the upper banks.
 *
 * `KitPad` takes slots to 128 and both importers accept that range, so an
 * `.xpn` or `.sfz` using banks F–H lands pads above slot 92. Every one of
 * these paths built its note as a bare `35 + slot`, which passes
 * `Mpc3Note`'s `require(note in 0..127)` only while the slot stays under
 * 93 — so importing such a kit and then previewing, filling or ghosting it
 * raised `note out of range: 128`. One shared map, and they all hold.
 */
class HighPadSlotTest {

    /** A kit whose snare, hat and perc all sit in the upper banks. */
    private fun upperBankKit() = Kit(
        "Upper Banks",
        listOf(
            KitPad(slot = 93, sampleFile = "a.wav", drumClass = DrumClass.SNARE),
            KitPad(slot = 100, sampleFile = "b.wav", drumClass = DrumClass.HAT_CLOSED),
            KitPad(slot = 128, sampleFile = "c.wav", drumClass = DrumClass.KICK),
        ),
    )

    private val base = Mpc3Clip("Base", 4, listOf(Mpc3Note(Mpc3Note.noteFor(1), 0, 0.9f)))

    @Test
    fun `a fill rolls on a snare in the upper banks`() {
        val filled = GrooveVariations.fill(base, upperBankKit())
        assertTrue(filled.notes.size > base.notes.size, "the fill should add a roll")
        // Every note it wrote addresses a pad this kit actually has.
        val slots = filled.notes.map { Mpc3Note.slotFor(it.note) }.toSet()
        assertTrue(slots.any { it == 93 }, "the roll is on the snare at slot 93: $slots")
    }

    @Test
    fun `ghosts whisper on a snare in the upper banks`() {
        val ghosted = GrooveVariations.ghosted(base, upperBankKit())
        val slots = ghosted.notes.map { Mpc3Note.slotFor(it.note) }.toSet()
        assertTrue(slots.any { it == 93 }, "the ghosts are on the snare at slot 93: $slots")
    }

    @Test
    fun `the default pattern plays the classes wherever they sit`() {
        val pattern = KitPreview.defaultPattern(upperBankKit())
        val slots = pattern.notes.map { Mpc3Note.slotFor(it.note) }.toSet()
        assertEquals(setOf(93, 100, 128), slots, "kick, snare and hat, all above the wrap")
    }

    @Test
    fun `a captured hit on an upper-bank pad keeps its pad`() {
        for (slot in listOf(1, 92, 93, 128)) {
            val clip = CapturedGroove.clip(
                "Capture",
                listOf(CapturedGroove.Hit(padSlot = slot, sourceFrame = 0, lengthFrames = 1_000, velocity = 0.9f)),
                bpm = 120f,
                sampleRate = 48_000,
            )
            assertEquals(slot, Mpc3Note.slotFor(clip.notes.single().note), "slot $slot did not survive the capture")
        }
    }

    @Test
    fun `the step editor's lanes are unmoved by the shared map`() {
        // These lanes are all low slots, so the map must return exactly
        // what the hand-written arithmetic did - no clip written before
        // this change reads differently.
        assertEquals(36, GrooveEdit.noteFor(GrooveEdit.Lane.KICK))
        assertEquals(37, GrooveEdit.noteFor(GrooveEdit.Lane.SNARE))
        assertEquals(38, GrooveEdit.noteFor(GrooveEdit.Lane.HAT_CLOSED))
        assertEquals(39, GrooveEdit.noteFor(GrooveEdit.Lane.HAT_OPEN))
        assertEquals(47, GrooveEdit.noteFor(GrooveEdit.Lane.PERC))
    }
}
