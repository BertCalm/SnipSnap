package com.snipsnap.shell

import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DigTest {

    private val rate = 44_100

    private fun padSection(seconds: Float): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val chord = doubleArrayOf(220.0, 277.18, 329.63)
        for (i in 0 until n) {
            var s = 0.0
            for (hz in chord) s += Math.sin(2.0 * Math.PI * hz * i / rate)
            out[i] = (0.18 * s).toFloat()
        }
        return out
    }

    private fun breakSection(seconds: Float): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val beat = (60f / 100f * rate).toInt()
        fun place(hit: Snip, at: Int, gain: Float) {
            for (i in hit.samples.indices) {
                val idx = at + i
                if (idx >= n) break
                out[idx] += hit.samples[i] * gain
            }
        }
        val kick = DrumSynth.kick()
        val snare = DrumSynth.snare()
        val hat = DrumSynth.closedHat()
        var t = 0
        var count = 0
        while (t < n) {
            place(kick, t, 0.9f)
            if (count % 2 == 1) place(snare, t, 0.8f)
            place(hat, t, 0.5f)
            place(hat, t + beat / 2, 0.4f)
            t += beat
            count++
        }
        return out
    }

    @Test
    fun `the break inside a song comes back as deck frames where it is`() {
        val pad = padSection(12f)
        val brk = breakSection(10f)
        val song = Snip(pad + brk + pad, 1, rate)
        val found = assertNotNull(Dig.best(song))
        assertTrue(found.startFrame < found.endFrame && found.endFrame <= song.frameCount)
        assertTrue(found.startSec in 9f..15f, "starts near 12 s: ${found.startSec}")
        assertTrue(found.endSec in 19f..25f, "ends near 22 s: ${found.endSec}")
        assertEquals((found.startSec * rate).toInt(), found.startFrame)
    }

    @Test
    fun `silence and an empty tape are no break, and the stamp reads m ss`() {
        assertNull(Dig.best(Snip(FloatArray(rate * 8), 1, rate)))
        assertNull(Dig.best(Snip(FloatArray(0), 1, rate)))
        assertEquals("1:05", Dig.stamp(65.4f))
        assertEquals("0:00", Dig.stamp(0f))
    }
}
