package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Bore
import com.snipsnap.synth.BoreKeys
import com.snipsnap.synth.BorePatch
import com.snipsnap.synth.BorePresets
import com.snipsnap.synth.BoreVoice
import com.snipsnap.synth.KeyNote
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BORE held through its own maker ([BorePadMaker]): nine zones a voice, two layers a zone (soft first), each an attack
 * and a sustain loop; the program the phone and the MPC read; the preview a held key plays. The DSP claims (the join,
 * the attack, the layers) are [BoreKeys]'s and are pinned in `:synth`'s `BoreKeysTest`; this pins the instrument.
 */
class BorePadMakerTest {

    private val dest: File = java.nio.file.Files.createTempDirectory("borepad").toFile()

    @AfterTest
    fun cleanUp() {
        dest.deleteRecursively()
    }

    private companion object {
        val patch: BorePatch = BorePresets.forVoice(BoreVoice.FLUTE).first { it.name == "STEADY LOOP" }
        val spec = BorePadMaker.spec(patch, BorePadMaker.RELEASE.fraction(.6f))

        /** One FLUTE instrument, every zone and layer: 18 renders, shared by the tests below. */
        val notes: List<List<KeyNote>> by lazy {
            val t0 = System.nanoTime()
            BorePadMaker.zoneMidis(spec).map { BorePadMaker.renderZone(spec, it) }.also {
                println("BORE held FLUTE STEADY LOOP through BorePadMaker: 9 zones, 18 layers in %.1f s".format(java.util.Locale.ROOT, (System.nanoTime() - t0) / 1e9))
            }
        }

        val rate = 44_100

        fun rms(samples: FloatArray, start: Int, end: Int): Double =
            sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))
    }

    @Test
    fun `the knob, the spec and the voices - and BORE has left the loop-only maker`() {
        assertEquals(.1f, BorePadMaker.RELEASE_MIN_SECONDS)
        assertEquals(1.5f, BorePadMaker.RELEASE_MAX_SECONDS)
        assertEquals(.6f, BorePadMaker.RELEASE.default)
        assertEquals(.6f, BorePadMaker.Spec(patch).releaseSeconds)
        assertEquals(.6f, spec.releaseSeconds, 1e-4f)
        assertEquals(.1f, BorePadMaker.spec(patch, 0f).releaseSeconds, 1e-4f)
        assertEquals(1.5f, BorePadMaker.spec(patch, 1f).releaseSeconds, 1e-4f)
        for (bad in listOf(.05f, 2f, Float.NaN)) assertFailsWith<IllegalArgumentException> { BorePadMaker.Spec(patch, bad) }
        for (voice in BoreVoice.entries) assertTrue(BorePadMaker.supports(BorePatch("ANY", voice, Bore.defaults(voice))), "$voice")
        assertEquals("0.60 s", BorePadMaker.secondsLabel(.6f))
        assertEquals(listOf(1..63, 64..127), BorePadMaker.VELOCITY_WINDOWS)
        assertFalse(LoopPadMaker.offers("BORE"), "BORE's keys are its own maker's now; the loop-only one must not offer them too")
        assertFalse(LoopPadMaker.supports(patch))
    }

    @Test
    fun `the zones are the voice's nine whatever the patch's TUNE, and a zone the voice does not have is refused`() {
        assertEquals((60..84 step 3).toList(), BorePadMaker.zoneMidis(spec))
        for (voice in BoreVoice.entries) for (tune in listOf(0f, .5f, 1f)) {
            val s = BorePadMaker.Spec(BorePatch("ANY", voice, Bore.defaults(voice) + ("TUNE" to tune)))
            assertEquals(BoreKeys.padMidis(voice), BorePadMaker.zoneMidis(s), "$voice at TUNE $tune")
        }
        assertFailsWith<IllegalArgumentException> { BorePadMaker.renderZone(spec, 61) }
        assertFailsWith<IllegalArgumentException> { BorePadMaker.renderZone(spec, 48) }
    }

    @Test
    fun `each zone is two layers, soft first, each its attack then a loop, and TUNE does not move a zone`() {
        val midis = BorePadMaker.zoneMidis(spec)
        for ((i, zone) in notes.withIndex()) {
            assertEquals(2, zone.size, "zone ${midis[i]}")
            for (note in zone) {
                assertEquals(1, note.snip.channels)
                assertEquals(rate, note.snip.sampleRate)
                // The owner's pick (HeldStart.SHORT): the loop starts once the onset's 80 ms have handed over by 300 ms, never inside it.
                val handedOver = (com.snipsnap.synth.HeldStart.SHORT.handover * rate).toLong() + com.snipsnap.synth.HeldStart.SETTLED_FRAMES
                assertTrue(note.loopStartFrame >= handedOver, "zone ${midis[i]}: the loop starts at ${note.loopStartFrame}, inside the onset's handover")
                assertTrue(note.loopStartFrame < rate, "zone ${midis[i]}: the loop starts at ${note.loopStartFrame}, a second or more into the key")
                assertTrue(note.loopStartFrame < note.snip.frameCount, "zone ${midis[i]}: the loop start is past the end")
            }
            assertFalse(zone[0].snip.samples.contentEquals(zone[1].snip.samples), "zone ${midis[i]}: the two layers are one sound")
        }
        val breaths = BoreKeys.layerBreaths(BoreVoice.FLUTE, patch.macros)
        assertTrue(breaths[0] < breaths[1], "the soft layer is not first")
        assertContentEquals(BoreKeys.padLayer(BoreVoice.FLUTE, patch.macros, 72, breaths[0]).snip.samples, notes[4][0].snip.samples, "the first layer is not the soft breath's")
        // The patch's own TUNE plays no part: the zone at C5 from the same patch at TUNE 0 is the same zone.
        val low = BorePadMaker.Spec(patch.withMacros(patch.macros + ("TUNE" to 0f)))
        val moved = BorePadMaker.renderZone(low, 72)
        for (layer in 0..1) assertContentEquals(notes[4][layer].snip.samples, moved[layer].snip.samples, "TUNE moved layer $layer of the C5 zone")
    }

    @Test
    fun `the zones tile as a two-layer keygroup program and survive the export and the phone's sidecar`() {
        val name = "BORE FLUTE"
        val program = BorePadMaker.export(name, spec, notes, dest)
        assertEquals(9, program.keygroups.size)
        val instrument = InstrumentStore.read(InstrumentStore.sidecar(dest, name))
        val midis = BorePadMaker.zoneMidis(spec)
        assertEquals(midis, instrument.zones.map { it.rootNote })
        assertEquals(spec.releaseSeconds, instrument.release, 1e-6f)
        val zones = instrument.zones
        // The program reaches nine semitones past each end; the package writer then stretches the outer zones to the whole
        // keyboard (OneNote's spanKeyboard), as every held instrument's package is written.
        assertEquals(midis.first() - 9, program.keygroups.first().lowNote, "the bottom zone reaches nine semitones under")
        assertEquals(midis.last() + 9, program.keygroups.last().highNote, "the top zone reaches nine semitones over")
        assertEquals(0, zones.first().lowNote, "the package does not span the keyboard")
        assertEquals(127, zones.last().highNote, "the package does not span the keyboard")
        for (i in 1 until zones.size) assertEquals(zones[i - 1].highNote + 1, zones[i].lowNote, "zones tile without gaps")
        var bytes = 0L
        zones.forEachIndexed { i, z ->
            assertEquals(2, z.layers.size, "zone ${z.rootNote}")
            z.layers.forEachIndexed { layer, l ->
                assertEquals(BorePadMaker.VELOCITY_WINDOWS[layer], l.velStart..l.velEnd, "zone ${z.rootNote} layer $layer")
                assertEquals(notes[i][layer].loopStartFrame, l.loopStartFrame, "zone ${z.rootNote} layer $layer")
                val file = File(InstrumentStore.sidecar(dest, name).parentFile, l.sample)
                val wav = WavReader.read(file)
                assertEquals(notes[i][layer].snip.frameCount, wav.frameCount, "zone ${z.rootNote} layer $layer")
                bytes += file.length()
            }
            assertEquals(z.layers.last().sample, z.sample, "the headline sample is the hard layer")
        }
        println("BORE held FLUTE STEADY LOOP exported: 18 WAVs, %.1f MB".format(java.util.Locale.ROOT, bytes / 1e6))
        // What assemble refuses: an unsafe name, a missing zone, a missing layer, a layer without a loop.
        assertFailsWith<IllegalArgumentException> { BorePadMaker.assemble("BORE/FLUTE", spec, notes) }
        assertFailsWith<IllegalArgumentException> { BorePadMaker.assemble(name, spec, notes.drop(1)) }
        assertFailsWith<IllegalArgumentException> { BorePadMaker.assemble(name, spec, notes.mapIndexed { i, z -> if (i == 0) z.take(1) else z }) }
        assertFailsWith<IllegalArgumentException> {
            BorePadMaker.assemble(name, spec, notes.mapIndexed { i, z -> if (i == 0) listOf(z[0].copy(loopStartFrame = 0), z[1]) else z })
        }
    }

    @Test
    fun `the preview holds a key through the attack, into the loop and past a wrap, then obeys the chosen release`() {
        val breath = BoreKeys.layerBreaths(BoreVoice.FLUTE, patch.macros)[1]
        val source = BoreKeys.padLayer(BoreVoice.FLUTE, patch.macros, 72, breath)
        val holdFrames = (BorePadMaker.PREVIEW_HOLD_SECONDS * rate).toInt()
        val loopStart = source.loopStartFrame.toInt()
        val size = source.snip.frameCount
        // The hold crosses the file's end (the first wrap) with a second to spare, so the preview plays the loop after a wrap.
        assertTrue(size + rate < holdFrames, "the preview's hold ($holdFrames frames) does not run a second past the file's end ($size)")
        val short = BorePadMaker.preview(BorePadMaker.Spec(patch, .1f))
        val long = BorePadMaker.preview(BorePadMaker.Spec(patch, 1.5f))
        assertEquals(1, short.channels)
        assertEquals(rate, short.sampleRate)
        assertEquals(holdFrames + 4_410 + 441, short.frameCount)
        assertEquals(holdFrames + 66_150 + 441, long.frameCount)
        // The player at the zone's own root reads the file frame for frame: the attack, the loop, and from the file's end the
        // loop start again. This is the player's view of the wrap: the frame after the last is the loop start.
        val gain = BorePadMaker.PREVIEW_VELOCITY * InstrumentEngine.VOICE_LEVEL
        val s = source.snip.samples
        for (i in 0 until holdFrames) {
            val frame = if (i < size) i else loopStart + (i - size) % (size - loopStart)
            assertEquals(s[frame] * gain, short.samples[i], 1e-6f, "preview frame $i, source frame $frame")
        }
        assertContentEquals(short.samples.copyOfRange(0, holdFrames), long.samples.copyOfRange(0, holdFrames), "the release moved the held sound")
        assertTrue(short.samples.all { it.isFinite() && it in -1f..1f })
        assertTrue(long.samples.all { it.isFinite() && it in -1f..1f })
        assertTrue(rms(short.samples, holdFrames - 4_410, holdFrames) > .001, "the key stops sounding before it is let go")
        val afterShort = holdFrames + 6_615
        assertTrue(rms(long.samples, afterShort, afterShort + 4_410) > .001, "the long release ended with the short one")
        assertTrue(short.samples.takeLast(220).all { it == 0f })
        assertTrue(long.samples.takeLast(220).all { it == 0f })
        assertContentEquals(short.samples, BorePadMaker.preview(BorePadMaker.Spec(patch, .1f)).samples, "the preview differs between two runs")
        assertFailsWith<CancellationException> { BorePadMaker.preview(spec) { true } }
    }
}
