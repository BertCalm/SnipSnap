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
    fun `stack the takes - real prior takes become soft zones, copied out of the bin, and clear undoes it`() {
        val dir = File(temp, "Stack")
        val m = KitBuilderModel.create("Stack", dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        val liveName = m.pad(1)!!.sampleFile
        val originalBytes = File(dir, liveName).readBytes()
        m.treatPad(1, "reversed") // bins the original
        m.treatPad(1, "punched") // bins the reversed
        val prior = m.priorTakes(1)
        assertEquals(2, prior.size, "two prior takes of this pad wait in the bin")
        val binBefore = m.binContents().map { it.file }.toSet()

        // Oldest as SOFT, the reversed one as MID, live on top.
        val stacked = m.stackTakes(1, listOf(prior[1], prior[0]))
        assertEquals(3, stacked.velocityLayers.size)
        assertEquals(listOf(1..41, 42..84, 85..127), stacked.velocityLayers.map { it.velStart..it.velEnd })
        assertEquals(liveName, stacked.velocityLayers.last().sampleFile, "LIVE stays the loudest zone")
        val soft = File(dir, stacked.velocityLayers[0].sampleFile)
        val mid = File(dir, stacked.velocityLayers[1].sampleFile)
        assertTrue(soft.readBytes().contentEquals(originalBytes), "SOFT is the original take, byte for byte")
        assertTrue(mid.readBytes().contentEquals(prior[0].file.readBytes()), "MID is the reversed take, byte for byte")
        assertEquals(binBefore, m.binContents().map { it.file }.toSet(), "copied, not consumed: the bin still holds both sources")
        assertNull(stacked.recipe?.entries?.get("stack"), "layers aren't a recipe, exactly as GHOSTS")

        assertFailsWith<IllegalArgumentException> { m.stackTakes(1, listOf(prior[0])) } // already layered

        val cleared = m.clearGhostLayers(1)
        assertTrue(cleared.velocityLayers.isEmpty())
        assertFalse(soft.exists() || mid.exists(), "clearing deletes the copies")
        assertEquals(binBefore, m.binContents().map { it.file }.toSet(), "…and leaves the bin sources alone")
    }

    @Test
    fun `stack the takes - refusals, and layer names that are already taken are skipped`() {
        val dir = File(temp, "StackRefuse")
        val m = KitBuilderModel.create("StackRefuse", dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(2, DrumSynth.kick(), DrumClass.KICK)
        m.treatPad(1, "reversed")
        m.treatPad(2, "reversed")
        val ofPad1 = m.priorTakes(1).single()
        val ofPad2 = m.priorTakes(2).single()

        assertFailsWith<IllegalArgumentException> { m.stackTakes(1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { m.stackTakes(1, listOf(ofPad1, ofPad1)) } // same take twice
        assertFailsWith<IllegalArgumentException> { m.stackTakes(1, listOf(ofPad2)) } // another pad's history
        assertFailsWith<IllegalArgumentException> { m.stackTakes(9, listOf(ofPad1)) } // no pad there
        // A forged entry: the right originalName, but a file that isn't in this kit's bin.
        val stranger = File(temp, "stranger.wav").apply { writeBytes(File(dir, m.pad(1)!!.sampleFile).readBytes()) }
        val forged = KitBuilderModel.BinEntry(ofPad1.originalName, ofPad1.binnedAtMillis, stranger)
        assertFailsWith<IllegalArgumentException> { m.stackTakes(1, listOf(forged)) }
        assertTrue(m.pad(1)!!.velocityLayers.isEmpty(), "a refusal touches nothing")

        // A stale `_v1` render left on disk is not overwritten: STACK takes `_v2`.
        val stem = m.pad(1)!!.sampleStem
        val stale = File(dir, "${stem}_v1.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stacked = m.stackTakes(1, listOf(ofPad1))
        assertEquals("${stem}_v2.wav", stacked.velocityLayers[0].sampleFile)
        assertTrue(stale.readBytes().contentEquals(byteArrayOf(1, 2, 3)), "the stale file is untouched")

        // GHOSTS skips taken names the same way now.
        m.clearGhostLayers(1)
        val ghosted = m.addGhostLayers(1)
        assertEquals("${stem}_v2.wav", ghosted.velocityLayers[0].sampleFile)
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
    fun `restoreTake reattaches the audio that was live when the take was archived, not newer audio squatting on the filename`() {
        val dir = File(temp, "RestoreFidelity")
        val m = KitBuilderModel.create("RestoreFidelity", dir)
        val pad = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()

        // No sleeps: archiveTake's own T-monotonicity guard (KitBuilder.kt)
        // is what keeps this deterministic now, not artificial spacing -
        // a treat's moveToBin landing in the same millisecond as the save
        // right after it is exactly the boundary that guard exists for.
        m.treatPad(2, "crushed") // bins the pristine original; CRUSHED (T1) becomes live
        val crushedBytes = File(dir, pad.sampleFile).readBytes()
        m.save() // archives a take whose live audio, right now, IS the crushed copy
        val take = m.takes().last()

        m.treatPad(2, "washed", amount = 0.6f) // bins CRUSHED (a second copy); WASHED (T2) becomes live
        val washedBytes = File(dir, pad.sampleFile).readBytes()
        assertFalse(crushedBytes.contentEquals(washedBytes), "washed must differ from crushed for this test to mean anything")

        m.restoreTake(take)

        val restored = File(dir, m.pad(2)!!.sampleFile).readBytes()
        assertTrue(
            restored.contentEquals(crushedBytes),
            "restore reattaches the audio live when the take was archived (crushed), not the washed treatment applied afterward",
        )
        assertFalse(restored.contentEquals(washedBytes))

        // Copy, don't consume: the entry that funded the restore is still in the bin.
        assertTrue(
            m.binContents().any { it.file.readBytes().contentEquals(crushedBytes) },
            "restoring a take must not spend the bin history a later take-restore might also need",
        )
    }

    @Test
    fun `restoreTake leaves a file untouched when nothing was binned since the take was archived`() {
        val dir = File(temp, "RestoreUntouched")
        val m = KitBuilderModel.create("RestoreUntouched", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        val pad2 = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save() // take_001 = the empty original
        m.assign(3, DrumSynth.tom(), DrumClass.TOM)
        m.save() // take_002 = pad1 + pad2 - references pad2's file, which is never rewritten

        val before = File(dir, pad2.sampleFile).readBytes()
        val take = m.takes().last()

        m.restoreTake(take)

        val live = File(dir, pad2.sampleFile)
        assertTrue(live.isFile, "the file was never rewritten - it's already the right audio")
        assertTrue(live.readBytes().contentEquals(before), "untouched: no bin history postdates the take for this file")
        assertEquals(0, m.binContents().size, "nothing should be binned when restoring a file that was never rewritten")
    }

    @Test
    fun `restoreTake bins the audio it displaces, so the restore is itself undoable`() {
        val dir = File(temp, "RestoreUndo")
        val m = KitBuilderModel.create("RestoreUndo", dir)
        val pad = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()

        // No sleeps here either - see the fidelity test above for why.
        m.treatPad(2, "crushed") // bins the pristine original; CRUSHED (T1) becomes live
        val crushedBytes = File(dir, pad.sampleFile).readBytes()
        m.save() // archives a take whose live audio, right now, IS the crushed copy
        val take = m.takes().last()

        m.treatPad(2, "washed", amount = 0.6f) // bins CRUSHED (a second copy); WASHED (T2) becomes live
        val washedBytes = File(dir, pad.sampleFile).readBytes()

        m.restoreTake(take)
        assertTrue(
            File(dir, m.pad(2)!!.sampleFile).readBytes().contentEquals(crushedBytes),
            "restore brings the archived-at-T audio (crushed) back",
        )

        // The washed audio the restore displaced wasn't lost - it's the newest bin entry now.
        val restoredBack = m.restoreFromBin(m.pad(2)!!.sampleFile)
        assertTrue(
            restoredBack != null && restoredBack.readBytes().contentEquals(washedBytes),
            "restore-then-restore-back round-trips to the audio the take-restore displaced",
        )
    }

    @Test
    fun `archiveTake keeps its T strictly after every existing bin entry, so a millisecond tie can't fool restoreTake`() {
        val dir = File(temp, "TakeTMonotonic")
        val m = KitBuilderModel.create("TakeTMonotonic", dir)
        val pad = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()

        m.treatPad(2, "crushed") // bins the pristine ORIGINAL; CRUSHED becomes live
        val crushedBytes = File(dir, pad.sampleFile).readBytes()

        // Construct the collision by hand rather than trust real clock
        // timing to reproduce it: retime the just-binned ORIGINAL entry to
        // land a hair ahead of "now" - the same-millisecond race a fast
        // filesystem can hit for real when a treat's moveToBin and the
        // very next save land in the same tick (F1). A single AtomicFile
        // flush can't reliably outrun even 1ms on its own, so without the
        // monotonicity guard the archive below lands at-or-before this
        // instant.
        val originalEntry = m.binContents().single()
        val raceInstant = System.currentTimeMillis() + 1
        val collided = File(originalEntry.file.parentFile, "${raceInstant}_${originalEntry.originalName}")
        assertTrue(originalEntry.file.renameTo(collided), "test setup: rename must succeed")

        m.save() // archives a take whose live audio, right now, IS crushed
        val take = m.takes().last()

        assertTrue(
            take.lastModified() > raceInstant,
            "the guard must push the take's own T strictly past every bin entry that already existed at archive time",
        )

        m.treatPad(2, "washed", amount = 0.6f) // bins CRUSHED (a second copy); WASHED becomes live
        // Pin this displacement's own bin timestamp explicitly past T too,
        // rather than trust it landed later than the forced race instant
        // above by luck - the point of this test is the guard's guarantee,
        // not how long the DSP work happens to take on this machine.
        val crushedDisplacedByWashed = m.binContents().single { it.originalName == pad.sampleFile && it.file != collided }
        val pinned = File(
            crushedDisplacedByWashed.file.parentFile,
            "${take.lastModified() + 1}_${crushedDisplacedByWashed.originalName}",
        )
        assertTrue(crushedDisplacedByWashed.file.renameTo(pinned), "test setup: rename must succeed")

        m.restoreTake(take)

        val restored = File(dir, m.pad(2)!!.sampleFile).readBytes()
        assertTrue(
            restored.contentEquals(crushedBytes),
            "even with the pre-treat entry forced to tie/overlap the archive instant, restore reattaches CRUSHED, not the pre-treat ORIGINAL",
        )
    }

    @Test
    fun `restoring the same take twice does not grow the bin`() {
        val dir = File(temp, "RestoreIdempotent")
        val m = KitBuilderModel.create("RestoreIdempotent", dir)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()

        m.treatPad(2, "crushed") // bins the pristine original; CRUSHED becomes live
        m.save() // archives a take whose live audio, right now, IS crushed
        val take = m.takes().last()
        m.treatPad(2, "washed", amount = 0.6f) // bins CRUSHED; WASHED becomes live

        m.restoreTake(take)
        val binAfterFirst = m.binContents().size

        m.restoreTake(take) // same take, again - the audio is already correct
        val binAfterSecond = m.binContents().size

        assertEquals(
            binAfterFirst,
            binAfterSecond,
            "a repeat restore of an already-restored take is a no-op on the bin, not another bin entry",
        )
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

    // ---- finding 20: the two families compose, they do not stack ----

    /**
     * SMEAR is special-cased - its own recipe shape, its own door - and that
     * special-casing used to leak. `smearPad`'s restore-first guard asked
     * `readSmear` alone, so an *aged* pad was smeared on top of the ageing
     * and then had SMEAR's recipe written over the era's: the card naming one
     * treatment while the file carried two.
     *
     * The proof is byte-stable rather than "it changed": smearing an aged pad
     * must produce exactly what smearing the original produces.
     */
    @Test
    fun `smearing an aged pad restores it first, instead of stacking on the ageing`() {
        // The reference: the same source, smeared once, never aged.
        val refDir = File(temp, "SmearRef")
        val ref = KitBuilderModel.create("SmearRef", refDir)
        ref.assign(1, DrumSynth.kick(), DrumClass.KICK)
        ref.save()
        ref.smearPad(1, 0.5f)
        ref.save()
        val reference = File(refDir, ref.pad(1)!!.sampleFile).readBytes()

        // The same source, aged first, then smeared.
        val dir = File(temp, "SmearOverEra")
        val m = KitBuilderModel.create("SmearOverEra", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val original = File(dir, m.pad(1)!!.sampleFile).readBytes()
        m.eraPad(1, "tape", 1f)
        m.save()
        assertFalse(File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(original), "the fixture really aged")

        m.smearPad(1, 0.5f)
        m.save()
        assertTrue(
            File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(reference),
            "SMEAR must land on the restored original, not on the aged audio",
        )
        // And the recipe says SMEAR alone, which is now the truth.
        assertEquals(0.5f, PadSheet.readSmear(m.pad(1)!!.recipe))
        assertNull(PadSheet.read(m.pad(1)!!.recipe), "the era's recipe is gone, not merely overwritten")
    }

    /**
     * The other direction, and the one the pad sheet drives: re-smearing an
     * already-smeared pad was always restore-first. That must stay true now
     * the guard asks a broader question.
     */
    @Test
    fun `re-smearing still restores first`() {
        val dir = File(temp, "ReSmear")
        val m = KitBuilderModel.create("ReSmear", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        m.smearPad(1, 0.5f)
        m.save()
        val once = File(dir, m.pad(1)!!.sampleFile).readBytes()

        m.smearPad(1, 0.5f)
        m.save()
        assertTrue(
            File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(once),
            "the same AMT twice is the same sound, not the stretch applied twice",
        )
    }

    /**
     * A pad whose recipe the bin cannot undo - a bank-B twin, a CLI treat -
     * still stacks, because there is nothing to restore. Refusing outright
     * would take away a sound the user can still legitimately reach for.
     */
    @Test
    fun `a recipe with nothing in the bin behind it still smears on top`() {
        val dir = File(temp, "SmearUnbinned")
        val m = KitBuilderModel.create("SmearUnbinned", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        m.eraPad(1, "tape", 1f)
        m.save()
        m.emptyBin()
        val aged = File(dir, m.pad(1)!!.sampleFile).readBytes()

        m.smearPad(1, 0.5f)
        m.save()
        val after = File(dir, m.pad(1)!!.sampleFile).readBytes()
        assertFalse(after.contentEquals(aged), "it still smeared something")
        assertEquals(0.5f, PadSheet.readSmear(m.pad(1)!!.recipe))
    }

    @Test
    fun `a character treats the whole pad, layers included, and unEraPad restores it`() {
        val dir = File(temp, "CharKit")
        val m = KitBuilderModel.create("CharKit", dir)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.addGhostLayers(2) // a layered pad: every zone must change
        m.save()
        val files = (listOf(m.pad(2)!!.sampleFile) + m.pad(2)!!.velocityLayers.map { it.sampleFile }).distinct()
        assertTrue(files.size > 1, "the fixture is layered")
        val originals = files.associateWith { File(dir, it).readBytes() }

        val treated = m.characterPad(2, "smeared", 0.6f)
        m.save()
        for ((f, bytes) in originals) {
            assertFalse(File(dir, f).readBytes().contentEquals(bytes), "$f re-rendered")
        }
        val applied = PadSheet.read(treated.recipe)
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Character("smeared"), 0.6f, "TAIL"), applied)
        assertTrue(files.all { f -> m.binContents().any { it.originalName == f } }, "every file is in the bin")

        m.unEraPad(2)
        m.save()
        assertNull(m.pad(2)!!.recipe, "recipe cleared on undo")
        for ((f, bytes) in originals) {
            assertTrue(File(dir, f).readBytes().contentEquals(bytes), "$f back byte-identical")
        }
    }

    /** An off-key bell: three inharmonic partials, none on a C major note. */
    private fun clang(): com.snipsnap.audio.Snip {
        val rate = 44_100
        return com.snipsnap.audio.Snip(
            FloatArray(rate / 2) { i ->
                val t = i.toDouble() / rate
                (
                    0.5 * Math.sin(2 * Math.PI * 227.0 * t) * Math.exp(-2 * t) +
                        0.3 * Math.sin(2 * Math.PI * 545.0 * t) * Math.exp(-3 * t) +
                        0.15 * Math.sin(2 * Math.PI * 1290.0 * t) * Math.exp(-4 * t)
                    ).toFloat()
            },
            1, rate,
        )
    }

    @Test
    fun `retunePad talks a clang into the kit's key, refuses a kick, and undoes out of the bin`() {
        val dir = File(temp, "Tune")
        val m = KitBuilderModel.create("Tune", dir)
        val bell = m.assign(2, clang(), DrumClass.PERC)
        val kick = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val before = File(dir, bell.sampleFile).readBytes()

        // No key set: the nearest semitones.
        assertEquals(KitBuilderModel.NO_KEY_LABEL, m.retuneKeyLabel())
        m.setKey(com.snipsnap.audio.KeySpec.parse("C"))
        assertEquals("C MAJOR", m.retuneKeyLabel())

        val tuned = m.retunePad(2, 1f, seed = 3)
        m.save()
        assertFalse(File(dir, bell.sampleFile).readBytes().contentEquals(before), "re-rendered")
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Keyed("retuned"), 1f, PadSheet.TUNE), PadSheet.read(tuned.recipe))
        assertEquals("C MAJOR", m.lastKeyLabel)
        val landed = com.snipsnap.audio.Retune.analyze(
            com.snipsnap.audio.WavReader.read(File(dir, bell.sampleFile)),
            com.snipsnap.audio.KeySpec(0, com.snipsnap.audio.Scale.CHROMATIC),
        ).partials
        assertTrue(landed.all { kotlin.math.abs(it.cents) < 6f }, "every partial on a semitone now: ${landed.map { it.cents }}")
        assertEquals(listOf("A3", "C5", "E6"), landed.map { it.targetName })

        val refused = assertFailsWith<KitBuilderModel.Unpitched> { m.retunePad(1, 1f) }
        assertTrue("drum" in refused.message!!, refused.message)
        assertTrue(File(dir, kick.sampleFile).readBytes().isNotEmpty() && m.pad(1)!!.recipe == null, "the kick is untouched")

        assertEquals(bell.copy(recipe = tuned.recipe), m.retunePad(2, 0f), "AMT 0 leaves the pad as it is")
        m.unEraPad(2)
        m.save()
        assertNull(m.pad(2)!!.recipe)
        assertTrue(File(dir, bell.sampleFile).readBytes().contentEquals(before), "back byte-identical")

        // BODY never refuses: the kick gets a body in the kit's key, and rings past its own length.
        val kickBefore = File(dir, kick.sampleFile).readBytes()
        val bodied = m.keyedPad(1, "bodied", 1f, dials = Keyed.Dials(decay = 0.4f))
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Keyed("bodied"), 1f, "BODY"), PadSheet.read(bodied.recipe))
        assertEquals("C MAJOR", m.lastKeyLabel)
        val rung = com.snipsnap.audio.WavReader.read(File(dir, kick.sampleFile))
        assertTrue(rung.frameCount > DrumSynth.kick().frameCount, "the body rings past the hit")
        assertFailsWith<IllegalArgumentException> { m.keyedPad(1, "frozen", 1f) }
        m.unEraPad(1)
        assertTrue(File(dir, kick.sampleFile).readBytes().contentEquals(kickBefore), "the kick came back")

        // WOBBLE reads the tempo: none set, the preview's default; the division rides the recipe.
        val wobbled = m.keyedPad(1, "wobbled", 1f, dials = Keyed.Dials(division = "1/16"))
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Keyed("wobbled"), 1f, "WOBBLE"), PadSheet.read(wobbled.recipe))
        assertEquals("1/16 AT 92 BPM", m.lastKeyLabel)
        assertEquals("1/16", (wobbled.recipe!!.entries["division"] as com.snipsnap.json.JsonValue.Str).value)
        m.unEraPad(1)
        assertTrue(File(dir, kick.sampleFile).readBytes().contentEquals(kickBefore), "and back again")

        // ETERNAL keeps the attack bit for bit and takes its tail from AMT unless the CLI says seconds.
        // Against the pad as it sits on disk: the WAV round trip already quantized the synth's floats.
        val kickSnip = com.snipsnap.audio.WavReader.read(File(dir, kick.sampleFile))
        val eternal = m.keyedPad(1, "eternal", 0.5f)
        assertEquals(PadSheet.Applied(PadSheet.Treatment.Keyed("eternal"), 0.5f, "ETERNAL"), PadSheet.read(eternal.recipe))
        val held = com.snipsnap.audio.WavReader.read(File(dir, kick.sampleFile))
        val knee = (com.snipsnap.audio.Eternal.KNEE_DEFAULT_SEC * 44_100).toInt()
        assertEquals(knee + (com.snipsnap.audio.Eternal.tailFor(0.5f) * 44_100).toInt(), held.frameCount)
        // Bit-identical in memory (EternalTest); on disk, to the WAV's own precision.
        for (i in 0 until knee) assertEquals(kickSnip.samples[i], held.samples[i], 1e-6f, "the attack is the kick's own")
        assertTrue(m.lastKeyLabel.endsWith("S TAIL"), m.lastKeyLabel)
        m.unEraPad(1)
        // A second-long tone has more tail than half a second: refused before anything is touched.
        val tone = m.assign(3, com.snipsnap.audio.Snip(FloatArray(44_100) { (0.4 * Math.sin(2 * Math.PI * 220.0 * it / 44_100)).toFloat() }, 1, 44_100), DrumClass.TONAL)
        m.save()
        val toneBefore = File(dir, tone.sampleFile).readBytes()
        val tooShort = assertFailsWith<KitBuilderModel.Unpitched> { m.keyedPad(3, "eternal", 1f, dials = Keyed.Dials(tail = 0.5f)) }
        assertTrue("already" in tooShort.message!!, tooShort.message)
        assertTrue(File(dir, tone.sampleFile).readBytes().contentEquals(toneBefore), "refused before anything was touched")
    }

    @Test
    fun `desamplePad swaps a capture for the nearest patch's render, refuses a stranger, and undoes`() {
        val dir = File(temp, "Desample")
        val m = KitBuilderModel.create("Desample", dir)
        val kick = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        val rnd = java.util.Random(4)
        val hiss = m.assign(2, com.snipsnap.audio.Snip(FloatArray(44_100) { (rnd.nextFloat() * 2f - 1f) * 0.5f }, 1, 44_100), DrumClass.UNKNOWN)
        m.save()
        val before = File(dir, kick.sampleFile).readBytes()

        val match = m.desamplePad(1)
        assertEquals(com.snipsnap.synth.ThumpVoice.KICK, match.patch.voice, "a kick's search starts on kicks")
        assertTrue(!match.far)
        val pad = m.pad(1)!!
        val recipe = com.snipsnap.synth.PadRecipe.fromJsonValue(pad.recipe!!)
        assertEquals(match.patch, recipe.patch, "the patch rides the pad")
        assertTrue(File(dir, kick.sampleFile).readBytes().let { !it.contentEquals(before) }, "the render replaced the capture")
        val onDisk = com.snipsnap.audio.WavReader.read(File(dir, kick.sampleFile)).samples
        val render = match.patch.render().samples
        assertEquals(render.size, onDisk.size, "the pad is the patch's own render")
        for (i in onDisk.indices) assertEquals(render[i], onDisk[i], 1e-5f, "sample $i, to the WAV's precision")
        assertTrue(pad.source["desampled"]!!.toFloat() < com.snipsnap.synth.Desample.FAR)

        val far = assertFailsWith<KitBuilderModel.Far> { m.desamplePad(2) }
        assertTrue(far.match.far && "no patch is near" in far.message!!)
        assertNull(m.pad(2)!!.recipe, "a refusal touches nothing")
        val forced = m.desamplePad(2, evenIfFar = true)
        assertTrue(forced.far)
        assertTrue(m.pad(2)!!.recipe != null)

        m.untreatPad(1)
        assertTrue(File(dir, kick.sampleFile).readBytes().contentEquals(before), "back byte-identical")
        assertNull(m.pad(1)!!.recipe)
    }

    @Test
    fun `priorTakes is empty until a rewrite bins something, then only this pad's own history`() {
        val dir = File(temp, "PriorTakes")
        val m = KitBuilderModel.create("PriorTakes", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        assertEquals(0, m.priorTakes(1).size, "nothing rewritten yet")

        m.treatPad(1, "punched")
        assertEquals(1, m.priorTakes(1).size, "the pre-treatment capture is now recoverable")
        assertEquals(0, m.priorTakes(2).size, "slot 2's own history is untouched")

        assertFailsWith<IllegalArgumentException> { m.priorTakes(9) }
    }

    @Test
    fun `splicePad joins two prior takes, bins whatever was live, and undoes`() {
        val dir = File(temp, "Splice")
        val m = KitBuilderModel.create("Splice", dir)
        val kick = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val originalCapture = com.snipsnap.audio.WavReader.read(File(dir, kick.sampleFile))

        // Two rewrites give this pad two recoverable prior takes to splice between.
        m.treatPad(1, "punched")
        m.treatPad(1, "washed")
        val beforeSplice = File(dir, kick.sampleFile).readBytes()
        val takes = m.priorTakes(1)
        assertEquals(2, takes.size, "the original capture and the punched take are both recoverable")

        val head = com.snipsnap.audio.WavReader.read(takes[0].file) // newest: the punched take
        val tail = com.snipsnap.audio.WavReader.read(takes[1].file) // the original capture
        val spliced = m.splicePad(1, head, head.frameCount / 3, tail, tail.frameCount / 2)

        assertTrue(File(dir, kick.sampleFile).readBytes().let { !it.contentEquals(beforeSplice) }, "the join replaced the live sample")
        val onDisk = com.snipsnap.audio.WavReader.read(File(dir, kick.sampleFile)).samples
        assertEquals(spliced.snip.samples.size, onDisk.size, "the pad is the joined result")
        for (i in onDisk.indices) assertEquals(spliced.snip.samples[i], onDisk[i], 1e-4f, "sample $i, to the WAV's precision")

        val recipe = m.pad(1)!!.recipe!!
        val recipeSplice = (recipe as com.snipsnap.json.JsonValue.Obj).entries["splice"] as com.snipsnap.json.JsonValue.Obj
        assertEquals(spliced.crossfaded, (recipeSplice.entries["crossfaded"] as com.snipsnap.json.JsonValue.Bool).value)

        // Whatever was live before the splice (the washed take) is itself
        // now the newest bin entry - undoable like any other rewrite.
        m.untreatPad(1)
        assertTrue(File(dir, kick.sampleFile).readBytes().contentEquals(beforeSplice), "back to the pre-splice audio")
        assertNull(m.pad(1)!!.recipe)

        // Sanity: the original capture is still exactly what it always was.
        assertTrue(originalCapture.samples.contentEquals(tail.samples))
    }

    @Test
    fun `splicePad refuses a velocity-layered pad, same door as replaceAudio`() {
        val dir = File(temp, "SpliceLayered")
        val m = KitBuilderModel.create("SpliceLayered", dir)
        val pad = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.treatPad(1, "punched")
        val head = com.snipsnap.audio.WavReader.read(File(dir, pad.sampleFile))
        m.addGhostLayers(1)
        assertFailsWith<IllegalArgumentException> { m.splicePad(1, head, head.frameCount / 2, head, head.frameCount / 2) }
    }

    @Test
    fun `with a key set, a tonal pad retunes on assign and the kick is untouched`() {
        val m = KitBuilderModel.create("OnAssign", File(temp, "OnAssign"))
        val rate = 44_100
        // 227 Hz: 54 cents under A3, a semitone-and-a-bit off G#3.
        val note = com.snipsnap.audio.Snip(FloatArray(rate / 2) { (0.5 * Math.sin(2 * Math.PI * 227.0 * it / rate)).toFloat() }, 1, rate)
        val before = m.assign(1, note, DrumClass.TONAL)
        assertEquals(0, before.tuneCoarse)
        assertEquals(0, before.tuneFine, "no key: as captured")

        m.setKey(com.snipsnap.audio.KeySpec.parse("C"))
        val inKey = m.assign(2, note, DrumClass.TONAL)
        val expected = com.snipsnap.audio.Tuner.inKey(note, 0, com.snipsnap.audio.Scale.MAJOR)!!
        assertEquals(expected.tuneCoarse, inKey.tuneCoarse)
        assertEquals(expected.tuneFine, inKey.tuneFine)
        assertTrue(inKey.tuneCoarse != 0 || inKey.tuneFine != 0, "227 Hz moved onto A3: ${inKey.tuneCoarse} st ${inKey.tuneFine} c")
        assertEquals("A3", expected.targetName)

        val kick = m.assign(3, DrumSynth.kick(), DrumClass.KICK)
        assertEquals(0, kick.tuneCoarse)
        assertEquals(0, kick.tuneFine, "the kick is untouched")
        assertEquals(listOf("A02 · TONAL 02 · " + "%+d ST %+d¢".format(java.util.Locale.ROOT, expected.tuneCoarse, expected.tuneFine).replace("-", "−")).first(), KeyPicker.readouts(m.kit)[1])
    }

    @Test
    fun `characterPad refuses a typo before it looks at the amount, and AMT 0 is a no-op`() {
        val dir = File(temp, "CharNoop")
        val m = KitBuilderModel.create("CharNoop", dir)
        val pad = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val before = File(dir, pad.sampleFile).readBytes()

        assertFailsWith<IllegalArgumentException> { m.characterPad(1, "sparkled", 0f) }
        val untouched = m.characterPad(1, "punched", 0f)
        assertEquals(pad, untouched)
        assertTrue(File(dir, pad.sampleFile).readBytes().contentEquals(before), "never rewritten")
        assertEquals(0, m.binContents().size, "nothing binned")
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
        assertEquals(
            1,
            m.binContents().size,
            "restoreTake copies the entry back, it doesn't consume it - the bin still holds it",
        )

        // Purge honours the 30-day promise; a fresh delete survives it.
        m.clear(1)
        assertEquals(0, m.purgeBin(nowMillis = System.currentTimeMillis()), "nothing is 30 days old yet")
        assertEquals(
            2,
            m.purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000),
            "the un-consumed restore entry plus the fresh re-clear entry",
        )
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

    @Test
    fun `assign carries an optional source tag onto the pad, defaulting to none, and it survives save-reopen`() {
        val dir = File(temp, "SourceTag")
        val m = KitBuilderModel.create("SourceTag", dir)

        val untagged = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        assertEquals(emptyMap(), untagged.source, "a plain assign (GRAB/HOLD off the ring) tags nothing")

        val tagged = m.assign(2, DrumSynth.snare(), DrumClass.SNARE, source = mapOf("file" to "snip_123.wav"))
        assertEquals("snip_123.wav", tagged.source["file"], "an assign from an existing snip file records which one")

        // Task 3's "USED" badge reads this off a reopened kit, not the
        // in-memory return value - kit.json must actually carry it.
        m.save()
        val reopened = KitBuilderModel.open(dir)
        assertEquals("snip_123.wav", reopened.pad(2)!!.source["file"], "the source tag round-trips through kit.json")
        assertEquals(emptyMap(), reopened.pad(1)!!.source, "an untagged pad reopens with no source either")
    }

    @Test
    fun `SnipStore provenanceTag's capturedAtMillis round-trips through kit json and keeps the USED badge honest after save-reopen`() {
        val snipsRoot = File(temp, "SnipsRoot")
        val snip = SnipStore.commit(DrumSynth.snare().samples, 44_100, snipsRoot, nowMillis = 123_000L)

        val dir = File(temp, "SourceTagMillis")
        val m = KitBuilderModel.create("SourceTagMillis", dir)
        val tagged = m.assign(1, DrumSynth.snare(), DrumClass.SNARE, source = SnipStore.provenanceTag(snip))
        assertEquals(snip.name, tagged.source["file"])
        assertEquals("123000", tagged.source["capturedAtMillis"])

        m.save()
        val reopened = KitBuilderModel.open(dir)
        val pad = reopened.pad(1)!!
        assertEquals(snip.name, pad.source["file"], "\"file\" round-trips through kit.json")
        assertEquals("123000", pad.source["capturedAtMillis"], "capturedAtMillis round-trips through kit.json too")

        // The whole point: a reopened pad's badge must still resolve after
        // the snip that made it is renamed - the exact regression this
        // task fixes, exercised end-to-end (write -> persist -> reopen ->
        // rename -> read).
        val renamed = SnipStore.rename(snip, "Snare One")!!
        assertTrue(SnipStore.isUsedBy(pad, renamed), "the reopened pad's badge survives a rename of its snip")
    }

    @Test
    fun `save refuses to resurrect a directory that no longer exists`() {
        val dir = File(temp, "Vanished")
        val m = KitBuilderModel.create("Vanished", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        assertTrue(dir.deleteRecursively(), "test setup: the kit folder must actually go away")

        // A model holding a snapshot of a kit whose folder was since
        // renamed or deleted (e.g. a stale dispose-time flush racing a
        // rename) must not call KitStore.save, which unconditionally
        // mkdirs() its target and would otherwise resurrect a ghost
        // directory containing nothing but this kit.json.
        assertFailsWith<IllegalArgumentException> { m.save() }
        assertFalse(dir.exists(), "save() must not resurrect the folder it once lived in")
    }

    @Test
    fun `a take that names a file outside the folder is refused before anything moves`() {
        val dir = File(temp, "ClimbingTake")
        val m = KitBuilderModel.create("ClimbingTake", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        m.treatPad(1, "reversed")
        m.save()
        // The newest take is the kit with its pad; the first is FRESH TAPE's empty kit.
        val take = m.takes().last()
        assertTrue(m.pad(1)!!.sampleFile in take.readText(), "the take names the pad's file")
        val kitBefore = File(dir, "kit.json").readBytes()
        val liveBefore = File(dir, m.pad(1)!!.sampleFile).readBytes()
        val binBefore = m.binContents().map { it.file.name }.toSet()

        // An absolute-path climb, scoped to this test's own temp dir rather
        // than a real global path - a hard-coded /tmp/escaped.wav would be
        // flaky wherever that file already exists (a parallel run, a stray
        // leftover) or /tmp is unwritable, and would leak outside this
        // test's own cleanup if the refusal somehow failed.
        val absoluteEscape = File(temp, "escaped.wav").absolutePath
        // The backslash is JSON-escaped in the take text, the way a writer would
        // carry it, so the pad type - not the JSON parser - is what refuses it.
        for (climb in listOf("../escaped.wav", "..\\\\escaped.wav", absoluteEscape, "sub/escaped.wav")) {
            take.writeText(take.readText().replace(m.pad(1)!!.sampleFile, climb))
            val e = assertFailsWith<IllegalArgumentException> { KitBuilderModel.open(dir).restoreTake(take) }
            assertTrue("bare filename" in (e.message ?: ""), "refused in the pad's own words: ${e.message}")
            assertTrue(kitBefore.contentEquals(File(dir, "kit.json").readBytes()), "kit.json untouched after '$climb'")
            assertTrue(liveBefore.contentEquals(File(dir, m.pad(1)!!.sampleFile).readBytes()), "live audio untouched after '$climb'")
            assertEquals(binBefore, m.binContents().map { it.file.name }.toSet(), "the bin untouched after '$climb'")
            assertFalse(File(dir.parentFile, "escaped.wav").exists() || File(absoluteEscape).exists(), "nothing landed outside after '$climb'")
            take.writeText(take.readText().replace(climb, m.pad(1)!!.sampleFile))
        }
        // ".." has no separator, so the pad type lets it through; the restore
        // then finds no such file and no such bin entry, and moves nothing -
        // a quiet success, not a refusal. Asserted, not just run-and-forget:
        // a house rule this whole file otherwise enforces (a valid result or
        // a NAMED refusal, never a silently swallowed throwable) would
        // otherwise not apply to this one branch.
        take.writeText(take.readText().replace(m.pad(1)!!.sampleFile, ".."))
        val dotDotResult = runCatching { KitBuilderModel.open(dir).restoreTake(take) }
        dotDotResult.exceptionOrNull()?.let { ex ->
            assertTrue(ex is IllegalArgumentException, "\"..\" threw ${ex::class.simpleName} instead of succeeding or refusing by name: ${ex.message}")
            assertTrue(!ex.message.isNullOrBlank(), "\"..\" refused without saying why")
        }
        assertTrue(liveBefore.contentEquals(File(dir, m.pad(1)!!.sampleFile).readBytes()))
        assertEquals(binBefore, m.binContents().map { it.file.name }.toSet())
        assertTrue(dir.parentFile.listFiles()!!.none { it.name.startsWith("escaped") })
    }
}
