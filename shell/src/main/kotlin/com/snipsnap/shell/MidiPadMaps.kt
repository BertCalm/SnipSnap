package com.snipsnap.shell

/**
 * Which pad map PLAY uses, remembered for the controller that was plugged in.
 *
 * Per device, not per port: the owner decided on 2026-10-26 that a controller
 * is one thing however many ports it shows, and [MidiFingerprint.key] already
 * identifies a device steadily across reconnects (it is not the display name,
 * which changes after a USB permission grant). So the store keys on that.
 *
 * When several controllers are connected the most recently connected one wins
 * (device lists arrive in the order each `openDevice` completes), because that is the one the
 * player has just reached for. SnipSnap's own virtual port
 * (Transport.VIRTUAL) never stores or restores: another app's notes follow
 * whatever PLAY shows, they are not a controller to remember.
 *
 * Pure; reading and writing the preferences is the screen's side.
 */
object MidiPadMaps {

    /** The preferences key a device's pad map is stored under. */
    fun key(device: MidiDeviceId): String = "midi.padmap.${device.fingerprint.key}"

    /** A stored name back to a map; anything unknown is null, never a throw. */
    fun decode(raw: String?): MidiPads.Map? = MidiPads.Map.entries.firstOrNull { it.name == raw }

    /**
     * The map to switch to when [devices] changes: the most recently connected hardware device
     * (last in [devices], skipping Transport.VIRTUAL) that has a stored map. null: keep the current one.
     */
    fun restore(devices: List<MidiDeviceId>, stored: (String) -> String?): MidiPads.Map? =
        devices.asReversed().asSequence()
            .filter { it.fingerprint.transport != MidiFingerprint.Transport.VIRTUAL }
            .firstNotNullOfOrNull { decode(stored(key(it))) }

    /** The keys to write when the player picks [map]: every connected hardware device. */
    fun keysToSave(devices: List<MidiDeviceId>): List<String> =
        devices.filter { it.fingerprint.transport != MidiFingerprint.Transport.VIRTUAL }
            .map(::key).distinct()
}
