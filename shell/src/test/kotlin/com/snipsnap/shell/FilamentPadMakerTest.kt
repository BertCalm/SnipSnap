package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.FilamentConfig
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentPresets
import com.snipsnap.synth.FilamentProfile
import com.snipsnap.synth.FilamentVoice
import com.snipsnap.synth.KeyNote
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real THREAD notes cross the package boundary once, then exercise both files and the player. */
class FilamentPadMakerTest {
    private val root = kotlin.io.path.createTempDirectory("filament-keys-package").toFile()

    @AfterTest
    fun cleanUp() { root.deleteRecursively() }

    private companion object {
        val patch = FilamentPresets.forVoice(FilamentVoice.THREAD).first().copy(
            name = "MY KEPT GLASS", midi = 57, velocity = .61f,
            config = FilamentConfig(seed = Int.MIN_VALUE + 7, profile = FilamentProfile.LATE),
        )
        val spec = FilamentPadMaker.Spec(patch, releaseSeconds = .37f)

        // Three native notes, shared by the transport tests rather than rendered again per assertion.
        val notes by lazy { FilamentPadMaker.zoneMidis(spec).map { FilamentPadMaker.renderZone(spec, it) } }
    }

    @Test
    fun `three native roots tile C3 to C5 and keep one host controlled velocity layer`() {
        val (program, samples) = FilamentPadMaker.assemble("FILAMENT THREAD", spec, notes)
        assertEquals(listOf(48, 60, 72), program.keygroups.map { it.rootNote })
        assertEquals(listOf(48..54, 55..66, 67..72), program.keygroups.map { it.lowNote..it.highNote })
        assertEquals(.37f, program.volumeRelease)
        assertEquals(3, samples.size)
        program.keygroups.forEachIndexed { index, group ->
            val layer = group.layers.single()
            assertEquals(1, layer.velStart)
            assertEquals(127, layer.velEnd)
            assertEquals(notes[index].loopStartFrame, layer.loopStartFrame)
            assertEquals(notes[index].snip.frameCount.toLong(), layer.frameCount)
            assertContentEquals(notes[index].snip.samples, samples.getValue(layer.sampleName).samples,
                "assembly must not re-level a host-controlled layer")
            assertTrue(layer.loopStartFrame > 0)
        }
        assertEquals(57, spec.patch.midi, "zone roots never rewrite the saved patch")
        assertEquals(.61f, spec.patch.velocity)
        assertEquals(0f, spec.patch.macros.getValue("HOLD"), "rendering keys does not turn the saved finite patch into a loop")
    }

    @Test
    fun `export keeps native audio loop roots source recipe and release in both MPC generations`() {
        val name = "FILAMENT THREAD"
        val program = FilamentPadMaker.export(name, spec, notes, root)
        val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
        val xpm = File(data, "$name.xpm").readText()
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm)
        for ((index, group) in program.keygroups.withIndex()) {
            val layer = group.layers.single()
            assertTrue("<RootNote>${group.rootNote}</RootNote>" in xpm)
            assertTrue("<SliceLoopStart>${layer.loopStartFrame}</SliceLoopStart>" in xpm)
            val wave = File(data, "${layer.sampleName}.wav")
            val smpl = assertNotNull(WavReader.readSmpl(wave))
            assertEquals(group.rootNote, smpl.rootNote)
            val loop = assertNotNull(smpl.loop)
            assertEquals(layer.loopStartFrame, loop.startFrame)
            assertEquals(layer.frameCount, loop.endFrameExclusive)
            val read = WavReader.read(wave)
            assertEquals(44_100, read.sampleRate)
            assertEquals(1, read.channels)
            assertEquals(notes[index].snip.frameCount, read.frameCount)
            val worst = read.samples.indices.maxOf { abs(read.samples[it] - notes[index].snip.samples[it]) }
            assertTrue(worst < 2e-7f, "PCM24 transport changed the actual source: worst difference $worst")
        }
        val track = Acvs.read(File(root, "$name.xty"))
        assertEquals(Mpc3TrackWriter().keygroupPayloadText(program), track.payloadText,
            "read the real MPC 3 container, including every loop marker")

        val (_, instrument) = InstrumentStore.list(root).single()
        assertEquals(3, instrument.zones.size, "the additional patch JSON is not another instrument")
        assertEquals(listOf(48, 60, 72), instrument.zones.map { it.rootNote })
        assertEquals(spec.releaseSeconds, instrument.release)
        assertEquals(notes.map { it.loopStartFrame }, instrument.zones.map { it.loopStartFrame })
        assertEquals(patch, FilamentPatch.fromJsonText(File(data, FilamentPadMaker.PATCH_FILE).readText()),
            "source controls, root, velocity, seed, profile and saved model remain replayable")
    }

    @Test
    fun `exported root keeps sounding through the wrap and releases with one host velocity gain`() {
        FilamentPadMaker.export("HELD THREAD", spec, notes, root)
        val (sidecar, instrument) = InstrumentStore.list(root).single()
        val loaded = InstrumentEngine.Loaded(instrument, 44_100,
            instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples })
        val full = InstrumentEngine(loaded, 44_100)
        val half = InstrumentEngine(loaded, 44_100)
        assertTrue(full.noteOn(60, 1f))
        assertTrue(half.noteOn(60, .5f))
        val note = notes[1]
        val period = note.snip.frameCount - note.loopStartFrame.toInt()
        val frames = note.snip.frameCount + 2 * period + 137
        val heard = render(full, frames)
        val softer = render(half, frames)
        assertTrue(heard.samples.any { abs(it) > .00001f }, "this cannot pass on silent transport")
        for (i in heard.samples.indices) assertEquals(heard.samples[i] * .5f, softer.samples[i], 1e-7f,
            "one layer supplies timbre; the player's note velocity supplies its gain once")

        // This checks the reference player's use of exported markers after the actual file ends.
        // Independent synthesis/convergence is tested in FilamentKeysTest, not by this repetition.
        val afterFile = (note.snip.frameCount + 17) * 2
        val first = heard.samples.copyOfRange(afterFile, afterFile + period * 2)
        val second = heard.samples.copyOfRange(afterFile + period * 2, afterFile + period * 4)
        assertTrue(first.any { abs(it) > .00001f }, "the held note must remain audible past the file end")
        assertContentEquals(first, second)
        full.noteOff(60)
        val released = render(full, ((instrument.release + .1f) * 44_100).toInt())
        assertTrue(released.samples.takeLast(882).all { abs(it) < 1e-5f })
        assertTrue(full.activeNotes.isEmpty())
    }

    @Test
    fun `unsupported patches and malformed zones are refused before any export writes`() {
        for (wrong in listOf(patch.copy(model = 1), patch.copy(model = 2),
            FilamentPresets.forVoice(FilamentVoice.PULSE).first(), FilamentPresets.forVoice(FilamentVoice.CURL).first())) {
            assertFalse(FilamentPadMaker.supports(wrong))
            assertFailsWith<IllegalArgumentException> { FilamentPadMaker.Spec(wrong) }
        }
        val valid = List(3) { KeyNote(Snip(FloatArray(16) { .01f }, 1, 44_100), 8) }
        val bad = listOf(
            valid.take(2),
            valid.toMutableList().also { it[0] = valid[0].copy(loopStartFrame = 0) },
            valid.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(16), 2, 44_100), 4) },
            valid.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(16), 1, 48_000), 8) },
            valid.toMutableList().also { it[0] = KeyNote(Snip(FloatArray(16) { Float.NaN }, 1, 44_100), 8) },
        )
        for (zones in bad) assertFailsWith<IllegalArgumentException> {
            FilamentPadMaker.export("BAD THREAD", spec, zones, root)
        }
        for (name in listOf("../escape", "/tmp/escape", "x".repeat(OneNote.MAX_NAME_LENGTH + 1))) {
            assertFailsWith<IllegalArgumentException> { FilamentPadMaker.export(name, spec, valid, root) }
        }
        assertTrue(root.listFiles()!!.isEmpty(), "failed validation must not leave a partial instrument")
    }

    @Test
    fun `cancelled rendering leaves no package and a second completed export never clobbers the first`() {
        assertFailsWith<CancellationException> { FilamentPadMaker.renderZone(spec, 60) { true } }
        assertTrue(root.listFiles()!!.isEmpty())
        val first = OneNote.freshName(root, "FILAMENT THREAD")
        FilamentPadMaker.export(first, spec, notes, root)
        val files = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
        assertFailsWith<DestinationExists> { FilamentPadMaker.export(first, spec, notes, root) }
        for ((path, bytes) in files) assertContentEquals(bytes, File(root, path).readBytes())
        val next = OneNote.freshName(root, "FILAMENT THREAD")
        assertNotEquals(first, next)
        FilamentPadMaker.export(next, spec, notes, root)
        assertEquals(2, InstrumentStore.list(root).size)
    }

    @Test
    fun `release stepper remains valid at every edge`() {
        for (fraction in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f, 0f, .5f, 1f, 2f)) {
            val current = FilamentPadMaker.spec(patch, fraction)
            assertTrue(current.releaseSeconds.isFinite())
            assertTrue(current.releaseSeconds in FilamentPadMaker.RELEASE_MIN_SECONDS..FilamentPadMaker.RELEASE_MAX_SECONDS)
        }
        for (seconds in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 2f)) {
            assertFailsWith<IllegalArgumentException> { FilamentPadMaker.Spec(patch, seconds) }
        }
        assertEquals("0.30 s", FilamentPadMaker.secondsLabel(.3f))
    }

    private fun render(engine: InstrumentEngine, frames: Int): Snip {
        val out = FloatArray(frames * 2)
        val block = FloatArray(512)
        var at = 0
        while (at < frames) {
            val count = minOf(256, frames - at)
            engine.render(block, count)
            block.copyInto(out, at * 2, 0, count * 2)
            at += count
        }
        return Snip(out, 2, 44_100)
    }
}
