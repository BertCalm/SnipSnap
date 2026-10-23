package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MidiFingerprintTest {
    private fun usb(manufacturer: String? = "Novation", product: String? = "Launchpad X", name: String? = "Launchpad X MIDI 1") =
        MidiFingerprint.of(MidiFingerprint.Transport.USB, 0x1235, 0x0113, 2, 2, manufacturer, product, name)

    @Test fun `the same device gives the same key`() = assertEquals(usb().key, usb().key)

    @Test fun `a different product id is a different key`() =
        assertNotEquals(usb().key, MidiFingerprint.of(MidiFingerprint.Transport.USB, 0x1235, 0x0114, 2, 2, "Novation", "Launchpad X", "Launchpad X MIDI 1").key)

    @Test fun `a blank product string is absent, so the display name is used`() =
        assertNotEquals(
            MidiFingerprint.of(MidiFingerprint.Transport.USB, 1, 2, 1, 1, "M", "", "A").key,
            MidiFingerprint.of(MidiFingerprint.Transport.USB, 1, 2, 1, 1, "M", "", "B").key,
        )

    @Test fun `a different manufacturer is a different key`() =
        assertNotEquals(usb(manufacturer = "M1").key, usb(manufacturer = "M2").key)

    @Test fun `a different product is a different key`() =
        assertNotEquals(usb(product = "P1").key, usb(product = "P2").key)

    @Test fun `the display name changing after a permission grant does not change the key`() =
        assertEquals(usb(name = "USB-MIDI").key, usb(name = "Launchpad X MIDI 1").key)

    @Test fun `with no product string the display name is all there is, and it is used`() =
        assertNotEquals(usb(product = null, name = "A").key, usb(product = null, name = "B").key)

    @Test fun `a bluetooth device has no vendor or product and its key says so`() {
        val f = MidiFingerprint.of(MidiFingerprint.Transport.BLUETOOTH, 0, 0, 0, 1, null, null, "Keys")
        assertTrue(f.key.startsWith("bluetooth:0:0:0:1:"))
    }

    @Test fun `the key is the transport, the ids, the port counts and the hash in hex`() =
        assertEquals("usb:1235:113:2:2:${Integer.toHexString(usb().nameHash)}", usb().key)
}
