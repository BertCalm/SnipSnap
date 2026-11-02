package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Scale
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.BoardRecipe
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardSheetTest {
    private val temp = java.nio.file.Files.createTempDirectory("board-sheet").toFile()
    @AfterTest fun cleanup() { temp.deleteRecursively() }

    private fun hit(gain: Float = .6f): Snip = Snip(FloatArray(4410) { i ->
        (gain * sin(2.0 * PI * 220 * i / 44100.0) * exp(-i / 1400.0)).toFloat()
    }, 1, 44100)

    private fun model(name: String = "Board", recipe: JsonValue.Obj? = null, layered: Boolean = false, shared: Boolean = false): KitBuilderModel {
        val dir = File(temp, name).apply { mkdirs() }
        WavWriter.write(File(dir, "main.wav"), hit())
        if (layered) WavWriter.write(File(dir, "soft.wav"), hit(.2f))
        val layers = if (layered) listOf(KitLayer("soft.wav", 0, 63), KitLayer("main.wav", 64, 127)) else emptyList()
        val pad = KitPad(1, "main.wav", recipe = recipe, velocityLayers = layers)
        KitStore.save(Kit(name, if (shared) listOf(pad, pad.copy(slot = 2)) else listOf(pad)), dir)
        return KitBuilderModel.open(dir)
    }

    private fun bytes(model: KitBuilderModel, name: String = model.pad(1)!!.sampleFile): ByteArray = File(model.kitDir, name).readBytes()
    private fun encoded(snip: Snip): ByteArray = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24) }.toByteArray()
    private fun allFiles(dir: File): Map<String, List<Byte>> = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }
    private fun originalRecipe(): JsonValue.Obj = Json.parse("""{"engine":"VELVET","voice":"BASS","macros":{"TUNE":0.5},"future":{"unknown":[1,"keep this",null]}}""") as JsonValue.Obj

    @Test fun `soft hit changes refuse before touching a kept board and removal still works`() {
        for (layered in listOf(false, true)) {
            val m = model(name = "Layers-$layered", recipe = originalRecipe(), layered = layered)
            val original = m.pad(1)!!
            val dry = bytes(m)
            val kept = BoardSheet.apply(m, 1, .7f)
            val before = allFiles(m.kitDir)
            val error = assertFailsWith<IllegalArgumentException> {
                if (layered) m.clearGhostLayers(1) else m.addGhostLayers(1)
            }
            assertEquals(Copy.WIDE_REMOVE_BEFORE_SOFT_HITS, error.message)
            assertEquals(kept, m.pad(1))
            assertEquals(before, allFiles(m.kitDir))
            assertTrue(BoardSheet.canUndo(m, 1))
            assertEquals(original, BoardSheet.remove(m, 1))
            assertTrue(dry.contentEquals(bytes(m)))
            assertEquals(kept, BoardSheet.undo(m, 1))
        }
    }

    @Test fun `stacking takes refuses before changing a kept board or reading an old take`() {
        val m = model()
        val kept = BoardSheet.apply(m, 1, .7f)
        val before = allFiles(m.kitDir)
        val oldTake = KitBuilderModel.BinEntry("main.wav", 0L, File(m.kitDir, "missing-old-take.wav"))
        val error = assertFailsWith<IllegalArgumentException> { m.stackTakes(1, listOf(oldTake)) }
        assertEquals(Copy.WIDE_REMOVE_BEFORE_STACK, error.message)
        assertEquals(before, allFiles(m.kitDir))
        assertEquals(kept, m.pad(1))
        assertNull(BoardSheet.remove(m, 1).recipe)
    }

    @Test fun `spread refuses a stored board before overwriting any destination pad`() {
        val m = model()
        m.assign(17, hit(.2f))
        val kept = BoardSheet.apply(m, 1, .7f)
        val before = allFiles(m.kitDir)
        val kit = m.kit
        val plan = Spread.plan(kit, 60f, Spread.Options(60, Scale.MAJOR, padCount = 4, bank = 1, replaceFull = true))
        val error = assertFailsWith<IllegalArgumentException> {
            Spread.apply(m, WavReader.read(File(m.kitDir, kept.sampleFile)), plan,
                Spread.Sound("Wide", kept.drumClass, "#19645B", recipe = kept.recipe, like = kept))
        }
        assertEquals(Copy.WIDE_REMOVE_BEFORE_SPREAD, error.message)
        assertEquals(kit, m.kit)
        assertEquals(before, allFiles(m.kitDir))
        assertNull(BoardSheet.remove(m, 1).recipe)
    }

    @Test fun `an unlabelled audio replacement becomes a fresh source instead of retaining stale WIDE`() {
        val m = model()
        BoardSheet.apply(m, 1, .7f)
        val replaced = m.replaceAudio(1, null) { hit(.2f) }
        val replacement = bytes(m)
        assertNull(replaced.recipe)
        assertFalse(BoardSheet.canUndo(m, 1))
        BoardSheet.apply(m, 1, .35f)
        assertNull(BoardSheet.remove(m, 1).recipe)
        assertTrue(replacement.contentEquals(bytes(m)), "a new board must restore the replacement, not the previous sound")

        val legacy = model(name = "Legacy", recipe = originalRecipe())
        assertEquals(originalRecipe(), legacy.replaceAudio(1, null) { hit(.2f) }.recipe,
            "non-board callers retain preserve-on-null behavior")
    }

    @Test fun `captured source survives bin purge and removal preserves current pad controls`() {
        val m = model()
        val original = bytes(m)
        val first = BoardSheet.apply(m, 1, .7f)
        assertEquals(.7f, BoardSheet.read(first.recipe)!!.amount)
        assertNotEquals("main.wav", first.sampleFile)
        assertTrue(original.contentEquals(bytes(m, "main.wav")))
        File(m.kitDir, "main.wav").delete()
        m.emptyBin()
        m.update(1) { it.copy(level = .25f, pan = .8f, attack = .3f, cutoff = .4f, displayName = "My pad") }
        val removed = BoardSheet.remove(m, 1)
        assertEquals("main.wav", removed.sampleFile)
        assertNull(removed.recipe)
        assertTrue(original.contentEquals(bytes(m)))
        assertEquals(.25f, removed.level)
        assertEquals(.8f, removed.pan)
        assertEquals(.3f, removed.attack)
        assertEquals(.4f, removed.cutoff)
        assertEquals("My pad", removed.displayName)
        assertTrue(BoardSheet.canUndo(m, 1), "REMOVE itself can be undone")
        assertEquals(first.recipe, BoardSheet.undo(m, 1).recipe)
        assertFalse(BoardSheet.canUndo(m, 1), "undo is deliberately one step")
    }

    /**
     * The board's own store keeps the era'd source; the kit bin has to keep what that source's NONE
     * reads, under every file the source plays: its velocity layers as well as its main file.
     */
    @Test fun `a board over a treated pad keeps the source's original in the kit bin, and after REMOVE, NONE gives it back`() {
        for (layered in listOf(false, true)) {
            val m = model("BinKept-$layered", layered = layered)
            val files = if (layered) listOf("main.wav", "soft.wav") else listOf("main.wav")
            val originals = files.map { bytes(m, it) }
            m.eraPad(1, "tape", 1f)
            BoardSheet.apply(m, 1, .7f)
            m.save()

            val swept = KitBuilderModel.open(m.kitDir).purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000)
            assertEquals(0, swept, "layered=$layered: the sweep took an original the board's era'd source needs")
            assertEquals(files.sorted(), m.binHolds().map { it.entry.originalName }.sorted(), "layered=$layered: one entry per file the source plays")
            assertTrue(m.binHolds().all { it.keptFor == 1 }, "layered=$layered: every one kept for the printed pad's slot")
            // THE BIN asks the pad under the print whether it can undo itself, and it can: the
            // printed pad's NONE removes the board, then NONE again reads these entries. So no BACK.
            val binned = m.binHolds().map { it.entry.originalName }.toSet()
            val under = m.unprinted(m.pad(1)!!)
            for (f in files) {
                assertTrue(PadSheet.hasOwnWayBack(under, f, binned), "layered=$layered: under the board, NONE gives $f back")
                assertFalse(PadSheet.hasOwnWayBack(m.pad(1)!!, f, binned), "layered=$layered: the print itself plays none of $f")
            }
            val removed = BoardSheet.remove(m, 1)
            assertEquals("main.wav", removed.sampleFile)
            assertTrue(PadSheet.read(removed.recipe) != null, "test setup: REMOVE brought the era back")
            m.unEraPad(1)
            for ((f, b) in files.zip(originals)) assertTrue(b.contentEquals(bytes(m, f)), "layered=$layered: NONE after REMOVE gives $f back")
        }
    }

    /**
     * THE BIN shows no BACK on a row whose file a board's saved source names (the owner's call,
     * 2026-11-01): REMOVE restores those files and first checks each by hash, so BACK there would
     * leave the board stuck on for good. The names count while the board can come back, live or in
     * its tombstone, and leave once it cannot.
     */
    @Test fun `the files under a board are named while the board can come back, and BACK there would keep it on for good`() {
        for (layered in listOf(false, true)) {
            val m = model("UnderBoard-$layered", layered = layered)
            val files = if (layered) setOf("main.wav", "soft.wav") else setOf("main.wav")
            m.eraPad(1, "tape", 1f)
            assertEquals(emptySet(), m.filesUnderPrints(), "layered=$layered: no print yet")
            BoardSheet.apply(m, 1, .7f)
            assertEquals(files, m.filesUnderPrints(), "layered=$layered: every file the board's source plays")
            BoardSheet.remove(m, 1)
            assertEquals(emptySet(), m.filesUnderPrints(), "layered=$layered: after REMOVE the names are free, and BACK comes back")
        }

        val ejected = model("UnderEjectedBoard")
        ejected.eraPad(1, "tape", 1f)
        BoardSheet.apply(ejected, 1, .7f)
        ejected.clear(1)
        assertEquals(setOf("main.wav"), ejected.filesUnderPrints(), "an ejected board BACK can bring back still counts")
        ejected.emptyBin()
        assertEquals(emptySet(), ejected.filesUnderPrints(), "with its tombstone gone, nothing can bring it back")

        // binHolds' one exception holds here too: an ejected board past its tombstone's days whose
        // saved source will not read is skipped, so it cannot drop THE BIN to its fallback.
        val overdue = model("UnderOverdueBoard")
        overdue.eraPad(1, "tape", 1f)
        val source = (BoardSheet.apply(overdue, 1, .7f).recipe!!.entries.getValue("source") as JsonValue.Str).value
        overdue.clear(1)
        overdue.save()
        File(overdue.kitDir, ".boards/states/$source.json").appendText(" ")
        val now = System.currentTimeMillis()
        assertFailsWith<IllegalArgumentException>("while BACK can bring it back, a source that will not read stops it") { overdue.filesUnderPrints(nowMillis = now) }
        assertEquals(emptySet(), overdue.filesUnderPrints(nowMillis = now + 31L * 24 * 60 * 60 * 1000), "past its days, the damaged board is skipped")

        // What the guard prevents: BACK on the era's row, under the board, and REMOVE refuses.
        val stuck = model("BackUnderBoard")
        stuck.eraPad(1, "tape", 1f)
        BoardSheet.apply(stuck, 1, .7f)
        stuck.restoreFromBin(stuck.binContents().single { it.originalName == "main.wav" })
        assertFailsWith<IllegalArgumentException>("BACK overwrote a file the board restores") { BoardSheet.remove(stuck, 1) }
    }

    /**
     * An ejected board's tombstone holds the printed pad, and BACK brings it back; REMOVE then brings
     * the era'd source back, and its NONE reads the original under the source's own file. So that
     * original lives as long as the tombstone, past its own 30 days. And a tombstone whose saved
     * source will not read stops the sweep, as a live board's does.
     */
    @Test fun `an ejected board over a treated pad keeps the source's original while its tombstone stays`() {
        val dayMs = 24L * 60 * 60 * 1000
        val m = model("BinEjectedBoard")
        val original = bytes(m)
        m.eraPad(1, "tape", 1f)
        BoardSheet.apply(m, 1, .7f)
        m.clear(1) // EJECT: the board's own file goes in with a tombstone holding the printed pad
        m.save()
        val now = System.currentTimeMillis()
        val era = m.binContents().single { it.originalName == "main.wav" }
        assertTrue(era.file.renameTo(File(era.file.parentFile, "${now - 31 * dayMs}_main.wav")), "test setup: the era went in 31 days ago")
        val eject = m.binContents().single { it.padSnapshot != null }

        assertEquals(0, KitBuilderModel.open(m.kitDir).purgeBin(nowMillis = now), "the source's original outlives its 30 days while BACK can bring the board back")
        val kept = m.binHolds().single { it.entry.originalName == "main.wav" }
        assertEquals(eject.binnedAtMillis + 30 * dayMs, kept.purgeAtMillis, "it leaves with the board's tombstone")
        m.restoreFromBin(eject) // BACK
        assertTrue(PrintedPads.has(m.pad(1)!!.recipe), "test setup: BACK brought the board back")
        assertTrue(PadSheet.read(BoardSheet.remove(m, 1).recipe) != null, "test setup: REMOVE brought the era back")
        m.unEraPad(1)
        assertTrue(original.contentEquals(bytes(m)), "NONE after BACK and REMOVE gives the original back")

        val damaged = model("BinEjectedBoardDamaged")
        damaged.eraPad(1, "tape", 1f)
        val source = (BoardSheet.apply(damaged, 1, .7f).recipe!!.entries.getValue("source") as JsonValue.Str).value
        damaged.clear(1)
        damaged.save()
        File(damaged.kitDir, ".boards/states/$source.json").appendText(" ")
        val binDir = File(damaged.kitDir, KitBuilderModel.BIN_DIR)
        val before = allFiles(binDir)
        val later = System.currentTimeMillis() + 1000
        assertFailsWith<IllegalArgumentException> { KitBuilderModel.open(damaged.kitDir).purgeBin(nowMillis = later) }
        assertEquals(before, allFiles(binDir), "nothing in the kit's bin was deleted while BACK can bring the board back")
        assertEquals(
            2,
            KitBuilderModel.open(damaged.kitDir).purgeBin(nowMillis = later + 31 * dayMs),
            "once the tombstone's days are up, the sweep takes it, the board's file and the era's original, damaged source and all",
        )
        assertTrue(allFiles(binDir).isEmpty(), "the tombstone left with its file")
    }

    /**
     * Fail closed: a sweep that cannot read a printed pad's saved source cannot know what that source
     * needs, so it deletes nothing in the kit and throws (the launch moves on to the next kit). Only
     * the snapshot is checked, by its own hash; damaged saved audio does not stop the sweep, because
     * the sweep needs the file names the source played, not its sound.
     */
    @Test fun `a board whose saved source will not read stops the kit bin's sweep before it deletes anything`() {
        for (damage in listOf("damaged snapshot", "missing snapshot", "damaged audio")) {
            val m = model("BinFailClosed-${damage.replace(' ', '-')}")
            m.eraPad(1, "tape", 1f)
            BoardSheet.apply(m, 1, .7f)
            m.assign(2, hit(), com.snipsnap.audio.DrumClass.SNARE)
            m.clear(2) // an eject record nothing keeps, which the sweep takes whenever it can
            m.save()
            val source = (m.pad(1)!!.recipe!!.entries.getValue("source") as JsonValue.Str).value
            val snapshot = File(m.kitDir, ".boards/states/$source.json")
            when (damage) {
                "damaged snapshot" -> snapshot.appendText(" ")
                "missing snapshot" -> snapshot.delete()
                else -> File(m.kitDir, ".boards/audio").listFiles()!!.forEach { it.writeText("not audio") }
            }
            val binDir = File(m.kitDir, KitBuilderModel.BIN_DIR)
            val before = allFiles(binDir)
            val sweep = { KitBuilderModel.open(m.kitDir).purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000) }
            if (damage == "damaged audio") {
                assertEquals(1, sweep(), "the snapshot still reads, so the sweep runs and the original stays")
                assertEquals(listOf<Int?>(1), m.binHolds().map { it.keptFor })
            } else {
                assertFailsWith<IllegalArgumentException>(damage) { sweep() }
                assertEquals(before, allFiles(binDir), "$damage: nothing in the kit's bin was deleted")
            }
        }
    }

    @Test fun `opaque synth recipe returns exactly after keep reload and amount zero`() {
        val recipe = originalRecipe()
        val m = model(recipe = recipe)
        val original = bytes(m)
        BoardSheet.apply(m, 1, 1f)
        m.save()
        val reopened = KitBuilderModel.open(m.kitDir)
        val dry = BoardSheet.preview(reopened, 1, 0f)
        assertTrue(original.contentEquals(encoded(dry)), "dry audition is exact, with no stereo conversion or new tail")
        val removed = BoardSheet.apply(reopened, 1, 0f)
        assertEquals(recipe, removed.recipe, "unknown nested JSON is preserved")
        assertTrue(original.contentEquals(bytes(reopened)))
        assertTrue(BoardSheet.canUndo(reopened, 1))
        BoardSheet.undo(reopened, 1)
        assertEquals(1f, BoardSheet.read(reopened.pad(1)!!.recipe)!!.amount)
    }

    @Test fun `repeated amounts never stack and undo restores the previous kept amount after reload`() {
        val m = model(recipe = originalRecipe())
        val original = WavReader.read(File(m.kitDir, "main.wav"))
        val first = BoardSheet.apply(m, 1, .35f)
        val firstBytes = bytes(m)
        val second = BoardSheet.apply(m, 1, .7f)
        assertTrue(encoded(BoardRecipe(amount = .7f).process(original)).contentEquals(bytes(m)), "new amount renders the dry baseline")
        assertNotEquals(first.sampleFile, second.sampleFile)
        m.save()
        val reopened = KitBuilderModel.open(m.kitDir)
        reopened.update(1) { it.copy(level = .2f, decay = .8f) }
        assertTrue(BoardSheet.canUndo(reopened, 1), "metadata edits do not invalidate audio undo")
        val undone = BoardSheet.undo(reopened, 1)
        assertEquals(first.recipe, undone.recipe)
        assertEquals(first.sampleFile, undone.sampleFile)
        assertTrue(firstBytes.contentEquals(bytes(reopened)))
        assertEquals(.2f, undone.level)
        assertEquals(.8f, undone.decay)
    }

    @Test fun `keeping the same amount twice preserves the previous meaningful undo without writes`() {
        val m = model()
        val first = BoardSheet.apply(m, 1, .7f)
        val second = BoardSheet.apply(m, 1, .35f)
        val files = allFiles(m.kitDir)
        assertEquals(second, BoardSheet.apply(m, 1, .35f))
        assertEquals(files, allFiles(m.kitDir), "redundant KEEP leaves audio, snapshots and undo sidecar unchanged")
        val restored = BoardSheet.undo(m, 1)
        assertEquals(first, restored)
        assertEquals(.7f, BoardSheet.read(restored.recipe)!!.amount)
    }

    @Test fun `every velocity layer renders and restores and shared source files are untouched`() {
        val m = model(layered = true, shared = true)
        val original = bytes(m, "main.wav")
        val soft = bytes(m, "soft.wav")
        val otherPad = m.pad(2)!!
        val processed = BoardSheet.apply(m, 1, .7f)
        assertEquals(listOf(0 to 63, 64 to 127), processed.velocityLayers.map { it.velStart to it.velEnd })
        assertTrue(processed.velocityLayers.all { it.sampleFile != "soft.wav" && it.sampleFile != "main.wav" })
        assertTrue(original.contentEquals(bytes(m, "main.wav")))
        assertTrue(soft.contentEquals(bytes(m, "soft.wav")))
        assertEquals(otherPad, m.pad(2))
        val expectedSoft = BoardRecipe(amount = .7f).process(WavReader.read(File(m.kitDir, "soft.wav")))
        assertTrue(encoded(expectedSoft).contentEquals(bytes(m, processed.velocityLayers.first().sampleFile)))
        val restored = BoardSheet.remove(m, 1)
        assertEquals(otherPad.velocityLayers, restored.velocityLayers)
        assertTrue(original.contentEquals(bytes(m)))
    }

    @Test fun `post-board layer changes refuse preview keep removal and undo without mutations`() {
        val m = model(layered = true)
        BoardSheet.apply(m, 1, .7f)
        m.update(1) { it.copy(velocityLayers = emptyList()) }
        val before = m.pad(1)
        val files = allFiles(m.kitDir)
        assertFailsWith<IllegalArgumentException> { BoardSheet.preview(m, 1, .35f) }
        assertFailsWith<IllegalArgumentException> { BoardSheet.apply(m, 1, .35f) }
        assertFailsWith<IllegalArgumentException> { BoardSheet.remove(m, 1) }
        assertFalse(BoardSheet.canUndo(m, 1))
        assertFailsWith<IllegalArgumentException> { BoardSheet.undo(m, 1) }
        assertEquals(before, m.pad(1))
        assertEquals(files, allFiles(m.kitDir))
    }

    @Test fun `missing baseline snapshot or audio refuses before any writes`() {
        for (missingAudio in listOf(false, true)) {
            val m = model(name = "Missing-$missingAudio")
            BoardSheet.apply(m, 1, .7f)
            val recipe = m.pad(1)!!.recipe!!
            val source = (recipe.entries.getValue("source") as JsonValue.Str).value
            val snapshot = File(m.kitDir, ".boards/states/$source.json")
            if (missingAudio) {
                val value = Json.parse(snapshot.readText()) as JsonValue.Obj
                val inv = value.entries.getValue("inventory") as JsonValue.Obj
                val file = (inv.entries.getValue("files") as JsonValue.Arr).items.first() as JsonValue.Obj
                val hash = (file.entries.getValue("sha256") as JsonValue.Str).value
                File(m.kitDir, ".boards/audio/$hash.wav").delete()
            } else snapshot.delete()
            val pad = m.pad(1)
            val files = allFiles(m.kitDir)
            assertFailsWith<IllegalArgumentException> { BoardSheet.preview(m, 1, .35f) }
            assertFailsWith<IllegalArgumentException> { BoardSheet.apply(m, 1, .35f) }
            assertFailsWith<IllegalArgumentException> { BoardSheet.remove(m, 1) }
            assertEquals(pad, m.pad(1))
            assertEquals(files, allFiles(m.kitDir))
        }
    }

    @Test fun `unrelated audio and recipe edits invalidate one-step undo`() {
        val m = model()
        BoardSheet.apply(m, 1, .7f)
        m.update(1) { it.copy(recipe = originalRecipe()) }
        assertFalse(BoardSheet.canUndo(m, 1))
        assertFailsWith<IllegalArgumentException> { BoardSheet.undo(m, 1) }
        val other = model(name = "Modified")
        BoardSheet.apply(other, 1, .7f)
        WavWriter.write(File(other.kitDir, other.pad(1)!!.sampleFile), hit(.1f))
        assertFalse(BoardSheet.canUndo(other, 1))
        assertFailsWith<IllegalArgumentException> { BoardSheet.remove(other, 1) }
    }

    @Test fun `copied board carries editable layered baseline without original kit or filename collisions`() {
        val recipe = originalRecipe()
        val source = model(name = "Source", recipe = recipe, layered = true)
        val dry = WavReader.read(File(source.kitDir, "main.wav"))
        val active = BoardSheet.apply(source, 1, .7f) // Deliberately not saved: import uses the supplied pad.
        val targetDir = File(temp, "Target").apply { mkdirs() }
        val mapping = active.velocityLayers.mapIndexed { i, layer -> layer.sampleFile to "copy-$i.wav" }.toMap()
        for ((old, name) in mapping) File(source.kitDir, old).copyTo(File(targetDir, name))
        val copied = active.copy(sampleFile = mapping.getValue(active.sampleFile), velocityLayers = active.velocityLayers.map { it.copy(sampleFile = mapping.getValue(it.sampleFile)) })
        // Another pad already owns the original kit's source filename with different bytes.
        WavWriter.write(File(targetDir, "main.wav"), hit(.1f))
        val unrelated = File(targetDir, "main.wav").readBytes()
        val imported = BoardSheet.importPad(source.kitDir, targetDir, active, copied)
        KitStore.save(Kit("Target", listOf(imported, KitPad(2, "main.wav"))), targetDir)
        source.kitDir.deleteRecursively()
        val target = KitBuilderModel.open(targetDir)
        assertFalse(BoardSheet.canUndo(target, 1), "copy starts a new editing history")
        assertTrue(encoded(dry).contentEquals(encoded(BoardSheet.preview(target, 1, 0f))))
        BoardSheet.apply(target, 1, .35f)
        assertTrue(encoded(BoardRecipe(amount = .35f).process(dry)).contentEquals(bytes(target)))
        val restored = BoardSheet.remove(target, 1)
        assertEquals(recipe, restored.recipe)
        assertEquals(2, restored.velocityLayers.size)
        assertTrue(restored.sampleFile.startsWith("WS_"))
        assertTrue(restored.sampleFile.length <= 64, "restored samples fit the MPC filename budget")
        assertTrue(encoded(dry).contentEquals(bytes(target)))
        assertTrue(unrelated.contentEquals(File(targetDir, "main.wav").readBytes()), "destination's other pad is untouched")
    }

    @Test fun `copying a board with missing baseline refuses without changing destination storage`() {
        val source = model(name = "SourceMissing")
        val active = BoardSheet.apply(source, 1, .7f)
        val target = File(temp, "CopyMissing").apply { mkdirs() }
        File(source.kitDir, active.sampleFile).copyTo(File(target, "copy.wav"))
        val copied = active.copy(sampleFile = "copy.wav")
        val id = (active.recipe!!.entries.getValue("source") as JsonValue.Str).value
        File(source.kitDir, ".boards/states/$id.json").delete()
        val before = allFiles(target)
        assertFailsWith<IllegalArgumentException> { BoardSheet.importPad(source.kitDir, target, active, copied) }
        assertEquals(before, allFiles(target))
    }

    @Test fun `invalid amounts corrupt versions path traversal and chains refuse safely`() {
        val m = model()
        val initial = allFiles(m.kitDir)
        for (amount in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            assertFailsWith<IllegalArgumentException> { BoardSheet.apply(m, 1, amount) }
            assertFailsWith<IllegalArgumentException> { BoardSheet.preview(m, 1, amount) }
        }
        assertEquals(initial, allFiles(m.kitDir))
        assertEquals(m.pad(1), BoardSheet.apply(m, 1, 0f))
        val active = BoardSheet.apply(m, 1, .7f).recipe!!
        val corruptions = listOf(
            JsonValue.Obj(active.entries + ("boardPad" to JsonValue.Num(2.0))),
            JsonValue.Obj(active.entries + ("source" to JsonValue.Str("../../outside"))),
        )
        for (recipe in corruptions) {
            assertNull(BoardSheet.read(recipe))
            m.update(1) { it.copy(recipe = recipe) }
            val files = allFiles(m.kitDir)
            assertFailsWith<IllegalArgumentException> { BoardSheet.apply(m, 1, .7f) }
            assertFailsWith<IllegalArgumentException> { BoardSheet.preview(m, 1, .7f) }
            assertFailsWith<IllegalArgumentException> { BoardSheet.remove(m, 1) }
            assertEquals(files, allFiles(m.kitDir))
        }
        m.update(1) { it.copy(chain = ChainInfo(listOf(0L, 200L), 2)) }
        assertFailsWith<IllegalArgumentException> { BoardSheet.preview(m, 1, .7f) }
        assertFailsWith<IllegalArgumentException> { BoardSheet.apply(m, 1, .7f) }
    }
}
