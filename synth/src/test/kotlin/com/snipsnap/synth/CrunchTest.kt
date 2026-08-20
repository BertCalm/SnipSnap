package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrunchTest {

    private fun tone(hz: Double, seconds: Float = 0.4f): Snip =
        Snip(
            FloatArray((seconds * 44_100).toInt()) { (0.7 * sin(2.0 * PI * hz * it / 44_100)).toFloat() },
            1, 44_100,
        )

    private val CLEAN = mapOf("BITS" to 0f, "RATE" to 0f, "TONE" to 1f, "GRIT" to 0f)

    @Test
    fun `all-zeros is close to transparent`() {
        val src = tone(440.0)
        val out = Crunch.process(src, CLEAN)
        var maxDiff = 0f
        for (i in src.samples.indices) {
            val d = abs(src.samples[i] - out.samples[i])
            if (d > maxDiff) maxDiff = d
        }
        assertTrue(maxDiff < 0.05f, "clean settings should barely touch the audio, max diff $maxDiff")
    }

    @Test
    fun `BITS adds quantization error monotonically`() {
        val src = tone(220.0)
        fun err(bits: Float): Double {
            val out = Crunch.process(src, CLEAN + mapOf("BITS" to bits))
            var sum = 0.0
            for (i in src.samples.indices) sum += abs(src.samples[i] - out.samples[i]).toDouble()
            return sum / src.samples.size
        }
        val e0 = err(0f); val e5 = err(0.5f); val e1 = err(1f)
        assertTrue(e5 > e0 && e1 > e5, "quantization error should grow with BITS: $e0, $e5, $e1")
    }

    @Test
    fun `RATE puts aliasing where the source had none`() {
        // A pure 1 kHz sine has no high band. Zero-order hold at ~11 kHz
        // folds images into the spectrum - the aliasing IS the character.
        val src = tone(1000.0)
        val before = FeatureExtractor.extract(src)
        val after = FeatureExtractor.extract(Crunch.process(src, CLEAN + mapOf("RATE" to 1f)))
        assertTrue(
            after.highRatio > before.highRatio + 0.01f,
            "hold-rate crunch should add high-band images: ${before.highRatio} -> ${after.highRatio}",
        )
    }

    @Test
    fun `TONE darkens`() {
        val src = Thump.render(ThumpVoice.HAT_CLOSED)
        val open = FeatureExtractor.extract(Crunch.process(src, CLEAN + mapOf("TONE" to 1f)))
        val dark = FeatureExtractor.extract(Crunch.process(src, CLEAN + mapOf("TONE" to 0f)))
        assertTrue(dark.centroidHz < open.centroidHz * 0.8f, "TONE down should darken: ${open.centroidHz} -> ${dark.centroidHz}")
    }

    @Test
    fun `character does not masquerade as loudness`() {
        val src = Thump.render(ThumpVoice.SNARE)
        val out = Crunch.process(src)
        assertTrue(abs(src.peak() - out.peak()) < 0.05f, "peak should be preserved: ${src.peak()} -> ${out.peak()}")
    }

    @Test
    fun `a crunched kick is still a kick, a crunched snare still a snare`() {
        // The whole point of a character pass: identity survives the era.
        val kick = Crunch.process(Thump.render(ThumpVoice.KICK))
        val snare = Crunch.process(Thump.render(ThumpVoice.SNARE))
        assertEquals(DrumClass.KICK, Classifier.classify(kick).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(snare).drumClass)
    }

    @Test
    fun `every corner is clean audio`() {
        val src = Thump.render(ThumpVoice.CLAP)
        for (value in floatArrayOf(0f, 1f)) {
            val out = Crunch.process(src, Crunch.MACROS.associate { it.name to value })
            assertEquals(src.frameCount, out.frameCount)
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f })
            assertTrue(out.peak() > 0.3f, "corner $value went quiet")
        }
    }

    @Test
    fun `scrambled settings stay playable and reproducible`() {
        val src = Thump.render(ThumpVoice.TOM)
        assertEquals(Crunch.scramble(Random(9)), Crunch.scramble(Random(9)))
        repeat(8) { roll ->
            val out = Crunch.process(src, Crunch.scramble(Random(roll)))
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "roll $roll broke")
        }
    }

    @Test
    fun `stereo is processed per channel`() {
        val mono = tone(300.0, 0.1f)
        val stereo = Snip(FloatArray(mono.frameCount * 2) { mono.samples[it / 2] }, 2, 44_100)
        val out = Crunch.process(stereo, CLEAN + mapOf("BITS" to 0.8f))
        assertEquals(2, out.channels)
        assertEquals(stereo.frameCount, out.frameCount)
        // Identical input channels must stay identical after processing.
        for (f in 0 until out.frameCount) {
            assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "channels diverged at frame $f")
        }
    }

    @Test
    fun `is deterministic`() {
        val src = Thump.render(ThumpVoice.COWBELL)
        assertTrue(Crunch.process(src).samples.contentEquals(Crunch.process(src).samples))
    }
}
