package com.snipsnap.app

import android.content.ContentResolver
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.snipsnap.audio.Pcm
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.shell.SnipStore
import java.nio.ByteBuffer

/**
 * The demux (F3.2): a file the user shared or opened into the app becomes
 * a [Snip] at the source's own rate and channel count, ready for
 * [SnipStore.importDecoded]. Two doors:
 *
 * - A WAV under [WAV_DIRECT_MAX_BYTES] goes through the app's own
 *   [WavReader], the sample-exact path that already handles 24-bit and
 *   float files.
 * - Everything else — an MP4 or MOV from the screen recorder, an M4A, an
 *   MP3, an oversized WAV — goes through [MediaExtractor] and the
 *   platform's decoder for the first audio track, block by block into one
 *   growing buffer, stopping at [SnipStore.IMPORT_MAX_SECONDS] so a whole
 *   album never has to fit in memory as floats.
 *
 * Returns null when there is no audio track to pull out. Anything else
 * that goes wrong throws with a reason in words; the app says so.
 */
object MediaImport {

    /** A WAV bigger than this is read through the extractor with the cap, not slurped whole. */
    private const val WAV_DIRECT_MAX_BYTES = 64L * 1024 * 1024
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    fun decode(context: Context, uri: Uri): Snip? {
        val resolver = context.contentResolver
        if (isSmallWav(resolver, uri)) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("can't open that file")
            return WavReader.read(bytes)
        }
        return decodeWithExtractor(context, uri)
    }

    private fun isSmallWav(resolver: ContentResolver, uri: Uri): Boolean {
        val size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: return false
        if (size < 0 || size > WAV_DIRECT_MAX_BYTES) return false
        val head = ByteArray(12)
        val n = resolver.openInputStream(uri)?.use { it.read(head) } ?: return false
        return n == 12 &&
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    private fun decodeWithExtractor(context: Context, uri: Uri): Snip? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sink = PcmSink(
                rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                readRaw(extractor, sink, format)
            } else {
                runCodec(extractor, format, mime, sink)
            }
            return sink.toSnip()
        } finally {
            extractor.release()
        }
    }

    /** A WAV or other PCM container: the extractor hands out the samples itself, no codec. */
    private fun readRaw(extractor: MediaExtractor, sink: PcmSink, format: MediaFormat) {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        var buffer = ByteBuffer.allocate(256 * 1024)
        while (!sink.full) {
            val size = extractor.sampleSize
            if (size < 0) break
            if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size.toInt())
            buffer.clear()
            val n = extractor.readSampleData(buffer, 0)
            if (n < 0) break
            sink.take(buffer, 0, n, encoding)
            if (!extractor.advance()) break
        }
    }

    private fun runCodec(extractor: MediaExtractor, format: MediaFormat, mime: String, sink: PcmSink) {
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && !sink.full) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder's own word on what it emits beats the
                        // container's (HE-AAC doubles the rate; some
                        // decoders emit float): re-read all three.
                        val out = codec.outputFormat
                        encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        sink.reformat(
                            rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        )
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0) sink.take(buf, info.offset, info.size, encoding)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /**
     * One growing float buffer the decode pours into. Grows by doubling,
     * copies bytes out of the codec's buffer through one reusable scratch
     * array, and reports [full] once [SnipStore.IMPORT_MAX_SECONDS] of the
     * source are in — the loop above stops feeding then.
     */
    private class PcmSink(var rate: Int, var channels: Int) {
        private var data = FloatArray(1 shl 20)
        private var count = 0
        private var scratch = ByteArray(1 shl 16)

        val full: Boolean
            get() = count / channels >= SnipStore.IMPORT_MAX_SECONDS.toLong() * rate

        fun reformat(rate: Int, channels: Int) {
            // Only honoured before any samples land: a format change
            // mid-stream would silently mix rates in one buffer.
            if (count == 0) {
                this.rate = rate
                this.channels = channels
            }
        }

        fun take(buf: ByteBuffer, offset: Int, size: Int, encoding: Int) {
            if (size > scratch.size) scratch = ByteArray(size)
            buf.position(offset)
            buf.get(scratch, 0, size)
            val incoming = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) size / 4 else size / 2
            if (count + incoming > data.size) {
                var grown = data.size.toLong()
                while (count + incoming > grown) grown *= 2
                data = data.copyOf(grown.coerceAtMost(Int.MAX_VALUE - 8L).toInt())
            }
            val written = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                Pcm.floatToFloat(scratch, 0, size, data, count)
            } else {
                Pcm.int16ToFloat(scratch, 0, size, data, count)
            }
            count += written
        }

        fun toSnip(): Snip? {
            if (count == 0) return null
            require(channels in 1..2) { "$channels channels - the tape takes mono or stereo" }
            val frames = count / channels
            return Snip(data.copyOf(frames * channels), channels, rate)
        }
    }
}
