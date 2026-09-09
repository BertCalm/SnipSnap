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
 *
 * **Audio focus.** This is the one voice in the app with a genuine
 * pause/resume: [AudioFocus.acquire] on construction, [AudioFocus.release]
 * on [close]. [silence] is `track.pause()` (no flush - nothing buffered is
 * discarded, [write]'s blocking loop just stalls until more room opens up,
 * which is exactly LOOP's own transport stalling with it), and [resume] is
 * `track.play()` picking the same position back up. See [AudioFocus]'s KDoc
 * for why most of this app's other voices leave [resume] as a no-op and
 * this one doesn't.
 */
class AndroidAudioSink(override val sampleRate: Int) : AudioSink, AudioVoice {

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

    init {
        track.play()
        AudioFocus.acquire(this)
    }

    override fun silence() {
        runCatching { track.pause() }
    }

    override fun resume() {
        runCatching { track.play() }
    }

    override fun write(block: FloatArray) {
        // Blocking: this call is the transport's pacing. It returns when the
        // device has room, which is exactly when the next interval is due.
        //
        // stop() only flips a flag the engine checks BETWEEN intervals — it
        // never interrupts a write already blocked in here. onDestroy gives
        // the audio thread a 1s join before calling close(), which drains an
        // in-flight write in the normal case (the device buffer is ~150ms),
        // but a timed join is not a guarantee: a stalled device or a route
        // change can leave this thread still inside track.write() when
        // close() calls track.release() on another thread. AudioTrack is not
        // documented as safe for a concurrent write + release, and the
        // observed failure mode is write() throwing IllegalStateException
        // out from under a track that vanished mid-call. The AudioSink
        // contract says write must not throw, so that exception is caught
        // here and treated exactly like the existing "device gone" path
        // below: drop this call rather than propagate.
        //
        // An interrupt-and-join alternative was considered and rejected:
        // AudioTrack.write is a native blocking call, not an interruptible
        // Java one, so Thread.interrupt() does not reliably wake it — it
        // would not actually shrink the race. A "closing" AtomicBoolean
        // checked before each write was also considered, but it only
        // narrows the window (close() can still land between the check and
        // the call); catching the exception closes it regardless of timing.
        var written = 0
        while (written < block.size) {
            val n = try {
                track.write(block, written, block.size - written, AudioTrack.WRITE_BLOCKING)
            } catch (e: IllegalStateException) {
                return
            }
            if (n <= 0) return // device gone or stopped; drop rather than spin
            written += n
        }
    }

    override fun close() {
        AudioFocus.release(this)
        runCatching { track.stop() }
        runCatching { track.release() }
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
