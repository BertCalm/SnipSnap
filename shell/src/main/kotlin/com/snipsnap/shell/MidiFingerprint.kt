package com.snipsnap.shell

/**
 * What a MIDI device is, steadily enough to key a controller profile or a
 * learned mapping on.
 *
 * The display name is not that. RtMidi's October 2026 Android work found a
 * USB device's name changes once permission is granted (a generic string
 * before, the product's own after), and a Bluetooth device's address
 * randomises, so neither can be what a saved profile finds its device by.
 * What holds still is the transport, the USB vendor and product ids (0
 * where the transport has none), the port counts, and the manufacturer and
 * product strings the device reports about itself.
 *
 * The rule, then: never key on the display name alone, and never read the
 * Bluetooth device's address. [nameHash] is of manufacturer and product;
 * the display name enters it only when the device reports no product
 * string, because then there is nothing else to tell two of them apart by.
 *
 * Pure, so the rule is tested here; reading a `MidiDeviceInfo` into these
 * arguments is the app's side. Nothing is persisted from this yet.
 */
data class MidiFingerprint(
    val transport: Transport,
    /** 0 when the transport has none. */
    val vendorId: Int,
    /** 0 when the transport has none. */
    val productId: Int,
    val inputPorts: Int,
    val outputPorts: Int,
    /** Of manufacturer and product, never of the display name alone. */
    val nameHash: Int,
) {
    enum class Transport { USB, BLUETOOTH, VIRTUAL, UNKNOWN }

    /**
     * A stable string for persistence, e.g. `usb:1235:113:2:2:7f3a2b1c`:
     * the transport in lower case, then the ids, port counts and hash in
     * lower-case hex with no leading zeros.
     */
    val key: String
        get() = listOf(
            transport.name.lowercase(),
            Integer.toHexString(vendorId),
            Integer.toHexString(productId),
            inputPorts.toString(),
            outputPorts.toString(),
            Integer.toHexString(nameHash),
        ).joinToString(":")

    companion object {
        /**
         * The fingerprint of a device from what it reports. [name] is the
         * display name, used only when [product] is null.
         */
        fun of(
            transport: Transport,
            vendorId: Int,
            productId: Int,
            inputPorts: Int,
            outputPorts: Int,
            manufacturer: String?,
            product: String?,
            name: String?,
        ): MidiFingerprint = MidiFingerprint(
            transport, vendorId, productId, inputPorts, outputPorts,
            ((manufacturer ?: "").hashCode() * 31) + (product ?: name ?: "").hashCode(),
        )
    }
}

/** One connected device as the screens see it: what to print, and what to key on. */
data class MidiDeviceId(val name: String, val fingerprint: MidiFingerprint)
