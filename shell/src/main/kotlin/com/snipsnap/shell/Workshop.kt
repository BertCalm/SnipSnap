package com.snipsnap.shell

/**
 * The WORKSHOP: the developer's tools inside the app, behind a knock
 * (`docs/WORKSHOP.md`).
 *
 * The tools live at the foot of SETUP and show only once the workshop is
 * open. It opens on [KNOCKS] taps on SETUP's own title, each inside
 * [KNOCK_WINDOW_MS] of the last — the gesture Android puts on its build
 * number, so it is discoverable by poking at the machine
 * (`docs/PERSONALITY.md`, law 4) and is never tripped by an idle thumb.
 * The app remembers it open like the scheme; the section carries its own
 * CLOSE, and the closing toast says how to get back in.
 *
 * Nothing behind the knock gates function (law 3): a workshop tool adds a
 * control and never hides one, so a phone that never knocks plays exactly
 * as it did. And nothing behind it sends anything by itself — SEND TO
 * BENCH ([BenchExport]) is a button, pressed on purpose, through the same
 * chooser BACKUP uses.
 *
 * Why not a tab: the menu row holds twelve and its own comment says a
 * thirteenth fits no phone; and a tab is a promise to every player that
 * the screen behind it is for them. Why not a build type: a debug APK is
 * the only build anyone installs today, so `BuildConfig.DEBUG` would be a
 * gate that is always open, which is no gate.
 */
object Workshop {

    /** Taps on SETUP's title that open the workshop. */
    const val KNOCKS = 7

    /** The longest gap between two taps that still counts as one knock. */
    const val KNOCK_WINDOW_MS = 2_000L

    /**
     * From this many taps still needed and down, each tap says how many are
     * left ([Copy.workshopKnock]) — Android's own developer-options
     * convention: a few quiet taps, then a countdown. A knock that never
     * spoke would be a secret; one that counted from the first tap would
     * be a button.
     */
    const val HINT_FROM = 3

    /** Whether a tap that left [remaining] taps to go should say so. */
    fun hints(remaining: Int): Boolean = remaining in 1..HINT_FROM

    /**
     * The knock itself: taps counted while they come inside the window,
     * reset by a pause. One instance lives as long as the app does; the
     * count is not remembered across a restart, because a knock is a
     * gesture, not a setting.
     */
    class Knock(
        private val knocks: Int = KNOCKS,
        private val windowMs: Long = KNOCK_WINDOW_MS,
    ) {
        init {
            require(knocks >= 1) { "a knock is at least one tap, got $knocks" }
            require(windowMs >= 0) { "the window can't be negative, got $windowMs" }
        }

        private var taps = 0
        private var lastMs = 0L

        /**
         * One tap at [nowMs] (any millisecond clock; only differences are
         * read). Returns the taps still needed: 0 means this tap opened the
         * workshop, and the count starts over for the next knock.
         *
         * `taps > 0 &&` first: with no tap yet there is no gap to measure,
         * and measuring one against a zero `lastMs` would count the very
         * first tap as inside the window of a tap that never happened.
         */
        fun tap(nowMs: Long): Int {
            taps = if (taps > 0 && nowMs - lastMs <= windowMs) taps + 1 else 1
            lastMs = nowMs
            val remaining = knocks - taps
            if (remaining <= 0) {
                taps = 0
                return 0
            }
            return remaining
        }
    }
}
