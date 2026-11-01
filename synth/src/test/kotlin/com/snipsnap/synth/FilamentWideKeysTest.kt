package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** New registers must retain the played onset and converge in the actual guided network. */
class FilamentWideKeysTest {
    @Test fun `thread bass and upper roots retain pitch and independently synthesized recurrence at every touch`() = verify(FilamentVoice.THREAD)
    @Test fun `pulse bass and upper roots retain pitch and independently synthesized recurrence at every touch`() = verify(FilamentVoice.PULSE)
    @Test fun `curl bass and upper roots retain pitch and independently synthesized recurrence at every touch`() = verify(FilamentVoice.CURL)

    private fun verify(voice: FilamentVoice) {
        val patch = FilamentPresets.forVoice(voice).first().copy(midi = 57, velocity = .72f,
            config = FilamentConfig(seed = Int.MIN_VALUE + 17 + voice.ordinal, profile = FilamentProfile.LATE))
        val saved = patch.toJsonText()
        for (midi in listOf(36, 84)) for (touch in FilamentTouchKeys.TOUCHES) {
            val source = FilamentTouchKeys.sourcePatch(patch, midi, touch)
            val started = System.nanoTime()
            val evidence = Filament.keyNoteMeasured(source)
            val note = evidence.note
            val samples = note.snip.samples
            val start = note.loopStartFrame.toInt()
            val sustain = samples.copyOfRange(start, samples.size)
            assertTrue(start >= evidence.cycleFrames)
            assertEquals(evidence.cycleFrames, sustain.size)
            assertTrue(samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(rms(sustain) > 1e-5, "$voice $midi $touch silent sustain")
            assertTrue(rms(samples.copyOfRange(0, 44)) < rms(sustain) * .1, "$voice $midi $touch missing quiet onset")
            assertTrue(evidence.stateError < 1e-5 && evidence.fullCycleError < 1e-5)
            for (depth in listOf(WavWriter.BitDepth.PCM_16, WavWriter.BitDepth.PCM_24)) {
                val current = encoded(sustain, depth)
                assertTrue(relativeError(encoded(evidence.previous, depth), current) < 1e-5,
                    "$voice $midi $touch $depth differs from synthesized predecessor")
                assertTrue(relativeError(current, encoded(evidence.continuation, depth)) < 1e-5,
                    "$voice $midi $touch $depth differs from synthesized continuation")
                assertTrue(Keys.seamError(encoded(samples, depth), start) < Keys.MAX_SEAM_ERROR)
            }
            val hz = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(Snip(sustain, 1, Dsp.RATE), hz, .12f, .5f)
            val cents = FineTuning.cents(measured, hz.toDouble())
            val toneShare = TangleMeasure.toneShare(sustain, hz.toDouble())
            assertTrue(abs(cents) < 25, "$voice $midi $touch requested root lost: $cents cents")
            assertTrue(toneShare > .025, "$voice $midi $touch root is only a spectral trace: $toneShare")
            // Exercise the public compensated capture as well as the diagnostic path.
            val delivered = FilamentKeys.layer(source)
            val gain = source.velocity.toDouble().pow(.72).toFloat()
            assertEquals(note.loopStartFrame, delivered.loopStartFrame)
            assertContentEquals(FloatArray(samples.size) { samples[it] / gain }, delivered.snip.samples)
            assertTrue(delivered.snip.samples.all { it.isFinite() && abs(it) <= 1f })
            println("FILAMENT WIDE $voice MIDI$midi touch=$touch cents=$cents toneShare=$toneShare attempts=${evidence.attempts} stateError=${evidence.stateError} cycleError=${evidence.fullCycleError} frames=${samples.size} loop=$start elapsedMs=${(System.nanoTime() - started) / 1e6}")
        }
        assertEquals(saved, patch.toJsonText())
    }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun encoded(samples: FloatArray, depth: WavWriter.BitDepth) = WavReader.read(ByteArrayOutputStream().also {
        WavWriter.write(it, Snip(samples, 1, Dsp.RATE), depth)
    }.toByteArray()).samples
    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var delta = 0.0
        var energy = 0.0
        for (i in a.indices) {
            delta += (a[i].toDouble() - b[i]).let { it * it }
            energy += a[i].toDouble() * a[i] + b[i].toDouble() * b[i]
        }
        return if (energy == 0.0) 0.0 else 2.0 * delta / energy
    }
}
