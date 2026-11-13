package com.snipsnap.app

import com.snipsnap.shell.MurmurRoundRobinShelf
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A dedicated finite RR player; deliberately not connected to the ordinary shelf UI yet.
 * open() is blocking IO. All control and retry calls share this monitor. A new bank always
 * gets a new native handle and controller; ended IDs from an old bank cannot meet new IDs.
 * The source controls PCM consistency; Backend must copy synchronously and destroy noexcept.
 * Stream/device start memory is separate from the shelf's128MiB PCM working budget.
 */
internal class MurmurRoundRobinOwnedPlayer(
    private val preferredRate: Int = 44100,
    private val backend: Backend = NativeMurmurRoundRobinBank,
    private val retry: Retry = TimerRetry(),
) : AutoCloseable {
    data class Cell(val ordinal: Int, val root: Int, val low: Int, val high: Int,
                    val take: Int, val touch: Int, val frames: Int)
    data class Plan(val name: String, val identity: String, val pcmBytes: Long,
                    val releaseMs: Float, val cells: List<Cell>)
    interface Source {
        /** begin precedes all cells; return only after final identity/cancellation checks. */
        fun transfer(resident: Long, cancelled: () -> Boolean, begin: (Plan) -> Unit,
                     cell: (Cell, FloatArray) -> Unit): Plan
    }
    interface Backend {
        fun prepare(rate: Int): Long
        fun add(handle: Long, pcm: FloatArray): Int
        fun commit(handle: Long): Boolean
        fun start(handle: Long): Boolean
        fun needsRestart(handle: Long): Boolean
        fun port(handle: Long): MurmurRoundRobinAdmission.Port
        /** Must synchronously stop/close the stream and release the handle, and must not throw. */
        fun destroy(handle: Long)
    }
    interface Retry : AutoCloseable {
        /** One delayed task; never execute inline, false means no task was queued. */
        fun post(task: () -> Unit): Boolean
        fun cancel()
    }
    private data class Owned(val handle: Long, val plan: Plan, val control: MurmurRoundRobinAdmission)
    private var current: Owned? = null
    private var closed = false
    private var loading = false
    private var retryTicket = 0L
    private var scheduled = false
    @get:Synchronized val identity: String? get() = current?.plan?.identity
    @get:Synchronized val activeCount: Int get() = current?.control?.activeCount ?: 0
    @get:Synchronized val hasPendingStops: Boolean get() = current?.control?.hasPendingStops ?: false

    fun open(saved: MurmurRoundRobinShelf.SavedBank, shelf: File, cancelled: () -> Boolean = { false }) =
        open(ShelfSource(saved, shelf), cancelled)

    @Synchronized
    internal fun open(source: Source, cancelled: () -> Boolean = { false }) {
        check(!closed && !loading) { "RR player is closed or already loading" }
        loading = true
        var staged = 0L
        var planned: Plan? = null
        val indices = linkedMapOf<Cell, Int>()
        fun checkLoad(): Boolean {
            // The external callback can reenter close; inspect closed AFTER invoking it.
            val cancelledNow = cancelled()
            if (closed || cancelledNow || Thread.currentThread().isInterrupted) throw CancellationException("RR load cancelled")
            return false
        }
        try {
            val result = source.transfer(current?.plan?.pcmBytes ?: 0L, ::checkLoad, { supplied ->
                checkLoad(); check(planned == null)
                val plan = supplied.copy(cells = supplied.cells.toList())
                validate(plan)
                require((current?.plan?.pcmBytes ?: 0L) + plan.pcmBytes <= MurmurRoundRobinShelf.MAX_TRANSFER_PCM_BYTES)
                planned = plan
                staged = backend.prepare(preferredRate)
                check(staged != 0L && staged != current?.handle) { "Cannot prepare a fresh RR bank" }
                checkLoad()
            }, { cell, pcm ->
                checkLoad()
                val plan = checkNotNull(planned)
                require(cell == plan.cells.getOrNull(indices.size) && pcm.size == cell.frames && pcm.all { it.isFinite() })
                val index = backend.add(staged, pcm)
                check(index >= 0 && index !in indices.values) { "RR native sample copy failed or reused a sample index" }
                indices[cell] = index
                checkLoad()
            })
            checkLoad()
            val plan = checkNotNull(planned)
            check(result == plan && indices.size == plan.cells.size) { "Incomplete or changed RR transfer" }
            val zones = plan.cells.groupBy { it.root }.values.map { group ->
                val first = group.first()
                MurmurRoundRobinAdmission.Zone(first.low, first.high, first.root,
                    (0..3).map { take -> (0..2).map { touch ->
                        val cell = group.single { it.take == take && it.touch == touch }
                        MurmurRoundRobinAdmission.Cell(indices.getValue(cell), 0, cell.frames.toLong())
                    } })
            }
            val control = MurmurRoundRobinAdmission(zones, plan.releaseMs, backend.port(staged))
            check(backend.commit(staged)) { "RR native bank commit failed" }
            checkLoad()
            check(backend.start(staged)) { "RR stream start failed; previous bank retained" }
            checkLoad() // Cancellation/close after start still destroys this private handle.
            val replacement = Owned(staged, plan, control)
            val old = current
            cancelRetry()
            current = replacement
            staged = 0L
            old?.let { backend.destroy(it.handle) }
        } finally {
            if (staged != 0L && staged != current?.handle) backend.destroy(staged)
            loading = false
        }
    }

    @Synchronized
    fun noteOnChord(notes: List<Int>, velocity: Float): List<MurmurRoundRobinAdmission.Attack> =
        using { it.control.noteOnChord(notes, velocity) } ?: emptyList()

    @Synchronized fun noteOff(midi: Int) { using { it.control.noteOff(midi); scheduleRetry(it) } }
    @Synchronized fun allOff() { using { it.control.allOff(); scheduleRetry(it) } }
    @Synchronized fun resetRoundRobin() { using { it.control.resetRoundRobin() } }
    @Synchronized fun poll() { using { it.control.poll(); scheduleRetry(it) } }

    private fun <T> using(action: (Owned) -> T): T? {
        check(!loading) { "RR controls cannot reenter a bank load" }
        val owned = current ?: return null
        if (closed) return null
        return try {
            if (backend.needsRestart(owned.handle)) {
                // Keep the SAME controller/monotonic IDs across a route restart. Native
                // start stops old voices and queues their endings; reap before new attacks.
                check(backend.start(owned.handle)) { "RR stream restart failed; reload required" }
                owned.control.poll()
            }
            action(owned)
        } catch (failure: Throwable) {
            cancelRetry(); current = null; backend.destroy(owned.handle); throw failure
        }
    }

    private fun scheduleRetry(owned: Owned) {
        if (!owned.control.hasPendingStops) { cancelRetry(); return }
        if (scheduled) return
        val ticket = ++retryTicket
        scheduled = true
        val posted = retry.post {
            synchronized(this) {
                if (!closed && !loading && scheduled && ticket == retryTicket && current === owned) {
                    scheduled = false
                    // Failure retires the handle in using(); a timer must not resurrect it.
                    runCatching { using { it.control.poll(); scheduleRetry(it) } }
                }
            }
        }
        if (!posted) scheduled = false // Debt remains visible; next poll/control call retries.
    }
    private fun cancelRetry() { ++retryTicket; scheduled = false; retry.cancel() }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelRetry()
        val old = current; current = null
        try { old?.let { backend.destroy(it.handle) } } finally { retry.close() }
    }

    private fun validate(plan: Plan) {
        require(plan.cells.size == 84 && plan.pcmBytes in 1..MurmurRoundRobinShelf.MAX_TRANSFER_PCM_BYTES)
        require(plan.cells.map { it.ordinal } == (0..83).toList())
        require(plan.cells.sumOf { it.frames.toLong() * 4 } == plan.pcmBytes)
        require(plan.cells.all { it.frames > 0 && it.take in 0..3 && it.touch in 0..2 && it.low <= it.root && it.root <= it.high })
        require(plan.cells.groupBy { it.root }.size == 7)
        for (group in plan.cells.groupBy { it.root }.values) {
            require(group.size == 12 && group.map { it.take to it.touch }.toSet().size == 12)
            require(group.all { it.low == group.first().low && it.high == group.first().high })
        }
    }

    private class ShelfSource(val saved: MurmurRoundRobinShelf.SavedBank, val shelf: File) : Source {
        override fun transfer(resident: Long, cancelled: () -> Boolean, begin: (Plan) -> Unit,
                              cell: (Cell, FloatArray) -> Unit): Plan {
            fun mapped(c: MurmurRoundRobinShelf.PcmCell) = Cell(c.ordinal, c.root, c.low, c.high, c.take, c.touch, c.frames)
            fun mapped(p: MurmurRoundRobinShelf.PcmPlan) = Plan(p.saved.name, p.saved.identitySha256,
                p.saved.decodedPcmBytes, p.releaseMs, p.cells.map(::mapped))
            return mapped(MurmurRoundRobinShelf.streamPcm(saved, shelf, resident, cancelled,
                onPlan = { begin(mapped(it)) }, onCell = { c, pcm -> cell(mapped(c), pcm) }))
        }
    }

    private class TimerRetry : Retry {
        private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Murmur RR release retry").apply { isDaemon = true } }
        private var pending: ScheduledFuture<*>? = null
        override fun post(task: () -> Unit): Boolean = try {
            pending = executor.schedule(task, 16L, TimeUnit.MILLISECONDS); true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
        override fun cancel() { pending?.cancel(false); pending = null }
        override fun close() { cancel(); executor.shutdownNow() }
    }
}
