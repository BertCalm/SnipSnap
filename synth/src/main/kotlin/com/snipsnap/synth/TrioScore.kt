package com.snipsnap.synth

import java.util.Collections
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.sqrt

/** One course contact, before any excitation buffer or resonator exists. */
internal data class TrioCourse(val actor: TrioActor, val string: Int, val at: Int, val n: Int,
    val hz: Float, val pickHz: Float, val position: Float, val seed: Int, val gain: Float)

/**
 * A score is timing and energy, independent of the renderer. The grid bounds the
 * beat starts; answers and course offsets can extend beyond it. It does not bound
 * the natural ring or the finite 3.5-second recording.
 */
internal class TrioScore(val gridSpanSeconds: Float, actors: List<TrioActor>, events: List<TrioEvent>,
    courses: List<TrioCourse>) {
    val actors: List<TrioActor> = Collections.unmodifiableList(actors.toList())
    val events: List<TrioEvent> = Collections.unmodifiableList(events.toList())
    val courses: List<TrioCourse> = Collections.unmodifiableList(courses.toList())
    val lastGestureSeconds: Float = courses.maxOfOrNull { it.at.toFloat() / (Dsp.RATE * Dsp.OVERSAMPLE) } ?: 0f
}

/**
 * Keep only the union of contact intervals. Contacts are accumulated in score
 * order, preserving the old Float additions even where two bursts overlap.
 * Playback is sequential, so each sample needs one cursor and one bounds check.
 */
internal class TrioExcitation(private val frames: Int) {
    private data class Burst(val at: Int, val samples: FloatArray, val gain: Float)
    private data class Region(val at: Int, val samples: FloatArray) { val end get() = at + samples.size }
    private val bursts = mutableListOf<Burst>()
    private val regions = mutableListOf<Region>()
    private var cursor = 0
    fun add(at: Int, samples: FloatArray, gain: Float) { bursts += Burst(at, samples, gain) }
    fun prepare() {
        for (b in bursts.sortedBy { it.at }) {
            val from = b.at.coerceAtLeast(0)
            val end = min(frames, b.at + b.samples.size)
            if (end <= from) continue
            val last = regions.lastOrNull()
            if (last != null && from <= last.end) {
                if (end > last.end) regions[regions.lastIndex] = Region(last.at, last.samples.copyOf(end - last.at))
            } else regions += Region(from, FloatArray(end - from))
        }
        for (b in bursts) {
            val region = regions.firstOrNull { b.at < it.end && b.at + b.samples.size > it.at } ?: continue
            for (j in b.samples.indices) {
                val at = b.at + j
                if (at in region.at until region.end) region.samples[at - region.at] += b.samples[j] * b.gain
            }
        }
        bursts.clear()
    }
    operator fun get(index: Int): Float {
        while (cursor < regions.size && index >= regions[cursor].end) cursor++
        if (cursor >= regions.size) return 0f
        val region = regions[cursor]
        return if (index >= region.at) region.samples[index - region.at] else 0f
    }
}

internal object TrioRenderSupport {
    fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("TRIO render cancelled")
    }

    /**
     * Same causal body law as Strings.bodyRing. TRIO's input already fills its
     * recording ceiling, so rendering padded samples beyond that ceiling cannot
     * affect a returned sample or the RMS match. Keep that unused work out.
     */
    fun body(string: FloatArray, table: List<Modes.Mode>, amount: Float, rate: Int): FloatArray {
        if (amount <= 0f || table.isEmpty()) return string
        val drive = FloatArray(string.size)
        var previous = 0f
        for (i in string.indices) { drive[i] = string[i] - previous; previous = string[i] }
        checkCancelled()
        val wet = Modes.ring(drive, 1f, table, rate)
        checkCancelled()
        fun rms(x: FloatArray): Float {
            var sum = 0.0
            for (i in string.indices) sum += x[i].toDouble() * x[i]
            return sqrt(sum / string.size.coerceAtLeast(1)).toFloat()
        }
        val gain = rms(string) / rms(wet).coerceAtLeast(1e-9f)
        // TRIO supplies a complete recording-length input, so reuse it.
        for (i in string.indices) string[i] = string[i] + amount * gain * wet[i]
        return string
    }
}
