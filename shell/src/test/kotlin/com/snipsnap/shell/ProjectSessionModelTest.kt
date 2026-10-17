package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Project
import com.snipsnap.kit.ProjectStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectSessionModelTest {

    private val shelf: File = java.nio.file.Files.createTempDirectory("projectsession-shell").toFile()
    private val out: File = java.nio.file.Files.createTempDirectory("projectsession-shell-out").toFile()

    @AfterTest
    fun cleanUp() {
        shelf.deleteRecursively()
        out.deleteRecursively()
    }

    private fun kitOnShelf(folder: String, seed: Int, groove: String? = null, withWav: Boolean = true): File {
        val dir = File(shelf, folder)
        dir.mkdirs()
        if (withWav) {
            WavWriter.write(
                File(dir, "A01_Kick_01.wav"),
                Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / (10.0 + seed))).toFloat() }, 1, 44_100)),
            )
        }
        KitStore.save(Kit(folder, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK))), dir)
        groove?.let { GrooveStore.save(dir, listOf(Mpc3Clip(it, 1, listOf(Mpc3Note(36, 0, 0.9f, 240))))) }
        return dir
    }

    private fun clip(name: String) = Mpc3Clip(name, 2, listOf(Mpc3Note(36, 0, 0.9f, 240)))

    @Test
    fun `a project writes as one session, with its beat on the active kit, and the stages move`() {
        kitOnShelf("Kitchen", 1, groove = "Kitchen Groove")
        kitOnShelf("Funk Room", 5, groove = "Funk Groove")
        val project = Project("Late Set", listOf("Kitchen", "Funk Room"), activeKit = "Funk Room", tempoBpm = 111f)
        ProjectStore.save(shelf, project)
        ProjectStore.saveBeat(ProjectStore.dir(shelf, "Late Set"), listOf(clip("Late Verse")))

        val model = ProjectSessionModel(project, shelf)
        assertEquals("2 KITS · BEAT ON FUNK ROOM · 111 BPM", model.subtitle)
        assertEquals(ProjectSessionModel.Stage.READY, model.stage)
        assertEquals(listOf("Kitchen", "Funk Room"), model.runPreflight().map { it.first })
        assertFalse(model.blocked)

        val result = model.write(out, withMixdown = true)
        val done = assertIs<ProjectSessionModel.WriteResult.Done>(result)
        assertEquals(ProjectSessionModel.Stage.COMPLETE, model.stage)
        assertEquals(File(out, "Late Set.xpj"), done.result.xpj)
        assertEquals(File(out, "Late Set.wav"), done.mixdown)
        assertTrue(done.mixdown!!.isFile)
        val text = Acvs.read(done.result.xpj).payloadText
        assertTrue("Late Verse" in text && "Kitchen Groove" in text && "Funk Groove" !in text)

        model.eject()
        assertEquals(ProjectSessionModel.Stage.READY, model.stage)
        val again = model.write(out, overwrite = false)
        assertIs<ProjectSessionModel.WriteResult.WouldOverwrite>(again)
        assertEquals(ProjectSessionModel.Stage.READY, model.stage, "nothing written; the wizard waits for the answer")
    }

    @Test
    fun `a held kit that is gone blocks in words before anything is written`() {
        kitOnShelf("Kitchen", 1)
        val project = Project("Late Set", listOf("Kitchen", "Funk Room"))
        ProjectStore.save(shelf, project)
        val model = ProjectSessionModel(project, shelf)
        assertEquals(listOf("Funk Room"), model.missingKits)
        assertTrue(model.blocked)
        val result = model.write(out)
        val blocked = assertIs<ProjectSessionModel.WriteResult.Blocked>(result)
        assertEquals(listOf("Funk Room"), blocked.missingKits)
        assertEquals(ProjectSessionModel.Stage.READY, model.stage)
        assertFalse(File(out, "Late Set.xpj").exists())
    }

    @Test
    fun `a kit that fails preflight blocks the session the way it blocks the kit wizard`() {
        kitOnShelf("Kitchen", 1)
        kitOnShelf("Broken", 2, withWav = false)
        val project = Project("Late Set", listOf("Kitchen", "Broken"))
        ProjectStore.save(shelf, project)
        val model = ProjectSessionModel(project, shelf)
        val result = model.write(out)
        val blocked = assertIs<ProjectSessionModel.WriteResult.Blocked>(result)
        assertEquals(emptyList(), blocked.missingKits)
        assertTrue(blocked.findings.first { it.first == "Broken" }.second.isNotEmpty(), "the broken kit's checklist names it")
        assertFalse(File(out, "Late Set.xpj").exists())
    }

    @Test
    fun `an empty project is blocked, and its subtitle says so`() {
        val project = Project("Empty")
        ProjectStore.save(shelf, project)
        val model = ProjectSessionModel(project, shelf)
        assertEquals("0 KITS", model.subtitle)
        assertTrue(model.blocked)
        assertIs<ProjectSessionModel.WriteResult.Blocked>(model.write(out))
    }
}
