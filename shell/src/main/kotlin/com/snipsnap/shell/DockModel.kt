package com.snipsnap.shell

/**
 * The dock (`docs/SCREEN_PLAN_2026_10.md` §1): the strip at the foot of
 * every screen, on the LCD surface, with three regions the Compose layer
 * draws from this one object.
 *
 * - [transport]: the project's beat, which keeps playing when you leave
 *   BEAT for a pad bench. Today that lives inside GrooveScreen and stops
 *   on a tab change.
 * - [capture]: the light and counter the shelf's ArmControl shows today,
 *   and nothing else does.
 * - [now]: the line and the standing door. The toast's words land here
 *   and stay until replaced; the door stays until it is taken or another
 *   offer replaces it. The seven-second offer dwell (J10) retires:
 *   nothing here is timed.
 */
class DockModel {

    sealed interface Transport {
        data object Stopped : Transport
        data class Playing(val bpm: Float?, val bar: Int) : Transport
        data class Recording(val bar: Int) : Transport
    }

    sealed interface Capture {
        data object Idle : Capture
        data class Armed(val secondsOnTape: Int) : Capture
        data object Snipped : Capture
    }

    data class Now(val text: String, val door: Door?)

    var transport: Transport = Transport.Stopped
    var capture: Capture = Capture.Idle

    var now: Now = Now(Copy.DOCK_READY, null)
        private set

    /** A new line. The standing door, if any, stays: a line is not an offer. */
    fun say(text: String) {
        now = now.copy(text = text)
    }

    /** A line with a door. Replaces any earlier door. */
    fun offer(text: String, door: Door) {
        now = Now(text, door)
    }

    /** The door is taken: the caller navigates to what it returns, and it is gone from the dock. */
    fun take(): Door? {
        val door = now.door ?: return null
        now = now.copy(door = null)
        return door
    }

    /** Back to the resting line, no door. */
    fun clear() {
        now = Now(Copy.DOCK_READY, null)
    }

    /** What the capture region reads. */
    val captureLabel: String
        get() = when (val c = capture) {
            Capture.Idle -> Places.LISTEN
            is Capture.Armed -> Places.armed(c.secondsOnTape)
            Capture.Snipped -> Places.SNIPPED
        }

    /** What the transport's readout reads. */
    val transportLabel: String
        get() = when (val t = transport) {
            Transport.Stopped -> Places.STOPPED
            is Transport.Playing -> Places.playing(t.bpm, t.bar)
            is Transport.Recording -> Places.recording(t.bar)
        }
}
