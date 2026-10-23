package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CreaseTouchKeysTest {
    private data class Example(val patch: CreasePatch, val bank: CreaseTouchKeys.Bank)

    companion object {
        private val examples by lazy {
            listOf("Paper Keys", "Ink Ribbon", "Reed Mark").map { name ->
                val patch = CreasePresets.patches().single { it.name == name }
                Example(patch, CreaseTouchKeys.probe(patch, midi = 60))
            }
        }
    }

    @Test
    fun `touch layers have real independent pressure gestures and substantial spectral contrasts`() {
        for ((patch, bank) in examples) {
            assertEquals(3, bank.layers.size, patch.name)
            assertEquals(CreaseTouchKeys.TOUCHES, bank.layers.map { it.touch })
            assertEquals(CreaseTouchKeys.VELOCITY_WINDOWS, bank.layers.map { it.velocityWindow })
            val maxima = bank.layers.map { layer ->
                assertEquals(patch.velocity * layer.touch, layer.excitationVelocity)
                assertEquals(patch.model, layer.probe.diagnostics.modelVersion)
                layer.probe.trace.pressure.max()
            }
            for (i in 0..1) {
                val low = bank.layers[i].probe
                val high = bank.layers[i + 1].probe
                assertEquals(low.diagnostics.loopFrames, high.diagnostics.loopFrames)
                assertEquals(low.diagnostics.renderedHz, high.diagnostics.renderedHz)
                assertTrue(maxima[i + 1] > maxima[i] + .05f, "${patch.name} pressure strengths collapsed: $maxima")
                val distances = listOf(.04, .12, .25, .50, .80).map { seconds ->
                    variation(profile(low.note.snip.samples, (seconds * Crease.RATE).toInt(), low.diagnostics.renderedHz),
                        profile(high.note.snip.samples, (seconds * Crease.RATE).toInt(), high.diagnostics.renderedHz))
                }
                val sustainDistances = listOf(.10, .35, .60, .80).map { fraction ->
                    variation(profile(low.note.snip.samples,
                        low.prefixFrames + (fraction * low.diagnostics.loopFrames).toInt(), low.diagnostics.renderedHz),
                        profile(high.note.snip.samples,
                            high.prefixFrames + (fraction * high.diagnostics.loopFrames).toInt(), high.diagnostics.renderedHz))
                }
                // These are normalized harmonic POWERS. A gain change, waveform phase
                // shift or a silent high harmonic cannot masquerade as a new touch timbre.
                assertTrue(distances.average() >= .025,
                    "${patch.name} touch$i has little attack/body harmonic movement: $distances")
                assertTrue(sustainDistances.average() >= .025,
                    "${patch.name} touch$i has little sustain harmonic movement: $sustainDistances")
                println("CREASE TOUCH TIMBRE ${patch.name} pair=$i pressureMaxima=$maxima " +
                    "bodyTV=${distances.average()} sustainTV=${sustainDistances.average()}")
            }
        }
    }

    @Test
    fun `all complete notes share a common sustain level before ordinary live gain`() {
        for ((patch, bank) in examples) {
            assertTrue(bank.sharedHeadroomGain.isFinite() && bank.sharedHeadroomGain in 0f..1f)
            val unmatchedPeak = bank.layers.maxOf { Dsp.finitePeak(it.probe.prelevel) * it.levelGain }
            val expectedHeadroom = if (unmatchedPeak > .99f) .99f / unmatchedPeak else 1f
            assertEquals(expectedHeadroom, bank.sharedHeadroomGain, 1e-7f,
                "${patch.name} did not derive one headroom gain from the complete bank")
            val target = Dsp.MELODIC_LOUDNESS_TARGET * patch.velocity.pow(.4f) * bank.sharedHeadroomGain
            val levels = bank.layers.map { layer ->
                val p = layer.probe
                val samples = p.note.snip.samples
                val level = Loudness.of(samples.copyOfRange(p.prefixFrames, samples.size), 1, Crease.RATE)
                assertEquals(target, level, target * 2e-5f, "${patch.name} unequal sustain at touch${layer.touch}")
                assertTrue(layer.levelGain.isFinite() && layer.levelGain > 0f)
                val gain = layer.levelGain * bank.sharedHeadroomGain
                for (i in samples.indices step 101) {
                    assertEquals(p.prelevel[i] * gain, samples[i], 2e-7f,
                        "${patch.name} touch${layer.touch} changed envelope at frame$i")
                }
                assertTrue(samples.all { it.isFinite() && abs(it) <= .990001f })
                assertTrue(abs(samples.average()) < .01)
                assertTrue(p.diagnostics.rawPeak < 1.0 && p.diagnostics.maxAbsState <= 1.00001)
                level
            }
            val firstCrossover = 20.0 * log10((levels[1] * 43.0) / (levels[0] * 42.0))
            val secondCrossover = 20.0 * log10((levels[2] * 85.0) / (levels[1] * 84.0))
            assertEquals(20.0 * log10(43.0 / 42.0), firstCrossover, .001)
            assertEquals(20.0 * log10(85.0 / 84.0), secondCrossover, .001)
            println("CREASE TOUCH LEVEL ${patch.name} sustain=$levels sharedHeadroom=${bank.sharedHeadroomGain} " +
                "levelGains=${bank.layers.map { it.levelGain }} crossoverDb=$firstCrossover/$secondCrossover")
        }
        assertTrue(examples.any { it.bank.sharedHeadroomGain < .99f },
            "the fixture bank must exercise shared attenuation as well as the unity-gain path")
    }

    @Test
    fun `each layered note retains its independent full state actual PCM wrap and native root`() {
        for ((patch, bank) in examples) for (layer in bank.layers) {
            val p = layer.probe
            val samples = p.note.snip.samples
            val frames = p.diagnostics.loopFrames
            val stateError = p.loopStateStart.indices.maxOf { abs(p.loopStateStart[it] - p.loopStateEnd[it]) }
            assertEquals(p.diagnostics.stateError, stateError, 1e-12)
            assertTrue(stateError < Crease.LOOP_STATE_TOLERANCE)
            assertTrue(p.diagnostics.converged && p.diagnostics.prerollCycles <= CreaseKeys.MAX_SETTLE_CYCLES)
            assertTrue(p.note.snip.durationSeconds <= CreaseKeys.MAX_SAMPLE_SECONDS)
            val previousError = p.previousCycle.indices.maxOf {
                abs(p.previousCycle[it] - samples[p.prefixFrames - frames + it])
            }
            assertTrue(previousError <= 2e-7f, "${patch.name} independent predecessor changed: $previousError")
            assertEquals(Keys.seamError(samples, p.prefixFrames), p.diagnostics.seamError, 1e-14)
            assertTrue(p.diagnostics.seamError < Keys.MAX_SEAM_ERROR)
            assertEquals(p.diagnostics.rootCycles * Crease.RATE.toDouble() / frames, p.diagnostics.renderedHz)
            assertTrue(abs(FineTuning.cents(p.diagnostics.renderedHz, Keys.midiHz(60).toDouble())) < 1.0)
            for (depth in listOf(WavWriter.BitDepth.PCM_16, WavWriter.BitDepth.PCM_24)) {
                val bytes = ByteArrayOutputStream().also { WavWriter.write(it, p.note.snip, depth) }.toByteArray()
                val actual = WavReader.read(bytes)
                val seam = Keys.seamError(actual.samples, p.prefixFrames)
                assertTrue(seam < Keys.MAX_SEAM_ERROR, "${patch.name} ${layer.touch} $depth seam=$seam")
                val sustain = Snip(actual.samples.copyOfRange(p.prefixFrames, actual.samples.size), 1, Crease.RATE)
                val hz = FineTuning.measuredHz(sustain, Keys.midiHz(60), .12f, .6f)
                val cents = FineTuning.cents(hz, Keys.midiHz(60).toDouble())
                assertTrue(abs(cents) < 1.0, "${patch.name} ${layer.touch} $depth pitch=$cents")
                println("CREASE TOUCH PCM ${patch.name} touch=${layer.touch} $depth seam=$seam pitchCents=$cents " +
                    "stateError=$stateError prefix=${p.prefixSeconds} renderSeconds=${p.diagnostics.renderNanos / 1e9}")
            }
        }
    }

    @Test
    fun `source prelevel state and trace stay exactly those of the existing held renderer`() {
        val (patch, bank) = examples.first()
        val layer = bank.layers.first()
        val source = CreaseKeys.probe(patch.copy(velocity = layer.excitationVelocity), 60, normalize = false)
        assertContentEquals(source.prelevel, layer.probe.prelevel)
        assertContentEquals(source.loopStateStart, layer.probe.loopStateStart)
        assertContentEquals(source.loopStateEnd, layer.probe.loopStateEnd)
        assertContentEquals(source.trace.timeSeconds, layer.probe.trace.timeSeconds)
        assertContentEquals(source.trace.pressure, layer.probe.trace.pressure)
        assertContentEquals(source.trace.deformationA, layer.probe.trace.deformationA)
        assertContentEquals(source.trace.deformationB, layer.probe.trace.deformationB)
        assertContentEquals(source.trace.loadA, layer.probe.trace.loadA)
        assertContentEquals(source.trace.loadB, layer.probe.trace.loadB)
    }

    @Test
    fun `zero anchors retain three valid silent notes and cancelled or unsupported work refuses`() {
        val paper = CreasePresets.patches().single { it.name == "Paper Keys" }
        val bank = CreaseTouchKeys.probe(paper.copy(velocity = 0f))
        assertEquals(1f, bank.sharedHeadroomGain)
        for (layer in bank.layers) {
            assertEquals(0f, layer.excitationVelocity)
            assertTrue(layer.levelGain.isFinite())
            assertTrue(layer.probe.note.snip.samples.all { it == 0f })
            assertTrue(layer.probe.prelevel.all { it == 0f })
            assertTrue(layer.probe.previousCycle.all { it == 0f })
            assertEquals(0.0, layer.probe.diagnostics.seamError)
            assertTrue(layer.probe.diagnostics.converged)
            assertTrue(layer.probe.note.loopStartFrame > 0)
        }
        for (model in listOf(1, 2)) assertFailsWith<IllegalArgumentException> {
            CreaseTouchKeys.render(paper.copy(model = model))
        }
        for (midi in listOf(23, 85)) assertFailsWith<IllegalArgumentException> { CreaseTouchKeys.render(paper, midi) }
        assertFailsWith<IllegalArgumentException> { CreaseTouchKeys.probe(paper, oversample = 8) }
        for (anchor in listOf(Float.MIN_VALUE, CreaseTouchKeys.MIN_POSITIVE_ANCHOR * .5f)) {
            val error = assertFailsWith<IllegalArgumentException> {
                CreaseTouchKeys.render(paper.copy(velocity = anchor))
            }
            assertTrue(error.message.orEmpty().contains("subnormal"))
        }
        val tiny = CreaseTouchKeys.probe(paper.copy(velocity = CreaseTouchKeys.MIN_POSITIVE_ANCHOR))
        assertTrue(tiny.layers.all { layer ->
            layer.probe.note.snip.samples.all { it.isFinite() } && layer.probe.diagnostics.seamError.isFinite()
        })
        assertFailsWith<CancellationException> { CreaseTouchKeys.render(paper, cancelled = { true }) }
        var polls = 0
        assertFailsWith<CancellationException> { CreaseTouchKeys.probe(paper, cancelled = { ++polls > 10 }) }
        assertTrue(polls > 10, "renderer did not consult cancellation during work")
    }

    @Test
    fun `high register bank preserves its hard harmonic shape at the sixteen times reference`() {
        val patch = CreasePresets.patches().single { it.name == "Paper Keys" }
        val normal = CreaseTouchKeys.probe(patch, midi = 84)
        val reference = CreaseTouchKeys.probe(patch, midi = 84, oversample = 16)
        for ((a, b) in normal.layers.zip(reference.layers)) {
            assertEquals(a.probe.diagnostics.loopFrames, b.probe.diagnostics.loopFrames)
            assertEquals(a.probe.diagnostics.rootCycles, b.probe.diagnostics.rootCycles)
            val difference = variation(
                profile(a.probe.note.snip.samples, a.probe.prefixFrames, a.probe.diagnostics.renderedHz),
                profile(b.probe.note.snip.samples, b.probe.prefixFrames, b.probe.diagnostics.renderedHz))
            assertTrue(difference < 1e-4, "touch${a.touch} MIDI84 4x/16x harmonic TV=$difference")
            assertTrue(b.probe.diagnostics.seamError < Keys.MAX_SEAM_ERROR)
            println("CREASE TOUCH REFERENCE touch=${a.touch} MIDI84 harmonicTV4x16x=$difference")
        }
    }

    private fun variation(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) } * .5

    private fun profile(samples: FloatArray, start: Int, root: Double): DoubleArray {
        val count = minOf((.20 * Crease.RATE).toInt(), samples.size - start)
        require(count > 1024)
        val size = 32768
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        val mean = (start until start + count).sumOf { samples[it].toDouble() } / count
        for (i in 0 until count) real[i] = ((samples[start + i] - mean) *
            (.5 - .5 * cos(2.0 * PI * i / (count - 1)))).toFloat()
        Fft.forward(real, imaginary)
        val powers = DoubleArray(32)
        for (harmonic in 1..32) {
            val center = root * harmonic
            if (center >= Crease.RATE * .5 - 15.0) continue
            val low = ((center - 15.0) * size / Crease.RATE).toInt().coerceAtLeast(1)
            val high = ((center + 15.0) * size / Crease.RATE).toInt().coerceAtMost(size / 2)
            for (bin in low..high) powers[harmonic - 1] +=
                real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
        }
        val total = powers.sum().coerceAtLeast(1e-30)
        return DoubleArray(powers.size) { powers[it] / total }
    }
}
