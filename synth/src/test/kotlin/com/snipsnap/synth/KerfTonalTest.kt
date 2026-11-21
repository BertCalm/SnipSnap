package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KERF's pitched notes ([KerfTonal], [KerfRender.ring]) on a hand-built note whose parts are known: one 440 Hz partial
 * ringing 2 s, a little crack and ring in the noise, and a floor (the loop's wash) under all of it.
 */
class KerfTonalTest {
    private val rate = Dsp.RATE

    private fun note(t60: Double = 2.0, floor: Double = 1e-7): KerfPatchData = KerfPatchData(
        seconds = 0.2, seed = 12345L,
        partials = listOf(KerfPartial(hzEnd = 440.0, glideHz = 0.0, glideS = 0.01, amp = 0.5, startS = 0.0, attackS = 0.002, t60 = t60, phase = 0.0)),
        bands = List(KerfRender.BAND_COUNT) { KerfBand(snap = 1e-6, snapT60 = 0.03, level = 1e-7, t60 = 0.2, floor = floor, attackS = 0.0, layers = emptyList()) },
        colour = null, ticks = emptyList(), events = emptyList(),
    )

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * rate).toInt().coerceAtMost(x.size); val b = (to * rate).toInt().coerceAtMost(x.size)
        var s = 0.0; for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / maxOf(1, b - a))
    }

    /** The strongest frequency in [x] between 50 Hz and 5 kHz, from a long FFT of its first second. */
    private fun peakHz(x: FloatArray): Double {
        val n = 1 shl 16; val re = FloatArray(n); val im = FloatArray(n)
        for (i in 0 until minOf(n, x.size)) re[i] = x[i]
        com.snipsnap.audio.Fft.forward(re, im)
        var best = 0; var bestP = 0.0
        for (k in (50.0 * n / rate).toInt()..(5000.0 * n / rate).toInt()) {
            val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]; if (p > bestP) { bestP = p; best = k }
        }
        return best.toDouble() * rate / n
    }

    @Test
    fun `a ring runs as long as its slowest partial, past the cut, and no longer than the cap`() {
        val x = KerfRender.ring(note(t60 = 2.0))
        assertEquals(2.0, x.size.toDouble() / rate, 0.01, "the partial's 60 dB decay sets the length")
        assertTrue(x.all { it.isFinite() })
        // 0.5 x 10^(-3 x 0.6 / 2) = 0.063 at 0.6 s, an rms of 0.045: still ringing well past the 0.2 s cut
        assertEquals(0.045, rms(x, 0.5, 0.7), 0.01, "it is still ringing, as its decay says, past the 0.2 s cut")
        assertTrue(abs(x.last()) < 1e-4, "it ends on its fade")
        val long = KerfRender.ring(note(t60 = 60.0))
        assertEquals(KerfRender.RING_MAX_SECONDS, long.size.toDouble() / rate, 0.01, "a minute-long fitted decay is capped")
        // a slice that rings less than its own length keeps its length
        assertEquals(0.2, KerfRender.ring(note(t60 = 0.05)).size.toDouble() / rate, 0.01)
    }

    @Test
    fun `the floor stops at the cut, where the old extension let it run on`() {
        val loud = note(t60 = 1.0, floor = 1e-3)
        val rung = KerfRender.ring(loud)
        val extended = KerfRender.render(loud, extendSeconds = rung.size.toDouble() / rate - loud.seconds, edge = false)
        // 0.3 s on, past FLOOR_STOP_T60: the partial is the same in both, the floor only in the old one
        val residue = rms(extended, 0.3, 0.6) - rms(rung, 0.3, 0.6)
        assertTrue(residue > 0.0, "the extension carries the floor on (residue $residue)")
        val withoutFloor = KerfRender.ring(note(t60 = 1.0, floor = 0.0))
        val diff = (0 until minOf(rung.size, withoutFloor.size)).filter { it > (0.3 * rate).toInt() }
            .maxOf { abs(rung[it] - withoutFloor[it]).toDouble() }
        assertTrue(diff < 1e-3, "past the stop, a ring with a floor is the ring without one (max diff $diff)")
    }

    @Test
    fun `a note lands on the note it is asked for, an octave either way`() {
        val n = KerfTonalNote(note(), rootMidi = 69.0)   // 440 Hz is A4, MIDI 69
        assertEquals(440.0, peakHz(KerfTonal.render(n, 69.0).samples), 2.0)
        assertEquals(880.0, peakHz(KerfTonal.render(n, 81.0).samples), 3.0)
        assertEquals(440.0 * Math.pow(2.0, -5.0 / 12.0), peakHz(KerfTonal.render(n, 64.0).samples), 2.0)
        // a root off the semitone is corrected: a note fitted 30 cents sharp still lands on A4
        val sharp = KerfTonalNote(note(), rootMidi = 69.3)
        assertEquals(440.0 * Math.pow(2.0, -0.3 / 12.0), peakHz(KerfTonal.render(sharp, 69.0).samples), 2.0)
    }

    @Test
    fun `a take is the bar struck again, not a copy and not another bar`() {
        val n = KerfTonalNote(note(), 69.0)
        val takes = (0..3).map { KerfTonal.render(n, 69.0, take = it).samples }
        for (i in 0..3) for (j in i + 1..3) {
            val a = takes[i]; val b = takes[j]; val m = minOf(a.size, b.size)
            var d = 0.0; var s = 0.0
            for (k in 0 until m) { val e = (a[k] - b[k]).toDouble(); d += e * e; s += a[k].toDouble() * a[k] }
            assertTrue(10 * log10(d / s) > -60, "takes $i and $j differ")
            val level = 20 * log10(rms(a, 0.0, 1.0) / rms(b, 0.0, 1.0))
            assertTrue(abs(level) < 1.5, "takes $i and $j are the same bar: $level dB apart")
            assertEquals(peakHz(a), peakHz(b), 2.0, "takes $i and $j are the same note")
        }
    }

    @Test
    fun `a soft strike is quieter, and the default chop path is untouched by the ring's controls`() {
        val n = KerfTonalNote(note(), 69.0)
        assertTrue(rms(KerfTonal.render(n, 69.0, velocity = 0.3).samples, 0.0, 0.5) < rms(KerfTonal.render(n, 69.0).samples, 0.0, 0.5))
        val p = KerfBank.kits.getValue("A").first().patch
        val plain = KerfRender.render(p)
        val explicit = KerfRender.render(p, KerfRender.Controls(floorUntil = Double.POSITIVE_INFINITY))
        assertTrue(plain.contentEquals(explicit), "Controls' new default is the old render")
    }
}
