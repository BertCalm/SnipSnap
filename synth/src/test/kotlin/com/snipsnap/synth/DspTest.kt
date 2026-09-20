package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Dsp.scrambleNear] — U2 of `docs/SYNTH_UPGRADE.md`: SCRAMBLE rolls near a
 * seed instead of flat across the macro box. Every engine's `scramble()`
 * delegates here, so the boundary contract is proven once, centrally,
 * rather than trusted seven times over.
 */
class DspTest {

    private val seed = mapOf("A" to 0.2f, "B" to 0.5f, "C" to 0.9f)

    @Test
    fun `temperature 0 returns the seed untouched`() {
        assertEquals(seed, Dsp.scrambleNear(seed, 0f, Random(1)))
    }

    @Test
    fun `temperature 1 discards the seed and rolls flat-uniform`() {
        val rolled = Dsp.scrambleNear(seed, 1f, Random(1))
        assertEquals(seed.keys, rolled.keys)
        assertTrue(rolled.values.all { it in 0f..1f })
        // A flat roll owes the seed nothing - matching values would be
        // astronomically unlikely across three independent draws.
        assertTrue(rolled.values.toList() != seed.values.toList())
    }

    @Test
    fun `temperature out of range clamps to the same bounds`() {
        assertEquals(Dsp.scrambleNear(seed, 0f, Random(1)), Dsp.scrambleNear(seed, -0.5f, Random(1)))
        assertEquals(Dsp.scrambleNear(seed, 1f, Random(1)), Dsp.scrambleNear(seed, 1.5f, Random(1)))
    }

    @Test
    fun `same seed and random roll the same patch`() {
        assertEquals(Dsp.scrambleNear(seed, 0.35f, Random(7)), Dsp.scrambleNear(seed, 0.35f, Random(7)))
    }

    @Test
    fun `a mid temperature roll stays bounded and usually stays close to the seed`() {
        repeat(50) { roll ->
            val rolled = Dsp.scrambleNear(seed, 0.35f, Random(roll))
            assertTrue(rolled.values.all { it in 0f..1f }, "roll $roll left the 0..1 range")
        }
    }

    @Test
    fun `a low temperature rolls closer to the seed on average than a high one`() {
        fun meanAbsDelta(temperature: Float): Float {
            var total = 0f
            var n = 0
            repeat(200) { roll ->
                val rolled = Dsp.scrambleNear(seed, temperature, Random(roll))
                for ((k, v) in seed) { total += kotlin.math.abs(rolled.getValue(k) - v); n++ }
            }
            return total / n
        }
        val low = meanAbsDelta(0.1f)
        val high = meanAbsDelta(0.9f)
        assertTrue(low < high, "low-temperature rolls ($low) should land closer to the seed than high-temperature rolls ($high)")
    }

    // ---------- Dsp.limitPeak (U3, docs/SYNTH_UPGRADE.md) ----------
    // A clipping safety net, not a level target: unlike normalize, it must
    // leave an already-safe buffer's level exactly alone.

    @Test
    fun `limitPeak leaves a buffer under the ceiling untouched`() {
        val buf = floatArrayOf(0.1f, -0.3f, 0.5f, -0.2f)
        val original = buf.copyOf()
        Dsp.limitPeak(buf, ceiling = 1f)
        assertTrue(original.contentEquals(buf), "a peak already under the ceiling must not be rescaled")
    }

    @Test
    fun `limitPeak rescales an over-ceiling buffer down to exactly the ceiling`() {
        val buf = floatArrayOf(0.5f, -2f, 1f)
        Dsp.limitPeak(buf, ceiling = 1f)
        val peak = buf.maxOf { kotlin.math.abs(it) }
        assertEquals(1f, peak, 1e-5f)
        // A uniform rescale: every sample keeps its share of the original peak.
        assertEquals(0.25f, buf[0], 1e-5f)
    }

    @Test
    fun `limitPeak leaves silence alone`() {
        val buf = FloatArray(8)
        Dsp.limitPeak(buf, ceiling = 1f)
        assertTrue(buf.all { it == 0f }, "silence has no peak to rescale from")
    }

    // ---------- Dsp.decimate (U6, docs/SYNTH_UPGRADE.md) ----------
    // The whole point of rendering oversampled: content above the target
    // rate's Nyquist must actually be rejected here, not just resized away.
    // A regression to a single direct 4:1 Resampler.resample call (instead
    // of the two cascaded 2x steps) would still pass every other synth
    // test in this build - it only shows up as weaker rejection right
    // above the new Nyquist, which is exactly what these measure.

    private fun sine(hz: Double, seconds: Float, rate: Int, amp: Float = 0.5f): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i -> (amp * kotlin.math.sin(2.0 * kotlin.math.PI * hz * i / rate)).toFloat() }
    }

    private fun rms(buf: FloatArray): Float =
        kotlin.math.sqrt(buf.sumOf { (it * it).toDouble() } / buf.size).toFloat()

    @Test
    fun `decimate rejects a tone above the target rate's Nyquist more than a single direct step would`() {
        val oversampledRate = Dsp.RATE * Dsp.OVERSAMPLE
        // 30kHz sits above 44.1kHz's 22.05kHz Nyquist but well inside the
        // oversampled rate's own Nyquist (88.2kHz) - exactly the band the
        // render loop is free to produce harmonics into, that decimate then
        // has to remove before the audio ships at RATE.
        val above = sine(30_000.0, seconds = 0.05f, rate = oversampledRate)
        val before = rms(above)
        val cascaded = rms(Dsp.decimate(above.copyOf(), Dsp.RATE))
        // Compared against a single direct 4:1 Resampler.resample call, not
        // a hardcoded ratio: measured for this exact tone, a direct step
        // already rejects it to ~6% of `before` - comfortably under a loose
        // fixed threshold like 0.3x, so a regression to the single-step
        // approach this PR chose against would pass a threshold-only
        // version of this test. The cascaded approach has to actually beat
        // that, not just clear an arbitrary bar.
        val direct = rms(
            com.snipsnap.audio.Resampler.resample(
                com.snipsnap.audio.Snip(above.copyOf(), channels = 1, sampleRate = oversampledRate),
                Dsp.RATE,
            ).samples,
        )
        assertTrue(
            cascaded < direct * 0.5f,
            "two cascaded 2x steps should reject a 30kHz tone well below a single direct 4:1 step: " +
                "cascaded=$cascaded direct=$direct (before=$before)",
        )
        assertTrue(
            cascaded < before * 0.05f,
            "and reject it outright, not just relatively better than the alternative: $before -> $cascaded",
        )
    }

    @Test
    fun `decimate preserves an in-band tone`() {
        val oversampledRate = Dsp.RATE * Dsp.OVERSAMPLE
        val inBand = sine(1_000.0, seconds = 0.05f, rate = oversampledRate)
        val before = rms(inBand)
        val after = rms(Dsp.decimate(inBand, Dsp.RATE))
        assertTrue(
            kotlin.math.abs(after - before) < before * 0.1f,
            "a 1kHz tone should pass through decimation to 44.1kHz essentially unchanged: $before -> $after",
        )
    }

    @Test
    fun `decimate returns audio at a quarter the sample count`() {
        val oversampledRate = Dsp.RATE * Dsp.OVERSAMPLE
        val buf = sine(1_000.0, seconds = 0.05f, rate = oversampledRate)
        val decimated = Dsp.decimate(buf, Dsp.RATE)
        val expected = buf.size / Dsp.OVERSAMPLE
        assertTrue(
            kotlin.math.abs(decimated.size - expected) <= 2,
            "decimating by ${Dsp.OVERSAMPLE}x should return about $expected frames, got ${decimated.size}",
        )
    }

    // ---------- Dsp.levelTo ----------
    // The whole reason the melodic engines moved off Dsp.normalize: peak
    // says nothing about how loud a voice actually reads, and a sine's low
    // crest factor was quietly sinking every tonal pad under the drums.
    // levelTo targets Loudness.of instead, with limitPeak as a true-peak
    // backstop so 24-bit export can't clip.

    private fun saw(hz: Double, seconds: Float, rate: Int, amp: Float = 0.2f): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i -> (amp * (2.0 * ((hz * i / rate) % 1.0) - 1.0)).toFloat() }
    }

    private fun noise(seconds: Float, rate: Int, amp: Float, seed: Int): FloatArray {
        val random = Random(seed)
        val n = (seconds * rate).toInt()
        return FloatArray(n) { (random.nextFloat() * 2f - 1f) * amp }
    }

    @Test
    fun `a sine and a saw at the same target land at the same loudness`() {
        val rate = Dsp.RATE
        val sineBuf = sine(220.0, seconds = 0.5f, rate = rate, amp = 0.2f)
        val sawBuf = saw(220.0, seconds = 0.5f, rate = rate, amp = 0.2f)
        Dsp.levelTo(sineBuf, rate, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.levelTo(sawBuf, rate, target = Dsp.MELODIC_LOUDNESS_TARGET)
        val ls = Loudness.of(Snip(sineBuf, 1, rate))
        val lw = Loudness.of(Snip(sawBuf, 1, rate))
        assertTrue(kotlin.math.abs(ls - lw) < 0.02f, "loudness should match within tolerance: sine=$ls saw=$lw")
    }

    @Test
    fun `levelTo never exceeds the ceiling`() {
        val rate = Dsp.RATE
        val hot = FloatArray(1000) { if (it % 2 == 0) 0.9f else -0.9f }
        Dsp.levelTo(hot, rate, target = 0.9f, ceiling = 0.99f)
        assertTrue(hot.all { kotlin.math.abs(it) <= 0.99f + 1e-6f }, "ceiling must hold")
    }

    // A ceiling that fires is only half the story - policy item 4 (this
    // task's brief) requires proving a clamp doesn't silently swallow what
    // it's supposed to guard, the same class of gap that let Task 3's
    // cycles=1.5 defect through a test that only checked the floor was
    // never crossed. These prove levelTo actually LANDS on its target when
    // the ceiling isn't in play (a crest-heavy sine, then dense noise where
    // loudness and peak sit close together), and that when the ceiling
    // genuinely has to intervene, it stops the signal right at the
    // ceiling - not somewhere conservatively short of it.

    @Test
    fun `levelTo reaches its target exactly for a crest-heavy sine, nowhere near the ceiling`() {
        val rate = Dsp.RATE
        val buf = sine(220.0, seconds = 0.5f, rate = rate, amp = 0.2f)
        Dsp.levelTo(buf, rate, target = Dsp.MELODIC_LOUDNESS_TARGET)
        val reached = Loudness.of(Snip(buf, 1, rate))
        assertTrue(
            kotlin.math.abs(reached - Dsp.MELODIC_LOUDNESS_TARGET) < Dsp.MELODIC_LOUDNESS_TARGET * 0.01f,
            "sine should land within 1% of the target: reached=$reached target=${Dsp.MELODIC_LOUDNESS_TARGET}",
        )
        assertTrue(buf.all { kotlin.math.abs(it) < 0.99f }, "a sine at this target shouldn't approach the ceiling")
    }

    @Test
    fun `levelTo reaches its target for dense noise when the ceiling isn't binding`() {
        val rate = Dsp.RATE
        // Noise has almost no crest factor - loudness and peak sit close
        // together - which is exactly the signal shape most likely to make
        // a ceiling clamp fight the target instead of just guarding it.
        val buf = noise(seconds = 0.5f, rate = rate, amp = 0.2f, seed = 1)
        Dsp.levelTo(buf, rate, target = Dsp.MELODIC_LOUDNESS_TARGET)
        val reached = Loudness.of(Snip(buf, 1, rate))
        assertTrue(
            kotlin.math.abs(reached - Dsp.MELODIC_LOUDNESS_TARGET) < Dsp.MELODIC_LOUDNESS_TARGET * 0.05f,
            "noise should land near the target when the ceiling isn't in play: reached=$reached",
        )
    }

    @Test
    fun `a Pluck-like crest-heavy voice at the real melodic target lands pinned to the ceiling, below target`() {
        // Not every voice reaches Dsp.MELODIC_LOUDNESS_TARGET - a shared
        // target across five engines with very different crest factors can
        // pull a loud, dense voice DOWN freely, but can only push a peaky
        // one UP as far as the ceiling allows. This is the actual regime
        // PLUCK's KALIMBA lands in (task-4-report.md): a decaying tone
        // whose loudness sits far under its own peak, the way a plucked
        // string's fast-decaying transient does. Built here instead of
        // rendered via Pluck directly so this stays a pure Dsp proof, not
        // one that could pass or fail because PLUCK's own synthesis changed
        // for unrelated reasons.
        val rate = Dsp.RATE
        val tau = 0.02f
        val n = (0.5f * rate).toInt()
        val buf = FloatArray(n) { i ->
            val t = i.toFloat() / rate
            (kotlin.math.exp(-t / tau) * kotlin.math.sin(2.0 * Math.PI * 440.0 * t)).toFloat()
        }
        val crest = buf.maxOf { kotlin.math.abs(it) } / Loudness.of(Snip(buf.copyOf(), 1, rate))
        Dsp.levelTo(buf, rate, target = Dsp.MELODIC_LOUDNESS_TARGET)
        val peak = buf.maxOf { kotlin.math.abs(it) }
        val reached = Loudness.of(Snip(buf, 1, rate))
        assertTrue(
            kotlin.math.abs(peak - 0.99f) < 1e-3f,
            "a signal this peaky should pin the ceiling exactly, not sit short of it: peak=$peak",
        )
        assertTrue(
            reached < Dsp.MELODIC_LOUDNESS_TARGET,
            "the ceiling should keep this below the shared target, not silently reach it anyway: reached=$reached",
        )
        assertTrue(
            kotlin.math.abs(reached - 0.99f / crest) < Dsp.MELODIC_LOUDNESS_TARGET * 0.02f,
            "the achieved loudness should match ceiling/crest exactly, not over- or under-shoot it: " +
                "reached=$reached expected=${0.99f / crest}",
        )
    }

    @Test
    fun `when the ceiling genuinely has to intervene, it stops right at the ceiling, not short of it`() {
        val rate = Dsp.RATE
        // A target this far above what dense noise can reach at 0.2 peak
        // forces a gain that would clip - the ceiling has to step in.
        val buf = noise(seconds = 0.5f, rate = rate, amp = 0.2f, seed = 2)
        Dsp.levelTo(buf, rate, target = 0.9f, ceiling = 0.99f)
        val peak = buf.maxOf { kotlin.math.abs(it) }
        assertTrue(peak <= 0.99f + 1e-6f, "ceiling must hold: peak=$peak")
        assertTrue(
            kotlin.math.abs(peak - 0.99f) < 1e-3f,
            "the ceiling should let the signal reach right up to it, not clamp conservatively short: peak=$peak",
        )
    }

    // ---------- keyTrack (Task 6) ----------

    @Test
    fun `zero amount leaves cutoff exactly where it was, at any pitch`() {
        assertEquals(1000f, Dsp.keyTrack(1000f, 440f, 110f, 0f), 0.01f)
        assertEquals(1000f, Dsp.keyTrack(1000f, 55f, 110f, 0f), 0.01f)
    }

    @Test
    fun `full tracking keeps cutoff proportional to pitch`() {
        val low = Dsp.keyTrack(cutoffHz = 1000f, baseHz = 110f, referenceHz = 110f, amount = 1f)
        val high = Dsp.keyTrack(cutoffHz = 1000f, baseHz = 220f, referenceHz = 110f, amount = 1f)
        assertEquals(1000f, low, 0.01f, "at the reference pitch, full tracking is a no-op")
        assertEquals(2f, high / low, 0.01f, "an octave up should double the cutoff at full tracking")
    }

    @Test
    fun `partial amount is a log-domain blend, not a linear one`() {
        // Half tracking across an octave should move the cutoff by half an
        // octave (sqrt(2)x), not half the Hz distance to full tracking.
        val half = Dsp.keyTrack(cutoffHz = 1000f, baseHz = 220f, referenceHz = 110f, amount = 0.5f)
        assertEquals(kotlin.math.sqrt(2f), half / 1000f, 0.001f)
    }

    @Test
    fun `amount clamps to 0 to 1`() {
        val atOne = Dsp.keyTrack(1000f, 220f, 110f, 1f)
        assertEquals(atOne, Dsp.keyTrack(1000f, 220f, 110f, 2f), 0.01f)
        assertEquals(1000f, Dsp.keyTrack(1000f, 220f, 110f, -1f), 0.01f)
    }

    @Test
    fun `a non-positive baseHz or referenceHz is refused, not divided by`() {
        assertEquals(1000f, Dsp.keyTrack(1000f, 0f, 110f, 1f))
        assertEquals(1000f, Dsp.keyTrack(1000f, 220f, 0f, 1f))
        assertEquals(1000f, Dsp.keyTrack(1000f, -10f, 110f, 1f))
    }
}
