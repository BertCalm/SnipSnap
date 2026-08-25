package com.snipsnap.app.ui

import com.snipsnap.kit.Names
import com.snipsnap.shell.StarterKits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarterNamingTest {

    /** The regression this function exists to prevent: "LUCKY DIP A/B" has a `/` in it. */
    @Test
    fun `every starter's name is MPC-safe, even with no other kits on the shelf`() {
        for (starter in StarterKits.ALL) {
            val name = starterKitName(starter, emptyList())
            assertTrue(Names.isMpcSafe(name), "${starter.id} produced an unsafe name: '$name'")
        }
    }

    @Test
    fun `the LUCKY DIP A-B starter sanitizes its slash away`() {
        val starter = StarterKits.byId("lucky-dip-ab")!!
        assertEquals("LUCKY DIP A_B", starterKitName(starter, emptyList()))
    }

    @Test
    fun `a free display name is used as-is`() {
        val factory = StarterKits.byId("factory")!!
        assertEquals("FACTORY", starterKitName(factory, emptyList()))
        assertEquals("FACTORY", starterKitName(factory, listOf("SOMETHING ELSE")))
    }

    @Test
    fun `a taken name gets a two-digit counter`() {
        val factory = StarterKits.byId("factory")!!
        assertEquals("FACTORY 02", starterKitName(factory, listOf("FACTORY")))
        assertEquals("FACTORY 03", starterKitName(factory, listOf("FACTORY", "FACTORY 02")))
    }

    @Test
    fun `the collision check is case-insensitive`() {
        val factory = StarterKits.byId("factory")!!
        assertEquals("FACTORY 02", starterKitName(factory, listOf("factory")))
    }

    @Test
    fun `renderingLine names the kit`() {
        assertTrue("FACTORY 02" in renderingLine("FACTORY 02"))
    }
}
