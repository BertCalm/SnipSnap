package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * The demo groove: a kit playing itself.
 *
 * Given an arranged kit, render a few bars of pattern from its own pads —
 * kick on the floor, snare on the backbeat, hats keeping eighths, a tom
 * fill into the turnaround, tonal pads walking their notes. Three jobs, one
 * renderer: the audio preview an expansion wants, the "hear it slap" button
 * before export, and the most delightful moment in the app — your kit
 * playing back seconds after you made it.
 *
 * Deterministic per (kit, seed): same inputs, same groove, testable.
 */
object Groove {

    private const val STEPS_PER_BAR = 16

    fun render(
        arranged: List<ArrangedPad?>,
        bpm: Float = 92f,
        bars: Int = 4,
        seed: Int = 0,
    ): Snip {
        require(bpm in 40f..220f) { "bpm out of range: $bpm" }
        require(bars in 1..16) { "bars out of range: $bars" }

        val rate = arranged.firstNotNullOfOrNull { it?.snip?.sampleRate } ?: 44_100
        val stepFrames = (60.0 / bpm / 4.0 * rate).toInt()
        val tailFrames = rate // let the last hit ring
        val total = stepFrames * STEPS_PER_BAR * bars + tailFrames
        val mix = FloatArray(total)

        val byClass = HashMap<DrumClass, MutableList<Snip>>()
        for (pad in arranged) {
            if (pad == null) continue
            byClass.getOrPut(pad.drumClass) { mutableListOf() }.add(
                if (pad.snip.channels == 1) pad.snip else com.snipsnap.audio.Cleanup.toMono(pad.snip),
            )
        }
        if (byClass.isEmpty()) return Snip(FloatArray(rate), 1, rate)

        var rng = if (seed == 0) 1 else seed
        fun roll(): Float { rng = (rng * 1103515245 + 12345) and 0x7fffffff; return rng / 0x7fffffff.toFloat() }

        fun place(snip: Snip, step: Int, gain: Float) {
            val start = step * stepFrames
            val n = minOf(snip.samples.size, total - start)
            for (i in 0 until n) mix[start + i] += snip.samples[i] * gain
        }

        fun first(cls: DrumClass): Snip? = byClass[cls]?.firstOrNull()

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

        Dsp.normalize(mix)
        Dsp.fadeTail(mix, 12f)
        return Snip(mix, channels = 1, sampleRate = rate)
    }
}
