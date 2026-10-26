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
import com.snipsnap.synth.FlockTouchKeys
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.KeyNote
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Velocity windows, source provenance and loops survive the actual phone/MPC package. */
class FlockTouchInstrumentTest {
    private val patch = FlockPatch("RIBBON TOUCH", FlockVoice.RIBBON, Flock.defaults(FlockVoice.RIBBON),
        midi = 60, velocity = .7f, seed = Long.MAX_VALUE, model = 1,
        playback = FlockPlayback.HELD, releaseSeconds = .3f)
    private val spec = FlockPadMaker.Spec(patch, touchLayers = true)

    private fun fixtures(): List<List<KeyNote>> = List(9) { root ->
        List(3) { layer ->
            KeyNote(Snip(FloatArray(128 + root + layer * 16) { .03f * (layer + 1) }, 1, WavWriter.MPC_SAMPLE_RATE),
                32L + layer * 3)
        }
    }

    @Test
    fun `all twenty seven samples preserve velocity windows per layer endpoints and exact source provenance`() {
        val dir = createTempDirectory("flock-touch-package").toFile()
        try {
            val notes = fixtures()
            val program = FlockPadMaker.exportLayers(patch.name, spec, notes, dir)
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(27, instrument.sampleFiles.size)
            assertEquals(9, instrument.zones.size)
            assertEquals(patch.releaseSeconds, instrument.release)
            assertEquals(patch, FlockPatch.fromJsonText(File(dir, "${patch.name}.patch.json").readText()))
            val recipe = Json.parse(FlockPadMaker.recipeFile(dir, patch.name).readText()).obj()
            assertEquals(1, recipe.getValue("version").int())
            assertEquals("${patch.name}.patch.json", recipe.getValue("sourcePatch").str())
            assertTrue(recipe.getValue("touchLayers").bool())
            assertEquals(FlockTouchKeys.RENDER_VERSION, recipe.getValue("touchVersion").int())
            assertEquals(FlockTouchKeys.TOUCHES.map { it.toDouble() }, recipe.getValue("strengths").arr().map { it.num() })
            assertEquals(FlockTouchKeys.VELOCITY_WINDOWS, recipe.getValue("velocityWindows").arr().map {
                val window = it.obj()
                window.getValue("start").int()..window.getValue("end").int()
            })
            assertEquals(listOf("--patch", "${patch.name}.patch.json", "--instrument", "--touch", "--release", ".3".toFloat().toString()),
                recipe.getValue("replayFlags").arr().map { it.str() })
            val native = Json.parse(Acvs.read(File(dir, "${patch.name}.xty")).payloadText).obj()
                .getValue("data").obj().getValue("program").obj().getValue("drum").obj().getValue("instruments").arr()
            val dataDir = File(dir, "${patch.name}_[TrackData]")
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dataDir, "${patch.name}.xpm"))
            val compatibility = xml.getElementsByTagName("Instrument")
            assertEquals(9, compatibility.length)
            for ((i, zone) in instrument.zones.withIndex()) {
                assertEquals(program.keygroups[i].rootNote, zone.rootNote)
                assertEquals(3, zone.playableLayers.size)
                val nativeLayers = native[i].obj().getValue("layersv").arr().map { it.obj() }
                    .filter { it.getValue("sampleName").str().isNotEmpty() }
                val xmlLayers = (compatibility.item(i) as Element).getElementsByTagName("Layer")
                for ((j, layer) in zone.playableLayers.withIndex()) {
                    val note = notes[i][j]
                    val window = FlockTouchKeys.VELOCITY_WINDOWS[j]
                    assertEquals(window, layer.velStart..layer.velEnd)
                    assertTrue(layer.sample.endsWith("_v${j + 1}.wav"))
                    assertEquals(note.loopStartFrame, layer.loopStartFrame)
                    assertEquals(note.snip.frameCount.toLong(), layer.frameCount)
                    val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(layer.loopStartFrame, layer.frameCount))
                    val wavFile = File(sidecar.parentFile, layer.sample)
                    assertEquals(sheet, WavReader.readSmpl(wavFile))
                    val expected = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, smpl = sheet) }
                    assertContentEquals(expected.toByteArray(), wavFile.readBytes(), "root $i / layer $j PCM24")
                    val stem = program.keygroups[i].layers[j].sampleName
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
            // Read the package's own PCM at the exact boundaries, through the shared phone resolver and JVM player.
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            for ((midiVelocity, index) in listOf(1 to 0, 42 to 0, 43 to 1, 84 to 1, 85 to 2, 127 to 2)) {
                val velocity = midiVelocity / 127f
                val selected = instrument.zones[4].playableLayers[index]
                val hit = requireNotNull(KeyHit.resolve(instrument, patch.midi, velocity) { samples[it]?.size?.toLong() })
                assertEquals(selected.sample, hit.sampleFile)
                assertEquals(selected.loopStartFrame, hit.loopStartFrame)
                assertEquals(velocity * InstrumentEngine.VOICE_LEVEL, hit.gain)
                val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, WavWriter.MPC_SAMPLE_RATE, samples), WavWriter.MPC_SAMPLE_RATE)
                assertTrue(player.noteOn(patch.midi, velocity))
                val out = FloatArray(1000)
                player.render(out, 500)
                val expected = samples.getValue(selected.sample)[0] * hit.gain
                assertTrue(out.all { abs(it - expected) < 1e-6f }, "velocity $midiVelocity selected wrong PCM")
                player.noteOff(patch.midi)
                val release = FloatArray(((patch.releaseSeconds + .03f) * WavWriter.MPC_SAMPLE_RATE).roundToInt() * 2)
                player.render(release, release.size / 2)
                assertTrue(release.takeLast(100).all { it == 0f })
                assertTrue(player.activeNotes.isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `touch assembly refuses missing layers invalid audio and a legacy call that would discard softer sources`() {
        assertEquals(3, spec.layerCount)
        assertEquals(1, FlockPadMaker.Spec(patch).layerCount)
        assertFailsWith<IllegalArgumentException> { FlockPadMaker.assemble("NO LOSS", spec, fixtures().map { it.last() }) }
        val missing = fixtures().toMutableList().also { it[0] = it[0].dropLast(1) }
        val failure = assertFailsWith<IllegalArgumentException> { FlockPadMaker.assembleLayers("BAD", spec, missing) }
        assertTrue("3 velocity layers" in requireNotNull(failure.message))
        for (badNote in listOf(
            KeyNote(Snip(FloatArray(128), 1, WavWriter.MPC_SAMPLE_RATE), 0),
            KeyNote(Snip(FloatArray(128) { Float.NaN }, 1, WavWriter.MPC_SAMPLE_RATE), 32),
            KeyNote(Snip(FloatArray(128), 1, 48_000), 32),
        )) {
            val bad = fixtures().toMutableList().also { it[0] = it[0].toMutableList().also { row -> row[0] = badNote } }
            assertFailsWith<IllegalArgumentException> { FlockPadMaker.assembleLayers("BAD", spec, bad) }
        }
        assertFailsWith<CancellationException> { FlockPadMaker.renderLayers(spec, patch.midi) { true } }
        assertFailsWith<CancellationException> { FlockPadMaker.preview(spec) { true } }
    }

    @Test
    fun `an orphan delivery recipe refuses before package writes and explicit single layer overwrite removes stale touch provenance`() {
        val dir = createTempDirectory("flock-touch-recipe").toFile()
        try {
            val orphan = FlockPadMaker.recipeFile(dir, patch.name).also { it.writeText("edited delivery") }
            val failure = assertFailsWith<DestinationExists> { FlockPadMaker.exportLayers(patch.name, spec, fixtures(), dir) }
            assertEquals(orphan, failure.path)
            assertEquals("edited delivery", orphan.readText())
            assertFalse(File(dir, "${patch.name}.xty").exists())
            assertFalse(File(dir, "${patch.name}.patch.json").exists())
            FlockPadMaker.exportLayers(patch.name, spec, fixtures(), dir, overwrite = true)
            assertTrue(Json.parse(orphan.readText()).obj().getValue("touchLayers").bool())
            val single = FlockPadMaker.Spec(patch)
            FlockPadMaker.export(patch.name, single, fixtures().map { it.last() }, dir, overwrite = true)
            assertFalse(orphan.exists(), "a single-layer replacement must not retain a recipe claiming three layers")
            assertTrue(InstrumentStore.list(dir).single().second.zones.all { it.playableLayers.size == 1 })
            assertEquals(patch, FlockPatch.fromJsonText(File(dir, "${patch.name}.patch.json").readText()))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun text(parent: Element, tag: String): String = parent.getElementsByTagName(tag).item(0).textContent
}
