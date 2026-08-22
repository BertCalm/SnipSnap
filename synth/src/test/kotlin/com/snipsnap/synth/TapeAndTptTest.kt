package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TapeAndTptTest {

    // ---------- the trapezoidal SVF ----------

    @Test
    fun `TptSvf is stable where the Chamberlin died`() {
        // The exact conditions that used to render NaN silence: cutoff way
        // past 7 kHz with heavy resonance, swept fast.
        val svf = Dsp.TptSvf()
        var peak = 0f
        for (i in 0 until 44_100) {
            val input = sin(2.0 * PI * 220.0 * i / 44_100).toFloat()
            val fc = 2_000f + 14_000f * (i / 44_100f)
            svf.process(input, fc, 0.15f)
            assertTrue(svf.low.isFinite(), "blew up at sample $i (fc=$fc)")
            val a = abs(svf.low)
            if (a > peak) peak = a
        }
        assertTrue(peak < 20f, "resonant but bounded, got peak $peak")
    }

    @Test
    fun `TptSvf actually filters`() {
        // 200 Hz through a 2 kHz low-pass passes; 8 kHz is attenuated hard.
        fun passThrough(toneHz: Double): Float {
            val svf = Dsp.TptSvf()
            var peak = 0f
            for (i in 0 until 22_050) {
                svf.process(sin(2.0 * PI * toneHz * i / 44_100).toFloat(), 2_000f, 1f)
                if (i > 4_410) { val a = abs(svf.low); if (a > peak) peak = a }
            }
            return peak
        }
        assertTrue(passThrough(200.0) > 0.9f, "passband should pass")
        assertTrue(passThrough(8_000.0) < 0.15f, "stopband should stop")
    }

    @Test
    fun `VELVET's filter now opens all the way and stays clean`() {
        for (voice in VelvetVoice.entries) {
            val bright = Velvet.render(voice, mapOf("CUTOFF" to 1f, "SQUEEZE" to 1f))
            assertTrue(bright.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke at full tilt")
            assertTrue(bright.peak() > 0.5f, "$voice went quiet at full tilt")
        }
        // And the ceiling audibly moved: full CUTOFF is brighter than the
        // old 5.2 kHz cap could ever be.
        val open = FeatureExtractor.extract(Velvet.render(VelvetVoice.CHIP, mapOf("CUTOFF" to 1f, "SQUEEZE" to 0f)))
        val dark = FeatureExtractor.extract(Velvet.render(VelvetVoice.CHIP, mapOf("CUTOFF" to 0.1f, "SQUEEZE" to 0f)))
        assertTrue(open.centroidHz > dark.centroidHz * 2f, "CUTOFF range should be audible: ${dark.centroidHz} -> ${open.centroidHz}")
    }

    // ---------- TAPE ----------

    private val snare = Thump.render(ThumpVoice.SNARE)

    @Test
    fun `WOBBLE bends pitch like a warped capstan`() {
        val tone = Snip(
            FloatArray(44_100) { (0.6 * sin(2.0 * PI * 440.0 * it / 44_100)).toFloat() },
            1, 44_100,
        )
        val steady = Tape.process(tone, mapOf("WOBBLE" to 0f, "DRIVE" to 0f, "AGE" to 0f))
        val warped = Tape.process(tone, mapOf("WOBBLE" to 1f, "DRIVE" to 0f, "AGE" to 0f))
        // Steady tape keeps the pitch; wobbled tape audibly modulates it,
        // which autocorrelation reads as a moved/blurred period.
        val steadyPitch = assertNotNull(Pitch.detect(steady))
        assertTrue(abs(steadyPitch.hz - 440f) < 5f, "no wobble, no detune: ${steadyPitch.hz}")
        var diff = 0.0
        var level = 0.0
        for (i in steady.samples.indices) {
            diff += abs((steady.samples[i] - warped.samples[i]).toDouble())
            level += abs(steady.samples[i].toDouble())
        }
        assertTrue(diff > level * 0.3, "full WOBBLE should audibly move the signal")
    }

    @Test
    fun `AGE wears the head down`() {
        val fresh = FeatureExtractor.extract(Tape.process(snare, mapOf("AGE" to 0f, "WOBBLE" to 0f)))
        val worn = FeatureExtractor.extract(Tape.process(snare, mapOf("AGE" to 1f, "WOBBLE" to 0f)))
        assertTrue(
            worn.centroidHz < fresh.centroidHz * 0.7f,
            "AGE should darken: ${fresh.centroidHz} -> ${worn.centroidHz}",
        )
    }

    @Test
    fun `DRIVE saturates without shouting`() {
        val clean = Tape.process(snare, mapOf("DRIVE" to 0f, "WOBBLE" to 0f, "AGE" to 0f))
        val hot = Tape.process(snare, mapOf("DRIVE" to 1f, "WOBBLE" to 0f, "AGE" to 0f))
        assertTrue(abs(clean.peak() - hot.peak()) < 0.05f, "peak-matched: character, not loudness")
        var diff = 0.0
        for (i in clean.samples.indices) diff += abs((clean.samples[i] - hot.samples[i]).toDouble())
        assertTrue(diff / clean.samples.size > 0.005, "full DRIVE should be audible")
    }

    @Test
    fun `a taped kick is still a kick and the chain carries tape`() {
        val kick = Thump.render(ThumpVoice.KICK)
        assertEquals(DrumClass.KICK, Classifier.classify(Tape.process(kick)).drumClass)

        val chain = FxChain(tape = mapOf("WOBBLE" to 0.6f, "AGE" to 0.7f))
        val back = FxChain.fromJsonText(chain.toJsonText())
        assertEquals(chain, back)
        assertTrue(back.process(kick).samples.contentEquals(chain.process(kick).samples))
        assertEquals(DrumClass.KICK, Classifier.classify(chain.process(kick)).drumClass)
    }

    @Test
    fun `TAPE is deterministic, clean and stereo-safe on any roll`() {
        assertTrue(Tape.process(snare).samples.contentEquals(Tape.process(snare).samples))
        repeat(6) { seed ->
            val out = Tape.process(snare, Tape.scramble(Random(seed)))
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "roll $seed broke")
            assertTrue(abs(out.peak() - snare.peak()) < 0.05f, "roll $seed changed loudness")
        }
        val stereo = Snip(FloatArray(snare.frameCount * 2) { snare.samples[it / 2] }, 2, 44_100)
        val out = Tape.process(stereo)
        assertEquals(2, out.channels)
        for (f in 0 until out.frameCount) {
            assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "channels diverged at $f")
        }
    }
}
