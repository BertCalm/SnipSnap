package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // ---- the smear --------------------------------------------------------

    /** The STN fixture again: a held 400 Hz tone, four broadband bursts, hiss under all. */
    private fun toneClicksHiss(): Pair<Snip, List<Int>> {
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
        return Snip(mix, 1, rate) to clickAt
    }

    private fun energy(s: FloatArray, from: Int, to: Int): Double {
        var acc = 0.0
        for (i in from until to) acc += s[i] * s[i].toDouble()
        return acc
    }

    /**
     * How much louder the click windows are than the same-length windows
     * 100 ms later, where only the tone and the hiss play: 1 = no click
     * left to hear, larger = the bursts still stand out.
     */
    private fun clickProminence(s: FloatArray, clickAt: List<Int>): Double {
        val win = (0.012f * rate).toInt()
        val later = (0.1f * rate).toInt()
        val inWindows = clickAt.sumOf { energy(s, it - win / 4, it + win) }
        val control = clickAt.sumOf { energy(s, it + later - win / 4, it + later + win) }
        return inWindows / control
    }

    @Test
    fun `smear at zero is the input itself, not a copy`() {
        val (mix, _) = toneClicksHiss()
        assertTrue(Separate.smear(mix, 0f) === mix, "amount 0 hands back the very same object")
    }

    @Test
    fun `smear takes the clicks out and keeps the tone and the hiss`() {
        val (mix, clickAt) = toneClicksHiss()
        val smeared = Separate.smear(mix, 1f)
        assertEquals(mix.frameCount, smeared.frameCount, "length is untouched")

        // The bursts were the only verticals: they stop standing out of the wash.
        val before = clickProminence(mix.samples, clickAt)
        val after = clickProminence(smeared.samples, clickAt)
        assertTrue(before > 1.5, "the fixture's clicks are audible to begin with: $before")
        assertTrue(after - 1.0 < 0.25 * (before - 1.0), "the clicks are gone: prominence $before -> $after")

        // The tone survives, within the makeup's reach.
        val toneIn = probe(mix.samples, 400f)
        val toneOut = probe(smeared.samples, 400f)
        assertTrue(toneOut > 0.5f * toneIn && toneOut < 4.5f * toneIn, "the held tone is the wash: $toneIn -> $toneOut")

        // So does the hiss - probed in a top band away from any burst.
        fun hiBand(s: FloatArray): Float {
            val seg = s.copyOfRange((0.5f * rate).toInt(), (0.7f * rate).toInt())
            return CaptureDoctor.goertzel(seg, seg.size, 9000f, rate)
        }
        assertTrue(hiBand(smeared.samples) > 0.5f * hiBand(mix.samples), "the hiss is kept")
    }

    @Test
    fun `smear is graded - half the amount leaves more of the attack`() {
        val (mix, clickAt) = toneClicksHiss()
        val half = clickProminence(Separate.smear(mix, 0.5f).samples, clickAt)
        val full = clickProminence(Separate.smear(mix, 1f).samples, clickAt)
        val none = clickProminence(mix.samples, clickAt)
        assertTrue(full < half && half < none, "monotonic in amount: $none > $half > $full")
    }

    @Test
    fun `a steady tone has no attack to lose - the smear leaves it alone`() {
        val n = 2 * rate
        val tone = Snip(FloatArray(n) { i -> (0.4 * Math.sin(2.0 * Math.PI * 330.0 * i / rate)).toFloat() }, 1, rate)
        val smeared = Separate.smear(tone, 1f)
        // Judge the steady middle: the STFT's own edges are the only verticals here.
        var worst = 0f
        for (i in (0.1f * rate).toInt() until (1.9f * rate).toInt()) {
            val d = Math.abs(smeared.samples[i] - tone.samples[i])
            if (d > worst) worst = d
        }
        assertTrue(worst < 0.04f, "a held tone passes through: worst diff $worst of a 0.4 peak")
    }

    @Test
    fun `the makeup is capped - a bare click does not become full-scale residue`() {
        val n = rate / 2
        val click = FloatArray(n)
        val burst = java.util.Random(9)
        for (i in 0 until 60) click[n / 3 + i] = (burst.nextFloat() * 2f - 1f) * 0.9f
        val smeared = Separate.smear(Snip(click, 1, rate), 1f)
        var peak = 0f
        for (v in smeared.samples) if (Math.abs(v) > peak) peak = Math.abs(v)
        assertTrue(peak < 0.9f, "an attack with no wash behind it is not shouted back up: peak $peak")
    }

    @Test
    fun `the smear is deterministic and stereo-safe`() {
        val (mono, _) = toneClicksHiss()
        val stereo = Snip(FloatArray(mono.samples.size * 2) { mono.samples[it / 2] }, 2, rate)
        val a = Separate.smear(stereo, 0.8f)
        val b = Separate.smear(stereo, 0.8f)
        assertTrue(a.samples.contentEquals(b.samples), "same input, same bytes")
        assertEquals(2, a.channels)
        for (f in 0 until a.frameCount step 997) {
            assertEquals(a.samples[f * 2], a.samples[f * 2 + 1], 1e-6f, "identical channels smear identically")
        }
    }

    // ---- the ghost --------------------------------------------------------

    @Test
    fun `ghost keeps the hiss and loses both the tone and the clicks`() {
        val (mix, clickAt) = toneClicksHiss()
        assertTrue(Separate.ghost(mix, 0f) === mix, "amount 0 hands back the very same object")
        val ghost = Separate.ghost(mix, 1f)
        assertEquals(mix.frameCount, ghost.frameCount)

        // The tone is gone: its line is far down relative to the hiss around it.
        val toneIn = probe(mix.samples, 400f)
        val toneOut = probe(ghost.samples, 400f)
        assertTrue(toneOut < 0.1f * toneIn, "the held tone is not a ghost: $toneIn -> $toneOut")

        // The clicks are gone: they stop standing out of the wash.
        val before = clickProminence(mix.samples, clickAt)
        val after = clickProminence(ghost.samples, clickAt)
        assertTrue(after - 1.0 < 0.25 * (before - 1.0), "the clicks are not a ghost: $before -> $after")

        // The hiss is what's left, and it's been brought up to be heard.
        fun hiBand(s: FloatArray): Float {
            val seg = s.copyOfRange((0.5f * rate).toInt(), (0.7f * rate).toInt())
            return CaptureDoctor.goertzel(seg, seg.size, 9000f, rate)
        }
        assertTrue(hiBand(ghost.samples) > hiBand(mix.samples), "the breath is kept, and heard")
        var peak = 0f
        for (v in ghost.samples) peak = maxOf(peak, Math.abs(v))
        assertTrue(peak <= mix.peak() * 1.001f, "never above the source's own peak")
    }

    @Test
    fun `the banded smear leaves everything under the floor alone`() {
        val (mix, clickAt) = toneClicksHiss()
        // A floor at 1 kHz: the 400 Hz tone's bins are untouched, the broadband clicks above it still go.
        val skimmed = Separate.smear(mix, 1f, aboveHz = 1000f)
        val full = Separate.smear(mix, 1f)
        val toneIn = probe(mix.samples, 400f)
        assertEquals(toneIn, probe(skimmed.samples, 400f), toneIn * 0.05f, "the tone under the floor is as it was")
        assertTrue(clickProminence(skimmed.samples, clickAt) < clickProminence(mix.samples, clickAt), "the clicks above the floor still go")
        assertTrue(!skimmed.samples.contentEquals(full.samples), "a floor is not the full smear")
        assertFailsWith<IllegalArgumentException> { Separate.smear(mix, 1f, aboveHz = -1f) }
    }
}
