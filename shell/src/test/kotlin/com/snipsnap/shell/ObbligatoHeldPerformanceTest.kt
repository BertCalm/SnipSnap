package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.ObbligatoPlayback
import com.snipsnap.synth.ObbligatoRelationship
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** Complete held recipes must survive packages and drive the actual note-off preview. */
class ObbligatoHeldPerformanceTest {
    private fun patch(release: Float = .23f) = ObbligatoPatch("MY DUET",
        Obbligato.defaults() + mapOf("BOND" to ObbligatoRelationship.PLAY.bond, "HOLD" to 1f),
        midi = 60, velocity = .37f, seed = -617, model = Obbligato.PERFORMANCE_MODEL,
        playback = ObbligatoPlayback.HELD, releaseSeconds = release)

    // The companion PadMaker test renders all thirteen real notes. Small periodic fixtures
    // isolate package metadata here, so serializer tests do not repeat that expensive solve.
    private fun fixtures(spec: ObbligatoPadMaker.Spec): List<KeyNote> =
        ObbligatoPadMaker.zoneMidis(spec).map { midi ->
            val period = (44_100 / Keys.midiHz(midi)).roundToInt()
            val onset = period * 8
            val body = period * ceil(22_050.0 / period).toInt()
            KeyNote(Snip(FloatArray(onset + body) { frame ->
                val attack = minOf(1.0, frame.toDouble() / onset)
                (.2 * attack * sin(2.0 * PI * (frame % period) / period)).toFloat()
            }, 1, 44_100), onset.toLong())
        }

    @Test
    fun `edited held settings and bounded loop metadata survive every package format`() {
        val directory = createTempDirectory("obbligato-held-package").toFile()
        try {
            val source = patch()
            val spec = ObbligatoPadMaker.Spec(source, releaseSeconds = .43f)
            val expected = source.copy(releaseSeconds = .43f)
            val notes = fixtures(spec)
            val name = source.name
            val program = ObbligatoPadMaker.export(name, spec, notes, directory)
            assertEquals(expected, ObbligatoPatch.fromJsonText(
                ObbligatoPadMaker.recipeFile(directory, name).readText()))
            assertTrue(program.explicitNoteOn)
            val phone = InstrumentStore.read(InstrumentStore.sidecar(directory, name))
            assertEquals(.43f, phone.release)
            assertEquals((48..84 step 3).toList(), phone.zones.map { it.rootNote })
            assertEquals(48, phone.zones.first().lowNote)
            assertEquals(84, phone.zones.last().highNote)
            val native = Mpc3Project.read(File(directory, "$name.xty")).tracks.single()
                .getValue("program").obj().getValue("drum").obj().getValue("instruments").arr()
            val data = File(directory, Mpc3TrackWriter.trackDataDirName(name))
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val xml = factory.newDocumentBuilder().parse(File(data, "$name.xpm"))
            val instruments = xml.getElementsByTagName("Instrument")
            assertEquals(13, instruments.length)
            for ((index, zone) in phone.zones.withIndex()) {
                val x = instruments.item(index) as Element
                fun text(tag: String) = x.getElementsByTagName(tag).item(0).textContent
                assertEquals(zone.lowNote, text("LowNote").toInt())
                assertEquals(zone.highNote, text("HighNote").toInt())
                assertEquals("2", text("TriggerMode"))
                assertEquals(expected.releaseSeconds, text("VolumeRelease").toFloat())
                val layer = x.getElementsByTagName("Layer").item(0) as Element
                fun layerText(tag: String) = layer.getElementsByTagName(tag).item(0).textContent
                assertEquals(zone.rootNote, layerText("RootNote").toInt())
                assertEquals(zone.loopStartFrame, layerText("SliceLoopStart").toLong())
                assertEquals(zone.frameCount, layerText("SliceEnd").toLong())
                assertEquals("1", layerText("SliceLoop"))
                val n = native[index].obj()
                assertEquals(zone.lowNote, n.getValue("lowNote").int())
                assertEquals(zone.highNote, n.getValue("highNote").int())
                assertEquals(2, n.getValue("triggerMode").int())
                val envelope = n.getValue("synthSection").obj().getValue("ampEnvelope").obj()
                assertFalse(envelope.getValue("OneShot").obj().getValue("value0").bool())
                assertEquals(expected.releaseSeconds.toDouble(),
                    envelope.getValue("Release").obj().getValue("value0").num())
                val slice = n.getValue("layersv").arr().first().obj().getValue("sliceInfo").obj()
                assertEquals(zone.loopStartFrame, slice.getValue("LoopStart").long())
                assertEquals(zone.frameCount, slice.getValue("End").long())
                assertEquals(1, slice.getValue("LoopMode").int())
                val wav = File(directory, zone.sample)
                assertEquals(SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount)),
                    WavReader.readSmplStrict(wav))
                assertEquals(notes[index].snip.frameCount.toLong(), zone.frameCount)
                assertEquals(notes[index].loopStartFrame, zone.loopStartFrame)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `preview holds through a wrap and release changes only the actual note off tail`() {
        val short = patch()
        val spec = ObbligatoPadMaker.Spec(short)
        val native = ObbligatoPadMaker.renderZone(spec, short.midi)
        val loopFrames = native.snip.frameCount - native.loopStartFrame.toInt()
        val heldFrames = native.snip.frameCount + loopFrames
        val preview = ObbligatoPadMaker.preview(spec)
        assertEquals(2, preview.channels, "preview must run the stereo sample player")
        assertEquals(44_100, preview.sampleRate)
        assertEquals(heldFrames + (44_100 * (short.releaseSeconds + .05f)).roundToInt(), preview.frameCount)
        assertTrue(preview.samples.all { it.isFinite() })
        val afterWrap = native.snip.frameCount + loopFrames / 2
        assertTrue(rms(preview, afterWrap, minOf(heldFrames, afterWrap + 2205)) > 1e-5,
            "a held key stops when the source file ends")
        assertTrue(rms(preview, preview.frameCount - 882, preview.frameCount) < 1e-6,
            "the real sample player is not silent after note-off and release")
        val longer = ObbligatoPadMaker.preview(ObbligatoPadMaker.Spec(short.copy(releaseSeconds = .95f)))
        assertEquals(heldFrames + (44_100 * (0.95f + .05f)).roundToInt(), longer.frameCount)
        assertContentEquals(preview.samples.copyOf(heldFrames * 2), longer.samples.copyOf(heldFrames * 2),
            "sampler release changes the held source before note-off")
        assertTrue(rms(longer, longer.frameCount - 882, longer.frameCount) < 1e-6)
    }

    @Test
    fun `cancelled or unsupported work and orphan recipes are refused before package writes`() {
        val source = patch()
        val spec = ObbligatoPadMaker.Spec(source)
        assertFailsWith<IllegalArgumentException> {
            ObbligatoPadMaker.Spec(source.copy(playback = ObbligatoPlayback.FINITE))
        }
        assertFailsWith<IllegalArgumentException> { ObbligatoPadMaker.renderZone(spec, 47) }
        assertFailsWith<CancellationException> { ObbligatoPadMaker.renderZone(spec, 60) { true } }
        assertFailsWith<CancellationException> { ObbligatoPadMaker.preview(spec) { true } }
        val directory = createTempDirectory("obbligato-held-collision").toFile()
        try {
            val recipe = ObbligatoPadMaker.recipeFile(directory, source.name)
            recipe.writeText("keep this saved recipe")
            assertFailsWith<DestinationExists> {
                ObbligatoPadMaker.export(source.name, spec, fixtures(spec), directory)
            }
            assertEquals("keep this saved recipe", recipe.readText())
            assertEquals(listOf(recipe.name), directory.listFiles().orEmpty().map { it.name })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun rms(snip: Snip, fromFrame: Int, untilFrame: Int): Double {
        require(untilFrame > fromFrame)
        var power = 0.0
        for (index in fromFrame * snip.channels until untilFrame * snip.channels)
            power += snip.samples[index].toDouble() * snip.samples[index]
        return sqrt(power / ((untilFrame - fromFrame) * snip.channels))
    }
}
