package com.snipsnap.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.snipsnap.audio.Pcm
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * A shared file's audio as a [Snip] (F3.2): the app-side half of the
 * decode contract in `:audio`'s `DecodeContract`.
 *
 * Two doors. A WAV goes straight through [WavReader] — the same reader
 * the CLI and every kit use, bit for bit, no codec in the way. Anything
 * else (an MP3, an M4A, the audio track of an MP4 someone screen-
 * recorded) goes through the platform: [MediaExtractor] finds the first
 * audio track, [MediaCodec] decodes it, and the PCM comes out at
 * whatever rate and channel count the file carried — `SnipStore.import`
 * folds and resamples afterward, so this stays a faithful decode and
 * nothing more. That is what `DecodeContract.verify` wants to see:
 * right duration, right channel count, the same signal; an encoder's
 * delay and its gentle loss are the codec's business, not ours.
 *
 * The codec's output is 16-bit PCM unless it says float; both are read.
 * The decode stops at [MAX_FRAMES] so a shared album side never becomes
 * a gigabyte of floats or a minute of decoding — `SnipStore.import` caps
 * shorter still, and says so in words. A WAV is bounded the same way by
 * [MAX_WAV_BYTES], as a refusal, since the reader takes the whole file.
 *
 * Refusals are [IllegalArgumentException]s in plain words: the toast
 * shows them as they are.
 */
object MediaDecode {

    /** Ten minutes at 48 k, stereo: more than any import keeps, little enough to hold. */
    private const val MAX_FRAMES = 48_000 * 60 * 10

    /**
     * The most WAV the reader is handed, in bytes: 96 MB is three minutes
     * of 96 k / 24-bit stereo, well past `SnipStore.import`'s own cap.
     * [WavReader] wants the whole file in memory, so a bigger share is
     * refused in words rather than read toward an out-of-memory.
     */
    private const val MAX_WAV_BYTES = 96 * 1024 * 1024

    private const val TIMEOUT_US = 10_000L

    /** How many bytes of the head decide "this is a WAV" — RIFF….WAVE. */
    private const val SNIFF_BYTES = 12

    fun decode(context: Context, uri: Uri): Snip {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: ""
        // A short head is not a failure to open: a stream may hand back
        // fewer bytes than asked, and a file shorter than twelve bytes is
        // simply not a WAV. Only a stream that will not open at all is.
        val head = resolver.openInputStream(uri)?.use { s -> readUpTo(s, SNIFF_BYTES) }
            ?: throw IllegalArgumentException("the shared file could not be opened")

        if (isWav(head) || mime == "audio/wav" || mime == "audio/x-wav" || mime == "audio/wave") {
            val bytes = resolver.openInputStream(uri)?.use { s -> readUpTo(s, MAX_WAV_BYTES + 1) }
                ?: throw IllegalArgumentException("the shared file could not be opened")
            require(bytes.size <= MAX_WAV_BYTES) {
                "that WAV is over ${MAX_WAV_BYTES / (1024 * 1024)} MB - the tape takes three minutes, trim it first"
            }
            return WavReader.read(bytes)
        }
        return decodeCompressed(context, uri)
    }

    /** Up to [max] bytes of [stream], looping over short reads; the result is exactly what was there, up to [max]. */
    private fun readUpTo(stream: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(minOf(max, 64 * 1024))
        var total = 0
        while (total < max) {
            val n = stream.read(chunk, 0, minOf(chunk.size, max - total))
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun isWav(head: ByteArray): Boolean =
        head.size >= SNIFF_BYTES &&
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() && head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte()

    private fun decodeCompressed(context: Context, uri: Uri): Snip {
        val extractor = MediaExtractor()
        try {
            // setDataSource throws IO, Security and IllegalArgument
            // exceptions alike; every one is a refusal in words here, so
            // the toast carries the reason instead of a generic line.
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw IllegalArgumentException("the shared file could not be opened: ${e.message ?: e.javaClass.simpleName}", e)
            }
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            val trackFormat = format ?: throw IllegalArgumentException("nothing to hear in that - no audio track")
            val trackMime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(track)

            val codec = try {
                MediaCodec.createDecoderByType(trackMime)
            } catch (e: Exception) {
                throw IllegalArgumentException("this phone has no decoder for $trackMime", e)
            }
            try {
                codec.configure(trackFormat, null, null, 0)
                codec.start()
                return drain(extractor, codec, trackFormat)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * The standard synchronous decode loop: feed the extractor's samples
     * in, read PCM buffers out until the end-of-stream flag comes back.
     * The output format is read when the codec announces it (and from
     * the first buffer's format when it doesn't — some decoders skip the
     * announcement), because the rate and channel count the *track*
     * claims can differ from what the decoder actually emits.
     */
    private fun drain(extractor: MediaExtractor, codec: MediaCodec, trackFormat: MediaFormat): Snip {
        val info = MediaCodec.BufferInfo()
        var rate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val out = FloatList()
        val scratch = Scratch()
        var inputDone = false
        var outputDone = false
        var capped = false

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outIndex >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val room = MAX_FRAMES * channels - out.size
                        capped = appendPcm(buffer, info.size, floatPcm, out, room, scratch)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    // The cap is the end of the decode, not just of the
                    // keeping: an album side past ten minutes is not
                    // decoded to be thrown away. The codec is stopped and
                    // released by the caller's finally either way.
                    if (capped || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        }
        require(channels in 1..2) { "a $channels-channel file - the deck takes mono or stereo" }
        val samples = out.toArray()
        // A whole number of frames, whatever the last buffer's boundary was.
        val frames = samples.size / channels
        require(frames > 0) { "nothing to hear in that - the decoder returned no audio" }
        return Snip(if (samples.size == frames * channels) samples else samples.copyOf(frames * channels), channels, rate)
    }

    /**
     * Appends [size] bytes of [buffer]'s PCM as floats, up to [room]
     * samples; true when the cap was hit. The bytes go through [Pcm]
     * (`:audio`, tested) rather than a buffer view: the same symmetric
     * 16-bit scale [WavReader] reads with, so a decoded MP3 and a WAV of
     * the same signal land at the same level, and a NaN a float decoder
     * emits becomes silence instead of poisoning the deck.
     */
    private fun appendPcm(
        buffer: ByteBuffer,
        size: Int,
        floatPcm: Boolean,
        out: FloatList,
        room: Int,
        scratch: Scratch,
    ): Boolean {
        val bytesPerSample = if (floatPcm) 4 else 2
        val available = size / bytesPerSample
        val n = minOf(available, room)
        val bytes = scratch.bytes(n * bytesPerSample)
        buffer.get(bytes, 0, n * bytesPerSample)
        val floats = scratch.floats(n)
        val written = if (floatPcm) {
            Pcm.floatToFloat(bytes, 0, n * bytesPerSample, floats, 0)
        } else {
            Pcm.int16ToFloat(bytes, 0, n * bytesPerSample, floats, 0)
        }
        out.addAll(floats, written)
        return available > n
    }

    /** Two reusable arrays for the copy out of the codec's buffer — grown to the largest block seen, never per block. */
    private class Scratch {
        private var byteBuf = ByteArray(1 shl 16)
        private var floatBuf = FloatArray(1 shl 15)

        fun bytes(size: Int): ByteArray {
            if (size > byteBuf.size) byteBuf = ByteArray(size)
            return byteBuf
        }

        fun floats(size: Int): FloatArray {
            if (size > floatBuf.size) floatBuf = FloatArray(size)
            return floatBuf
        }
    }

    /** A growable float array — the decode's length is not known up front. */
    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun addAll(src: FloatArray, count: Int) {
            if (size + count > data.size) {
                var grown = data.size
                while (size + count > grown) grown *= 2
                data = data.copyOf(grown)
            }
            System.arraycopy(src, 0, data, size, count)
            size += count
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
