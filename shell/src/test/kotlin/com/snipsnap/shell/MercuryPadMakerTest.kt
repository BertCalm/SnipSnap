package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MercuryVoice
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MercuryPadMakerTest {

    private val dest: File = java.nio.file.Files.createTempDirectory("mercurypad").toFile()

    @AfterTest
    fun cleanUp() {
        dest.deleteRecursively()
    }

    private companion object {
        val spec = MercuryPadMaker.Spec(MercuryVoice.SING, emptyMap(), releaseSeconds = 0.6f)

        /** SING's nine zones, rendered once for every test that reads them. */
        val notes: List<KeyNote> by lazy { MercuryPadMaker.zoneMidis(spec).map { MercuryPadMaker.renderZone(spec, it) } }
    }

    @Test
    fun `each voice's zones are its own nine, from its own root`() {
        for (v in MercuryVoice.entries) {
            val midis = MercuryPadMaker.zoneMidis(MercuryPadMaker.Spec(v, emptyMap()))
            assertEquals(Keys.mercuryPadMidis(v), midis)
            assertEquals(9, midis.size)
        }
        assertEquals(60, MercuryPadMaker.zoneMidis(MercuryPadMaker.Spec(MercuryVoice.PING, emptyMap())).first())
        assertEquals(55, MercuryPadMaker.zoneMidis(MercuryPadMaker.Spec(MercuryVoice.BLADE, emptyMap())).first())
    }

    @Test
    fun `nine zones tile the voice and root at the minor thirds`() {
        val (program, samples) = MercuryPadMaker.assemble("MERCURY SING", spec, notes)
        val zones = program.keygroups
        assertEquals(9, zones.size)
        assertEquals(Keys.mercuryPadMidis(MercuryVoice.SING), zones.map { it.rootNote })
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
        val name = "MERCURY SING"
        val program = MercuryPadMaker.export(name, spec, notes, dest)

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
        MercuryPadMaker.export("MERCURY SING", spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val rate = 44_100
        val loaded = InstrumentEngine.Loaded(
            instrument,
            rate,
            instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples },
        )
        val engine = InstrumentEngine(loaded, rate)
        val root = Keys.mercuryPadMidis(MercuryVoice.SING)[4]
        assertTrue(engine.noteOn(root))

        val held = render(engine, 5f, rate)
        val early = rms(held, 1.5f, 2.0f)
        val late = rms(held, 4.9f, 5.0f)
        assertTrue(late > early * 0.5, "a held key must still sound at 5 s: $late vs $early")

        engine.noteOff(root)
        val released = render(engine, instrument.release + 0.1f, rate)
        val tail = rms(released, released.durationSeconds - 0.01f, released.durationSeconds)
        assertTrue(tail < 1e-4, "silent within RELEASE of letting go: $tail")
    }

    @Test
    fun `a second make does not clobber the first`() {
        val first = OneNote.freshName(dest, "MERCURY SING")
        MercuryPadMaker.export(first, spec, notes, dest)
        val second = OneNote.freshName(dest, "MERCURY SING")
        assertNotEquals(first, second)
        MercuryPadMaker.export(second, spec, notes, dest)
        assertEquals(2, InstrumentStore.list(dest).size)
    }

    @Test
    fun `preview is the middle zone's own two loop passes, no separate head`() {
        val middle = notes[4]
        val preview = MercuryPadMaker.preview(spec)
        assertEquals(middle.snip.samples.toList(), preview.samples.toList())
        val loopLen = middle.snip.frameCount - middle.loopStartFrame.toInt()
        // mercuryPad's own shape: two bit-identical copies back to back, the marker at the second.
        assertEquals(loopLen, middle.loopStartFrame.toInt(), "a MERCURY pad's render is two equal halves")
        for (i in 0 until loopLen) {
            assertEquals(preview.samples[i], preview.samples[loopLen + i], "pass two is pass one at $i")
        }
    }

    @Test
    fun `release is refused out of range`() {
        assertFailsWith<IllegalArgumentException> { MercuryPadMaker.Spec(MercuryVoice.SING, emptyMap(), releaseSeconds = 3f) }
        assertFailsWith<IllegalArgumentException> { MercuryPadMaker.Spec(MercuryVoice.SING, emptyMap(), releaseSeconds = 0.01f) }
    }

    @Test
    fun `the knob covers the spec's range`() {
        assertEquals(MercuryPadMaker.RELEASE.lo, MercuryPadMaker.RELEASE.value(0f))
        assertEquals(MercuryPadMaker.RELEASE.hi, MercuryPadMaker.RELEASE.value(1f), 1e-5f)
        assertEquals("0.30 s", MercuryPadMaker.secondsLabel(0.3f))
        // Every stepper position makes a spec, the ends included: the sheet
        // must never refuse a slider it drew.
        for (r in listOf(0f, 0.5f, 1f)) {
            val s = MercuryPadMaker.spec(MercuryVoice.PING, emptyMap(), r)
            assertTrue(s.releaseSeconds in MercuryPadMaker.RELEASE_MIN_SECONDS..MercuryPadMaker.RELEASE_MAX_SECONDS)
        }
        assertEquals(0.6f, MercuryPadMaker.spec(MercuryVoice.PING, emptyMap(), MercuryPadMaker.RELEASE.defaultFraction).releaseSeconds, 1e-4f)
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
