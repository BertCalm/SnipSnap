package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BellowsKeysTest {
    @Test
    fun `real onsets settle into PCM16 loops at low middle and high keyboard roots`() {
        for (voice in BellowsVoice.entries) for (midi in listOf(48, 60, 72)) {
            val macros = BellowsTestAudio.diagnosticMacros(voice)
            val note = BellowsKeys.layer(voice, macros, midi, .65f, BellowsGesture.STEADY)
            val s = note.snip.samples
            val start = note.loopStartFrame.toInt()
            assertEquals(1, note.snip.channels)
            assertEquals(44_100, note.snip.sampleRate)
            assertEquals(start * 2, s.size)
            assertTrue(start > 44_100)
            assertTrue(s.all { it.isFinite() && abs(it) <= .991f }, "$voice $midi source headroom")
            val quantized = FloatArray(s.size) { (s[it] * 32767f).toInt() / 32768f }
            val sustain = quantized.copyOfRange(start, s.size)
            val wrap = BellowsTestAudio.wrap(sustain)
            assertTrue(wrap.first <= 1.5 && wrap.second <= 1.5, "$voice $midi wrap $wrap")
            assertTrue(Keys.seamError(quantized, start) < Keys.MAX_SEAM_ERROR, "$voice $midi not settled at marker")
            val first1ms = BellowsTestAudio.rms(s.copyOfRange(0, 44))
            val sustained = BellowsTestAudio.rms(sustain)
            assertTrue(first1ms < sustained * .1, "$voice $midi onset was replaced by a settled loop")
            val hz = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(Snip(sustain, 1, 44_100), hz, .35f, .3f)
            assertTrue(abs(FineTuning.cents(measured, hz.toDouble())) < 12.0, "$voice $midi pitch $measured")
        }
    }

    @Test
    fun `wheel melody ignores tracking drone while its separate tonic retains it`() {
        val macros = Bellows.defaults(BellowsVoice.WHEEL)
        val off = BellowsKeys.note(BellowsVoice.WHEEL, macros + ("DRONE" to 0f), 60)
        val on = BellowsKeys.note(BellowsVoice.WHEEL, macros + ("DRONE" to 1f), 60)
        assertTrue(off.snip.samples.contentEquals(on.snip.samples))
        val bed = BellowsKeys.tonicBed(48, macros)
        val melody = BellowsKeys.note(BellowsVoice.WHEEL, macros, 48)
        assertTrue(!bed.snip.samples.contentEquals(melody.snip.samples))
        assertTrue(bed.loopStartFrame > 0)
    }

    @Test
    fun `native note and sample layer preserve one velocity curve and source timbre`() {
        val voice = BellowsVoice.FOLK
        val macros = BellowsTestAudio.diagnosticMacros(voice)
        val note = BellowsKeys.note(voice, macros, 60, .35f)
        val layer = BellowsKeys.layer(voice, macros, 60, .35f)
        val expectedGain = .35.pow(.60)
        for (i in note.snip.samples.indices step 13) {
            assertTrue(abs(note.snip.samples[i] - layer.snip.samples[i] * expectedGain) < 1e-6)
        }
        val hard = BellowsKeys.layer(voice, macros, 60, 1f)
        val softSpectrum = BellowsTestAudio.spectrum(layer.snip.samples.copyOfRange(layer.loopStartFrame.toInt(), layer.snip.samples.size), Keys.midiHz(60).toDouble())
        val hardSpectrum = BellowsTestAudio.spectrum(hard.snip.samples.copyOfRange(hard.loopStartFrame.toInt(), hard.snip.samples.size), Keys.midiHz(60).toDouble())
        val distance = BellowsTestAudio.logDistance(softSpectrum, hardSpectrum)
        val residual = BellowsTestAudio.scalarResidual(layer.snip.samples, hard.snip.samples)
        println("BELLOWS key velocity: harmonic distance=$distance dB, residual=$residual")
        assertTrue(residual > .004, "layers differ only in gain: $distance dB, residual $residual")
    }

    @Test
    fun `keys refuse legacy models invalid notes velocity and cancelled work`() {
        assertFailsWith<IllegalArgumentException> { BellowsKeys.note(BellowsVoice.CHAMBER, midi = 60, model = 1) }
        for (midi in listOf(47, 73)) assertFailsWith<IllegalArgumentException> { BellowsKeys.note(BellowsVoice.CHAMBER, midi = midi) }
        for (velocity in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            assertFailsWith<IllegalArgumentException> { BellowsKeys.note(BellowsVoice.FOLK, midi = 60, velocity = velocity) }
        }
        assertFailsWith<CancellationException> { BellowsKeys.note(BellowsVoice.WHEEL, midi = 60, cancelled = { true }) }
        assertTrue(BellowsKeys.layer(BellowsVoice.FOLK, midi = 60, velocity = 0f).snip.samples.all { it == 0f })
    }
}
