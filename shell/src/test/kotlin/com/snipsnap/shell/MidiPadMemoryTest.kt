package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MidiPadMemoryTest {
    private fun device(name: String, product: String, vendor: Int = 0x1C75) =
        MidiDeviceId(name, MidiFingerprint.of(MidiFingerprint.Transport.USB, vendor, 0x020B, 4, 4, "Maker", product, name))

    private val minilab = device("MiniLab 3", "MiniLab 3")
    private val fold = device("nanoKEY Fold", "nanoKEY Fold", vendor = 0x0944)

    @Test fun `a choice saved for a keyboard comes back when it connects`() {
        val store = MidiPadMemory.remember(listOf(minilab), MidiPads.Map.IN_ORDER)
        assertEquals(MidiPads.Map.IN_ORDER, MidiPadMemory.recall(listOf(minilab), store::get))
    }

    @Test fun `a keyboard with nothing saved recalls nothing, so the screen keeps its map`() =
        assertNull(MidiPadMemory.recall(listOf(fold), emptyMap<String, String>()::get))

    @Test fun `the first connected device with a saved choice wins`() {
        val store = MidiPadMemory.remember(listOf(fold), MidiPads.Map.MPC) +
            MidiPadMemory.remember(listOf(minilab), MidiPads.Map.IN_ORDER)
        assertEquals(MidiPads.Map.MPC, MidiPadMemory.recall(listOf(fold, minilab), store::get))
        assertEquals(MidiPads.Map.IN_ORDER, MidiPadMemory.recall(listOf(minilab, fold), store::get))
    }

    @Test fun `a saved value that names no map is ignored`() {
        val store = mapOf(MidiPadMemory.keyOf(minilab.fingerprint) to "SOMETHING_OLD")
        assertNull(MidiPadMemory.recall(listOf(minilab), store::get))
    }

    @Test fun `picking a map saves it for every connected device, and for none when nothing is`() {
        val saved = MidiPadMemory.remember(listOf(minilab, fold), MidiPads.Map.IN_ORDER)
        assertEquals(setOf(MidiPadMemory.keyOf(minilab.fingerprint), MidiPadMemory.keyOf(fold.fingerprint)), saved.keys)
        assertTrue(saved.values.all { it == MidiPads.Map.IN_ORDER.name })
        assertTrue(MidiPadMemory.remember(emptyList(), MidiPads.Map.IN_ORDER).isEmpty())
    }

    @Test fun `the key follows the fingerprint, not the display name that changes after a permission grant`() {
        val before = device("USB-MIDI", "MiniLab 3")
        val after = device("MiniLab 3 MIDI", "MiniLab 3")
        assertEquals(MidiPadMemory.keyOf(before.fingerprint), MidiPadMemory.keyOf(after.fingerprint))
        assertTrue(MidiPadMemory.keyOf(after.fingerprint).startsWith(MidiPadMemory.KEY_PREFIX))
    }
}
