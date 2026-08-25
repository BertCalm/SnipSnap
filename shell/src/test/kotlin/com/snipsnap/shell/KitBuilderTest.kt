package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitBuilderTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("kitbuilder").toFile()

    @Test
    fun `create, assign, save, reopen - the folder is the kit`() {
        val dir = File(temp, "Fresh")
        val m = KitBuilderModel.create("Fresh", dir)
        assertEquals(Copy.EMPTY_KIT, m.emptyStateLine)

        val kick = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        assertNull(m.emptyStateLine)
        assertTrue(m.dirty)

        assertEquals("A01_Kick_01.wav", kick.sampleFile)
        assertTrue(File(dir, kick.sampleFile).isFile, "a pad you can hear is a file that exists")
        assertEquals("#e8542e", kick.colorHex)
        assertEquals(1, m.pad(3)?.muteGroup, "hats bring their choke group")

        m.save()
        assertFalse(m.dirty)
        val reopened = KitBuilderModel.open(dir)
        assertEquals(2, reopened.kit.pads.size)
        assertEquals(DrumClass.KICK, reopened.pad(1)?.drumClass)
    }

    @Test
    fun `reassigning a pad removes the orphaned file`() {
        val dir = File(temp, "Reassign")
        val m = KitBuilderModel.create("Reassign", dir)
        val first = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        val second = m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        assertFalse(File(dir, first.sampleFile).exists(), "orphaned WAV should be deleted")
        assertTrue(File(dir, second.sampleFile).isFile)
        assertEquals(DrumClass.SNARE, m.pad(1)?.drumClass)
    }

    @Test
    fun `clear empties the pad and deletes its file`() {
        val dir = File(temp, "Clear")
        val m = KitBuilderModel.create("Clear", dir)
        val pad = m.assign(5, DrumSynth.tom(), DrumClass.TOM)
        m.clear(5)
        assertNull(m.pad(5))
        assertFalse(File(dir, pad.sampleFile).exists())
        m.clear(5) // clearing empty is a no-op
    }

    @Test
    fun `move swaps when the target is occupied`() {
        val dir = File(temp, "Move")
        val m = KitBuilderModel.create("Move", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)

        m.move(1, 2)
        assertEquals(DrumClass.KICK, m.pad(2)?.drumClass)
        assertEquals(DrumClass.SNARE, m.pad(1)?.drumClass)

        m.move(2, 9) // to an empty slot
        assertEquals(DrumClass.KICK, m.pad(9)?.drumClass)
        assertNull(m.pad(2))
        m.move(9, 9) // no-op
        assertEquals(DrumClass.KICK, m.pad(9)?.drumClass)
    }

    @Test
    fun `update edits parameters but never slot or sample`() {
        val dir = File(temp, "Update")
        val m = KitBuilderModel.create("Update", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)

        val edited = m.update(1) { it.copy(level = 0.5f, pan = 0.25f, oneShot = false, displayName = "Thump") }
        assertEquals(0.5f, edited.level)
        assertEquals("Thump", m.pad(1)?.displayName)

        assertFailsWith<IllegalArgumentException> { m.update(1) { it.copy(slot = 2) } }
        assertFailsWith<IllegalArgumentException> { m.update(1) { it.copy(sampleFile = "x.wav") } }
        assertFailsWith<IllegalArgumentException> { m.update(1) { it.copy(level = 3f) } }
        assertFailsWith<IllegalArgumentException> { m.update(4) { it } }
    }

    @Test
    fun `fromChop lands a whole arrangement as a kit folder`() {
        val dir = File(temp, "FromChop")
        val arranged = listOf<ArrangedPad?>(
            ArrangedPad(DrumSynth.kick(), DrumClass.KICK),
            ArrangedPad(DrumSynth.snare(), DrumClass.SNARE),
            ArrangedPad(DrumSynth.closedHat(), DrumClass.HAT_CLOSED),
            null,
        )
        val m = KitBuilderModel.fromChop("FromChop", arranged, dir)
        assertEquals(3, m.kit.pads.size)
        assertFalse(m.dirty, "fromChop saves through KitAssembler")
        assertEquals(m.kit, KitStore.load(dir))
    }

    @Test
    fun `the kit key persists and IN KEY retunes only pitched tonal pads`() {
        val dir = File(temp, "Keyed")
        val m = KitBuilderModel.create("Keyed", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        // The default tonal() sits on 110 Hz - an A, out of C minor.
        m.assign(13, DrumSynth.tonal(), DrumClass.TONAL)

        assertEquals(emptyList(), m.retuneTonalPads(), "no key set, nothing happens")

        m.setKey(com.snipsnap.audio.KeySpec.parse("Cm"))
        m.save()
        val reopened = KitBuilderModel.open(dir)
        assertEquals(com.snipsnap.audio.KeySpec.parse("C minor"), reopened.kit.key)

        val moved = reopened.retuneTonalPads()
        assertEquals(listOf(13), moved)
        val tonal = reopened.pad(13)!!
        assertTrue(tonal.tuneCoarse != 0 || tonal.tuneFine != 0, "A must move into C minor")
        assertEquals(0, reopened.pad(1)!!.tuneCoarse, "the kick is never 'corrected'")
        assertEquals(emptyList(), reopened.retuneTonalPads(), "second pass is a no-op")

        reopened.setKey(null)
        reopened.save()
        assertEquals(null, KitBuilderModel.open(dir).kit.key)
    }

    @Test
    fun `evil twins - bank B remixes bank A, keeps colour and choke, rerolls`() {
        val dir = File(temp, "Twins")
        val m = KitBuilderModel.create("Twins", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.assign(4, DrumSynth.openHat(), DrumClass.HAT_OPEN)

        val slots = m.remixBankB(seed = 5)
        assertEquals(listOf(17, 19, 20), slots)
        val twin = m.pad(19)!!
        assertEquals(m.pad(3)!!.colorHex, twin.colorHex, "twins keep the class colour")
        assertEquals(1, twin.muteGroup, "the hats still choke in bank B")
        assertTrue(twin.displayName.endsWith(" B"))
        assertTrue(File(dir, twin.sampleFile).isFile)
        assertTrue(twin.recipe != null, "twins carry fx-only recipes")

        // Reroll replaces the bank; a different seed treats differently.
        val firstBytes = File(dir, m.pad(17)!!.sampleFile).readBytes()
        m.remixBankB(seed = 6)
        assertEquals(3, m.kit.pads.count { it.slot > 16 })
        val secondBytes = File(dir, m.pad(17)!!.sampleFile).readBytes()
        assertTrue(
            !firstBytes.contentEquals(secondBytes) ||
                m.pad(17)!!.recipe.toString() != twin.recipe.toString(),
            "a new seed should treat differently",
        )
        m.save()
        assertEquals(m.kit, KitStore.load(dir))

        assertFailsWith<IllegalArgumentException> {
            KitBuilderModel.create("Empty", File(temp, "Empty")).remixBankB(1)
        }
    }

    @Test
    fun `ghost layers - darker soft zones, and reversible`() {
        val dir = File(temp, "Ghosts")
        val m = KitBuilderModel.create("Ghosts", dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)

        val layered = m.addGhostLayers(1)
        assertEquals(2, layered.velocityLayers.size)
        assertEquals(1, layered.velocityLayers[0].velStart, "soft zone starts at 1, not 0")
        assertEquals(127, layered.velocityLayers[1].velEnd)
        assertEquals(layered.sampleFile, layered.velocityLayers.last().sampleFile)

        val softFile = File(dir, layered.velocityLayers[0].sampleFile)
        assertTrue(softFile.isFile)
        // The soft render is darker, not just quieter: lower centroid.
        val softCentroid = com.snipsnap.audio.Classifier
            .classify(com.snipsnap.audio.WavReader.read(softFile)).features.centroidHz
        val mainCentroid = com.snipsnap.audio.Classifier
            .classify(com.snipsnap.audio.WavReader.read(File(dir, layered.sampleFile))).features.centroidHz
        assertTrue(softCentroid < mainCentroid, "soft $softCentroid vs main $mainCentroid")

        assertFailsWith<IllegalArgumentException> { m.addGhostLayers(1) } // already layered

        val cleared = m.clearGhostLayers(1)
        assertTrue(cleared.velocityLayers.isEmpty())
        assertFalse(softFile.exists(), "soft renders are deleted on revert")
    }

    @Test
    fun `bank view and the TEST kit egg`() {
        val dir = File(temp, "Banks")
        val m = KitBuilderModel.create("Banks", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(17, DrumSynth.snare(), DrumClass.SNARE) // B01

        val bankA = m.bank(0)
        val bankB = m.bank(1)
        assertEquals(16, bankA.size)
        assertEquals(DrumClass.KICK, bankA[0]?.drumClass)
        assertNull(bankA[1])
        assertEquals(DrumClass.SNARE, bankB[0]?.drumClass)

        assertEquals("VERY CREATIVE.", m.nameResponse("TEST"))
        assertNull(m.nameResponse("Banks"))
        assertFailsWith<IllegalArgumentException> { KitBuilderModel.create("bad:name", File(temp, "x")) }
    }
}
