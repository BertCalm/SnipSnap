package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.FilamentConfig
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentPresets
import com.snipsnap.synth.FilamentProfile
import com.snipsnap.synth.FilamentTouchKeys
import com.snipsnap.synth.FilamentVoice
import com.snipsnap.synth.KeyNote
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual voice/root captures cross the package boundary once, then exercise native and reference players. */
class FilamentTouchInstrumentTest {
    private class Example(val patch: FilamentPatch) {
        val spec = FilamentPadMaker.Spec(patch, .37f, touchLayers = true)
        val notes by lazy { FilamentPadMaker.zoneMidis(spec).map { FilamentPadMaker.renderLayers(spec, it) } }
    }

    private companion object {
        val examples by lazy { FilamentVoice.entries.map { voice -> Example(
            FilamentPresets.forVoice(voice).first().copy(name = "TOUCH ${voice.name}", midi = 57, velocity = .61f,
                config = FilamentConfig(seed = Int.MIN_VALUE + voice.ordinal + 9, profile = FilamentProfile.LATE)),
        ) } }
    }

    @Test
    fun `twenty seven native samples retain velocity windows own loops PCM and source recipes in both MPC generations`() {
        withDirectory { root ->
            for (example in examples) {
                val patch = example.patch
                val folder = File(root, patch.voice.name)
                val program = FilamentPadMaker.exportLayers(patch.name, example.spec, example.notes, folder)
                val data = File(folder, "${patch.name}_[TrackData]")
                val (sidecar, instrument) = InstrumentStore.list(folder).single()
                assertEquals(9, instrument.sampleFiles.size)
                assertEquals(listOf(48, 60, 72), instrument.zones.map { it.rootNote })
                assertEquals(.37f, instrument.release)
                assertEquals(patch, FilamentPatch.fromJsonText(File(data, FilamentPadMaker.PATCH_FILE).readText()))
                val recipe = Json.parse(File(data, FilamentPadMaker.TOUCH_FILE).readText()).obj()
                assertEquals(1, recipe.getValue("version").int())
                assertEquals(FilamentPadMaker.PATCH_FILE, recipe.getValue("sourcePatch").str())
                assertEquals("FilamentKeys.layer", recipe.getValue("renderer").str())
                assertEquals(FilamentTouchKeys.RENDER_VERSION, recipe.getValue("touchVersion").int())
                assertTrue(recipe.getValue("touchLayers").bool())
                assertEquals(FilamentTouchKeys.TOUCHES.map { (patch.velocity * it).toDouble() },
                    recipe.getValue("excitationVelocities").arr().map { it.num() })
                for ((rootIndex, row) in recipe.getValue("sourcesByRoot").arr().withIndex()) {
                    val source = row.obj()
                    val midi = listOf(48, 60, 72)[rootIndex]
                    assertEquals(midi, source.getValue("midi").int())
                    assertEquals(FilamentTouchKeys.TOUCHES.map { FilamentTouchKeys.sourcePatch(patch, midi, it) },
                        source.getValue("layers").arr().map { FilamentPatch.fromJsonValue(it) })
                }
                val native = Json.parse(Acvs.read(File(folder, "${patch.name}.xty")).payloadText).obj()
                    .getValue("data").obj().getValue("program").obj().getValue("drum").obj()
                    .getValue("instruments").arr()
                val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(data, "${patch.name}.xpm"))
                val compatibility = xml.getElementsByTagName("Instrument")
                assertEquals(3, compatibility.length)
                for ((rootIndex, zone) in instrument.zones.withIndex()) {
                    assertEquals(3, zone.playableLayers.size)
                    val nativeLayers = native[rootIndex].obj().getValue("layersv").arr().map { it.obj() }
                    val xmlLayers = (compatibility.item(rootIndex) as Element).getElementsByTagName("Layer")
                    for ((index, layer) in zone.playableLayers.withIndex()) {
                        val note = example.notes[rootIndex][index]
                        val window = FilamentTouchKeys.VELOCITY_WINDOWS[index]
                        assertEquals(window, layer.velStart..layer.velEnd)
                        assertEquals(note.loopStartFrame, layer.loopStartFrame)
                        assertEquals(note.snip.frameCount.toLong(), layer.frameCount)
                        val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(layer.loopStartFrame, layer.frameCount))
                        val wav = File(sidecar.parentFile, layer.sample)
                        assertEquals(sheet, WavReader.readSmpl(wav))
                        val expected = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, smpl = sheet) }
                        assertContentEquals(expected.toByteArray(), wav.readBytes(), "${patch.voice} root${zone.rootNote} layer$index PCM24")
                        val stem = program.keygroups[rootIndex].layers[index].sampleName
                        val n = nativeLayers.single { it.getValue("sampleName").str() == stem }
                        assertEquals(window.first, n.getValue("velocityStart").int())
                        assertEquals(window.last, n.getValue("velocityEnd").int())
                        assertEquals(layer.loopStartFrame, n.getValue("sliceInfo").obj().getValue("LoopStart").long())
                        assertEquals(layer.frameCount, n.getValue("sliceInfo").obj().getValue("End").long())
                        val x = (0 until xmlLayers.length).map { xmlLayers.item(it) as Element }
                            .single { text(it, "SampleName") == stem }
                        assertEquals(window.first, text(x, "VelStart").toInt())
                        assertEquals(window.last, text(x, "VelEnd").toInt())
                        assertEquals(layer.loopStartFrame, text(x, "SliceLoopStart").toLong())
                        assertEquals(layer.frameCount, text(x, "SliceEnd").toLong())
                    }
                }
            }
        }
    }

    @Test
    fun `exported files select every velocity boundary repeat the selected sustain and release with gain once`() {
        withDirectory { root ->
            for (example in examples) {
                val patch = example.patch
                val folder = File(root, patch.voice.name)
                FilamentPadMaker.exportLayers(patch.name, example.spec, example.notes, folder)
                val (sidecar, instrument) = InstrumentStore.list(folder).single()
                val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
                for ((velocity, index) in listOf(1 to 0, 42 to 0, 43 to 1, 84 to 1, 85 to 2, 127 to 2)) {
                    val touch = velocity / 127f
                    val layer = instrument.zones[1].playableLayers[index]
                    val hit = requireNotNull(KeyHit.resolve(instrument, 60, touch) { samples[it]?.size?.toLong() })
                    assertEquals(layer.sample, hit.sampleFile)
                    assertEquals(layer.loopStartFrame, hit.loopStartFrame)
                    assertEquals(touch * InstrumentEngine.VOICE_LEVEL, hit.gain)
                    val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, samples), 44_100)
                    assertTrue(player.noteOn(60, touch))
                    val source = samples.getValue(layer.sample)
                    val start = layer.loopStartFrame.toInt()
                    val period = source.size - start
                    val frames = source.size + 2 * period + 137
                    val heard = render(player, frames)
                    var error = 0f
                    var postWrapPeak = 0f
                    for (frame in 0 until frames) {
                        val sourceFrame = if (frame < source.size) frame else start + (frame - source.size) % period
                        val expected = source[sourceFrame] * hit.gain
                        error = maxOf(error, abs(heard[frame * 2] - expected), abs(heard[frame * 2 + 1] - expected))
                        if (frame >= source.size) postWrapPeak = maxOf(postWrapPeak, abs(heard[frame * 2]))
                    }
                    assertTrue(error < 2e-6, "${patch.voice} velocity$velocity wrong source, loop or gain: $error")
                    assertTrue(postWrapPeak > 1e-6, "${patch.voice} velocity$velocity stopped at the file's end")
                    player.noteOff(60)
                    val tail = render(player, ((instrument.release + .1f) * 44_100).toInt())
                    assertTrue(tail.takeLast(882).all { it == 0f })
                    assertTrue(player.activeNotes.isEmpty())
                }
            }
        }
    }

    @Test
    fun `legacy assembly cannot discard layers and malformed banks never leave partial files`() {
        withDirectory { root ->
            val spec = examples.first().spec
            val valid = fixtures()
            val failure = assertFailsWith<IllegalArgumentException> {
                FilamentPadMaker.export("BAD", spec, valid.map { it.last() }, root)
            }
            assertTrue(failure.message.orEmpty().contains("assembleLayers"))
            val missing = valid.toMutableList().also { it[1] = it[1].take(2) }
            val bad = assertFailsWith<IllegalArgumentException> { FilamentPadMaker.exportLayers("BAD", spec, missing, root) }
            assertTrue(bad.message.orEmpty().contains("3 velocity layers"))
            val invalid = valid.map { it.toMutableList() }.also { it[1][0] = KeyNote(Snip(FloatArray(64) { Float.NaN }, 1, 44_100), 32) }
            assertFailsWith<IllegalArgumentException> { FilamentPadMaker.exportLayers("BAD", spec, invalid, root) }
            assertFailsWith<CancellationException> { FilamentPadMaker.renderLayers(spec, 60) { true } }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun `an explicit single layer replacement drops obsolete touch metadata and keeps the source schema`() {
        withDirectory { root ->
            val spec = examples.first().spec
            FilamentPadMaker.exportLayers("REPLACE", spec, fixtures(), root)
            val data = File(root, "REPLACE_[TrackData]")
            assertTrue(File(data, FilamentPadMaker.TOUCH_FILE).isFile)
            FilamentPadMaker.export("REPLACE", spec.copy(touchLayers = false), fixtures().map { it.last() }, root, overwrite = true)
            assertFalse(File(data, FilamentPadMaker.TOUCH_FILE).exists())
            assertEquals(spec.patch, FilamentPatch.fromJsonText(File(data, FilamentPadMaker.PATCH_FILE).readText()))
            assertEquals(3, InstrumentStore.list(root).single().second.sampleFiles.size)
        }
    }

    @Test
    fun `touch preview plays three real source strengths and ends each release in silence`() {
        val example = examples.last()
        val before = example.patch.toJsonText()
        val heard = FilamentPadMaker.preview(example.spec)
        assertEquals(2, heard.channels)
        assertEquals(44_100, heard.sampleRate)
        val segment = heard.frameCount / 3
        for (i in 0..2) {
            val body = heard.samples.copyOfRange((i * segment + 4410) * 2, (i * segment + 44100) * 2)
            assertTrue(body.any { abs(it) > 1e-6f }, "touch $i preview is silent")
            val tailEnd = (i + 1) * segment * 2
            assertTrue(heard.samples.sliceArray(tailEnd - 882 until tailEnd).all { it == 0f })
        }
        assertTrue(heard.samples.all { it.isFinite() && abs(it) <= 1f })
        assertEquals(before, example.patch.toJsonText())
    }

    private fun fixtures(): List<List<KeyNote>> = List(3) { root -> List(3) { layer ->
        KeyNote(Snip(FloatArray(128 + root + layer * 16) { .03f * (layer + 1) }, 1, 44_100), 32L + layer * 3)
    } }

    private fun render(player: InstrumentEngine, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(512)
        var done = 0
        while (done < frames) {
            val count = minOf(256, frames - done)
            player.render(block, count)
            block.copyInto(out, done * 2, 0, count * 2)
            done += count
        }
        return out
    }

    private fun text(e: Element, tag: String): String = e.getElementsByTagName(tag).item(0).textContent

    private fun withDirectory(block: (File) -> Unit) {
        val root = createTempDirectory("filament-touch-package").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
