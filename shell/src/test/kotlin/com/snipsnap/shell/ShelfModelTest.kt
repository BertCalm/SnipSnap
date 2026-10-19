package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Project
import com.snipsnap.kit.ProjectStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShelfModelTest {

    private val shelf: File = java.nio.file.Files.createTempDirectory("shelfmodel").toFile()
    private val model = ShelfModel(shelf)

    @AfterTest
    fun cleanUp() {
        shelf.deleteRecursively()
    }

    private fun kitOnShelf(folder: String, tempo: Float? = null) {
        KitStore.save(
            Kit(folder, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)), tempoBpm = tempo),
            File(shelf, folder),
        )
    }

    @Test
    fun `opening the shelf gives every unheld kit a project, and the lists agree`() {
        kitOnShelf("Kitchen", tempo = 94f)
        kitOnShelf("Funk Room")
        val result = model.open()
        assertEquals(listOf("Funk Room", "Kitchen"), result.created)

        val projects = model.projects()
        assertEquals(setOf("Funk Room", "Kitchen"), projects.map { it.name }.toSet())
        assertEquals("1 KIT · 94 BPM", projects.first { it.name == "Kitchen" }.subtitle)
        assertEquals("1 KIT", projects.first { it.name == "Funk Room" }.subtitle)

        val kits = model.kits()
        assertEquals(listOf("Funk Room", "Kitchen"), kits.map { it.folder }, "A–Z, the shelf's order")
        assertEquals(listOf("Kitchen"), kits.first { it.folder == "Kitchen" }.heldBy)
        assertEquals("IN KITCHEN", kits.first { it.folder == "Kitchen" }.subtitle)
        assertEquals(emptyList(), model.open().created, "idempotent")
    }

    @Test
    fun `projects list most recently edited first`() {
        ProjectStore.save(shelf, Project("Old"))
        val oldFile = File(ProjectStore.dir(shelf, "Old"), ProjectStore.FILE_NAME)
        oldFile.setLastModified(oldFile.lastModified() - 60_000)
        ProjectStore.save(shelf, Project("Fresh"))
        assertEquals(listOf("Fresh", "Old"), model.projects().map { it.name })
        assertEquals(ShelfModel.EMPTY, model.projects().first().subtitle)
    }

    @Test
    fun `a new project takes a fresh name and starts empty`() {
        val a = model.newProject()
        val b = model.newProject()
        assertEquals("New Project", a.name)
        assertEquals("New Project 2", b.name)
        assertEquals(emptyList(), a.kits)
        assertTrue(ProjectStore.exists(shelf, "New Project 2"))
    }

    @Test
    fun `a kit joins a project, becomes active when first, and can be dropped or switched`() {
        kitOnShelf("Kitchen")
        kitOnShelf("Funk Room")
        val project = model.newProject("Late Set")
        assertEquals("IN NO PROJECT", model.kits().first { it.folder == "Kitchen" }.subtitle)

        val one = model.addKit(project.name, "Kitchen")
        assertEquals("Kitchen", one.activeKit)
        val two = model.addKit(project.name, "Funk Room")
        assertEquals("Kitchen", two.activeKit, "adding does not steal the active slot")
        assertEquals(listOf("Late Set"), model.heldBy("Funk Room"))
        assertEquals("2 KITS", model.projects().first { it.name == "Late Set" }.subtitle)

        assertEquals("Funk Room", model.activate(project.name, "Funk Room").activeKit)
        val dropped = model.dropKit(project.name, "Funk Room")
        assertEquals(listOf("Kitchen"), dropped.kits)
        assertEquals("Kitchen", dropped.activeKit)
        assertTrue(File(shelf, "Funk Room").isDirectory, "dropping a kit from a project deletes nothing")
        assertEquals(dropped, model.openProject("Late Set"), "saved")

        assertFailsWith<IllegalArgumentException> { model.addKit(project.name, "Nowhere") }
    }

    @Test
    fun `opening a kit row opens the project that holds it, or makes one`() {
        kitOnShelf("Kitchen")
        kitOnShelf("Late Arrival")
        ProjectStore.save(shelf, Project("Late Set", listOf("Kitchen")))

        assertEquals("Late Set", model.projectForKit("Kitchen").name, "the first project that holds it")
        val made = model.projectForKit("Late Arrival")
        assertEquals("Late Arrival", made.name, "a kit that arrived since the shelf opened gets the migration's own rule")
        assertEquals(listOf("Late Arrival"), made.kits)
        assertEquals("Late Arrival", model.projectForKit("Late Arrival").name, "and the second time, that project")
    }

    @Test
    fun `a project folder that will not read is skipped, not fatal`() {
        ProjectStore.save(shelf, Project("Good"))
        val bad = ProjectStore.dir(shelf, "Bad")
        bad.mkdirs()
        File(bad, ProjectStore.FILE_NAME).writeText("{not json")
        assertEquals(listOf("Good"), model.projects().map { it.name })
    }
}
