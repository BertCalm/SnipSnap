package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.PadRecipe
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual Meridian layers, sampler packages and JVM reference playback; no phone/hardware claim. */
class MeridianPadMakerTest {
    private val destination = kotlin.io.path.createTempDirectory("meridian-keys").toFile()

    @AfterTest fun cleanUp() { destination.deleteRecursively() }

    private companion object {
        val patch = MeridianPresets.all().first { it.name == "ROUND THREAD" }.let {
            it.copy(midi = 81, velocity = .45f, seedContext = 41,
                macros = it.macros + mapOf("TUNE" to .75f, "MEMORY" to .25f))
        }
        val spec = MeridianPadMaker.Spec(patch, releaseSeconds = .25f)
        // One real 39-layer render is shared across the package and hostile-input
        // tests. Invalid assemblies mutate copies of it, never a synthetic loop.
        val midis = (36..72 step 3).toList()
        val notes: List<List<KeyNote>> by lazy { midis.map { MeridianPadMaker.renderZone(spec, it) } }
    }

    @Test
    fun `absolute zones neutralize TUNE and retain the saved model seed and three physical touches`() {
        assertEquals(midis, MeridianPadMaker.zoneMidis(spec))
        val melody = MeridianPadMaker.melodyPatch(patch)
        assertEquals(patch.copy(macros = patch.macros + ("TUNE" to .5f)), melody)
        val midi = 60
        val actual = notes[midis.indexOf(midi)]
        for ((index, velocity) in MeridianPadMaker.VELOCITIES.withIndex()) {
            val expected = Meridian.renderHeld(patch.voice, melody.macros, midi, velocity, patch.seedContext)
            assertEquals(Keys.midiHz(midi).toDouble(), expected.rootHz)
            assertContentEquals(expected.snip.samples, actual[index].snip.samples,
                "zone MIDI is absolute and each touch must retain its native excitation and noise context")
            assertEquals(expected.loopStartFrame.toLong(), actual[index].loopStartFrame)
        }
        assertFalse(actual.first().snip.samples.contentEquals(actual.last().snip.samples),
            "the physical layers collapsed into the same render")
        assertContentEquals(notes[midis.size / 2][1].snip.samples, MeridianPadMaker.preview(spec).samples)
        assertEquals(81, spec.patch.midi)
        assertEquals(.75f, spec.patch.macros.getValue("TUNE"))
        assertEquals(.45f, spec.patch.velocity)
        assertEquals(41, spec.patch.seedContext)
        assertEquals(2, spec.patch.model)
    }

    @Test
    fun `native MPC packages and the phone sidecar preserve loops layers and sustained reference playback`() {
        val name = "MERIDIAN KEYS"
        val program = MeridianPadMaker.export(name, spec, notes, destination)
        assertEquals(midis, program.keygroups.map { it.rootNote })
        assertEquals((36..72).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertEquals(spec.releaseSeconds, program.volumeRelease)
        assertTrue(program.explicitNoteOn, "legacy MPC packages must explicitly obey key release")
        val track = Mpc3Project.read(File(destination, "$name.xty"))
        assertEquals(1, track.programs().single().getValue("type").int())
        val instruments = track.drumPrograms().single().getValue("instruments").arr()
        val data = File(destination, Mpc3TrackWriter.trackDataDirName(name))
        val xpm = File(data, "$name.xpm").readText()
        assertEquals(midis.size, Regex("<TriggerMode>2</TriggerMode>").findAll(xpm).count(),
            "every legacy keygroup zone must declare NoteOn alongside its sustain loop")
        val (sidecar, instrument) = InstrumentStore.list(destination).single()
        assertEquals(spec.releaseSeconds, instrument.release)
        assertEquals(midis, instrument.zones.map { it.rootNote })
        assertEquals(39, instrument.sampleFiles.size)
        val samples = linkedMapOf<String, FloatArray>()
        for ((zoneIndex, zone) in instrument.zones.withIndex()) {
            assertEquals(program.keygroups[zoneIndex].lowNote, zone.lowNote)
            assertEquals(program.keygroups[zoneIndex].highNote, zone.highNote)
            assertTrue((zone.lowNote..zone.highNote).all { kotlin.math.abs(it - zone.rootNote) <= 1 })
            assertEquals(listOf(0, 43, 85), zone.layers.map { it.velStart })
            assertEquals(listOf(42, 84, 127), zone.layers.map { it.velEnd })
            val native = instruments[zoneIndex].obj()
            assertEquals(2, native.getValue("triggerMode").int())
            val nativeLayers = native.getValue("layersv").arr().map { it.obj() }
                .filter { it.getValue("sampleName").str().isNotEmpty() }
            assertEquals(listOf(85, 43, 0), nativeLayers.map { it.getValue("velocityStart").int() })
            for ((layerIndex, layer) in zone.layers.withIndex()) {
                val note = notes[zoneIndex][layerIndex]
                val file = File(sidecar.parentFile, layer.sample)
                val audio = WavReader.read(file)
                samples[layer.sample] = audio.samples
                assertEquals(1, audio.channels)
                assertEquals(44_100, audio.sampleRate)
                assertEquals(note.snip.frameCount.toLong(), layer.frameCount)
                assertEquals(note.loopStartFrame, layer.loopStartFrame)
                val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(layer.loopStartFrame, layer.frameCount))
                assertEquals(sheet, WavReader.readSmpl(file))
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, smpl = sheet) }
                assertContentEquals(encoded.toByteArray(), file.readBytes())
                assertTrue(Keys.seamError(audio.samples, layer.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                val nativeLayer = nativeLayers.single { it.getValue("sampleFile").str() == file.name }
                assertEquals(zone.rootNote, nativeLayer.getValue("rootNote").int())
                val slice = nativeLayer.getValue("sliceInfo").obj()
                assertEquals(1, slice.getValue("LoopMode").int())
                assertEquals(layer.loopStartFrame, slice.getValue("LoopStart").long())
                assertTrue("<SliceLoopStart>${layer.loopStartFrame}</SliceLoopStart>" in xpm)
            }
            for ((layerIndex, velocity) in MeridianPadMaker.VELOCITIES.withIndex()) {
                val hit = assertNotNull(KeyHit.resolve(instrument, zone.rootNote, velocity) { samples[it]?.size?.toLong() })
                assertEquals(zone.layers[layerIndex].sample, hit.sampleFile)
                assertEquals(1.0, hit.pitchRatio, "native roots must play at native speed")
                assertEquals(zone.layers[layerIndex].loopStartFrame, hit.loopStartFrame)
                assertTrue(hit.loops)
            }
        }
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm)
        assertNull(KeyHit.resolve(instrument, 35, 1f) { samples[it]?.size?.toLong() })
        assertNull(KeyHit.resolve(instrument, 73, 1f) { samples[it]?.size?.toLong() })
        val neighbor = assertNotNull(KeyHit.resolve(instrument, 61, .65f) { samples[it]?.size?.toLong() })
        assertEquals(2.0.pow(1.0 / 12), neighbor.pitchRatio, 1e-12)
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, samples), 44_100)
        assertTrue(player.noteOn(60, .65f))
        val source = assertNotNull(instrument.zoneFor(60)).layers[1]
        val duration = source.frameCount.toFloat() / 44_100
        val held = render(player, duration + 1f)
        assertTrue(rms(held, duration + .7f, duration + 1f) > 1e-5,
            "the real Meridian sample must continue after its file ends")
        assertEquals(listOf(60), player.activeNotes)
        player.noteOff(60)
        val released = render(player, spec.releaseSeconds + .1f)
        assertTrue(rms(released, spec.releaseSeconds + .05f, spec.releaseSeconds + .1f) < 1e-6)
        assertTrue(player.activeNotes.isEmpty(), "the reference voice must finish its authored release")
    }

    @Test
    fun `finite legacy and malformed instrument requests refuse without silently upgrading the source`() {
        assertTrue(MeridianPadMaker.supports(patch))
        for (unsupported in listOf(patch.copy(model = 1), patch.copy(macros = patch.macros + ("HOLD" to .974f)))) {
            assertFalse(MeridianPadMaker.supports(unsupported))
            assertFailsWith<IllegalArgumentException> { MeridianPadMaker.Spec(unsupported) }
        }
        for (release in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, .099f, 1.501f)) {
            assertFailsWith<IllegalArgumentException> { MeridianPadMaker.Spec(patch, release) }
        }
        for (fraction in listOf(0f, .5f, 1f, Float.NaN)) {
            assertTrue(MeridianPadMaker.spec(patch, fraction).releaseSeconds in .1f..1.5f)
        }
        assertEquals("0.25 s", MeridianPadMaker.secondsLabel(.25f))
        for (midi in listOf(35, 37, 73)) {
            assertFailsWith<IllegalArgumentException> { MeridianPadMaker.renderZone(spec, midi) }
        }
    }

    @Test
    fun `assembly refuses missing layers invalid samples and a terminal fade in a real sustain loop`() {
        assertFailsWith<IllegalArgumentException> { MeridianPadMaker.assemble("KEYS", spec, notes.dropLast(1)) }
        val missingLayer = notes.mapIndexed { index, row -> if (index == 0) row.dropLast(1) else row }
        assertFailsWith<IllegalArgumentException> { MeridianPadMaker.assemble("KEYS", spec, missingLayer) }
        val original = notes.first().first()
        val nonfinite = original.snip.samples.copyOf().also { it[100] = Float.NaN }
        for (invalid in listOf(
            original.copy(loopStartFrame = 0),
            original.copy(snip = original.snip.copy(sampleRate = 48_000)),
            original.copy(snip = original.snip.copy(channels = 2)),
            original.copy(snip = original.snip.copy(samples = nonfinite)),
        )) {
            assertFailsWith<IllegalArgumentException> { MeridianPadMaker.assemble("KEYS", spec, replacingFirst(invalid)) }
        }
        val fadedPatch = MeridianPadMaker.melodyPatch(patch).copy(midi = midis.first(), velocity = MeridianPadMaker.VELOCITIES.first())
        val faded = PadRecipe(fadedPatch, terminalFadeMs = 20f).render()
        assertTrue(Keys.seamError(faded.samples, original.loopStartFrame.toInt()) >= Keys.MAX_SEAM_ERROR,
            "the hostile fixture must actually damage the existing loop")
        assertFailsWith<IllegalArgumentException> {
            MeridianPadMaker.assemble("KEYS", spec, replacingFirst(KeyNote(faded, original.loopStartFrame)))
        }
    }

    @Test
    fun `callback cancellation and interruption stop native-zone rendering without partial layers`() {
        var checks = 0
        assertFailsWith<CancellationException> { MeridianPadMaker.renderZone(spec, 60) { ++checks; true } }
        assertEquals(1, checks, "pre-cancelled work must stop before its first DSP pass")
        checks = 0
        assertFailsWith<CancellationException> { MeridianPadMaker.renderZone(spec, 60) { ++checks > 1 } }
        assertEquals(2, checks, "a cancellation after the first physical layer must prevent the next one")
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { MeridianPadMaker.renderZone(spec, 60) }
        } finally {
            Thread.interrupted()
        }
    }

    private fun replacingFirst(note: KeyNote) = notes.mapIndexed { index, row -> if (index == 0) listOf(note) + row.drop(1) else row }

    private fun render(player: InstrumentEngine, seconds: Float): Snip {
        val frames = (seconds * 44_100).toInt()
        val samples = FloatArray(frames * 2)
        player.render(samples, frames)
        return Snip(samples, 2, 44_100)
    }

    private fun rms(snip: Snip, from: Float, until: Float): Double {
        val first = (from * snip.sampleRate).toInt()
        val end = minOf((until * snip.sampleRate).toInt(), snip.frameCount)
        return sqrt((first until end).sumOf { snip.samples[it * 2].toDouble() * snip.samples[it * 2] } / (end - first))
    }
}
