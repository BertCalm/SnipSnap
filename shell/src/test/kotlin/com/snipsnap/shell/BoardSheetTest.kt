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
