package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

class BristleKeygroupMakerTest {
    @Test fun `every note and touch survives package save load selection and reference playback`() {
        val root = createTempDirectory("bristle-packages").toFile()
        try {
            for (route in Bristle.Route.entries) {
                val patch = BristlePresets.forVoice(route).single()
                val spec = BristleKeygroupMaker.Spec(patch)
                val name = "Bristle $route"
                val notes = BristleKeygroupMaker.render(spec)
                assertEquals(75, notes.size)
                val built = BristleKeygroupMaker.exportFresh(name, spec, notes, root)
                assertEquals(built.program, BristleKeygroupMaker.validate(name, root, spec).program)
                val sidecar = InstrumentStore.sidecar(root, name)
                val instrument = InstrumentStore.read(sidecar)
                val samples = instrument.sampleFiles.associateWith { WavReader.read(File(root, it)).samples }
                val native = Mpc3Project.read(File(root, "$name.xty")).drumPrograms().single().getValue("instruments").arr()
                assertEquals(25, instrument.zones.size)
                assertFalse(instrument.voiceMode.mono)
                for ((index, zone) in instrument.zones.withIndex()) {
                    assertEquals(zone.rootNote, zone.lowNote)
                    assertEquals(zone.rootNote, zone.highNote)
                    val layers = native[index].obj().getValue("layersv").arr().map { it.obj() }
                        .filter { it.getValue("sampleName").str().isNotEmpty() }
                    assertEquals(listOf(105, 64, 0), layers.map { it.getValue("velocityStart").int() })
                    for ((v, touch) in listOf(1 to 0, 63 to 0, 64 to 1, 104 to 1, 105 to 2, 127 to 2)) {
                        val hit = assertNotNull(KeyHit.resolve(instrument, zone.rootNote, v / 127f) { samples[it]?.size?.toLong() })
                        assertEquals(zone.layers[touch].sample, hit.sampleFile)
                        assertEquals(1.0, hit.pitchRatio)
                        assertEquals(-1L, hit.loopStartFrame)
                    }
                    for ((touch, velocity) in BristleKeygroupMaker.TOUCHES.withIndex()) {
                        val hit = assertNotNull(KeyHit.resolve(instrument, zone.rootNote, velocity) { samples[it]?.size?.toLong() })
                        assertEquals(zone.layers[touch].sample, hit.sampleFile)
                        val pcm = samples.getValue(hit.sampleFile)
                        val accepted = notes.getValue(BristleKeygroupMaker.Cell(zone.rootNote, touch)).recipe.render().samples
                        assertTrue(pcm.indices.maxOf { abs(pcm[it] * velocity - accepted[it]) } < .000001f,
                            "prepared sample applies touch only once: $route ${zone.rootNote} $touch")
                        val sheet = assertNotNull(WavReader.readSmplStrict(File(root, hit.sampleFile)))
                        assertEquals(zone.rootNote, sheet.rootNote)
                        assertNull(sheet.loop)
                    }
                }
                val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44100, samples), 44100)
                for (midi in listOf(36, 39, 43)) assertTrue(player.noteOn(midi, .65f))
                assertEquals(setOf(36, 39, 43), player.activeNotes.toSet())
                val audio = FloatArray(4096 * 2)
                player.render(audio, 4096)
                assertTrue(audio.any { abs(it) > .01f } && audio.all { it.isFinite() })
                assertFalse(player.noteOn(23)); assertFalse(player.noteOn(49))
                // Both real sampler descriptors are source-bound, not just the phone metadata.
                val data = File(root, Mpc3TrackWriter.trackDataDirName(name))
                assertFalse("<SliceLoop>1</SliceLoop>" in File(data, "$name.xpm").readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `edited root native velocity and model survive planning without double tuning`() {
        val patch = BristlePresets.all().last().copy(midi = 30, velocity = .7f,
            macros = Bristle.defaults + ("TUNE" to .75f))
        val spec = BristleKeygroupMaker.Spec(patch)
        val recipes = BristleKeygroupMaker.recipes(spec)
        assertEquals((24..48).toList(), BristleKeygroupMaker.roots(spec))
        assertEquals(patch, recipes.getValue(BristleKeygroupMaker.Cell(36, 2)))
        for ((cell, recipe) in recipes) {
            assertEquals(cell.midi, Bristle.rootMidi(recipe))
            assertEquals(.7f * BristleKeygroupMaker.TOUCHES[cell.touch], recipe.velocity)
            assertEquals(patch.model, recipe.model)
        }
        assertFalse(BristlePadMaker.supports(patch.copy(velocity = 0f)))
        assertFails { BristleKeygroupMaker.Spec(patch, lowMidi = 40) }
    }

    @Test fun `screen adapter produces the same prepared samples and preserves its preview`() {
        val patch = BristlePresets.all().first().copy(velocity = .65f)
        val spec = BristlePadMaker.Spec(patch)
        assertContentEquals(patch.render().samples, BristlePadMaker.preview(spec).samples)
        val zone = BristlePadMaker.renderZone(spec, 36)
        val expected = BristleKeygroupMaker.render(BristleKeygroupMaker.Spec(patch, 36, 36))
        for (touch in 0..2) assertContentEquals(expected.getValue(BristleKeygroupMaker.Cell(36, touch)).snip.samples, zone[touch].snip.samples)
        assertFailsWith<java.util.concurrent.CancellationException> { BristlePadMaker.renderZone(spec, 36) { true } }
        assertFails { BristlePadMaker.cells(spec, listOf(zone)) }
    }

    @Test fun `changed samples or native mapping are refused before shelf publication`() {
        val root = createTempDirectory("bristle-reject").toFile()
        try {
            val spec = BristleKeygroupMaker.Spec(BristlePresets.all().first(), 36, 36)
            val notes = BristleKeygroupMaker.render(spec)
            for (file in listOf("Bass.xty", "Bass_[TrackData]/Bristle_36_T1.wav")) {
                assertFails {
                    BristleKeygroupMaker.exportFresh("Bass", spec, notes, root,
                        InstrumentPackageAdmission.Hooks(staged = { File(it, file).appendBytes(byteArrayOf(0)) }))
                }
                assertTrue(InstrumentStore.list(root).isEmpty())
                assertTrue(root.listFiles()!!.isEmpty())
            }
            BristleKeygroupMaker.exportFresh("Bass", spec, notes, root)
            val saved = File(root, "Bass.xty").readBytes()
            assertFails { BristleKeygroupMaker.exportFresh("Bass", spec, notes, root) }
            assertContentEquals(saved, File(root, "Bass.xty").readBytes())
        } finally { root.deleteRecursively() }
    }
}
