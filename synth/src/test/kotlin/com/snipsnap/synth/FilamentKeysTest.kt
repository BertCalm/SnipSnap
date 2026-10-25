package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The loop proof uses synthesized neighbors, never a returned cycle copied twice. */
class FilamentKeysTest {
    private fun thread() = FilamentPresets.forVoice(FilamentVoice.THREAD).first()

    private fun encoded(samples: FloatArray, depth: WavWriter.BitDepth): FloatArray {
        val bytes = ByteArrayOutputStream().also {
            WavWriter.write(it, Snip(samples, channels = 1, sampleRate = Dsp.RATE), depth)
        }.toByteArray()
        return WavReader.read(bytes).samples
    }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var difference = 0.0
        var energy = 0.0
        for (i in a.indices) {
            val delta = a[i].toDouble() - b[i]
            difference += delta * delta
            energy += a[i].toDouble() * a[i] + b[i].toDouble() * b[i]
        }
        return if (energy == 0.0) 0.0 else 2.0 * difference / energy
    }

    @Test
    fun `three keyboard roots retain a real onset and close against separately synthesized cycles after PCM encoding`() {
        for (midi in listOf(48, 60, 72)) {
            // The upper fixture also exercises authored, non-default strand geometry.
            val patch = thread().copy(midi = midi, config = if (midi == 72)
                FilamentConfig(seed = Int.MIN_VALUE + 29, profile = FilamentProfile.LATE) else thread().config)
            val saved = patch.toJsonText()
            val evidence = Filament.keyNoteMeasured(patch)
            val note = evidence.note
            val samples = note.snip.samples
            val start = note.loopStartFrame.toInt()
            val sustain = samples.copyOfRange(start, samples.size)
            assertEquals(saved, patch.toJsonText(), "keys must not rewrite the supplied patch")
            assertEquals(1, note.snip.channels)
            assertEquals(Dsp.RATE, note.snip.sampleRate)
            assertEquals(evidence.cycleFrames, sustain.size)
            assertTrue(start >= evidence.cycleFrames, "the onset was replaced by a sustain cycle")
            assertTrue(samples.all { it.isFinite() && abs(it) <= 1f }, "THREAD $midi output bounds")
            assertTrue(rms(sustain) > 1e-5, "THREAD $midi sustain is silent")
            assertTrue(rms(samples.copyOfRange(0, 44)) < rms(sustain) * .1,
                "THREAD $midi starts in its settled state instead of retaining its real onset")
            assertTrue(evidence.attempts >= 4)
            assertTrue(evidence.stateError < 1e-5)
            assertTrue(evidence.fullCycleError < 1e-5)

            // Compare every frame with neighbors produced by advancing the network,
            // then repeat the proof after the real WAV writer/reader quantizes them.
            for (depth in listOf(WavWriter.BitDepth.PCM_16, WavWriter.BitDepth.PCM_24)) {
                val previous = encoded(evidence.previous, depth)
                val current = encoded(sustain, depth)
                val following = encoded(evidence.continuation, depth)
                assertTrue(relativeError(previous, current) < 1e-5,
                    "THREAD $midi $depth differs from its synthesized predecessor")
                assertTrue(relativeError(current, following) < 1e-5,
                    "THREAD $midi $depth differs from its synthesized continuation")
                val persisted = encoded(samples, depth)
                assertTrue(Keys.seamError(persisted, start) < Keys.MAX_SEAM_ERROR,
                    "THREAD $midi $depth does not recur at the exported loop marker")
            }

            // The accepted sustain is still the saved model-three held sound.
            val held = patch.copy(macros = patch.macros + ("HOLD" to 1f)).render().samples
            assertContentEquals(held, sustain, "THREAD $midi keys changed the approved held renderer")
            val requestedHz = Keys.midiHz(midi).toDouble()
            assertTrue(abs(1200.0 * kotlin.math.log2(evidence.actualHz / requestedHz)) < 3.0,
                "THREAD $midi loop-period fit moved the requested root")
            val measuredHz = FineTuning.measuredHz(Snip(sustain, 1, Dsp.RATE), requestedHz.toFloat(), .12f, .5f)
            assertTrue(abs(FineTuning.cents(measuredHz, requestedHz)) < 25.0,
                "THREAD $midi actual sustain lost the requested pitch: $measuredHz Hz")
            assertTrue(TangleMeasure.toneShare(sustain, requestedHz) > .025,
                "THREAD $midi requested pitch is only a spectral trace")
            if (midi == 60) {
                val publicNote = FilamentKeys.note(patch)
                assertEquals(note.loopStartFrame, publicNote.loopStartFrame)
                assertContentEquals(samples, publicNote.snip.samples,
                    "diagnostic continuation must not change the delivered note")
            }
        }
    }

    @Test
    fun `sample layers remove one native velocity gain while retaining the played source`() {
        val patch = thread().copy(midi = 60, velocity = .35f)
        val native = FilamentKeys.note(patch)
        val layer = FilamentKeys.layer(patch)
        val gain = patch.velocity.toDouble().pow(.72)
        assertEquals(native.loopStartFrame, layer.loopStartFrame)
        assertEquals(native.snip.frameCount, layer.snip.frameCount)
        for (i in native.snip.samples.indices step 31) {
            assertTrue(abs(native.snip.samples[i] - layer.snip.samples[i] * gain) < 1e-6,
                "host velocity compensation changed the native source at frame $i")
        }
        assertTrue(layer.snip.samples.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test
    fun `the pilot refuses other voices old models and out of range notes before rendering`() {
        for (voice in listOf(FilamentVoice.PULSE, FilamentVoice.CURL)) {
            val patch = FilamentPresets.forVoice(voice).first()
            assertFailsWith<IllegalArgumentException> { FilamentKeys.note(patch) }
        }
        for (model in listOf(Filament.ORIGINAL_MODEL, Filament.CHARACTER_MODEL)) {
            assertFailsWith<IllegalArgumentException> { FilamentKeys.note(thread().copy(model = model)) }
        }
        for (midi in listOf(47, 73)) {
            assertFailsWith<IllegalArgumentException> { FilamentKeys.note(thread(), midi = midi) }
        }
    }

    @Test
    fun `cancellation stops before or during source work and zero velocity remains silent`() {
        assertFailsWith<CancellationException> { FilamentKeys.note(thread(), cancelled = { true }) }
        var checks = 0
        assertFailsWith<CancellationException> {
            FilamentKeys.note(thread(), cancelled = { ++checks > 2 })
        }
        assertTrue(checks > 2, "the engine never reached a cooperative cancellation point")
        val zero = FilamentKeys.layer(thread().copy(velocity = 0f))
        assertTrue(zero.loopStartFrame > 0)
        assertTrue(zero.snip.samples.all { it == 0f }, "zero velocity acquired a non-finite compensation")
    }
}
