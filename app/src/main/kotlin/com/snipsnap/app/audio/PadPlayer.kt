package com.snipsnap.app.audio

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
 * Needs no `Context` — `SoundPool.Builder` and `AudioAttributes.Builder`
 * ask for none.
 */
class PadPlayer(private val maxStreams: Int = 8) : PadSound {

    private var pool: SoundPool = newPool()

    /** slot → sound id, present only once the pool reports the load done. */
    private val ready = HashMap<Int, Int>()
    private val pending = HashMap<Int, Int>()

    /**
     * `SoundPool` recycles sample ids after `unload()`, so an id alone
     * cannot tell a pre-[reset] load's stale completion apart from a
     * post-[reset] load that was handed the same id — a generation
     * counter compared *after* the fact has the same problem, since the
     * new load's own bookkeeping overwrites whatever the counter said.
     * The one thing `SoundPool` does *not* recycle is the pool object
     * itself: [reset] retires the current pool wholesale and builds a
     * fresh one, so a callback closed over the pool it belongs to can
     * simply check identity against whichever pool is live *right now*.
     * A stale callback from a retired pool fails that check and is
     * dropped, no matter what sample id it reports.
     */
    private fun newPool(): SoundPool {
        val newPool = SoundPool.Builder()
            .setMaxStreams(maxStreams)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        newPool.setOnLoadCompleteListener { source, sampleId, status ->
            if (source !== pool) {
                Log.w(TAG, "load completed on a discarded pool: sample=$sampleId, dropped")
                return@setOnLoadCompleteListener
            }
            val slot = pending.entries.firstOrNull { it.value == sampleId }?.key
            if (slot == null) {
                Log.w(TAG, "pad load completed for an unknown sample: sample=$sampleId, dropped")
            } else if (status == 0) {
                ready[slot] = sampleId
                pending.remove(slot)
                Log.i(TAG, "pad $slot loaded (sample $sampleId)")
            } else {
                pending.remove(slot)
                Log.w(TAG, "pad load failed: sample=$sampleId status=$status slot=$slot")
            }
        }
        return newPool
    }

    override fun load(slot: Int, file: File) {
        if (ready.containsKey(slot) || pending.containsKey(slot)) return
        if (!file.isFile) {
            Log.w(TAG, "pad $slot: no such file ${file.name}")
            return
        }
        val sampleId = pool.load(file.absolutePath, 1)
        if (sampleId == 0) {
            Log.w(TAG, "pad $slot: pool refused the load")
            return
        }
        pending[slot] = sampleId
    }

    override fun reset() {
        pool.release()
        pool = newPool()
        ready.clear()
        pending.clear()
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

    companion object {
        const val TAG = "SnipSnapPad"
    }
}
