package com.snipsnap.synth

/**
 * A preview worker whose interrupt arrives late: its flag reads clear for the first [clearLooks] times the
 * render looks at it and set every time after, as if a newer request had interrupted the thread just then.
 *
 * Setting the real flag before a render is only ever caught by the render's first look, so a test that does
 * that cannot show a later look is there at all. An engine that has already looked once before its band
 * limit, its decimation, its level or its seam needs the interrupt to arrive after that look: a render that
 * no longer looks where it should then simply finishes. The engines read the flag through
 * `Thread.currentThread().isInterrupted`, which this worker answers itself; nothing in the render changes.
 *
 * Where the interrupt lands, the render stops on the next look, so [looks] after a stop is always one more
 * than [clearLooks] and proves nothing by itself. With `clearLooks = Int.MAX_VALUE` the flag never reads set
 * and [looks] is every look an uninterrupted render makes: an exact count that changes when any one is removed.
 */
internal class LateInterrupt(private val clearLooks: Int) {
    /** How many times the render looked at the flag, counting the look that stopped it. */
    var looks = 0
        private set

    /** Runs [render] on the worker and returns its result, or throws what it threw. */
    fun <T> run(render: () -> T): T {
        var outcome: Result<T>? = null
        val worker = object : Thread("late-interrupt") {
            private var asked = 0
            override fun isInterrupted(): Boolean {
                if (currentThread() !== this) return super.isInterrupted()
                return asked++ >= clearLooks
            }
            override fun run() {
                outcome = runCatching { render() }
                looks = asked
            }
        }
        worker.start()
        worker.join()
        return outcome!!.getOrThrow()
    }
}
