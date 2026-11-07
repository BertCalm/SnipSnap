package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourPresets
import com.snipsnap.synth.ContourRelation
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Saved recipes, real packages and reference key playback through the new held route. */
class ContourPadMakerTest {
    private val dest = java.nio.file.Files.createTempDirectory("contour-pad").toFile()

    @AfterTest
    fun cleanUp() { dest.deleteRecursively() }

    private companion object {
        val original = ContourPresets.all().first { it.name == "Copper Scan" }
        val authored = original.copy(
            name = "CUSTOM CONTOUR",
            macros = original.macros + mapOf("TUNE" to .9f, "HOLD" to .25f, "EDGE" to .57f),
            config = original.config.copy(edgeRatio = 2f, relation = ContourRelation.OPPOSED, loopSeconds = 1f),
            velocity = .37f,
        )
        val saved = ContourPatch.fromJsonText(authored.toJsonText())
        val spec = ContourPadMaker.Spec(saved, releaseSeconds = .4f)
        val notes: List<KeyNote> by lazy {
            ContourPadMaker.zoneMidis(spec).map { ContourPadMaker.renderZone(spec, it) }
        }

        fun fixtureNote(): KeyNote {
            val cycle = FloatArray(512) { (.1 * sin(2 * PI * it / 512)).toFloat() }
            return KeyNote(Snip(cycle + cycle, 1, 44_100), 512)
        }

        fun fixtures(): List<KeyNote> = List(17) { fixtureNote() }
    }

    @Test
    fun `every voice maps exactly its supported four octaves with no key gaps`() {
        for (voice in ContourVoice.entries) {
            val local = ContourPadMaker.Spec(saved.copy(voice = voice))
            val root = ContourEngine.rootMidi(voice)
            val (program, samples) = ContourPadMaker.assemble("CONTOUR KEYS", local, fixtures())
            assertEquals((root - 24..root + 24 step 3).toList(), program.keygroups.map { it.rootNote })
            assertEquals(root - 24, program.keygroups.first().lowNote)
            assertEquals(root + 24, program.keygroups.last().highNote)
            program.keygroups.zipWithNext().forEach { (a, b) -> assertEquals(a.highNote + 1, b.lowNote) }
            assertEquals(17, samples.size)
            for (midi in root - 24..root + 24) {
                val group = program.keygroups.single { midi in it.lowNote..it.highNote }
                assertTrue(abs(midi - group.rootNote) <= 1, "only neighboring semitones transpose")
            }
        }
    }

    @Test
    fun `saved recipe and excitation survive while each zone replaces TUNE and HOLD`() {
        assertEquals(authored, saved)
        val midi = 63
        val note = notes[9]
        val tune = .5f + (midi - ContourEngine.rootMidi(saved.voice)) / (2f * ContourEngine.TUNE_SEMITONES)
        val expected = ContourEngine.renderLoop(saved.voice,
            saved.macros + mapOf("TUNE" to tune, "HOLD" to 1f), saved.velocity, saved.config)
        assertEquals(expected.size.toLong(), note.loopStartFrame)
        assertEquals(0f, note.snip.samples[0])
        assertContentEquals(expected.copyOfRange(221, expected.size), note.snip.samples.copyOfRange(221, expected.size))
        assertContentEquals(expected, note.snip.samples.copyOfRange(expected.size, note.snip.frameCount))
        assertTrue(Keys.seamError(note.snip.samples, expected.size) < Keys.MAX_SEAM_ERROR)
        val fresh = ContourEngine.renderLoop(saved.voice,
            saved.macros + ("TUNE" to tune), saved.velocity)
        assertFalse(expected.contentEquals(fresh), "custom tables, layer relationships and loop length survive export")
        val hard = ContourEngine.renderLoop(saved.voice,
            saved.macros + ("TUNE" to tune), 1f, saved.config)
        assertFalse(expected.contentEquals(hard), "saved excitation is not silently reset to full velocity")
        assertContentEquals(notes[8].snip.samples, ContourPadMaker.preview(spec).samples)
    }

    @Test
    fun `nonzero settled phases enter from silence without changing the sustain cycle`() {
        val dark = ContourPresets.all().first { it.name == "Dark Table" }
        val local = ContourPadMaker.Spec(dark)
        val root = ContourEngine.rootMidi(dark.voice)
        for (midi in listOf(root - 24, root)) {
            val tune = .5f + (midi - root) / (2f * ContourEngine.TUNE_SEMITONES)
            val loop = ContourEngine.renderLoop(dark.voice,
                dark.macros + mapOf("TUNE" to tune, "HOLD" to 1f), dark.velocity, dark.config)
            assertTrue(abs(loop[0]) > .3f, "fixture exposes an abrupt settled-phase key-on")
            val note = ContourPadMaker.renderZone(local, midi)
            assertEquals(loop.size.toLong(), note.loopStartFrame)
            assertEquals(0f, note.snip.samples[0], "key-on starts at silence")
            assertTrue(abs(note.snip.samples[1]) < .0001f, "the entry has no first-sample amplitude jump")
            for (i in 0 until 221) {
                assertTrue(abs(note.snip.samples[i]) <= abs(loop[i]) + 1e-7f, "entry only attenuates")
            }
            assertEquals(loop[220], note.snip.samples[220], "entry reaches full signal after 5 ms")
            assertContentEquals(loop.copyOfRange(221, loop.size), note.snip.samples.copyOfRange(221, loop.size))
            assertContentEquals(loop, note.snip.samples.copyOfRange(loop.size, note.snip.frameCount),
                "every held wrap uses the untouched cycle")
            assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
        }
    }

    @Test
    fun `exported XPM XTY WAV and phone sidecar retain the same loops roots and release`() {
        val program = ContourPadMaker.export("CONTOUR KEYS", spec, notes, dest)
        val data = File(dest, "CONTOUR KEYS_[TrackData]")
        val xpm = data.listFiles { f -> f.extension == "xpm" }!!.single().readText()
        val xty = Acvs.read(File(dest, "CONTOUR KEYS.xty")).payloadText
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        assertEquals(17, instrument.zones.size)
        assertEquals(.4f, instrument.release)
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm)
        assertTrue("\"LoopMode\": 1" in xty)
        assertEquals(0, instrument.zones.first().lowNote)
        assertEquals(127, instrument.zones.last().highNote)
        val spanned = program.fullKeyboard()
        instrument.zones.forEachIndexed { i, zone ->
            val group = spanned.keygroups[i]
            val expected = notes[i]
            assertEquals(group.lowNote, zone.lowNote)
            assertEquals(group.highNote, zone.highNote)
            assertEquals(group.rootNote, zone.rootNote)
            assertEquals(expected.loopStartFrame, zone.loopStartFrame)
            assertEquals(expected.snip.frameCount.toLong(), zone.frameCount)
            assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in xpm)
            assertTrue("\"LoopStart\": ${zone.loopStartFrame}" in xty)
            val file = File(sidecar.parentFile, zone.sample)
            val wav = WavReader.read(file)
            val smpl = assertNotNull(WavReader.readSmpl(file))
            assertEquals(zone.rootNote, smpl.rootNote)
            assertEquals(zone.loopStartFrame, assertNotNull(smpl.loop).startFrame)
            assertEquals(zone.frameCount, smpl.loop!!.endFrameExclusive)
            assertEquals(44_100, wav.sampleRate)
            assertEquals(1, wav.channels)
            assertEquals(zone.frameCount, wav.frameCount.toLong())
            assertTrue(wav.samples.all { it.isFinite() })
            assertTrue(expected.snip.samples.indices.all { abs(expected.snip.samples[it] - wav.samples[it]) < 2e-7f },
                "zone ${zone.rootNote}: PCM24 readback retains the complete rendered sample")
            assertTrue(Keys.seamError(wav.samples, zone.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            assertTrue(wav.peak() <= .991f)
        }
    }

    @Test
    fun `readback plays exact and interpolated keys through repeated loops velocity and release`() {
        ContourPadMaker.export("PLAYABLE CONTOUR", spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val loaded = InstrumentEngine.Loaded(instrument, 44_100,
            instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples })
        for (midi in listOf(36, 61, 84)) {
            val engine = InstrumentEngine(loaded, 48_000)
            assertTrue(engine.noteOn(midi, .8f))
            val held = render(engine, 3.8f, 48_000)
            val estimate = assertNotNull(Pitch.detect(held, fromSec = 3.1f, windowSec = .5f))
            val cents = abs(1200 * ln(estimate.hz / Keys.midiHz(midi).toDouble()) / ln(2.0))
            assertTrue(cents < 4, "MIDI $midi after loop wraps at 48 kHz: $cents cents")
            assertTrue(rms(held.samples, 3.5f, 3.7f, 48_000, 2) > .005)
            engine.noteOff(midi)
            val release = render(engine, instrument.release + .1f, 48_000)
            assertTrue(release.samples.takeLast(960).all { it == 0f })
            assertTrue(engine.activeNotes.isEmpty())
        }
        // The edge zones stretch to the keyboard's ends, so keys beyond the authored span now play.
        assertTrue(InstrumentEngine(loaded, 44_100).noteOn(35))
        assertTrue(InstrumentEngine(loaded, 44_100).noteOn(85))
        val soft = InstrumentEngine(loaded, 44_100).also { it.noteOn(60, .25f) }
        val hard = InstrumentEngine(loaded, 44_100).also { it.noteOn(60, 1f) }
        val softAudio = render(soft, .3f, 44_100).samples
        val hardAudio = render(hard, .3f, 44_100).samples
        assertTrue(softAudio.indices.all { abs(softAudio[it] - hardAudio[it] * .25f) < 1e-6f },
            "playing velocity uses the standard keygroup amplitude response")
    }

    @Test
    fun `zero excitation remains silent and cancellation reaches synthesis`() {
        val silent = ContourPadMaker.renderZone(ContourPadMaker.Spec(saved.copy(velocity = 0f)), 60)
        assertTrue(silent.snip.samples.all { it == 0f })
        assertTrue(silent.loopStartFrame > 0)
        assertFailsWith<CancellationException> { ContourPadMaker.renderZone(spec, 60) { true } }
        var checks = 0
        assertFailsWith<CancellationException> { ContourPadMaker.renderZone(spec, 60) { ++checks > 8 } }
        assertEquals(9, checks, "cancel during the zone's internal render")
        assertFailsWith<CancellationException> { ContourPadMaker.preview(spec) { true } }
        assertFailsWith<IllegalArgumentException> { ContourPadMaker.renderZone(spec, 35) }
        assertFailsWith<IllegalArgumentException> { ContourPadMaker.renderZone(spec, 85) }
    }

    @Test
    fun `bad release or malformed zones fail before a package is created`() {
        for (release in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, .09f, 1.51f)) {
            assertFailsWith<IllegalArgumentException> { ContourPadMaker.Spec(saved, release) }
        }
        assertEquals(.1f, ContourPadMaker.spec(saved, 0f).releaseSeconds)
        assertEquals(1.5f, ContourPadMaker.spec(saved, 1f).releaseSeconds)
        assertEquals("0.60 s", ContourPadMaker.secondsLabel(.6f))
        assertFailsWith<IllegalArgumentException> { ContourPadMaker.assemble("../bad", spec, fixtures()) }
        assertFailsWith<IllegalArgumentException> { ContourPadMaker.assemble("VALID", spec, fixtures().dropLast(1)) }
        val good = fixtureNote()
        val invalid = listOf(
            good.copy(snip = Snip(FloatArray(1024), 2, 44_100)),
            good.copy(snip = Snip(FloatArray(1024), 1, 48_000)),
            good.copy(snip = Snip(floatArrayOf(Float.NaN, 0f, 0f, 0f), 1, 44_100), loopStartFrame = 1),
            good.copy(snip = Snip(floatArrayOf(0f, Float.POSITIVE_INFINITY, 0f, 0f), 1, 44_100), loopStartFrame = 1),
            good.copy(loopStartFrame = 0),
            good.copy(loopStartFrame = -1),
            good.copy(loopStartFrame = 1023),
            good.copy(loopStartFrame = 1024),
        )
        invalid.forEachIndexed { i, bad ->
            val target = File(dest, "refused-$i")
            assertFailsWith<IllegalArgumentException> {
                ContourPadMaker.export("VALID", spec, fixtures().toMutableList().apply { this[8] = bad }, target)
            }
            assertFalse(target.exists(), "refuse invalid zone $i before any output is made")
        }
    }

    private fun render(engine: InstrumentEngine, seconds: Float, rate: Int): Snip {
        val frames = (seconds * rate).toInt()
        val samples = FloatArray(frames * 2)
        engine.render(samples, frames)
        return Snip(samples, 2, rate)
    }

    private fun rms(samples: FloatArray, from: Float, until: Float, rate: Int, channels: Int): Double {
        val a = (from * rate).toInt()
        val b = (until * rate).toInt().coerceAtMost(samples.size / channels)
        return sqrt((a until b).sumOf { samples[it * channels].toDouble() * samples[it * channels] } / (b - a))
    }
}
