package com.snipsnap.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.snipsnap.shell.SongCursor
import kotlin.concurrent.thread

/**
 * ARRANGE's transport: streams an interleaved stereo [mix] (a song
 * mixdown, [com.snipsnap.shell.Arranger.mixdown]'s own output) forward
 * through a float `AudioTrack`, always from the top — there is no needle
 * to scrub here, only PLAY and STOP.
 *
 * The stream/track/drain shape below is [TapeVoice]'s own — a generation
 * counter so a fresh [start] always wins over whatever thread was
 * running before, and a drain that waits for a short clip's last buffer
 * to actually finish playing rather than cutting it off the instant the
 * write loop empties — copied rather than shared because the two differ
 * in the one place that matters: this is stereo (`CHANNEL_OUT_STEREO`,
 * interleaved LR) where TAPE's is mono, and generalizing the shared
 * class over channel count buys nothing when there are exactly two call
 * sites and each already knows its own answer.
 */
class MixVoice private constructor(
    private val mix: FloatArray,
    private val sampleRate: Int,
    private val cursor: SongCursor?,
) : AudioVoice {

    constructor(mix: FloatArray, sampleRate: Int) : this(mix, sampleRate, null)

    /**
     * SONG's live play: streams what [cursor] reads from [mix] (play from a
     * section, loop it, go to a queued one) instead of the mix from the
     * top. A [start] does not rewind a cursor; make a new voice instead.
     */
    constructor(cursor: SongCursor, mix: FloatArray, sampleRate: Int) : this(mix, sampleRate, cursor)

    @Volatile private var track: AudioTrack? = null
    @Volatile private var heard = 0L

    /** True once the stream played out to its end and drained; false while playing or after [stop]. */
    @Volatile var ended: Boolean = false
        private set

    /**
     * Frames the speaker has played since [start]: the track's playback
     * head, which runs about a buffer behind what has been written. What
     * the cursor wrote ahead is not heard yet.
     */
    fun heardFrames(): Long {
        val t = track ?: return heard
        val h = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() ?: return heard
        heard = h
        return h
    }

    private val running = AtomicBoolean(false)
    private val position = AtomicInteger(0)
    private val generation = AtomicInteger(0)
    private var streamThread: Thread? = null
    private val focusOwners = AtomicInteger(0)

    override fun silence() = stop()

    /** Begin streaming from the top. Safe to call while already running — see [TapeVoice.start]'s own KDoc for the handoff this mirrors. */
    fun start() {
        position.set(0)
        heard = 0L
        ended = false
        running.set(true)
        val myGeneration = generation.incrementAndGet()
        focusOwners.incrementAndGet()
        AudioFocus.acquire(this)
        streamThread = thread(name = "MixVoice", isDaemon = true) { runLoop(myGeneration) }
    }

    /** Signal the stream thread to stop. Non-blocking — see [TapeVoice.stop]'s own KDoc. */
    fun stop() {
        running.set(false)
    }

    /** Release for good — call once, from the screen's exit. See [TapeVoice.release]'s own KDoc. */
    fun release() {
        running.set(false)
        generation.incrementAndGet()
        streamThread?.join(200)
        streamThread = null
    }

    private fun padToFillBuffer(track: AudioTrack, alreadyWrittenFrames: Int, myGeneration: Int): Int {
        val remaining = track.bufferSizeInFrames - alreadyWrittenFrames
        if (remaining <= 0) return 0
        val silence = FloatArray(minOf(remaining, BLOCK_FRAMES) * CHANNELS)
        var padded = 0
        while (padded < remaining) {
            if (generation.get() != myGeneration) break
            val chunkFrames = minOf(silence.size / CHANNELS, remaining - padded)
            val w = try {
                track.write(silence, 0, chunkFrames * CHANNELS, AudioTrack.WRITE_BLOCKING)
            } catch (e: IllegalStateException) {
                break
            }
            if (w <= 0) break
            padded += w / CHANNELS
        }
        return padded
    }

    private fun runLoop(myGeneration: Int) {
        val totalFrames = mix.size / CHANNELS
        val track = try {
            buildTrack(sampleRate, totalFrames)
        } catch (e: Exception) {
            Log.w(TAG, "MixVoice: buildTrack rejected the format, staying silent", e)
            running.set(false)
            if (focusOwners.decrementAndGet() == 0) AudioFocus.release(this)
            return
        }
        var ranToCompletion = false
        var framesWritten = 0
        this.track = track
        try {
            runCatching { track.play() }
            val block = FloatArray(BLOCK_FRAMES * CHANNELS)
            while (running.get() && generation.get() == myGeneration) {
                val at = position.get()
                val n = if (cursor != null) cursor.read(block, BLOCK_FRAMES) else minOf(BLOCK_FRAMES, totalFrames - at).coerceAtLeast(0)
                if (n <= 0) {
                    running.set(false)
                    ranToCompletion = true
                    framesWritten += padToFillBuffer(track, framesWritten, myGeneration)
                    return
                }
                if (cursor == null) System.arraycopy(mix, at * CHANNELS, block, 0, n * CHANNELS)
                var written = 0
                while (written < n * CHANNELS) {
                    if (!running.get() || generation.get() != myGeneration) return
                    val w = try {
                        track.write(block, written, n * CHANNELS - written, AudioTrack.WRITE_BLOCKING)
                    } catch (e: IllegalStateException) {
                        return
                    }
                    if (w <= 0) return
                    written += w
                }
                position.addAndGet(n)
                framesWritten += n
            }
        } finally {
            // Read before a flush, which may put the head back to zero.
            heardFrames()
            if (ranToCompletion) {
                val drainTimeoutNanos = (framesWritten * 1_000_000_000L / sampleRate) + DRAIN_MARGIN_NANOS
                val deadline = System.nanoTime() + drainTimeoutNanos
                var drained = false
                while (System.nanoTime() < deadline) {
                    if (generation.get() != myGeneration) break
                    val played = runCatching { track.playbackHeadPosition }.getOrDefault(framesWritten)
                    if (played >= framesWritten) {
                        drained = true
                        break
                    }
                    Thread.sleep(DRAIN_POLL_MILLIS)
                }
                if (drained) heard = framesWritten.toLong()
                if (!drained) {
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                }
            } else {
                runCatching { track.pause() }
                runCatching { track.flush() }
            }
            if (this.track === track) this.track = null
            if (ranToCompletion && generation.get() == myGeneration) ended = true
            runCatching { track.release() }
            if (focusOwners.decrementAndGet() == 0) AudioFocus.release(this)
        }
    }

    private companion object {
        const val TAG = "MixVoice"
        const val CHANNELS = 2
        const val BLOCK_FRAMES = 2048
        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MILLIS = 150
        const val DRAIN_MARGIN_NANOS = 300_000_000L
        const val DRAIN_POLL_MILLIS = 5L

        fun buildTrack(sampleRate: Int, totalFrames: Int): AudioTrack = AudioTrack.Builder()
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
            .setBufferSizeInBytes(bufferBytes(sampleRate, totalFrames))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        /** Same short-clip buffer cap as [TapeVoice.bufferBytes] — see its KDoc for the on-device measurement behind it. */
        fun bufferBytes(sampleRate: Int, totalFrames: Int): Int {
            val wanted = sampleRate * BUFFER_MILLIS / 1000 * CHANNELS * BYTES_PER_FLOAT
            val clipBytes = totalFrames * CHANNELS * BYTES_PER_FLOAT
            val capped = if (clipBytes in 1 until wanted) clipBytes else wanted
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            return maxOf(capped, minimum)
        }
    }
}
