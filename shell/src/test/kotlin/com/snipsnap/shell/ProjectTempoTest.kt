package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Project
import com.snipsnap.kit.ProjectStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProjectTempoTest {

    private val shelf: File = java.nio.file.Files.createTempDirectory("projecttempo").toFile()

    @AfterTest
    fun cleanUp() {
        shelf.deleteRecursively()
    }

    private fun kit(tempo: Float?) =
        Kit("K", listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)), tempoBpm = tempo)

    @Test
    fun `the project wins, then the kit, then the default`() {
        assertEquals(120f, ProjectTempo.of(Project("P", tempoBpm = 120f), kit(96f)))
        assertEquals(96f, ProjectTempo.of(Project("P"), kit(96f)))
        assertEquals(96f, ProjectTempo.of(null, kit(96f)))
        assertEquals(KitPreview.DEFAULT_BPM, ProjectTempo.of(Project("P"), kit(null)))
        assertEquals(92f, ProjectTempo.of(null, null))
    }

    @Test
    fun `clamped holds the tempo to what ORBIT and LOOP require`() {
        assertEquals(40f, ProjectTempo.clamped(Project("P", tempoBpm = 30f), kit(null)))
        assertEquals(220f, ProjectTempo.clamped(Project("P"), kit(300f)))
        assertEquals(96f, ProjectTempo.clamped(Project("P", tempoBpm = 96f), kit(null)))
        assertEquals(30f, ProjectTempo.of(Project("P", tempoBpm = 30f), kit(null)), "of() itself does not clamp")
    }

    @Test
    fun `withTempo clamps to 40 to 220 and refuses what is not a number`() {
        assertEquals(40f, Project("P").withTempo(10f).tempoBpm)
        assertEquals(220f, Project("P").withTempo(999f).tempoBpm)
        assertEquals(133.5f, Project("P").withTempo(133.5f).tempoBpm)
        kotlin.test.assertFailsWith<IllegalArgumentException> { Project("P").withTempo(Float.NaN) }
    }

    @Test
    fun `the overlay carries the project tempo and leaves a kit it need not change alone`() {
        val k = kit(92f)
        assertEquals(120f, ProjectTempo.effective(k, Project("P", tempoBpm = 120f)).tempoBpm)
        assertSame(k, ProjectTempo.effective(k, Project("P")), "no project tempo, no copy")
        assertSame(k, ProjectTempo.effective(k, null))
        assertSame(k, ProjectTempo.effective(k, Project("P", tempoBpm = 92f)), "the same number is no change")
        assertNull(ProjectTempo.effective(kit(null), Project("P")).tempoBpm, "a kit with no tempo stays so")
    }

    @Test
    fun `stored puts the file's own tempo back`() {
        val onDisk = kit(92f)
        val effective = ProjectTempo.effective(onDisk, Project("P", tempoBpm = 120f))
        assertEquals(92f, ProjectTempo.stored(effective, onDisk).tempoBpm)
        assertNull(ProjectTempo.stored(effective, kit(null)).tempoBpm)
    }

    @Test
    fun `setTempo is saved in project json and survives a reload`() {
        val model = ShelfModel(shelf)
        File(shelf, "K").let { KitStore.save(kit(92f), it) }
        model.newProject("P")
        model.addKit("P", "K")
        val saved = model.setTempo("P", 400f)
        assertEquals(220f, saved.tempoBpm)
        assertEquals(220f, ProjectStore.load(ProjectStore.dir(shelf, "P")).tempoBpm)
        assertEquals(listOf("K"), ProjectStore.load(ProjectStore.dir(shelf, "P")).kits, "the rest of the project is as it was")
        assertEquals(133f, model.setTempo("P", 133f).tempoBpm)
        assertEquals(133f, model.openProject("P").tempoBpm)
    }

    @Test
    fun `the overlay never reaches kit json through the save a screen makes`() {
        val dir = File(shelf, "K")
        KitStore.save(kit(92f), dir)
        val project = Project("P", kits = listOf("K"), tempoBpm = 150f)
        val effective = ProjectTempo.effective(KitStore.load(dir), project)
        assertEquals(150f, effective.tempoBpm, "the screens see the project tempo")

        // Every kit write goes through a model loaded from the folder.
        val model = KitBuilderModel.open(dir)
        model.setKey(com.snipsnap.audio.KeySpec(0, com.snipsnap.audio.Scale.entries.first()))
        model.save()
        assertEquals(92f, KitStore.load(dir).tempoBpm, "kit.json still says what the file said")

        // And a caller that did hold the effective kit has the way back.
        KitStore.save(ProjectTempo.stored(effective, KitStore.load(dir)), dir)
        assertEquals(92f, KitStore.load(dir).tempoBpm)
    }
}
