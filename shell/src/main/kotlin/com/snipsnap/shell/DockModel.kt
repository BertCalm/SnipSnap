package com.snipsnap.shell

/**
 * The dock (`docs/SCREEN_PLAN_2026_10.md` §1): the strip at the foot of
 * every screen, on the LCD surface, with three regions the Compose layer
 * draws from this one object.
 *
 * - [transport]: the project's beat, which keeps playing when you leave
 *   BEAT for a pad bench. The clock is App's (`BeatPlayer`), and writes
 *   this at bar granularity.
 * - [capture]: the light and counter the shelf's ArmControl shows today,
 *   and nothing else does.
 * - [now]: the line and the standing door. The toast's words land here
 *   and stay until replaced; the door stays until it is taken or another
 *   offer replaces it. The seven-second offer dwell (J10) retires:
 *   nothing here is timed. A line is scoped to where it was said: moving
 *   to another place or stage rests it ([moved]). A door is not — its job
 *   is to survive the move — so a door and its words stay until the door
 *   is taken, replaced, or the user gets to its target some other way.
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

    /**
     * The line, its door, and [at]: where the line was said. [at] is null
     * only for the resting line, which belongs nowhere and so is never
     * stale.
     */
    data class Now(val text: String, val door: Door?, val at: Location?)

    var transport: Transport = Transport.Stopped
    var capture: Capture = Capture.Idle

    var now: Now = RESTING
        private set

    /** A new line, said at [at]. The standing door, if any, stays: a line is not an offer. */
    fun say(text: String, at: Location) {
        now = now.copy(text = text, at = at)
    }

    /**
     * A line with a door, said at [at]. Replaces any earlier door. A door
     * to where [at] already is leads nowhere, so it is never stood: the
     * words land as a plain line.
     */
    fun offer(text: String, door: Door, at: Location) {
        now = Now(text, door.takeIf { it.target != at }, at)
    }

    /**
     * The user is now at [to], by whatever road: a place, a stage, Back, a
     * door, a landing. A door to [to] is moot and goes with its words, since
     * the user got there another way. Any other door stays, and its words
     * with it ("a door can never outlive its words" holds both ways). With
     * no door, a line said at another place or stage rests; a bench over
     * the same stage keeps it, since the pad sheet still shows what PADS
     * just said.
     */
    fun moved(to: Location) {
        val door = now.door
        val at = now.at
        now = when {
            door != null && door.target == to -> RESTING
            door != null -> now
            at != null && (at.place != to.place || at.stage != to.stage) -> RESTING
            else -> now
        }
    }

    /** The door is taken: the caller navigates to what it returns, and it is gone from the dock. */
    fun take(): Door? {
        val door = now.door ?: return null
        now = now.copy(door = null)
        return door
    }

    /** Back to the resting line, no door. */
    fun clear() {
        now = RESTING
    }

    private companion object {
        val RESTING = Now(Copy.DOCK_READY, null, null)
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
