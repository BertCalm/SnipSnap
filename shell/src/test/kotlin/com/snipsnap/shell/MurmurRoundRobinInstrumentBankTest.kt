package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic transport/player fixtures only. No native renderer, sound approval or device inference. */
class MurmurRoundRobinInstrumentBankTest {
    private val holders = mutableListOf<File>()
    @AfterTest fun cleanUp() { holders.forEach { it.deleteRecursively() } }
    private fun holder() = Files.createTempDirectory("murmur-rr-bank-test-").toFile().also { holders += it }
    private fun spec(id: String = MurmurKeptSources.STEADY_C2) =
        MurmurRoundRobinInstrumentBank.Spec(MurmurKeptSources.byId(id).factoryPatch())
    private fun build(id: String = MurmurKeptSources.STEADY_C2): Pair<File, MurmurRoundRobinInstrumentBank.Packet> {
        val root = File(holder(), "bank")
        return root to MurmurRoundRobinInstrumentBank.writeNewUsing(NAME, spec(id), root, { false }, { _, _ -> }, ::capture)
    }
    private fun takeRoot(root: File, take: Int = 0) = File(root, MurmurRoundRobinInstrumentBank.takeFolder(take))
    private fun takeName(take: Int = 0) = MurmurRoundRobinInstrumentBank.programName(NAME, take)
    private fun data(root: File, take: Int = 0) = File(takeRoot(root, take), Mpc3TrackWriter.trackDataDirName(takeName(take)))
    private fun metadata(root: File, take: Int = 0) = File(data(root, take), MurmurRoundRobinTakePackage.SOURCES)
    private fun manifest(root: File) = File(root, MurmurRoundRobinInstrumentBank.MANIFEST)
    private fun drain(player: MurmurRoundRobinPlayer, frames: Int = 32768) {
        var left = frames
        while (left > 0) { val n = minOf(left, 4096); player.render(FloatArray(n * 2), n); left -= n }
    }
    private fun capture(recipe: MurmurRoundRobinInstrumentSource.CellRecipe): MurmurRoundRobinInstrumentSource.Capture {
        val held = recipe.outputPolicy == MurmurRoundRobinInstrumentSource.OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1
        val amplitude = .02 * (recipe.take + 1) * (recipe.touchIndex + 1)
        val raw = FloatArray(512) { (amplitude * sin(2 * PI * it / 64)).toFloat() }
        val samples = if (held) raw + raw else raw.copyOf().also { it[it.lastIndex] = 0f }
        if (held) for (i in 0 until recipe.onsetFrames) samples[i] *= ((1 - cos(PI * i / (recipe.onsetFrames - 1))) * .5).toFloat()
        val start = if (held) raw.size else 0
        return MurmurRoundRobinInstrumentSource.Capture(recipe, Snip(samples, 1, WavWriter.MPC_SAMPLE_RATE), start, samples.size,
            MurmurRoundRobinInstrumentSource.floatSha256(raw), MurmurRoundRobinInstrumentSource.floatSha256(if (held) raw else floatArrayOf()),
            MurmurRoundRobinInstrumentSource.floatSha256(samples), if (held) Keys.seamError(samples, start) else 0.0,
            Keys.midiHz(recipe.midi).toDouble())
    }

    @Test fun `all selected shapes retain complete absolute touch by four take grids and one semitone root reach`() {
        for (source in MurmurKeptSources.all()) {
            val spec = spec(source.id)
            val zones = MurmurRoundRobinInstrumentBank.zones(spec)
            assertEquals(source.supportedMidi.toList(), zones.flatMap { (it.low..it.high).toList() })
            assertTrue(zones.all { z -> (z.low..z.high).all { kotlin.math.abs(it - z.root) <= 1 } })
            val recipes = (0..3).flatMap { take -> MurmurRoundRobinTakePackage.recipes(spec.takeSpec(take)).values }
            assertEquals(zones.size * 3 * 4, recipes.size)
            for (root in zones.map { it.root }) for (touch in 0..2) {
                val cells = recipes.filter { it.midi == root && it.touchIndex == touch }
                assertEquals(listOf(0, 1, 2, 3), cells.map { it.take })
                cells.forEach { cell ->
                    assertEquals(spec.sourcePatch(), cell.sourcePatch())
                    assertEquals(listOf(.35f, .65f, 1f)[touch], cell.cellPatch().velocity)
                    assertEquals(spec.sourcePatch().copy(midi = root, velocity = listOf(.35f, .65f, 1f)[touch]), cell.cellPatch())
                }
            }
        }
    }

    @Test fun `saved bank reload binds every fixed take descriptor recipe PCM and executable synthetic replay`() {
        val (root, made) = build()
        val loaded = MurmurRoundRobinInstrumentBank.validate(NAME, root)
        assertEquals(84, loaded.cellCount)
        assertEquals(4, loaded.takes.size)
        assertEquals(made.spec.sourcePatchJson, loaded.spec.sourcePatchJson)
        assertEquals(listOf(0, 1, 2, 3), loaded.takes.map { it.spec.take })
        for ((take, packet) in loaded.takes.withIndex()) {
            assertEquals(21, packet.rows.size)
            assertTrue(packet.rows.all { it.cell.take == take })
            assertTrue(packet.program.keygroups.all { it.layers.map { layer -> layer.velStart..layer.velEnd } == listOf(1..42, 43..84, 85..127) })
            assertEquals(21, MurmurRoundRobinTakePackage.replayAllUsing(takeName(take), takeRoot(root, take), { false }, { _, _ -> }, ::capture))
        }
        assertEquals(4, loaded.takes.map { it.rows.first().wavSha256 }.distinct().size)
        assertEquals(MurmurRoundRobinInstrumentBank.EXPORT_POLICY, Json.parse(manifest(root).readText()).obj().getValue("exportPolicy").str())
    }

    @Test fun `four fixed take exports preserve exact WAVs and velocity windows while shelf siblings survive`() {
        val (root, packet) = build()
        val shelf = holder()
        File(shelf, "keep.txt").writeText("preserve")
        for (take in 0..3) {
            val exported = MurmurRoundRobinInstrumentBank.exportFixedTake(NAME, root, take, shelf)
            val read = InstrumentStore.read(InstrumentStore.sidecar(shelf, takeName(take)))
            assertEquals(packet.takes[take].program, read.toProgram())
            assertEquals(exported.program, MurmurRoundRobinTakePackage.validateOnShelf(takeName(take), shelf, spec().takeSpec(take)).program)
            for (row in exported.rows) {
                val copied = File(File(shelf, Mpc3TrackWriter.trackDataDirName(takeName(take))), row.sample)
                assertTrue(File(data(root, take), row.sample).readBytes().contentEquals(copied.readBytes()))
            }
        }
        assertEquals("preserve", File(shelf, "keep.txt").readText())
        assertEquals(4, InstrumentStore.list(shelf).size)
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.exportFixedTake(NAME, root, 0, shelf) }
    }

    @Test fun `old v1 package remains byte exact and neither format silently admits the other`() {
        val oldRoot = File(holder(), "legacy")
        val patch = spec().sourcePatch()
        MurmurSelectedInstrumentMaker.writeNewPackageUsing("OLD KEPT", MurmurSelectedInstrumentMaker.Spec(patch), oldRoot,
            { false }, { _, _ -> }) { recipe ->
            val rr = capture(MurmurRoundRobinInstrumentSource.cellRecipe(recipe.sourcePatch(), recipe.midi, recipe.touchIndex, 0, recipe.releaseSeconds))
            MurmurSelectedInstrumentSource.Capture(recipe, rr.snip, rr.loopStartFrame, rr.loopEndFrameExclusive,
                rr.rawSampleSha256, rr.rawPreviousSha256, rr.finishedFloatSha256, rr.seamError, rr.rootHz)
        }
        val before = treeHashes(oldRoot)
        val (root, _) = build()
        assertEquals(21, MurmurSelectedInstrumentMaker.validatePacket("OLD KEPT", oldRoot).rows.size)
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinTakePackage.validatePacket("OLD KEPT", oldRoot) }
        assertFailsWith<IllegalArgumentException> { MurmurSelectedInstrumentMaker.validatePacket(takeName(), takeRoot(root)) }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, oldRoot) }
        assertEquals(before, treeHashes(oldRoot))
    }

    @Test fun `missing duplicate fractional and relabelled takes cannot enter a saved bank`() {
        val (root, _) = build()
        mutateAndRefuse(manifest(root), root) { o -> replace(o, "cursorPolicy", JsonValue.Str("GLOBAL")) }
        mutateAndRefuse(manifest(root), root) { o -> replace(o, "exportPolicy", JsonValue.Str("MPC_AUTOMATIC_RR")) }
        mutateAndRefuse(manifest(root), root) { o -> replace(o, "takes", JsonValue.Arr(o.entries.getValue("takes").arr().dropLast(1))) }
        mutateAndRefuse(metadata(root), root) { o -> replace(o, "take", JsonValue.Num(.5)) }
        mutateAndRefuse(metadata(root), root) { o -> replace(o, "take", JsonValue.Num(1.0)) }
        mutateAndRefuse(metadata(root), root) { o ->
            val rows = o.entries.getValue("samples").arr().toMutableList(); rows[1] = rows[0]
            replace(o, "samples", JsonValue.Arr(rows))
        }
        mutateAndRefuse(metadata(root), root) { o ->
            val rows = o.entries.getValue("samples").arr().toMutableList(); rows[0] = replace(rows[0] as JsonValue.Obj, "take", JsonValue.Num(3.0))
            replace(o, "samples", JsonValue.Arr(rows))
        }
        assertEquals(84, MurmurRoundRobinInstrumentBank.validate(NAME, root).cellCount)
    }

    @Test fun `changed PCM descriptors symlinks and undeclared packet entries fail closed`() {
        val (root, packet) = build()
        val wav = File(data(root), packet.takes.first().rows.first().sample)
        val original = wav.readBytes()
        try {
            wav.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, root) }
            assertFailsWith<IllegalArgumentException> { MurmurRoundRobinPlayer.load(NAME, root) }
        } finally { wav.writeBytes(original) }
        val xty = File(takeRoot(root), "${takeName()}.xty"); val descriptor = xty.readBytes()
        try { xty.appendText("changed"); assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, root) } }
        finally { xty.writeBytes(descriptor) }
        val extra = File(root, "undeclared")
        try { extra.writeText("x"); assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, root) } }
        finally { extra.delete() }
        val external = File(holder(), "outside.wav").apply { writeBytes(original) }
        wav.delete(); Files.createSymbolicLink(wav.toPath(), external.toPath())
        try { assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, root) } }
        finally { Files.delete(wav.toPath()); wav.writeBytes(original) }
        assertEquals(84, MurmurRoundRobinInstrumentBank.validate(NAME, root).cellCount)
    }

    @Test fun `cancelled or corrupted private banks never become visible and preserve existing destinations`() {
        val holder = holder(); val root = File(holder, "bank")
        var stopped = false; var cells = 0
        assertFailsWith<CancellationException> {
            MurmurRoundRobinInstrumentBank.writeNewUsing(NAME, spec(), root, { stopped }, { done, _ -> cells = done; stopped = true }, ::capture)
        }
        assertEquals(1, cells); assertFalse(root.exists()); assertTrue(holder.listFiles()!!.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinInstrumentBank.writeNewUsing(NAME, spec(), root, { false }, { _, _ -> }, ::capture,
                staged = { File(it, "unowned").writeText("bad") })
        }
        assertFalse(root.exists()); assertTrue(holder.listFiles()!!.isEmpty())
        root.mkdir(); File(root, "keep").writeText("keep")
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.writeNewUsing(NAME, spec(), root, { false }, { _, _ -> }, ::capture) }
        assertEquals("keep", File(root, "keep").readText())
    }

    @Test fun `every fixed take shelf move failure rolls back only its new owned entries`() {
        val (root, _) = build()
        for (failAfter in 1..3) {
            val shelf = holder(); File(shelf, "keep").writeText("keep")
            var moves = 0
            assertFailsWith<IllegalStateException> {
                MurmurRoundRobinInstrumentBank.exportFixedTake(NAME, root, 2, shelf,
                    InstrumentPackageAdmission.Hooks(moved = { if (++moves == failAfter) error("injected move failure") }))
            }
            assertEquals(failAfter, moves)
            assertEquals(listOf("keep"), shelf.listFiles()!!.map { it.name })
            assertEquals("keep", File(shelf, "keep").readText())
        }
    }

    @Test fun `one pitch zone shares rotation across touch levels while neighboring zones and reload are independent`() {
        val (root, _) = build()
        MurmurRoundRobinPlayer.load(NAME, root).use { player ->
            val first = assertNotNull(player.noteOn(36, .2f)); val second = assertNotNull(player.noteOn(37, .65f))
            val neighbor = assertNotNull(player.noteOn(39, 1f)); val third = assertNotNull(player.noteOn(36, .9f))
            assertEquals(listOf(0, 1, 0, 2), listOf(first.take, second.take, neighbor.take, third.take))
            assertEquals(listOf(0, 1, 2, 2), listOf(first.touchIndex, second.touchIndex, neighbor.touchIndex, third.touchIndex))
            assertEquals(.2f * InstrumentEngine.VOICE_LEVEL, first.hit.gain)
            assertTrue(second.hit.pitchRatio > 1)
            assertNull(player.noteOn(35, .5f)); assertNull(player.noteOn(36, 0f)); assertNull(player.noteOn(36, Float.NaN))
            assertEquals(3, assertNotNull(player.noteOn(36, .5f)).take)
            drain(player)
            assertEquals(0, assertNotNull(player.noteOn(36, .5f)).take)
            player.resetRoundRobin(); assertEquals(1, player.activeNotes.size)
            assertEquals(0, assertNotNull(player.noteOn(36, .5f)).take)
            player.allOff(); drain(player); assertTrue(player.activeNotes.isEmpty())
            assertEquals(0, assertNotNull(player.noteOn(36, .5f)).take)
        }
        MurmurRoundRobinPlayer.load(NAME, root).use { player -> assertEquals(0, assertNotNull(player.noteOn(36, .5f)).take) }
    }

    @Test fun `invalid chords and full voice capacity consume no takes while valid same zone tones advance in order`() {
        val (root, _) = build()
        MurmurRoundRobinPlayer.load(NAME, root).use { player ->
            assertTrue(player.noteOnChord(listOf(36, 200), .65f).isEmpty())
            assertTrue(player.noteOnChord(listOf(36, 36), .65f).isEmpty())
            val chord = player.noteOnChord(listOf(36, 37, 39), .65f)
            assertEquals(listOf(0, 1, 0), chord.map { it.take })
            assertEquals(3, player.activeNotes.size)
            assertEquals(2, assertNotNull(player.noteOn(36, .65f)).take)
            assertEquals(1, assertNotNull(player.noteOn(39, .65f)).take)
            player.allOff(); drain(player)
            assertEquals(8, player.noteOnChord((36..43).toList(), .65f).size)
            assertNull(player.noteOn(44, .65f))
            assertTrue(player.noteOnChord(listOf(44, 45), .65f).isEmpty())
            assertEquals(8, player.activeNotes.size)
            drain(player)
            assertEquals(0, assertNotNull(player.noteOn(44, .65f)).take)
            player.close(); assertNull(player.noteOn(36, .65f)); assertTrue(player.activeNotes.isEmpty())
        }
    }

    @Test fun `saved PCM playback preserves live gain and previous take tails until note off reaches every engine`() {
        val (root, packet) = build()
        MurmurRoundRobinPlayer.load(NAME, root).use { player ->
            val first = assertNotNull(player.noteOn(36, .2f))
            val pcm = WavReader.read(File(data(root, 0), packet.takes[0].rows.first().sample)).samples
            val out = FloatArray(4); player.render(out, 2)
            assertEquals(pcm[1] * first.hit.gain, out[2], 0.0000001f)
            val second = assertNotNull(player.noteOn(36, .65f))
            assertEquals(1, second.take); assertEquals(2, player.activeNotes.size)
            val prior = pcm[2] * first.hit.gain
            val next = FloatArray(2); player.render(next, 1)
            assertEquals(prior, next[0], 0.0000001f) // New take begins at zero; old tail must remain.
            player.noteOff(36); drain(player)
            assertTrue(player.activeNotes.isEmpty())
            val silent = FloatArray(128); player.render(silent, 64); assertTrue(silent.all { it == 0f })
        }
    }

    @Test fun `held saved cells retain real format loop markers and reference player key up finishes every take`() {
        val (root, packet) = build(MurmurKeptSources.HELD_WIRE)
        assertEquals(252, packet.cellCount)
        assertTrue(packet.takes.flatMap { it.rows }.all { it.loopStartFrame == 512 && it.loopEndFrameExclusive == 1024 })
        MurmurRoundRobinPlayer.load(NAME, root).use { player ->
            assertEquals(0, assertNotNull(player.noteOn(60, .65f)).take)
            drain(player, 2048); assertEquals(1, player.activeNotes.size)
            assertEquals(1, assertNotNull(player.noteOn(60, .65f)).take)
            drain(player, 2048); assertEquals(2, player.activeNotes.size)
            player.noteOff(60); drain(player); assertTrue(player.activeNotes.isEmpty())
            val out = FloatArray(512); player.render(out, 256); assertTrue(out.all { it == 0f })
            player.allOff(); assertEquals(0, assertNotNull(player.noteOn(60, .65f)).take)
        }
    }

    private fun replace(obj: JsonValue.Obj, field: String, value: JsonValue) = JsonValue.Obj(LinkedHashMap(obj.entries).apply { put(field, value) })
    private fun mutateAndRefuse(file: File, root: File, change: (JsonValue.Obj) -> JsonValue.Obj) {
        val before = file.readText()
        try { file.writeText(Json.write(change(Json.parse(before) as JsonValue.Obj))); assertFailsWith<Exception> { MurmurRoundRobinInstrumentBank.validate(NAME, root) } }
        finally { file.writeText(before) }
    }
    private fun treeHashes(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to
        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) } }
    companion object { private const val NAME = "RR TEST" }
}
