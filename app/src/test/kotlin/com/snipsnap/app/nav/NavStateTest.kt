package com.snipsnap.app.nav

import com.snipsnap.shell.Copy
import com.snipsnap.shell.Personality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NavStateTest {

    @Test
    fun `the menu row is the prototype's nine items in order`() {
        assertEquals(
            listOf("KITS", "KIT", "TAPE", "CHOP", "PLAY", "SYNTH", "EXPORT", "⚙", "HELP"),
            Screen.entries.map { it.menuLabel },
        )
    }

    /**
     * Exhaustive over `Screen.entries`, not three spot checks: the two
     * renaming screens are the whole point of the property, and a test
     * that names them without asserting both is worse than none.
     */
    @Test
    fun `only the deck and the properties panel rename the window`() {
        val renamed = mapOf(
            Screen.TAPE to "TAPE DECK",
            Screen.PROPS to "TAPE PROPERTIES",
        )
        for (screen in Screen.entries) {
            assertEquals(
                renamed[screen] ?: "SNIPSNAP.EXE",
                screen.title,
                "wrong titlebar for $screen",
            )
        }
    }

    @Test
    fun `navigation moves the screen and leaves everything else alone`() {
        val start = NavState(Screen.KITS, Personality.FULL, quipIndex = 2)
        val next = start.goTo(Screen.PLAY)
        assertEquals(Screen.PLAY, next.screen)
        assertEquals(Personality.FULL, next.personality)
        assertEquals(2, next.quipIndex)
    }

    @Test
    fun `quips rotate through the shipped list at FULL`() {
        var s = NavState(Screen.KITS, Personality.FULL, quipIndex = 0)
        assertEquals(Copy.STATUS_QUIPS[0], s.statusQuip())
        s = s.tickQuip()
        assertEquals(Copy.STATUS_QUIPS[1], s.statusQuip())
        assertNotEquals(s.statusQuip(), NavState(Screen.KITS, Personality.FULL, 0).statusQuip())
    }

    @Test
    fun `quips wrap without running off the end`() {
        val n = Copy.STATUS_QUIPS.size
        // The boundary alone would also pass a `if (i >= n) first else i`
        // implementation; the second case pins the modulo.
        assertEquals(Copy.STATUS_QUIPS[0], NavState(Screen.KITS, Personality.FULL, n).statusQuip())
        assertEquals(Copy.STATUS_QUIPS[2], NavState(Screen.KITS, Personality.FULL, n + 2).statusQuip())
    }

    @Test
    fun `OFF and MILD show the drive readout instead of a quip`() {
        for (level in listOf(Personality.OFF, Personality.MILD)) {
            val s = NavState(Screen.KITS, level, quipIndex = 3)
            assertEquals(NavState.CARD_READY, s.statusQuip(), "personality $level")
        }
    }
}
