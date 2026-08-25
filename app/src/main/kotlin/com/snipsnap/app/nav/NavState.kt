package com.snipsnap.app.nav

import com.snipsnap.shell.Copy
import com.snipsnap.shell.Delight
import com.snipsnap.shell.Personality

/**
 * Where the app is and how chatty it is. A plain immutable value with pure
 * transitions — the whole navigation layer is testable without an
 * emulator, which is the point of keeping `Context` out of it.
 */
data class NavState(
    val screen: Screen,
    val personality: Personality = Personality.FULL,
    val quipIndex: Int = 0,
) {
    companion object {
        /** What the third status cell reads when quips are switched off. */
        const val CARD_READY = "SD (E:) READY"
    }
}

fun NavState.goTo(screen: Screen): NavState = copy(screen = screen)

/** Advances the rotating status quip; call on [com.snipsnap.shell.Motion.QUIP_ROTATE_MS]. */
fun NavState.tickQuip(): NavState = copy(quipIndex = quipIndex + 1)

/**
 * The third status cell. Law 3 — jokes never gate function: with
 * personality below FULL the cell still says something true and useful.
 */
fun NavState.statusQuip(): String =
    if (Delight.quipsEnabled(personality)) {
        Copy.rotating(Copy.STATUS_QUIPS, quipIndex)
    } else {
        NavState.CARD_READY
    }
