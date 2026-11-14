package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules for how long the pad editor's saved state lives (spec §7.3.1). */
class PadEditorKeepTest {

    private fun overPads(vararg benches: Bench): Navigation {
        val nav = Navigation()
        nav.go(Place.PROJECT)
        benches.forEach { nav.open(it) }
        return nav
    }

    @Test
    fun `the editor is in the stack under GRAIN, SPLICE and STACK, and gone after Back out of it`() {
        for (tool in listOf(Bench.GRAIN_FIELD, Bench.SPLICE, Bench.STACK)) {
            val nav = overPads(Bench.PAD_SHEET, tool)
            assertEquals(tool, nav.location.top)
            assertTrue(PadEditorKeep.inStack(nav.location), "$tool opens over the editor, which is still in the stack")
            nav.closeBench()
            assertTrue(PadEditorKeep.inStack(nav.location), "Back from $tool lands on the editor")
            nav.closeBench()
            assertFalse(PadEditorKeep.inStack(nav.location), "Back out of the editor takes it out of the stack")
        }
    }

    @Test
    fun `its own EFFECTS and VARIATIONS pages count as the editor, and a place switch drops it`() {
        assertTrue(PadEditorKeep.inStack(overPads(Bench.PAD_SHEET, Bench.PAD_EFFECTS).location))
        assertTrue(PadEditorKeep.inStack(overPads(Bench.PAD_SHEET, Bench.PAD_VARIATIONS).location))
        val nav = overPads(Bench.PAD_SHEET, Bench.GRAIN_FIELD)
        nav.go(Place.SHELF)
        assertFalse(PadEditorKeep.inStack(nav.location))
        assertFalse(PadEditorKeep.inStack(overPads(Bench.VERSIONS).location), "another bench over PADS is not the editor")
    }

    @Test
    fun `release drops every key it handed out, once`() {
        val removed = mutableListOf<String>()
        val keys = PadEditorKeys { removed += it }
        val a = keys.keyFor("/kits/a")
        val b = keys.keyFor("/kits/b")
        assertTrue(a != b, "a different kit is a different entry")
        keys.release()
        assertEquals(listOf(a, b), removed)
        keys.release()
        assertEquals(listOf(a, b), removed, "nothing left to drop the second time")
    }

    @Test
    fun `opening a different pad or kit drops what was held, and the same pad keeps it`() {
        val removed = mutableListOf<String>()
        val keys = PadEditorKeys { removed += it }
        keys.opened("/kits/a", 5)
        val k = keys.keyFor("/kits/a")
        keys.opened("/kits/a", 5)
        assertEquals(emptyList(), removed, "the same pad opened again keeps what is parked")
        keys.opened("/kits/a", 6)
        assertEquals(listOf(k), removed, "pad 6 does not inherit pad 5's staged edits")
        removed.clear()
        keys.keyFor("/kits/a")
        keys.opened("/kits/b", 6)
        assertEquals(listOf(k), removed, "another kit does not inherit them either")
    }

    @Test
    fun `stepping pads inside the editor is not an opening`() {
        val removed = mutableListOf<String>()
        val keys = PadEditorKeys { removed += it }
        keys.opened("/kits/a", 5)
        keys.keyFor("/kits/a")
        keys.retarget(7)
        keys.opened("/kits/a", 7)
        assertEquals(emptyList(), removed, "the editor stayed in the stack, on the pad it stepped to")
    }

    @Test
    fun `keys a previous process handed out are released by the next one, so no pad inherits them`() {
        // Process death: the holder's parked state comes back under the old keys, but a fresh
        // PadEditorKeys starts with nothing, so release() would remove nothing.
        val before = PadEditorKeys { }
        val old = before.keyFor("/kits/a")
        val restored = mutableListOf<String>()
        val after = PadEditorKeys(before.heldKeys()) { restored += it }
        after.opened("/kits/a", 7)
        assertEquals(listOf(old), restored, "pad 7 does not inherit what pad 3 parked before the process died")
        after.release()
        assertEquals(emptyList(), after.heldKeys())
    }
}
