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
    fun `a tempo set with withTempo round-trips, and a project with none stays none`() {
        ProjectStore.save(shelf, Project("Tempoed").withTempo(133f))
        ProjectStore.save(shelf, Project("Plain"))
        assertEquals(133f, ProjectStore.load(ProjectStore.dir(shelf, "Tempoed")).tempoBpm)
        assertNull(ProjectStore.load(ProjectStore.dir(shelf, "Plain")).tempoBpm)
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

    // ---- rename, bin, restore ----

    private fun projectWithBeat(name: String, kits: List<String> = emptyList()): File {
        ProjectStore.save(shelf, Project(name, kits, tempoBpm = 100f))
        val dir = ProjectStore.dir(shelf, name)
        ProjectStore.saveBeat(dir, listOf(clip("Beat A")))
        File(dir, "song.json").writeText("{}")
        return dir
    }

    @Test
    fun `rename moves the folder, rewrites the name, and the project still opens with its beat and tempo`() {
        projectWithBeat("Late Set", listOf("Kitchen"))
        val renamed = ProjectStore.rename(shelf, "Late Set", "  Early Set ")
        assertEquals("Early Set", renamed.name)
        assertFalse(File(ProjectStore.projectsDir(shelf), "Late Set").exists())
        val dir = ProjectStore.dir(shelf, "Early Set")
        val loaded = ProjectStore.load(dir)
        assertEquals("Early Set", loaded.name)
        assertEquals(listOf("Kitchen"), loaded.kits)
        assertEquals(100f, loaded.tempoBpm)
        assertEquals(listOf("Beat A"), ProjectStore.beat(dir).map { it.name })
        assertTrue(File(dir, "song.json").isFile, "what lives in the folder rides along")
        assertEquals(listOf("Early Set"), ProjectStore.list(shelf).map { it.name })
    }

    @Test
    fun `rename to a name another project holds is refused, ignoring case, and changes nothing`() {
        projectWithBeat("Alpha")
        projectWithBeat("Beta")
        assertFailsWith<ProjectStore.NameTakenException> { ProjectStore.rename(shelf, "Alpha", "Beta") }
        assertFailsWith<ProjectStore.NameTakenException> { ProjectStore.rename(shelf, "Alpha", "beta") }
        assertEquals(listOf("Alpha", "Beta"), ProjectStore.list(shelf).map { it.name })
        assertEquals("Alpha", ProjectStore.load(ProjectStore.dir(shelf, "Alpha")).name)
    }

    @Test
    fun `rename to its own name is a no-op and a case-only change is allowed`() {
        projectWithBeat("Alpha")
        assertEquals("Alpha", ProjectStore.rename(shelf, "Alpha", "Alpha").name)
        assertEquals("ALPHA", ProjectStore.rename(shelf, "Alpha", "ALPHA").name)
        assertEquals("ALPHA", ProjectStore.load(ProjectStore.dir(shelf, "ALPHA")).name)
        assertEquals(1, ProjectStore.list(shelf).size)
    }

    @Test
    fun `rename refuses a name that cannot be a folder, and a project that is not there`() {
        projectWithBeat("Alpha")
        for (bad in listOf("", "   ", "a/b", "..", "a:b", "ends.", ".hidden")) {
            assertFailsWith<IllegalArgumentException>(bad) { ProjectStore.rename(shelf, "Alpha", bad) }
        }
        assertEquals("Alpha", ProjectStore.load(ProjectStore.dir(shelf, "Alpha")).name)
        assertFailsWith<IOException> { ProjectStore.rename(shelf, "Nothing", "Something") }
    }

    @Test
    fun `trash moves the whole project into the bin and leaves its kits where they are`() {
        val kit = kitOnShelf("Kitchen")
        val dir = projectWithBeat("Late Set", listOf("Kitchen"))
        val binned = ProjectStore.trash(shelf, "Late Set", nowMillis = 1_000L)
        assertEquals(File(File(shelf, ProjectStore.BIN_DIR), "Late Set-1000"), binned)
        assertFalse(dir.exists())
        assertTrue(File(binned, "song.json").isFile)
        assertTrue(File(kit, KitStore.FILE_NAME).isFile, "the kit is untouched")
        assertEquals(emptyList(), ProjectStore.list(shelf))
        val listed = ProjectStore.binned(shelf)
        assertEquals(listOf("Late Set"), listed.map { it.project.name })
        assertEquals(1_000L, listed.single().binnedAtMillis)
        assertEquals(emptyList(), KitStore.list(shelf).filter { it.name == ".bin" }, "a binned project is not a kit")
    }

    @Test
    fun `two projects binned in the same millisecond do not share a folder`() {
        projectWithBeat("A")
        val first = ProjectStore.trash(shelf, "A", nowMillis = 5L)
        projectWithBeat("A")
        val second = ProjectStore.trash(shelf, "A", nowMillis = 5L)
        assertTrue(first != second)
        assertEquals(2, ProjectStore.binned(shelf).size)
    }

    @Test
    fun `restore puts the project back under its own name with its beat`() {
        projectWithBeat("Late Set", listOf("Kitchen"))
        val binned = ProjectStore.trash(shelf, "Late Set")
        val restored = ProjectStore.restore(shelf, binned)
        assertEquals("Late Set", restored.name)
        assertEquals(listOf("Late Set"), ProjectStore.list(shelf).map { it.name })
        assertEquals(listOf("Beat A"), ProjectStore.beat(ProjectStore.dir(shelf, "Late Set")).map { it.name })
        assertEquals(emptyList(), ProjectStore.binned(shelf))
    }

    @Test
    fun `restore onto a name now in use lands as NAME 2, never over the project there, and the name is rewritten`() {
        projectWithBeat("Late Set")
        val binned = ProjectStore.trash(shelf, "Late Set")
        ProjectStore.save(shelf, Project("Late Set", tempoBpm = 70f)) // a newer project took the name
        val restored = ProjectStore.restore(shelf, binned)
        assertEquals("Late Set 2", restored.name)
        assertEquals("Late Set 2", ProjectStore.load(ProjectStore.dir(shelf, "Late Set 2")).name)
        assertEquals(70f, ProjectStore.load(ProjectStore.dir(shelf, "Late Set")).tempoBpm, "the project already there is untouched")
        assertEquals(listOf("Beat A"), ProjectStore.beat(ProjectStore.dir(shelf, "Late Set 2")).map { it.name })

        // And a case-only clash counts, as it does on the card.
        ProjectStore.save(shelf, Project("Mix"))
        val b2 = ProjectStore.trash(shelf, "Mix")
        ProjectStore.save(shelf, Project("MIX"))
        assertEquals("Mix 2", ProjectStore.restore(shelf, b2).name)
    }

    @Test
    fun `restore of a folder that is gone says so`() {
        assertFailsWith<IOException> { ProjectStore.restore(shelf, File(shelf, ".bin/nothing-1")) }
    }

    @Test
    fun `a kit only the deleted project held is not re-adopted into a project of its own, until it is opened again`() {
        kitOnShelf("Kitchen")
        kitOnShelf("Funk Room")
        ProjectStore.save(shelf, Project("Late Set", listOf("Kitchen")))
        ProjectStore.save(shelf, Project("Other", listOf("Funk Room")))
        ProjectStore.trash(shelf, "Late Set")
        assertEquals(setOf("Kitchen"), ProjectStore.released(shelf))
        assertEquals(emptyList(), ProjectMigration.adopt(shelf).created)
        assertEquals(listOf("Other"), ProjectStore.list(shelf).map { it.name })

        // Restoring holds it again, which takes it off the list.
        val restored = ProjectStore.restore(shelf, ProjectStore.binned(shelf).single().dir)
        assertEquals("Late Set", restored.name)
        assertEquals(emptySet(), ProjectStore.released(shelf))
    }

    @Test
    fun `a kit another project still holds is not released when one project goes`() {
        kitOnShelf("Kitchen")
        ProjectStore.save(shelf, Project("A", listOf("Kitchen")))
        ProjectStore.save(shelf, Project("B", listOf("Kitchen")))
        ProjectStore.trash(shelf, "A")
        assertEquals(emptySet(), ProjectStore.released(shelf))
    }
    @Test
    fun `a kit renamed on the shelf is held under its new name, in place and still active`() {
        val renamed = sampleProject().withKitRenamed("Funk Room", "Funk Den")
        assertEquals(listOf("Kitchen", "Funk Den"), renamed.kits)
        assertEquals("Funk Den", renamed.activeKit)
        assertEquals(sampleProject(), sampleProject().withKitRenamed("Elsewhere", "Gone"), "a project not holding it is unchanged")
        val both = Project("Both", listOf("A", "B"), activeKit = "A").withKitRenamed("A", "B")
        assertEquals(listOf("B"), both.kits, "never held twice")
        assertEquals("B", both.activeKit)
    }

    @Test
    fun `following a kit rename rewrites every project that held it, its song and the bin's, and leaves the rest`() {
        kitOnShelf("Kitchen")
        kitOnShelf("Funk Den")
        ProjectStore.save(shelf, sampleProject())
        ProjectStore.save(shelf, Project("Other", listOf("Kitchen")))
        val binned = Project("Asleep", listOf("Funk Room"))
        ProjectStore.save(shelf, binned)
        ProjectStore.trash(shelf, "Asleep")
        val lateSet = ProjectStore.dir(shelf, "Late Set")
        val song = SongPlan(0, listOf(PlanSection("theme", ClipRef.CAPTURED)))
        SongPlanStore.save(lateSet, "Funk Room", song)
        assertEquals(listOf("Funk Room"), ProjectStore.missingKits(shelf, sampleProject()), "the folder is already gone: this is the bug")

        val followed = ProjectStore.followKitRename(shelf, "Funk Room", "Funk Den")

        assertEquals(listOf("Late Set"), followed.updated.map { it.name }, "only live projects that held it")
        assertTrue(followed.failed.isEmpty())
        val late = ProjectStore.load(lateSet)
        assertEquals(listOf("Kitchen", "Funk Den"), late.kits)
        assertEquals("Funk Den", late.activeKit)
        assertTrue(ProjectStore.missingKits(shelf, late).isEmpty(), "the kit is no longer gone")
        assertEquals(song, SongPlanStore.load(lateSet, "Funk Den"))
        assertNull(SongPlanStore.load(lateSet, "Funk Room"))
        assertEquals(listOf("Kitchen"), ProjectStore.load(ProjectStore.dir(shelf, "Other")).kits)
        val asleep = ProjectStore.binned(shelf).single()
        assertEquals(listOf("Funk Den"), asleep.project.kits, "a restore finds the kit")
    }

    @Test
    fun `a released kit stays released under its new name, and one bad project does not stop the rest`() {
        ProjectStore.release(shelf, listOf("Loose"))
        ProjectStore.save(shelf, Project("Holds", listOf("Loose 2")))
        val broken = File(ProjectStore.projectsDir(shelf), "Broken").apply { mkdirs() }
        File(broken, ProjectStore.FILE_NAME).writeText("{ not json")
        ProjectStore.save(shelf, Project("Fine", listOf("Loose 2")))

        val followed = ProjectStore.followKitRename(shelf, "Loose", "Free")
        assertEquals(setOf("Free"), ProjectStore.released(shelf))
        assertEquals(listOf(broken.name), followed.failed.map { it.name })

        val again = ProjectStore.followKitRename(shelf, "Loose 2", "Bound")
        assertEquals(setOf("Fine", "Holds"), again.updated.map { it.name }.toSet(), "the broken one did not stop the others")
    }
}
