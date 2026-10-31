package com.snipsnap.shell

/**
 * Which [MidiPads.Map] a keyboard was last played with, remembered per
 * keyboard so it comes back when that keyboard does.
 *
 * KIT used to open on [MidiPads.Map.MPC] every time. That is right for a
 * controller laid out like an MPC and wrong for most small ones: a MiniLab 3
 * or an MPK Mini sends notes 36 to 51 from its pads, which [MidiPads.Map.IN_ORDER]
 * puts on A01 to A16 exactly and the MPC map scatters (three of them land
 * outside the first sixteen pads). A player who picked "A01 = C1" once should
 * not have to pick it again every time the screen opens.
 *
 * The key is the device's [MidiFingerprint.key], never its display name,
 * which changes once USB permission is granted. The value is the map's enum
 * name, so a renamed label never strands a saved choice.
 *
 * Pure: where the pairs are stored is the app's side (`SharedPreferences`).
 */
object MidiPadMemory {

    /** Every saved choice's key starts with this. */
    const val KEY_PREFIX = "midi.padmap."

    fun keyOf(fingerprint: MidiFingerprint): String = KEY_PREFIX + fingerprint.key

    /**
     * The map to switch to for what is connected now: the saved choice of the
     * first device, in [devices] order, that has one. Null when none has, in
     * which case the screen keeps the map it is showing rather than resetting
     * it. A saved value that names no map (an older build's) counts as none.
     */
    fun recall(devices: List<MidiDeviceId>, read: (key: String) -> String?): MidiPads.Map? =
        devices.firstNotNullOfOrNull { device ->
            read(keyOf(device.fingerprint))?.let { name -> MidiPads.Map.entries.firstOrNull { it.name == name } }
        }

    /**
     * What to save when the player picks [map]: the same choice for every
     * device connected now, since the player chose it while playing all of
     * them. Empty when nothing is connected; a choice made with no keyboard
     * plugged in belongs to no keyboard.
     */
    fun remember(devices: List<MidiDeviceId>, map: MidiPads.Map): Map<String, String> =
        devices.associate { keyOf(it.fingerprint) to map.name }
}
