package com.snipsnap.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The TAPE screen's unity-speed voice: streams [tape] forward through a mono
 * float `AudioTrack`, starting from wherever [start] last pointed and running
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
 *
 * **Track ownership.** Earlier revisions shared one long-lived `AudioTrack`
 * across every stream thread and used `Thread.join` to serialize access to
 * it — but a join with any timeout is a race, not a guarantee: if it times
 * out while the old thread is blocked inside `track.write()`, [start] would
 * spawn a second thread onto the *same* track while the first was still
 * alive, and the old thread's `finally` would later pause/flush a track the
 * new thread was actively writing. No amount of shortening the timeout
 * closes that window, so this version removes the shared object instead:
 * each [runLoop] builds, plays, writes to, and releases its **own**
 * `AudioTrack`, which never escapes the thread that owns it. [start] no
 * longer needs to synchronize with a previous thread at all — it just bumps
 * [generation] and spawns a new one; an old thread notices the generation
 * moved on (or [running] went false) and exits on its own, touching only
 * its own track. The worst case from a stale generation racing a fresh one
 * is at most one buffer's worth (~150ms) of overlapping audio on device
 * output — self-healing, and a different order of problem than a foreign
 * thread flushing the live stream.
 */
class TapeVoice(private val tape: FloatArray, private val sampleRate: Int) {

    private val running = AtomicBoolean(false)
    private val cursor = AtomicInteger(0)

    /** Bumped by every [start]; a stream thread stops once this moves past its own value. */
    private val generation = AtomicInteger(0)
    private var streamThread: Thread? = null

    /**
     * Begin streaming from [frame]. Safe to call while already running — a
     * fresh thread (and a fresh, private `AudioTrack`) takes over; whatever
     * thread was running before notices its generation is stale and winds
     * down on its own, without touching the new thread's track.
     */
    fun start(frame: Int) {
        cursor.set(frame.coerceIn(0, tape.size))
        running.set(true)
        val myGeneration = generation.incrementAndGet()
        streamThread = thread(name = "TapeVoice", isDaemon = true) { runLoop(myGeneration) }
    }

    /**
     * Signal the stream thread to stop. Non-blocking, and cheap — no join,
     * no track access — so a caller on the UI thread (a drag gesture
     * starting, most often) never waits on it. The thread settles and
     * releases its own `AudioTrack` on its way out; see [runLoop].
     */
    fun stop() {
        running.set(false)
    }

    /**
     * Release for good — call once, from the screen's exit. Signals then
     * joins briefly: this is not a gesture path (it runs once, on teardown),
     * so a bounded wait here is a reasonable price for not leaving a stream
     * thread outliving the screen that owns [tape]'s backing array. The
     * thread is a daemon regardless, and releases its own track in
     * `runLoop`'s `finally` even if this join times out — so a slow exit
     * here is a delay, never a leak.
     */
    fun release() {
        running.set(false)
        generation.incrementAndGet()
        streamThread?.join(200)
        streamThread = null
    }

    private fun runLoop(myGeneration: Int) {
        // Construction cost lives here, off the UI thread that calls start()
        // — this is a MODE_STREAM track (no asset to decode or buffer to
        // prefill up front, unlike AndroidAudioSink's long-lived track,
        // which is built once for the process and kept), so building one
        // per stream is cheap relative to the write loop it's about to run.
        val track = buildTrack(sampleRate)
        try {
            runCatching { track.play() }
            val block = FloatArray(BLOCK_FRAMES)
            while (running.get() && generation.get() == myGeneration) {
                val at = cursor.get()
                if (at >= tape.size) {
                    running.set(false)
                    return
                }
                val n = minOf(BLOCK_FRAMES, tape.size - at)
                System.arraycopy(tape, at, block, 0, n)
                var written = 0
                while (written < n) {
                    if (!running.get() || generation.get() != myGeneration) return
                    val w = try {
                        track.write(block, written, n - written, AudioTrack.WRITE_BLOCKING)
                    } catch (e: IllegalStateException) {
                        // The track vanished under us (screen exit racing this write) —
                        // drop the rest of this block rather than propagate, matching
                        // AndroidAudioSink's contract for the same race.
                        return
                    }
                    if (w <= 0) return
                    written += w
                }
                cursor.addAndGet(n)
            }
        } finally {
            // Every exit path lands here — a signaled stop(), a superseding
            // start(), and running off the end of the tape alike — and every
            // one of them settles and releases only the track this thread
            // itself built. Nothing else can ever be holding it.
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 2048
        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MILLIS = 150

        fun buildTrack(sampleRate: Int): AudioTrack = AudioTrack.Builder()
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
