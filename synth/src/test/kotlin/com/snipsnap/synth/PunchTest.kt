package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [Punch] — U3 of `docs/SYNTH_UPGRADE.md`: transient shaping + saturation +
 * loudness-targeted normalise, proven standalone here before THUMP wires it
 * in behind the PUNCH macro.
 */
class PunchTest {

    private fun tone(seconds: Float = 0.1f, amp: Float = 0.5f, hz: Double = 200.0): FloatArray {
        val n = (seconds * Dsp.RATE).toInt()
        return FloatArray(n) { i -> (amp * sin(2.0 * PI * hz * i / Dsp.RATE)).toFloat() }
    }

    @Test
    fun `amount 0 leaves the buffer untouched`() {
        val buf = tone()
        val original = buf.copyOf()
        Punch.apply(buf, 0f)
        assertTrue(original.contentEquals(buf), "amount 0 must be a no-op")
    }

    @Test
    fun `never produces non-finite samples at full amount`() {
        val buf = tone(amp = 0.95f)
        Punch.apply(buf, 1f)
        assertTrue(buf.all { it.isFinite() }, "every sample must stay finite")
    }

    @Test
    fun `emphasizes an attack relative to its own decay`() {
        // A short percussive hit - fast onset, smooth exponential decay -
        // the actual shape every THUMP voice has, not an artificial hard
        // step (which leaves the slow follower stranded above a
        // discontinuous drop and gets boosted right along with it).
        // Crest factor (peak over RMS) should rise once PUNCH pulls the
        // onset up relative to a loudness-matched whole.
        val n = (0.05f * Dsp.RATE).toInt()
        val t60 = 0.01f
        fun hit() = FloatArray(n) { i ->
            val t = i.toFloat() / Dsp.RATE
            (0.9f * Dsp.envAt(t, t60) * sin(2.0 * PI * 1000.0 * t)).toFloat()
        }
        fun crestFactor(buf: FloatArray): Float {
            val peak = buf.maxOf { abs(it) }
            val rms = kotlin.math.sqrt(buf.sumOf { (it * it).toDouble() } / buf.size).toFloat()
            return peak / rms
        }
        val before = hit()
        val after = hit()
        Punch.apply(after, 1f)
        assertTrue(
            crestFactor(after) > crestFactor(before),
            "PUNCH should widen the gap between the onset and the decay: ${crestFactor(before)} -> ${crestFactor(after)}",
        )
    }

    @Test
    fun `the transient window's taper reaches exactly zero at its own boundary`() {
        // A constant-amplitude buffer isolates the taper itself: the last
        // shaped sample and the very next (fully untouched) one see
        // identical input, so if the taper reaches zero exactly at the
        // boundary they must come out identical too. A regression to the
        // un-offset exp(-i/window) taper left a small but real step here -
        // the review that caught it asked for exactly this proof.
        val windowSamples = (0.0005f * Dsp.RATE).toInt().coerceAtLeast(1) // mirrors Punch.ATTACK_WINDOW_SECONDS
        val cutoffSamples = windowSamples * 4
        val buf = FloatArray(cutoffSamples + 10) { 0.7f }
        Punch.apply(buf, 1f)
        assertTrue(
            abs(buf[cutoffSamples - 1] - buf[cutoffSamples]) < 1e-5f,
            "the last shaped sample should meet the first untouched one at the same gain: " +
                "${buf[cutoffSamples - 1]} vs ${buf[cutoffSamples]}",
        )
    }

    @Test
    fun `roughly preserves loudness - it reshapes, it does not just get louder`() {
        val buf = tone(seconds = 0.2f, amp = 0.6f)
        val loudBefore = Loudness.of(Snip(buf.copyOf(), channels = 1, sampleRate = Dsp.RATE))
        Punch.apply(buf, 1f)
        val loudAfter = Loudness.of(Snip(buf, channels = 1, sampleRate = Dsp.RATE))
        assertTrue(
            abs(loudAfter - loudBefore) < loudBefore * 0.15f,
            "should stay close to the pre-PUNCH loudness: $loudBefore -> $loudAfter",
        )
    }

    // ---------- Punch.applyOversampled: saturate/boost before decimate, not after ----------
    // (U6, docs/SYNTH_UPGRADE.md) An oversampling engine's saturate and
    // boostEnvelope calls belong BEFORE Dsp.decimate, specifically so
    // decimate's own band-limiting filter catches whatever new harmonics
    // and sidebands they mint. Exercising Punch.applyOversampled itself -
    // the exact function Thump.render() calls - rather than a hand-rolled
    // stand-in for it, means a future edit that moves either stage back
    // across the decimate boundary inside that function fails here too,
    // not just in a reconstruction that could drift out of sync with it.

    @Test
    fun `applyOversampled leaves far less aliased energy than running saturate and boost after decimate`() {
        // A 16kHz tone: close enough to 44.1kHz's 22.05kHz Nyquist to be
        // exactly the kind of content a bright THUMP voice (HAT, CLICK) has
        // plenty of. Dsp.drive is tanh-based, an odd function, so a sine
        // only picks up odd harmonics - the 3rd (48kHz) sits well inside
        // the oversampled rate's own Nyquist (88.2kHz), where decimate
        // legitimately removes it, but has no representation at all in a
        // signal already sampled at 44.1kHz except as its alias,
        // 48000-44100 = 3900Hz: a spurious tone nowhere near the
        // fundamental or its real harmonics, which a clean sine has no
        // business producing.
        val hz = 16_000.0
        val oversampledRate = Dsp.RATE * Dsp.OVERSAMPLE
        fun tone176k() = FloatArray((0.1f * oversampledRate).toInt()) { i ->
            (0.8f * sin(2.0 * PI * hz * i / oversampledRate)).toFloat()
        }

        val correct = Punch.applyOversampled(tone176k(), 1f, oversampledRate, Dsp.RATE)

        // The buggy ordering this test exists to catch: decimate first,
        // then run both stages on the already-final-rate buffer - what
        // Thump.render() did before U6's aliasing fix, and what a
        // regression back to it would reintroduce.
        val buggy = Dsp.decimate(tone176k(), Dsp.RATE)
        Dsp.normalize(buggy)
        Punch.saturate(buggy, 1f, Dsp.RATE)
        Punch.boostEnvelope(buggy, 1f, Dsp.RATE)

        val fftSize = 4096
        val correctSpectrum = com.snipsnap.audio.Fft.magnitudeSpectrum(correct, fftSize)
        val buggySpectrum = com.snipsnap.audio.Fft.magnitudeSpectrum(buggy, fftSize)
        val binHz = Dsp.RATE.toFloat() / fftSize

        // A band with the fundamental, DC, and every real harmonic excluded
        // - a clean sine shaped the correct way should have essentially
        // nothing here; measured, running both stages post-decimate leaves
        // roughly 90x the energy in it (the boost envelope's own sidebands
        // compound with saturate's aliased harmonic here).
        val lo = (2_000 / binHz).toInt()
        val hi = (8_500 / binHz).toInt()
        fun bandEnergy(spectrum: FloatArray) = (lo..hi).sumOf { (spectrum[it] * spectrum[it]).toDouble() }
        val correctEnergy = bandEnergy(correctSpectrum)
        val buggyEnergy = bandEnergy(buggySpectrum)
        assertTrue(
            buggyEnergy > correctEnergy * 10,
            "running saturate/boostEnvelope after decimate should leave detectably more aliased " +
                "energy in a band the clean tone has none of: correct=$correctEnergy buggy=$buggyEnergy",
        )
    }
}
