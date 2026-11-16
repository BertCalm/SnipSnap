package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.test.*

class SplinterTest {
    private fun rms(x: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { x[it].toDouble().pow(2) } / (to - from))

    /** Energy near [hz], by a single-bin Fourier sum. */
    private fun bin(x: FloatArray, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val p = 2 * PI * hz * i / Dsp.RATE; re += x[i] * cos(p); im += x[i] * sin(p) }
        return hypot(re, im) / x.size
    }

    @Test fun `every pad has timbral velocity, fresh takes and a deterministic saved source`() {
        for (p in Splinter.layout.indices) {
            val name = Splinter.layout[p].name
            val recipe = Splinter.Recipe(p)
            assertEquals(recipe, Splinter.Recipe.fromJsonText(recipe.toJsonText()))
            val hard = recipe.render(); val soft = recipe.copy(velocity = .3).render(); val medium = recipe.copy(velocity = .65).render()
            assertContentEquals(hard.samples, Splinter.Recipe.fromJsonText(recipe.toJsonText()).render().samples, name)
            assertTrue(soft.peak() < medium.peak() && medium.peak() < hard.peak(), "$name velocity peaks ${soft.peak()} ${medium.peak()} ${hard.peak()}")
            val n = min(soft.samples.size, hard.samples.size)
            val distance = (0 until n).sumOf { (soft.samples[it] / soft.peak() - hard.samples[it] / hard.peak()).toDouble().pow(2) }
            assertTrue(distance > 1e-4, "$name velocity changes only gain")
            for (t in 1..2) assertFalse(hard.samples.contentEquals(recipe.copy(take = t).render().samples), "$name cloned take")
            assertTrue(hard.samples.all { it.isFinite() } && hard.peak() < 1f, "$name peak")
            assertTrue(hard.peak() > .25f, "$name is too quiet at full force: ${hard.peak()}")
            assertTrue(abs(hard.samples.average()) < .005, "$name DC")
            assertTrue(hard.samples.last() == 0f, "$name tail")
            assertTrue(recipe.copy(velocity = 0.0).render().samples.all { it == 0f }, "$name sounds at zero force")
        }
    }

    @Test fun `speed moves pitch and length together`() {
        val tone = FloatArray(Dsp.RATE / 2) { sin(2 * PI * 1000 * it / Dsp.RATE).toFloat() }
        val fast = Splinter.repitch(tone, Splinter.SPEED_SEMITONES)
        val ratio = Splinter.BPM.toDouble() / Splinter.PLAYED_BPM
        assertEquals(tone.size / ratio, fast.size.toDouble(), 2.0)
        assertTrue(bin(fast, 1000 * ratio) > 20 * bin(fast, 1000.0), "the tone did not move to ${1000 * ratio} Hz")
        // The slice pads were played at 137 and must land on exactly one beat at 200.
        assertEquals((60.0 / Splinter.BPM * Dsp.RATE).roundToInt(), Splinter.renderPad(10).frameCount)
    }

    @Test fun `the clipper adds odd harmonics inside the band and nothing folded back`() {
        val tone = FloatArray(Dsp.RATE / 2) { (.8 * sin(2 * PI * 5000 * it / Dsp.RATE)).toFloat() }
        val out = Splinter.clip(tone, 8.0)
        assertTrue(out.maxOf { abs(it) } < 1.35f, "band-limit overshoot grew: ${out.maxOf { abs(it) }}")
        assertTrue(bin(out, 15000.0) > .02, "no third harmonic: the clipper is not clipping")
        // The 5th harmonic (25 kHz) of a naive clipper folds to 19.1 kHz, the 7th to 9.1 kHz.
        // A naive clipper leaves them near a fifth of the fundamental; measured here at -51 dB.
        for (alias in listOf(19100.0, 9100.0)) assertTrue(bin(out, alias) < .005 * bin(out, 5000.0), "alias at $alias Hz: ${bin(out, alias)}")
    }

    @Test fun `stretch lengthens without lowering and gates at the grain rate`() {
        val tone = FloatArray(Dsp.RATE / 4) { sin(2 * PI * 2000 * it / Dsp.RATE).toFloat() }
        val out = Splinter.stretch(tone, 3.0, 10.0)
        assertEquals(tone.size * 3, out.size)
        // Every 10 ms grain starts from silence: that 100 Hz gate is the buzz.
        val grain = Dsp.RATE / 100
        assertTrue((0 until out.size / grain).all { out[it * grain] == 0f })
        // Pitch holds: still about 2000 cycles a second, where reading slower would give 667.
        val crossings = out.toList().zipWithNext().count { (a, b) -> a <= 0f && b > 0f }
        assertEquals(2000.0, crossings / (out.size.toDouble() / Dsp.RATE), 150.0)
    }

    @Test fun `the rush halves its gap each beat and the riser climbs`() {
        val rush = Splinter.rushEvents()
        assertEquals(listOf(4, 8, 16, 32), (0..3).map { b -> rush.count { it.beat.toInt() == b } })
        assertTrue(rush.zipWithNext().all { (a, b) -> b.beat > a.beat && b.force >= a.force && b.tune >= a.tune })
        val rise = Splinter.riserEvents()
        assertTrue(rise.last().beat - rise[rise.lastIndex - 1].beat < rise[1].beat - rise[0].beat)
        assertTrue(rise.last().tune > rise.first().tune && rise.last().force > rise.first().force)
        // Both gestures are four beats at 200 BPM plus one source tail, and grow louder as they go.
        for (pad in 16..17) {
            val s: Snip = Splinter.renderPad(pad); val beat = 60.0 / Splinter.BPM * Dsp.RATE
            assertTrue(s.frameCount > 4 * beat && s.frameCount < 4 * beat + Dsp.RATE / 2, "pad $pad length ${s.frameCount}")
            assertTrue(rms(s.samples, (3 * beat).toInt(), (4 * beat).toInt()) > 1.5 * rms(s.samples, 0, beat.toInt()), "pad $pad does not build")
        }
    }

    @Test fun `a chop ends in silence and the stutter is repeats of one buffer`() {
        val beat = (60.0 / Splinter.BPM * Dsp.RATE).roundToInt()
        assertTrue(Splinter.chop(FloatArray(1000) { 1f }, 500).last() == 0f)
        // Half + half, four quarters, eight eighths of a beat: three beats.
        assertEquals(3.0 * beat, Splinter.renderPad(12).frameCount.toDouble(), 16.0)
    }
}
