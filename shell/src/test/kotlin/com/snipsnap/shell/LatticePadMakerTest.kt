package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonException
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Lattice
import com.snipsnap.synth.LatticePatch
import com.snipsnap.synth.LatticeVoice
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LatticePadMakerTest {
    private val root = kotlin.io.path.createTempDirectory("lattice-keys-package").toFile()
    private val patch = LatticePatch("MY TRACE", LatticeVoice.TRACE,
        Lattice.defaults(LatticeVoice.TRACE) + mapOf("TUNE" to .625f, "SPEED" to .23f, "HOLD" to .7f),
        midi = 57, velocity = .42f)
    private val spec = LatticePadMaker.Spec(patch, .37f)
    // Transport tests use distinct markers; synthesis/convergence has its own independent tests.
    private val transportNotes = List(9) { index ->
        KeyNote(Snip(FloatArray(256 + index * 16) { .05f * kotlin.math.sin(it * .1).toFloat() }, 1, 44_100),
            96L + index * 4)
    }

    @AfterTest
    fun cleanUp() { root.deleteRecursively() }

    @Test
    fun `held recipe preserves complete finite source and has an independent version`() {
        val restored = LatticePadMaker.Spec.fromJsonText(spec.toJsonText())
        assertEquals(spec, restored)
        assertEquals(patch.toJsonText(), restored.patch.toJsonText())
        assertEquals(.7f, restored.patch.macros.getValue("HOLD"), "keyboard capture must not rewrite finite HOLD")
        assertFailsWith<JsonException> {
            LatticePadMaker.Spec.fromJsonText(spec.toJsonText().replace("\"heldModel\": 1", "\"heldModel\": 999"))
        }
        assertFailsWith<JsonException> {
            LatticePadMaker.Spec.fromJsonText(spec.toJsonText().replaceFirst("\"version\": 1", "\"version\": 999"))
        }
        for (voice in LatticeVoice.entries.filter { it != LatticeVoice.TRACE }) {
            val unsupported = LatticePatch("SOURCE", voice, Lattice.defaults(voice))
            assertFalse(LatticePadMaker.supports(unsupported))
            assertFailsWith<IllegalArgumentException> { LatticePadMaker.Spec(unsupported) }
        }
        for (release in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 2f)) {
            assertFailsWith<IllegalArgumentException> { LatticePadMaker.Spec(patch, release) }
        }
    }

    @Test
    fun `package retains bounded C3 to C5 roots loops and release in every delivery format`() {
        val name = "TRACE KEYS"
        val program = LatticePadMaker.export(name, spec, transportNotes, root)
        val roots = (48..72 step 3).toList()
        assertEquals(roots, program.keygroups.map { it.rootNote })
        assertEquals((48..72).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertEquals(.37f, program.volumeRelease)
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        val xpm = File(data, "$name.xpm").readText()
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm)
        assertEquals(Mpc3TrackWriter().keygroupPayloadText(program), Acvs.read(File(root, "$name.xty")).payloadText,
            "MPC3 must not silently extend this measured pitch range to MIDI 0..127")
        val (_, instrument) = InstrumentStore.list(root).single()
        assertEquals(roots, instrument.zones.map { it.rootNote })
        assertEquals((48..72).toList(), instrument.zones.flatMap { (it.lowNote..it.highNote).toList() })
        assertEquals(.37f, instrument.release)
        assertEquals(spec, LatticePadMaker.Spec.fromJsonText(LatticePadMaker.recipeFile(root, name).readText()))
        for ((index, group) in program.keygroups.withIndex()) {
            val layer = group.layers.single()
            assertEquals(1, layer.velStart)
            assertEquals(127, layer.velEnd)
            assertEquals(transportNotes[index].loopStartFrame, layer.loopStartFrame)
            assertTrue("<SliceLoopStart>${layer.loopStartFrame}</SliceLoopStart>" in xpm)
            val wave = File(data, "${layer.sampleName}.wav")
            val smpl = assertNotNull(WavReader.readSmpl(wave))
            assertEquals(group.rootNote, smpl.rootNote)
            val loop = assertNotNull(smpl.loop)
            assertEquals(layer.loopStartFrame, loop.startFrame)
            assertEquals(layer.frameCount, loop.endFrameExclusive)
            assertEquals(layer.loopStartFrame, instrument.zones[index].loopStartFrame)
            val decoded = WavReader.read(wave)
            assertEquals(transportNotes[index].snip.frameCount, decoded.frameCount)
            assertTrue(decoded.samples.indices.all { abs(decoded.samples[it] - transportNotes[index].snip.samples[it]) < 2e-7f })
        }
    }

    @Test
    fun `real TRACE keeps sounding through sample wraps and note off releases with one velocity gain`() {
        val note = LatticePadMaker.renderZone(spec, 60)
        val zone = InstrumentStore.Zone(60, 60, 60, "trace.wav", note.snip.frameCount.toLong(), note.loopStartFrame)
        val instrument = InstrumentStore.Instrument("TRACE", spec.releaseSeconds, listOf(zone))
        val loaded = InstrumentEngine.Loaded(instrument, 44_100, listOf(note.snip.samples))
        val full = InstrumentEngine(loaded, 44_100)
        val half = InstrumentEngine(loaded, 44_100)
        assertTrue(full.noteOn(60, 1f))
        assertTrue(half.noteOn(60, .5f))
        val period = note.snip.frameCount - note.loopStartFrame.toInt()
        val frames = note.snip.frameCount + 2 * period + 137
        val heard = render(full, frames)
        val softer = render(half, frames)
        assertTrue(heard.any { abs(it) > .00001f })
        for (i in heard.indices) assertEquals(heard[i] * .5f, softer[i], 1e-7f,
            "capture gain is removed once and host velocity is applied once")
        val preview = LatticePadMaker.preview(spec)
        val previewHeldFrames = maxOf(44_100 * 3, note.snip.frameCount + period)
        for (i in 0 until minOf(previewHeldFrames, frames)) {
            assertEquals(heard[i * 2], preview.samples[i], 1e-7f,
                "sheet preview must use the same full-velocity layer gain as the exported instrument")
        }
        assertTrue(preview.samples.takeLast(882).all { abs(it) < 1e-5f })
        val afterFile = (note.snip.frameCount + 17) * 2
        val first = heard.copyOfRange(afterFile, afterFile + period * 2)
        val second = heard.copyOfRange(afterFile + period * 2, afterFile + period * 4)
        assertTrue(first.any { abs(it) > .00001f }, "the settled collector must remain audible")
        assertContentEquals(first, second, "playback must use the marked loop after EOF")
        full.noteOff(60)
        val released = render(full, ((instrument.release + .1f) * 44_100).toInt())
        assertTrue(released.takeLast(882).all { abs(it) < 1e-5f })
        assertTrue(full.activeNotes.isEmpty())
        assertEquals(57, spec.patch.midi)
        assertEquals(.625f, spec.patch.macros.getValue("TUNE"))
    }

    @Test
    fun `malformed zones and cancelled rendering cannot leave a partial package`() {
        assertFailsWith<CancellationException> { LatticePadMaker.renderZone(spec, 60) { true } }
        assertFailsWith<CancellationException> { LatticePadMaker.preview(spec) { true } }
        assertFailsWith<IllegalArgumentException> { LatticePadMaker.renderZone(spec, 61) }
        val bad = listOf(
            transportNotes.take(8),
            transportNotes.toMutableList().also { it[0] = it[0].copy(loopStartFrame = 0) },
            transportNotes.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(32), 2, 44_100), 8) },
            transportNotes.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(32), 1, 48_000), 8) },
            transportNotes.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(32) { Float.NaN }, 1, 44_100), 8) },
        )
        for (notes in bad) assertFailsWith<IllegalArgumentException> { LatticePadMaker.export("BAD", spec, notes, root) }
        for (name in listOf("../escape", "x".repeat(OneNote.MAX_NAME_LENGTH + 1))) {
            assertFailsWith<IllegalArgumentException> { LatticePadMaker.export(name, spec, transportNotes, root) }
        }
        assertTrue(root.listFiles()!!.isEmpty())
        LatticePadMaker.export("TRACE", spec, transportNotes, root)
        val files = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
        assertFailsWith<DestinationExists> { LatticePadMaker.export("TRACE", spec, transportNotes, root) }
        for ((path, bytes) in files) assertContentEquals(bytes, File(root, path).readBytes())
        // An orphan recipe must also refuse before creating descriptors or sample folders.
        LatticePadMaker.recipeFile(root, "ORPHAN").writeText(spec.toJsonText())
        assertFailsWith<DestinationExists> { LatticePadMaker.export("ORPHAN", spec, transportNotes, root) }
        assertFalse(File(root, "ORPHAN.xty").exists())
        assertFalse(File(root, Mpc3TrackWriter.trackDataDirName("ORPHAN")).exists())
    }

    private fun render(engine: InstrumentEngine, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(512)
        var position = 0
        while (position < frames) {
            val count = minOf(256, frames - position)
            engine.render(block, count)
            block.copyInto(out, position * 2, 0, count * 2)
            position += count
        }
        return out
    }
}
