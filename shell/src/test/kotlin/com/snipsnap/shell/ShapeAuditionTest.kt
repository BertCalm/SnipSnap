package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapeAuditionTest {

    private val rate = 44_100

    /** A low tone and a high tone together, so a filter has something to take away. */
    private fun twoTones(): Snip = Snip(
        FloatArray(rate) { i ->
            (0.3 * Math.sin(2.0 * Math.PI * 110.0 * i / rate) + 0.3 * Math.sin(2.0 * Math.PI * 6000.0 * i / rate)).toFloat()
        },
        1,
        rate,
    )

    /** Goertzel: the energy at one frequency, enough to compare a tone before and after. */
    private fun probe(s: Snip, hz: Float): Float {
        val w = 2.0 * Math.PI * hz / rate
        val coeff = 2.0 * Math.cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (v in s.samples) {
            val s0 = v + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return (s1 * s1 + s2 * s2 - coeff * s1 * s2).toFloat()
    }

    @Test
    fun `an unshaped pad auditions the bytes on disk`() {
        val src = twoTones()
        assertTrue(ShapeAudition.render(src, KitPad(1, "a.wav")) === src)
    }

    @Test
    fun `cutoff closes the top and leaves the bottom`() {
        val src = twoTones()
        // 0.5 -> ~632 Hz: the 110 Hz tone passes, the 6 kHz tone does not.
        val shaped = ShapeAudition.render(src, KitPad(1, "a.wav", cutoff = 0.5f))
        assertEquals(src.frameCount, shaped.frameCount)
        assertTrue(probe(shaped, 110f) > 0.7f * probe(src, 110f), "the low tone survives")
        assertTrue(probe(shaped, 6000f) < 0.05f * probe(src, 6000f), "the high tone is gone")
    }

    @Test
    fun `resonance rings at the cutoff and never lands above the source's peak`() {
        val src = twoTones()
        // Cutoff parked on the low tone so resonance has something to lift.
        val cutoff = (Math.log10(110.0 / 20.0) / 3.0).toFloat()
        val flat = ShapeAudition.render(src, KitPad(1, "a.wav", cutoff = cutoff))
        val ringing = ShapeAudition.render(src, KitPad(1, "a.wav", cutoff = cutoff, resonance = 1f))
        fun peak(s: Snip): Float { var p = 0f; for (v in s.samples) p = maxOf(p, Math.abs(v)); return p }
        assertTrue(peak(ringing) <= peak(src) * 1.001f, "peak held at the source's")
        assertTrue(probe(ringing, 110f) / peak(ringing) > probe(flat, 110f) / peak(flat), "the tone at the cutoff stands out more")
        assertEquals(2f, ShapeAudition.damping(0f))
        assertEquals(ShapeAudition.MIN_DAMPING, ShapeAudition.damping(1f))
    }

    @Test
    fun `envelope and filter compose - decay trims, then the filter runs`() {
        val src = twoTones()
        val shaped = ShapeAudition.render(src, KitPad(1, "a.wav", decay = 0.5f, cutoff = 0.5f))
        assertEquals(src.frameCount / 2, shaped.frameCount)
        assertTrue(probe(shaped, 6000f) < 0.05f * probe(src, 6000f))
    }
}
