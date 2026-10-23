package com.snipsnap.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A kit's name is a folder name everywhere it lands (shelf, export, the
 * `.xpn`/`.xpj` stem), so a name read out of a stranger's `kit.json` must
 * not be able to walk an export out of its destination — the same rule
 * [Project] already holds its name to, and pads hold their sample files to.
 */
class KitNameTest {

    @Test
    fun `a kit name cannot escape its folder`() {
        for (name in listOf("../Escape", "..\\Escape", "a/b", "a\\b", "..", ".", "", "  padded  ", "nul\u0000name")) {
            assertFailsWith<IllegalArgumentException>("name '$name' must be refused") {
                Kit(name, emptyList())
            }
        }
    }

    @Test
    fun `ordinary kit names still construct`() {
        for (name in listOf("SnipSnap Kit 01", "FUNK 2", "Café Breaks", "kit.name.v2", "A [Previews] Kit")) {
            assertEquals(name, Kit(name, emptyList()).name)
        }
    }
}
