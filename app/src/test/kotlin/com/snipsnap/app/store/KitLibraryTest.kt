package com.snipsnap.app.store

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KitLibraryTest {

    private fun library() = KitLibrary(Files.createTempDirectory("kits").toFile())

    @Test
    fun `an empty root lists nothing`() {
        assertTrue(library().list().isEmpty())
    }

    @Test
    fun `seeding an empty root renders the factory kit`() {
        val lib = library()
        assertTrue(lib.seedIfEmpty(), "first seed should report that it wrote something")

        val entries = lib.list()
        assertEquals(1, entries.size)
        assertEquals(KitLibrary.SEED_NAME, entries[0].name)
        assertEquals(16, entries[0].padCount)

        val kit = lib.open(entries[0]).kit
        for (pad in kit.pads) {
            assertTrue(
                java.io.File(entries[0].dir, pad.sampleFile).length() > 44,
                "${pad.sampleFile} is header-only",
            )
        }
    }

    @Test
    fun `seeding is idempotent`() {
        val lib = library()
        lib.seedIfEmpty()
        assertFalse(lib.seedIfEmpty(), "a populated shelf must not be re-seeded")
        assertEquals(1, lib.list().size)
    }

    @Test
    fun `created kits appear on the shelf, sorted`() {
        val lib = library()
        lib.create("ZEBRA")
        lib.create("APPLE")
        assertEquals(listOf("APPLE", "ZEBRA"), lib.list().map { it.name })
    }

    @Test
    fun `a new kit starts empty`() {
        val lib = library()
        assertEquals(0, lib.create("NIGHT BUS").kit.pads.size)
        assertEquals(0, lib.list().single().padCount)
    }

    @Test
    fun `names that would not survive an MPC card are refused`() {
        val lib = library()
        assertFailsWith<IllegalArgumentException> { lib.create("BAD/NAME") }
        assertFailsWith<IllegalArgumentException> { lib.create("   ") }
        assertTrue(lib.list().isEmpty(), "a refused name must not leave a folder behind")
    }

    @Test
    fun `a folder without kit json is not a kit`() {
        val lib = library()
        lib.create("REAL")
        java.io.File(lib.root, "not-a-kit").mkdirs()
        assertEquals(listOf("REAL"), lib.list().map { it.name })
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

        val entries = lib.list()
        assertEquals(listOf("GOOD ONE", "GOOD TWO"), entries.map { it.name })
        assertEquals(1, lib.unreadable.size)
        assertEquals(badDir, lib.unreadable[0].dir)
    }

    @Test
    fun `create refuses to overwrite a kit that sanitizes to the same folder`() {
        val lib = library()
        val first = lib.create("A_B")
        assertFailsWith<IllegalArgumentException> { lib.create("A__B") }

        // The one that matters: the first kit's kit.json must still be intact.
        val reloaded = lib.open(lib.list().single { it.dir == first.kitDir })
        assertEquals("A_B", reloaded.kit.name)
    }
}
