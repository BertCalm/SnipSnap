package com.snipsnap.app

import com.snipsnap.kit.Project
import com.snipsnap.kit.ProjectStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A deleted project sleeps in the kits' own bin (`ProjectStore.BIN_DIR`), so
 * `KitShelf`'s one sweep and its EMPTY THE BIN NOW cover projects too. This
 * pins the two folder names together and the days-left readout to the sweep.
 */
class ProjectBinTest {

    private val root: File = java.nio.file.Files.createTempDirectory("projectbin").toFile()
    private val shelf = KitShelf(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun binOne(at: Long) {
        ProjectStore.save(root, Project("Late Set", emptyList()))
        ProjectStore.trash(root, "Late Set", nowMillis = at)
    }

    @Test
    fun `the project bin is the kits bin`() {
        assertEquals(KitShelf.BIN_DIR, ProjectStore.BIN_DIR)
    }

    @Test
    fun `days left count up to the sweep, rounded up, and the sweep takes the project at 30 days`() {
        val day = 24L * 60 * 60 * 1000
        binOne(at = 1_000_000L)
        val fresh = shelf.binnedProjects(nowMillis = 1_000_000L).single()
        assertEquals("Late Set", fresh.name)
        assertEquals(30, fresh.daysLeft)
        assertEquals(1, shelf.binnedProjects(nowMillis = 1_000_000L + 29 * day + 1).single().daysLeft)
        assertEquals(0, shelf.sweepDeletedKits(nowMillis = 1_000_000L + 29 * day))
        assertEquals(1, shelf.sweepDeletedKits(nowMillis = 1_000_000L + 30 * day))
        assertEquals(emptyList(), shelf.binnedProjects())
    }

    @Test
    fun `the kits list never mistakes a binned project for a kit, and EMPTY THE BIN takes it`() {
        binOne(at = 5L)
        assertEquals(emptyList(), shelf.binnedKits())
        assertEquals(1, shelf.emptyKitBin())
        assertFalse(File(root, ".bin").listFiles().orEmpty().isNotEmpty())
    }
}
