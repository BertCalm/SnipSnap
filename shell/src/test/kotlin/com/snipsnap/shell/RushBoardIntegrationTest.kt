package com.snipsnap.shell

import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.BoardRecipe
import com.snipsnap.synth.BoardType
import com.snipsnap.synth.PadRecipe
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A board change reuses the captured source; it must never print one board through another. */
class RushBoardIntegrationTest {
    private val temp = java.nio.file.Files.createTempDirectory("rush-board-integration").toFile()

    @AfterTest fun cleanup() { temp.deleteRecursively() }

    private fun hit(hz: Double = 220.0, gain: Double = .4): Snip = Snip(FloatArray(3600) { i ->
        (gain * sin(2.0 * PI * hz * i / 44100.0) * exp(-i / 1200.0)).toFloat()
    }, 1, 44100)

    private fun opaqueRecipe(): JsonValue.Obj = Json.parse(
        """{"engine":"CAPTURED","future":{"unknown":[1,"keep exactly",null],"board":{"type":"future"}}}""",
    ) as JsonValue.Obj

    private fun model(name: String, layered: Boolean = false, shared: Boolean = false): KitBuilderModel {
        val dir = File(temp, name).apply { mkdirs() }
        WavWriter.write(File(dir, "main.wav"), hit())
        if (layered) WavWriter.write(File(dir, "soft.wav"), hit(330.0, .15))
        val layers = if (layered) listOf(KitLayer("soft.wav", 0, 63), KitLayer("main.wav", 64, 127)) else emptyList()
        val pad = KitPad(
            1, "main.wav", recipe = opaqueRecipe(), velocityLayers = layers,
            level = .6f, pan = .7f, attack = .1f, cutoff = .8f,
        )
        KitStore.save(Kit(name, if (shared) listOf(pad, pad.copy(slot = 2)) else listOf(pad)), dir)
        return KitBuilderModel.open(dir)
    }

    private fun bytes(model: KitBuilderModel, name: String = model.pad(1)!!.sampleFile): ByteArray =
        File(model.kitDir, name).readBytes()

    private fun encoded(snip: Snip): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24)
    }.toByteArray()

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun files(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).path to sha(it.readBytes()) }

    private fun source(pad: KitPad): String =
        ((pad.recipe ?: error("missing board recipe")).entries.getValue("source") as JsonValue.Str).value

    private fun sourceHashes(model: KitBuilderModel, pad: KitPad): Map<String, String> {
        val state = Json.parse(File(model.kitDir, ".boards/states/${source(pad)}.json").readText()) as JsonValue.Obj
        val inventory = state.entries.getValue("inventory") as JsonValue.Obj
        val entries = inventory.entries.getValue("files") as JsonValue.Arr
        return entries.items.associate { item ->
            val entry = item as JsonValue.Obj
            (entry.entries.getValue("file") as JsonValue.Str).value to
                (entry.entries.getValue("sha256") as JsonValue.Str).value
        }
    }

    @Test fun `board audio edits clear only the current WAV fingerprint while previews and no op keeps retain it`() {
        val m = model("Fingerprint")
        val originalAudio = bytes(m)
        val provenance = mapOf("renderer" to "IMPORTED", "importedFrom" to "Audition.zip", "origin" to "keep this")
        fun fingerprintCurrent(): KitPad = m.update(1) {
            it.copy(source = provenance + ("rawSha256" to sha(bytes(m))))
        }
        val original = fingerprintCurrent()
        val beforePreview = files(m.kitDir)
        BoardSheet.preview(m, 1, .7f, BoardType.RUSH)
        assertEquals(original, BoardSheet.apply(m, 1, 0f, BoardType.RUSH))
        assertEquals(original.source, m.pad(1)!!.source)
        assertEquals(beforePreview, files(m.kitDir))

        // Clearing the fingerprint is an internal consequence of adoption,
        // never permission for a caller to change unrelated pad provenance.
        assertFailsWith<IllegalArgumentException> {
            m.replaceBoardPad(1, original, original.copy(source = mapOf("origin" to "changed")))
        }
        val rush = BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        assertEquals(provenance, rush.source)
        assertEquals(rush.source, m.pad(1)!!.source)

        val stampedRush = fingerprintCurrent()
        val beforeNoop = files(m.kitDir)
        BoardSheet.preview(m, 1, .35f, BoardType.WIDE)
        assertEquals(stampedRush, BoardSheet.apply(m, 1, .7f, BoardType.RUSH))
        assertEquals(stampedRush.source, m.pad(1)!!.source)
        assertEquals(beforeNoop, files(m.kitDir), "preview and same-board same-AMT KEEP do not invalidate a valid fingerprint")

        val wide = BoardSheet.apply(m, 1, .35f, BoardType.WIDE)
        val wideAudio = bytes(m)
        assertEquals(provenance, wide.source, "switching boards invalidates only the current WAV hash")
        fingerprintCurrent()
        val removed = BoardSheet.remove(m, 1)
        assertEquals(provenance, removed.source)
        assertContentEquals(originalAudio, bytes(m))
        assertEquals(original.recipe, removed.recipe)
        fingerprintCurrent()
        val undone = BoardSheet.undo(m, 1)
        assertEquals(provenance, undone.source)
        assertEquals(wide.recipe, undone.recipe)
        assertContentEquals(wideAudio, bytes(m))
    }

    @Test fun `switching WIDE to RUSH at the same amount previews and prints the original baseline`() {
        val m = model("Switch")
        val original = WavReader.read(File(m.kitDir, "main.wav"))
        val wide = BoardSheet.apply(m, 1, .7f)
        assertEquals(BoardType.WIDE, BoardSheet.read(wide.recipe)?.type, "existing callers still default to WIDE")
        val beforePreview = files(m.kitDir)
        val expected = encoded(BoardRecipe(BoardType.RUSH, .7f).process(original))
        assertContentEquals(expected, encoded(BoardSheet.preview(m, 1, .7f, BoardType.RUSH)))
        assertEquals(beforePreview, files(m.kitDir), "choosing a board and hearing it does not keep it")

        val rush = BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        assertEquals(BoardType.RUSH, BoardSheet.read(rush.recipe)?.type)
        assertEquals(.7f, BoardSheet.read(rush.recipe)?.amount)
        assertEquals(source(wide), source(rush), "board identity changes retain the pre-WIDE source")
        assertContentEquals(expected, bytes(m))
        val stacked = BoardRecipe(BoardType.RUSH, .7f).process(WavReader.read(File(m.kitDir, wide.sampleFile)))
        assertFalse(encoded(stacked).contentEquals(bytes(m)), "RUSH must not process the already printed WIDE audio")
    }

    @Test fun `switching RUSH back to WIDE reuses the dry source and undo restores exact RUSH`() {
        val m = model("Reverse")
        val original = WavReader.read(File(m.kitDir, "main.wav"))
        val rush = BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        val rushAudio = bytes(m)
        val rushSources = sourceHashes(m, rush)
        val expected = encoded(BoardRecipe(BoardType.WIDE, .7f).process(original))
        val beforePreview = files(m.kitDir)
        assertContentEquals(expected, encoded(BoardSheet.preview(m, 1, .7f, BoardType.WIDE)))
        assertEquals(beforePreview, files(m.kitDir))

        val wide = BoardSheet.apply(m, 1, .7f, BoardType.WIDE)
        assertEquals(BoardType.WIDE, BoardSheet.read(wide.recipe)?.type)
        assertEquals(source(rush), source(wide))
        assertEquals(rushSources, sourceHashes(m, wide))
        assertContentEquals(expected, bytes(m), "WIDE prints the full dry baseline, rather than a RUSH print")
        val stacked = BoardRecipe(BoardType.WIDE, .7f).process(WavReader.read(rushAudio))
        assertFalse(encoded(stacked).contentEquals(bytes(m)))
        m.save()
        val reopened = KitBuilderModel.open(m.kitDir)
        val undone = BoardSheet.undo(reopened, 1)
        assertEquals(rush, undone)
        assertEquals(BoardType.RUSH, BoardSheet.read(undone.recipe)?.type)
        assertContentEquals(rushAudio, bytes(reopened))
        assertFalse(BoardSheet.canUndo(reopened, 1))
    }

    @Test fun `reloaded RUSH undo restores exact WIDE and removal restores the opaque source recipe`() {
        val m = model("Reload")
        val original = m.pad(1)!!
        val originalAudio = bytes(m)
        val wide = BoardSheet.apply(m, 1, .35f)
        val wideAudio = bytes(m)
        BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        m.save()
        val reopened = KitBuilderModel.open(m.kitDir)
        assertEquals(BoardType.RUSH, BoardSheet.read(reopened.pad(1)!!.recipe)?.type)
        reopened.update(1) { it.copy(level = .2f, pan = .1f, decay = .8f) }
        assertTrue(BoardSheet.canUndo(reopened, 1))
        val undone = BoardSheet.undo(reopened, 1)
        assertEquals(wide.recipe, undone.recipe)
        assertEquals(wide.sampleFile, undone.sampleFile)
        assertContentEquals(wideAudio, bytes(reopened))
        assertFalse(BoardSheet.canUndo(reopened, 1))

        val rush = BoardSheet.apply(reopened, 1, .7f, BoardType.RUSH)
        val rushAudio = bytes(reopened)
        val removed = BoardSheet.remove(reopened, 1)
        assertEquals(original.recipe, removed.recipe, "unknown nested JSON returns exactly")
        assertEquals(original.sampleFile, removed.sampleFile)
        assertContentEquals(originalAudio, bytes(reopened))
        assertEquals(.2f, removed.level)
        assertEquals(.1f, removed.pan)
        assertEquals(.8f, removed.decay)
        assertEquals(rush.recipe, BoardSheet.undo(reopened, 1).recipe)
        assertContentEquals(rushAudio, bytes(reopened), "REMOVE also restores the exact previous RUSH print on undo")
    }

    @Test fun `RUSH renders each velocity layer and keeps hashed shared source audio intact`() {
        val m = model("Layers", layered = true, shared = true)
        val original = m.pad(1)!!
        val shared = m.pad(2)!!
        val dry = mapOf("main.wav" to bytes(m, "main.wav"), "soft.wav" to bytes(m, "soft.wav"))
        val wide = BoardSheet.apply(m, 1, .35f)
        val rush = BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        assertEquals(source(wide), source(rush))
        assertEquals(dry.mapValues { sha(it.value) }, sourceHashes(m, rush))
        assertEquals(listOf(0 to 63, 64 to 127), rush.velocityLayers.map { it.velStart to it.velEnd })
        for ((old, kept) in original.velocityLayers.zip(rush.velocityLayers)) {
            val expected = BoardRecipe(BoardType.RUSH, .7f).process(WavReader.read(File(m.kitDir, old.sampleFile)))
            assertContentEquals(encoded(expected), bytes(m, kept.sampleFile))
            assertNotEquals(old.sampleFile, kept.sampleFile)
        }
        for ((name, audio) in dry) assertContentEquals(audio, bytes(m, name))
        assertEquals(shared, m.pad(2), "another pad sharing the original WAVs is untouched")
        m.save()
        val reopened = KitBuilderModel.open(m.kitDir)
        assertEquals(original, BoardSheet.remove(reopened, 1))
        for ((name, audio) in dry) assertContentEquals(audio, bytes(reopened, name))
    }

    @Test fun `a damaged RUSH source hash refuses preview switching removal and undo without writes`() {
        val m = model("Damaged", layered = true)
        val rush = BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        val hash = sourceHashes(m, rush).getValue("soft.wav")
        WavWriter.write(File(m.kitDir, ".boards/audio/$hash.wav"), hit(880.0, .1))
        val before = files(m.kitDir)
        assertFailsWith<IllegalArgumentException> { BoardSheet.preview(m, 1, .35f, BoardType.RUSH) }
        assertFailsWith<IllegalArgumentException> { BoardSheet.apply(m, 1, .35f, BoardType.WIDE) }
        assertFailsWith<IllegalArgumentException> { BoardSheet.remove(m, 1) }
        assertFalse(BoardSheet.canUndo(m, 1))
        assertFailsWith<IllegalArgumentException> { BoardSheet.undo(m, 1) }
        assertEquals(rush, m.pad(1))
        assertEquals(before, files(m.kitDir))
    }

    @Test fun `RUSH replay names its type and uses each destinations own source and recipe`() {
        val m = model("Replay")
        m.assign(2, hit(440.0))
        m.assign(3, hit(660.0))
        val base = opaqueRecipe()
        m.update(2) { it.copy(recipe = base) }
        m.update(3) { it.copy(recipe = base) }
        val dry = (2..3).associateWith { slot -> File(m.kitDir, m.pad(slot)!!.sampleFile).readBytes() }
        val rush = BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        val wrapper = assertNotNull(rush.recipe)
        assertEquals("RUSH 70%", KitDiff.recipeName(wrapper))
        val clip = assertNotNull(RecipeReplay.clip(wrapper, "Replay", 1))
        assertEquals(wrapper, clip.recipe)
        assertEquals("RUSH 70%", clip.word)
        assertEquals("REPLAY A01", clip.from)
        assertEquals("RUSH · 70%", PadSheetBoxes.summaries(rush, null)[PadSheetBoxes.Box.TREATMENT])
        assertEquals(setOf(PadSheetBoxes.Box.TREATMENT, PadSheetBoxes.Box.SHAPE), PadSheetBoxes.touched(rush))
        val typed = PadRecipe(board = BoardRecipe(BoardType.RUSH, .7f)).toJsonValue()
        assertEquals("RUSH 70%", KitDiff.recipeName(typed))
        assertEquals("RUSH 70%", assertNotNull(RecipeReplay.clip(typed, "Typed", 1)).word)
        // Pasting onto a WIDE destination must also render that destination's dry baseline.
        BoardSheet.apply(m, 3, .7f)
        for ((slot, recipe) in listOf(2 to wrapper, 3 to typed)) {
            val plan = assertIs<RecipeReplay.Plan.Board>(RecipeReplay.plan(recipe))
            assertEquals(BoardType.RUSH, plan.type)
            assertEquals(.7f, plan.amount)
            val done = RecipeReplay.apply(m, slot, recipe, "DESTINATION $slot")
            assertEquals(BoardType.RUSH, BoardSheet.read(done.pad.recipe)?.type)
            assertTrue("RUSH" in done.toast)
            assertTrue("ORIGINAL KEPT" in done.toast)
            val expected = BoardRecipe(BoardType.RUSH, .7f).process(WavReader.read(dry.getValue(slot)))
            assertContentEquals(encoded(expected), File(m.kitDir, done.pad.sampleFile).readBytes())
            val removed = BoardSheet.remove(m, slot)
            assertEquals(base, removed.recipe)
            assertContentEquals(dry.getValue(slot), File(m.kitDir, removed.sampleFile).readBytes())
        }
    }

    @Test fun `copied RUSH remains editable after the original kit is gone`() {
        val source = model("Source", layered = true)
        val dry = bytes(source)
        val active = BoardSheet.apply(source, 1, .7f, BoardType.RUSH)
        val targetDir = File(temp, "Target").apply { mkdirs() }
        val mapping = active.velocityLayers.mapIndexed { index, layer -> layer.sampleFile to "copy-$index.wav" }.toMap()
        for ((old, name) in mapping) File(source.kitDir, old).copyTo(File(targetDir, name))
        val copied = active.copy(
            sampleFile = mapping.getValue(active.sampleFile),
            velocityLayers = active.velocityLayers.map { it.copy(sampleFile = mapping.getValue(it.sampleFile)) },
        )
        WavWriter.write(File(targetDir, "main.wav"), hit(990.0))
        val unrelated = File(targetDir, "main.wav").readBytes()
        val imported = BoardSheet.importPad(source.kitDir, targetDir, active, copied)
        KitStore.save(Kit("Target", listOf(imported, KitPad(2, "main.wav"))), targetDir)
        source.kitDir.deleteRecursively()
        val target = KitBuilderModel.open(targetDir)
        assertEquals(BoardType.RUSH, BoardSheet.read(target.pad(1)!!.recipe)?.type)
        assertFalse(BoardSheet.canUndo(target, 1), "import starts its own editing history")
        assertContentEquals(dry, encoded(BoardSheet.preview(target, 1, 0f, BoardType.RUSH)))
        BoardSheet.apply(target, 1, .35f, BoardType.RUSH)
        val expected = BoardRecipe(BoardType.RUSH, .35f).process(WavReader.read(dry))
        assertContentEquals(encoded(expected), bytes(target))
        val restored = BoardSheet.remove(target, 1)
        assertEquals(opaqueRecipe(), restored.recipe)
        assertEquals(2, restored.velocityLayers.size)
        assertTrue(restored.sampleFile.length <= 64)
        assertContentEquals(dry, bytes(target))
        assertContentEquals(unrelated, File(targetDir, "main.wav").readBytes())
    }

    @Test fun `same RUSH keep preserves meaningful undo and zero removes whichever board is selected`() {
        val m = model("Noop")
        val original = m.pad(1)!!
        val wide = BoardSheet.apply(m, 1, .7f)
        val rush = BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        val before = files(m.kitDir)
        assertEquals(rush, BoardSheet.apply(m, 1, .7f, BoardType.RUSH))
        assertEquals(before, files(m.kitDir))
        assertEquals(wide, BoardSheet.undo(m, 1))
        BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        assertEquals(original, BoardSheet.apply(m, 1, 0f, BoardType.WIDE))
        assertEquals(BoardType.RUSH, BoardSheet.read(BoardSheet.undo(m, 1).recipe)?.type)
    }

    @Test fun `RUSH names and refuses soft hit changes stacking and spreading before any mutation`() {
        for (layered in listOf(false, true)) {
            val m = model("Guards-$layered", layered = layered)
            m.assign(17, hit(550.0))
            val original = m.pad(1)!!
            val dry = bytes(m)
            val rush = BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
            val before = files(m.kitDir)
            val kit = m.kit
            val missingTake = KitBuilderModel.BinEntry("main.wav", 0L, File(m.kitDir, "missing-old-take.wav"))
            val spread = Spread.plan(kit, 60f, Spread.Options(60, Scale.MAJOR, padCount = 4, bank = 1, replaceFull = true))
            val refusals = listOf<Pair<String, () -> Unit>>(
                Copy.boardRemoveBeforeSoftHits("RUSH") to { m.addGhostLayers(1) },
                Copy.boardRemoveBeforeSoftHits("RUSH") to { m.clearGhostLayers(1) },
                Copy.boardRemoveBeforeStack("RUSH") to { m.stackTakes(1, listOf(missingTake)) },
                Copy.boardRemoveBeforeSpread("RUSH") to {
                    Spread.apply(
                        m, WavReader.read(File(m.kitDir, rush.sampleFile)), spread,
                        Spread.Sound("Rush", rush.drumClass, "#19645B", recipe = rush.recipe, like = rush),
                    )
                },
            )
            for ((message, action) in refusals) {
                val error = assertFailsWith<IllegalArgumentException> { action() }
                assertEquals(message, error.message)
                assertTrue("RUSH" in message)
                assertEquals(kit, m.kit)
                assertEquals(before, files(m.kitDir), "a refused edit must leave every audio, source and undo file unchanged")
                assertTrue(BoardSheet.canUndo(m, 1))
            }
            assertEquals(original, BoardSheet.remove(m, 1), "refused edits leave the RUSH source removable")
            assertContentEquals(dry, bytes(m))
        }
    }

    @Test fun `an unlabelled replacement clears RUSH and undo before capturing a fresh board source`() {
        val m = model("Replacement")
        BoardSheet.apply(m, 1, .7f, BoardType.RUSH)
        val replaced = m.replaceAudio(1, null) { hit(770.0, .2) }
        val replacement = bytes(m)
        assertNull(replaced.recipe)
        assertFalse(BoardSheet.canUndo(m, 1), "audio replacement invalidates the earlier board history")
        assertFailsWith<IllegalArgumentException> { BoardSheet.undo(m, 1) }
        BoardSheet.apply(m, 1, .35f, BoardType.RUSH)
        assertNull(BoardSheet.remove(m, 1).recipe)
        assertContentEquals(replacement, bytes(m), "a new board restores the replacement instead of the earlier source")
    }
}
