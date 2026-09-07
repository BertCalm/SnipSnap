package com.snipsnap.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * The decode is capped at [MAX_FRAMES] so a shared album side never
 * becomes a gigabyte of floats — `SnipStore.import` caps shorter still,
 * and says so in words.
 *
 * Refusals are [IllegalArgumentException]s in plain words: the toast
 * shows them as they are.
 */
object MediaDecode {

    /** Ten minutes at 48 k, stereo: more than any import keeps, little enough to hold. */
    private const val MAX_FRAMES = 48_000 * 60 * 10

    private const val TIMEOUT_US = 10_000L

    /** How many bytes of the head decide "this is a WAV" — RIFF….WAVE. */
    private const val SNIFF_BYTES = 12

    fun decode(context: Context, uri: Uri): Snip {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: ""
        val head = resolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(SNIFF_BYTES)
            val n = s.read(b)
            if (n == SNIFF_BYTES) b else null
        } ?: throw IllegalArgumentException("the shared file could not be opened")

        if (isWav(head) || mime == "audio/wav" || mime == "audio/x-wav" || mime == "audio/wave") {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("the shared file could not be opened")
            return WavReader.read(bytes)
        }
        return decodeCompressed(context, uri)
    }

    private fun isWav(head: ByteArray): Boolean =
        head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() && head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte()

    private fun decodeCompressed(context: Context, uri: Uri): Snip {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
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
                    if (info.size > 0 && !capped) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val room = MAX_FRAMES * channels - out.size
                        capped = appendPcm(buffer, floatPcm, out, room)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
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

    /** Appends [buffer]'s PCM as floats, up to [room] samples; true when the cap was hit. */
    private fun appendPcm(buffer: ByteBuffer, floatPcm: Boolean, out: FloatList, room: Int): Boolean {
        val b = buffer.order(ByteOrder.nativeOrder())
        if (floatPcm) {
            val fb = b.asFloatBuffer()
            val n = minOf(fb.remaining(), room)
            for (i in 0 until n) out.add(fb.get())
            return fb.remaining() > 0
        }
        val sb = b.asShortBuffer()
        val n = minOf(sb.remaining(), room)
        for (i in 0 until n) out.add(sb.get() / 32768f)
        return sb.remaining() > 0
    }

    /** A growable float array — the decode's length is not known up front. */
    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
