package com.snipsnap.shell

/**
 * Which of the jobs running at once the dock draws.
 *
 * The dock has one line, so it shows one job: [current], the latest begun
 * that has not ended. A job that is still running when a later one begins
 * waits behind it, and is the one shown again once the later one ends.
 *
 * Each job is a [Busy] under an id that [begin] hands out and that is never
 * handed out twice, not even after the job has ended. That is what makes
 * [end] and [update] safe to call late or twice: an id that is not running
 * matches nothing, so an ended job is never brought back by a stray update,
 * and a second [end] cannot take a different job off the board.
 *
 * An [update] changes what a job says (its label, its count), not where it
 * stands: a job begun first does not jump ahead of one begun after it
 * because its count moved on.
 *
 * Not thread-safe: the owner serialises calls (the app's `AppJobs` does).
 */
class JobBoard {

    private var nextId = 1L

    /** Running jobs, oldest first. Replacing a value under its key leaves the key where it was. */
    private val running = LinkedHashMap<Long, Busy>()

    /** Starts [busy] as the latest job and returns the id to [update] and [end] it by. */
    fun begin(busy: Busy): Long {
        val id = nextId++
        running[id] = busy
        return id
    }

    /** Replaces what job [id] says, keeping its place. Nothing happens for an id that is not running. */
    fun update(id: Long, busy: Busy) {
        if (id in running) running[id] = busy
    }

    /** Takes job [id] off the board. Nothing happens for an id that is not running, or already ended. */
    fun end(id: Long) {
        running.remove(id)
    }

    /** The most recently begun job still running, or null when none is. */
    val current: Busy?
        get() = running.values.lastOrNull()
}
