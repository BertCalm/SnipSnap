package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic package/encoding/admission evidence only; this does not prove native sound or history. */
class MurmurSelectedInstrumentPackageTest {
    private val holders = mutableListOf<File>()
    @AfterTest fun cleanUp() { holders.forEach { it.deleteRecursively() } }
    private fun holder() = Files.createTempDirectory("murmur-selected-test-").toFile().also { holders.add(it) }
    private fun spec(id: String = MurmurKeptSources.STEADY_C2) = MurmurSelectedInstrumentMaker.Spec(MurmurKeptSources.byId(id).factoryPatch())
    private fun build(id: String = MurmurKeptSources.STEADY_C2): Pair<File, MurmurSelectedInstrumentMaker.Packet> {
        val dest = File(holder(), "packet")
        val packet = MurmurSelectedInstrumentMaker.writeNewPackageUsing("KEPT TEST", spec(id), dest,
            { false }, { _, _ -> }, MurmurSelectedSynthetic::capture)
        return dest to packet
    }
    private fun metadata(root: File) = File(root, "${Mpc3TrackWriter.trackDataDirName("KEPT TEST")}/${MurmurSelectedInstrumentMaker.SOURCES}")

    @Test
    fun `all three synthetic packets preserve full source profiles bounded descriptors and exact executable PCM24 replay`() {
        for (source in MurmurKeptSources.all()) {
            val (root, packet) = build(source.id)
            val checked = MurmurSelectedInstrumentMaker.validatePacket("KEPT TEST", root)
            assertEquals(packet.program, checked.program)
            assertEquals(if (source.id == MurmurKeptSources.STEADY_C2) 21 else 63, checked.rows.size)
            assertEquals(source.supportedMidi.first, checked.program.keygroups.first().lowNote)
            assertEquals(source.supportedMidi.last, checked.program.keygroups.last().highNote)
            assertTrue(checked.program.explicitNoteOn)
            assertEquals(com.snipsnap.xpm.VoiceMode.POLY, checked.program.voiceMode)
            assertEquals(.35f, checked.program.volumeRelease)
            for (group in checked.program.keygroups) {
                assertEquals(listOf(1..42, 43..84, 85..127), group.layers.map { it.velStart..it.velEnd })
                assertTrue(group.layers.all { if (checked.spec.held) it.loopStartFrame == 512L else it.loopStartFrame == 0L })
            }
            val record = Json.parse(metadata(root).readText()).obj()
            assertEquals(source.toJsonValue(), record.getValue("selectedSource"))
            assertEquals(source.factoryPatch(), checked.spec.sourcePatch())
            assertEquals(checked.rows.size, MurmurSelectedInstrumentMaker.replayAllUsing("KEPT TEST", root,
                { false }, { _, _ -> }, MurmurSelectedSynthetic::capture))
            val data = File(root, Mpc3TrackWriter.trackDataDirName("KEPT TEST"))
            val last = WavReader.read(File(data, checked.rows.first().sample)).samples.last()
            if (checked.spec.held) assertTrue(last != 0f, "a held cycle's EOF must not receive a terminal fade")
            else assertEquals(0f, last)
        }
    }

    @Test
    fun `fresh shelf admission preserves siblings but packet validation never accepts unrelated root entries`() {
        val shelf = holder()
        val sibling = File(shelf, "unrelated-neighbor.txt").apply { writeText("keep sibling") }
        val packet = MurmurSelectedInstrumentMaker.exportFreshUsing("KEPT TEST", spec(), shelf,
            { false }, { _, _ -> }, InstrumentPackageAdmission.Hooks(), MurmurSelectedSynthetic::capture)
        assertEquals("keep sibling", sibling.readText())
        assertEquals(packet.program, MurmurSelectedInstrumentMaker.validateOnShelf("KEPT TEST", shelf).program)
        val strict = assertFailsWith<IllegalArgumentException> { MurmurSelectedInstrumentMaker.validatePacket("KEPT TEST", shelf) }
        assertTrue(strict.message.orEmpty().contains("root entry"))
        assertEquals(21, MurmurSelectedInstrumentMaker.replayAllUsing("KEPT TEST", shelf, { false }, { _, _ -> },
            MurmurSelectedSynthetic::capture, onShelf = true))
        assertFailsWith<IllegalArgumentException> {
            MurmurSelectedInstrumentMaker.replayAllUsing("KEPT TEST", shelf, { false }, { _, _ -> }, MurmurSelectedSynthetic::capture)
        }
        val conflict = assertFailsWith<IllegalArgumentException> {
            MurmurSelectedInstrumentMaker.exportFreshUsing("KEPT TEST", spec(), shelf, { false }, { _, _ -> },
                InstrumentPackageAdmission.Hooks(), MurmurSelectedSynthetic::capture)
        }
        assertTrue(conflict.message.orEmpty().contains("already exists"))
        assertEquals("keep sibling", sibling.readText())
    }

    @Test
    fun `each of the three publication move failures rolls back only the new owned package`() {
        for (failAfter in 1..3) {
            val shelf = holder()
            File(shelf, "keep.txt").writeText("preserve")
            var moves = 0
            val error = assertFailsWith<IllegalStateException> {
                MurmurSelectedInstrumentMaker.exportFreshUsing("KEPT TEST", spec(), shelf, { false }, { _, _ -> },
                    InstrumentPackageAdmission.Hooks(moved = { if (++moves == failAfter) error("move-$failAfter") }), MurmurSelectedSynthetic::capture)
            }
            assertEquals("move-$failAfter", error.message)
            assertEquals(failAfter, moves)
            assertEquals(listOf("keep.txt"), shelf.listFiles()!!.map { it.name })
            assertEquals("preserve", File(shelf, "keep.txt").readText())
        }
    }

    @Test
    fun `cancellation after a streamed cell leaves no packet or stale shelf entry`() {
        val shelf = holder()
        var stop = false
        var rendered = 0
        assertFailsWith<CancellationException> {
            MurmurSelectedInstrumentMaker.exportFreshUsing("KEPT TEST", spec(), shelf, { stop },
                { done, _ -> rendered = done; stop = true }, InstrumentPackageAdmission.Hooks(), MurmurSelectedSynthetic::capture)
        }
        assertEquals(1, rendered)
        assertTrue(shelf.listFiles()!!.isEmpty())
        assertFalse(InstrumentStore.sidecar(shelf, "KEPT TEST").exists())
    }

    @Test
    fun `cancellation during every publication move rolls back the complete new package`() {
        for (cancelAfter in 1..3) {
            val shelf = holder()
            var stop = false
            var moves = 0
            assertFailsWith<CancellationException> {
                MurmurSelectedInstrumentMaker.exportFreshUsing("KEPT TEST", spec(), shelf, { stop }, { _, _ -> },
                    InstrumentPackageAdmission.Hooks(moved = { if (++moves == cancelAfter) stop = true }), MurmurSelectedSynthetic::capture)
            }
            assertEquals(cancelAfter, moves)
            assertTrue(shelf.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun `unknown source profile grid path policy and loop tampering refuse for their named guards`() {
        val (root, _) = build()
        val changes = listOf<Pair<String, (JsonValue.Obj) -> JsonValue.Obj>>(
            "kind/version" to { o -> replaced(o, "version", JsonValue.Num(99.0)) },
            "historical selection" to { o ->
                val selected = o.entries.getValue("selectedSource") as JsonValue.Obj
                val reference = selected.entries.getValue("reference") as JsonValue.Obj
                replaced(o, "selectedSource", replaced(selected, "reference", replaced(reference, "rawOutputGain", JsonValue.Num(9.0))))
            },
            "selected domain" to { o -> replaced(o, "highMidi", JsonValue.Num(96.0)) },
            "adaptation policy" to { o -> replaced(o, "touchPolicy", JsonValue.Str("RELATIVE_TO_SOURCE")) },
            "path or cell label" to { o -> rowChange(o) { replaced(it, "sample", JsonValue.Str("../escape.wav")) } },
            "relabelled" to { o -> rowChange(o) { replaced(it, "touchIndex", JsonValue.Num(2.0)) } },
            "finite cell must not loop" to { o -> rowChange(o) { replaced(it, "loopStartFrame", JsonValue.Num(256.0)) } },
            "frame bounds" to { o -> rowChange(o) { replaced(it, "frameCount", JsonValue.Num(1000.0)) } },
            "cell kind/version" to { o -> rowChange(o) { row ->
                val cell = row.entries.getValue("recipe") as JsonValue.Obj
                replaced(row, "recipe", replaced(cell, "kind", JsonValue.Str("PAD_RECIPE")))
            } },
            "Duplicate or out-of-grid" to { o ->
                val rows = o.entries.getValue("samples").arr().toMutableList(); rows[1] = rows[0]
                replaced(o, "samples", JsonValue.Arr(rows))
            },
        )
        for ((guard, change) in changes) assertMetadataRefuses(root, guard, change)
        val file = metadata(root)
        val original = file.readText()
        try {
            val value = Json.parse(original) as JsonValue.Obj
            file.writeText(Json.write(JsonValue.Obj(value.entries + ("unknown" to JsonValue.Bool(true)))))
            val error = assertFailsWith<IllegalArgumentException> { MurmurSelectedInstrumentMaker.validatePacket("KEPT TEST", root) }
            assertTrue(error.message.orEmpty().contains("Unknown or incomplete"))
        } finally { file.writeText(original) }
        assertEquals(21, MurmurSelectedInstrumentMaker.validatePacket("KEPT TEST", root).rows.size)
    }

    @Test
    fun `changed PCM sampler sheet descriptor files and undeclared closure never validate`() {
        val (root, packet) = build(MurmurKeptSources.HELD_WIRE)
        val data = File(root, Mpc3TrackWriter.trackDataDirName("KEPT TEST"))
        val wav = File(data, packet.rows.first().sample)
        val bytes = wav.readBytes()
        try {
            wav.writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertRefuses(root, "WAV bytes")
        } finally { wav.writeBytes(bytes) }
        val xty = File(root, "KEPT TEST.xty"); val native = xty.readBytes()
        try { xty.appendText("changed"); assertRefuses(root, "native descriptor") } finally { xty.writeBytes(native) }
        val extraData = File(data, "extra.json")
        try { extraData.writeText("{}"); assertRefuses(root, "TrackData entry") } finally { extraData.delete() }
        val extraRoot = File(root, "extra.txt")
        try { extraRoot.writeText("extra"); assertRefuses(root, "root entry") } finally { extraRoot.delete() }
        val outside = File(holder(), "original.wav").apply { writeBytes(bytes) }
        wav.delete(); Files.createSymbolicLink(wav.toPath(), outside.toPath())
        try { assertRefuses(root, "symlink") } finally { Files.delete(wav.toPath()); wav.writeBytes(bytes) }
        assertEquals(packet.rows.size, MurmurSelectedInstrumentMaker.validatePacket("KEPT TEST", root).rows.size)
    }

    @Test
    fun `rehashed sampler sheet tampering cannot relabel an actual cell root`() {
        val (root, packet) = build(MurmurKeptSources.HELD_WIRE)
        val data = File(root, Mpc3TrackWriter.trackDataDirName("KEPT TEST"))
        val row = packet.rows.first()
        val wav = File(data, row.sample)
        val before = wav.readBytes()
        val sourceFile = metadata(root)
        val sourceBefore = sourceFile.readText()
        try {
            val samples = WavReader.read(wav)
            WavWriter.write(wav, samples, WavWriter.BitDepth.PCM_24,
                smpl = SmplChunk(row.cell.midi + 1, SmplChunk.Loop(row.loopStartFrame.toLong(), row.loopEndFrameExclusive.toLong())))
            val decoded = WavReader.read(wav)
            val obj = Json.parse(sourceBefore) as JsonValue.Obj
            sourceFile.writeText(Json.write(rowChange(obj) { changed ->
                replaced(replaced(replaced(changed, "wavSha256", JsonValue.Str(hash(wav.readBytes()))),
                    "decodedPcmSha256", JsonValue.Str(MurmurSelectedInstrumentSource.floatSha256(decoded.samples))),
                    "encodedSeamError", JsonValue.Num(Keys.seamError(decoded.samples, row.loopStartFrame)))
            }))
            assertRefuses(root, "WAV root/loop sheet")
        } finally { wav.writeBytes(before); sourceFile.writeText(sourceBefore) }
        assertEquals(packet.rows.size, MurmurSelectedInstrumentMaker.validatePacket("KEPT TEST", root).rows.size)
    }

    @Test
    fun `oversized metadata is refused before JSON parsing`() {
        val (root, _) = build()
        val file = metadata(root)
        val before = file.readText()
        try { file.writeText(" ".repeat(8 * 1024 * 1024 + 1)); assertRefuses(root, "metadata size") }
        finally { file.writeText(before) }
    }

    @Test
    fun `captured source and Float evidence must agree before any package can become visible`() {
        val dest = File(holder(), "packet")
        val error = assertFailsWith<IllegalArgumentException> {
            MurmurSelectedInstrumentMaker.writeNewPackageUsing("KEPT TEST", spec(), dest, { false }, { _, _ -> }) { recipe ->
                val capture = MurmurSelectedSynthetic.capture(recipe)
                capture.copy(snip = capture.snip.copy(samples = capture.snip.samples.copyOf().also { it[1] += .01f }))
            }
        }
        assertTrue(error.message.orEmpty().contains("Float evidence"))
        assertFalse(dest.exists())
        val other = File(holder(), "packet")
        val mismatch = assertFailsWith<IllegalArgumentException> {
            MurmurSelectedInstrumentMaker.writeNewPackageUsing("KEPT TEST", spec(), other, { false }, { _, _ -> }) { recipe ->
                MurmurSelectedSynthetic.capture(recipe).copy(recipe = MurmurSelectedInstrumentSource.cellRecipe(recipe.sourcePatch(), recipe.midi, 2, recipe.releaseSeconds))
            }
        }
        assertTrue(mismatch.message.orEmpty().contains("source recipe differs"))
        assertFalse(other.exists())
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertRefuses(root: File, fragment: String) {
        val failure = assertFailsWith<Exception> { MurmurSelectedInstrumentMaker.validatePacket("KEPT TEST", root) }
        assertTrue(failure.message.orEmpty().contains(fragment), "Expected $fragment, got ${failure.message}")
    }
    private fun assertMetadataRefuses(root: File, fragment: String, change: (JsonValue.Obj) -> JsonValue.Obj) {
        val file = metadata(root); val original = file.readText()
        try { file.writeText(Json.write(change(Json.parse(original) as JsonValue.Obj))); assertRefuses(root, fragment) }
        finally { file.writeText(original) }
    }
    private fun replaced(obj: JsonValue.Obj, field: String, value: JsonValue) = JsonValue.Obj(LinkedHashMap(obj.entries).apply { put(field, value) })
    private fun rowChange(obj: JsonValue.Obj, change: (JsonValue.Obj) -> JsonValue.Obj): JsonValue.Obj {
        val rows = obj.entries.getValue("samples").arr().toMutableList()
        rows[0] = change(rows[0] as JsonValue.Obj)
        return replaced(obj, "samples", JsonValue.Arr(rows))
    }
}
