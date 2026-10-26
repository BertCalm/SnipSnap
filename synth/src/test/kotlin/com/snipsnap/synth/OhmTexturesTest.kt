package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OhmTexturesTest {
    private val rate = Dsp.RATE
    private fun source(frame: Int, hz: Double, rate: Int = this.rate): Float =
        (.20 * sin(2 * PI * hz * frame / rate) + .035 * sin(2 * PI * hz * 3 * frame / rate)).toFloat()

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size.coerceAtLeast(1))

    private fun run(
        helper: OhmTextures,
        frames: Int,
        activity: Float = .85f,
        observed: (Int) -> Float,
    ): Array<FloatArray> {
        val out = Array(2) { FloatArray(frames) }
        val pair = FloatArray(2)
        for (i in 0 until frames) {
            helper.fill(i, activity, .5f, observed(i), pair)
            out[0][i] = pair[0]
            out[1][i] = pair[1]
        }
        return out
    }

    @Test
    fun `all optional sources off are exact zero even with a populated observed bus`() {
        val helper = OhmTextures(OhmOptions(), 60, 731, rate)
        val out = run(helper, rate / 2, observed = { source(it, 261.625565) })
        assertTrue(out.all { channel -> channel.all { it.toRawBits() == 0 } })
        assertEquals(0f, helper.maxOutput)
        assertEquals(0, helper.grainCount)
        assertEquals(0, helper.frozenSnapshotFrames)
    }

    @Test
    fun `grains read written audio only and seeded overlap stays bounded`() {
        for (seconds in listOf(.02f, .08f, .20f)) {
            val options = OhmOptions(grains = 1f, grainSeconds = seconds)
            val first = OhmTextures(options, 60, 731, rate)
            val again = OhmTextures(options, 60, 731, rate)
            val a = FloatArray(2)
            val b = FloatArray(2)
            var previousCount = 0
            for (frame in 0 until rate / 2) {
                val sample = source(frame, 261.625565)
                first.fill(frame, 1f, 0f, sample, a)
                again.fill(frame, 1f, 1f, sample, b)
                assertContentEquals(a, b, "the bridge relationship must not secretly alter source energy")
                if (first.grainCount != previousCount) {
                    assertTrue(first.lastGrainSourceFromFrame >= maxOf(0, frame - first.historyFrames))
                    assertTrue(first.lastGrainSourceUntilFrame <= frame, "grain read unwritten current/future input")
                    previousCount = first.grainCount
                }
                assertTrue(a.all { it.isFinite() && abs(it) <= first.excitationCeiling + 1e-7f })
            }
            assertTrue(first.grainCount >= 2, "$seconds generated no actual slices")
            assertTrue(first.maxActiveGrains in 1..3, "$seconds exceeded its fixed overlap budget")
            assertTrue(first.maxOutput > 1e-5f, "$seconds produced inaudible numerical dust")
        }
    }

    @Test
    fun `freeze alone retains a complete populated buffer and ignores all later source audio`() {
        val a = OhmTextures(OhmOptions(freeze = 1f), 60, 731, rate)
        val b = OhmTextures(OhmOptions(freeze = 1f), 60, 731, rate)
        val count = rate
        val common = a.historyFrames
        val first = run(a, count, observed = { if (it < common) source(it, 261.625565) else 0f })[1]
        val second = run(b, count, observed = { if (it < common) source(it, 261.625565) else .8f * sin(it * .713).toFloat() })[1]
        assertEquals(common, a.frozenAtFrame)
        assertEquals((.25f * rate).roundToInt(), a.frozenSnapshotFrames)
        assertEquals(0, a.grainCount, "freeze must work without enabling grains")
        assertTrue(first.copyOfRange(0, common).all { it == 0f })
        assertContentEquals(first, second, "future source changed an already frozen buffer")
        val tail = first.copyOfRange(rate / 2, rate)
        assertTrue(rms(tail) > 1e-5, "freeze captured silence")
        assertTrue(tail.maxOrNull()!! > 0f && tail.minOrNull()!! < 0f, "freeze became a held scalar or DC")
    }

    @Test
    fun `native speed captured freeze retains the requested root across the full note range`() {
        for (midi in listOf(48, 60, 72)) {
            val hz = Keys.midiHz(midi)
            val helper = OhmTextures(OhmOptions(freeze = 1f), midi, 731, rate)
            val out = run(helper, rate, activity = 1f, observed = { source(it, hz.toDouble()) })[1]
            val measured = FineTuning.measuredHz(out, rate, hz, .40f, .45f)
            val cents = FineTuning.cents(measured, hz.toDouble())
            assertTrue(abs(cents) < 5, "MIDI$midi frozen source moved $cents cents")
            assertTrue(abs(out.copyOfRange(rate / 2, rate).average()) < 1e-4, "freeze retained a DC pedestal")
        }
    }

    @Test
    fun `held sources repeat their prepared input schedule without rebuilding or transposing it`() {
        val midi = 72
        val hz = Keys.midiHz(midi).toDouble()
        val cycles = (hz * .5).roundToInt()
        val size = (cycles * rate / hz).roundToInt()
        val input = FloatArray(size) { i -> if (i < rate / 4) source(i, hz) else 0f }
        val helper = OhmTextures(OhmOptions(elder = .8f, grains = 1f, freeze = .8f, fmIndex = 3f),
            midi, 731, rate, size)
        helper.prepareLoop(input)
        val out = run(helper, size * 3, activity = 1f, observed = { 0f })
        for (port in 0..1) {
            val cycle = out[port].copyOfRange(0, size)
            assertTrue(rms(cycle) > 1e-5, "held port$port missed the populated attack")
            assertContentEquals(cycle, out[port].copyOfRange(size, 2 * size))
            assertContentEquals(cycle, out[port].copyOfRange(2 * size, 3 * size))
            assertNaturalWrap(cycle)
            assertTrue(cycle.all { it.isFinite() && abs(it) <= helper.excitationCeiling + 1e-7f })
        }
        assertEquals(helper.historyFrames, helper.frozenSnapshotFrames)
    }

    @Test
    fun `pure held freeze retains transient material pitch and its own natural wrap in every register`() {
        for (midi in listOf(48, 60, 72)) {
            val hz = Keys.midiHz(midi).toDouble()
            val cycles = (hz * .5).roundToInt()
            val size = (cycles * rate / hz).roundToInt()
            val input = FloatArray(size) { i ->
                if (i < rate / 4) source(i, hz) * exp(-i.toDouble() / (.09 * rate)).toFloat() else 0f
            }
            val helper = OhmTextures(OhmOptions(freeze = 1f), midi, 731, rate, size)
            helper.prepareLoop(input)
            val freeze = run(helper, size * 2, activity = 1f, observed = { 0f })[1]
            assertTrue(rms(freeze) > 1e-5, "held freeze discarded the transient source")
            assertNaturalWrap(freeze.copyOfRange(0, size))
            val measured = FineTuning.measuredHz(freeze, rate, hz.toFloat(), .10f, .35f)
            assertTrue(abs(FineTuning.cents(measured, hz)) < 5,
                "MIDI$midi held-cycle fitting transposed the captured source")
        }
    }

    @Test
    fun `elder enters continuously with meddling and FM bounds retain raw headroom`() {
        for (midi in listOf(48, 72)) for (ratio in listOf(.5f, 1.5f, 2f)) {
            val options = OhmOptions(elder = 1f, fmRatio = ratio, fmIndex = 3f)
            val quiet = OhmTextures(options, midi, 731, rate)
            val middle = OhmTextures(options, midi, 731, rate)
            val busy = OhmTextures(options, midi, 731, rate)
            val q = run(quiet, rate / 10, activity = .25f, observed = { 0f })[0]
            val m = run(middle, rate / 10, activity = .65f, observed = { 0f })[0]
            val b = run(busy, rate / 10, activity = 1f, observed = { 0f })[0]
            assertTrue(q.all { it == 0f })
            assertTrue(rms(m) > 1e-5 && rms(b) > rms(m) * 1.5, "elder participation is a binary gate")
            assertTrue(busy.effectiveFmIndex <= 3 && busy.fmBandEstimateHz < 18_000)
            assertTrue(b.all { it.isFinite() && abs(it) <= busy.excitationCeiling })
        }
    }

    @Test
    fun `high note deep FM converges between four and eight times rate after real resampling`() {
        val options = OhmOptions(elder = 1f, fmRatio = 2f, fmIndex = 3f)
        fun rendered(multiplier: Int): FloatArray {
            val internalRate = rate * multiplier
            val helper = OhmTextures(options, 72, 731, internalRate)
            val wave = run(helper, internalRate / 4, activity = 1f, observed = { 0f })[0]
            return Resampler.resample(Snip(wave, 1, internalRate), rate).samples
        }
        val four = rendered(4)
        val eight = rendered(8)
        assertEquals(four.size, eight.size)
        val from = rate / 20
        val difference = FloatArray(four.size - from) { i -> four[from + i] - eight[from + i] }
        assertTrue(rms(difference) / rms(eight.copyOfRange(from, eight.size)) < .01,
            "deep FM changes materially with the internal Nyquist rate")
    }

    @Test
    fun `source input and schedule mistakes fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { OhmTextures(OhmOptions(), 47, 731, rate) }
        val finite = OhmTextures(OhmOptions(grains = 1f), 60, 731, rate)
        assertFailsWith<IllegalArgumentException> { finite.fill(1, 1f, 0f, 0f, FloatArray(2)) }
        assertFailsWith<IllegalArgumentException> { finite.fill(0, 1f, 0f, Float.NaN, FloatArray(2)) }
        val held = OhmTextures(OhmOptions(freeze = 1f), 60, 731, rate, rate / 2)
        assertFailsWith<IllegalArgumentException> { held.prepareLoop(FloatArray(10)) }
        assertFailsWith<IllegalStateException> { held.fill(0, 1f, 0f, 0f, FloatArray(2)) }
    }

    @Test
    fun `held preparation respects cancellation even when optional amounts are zero`() {
        val helper = OhmTextures(OhmOptions(), 60, 731, rate, rate / 2)
        val source = FloatArray(rate / 2)
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { helper.prepareLoop(source) }
        } finally {
            Thread.interrupted()
        }
    }

    private fun assertNaturalWrap(cycle: FloatArray) {
        var steepest = 0.0
        var kink = 0.0
        for (i in 1 until cycle.size) steepest = maxOf(steepest, abs(cycle[i] - cycle[i - 1]).toDouble())
        for (i in 2 until cycle.size) kink = maxOf(kink,
            abs((cycle[i] - cycle[i - 1]) - (cycle[i - 1] - cycle[i - 2])).toDouble())
        val wrap = (cycle.first() - cycle.last()).toDouble()
        val before = (cycle.last() - cycle[cycle.lastIndex - 1]).toDouble()
        val after = (cycle[1] - cycle.first()).toDouble()
        assertTrue(abs(wrap) <= steepest * 1.05 + 1e-7, "source wrap jumps beyond its own sample steps")
        assertTrue(maxOf(abs(wrap - before), abs(after - wrap)) <= kink * 1.1 + 1e-7,
            "source wrap changes slope beyond its own signal")
    }
}
