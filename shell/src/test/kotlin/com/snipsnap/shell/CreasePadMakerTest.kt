package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreaseVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Check the actual dual-generation package and the same key map used by the phone. */
class CreasePadMakerTest {
    private val dest = java.nio.file.Files.createTempDirectory("crease-keys").toFile()

    @AfterTest fun cleanUp() { dest.deleteRecursively() }

    private companion object {
        val patch = CreasePatch("CREASE KEYS", CreaseVoice.ROUND,
            Crease.defaults(CreaseVoice.ROUND) + mapOf("FOLD" to .25f, "PRESSURE" to .65f,
                "DRAG" to .22f, "RELAX" to .25f, "HOLD" to 0f),
            midi = 48, velocity = .65f, model = 3)
        val spec = CreasePadMaker.Spec(patch, releaseSeconds = .4f)
        val notes: List<KeyNote> by lazy {
            CreasePadMaker.zoneMidis(spec).map { CreasePadMaker.renderZone(spec, it) }
        }
    }

    @Test
    fun `saved root and boundary roots tile only their sampled keyboard span`() {
        assertEquals((36..60 step 3).toList(), CreasePadMaker.zoneMidis(spec))
        // Genuine audio is unnecessary for testing the keyboard's range and file-name contract.
        val fixture = KeyNote(Snip(floatArrayOf(0f, .2f, 0f, -.2f, 0f, .2f, 0f, -.2f), 1, Crease.RATE), 4L)
        for (root in listOf(24, 25, 48, 83, 84)) {
            val edge = CreasePadMaker.Spec(patch.copy(midi = root))
            val roots = CreasePadMaker.zoneMidis(edge)
            val (program, samples) = CreasePadMaker.assemble("CREASE EDGE", edge, roots.map { fixture })
            assertTrue(root in roots, "the saved root is a directly rendered zone")
            assertEquals(roots.distinct().sorted(), roots)
            assertEquals(maxOf(24, root - 12), roots.first())
            assertEquals(minOf(84, root + 12), roots.last())
            assertEquals(roots, program.keygroups.map { it.rootNote })
            assertEquals(roots.size, samples.size, "sharp and natural root names cannot overwrite a WAV")
            assertEquals((roots.first()..roots.last()).toList(),
                program.keygroups.flatMap { (it.lowNote..it.highNote).toList() }, "no keyboard gaps or overlap")
            assertTrue(program.keygroups.all { zone ->
                zone.rootNote in zone.lowNote..zone.highNote &&
                    (zone.lowNote..zone.highNote).all { abs(it - zone.rootNote) <= 1 }
            })
        }
    }

    @Test
    fun `WAV sampler metadata both MPC generations and phone sidecar retain roots loops and release`() {
        val before = patch.toJsonText()
        val program = CreasePadMaker.export(patch.name, spec, notes, dest)
        val data = File(dest, Mpc3TrackWriter.trackDataDirName(patch.name))
        val xpm = File(data, "${patch.name}.xpm").readText()
        val xty = Acvs.read(File(dest, "${patch.name}.xty")).payloadText
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm)
        assertTrue("<VolumeAttack>0.000000</VolumeAttack>" in xpm, "the WAV already owns its onset")
        assertTrue("<VolumeRelease>0.400000</VolumeRelease>" in xpm)
        assertTrue("\"LoopMode\": 1" in xty)
        val envelopes = objects(Json.parse(xty)).mapNotNull { it["ampEnvelope"]?.obj() }
            .take(notes.size).toList()
        assertEquals(notes.size, envelopes.size)
        for (envelope in envelopes) {
            assertEquals(spec.releaseSeconds.toDouble(), envelope.getValue("Release").obj().getValue("value0").num())
            assertFalse(envelope.getValue("OneShot").obj().getValue("value0").bool())
        }
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        assertEquals(spec.releaseSeconds, instrument.release)
        assertEquals(CreasePadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
        instrument.zones.forEachIndexed { index, zone ->
            assertEquals(program.keygroups[index].lowNote, zone.lowNote)
            assertEquals(program.keygroups[index].highNote, zone.highNote)
            assertEquals(notes[index].loopStartFrame, zone.loopStartFrame)
            val wav = File(sidecar.parentFile, zone.sample)
            val audio = WavReader.read(wav)
            val sheet = assertNotNull(WavReader.readSmpl(wav))
            val loop = assertNotNull(sheet.loop)
            assertEquals(zone.rootNote, sheet.rootNote)
            assertEquals(zone.loopStartFrame, loop.startFrame)
            assertEquals(zone.frameCount, loop.endFrameExclusive, "the player wraps at the sample's exclusive end")
            assertEquals(notes[index].snip.frameCount.toLong(), zone.frameCount)
            assertEquals(Crease.RATE, audio.sampleRate)
            assertEquals(1, audio.channels)
            assertTrue(audio.samples.all { it.isFinite() })
            assertTrue(audio.samples.indices.all { abs(audio.samples[it] - notes[index].snip.samples[it]) < 2e-7f },
                "authored model, excitation and macro audio survives PCM24 export")
            assertTrue(Keys.seamError(audio.samples, zone.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in xpm)
            assertTrue("\"LoopStart\": ${zone.loopStartFrame}" in xty)
        }
        assertEquals(before, patch.toJsonText(), "making an instrument leaves the complete saved recipe intact")
        assertEquals(0f, patch.macros.getValue("HOLD"))
        assertNull(instrument.zoneFor(35))
        assertNull(instrument.zoneFor(61))
    }

    @Test
    fun `export readback holds at the played pitch at 48 kHz then releases on note off`() {
        CreasePadMaker.export(patch.name, spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        val loaded = InstrumentEngine.Loaded(instrument, Crease.RATE,
            instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples })
        val player = InstrumentEngine(loaded, 48_000)
        assertTrue(player.noteOn(49, .8f))
        val seconds = notes.maxOf { it.snip.durationSeconds } + 1f
        val held = render(player, seconds, 48_000)
        assertTrue(rms(held.samples, held.samples.size - 48_000, held.samples.size) > .001,
            "the note keeps sounding after the exported file has ended")
        val detected = assertNotNull(Pitch.detect(held, fromSec = seconds - .6f, windowSec = .4f))
        val cents = abs(1200 * ln(detected.hz / Keys.midiHz(49).toDouble()) / ln(2.0))
        assertTrue(cents < 5, "the transposed held key is $cents cents from its requested pitch")
        player.noteOff(49)
        val release = render(player, instrument.release + .15f, 48_000)
        assertTrue(release.samples.takeLast(960).all { it == 0f })
        assertTrue(player.activeNotes.isEmpty())

        // The saved excitation is baked once. A key touch adds only the existing linear player gain.
        val soft = InstrumentEngine(loaded, Crease.RATE).also { assertTrue(it.noteOn(48, .25f)) }
        val hard = InstrumentEngine(loaded, Crease.RATE).also { assertTrue(it.noteOn(48, 1f)) }
        val a = render(soft, .3f, Crease.RATE).samples
        val b = render(hard, .3f, Crease.RATE).samples
        assertTrue(a.indices.all { abs(a[it] - .25f * b[it]) < 1e-7f })
    }

    @Test
    fun `preview includes the saved root arrival and another complete pass around its held region`() {
        val root = notes[CreasePadMaker.zoneMidis(spec).indexOf(patch.midi)]
        val preview = CreasePadMaker.preview(spec)
        assertEquals(root.snip.frameCount * 2 - root.loopStartFrame.toInt(), preview.frameCount)
        assertContentEquals(root.snip.samples, preview.samples.copyOf(root.snip.frameCount))
        assertContentEquals(root.snip.samples.copyOfRange(root.loopStartFrame.toInt(), root.snip.frameCount),
            preview.samples.copyOfRange(root.snip.frameCount, preview.frameCount))
    }

    @Test
    fun `legacy recipes invalid packages and cancelled zones publish no partial instrument`() {
        assertTrue(CreasePadMaker.supports(patch))
        for (model in listOf(1, 2)) {
            val legacy = patch.copy(model = model)
            assertFalse(CreasePadMaker.supports(legacy))
            assertFailsWith<IllegalArgumentException> { CreasePadMaker.Spec(legacy) }
            assertEquals(model, legacy.model, "a builder refusal never upgrades an old recipe")
        }
        for (release in listOf(.01f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { CreasePadMaker.Spec(patch, release) }
        }
        assertEquals(.1f, CreasePadMaker.spec(patch, 0f).releaseSeconds)
        assertEquals(1.5f, CreasePadMaker.spec(patch, 1f).releaseSeconds)
        assertEquals("0.40 s", CreasePadMaker.secondsLabel(.4f))
        assertFailsWith<CancellationException> { CreasePadMaker.renderZone(spec, patch.midi) { true } }
        var checks = 0
        assertFailsWith<CancellationException> { CreasePadMaker.renderZone(spec, patch.midi) { ++checks > 4 } }
        assertTrue(checks > 4, "cancellation is polled inside the held render")
        assertFailsWith<IllegalArgumentException> { CreasePadMaker.renderZone(spec, 35) }
        val untouched = File(dest, "invalid")
        val invalid = notes.toMutableList().also { it[0] = it[0].copy(loopStartFrame = 0L) }
        assertFailsWith<IllegalArgumentException> { CreasePadMaker.export("INVALID", spec, invalid, untouched) }
        assertFalse(untouched.exists(), "all zones are validated before the package writer creates a directory")
    }

    private fun objects(value: JsonValue): Sequence<Map<String, JsonValue>> = sequence {
        when (value) {
            is JsonValue.Obj -> {
                yield(value.entries)
                value.entries.values.forEach { yieldAll(objects(it)) }
            }
            is JsonValue.Arr -> value.items.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }

    private fun render(player: InstrumentEngine, seconds: Float, rate: Int): Snip {
        val frames = (seconds * rate).toInt()
        val samples = FloatArray(frames * 2)
        player.render(samples, frames)
        return Snip(samples, channels = 2, sampleRate = rate)
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Double =
        sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))
}
