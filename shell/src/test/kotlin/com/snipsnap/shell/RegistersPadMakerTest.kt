package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Registers
import com.snipsnap.synth.RegistersPatch
import com.snipsnap.synth.RegistersVoice
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercise actual exported files, reference playback and the attack-to-held transition. */
class RegistersPadMakerTest {
    private val dest = java.nio.file.Files.createTempDirectory("registers-pad").toFile()

    @AfterTest
    fun cleanUp() { dest.deleteRecursively() }

    private companion object {
        val patch = RegistersPatch("PIPE AIR", RegistersVoice.PIPE, Registers.defaults(RegistersVoice.PIPE),
            midi = 81, velocity = .7f, model = 3)
        val spec = RegistersPadMaker.Spec(patch, releaseSeconds = .4f)
        val notes: List<KeyNote> by lazy {
            RegistersPadMaker.zoneMidis(spec).map { RegistersPadMaker.renderZone(spec, it) }
        }
        // Keep this registration straight so the exported reference player's pitch can be
        // measured independently of a deliberate rank detune or vibrato.
        val transistorPatch = RegistersPatch("COMPACT KEYS", RegistersVoice.TRANSISTOR,
            Registers.defaults(RegistersVoice.TRANSISTOR) + mapOf("SPREAD" to 0f, "MOTION" to 0f),
            midi = 81, velocity = .7f, model = 4)
        val transistorSpec = RegistersPadMaker.Spec(transistorPatch, releaseSeconds = .4f)
        val transistorNotes: List<KeyNote> by lazy {
            val saved = PadRecipe.fromJsonText(PadRecipe(transistorPatch).toJsonText()).patch as RegistersPatch
            assertEquals(transistorPatch, saved)
            RegistersPadMaker.zoneMidis(transistorSpec).map {
                RegistersPadMaker.renderZone(RegistersPadMaker.Spec(saved, .4f), it)
            }
        }
    }

    @Test
    fun `speaking attack survives while independently rendered held flow closes`() {
        for (voice in RegistersVoice.entries) for (midi in listOf(48, 60, 72)) {
            val recipe = patch.copy(voice = voice, macros = Registers.defaults(voice), midi = 24,
                model = if (voice == RegistersVoice.TRANSISTOR) 4 else 3)
            val note = if (voice == RegistersVoice.PIPE) notes[(midi - 48) / 3]
                else RegistersPadMaker.renderZone(RegistersPadMaker.Spec(recipe), midi)
            val finite = recipe.copy(midi = midi).render().samples
            val unchanged = (note.snip.sampleRate * .350).toInt()
            assertContentEquals(finite.copyOf(unchanged), note.snip.samples.copyOf(unchanged), "$voice $midi keeps the complete authored onset")
            val held = Registers.probe(voice, recipe.macros + ("HOLD" to 1f), midi, recipe.velocity, recipe.model)
            assertTrue(requireNotNull(held.cycleError) < 1e-5, "independent held cycles recur")
            assertTrue(requireNotNull(held.seamError) < Keys.MAX_SEAM_ERROR)
            if (voice == RegistersVoice.PIPE) assertTrue(requireNotNull(held.airflowCycleError) < 1e-5)
            assertEquals(held.samples.size, note.snip.frameCount - note.loopStartFrame.toInt())
            assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            // Compare with both neighboring body windows: a phase reset must not hollow the overlap.
            val join = rms(note.snip.samples, .350f, .450f)
            val before = rms(note.snip.samples, .250f, .350f)
            val after = rms(note.snip.samples, .450f, .550f)
            assertTrue(join > minOf(before, after) * .7, "$voice $midi transition loses body: $join vs $before / $after")
            assertTrue(join < maxOf(before, after) * 1.4, "$voice $midi transition swells abruptly")
        }
    }

    @Test
    fun `exported loops roots and release agree in XPM XTY and phone sidecar`() {
        assertExportedPackage("PIPE AIR", spec, notes)
        assertExportedPackage("COMPACT KEYS", transistorSpec, transistorNotes)
    }

    private fun assertExportedPackage(name: String, spec: RegistersPadMaker.Spec, notes: List<KeyNote>) {
        val directory = File(dest, name).apply { mkdirs() }
        val program = RegistersPadMaker.export(name, spec, notes, directory)
        assertEquals((48..72 step 3).toList(), program.keygroups.map { it.rootNote }, "zone pitch replaces saved MIDI 81")
        assertEquals(39, program.keygroups.first().lowNote)
        assertEquals(81, program.keygroups.last().highNote)
        program.keygroups.zipWithNext().forEach { (a, b) -> assertEquals(a.highNote + 1, b.lowNote) }
        val data = File(directory, "${name}_[TrackData]")
        val xpm = data.listFiles { f -> f.extension == "xpm" }!!.single().readText()
        val xty = Acvs.read(File(directory, "$name.xty")).payloadText
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm)
        assertTrue("\"LoopMode\": 1" in xty)
        val (sidecar, instrument) = InstrumentStore.list(directory).single()
        assertEquals(.4f, instrument.release)
        assertEquals(9, instrument.zones.size)
        instrument.zones.forEachIndexed { i, zone ->
            assertEquals(program.keygroups[i].rootNote, zone.rootNote)
            assertEquals(notes[i].loopStartFrame, zone.loopStartFrame)
            assertEquals(notes[i].snip.frameCount.toLong(), zone.frameCount)
            assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in xpm)
            assertTrue("\"LoopStart\": ${zone.loopStartFrame}" in xty)
            val wav = WavReader.read(File(sidecar.parentFile, zone.sample))
            val sampler = assertNotNull(WavReader.readSmpl(File(sidecar.parentFile, zone.sample)))
            assertEquals(zone.rootNote, sampler.rootNote)
            assertEquals(zone.loopStartFrame, assertNotNull(sampler.loop).startFrame)
            assertEquals(zone.frameCount, sampler.loop!!.endFrameExclusive)
            assertEquals(44_100, wav.sampleRate)
            assertEquals(1, wav.channels)
            assertEquals(zone.frameCount, wav.frameCount.toLong())
            assertTrue(wav.samples.all { it.isFinite() })
            val expected = notes[i].snip.samples
            assertTrue(expected.indices.all { abs(expected[it] - wav.samples[it]) < 2e-7f },
                "$name zone ${zone.rootNote} preserves the rendered recipe within PCM24 rounding")
            assertTrue(Keys.seamError(wav.samples, zone.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            assertTrue(wav.peak() <= .991f)
        }
    }

    @Test
    fun `export readback sounds at requested key and velocity until release`() {
        assertReferencePlayback("PIPE AIR", spec, notes)
        assertReferencePlayback("COMPACT KEYS", transistorSpec, transistorNotes)
    }

    private fun assertReferencePlayback(name: String, spec: RegistersPadMaker.Spec, notes: List<KeyNote>) {
        val directory = File(dest, name).apply { mkdirs() }
        RegistersPadMaker.export(name, spec, notes, directory)
        val (sidecar, instrument) = InstrumentStore.list(directory).single()
        val loaded = InstrumentEngine.Loaded(instrument, 44_100,
            instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples })
        for ((root, transpose) in listOf(48 to 0, 60 to 1, 72 to 0)) {
            val engine = InstrumentEngine(loaded, 48_000)
            assertTrue(engine.noteOn(root + transpose, .8f))
            val held = render(engine, 5.5f, 48_000)
            val estimate = assertNotNull(Pitch.detect(held, fromSec = 4.8f, windowSec = .5f))
            val cents = abs(1200 * ln(estimate.hz / Keys.midiHz(root + transpose).toDouble()) / ln(2.0))
            assertTrue(cents < 4, "$name MIDI ${root + transpose} in the reference player at 48 kHz is $cents cents away")
            assertTrue(rms(held.samples, 5.2f, 5.4f, 48_000, 2) > .005, "key still sounds after loop wraps")
            engine.noteOff(root + transpose)
            val release = render(engine, instrument.release + .1f, 48_000)
            assertTrue(release.samples.takeLast(960).all { it == 0f }, "release reaches silence")
            assertTrue(engine.activeNotes.isEmpty())
        }
        val soft = InstrumentEngine(loaded, 44_100).also { it.noteOn(60, .25f) }
        val hard = InstrumentEngine(loaded, 44_100).also { it.noteOn(60, 1f) }
        val softAudio = render(soft, .3f, 44_100).samples
        val hardAudio = render(hard, .3f, 44_100).samples
        assertTrue(softAudio.indices.all { abs(softAudio[it] - hardAudio[it] * .25f) < 1e-6f }, "phone key velocity uses its standard map")
    }

    @Test
    fun `legacy model silence and cancellation survive instrument rendering`() {
        val legacy = patch.copy(model = 1)
        val note = Keys.registersPad(legacy, 60)
        val finite = legacy.copy(midi = 60).render().samples
        assertContentEquals(finite.copyOf(3528), note.snip.samples.copyOf(3528), "legacy finite onset remains legacy")
        val silent = Keys.registersPad(patch.copy(velocity = 0f), 60)
        assertTrue(silent.snip.samples.all { it == 0f })
        assertTrue(silent.loopStartFrame > 0)
        assertFailsWith<CancellationException> { Keys.registersPad(patch, 60) { true } }
        var checks = 0
        assertFailsWith<CancellationException> { Keys.registersPad(patch, 60) { ++checks > 8 } }
        assertEquals(9, checks, "stop during synthesis work")
        assertFailsWith<IllegalArgumentException> { Keys.registersPad(patch, 47) }
        assertFailsWith<IllegalArgumentException> { RegistersPadMaker.Spec(patch, Float.NaN) }
    }

    private fun render(engine: InstrumentEngine, seconds: Float, rate: Int): Snip {
        val frames = (seconds * rate).toInt()
        val samples = FloatArray(frames * 2)
        engine.render(samples, frames)
        return Snip(samples, 2, rate)
    }

    private fun rms(samples: FloatArray, from: Float, until: Float, rate: Int = 44_100, channels: Int = 1): Double {
        val a = (from * rate).toInt()
        val b = (until * rate).toInt().coerceAtMost(samples.size / channels)
        return sqrt((a until b).sumOf { samples[it * channels].toDouble() * samples[it * channels] } / (b - a))
    }
}
