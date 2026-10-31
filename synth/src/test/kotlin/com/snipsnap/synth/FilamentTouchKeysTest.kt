package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real source captures, not gain-scaled copies, with independent recurrence evidence. */
class FilamentTouchKeysTest {
    private data class Example(val patch: FilamentPatch, val layers: List<FilamentTouchKeys.Layer>)

    private companion object {
        val examples by lazy {
            FilamentVoice.entries.map { voice ->
                val patch = FilamentPresets.forVoice(voice).first().copy(midi = 60, velocity = .72f,
                    config = FilamentConfig(seed = Int.MIN_VALUE + 31 + voice.ordinal, profile = FilamentProfile.LATE))
                Example(patch, FilamentTouchKeys.capture(patch))
            }
        }
    }

    @Test
    fun `every touch preserves its actual onset and independently synthesized neighbors after PCM encoding`() {
        for ((patch, layers) in examples) {
            val saved = patch.toJsonText()
            assertEquals(FilamentTouchKeys.TOUCHES, layers.map { it.touch })
            assertEquals(FilamentTouchKeys.VELOCITY_WINDOWS, layers.map { it.velocityWindow })
            for (layer in layers) {
                assertEquals(patch.velocity * layer.touch, layer.excitationVelocity)
                assertEquals(patch.copy(velocity = layer.excitationVelocity,
                    macros = patch.macros + mapOf("INJECT" to patch.macros.getValue("INJECT") * layer.touch, "HOLD" to 1f)),
                    layer.sourcePatch, "capture recipe must retain every authored field apart from touch excitation and HOLD")
                val source = Filament.keyNoteMeasured(layer.sourcePatch)
                val gain = layer.excitationVelocity.toDouble().pow(.72).toFloat()
                val samples = layer.note.snip.samples
                val start = layer.note.loopStartFrame.toInt()
                assertEquals(source.note.loopStartFrame, layer.note.loopStartFrame)
                assertEquals(source.note.snip.frameCount, samples.size)
                for (i in samples.indices) assertEquals(source.note.snip.samples[i] / gain, samples[i],
                    "${patch.voice} touch${layer.touch} changed source PCM beyond its one gain removal at $i")
                val sustain = samples.copyOfRange(start, samples.size)
                assertTrue(source.stateError < 1e-5 && source.fullCycleError < 1e-5)
                assertTrue(samples.all { it.isFinite() && abs(it) <= 1f })
                assertTrue(rms(sustain) > 1e-5)
                assertTrue(rms(samples.copyOfRange(0, 44)) < rms(sustain) * .1)
                for (depth in listOf(WavWriter.BitDepth.PCM_16, WavWriter.BitDepth.PCM_24)) {
                    val previous = encode(FloatArray(source.previous.size) { source.previous[it] / gain }, depth)
                    val following = encode(FloatArray(source.continuation.size) { source.continuation[it] / gain }, depth)
                    val current = encode(sustain, depth)
                    assertTrue(relativeError(previous, current) < 1e-5,
                        "${patch.voice} touch${layer.touch} $depth differs from its real predecessor")
                    assertTrue(relativeError(current, following) < 1e-5,
                        "${patch.voice} touch${layer.touch} $depth differs from its real continuation")
                    assertTrue(Keys.seamError(encode(samples, depth), start) < Keys.MAX_SEAM_ERROR)
                }
            }
            val original = FilamentKeys.layer(patch)
            assertEquals(original.loopStartFrame, layers.last().note.loopStartFrame)
            assertContentEquals(original.snip.samples, layers.last().note.snip.samples,
                "the hard touch changed the previous single-layer instrument")
            assertEquals(saved, patch.toJsonText())
        }
    }

    @Test
    fun `touch changes normalized spectral development rather than only volume`() {
        for ((patch, layers) in examples) for ((soft, hard) in layers.zipWithNext()) {
            val distances = listOf(.04, .25, .80).map { from ->
                val a = profile(soft.note.snip.samples, from)
                val b = profile(hard.note.snip.samples, from)
                a.indices.sumOf { abs(a[it] - b[it]) } * .5
            }
            println("FILAMENT TOUCH ${patch.voice} ${soft.touch}/${hard.touch} normalizedPowerTV=$distances")
            assertTrue(distances.average() > .001,
                "${patch.voice} touch${soft.touch}/${hard.touch} only changed gain: $distances")
        }
    }

    @Test
    fun `soft and hard captures keep pitch at the native keyboard edges`() {
        for ((voice, midi) in listOf(FilamentVoice.THREAD to 48, FilamentVoice.CURL to 72)) {
            val patch = FilamentPresets.forVoice(voice).first()
            for (note in FilamentTouchKeys.render(patch, midi)) {
                val sustain = note.snip.samples.copyOfRange(note.loopStartFrame.toInt(), note.snip.frameCount)
                val hz = Keys.midiHz(midi)
                val measured = FineTuning.measuredHz(Snip(sustain, 1, Dsp.RATE), hz, .12f, .5f)
                assertTrue(abs(FineTuning.cents(measured, hz.toDouble())) < 25,
                    "$voice MIDI$midi lost the requested root: $measured")
                assertTrue(TangleMeasure.toneShare(sustain, hz.toDouble()) > .025)
            }
        }
    }

    @Test
    fun `complete saved sources replay without sharing mutable layer arrays`() {
        for ((patch, layers) in examples) {
            val replay = FilamentTouchKeys.render(FilamentPatch.fromJsonText(patch.toJsonText()))
            for (i in layers.indices) {
                assertEquals(layers[i].note.loopStartFrame, replay[i].loopStartFrame)
                assertContentEquals(layers[i].note.snip.samples, replay[i].snip.samples)
            }
            replay.first().snip.samples.fill(Float.NaN)
            assertTrue(replay.last().snip.samples.all(Float::isFinite))
            assertTrue(layers.first().note.snip.samples.all(Float::isFinite))
        }
    }

    @Test
    fun `partial sources use voice defaults without changing their saved shape or hard capture`() {
        for (voice in FilamentVoice.entries) {
            val patch = FilamentPresets.forVoice(voice).first().copy(macros = mapOf("LENGTH" to .2f), midi = 60)
            val saved = patch.toJsonText()
            for (touch in FilamentTouchKeys.TOUCHES) {
                val source = FilamentTouchKeys.sourcePatch(patch, 60, touch)
                assertEquals(Filament.defaults(voice).getValue("INJECT") * touch, source.macros.getValue("INJECT"))
                assertEquals(.2f, source.macros.getValue("LENGTH"))
            }
            assertEquals(saved, patch.toJsonText(), "a derived source expanded the saved partial recipe")
            if (voice == FilamentVoice.PULSE) {
                val old = FilamentKeys.layer(patch)
                val hard = FilamentKeys.layer(FilamentTouchKeys.sourcePatch(patch, 60, 1f))
                assertEquals(old.loopStartFrame, hard.loopStartFrame)
                assertContentEquals(old.snip.samples, hard.snip.samples)
            }
        }
    }

    @Test
    fun `zero subnormal unsupported and cancelled captures retain the existing contracts`() {
        val patch = FilamentPresets.forVoice(FilamentVoice.THREAD).first()
        for (velocity in listOf(0f, Float.MIN_VALUE)) {
            for (layer in FilamentTouchKeys.capture(patch.copy(velocity = velocity))) {
                assertTrue(layer.note.loopStartFrame > 0)
                assertTrue(layer.note.snip.samples.all { it.isFinite() && abs(it) <= 1f })
                if (velocity == 0f) assertTrue(layer.note.snip.samples.all { it == 0f })
            }
        }
        for (model in listOf(1, 2)) {
            val failure = assertFailsWith<IllegalArgumentException> { FilamentTouchKeys.render(patch.copy(model = model)) }
            assertTrue(failure.message.orEmpty().contains("model-3"))
        }
        for (midi in listOf(47, 73)) assertFailsWith<IllegalArgumentException> { FilamentTouchKeys.render(patch, midi) }
        assertFailsWith<CancellationException> { FilamentTouchKeys.render(patch) { true } }
        var checks = 0
        assertFailsWith<CancellationException> { FilamentTouchKeys.render(patch) { ++checks > 6 } }
        assertTrue(checks > 6)
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { FilamentTouchKeys.render(patch) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    private fun encode(samples: FloatArray, depth: WavWriter.BitDepth): FloatArray =
        WavReader.read(ByteArrayOutputStream().also {
            WavWriter.write(it, Snip(samples, 1, Dsp.RATE), depth)
        }.toByteArray()).samples

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var difference = 0.0
        var energy = 0.0
        for (i in a.indices) {
            val delta = a[i].toDouble() - b[i]
            difference += delta * delta
            energy += a[i].toDouble() * a[i] + b[i].toDouble() * b[i]
        }
        return if (energy == 0.0) 0.0 else 2 * difference / energy
    }

    private fun profile(samples: FloatArray, from: Double): DoubleArray {
        val count = 8192
        val real = FloatArray(count * 2)
        val imaginary = FloatArray(real.size)
        val start = (from * Dsp.RATE).toInt()
        for (i in 0 until count) real[i] = (samples[start + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(real, imaginary)
        val power = DoubleArray(real.size / 2) { real[it].toDouble() * real[it] + imaginary[it].toDouble() * imaginary[it] }
        val total = power.sum().coerceAtLeast(1e-30)
        return DoubleArray(power.size) { power[it] / total }
    }
}
