package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmCapture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OhmPadMakerTest {
    private companion object {
        val capture = OhmCapture.fromSnip(Snip(FloatArray(11_025) { i ->
            (.3 * sin(2 * PI * 167 * i / 44_100) * kotlin.math.exp(-i / 3_000.0)).toFloat()
        }, 1, 44_100), "Generated tap")
        val patch = OhmPatch("OHM Captured Keys", Ohm.defaults(4) + mapOf(
            "LENGTH" to .2f, "MEDDLING" to .6f, "COMMUNE" to .7f, "HOLD" to 1f),
            midi = 61, velocity = .65f, seed = Int.MIN_VALUE, gesture = OhmGesture.GATHER,
            model = 4, options = OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture), releaseSeconds = .15f)
        val spec = OhmPadMaker.Spec(patch)
        val notes by lazy { OhmPadMaker.zoneMidis(spec).map { OhmPadMaker.renderZone(spec, it) } }
    }

    @Test
    fun `the complete captured performance loops and releases through both MPC generations and the phone`() {
        val dir = createTempDirectory("ohm-instrument").toFile()
        try {
            val program = OhmPadMaker.export(patch.name, spec, notes, dir)
            assertEquals(patch, OhmPatch.fromJsonText(File(dir, "${patch.name}.patch.json").readText()))
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(patch.releaseSeconds, instrument.release)
            assertEquals(OhmPadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
            val native = Json.parse(Acvs.read(File(dir, "${patch.name}.xty")).payloadText).obj()
                .getValue("data").obj().getValue("program").obj().getValue("drum").obj()
                .getValue("instruments").arr()
            val data = File(dir, "${patch.name}_[TrackData]")
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = factory.newDocumentBuilder().parse(File(data, "${patch.name}.xpm"))
                .getElementsByTagName("Instrument")
            assertEquals(notes.size, xml.length)
            val audio = instrument.zones.mapIndexed { index, zone ->
                val file = File(sidecar.parentFile, zone.sample)
                val sample = WavReader.read(file)
                val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount))
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, notes[index].snip, smpl = sheet) }
                assertContentEquals(encoded.toByteArray(), file.readBytes(), "${zone.rootNote}: exported PCM changed")
                assertEquals(sheet, WavReader.readSmpl(file))
                assertEquals(notes[index].loopStartFrame, zone.loopStartFrame)
                val layer = program.keygroups[index].layers.single()
                val n = native[index].obj()
                assertEquals(2, n.getValue("triggerMode").int(), "held notes use Note On trigger mode")
                val nativeLayer = n.getValue("layersv").arr().first().obj()
                val slice = nativeLayer.getValue("sliceInfo").obj()
                assertEquals(zone.rootNote, nativeLayer.getValue("rootNote").int())
                assertEquals(zone.frameCount, slice.getValue("End").long())
                assertEquals(zone.loopStartFrame, slice.getValue("LoopStart").long())
                assertEquals(1, slice.getValue("LoopMode").int())
                assertEquals(spec.releaseSeconds.toDouble(), n.getValue("synthSection").obj()
                    .getValue("ampEnvelope").obj().getValue("Release").obj().getValue("value0").num())
                val x = xml.item(index) as Element
                assertEquals(zone.lowNote, text(x, "LowNote").toInt())
                assertEquals(zone.highNote, text(x, "HighNote").toInt())
                assertEquals(spec.releaseSeconds, text(x, "VolumeRelease").toFloat())
                val xLayer = x.getElementsByTagName("Layer").item(0) as Element
                assertEquals(layer.sampleName, text(xLayer, "SampleName"))
                assertEquals(zone.rootNote, text(xLayer, "RootNote").toInt())
                assertEquals(zone.loopStartFrame, text(xLayer, "SliceLoopStart").toLong())
                assertEquals(zone.frameCount, text(xLayer, "SliceEnd").toLong())
                assertEquals("1", text(xLayer, "SliceLoop"))
                val hit = assertNotNull(KeyHit.resolve(instrument, zone.rootNote, 1f) { path ->
                    WavReader.read(File(sidecar.parentFile, path)).frameCount.toLong()
                })
                assertEquals(1.0, hit.pitchRatio)
                assertTrue(hit.loops)
                sample.samples
            }
            // The outer zones stretch to the keyboard's ends, so keys past the authored span land in them.
            assertEquals(0, instrument.zones.first().lowNote)
            assertEquals(127, instrument.zones.last().highNote)
            assertEquals(instrument.zones.first(), instrument.zoneFor(47))
            assertEquals(instrument.zones.last(), instrument.zoneFor(73))
            val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, audio), 44_100)
            assertTrue(player.noteOn(patch.midi))
            val sourceSeconds = notes[OhmPadMaker.zoneMidis(spec).indexOf(patch.midi)].snip.durationSeconds
            val played = render(player, sourceSeconds + 1f)
            assertTrue(rms(played.samples.copyOfRange(played.samples.size - 8_820, played.samples.size)) > 1e-5,
                "held playback stopped at the original file end")
            assertEquals(listOf(patch.midi), player.activeNotes)
            player.noteOff(patch.midi)
            val released = render(player, spec.releaseSeconds + .1f)
            assertTrue(rms(released.samples.takeLast(4_410).toFloatArray()) < 1e-7)
            assertTrue(player.activeNotes.isEmpty(), "note-off must release every held voice")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `native roots include every saved note and bound capture speed to one semitone`() {
        for (midi in 48..72) {
            val local = OhmPadMaker.Spec(patch.copy(midi = midi))
            val roots = OhmPadMaker.zoneMidis(local)
            assertTrue(midi in roots)
            val (program, _) = OhmPadMaker.assemble("OHM ROOTS", local, fixtures(roots.size))
            assertEquals((48..72).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
            for (zone in program.keygroups) for (note in zone.lowNote..zone.highNote) assertTrue(abs(note - zone.rootNote) <= 1)
        }
        for (model in 3..4) for (fraction in listOf(0f, .5f, 1f)) {
            val converted = OhmPadMaker.spec(patch.copy(model = model, macros = patch.macros + ("HOLD" to 0f)), fraction)
            assertEquals(model, converted.patch.model, "instrument setup must preserve the selected renderer")
            assertEquals(patch.options, converted.patch.options)
            assertEquals(patch.seed, converted.patch.seed)
            assertEquals(patch.gesture, converted.patch.gesture)
            assertTrue(converted.releaseSeconds in .02f..3f)
            assertTrue(Ohm.isHeld(converted.patch))
        }
    }

    @Test
    fun `finite preview preserves native onset and sustain before its separate release`() {
        val source = notes[OhmPadMaker.zoneMidis(spec).indexOf(patch.midi)]
        val direct = Ohm.renderHeld(patch)
        assertEquals(direct.loopStartFrame, source.loopStartFrame)
        assertContentEquals(direct.snip.samples, source.snip.samples)
        val preview = OhmPadMaker.preview(spec)
        assertContentEquals(source.snip.samples, preview.samples.copyOf(source.snip.samples.size))
        assertEquals(0f, preview.samples.last())
        assertTrue(preview.frameCount > source.snip.frameCount)
        assertContentEquals(direct.snip.samples, source.snip.samples, "preview must not fade the exported source")
    }

    @Test
    fun `unsupported recipes bad loop metadata cancellation and orphan recipes refuse`() {
        assertTrue(OhmPadMaker.supports(patch))
        assertFalse(OhmPadMaker.supports(patch.copy(macros = patch.macros + ("HOLD" to 0f))))
        assertFalse(OhmPadMaker.supports(OhmPatch("Legacy", Ohm.defaults())))
        assertFailsWith<IllegalArgumentException> { OhmPadMaker.Spec(OhmPatch("Legacy", Ohm.defaults())) }
        assertFailsWith<CancellationException> { OhmPadMaker.renderZone(spec, patch.midi) { true } }
        assertFailsWith<IllegalArgumentException> { OhmPadMaker.renderZone(spec, 47) }
        val count = OhmPadMaker.zoneMidis(spec).size
        for (start in listOf(0L, 127L, 128L)) {
            val bad = fixtures(count).toMutableList().also { it[0] = it[0].copy(loopStartFrame = start) }
            assertFailsWith<IllegalArgumentException> { OhmPadMaker.assemble("OHM BAD", spec, bad) }
        }
        for (snip in listOf(Snip(FloatArray(256), 2, 44_100), Snip(FloatArray(128), 1, 48_000), Snip(FloatArray(128) { Float.NaN }, 1, 44_100))) {
            val bad = fixtures(count).toMutableList().also { it[0] = it[0].copy(snip = snip) }
            assertFailsWith<IllegalArgumentException> { OhmPadMaker.assemble("OHM BAD", spec, bad) }
        }
        val dir = createTempDirectory("ohm-orphan").toFile()
        try {
            val recipe = File(dir, "OHM ORPHAN.patch.json").also { it.writeText("saved edit") }
            assertFailsWith<DestinationExists> { OhmPadMaker.export("OHM ORPHAN", spec, fixtures(count), dir) }
            assertEquals("saved edit", recipe.readText())
            assertFalse(File(dir, "OHM ORPHAN.xty").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun fixtures(count: Int): List<KeyNote> = List(count) { KeyNote(Snip(FloatArray(128), 1, 44_100), 32L) }
    private fun text(parent: Element, name: String): String = parent.getElementsByTagName(name).item(0).textContent
    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun render(player: InstrumentEngine, seconds: Float): Snip {
        val frames = (seconds * 44_100).toInt()
        val samples = FloatArray(frames * 2)
        val block = FloatArray(512)
        var at = 0
        while (at < frames) {
            val count = minOf(256, frames - at)
            player.render(block, count)
            block.copyInto(samples, at * 2, 0, count * 2)
            at += count
        }
        return Snip(samples, 2, 44_100)
    }
}
