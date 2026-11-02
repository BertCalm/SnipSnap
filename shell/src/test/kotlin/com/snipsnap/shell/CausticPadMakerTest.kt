package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CausticPadMakerTest {
    private val dest = java.nio.file.Files.createTempDirectory("caustic-keys").toFile()
    @AfterTest fun cleanUp() { dest.deleteRecursively() }

    private fun patch(voice: CausticVoice = CausticVoice.ALLOY, midi: Int = 60) =
        CausticPatch("Saved vessel", voice, CausticInstrument.defaults(voice, 2) + mapOf(
            "SOURCE" to .62f, "FOCUS" to .29f, "LENGTH" to 0f, "MOTION" to 0f, "HOLD" to 0f),
            midi = midi, velocity = .43f, model = 2)

    /** Cheap exact-period audio isolates package and player contracts from DSP generation cost. */
    private fun fixture(midi: Int): KeyNote {
        val period = (16.0 * 44_100 / Keys.midiHz(midi)).roundToInt()
        val audio = FloatArray(period * 3) { index ->
            val onset = minOf(1f, index / 64f)
            (.2 * onset * sin(2 * PI * 16 * (index % period) / period)).toFloat()
        }
        return KeyNote(Snip(audio, 1, 44_100), (2L * period))
    }

    private fun fixtures(spec: CausticPadMaker.Spec) = CausticPadMaker.zoneMidis(spec).map(::fixture)

    @Test fun `every voice tiles a clipped native keyboard span around its saved root`() {
        for (voice in CausticVoice.entries) for (root in listOf(36, 37, 60, 83, 84)) {
            val spec = CausticPadMaker.Spec(patch(voice, root))
            val midis = CausticPadMaker.zoneMidis(spec)
            assertEquals(maxOf(36, root - 12), midis.first())
            assertEquals(minOf(84, root + 12), midis.last())
            assertTrue(root in midis)
            assertEquals(midis.distinct().sorted(), midis)
            val (program, samples) = CausticPadMaker.assemble("Caustic Keys", spec, fixtures(spec))
            assertEquals(midis, program.keygroups.map { it.rootNote })
            assertEquals((midis.first()..midis.last()).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
            assertTrue(program.keygroups.all { group ->
                group.rootNote in group.lowNote..group.highNote &&
                    (group.lowNote..group.highNote).all { abs(it - group.rootNote) <= 1 }
            })
            assertEquals(midis.size, samples.size)
            assertTrue(program.explicitNoteOn)
            assertTrue(program.keygroups.all { it.layers.single().velStart == 0 && it.layers.single().velEnd == 127 })
        }
        assertEquals((48..72 step 3).toList(), CausticPadMaker.zoneMidis(CausticPadMaker.Spec(patch())))
    }

    @Test fun `all voices retain root loop release and source recipe in every package representation`() {
        for ((index, voice) in CausticVoice.entries.withIndex()) {
            val patch = patch(voice)
            val before = patch.toJsonText()
            val spec = CausticPadMaker.Spec(patch, .4f)
            val notes = fixtures(spec)
            val name = "Caustic Keys $index"
            val program = CausticPadMaker.export(name, spec, notes, dest)
            val data = File(dest, Mpc3TrackWriter.trackDataDirName(name))
            assertEquals(before, File(data, CausticPadMaker.PATCH_FILE).readText())
            assertEquals(patch, CausticPatch.fromJsonText(File(data, CausticPadMaker.PATCH_FILE).readText()))
            val (_, instrument) = InstrumentStore.list(dest).single { it.second.name == name }
            assertEquals(.4f, instrument.release)
            assertEquals(CausticPadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
            val native = Json.parse(Acvs.read(File(dest, "$name.xty")).payloadText).obj()
                .getValue("data").obj().getValue("program").obj().getValue("drum").obj()
                .getValue("instruments").arr()
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = factory.newDocumentBuilder().parse(File(data, "$name.xpm"))
                .getElementsByTagName("Instrument")
            assertEquals(notes.size, xml.length)
            for ((zoneIndex, zone) in instrument.zones.withIndex()) {
                val source = notes[zoneIndex]
                val file = File(dest, zone.sample)
                val decoded = WavReader.read(file)
                assertEquals(SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount)), WavReader.readSmpl(file))
                assertEquals(source.loopStartFrame, zone.loopStartFrame)
                assertEquals(source.snip.frameCount.toLong(), zone.frameCount)
                assertEquals(1, decoded.channels)
                assertEquals(44_100, decoded.sampleRate)
                assertTrue(decoded.samples.indices.all { abs(decoded.samples[it] - source.snip.samples[it]) < 2e-7f })
                assertTrue(Keys.seamError(decoded.samples, zone.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                val layer = program.keygroups[zoneIndex].layers.single()
                assertEquals(source.loopStartFrame, layer.loopStartFrame)
                val n = native[zoneIndex].obj()
                val nLayer = n.getValue("layersv").arr().first().obj()
                val slice = nLayer.getValue("sliceInfo").obj()
                assertEquals(2, n.getValue("triggerMode").int())
                assertEquals(zone.rootNote, nLayer.getValue("rootNote").int())
                assertEquals(zone.frameCount, slice.getValue("End").long())
                assertEquals(zone.loopStartFrame, slice.getValue("LoopStart").long())
                assertEquals(1, slice.getValue("LoopMode").int())
                val envelope = n.getValue("synthSection").obj().getValue("ampEnvelope").obj()
                assertEquals(.4f.toDouble(), envelope.getValue("Release").obj().getValue("value0").num())
                assertFalse(envelope.getValue("OneShot").obj().getValue("value0").bool())
                val x = xml.item(zoneIndex) as Element
                assertEquals(zone.lowNote, text(x, "LowNote").toInt())
                assertEquals(zone.highNote, text(x, "HighNote").toInt())
                assertEquals(2, text(x, "TriggerMode").toInt())
                assertEquals(0f, text(x, "VolumeAttack").toFloat())
                assertEquals(.4f, text(x, "VolumeRelease").toFloat())
                val xLayer = x.getElementsByTagName("Layer").item(0) as Element
                assertEquals(zone.rootNote, text(xLayer, "RootNote").toInt())
                assertEquals(zone.loopStartFrame, text(xLayer, "SliceLoopStart").toLong())
                assertEquals(zone.frameCount, text(xLayer, "SliceEnd").toLong())
                assertEquals(1, text(xLayer, "SliceLoop").toInt())
            }
            assertEquals(before, patch.toJsonText())
            assertEquals(0f, patch.macros.getValue("HOLD"))
        }
    }

    @Test fun `exported player sustains beyond the source then note off ends it at the chosen release`() {
        val spec = CausticPadMaker.Spec(patch(), .2f)
        val name = "Caustic Player"
        CausticPadMaker.export(name, spec, fixtures(spec), dest)
        val (_, instrument) = InstrumentStore.list(dest).single()
        val loaded = InstrumentEngine.Loaded(instrument, 44_100,
            instrument.zones.associate { it.sample to WavReader.read(File(dest, it.sample)).samples })
        val player = InstrumentEngine(loaded, 48_000)
        assertTrue(player.noteOn(61, .8f))
        val held = render(player, 1.1f, 48_000)
        assertTrue(rms(held.samples.takeLast(48_000).toFloatArray()) > .001)
        val pitch = assertNotNull(Pitch.detect(held, .5f, .4f))
        val cents = 1200 * ln(pitch.hz / Keys.midiHz(61).toDouble()) / ln(2.0)
        assertTrue(abs(cents) < 5, "held transposed key is $cents cents from MIDI61")
        player.noteOff(61)
        val release = render(player, .35f, 48_000)
        assertTrue(rms(release.samples.copyOfRange(0, 960)) > .001)
        assertTrue(release.samples.takeLast(960).all { it == 0f })
        assertTrue(player.activeNotes.isEmpty())
        assertFalse(player.noteOn(47), "the package does not advertise unsampled outer roots")
    }

    @Test fun `actual held zones preserve every saved voice and control without changing the source recipe`() {
        for (voice in CausticVoice.entries) {
            val patch = patch(voice)
            val before = patch.toJsonText()
            val spec = CausticPadMaker.Spec(patch)
            val note = CausticPadMaker.renderZone(spec, patch.midi)
            val direct = CausticInstrument.keyNote(voice, patch.macros + ("HOLD" to 1f),
                patch.midi, patch.velocity, patch.model)
            assertEquals(direct.loopStartFrame, note.loopStartFrame)
            assertContentEquals(direct.snip.samples, note.snip.samples)
            assertTrue(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1)
            assertEquals(44_100, note.snip.sampleRate)
            assertEquals(1, note.snip.channels)
            assertTrue(note.snip.samples.all { it.isFinite() })
            assertTrue(note.snip.peak() > 0f)
            assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            assertEquals(before, patch.toJsonText())
        }
    }

    @Test fun `preview demonstrates arrival sustain and host release while cancellation writes nothing`() {
        val patch = patch(CausticVoice.HOLLOW_MALLET)
        val before = patch.toJsonText()
        val spec = CausticPadMaker.Spec(patch, .1f)
        val preview = CausticPadMaker.preview(spec)
        assertEquals(2, preview.channels)
        assertEquals(44_100, preview.sampleRate)
        assertTrue(preview.samples.all { it.isFinite() })
        assertTrue(preview.peak() > 0f)
        assertTrue(preview.samples.takeLast(1_000).all { it == 0f })
        assertEquals(before, patch.toJsonText())
        assertFailsWith<CancellationException> { CausticPadMaker.renderZone(spec, patch.midi) { true } }
        assertFailsWith<CancellationException> { CausticPadMaker.preview(spec) { true } }
        assertTrue(dest.listFiles().orEmpty().isEmpty())
    }

    @Test fun `legacy models bad releases invalid captures and collisions refuse without partial packages`() {
        val patch = patch()
        assertTrue(CausticPadMaker.supports(patch))
        for (voice in CausticVoice.entries) {
            val old = CausticPatch("Old vessel", voice, CausticInstrument.defaults(voice, 1), model = 1)
            assertFalse(CausticPadMaker.supports(old))
            assertFailsWith<IllegalArgumentException> { CausticPadMaker.Spec(old) }
            assertEquals(1, old.model)
        }
        for (release in listOf(.01f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { CausticPadMaker.Spec(patch, release) }
        assertEquals(.1f, CausticPadMaker.spec(patch, 0f).releaseSeconds)
        assertEquals(1.5f, CausticPadMaker.spec(patch, 1f).releaseSeconds)
        assertEquals("0.40 s", CausticPadMaker.secondsLabel(.4f))
        val spec = CausticPadMaker.Spec(patch)
        val notes = fixtures(spec)
        assertFailsWith<IllegalArgumentException> { CausticPadMaker.renderZone(spec, 47) }
        assertFailsWith<IllegalArgumentException> { CausticPadMaker.export("Bad/Name", spec, notes, dest) }
        assertFailsWith<IllegalArgumentException> { CausticPadMaker.export("Incomplete", spec, notes.dropLast(1), dest) }
        val invalid = listOf(
            notes.first().copy(loopStartFrame = 0),
            notes.first().copy(loopStartFrame = notes.first().snip.frameCount.toLong()),
            KeyNote(Snip(floatArrayOf(0f, Float.NaN, 0f, 0f), 1, 44_100), 1),
            KeyNote(Snip(floatArrayOf(0f, 1.1f, 0f, 0f), 1, 44_100), 1),
            KeyNote(Snip(FloatArray(8), 2, 44_100), 1),
        )
        for (bad in invalid) {
            val replaced = notes.toMutableList().also { it[0] = bad }
            assertFailsWith<IllegalArgumentException> { CausticPadMaker.export("Invalid", spec, replaced, dest) }
        }
        assertTrue(dest.listFiles().orEmpty().isEmpty())
        CausticPadMaker.export("Existing", spec, notes, dest)
        assertFailsWith<DestinationExists> { CausticPadMaker.export("Existing", spec, notes, dest) }
    }

    private fun text(element: Element, tag: String) = element.getElementsByTagName(tag).item(0).textContent
    private fun render(player: InstrumentEngine, seconds: Float, rate: Int): Snip {
        val frames = (seconds * rate).roundToInt()
        return Snip(FloatArray(frames * 2).also { player.render(it, frames) }, 2, rate)
    }
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
