package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmPatch
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OhmReplyRingPadMakerTest {
    private companion object {
        const val RATE = WavWriter.MPC_SAMPLE_RATE
        val patch = Ohm.replyRingPatch("Reply Ring Keys").copy(reply = .2f, ring = .8f,
            velocity = .6f, seed = -917, releaseSeconds = .15f)
        val spec = OhmPadMaker.Spec(patch)
        val notes by lazy { OhmPadMaker.zoneMidis(spec).map { OhmPadMaker.renderZone(spec, it) } }
    }

    @Test
    fun `finite native zones retain production audio and note off release in MPC2 MPC3 and phone`() {
        val dir = createTempDirectory("ohm-reply-instrument").toFile()
        try {
            val program = OhmPadMaker.export(patch.name, spec, notes, dir)
            assertTrue(program.explicitNoteOn)
            assertEquals(patch, OhmPatch.fromJsonText(File(dir, "${patch.name}.patch.json").readText()))
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(patch.releaseSeconds, instrument.release)
            assertEquals((48..72 step 3).toList(), instrument.zones.map { it.rootNote })
            assertEquals((48..72).toList(), instrument.zones.flatMap { (it.lowNote..it.highNote).toList() })
            assertEquals(null, instrument.zoneFor(47))
            assertEquals(null, instrument.zoneFor(73))
            val native = Json.parse(Acvs.read(File(dir, "${patch.name}.xty")).payloadText).obj()
                .getValue("data").obj().getValue("program").obj().getValue("drum").obj()
                .getValue("instruments").arr()
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = factory.newDocumentBuilder().parse(File(dir, "${patch.name}_[TrackData]/${patch.name}.xpm"))
                .getElementsByTagName("Instrument")
            assertEquals(9, xml.length)
            val audio = instrument.zones.mapIndexed { index, zone ->
                val file = File(sidecar.parentFile, zone.sample)
                val sheet = SmplChunk(zone.rootNote, null)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, notes[index].snip, smpl = sheet) }
                assertContentEquals(expected.toByteArray(), file.readBytes(), "${zone.rootNote}: PCM24 must remain exact")
                assertEquals(sheet, WavReader.readSmpl(file))
                assertEquals(7 * RATE, notes[index].snip.frameCount)
                assertEquals(0L, zone.loopStartFrame)
                val n = native[index].obj()
                assertEquals(2, n.getValue("triggerMode").int())
                val layer = n.getValue("layersv").arr().first().obj()
                assertFalse(layer.getValue("loop").bool())
                val slice = layer.getValue("sliceInfo").obj()
                assertEquals(0, slice.getValue("LoopMode").int())
                assertEquals(0L, slice.getValue("LoopStart").long())
                assertEquals(zone.frameCount, slice.getValue("End").long())
                assertEquals(spec.releaseSeconds.toDouble(), n.getValue("synthSection").obj()
                    .getValue("ampEnvelope").obj().getValue("Release").obj().getValue("value0").num())
                val x = xml.item(index) as Element
                assertEquals("2", text(x, "TriggerMode"))
                assertEquals(spec.releaseSeconds, text(x, "VolumeRelease").toFloat())
                val xLayer = x.getElementsByTagName("Layer").item(0) as Element
                assertEquals(zone.rootNote, text(xLayer, "RootNote").toInt())
                assertEquals("0", text(xLayer, "SliceLoop"))
                assertEquals("0", text(xLayer, "SliceLoopStart"))
                assertEquals(zone.frameCount, text(xLayer, "SliceEnd").toLong())
                val hit = assertNotNull(KeyHit.resolve(instrument, zone.rootNote, 1f) { zone.frameCount })
                assertEquals(1.0, hit.pitchRatio)
                assertFalse(hit.loops)
                WavReader.read(file).samples
            }
            val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, RATE, audio), RATE)
            assertTrue(player.noteOn(patch.midi))
            assertTrue(render(player, .5f).any { it != 0f })
            player.noteOff(patch.midi)
            val released = render(player, spec.releaseSeconds + .1f)
            assertTrue(released.takeLast(RATE / 10).all { it == 0f })
            assertTrue(player.activeNotes.isEmpty(), "finite voices must obey note-off before the sample ends")
            assertTrue(player.noteOn(patch.midi))
            val completed = render(player, 7.1f)
            assertTrue(completed.takeLast(RATE / 10).all { it == 0f })
            assertTrue(player.activeNotes.isEmpty(), "holding a finite encounter cannot create a sustain loop")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `preview is one intact arrival and release editing never injects HOLD`() {
        assertTrue(OhmPadMaker.supports(patch))
        val edited = OhmPadMaker.spec(patch, .7f)
        assertEquals(patch.copy(releaseSeconds = OhmPadMaker.RELEASE.value(.7f)), edited.patch)
        assertFalse("HOLD" in edited.patch.macros)
        val source = notes[OhmPadMaker.zoneMidis(spec).indexOf(patch.midi)]
        assertEquals(0L, source.loopStartFrame)
        assertContentEquals(patch.render().samples, source.snip.samples)
        assertContentEquals(source.snip.samples, OhmPadMaker.preview(spec).samples)
        assertFailsWith<CancellationException> { OhmPadMaker.renderZone(spec, patch.midi) { true } }
        val bad = List(9) { KeyNote(Snip(FloatArray(128), 1, RATE), 32L) }
        assertFailsWith<IllegalArgumentException> { OhmPadMaker.assemble("Invalid Loop", spec, bad) }
    }

    private fun text(parent: Element, name: String): String = parent.getElementsByTagName(name).item(0).textContent
    private fun render(player: InstrumentEngine, seconds: Float): FloatArray {
        val frames = (seconds * RATE).toInt()
        val out = FloatArray(frames * 2)
        val block = FloatArray(512)
        var at = 0
        while (at < frames) {
            val count = minOf(256, frames - at)
            player.render(block, count)
            block.copyInto(out, at * 2, 0, count * 2)
            at += count
        }
        return out
    }
}
