package com.snipsnap.app

import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One screen's nonblocking boundary to its owned RR player. Every Port call runs
 * on the same FIFO worker, including reads, release, route polling and destruction.
 * UI calls only change short-lived state/cancellation flags and enqueue work.
 * In particular, disposal never waits for OwnedPlayer.open's IO monitor.
 */
internal class MurmurRoundRobinScreenSession(
    private val supportedMidi: IntRange,
    private val port: Port,
    private val worker: Worker = SerialWorker(),
) : AutoCloseable {
    interface Port {
        val ready: Boolean
        val activeCount: Int
        val hasPendingStops: Boolean
        fun open(cancelled: () -> Boolean)
        fun chord(notes: List<Int>, velocity: Float): Boolean
        fun release(note: Int)
        fun allOff()
        fun poll()
        fun close()
    }
    /** Owned FIFO executor. execute must accept until shutdown; shutdown drains accepted work. */
    interface Worker {
        fun execute(task: () -> Unit)
        fun shutdown()
    }
    data class State(
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val foreground: Boolean = true,
        val activeCount: Int = 0,
        val pendingStops: Boolean = false,
        val error: String? = null,
        val closed: Boolean = false,
    ) {
        val canPlay: Boolean get() = loaded && foreground && !loading && !closed
        val needsPoll: Boolean get() = loaded && !loading && !closed && (activeCount > 0 || pendingStops)
    }
    @Volatile var state = State()
        private set
    private var generation = 0L
    private var cancellation: AtomicBoolean? = null
    private var pollQueued = false
    // Accessed only by the worker. Shared pitches release when their last gesture releases.
    private val held = linkedMapOf<Int, List<Int>>()

    @Synchronized fun load(): Boolean {
        if (state.closed || state.loading || !state.foreground) return false
        ++generation
        val cancel = AtomicBoolean(false)
        cancellation = cancel
        state = state.copy(loading = true, error = null)
        worker.execute {
            var error: String? = null
            try {
                held.clear()
                port.allOff()
                if (cancel.get()) throw CancellationException()
                port.open(cancel::get)
            } catch (_: CancellationException) {
                // The old owner, if any, remains usable after staged cancellation.
            } catch (_: Exception) {
                error = "Could not load this instrument. Try reloading it."
            } finally {
                val observed = observe()
                synchronized(this) {
                    if (!state.closed && cancellation === cancel) {
                        cancellation = null
                        state = observed.applyTo(state.copy(loading = false, error = error))
                    }
                }
            }
        }
        return true
    }

    @Synchronized fun cancelLoading() { cancellation?.set(true) }

    /** Final MIDI tones, after chord-tool expansion; range-check BEFORE deduplicating. */
    @Synchronized fun press(gesture: Int, finalNotes: List<Int>, velocity: Float): Boolean {
        if (!state.canPlay || !velocity.isFinite() || velocity <= 0f || velocity > 1f ||
            finalNotes.size !in 1..128 || finalNotes.any { it !in supportedMidi }) return false
        val notes = finalNotes.distinct()
        if (notes.size !in 1..MurmurRoundRobinAdmission.MAX_VOICES) return false
        val epoch = generation
        worker.execute {
            if (!current(epoch, playing = true) || held.containsKey(gesture)) return@execute
            control {
                // One call publishes the whole chord or none; a rejected chord owns no releases.
                if (port.chord(notes, velocity)) held[gesture] = notes
            }
        }
        return true // Queued, not a claim of native admission.
    }

    @Synchronized fun release(gesture: Int) {
        if (state.closed || state.loading) return
        val epoch = generation
        worker.execute {
            if (!current(epoch)) return@execute
            control {
                val notes = held.remove(gesture).orEmpty()
                val stillHeld = held.values.flatten().toSet()
                notes.filterNot { it in stillHeld }.forEach(port::release)
            }
        }
    }

    /** Invalidate queued attacks immediately; the worker also releases already accepted notes. */
    @Synchronized fun silence() {
        if (state.closed) return
        cancellation?.set(true)
        val epoch = ++generation
        worker.execute {
            if (!current(epoch)) return@execute
            control { held.clear(); port.allOff() }
        }
    }

    @Synchronized fun foreground(value: Boolean) {
        if (state.closed || state.foreground == value) return
        state = state.copy(foreground = value)
        if (!value) silence()
    }

    /** Coalesced tick remains useful after all fingers lift, through tails and refused stops. */
    @Synchronized fun poll() {
        if (!state.loaded || state.loading || state.closed || pollQueued) return
        pollQueued = true
        val epoch = generation
        worker.execute {
            try { if (current(epoch)) control { port.poll() } }
            finally { synchronized(this) { pollQueued = false } }
        }
    }

    @Synchronized override fun close() {
        if (state.closed) return
        cancellation?.set(true)
        ++generation
        state = state.copy(closed = true, loading = false, loaded = false, activeCount = 0, pendingStops = false)
        worker.execute { held.clear(); port.close() }
        worker.shutdown()
    }

    @Synchronized private fun current(epoch: Long, playing: Boolean = false): Boolean =
        !state.closed && generation == epoch && (!playing || state.canPlay)

    private fun control(action: () -> Unit) {
        var error: String? = null
        try { action() }
        catch (_: Exception) { held.clear(); error = "Playback stopped. Reload this instrument to continue." }
        val observed = observe()
        synchronized(this) {
            if (!state.closed) state = observed.applyTo(state.copy(error = error ?: state.error))
        }
    }

    private data class Observation(val loaded: Boolean, val count: Int, val debt: Boolean) {
        fun applyTo(previous: State) = previous.copy(loaded = loaded, activeCount = count, pendingStops = debt)
    }
    /** Worker only, OUTSIDE the session monitor: even a slow native route retry cannot block UI cancellation. */
    private fun observe() = Observation(port.ready, port.activeCount, port.hasPendingStops)

    private class SerialWorker : Worker {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Murmur RR screen").apply { isDaemon = true }
        }
        override fun execute(task: () -> Unit) { executor.execute(task) }
        override fun shutdown() { executor.shutdown() }
    }
}
