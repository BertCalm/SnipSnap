package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.OverlapHeld
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.OverlapPlayback
import com.snipsnap.synth.OverlapTopology
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlapPadMakerTest {
    @Test
    fun `three native roots retain the selected reference and limit resampling at both edges`() {
        assertEquals(listOf(36, 48, 60), OverlapPadMaker.zoneMidis(OverlapPadMaker.Spec(patch())))
        assertEquals(listOf(24, 36, 48), OverlapPadMaker.zoneMidis(OverlapPadMaker.Spec(patch().copy(midi = 24))))
        assertEquals(listOf(60, 72, 84), OverlapPadMaker.zoneMidis(OverlapPadMaker.Spec(patch().copy(midi = 84))))
        for (midi in 24..84) {
            val spec = OverlapPadMaker.Spec(patch().copy(midi = midi))
            val roots = OverlapPadMaker.zoneMidis(spec)
            val (program, _) = OverlapPadMaker.assemble("ZONES", spec, fixtures())
            assertEquals(3, roots.size)
            assertTrue(midi in roots)
            assertTrue(roots.all { it in 24..84 })
            assertEquals(listOf(12, 12), roots.zipWithNext { a, b -> b - a })
            assertEquals(roots.first() - 6, program.keygroups.first().lowNote)
            assertEquals(roots.last() + 6, program.keygroups.last().highNote)
            program.keygroups.zipWithNext { a, b -> assertEquals(a.highNote + 1, b.lowNote) }
            for (note in program.keygroups.first().lowNote..program.keygroups.last().highNote) {
                val zone = program.keygroups.single { note in it.lowNote..it.highNote }
                assertTrue(abs(note - zone.rootNote) <= 6)
            }
        }
    }

    @Test
    fun `complete edited held recipe and native loops agree across source WAV sidecar XPM and XTY`() {
        val dir = createTempDirectory("overlap-phone-export").toFile()
        try {
            val source = patch().copy(midi = 55, velocity = .37f, releaseSeconds = .43f,
                macros = OverlapHeld.defaults(OverlapTopology.TREFOIL) + mapOf("KNOT" to .42f, "RING" to .51f))
            val spec = OverlapPadMaker.Spec(source)
            val notes = fixtures()
            val name = "MY WEAVE"
            val program = OverlapPadMaker.export(name, spec, notes, dir)
            assertTrue(program.explicitNoteOn)
            assertEquals(source, OverlapPatch.fromJsonText(OverlapPadMaker.recipeFile(dir, name).readText()))
            val sidecar = InstrumentStore.sidecar(dir, name)
            val phone = InstrumentStore.read(sidecar)
            assertEquals(source.releaseSeconds, phone.release)
            assertEquals(listOf(43, 55, 67), phone.zones.map { it.rootNote })
            assertEquals(listOf(37..49, 50..61, 62..73), phone.zones.map { it.lowNote..it.highNote })
            val native = Mpc3Project.read(File(dir, "$name.xty")).tracks.single()
                .getValue("program").obj().getValue("drum").obj().getValue("instruments").arr()
            val data = File(dir, "${name}_[TrackData]")
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val xml = factory.newDocumentBuilder().parse(File(data, "$name.xpm"))
            val instruments = xml.getElementsByTagName("Instrument")
            assertEquals(3, instruments.length)
            for ((index, zone) in phone.zones.withIndex()) {
                val layer = program.keygroups[index].layers.single()
                val file = File(dir, zone.sample)
                val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount))
                assertEquals(sheet, WavReader.readSmplStrict(file))
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, notes[index].snip, smpl = sheet) }
                assertContentEquals(encoded.toByteArray(), file.readBytes(), "the package must keep its native samples and onset")
                assertEquals(notes[index].loopStartFrame, zone.loopStartFrame)
                assertEquals(notes[index].snip.frameCount.toLong(), zone.frameCount)
                val riff = file.readBytes()
                val smpl = riffChunk(riff, "smpl")
                assertEquals(zone.rootNote, leInt(riff, smpl + 12))
                assertEquals(zone.loopStartFrame, leInt(riff, smpl + 44).toLong())
                assertEquals(zone.frameCount - 1, leInt(riff, smpl + 48).toLong(), "RIFF stores an inclusive loop end")
                val x = instruments.item(index) as Element
                assertEquals(zone.lowNote, text(x, "LowNote").toInt())
                assertEquals(zone.highNote, text(x, "HighNote").toInt())
                assertEquals("2", text(x, "TriggerMode"))
                assertEquals(0f, text(x, "VolumeAttack").toFloat())
                assertEquals(source.releaseSeconds, text(x, "VolumeRelease").toFloat())
                val xl = x.getElementsByTagName("Layer").item(0) as Element
                assertEquals(layer.sampleName, text(xl, "SampleName"))
                assertEquals(zone.rootNote, text(xl, "RootNote").toInt())
                assertEquals(zone.frameCount, text(xl, "SliceEnd").toLong())
                assertEquals(zone.loopStartFrame, text(xl, "SliceLoopStart").toLong())
                assertEquals("1", text(xl, "SliceLoop"))
                val n = native[index].obj()
                assertEquals(zone.lowNote, n.getValue("lowNote").int())
                assertEquals(zone.highNote, n.getValue("highNote").int())
                assertEquals(2, n.getValue("triggerMode").int())
                val envelope = n.getValue("synthSection").obj().getValue("ampEnvelope").obj()
                assertFalse(envelope.getValue("OneShot").obj().getValue("value0").bool())
                assertEquals(source.releaseSeconds.toDouble(), envelope.getValue("Release").obj().getValue("value0").num())
                val nl = n.getValue("layersv").arr().first().obj()
                assertEquals(layer.sampleName, nl.getValue("sampleName").str())
                assertEquals(zone.rootNote, nl.getValue("rootNote").int())
                val slice = nl.getValue("sliceInfo").obj()
                assertEquals(0L, slice.getValue("Start").long())
                assertEquals(zone.frameCount, slice.getValue("End").long())
                assertEquals(zone.loopStartFrame, slice.getValue("LoopStart").long())
                assertEquals(1, slice.getValue("LoopMode").int())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `a real steady zone wraps at its declared body and note off releases its current phase`() {
        val source = patch().copy(midi = 55, velocity = .37f, releaseSeconds = .43f,
            macros = OverlapHeld.defaults(OverlapTopology.TREFOIL) + mapOf("KNOT" to .42f, "RING" to .51f))
        val spec = OverlapPadMaker.Spec(source)
        val midi = source.midi
        val note = OverlapPadMaker.renderZone(spec, spec.patch.midi)
        assertContentEquals(OverlapHeld.render(source).snip.samples, note.snip.samples,
            "the maker must render the edited topology, note, velocity and macros without resetting them")
        val sample = "steady.wav"
        val instrument = InstrumentStore.Instrument("STEADY", spec.releaseSeconds,
            listOf(InstrumentStore.Zone(midi, midi, midi, sample, note.snip.frameCount.toLong(), note.loopStartFrame)))
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44100, mapOf(sample to note.snip.samples)), 44100)
        assertTrue(player.noteOn(midi))
        val heldFrames = note.snip.frameCount + 257
        val held = render(player, heldFrames)
        for (frame in note.snip.frameCount - 2 until heldFrames) {
            val source = if (frame < note.snip.frameCount) frame else note.loopStartFrame.toInt() + frame - note.snip.frameCount
            assertEquals(note.snip.samples[source] * InstrumentEngine.VOICE_LEVEL, held[frame * 2], 1e-7f)
        }
        assertEquals(listOf(midi), player.activeNotes)
        player.noteOff(midi)
        val released = render(player, ((spec.releaseSeconds + .05f) * 44100).roundToInt())
        assertTrue(released.take(4410).any { abs(it) > 1e-5f }, "note-off must fade the current loop rather than stop it")
        assertTrue(released.takeLast(882).all { it == 0f })
        assertTrue(player.activeNotes.isEmpty())
        val preview = OverlapPadMaker.preview(spec)
        assertEquals(2, preview.channels)
        assertEquals(3 * note.loopStartFrame.toInt() + ((spec.releaseSeconds + .05f) * 44100).roundToInt(), preview.frameCount)
        assertTrue(preview.samples.all { it.isFinite() })
        assertTrue(preview.samples.takeLast(1764).all { it == 0f })
        val anotherRelease = OverlapPadMaker.renderZone(OverlapPadMaker.Spec(spec.patch.copy(releaseSeconds = 1.2f)), midi)
        assertContentEquals(note.snip.samples, anotherRelease.snip.samples, "release changes note-off, not captured audio")
    }

    @Test
    fun `finite delivery unsupported zones malformed audio and cancellation refuse before writing`() {
        val source = patch()
        val spec = OverlapPadMaker.Spec(source)
        assertTrue(OverlapPadMaker.supports(source))
        assertFalse(OverlapPadMaker.supports(source.copy(playback = OverlapPlayback.FINITE)))
        assertFailsWith<IllegalArgumentException> { OverlapPadMaker.Spec(source.copy(playback = OverlapPlayback.FINITE)) }
        assertFailsWith<IllegalArgumentException> { OverlapPadMaker.renderZone(spec, 49) }
        assertFailsWith<CancellationException> { OverlapPadMaker.renderZone(spec, 48) { true } }
        assertFailsWith<CancellationException> { OverlapPadMaker.preview(spec) { true } }
        assertFailsWith<IllegalArgumentException> { OverlapPadMaker.assemble("TOO FEW", spec, fixtures().take(2)) }
        for (bad in listOf(
            KeyNote(Snip(FloatArray(4096), 2, 44100), 1024),
            KeyNote(Snip(FloatArray(4096), 1, 48000), 2048),
            KeyNote(Snip(FloatArray(4096), 1, 44100), 0),
            KeyNote(Snip(FloatArray(4096), 1, 44100), 2047),
            KeyNote(Snip(FloatArray(4096) { Float.NaN }, 1, 44100), 2048),
        )) {
            assertFailsWith<IllegalArgumentException> { OverlapPadMaker.assemble("BAD", spec, listOf(bad) + fixtures().drop(1)) }
        }
        for (fraction in listOf(0f, .5f, 1f, Float.NaN)) {
            val edited = OverlapPadMaker.spec(source, fraction)
            assertEquals(source.copy(releaseSeconds = edited.releaseSeconds), edited.patch)
            assertTrue(edited.releaseSeconds in .1f..1.5f)
        }
    }

    @Test
    fun `an orphan patch or phone sidecar cannot be overwritten by a new package`() {
        val directory = createTempDirectory("overlap-package-refusal").toFile()
        try {
            val spec = OverlapPadMaker.Spec(patch())
            for (file in listOf(OverlapPadMaker.recipeFile(directory, "MINE"), InstrumentStore.sidecar(directory, "MINE"))) {
                file.writeText("existing source")
                val failure = assertFailsWith<DestinationExists> { OverlapPadMaker.export("MINE", spec, fixtures(), directory) }
                assertEquals(file, failure.path)
                assertEquals("existing source", file.readText())
                assertFalse(File(directory, "MINE.xty").exists())
                assertFalse(File(directory, "MINE_[TrackData]").exists())
                file.delete()
            }
            OverlapPadMaker.export("MINE", spec, fixtures(), directory)
            val saved = OverlapPadMaker.recipeFile(directory, "MINE").readText()
            assertFailsWith<DestinationExists> { OverlapPadMaker.export("MINE", spec, fixtures(), directory) }
            assertEquals(saved, OverlapPadMaker.recipeFile(directory, "MINE").readText())
        } finally { directory.deleteRecursively() }
    }

    private fun patch() = OverlapPatch("STEADY WEAVE", OverlapTopology.TREFOIL,
        OverlapHeld.defaults(OverlapTopology.TREFOIL), midi = 48, velocity = .8f,
        playback = OverlapPlayback.HELD)

    private fun fixtures(): List<KeyNote> = List(3) { zone ->
        KeyNote(Snip(FloatArray(4096) { (.05 * (zone + 1) * sin(2 * PI * (it % 2048) / 64)).toFloat() }, 1, 44100), 2048)
    }

    private fun render(player: InstrumentEngine, frames: Int): FloatArray {
        val result = FloatArray(frames * 2)
        var at = 0
        while (at < frames) {
            val count = minOf(512, frames - at)
            val block = FloatArray(count * 2)
            player.render(block, count)
            block.copyInto(result, at * 2)
            at += count
        }
        return result
    }

    private fun text(parent: Element, tag: String): String = parent.getElementsByTagName(tag).item(0).textContent
    private fun leInt(bytes: ByteArray, at: Int): Int = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun riffChunk(bytes: ByteArray, wanted: String): Int {
        var at = 12
        while (at + 8 <= bytes.size) {
            val size = leInt(bytes, at + 4)
            if (bytes.copyOfRange(at, at + 4).toString(Charsets.US_ASCII) == wanted) return at + 8
            at += 8 + size + (size and 1)
        }
        error("no $wanted chunk")
    }
}
