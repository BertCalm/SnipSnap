package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Preset roles at the same C4 and .65 playing velocity. These gain-independent
 * colour and gesture checks catch near-duplicate recipes; listening still
 * decides whether the measured differences deserve places in the roster.
 */
class OvenPresetsDistinctnessTest {
    @Test
    fun `felt ledger is compact and muted while quiet room is clear and lingering`() {
        for (finished in listOf(false, true)) {
            val felt = signature("Felt Ledger", finished)
            val quiet = signature("Quiet Room", finished)
            assertTrue(quiet.upperToRootDb - felt.upperToRootDb > 6.0,
                "FELT colour separation vanished: $felt / $quiet")
            assertTrue(quiet.decay20Seconds > felt.decay20Seconds * 4.0,
                "FELT roles need different decay gestures: $felt / $quiet")
            assertTrue(quiet.tailAt1s > felt.tailAt1s * 50.0,
                "Quiet Room needs a lingering tail after Felt Ledger has settled: $felt / $quiet")
        }
    }

    @Test
    fun `every preset pair separates in colour attack or decay before and after leveling`() {
        val names = sounds.keys.toList()
        for (finished in listOf(false, true)) {
            for (i in names.indices) for (j in i + 1 until names.size) {
                val a = signature(names[i], finished)
                val b = signature(names[j], finished)
                val colourGap = abs(a.upperToRootDb - b.upperToRootDb)
                val attackGap = abs(a.peakSeconds - b.peakSeconds)
                val decayRatio = max(a.decay20Seconds, b.decay20Seconds) /
                    minOf(a.decay20Seconds, b.decay20Seconds).coerceAtLeast(.01)
                // Three dB doubles relative upper-harmonic power. A 60 ms attack
                // change or a 40% decay change can instead define a gesture role.
                // Requiring one meaningful difference avoids rejecting intentional
                // bright-family variants that differ in rhythmic use rather than colour.
                assertTrue(colourGap >= 3.0 || attackGap >= .060 || decayRatio >= 1.4,
                    "Near collision ${names[i]} / ${names[j]} (finished=$finished): " +
                        "colour $colourGap dB, attack $attackGap s, decay ratio $decayRatio; $a / $b")
            }
        }
    }

    @Test
    fun `signatures ignore playback gain and silent file padding`() {
        val source = sounds.getValue("Quiet Room").finished
        val quietPadded = FloatArray(source.size + 2 * Dsp.RATE) { i ->
            if (i < source.size) source[i] * .013f else 0f
        }
        val expected = measure(source)
        val actual = measure(quietPadded)
        assertEquals(expected.upperToRootDb, actual.upperToRootDb, .002)
        assertEquals(expected.peakSeconds, actual.peakSeconds, .001)
        assertEquals(expected.decay20Seconds, actual.decay20Seconds, .001)
        assertEquals(expected.tailAt1s, actual.tailAt1s, 1e-5)
        assertEquals(expected.tailAt2s, actual.tailAt2s, 1e-5)
    }

    @Test
    fun `afterglows later ring depends on halo without changing its swelling gesture`() {
        val patch = common(OvenPresets.all().first { it.name == "Afterglow" })
        // Both ablations use the same source, controls and explicit duration;
        // the 2 s window is safely before either diagnostic end fade.
        val on = measure(Oven.probe(patch.voice, patch.macros, patch.midi, patch.velocity,
            haloEnabled = true, seconds = 3f).samples)
        val off = measure(Oven.probe(patch.voice, patch.macros, patch.midi, patch.velocity,
            haloEnabled = false, seconds = 3f).samples)
        assertTrue(on.tailAt2s > off.tailAt2s * 10.0,
            "Afterglow lost its independently stored late ring: $on / $off")
        assertTrue(abs(on.peakSeconds - off.peakSeconds) <= .040,
            "Afterglow's bloom gesture changed during a halo ablation: $on / $off")
    }

    private fun signature(name: String, finished: Boolean): Signature = sounds.getValue(name).let {
        if (finished) it.finishedSignature else it.rawSignature
    }

    private companion object {
        private data class Signature(
            val upperToRootDb: Double,
            val peakSeconds: Double,
            val decay20Seconds: Double,
            val tailAt1s: Double,
            val tailAt2s: Double,
        )

        private class Sound(val raw: FloatArray, val finished: FloatArray) {
            val rawSignature = measure(raw)
            val finishedSignature = measure(finished)
        }

        private fun common(patch: OvenPatch) = patch.copy(
            macros = patch.macros + ("TUNE" to .5f), midi = 60, velocity = .65f,
        )

        private val sounds: Map<String, Sound> by lazy {
            OvenPresets.all().associate { saved ->
                val patch = common(saved)
                saved.name to Sound(
                    Oven.probe(patch.voice, patch.macros, patch.midi, patch.velocity).samples,
                    patch.render().samples,
                )
            }
        }

        private fun rms(samples: FloatArray, from: Double, to: Double): Double {
            val lo = (from * Dsp.RATE).roundToInt().coerceIn(0, samples.size)
            val hi = (to * Dsp.RATE).roundToInt().coerceIn(lo, samples.size)
            var energy = 0.0
            for (i in lo until hi) energy += samples[i].toDouble() * samples[i]
            return sqrt(energy / (hi - lo).coerceAtLeast(1))
        }

        private fun measure(samples: FloatArray): Signature {
            // A fixed attack window and relative frequency bands keep pitch,
            // total sample duration and absolute output level out of the score.
            val n = 16384
            val offset = (.008 * Dsp.RATE).roundToInt()
            val count = (.120 * Dsp.RATE).roundToInt()
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (i in 0 until count) {
                re[i] = samples[offset + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
            }
            Fft.forward(re, im)
            val root = Keys.midiHz(60).toDouble()
            var lowerPower = 0.0
            var upperPower = 0.0
            for (i in 1 until n / 2) {
                val hz = i * Dsp.RATE.toDouble() / n
                val power = re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
                if (hz < root * 1.5) lowerPower += power
                if (hz > root * 2.5) upperPower += power
            }
            val colour = 10.0 * log10(upperPower.coerceAtLeast(1e-30) / lowerPower.coerceAtLeast(1e-30))

            val envelope = DoubleArray((samples.size + Dsp.RATE / 100 - 1) / (Dsp.RATE / 100)) { i ->
                rms(samples, i * .010, (i + 1) * .010)
            }
            val peakIndex = envelope.indices.maxBy { envelope[it] }
            val peak = envelope[peakIndex].coerceAtLeast(1e-30)
            // Last crossing of -20 dB accommodates small natural unison beats.
            // Appended silence adds no crossing, unlike a duration-based metric.
            val lastAbove = (peakIndex until envelope.size).last { envelope[it] >= peak * .1 }
            return Signature(colour, (peakIndex + .5) * .010, (lastAbove + .5) * .010,
                rms(samples, .975, 1.025) / peak, rms(samples, 1.975, 2.025) / peak)
        }
    }
}
