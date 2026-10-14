package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectMigrationTest {

    private val shelf: File = java.nio.file.Files.createTempDirectory("projectmigration").toFile()

    @AfterTest
    fun cleanUp() {
        shelf.deleteRecursively()
    }

    private fun kitOnShelf(folder: String, tempo: Float? = null, key: KeySpec? = null): File {
        val dir = File(shelf, folder)
        KitStore.save(
            Kit(
                folder,
                listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)),
                key = key,
                tempoBpm = tempo,
            ),
            dir,
        )
        return dir
    }

    private fun clip(name: String) = Mpc3Clip(name, 2, listOf(Mpc3Note(36, 0, 1f, 240)))

    @Test
    fun `every kit becomes a project of its folder name, holding it, with its tempo, key and grooves`() {
        val key = KeySpec(rootSemitone = 2, scale = Scale.entries.first())
        val kitchen = kitOnShelf("Kitchen", tempo = 94f, key = key)
        GrooveStore.save(kitchen, listOf(clip("Captured"), clip("Swing")))
        kitOnShelf("Funk Room")

        val result = ProjectMigration.adopt(shelf)

        assertEquals(listOf("Funk Room", "Kitchen"), result.created, "shelf order, which is A-Z")
        assertEquals(emptyList(), result.alreadyHeld)

        val kitchenProject = ProjectStore.load(ProjectStore.dir(shelf, "Kitchen"))
        assertEquals(Project("Kitchen", listOf("Kitchen"), "Kitchen", tempoBpm = 94f, key = key), kitchenProject)
        assertEquals(listOf("Captured", "Swing"), ProjectStore.beat(ProjectStore.dir(shelf, "Kitchen")).map { it.name })

        val funk = ProjectStore.load(ProjectStore.dir(shelf, "Funk Room"))
        assertEquals(Project("Funk Room", listOf("Funk Room")), funk)
        assertEquals(emptyList(), ProjectStore.beat(ProjectStore.dir(shelf, "Funk Room")), "no grooves, no beat file")
    }

    @Test
    fun `the kit keeps its own grooves - the beat is copied in this step, not moved`() {
        val kitchen = kitOnShelf("Kitchen")
        GrooveStore.save(kitchen, listOf(clip("Captured")))
        ProjectMigration.adopt(shelf)
        assertEquals(listOf("Captured"), GrooveStore.load(kitchen).map { it.name })
    }

    @Test
    fun `running twice creates nothing the second time`() {
        kitOnShelf("Kitchen")
        kitOnShelf("Funk Room")
        ProjectMigration.adopt(shelf)
        val again = ProjectMigration.adopt(shelf)
        assertEquals(emptyList(), again.created)
        assertEquals(listOf("Funk Room", "Kitchen"), again.alreadyHeld)
        assertEquals(2, ProjectStore.list(shelf).size)
    }

    @Test
    fun `a kit a person already placed in a project is left where they put it`() {
        kitOnShelf("Kitchen")
        kitOnShelf("Funk Room")
        ProjectStore.save(shelf, Project("Late Set", listOf("Kitchen", "Funk Room")))

        val result = ProjectMigration.adopt(shelf)

        assertEquals(emptyList(), result.created, "both kits are held, so no project is manufactured")
        assertEquals(listOf("Funk Room", "Kitchen"), result.alreadyHeld)
        assertEquals(listOf("Late Set"), ProjectStore.list(shelf).map { it.name })
    }

    @Test
    fun `a kit whose name a project already uses for something else is adopted as name 2`() {
        kitOnShelf("Kitchen")
        kitOnShelf("Other")
        ProjectStore.save(shelf, Project("Kitchen", listOf("Other")))

        val result = ProjectMigration.adopt(shelf)

        assertEquals(listOf("Kitchen 2"), result.created)
        assertEquals(listOf("Other"), result.alreadyHeld)
        assertEquals(Project("Kitchen 2", listOf("Kitchen")), ProjectStore.load(ProjectStore.dir(shelf, "Kitchen 2")))
        assertEquals(Project("Kitchen", listOf("Other")), ProjectStore.load(ProjectStore.dir(shelf, "Kitchen")), "untouched")
    }

    @Test
    fun `the shelf's other folders are not kits and get no project`() {
        kitOnShelf("Kitchen")
        File(shelf, "snips").mkdirs()
        File(shelf, "Instruments").mkdirs()
        File(shelf, "Rooms").mkdirs()
        File(shelf, ".bin/Old-123").mkdirs()
        KitStore.save(Kit("Old", emptyList()), File(shelf, ".bin/Old-123"))
        File(shelf, "Projects/Stray").mkdirs()

        val result = ProjectMigration.adopt(shelf)

        assertEquals(listOf("Kitchen"), result.created)
        assertTrue(ProjectStore.list(shelf).map { it.name } == listOf("Kitchen"))
    }

    @Test
    fun `an empty shelf migrates to nothing`() {
        assertEquals(ProjectMigration.Result(emptyList(), emptyList()), ProjectMigration.adopt(shelf))
        assertEquals(emptyList(), ProjectStore.list(shelf))
    }
}
