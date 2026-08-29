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
    fun `stn dissects the anatomy - tone to sines, clicks to transients, hiss to noise`() {
        // A held 400 Hz tone, four short broadband bursts, hiss under all.
        val rnd = java.util.Random(3)
        val n = 2 * rate
        val mix = FloatArray(n) { i ->
            (0.3 * Math.sin(2.0 * Math.PI * 400.0 * i / rate)).toFloat() +
                (rnd.nextFloat() * 2f - 1f) * 0.05f
        }
        val clickAt = listOf(0.4f, 0.8f, 1.2f, 1.6f).map { (it * rate).toInt() }
        val burst = java.util.Random(4)
        for (at in clickAt) {
            for (i in 0 until 90) mix[at + i] += (burst.nextFloat() * 2f - 1f) * 0.8f
        }
        val stn = Separate.stn(Snip(mix, 1, rate))

        // Nothing lost: the three parts sum back to the input.
        var worst = 0f
        for (i in mix.indices) {
            val d = Math.abs(stn.sines.samples[i] + stn.transients.samples[i] + stn.noise.samples[i] - mix[i])
            if (d > worst) worst = d
        }
        assertTrue(worst < 2e-4f, "three masks, one whole: worst $worst")

        // The tone lives in sines.
        assertTrue(
            probe(stn.sines.samples, 400f) > 5 * probe(stn.transients.samples, 400f) &&
                probe(stn.sines.samples, 400f) > 5 * probe(stn.noise.samples, 400f),
            "the held tone is sines",
        )
        // The bursts live in transients: most of that part's energy sits
        // inside the click windows, the only verticals in the fixture.
        fun energy(s: FloatArray, from: Int, to: Int): Double {
            var acc = 0.0
            for (i in from until to) acc += s[i] * s[i].toDouble()
            return acc
        }
        val win = (0.012f * rate).toInt()
        val inWindows = clickAt.sumOf { energy(stn.transients.samples, it - win / 4, it + win) }
        val total = energy(stn.transients.samples, 0, n)
        assertTrue(inWindows > 0.5 * total, "the transients part is made of the bursts: ${inWindows / total}")

        // The hiss lives in noise - probe a top band away from any burst.
        fun hiBand(s: FloatArray): Float {
            val seg = s.copyOfRange((0.5f * rate).toInt(), (0.7f * rate).toInt())
            return CaptureDoctor.goertzel(seg, seg.size, 9000f, rate)
        }
        assertTrue(
            hiBand(stn.noise.samples) > 3 * hiBand(stn.sines.samples) &&
                hiBand(stn.noise.samples) > 3 * hiBand(stn.transients.samples),
            "the hiss is noise",
        )
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
