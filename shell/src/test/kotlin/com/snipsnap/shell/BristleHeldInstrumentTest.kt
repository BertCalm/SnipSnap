package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

class BristleHeldInstrumentTest {
    @Test fun `both held routes survive export with real loops and release after repeated wraps`() {
        val root = createTempDirectory("bristle-held").toFile()
        try {
            for (patch in BristlePresets.all()) {
                val name = "Held ${patch.voice}"
                val spec = BristleKeygroupMaker.Spec(patch, 36, 36, .2f, held = true)
                val notes = BristleKeygroupMaker.render(spec)
                val built = BristleKeygroupMaker.exportFresh(name, spec, notes, root)
                assertEquals(built.program, BristleKeygroupMaker.validate(name, root, spec).program)
                assertFails { BristleKeygroupMaker.validate(name, root, spec.copy(held = false)) }
                val instrument = InstrumentStore.read(InstrumentStore.sidecar(root, name))
                assertEquals(.2f, instrument.release)
                val samples = instrument.sampleFiles.associateWith { WavReader.read(File(root, it)).samples }
                val layers = Mpc3Project.read(File(root, "$name.xty")).drumPrograms().single()
                    .getValue("instruments").arr().first().obj().getValue("layersv").arr().map { it.obj() }
                    .filter { it.getValue("sampleName").str().isNotEmpty() }
                assertEquals(listOf(105,64,0), layers.map { it.getValue("velocityStart").int() })
                for (layer in layers) assertEquals(1, layer.getValue("sliceInfo").obj().getValue("LoopMode").int())
                val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
                assertTrue("<SliceLoop>1</SliceLoop>" in File(data, "$name.xpm").readText())
                for ((touch, velocity) in BristleKeygroupMaker.TOUCHES.withIndex()) {
                    val hit = assertNotNull(KeyHit.resolve(instrument, 36, velocity) { samples[it]?.size?.toLong() })
                    assertEquals(instrument.zones.single().layers[touch].sample, hit.sampleFile)
                    assertEquals(BristleHeld.LOOP_START_FRAME.toLong(), hit.loopStartFrame)
                    val pcm = samples.getValue(hit.sampleFile)
                    assertTrue(Keys.seamError(pcm, hit.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                    val expected = BristleHeld.render(patch.copy(velocity = velocity)).note.snip.samples
                    assertTrue(pcm.indices.maxOf { abs(pcm[it] * velocity - expected[it]) } < .000001f)
                    val sampler = assertNotNull(WavReader.readSmplStrict(File(root, hit.sampleFile)))
                    assertEquals(hit.loopStartFrame, sampler.loop!!.startFrame)
                    assertEquals(pcm.size.toLong(), sampler.loop!!.endFrameExclusive)
                    val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44100, samples), 44100)
                    assertTrue(player.noteOn(36, velocity))
                    val held = FloatArray(44100 * 4 * 2)
                    player.render(held, 44100 * 4)
                    assertEquals(listOf(36), player.activeNotes)
                    assertTrue(held.takeLast(4410 * 2).any { abs(it) > .001f }, "still audible after repeated wraps")
                    player.noteOff(36)
                    val released = FloatArray(13230 * 2)
                    player.render(released, 13230)
                    assertTrue(released.take(1000).any { it != 0f })
                    assertTrue(released.takeLast(1000).all { it == 0f })
                    assertTrue(player.activeNotes.isEmpty())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `held metadata cannot be downgraded or given a different loop or renderer`() {
        val root = createTempDirectory("bristle-held-tamper").toFile()
        try {
            val spec = BristleKeygroupMaker.Spec(BristlePresets.all().first(), 36, 36, held = true)
            val notes = BristleKeygroupMaker.render(spec)
            val edits: List<(MutableMap<String, JsonValue>) -> Unit> = listOf(
                { it["heldModel"] = JsonValue.Num(2.0) },
                { it["playback"] = JsonValue.Str("FINITE_TOUCH_PREPARED") },
                { o ->
                    val rows = o.getValue("samples").arr().toMutableList()
                    rows[0] = JsonValue.Obj(rows[0].obj() + ("loopStartFrame" to JsonValue.Num(44101.0)))
                    o["samples"] = JsonValue.Arr(rows)
                },
            )
            for (edit in edits) {
                assertFails {
                    BristleKeygroupMaker.exportFresh("Held", spec, notes, root, InstrumentPackageAdmission.Hooks(staged = { stage ->
                        val f = File(stage, "Held_[TrackData]/bristle-source-recipes.json")
                        val o = Json.parse(f.readText()).obj().toMutableMap(); edit(o)
                        f.writeText(Json.write(JsonValue.Obj(o)))
                    }))
                }
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `finite remains default and held sheet uses its own release and loop preview`() {
        val patch = BristlePresets.all().first().copy(midi = 30, velocity = .65f)
        assertFalse(BristlePadMaker.Spec(patch).held)
        val spec = BristlePadMaker.spec(patch, true, BristlePadMaker.RELEASE.fraction(.2f))
        assertEquals(.2f, spec.releaseSeconds, .00001f)
        val preview = BristlePadMaker.previewNote(spec)
        assertEquals(44100L, preview.loopStartFrame)
        val keys = BristlePadMaker.renderZone(spec, 30)
        assertTrue(keys.all { it.loopStartFrame == preview.loopStartFrame })
        assertContentEquals(preview.snip.samples, keys.last().snip.samples)
        val audition = BristlePadMaker.preview(spec)
        assertEquals(2, audition.channels)
        assertTrue(audition.samples.takeLast(1000).all { it == 0f })
        assertTrue(audition.samples.slice(44100 * 2 * 2 until 44100 * 3 * 2).any { abs(it) > .001f })
        val slower = BristlePadMaker.preview(spec.copy(releaseSeconds = .5f))
        assertTrue(slower.frameCount > audition.frameCount)
        assertTrue(slower.samples.takeLast(1000).all { it == 0f })
    }
}
