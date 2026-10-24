package com.snipsnap.shell

/**
 * One connected device and its ports, as the MIDI screen lists them.
 *
 * [sends] are the device's output ports, the ones SnipSnap listens to;
 * [receives] are its input ports, which nothing in the app writes to yet.
 * A controller often has several of each (the MiniLab 3's Live guide
 * names four), and knowing which one a control speaks on is half of
 * mapping it.
 */
data class MidiEndpoint(val device: MidiDeviceId, val sends: List<String>, val receives: List<String>)

/**
 * The MIDI screen's words, and the report its COPY REPORT button puts on
 * the clipboard.
 *
 * Every "my keyboard does nothing" is one of four cases: the phone has no
 * MIDI at all, nothing is plugged in (or it is not powered), the device
 * refused to open, or it is open and sending something the screen does
 * not use. [status] says which in plain words, and [text] carries the
 * same facts plus the monitor's last lines, so a report pasted into an
 * issue answers the first questions anyone would ask about it.
 *
 * Pure, so the words are tested here; reading the phone and the devices
 * into these arguments is the app's side.
 */
object MidiReport {

    /** What the report says about the phone and the build, read by the app. */
    data class Phone(
        /** The app's version name; null when the package manager would not say. */
        val app: String?,
        /** `Build.VERSION.RELEASE`, e.g. "15". */
        val android: String,
        val sdk: Int,
        val maker: String,
        val model: String,
    )

    const val TITLE = "MIDI"
    const val COPY = "COPY REPORT"
    const val COPIED = "REPORT COPIED"
    const val CLEAR = "CLEAR"
    const val MONITOR = "MONITOR"
    const val CLIP_LABEL = "SnipSnap MIDI report"

    /** How many monitor lines the report carries, oldest first. */
    const val REPORT_LINES = 32

    const val NO_MIDI = "THIS PHONE HAS NO MIDI SUPPORT"
    val NO_MIDI_HELP = listOf(
        "ANDROID REPORTS NO android.software.midi",
        "NO KEYBOARD CAN BE HEARD ON THIS PHONE",
    )

    const val NOTHING = "NO MIDI DEVICE FOUND"
    val NOTHING_HELP = listOf(
        "PLUG IN WITH A USB-C CABLE OR OTG ADAPTER",
        "STILL DARK? IT MAY NEED MORE POWER:",
        "TRY A POWERED USB HUB",
        "BLUETOOTH KEYBOARDS ARE NOT SUPPORTED YET",
    )

    const val PLAY_SOMETHING = "PLAY A KEY OR MOVE A KNOB: IT SHOWS BELOW"

    /** The headline, then the lines under it. */
    fun status(supported: Boolean, endpoints: List<MidiEndpoint>, refused: List<String>): List<String> = when {
        !supported -> listOf(NO_MIDI) + NO_MIDI_HELP
        endpoints.isEmpty() -> listOf(NOTHING) + refused.map(::refusedLine) + NOTHING_HELP
        else -> listOf(connected(endpoints.size)) + refused.map(::refusedLine) + PLAY_SOMETHING
    }

    fun connected(count: Int): String = if (count == 1) "1 DEVICE CONNECTED" else "$count DEVICES CONNECTED"

    fun refusedLine(name: String): String = "${name.uppercase()} WOULD NOT OPEN"

    /** `USB 1C75:020B`, or the transport alone when it has no ids. */
    fun transport(f: MidiFingerprint): String =
        if (f.vendorId == 0 && f.productId == 0) f.transport.name else "%s %04X:%04X".format(java.util.Locale.ROOT, f.transport.name, f.vendorId, f.productId)

    /** One monitor line as the screen and the report print it: delta, port, bytes, meaning. */
    fun line(l: MidiMonitor.Line): String {
        val delta = l.deltaMillis?.let { "+%.1f ms".format(java.util.Locale.ROOT, it) } ?: "first"
        return "$delta  ${l.port}  ${l.hex}  ${l.meaning}"
    }

    /** The whole report, plain text, for an issue or a message. */
    fun text(
        phone: Phone,
        supported: Boolean,
        endpoints: List<MidiEndpoint>,
        refused: List<String>,
        monitor: MidiMonitor.Snapshot,
    ): String = buildString {
        appendLine("SNIPSNAP MIDI REPORT")
        appendLine("APP ${phone.app ?: "UNKNOWN"} · ANDROID ${phone.android} (API ${phone.sdk}) · ${phone.maker} ${phone.model}")
        appendLine("MIDI SUPPORT: ${if (supported) "YES" else "NO"}")
        appendLine("DEVICES: ${endpoints.size}")
        for (e in endpoints) {
            appendLine("- ${e.device.name} · ${transport(e.device.fingerprint)} · KEY ${e.device.fingerprint.key}")
            appendLine("  SENDS: ${e.sends.ifEmpty { listOf("NONE") }.joinToString(" | ")}")
            appendLine("  RECEIVES: ${e.receives.ifEmpty { listOf("NONE") }.joinToString(" | ")}")
        }
        for (name in refused) appendLine(refusedLine(name))
        appendLine("CLOCK TICKS ${monitor.clockTicks} · ACTIVE SENSING ${monitor.activeSensing} · MESSAGES ${monitor.total}")
        val last = monitor.lines.take(REPORT_LINES).asReversed()
        appendLine("LAST ${last.size} MESSAGES, OLDEST FIRST")
        for (l in last) appendLine(line(l))
    }
}
