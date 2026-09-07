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

    /**
     * Writes silence into [track] until [alreadyWritten] plus the padding
     * reaches the track's own buffer capacity (a no-op once [alreadyWritten]
     * already meets or exceeds it — the normal case for any clip longer than
     * the buffer). Returns how many silent frames were actually accepted, so
     * the caller's frames-written tally — and therefore the drain wait's
     * target — includes them.
     */
    private fun padToFillBuffer(track: AudioTrack, alreadyWritten: Int, myGeneration: Int): Int {
        val remaining = track.bufferSizeInFrames - alreadyWritten
        if (remaining <= 0) return 0
        val silence = FloatArray(minOf(remaining, BLOCK_FRAMES))
        var padded = 0
        while (padded < remaining) {
            if (generation.get() != myGeneration) break
            val chunk = minOf(silence.size, remaining - padded)
            val w = try {
                track.write(silence, 0, chunk, AudioTrack.WRITE_BLOCKING)
            } catch (e: IllegalStateException) {
                break
            }
            if (w <= 0) break
            padded += w
        }
        return padded
    }

    private fun runLoop(myGeneration: Int) {
        // Construction cost lives here, off the UI thread that calls start()
        // — this is a MODE_STREAM track (no asset to decode or buffer to
        // prefill up front, unlike AndroidAudioSink's long-lived track,
        // which is built once for the process and kept), so building one
        // per stream is cheap relative to the write loop it's about to run.
        val track = buildTrack(sampleRate, tape.size)
        // Set once the loop exits because the tape genuinely ran out (as
        // opposed to a signaled stop(), a superseding start(), a vanished
        // track, or a short write) — the one case where the *last* buffer's
        // worth of already-written-but-not-yet-rendered audio should be
        // allowed to finish playing rather than being cut off. See the
        // drain comment in the `finally` block below for why this matters.
        var ranToCompletion = false
        var framesWritten = 0
        try {
            runCatching { track.play() }
            val block = FloatArray(BLOCK_FRAMES)
            while (running.get() && generation.get() == myGeneration) {
                val at = cursor.get()
                if (at >= tape.size) {
                    running.set(false)
                    ranToCompletion = true
                    // Even a buffer capped to the clip's own size (see
                    // bufferBytes()'s KDoc) can't shrink below the device's
                    // own AudioTrack.getMinBufferSize() floor — measured on
                    // this device, that floor is bigger than some one-shots
                    // (RIM's 3208 frames vs. a 5304-frame minimum), which
                    // leaves the buffer under-filled and the HAL never
                    // starts outputting at all. Padding with silence up to
                    // the buffer's actual size is threshold-agnostic
                    // regardless of *why* the buffer came up short — it
                    // reaches 100% fill either way. Silence is inaudible,
                    // so this is safe even when the cap in bufferBytes()
                    // already made padding unnecessary (remaining <= 0).
                    framesWritten += padToFillBuffer(track, framesWritten, myGeneration)
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
                framesWritten += n
            }
        } finally {
            // Every exit path lands here — a signaled stop(), a superseding
            // start(), a vanished track, and running off the end of the tape
            // alike — and every one of them settles and releases only the
            // track this thread itself built. Nothing else can ever be
            // holding it.
            //
            // But they don't all want the same settle: `write(WRITE_
            // BLOCKING)` only blocks until data is *accepted* into the
            // track's internal buffer, not until it's actually rendered —
            // for a one-shot short enough to fit in a single buffer (most
            // of THUMP/TINES' percussive voices — RIM and BLOCK both land
            // here), the writes return almost immediately and the tape
            // "finishes" before a single frame has left the speaker.
            // `pause()` + `flush()` — the correct, deliberate choice for
            // every *interrupted* exit below — is "for an immediate stop"
            // per AudioTrack's own docs, and discards exactly that unplayed
            // tail. MODE_STREAM's documented alternative is `stop()`
            // ("audio will stop playing after the last buffer that was
            // written has been played") — measured on this device, that
            // documented behavior doesn't hold: calling `stop()` here
            // flips `playState` straight to STOPPED with `playback
            // HeadPosition` frozen at whatever it already was, same as
            // `pause()`+`flush()` would. So the ranToCompletion path
            // doesn't call `stop()` at all — it leaves the track in the
            // PLAYING state `play()` already put it in, and just waits
            // (bounded, and abortable the same way the write loop above
            // is) for `playbackHeadPosition` to catch up to what was
            // written on its own. A superseding start() or an explicit
            // stop() during that wait still wants the immediate cutoff, so
            // an aborted or timed-out drain falls through to the same
            // pause()+flush() the interrupted paths use, rather than to
            // release() directly.
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
                if (!drained) {
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                }
            } else {
                runCatching { track.pause() }
                runCatching { track.flush() }
            }
            runCatching { track.release() }
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 2048
        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MILLIS = 150

        // Extra slack on top of the linear playback-time estimate, before
        // giving up on the drain wait and falling back to an immediate
        // cutoff. Measured on-device: a short one-shot's `playbackHead
        // Position` doesn't start advancing the instant the drain wait
        // begins — there's HAL/mixer startup latency on top of the
        // straight-line duration (a real BLOCK hit took ~550ms wall clock
        // to fully drain against a ~360ms linear estimate, a ~190ms gap) —
        // so the margin is generous rather than the couple-buffers'-worth
        // a fixed device might need; a one-shot is short regardless, so
        // the worst case here is still well under a second.
        const val DRAIN_MARGIN_NANOS = 300_000_000L
        const val DRAIN_POLL_MILLIS = 5L

        fun buildTrack(sampleRate: Int, tapeFrames: Int): AudioTrack = AudioTrack.Builder()
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
            .setBufferSizeInBytes(bufferBytes(sampleRate, tapeFrames))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        /**
         * A one-shot shorter than [BUFFER_MILLIS] can never fill a
         * streaming buffer sized for the full 150ms — measured on-device,
         * some HALs never start actual output until the buffer is filled
         * to capacity (a 3208-frame RIM hit in a 6615-frame buffer sat at
         * `playbackHeadPosition == 0` for 800ms despite `playState ==
         * PLAYING`, while a 10577-frame COWBELL hit — which fills the same
         * buffer to capacity and beyond — drained correctly). So the
         * buffer is capped to the clip's own length whenever that's
         * shorter than the streaming default: writing the whole clip then
         * fills it to exactly 100%, which is threshold-agnostic by
         * construction. Longer clips keep the original streaming-sized
         * buffer untouched.
         */
        fun bufferBytes(sampleRate: Int, tapeFrames: Int): Int {
            val wanted = sampleRate * BUFFER_MILLIS / 1000 * BYTES_PER_FLOAT
            val tapeBytes = tapeFrames * BYTES_PER_FLOAT
            val capped = if (tapeBytes in 1 until wanted) tapeBytes else wanted
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            return maxOf(capped, minimum)
        }
    }
}
