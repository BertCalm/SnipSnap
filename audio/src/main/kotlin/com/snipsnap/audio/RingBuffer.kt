package com.snipsnap.audio

/**
 * The rolling capture buffer — the thing that lets you snip *after* you hear it.
 *
 * Fixed capacity, interleaved samples, oldest frames overwritten once full.
 * Sixty seconds of 44.1 kHz stereo is about 21 MB as floats, which is the
 * intended working size.
 *
 * Threading: one writer (the capture loop reading `AudioRecord`) and one reader
 * (the UI thread taking a snapshot when the user taps snip). Both go through a
 * lock. That would be wrong in a hard real-time callback, but the Android
 * capture path is a normal thread doing blocking reads, so the simpler
 * correctness guarantee is worth more here than lock-freedom.
 */
class RingBuffer(
    /** Capacity in frames. A frame is one sample per channel. */
    val capacityFrames: Int,
    val channels: Int,
    val sampleRate: Int,
) {
    init {
        require(capacityFrames > 0) { "capacityFrames must be positive: $capacityFrames" }
        require(channels in 1..2) { "channels must be 1 or 2: $channels" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
    }

    private val lock = Any()
    private val buffer = FloatArray(capacityFrames * channels)

    /** Write position, in frames, modulo capacity. */
    private var writeFrame = 0

    /** Total frames ever written; saturates conceptually at "full". */
    private var totalFrames = 0L

    /** Frames currently retained, up to [capacityFrames]. */
    val availableFrames: Int
        get() = synchronized(lock) { minOf(totalFrames, capacityFrames.toLong()).toInt() }

    /** Seconds currently retained. */
    val availableSeconds: Float
        get() = availableFrames.toFloat() / sampleRate

    /** Seconds the buffer can hold when full. */
    val capacitySeconds: Float
        get() = capacityFrames.toFloat() / sampleRate

    /**
     * Append interleaved samples.
     *
     * [count] is a sample count, not a frame count — it is whatever
     * `AudioRecord.read` returned — and must be a whole number of frames.
     */
    fun write(samples: FloatArray, count: Int = samples.size) {
        require(count >= 0 && count <= samples.size) { "count out of range: $count" }
        require(count % channels == 0) { "count $count is not a whole number of frames" }
        if (count == 0) return

        synchronized(lock) {
            val frames = count / channels

            // A write larger than the buffer would wrap over itself; only the
            // tail could survive anyway, so keep just that.
            if (frames >= capacityFrames) {
                val startSample = count - capacityFrames * channels
                System.arraycopy(samples, startSample, buffer, 0, capacityFrames * channels)
                writeFrame = 0
                totalFrames += frames
                return
            }

            val firstChunkFrames = minOf(frames, capacityFrames - writeFrame)
            System.arraycopy(
                samples, 0,
                buffer, writeFrame * channels,
                firstChunkFrames * channels,
            )

            val remaining = frames - firstChunkFrames
            if (remaining > 0) {
                System.arraycopy(
                    samples, firstChunkFrames * channels,
                    buffer, 0,
                    remaining * channels,
                )
            }

            writeFrame = (writeFrame + frames) % capacityFrames
            totalFrames += frames
        }
    }

    /**
     * The most recent [frames] frames, oldest first.
     *
     * Returns fewer than asked for if the buffer hasn't filled yet. This is the
     * snip: the user taps, and what they already heard is right here.
     */
    fun snapshot(frames: Int = capacityFrames): FloatArray {
        require(frames >= 0) { "frames must not be negative: $frames" }

        synchronized(lock) {
            val available = minOf(totalFrames, capacityFrames.toLong()).toInt()
            val take = minOf(frames, available)
            if (take == 0) return FloatArray(0)

            val out = FloatArray(take * channels)

            // Walk back `take` frames from the write head, wrapping if needed.
            val startFrame = ((writeFrame - take) % capacityFrames + capacityFrames) % capacityFrames
            val firstChunkFrames = minOf(take, capacityFrames - startFrame)

            System.arraycopy(
                buffer, startFrame * channels,
                out, 0,
                firstChunkFrames * channels,
            )

            val remaining = take - firstChunkFrames
            if (remaining > 0) {
                System.arraycopy(
                    buffer, 0,
                    out, firstChunkFrames * channels,
                    remaining * channels,
                )
            }

            return out
        }
    }

    /** The most recent [seconds] of audio, oldest first. */
    fun snapshotSeconds(seconds: Float): FloatArray {
        require(seconds >= 0f) { "seconds must not be negative: $seconds" }
        return snapshot((seconds * sampleRate).toInt())
    }

    /** Drop everything. Used when a capture session ends. */
    fun clear() {
        synchronized(lock) {
            buffer.fill(0f)
            writeFrame = 0
            totalFrames = 0
        }
    }

    companion object {
        /**
         * A buffer sized in seconds.
         *
         * 60 s stereo at 44.1 kHz is ~21 MB — comfortable on any device that can
         * run the capture API at all.
         */
        fun ofSeconds(seconds: Float, channels: Int = 2, sampleRate: Int = 44_100): RingBuffer {
            require(seconds > 0f) { "seconds must be positive: $seconds" }
            return RingBuffer((seconds * sampleRate).toInt(), channels, sampleRate)
        }
    }
}
