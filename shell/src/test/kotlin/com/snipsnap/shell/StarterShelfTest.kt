package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarterShelfTest {
    private val placed = StarterShelf.GROUPS.flatMap { it.ids }

    @Test
    fun `every starter appears in exactly one group`() {
        val all = StarterKits.ALL.map { it.id }
        assertEquals(emptyList(), placed.groupBy { it }.filterValues { it.size > 1 }.keys.toList(), "starters filed twice")
        assertEquals(emptyList(), all - placed.toSet(), "starters with no group: place each in StarterShelf")
        assertEquals(emptyList(), placed - all.toSet(), "StarterShelf names starters that do not exist")
        assertEquals(all.size, placed.size)
    }

    @Test
    fun `headings come in the approved order and no group is empty`() {
        assertEquals(
            listOf(
                "START HERE", "BASS", "KEYS AND CHORDS", "PLUCKS AND STRINGS", "BREATH AND REEDS",
                "VOICES", "METAL WOOD AND GLASS", "SKINS", "MOVING AND STRANGE",
            ),
            StarterShelf.GROUPS.map { it.heading },
        )
        assertTrue(StarterShelf.GROUPS.all { it.ids.isNotEmpty() })
    }

    @Test
    fun `display resolves every starter and leaves the stored order alone`() {
        assertEquals(StarterKits.ALL.size, StarterShelf.display().sumOf { it.second.size })
        assertEquals("blank", StarterKits.ALL.first().id)
    }
}
