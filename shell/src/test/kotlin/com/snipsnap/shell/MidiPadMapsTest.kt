package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MidiPadMapsTest {
    private fun usb(product: String) =
        MidiDeviceId(product, MidiFingerprint.of(MidiFingerprint.Transport.USB, 1, 2, 1, 1, "Maker", product, product))

    private val virtual = MidiDeviceId(
        "SNIPSNAP IN",
        MidiFingerprint.of(MidiFingerprint.Transport.VIRTUAL, 0, 0, 1, 0, "SnipSnap", "SNIPSNAP IN", "SNIPSNAP IN"),
    )

    @Test fun `decode round-trips every map by name`() {
        for (m in MidiPads.Map.entries) assertEquals(m, MidiPadMaps.decode(m.name))
    }

    @Test fun `decode of an unknown or missing name is null`() {
        assertNull(MidiPadMaps.decode("BOGUS"))
        assertNull(MidiPadMaps.decode(null))
    }

    @Test fun `restore picks the last hardware device with a stored map`() {
        val a = usb("A"); val b = usb("B")
        val stored = mapOf(
            MidiPadMaps.key(a) to MidiPads.Map.IN_ORDER.name,
            MidiPadMaps.key(b) to MidiPads.Map.MPC.name,
        )
        assertEquals(MidiPads.Map.MPC, MidiPadMaps.restore(listOf(a, b)) { stored[it] })
        assertEquals(MidiPads.Map.IN_ORDER, MidiPadMaps.restore(listOf(b, a)) { stored[it] })
    }

    @Test fun `restore skips a device with nothing stored and takes the earlier one`() {
        val a = usb("A"); val b = usb("B")
        val stored = mapOf(MidiPadMaps.key(a) to MidiPads.Map.IN_ORDER.name)
        assertEquals(MidiPads.Map.IN_ORDER, MidiPadMaps.restore(listOf(a, b)) { stored[it] })
    }

    @Test fun `restore skips the virtual port even when it has a stored entry`() {
        val stored = mapOf(MidiPadMaps.key(virtual) to MidiPads.Map.IN_ORDER.name)
        assertNull(MidiPadMaps.restore(listOf(virtual)) { stored[it] })
    }

    @Test fun `restore is null when nothing is stored or the stored name is unknown`() {
        assertNull(MidiPadMaps.restore(listOf(usb("A"))) { null })
        assertNull(MidiPadMaps.restore(listOf(usb("A"))) { "BOGUS" })
        assertNull(MidiPadMaps.restore(emptyList()) { error("no device, no read") })
    }

    @Test fun `keysToSave lists each hardware device once and never the virtual port`() {
        val a = usb("A"); val b = usb("B")
        assertEquals(
            listOf(MidiPadMaps.key(a), MidiPadMaps.key(b)),
            MidiPadMaps.keysToSave(listOf(a, virtual, b, a)),
        )
    }

    @Test fun `the key is the fingerprint key under a fixed prefix`() =
        assertEquals("midi.padmap.${usb("A").fingerprint.key}", MidiPadMaps.key(usb("A")))
}
