package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaConfig
import com.snipsnap.synth.OrphicaMaterial
import com.snipsnap.synth.OrphicaMode
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrphicaPadMakerTest {
    private val dest = java.nio.file.Files.createTempDirectory("orphica-keys").toFile()

    @AfterTest fun cleanUp() { dest.deleteRecursively() }

    private companion object {
        val spec = OrphicaPadMaker.Spec(
            OrphicaConfig(
                material = OrphicaMaterial.CRYSTAL, mode = OrphicaMode.SCATTER, midi = 62,
                velocity = .7f, pluck = .65f, fracture = .8f, grainSizeMs = 42f,
                grainRateHz = 41f, scatter = .7f, damping = .2f, seconds = .65f,
                seed = Long.MAX_VALUE, captureSeconds = .17f,
            ),
            mapOf("TUNE" to .625f), releaseSeconds = .3f,
        )
        val notes: List<KeyNote> by lazy { OrphicaPadMaker.zoneMidis(spec).map { OrphicaPadMaker.renderZone(spec, it) } }
    }

    @Test fun `roots follow saved pitch and dense zones bound grain speed changes`() {
        val midis = OrphicaPadMaker.zoneMidis(spec)
        assertEquals((53..77 step 3).toList(), midis)
        val (program, samples) = OrphicaPadMaker.assemble("ORPHICA CRYSTAL", spec, notes)
        assertEquals(midis, program.keygroups.map { it.rootNote })
        assertEquals(53, program.keygroups.first().lowNote)
        assertEquals(77, program.keygroups.last().highNote)
        for (i in 1 until midis.size) assertEquals(program.keygroups[i - 1].highNote + 1, program.keygroups[i].lowNote)
        for (zone in program.keygroups) for (midi in zone.lowNote..zone.highNote) {
            assertTrue(abs(midi - zone.rootNote) <= 1, "grain playback shifts by at most one semitone: $zone")
        }
        assertEquals(.3f, program.volumeRelease)
        assertEquals(midis.size, samples.size)
    }

    @Test fun `boundary roots cover their span without duplicate or unreachable roots`() {
        for (root in listOf(Orphica.MIDI_MIN, 23, 103, Orphica.MIDI_MAX)) {
            val edge = OrphicaPadMaker.Spec(OrphicaConfig(midi = root), emptyMap())
            val roots = OrphicaPadMaker.zoneMidis(edge)
            assertEquals(roots.distinct().sorted(), roots)
            assertEquals((root - 12).coerceAtLeast(Orphica.MIDI_MIN), roots.first())
            assertEquals((root + 12).coerceAtMost(Orphica.MIDI_MAX), roots.last())
            assertTrue(root in roots)
            // Assembly needs genuine stereo loop metadata, not an expensive render just to test tiling.
            val sample = KeyNote(Snip(floatArrayOf(.1f, .2f, .2f, .1f, .1f, .2f, .2f, .1f), 2, 44_100), 1L)
            val (program, _) = OrphicaPadMaker.assemble("EDGE", edge, roots.map { sample })
            val ranges = program.keygroups.flatMap { (it.lowNote..it.highNote).toList() }
            assertEquals((roots.first()..roots.last()).toList(), ranges)
            assertTrue(program.keygroups.all { zone -> zone.rootNote in zone.lowNote..zone.highNote })
        }
    }

    @Test fun `zones retain material mode full seed and physical capture settings at exact notes`() {
        val midi = 65
        val effective = Orphica.configWithMacros(spec.config, spec.macros).copy(hold = 1f)
        val expected = Keys.orphicaPad(effective, midi)
        val actual = notes[OrphicaPadMaker.zoneMidis(spec).indexOf(midi)]
        assertContentEquals(expected.snip.samples, actual.snip.samples)
        assertEquals(expected.loopStartFrame, actual.loopStartFrame)
        assertContentEquals(actual.snip.samples, OrphicaPadMaker.preview(spec).samples)
        assertEquals(0f, spec.config.hold, "making captured keys leaves the saved finite patch alone")
    }

    @Test fun `stereo audio and frame loops survive WAV both MPC generations and the phone map`() {
        val name = "ORPHICA CRYSTAL"
        val program = OrphicaPadMaker.export(name, spec, notes, dest)
        val data = File(dest, Mpc3TrackWriter.trackDataDirName(name))
        val xpm = data.listFiles { f -> f.extension == "xpm" }!!.single().readText()
        val xty = GZIPInputStream(File(dest, "$name.xty").inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue("<SliceLoop>1</SliceLoop>" in xpm)
        assertTrue("\"LoopMode\": 1" in xty)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        assertEquals(spec.releaseSeconds, instrument.release)
        assertEquals(OrphicaPadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
        instrument.zones.forEachIndexed { i, zone ->
            val wav = File(sidecar.parentFile, zone.sample)
            val audio = WavReader.read(wav)
            assertEquals(2, audio.channels, "stereo is retained on disk")
            assertEquals(notes[i].snip.frameCount, audio.frameCount, "markers count frames, not interleaved samples")
            assertEquals(notes[i].loopStartFrame, zone.loopStartFrame)
            val sheet = assertNotNull(WavReader.readSmpl(wav))
            assertEquals(zone.rootNote, sheet.rootNote)
            val loop = assertNotNull(sheet.loop)
            assertEquals(zone.loopStartFrame, loop.startFrame)
            assertEquals(zone.frameCount, loop.endFrameExclusive)
            assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in xpm)
            assertTrue("\"LoopStart\": ${zone.loopStartFrame}" in xty)
            assertEquals(program.keygroups[i].layers.single().frameCount, zone.frameCount)
            val hit = assertNotNull(KeyHit.resolve(instrument, zone.rootNote, 1f) { path ->
                WavReader.read(File(sidecar.parentFile, path)).frameCount.toLong()
            })
            assertEquals(1.0, hit.pitchRatio)
            assertTrue(hit.loops)
            assertEquals(zone.loopStartFrame, hit.loopStartFrame)
        }
        // The outer zones stretch to the keyboard's ends, so keys past the authored span land in them.
        assertEquals(0, instrument.zones.first().lowNote)
        assertEquals(127, instrument.zones.last().highNote)
        assertEquals(instrument.zones.first(), instrument.zoneFor(52))
        assertEquals(instrument.zones.last(), instrument.zoneFor(78))
    }

    @Test fun `captured keys hold after the sample ends and release on note off`() {
        OrphicaPadMaker.export("ORPHICA CRYSTAL", spec, notes, dest)
        val (sidecar, instrument) = InstrumentStore.list(dest).single()
        // The pure-JVM reference player accepts mono; the exported/native samples remain stereo.
        val samples = instrument.zones.map { Cleanup.toMono(WavReader.read(File(sidecar.parentFile, it.sample))).samples }
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44_100, samples), 44_100)
        assertTrue(player.noteOn(65))
        val longest = notes.maxOf { it.snip.durationSeconds }
        val held = render(player, longest + 1f)
        assertTrue(rms(held, longest + .8f, longest + 1f) > 1e-5, "capture sounds past the file's end")
        assertEquals(listOf(65), player.activeNotes)
        player.noteOff(65)
        val released = render(player, instrument.release + .1f)
        assertTrue(rms(released, instrument.release + .05f, instrument.release + .1f) < 1e-6)
        assertTrue(player.activeNotes.isEmpty())
    }

    @Test fun `release endpoints and invalid assemblies respect the maker contract`() {
        for (fraction in listOf(0f, .5f, 1f)) {
            assertTrue(OrphicaPadMaker.spec(spec.config, spec.macros, fraction).releaseSeconds in .1f..1.5f)
        }
        assertEquals("0.30 s", OrphicaPadMaker.secondsLabel(.3f))
        assertFailsWith<IllegalArgumentException> { OrphicaPadMaker.Spec(spec.config, emptyMap(), Float.NaN) }
        assertFailsWith<IllegalArgumentException> { OrphicaPadMaker.Spec(spec.config, emptyMap(), 2f) }
        assertFailsWith<IllegalArgumentException> { OrphicaPadMaker.assemble("ORPHICA", spec, emptyList()) }
        assertFailsWith<IllegalArgumentException> { OrphicaPadMaker.renderZone(spec, 52) }
    }

    private fun render(player: InstrumentEngine, seconds: Float): Snip {
        val frames = (seconds * 44_100).toInt()
        val out = FloatArray(frames * 2)
        val block = FloatArray(512)
        var at = 0
        while (at < frames) {
            val n = minOf(256, frames - at)
            player.render(block, n)
            System.arraycopy(block, 0, out, at * 2, n * 2)
            at += n
        }
        return Snip(out, 2, 44_100)
    }

    private fun rms(snip: Snip, from: Float, until: Float): Double {
        val a = (from * snip.sampleRate).toInt()
        val b = (until * snip.sampleRate).toInt().coerceAtMost(snip.frameCount)
        var sum = 0.0
        for (frame in a until b) sum += snip.samples[frame * 2].toDouble() * snip.samples[frame * 2]
        return kotlin.math.sqrt(sum / (b - a))
    }
}
