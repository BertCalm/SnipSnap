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
    fun `takes archive every meaningful save and restore rolls back`() {
        val dir = File(temp, "Takes")
        val m = KitBuilderModel.create("Takes", dir)
        assertEquals(0, m.takes().size, "creation isn't a take")

        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save() // archives the empty original
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save() // archives the one-pad version
        assertEquals(2, m.takes().size)
        m.save() // clean save: no new take
        assertEquals(2, m.takes().size)

        val onePadTake = m.takes().last()
        m.restoreTake(onePadTake)
        assertTrue(m.dirty)
        assertEquals(1, m.kit.pads.size, "rolled back to the one-pad take")
        assertEquals(DrumClass.KICK, m.pad(1)?.drumClass)
    }

    @Test
    fun `an era ages the whole kit, layers included, and undo restores it`() {
        val dir = File(temp, "EraKit")
        val m = KitBuilderModel.create("EraKit", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.addGhostLayers(2) // a layered pad: every zone must age
        m.save()
        val originals = m.kit.pads.flatMap { p ->
            (listOf(p.sampleFile) + p.velocityLayers.map { it.sampleFile }).distinct()
        }.associateWith { File(dir, it).readBytes() }

        val aged = m.eraKit("sp1200")
        m.save()
        assertEquals(2, aged)
        for ((f, bytes) in originals) {
            assertFalse(File(dir, f).readBytes().contentEquals(bytes), "$f aged")
        }
        assertTrue(m.kit.pads.all { it.recipe != null }, "era recipes recorded")

        // Undo brings the previous audio back out of the bin, byte-identical.
        m.unEraPad(1)
        m.save()
        assertTrue(m.pad(1)!!.recipe == null, "pad 1 recipe cleared on undo")
        val restored = File(dir, m.pad(1)!!.sampleFile).readBytes()
        assertTrue(
            restored.contentEquals(originals.getValue(m.pad(1)!!.sampleFile)),
            "pad 1 audio restored byte-identical",
        )
    }

    @Test
    fun `eraPad at amount 0 is a no-op and leaves the bin empty`() {
        val dir = File(temp, "EraNoop")
        val m = KitBuilderModel.create("EraNoop", dir)
        val pad = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val before = File(dir, pad.sampleFile).readBytes()

        val untouched = m.eraPad(1, "sp1200", amount = 0f)
        assertEquals(pad, untouched, "amount 0 leaves the pad exactly as it was")
        assertNull(m.pad(1)!!.recipe, "no recipe recorded for a no-op")
        assertTrue(
            File(dir, pad.sampleFile).readBytes().contentEquals(before),
            "the file was never rewritten",
        )
        assertEquals(0, m.binContents().size, "nothing moved to the bin")

        val agedNone = m.eraKit("sp1200", amount = 0f)
        assertEquals(0, agedNone, "amount 0 ages nothing")
        assertEquals(0, m.binContents().size, "still nothing in the bin")
    }

    @Test
    fun `a torn take from a killed archive is skipped, not surfaced`() {
        val dir = File(temp, "TornTakes")
        val m = KitBuilderModel.create("TornTakes", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        assertEquals(2, m.takes().size)

        // A process killed mid-archive leaves a half-written take file.
        File(dir, ".takes/take_003.json").writeText("{\"name\": \"Torn\", \"pads\": [{\"slo")
        assertEquals(2, m.takes().size, "the torn take is skipped, the good ones remain")
        // And a rollback still works, unbothered by the garbage beside them.
        m.restoreTake(m.takes().last())
        assertEquals(1, m.kit.pads.size)
    }

    @Test
    fun `the bin keeps deletes 30 days and takes pull samples back out`() {
        val dir = File(temp, "Bin")
        val m = KitBuilderModel.create("Bin", dir)
        val pad = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save() // take_001 = the empty original
        m.assign(2, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save() // take_002 = the kick-only kit

        m.clear(1)
        assertFalse(File(dir, pad.sampleFile).exists(), "cleared from the kit")
        val bin = m.binContents()
        assertEquals(1, bin.size)
        assertEquals(pad.sampleFile, bin[0].originalName, "recoverable, not gone")

        // Restoring the kick-only take pulls the WAV back out of the bin.
        m.restoreTake(m.takes().last())
        assertEquals(DrumClass.KICK, m.pad(1)?.drumClass)
        assertTrue(File(dir, pad.sampleFile).isFile, "the take brought its sample home")
        assertEquals(0, m.binContents().size)

        // Purge honours the 30-day promise; a fresh delete survives it.
        m.clear(1)
        assertEquals(0, m.purgeBin(nowMillis = System.currentTimeMillis()), "nothing is 30 days old yet")
        assertEquals(1, m.purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000))
        assertEquals(0, m.binContents().size)

        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.clear(2)
        assertEquals(1, m.emptyBin())
    }

    @Test
    fun `restoreFromBin by entry restores the tapped copy, not just the newest`() {
        val dir = File(temp, "BinDup")
        val m = KitBuilderModel.create("BinDup", dir)
        val pad = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        val original = File(dir, pad.sampleFile).readBytes()

        // Two treat cycles on the same pad bin two different copies under
        // the SAME originalName (pad.sampleFile never changes) - moveToBin
        // keys bin filenames off System.currentTimeMillis(), so a short
        // sleep guarantees the two entries don't collide on that key.
        m.treatPad(2, "crushed") // bins the ORIGINAL audio
        Thread.sleep(5)
        val crushedBytes = File(dir, pad.sampleFile).readBytes()
        m.treatPad(2, "washed", amount = 0.6f) // bins the CRUSHED audio

        val bin = m.binContents()
        assertEquals(2, bin.size, "two entries share pad.sampleFile as their originalName")
        assertTrue(bin.all { it.originalName == pad.sampleFile })
        val newer = bin.first() // binContents() is newest-first
        val older = bin.last()
        assertTrue(newer.file.readBytes().contentEquals(crushedBytes), "newer entry holds the crushed copy")
        assertTrue(older.file.readBytes().contentEquals(original), "older entry holds the original")

        // restoreFromBin(String) would always pick `newer` (see its own
        // KDoc); the entry-keyed overload restores exactly the one handed
        // to it - the older entry, deeper in the bin, the row a user would
        // have tapped by its own distinct countdown.
        val restored = m.restoreFromBin(older)
        assertTrue(restored != null && restored.readBytes().contentEquals(original), "the tapped (older) entry's own content came back")
        assertEquals(1, m.binContents().size, "only the tapped entry left the bin")
        assertEquals(newer.file, m.binContents().single().file, "the newer entry is untouched, still in the bin")

        // A stale entry - already restored, or purged out from under the
        // caller - is a null, not a crash.
        assertNull(m.restoreFromBin(older))
    }

    @Test
    fun `treatments re-render one pad, stack, and undo out of the bin`() {
        val dir = File(temp, "Treat")
        val m = KitBuilderModel.create("Treat", dir)
        val pad = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        val original = File(dir, pad.sampleFile).readBytes()

        val treated = m.treatPad(2, "crushed")
        assertTrue(treated.recipe != null, "the fx recipe is recorded")
        val crushedBytes = File(dir, pad.sampleFile).readBytes()
        assertTrue(!original.contentEquals(crushedBytes), "crushing must change the audio")
        assertEquals(DrumClass.SNARE, treated.drumClass, "identity fields untouched")

        // Stack a second character, then undo twice: back to the source.
        m.treatPad(2, "washed", amount = 0.6f)
        assertTrue(!File(dir, pad.sampleFile).readBytes().contentEquals(crushedBytes))
        m.untreatPad(2)
        assertTrue(File(dir, pad.sampleFile).readBytes().contentEquals(crushedBytes), "undo pops one layer")
        m.untreatPad(2)
        assertTrue(File(dir, pad.sampleFile).readBytes().contentEquals(original))
        assertEquals(null, m.pad(2)?.recipe)
        assertFailsWith<IllegalArgumentException> { m.untreatPad(2) }

        assertFailsWith<IllegalArgumentException> { m.treatPad(2, "sparkled") }
        m.addGhostLayers(2)
        assertFailsWith<IllegalArgumentException> { m.treatPad(2, "crushed") }
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
