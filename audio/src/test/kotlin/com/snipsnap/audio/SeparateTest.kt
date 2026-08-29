package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertTrue

class SeparateTest {

    private val rate = 44_100

    private fun drums(): FloatArray {
        val total = FloatArray(3 * rate)
        val plan = listOf(
            0.05f to DrumSynth.kick(),
            0.55f to DrumSynth.closedHat(),
            1.05f to DrumSynth.snare(),
            1.55f to DrumSynth.closedHat(),
            2.05f to DrumSynth.snare(),
            2.55f to DrumSynth.closedHat(),
        )
        for ((at, hit) in plan) {
            val start = (at * rate).toInt()
            for (i in hit.samples.indices) {
                val idx = start + i
                if (idx < total.size) total[idx] += hit.samples[i] * 0.8f
            }
        }
        return total
    }

    private fun chord(): FloatArray = FloatArray(3 * rate) { i ->
        var s = 0.0
        for (hz in doubleArrayOf(220.0, 277.18, 329.63)) {
            s += Math.sin(2.0 * Math.PI * hz * i / rate)
        }
        (0.15 * s).toFloat()
    }

    private fun probe(samples: FloatArray, hz: Float): Float =
        CaptureDoctor.goertzel(samples, samples.size, hz, rate)

    @Test
    fun `the split loses nothing - the parts sum back to the input`() {
        val d = drums()
        val c = chord()
        val mix = Snip(FloatArray(d.size) { d[it] + c[it] }, 1, rate)
        val split = Separate.hpss(mix)
        var worst = 0f
        for (i in mix.samples.indices) {
            val diff = Math.abs(split.harmonic.samples[i] + split.percussive.samples[i] - mix.samples[i])
            if (diff > worst) worst = diff
        }
        assertTrue(worst < 2e-4f, "masks sum to one, so the parts sum to the input: worst $worst")
    }

    @Test
    fun `notes go harmonic, hits go percussive`() {
        val d = drums()
        val c = chord()
        val mix = Snip(FloatArray(d.size) { d[it] + c[it] }, 1, rate)
        val split = Separate.hpss(mix)

        // The chord's own lines live in the harmonic part.
        val toneH = probe(split.harmonic.samples, 220f) + probe(split.harmonic.samples, 329.63f)
        val toneP = probe(split.percussive.samples, 220f) + probe(split.percussive.samples, 329.63f)
        assertTrue(toneH > 5 * toneP, "the chord goes harmonic: $toneH vs $toneP")

        // The hats' verticals live in the percussive part - probe the top
        // band during a hat window.
        fun hatBand(s: FloatArray): Float {
            val seg = s.copyOfRange((0.55f * rate).toInt(), (0.62f * rate).toInt())
            return CaptureDoctor.goertzel(seg, seg.size, 8000f, rate)
        }
        assertTrue(
            hatBand(split.percussive.samples) > 5 * hatBand(split.harmonic.samples),
            "the hat goes percussive",
        )

        // Noisy drums land overwhelmingly percussive. (A boomy kick's
        // sub is a held tone - a horizontal line - and honestly reads
        // harmonic; the vertical promise is about attacks and noise.)
        val snappy = FloatArray(3 * rate)
        for ((at, hit) in listOf(0.2f to DrumSynth.closedHat(), 0.9f to DrumSynth.snare(), 1.6f to DrumSynth.closedHat(), 2.3f to DrumSynth.clap())) {
            val start = (at * rate).toInt()
            for (i in hit.samples.indices) {
                val idx = start + i
                if (idx < snappy.size) snappy[idx] += hit.samples[i] * 0.8f
            }
        }
        val dOnly = Separate.hpss(Snip(snappy, 1, rate))
        fun energy(s: FloatArray): Double = s.sumOf { (it * it).toDouble() }
        assertTrue(
            energy(dOnly.percussive.samples) > 2 * energy(dOnly.harmonic.samples),
            "hats, snares and claps read as drums",
        )
        val cOnly = Separate.hpss(Snip(c, 1, rate))
        assertTrue(
            energy(cOnly.harmonic.samples) > 8 * energy(cOnly.percussive.samples),
            "a chord reads as music",
        )
    }
}
