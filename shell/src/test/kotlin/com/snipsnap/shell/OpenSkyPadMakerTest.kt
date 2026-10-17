package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.OpenSky
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OpenSkyTimbre
import com.snipsnap.synth.OpenSkyVoice
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenSkyPadMakerTest {
    private val patch = OpenSkyPatch("MY HALO", OpenSkyVoice.HALO, OpenSky.defaults(OpenSkyVoice.HALO),
        midi = 72, velocity = .35f, wet = false,
        timbre = OpenSkyTimbre(sineMix = .65f, attackScale = 1.4f, toneScale = .7f))
    private val spec = OpenSkyPadMaker.Spec(patch, releaseSeconds = .9f)

    /** Packaging fixtures deliberately differ between channels and use frame-based loop markers. */
    private fun notes(): List<KeyNote> = OpenSkyPadMaker.zoneMidis(spec).mapIndexed { index, _ ->
        KeyNote(Snip(FloatArray(256) { if (it % 2 == 0) .125f else -.0625f }, channels = 2, sampleRate = OpenSky.RATE),
            loopStartFrame = (32 + index).toLong())
    }

    @Test
    fun `the complete patch is preserved and nine zones tile around its saved root`() {
        assertEquals(patch, spec.patch)
        val (program, samples) = OpenSkyPadMaker.assemble("MY HALO", spec, notes())
        assertEquals((60..84 step 3).toList(), program.keygroups.map { it.rootNote })
        assertEquals(9, samples.size)
        assertEquals(.9f, program.volumeRelease)
        assertEquals(51, program.keygroups.first().lowNote)
        assertEquals(93, program.keygroups.last().highNote)
        program.keygroups.zipWithNext().forEach { (a, b) -> assertEquals(a.highNote + 1, b.lowNote) }
        program.keygroups.forEach { assertEquals(128L, it.layers.single().frameCount) }
    }

    @Test
    fun `stereo samples loops and release survive native compatibility and phone export`() {
        val dir = createTempDirectory("opensky-instrument").toFile()
        try {
            val notes = notes()
            val program = OpenSkyPadMaker.export("MY HALO", spec, notes, dir)
            val data = File(dir, Mpc3TrackWriter.trackDataDirName(program.name))
            assertTrue(File(dir, "${program.name}.xty").isFile)
            val native = Mpc3TrackWriter().keygroupPayloadText(program)
            val compatibility = File(data, "${program.name}.xpm").readText()
            assertTrue("\"LoopMode\": 1" in native)
            assertTrue("<SliceLoop>1</SliceLoop>" in compatibility)
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(.9f, instrument.release)
            for ((index, zone) in instrument.zones.withIndex()) {
                val wav = File(sidecar.parentFile, zone.sample)
                val audio = WavReader.read(wav)
                assertEquals(2, audio.channels)
                assertEquals(128, audio.frameCount)
                val sheet = SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount))
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, notes[index].snip, smpl = sheet) }
                assertContentEquals(expected.toByteArray(), wav.readBytes())
                assertEquals(notes[index].loopStartFrame, zone.loopStartFrame)
                assertEquals(sheet, WavReader.readSmpl(wav))
                assertTrue("\"LoopStart\": ${zone.loopStartFrame}" in native)
                assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in compatibility)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preview plays the authored stereo arrival and two sustain passes`() {
        val note = OpenSkyPadMaker.renderZone(spec, patch.midi)
        val preview = OpenSkyPadMaker.preview(spec)
        val loopFrames = note.snip.frameCount - note.loopStartFrame.toInt()
        assertEquals(2, preview.channels)
        assertEquals(note.snip.frameCount + loopFrames, preview.frameCount)
        assertContentEquals(note.snip.samples, preview.samples.copyOfRange(0, note.snip.samples.size))
        assertContentEquals(note.snip.samples.copyOfRange(note.loopStartFrame.toInt() * 2, note.snip.samples.size),
            preview.samples.copyOfRange(note.snip.samples.size, preview.samples.size))
    }

    @Test
    fun `unsupported voices roots and invalid loops refuse instead of clamping`() {
        assertTrue(OpenSkyPadMaker.supports(patch))
        for (voice in OpenSkyVoice.entries.filter { it != OpenSkyVoice.HALO }) {
            assertFalse(OpenSkyPadMaker.supports(voice))
            assertFailsWith<IllegalArgumentException> { OpenSkyPadMaker.Spec(patch.copy(voice = voice)) }
        }
        for (midi in listOf(35, 97)) {
            assertFalse(OpenSkyPadMaker.supports(patch.copy(midi = midi)))
            assertFailsWith<IllegalArgumentException> { OpenSkyPadMaker.Spec(patch.copy(midi = midi)) }
        }
        val bad = notes().toMutableList().also { it[0] = it[0].copy(loopStartFrame = 0) }
        assertFailsWith<IllegalArgumentException> { OpenSkyPadMaker.assemble("MY HALO", spec, bad) }
        for (release in listOf(.01f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { OpenSkyPadMaker.Spec(patch, release) }
        }
        for (fraction in listOf(0f, .5f, 1f)) {
            assertTrue(OpenSkyPadMaker.spec(patch, fraction).releaseSeconds in .1f..1.5f)
        }
    }

    @Test
    fun `a cancelled held render stops before making an instrument`() {
        assertFailsWith<CancellationException> { OpenSkyPadMaker.renderZone(spec, patch.midi) { true } }
    }
}
