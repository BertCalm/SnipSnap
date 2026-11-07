package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonException
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Libration
import com.snipsnap.synth.LibrationPatch
import com.snipsnap.synth.LibrationTouchKeys
import com.snipsnap.synth.LibrationVoice
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class LibrationTouchPadMakerTest {
    private val patch = LibrationPatch("TOUCH KEYS", LibrationVoice.GATHER,
        Libration.defaults(LibrationVoice.GATHER) + mapOf("SWARM" to .05f, "RESONANCE" to .3f),
        midi = 36, velocity = .8f)

    @Test fun `version two opts into touch captures and old version one keeps its original JSON shape`() {
        val old = LibrationPadMaker.Spec(patch, .4f)
        val archived = requireNotNull(javaClass.getResourceAsStream("/libration/v1-gather-keys.json"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals(archived, LibrationPadMaker.Spec.fromJsonText(archived).toJsonText(),
            "published version-one recipe keeps its exact JSON")
        val layered = old.copy(touchLayers = true)
        assertEquals(old, LibrationPadMaker.Spec.fromJsonText(old.toJsonText()))
        assertFalse("touch" in old.toJsonText())
        assertEquals(layered, LibrationPadMaker.Spec.fromJsonText(layered.toJsonText()))
        assertEquals(2, layered.layerCount)
        val fields = Json.parse(layered.toJsonText()).obj()
        assertEquals(1, fields.getValue("touchModel").int())
        assertFailsWith<JsonException> {
            LibrationPadMaker.Spec.fromJsonText(Json.write(JsonValue.Obj(fields + ("touchModel" to JsonValue.Num(2.0)))))
        }
        val error = assertFailsWith<JsonException> {
            LibrationPadMaker.Spec.fromJsonText(Json.write(JsonValue.Obj(fields + ("version" to JsonValue.Num(1.0)))))
        }
        assertTrue(error.message.orEmpty().contains("version 2"))
        val note = KeyNote(Snip(FloatArray(1024), 1, 44100), 512)
        assertFailsWith<IllegalArgumentException> {
            LibrationPadMaker.assemble("TOUCH KEYS", layered, LibrationPadMaker.zoneMidis(layered).map { note })
        }
        assertFailsWith<IllegalArgumentException> { LibrationPadMaker.renderZone(layered, patch.midi) }
        assertFailsWith<IllegalArgumentException> {
            LibrationPadMaker.assembleLayers("TOUCH KEYS", layered, LibrationPadMaker.zoneMidis(layered).map { listOf(note) })
        }
    }

    @Test fun `actual two touch samples cross both MPC writers the phone sidecar and live layer boundary`() {
        val spec = LibrationPadMaker.Spec(patch, .4f, touchLayers = true)
        val notes = LibrationPadMaker.zoneMidis(spec).map { LibrationPadMaker.renderLayers(spec, it) }
        val directory = kotlin.io.path.createTempDirectory("libration-touch-").toFile()
        try {
            val program = LibrationPadMaker.exportLayers("TOUCH KEYS", spec, notes, directory)
            val sidecar = InstrumentStore.sidecar(directory, "TOUCH KEYS")
            val instrument = InstrumentStore.read(sidecar)
            assertTrue(instrument.explicitNoteOn)
            assertEquals(program, instrument.toProgram())
            assertEquals(notes.size * 2, instrument.sampleFiles.size)
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(directory, it)).samples }
            val data = File(directory, Mpc3TrackWriter.trackDataDirName("TOUCH KEYS"))
            val xpm = File(data, "TOUCH KEYS.xpm").readText()
            val xty = Acvs.read(File(directory, "TOUCH KEYS.xty")).payloadText
            val nativeZones = Json.parse(xty).obj().getValue("data").obj().getValue("program").obj()
                .getValue("drum").obj().getValue("instruments").arr()
            for ((index, zone) in instrument.zones.withIndex()) {
                assertEquals(LibrationTouchKeys.VELOCITY_WINDOWS, zone.playableLayers.map { it.velStart..it.velEnd })
                for ((layerIndex, layer) in zone.playableLayers.withIndex()) {
                    val file = File(directory, layer.sample)
                    val sampler = assertNotNull(WavReader.readSmpl(file))
                    assertEquals(zone.rootNote, sampler.rootNote)
                    assertEquals(layer.loopStartFrame, assertNotNull(sampler.loop).startFrame)
                    assertEquals(layer.frameCount, sampler.loop!!.endFrameExclusive)
                    assertTrue(samples.getValue(layer.sample).indices.all {
                        abs(samples.getValue(layer.sample)[it] - notes[index][layerIndex].snip.samples[it]) < 2e-7f
                    })
                    assertTrue("<VelStart>${layer.velStart}</VelStart>" in xpm)
                    assertTrue("<VelEnd>${layer.velEnd}</VelEnd>" in xpm)
                    // MPC 3 stores the hard layer first. Compare the named samples,
                    // rather than borrowing the phone/XPM's soft-first slot order.
                    val nativeLayer = nativeZones[index].obj().getValue("layersv").arr().map { it.obj() }
                        .single { it.getValue("sampleName").str() == File(layer.sample).nameWithoutExtension }
                    assertEquals(layer.velStart, nativeLayer.getValue("velocityStart").int())
                    assertEquals(layer.velEnd, nativeLayer.getValue("velocityEnd").int())
                }
            }
            val low = assertNotNull(KeyHit.resolve(instrument, 36, 63 / 127f) { samples[it]?.size?.toLong() })
            val high = assertNotNull(KeyHit.resolve(instrument, 36, 64 / 127f) { samples[it]?.size?.toLong() })
            assertEquals(instrument.zones.first().playableLayers[0].sample, low.sampleFile)
            assertEquals(instrument.zones.first().playableLayers[1].sample, high.sampleFile)
            assertNotEquals(low.sampleFile, high.sampleFile)
            for (velocity in listOf(1, 63, 64, 127)) {
                val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44100, samples), 48000)
                assertTrue(player.noteOn(37, velocity / 127f))
                val frames = ((notes.first().first().snip.durationSeconds + 5) * 48000).toInt()
                val block = FloatArray(1024)
                var peak = 0f
                var remaining = frames
                while (remaining > 0) {
                    val count = minOf(512, remaining)
                    player.render(block, count)
                    if (remaining < 24000) peak = maxOf(peak, block.take(count * 2).maxOf { abs(it) })
                    remaining -= count
                }
                assertTrue(peak > 1e-5, "velocity $velocity stopped at the file end")
                player.noteOff(37)
                repeat(60) { player.render(block, 512) }
                assertTrue(player.activeNotes.isEmpty())
                assertTrue(block.all { it == 0f })
            }
            val restored = LibrationPadMaker.Spec.fromJsonText(LibrationPadMaker.recipeFile(directory, "TOUCH KEYS").readText())
            assertEquals(spec, restored)
            val replay = LibrationPadMaker.renderLayers(restored, 36)
            replay.forEachIndexed { index, note -> assertContentEquals(notes.first()[index].snip.samples, note.snip.samples) }
        } finally { directory.deleteRecursively() }
    }
}
