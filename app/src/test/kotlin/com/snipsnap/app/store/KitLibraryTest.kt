package com.snipsnap.app.store

import com.snipsnap.shell.StarterKits
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KitLibraryTest {

    private fun library() = KitLibrary(Files.createTempDirectory("kits").toFile())

    @Test
    fun `an empty root lists nothing`() {
        assertTrue(library().list().entries.isEmpty())
    }

    @Test
    fun `createFromStarter renders a playable kit and lands it on the shelf`() {
        val lib = library()
        val factory = StarterKits.byId("factory")!!
        val model = lib.createFromStarter(factory, "FACTORY", seed = 0)

        assertTrue(model.kit.pads.isNotEmpty())
        val entries = lib.list().entries
        assertEquals(1, entries.size)
        assertEquals("FACTORY", entries[0].name)
        assertEquals(model.kit.pads.size, entries[0].padCount)
    }

    @Test
    fun `createFromStarter refuses a folder that already exists`() {
        val lib = library()
        val factory = StarterKits.byId("factory")!!
        lib.createFromStarter(factory, "FACTORY", seed = 0)
        assertFailsWith<IllegalArgumentException> { lib.createFromStarter(factory, "FACTORY", seed = 1) }
        assertEquals(1, lib.list().entries.size)
    }

    @Test
    fun `created kits appear on the shelf, sorted`() {
        val lib = library()
        lib.create("ZEBRA")
        lib.create("APPLE")
        assertEquals(listOf("APPLE", "ZEBRA"), lib.list().entries.map { it.name })
    }

    @Test
    fun `a new kit starts empty`() {
        val lib = library()
        assertEquals(0, lib.create("NIGHT BUS").kit.pads.size)
        assertEquals(0, lib.list().entries.single().padCount)
    }

    @Test
    fun `names that would not survive an MPC card are refused`() {
        val lib = library()
        assertFailsWith<IllegalArgumentException> { lib.create("BAD/NAME") }
        assertFailsWith<IllegalArgumentException> { lib.create("   ") }
        assertTrue(lib.list().entries.isEmpty(), "a refused name must not leave a folder behind")
    }

    @Test
    fun `a folder without kit json is not a kit`() {
        val lib = library()
        lib.create("REAL")
        java.io.File(lib.root, "not-a-kit").mkdirs()
        assertEquals(listOf("REAL"), lib.list().entries.map { it.name })
    }

    @Test
    fun `one unreadable kit is skipped, not fatal`() {
        val lib = library()
        lib.create("GOOD ONE")
        lib.create("GOOD TWO")

        val badDir = java.io.File(lib.root, "FUTURE")
        badDir.mkdirs()
        java.io.File(badDir, "kit.json").writeText(
            """{"version": 99, "name": "FUTURE", "pads": []}""",
        )

        val listing = lib.list()
        assertEquals(listOf("GOOD ONE", "GOOD TWO"), listing.entries.map { it.name })
        assertEquals(1, listing.unreadable.size)
        assertEquals(badDir, listing.unreadable[0].dir)
    }

    @Test
    fun `create refuses to overwrite a kit that sanitizes to the same folder`() {
        val lib = library()
        val first = lib.create("A_B")
        assertFailsWith<IllegalArgumentException> { lib.create("A__B") }

        // The one that matters: the first kit's kit.json must still be intact.
        val reloaded = lib.open(lib.list().entries.single { it.dir == first.kitDir })
        assertEquals("A_B", reloaded.kit.name)
    }

    @Test
    fun `creating the same name twice is refused`() {
        val lib = library()
        lib.create("APPLE")
        assertFailsWith<IllegalArgumentException> { lib.create("APPLE") }
        assertEquals(1, lib.list().entries.size)
    }
}
