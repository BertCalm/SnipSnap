package com.snipsnap.audio

/** Unity-speed PCM block reader. Positions, seeks and loop bounds are always audio frames. */
class PcmPlayback(
    private val samples: FloatArray,
    val channels: Int = 1,
    startFrame: Int = 0,
    loop: IntRange? = null,
) {
    init {
        require(channels in 1..2) { "PCM playback supports mono or stereo, got $channels channels" }
        require(samples.size % channels == 0) { "PCM playback needs complete interleaved frames" }
    }

    val frameCount: Int = samples.size / channels
    var positionFrame: Int = startFrame.coerceIn(0, frameCount)
        private set

    private val loopStart = loop?.first?.coerceIn(0, frameCount) ?: 0
    private val loopEnd = loop?.let { (it.last.toLong() + 1L).coerceIn(0L, frameCount.toLong()).toInt() } ?: 0
    private val looping = loop != null && loop.first >= 0 && loopEnd > loopStart

    /** Copies up to [maxFrames] complete frames into [out], returning frames copied; zero means end. */
    fun readFrames(out: FloatArray, maxFrames: Int = out.size / channels): Int {
        require(out.size % channels == 0) { "PCM output needs complete interleaved frames" }
        require(maxFrames >= 0 && maxFrames <= out.size / channels) { "PCM frame count exceeds output capacity" }
        if (looping && positionFrame >= loopEnd) positionFrame = loopStart
        val end = if (looping) loopEnd else frameCount
        val count = minOf(maxFrames, end - positionFrame)
        if (count <= 0) return 0
        System.arraycopy(samples, positionFrame * channels, out, 0, count * channels)
        positionFrame += count
        return count
    }
}
