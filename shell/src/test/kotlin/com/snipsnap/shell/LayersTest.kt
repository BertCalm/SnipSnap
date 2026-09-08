package com.snipsnap.shell

import com.snipsnap.audio.Separate
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LayersTest {

    /** A tone with clicks on it: something with a real sines part and a real transient part. */
    private fun source(frames: Int = 8192, channels: Int = 1): Snip {
        val s = FloatArray(frames * channels)
        for (f in 0 until frames) {
            var v = 0.3f * sin(2.0 * PI * 220.0 * f / 44100.0).toFloat()
            if (f % 2048 < 24) v += 0.5f  // a click every so often
            for (c in 0 until channels) s[f * channels + c] = if (c == 0) v else v * 0.7f
        }
        return Snip(s, channels, 44100)
    }

    @Test
    fun `a desk at rest is the sound itself`() {
        // The whole point: the parts sum back to the input, so three faders
        // at unity must give the input back. A mixer you can hear when it is
        // doing nothing is not one to print through.
        val src = source()
        val stn = Separate.stn(src)
        val out = Layers.render(stn, Layers.Desk())
        assertEquals(src.samples.size, out.samples.size)
        var worst = 0f
        for (i in src.samples.indices) worst = maxOf(worst, abs(out.samples[i] - src.samples[i]))
        assertTrue(worst < 1e-4f, "the desk coloured the sound at rest by $worst")
    }

    @Test
    fun `a fader down is that part gone and nothing else`() {
        val stn = Separate.stn(source())
        val desk = Layers.Desk()
        val withoutAir = desk.with(Layers.Part.AIR) { it.copy(level = 0f) }
        val a = Layers.render(stn, withoutAir)
        // The same thing said another way: sines + transient.
        val b = Layers.render(
            stn,
            desk.with(Layers.Part.AIR) { it.copy(muted = true) },
        )
        for (i in a.samples.indices) assertTrue(abs(a.samples[i] - b.samples[i]) < 1e-6f)
        // And it is not simply the whole sound: the air really left.
        val whole = Layers.render(stn, desk)
        assertTrue(a.samples.indices.any { abs(a.samples[it] - whole.samples[it]) > 1e-5f })
    }

    @Test
    fun `mute silences, solo silences the others, and mute wins`() {
        val d = Layers.Desk()
        assertEquals(1f, d.gain(Layers.Part.SINES))

        val muted = d.with(Layers.Part.SINES) { it.copy(muted = true) }
        assertEquals(0f, muted.gain(Layers.Part.SINES))
        assertEquals(1f, muted.gain(Layers.Part.AIR))

        val soloed = d.with(Layers.Part.AIR) { it.copy(soloed = true) }
        assertEquals(0f, soloed.gain(Layers.Part.SINES))
        assertEquals(0f, soloed.gain(Layers.Part.TRANSIENT))
        assertEquals(1f, soloed.gain(Layers.Part.AIR))

        // Both: muting is the more deliberate, so it stays off rather than
        // surprising you when you reach for solo.
        val both = d.with(Layers.Part.AIR) { it.copy(soloed = true, muted = true) }
        assertEquals(0f, both.gain(Layers.Part.AIR))
        assertTrue(both.silent)
    }

    @Test
    fun `reverse turns the frames around, not the samples`() {
        // Stereo, with left and right told apart, so a flipped image would show.
        val src = Snip(floatArrayOf(1f, -1f, 2f, -2f, 3f, -3f), 2, 44100)
        val back = Layers.reversed(src)
        assertEquals(listOf(3f, -3f, 2f, -2f, 1f, -1f), back.samples.toList())
        assertEquals(2, back.channels)
        assertEquals(src.sampleRate, back.sampleRate)
        // Twice round is where it started.
        assertEquals(src.samples.toList(), Layers.reversed(back).samples.toList())
    }

    @Test
    fun `a reversed strip lands reversed in the mix`() {
        val stn = Separate.stn(source(frames = 4096))
        val onlyAir = Layers.Desk()
            .with(Layers.Part.AIR) { it.copy(soloed = true) }
        val forward = Layers.render(stn, onlyAir)
        val backward = Layers.render(stn, onlyAir.with(Layers.Part.AIR) { it.copy(reverse = true) })
        val flipped = Layers.reversed(forward)
        for (i in flipped.samples.indices) assertTrue(abs(backward.samples[i] - flipped.samples[i]) < 1e-6f)
    }

    @Test
    fun `the desk knows when it is doing nothing and when it is doing everything`() {
        val d = Layers.Desk()
        assertTrue(d.atRest)
        assertTrue(!d.silent)
        assertTrue(!d.with(Layers.Part.SINES) { it.copy(reverse = true) }.atRest)
        assertTrue(!d.with(Layers.Part.SINES) { it.copy(level = 0.5f) }.atRest)
        val allOff = Layers.Part.entries.fold(d) { acc, p -> acc.with(p) { it.copy(muted = true) } }
        assertTrue(allOff.silent)
    }

    @Test
    fun `refusals in words`() {
        assertFailsWith<IllegalArgumentException> { Layers.Strip(level = -0.1f) }
        assertFailsWith<IllegalArgumentException> { Layers.Strip(level = Layers.MAX_LEVEL + 0.1f) }
        assertFailsWith<IllegalArgumentException> { Layers.Strip(level = Float.NaN) }
        // Parts of different lengths did not come from one split.
        val one = Snip(FloatArray(100), 1, 44100)
        val other = Snip(FloatArray(50), 1, 44100)
        assertFailsWith<IllegalArgumentException> {
            Layers.render(Separate.Stn(one, other, one), Layers.Desk())
        }
        assertFailsWith<IllegalArgumentException> {
            val empty = Snip(FloatArray(0), 1, 44100)
            Layers.render(Separate.Stn(empty, empty, empty), Layers.Desk())
        }
    }
}
