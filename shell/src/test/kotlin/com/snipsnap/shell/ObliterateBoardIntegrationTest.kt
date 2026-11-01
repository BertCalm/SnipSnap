package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.*
import com.snipsnap.synth.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class ObliterateBoardIntegrationTest {
    private val temp = java.nio.file.Files.createTempDirectory("obliterate-board-integration").toFile()
    @AfterTest fun cleanup() { temp.deleteRecursively() }
    private val state = ObliterateBoard.Settings(.3, false, 98.0, 8, 98765L)
    private val board = BoardRecipe(BoardType.OBLITERATE, .35f, state)
    private fun originalRecipe() = Json.parse("""{"future":{"opaque":[1,"preserve",null]}}""") as JsonValue.Obj
    private fun hit(hz: Double) = Snip(FloatArray(2400) { (.2 * sin(2 * PI * hz * it / 44100)).toFloat() }, 1, 44100)
    private fun model(name: String, longSoft: Boolean = false): KitBuilderModel {
        val dir = File(temp, name).apply { mkdirs() }
        WavWriter.write(File(dir, "main.wav"), hit(220.0))
        WavWriter.write(File(dir, "soft.wav"), if (longSoft) Snip(FloatArray(9 * 44100 + 1), 1, 44100) else hit(330.0))
        val pad = KitPad(1, "main.wav", recipe = originalRecipe(), level = .6f, pan = .7f,
            velocityLayers = listOf(KitLayer("soft.wav", 0, 63), KitLayer("main.wav", 64, 127)))
        KitStore.save(Kit(name, listOf(pad, pad.copy(slot = 2))), dir)
        return KitBuilderModel.open(dir)
    }
    private fun encoded(snip: Snip) = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24) }.toByteArray()
    private fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(dir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }

    @Test fun `preview KEEP and all velocity zones use the immutable baseline and preserve full settings`() {
        val m = model("zones"); val original = m.pad(1)!!; val other = m.pad(2)!!
        val dry = original.velocityLayers.associate { it.sampleFile to File(m.kitDir, it.sampleFile).readBytes() }
        val before = files(m.kitDir)
        assertContentEquals(encoded(board.process(WavReader.read(dry.getValue(original.sampleFile)))), encoded(BoardSheet.preview(m, 1, board)))
        assertEquals(before, files(m.kitDir))
        val kept = BoardSheet.apply(m, 1, board)
        assertEquals(board, BoardSheet.read(kept.recipe)!!.recipe)
        for ((old, new) in original.velocityLayers.zip(kept.velocityLayers)) {
            assertEquals(old.velStart, new.velStart); assertEquals(old.velEnd, new.velEnd)
            assertContentEquals(encoded(board.process(WavReader.read(dry.getValue(old.sampleFile)))), File(m.kitDir, new.sampleFile).readBytes())
        }
        assertEquals(other, m.pad(2)); assertEquals(original.level, kept.level); assertEquals(original.pan, kept.pan)
        val after = files(m.kitDir)
        assertEquals(kept, BoardSheet.apply(m, 1, board)); assertEquals(after, files(m.kitDir))
        m.save(); val reopened = KitBuilderModel.open(m.kitDir)
        assertEquals(board, BoardSheet.read(reopened.pad(1)!!.recipe)!!.recipe)
        val edited = board.copy(amount = .7f, obliterate = state.copy(seed = 77L, bpm = 110.0, pitched = true))
        val changed = BoardSheet.apply(reopened, 1, edited)
        assertEquals(edited, BoardSheet.read(changed.recipe)!!.recipe)
        assertEquals(kept, BoardSheet.undo(reopened, 1))
        assertEquals(original, BoardSheet.remove(reopened, 1))
        assertEquals(kept, BoardSheet.undo(reopened, 1))
        for ((name, bytes) in dry) assertContentEquals(bytes, File(m.kitDir, name).readBytes())
    }

    @Test fun `copy replay keeps all controls for printed and typed recipes on the destinations own audio`() {
        val m = model("replay")
        val dry = File(m.kitDir, "main.wav").readBytes()
        val printed = BoardSheet.apply(m, 1, board).recipe!!
        val typed = PadRecipe(board = board).toJsonValue()
        for (recipe in listOf(printed, typed)) {
            val plan = assertIs<RecipeReplay.Plan.Board>(RecipeReplay.plan(recipe))
            assertEquals(board, plan.recipe)
            val done = RecipeReplay.apply(m, 2, recipe, "destination")
            assertEquals(board, BoardSheet.read(done.pad.recipe)!!.recipe)
            assertContentEquals(encoded(board.process(WavReader.read(dry))), File(m.kitDir, done.pad.sampleFile).readBytes())
            assertEquals(originalRecipe(), BoardSheet.remove(m, 2).recipe)
        }
    }

    @Test fun `an over budget soft zone refuses before any file or pad is changed`() {
        val m = model("budget", longSoft = true); val before = files(m.kitDir); val kit = m.kit
        val error = assertFailsWith<IllegalArgumentException> { BoardSheet.apply(m, 1, board) }
        assertTrue(error.message!!.contains("9 seconds"))
        assertEquals(kit, m.kit); assertEquals(before, files(m.kitDir)); assertFalse(BoardSheet.canUndo(m, 1))
    }

    @Test fun `zero restores the source of a different board and undo keeps the complete OBLITERATE recipe`() {
        val m = model("zero"); val original = m.pad(1)!!
        val kept = BoardSheet.apply(m, 1, board)
        assertEquals(original, BoardSheet.apply(m, 1, 0f, BoardType.WIDE))
        assertEquals(kept, BoardSheet.undo(m, 1))
        BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        assertEquals(original, BoardSheet.apply(m, 1, board.copy(amount = 0f)))
        assertEquals(BoardType.RUSH, BoardSheet.read(BoardSheet.undo(m, 1).recipe)!!.type)
    }

    @Test fun `copied complete board remains editable and removable after the source kit is gone`() {
        val source = model("source"); val original = source.pad(1)!!
        val dry = original.velocityLayers.associate { it.sampleFile to File(source.kitDir, it.sampleFile).readBytes() }
        val kept = BoardSheet.apply(source, 1, board)
        val target = File(temp, "target").apply { mkdirs() }
        val names = kept.velocityLayers.mapIndexed { i, layer -> layer.sampleFile to "copy$i.wav" }.toMap()
        for ((old, new) in names) File(source.kitDir, old).copyTo(File(target, new))
        val copied = kept.copy(sampleFile = names.getValue(kept.sampleFile), velocityLayers = kept.velocityLayers.map { it.copy(sampleFile = names.getValue(it.sampleFile)) })
        val imported = BoardSheet.importPad(source.kitDir, target, kept, copied)
        KitStore.save(Kit("target", listOf(imported)), target); source.kitDir.deleteRecursively()
        val m = KitBuilderModel.open(target)
        assertEquals(board, BoardSheet.read(m.pad(1)!!.recipe)!!.recipe)
        assertFalse(BoardSheet.canUndo(m, 1))
        val edited = board.copy(obliterate = state.copy(seed = 80L, shimmerMix = .15))
        assertEquals(edited, BoardSheet.read(BoardSheet.apply(m, 1, edited).recipe)!!.recipe)
        val removed = BoardSheet.remove(m, 1)
        assertEquals(originalRecipe(), removed.recipe)
        assertContentEquals(dry.getValue(original.sampleFile), File(target, removed.sampleFile).readBytes())
        for ((old, restored) in original.velocityLayers.zip(removed.velocityLayers))
            assertContentEquals(dry.getValue(old.sampleFile), File(target, restored.sampleFile).readBytes())
    }
}
