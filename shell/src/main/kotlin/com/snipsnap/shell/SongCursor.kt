package com.snipsnap.shell

import java.util.concurrent.CopyOnWriteArrayList

/**
 * SONG's live transport: plays a song's mixdown ([Arranger.Mix]) pass by
 * pass, so it can start at any section, loop one, or go to a queued one,
 * and keeps a log of what it played for KEEP THIS ORDER.
 *
 * Nothing is rendered here. Every move is the read position jumping
 * between [Arranger.Pass]es of the one mix, so a song played straight
 * through is the mixdown sample for sample, and a jump costs nothing.
 *
 * A move happens only where a pass ends (decision A, 2026-11-09): a
 * queued section starts when the current pass of the current clip
 * finishes, as the MPC's Next Sequence does. What was played is then whole
 * passes, which is exactly what a [com.snipsnap.kit.SongPlan] section
 * holds (a clip and how many times it plays).
 *
 * A jump that does not run on into the next pass of the mix crossfades
 * over [FADE_FRAMES], because a sample-exact cut between two unrelated
 * points of the waveform clicks. What the crossfade cannot fix, and is
 * said rather than hidden:
 * - the section jumped to opens with the ring-out of whatever came before
 *   it in the song, and the section left has its own ring-out cut;
 * - the pull-up before a section marked PULL-UP is in the audio of the
 *   pass before it, so looping that section plays the spinback each time
 *   round; the tape stop at the end of the outro does the same.
 *
 * Threads: [read] runs on the audio thread. [loop] and [queued] are set
 * from the screen and read at the next pass end. The log is read from the
 * screen while the audio thread appends to it.
 *
 * [mix] is interleaved stereo.
 */
class SongCursor(
    private val mix: FloatArray,
    private val passes: List<Arranger.Pass>,
    from: Int,
) {
    /** One pass starting to sound at [stream] frames into the playback. */
    data class Mark(val stream: Long, val section: Int)

    init {
        require(passes.isNotEmpty()) { "a song has at least one pass" }
    }

    private val totalFrames = mix.size / CHANNELS

    /** When on, the playing section starts again from its first pass when its last ends. */
    @Volatile var loop: Boolean = false

    /** The section to go to when the current pass ends; cleared once it starts. Wins over [loop]. */
    @Volatile var queued: Int? = null

    private var pass = firstPassOf(from).takeIf { it >= 0 } ?: 0
    private var at = passes[pass].start
    private var stream = 0L
    private var tail = false

    // The crossfade: the old read position, and how many frames of it are left to fade out.
    private var fadeAt = 0
    private var fadeLeft = 0

    private val marks = CopyOnWriteArrayList<Mark>().apply { add(Mark(0, passes[pass].section)) }

    /** True once the song has played out to the end of its last ring-out. */
    @Volatile var finished: Boolean = false
        private set

    /**
     * Fills [out] (interleaved stereo) with up to [frames] frames and
     * returns how many it wrote; fewer than asked only at the very end.
     */
    fun read(out: FloatArray, frames: Int): Int {
        var n = 0
        while (n < frames) {
            if (!tail && at >= passes[pass].end) {
                val next = nextPass()
                if (next == null) {
                    tail = true
                } else {
                    if (next != pass + 1) {
                        fadeAt = at
                        fadeLeft = FADE_FRAMES
                        at = passes[next].start
                    }
                    pass = next
                    marks += Mark(stream, passes[next].section)
                }
                continue
            }
            val stop = if (tail) totalFrames else passes[pass].end
            if (at >= stop) {
                finished = true
                break
            }
            val take = minOf(frames - n, stop - at)
            copy(out, n, take)
            n += take
        }
        return n
    }

    private fun copy(out: FloatArray, offset: Int, frames: Int) {
        for (f in 0 until frames) {
            for (c in 0 until CHANNELS) {
                var s = sample(at + f, c)
                if (fadeLeft > 0) {
                    // Equal-gain over the fade: the new position rises as the old one falls.
                    val w = (FADE_FRAMES - fadeLeft).toFloat() / FADE_FRAMES
                    s = s * w + sample(fadeAt, c) * (1f - w)
                }
                out[(offset + f) * CHANNELS + c] = s
            }
            if (fadeLeft > 0) {
                fadeAt++
                fadeLeft--
            }
        }
        at += frames
        stream += frames
    }

    private fun sample(frame: Int, channel: Int): Float =
        if (frame in 0 until totalFrames) mix[frame * CHANNELS + channel] else 0f

    /** Where the pass that just ended goes: the queued section, the loop, the song's next pass, or null at the end. */
    private fun nextPass(): Int? {
        val section = passes[pass].section
        queued?.let { q ->
            queued = null
            val first = firstPassOf(q)
            if (first >= 0) return first
        }
        val lastOfSection = pass == passes.lastIndex || passes[pass + 1].section != section
        if (loop && lastOfSection) return firstPassOf(section)
        return if (pass < passes.lastIndex) pass + 1 else null
    }

    private fun firstPassOf(section: Int): Int = passes.indexOfFirst { it.section == section }

    /** The section sounding at [streamFrame] frames into the playback. */
    fun sectionAt(streamFrame: Long): Int = marks.lastOrNull { it.stream <= streamFrame }?.section ?: marks.first().section

    /**
     * The section of every pass that had started sounding by
     * [streamFrame], in order. A pass already written ahead of the speaker
     * but not yet heard when STOP was pressed is not in it.
     */
    fun played(streamFrame: Long = Long.MAX_VALUE): List<Int> = marks.filter { it.stream <= streamFrame }.map { it.section }

    companion object {
        const val CHANNELS = 2

        /** 5 ms at 44.1 kHz. */
        const val FADE_FRAMES = 220
    }
}
