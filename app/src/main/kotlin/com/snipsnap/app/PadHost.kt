package com.snipsnap.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.snipsnap.shell.StreamFacts
import com.snipsnap.shell.VoiceAllocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One kit's sound: the native [engine], the [allocator] that keeps its
 * voices honest, and the one loop that drains the engine's endings back into
 * the allocator. Whoever has the kit's pads in hand holds this - the grid,
 * the beat, the roll's step editor - instead of each building an engine of
 * its own, because two engines on one kit are two streams (the second falls
 * back to the shared path, and every ending reaches whichever drain happens
 * to get there first) and two allocators that disagree about who is
 * sounding.
 *
 * The allocator is the host's, and so are the stops a hit owes it: a caller
 * that takes a voice from [allocator] still stops what the allocation
 * displaced (`allocation.choked + allocation.stolen`) before it sounds the
 * pad, exactly as KIT's and the beat's own hit functions do. The host
 * deliberately has no helper that does both, so each of those lines stays
 * where `ConventionTest`'s voice-allocation law reads them.
 *
 * Main thread, except what [PadEngine] documents as safe elsewhere. The
 * state a screen draws from ([up], [bankReady], [voiceCount], [latency]) is
 * Compose state, written only from [open], [pump] and the load [request]
 * starts.
 */
class PadHost(preferredSampleRate: Int) {

    val engine = PadEngine(preferredSampleRate)

    /** Built at the engine's own polyphony so the status line and the engine cannot drift: at the cap the engine steals exactly the voice the allocator said it would. */
    val allocator: VoiceAllocator = VoiceAllocator(maxVoices = PadEngine.MAX_VOICES)

    /** A stream is running: [open] got one, and no route change has killed it since. False reads as NO STREAM. */
    var up by mutableStateOf(false)
        private set

    /**
     * The first bank has been committed. Until then `PadEngine.clickSampleIndex`
     * is -1 and a click is a no-op, so a count-in would be four silent beats.
     * It stays true through a reload: the engine plays the old bank, click
     * included, until the new one is committed whole, so a reload is not a gap
     * in what the host can do. Only a fresh host starts not-ready.
     */
    var bankReady by mutableStateOf(false)
        private set

    /** What the allocator counts, refreshed each [pump]. The fullscreen grid's status line reads it. */
    var voiceCount by mutableIntStateOf(0)
        private set

    /** What the device says a round trip costs, polled about once a second. */
    var latency by mutableStateOf(StreamFacts.latency(null))
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wanted: KitShelf.Entry? = null
    private var loading: Job? = null
    private var lastLatencyAt = 0L
    private var closed = false

    /** Start the stream. The result is [up]; a host that never got one still holds and plays nothing. */
    fun open() {
        if (closed) return
        up = engine.start()
    }

    /**
     * Ask for [entry]'s pads to be the bank. The first ask loads at once;
     * every later one waits [RELOAD_DEBOUNCE_MS] for the next, and loads
     * only the last. A commit ends every voice sounding (the engine adopts
     * its new bank whole), so a drag on TUNE that wrote the kit forty times
     * would otherwise cut the beat forty times.
     *
     * Asking again with the kit it already has is nothing: a screen, the
     * beat and the App can all ask for the same entry and it loads once. A
     * kit's identity changes on every real edit, which is why identity and
     * not equality is the test.
     */
    fun request(entry: KitShelf.Entry) {
        if (closed) return
        val prior = wanted
        if (prior != null && prior.kit === entry.kit && prior.dir == entry.dir) return
        wanted = entry
        loading?.cancel()
        val wait = if (prior == null) 0L else RELOAD_DEBOUNCE_MS
        loading = scope.launch {
            if (wait > 0L) delay(wait)
            // The load is blocking disk IO, so it runs to its end even if a
            // newer ask cancels this job; the cancel is seen on the way
            // back, and the newer ask's load commits after it.
            withContext(Dispatchers.IO) { engine.load(entry) }
            bankReady = true
        }
    }

    /**
     * One turn of the housekeeping a frame owes the engine: hand the voices
     * that ended since the last turn back to the allocator, restart a
     * stream a route change killed, and keep [voiceCount] and [latency]
     * current. Cheap, and safe to call from more than one loop in a frame:
     * the endings are drained once and they all land on the same allocator.
     */
    fun pump(nowNanos: Long) {
        if (closed) return
        for (id in engine.drainEnded()) allocator.voiceEnded(id)
        if (engine.needsRestart()) up = engine.start()
        val count = allocator.activeCount
        if (count != voiceCount) voiceCount = count
        if (nowNanos - lastLatencyAt >= StreamFacts.POLL_NANOS) {
            lastLatencyAt = nowNanos
            latency = StreamFacts.latency(engine.latencyMillis(), engine.isShared())
        }
    }

    /** [pump] at screen rate, for a holder to run from its own composition - the frame clock is the composition's. */
    suspend fun pumpLoop() {
        while (true) {
            pump(withFrameNanos { it })
        }
    }

    /**
     * Everything quiet: the allocator forgets its voices and the engine
     * fades them. A backgrounded phone should not keep a choke group
     * ringing, nor leave the allocator counting voices no frame loop is
     * draining the endings of.
     */
    fun allOff() {
        allocator.allOff()
        engine.allOff()
        voiceCount = allocator.activeCount
    }

    /** Idempotent. The stream stops, the engine is destroyed, and a load still on its way finds nothing to commit to. */
    fun close() {
        if (closed) return
        closed = true
        up = false
        scope.cancel()
        engine.close()
    }

    companion object {
        /** How long a kit change waits for the next before its bank is rebuilt. */
        const val RELOAD_DEBOUNCE_MS = 250L
    }
}
