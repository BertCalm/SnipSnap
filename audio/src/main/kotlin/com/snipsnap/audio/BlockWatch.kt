package com.snipsnap.audio

/**
 * The verdict [SilenceWatch] is evidence for: *this app is blocking the
 * capture*, as opposed to *there is simply nothing playing*.
 *
 * Digital silence alone proves nothing — a paused video and an opted-out
 * app look identical on the wire. The platform's "is music playing?"
 * is what tells them apart, and it costs a binder call, so it must not
 * be asked per block. [SilenceWatch] already ticks once per hold period
 * rather than once per block for exactly that reason; this is the other
 * half, and the two together are the whole of the F3.4 test.
 *
 * It lives here, and not in the service that uses it, because it is the
 * only decision in that reader loop and it turns on an interaction
 * between two objects that is easy to get wrong and impossible to see:
 * [SilenceWatch.feed] **zeroes its run when it ticks**, so
 * [SilenceWatch.silent] reads false on the tick block itself even though
 * the stream is still silent. A clear-condition written the obvious way
 * against `silent` would drop the verdict one block after raising it.
 * The cases beside this file hold that down.
 */
class BlockWatch(private val watch: SilenceWatch) {

    /**
     * True while the stream is silent *and* the platform says something
     * is playing. False means either sound is arriving or nothing is
     * playing to be blocked — both of which are "carry on", and neither
     * of which is worth telling anybody about.
     */
    var blocked: Boolean = false
        private set

    /**
     * Consumes [count] samples of [block]. [musicActive] is called at
     * most once per hold period — never per block — so it is the right
     * place to hang a binder call. Returns true when [blocked] changed,
     * which is the caller's cue to publish it.
     */
    fun feed(block: FloatArray, count: Int, musicActive: () -> Boolean): Boolean {
        val was = blocked
        if (watch.feed(block, count)) {
            // A tick: the stream has been silent for a whole hold period,
            // so now it is worth the binder call. Assigning the answer
            // rather than only raising the flag is what lets the verdict
            // come back *down* when the playing stops but the silence
            // does not - a paused song is not a block, and saying so
            // would be a message box about a problem nobody has.
            blocked = musicActive()
        } else if (!watch.silent) {
            // Sound arrived: the run is over and so is any verdict. Note
            // this cannot fire on the block right after a tick, where
            // `silent` is false for the other reason - `feed` returned
            // true there, so this branch was never reached.
            blocked = false
        }
        return blocked != was
    }

    /** Forgets the run and the verdict — for a session that just (re)started. */
    fun reset() {
        watch.reset()
        blocked = false
    }

    companion object {
        /** As the service builds it: a hold in seconds at the session's rate. */
        fun forSeconds(seconds: Double, sampleRate: Int): BlockWatch =
            BlockWatch(SilenceWatch.forSeconds(seconds, sampleRate))
    }
}
