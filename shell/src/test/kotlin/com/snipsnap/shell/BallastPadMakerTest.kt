package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.BallastVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import java.io.File
import org.junit.jupiter.api.TestInstance
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One instance for the class, so the thirteen zones are rendered once and released with it. A companion
 * object would keep about 70 s of audio alive for the rest of the module's test run, and rendering the
 * zones in parallel raised the peak; together they ran the shell test JVM out of heap in a later class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BallastPadMakerTest {

    private lateinit var dest: File

    @BeforeTest
    fun setUp() {
        dest = java.nio.file.Files.createTempDirectory("ballastpad").toFile()
    }

    @AfterTest
    fun cleanUp() {
        dest.deleteRecursively()
    }

    private val spec = BallastPadMaker.Spec(BallastVoice.ROOT, emptyMap(), releaseSeconds = 0.6f)

    /** ROOT's thirteen zones, rendered one at a time, once for every test that reads them. */
    private val notes: List<KeyNote> by lazy { BallastPadMaker.zoneMidis(spec).map { BallastPadMaker.renderZone(spec, it) } }

    @Test
    fun `thirteen zones tile C1 to C4 and root at the minor thirds`() {
        val (program, samples) = BallastPadMaker.assemble("BALLAST ROOT", spec, notes)
        val zones = program.keygroups
        assertEquals(13, zones.size)
        assertEquals((24..60 step 3).toList(), zones.map { it.rootNote })
        for (i in 1 until zones.size) assertEquals(zones[i - 1].highNote + 1, zones[i].lowNote, "zones tile without gaps")
        assertEquals(zones.first().rootNote - 9, zones.first().lowNote)
        assertEquals(zones.last().rootNote + 9, zones.last().highNote)
        assertEquals(0.6f, program.volumeRelease)
        assertEquals(13, samples.size)
        zones.forEachIndexed { i, kg ->
            val layer = kg.layers.single()
            assertEquals(notes[i].loopStartFrame, layer.loopStartFrame)
            assertEquals(notes[i].snip.frameCount.toLong(), layer.frameCount)
        }
    }

    @Test
    fun `every zone's loop survives into both generations and the phone's sidecar`() {
        val name = "BALLAST ROOT"
        val program = BallastPadMaker.export(name, spec, notes, dest)

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
        assertEquals(13, instrument.zones.size)
        assertTrue(instrument.zones.all { it.loopStartFrame > 0 }, "every zone HOLDS")
        assertEquals(0.6f, instrument.release)
    }

    @Test
    fun `the phone's own player holds it and lets it go`() {
        BallastPadMaker.export("BALLAST ROOT", spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val rate = 44_100
        val loaded = InstrumentEngine.Loaded(
            instrument,
            rate,
            instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples },
        )
        val engine = InstrumentEngine(loaded, rate)
        val root = Keys.ballastPadMidis()[6]
        assertTrue(engine.noteOn(root))

        val held = render(engine, 10f, rate)
        // BALLAST's two oscillators beat and its filter wobbles, so its level swings by up to
        // about 9 dB over a second (ROOT at F#2). Short windows would compare a peak with a
        // trough. Each window here is one whole loop, which by construction holds whole beats
        // and whole wobble cycles, so the comparison reads the level and not the beat.
        val note = notes[6]
        val start = note.loopStartFrame.toFloat() / rate
        val loop = note.snip.durationSeconds - start
        val attack = rms(held, 0f, start)
        val firstPass = rms(held, start, start + loop)
        assertTrue(firstPass > attack * 0.5, "a held key must still sound once the loop starts: $firstPass vs $attack")

        // The last window sits past the file's end: only the marker keeps it sounding.
        assertTrue(note.snip.durationSeconds < 10f - loop, "the zone must end before the last window, or it tests nothing")
        val wrapped = rms(held, 10f - loop, 10f)
        assertTrue(wrapped > firstPass * 0.7, "a held key must still sound past the file's end, through the loop's wrap: $wrapped vs $firstPass")

        engine.noteOff(root)
        val released = render(engine, instrument.release + 0.1f, rate)
        val tail = rms(released, released.durationSeconds - 0.01f, released.durationSeconds)
        assertTrue(tail < 1e-4, "silent within RELEASE of letting go: $tail")
    }

    @Test
    fun `a second make does not clobber the first`() {
        val first = OneNote.freshName(dest, "BALLAST ROOT")
        BallastPadMaker.export(first, spec, notes, dest)
        val second = OneNote.freshName(dest, "BALLAST ROOT")
        assertNotEquals(first, second)
        BallastPadMaker.export(second, spec, notes, dest)
        assertEquals(2, InstrumentStore.list(dest).size)
    }

    @Test
    fun `preview is the middle zone's own render`() {
        // notes[6] is renderZone(spec, midis[6]), the middle of the thirteen, from the class's one fixture.
        val middle = notes[6]
        val preview = BallastPadMaker.preview(spec)
        assertTrue(preview.samples.contentEquals(middle.snip.samples), "preview is the middle zone's own render, sample for sample")
    }

    @Test
    fun `every voice shares the one layout`() {
        for (v in BallastVoice.entries) assertEquals(Keys.ballastPadMidis(), BallastPadMaker.zoneMidis(BallastPadMaker.spec(v, emptyMap(), 0.5f)))
    }

    @Test
    fun `release is refused out of range`() {
        assertFailsWith<IllegalArgumentException> { BallastPadMaker.Spec(BallastVoice.ROOT, emptyMap(), releaseSeconds = 3f) }
        assertFailsWith<IllegalArgumentException> { BallastPadMaker.Spec(BallastVoice.ROOT, emptyMap(), releaseSeconds = 0.01f) }
    }

    @Test
    fun `the knob covers the spec's range`() {
        assertEquals(BallastPadMaker.RELEASE.lo, BallastPadMaker.RELEASE.value(0f))
        assertEquals(BallastPadMaker.RELEASE.hi, BallastPadMaker.RELEASE.value(1f), 1e-5f)
        assertEquals("0.30 s", BallastPadMaker.secondsLabel(0.3f))
        // Every stepper position makes a spec, the ends included: the sheet
        // must never refuse a slider it drew.
        for (r in listOf(0f, 0.5f, 1f)) {
            val s = BallastPadMaker.spec(BallastVoice.ROOT, emptyMap(), r)
            assertTrue(s.releaseSeconds in BallastPadMaker.RELEASE_MIN_SECONDS..BallastPadMaker.RELEASE_MAX_SECONDS)
        }
        assertEquals(0.6f, BallastPadMaker.spec(BallastVoice.ROOT, emptyMap(), BallastPadMaker.RELEASE.defaultFraction).releaseSeconds, 1e-4f)
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
