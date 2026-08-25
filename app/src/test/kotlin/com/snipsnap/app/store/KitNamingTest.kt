package com.snipsnap.app.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KitNamingTest {

    @Test
    fun `a plain name is accepted`() {
        val v = verifyKitName("NIGHT BUS", existing = emptyList())
        assertTrue(v is NameVerdict.Ok)
        assertEquals("NIGHT BUS", v.name)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val v = verifyKitName("  NIGHT BUS  ", existing = emptyList())
        assertTrue(v is NameVerdict.Ok)
        assertEquals("NIGHT BUS", v.name)
    }

    @Test
    fun `a blank name is refused`() {
        assertTrue(verifyKitName("   ", existing = emptyList()) is NameVerdict.Rejected)
    }

    @Test
    fun `a name the MPC browser could not show is refused`() {
        for (bad in listOf("BAD/NAME", "A:B", "Q?", "pipe|d", "star*")) {
            assertTrue(verifyKitName(bad, existing = emptyList()) is NameVerdict.Rejected, bad)
        }
    }

    @Test
    fun `a duplicate name is refused, case-insensitively`() {
        val v = verifyKitName("night bus", existing = listOf("NIGHT BUS"))
        assertTrue(v is NameVerdict.Rejected)
        assertTrue(v.reason.isNotBlank())
    }

    @Test
    fun `naming a kit TEST still works`() {
        val v = verifyKitName("TEST", existing = emptyList())
        assertTrue(v is NameVerdict.Ok)
        assertEquals("TEST", v.name)
    }
}
