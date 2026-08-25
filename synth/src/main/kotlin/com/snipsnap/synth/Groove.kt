package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * One hit of the demo groove: which arranged pad, on which 16th-note step,
 * how hard. The pattern itself — [Groove.hits] — is the product; the audio
 * render and the MPC 3 embedded clip are two consumers of the same events.
 */
data class GrooveHit(
    /** 0-based index into the arranged list (pad A01 = 0). */
    val padIndex: Int,
    /** 16th-note step from the top of the pattern. */
    val step: Int,
    /** 0..1, doubles as playback gain and as MPC note velocity. */
    val velocity: Float,
)

/**
 * The demo groove: a kit playing itself.
 *
 * Given an arranged kit, build a few bars of pattern from its own pads —
 * kick on the floor, snare on the backbeat, hats keeping eighths, a tom
 * fill into the turnaround, tonal pads walking their notes. Three jobs, one
 * pattern: the audio preview an expansion wants, the "hear it slap" button
 * before export, and — via the MPC 3 track writer — a clip the kit carries
 * onto the hardware, ready to play.
 *
 * Deterministic per (kit, seed): same inputs, same groove, testable.
 */
object Groove {

    const val STEPS_PER_BAR = 16

    /** The pattern as events. [render] plays exactly these. */
    fun hits(
        arranged: List<ArrangedPad?>,
        bars: Int = 4,
        seed: Int = 0,
    ): List<GrooveHit> {
        require(bars in 1..16) { "bars out of range: $bars" }

        val byClass = HashMap<DrumClass, MutableList<Int>>()
        for ((index, pad) in arranged.withIndex()) {
            if (pad == null) continue
            byClass.getOrPut(pad.drumClass) { mutableListOf() }.add(index)
        }
        if (byClass.isEmpty()) return emptyList()

        var rng = if (seed == 0) 1 else seed
        fun roll(): Float { rng = (rng * 1103515245 + 12345) and 0x7fffffff; return rng / 0x7fffffff.toFloat() }

        val out = mutableListOf<GrooveHit>()
        fun place(padIndex: Int, step: Int, velocity: Float) {
            out.add(GrooveHit(padIndex, step, velocity))
        }

        fun first(cls: DrumClass): Int? = byClass[cls]?.firstOrNull()

        val tonal = byClass[DrumClass.TONAL].orEmpty()
        val toms = byClass[DrumClass.TOM].orEmpty()
        val percs = byClass[DrumClass.PERC].orEmpty()

        for (bar in 0 until bars) {
            val base = bar * STEPS_PER_BAR
            val lastBar = bar == bars - 1

            // The floor: boom on 1, push into 3.
            first(DrumClass.KICK)?.let { kick ->
                place(kick, base + 0, 0.9f)
                place(kick, base + 10, 0.85f)
                if (roll() > 0.5f) place(kick, base + 7, 0.7f)
            }
            // The backbeat; clap doubles the second one when there is one.
            first(DrumClass.SNARE)?.let { snare ->
                place(snare, base + 4, 0.85f)
                place(snare, base + 12, 0.9f)
            }
            first(DrumClass.CLAP)?.let { clap -> place(clap, base + 12, 0.6f) }

            // Eighths on the closed hat, ghosted off-beats; open hat lifts
            // the turnaround every other bar (the choke does the rest live).
            first(DrumClass.HAT_CLOSED)?.let { hat ->
                for (s in 0 until STEPS_PER_BAR step 2) {
                    place(hat, base + s, if (s % 4 == 0) 0.5f else 0.3f)
                }
            }
            first(DrumClass.HAT_OPEN)?.let { open ->
                if (bar % 2 == 1) place(open, base + 14, 0.45f)
            }

            // Sparse percussion, seeded, never on the backbeat.
            if (percs.isNotEmpty()) {
                repeat(2) {
                    val s = (roll() * STEPS_PER_BAR).toInt().coerceIn(0, 15)
                    if (s != 4 && s != 12) place(percs[(roll() * percs.size).toInt() % percs.size], base + s, 0.4f)
                }
            }

            // Tonal pads walk their notes across the bar starts; a melodic
            // kit turns this into a little arpeggio all by itself.
            if (tonal.isNotEmpty()) {
                place(tonal[bar % tonal.size], base + 0, 0.55f)
                if (tonal.size > 1) place(tonal[(bar + 2) % tonal.size], base + 8, 0.45f)
            }

            // The fill into the turnaround.
            if (lastBar && toms.isNotEmpty()) {
                for ((i, s) in intArrayOf(12, 13, 14, 15).withIndex()) {
                    place(toms[i % toms.size], base + s, 0.6f)
                }
            }
        }
        return out
    }

    fun render(
        arranged: List<ArrangedPad?>,
        bpm: Float = 92f,
        bars: Int = 4,
        seed: Int = 0,
    ): Snip {
        require(bpm in 40f..220f) { "bpm out of range: $bpm" }

        val rate = arranged.firstNotNullOfOrNull { it?.snip?.sampleRate } ?: 44_100
        val stepFrames = (60.0 / bpm / 4.0 * rate).toInt()
        val tailFrames = rate // let the last hit ring
        val total = stepFrames * STEPS_PER_BAR * bars + tailFrames
        val mix = FloatArray(total)

        val pattern = hits(arranged, bars, seed)
        if (pattern.isEmpty()) return Snip(FloatArray(rate), 1, rate)

        val mono = HashMap<Int, Snip>()
        for (hit in pattern) {
            val snip = mono.getOrPut(hit.padIndex) {
                val s = arranged[hit.padIndex]!!.snip
                if (s.channels == 1) s else com.snipsnap.audio.Cleanup.toMono(s)
            }
            val start = hit.step * stepFrames
            val n = minOf(snip.samples.size, total - start)
            for (i in 0 until n) mix[start + i] += snip.samples[i] * hit.velocity
        }

        Dsp.normalize(mix)
        Dsp.fadeTail(mix, 12f)
        return Snip(mix, channels = 1, sampleRate = rate)
    }
}
