package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RushTest {
    private val rate = 44_100

    private fun tone(hz: Double = 347.0, seconds: Double = .3, gain: Float = .3f, channels: Int = 1): Snip {
        val frames = (seconds * rate).toInt()
        return Snip(FloatArray(frames * channels) { i ->
            val f = i / channels
            val edge = minOf(1.0, f / 220.0, (frames - 1 - f) / 220.0).coerceAtLeast(0.0)
            val sample = (gain * edge * sin(2.0 * PI * hz * f / rate)).toFloat()
            if (channels == 2 && i % 2 == 1) -sample else sample
        }, channels, rate)
    }

    private fun stage(name: String, amount: Float = 1f) = Rush.stages(amount).single { it.name == name }

    private fun rms(snip: Snip, from: Double, to: Double, channel: Int = 0): Double {
        val start = (from * snip.sampleRate).toInt()
        val end = minOf(snip.frameCount, (to * snip.sampleRate).toInt())
        var sum = 0.0
        for (f in start until end) {
            val sample = snip.samples[f * snip.channels + channel].toDouble()
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / (end - start))
    }

    @Test
    fun `zero hard bypass preserves signed zero and empty source while invalid amounts refuse`() {
        val snip = Snip(floatArrayOf(-0f, .4f, -.1f, 0f), 2, rate)
        val bits = snip.samples.map { it.toRawBits() }
        assertSame(snip, Rush.process(snip, 0f))
        assertEquals(bits, snip.samples.map { it.toRawBits() })
        val empty = Snip(FloatArray(0), 1, rate)
        assertSame(empty, Rush.process(empty, 1f))
        for (amount in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.01f, 1.01f)) {
            assertFailsWith<IllegalArgumentException> { Rush.process(snip, amount) }
            assertFailsWith<IllegalArgumentException> { Rush.stages(amount) }
        }
        assertFailsWith<IllegalArgumentException> { Rush.process(Snip(floatArrayOf(Float.NaN), 1, rate), .7f) }
    }

    @Test
    fun `the compound stages run in the expressive lead order`() {
        assertTrue(Rush.stages(0f).isEmpty())
        assertEquals(listOf("Auto-Swell", "Ring", "Distortion", "Envelope Filter", "Pitched Echo"), Rush.stages(1f).map { it.name })
        val source = tone()
        var staged = source
        for (stage in Rush.stages(.7f)) staged = stage.run(staged, stage.macros)
        assertContentEquals(staged.samples, Rush.process(source, .7f).samples)
    }

    @Test
    fun `all audition intensities widen mono retain stereo and stay finite without mutating source`() {
        for (amount in listOf(.35f, .7f, 1f)) for (channels in listOf(1, 2)) {
            val source = tone(channels = channels)
            val before = source.samples.copyOf()
            val out = Rush.process(source, amount)
            assertEquals(2, out.channels)
            assertEquals(rate, out.sampleRate)
            assertTrue(out.samples.all { it.isFinite() })
            assertTrue(out.frameCount > source.frameCount)
            assertTrue(out.frameCount <= source.frameCount + (Rush.MAX_TAIL_SECONDS * rate).toInt())
            assertTrue(out.peak() <= source.peak() + 1e-6f, "new stages must not raise the source peak")
            assertTrue(out.samples.indices.any { it % 2 == 0 && out.samples[it] != out.samples[it + 1] })
            assertEquals(0f, out.samples.last())
            assertContentEquals(before, source.samples)
        }
        val mono = tone()
        val leftOnly = Snip(FloatArray(mono.frameCount * 2) { if (it % 2 == 0) mono.samples[it / 2] else 0f }, 2, rate)
        val out = Rush.process(leftOnly, .7f)
        assertTrue(out.samples.indices.all { it % 2 == 0 || out.samples[it] == 0f }, "stereo input must not be folded and widened again")
    }

    @Test
    fun `render state resets and silence stays silent at both device rates`() {
        for (sampleRate in listOf(44_100, 48_000)) {
            val source = tone().let { Snip(it.samples, it.channels, sampleRate) }
            val first = Rush.process(source, .7f)
            val silence = Rush.process(Snip(FloatArray(1000), 1, sampleRate), 1f)
            assertTrue(silence.samples.all { it.isFinite() && it == 0f })
            assertContentEquals(first.samples, Rush.process(source, .7f).samples)
        }
    }

    @Test
    fun `auto swell fades the existing hit in place and rearms after a quiet gap`() {
        val frames = (rate * .95).toInt()
        val source = Snip(FloatArray(frames) { f ->
            if (f < rate * .3 || f >= rate * .65) .4f else 0f
        }, 1, rate)
        val s = stage("Auto-Swell")
        val out = s.run(source, s.macros)
        assertEquals(source.frameCount, out.frameCount, "this is a gain swell, not a prepended wash")
        assertTrue(rms(out, .001, .006) < .08)
        assertTrue(rms(out, .15, .2) > .39)
        assertTrue(rms(out, .651, .656) < .08, "a later onset must swell again")
        assertTrue(rms(out, .80, .85) > .39)
        assertTrue(out.samples.indices.all { abs(out.samples[it]) <= abs(source.samples[it]) })
    }

    @Test
    fun `filter follows later input energy and linked antiphase rather than a timed contour`() {
        val source = Snip(FloatArray((rate * .7).toInt()) { f ->
            when { f < rate * .2 -> .04f; f < rate * .4 -> .4f; else -> .04f }
        }, 1, rate)
        val s = stage("Envelope Filter")
        val cutoffs = Rush.filterCutoffs(source, s.macros)
        fun hz(seconds: Double) = cutoffs[(seconds * rate).toInt()]
        assertTrue(hz(.35) > hz(.15) * 5f, "a later louder event must open the filter")
        assertTrue(hz(.65) < hz(.35) * .5f, "the follower must recover after the event")
        val stereo = Snip(FloatArray(source.frameCount * 2) { i -> source.samples[i / 2] * if (i % 2 == 0) 1f else -1f }, 2, rate)
        assertContentEquals(cutoffs, Rush.filterCutoffs(stereo, s.macros), "antiphase audio must not cancel the control envelope")
        val quiet = Snip(FloatArray(source.frameCount) { source.samples[it] * .125f }, 1, rate)
        val quietCutoffs = Rush.filterCutoffs(quiet, s.macros)
        assertTrue(cutoffs.indices.all { abs(cutoffs[it] - quietCutoffs[it]) < .01f }, "the documented envelope sensitivity is peak-relative")
    }

    @Test
    fun `filter audio path attenuates above a fixed cutoff while keeping low audio`() {
        val s = stage("Envelope Filter")
        // A full-level steady tone opens the peak-relative follower: with
        // the live depth, this fixture settles around 8.4 kHz rather than
        // 650 Hz. Hold cutoff fixed here to test the SVF's actual audio
        // path; the separate later-onset test checks its input control.
        val fixed = s.macros + mapOf("BASE_HZ" to 650f, "DEPTH_OCT" to 0f)
        val lowSource = tone(160.0, .4)
        val low = s.run(lowSource, fixed)
        val high = s.run(tone(9_000.0, .4), fixed)
        val lowRms = rms(low, .15, .3)
        val highRms = rms(high, .15, .3)
        assertTrue(lowRms > rms(lowSource, .15, .3) * .9, "the low note should remain in the passband")
        assertTrue(highRms < lowRms * .03, "650 Hz low-pass: 9 kHz RMS $highRms against 160 Hz RMS $lowRms")
    }

    @Test
    fun `dual head shifter moves a noncommensurate tone upward seven semitones`() {
        val source = tone(347.0, .8)
        assertSame(source, Rush.pitchShift(source, 0f))
        val shifted = Rush.pitchShift(source, 7f)
        val estimate = assertNotNull(Pitch.detect(shifted, .15f, .35f))
        val expected = 347.0 * 2.0.pow(7.0 / 12)
        val cents = 1200.0 * ln(estimate.hz / expected) / ln(2.0)
        assertTrue(abs(cents) < 20.0, "+7 expected $expected Hz; got ${estimate.hz} Hz ($cents cents)")
        assertTrue(estimate.confidence > .7f)
    }

    @Test
    fun `head wraps preserve DC gain and do not introduce waveform jumps`() {
        val dc = Snip(FloatArray(rate) { .25f }, 1, rate)
        val shifted = Rush.pitchShift(dc, 7f)
        for (f in rate / 10 until rate * 9 / 10) assertEquals(.25f, shifted.samples[f], 1e-6f)
        val sine = Rush.pitchShift(tone(73.21, .8, .25f), 7f)
        var jump = 0f
        for (f in rate / 10 until rate * 7 / 10) jump = maxOf(jump, abs(sine.samples[f] - sine.samples[f - 1]))
        assertTrue(jump < .012f, "a wrapped readhead introduced a $jump sample jump")
    }

    @Test
    fun `echo has no early repeats and alternating sides contain the stated intervals`() {
        val echo = stage("Pitched Echo")
        val impulse = Snip(FloatArray(rate / 100) { if (it == 0) .3f else 0f }, 1, rate)
        val out = echo.run(impulse, echo.macros)
        val delay = (Rush.DELAY_SECONDS * rate).toInt()
        assertTrue((1 until delay).all { out.samples[it * 2] == 0f && out.samples[it * 2 + 1] == 0f })
        assertTrue(out.samples[delay * 2] != 0f, "the first unison echo must start on the 240 ms grid")
        assertTrue(out.frameCount <= impulse.frameCount + (Rush.MAX_TAIL_SECONDS * rate).toInt())

        // A short burst leaves an empty interval between taps: measuring each
        // tap therefore cannot accidentally meter the unpitched dry voice.
        val burst = tone(400.0, .10)
        val repeats = echo.run(burst, echo.macros)
        fun frequency(tap: Int, channel: Int): Double {
            val start = tap * delay + (.045 * rate).toInt()
            val frames = (.035 * rate).toInt()
            val window = FloatArray(frames) { f -> repeats.samples[(start + f) * 2 + channel] }
            // A narrow DFT search is independent of the shifter's readhead state.
            var bestHz = 0.0
            var bestPower = 0.0
            for (hz in 350..650) {
                var re = 0.0; var im = 0.0
                for (i in window.indices) {
                    val w = .5 - .5 * cos(2.0 * PI * i / (window.size - 1))
                    val angle = 2.0 * PI * hz * i / rate
                    re += window[i] * w * cos(angle); im += window[i] * w * sin(angle)
                }
                val power = re * re + im * im
                if (power > bestPower) { bestPower = power; bestHz = hz.toDouble() }
            }
            return bestHz
        }
        assertTrue(abs(frequency(1, 0) - 400.0) < 3)
        assertTrue(abs(frequency(1, 1) - 400.0 * 2.0.pow(7.0 / 12)) < 5)
        assertTrue(abs(frequency(2, 0) - 400.0 * 2.0.pow(7.0 / 12)) < 5)
        assertTrue(abs(frequency(2, 1) - 400.0) < 3)
    }

    @Test
    fun `quiet source stays quiet rather than being levelled to the loud source`() {
        val loud = tone(gain = .5f)
        val scale = 10.0.pow(-18.0 / 20).toFloat()
        val quiet = Snip(FloatArray(loud.samples.size) { loud.samples[it] * scale }, 1, rate)
        val a = Rush.process(loud, .7f)
        val b = Rush.process(quiet, .7f)
        val ratio = rms(b, .04, .22) / rms(a, .04, .22)
        assertEquals(scale.toDouble(), ratio, .0001, "the peak-relative board should retain an overall -18 dB input difference")
    }
}
