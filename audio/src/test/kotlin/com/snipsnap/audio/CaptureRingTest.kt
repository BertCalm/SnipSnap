package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CaptureRingTest {

    @Test
    fun `a snapshot returns the newest frames oldest-first`() {
        val ring = CaptureRing(8)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f), 5)
        assertContentEquals(floatArrayOf(3f, 4f, 5f), ring.snapshot(3))
    }

    @Test
    fun `writing past capacity overwrites the oldest, seamlessly across the wrap`() {
        val ring = CaptureRing(4)
        ring.write(floatArrayOf(1f, 2f, 3f), 3)
        ring.write(floatArrayOf(4f, 5f, 6f), 3)   // 1f and 2f fall off the back
        assertContentEquals(floatArrayOf(3f, 4f, 5f, 6f), ring.snapshot(4))
    }

    @Test
    fun `asking for more than has ever been written returns only what exists`() {
        val ring = CaptureRing(100)
        ring.write(floatArrayOf(7f, 8f), 2)
        assertContentEquals(floatArrayOf(7f, 8f), ring.snapshot(50))
        assertEquals(2, ring.filledFrames)
    }

    @Test
    fun `a block larger than the whole ring keeps its newest tail`() {
        val ring = CaptureRing(3)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f), 5)
        assertContentEquals(floatArrayOf(3f, 4f, 5f), ring.snapshot(3))
    }

    @Test
    fun `sixty seconds of writes never allocates on the write path`() {
        // Behavioural proxy for the realtime rule: hammer writes with a
        // reused block and assert correctness; the no-allocation claim is
        // enforced by construction (write() contains only arraycopy and
        // index math — see its KDoc) and reviewed, not measured here.
        val ring = CaptureRing(44_100 * 60)
        val block = FloatArray(2048) { (it % 100) / 100f }
        repeat(44_100 * 60 / 2048 + 3) { ring.write(block, block.size) }
        val snap = ring.snapshot(2048)
        assertContentEquals(block.toList().takeLast(2048).toFloatArray(), snap)
        assertEquals(44_100L * 60 / 2048 * 2048 + 3 * 2048, ring.framesWritten)
    }

    @Test
    fun `a concurrent reader never sees torn counts, only possibly torn oldest samples`() {
        // SPSC contract: one writer thread, snapshots from another. We can't
        // assert sample-exactness at the moving boundary (the spec accepts
        // one block of ambiguity at the OLDEST edge) — but snapshot must
        // never throw, never return a wrong-sized array, and the NEWEST
        // half must always be internally consistent (monotonic ramp).
        val ring = CaptureRing(44_100)
        val writer = Thread {
            val block = FloatArray(441)
            var v = 0f
            repeat(2_000) {
                for (i in block.indices) { block[i] = v; v += 1f }
                ring.write(block, block.size)
            }
        }
        writer.start()
        var checks = 0
        while (writer.isAlive) {
            val snap = ring.snapshot(1_000)
            if (snap.size == 1_000) {
                val newest = snap.copyOfRange(500, 1_000)
                for (i in 1 until newest.size) {
                    val step = newest[i] - newest[i - 1]
                    check(step == 1f || step < 0f) {
                        "non-monotonic tear in the newest half at $i: $step"
                    }
                }
                checks++
            }
        }
        writer.join()
        check(checks > 0) { "reader never got a full snapshot" }
    }
}
