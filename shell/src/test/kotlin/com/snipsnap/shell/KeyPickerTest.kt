package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeyPickerTest {

    @Test
    fun `twelve roots C first, five scales, and every chip pair is a real key`() {
        assertEquals(12, KeyPicker.ROOTS.size)
        assertEquals("C", KeyPicker.ROOTS.first())
        assertEquals("A", KeyPicker.ROOTS[KeyPicker.DEFAULT_ROOT])
        assertEquals(listOf("MAJOR", "MINOR", "MAJ PENT", "MIN PENT", "CHROMATIC"), KeyPicker.SCALES)
        for (r in KeyPicker.ROOTS.indices) for (s in KeyPicker.SCALES) {
            val k = KeyPicker.key(r, s)
            assertEquals(r, k.rootSemitone)
            assertEquals(s, KeyPicker.scaleLabel(k.scale), "the label round-trips")
        }
        assertEquals(KeySpec.parse("Am"), KeyPicker.key(9, "MINOR"))
        assertEquals(KeySpec.parse("F#majpent"), KeyPicker.key(6, "MAJ PENT"))
        assertFailsWith<IllegalArgumentException> { KeyPicker.key(12, "MAJOR") }
        assertFailsWith<IllegalArgumentException> { KeyPicker.key(0, "DORIAN") }
    }

    @Test
    fun `labels read as the header shows them, OFF for no key`() {
        assertEquals("OFF", KeyPicker.label(null))
        assertEquals("A MINOR", KeyPicker.label(KeySpec.parse("Am")))
        assertEquals("D# MIN PENT", KeyPicker.label(KeySpec(3, Scale.MINOR_PENTATONIC)))
        assertEquals("C CHROMATIC", KeyPicker.label(KeySpec(0, Scale.CHROMATIC)))
    }

    @Test
    fun `the readout names each tonal pad's tune, in slot order, and nothing else`() {
        val kit = Kit(
            "K",
            listOf(
                KitPad(3, "bass.wav", "Bass 01", DrumClass.TONAL, tuneCoarse = 2, tuneFine = -13),
                KitPad(1, "kick.wav", "Kick 01", DrumClass.KICK, tuneCoarse = -1),
                KitPad(2, "note.wav", "Note 01", DrumClass.TONAL),
            ),
        )
        assertEquals(listOf("A02 · NOTE 01 · AS CAPTURED", "A03 · BASS 01 · +2 ST −13¢"), KeyPicker.readouts(kit))
        assertEquals(emptyList(), KeyPicker.readouts(Kit("Empty", emptyList())))
    }
}
