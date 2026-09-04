package com.snipsnap.audio

/**
 * The always-listening ring the Retroactive Snip reads from.
 *
 * Single producer (the capture thread), single consumer (the snip action).
 * The WRITE path is the realtime one and does nothing but `arraycopy` and
 * index arithmetic — no allocation, no locks, no logging — per
 * `docs/ANDROID_CAPTURE.md`'s realtime discipline. [RingBuffer] is the
 * offline model this was grown from; it stays lock-based and offline.
 *
 * Consistency contract: [framesWritten] is `@Volatile` and published
 * AFTER the samples land, so a [snapshot] reads a cursor that is never
 * ahead of real data. A write that lands DURING a snapshot can overwrite
 * the snapshot's OLDEST samples mid-copy — accepted and documented: one
 * block of ambiguity at the far edge of a 60-second window is inaudible,
 * and the alternative is a lock on the realtime path.
 */
class CaptureRing(val capacityFrames: Int) {

    private val buf = FloatArray(capacityFrames)

    @Volatile
    var framesWritten: Long = 0L
        private set

    val filledFrames: Int
        get() = minOf(framesWritten, capacityFrames.toLong()).toInt()

    fun write(block: FloatArray, count: Int) {
        val n = minOf(count, capacityFrames)
        val skip = count - n                       // block bigger than the ring: keep its tail
        var pos = ((framesWritten + skip) % capacityFrames).toInt()
        val first = minOf(n, capacityFrames - pos)
        System.arraycopy(block, skip, buf, pos, first)
        if (n > first) System.arraycopy(block, skip + first, buf, 0, n - first)
        framesWritten += count                     // volatile publish, after the copy
    }

    fun snapshot(frames: Int): FloatArray {
        val total = framesWritten                  // one volatile read; consistent basis
        val n = minOf(frames.toLong(), minOf(total, capacityFrames.toLong())).toInt()
        val out = FloatArray(n)
        var pos = ((total - n) % capacityFrames).toInt()
        if (pos < 0) pos += capacityFrames
        val first = minOf(n, capacityFrames - pos)
        System.arraycopy(buf, pos, out, 0, first)
        if (n > first) System.arraycopy(buf, 0, out, first, n - first)
        return out
    }
}
