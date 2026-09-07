package com.snipsnap.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EternalTest {

    private val rate = 44_100

    /** A click at the head, then a tail that chirps 300 → 600 Hz over 0.3 s: the map can be read off its pitch. */
    private fun chirp(): Snip {
        val head = (0.03f * rate).toInt()
        val tail = (0.3f * rate).toInt()
        return Snip(
            FloatArray(head + tail) { i ->
                if (i < head) {
                    if (i < 40) (0.8 * Math.sin(2 * Math.PI * 3000.0 * i / rate) * (1 - i / 40.0)).toFloat() else 0f
                } else {
                    val t = (i - head).toDouble() / rate
                    // Phase of a linear chirp: 2π(f0 t + (f1−f0) t² / 2L).
                    val phase = 2 * Math.PI * (300.0 * t + 300.0 * t * t / (2 * 0.3))
                    (0.5 * Math.sin(phase)).toFloat()
                }
            },
            1, rate,
        )
    }

    @Test
    fun `the first 30 ms are bit-identical and the tail is exactly the knob`() {
        val src = chirp()
        val out = Eternal.stretch(src, tailSec = 3f, seed = 5)
        val knee = (0.03f * rate).toInt()
        assertEquals(knee + 3 * rate, out.frameCount, "knee plus the knob")
        for (i in 0 until knee) assertEquals(src.samples[i], out.samples[i], "sample $i of the head is the source's own")
        assertTrue(out.samples.copyOfRange(knee + rate, knee + rate + rate).any { abs(it) > 0.05f }, "a second in, the tail is still sounding")
        assertTrue(out.samples.contentEquals(Eternal.stretch(src, tailSec = 3f, seed = 5).samples), "same seed, same bytes")
        assertTrue(!out.samples.contentEquals(Eternal.stretch(src, tailSec = 3f, seed = 6).samples), "another seed, other phases")
    }

    @Test
    fun `the tail slows - speed one at the knee, a crawl by the end, the pitch line kept`() {
        val out = Eternal.stretch(chirp(), tailSec = 3f)
        val knee = 0.03f
        // The chirp's frequency at output time τ tells where in the source we are.
        fun hzAt(tauSec: Float): Float {
            val e = assertNotNull(Pitch.detect(out, fromSec = knee + tauSec, windowSec = 0.08f), "a pitch at $tauSec s")
            return e.hz
        }
        val early = hzAt(0.05f)
        val mid = hzAt(1.5f)
        val late = hzAt(2.85f)
        // Right after the knee the tail runs at speed: 50 ms in, the chirp has climbed ~50 Hz as it would untouched.
        assertTrue(early > 320f && early < 400f, "at speed just past the knee: $early Hz")
        // Halfway through three seconds we are most of the way through the source, not at its end.
        assertTrue(mid > 480f && mid < 590f, "slowing, not frozen, at 1.5 s: $mid Hz")
        // The end crawls onto the source's last instant.
        assertTrue(late > 560f, "the crawl ends on the chirp's top: $late Hz")
        assertTrue(early < mid && mid < late, "monotone: $early < $mid < $late")
    }

    @Test
    fun `refusals are honest, AMOUNT maps to seconds, and stereo stays stereo`() {
        val src = chirp()
        assertNull(Eternal.refusal(src, 3f))
        assertNull(Eternal.refusal(src, 0.5f), "a 0.3 s tail fits in half a second")
        val long = Snip(FloatArray(rate) { (0.4 * Math.sin(2 * Math.PI * 220.0 * it / rate)).toFloat() }, 1, rate)
        assertNotNull(Eternal.refusal(long, 0.5f))?.let { assertTrue("already" in it, it) }
        assertFailsWith<IllegalArgumentException> { Eternal.stretch(long, tailSec = 0.5f) }
        assertFailsWith<IllegalArgumentException> { Eternal.stretch(src, tailSec = 0.2f) }
        assertFailsWith<IllegalArgumentException> { Eternal.stretch(src, tailSec = 3f, kneeSec = 1f) }
        val blip = Snip(FloatArray(rate / 100) { 0.5f }, 1, rate)
        assertNotNull(Eternal.refusal(blip, 3f))?.let { assertTrue("inside the knee" in it, it) }
        assertEquals(Eternal.TAIL_MIN_SEC, Eternal.tailFor(0f), 1e-4f)
        assertEquals(Eternal.TAIL_MAX_SEC, Eternal.tailFor(1f), 1e-3f)
        assertEquals(Math.sqrt(0.5 * 30.0).toFloat(), Eternal.tailFor(0.5f), 1e-2f)

        val stereo = Snip(FloatArray(src.frameCount * 2) { i -> src.samples[i / 2] * if (i % 2 == 0) 1f else 0.6f }, 2, rate)
        val out = Eternal.stretch(stereo, tailSec = 1f)
        assertEquals(2, out.channels)
        assertEquals((0.03f * rate).toInt() + rate, out.frameCount)
        for (i in 0 until (0.03f * rate).toInt() * 2) assertEquals(stereo.samples[i], out.samples[i])
    }
}
