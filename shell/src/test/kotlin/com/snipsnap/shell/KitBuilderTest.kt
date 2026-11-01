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
import kotlin.test.assertNotEquals
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
        assertEquals("A03", twin.source[KitBuilderModel.TWIN_OF], "a twin knows which pad it twins")
        assertTrue(m.isTwin(twin))
        assertTrue(m.ownPadsOnBankB().isEmpty(), "twins are the remix's own to replace")
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
    fun `ghost layers on a synth-backed pad re-render via atVelocity - not soften on the frozen take`() {
        val patch = com.snipsnap.synth.TinesPresets.forVoice(com.snipsnap.synth.TinesVoice.BELL).first()
        val dir = File(temp, "SynthGhosts")
        val m = KitBuilderModel.create("SynthGhosts", dir)
        m.assign(1, patch.render(), DrumClass.TONAL)
        // Attach the patch after assigning its own render, the same shape
        // `desamplePad` and every synth-kit builder leave on a pad: the
        // recipe is what makes a pad "synth-backed", not the drum class.
        m.update(1) { it.copy(recipe = com.snipsnap.synth.PadRecipe(patch = patch).toJsonValue()) }

        val main = com.snipsnap.audio.WavReader.read(File(dir, m.pad(1)!!.sampleFile))
        val layered = m.addGhostLayers(1)
        val soft = com.snipsnap.audio.WavReader.read(File(dir, layered.velocityLayers[0].sampleFile))

        // amount 0.55 (addGhostLayers' single-zone depth) is velocity 0.45
        // (1f - amount) in atVelocity's terms, peak-matched to the pad's own
        // audio (atVelocity isn't peak-matched itself; addGhostLayers must
        // restore that or the "darker, not quieter" contract breaks) - the
        // exact render the ghost layer must match, modulo 16-bit quantizing.
        val expected = com.snipsnap.synth.Velocity.peakMatch(main, com.snipsnap.synth.Velocity.atVelocity(patch, 0.45f))
        assertEquals(expected.samples.size, soft.samples.size, "a re-render, not a filtered copy, has its own length")
        for (i in soft.samples.indices) {
            assertTrue(
                kotlin.math.abs(soft.samples[i] - expected.samples[i]) <= 2f / 32767f,
                "sample $i: ghost ${soft.samples[i]} vs atVelocity render ${expected.samples[i]}",
            )
        }

        // And it must NOT be what the old soften()-on-the-main-take path
        // would have produced - proof the onset actually changed, the
        // property a low-pass on one frozen render could never have.
        val viaSoften = com.snipsnap.synth.Velocity.soften(main, 0.55f)
        val n = minOf(soft.samples.size, viaSoften.samples.size, 400)
        val differing = (0 until n).count { kotlin.math.abs(soft.samples[it] - viaSoften.samples[it]) > 1e-3f }
        assertTrue(differing > 50, "onset should differ from soften()'s output; only $differing samples did")

        // "Timbre only, level is the hardware's job" as a measurement, not a
        // restatement of the production formula: a duller BRIGHT/CUTOFF is
        // naturally quieter as a side effect of the engine, so this only
        // holds if addGhostLayers actually peak-matches - it isn't free.
        fun peak(x: FloatArray) = x.maxOf { kotlin.math.abs(it) }
        assertTrue(
            kotlin.math.abs(peak(soft.samples) - peak(main.samples)) <= 2f / 32767f,
            "the ghost layer should be peak-matched to the main take: ${peak(soft.samples)} vs ${peak(main.samples)}",
        )
    }

    @Test
    fun `ghost layers on a synth-backed pad WITH an fx rack fall back to soften - atVelocity can't replay the rack`() {
        val patch = com.snipsnap.synth.TinesPresets.forVoice(com.snipsnap.synth.TinesVoice.BELL).first()
        val fx = com.snipsnap.synth.FxChain(reverse = false)
        val dir = File(temp, "SynthGhostsFx")
        val m = KitBuilderModel.create("SynthGhostsFx", dir)
        val recipe = com.snipsnap.synth.PadRecipe(patch = patch, fx = fx)
        m.assign(1, recipe.render(), DrumClass.TONAL)
        m.update(1) { it.copy(recipe = recipe.toJsonValue()) }

        val main = com.snipsnap.audio.WavReader.read(File(dir, m.pad(1)!!.sampleFile))
        val layered = m.addGhostLayers(1)
        val soft = com.snipsnap.audio.WavReader.read(File(dir, layered.velocityLayers[0].sampleFile))

        // The same path a captured pad takes, not merely "different from
        // atVelocity" - a patch riding its own fx chain has no way for
        // atVelocity to replay the rack on top of its re-render, so it
        // must soften() the fx-processed take exactly like a captured pad
        // would (tolerance is the WAV's 16-bit quantizing on the way to
        // and from disk, same as every other byte-match in this file).
        val viaSoften = com.snipsnap.synth.Velocity.soften(main, 0.55f)
        assertEquals(viaSoften.samples.size, soft.samples.size)
        for (i in soft.samples.indices) {
            assertTrue(
                kotlin.math.abs(soft.samples[i] - viaSoften.samples[i]) <= 2f / 32767f,
                "sample $i: ghost ${soft.samples[i]} vs soften() ${viaSoften.samples[i]} - should fall back exactly",
            )
        }
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
     * AMT 0 must not be destructive outside SMEAR's own family. The pad
     * sheet's AMT slider reaches 0, and `applySmear` hands whatever it reads
     * straight to this door — so an aged pad, AMT dragged to zero, SMEAR
     * tapped would otherwise have its era silently undone with nothing
     * smeared in its place. NONE is the way to take a treatment off; a
     * slider passing through zero is not.
     */
    @Test
    fun `AMT 0 leaves an era alone instead of quietly undoing it`() {
        val dir = File(temp, "SmearZeroOverEra")
        val m = KitBuilderModel.create("SmearZeroOverEra", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        m.eraPad(1, "tape", 1f)
        m.save()
        val aged = File(dir, m.pad(1)!!.sampleFile).readBytes()
        val recipe = m.pad(1)!!.recipe

        m.smearPad(1, 0f)
        m.save()
        assertTrue(
            File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(aged),
            "AMT 0 is a no-op: the aged audio must still be there",
        )
        assertEquals(recipe, m.pad(1)!!.recipe, "and the era's recipe must still be riding the pad")
    }

    /**
     * The prior behaviour this must not disturb: AMT 0 on a pad that is
     * already smeared still takes the smear off, which is what "no smear"
     * has always meant at this door.
     */
    @Test
    fun `AMT 0 on an already-smeared pad still takes the smear off`() {
        val dir = File(temp, "SmearZeroOverSmear")
        val m = KitBuilderModel.create("SmearZeroOverSmear", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val original = File(dir, m.pad(1)!!.sampleFile).readBytes()
        m.smearPad(1, 0.5f)
        m.save()
        assertFalse(File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(original), "the fixture really smeared")

        m.smearPad(1, 0f)
        m.save()
        assertTrue(
            File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(original),
            "back to the original, byte-identical",
        )
        assertNull(m.pad(1)!!.recipe, "and the SMEAR recipe comes off with it")
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
        // A slow sweep, not the second of hiss this used to use: hiss stopped
        // being a stranger the moment SKIN's noise voices became searchable
        // (it lands on SKIN/RIDE at 0.391, inside FAR). `DesampleTest` carries
        // the reasoning and pins the change; this needs a source that is still
        // genuinely far, so the refusal path below has something to refuse.
        val sweep = com.snipsnap.audio.Snip(
            FloatArray(88_200) { i ->
                val f = 80.0 + 3000.0 * i / 88_200.0
                kotlin.math.sin(2.0 * Math.PI * f * i / 44_100.0).toFloat() * 0.5f
            },
            1, 44_100,
        )
        m.assign(2, sweep, DrumClass.UNKNOWN)
        m.save()
        val before = File(dir, kick.sampleFile).readBytes()

        val match = m.desamplePad(1)
        assertEquals("KICK", match.patch.voiceName, "a kick's search starts on kicks")
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
        // 2, not 1: the cleared snare pad is a simple (unlayered, unchained)
        // pad, so moveToBin now writes its JSON tombstone alongside the WAV
        // (see KitBuilder.moveToBin) - both are real files under `.bin/`
        // and emptyBin() counts every file it deletes, sidecar included.
        assertEquals(2, m.emptyBin())
    }

    @Test
    fun `clear()-ed pad is fully restored via restoreFromBin - not just its audio`() {
        val dir = File(temp, "BinTombstone")
        val m = KitBuilderModel.create("BinTombstone", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.update(1) { it.copy(level = 0.5f, pan = 0.3f) }
        val original = m.pad(1)!!
        m.save()

        m.clear(1)
        assertNull(m.pad(1))
        val entry = m.binContents().single()
        assertEquals(original.sampleFile, entry.originalName)
        assertTrue(entry.padSnapshot != null, "a simple (unlayered, unchained) pad's tombstone rides along with its WAV")
        assertEquals(1, entry.padSnapshot!!.slot)
        assertEquals(DrumClass.KICK, entry.padSnapshot!!.drumClass)
        assertEquals(0.5f, entry.padSnapshot!!.level)
        assertEquals(0.3f, entry.padSnapshot!!.pan)

        val restored = m.restoreFromBin(entry)
        assertTrue(restored != null && restored.isFile, "the WAV came back")

        val pad = m.pad(1)
        assertTrue(pad != null, "the pad itself came back, not just the file")
        assertEquals(DrumClass.KICK, pad!!.drumClass)
        assertEquals(original.displayName, pad.displayName)
        assertEquals(0.5f, pad.level)
        assertEquals(0.3f, pad.pan)

        assertTrue(m.binContents().isEmpty(), "the bin entry is consumed")
        val binFiles = File(dir, KitBuilderModel.BIN_DIR).listFiles()?.toList() ?: emptyList()
        assertTrue(binFiles.none { it.name.endsWith(".json") }, "the paired tombstone is consumed too, not left as litter")
    }

    @Test
    fun `restoreFromBin never clobbers a pad that has since taken the old pad's slot`() {
        val dir = File(temp, "BinSlotGuard")
        val m = KitBuilderModel.create("BinSlotGuard", dir)
        val oldPad = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.clear(1)
        val entry = m.binContents().single()
        assertTrue(entry.padSnapshot != null)

        // A genuinely different new pad takes the same slot before anyone
        // acts on the old bin entry.
        val newPad = m.assign(1, DrumSynth.snare(), DrumClass.SNARE)

        val restored = m.restoreFromBin(entry)

        // The slot-occupancy guard's whole job: the new pad is untouched.
        assertEquals(DrumClass.SNARE, m.pad(1)?.drumClass)
        assertEquals(newPad.sampleFile, m.pad(1)?.sampleFile)
        assertEquals(1, m.kit.pads.count { it.slot == 1 }, "still exactly one pad on slot 1")

        // The file itself: this branch keeps restoreFromBin's pre-existing,
        // always-was behaviour (the copy-back runs before the pad-reinstate
        // guard is even checked - same order the assign()-replaced case
        // needs to stay correct too). With nowhere left to attach it - a
        // different pad already owns the slot - the old kick's bytes land
        // back on disk under their own name but ownerless, exactly the
        // pre-fix behaviour for every bin restore. Not a regression this
        // fix introduces; the fix's whole point is that a *still-empty*
        // slot no longer ends up this way.
        assertTrue(restored != null && restored.isFile, "the WAV still comes back onto disk")
        assertEquals(oldPad.sampleFile, restored!!.name)
        assertFalse(m.kit.pads.any { it.sampleFile == oldPad.sampleFile }, "…but nothing in the live kit references it")
    }

    @Test
    fun `a velocity-layered pad's eject is marked but never reinstated - reconstructing a multi-file pad is out of scope`() {
        val dir = File(temp, "BinLayered")
        val m = KitBuilderModel.create("BinLayered", dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.addGhostLayers(1)
        assertTrue(m.pad(1)!!.velocityLayers.isNotEmpty())

        m.clear(1)
        val bin = m.binContents()
        assertTrue(bin.size >= 2, "every file the layered pad referenced was binned")
        assertTrue(bin.all { it.padSnapshot == null }, "a layered pad's bin entries carry no pad for BACK to reinstate")
        assertTrue(
            bin.all { File(it.file.parentFile, "${it.file.name}.json").isFile },
            "but each is marked as an eject record, so a new pad on its name never keeps it (binHolds)",
        )
        m.restoreFromBin(bin.first())
        assertNull(m.pad(1), "BACK brings the audio back, not the pad")
    }

    @Test
    fun `purgeBin removes a purged entry's tombstone too - no orphaned json litter`() {
        val dir = File(temp, "BinPurgeTombstone")
        val m = KitBuilderModel.create("BinPurgeTombstone", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.clear(1)
        val entry = m.binContents().single()
        assertTrue(entry.padSnapshot != null)

        val binDir = File(dir, KitBuilderModel.BIN_DIR)
        assertEquals(2, binDir.listFiles()?.size, "the WAV and its paired tombstone")

        assertEquals(
            1,
            m.purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000),
            "one entry purged",
        )
        assertEquals(0, m.binContents().size)
        assertEquals(0, binDir.listFiles()?.size ?: 0, "the tombstone was purged along with its WAV, not left behind")
    }

    // ---- the bin keeps what a pad needs (docs/superpowers/specs/2026-10-31-bin-keeps-what-a-pad-needs-design.md) ----

    private val dayMs = 24L * 60 * 60 * 1000

    /**
     * The launch sweep as `App.kt` runs it (a fresh open of the saved kit), with every entry already
     * old enough to go: whatever is left afterwards is what the bin keeps, not what is too young.
     */
    private fun sweepEverythingOld(dir: File): Int =
        KitBuilderModel.open(dir).purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000)

    /** Re-stamps one bin entry, tombstone and all, for a test where two entries need different ages. */
    /**
     * The kit in [dir] as a build from before 2026-11-01 left it, when a new pad could take a name
     * the bin still held: each slot in [names] is moved onto that name, its file with it. Saved and
     * reopened, as an old kit arrives. `nextStem` no longer hands such names out, but bins and kits
     * from before still hold them, and [KitBuilderModel.binHolds]' tombstone guard is for those.
     */
    private fun fromBeforeNamesSkippedTheBin(dir: File, names: Map<Int, String>): KitBuilderModel {
        val kit = KitStore.load(dir)
        val moved = kit.pads.map { pad ->
            val name = names[pad.slot] ?: return@map pad
            assertTrue(File(dir, pad.sampleFile).renameTo(File(dir, name)), "test setup: slot ${pad.slot} takes $name")
            pad.copy(sampleFile = name)
        }
        KitStore.save(kit.copy(pads = moved), dir)
        return KitBuilderModel.open(dir)
    }

    @Test
    fun `a new pad never takes a name the bin still holds, and the name comes free once the bin lets it go`() {
        val dir = File(temp, "StemPastBin")
        val m = KitBuilderModel.create("StemPastBin", dir)
        val first = m.assign(1, DrumSynth.kick(), DrumClass.KICK).sampleFile
        m.clear(1)
        val second = m.assign(1, DrumSynth.kick(), DrumClass.KICK).sampleFile
        assertNotEquals(first, second, "the bin still holds the first kick under its name")

        m.remixBankB(seed = 5)
        val twin = m.pad(17)!!.sampleFile
        m.remixBankB(seed = 6)
        assertNotEquals(twin, m.pad(17)!!.sampleFile, "a reroll's new twin passes the old twin's name, which the bin holds")
        assertTrue(m.binContents().any { it.originalName == twin }, "test setup: the old twin's eject record is in the bin")
        assertFailsWith<IllegalArgumentException>("the new twin's NONE finds nothing of the old twin's") { m.untreatPad(17) }

        m.emptyBin()
        m.clear(1)
        assertEquals(first, m.assign(1, DrumSynth.kick(), DrumClass.KICK).sampleFile, "once the bin lets the first kick go, its name is free again")
    }

    private fun restamp(entry: KitBuilderModel.BinEntry, millis: Long) {
        val bin = entry.file.parentFile
        val tombstone = File(bin, "${entry.file.name}.json")
        assertTrue(entry.file.renameTo(File(bin, "${millis}_${entry.originalName}")), "test setup: rename must succeed")
        if (tombstone.isFile) assertTrue(tombstone.renameTo(File(bin, "${millis}_${entry.originalName}.json")), "test setup: rename must succeed")
    }

    /** A small DUST print built in place, so the door needs no tape on a shelf: a hiss bed and a short room. */
    private fun dustPrint(): com.snipsnap.audio.Dust.Print {
        val r = java.util.Random(3)
        val hiss = com.snipsnap.audio.Snip(FloatArray(4_410) { (r.nextGaussian() * 0.01).toFloat() }, 1, 44_100)
        val room = com.snipsnap.audio.Snip(FloatArray(2_205) { (r.nextGaussian() * 0.05 * Math.exp(-it / 500.0)).toFloat() }, 1, 44_100)
        return com.snipsnap.audio.Dust.Print(hiss, room)
    }

    /**
     * The owner's answer, door by door: every treated pad keeps its original past the sweep, and the
     * undo each door already has gives it back. The ninth pad is a chain with its recipe stripped:
     * `Robin.undo` reads only `chain`, so the chain alone has to be enough.
     */
    @Test
    fun `a treated pad's original outlives the sweep, and its own undo gives it back byte for byte`() {
        val dir = File(temp, "BinKeeps")
        val m = KitBuilderModel.create("BinKeeps", dir)
        for (slot in 1..9) m.assign(slot, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        val originals = (1..9).associateWith { File(dir, m.pad(it)!!.sampleFile).readBytes() }

        m.eraPad(1, "tape", 1f)
        m.characterPad(2, "smeared", 0.6f)
        m.smearPad(3, 0.5f)
        m.dustPad(4, 0.6f, "tape.wav", dustPrint())
        m.pedalPad(5, com.snipsnap.synth.PedalBoard(listOf(com.snipsnap.synth.PedalBoard.Pick("driven", 0.7f))))
        Mutate.apply(m, 6, listOf(Mutate.Source("BinKeeps:A01", DrumSynth.kick())), Mutate.Mode.SPLICE)
        m.treatPad(7, "crushed") // an fx-only recipe, what the CLI's `treat --undo` pops
        Robin.apply(m, 8, takes = 2, seed = 1)
        Robin.apply(m, 9, takes = 2, seed = 1)
        m.update(9) { it.copy(recipe = null) }
        m.save()
        val named = mapOf(1 to "era", 2 to "character", 3 to "SMEAR", 4 to "DUST", 5 to "pedals", 6 to "MUTATE", 7 to "fx-only", 8 to "chain", 9 to "bare chain")
        val holds = m.binHolds()
        for ((slot, what) in named) {
            assertEquals(slot, holds.single { it.entry.originalName == m.pad(slot)!!.sampleFile }.keptFor, "the $what pad keeps its original")
        }

        assertEquals(0, sweepEverythingOld(dir), "every entry is a pad's original, and every pad still needs its own")

        for (slot in 1..5) m.unEraPad(slot)
        Mutate.undo(m, 6)
        m.untreatPad(7)
        Robin.undo(m, 8)
        Robin.undo(m, 9)
        for ((slot, what) in named) {
            assertTrue(File(dir, m.pad(slot)!!.sampleFile).readBytes().contentEquals(originals.getValue(slot)), "the $what pad came back byte for byte")
        }
    }

    @Test
    fun `only the newest entry under a pad's file is kept, and the levels under it leave on the clock`() {
        val dir = File(temp, "BinNewest")
        val m = KitBuilderModel.create("BinNewest", dir)
        val pad = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        m.treatPad(2, "crushed") // bins the original
        val crushed = File(dir, pad.sampleFile).readBytes()
        m.treatPad(2, "washed", amount = 0.6f) // bins the crushed take: the newest, the one every undo reads
        m.save()
        val (newer, older) = m.binContents()

        assertEquals(1, sweepEverythingOld(dir), "only the newest entry under a name is what the pad's undo reads; the one under it leaves")
        assertEquals(listOf(newer.file), m.binContents().map { it.file }, "the newest entry stays")
        assertFalse(older.file.exists(), "the older level left")
        m.untreatPad(2)
        assertTrue(File(dir, pad.sampleFile).readBytes().contentEquals(crushed), "the pad's undo still lands")
    }

    @Test
    fun `a pad after NONE keeps nothing, and its leftover takes leave on the clock`() {
        val dir = File(temp, "BinAfterNone")
        val m = KitBuilderModel.create("BinAfterNone", dir)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        m.treatPad(2, "crushed")
        m.treatPad(2, "washed", amount = 0.6f)
        m.unEraPad(2) // NONE: the newest comes back and the recipe comes off; the first original is left over
        m.save()
        assertNull(m.pad(2)!!.recipe, "test setup: the pad is untreated")
        val leftover = m.binHolds().single()
        assertNull(leftover.keptFor, "an untreated pad keeps nothing")

        assertEquals(1, sweepEverythingOld(dir))
        assertFalse(leftover.entry.file.exists(), "the leftover take left on the clock")
    }

    @Test
    fun `a velocity-layered pad keeps one entry per file it plays`() {
        val dir = File(temp, "BinLayers")
        val m = KitBuilderModel.create("BinLayers", dir)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.addGhostLayers(2)
        m.save()
        val files = (listOf(m.pad(2)!!.sampleFile) + m.pad(2)!!.velocityLayers.map { it.sampleFile }).distinct()
        assertTrue(files.size > 1, "the fixture is layered")
        val originals = files.associateWith { File(dir, it).readBytes() }
        m.eraPad(2, "tape", 1f)
        m.save()

        assertEquals(0, sweepEverythingOld(dir), "every layer's original stays")
        val holds = m.binHolds()
        assertEquals(files.sorted(), holds.map { it.entry.originalName }.sorted(), "one entry per file, no more")
        assertTrue(holds.all { it.keptFor == 2 && it.purgeAtMillis == null }, "every one kept for A02")
        m.unEraPad(2)
        for ((f, bytes) in originals) assertTrue(File(dir, f).readBytes().contentEquals(bytes), "$f back byte for byte")
    }

    /**
     * An eject writes a tombstone holding the whole pad, recipe included, and BACK brings that pad
     * back; its NONE then needs the original under the eject record. So the original lives exactly as
     * long as the tombstone, past its own 30 days, and goes in the same sweep.
     */
    @Test
    fun `an ejected treated pad's original is kept while its tombstone stays, leaves with it, and NONE works after BACK`() {
        val dir = File(temp, "BinEjected")
        val m = KitBuilderModel.create("BinEjected", dir)
        val kick = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        val snare = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        val original = File(dir, kick.sampleFile).readBytes()
        for (slot in 1..2) {
            m.eraPad(slot, "tape", 1f) // the original goes in with no tombstone: a treatment bins without one
            m.clear(slot) // the eject record goes in with a tombstone holding the era'd pad
        }
        m.save()
        val now = System.currentTimeMillis()
        for (name in listOf(kick.sampleFile, snare.sampleFile)) {
            val treatedFrom = m.binContents().single { it.originalName == name && it.padSnapshot == null }
            restamp(treatedFrom, now - 31 * dayMs) // the originals went in 31 days ago, the ejects a moment ago
        }
        val snareEject = m.binContents().single { it.originalName == snare.sampleFile && it.padSnapshot != null }

        assertEquals(0, KitBuilderModel.open(dir).purgeBin(nowMillis = now), "the originals outlive their 30 days while their pads can still come back")
        val snareOriginal = m.binHolds().single { it.entry.originalName == snare.sampleFile && it.entry.padSnapshot == null }
        assertNull(snareOriginal.keptFor, "no live pad keeps it; its tombstone does")
        assertEquals(snareEject.binnedAtMillis + 30 * dayMs, snareOriginal.purgeAtMillis, "it leaves with the tombstone")

        // BACK brings the era'd pad back with its recipe, and its NONE needs the original.
        assertTrue(m.restoreFromBin(m.binContents().single { it.originalName == kick.sampleFile && it.padSnapshot != null }) != null)
        assertTrue(PadSheet.read(m.pad(1)!!.recipe) != null, "test setup: the pad came back era'd")
        m.unEraPad(1)
        assertTrue(File(dir, kick.sampleFile).readBytes().contentEquals(original), "NONE after BACK gives the original back")

        // The first sweep after the snare's tombstone runs out takes both, the tombstone with them.
        assertEquals(2, KitBuilderModel.open(dir).purgeBin(nowMillis = snareEject.binnedAtMillis + 30 * dayMs + 1))
        assertEquals(0, File(dir, KitBuilderModel.BIN_DIR).listFiles()?.size ?: 0, "the original left in the same sweep as its tombstone")
    }

    /**
     * EVIL TWINS rerolled in a kit from before 2026-11-01: the old twins are ejected with tombstones,
     * and the new twins took their file names and carry recipes the rule counts. Only an eject writes a tombstone, so an entry
     * with one is a record of an ejected pad, never a live pad's original, and a new pad on the same
     * name must not pin a stranger's sound forever. A torn tombstone still says so: the guard asks
     * whether the file is there, not whether it parses.
     */
    @Test
    fun `a new pad reusing an ejected pad's file name does not keep the ejected pad's entry`() {
        val dir = File(temp, "BinReroll")
        val m = KitBuilderModel.create("BinReroll", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.remixBankB(seed = 5)
        val old = m.kit.pads.filter { it.slot > 16 }.associate { it.slot to it.sampleFile }
        val names = old.toSortedMap().values.toList()
        m.remixBankB(seed = 6)
        m.save()
        val reused = fromBeforeNamesSkippedTheBin(dir, old)
        val twins = reused.kit.pads.filter { it.slot > 16 }.sortedBy { it.slot }
        assertEquals(names, twins.map { it.sampleFile }, "test setup: the new twins play the old twins' names")
        assertTrue(twins.all { KitBuilderModel.dependsOnBin(it) }, "test setup: the new twins carry recipes the rule counts")
        val ejected = reused.binContents()
        assertEquals(names.sorted(), ejected.map { it.originalName }.sorted())
        assertTrue(ejected.all { it.padSnapshot != null }, "test setup: every entry is an eject record")
        File(ejected.first().file.parentFile, "${ejected.first().file.name}.json").writeText("{ torn")

        assertTrue(reused.binHolds().all { it.keptFor == null }, "a stranger's eject record is never kept for a new pad on its name")
        assertEquals(names.size, sweepEverythingOld(dir))
        assertEquals(0, File(dir, KitBuilderModel.BIN_DIR).listFiles()?.size ?: 0, "the eject records left with their tombstones")
    }

    /**
     * The guard comes after "newest": a live pad whose newest entry is an eject record keeps
     * nothing, never the entry underneath it. Here the first twin was era'd, so its own original
     * sits under its eject record, and the reroll's new twin plays the same name (a kit from before
     * 2026-11-01, when a reroll could hand it out).
     */
    @Test
    fun `a new pad whose newest entry is an eject record keeps nothing, not the entry under it`() {
        val dir = File(temp, "BinUnderEject")
        val m = KitBuilderModel.create("BinUnderEject", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.remixBankB(seed = 5)
        val name = m.pad(17)!!.sampleFile
        m.eraPad(17, "tape", 1f) // the first twin's original goes in with no tombstone
        m.remixBankB(seed = 6) // its eject record goes in on top
        m.save()
        val reused = fromBeforeNamesSkippedTheBin(dir, mapOf(17 to name)) // and the new twin plays the name
        assertTrue(KitBuilderModel.dependsOnBin(reused.pad(17)!!), "test setup: the new twin counts")
        val (eject, original) = reused.binContents().filter { it.originalName == name }
        assertTrue(eject.padSnapshot != null && original.padSnapshot == null, "test setup: the newest is the eject record")

        val hold = reused.binHolds().single { it.entry.file == original.file }
        assertNull(hold.keptFor, "the new twin must not pin the old twin's original")
        assertEquals(eject.binnedAtMillis + 30 * dayMs, hold.purgeAtMillis, "it rides its own eject record's tombstone instead")
    }

    /**
     * Clause (c) keeps the entry BACK-then-NONE reads: the newest under the tombstone's name that
     * went in before it. When that is an earlier eject record (a reroll's), the tombstone keeps
     * nothing, and an eject record is never stretched by a later one either. The first twin's own
     * original then leaves with the first twin's eject record, and the second twin's NONE refuses
     * rather than playing the first twin's sound. The second twin plays the first's name, as a kit
     * from before 2026-11-01 could.
     */
    @Test
    fun `a tombstone never reaches past an earlier eject record, and never stretches it`() {
        val dir = File(temp, "BinPastEject")
        val m = KitBuilderModel.create("BinPastEject", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.remixBankB(seed = 5)
        val name = m.pad(17)!!.sampleFile
        m.treatPad(17, "crushed") // the first twin's own original, no tombstone
        m.remixBankB(seed = 6) // the first twin's eject record
        m.save()
        val reused = fromBeforeNamesSkippedTheBin(dir, mapOf(17 to name)) // the second twin plays the name
        reused.clear(17) // the second twin's eject record, its slot left empty for BACK
        reused.save()
        val (secondEject, firstEject, firstOriginal) = reused.binContents()
        assertTrue(secondEject.padSnapshot != null && firstEject.padSnapshot != null && firstOriginal.padSnapshot == null, "test setup")
        val now = System.currentTimeMillis()
        restamp(secondEject, now - dayMs)
        restamp(firstEject, now - 40 * dayMs)
        restamp(firstOriginal, now - 50 * dayMs)
        val holds = reused.binHolds().associateBy { it.entry.binnedAtMillis }
        assertEquals(now - 10 * dayMs, holds.getValue(now - 40 * dayMs).purgeAtMillis, "the first eject record keeps its own clock")
        assertEquals(now - 10 * dayMs, holds.getValue(now - 50 * dayMs).purgeAtMillis, "the first original rides the first eject record, not the second")

        assertEquals(2, KitBuilderModel.open(dir).purgeBin(nowMillis = now), "both go in the same sweep")
        val left = reused.binContents().single()
        assertEquals(now - dayMs, left.binnedAtMillis, "only the second eject record is left")
        reused.restoreFromBin(left) // BACK: the second twin, with its copied recipe
        assertTrue(KitBuilderModel.dependsOnBin(reused.pad(17)!!), "test setup: BACK brought the second twin back")
        assertFailsWith<IllegalArgumentException>("NONE refuses rather than playing a stranger's sound") { reused.untreatPad(17) }
    }

    /** An untreated pad's tombstone needs nothing from the bin, so the leftover under it keeps its own 30 days. */
    @Test
    fun `an untreated pad's tombstone does not stretch a leftover take under it`() {
        val dir = File(temp, "BinUntreatedEject")
        val m = KitBuilderModel.create("BinUntreatedEject", dir)
        val snare = m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        m.treatPad(2, "crushed")
        m.treatPad(2, "washed", amount = 0.6f)
        m.unEraPad(2) // NONE: the first original is left over
        assertNull(m.pad(2)!!.recipe, "test setup: untreated")
        m.clear(2)
        m.save()
        val leftover = m.binContents().single { it.originalName == snare.sampleFile && it.padSnapshot == null }

        val hold = m.binHolds().single { it.entry.file == leftover.file }
        assertNull(hold.keptFor)
        assertEquals(leftover.binnedAtMillis + 30 * dayMs, hold.purgeAtMillis, "the tombstone of a pad that needs nothing keeps nothing")
    }

    /**
     * A GHOSTS-layered twin gets no pad snapshot BACK could reinstate, but its eject is still
     * marked, so the next twin on the same name (a kit from before 2026-11-01) keeps nothing of it
     * and its NONE refuses once the sweep has taken the old twin's audio, rather than playing it.
     */
    @Test
    fun `a layered pad's eject is marked, so a new pad on its name keeps nothing of it`() {
        val dir = File(temp, "BinLayeredReroll")
        val m = KitBuilderModel.create("BinLayeredReroll", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.remixBankB(seed = 5)
        val name = m.pad(17)!!.sampleFile
        m.addGhostLayers(17) // a twin keeps its stamp under GHOSTS, so the reroll still takes it
        m.remixBankB(seed = 6)
        m.save()
        val reused = fromBeforeNamesSkippedTheBin(dir, mapOf(17 to name))
        assertTrue(KitBuilderModel.dependsOnBin(reused.pad(17)!!), "test setup: the new twin counts")
        val ejected = reused.binContents()
        assertTrue(ejected.size >= 2 && ejected.all { it.padSnapshot == null }, "test setup: the layered twin's files, no pad to reinstate")

        assertTrue(reused.binHolds().all { it.keptFor == null }, "the new twin keeps nothing of the old one")
        assertEquals(ejected.size, sweepEverythingOld(dir))
        assertFailsWith<IllegalArgumentException> { reused.unEraPad(17) }
    }

    /**
     * DE-SAMPLE leaves a plain patch recipe, the shape a synth send leaves, but `snipsnap desample
     * --undo` reads the bin for the capture, so the `desampled` stamp makes the pad count.
     */
    @Test
    fun `a de-sampled pad keeps its capture past the sweep, and desample --undo gives it back`() {
        val dir = File(temp, "BinDesampled")
        val m = KitBuilderModel.create("BinDesampled", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.save()
        val capture = File(dir, m.pad(1)!!.sampleFile).readBytes()
        m.desamplePad(1, evenIfFar = true)
        m.save()
        assertEquals(1, m.binHolds().single().keptFor, "the capture is kept for A01")

        assertEquals(0, sweepEverythingOld(dir))
        m.untreatPad(1) // what `snipsnap desample --undo` runs
        assertTrue(File(dir, m.pad(1)!!.sampleFile).readBytes().contentEquals(capture), "the capture came back byte for byte")
    }

    @Test
    fun `daysLeft rounds up, reads 0 exactly when the sweep would take the entry, and is null for a kept entry`() {
        val dir = File(temp, "BinDaysLeft")
        val m = KitBuilderModel.create("BinDaysLeft", dir)
        val kick = m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.eraPad(2, "tape", 1f)
        m.clear(1)
        m.save()
        val holds = m.binHolds()

        val ejected = holds.single { it.entry.originalName == kick.sampleFile }
        val at = ejected.entry.binnedAtMillis
        assertNull(ejected.keptFor)
        assertEquals(at + 30 * dayMs, ejected.purgeAtMillis)
        assertEquals(30, ejected.daysLeft(at), "a fresh entry reads the bin's own days")
        assertEquals(2, ejected.daysLeft(at + 28 * dayMs + dayMs / 2), "a day and a half left reads 2")
        assertEquals(1, ejected.daysLeft(at + 29 * dayMs), "one whole day left reads 1")
        assertEquals(1, ejected.daysLeft(at + 30 * dayMs), "the last moment the sweep still leaves it reads 1, not 0")
        assertEquals(0, KitBuilderModel.open(dir).purgeBin(nowMillis = at + 30 * dayMs), "and the sweep does leave it then")
        assertEquals(0, ejected.daysLeft(at + 30 * dayMs + 1), "0 the moment the sweep would take it")
        assertEquals(0, ejected.daysLeft(at + 31 * dayMs), "never negative")
        val forged = KitBuilderModel.BinHold(ejected.entry, keptFor = null, purgeAtMillis = Long.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, forged.daysLeft(at), "a time the sweep never reaches reads as far off, not as a wrapped count")

        val kept = holds.single { it.entry.originalName == m.pad(2)!!.sampleFile }
        assertEquals(2, kept.keptFor)
        assertNull(kept.purgeAtMillis)
        assertNull(kept.daysLeft(at + 365 * dayMs), "a kept entry has no countdown")
        assertEquals("KEPT FOR A02", Copy.binKeptFor("A02"), "what THE BIN draws in place of its day count")

        assertEquals(1, KitBuilderModel.open(dir).purgeBin(nowMillis = at + 30 * dayMs + 1), "the eject goes; the kept original stays")
        assertEquals(listOf(kept.entry.file), m.binContents().map { it.file })
    }

    @Test
    fun `EMPTY THE BIN NOW still empties what a pad keeps`() {
        val dir = File(temp, "BinEmptyKept")
        val m = KitBuilderModel.create("BinEmptyKept", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.eraPad(1, "tape", 1f)
        m.save()
        assertEquals(1, m.binHolds().single().keptFor, "test setup: the original is kept")

        assertEquals(1, m.emptyBin(), "a choice made on purpose empties everything")
        assertTrue(m.binContents().isEmpty())
        assertFailsWith<IllegalArgumentException> { m.unEraPad(1) }
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
    fun `bank view`() {
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

    // ---------- RE-TRIM: BACK ONTO (docs/RETRIM.md §4) ----------

    @Test
    fun `backOnto replaces the audio and carries the pad's metadata, drops the treatment, bins the old file`() {
        val dir = File(temp, "BackOnto")
        val m = KitBuilderModel.create("BackOnto", dir)
        val first = m.assign(2, DrumSynth.snare(), DrumClass.SNARE, "BASS 5", source = Retrim.tag("snip_1_BASS.wav", 0, 44_100))
        m.update(2) {
            it.copy(
                level = 0.5f, pan = 0.25f, tuneCoarse = -2, tuneFine = 30, muteGroup = 3, oneShot = false,
                attack = 0.1f, decay = 0.4f, cutoff = 0.7f, resonance = 0.2f,
                recipe = com.snipsnap.json.JsonValue.Obj(
                    mapOf(
                        "treatment" to com.snipsnap.json.JsonValue.Str("crush"),
                        "amount" to com.snipsnap.json.JsonValue.Num(1.0),
                    ),
                ),
                source = it.source + mapOf("mutatedWith" to "X", "capturedAtMillis" to "1"),
            )
        }
        m.save()
        val oldFile = first.sampleFile
        val takesBefore = m.takes().size

        val cut = Retrim.tag("snip_1_BASS.wav", 52_920, 71_442)
        val recut = m.backOnto(2, DrumSynth.kick(), cut)
        m.save()

        assertEquals("BASS 5", recut.displayName)
        assertEquals(DrumClass.SNARE, recut.drumClass)
        assertEquals(0.5f, recut.level)
        assertEquals(0.25f, recut.pan)
        assertEquals(-2, recut.tuneCoarse)
        assertEquals(30, recut.tuneFine)
        assertEquals(3, recut.muteGroup)
        assertFalse(recut.oneShot)
        assertEquals(0.1f, recut.attack)
        assertEquals(0.4f, recut.decay)
        assertEquals(0.7f, recut.cutoff)
        assertEquals(0.2f, recut.resonance)
        assertNull(recut.recipe, "the treatment was baked into the old file and stays with it")
        assertEquals("52920", recut.source[Retrim.IN_KEY])
        assertEquals("71442", recut.source[Retrim.OUT_KEY])
        assertEquals("1", recut.source["capturedAtMillis"], "the rest of the provenance stays")
        assertNull(recut.source["mutatedWith"], "a stamp about the old audio's processing does not")

        assertTrue(recut.sampleFile != oldFile || !File(dir, oldFile).readBytes().contentEquals(File(dir, first.sampleFile).readBytes()))
        assertTrue(m.binContents().any { it.originalName == oldFile }, "the old file waits in the bin")
        assertEquals(takesBefore + 1, m.takes().size, "the save archived a take, so UNDO is the TAKES room")
        val reopened = KitBuilderModel.open(dir).pad(2)!!
        assertEquals(recut.copy(), reopened, "everything round-trips through kit.json")
    }

    @Test
    fun `backOnto refuses a velocity-layered pad and leaves it untouched - GHOSTS or a stack ride on the old file`() {
        val dir = File(temp, "BackOntoGhosts")
        val m = KitBuilderModel.create("BackOntoGhosts", dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        val ghosted = m.addGhostLayers(1, softZones = 2)
        m.save()

        assertFailsWith<IllegalArgumentException> { m.backOnto(1, DrumSynth.kick(), Retrim.tag("snip_1_X.wav", 0, 100)) }
        assertEquals(ghosted, m.pad(1), "a refusal changes nothing")
        assertTrue(ghosted.velocityLayers.all { File(dir, it.sampleFile).isFile })
        assertTrue(m.binContents().isEmpty(), "nothing binned on a refusal")
    }

    @Test
    fun `backOnto carries humanize like the rest of the pad's metadata`() {
        val dir = File(temp, "BackOntoHumanize")
        val m = KitBuilderModel.create("BackOntoHumanize", dir)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE, source = Retrim.tag("snip_1_X.wav", 0, 100))
        m.update(1) { it.copy(humanize = 0.35f) }
        val recut = m.backOnto(1, DrumSynth.kick(), Retrim.tag("snip_1_X.wav", 10, 90))
        assertEquals(0.35f, recut.humanize)
    }

    @Test
    fun `a remix never wipes a pad the user put on bank B, and an unstamped twin still reads as a twin`() {
        val dir = File(temp, "Own")
        val m = KitBuilderModel.create("Own", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.remixBankB(seed = 1)
        // A pad captured onto bank B by hand.
        m.assign(20, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        assertEquals(listOf(20), m.ownPadsOnBankB())
        val before = m.kit
        val refused = assertFailsWith<IllegalStateException> { m.remixBankB(seed = 2) }
        assertTrue("B04" in refused.message!!, refused.message)
        assertEquals(before, m.kit, "refused means untouched")
        m.clear(20)
        assertEquals(listOf(17, 18), m.remixBankB(seed = 2), "with the own pad gone the remix runs again")

        // A twin dealt before the stamp existed: name + " B", a recipe, sixteen up from a real pad.
        val legacy = m.pad(17)!!.copy(source = m.pad(17)!!.source - KitBuilderModel.TWIN_OF)
        assertTrue(m.isTwin(legacy), "the legacy shape still reads as a twin")
        assertFalse(m.isTwin(legacy.copy(displayName = "MY HAT")), "a renamed pad is the user's")
        assertFalse(m.isTwin(legacy.copy(recipe = null)), "no recipe, not a twin")
    }

    @Test
    fun `a chop lands onto an empty bank of an existing kit, first sixteen, through assign`() {
        val dir = File(temp, "Landed")
        val m = KitBuilderModel.create("Landed", dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        val rate = 44_100
        val total = FloatArray(rate * 10)
        val hits = listOf(DrumSynth.kick(), DrumSynth.snare(), DrumSynth.closedHat(), DrumSynth.openHat())
        for (n in 0 until 18) {
            val hit = hits[n % hits.size]
            val at = n * (rate / 2)
            for (i in hit.samples.indices) if (at + i < total.size) total[at + i] += hit.samples[i] * 0.8f
        }
        val send = ChopReviewModel.chop(com.snipsnap.audio.Snip(total, 1, rate), ChopReviewModel.ChopMode.ByHits(maxSlices = 18)).sendToGrid()
        assertTrue(send.arranged.size > 16, "wider than a bank: ${send.arranged.size}")

        assertFailsWith<IllegalArgumentException> { m.landArranged(send.arranged, bank = 0) }
        val landed = m.landArranged(send.arranged, bank = 1)
        assertTrue(landed.isNotEmpty() && landed.all { it in 17..32 }, "$landed")
        assertEquals(landed.size, m.kit.pads.count { it.slot in 17..32 })
        assertEquals(1, m.kit.pads.count { it.slot in 1..16 }, "bank A untouched")
        val first = m.pad(17)!!
        assertEquals("chop", first.source["origin"], "provenance rides through assign")
        assertTrue(File(dir, first.sampleFile).isFile)
        assertEquals(listOf(17), m.ownPadsOnBankB().take(1), "landed pads are the user's own, not twins")
        m.save()
        assertEquals(m.kit, KitStore.load(dir))
        assertFailsWith<IllegalArgumentException> { m.landArranged(send.arranged, bank = 1) }
    }
}
