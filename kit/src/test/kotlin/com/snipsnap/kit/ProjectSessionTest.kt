package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The exporters taking a [Project]: `docs/DIRECTION_2026_10.md` §2 and §6, step 2. */
class ProjectSessionTest {

    private val shelf: File = java.nio.file.Files.createTempDirectory("projectsession").toFile()
    private val out: File = java.nio.file.Files.createTempDirectory("projectsession-out").toFile()

    @AfterTest
    fun cleanUp() {
        shelf.deleteRecursively()
        out.deleteRecursively()
    }

    private fun kitOnShelf(folder: String, seed: Int, tempo: Float? = null, groove: String? = null): File {
        val dir = File(shelf, folder)
        dir.mkdirs()
        WavWriter.write(
            File(dir, "A01_Kick_01.wav"),
            Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / (10.0 + seed))).toFloat() }, 1, 44_100)),
        )
        KitStore.save(
            Kit(folder, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)), tempoBpm = tempo),
            dir,
        )
        groove?.let { GrooveStore.save(dir, listOf(clip(it, bars = 1))) }
        return dir
    }

    private fun clip(name: String, bars: Int = 2) = Mpc3Clip(name, bars, listOf(Mpc3Note(36, 0, 0.9f, 240)))

    private fun payload(result: SessionBuilder.Result): String = Acvs.read(result.xpj).payloadText

    @Test
    fun `a freshly migrated project exports what the kit session did`() {
        kitOnShelf("Kitchen", 1, tempo = 96.5f, groove = "Kitchen Groove")
        ProjectMigration.adopt(shelf)
        val project = ProjectStore.load(ProjectStore.dir(shelf, "Kitchen"))

        val fromKits = SessionBuilder.build("Kitchen", listOf(File(shelf, "Kitchen")), File(out, "kits"))
        val fromProject = SessionBuilder.build(project, shelf, File(out, "project"))

        assertEquals(fromKits.kitTracks, fromProject.kitTracks)
        assertEquals(fromKits.tempoBpm, fromProject.tempoBpm)
        assertEquals(File(out, "project/Kitchen.xpj"), fromProject.xpj, "named after the project")
        val a = payload(fromKits)
        val b = payload(fromProject)
        assertTrue("Kitchen Groove" in a && "Kitchen Groove" in b)
        assertTrue("\"masterTempo\": 96.5" in b)
    }

    @Test
    fun `the project's beat rides the active kit's track and the other kits keep their own`() {
        kitOnShelf("Kitchen", 1, groove = "Kitchen Groove")
        kitOnShelf("Funk Room", 5, groove = "Funk Groove")
        val project = Project("Late Set", listOf("Kitchen", "Funk Room"), activeKit = "Funk Room", tempoBpm = 120f)
        ProjectStore.save(shelf, project)
        ProjectStore.saveBeat(ProjectStore.dir(shelf, "Late Set"), listOf(clip("Late Verse"), clip("Late Chorus")))

        val result = SessionBuilder.build(project, shelf, File(out, "late"))

        assertEquals(listOf("Kitchen", "Funk Room"), result.kitTracks, "the project's order")
        assertEquals(120f, result.tempoBpm, "the project's tempo wins over the kits'")
        val text = payload(result)
        assertTrue("Late Verse" in text && "Late Chorus" in text, "the beat is in the session")
        assertFalse("Funk Groove" in text, "the active kit's own grooves are replaced by the beat")
        assertTrue("Kitchen Groove" in text, "the other kit still plays its own")
        assertTrue("\"masterTempo\": 120" in text)
        val read = Mpc3Project.read(result.xpj)
        assertTrue(read.isProject)
        assertTrue(read.trackNames.containsAll(listOf("Kitchen", "Funk Room")), "tracks: ${read.trackNames}")
    }

    @Test
    fun `a project with no beat yet lets every kit play its own grooves`() {
        kitOnShelf("Kitchen", 1, groove = "Kitchen Groove")
        kitOnShelf("Funk Room", 5, groove = "Funk Groove")
        val project = Project("Late Set", listOf("Kitchen", "Funk Room"))
        ProjectStore.save(shelf, project)

        val text = payload(SessionBuilder.build(project, shelf, File(out, "nobeat")))
        assertTrue("Kitchen Groove" in text && "Funk Groove" in text)
    }

    @Test
    fun `a held kit that is gone refuses by name before anything is written`() {
        kitOnShelf("Kitchen", 1)
        kitOnShelf("Funk Room", 5)
        val project = Project("Late Set", listOf("Kitchen", "Funk Room"))
        ProjectStore.save(shelf, project)
        File(shelf, "Funk Room").deleteRecursively()

        val e = assertFailsWith<ProjectKitsMissingException> { SessionBuilder.build(project, shelf, File(out, "gone")) }
        assertEquals(listOf("Funk Room"), e.missing)
        assertEquals("Late Set", e.project)
        assertFalse(File(out, "gone").exists(), "nothing written")
    }

    @Test
    fun `a project holding no kits is refused in words`() {
        val project = Project("Empty")
        ProjectStore.save(shelf, project)
        val e = assertFailsWith<IllegalArgumentException> { SessionBuilder.build(project, shelf, File(out, "empty")) }
        assertTrue("Empty" in (e.message ?: ""))
    }

    @Test
    fun `a project name a card cannot hold is sanitized into the session's name`() {
        kitOnShelf("Kitchen", 1)
        // A folder may hold '?' and '*'; a card may not.
        val project = Project("Late Set?", listOf("Kitchen"))
        ProjectStore.save(shelf, project)
        val result = SessionBuilder.build(project, shelf, File(out, "odd"))
        assertEquals(Names.sanitizeStem("Late Set?"), result.xpj.nameWithoutExtension)
        assertTrue(Names.isMpcSafe(result.xpj.nameWithoutExtension))
    }

    @Test
    fun `staged kit folders are accepted in the project's order, and a wrong count is not`() {
        kitOnShelf("Kitchen", 1, groove = "Kitchen Groove")
        val project = Project("Late Set", listOf("Kitchen"))
        ProjectStore.save(shelf, project)
        ProjectStore.saveBeat(ProjectStore.dir(shelf, "Late Set"), listOf(clip("Late Verse")))

        val stage = File(out, "stage/Kitchen")
        File(shelf, "Kitchen").copyRecursively(stage)
        val text = payload(SessionBuilder.build(project, shelf, File(out, "staged"), kitDirs = listOf(stage)))
        assertTrue("Late Verse" in text, "the beat still finds the active track by index")

        assertFailsWith<IllegalArgumentException> {
            SessionBuilder.build(project, shelf, File(out, "wrong"), kitDirs = listOf(stage, stage))
        }
    }

    @Test
    fun `the mixdown plays the beat on the active kit at the project's tempo`() {
        kitOnShelf("Kitchen", 1, groove = "Kitchen Groove")
        val project = Project("Late Set", listOf("Kitchen"), tempoBpm = 100f)
        ProjectStore.save(shelf, project)

        val own = SessionMixdown.render(project, shelf)
        // A two-bar beat plays longer than the kit's one-bar groove at the same tempo.
        ProjectStore.saveBeat(ProjectStore.dir(shelf, "Late Set"), listOf(clip("Late Verse", bars = 2)))
        val withBeat = SessionMixdown.render(project, shelf)
        assertTrue(withBeat.frameCount > own.frameCount, "beat ${withBeat.frameCount} vs own ${own.frameCount}")

        File(shelf, "Kitchen").deleteRecursively()
        assertFailsWith<ProjectKitsMissingException> { SessionMixdown.render(project, shelf) }
    }
}
