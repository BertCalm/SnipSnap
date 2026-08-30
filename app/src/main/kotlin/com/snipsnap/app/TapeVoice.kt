package com.snipsnap.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The TAPE screen's unity-speed voice: streams [tape] forward through a mono
 * float [AudioTrack], starting from wherever [start] last pointed and running
 * until it hits the end of the buffer or is told to [stop].
 *
 * Deliberately the simplest correct split with `TapeDeckModel`: the UI clock
 * (`TapeScreen`'s `LaunchedEffect`) owns calling `model.step()` and reading
 * `model.position` — this class never touches the model. It free-runs its
 * own cursor at the tape's own sample rate on a background thread, blocking
 * on `AudioTrack.write` the same way `AndroidAudioSink` paces the loop
 * engine. Drag/scrub is visual-only (the brief's call): this voice only ever
 * moves forward at 1.0×, so a coast or glide during a drag is silent until
 * the next [start].
 */
class TapeVoice(private val tape: FloatArray, sampleRate: Int) {

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
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(bufferBytes(sampleRate))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val running = AtomicBoolean(false)
    private val cursor = AtomicInteger(0)
    private var streamThread: Thread? = null

    /**
     * Begin streaming from [frame]. Safe to call while already running — the
     * cursor jumps, the thread keeps going.
     */
    fun start(frame: Int) {
        cursor.set(frame.coerceIn(0, tape.size))
        if (running.compareAndSet(false, true)) {
            runCatching { track.play() }
            streamThread = thread(name = "TapeVoice", isDaemon = true) { runLoop() }
        }
    }

    /** Stop streaming. Idempotent — a stopped voice stopping again is a no-op. */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            streamThread?.join(200)
            streamThread = null
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    /** Release the track for good — call once, from the screen's exit. */
    fun release() {
        stop()
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun runLoop() {
        val block = FloatArray(BLOCK_FRAMES)
        while (running.get()) {
            val at = cursor.get()
            if (at >= tape.size) {
                running.set(false)
                break
            }
            val n = minOf(BLOCK_FRAMES, tape.size - at)
            System.arraycopy(tape, at, block, 0, n)
            var written = 0
            while (written < n) {
                if (!running.get()) return
                val w = try {
                    track.write(block, written, n - written, AudioTrack.WRITE_BLOCKING)
                } catch (e: IllegalStateException) {
                    // The track vanished under us (screen exit racing this write) —
                    // drop the rest of this block rather than propagate, matching
                    // AndroidAudioSink's contract for the same race.
                    running.set(false)
                    return
                }
                if (w <= 0) {
                    running.set(false)
                    return
                }
                written += w
            }
            cursor.addAndGet(n)
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 2048
        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MILLIS = 150

        fun bufferBytes(sampleRate: Int): Int {
            val wanted = sampleRate * BUFFER_MILLIS / 1000 * BYTES_PER_FLOAT
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            return maxOf(wanted, minimum)
        }
    }
}
