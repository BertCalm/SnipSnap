package com.snipsnap.loop

import com.snipsnap.audio.Snip
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic, distinguishable fill value derived from a filename.
 *
 * The brief's own fixture returns identical `0.25f`-filled audio for every
 * filename, which makes a bug that hands back the wrong track's block
 * completely invisible — every possible answer looks the same. Deriving the
 * fill value from the requested filename instead means each distinct block
 * bakes to distinguishable audio, so tests can assert that track i actually
 * got track i's own block rather than merely getting "a buffer of the right
 * length from somewhere."
 */
private fun fillFor(sampleFile: String): Float = (sampleFile.hashCode() % 10_000).toFloat() / 10_000f

class ResidencyTest {

    /** Counts how many times a sample was asked for, so we can prove caching. */
    private class CountingSource(private val frames: Int) : SampleSource {
        val loopCalls = AtomicInteger(0)
        override fun loop(sampleFile: String): Snip {
            loopCalls.incrementAndGet()
            val value = fillFor(sampleFile)
            return Snip(FloatArray(frames * 2) { value }, 2, 48_000)
        }
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    private val sameThread = Executor { it.run() }

    private fun session(vararg sizes: Int, bpm: Float = 200f) = Session(
        tracks = sizes.mapIndexed { i, n ->
            Track("t$i", (1..n).map { LoopBlock("t$i-$it.wav") })
        },
        bpm = bpm,
        barsPerInterval = 1,
        sampleRate = 48_000,
    )

    @Test
    fun `bakes on demand and hands back one buffer per track`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val r = Residency(s, CountingSource(s.intervalFrames), sameThread)

        val buffers = r.buffersFor(0)
        assertEquals(6, buffers.size)
        for (i in buffers.indices) {
            assertEquals(s.intervalFrames, buffers[i].frameCount)
            // Each track has exactly one distinct block; the buffer handed
            // back for track i must carry THAT track's audio, not some other
            // track's — this is what a "return the first track's block for
            // everyone" bug would violate.
            assertEquals(
                fillFor("t$i-1.wav"),
                buffers[i].samples[0],
                "track $i got the wrong block's audio",
            )
        }
    }

    @Test
    fun `bakes each distinct block only once`() {
        val s = session(2, 1, 1, 1, 1, 1)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        r.buffersFor(0)
        r.buffersFor(0)
        // Six tracks, six distinct blocks at interval 0 — asked for once each.
        assertEquals(6, src.loopCalls.get())
    }

    @Test
    fun `prefetch fills the cache so the next interval is already there`() {
        val s = session(2, 1, 1, 1, 1, 1)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        val afterFirst = src.loopCalls.get()
        r.prefetch(1)
        val afterPrefetch = src.loopCalls.get()
        val buffers = r.buffersFor(1)

        assertTrue(afterPrefetch > afterFirst, "prefetch should have baked something")
        assertEquals(afterPrefetch, src.loopCalls.get(), "buffersFor should not have baked again")
        // Track 0's chain has two blocks; interval 1 plays its second file —
        // distinguishable from the first, so this proves prefetch(1) baked
        // the right block rather than merely priming the cache with anything.
        assertEquals(fillFor("t0-2.wav"), buffers[0].samples[0])
    }

    @Test
    fun `retain drops buffers no longer needed`() {
        val s = session(4, 1, 1, 1, 1, 1)
        val r = Residency(s, CountingSource(s.intervalFrames), sameThread)

        r.buffersFor(0)
        r.buffersFor(1)
        r.buffersFor(2)
        assertTrue(r.residentCount() > 6, "should be holding more than one interval's worth")

        r.retain(2, 3)
        // Track 0 contributes a distinct block per interval; the other five
        // share their single block. Two intervals is at most 7 distinct blocks.
        assertTrue(r.residentCount() <= 7, "still holding ${r.residentCount()}")
    }

    @Test
    fun `a bpm change invalidates every baked buffer`() {
        val s = session(1, 1, 1, 1, 1, 1, bpm = 200f)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        val before = src.loopCalls.get()
        r.update(s.copy(bpm = 100f))
        r.buffersFor(0)

        assertTrue(src.loopCalls.get() > before, "buffers are the wrong length now and must re-bake")
        assertEquals(r.session().intervalFrames, r.buffersFor(0)[0].frameCount)
    }

    @Test
    fun `a mute does not invalidate anything`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        val before = src.loopCalls.get()
        r.update(s.copy(tracks = s.tracks.mapIndexed { i, t -> t.copy(engaged = i != 0) }))
        r.buffersFor(0)

        assertEquals(before, src.loopCalls.get(), "muting is a gain flag, not a re-bake")
    }

    @Test
    fun `swapping a block bakes only the new one`() {
        val s = session(1, 1, 1, 1, 1, 1)
        val src = CountingSource(s.intervalFrames)
        val r = Residency(s, src, sameThread)

        r.buffersFor(0)
        val before = src.loopCalls.get()
        val swapped = s.copy(
            tracks = s.tracks.mapIndexed { i, t ->
                if (i == 0) t.copy(chain = listOf(LoopBlock("new.wav"))) else t
            },
        )
        r.update(swapped)
        val buffers = r.buffersFor(0)

        assertEquals(before + 1, src.loopCalls.get(), "only the replaced block should re-bake")
        // Track 0 must carry the swapped block's own audio, and every
        // untouched track must still carry ITS own — not each other's, and
        // not the swapped track's.
        assertEquals(fillFor("new.wav"), buffers[0].samples[0], "track 0 should carry the swapped block's audio")
        for (i in 1 until buffers.size) {
            assertEquals(
                fillFor("t$i-1.wav"),
                buffers[i].samples[0],
                "untouched track $i must keep its own audio",
            )
        }
    }

    @Test
    fun `shortening a chain past the playing index still returns six buffers`() {
        val s = session(4, 1, 1, 1, 1, 1)
        val r = Residency(s, CountingSource(s.intervalFrames), sameThread)
        r.buffersFor(3)

        val cut = s.copy(
            tracks = s.tracks.mapIndexed { i, t ->
                if (i == 0) t.copy(chain = t.chain.take(2)) else t
            },
        )
        r.update(cut)
        val buffers = r.buffersFor(3)
        assertEquals(6, buffers.size)
        // Chain shrank from 4 to 2 blocks; interval 3 now wraps to index 1
        // ("t0-2.wav"), not the original index 3 ("t0-4.wav") — a different
        // block's audio, which a stale-index bug would get wrong.
        assertEquals(fillFor("t0-2.wav"), buffers[0].samples[0])
    }
}
