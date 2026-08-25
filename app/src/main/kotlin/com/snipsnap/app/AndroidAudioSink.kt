package com.snipsnap.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.snipsnap.loop.AudioSink

/**
 * The device's native output rate.
 *
 * Ask once, at session start, and bake everything at whatever it says. Android
 * is commonly 48 kHz while SnipSnap is 44.1 kHz throughout, and converting per
 * callback would be the one piece of DSP in the hot path.
 */
fun deviceSampleRate(context: Context): Int {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
}

/**
 * Plays interleaved stereo floats through AudioTrack.
 *
 * Float mode rather than 16-bit so the mixer's output needs no conversion, and
 * MODE_STREAM with a blocking write so AudioTrack's own buffer is the clock —
 * the engine advances exactly as fast as the device drains, with no timer to
 * drift against.
 *
 * The device buffer is deliberately unrelated to the mix block. An interval is
 * 512,000 frames at the spec's defaults; asking AudioTrack for four of those
 * would be a 16 MB request it will simply refuse. [write] already loops on
 * partial writes, so the two sizes are independent — this asks for about 150 ms,
 * which is enough to ride out a slow bake and short enough that a mute is felt
 * rather than waited for.
 *
 * That short buffer is also what keeps the UI honest: the blocking write paces
 * the engine, so the interval counter cannot run ahead of what is audible.
 */
class AndroidAudioSink(override val sampleRate: Int) : AudioSink {

    override val channels = 2

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(bufferBytes(sampleRate))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    init { track.play() }

    override fun write(block: FloatArray) {
        // Blocking: this call is the transport's pacing. It returns when the
        // device has room, which is exactly when the next interval is due.
        var written = 0
        while (written < block.size) {
            val n = track.write(block, written, block.size - written, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return // device gone or stopped; drop rather than spin
            written += n
        }
    }

    override fun close() {
        runCatching { track.stop() }
        track.release()
    }

    private companion object {
        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MILLIS = 150

        fun bufferBytes(sampleRate: Int): Int {
            val wanted = sampleRate * BUFFER_MILLIS / 1000 * 2 * BYTES_PER_FLOAT
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            return maxOf(wanted, minimum)
        }
    }
}
