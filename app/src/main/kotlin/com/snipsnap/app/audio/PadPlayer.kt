package com.snipsnap.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File

/**
 * What a pad needs in order to make a sound. M4 replaces the
 * implementation with Oboe/AAudio for the latency the play surface wants;
 * keeping the seam here means that swap touches one file.
 */
interface PadSound {
    /** Loads [file] for [slot]. Idempotent per slot. */
    fun load(slot: Int, file: File)

    /**
     * Forgets every loaded pad. Call before loading a different kit —
     * [load] is idempotent per *slot*, so without this the new kit's pad 1
     * would keep playing the old kit's pad 1.
     */
    fun reset()

    /** Fires [slot]. Returns the stream id, or 0 if the pad is not ready. */
    fun play(slot: Int): Int

    fun release()
}

/**
 * The interim player: `SoundPool` decodes short one-shots into memory and
 * fires them with acceptable jitter for browsing a kit. Good enough to
 * hear a pad; not good enough to play a groove, which is why M4 exists.
 *
 * Holds a `Context` — one of the three files in this app allowed to.
 */
class PadPlayer(context: Context, maxStreams: Int = 8) : PadSound {

    private val pool = SoundPool.Builder()
        .setMaxStreams(maxStreams)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    /** slot → sound id, present only once the pool reports the load done. */
    private val ready = HashMap<Int, Int>()
    private val pending = HashMap<Int, Int>()

    /**
     * `SoundPool` recycles sample ids after `unload()`. Without this, a
     * load issued *before* [reset] that completes *after* it could match
     * a reused sample id against the *new* `pending` map and mark the
     * wrong slot ready. Every [reset] bumps [generation]; each pending
     * load remembers the generation it was issued in via
     * [pendingGeneration], and the completion callback drops any load
     * whose generation no longer matches — a stale callback is dropped
     * instead of cross-wiring pads.
     */
    private var generation = 0
    private val pendingGeneration = HashMap<Int, Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            val slot = pending.entries.firstOrNull { it.value == sampleId }?.key
            if (slot == null || pendingGeneration[sampleId] != generation) {
                Log.w(TAG, "pad load completed for a stale generation: sample=$sampleId, dropped")
            } else if (status == 0) {
                ready[slot] = sampleId
                pending.remove(slot)
                pendingGeneration.remove(sampleId)
                Log.i(TAG, "pad $slot loaded (sample $sampleId)")
            } else {
                Log.w(TAG, "pad load failed: sample=$sampleId status=$status slot=$slot")
            }
        }
    }

    override fun load(slot: Int, file: File) {
        if (ready.containsKey(slot) || pending.containsKey(slot)) return
        if (!file.isFile) {
            Log.w(TAG, "pad $slot: no such file ${file.name}")
            return
        }
        val sampleId = pool.load(file.absolutePath, 1)
        pending[slot] = sampleId
        pendingGeneration[sampleId] = generation
    }

    override fun reset() {
        generation++
        for (sampleId in ready.values) pool.unload(sampleId)
        for (sampleId in pending.values) pool.unload(sampleId)
        ready.clear()
        pending.clear()
        pendingGeneration.clear()
    }

    override fun play(slot: Int): Int {
        val sampleId = ready[slot] ?: run {
            Log.w(TAG, "pad $slot not ready")
            return 0
        }
        val stream = pool.play(sampleId, 1f, 1f, 1, 0, 1f)
        Log.i(TAG, "pad $slot hit -> stream $stream")
        return stream
    }

    override fun release() {
        pool.release()
        ready.clear()
        pending.clear()
    }

    /** True once [slot] has finished loading — the exit test's hook. */
    fun isReady(slot: Int): Boolean = ready.containsKey(slot)

    companion object {
        const val TAG = "SnipSnapPad"
    }
}
