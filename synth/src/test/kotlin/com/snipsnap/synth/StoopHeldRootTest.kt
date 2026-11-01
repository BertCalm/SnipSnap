package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A held brightness change must not cancel the independently anchored root. */
class StoopHeldRootTest {
    @Test
    fun `held length changes upper harmonics without erasing or rescaling the root`() {
        data class Case(val voice: StoopVoice, val midi: Int, val shape: Float, val length: Float)
        val cases = buildList {
            for (length in listOf(.75f, .85f, .97f, 1f)) add(Case(StoopVoice.BASS, 36, 0f, length))
            for (voice in listOf(StoopVoice.LEAD, StoopVoice.CHORD)) {
                for (length in listOf(.75f, .85f, 1f)) add(Case(voice, 60, 0f, length))
            }
            // Mixed saw/pulse registrations also exercise a root with a cosine component.
            add(Case(StoopVoice.BASS, 36, .37f, .85f))
            add(Case(StoopVoice.LEAD, 60, .62f, .85f))
        }

        for (case in cases) {
            val label = "${case.voice} shape ${case.shape} LENGTH ${case.length}"
            val patch = StoopPatch(label, case.voice,
                mapOf("TOUCH" to 1f, "COLOR" to 1f, "SHADOW" to 0f,
                    "WEAR" to 0f, "LENGTH" to case.length, "HOLD" to 1f),
                midi = case.midi,
                source = StoopSource(shape = case.shape, sub = 0f, harmonicTilt = 1f, detuneCents = 0f),
                shadowInterval = 0, chordIntervals = listOf(0), model = Stoop.MODEL_VERSION)

            // Measure before whole-note leveling: it could conceal a lost root by boosting
            // all remaining partials. The clean, root-only clock gives both models the
            // same period, filter and settled gain; their root powers should therefore agree.
            val legacy = Stoop.renderRaw(patch.copy(model = Stoop.LEGACY_MODEL_VERSION))
            val current = Stoop.renderRaw(patch)
            assertEquals(legacy.frameCount, current.frameCount, "$label measurement periods must match")
            val rootHz = Keys.midiHz(case.midi)
            val before = rootPower(legacy, rootHz.toDouble())
            val after = rootPower(current, rootHz.toDouble())
            val ratio = after.meanSquare / before.meanSquare
            val minimumShare = if (case.voice == StoopVoice.BASS) .02 else .005
            println("STOOP held root $label: legacy $before, model2 $after, H1 power ratio $ratio")
            assertTrue(after.harmonicShare >= minimumShare,
                "$label requested root disappeared below ${minimumShare * 100}% of H1–H8 power: $after")
            assertTrue(ratio in .98..1.02,
                "$label LENGTH must retain the legacy root power before leveling; ratio $ratio")
            val cents = FineTuning.cents(FineTuning.measuredHz(current, rootHz, .15f, .20f), rootHz.toDouble())
            assertTrue(abs(cents) <= 15, "$label requested root drifted $cents cents")
        }
    }

    private data class RootPower(val meanSquare: Double, val harmonicShare: Double)

    /** Integrate measured power around H1–H8; no knowledge of the oscillator's projection law. */
    private fun rootPower(snip: Snip, rootHz: Double): RootPower {
        val start = (snip.sampleRate * .15).toInt()
        val count = (snip.sampleRate * .20).toInt()
        require(start + count <= snip.samples.size)
        var size = 32768
        while (size < count) size *= 2
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        var windowEnergy = 0.0
        for (i in 0 until count) {
            val angle = 2 * PI * i / (count - 1)
            val window = .35875 - .48829 * cos(angle) + .14128 * cos(2 * angle) - .01168 * cos(3 * angle)
            real[i] = (snip.samples[start + i] * window).toFloat()
            windowEnergy += window * window
        }
        Fft.forward(real, imaginary)
        val width = minOf(rootHz * .35, max(rootHz * .10, 1.5 / .20))
        val partials = DoubleArray(8)
        for (bin in 1 until size / 2) {
            val hz = bin.toDouble() * snip.sampleRate / size
            val power = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
            for (harmonic in 1..8) {
                if (abs(hz - harmonic * rootHz) <= width) partials[harmonic - 1] += power
            }
        }
        val harmonicPower = partials.sum()
        require(partials[0] > 1e-20 && harmonicPower > 1e-20) { "silent root measurement" }
        return RootPower(2 * partials[0] / (size * windowEnergy), partials[0] / harmonicPower)
    }
}
