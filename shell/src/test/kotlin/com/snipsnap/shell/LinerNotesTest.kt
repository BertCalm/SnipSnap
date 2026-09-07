package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.WearLedger
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinerNotesTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("liner").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `the notes tell the kit's actual story, deterministically`() {
        val m = KitBuilderModel.create("Night Drive", File(temp, "Night Drive"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.update(1) { it.copy(source = mapOf("song" to "Track 07.wav", "at" to "1:32")) }
        m.update(2) {
            it.copy(
                source = mapOf("song" to "Track 07.wav", "at" to "1:32"),
                decay = 0.3f,
                humanize = 0.4f,
            )
        }
        m.eraPad(1, "sp1200")
        m.enableWear()
        m.recordPlays(143)
        m.setKey(com.snipsnap.audio.KeySpec.parse("Am"))
        m.save(accrueWear = false)
        GrooveStore.save(m.kitDir, listOf(Mpc3Clip("Night Groove", 1, listOf(Mpc3Note(36, 0, 0.9f)))))

        val notes = LinerNotes.render(m.kit, m.kitDir)
        assertTrue("NIGHT DRIVE" in notes, notes)
        assertTrue("A minor" in notes, "the key is named")
        assertTrue("Dug from \"Track 07.wav\" at 1:32." in notes, "provenance as prose")
        assertTrue("143 miles on the tape" in notes, "the wear ledger reads out")
        assertTrue("Plays: Night Groove." in notes)
        assertTrue("through the sp1200" in notes, "the era treatment is named")
        assertTrue("shaped" in notes && "humanized" in notes, "the shape shows")
        assertTrue("kick" in notes && "snare" in notes)
        assertTrue("Made with SnipSnap." in notes)

        assertEquals(notes, LinerNotes.render(m.kit, m.kitDir), "same kit, same words")
    }

    @Test
    fun `a plain kit gets plain honest notes`() {
        val m = KitBuilderModel.create("Plain", File(temp, "Plain"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val notes = LinerNotes.render(KitStore.load(m.kitDir), m.kitDir)
        assertTrue("Built by hand, pad by pad." in notes, notes)
        assertTrue("1 pad" in notes)
        assertTrue("mile" !in notes, "no ledger, no mileage line")

        // Resample lineage reads as a generation line.
        val gen = m.kit.copy(
            pads = m.kit.pads.map {
                it.copy(source = mapOf("resampledFrom" to "Origin", "generation" to "3"))
            },
        )
        assertTrue(
            "Generation 3 - bounced from \"Origin\" and chopped again." in LinerNotes.render(gen, m.kitDir),
        )
    }
}
