package com.snipsnap.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.snipsnap.audio.GrainField
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * GRAIN FIELD's live voice: a finger-driven granular texture over [source],
 * continuously re-triggering grains near whatever (x, y) [setTarget] last
 * wrote, for as long as [gate] is held on.
 *
 * **Thread ownership — copied from [TapeVoice].** [start] is the one place
 * that spawns the render thread, and it does so at most once: a
 * [running] `AtomicBoolean` guards it so a second call is a no-op (this
 * voice's contract is "idempotent start," unlike `TapeVoice`'s "every start
 * supersedes the last"). The render thread owns the [track] end-to-end for
 * writing — it plays and writes it — but unlike `TapeVoice`, this class's
 * own [release] (not the thread's `finally`) is the one that calls
 * `AudioTrack.release()`, because the design calls for `release()` to join
 * the thread *then* tear down the track from the caller's side. That still
 * only works race-free for the same reason `TapeVoice`'s KDoc gives for
 * building the track inside the thread that uses it: nothing outside this
 * class ever touches [track] while the render thread might still be
 * writing to it, except [release] itself, and [release] is documented and
 * guaranteed to run at most once (`released` guards re-entry). See
 * [release] for exactly how that race is closed rather than merely
 * narrowed.
 *
 * **Cross-thread state.** Three `@Volatile` fields — [targetX], [targetY],
 * [gated] — are the *entire* channel from the UI thread into the render
 * loop; the UI can write them at any rate (a drag callback firing at 60Hz,
 * a finger-down/up event) and the render loop reads each once per trigger
 * decision. A [generation] `AtomicInteger` (bumped once, by [release])
 * plus [running] tell a live loop iteration to wind down without the UI
 * thread ever touching the loop's private, allocation-free working state
 * (grain slots, the mix block, the RNG) directly — exactly the guard
 * `TapeVoice` uses to protect its own per-thread state from a racing
 * caller.
 *
 * **Render loop.** Every [TRIGGER_HOP] frames, while gated, one grain is
 * triggered: read the volatile target once, weighted-randomly choose among
 * the [NEAREST_K] grains (by squared distance in the map's normalized x/y
 * space) closest to it, and claim a slot (stealing the oldest if all
 * [MAX_OVERLAP] are already active) that will read `source[start + i] *
 * hann[i]` for [GrainField.GRAIN_FRAMES] samples at `1f / MAX_OVERLAP`
 * gain. Nothing in the loop allocates, locks, or logs: the mix block, the
 * grain-slot arrays, the nearest-K scratch, and the Hann window are all
 * preallocated once in [runLoop]; grains reference [source] by offset only
 * — no per-trigger copy. The mixed block is clamped to `[-1, 1]` (16
 * grains at `1/16` gain should never clip, but the clamp is one pass and
 * cheap insurance) and blocking-written to [track] — the write is the
 * loop's only clock, exactly [AndroidAudioSink]'s idiom.
 */
class GrainVoice(
    private val source: FloatArray,
    private val sampleRate: Int,
    private val map: GrainField.GrainMap,
) {

    @Volatile private var targetX: Float = 0.5f
    @Volatile private var targetY: Float = 0.5f
    @Volatile private var gated: Boolean = false

    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    /** Bumped once, by [release], so a stale loop iteration notices and exits. */
    private val generation = AtomicInteger(0)

    @Volatile private var renderThread: Thread? = null
    @Volatile private var track: AudioTrack? = null

    /**
     * Spin the render thread. Idempotent: a second call while already
     * running is a no-op (no second thread, no second `AudioTrack`) — the
     * `compareAndSet` below is the only gate, so two threads racing to
     * call `start()` at once still produce exactly one render thread. A
     * call after [release] is also a no-op; this voice is terminal once
     * released.
     *
     * [track] and [renderThread] are written here (on whichever thread
     * calls `start()`, normally the UI thread) and read from [release],
     * possibly on that same thread later — `@Volatile` is what makes that
     * write visible to a later `release()` call even without a shared lock;
     * the render thread itself never reads either field, it receives the
     * track as a constructor argument to [runLoop] instead, so no
     * happens-before edge is needed on that side.
     *
     * `buildTrack` can throw if the platform rejects the format (an
     * exception, not a crash this class should leave you stuck after): if
     * it does, [running] is reset to `false` before the exception
     * propagates, so a caller that catches it can retry `start()` instead
     * of finding this voice permanently wedged in "running but no thread."
     */
    fun start() {
        if (released.get()) return
        if (!running.compareAndSet(false, true)) return
        val myGeneration = generation.incrementAndGet()
        val t = try {
            buildTrack(sampleRate)
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
        track = t
        renderThread = thread(name = "GrainVoice", isDaemon = true) { runLoop(t, myGeneration) }
    }

    /**
     * Where to trigger the next grains from, in the map's normalized 0..1
     * (x, y) space. Safe to call from the UI thread at any rate — each
     * write lands in a `@Volatile` field the render loop reads once per
     * trigger, never mid-decision.
     */
    fun setTarget(x: Float, y: Float) {
        targetX = x
        targetY = y
    }

    /** Finger down/up: sound only triggers new grains while [on] is true. */
    fun gate(on: Boolean) {
        gated = on
    }

    /**
     * Terminal: stop the render thread and release [track]. Safe to call
     * more than once — only the first call does anything.
     *
     * **The race this closes.** `release()` runs on the UI thread and may
     * land while the render thread is blocked inside `track.write()` (the
     * loop's own pacing clock — see [runLoop]). Signaling [running] false
     * and bumping [generation] here doesn't wake that blocked call; only
     * the device draining more buffer does. So this can't simply wait for
     * the loop to notice before touching the track — instead, exactly like
     * [AndroidAudioSink]'s own documented choice, it accepts that
     * `track.release()` may run concurrently with a write still in flight,
     * and relies on the render loop to catch the `IllegalStateException`
     * that produces (see the `write` call in [runLoop]) and exit quietly
     * rather than propagate. `renderThread?.join(RELEASE_JOIN_MILLIS)` is a
     * bounded best-effort wait — if the loop happens to already be between
     * writes it exits within the timeout and the join returns clean; if the
     * loop is genuinely stuck inside a stalled write, the timeout expires,
     * [release] calls `track.release()` anyway, and that call is what
     * finally unblocks the stuck write (by making it throw). Either path
     * ends with the track released and the thread gone or dying; there is
     * no window where the track is released out from under the loop
     * *without* the loop being the one to observe the resulting exception,
     * because nothing else ever calls `track.write` on this instance.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        generation.incrementAndGet()
        renderThread?.join(RELEASE_JOIN_MILLIS)
        renderThread = null
        runCatching { track?.release() }
        track = null
    }

    private fun runLoop(track: AudioTrack, myGeneration: Int) {
        val grains = map.grains
        val grainFrames = map.grainFrames
        val hann = FloatArray(grainFrames) { i ->
            (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / grainFrames)).toFloat()
        }

        val block = FloatArray(BLOCK_FRAMES)

        // Active-grain slots: parallel primitive arrays, no boxing, no per-trigger allocation.
        val slotActive = BooleanArray(MAX_OVERLAP)
        val slotStart = IntArray(MAX_OVERLAP)
        val slotPos = IntArray(MAX_OVERLAP)
        val slotOrder = LongArray(MAX_OVERLAP)
        var triggerCounter = 0L

        // Nearest-K scratch, reused every trigger.
        val nearIdx = IntArray(NEAREST_K)
        val nearDist = FloatArray(NEAREST_K)
        val nearWeight = FloatArray(NEAREST_K)

        val rng = Random(RNG_SEED)
        val grainGain = 1f / MAX_OVERLAP

        runCatching { track.play() }
        while (running.get() && generation.get() == myGeneration) {
            java.util.Arrays.fill(block, 0f)

            var offset = 0
            while (offset < BLOCK_FRAMES) {
                if (gated && grains.isNotEmpty()) {
                    triggerCounter++
                    triggerGrain(
                        grains, targetX, targetY, rng,
                        nearIdx, nearDist, nearWeight,
                        slotActive, slotStart, slotPos, slotOrder,
                        triggerCounter,
                    )
                }
                mixSubBlock(
                    block, offset, TRIGGER_HOP,
                    slotActive, slotStart, slotPos, grainGain,
                    hann, grainFrames, source,
                )
                offset += TRIGGER_HOP
            }

            for (i in block.indices) block[i] = block[i].coerceIn(-1f, 1f)

            var written = 0
            while (written < block.size) {
                if (!running.get() || generation.get() != myGeneration) return
                val n = try {
                    track.write(block, written, block.size - written, AudioTrack.WRITE_BLOCKING)
                } catch (e: IllegalStateException) {
                    // The track was released out from under us — see release()'s KDoc
                    // for why this is the expected, race-closing outcome, not a bug.
                    // running is left alone here: release() already set it false before
                    // this could happen, so it already reflects reality.
                    return
                }
                if (n <= 0) {
                    // Device gone/stopped with no release() in progress (e.g. a route
                    // change) — an unsignaled death. Mark running false so a caller
                    // checking it sees a dead voice rather than one that looks alive
                    // with no thread behind it.
                    running.set(false)
                    return
                }
                written += n
            }
        }
    }

    /**
     * Weighted-random choice among the [NEAREST_K] grains closest to
     * ([targetX], [targetY]) in the map's normalized space (linear scan —
     * hundreds of grains at [TRIGGER_HOP]-paced triggers is cheap), then
     * claims a slot for it: the first inactive slot, or the oldest active
     * one (by [slotOrder]) if all [MAX_OVERLAP] are busy.
     */
    private fun triggerGrain(
        grains: List<GrainField.Grain>,
        targetX: Float,
        targetY: Float,
        rng: Random,
        nearIdx: IntArray,
        nearDist: FloatArray,
        nearWeight: FloatArray,
        slotActive: BooleanArray,
        slotStart: IntArray,
        slotPos: IntArray,
        slotOrder: LongArray,
        order: Long,
    ) {
        var filled = 0
        for (i in grains.indices) {
            val g = grains[i]
            val dx = g.x - targetX
            val dy = g.y - targetY
            val d2 = dx * dx + dy * dy
            if (filled < NEAREST_K) {
                nearIdx[filled] = i
                nearDist[filled] = d2
                filled++
            } else {
                var worstJ = 0
                var worstD = nearDist[0]
                for (j in 1 until NEAREST_K) {
                    if (nearDist[j] > worstD) {
                        worstD = nearDist[j]
                        worstJ = j
                    }
                }
                if (d2 < worstD) {
                    nearIdx[worstJ] = i
                    nearDist[worstJ] = d2
                }
            }
        }
        if (filled == 0) return

        var totalWeight = 0f
        for (k in 0 until filled) {
            val w = 1f / (nearDist[k] + DISTANCE_EPSILON)
            nearWeight[k] = w
            totalWeight += w
        }
        var r = rng.nextFloat() * totalWeight
        var chosen = nearIdx[filled - 1]
        for (k in 0 until filled) {
            r -= nearWeight[k]
            if (r <= 0f) {
                chosen = nearIdx[k]
                break
            }
        }

        val grain = grains[chosen]

        var slot = -1
        for (s in 0 until MAX_OVERLAP) {
            if (!slotActive[s]) {
                slot = s
                break
            }
        }
        if (slot == -1) {
            var oldest = 0
            var oldestOrder = slotOrder[0]
            for (s in 1 until MAX_OVERLAP) {
                if (slotOrder[s] < oldestOrder) {
                    oldestOrder = slotOrder[s]
                    oldest = s
                }
            }
            slot = oldest
        }
        slotActive[slot] = true
        slotStart[slot] = grain.startFrame
        slotPos[slot] = 0
        slotOrder[slot] = order
    }

    /** Advances every active grain slot by up to [len] frames, summing into [block] at [offset]. */
    private fun mixSubBlock(
        block: FloatArray,
        offset: Int,
        len: Int,
        slotActive: BooleanArray,
        slotStart: IntArray,
        slotPos: IntArray,
        grainGain: Float,
        hann: FloatArray,
        grainFrames: Int,
        source: FloatArray,
    ) {
        for (s in 0 until MAX_OVERLAP) {
            if (!slotActive[s]) continue
            var pos = slotPos[s]
            val start = slotStart[s]
            var i = 0
            while (i < len && pos < grainFrames) {
                val srcIdx = start + pos
                if (srcIdx >= 0 && srcIdx < source.size) {
                    block[offset + i] += source[srcIdx] * hann[pos] * grainGain
                }
                pos++
                i++
            }
            slotPos[s] = pos
            if (pos >= grainFrames) slotActive[s] = false
        }
    }

    private companion object {
        /** Written-to-device block size; the outer render-loop write unit. */
        const val BLOCK_FRAMES = 1024

        /** Trigger cadence: one grain considered every this many frames while gated. */
        const val TRIGGER_HOP = 512

        /** Simultaneous active grains; a new trigger steals the oldest once this is full. */
        const val MAX_OVERLAP = 16

        /** Neighbors considered for weighted-random selection around the live target. */
        const val NEAREST_K = 8

        /** Keeps inverse-distance weights finite when a grain sits exactly on the target. */
        const val DISTANCE_EPSILON = 1e-6f

        /** Deterministic per-voice RNG seed — reproducible triggering, not security-sensitive. */
        const val RNG_SEED = 0x67721A1EL

        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MILLIS = 150

        /** Bounded wait in [GrainVoice.release] — see its KDoc for why this can't be a guarantee. */
        const val RELEASE_JOIN_MILLIS = 200L

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
