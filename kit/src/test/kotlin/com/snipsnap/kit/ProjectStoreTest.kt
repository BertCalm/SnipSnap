package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.json.JsonException
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectStoreTest {

    private val shelf: File = java.nio.file.Files.createTempDirectory("projectstore").toFile()

    @AfterTest
    fun cleanUp() {
        shelf.deleteRecursively()
    }

    /** A kit folder on the shelf, the way the app lays one down. */
    private fun kitOnShelf(folder: String, kitName: String = folder): File {
        val dir = File(shelf, folder)
        KitStore.save(
            Kit(kitName, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK))),
            dir,
        )
        return dir
    }

    private fun clip(name: String = "Test Groove") = Mpc3Clip(
        name, 2,
        listOf(Mpc3Note(36, 0, 1f, 240), Mpc3Note(38, 960, 0.6f, 120)),
    )

    private fun sampleProject() = Project(
        name = "Late Set",
        kits = listOf("Kitchen", "Funk Room"),
        activeKit = "Funk Room",
        tempoBpm = 94f,
        key = KeySpec(rootSemitone = 9, scale = Scale.entries.first()),
    )

    @Test
    fun `round-trips with full fidelity`() {
        ProjectStore.save(shelf, sampleProject())
        assertEquals(sampleProject(), ProjectStore.load(ProjectStore.dir(shelf, "Late Set")))
    }

    @Test
    fun `a project is a folder under Projects, named after itself`() {
        val file = ProjectStore.save(shelf, sampleProject())
        assertEquals(File(shelf, "Projects/Late Set/project.json"), file)
        assertTrue(ProjectStore.exists(shelf, "Late Set"))
        assertFalse(ProjectStore.exists(shelf, "Early Set"))
        assertFalse(ProjectStore.exists(shelf, "../Late Set"), "an unsafe name exists nowhere")
    }

    @Test
    fun `list sees every project, in name order, and nothing else`() {
        ProjectStore.save(shelf, Project("zebra"))
        ProjectStore.save(shelf, Project("Alpha"))
        File(ProjectStore.projectsDir(shelf), "not a project").mkdirs()
        File(ProjectStore.projectsDir(shelf), "stray.txt").writeText("x")
        assertEquals(listOf("Alpha", "zebra"), ProjectStore.list(shelf).map { it.name })
    }

    @Test
    fun `an empty shelf lists no projects rather than failing`() {
        assertEquals(emptyList(), ProjectStore.list(shelf))
    }

    /**
     * The one invariant the layout rests on: the shelf's kit scan is a
     * direct-children-with-kit.json scan, and `Projects/` is a direct child
     * with none. If this ever fails, every project appears on the shelf as
     * a broken kit.
     */
    @Test
    fun `KitStore never lists the projects folder or anything in it as a kit`() {
        kitOnShelf("Kitchen")
        ProjectStore.save(shelf, Project("Kitchen", listOf("Kitchen")))
        ProjectStore.save(shelf, Project("Late Set", listOf("Kitchen")))
        assertEquals(listOf("Kitchen"), KitStore.list(shelf).map { it.name })
    }

    @Test
    fun `unknown fields are ignored and an unknown version is refused`() {
        val dir = ProjectStore.dir(shelf, "Future")
        dir.mkdirs()
        File(dir, ProjectStore.FILE_NAME).writeText(
            """{"version":1,"name":"Future","kits":["Kitchen"],"activeKit":"Kitchen","mood":"sunny"}""",
        )
        assertEquals(Project("Future", listOf("Kitchen")), ProjectStore.load(dir))

        File(dir, ProjectStore.FILE_NAME).writeText("""{"version":2,"name":"Future","kits":[]}""")
        assertFailsWith<JsonException> { ProjectStore.load(dir) }
    }

    @Test
    fun `a file that names no active kit opens on the first held one`() {
        val dir = ProjectStore.dir(shelf, "Quiet")
        dir.mkdirs()
        File(dir, ProjectStore.FILE_NAME).writeText("""{"version":1,"name":"Quiet","kits":["B","A"]}""")
        assertEquals("B", ProjectStore.load(dir).activeKit)
    }

    @Test
    fun `a missing project is an IOException, not a crash`() {
        assertFailsWith<IOException> { ProjectStore.load(File(shelf, "Projects/nowhere")) }
    }

    @Test
    fun `the model refuses what a folder cannot hold`() {
        assertFailsWith<IllegalArgumentException> { Project("") }
        assertFailsWith<IllegalArgumentException> { Project("../escape") }
        assertFailsWith<IllegalArgumentException> { Project("a/b") }
        assertFailsWith<IllegalArgumentException> { Project("ok", kits = listOf("Kitchen", "Kitchen")) }
        assertFailsWith<IllegalArgumentException> { Project("ok", kits = listOf("Kitchen"), activeKit = "Other") }
        assertFailsWith<IllegalArgumentException> { Project("ok", kits = listOf("Kitchen"), activeKit = null) }
        assertFailsWith<IllegalArgumentException> { Project("ok", kits = listOf("../Kitchen")) }
        assertFailsWith<IllegalArgumentException> { Project("ok", tempoBpm = 0f) }
        assertFailsWith<IllegalArgumentException> { ProjectStore.dir(shelf, "../escape") }
    }

    @Test
    fun `adding, dropping and activating kits keeps one active kit`() {
        val empty = Project("Late Set")
        assertNull(empty.activeKit)

        val one = empty.withKit("Kitchen")
        assertEquals("Kitchen", one.activeKit, "the first kit added becomes active")
        assertEquals(one, one.withKit("Kitchen"), "holding a kit twice changes nothing")

        val two = one.withKit("Funk Room")
        assertEquals("Kitchen", two.activeKit, "a second kit does not steal the active slot")
        assertEquals("Funk Room", two.activated("Funk Room").activeKit)
        assertFailsWith<IllegalArgumentException> { two.activated("Nowhere") }

        val dropped = two.activated("Funk Room").withoutKit("Funk Room")
        assertEquals(listOf("Kitchen"), dropped.kits)
        assertEquals("Kitchen", dropped.activeKit, "dropping the active kit hands the slot to the next")
        assertEquals(dropped, dropped.withoutKit("Nowhere"))
        assertNull(dropped.withoutKit("Kitchen").activeKit)
    }

    @Test
    fun `held kits resolve to their shelf folders, and the gone ones are named`() {
        kitOnShelf("Kitchen")
        val funk = kitOnShelf("Funk Room")
        val project = Project("Late Set", listOf("Kitchen", "Funk Room"))
        assertEquals(listOf(File(shelf, "Kitchen"), funk), ProjectStore.kitDirs(shelf, project))
        assertEquals(emptyList(), ProjectStore.missingKits(shelf, project))

        funk.deleteRecursively()
        assertEquals(listOf(File(shelf, "Kitchen")), ProjectStore.kitDirs(shelf, project))
        assertEquals(listOf("Funk Room"), ProjectStore.missingKits(shelf, project))
        assertEquals(project, ProjectStore.load(ProjectStore.dir(shelf, "Late Set").also { ProjectStore.save(shelf, project) }),
            "a project still opens with a kit gone")
    }

    @Test
    fun `the beat lives in the project's folder, in the groove store's format, and leaves the kit's own alone`() {
        val kitchen = kitOnShelf("Kitchen")
        GrooveStore.save(kitchen, listOf(clip("Kitchen groove")))
        ProjectStore.save(shelf, Project("Late Set", listOf("Kitchen")))
        val dir = ProjectStore.dir(shelf, "Late Set")

        assertEquals(emptyList(), ProjectStore.beat(dir), "a new project has no beat")
        val file = ProjectStore.saveBeat(dir, listOf(clip("Verse"), clip("Chorus")))
        assertEquals(File(dir, "beat/groove.json"), file)
        assertEquals(listOf("Verse", "Chorus"), ProjectStore.beat(dir).map { it.name })
        assertEquals(listOf("Kitchen groove"), GrooveStore.load(kitchen).map { it.name }, "the kit's grooves are untouched")

        assertTrue(ProjectStore.clearBeat(dir))
        assertEquals(emptyList(), ProjectStore.beat(dir))
    }
}
