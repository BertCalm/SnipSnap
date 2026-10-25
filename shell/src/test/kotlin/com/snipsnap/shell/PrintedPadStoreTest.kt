package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
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
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintedPadStoreTest {
    private val temp = java.nio.file.Files.createTempDirectory("printed-pad-store").toFile()
    @AfterTest fun cleanup() { temp.deleteRecursively() }

    private val descriptor = JsonValue.Obj(linkedMapOf("version" to JsonValue.Num(1.0)))
    private fun source(gain: Float = .25f) = Snip(FloatArray(882) { i -> (gain * sin(2 * PI * 220 * i / 44100)).toFloat() }, 1, 44100)
    private fun opaqueRecipe() = Json.parse("""{"engine":"unknown","future":{"nested":[1,null,"opaque"]}}""") as JsonValue.Obj
    private fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to sha(it.readBytes()) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun model(name: String, layered: Boolean = false, recipe: JsonValue.Obj? = opaqueRecipe(), smpl: SmplChunk? = null): KitBuilderModel {
        val dir = File(temp, name).apply { mkdirs() }
        WavWriter.write(File(dir, "main.wav"), source(), smpl = smpl)
        if (layered) WavWriter.write(File(dir, "soft.wav"), source(.1f), smpl = smpl)
        val layers = if (layered) listOf(KitLayer("soft.wav", 0, 63), KitLayer("main.wav", 64, 127)) else emptyList()
        KitStore.save(Kit(name, listOf(KitPad(1, "main.wav", recipe = recipe, velocityLayers = layers))), dir)
        return KitBuilderModel.open(dir)
    }
    private fun format(
        namespace: String = ".aquarium",
        validateFile: (File) -> Unit = {},
        preserveSmplRoot: Boolean = false,
    ) = PrintedPadStore.Format(
        namespace = namespace, markerKey = "aquariumPad", descriptorKey = "render",
        stateKey = "aquariumState", undoKey = "aquariumUndo", label = "Aquarium",
        validateDescriptor = { require(it == descriptor) { "Unsupported test descriptor" } },
        validateAudio = { require(it.sampleRate == 44100 && it.channels in 1..2 && it.frameCount > 0 && it.samples.all(Float::isFinite)) },
        validateFile = validateFile, preserveSmplRoot = preserveSmplRoot,
    )
    private fun keep(store: PrintedPadStore, model: KitBuilderModel, render: (Snip) -> Snip = { it }): KitPad =
        store.apply(model, 1, descriptor, false, "AQUARIUM", render)

    @Test fun `board facade still reads and removes the version one on-disk format`() {
        val m = model("Legacy")
        val original = m.pad(1)!!
        val originalBytes = File(m.kitDir, original.sampleFile).readBytes()
        val board = BoardRecipe(amount = .7f)
        WavWriter.write(File(m.kitDir, "legacy-board.wav"), board.process(WavReader.read(originalBytes)))
        fun inventory(name: String, digest: String) = JsonValue.Obj(linkedMapOf(
            "main" to JsonValue.Str(name), "layers" to JsonValue.Arr(emptyList()),
            "files" to JsonValue.Arr(listOf(JsonValue.Obj(linkedMapOf("file" to JsonValue.Str(name), "sha256" to JsonValue.Str(digest))))),
        ))
        val snapshot = JsonValue.Obj(linkedMapOf(
            "boardState" to JsonValue.Num(1.0),
            "inventory" to inventory("main.wav", sha(originalBytes)), "recipe" to original.recipe!!,
        ))
        val snapshotBytes = Json.write(snapshot).toByteArray(Charsets.UTF_8)
        val id = sha(snapshotBytes)
        File(m.kitDir, ".boards/audio/${sha(originalBytes)}.wav").apply { parentFile.mkdirs(); writeBytes(originalBytes) }
        File(m.kitDir, ".boards/states/$id.json").apply { parentFile.mkdirs(); writeBytes(snapshotBytes) }
        val active = original.copy(sampleFile = "legacy-board.wav", recipe = JsonValue.Obj(linkedMapOf(
            "boardPad" to JsonValue.Num(1.0), "board" to board.toJsonValue(), "source" to JsonValue.Str(id),
            "inventory" to inventory("legacy-board.wav", sha(File(m.kitDir, "legacy-board.wav").readBytes())),
        )))
        KitStore.save(m.kit.copy(pads = listOf(active)), m.kitDir)
        val reopened = KitBuilderModel.open(m.kitDir)
        val disk = files(m.kitDir)
        BoardSheet.requireImportable(m.kitDir, active)
        assertEquals(disk, files(m.kitDir), "source preflight does not create any editing history")
        assertEquals(.7f, BoardSheet.read(active.recipe)?.amount)
        assertContentEquals(WavReader.read(originalBytes).samples, BoardSheet.preview(reopened, 1, 0f).samples)
        assertEquals(original, BoardSheet.remove(reopened, 1))
        assertContentEquals(originalBytes, File(m.kitDir, original.sampleFile).readBytes())
        val undo = Json.parse(File(m.kitDir, ".boards/undo-pad-1.json").readText()) as JsonValue.Obj
        assertEquals(setOf("boardUndo", "before", "expected"), undo.entries.keys)
        assertEquals(active, BoardSheet.undo(reopened, 1))
    }

    @Test fun `a board amount narrowed from saved JSON remains a no-op`() {
        val m = model("Amount")
        BoardSheet.apply(m, 1, .7f)
        m.update(1) { pad ->
            val wrapper = pad.recipe!!
            val board = wrapper.entries.getValue("board") as JsonValue.Obj
            pad.copy(recipe = JsonValue.Obj(wrapper.entries + ("board" to JsonValue.Obj(board.entries + ("amount" to JsonValue.Num(.7000000001))))))
        }
        val before = m.pad(1)!!
        val disk = files(m.kitDir)
        assertEquals(before, BoardSheet.apply(m, 1, .7f))
        assertEquals(disk, files(m.kitDir))
    }

    @Test fun `all velocity zones render successfully before any live output is adopted`() {
        val m = model("Zones", layered = true)
        val original = m.pad(1)!!
        val main = File(m.kitDir, "main.wav").readBytes()
        val soft = File(m.kitDir, "soft.wav").readBytes()
        val store = PrintedPadStore(format())
        var calls = 0
        assertFailsWith<IllegalArgumentException> {
            keep(store, m) { snip ->
                require(++calls < 2) { "Second zone failed" }
                snip
            }
        }
        assertEquals(2, calls)
        assertEquals(original, m.pad(1))
        assertContentEquals(main, File(m.kitDir, "main.wav").readBytes())
        assertContentEquals(soft, File(m.kitDir, "soft.wav").readBytes())
        assertFalse(m.kitDir.listFiles().orEmpty().any { it.name.startsWith("AQUARIUM_") })
        assertFalse(store.canUndo(m, 1))
    }

    @Test fun `file validators guard source current and immutable backup audio`() {
        val m = model("FileChecks")
        var refuse: (File) -> Boolean = { it.name == "main.wav" }
        val inspected = mutableSetOf<String>()
        val store = PrintedPadStore(format(validateFile = { file ->
            inspected += file.relativeTo(m.kitDir).invariantSeparatorsPath
            require(!refuse(file)) { "Refused test file" }
        }))
        val initial = files(m.kitDir)
        assertFailsWith<IllegalArgumentException> { keep(store, m) }
        assertEquals(initial, files(m.kitDir))
        refuse = { false }
        val kept = keep(store, m)
        assertTrue(inspected.contains("main.wav"))
        assertTrue(inspected.any { it.startsWith(".aquarium/audio/") })
        assertTrue(inspected.contains(kept.sampleFile))
        val before = files(m.kitDir)
        refuse = { it.name == kept.sampleFile }
        assertFailsWith<IllegalArgumentException> { store.preview(m, 1) { it } }
        refuse = { it.parentFile.name == "audio" }
        assertFailsWith<IllegalArgumentException> { store.preview(m, 1) { it } }
        assertEquals(before, files(m.kitDir))
        assertEquals(kept, m.pad(1))
    }

    @Test fun `root preservation is optional and board WAV bytes still omit sampler metadata`() {
        val preserved = model("Root", smpl = SmplChunk(67))
        val store = PrintedPadStore(format(preserveSmplRoot = true))
        val kept = keep(store, preserved) { snip ->
            Snip(FloatArray(snip.frameCount * 2) { snip.samples[it / 2] }, 2, snip.sampleRate)
        }
        val sheet = assertNotNull(WavReader.readSmpl(File(preserved.kitDir, kept.sampleFile)))
        assertEquals(67, sheet.rootNote)
        assertNull(sheet.loop)
        assertEquals(2, WavReader.read(File(preserved.kitDir, kept.sampleFile)).channels)
        val board = model("BoardRoot", smpl = SmplChunk(67))
        val boardPad = BoardSheet.apply(board, 1, .7f)
        assertTrue(boardPad.sampleFile.startsWith("WIDE_1_"))
        assertNull(WavReader.readSmpl(File(board.kitDir, boardPad.sampleFile)))
    }

    @Test fun `same kit nested edits keep opaque prior sources but single pad imports refuse them`() {
        for (boardFirst in listOf(true, false)) {
            val m = model("Nested-$boardFirst")
            val original = m.pad(1)!!
            val store = PrintedPadStore(format())
            val lower = if (boardFirst) BoardSheet.apply(m, 1, .7f) else keep(store, m)
            val upper = if (boardFirst) keep(store, m) else BoardSheet.apply(m, 1, .7f)
            val sourceBefore = files(m.kitDir)
            val preflight = assertFailsWith<IllegalArgumentException> {
                if (boardFirst) store.requireImportable(m.kitDir, upper)
                else BoardSheet.requireImportable(m.kitDir, upper)
            }
            assertTrue(preflight.message.orEmpty().contains("complete kit folder copy"))
            assertEquals(sourceBefore, files(m.kitDir))
            val target = File(temp, "Target-$boardFirst").apply { mkdirs() }
            File(m.kitDir, upper.sampleFile).copyTo(File(target, "copied.wav"))
            val copied = upper.copy(sampleFile = "copied.wav")
            val before = files(target)
            val error = assertFailsWith<IllegalArgumentException> {
                if (boardFirst) store.importPad(m.kitDir, target, upper, copied)
                else BoardSheet.importPad(m.kitDir, target, upper, copied)
            }
            assertTrue(error.message.orEmpty().contains("complete kit folder copy"))
            assertEquals(before, files(target))
            assertEquals(upper, m.pad(1))
            val removed = if (boardFirst) store.remove(m, 1) else BoardSheet.remove(m, 1)
            assertEquals(lower, removed)
            val restored = if (boardFirst) store.undo(m, 1) else BoardSheet.undo(m, 1)
            assertEquals(upper, restored)
            if (boardFirst) {
                store.remove(m, 1)
                assertEquals(original, BoardSheet.remove(m, 1))
            } else {
                BoardSheet.remove(m, 1)
                assertEquals(original, store.remove(m, 1))
            }
        }
    }

    @Test fun `a present malformed marker is strict and storage cannot leave its namespace`() {
        for (namespace in listOf("../outside", ".", "..", "bad/folder", "bad\\folder"))
            assertFailsWith<IllegalArgumentException> { PrintedPadStore(format(namespace)) }
        val store = PrintedPadStore(format())
        assertNull(store.descriptor(null))
        assertNull(store.descriptor(opaqueRecipe()))
        val malformed = JsonValue.Obj(linkedMapOf("aquariumPad" to JsonValue.Num(1.0)))
        assertFailsWith<IllegalArgumentException> { store.descriptor(malformed) }
        val m = model("Malformed", recipe = malformed)
        val before = files(m.kitDir)
        assertFailsWith<IllegalArgumentException> { store.preview(m, 1) { it } }
        assertFailsWith<IllegalArgumentException> { keep(store, m) }
        assertEquals(before, files(m.kitDir))
    }
}
