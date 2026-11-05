package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.OrigamiKeys
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiPresets
import com.snipsnap.synth.OrigamiVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The held renderer becomes the same bounded, releasing instrument on the phone and both MPC generations. */
class OrigamiPadMakerTest {
    private val rate = WavWriter.MPC_SAMPLE_RATE
    private val patch = OrigamiPresets.forVoice(OrigamiVoice.FAN).first().copy(
        name = "MY PAPER", midi = 72, velocity = .61f, seed = -9007199254740993L,
        macros = OrigamiPresets.forVoice(OrigamiVoice.FAN).first().macros + ("TUNE" to 0f))

    /** Cheap distinct zones exercise packaging without rendering nine long sustain captures. */
    private fun fixtures(spec: OrigamiPadMaker.Spec): List<KeyNote> =
        OrigamiPadMaker.zoneMidis(spec).mapIndexed { index, _ ->
            KeyNote(Snip(FloatArray(384 + index * 8) { frame ->
                (.15 * sin(2 * PI * frame / 32) + index * .001).toFloat()
            }, 1, rate), loopStartFrame = (64 + index * 8).toLong())
        }

    @Test
    fun `finite recipes remain intact while sampled roots cover C3 through C5`() {
        val spec = OrigamiPadMaker.Spec(patch, releaseSeconds = .9f)
        assertEquals(patch, spec.patch)
        assertEquals(.9f, spec.releaseSeconds)
        assertTrue(OrigamiKeys.supports(patch))
        assertEquals((48..72 step 3).toList(), OrigamiPadMaker.zoneMidis(spec))
        val (program, samples) = OrigamiPadMaker.assemble("MY PAPER", spec, fixtures(spec))
        assertEquals(.9f, program.volumeRelease)
        assertEquals((48..72 step 3).toList(), program.keygroups.map { it.rootNote })
        assertEquals(48, program.keygroups.first().lowNote)
        assertEquals(72, program.keygroups.last().highNote)
        assertEquals(9, samples.size)
        program.keygroups.zipWithNext().forEach { (a, b) -> assertEquals(a.highNote + 1, b.lowNote) }
        assertEquals((48..72).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertEquals(patch, spec.patch, "making a keyboard cannot turn the saved finite recipe into a held patch")
    }

    @Test
    fun `WAV sampler sheets MPC programs and phone sidecar retain identical roots loops and release`() {
        val dir = createTempDirectory("origami-held-export").toFile()
        try {
            val spec = OrigamiPadMaker.Spec(patch, .9f)
            val notes = fixtures(spec)
            val program = OrigamiPadMaker.export("MY PAPER", spec, notes, dir)
            val data = File(dir, Mpc3TrackWriter.trackDataDirName(program.name))
            assertTrue(File(dir, "${program.name}.xty").isFile)
            val compatibility = File(data, "${program.name}.xpm").readText()
            assertTrue("<SliceLoop>1</SliceLoop>" in compatibility)
            assertTrue(Regex("<VolumeRelease>0\\.9(?:0*)</VolumeRelease>").containsMatchIn(compatibility))
            // Read the packaged descriptors: the assembly helper intentionally retains
            // its sampled span while the shared exporter extends outer keyboard zones.
            val nativeZones = Mpc3Project.read(File(dir, "${program.name}.xty")).tracks.single()
                .getValue("program").obj()
                .getValue("drum").obj().getValue("instruments").arr().take(9)
            val xmlZones = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(data, "${program.name}.xpm")).getElementsByTagName("Instrument")
            assertEquals(9, xmlZones.length)
            for (zone in nativeZones) {
                assertEquals(2, zone.obj().getValue("triggerMode").int(), "MPC must wait for key-up")
                val envelope = zone.obj().getValue("synthSection").obj().getValue("ampEnvelope").obj()
                assertEquals(.9f.toDouble(), envelope.getValue("Release").obj().getValue("value0").num())
                assertFalse(envelope.getValue("OneShot").obj().getValue("value0").bool())
            }
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(.9f, instrument.release)
            assertEquals(program.keygroups.map { it.rootNote }, instrument.zones.map { it.rootNote })
            assertEquals(listOf(0..49, 50..52, 53..55, 56..58, 59..61, 62..64, 65..67, 68..70, 71..127),
                instrument.zones.map { it.lowNote..it.highNote })
            for ((index, zone) in instrument.zones.withIndex()) {
                val wav = File(sidecar.parentFile, zone.sample)
                val audio = WavReader.read(wav)
                val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount))
                assertEquals(notes[index].loopStartFrame, zone.loopStartFrame)
                assertEquals(notes[index].snip.frameCount.toLong(), zone.frameCount)
                assertEquals(1, audio.channels)
                assertEquals(rate, audio.sampleRate)
                assertEquals(sheet, WavReader.readSmpl(wav))
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, notes[index].snip, smpl = sheet) }
                assertContentEquals(expected.toByteArray(), wav.readBytes())
                assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in compatibility)
                val nativeZone = nativeZones[index].obj()
                assertEquals(zone.lowNote, nativeZone.getValue("lowNote").int())
                assertEquals(zone.highNote, nativeZone.getValue("highNote").int())
                val nativeLayer = nativeZone.getValue("layersv").arr().first().obj()
                assertEquals(zone.rootNote, nativeLayer.getValue("rootNote").int())
                val slice = nativeLayer.getValue("sliceInfo").obj()
                assertEquals(zone.frameCount, slice.getValue("End").long())
                assertEquals(zone.loopStartFrame, slice.getValue("LoopStart").long())
                assertEquals(1, slice.getValue("LoopMode").int())
                val xmlZone = xmlZones.item(index) as Element
                assertEquals(zone.lowNote, xmlZone.getElementsByTagName("LowNote").item(0).textContent.toInt())
                assertEquals(zone.highNote, xmlZone.getElementsByTagName("HighNote").item(0).textContent.toInt())
                val hit = KeyHit.resolve(instrument, zone.rootNote, .5f) { file ->
                    instrument.zones.singleOrNull { it.sample == file }?.frameCount
                }!!
                assertTrue(hit.loops)
                assertEquals(zone.loopStartFrame, hit.loopStartFrame)
                assertEquals(1.0, hit.pitchRatio)
            }
            assertEquals(900f, KeyHit.releaseMs(instrument))
            assertEquals(spec, OrigamiPadMaker.Spec.fromJsonText(OrigamiPadMaker.recipeFile(dir, "MY PAPER").readText()))
            // The outer zones stretch to the keyboard's ends, so keys past the authored span still sound.
            assertTrue(KeyHit.resolve(instrument, 47, 1f) { 500L } != null)
            assertTrue(KeyHit.resolve(instrument, 73, 1f) { 500L } != null)
            assertTrue(KeyHit.resolve(instrument, 0, 1f) { 500L } != null)
            assertTrue(KeyHit.resolve(instrument, 127, 1f) { 500L } != null)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a separate held recipe preserves sparse finite state and protects an occupied destination`() {
        val sparse = patch.copy(macros = mapOf("CREASE" to .37f, "MOTION" to .71f))
        val spec = OrigamiPadMaker.Spec(sparse, .73f)
        val text = spec.toJsonText()
        assertEquals(spec, OrigamiPadMaker.Spec.fromJsonText(text))
        val fields = Json.parse(text).obj()
        assertEquals(1, fields.getValue("version").int())
        assertEquals(OrigamiKeys.MODEL_VERSION, fields.getValue("heldModel").int())
        assertEquals(sparse.toJsonValue(), fields.getValue("patch"))
        for (key in listOf("version", "heldModel")) {
            assertFailsWith<JsonException> { OrigamiPadMaker.Spec.fromJsonText(Json.write(JsonValue.Obj(fields - key))) }
            assertFailsWith<JsonException> {
                OrigamiPadMaker.Spec.fromJsonText(Json.write(JsonValue.Obj(fields + (key to JsonValue.Num(99.0)))))
            }
        }
        val dir = createTempDirectory("origami-held-occupied").toFile()
        try {
            val recipe = OrigamiPadMaker.recipeFile(dir, "MY PAPER")
            recipe.writeText("previous saved recipe")
            assertFailsWith<DestinationExists> { OrigamiPadMaker.export("MY PAPER", spec, fixtures(spec), dir) }
            assertEquals("previous saved recipe", recipe.readText())
            assertEquals(listOf(recipe.name), dir.listFiles()!!.map { it.name }, "refusal must precede package writes")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a real rendered zone keeps sounding past its file and releases at an arbitrary key lift`() {
        val dir = createTempDirectory("origami-held-playback").toFile()
        try {
            val spec = OrigamiPadMaker.Spec(patch, .2f)
            val notes = fixtures(spec).toMutableList()
            val actual = OrigamiPadMaker.renderZone(spec, 60)
            notes[OrigamiPadMaker.zoneMidis(spec).indexOf(60)] = actual
            OrigamiPadMaker.export("MY PAPER", spec, notes, dir)
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            val audio = instrument.zones.associate { it.sample to WavReader.read(File(sidecar.parentFile, it.sample)).samples }
            fun engine(release: Float) = InstrumentEngine(
                InstrumentEngine.Loaded(instrument.copy(release = release), rate, audio), rate)
            val short = engine(.2f)
            val long = engine(.8f)
            assertTrue(short.noteOn(60, .73f))
            assertTrue(long.noteOn(60, .73f))
            val heldFrames = actual.snip.frameCount + (rate * .371).toInt()
            val shortHeld = render(short, heldFrames)
            val longHeld = render(long, heldFrames)
            assertContentEquals(shortHeld, longHeld, "release is a key-up envelope, not a different capture")
            assertTrue(rms(shortHeld, shortHeld.size - rate / 5, shortHeld.size) > .005,
                "the real loop fell silent after its file ended")
            short.noteOff(60)
            long.noteOff(60)
            val shortTail = render(short, rate)
            val longTail = render(long, rate)
            assertTrue(rms(shortTail, 0, rate / 10) > .001, "key-up cut the voice immediately")
            assertTrue(shortTail.copyOfRange(rate / 2, shortTail.size).all { it == 0f })
            assertTrue(rms(longTail, rate * 2 / 5, rate / 2) > .001, "the longer release was ignored")
            assertTrue(longTail.copyOfRange(rate * 9 / 5, longTail.size).all { it == 0f })
            assertTrue(short.activeNotes.isEmpty() && long.activeNotes.isEmpty())
            // The outer zones stretch to the keyboard's ends, so keys past the authored span still sound.
            assertTrue(short.noteOn(47))
            assertTrue(short.noteOn(73))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preview uses middle C and the selected release after the same three second performance`() {
        val short = OrigamiPadMaker.preview(OrigamiPadMaker.Spec(patch, .2f))
        val long = OrigamiPadMaker.preview(OrigamiPadMaker.Spec(patch, .8f))
        assertEquals(1, short.channels)
        assertEquals(rate, short.sampleRate)
        assertEquals((rate * 3.25).toInt(), short.frameCount)
        assertEquals((rate * 3.85).toInt(), long.frameCount)
        assertContentEquals(short.samples.copyOfRange(0, rate * 3), long.samples.copyOfRange(0, rate * 3))
        assertTrue(rms(long.samples, rate * 3, (rate * 3.1).toInt()) > .001)
        assertTrue(short.samples.takeLast(rate / 30).all { it == 0f })
        assertTrue(long.samples.takeLast(rate / 30).all { it == 0f })
        val middle = patch.copy(midi = 60, macros = patch.macros + ("TUNE" to 1f))
        assertContentEquals(short.samples, OrigamiPadMaker.preview(OrigamiPadMaker.Spec(middle, .2f)).samples,
            "saved absolute MIDI and TUNE cannot transpose a keyboard's MIDI60 preview")
    }

    @Test
    fun `legacy models CRANE malformed zones and invalid release refuse and cancelled work stops`() {
        for (model in 1..3) {
            assertFalse(OrigamiKeys.supports(patch.copy(model = model)))
            assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.Spec(patch.copy(model = model)) }
        }
        assertFalse(OrigamiKeys.supports(patch.copy(voice = OrigamiVoice.CRANE)))
        assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.Spec(patch.copy(voice = OrigamiVoice.CRANE)) }
        for (release in listOf(.01f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.Spec(patch, release) }
        }
        for (fraction in listOf(0f, .5f, 1f)) {
            assertTrue(OrigamiPadMaker.spec(patch, fraction).releaseSeconds in .1f..1.5f)
        }
        val spec = OrigamiPadMaker.Spec(patch)
        for (midi in listOf(47, 49, 73)) {
            assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.renderZone(spec, midi) }
        }
        val notes = fixtures(spec)
        assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.assemble("MY PAPER", spec, notes.dropLast(1)) }
        for (bad in listOf(
            notes.first().copy(loopStartFrame = 0),
            notes.first().copy(loopStartFrame = notes.first().snip.frameCount.toLong()),
            notes.first().copy(snip = Snip(FloatArray(400), 2, rate)),
            notes.first().copy(snip = Snip(FloatArray(400), 1, 48000)),
        )) {
            assertFailsWith<IllegalArgumentException> {
                OrigamiPadMaker.assemble("MY PAPER", spec, listOf(bad) + notes.drop(1))
            }
        }
        assertFailsWith<CancellationException> { OrigamiPadMaker.renderZone(spec, 60) { true } }
        assertFailsWith<CancellationException> { OrigamiPadMaker.preview(spec) { true } }
    }

    /** Render in the same bounded blocks as the reference player, returning interleaved stereo. */
    private fun render(engine: InstrumentEngine, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(1024)
        var at = 0
        while (at < frames) {
            val count = minOf(512, frames - at)
            engine.render(block, count)
            block.copyInto(out, at * 2, 0, count * 2)
            at += count
        }
        return out
    }

    private fun rms(samples: FloatArray, from: Int, until: Int): Double =
        sqrt((from until until).sumOf { samples[it].toDouble() * samples[it] } / (until - from))
}
