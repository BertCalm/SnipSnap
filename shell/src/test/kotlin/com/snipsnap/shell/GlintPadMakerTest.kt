package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.GlintVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlintPadMakerTest {

    private val dest: File = java.nio.file.Files.createTempDirectory("glintpad").toFile()

    @AfterTest
    fun cleanUp() {
        dest.deleteRecursively()
    }

    private companion object {
        val spec = GlintPadMaker.Spec(GlintVoice.SWEEP, emptyMap(), releaseSeconds = 0.6f)

        /** SWEEP's nine zones, rendered once for every test that reads them (each is several seconds of audio at four times oversampling). */
        val notes: List<KeyNote> by lazy { GlintPadMaker.zoneMidis(spec).map { GlintPadMaker.renderZone(spec, it) } }
    }

    @Test
    fun `nine zones tile the voice and root at the minor thirds`() {
        val (program, samples) = GlintPadMaker.assemble("GLINT SWEEP", spec, notes)
        val zones = program.keygroups
        assertEquals(9, zones.size)
        assertEquals(Keys.glintPadMidis(GlintVoice.SWEEP), zones.map { it.rootNote })
        for (i in 1 until zones.size) assertEquals(zones[i - 1].highNote + 1, zones[i].lowNote, "zones tile without gaps")
        assertEquals(zones.first().rootNote - 9, zones.first().lowNote)
        assertEquals(zones.last().rootNote + 9, zones.last().highNote)
        assertEquals(0.6f, program.volumeRelease)
        assertEquals(9, samples.size)
        zones.forEachIndexed { i, kg ->
            val layer = kg.layers.single()
            assertEquals(notes[i].loopStartFrame, layer.loopStartFrame)
            assertEquals(notes[i].snip.frameCount.toLong(), layer.frameCount)
        }
    }

    @Test
    fun `every zone's loop survives into both generations and the phone's sidecar`() {
        val name = "GLINT SWEEP"
        val program = GlintPadMaker.export(name, spec, notes, dest)

        val dataDir = File(dest, Mpc3TrackWriter.trackDataDirName(name))
        val xpm = dataDir.listFiles { f -> f.extension == "xpm" }!!.single().readText()
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm, "MPC 2 idiom: SliceLoop=1")
        for (n in notes) assertTrue("<SliceLoopStart>${n.loopStartFrame}</SliceLoopStart>" in xpm, "loop ${n.loopStartFrame} in the .xpm")
        // The .xty is not plain text; InstrumentSuiteTest reads its payload the same way.
        assertTrue(File(dest, "$name.xty").isFile, "the MPC 3 track was written")
        val xty = Mpc3TrackWriter().keygroupPayloadText(program)
        assertTrue("\"LoopMode\": 1" in xty, "MPC 3 idiom: LoopMode=1")
        for (n in notes) assertTrue("\"LoopStart\": ${n.loopStartFrame}" in xty, "loop ${n.loopStartFrame} in the .xty payload")

        val (_, instrument) = InstrumentStore.list(dest).single()
        assertEquals(9, instrument.zones.size)
        assertTrue(instrument.zones.all { it.loopStartFrame > 0 }, "every zone HOLDS")
        assertEquals(0.6f, instrument.release)
    }

    @Test
    fun `the phone's own player holds it and lets it go`() {
        GlintPadMaker.export("GLINT SWEEP", spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val rate = 44_100
        val loaded = InstrumentEngine.Loaded(
            instrument,
            rate,
            instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples },
        )
        val engine = InstrumentEngine(loaded, rate)
        val root = Keys.glintPadMidis(GlintVoice.SWEEP)[4]
        assertTrue(engine.noteOn(root))

        val held = render(engine, 10f, rate)
        val early = rms(held, 1.5f, 2.0f)
        val late = rms(held, 4.9f, 5.0f)
        assertTrue(late > early * 0.5, "a held key must still sound at 5 s: $late vs $early")

        // A GLINT zone is the onset plus two breaths, about 6.5 s for this one, so
        // 5 s is still the file's first pass and never reaches the loop's wrap.
        // The last window sits past the file's end: only the marker keeps it sounding.
        assertTrue(notes[4].snip.durationSeconds < 9.9f, "the zone must end before the last window, or it tests nothing")
        val wrapped = rms(held, 9.9f, 10.0f)
        assertTrue(wrapped > early * 0.5, "a held key must still sound past the file's end, through the loop's wrap: $wrapped vs $early")

        engine.noteOff(root)
        val released = render(engine, instrument.release + 0.1f, rate)
        val tail = rms(released, released.durationSeconds - 0.01f, released.durationSeconds)
        assertTrue(tail < 1e-4, "silent within RELEASE of letting go: $tail")
    }

    @Test
    fun `a second make does not clobber the first`() {
        val first = OneNote.freshName(dest, "GLINT SWEEP")
        GlintPadMaker.export(first, spec, notes, dest)
        val second = OneNote.freshName(dest, "GLINT SWEEP")
        assertNotEquals(first, second)
        GlintPadMaker.export(second, spec, notes, dest)
        assertEquals(2, InstrumentStore.list(dest).size)
    }

    @Test
    fun `preview is the middle zone's own render`() {
        // notes[4] is renderZone(spec, midis[4]), the middle of the nine, from the class's one fixture.
        val middle = notes[4]
        val preview = GlintPadMaker.preview(spec)
        assertTrue(preview.samples.contentEquals(middle.snip.samples), "preview is the middle zone's own render, sample for sample")
    }

    @Test
    fun `STEP's zones sit an octave above SWEEP's`() {
        val sweep = GlintPadMaker.zoneMidis(GlintPadMaker.spec(GlintVoice.SWEEP, emptyMap(), 0.5f))
        val step = GlintPadMaker.zoneMidis(GlintPadMaker.spec(GlintVoice.STEP, emptyMap(), 0.5f))
        assertEquals(sweep.map { it + 12 }, step)
    }

    @Test
    fun `release is refused out of range`() {
        assertFailsWith<IllegalArgumentException> { GlintPadMaker.Spec(GlintVoice.SWEEP, emptyMap(), releaseSeconds = 3f) }
        assertFailsWith<IllegalArgumentException> { GlintPadMaker.Spec(GlintVoice.SWEEP, emptyMap(), releaseSeconds = 0.01f) }
    }

    @Test
    fun `the knob covers the spec's range`() {
        assertEquals(GlintPadMaker.RELEASE.lo, GlintPadMaker.RELEASE.value(0f))
        assertEquals(GlintPadMaker.RELEASE.hi, GlintPadMaker.RELEASE.value(1f), 1e-5f)
        assertEquals("0.30 s", GlintPadMaker.secondsLabel(0.3f))
        // Every stepper position makes a spec, the ends included: the sheet
        // must never refuse a slider it drew.
        for (r in listOf(0f, 0.5f, 1f)) {
            val s = GlintPadMaker.spec(GlintVoice.SWEEP, emptyMap(), r)
            assertTrue(s.releaseSeconds in GlintPadMaker.RELEASE_MIN_SECONDS..GlintPadMaker.RELEASE_MAX_SECONDS)
        }
        assertEquals(0.6f, GlintPadMaker.spec(GlintVoice.SWEEP, emptyMap(), GlintPadMaker.RELEASE.defaultFraction).releaseSeconds, 1e-4f)
    }

    private fun render(engine: InstrumentEngine, seconds: Float, rate: Int): Snip {
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * 2)
        val block = FloatArray(512)
        var at = 0
        while (at < frames) {
            val n = minOf(256, frames - at)
            engine.render(block, n)
            System.arraycopy(block, 0, out, at * 2, n * 2)
            at += n
        }
        return Snip(out, 2, rate)
    }

    private fun rms(s: Snip, fromSec: Float, toSec: Float): Double {
        val a = (fromSec * s.sampleRate).toInt()
        val b = (toSec * s.sampleRate).toInt().coerceAtMost(s.frameCount)
        var acc = 0.0
        for (i in a until b) acc += s.samples[i * 2].toDouble() * s.samples[i * 2]
        return Math.sqrt(acc / (b - a))
    }
}
