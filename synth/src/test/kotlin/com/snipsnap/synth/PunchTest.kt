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

    // ---------- saturate before decimate, not after (U6, docs/SYNTH_UPGRADE.md) ----------
    // An oversampling engine calls Punch.saturate on its own raw buffer
    // BEFORE Dsp.decimate, specifically so decimate's own band-limiting
    // filter catches whatever new harmonics saturate's nonlinearity mints.
    // A regression back to calling it on the already-decimated buffer
    // would pass every other test in this file (they all call Punch.apply
    // at a single, already-final rate) - this is the one that would catch it.

    @Test
    fun `saturating before decimate leaves far less aliased energy than saturating after`() {
        // An 18kHz tone: close enough to 44.1kHz's 22.05kHz Nyquist to be
        // exactly the kind of content a bright THUMP voice (HAT, CLICK) has
        // plenty of. Its 2nd harmonic (36kHz) sits well inside the
        // oversampled rate's own Nyquist (88.2kHz) - decimate legitimately
        // removes it there - but has no representation at all in a signal
        // already sampled at 44.1kHz except as its alias, 44100-36000 =
        // 8100Hz: a spurious tone nowhere near the fundamental or its real
        // harmonics, which a clean sine has no business producing.
        val hz = 18_000.0
        val oversampledRate = Dsp.RATE * Dsp.OVERSAMPLE
        fun tone176k() = FloatArray((0.1f * oversampledRate).toInt()) { i ->
            (0.8f * sin(2.0 * PI * hz * i / oversampledRate)).toFloat()
        }

        val correctOrder = tone176k()
        Punch.saturate(correctOrder, 1f, oversampledRate)
        val correct = Dsp.decimate(correctOrder, Dsp.RATE)

        val buggyOrder = Dsp.decimate(tone176k(), Dsp.RATE)
        Punch.saturate(buggyOrder, 1f, Dsp.RATE)

        val fftSize = 4096
        val correctSpectrum = com.snipsnap.audio.Fft.magnitudeSpectrum(correct, fftSize)
        val buggySpectrum = com.snipsnap.audio.Fft.magnitudeSpectrum(buggyOrder, fftSize)
        val binHz = Dsp.RATE.toFloat() / fftSize

        // A band with the fundamental, DC, and every real harmonic excluded
        // - a clean sine saturated the correct way should have essentially
        // nothing here; measured, saturating post-decimate leaves roughly
        // 5x the energy in it.
        val lo = (2_000 / binHz).toInt()
        val hi = (8_500 / binHz).toInt()
        fun bandEnergy(spectrum: FloatArray) = (lo..hi).sumOf { (spectrum[it] * spectrum[it]).toDouble() }
        val correctEnergy = bandEnergy(correctSpectrum)
        val buggyEnergy = bandEnergy(buggySpectrum)
        assertTrue(
            buggyEnergy > correctEnergy * 3,
            "saturating after decimate should leave detectably more aliased energy in a band the " +
                "clean tone has none of: correct=$correctEnergy buggy=$buggyEnergy",
        )
    }
}
