package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.KeyNote
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Read the actual exported files, including RIFF's inclusive loop endpoint. */
class FlockPadMakerTest {
    private companion object {
        val patch = FlockPatch("RIBBON HELD", FlockVoice.RIBBON, Flock.defaults(FlockVoice.RIBBON),
            midi = 60, velocity = .7f, seed = Long.MIN_VALUE, model = 1,
            playback = FlockPlayback.HELD, releaseSeconds = .9f)
        val spec = FlockPadMaker.Spec(patch)
        val notes: List<KeyNote> by lazy { FlockPadMaker.zoneMidis(spec).map { FlockPadMaker.renderZone(spec, it) } }
    }

    @Test
    fun `complete source and native loops agree across recipe WAV sidecar XTY and XPM`() {
        val dir = createTempDirectory("flock-instrument").toFile()
        try {
            val program = FlockPadMaker.export(patch.name, spec, notes, dir)
            assertEquals(patch, FlockPatch.fromJsonText(File(dir, "${patch.name}.patch.json").readText()))
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(patch.releaseSeconds, instrument.release)
            assertEquals((48..72 step 3).toList(), instrument.zones.map { it.rootNote })
            val native = Json.parse(Acvs.read(File(dir, "${patch.name}.xty")).payloadText).obj()
                .getValue("data").obj().getValue("program").obj()
                .getValue("drum").obj().getValue("instruments").arr()
            val dataDir = File(dir, "${patch.name}_[TrackData]")
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dataDir, "${patch.name}.xpm"))
            val compatibility = xml.getElementsByTagName("Instrument")
            assertEquals(9, compatibility.length)

            for ((i, zone) in instrument.zones.withIndex()) {
                val kg = program.keygroups[i]
                val layer = kg.layers.single()
                val wavFile = File(sidecar.parentFile, zone.sample)
                val wav = WavReader.read(wavFile)
                val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount))
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, notes[i].snip, smpl = sheet) }
                assertContentEquals(expected.toByteArray(), wavFile.readBytes(), "MIDI ${zone.rootNote} keeps the rendered PCM24 source")
                assertEquals(1, wav.channels)
                assertEquals(44_100, wav.sampleRate)
                assertEquals(layer.frameCount, wav.frameCount.toLong())
                assertEquals(layer.loopStartFrame, zone.loopStartFrame)
                assertEquals(layer.frameCount, zone.frameCount)
                assertEquals(sheet, WavReader.readSmpl(wavFile))
                val riff = wavFile.readBytes()
                val sampler = riffChunk(riff, "smpl")
                assertEquals(zone.rootNote, leInt(riff, sampler + 12))
                assertEquals(zone.loopStartFrame, leInt(riff, sampler + 44).toLong())
                assertEquals(zone.frameCount - 1, leInt(riff, sampler + 48).toLong(), "RIFF ends on the last played frame, inclusive")

                val n = native[i].obj()
                assertEquals(zone.lowNote, n.getValue("lowNote").int())
                assertEquals(zone.highNote, n.getValue("highNote").int())
                assertEquals(2, n.getValue("triggerMode").int(), "native Note On sustain")
                val nativeLayer = n.getValue("layersv").arr().first().obj()
                assertEquals(zone.rootNote, nativeLayer.getValue("rootNote").int())
                assertEquals(layer.sampleName, nativeLayer.getValue("sampleName").str())
                val slice = nativeLayer.getValue("sliceInfo").obj()
                assertEquals(0L, slice.getValue("Start").long())
                assertEquals(zone.frameCount, slice.getValue("End").long())
                assertEquals(zone.loopStartFrame, slice.getValue("LoopStart").long())
                assertEquals(1, slice.getValue("LoopMode").int())
                val envelope = n.getValue("synthSection").obj().getValue("ampEnvelope").obj()
                assertEquals(spec.releaseSeconds.toDouble(), envelope.getValue("Release").obj().getValue("value0").num())

                val x = compatibility.item(i) as Element
                assertEquals(zone.lowNote, text(x, "LowNote").toInt())
                assertEquals(zone.highNote, text(x, "HighNote").toInt())
                assertEquals(spec.releaseSeconds, text(x, "VolumeRelease").toFloat())
                val xmlLayer = x.getElementsByTagName("Layer").item(0) as Element
                assertEquals(zone.rootNote, text(xmlLayer, "RootNote").toInt())
                assertEquals(layer.sampleName, text(xmlLayer, "SampleName"))
                assertEquals(zone.frameCount, text(xmlLayer, "SliceEnd").toLong())
                assertEquals(zone.loopStartFrame, text(xmlLayer, "SliceLoopStart").toLong())
                assertEquals("1", text(xmlLayer, "SliceLoop"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `all saved roots get nine native zones with bounded gapless coverage`() {
        for (root in 36..84) {
            val local = FlockPadMaker.Spec(patch.copy(midi = root))
            val roots = FlockPadMaker.zoneMidis(local)
            assertEquals(9, roots.size)
            assertTrue(root in roots)
            assertTrue(roots.all { it in 36..84 && (it - root) % 3 == 0 })
            assertTrue(roots.zipWithNext().all { (a, b) -> b - a == 3 })
            val (program, _) = FlockPadMaker.assemble("ZONES", local, fixtures())
            assertEquals(roots.first(), program.keygroups.first().lowNote)
            assertEquals(roots.last(), program.keygroups.last().highNote)
            program.keygroups.zipWithNext().forEach { (a, b) -> assertEquals(a.highNote + 1, b.lowNote) }
            for (note in roots.first()..roots.last()) {
                val zone = program.keygroups.single { note in it.lowNote..it.highNote }
                assertTrue(kotlin.math.abs(note - zone.rootNote) <= 1)
            }
        }
    }

    @Test
    fun `preview retains the saved arrival and appends one exact captured period`() {
        val note = notes[4]
        val preview = FlockPadMaker.preview(spec)
        assertEquals(1, preview.channels)
        assertEquals(note.snip.frameCount * 2 - note.loopStartFrame.toInt(), preview.frameCount)
        assertContentEquals(note.snip.samples, preview.samples.copyOf(note.snip.samples.size))
        assertContentEquals(note.snip.samples.copyOfRange(note.loopStartFrame.toInt(), note.snip.samples.size),
            preview.samples.copyOfRange(note.snip.samples.size, preview.samples.size))
    }

    @Test
    fun `finite patches unsupported voices bad loop shapes and cancellation refuse`() {
        assertTrue(FlockPadMaker.supports(patch))
        assertFalse(FlockPadMaker.supports(patch.copy(playback = FlockPlayback.FINITE)))
        assertFailsWith<IllegalArgumentException> { FlockPadMaker.Spec(patch.copy(playback = FlockPlayback.FINITE)) }
        for (voice in listOf(FlockVoice.GATHER, FlockVoice.SPLIT)) assertFalse(FlockPadMaker.supports(voice))
        for (model in 1..2) assertTrue(FlockPadMaker.supports(patch.copy(model = model)))
        for (loopStart in listOf(0L, 127L, 128L)) {
            val bad = fixtures().toMutableList().also { it[0] = it[0].copy(loopStartFrame = loopStart) }
            assertFailsWith<IllegalArgumentException> { FlockPadMaker.assemble("BAD LOOP", spec, bad) }
        }
        for (snip in listOf(Snip(FloatArray(256), 2, 44_100), Snip(FloatArray(128), 1, 48_000))) {
            val bad = fixtures().toMutableList().also { it[0] = it[0].copy(snip = snip) }
            assertFailsWith<IllegalArgumentException> { FlockPadMaker.assemble("BAD AUDIO", spec, bad) }
        }
        assertFailsWith<IllegalArgumentException> { FlockPadMaker.renderZone(spec, 49) }
        assertFailsWith<CancellationException> { FlockPadMaker.renderZone(spec, patch.midi) { true } }
        for (fraction in listOf(0f, .5f, 1f)) {
            val converted = FlockPadMaker.spec(patch.copy(playback = FlockPlayback.FINITE), fraction)
            assertEquals(FlockPlayback.HELD, converted.patch.playback)
            assertEquals(patch.seed, converted.patch.seed)
            assertEquals(patch.model, converted.patch.model)
            assertTrue(converted.releaseSeconds in .1f..1.5f)
        }
    }

    @Test
    fun `an orphan editable recipe is never overwritten by package export`() {
        val dir = createTempDirectory("flock-recipe-refusal").toFile()
        try {
            val recipe = File(dir, "MY RIBBON.patch.json").also { it.writeText("edited source") }
            val failure = assertFailsWith<DestinationExists> { FlockPadMaker.export("MY RIBBON", spec, fixtures(), dir) }
            assertEquals(recipe, failure.path)
            assertEquals("edited source", recipe.readText())
            assertFalse(File(dir, "MY RIBBON.xty").exists())
            FlockPadMaker.export("MY RIBBON", spec, fixtures(), dir, overwrite = true)
            assertEquals(patch, FlockPatch.fromJsonText(recipe.readText()))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun fixtures(): List<KeyNote> = List(9) { KeyNote(Snip(FloatArray(128), 1, 44_100), 32L) }

    private fun text(parent: Element, tag: String): String = parent.getElementsByTagName(tag).item(0).textContent

    private fun leInt(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun riffChunk(bytes: ByteArray, wanted: String): Int {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val size = leInt(bytes, offset + 4)
            if (name == wanted) return offset + 8
            offset += 8 + size + (size and 1)
        }
        error("no $wanted RIFF chunk")
    }
}
