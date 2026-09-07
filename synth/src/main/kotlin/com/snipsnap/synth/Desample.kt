package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Features
import com.snipsnap.audio.Similar
import com.snipsnap.audio.Snip

/**
 * DE-SAMPLE (XX3) — the nearest synth patch to a captured hit. The
 * inverse of rendering: a THUMP patch is a voice and a handful of 0..1
 * macros, so every voice's macro space is walked on a coarse grid
 * ([GRID] levels per macro), each point rendered once and measured by
 * the classifier's own extractor; the hit is measured the same way and
 * [Similar]'s distance picks the nearest point. A short coordinate
 * descent then refines the macros from that point, halving its step
 * until it stops helping. The result is a patch the engine can render
 * again forever — the capture's sound, as a recipe.
 *
 * Honest about distance: [Match.distance] is reported, and past
 * [FAR] the nearest patch is named as far, not sold as the sound. The
 * grid is rendered once per process and kept ([GRID] per macro, a few
 * hundred renders in all). Deterministic: no seed, all measurement.
 */
object Desample {

    /** Levels per macro on the grid: the corners are bad neighbours, the thirds are not. */
    val GRID: FloatArray = floatArrayOf(0.15f, 0.5f, 0.85f)

    /** The refinement's first step, halved whenever a round no longer helps. */
    const val REFINE_STEP = 0.175f
    const val REFINE_ROUNDS = 8

    /** Past this distance the nearest patch is a stranger, and said to be. */
    const val FAR = 0.45f

    data class Match(val patch: ThumpPatch, val distance: Float) {
        val far: Boolean get() = distance > FAR
    }

    private class Point(val voice: ThumpVoice, val macros: Map<String, Float>, val features: Features)

    private val grid: List<Point> by lazy {
        val out = ArrayList<Point>()
        for (voice in ThumpVoice.entries) {
            val names = Thump.macrosFor(voice).map { it.name }
            val counts = IntArray(names.size)
            while (true) {
                val macros = names.indices.associate { i -> names[i] to GRID[counts[i]] }
                out.add(Point(voice, macros, FeatureExtractor.extract(Thump.render(voice, macros))))
                var k = 0
                while (k < counts.size) {
                    counts[k]++
                    if (counts[k] < GRID.size) break
                    counts[k] = 0
                    k++
                }
                if (k == counts.size) break
            }
        }
        out
    }

    /** How many points the grid holds. */
    val gridSize: Int get() = grid.size

    /**
     * The voices kindred to a drum class — where a kit pad's search
     * starts, so a hat comes back as a hat and not as the snare that
     * happened to measure a hair nearer. Unknown classes search everything.
     */
    fun voicesFor(drumClass: com.snipsnap.audio.DrumClass): List<ThumpVoice> = when (drumClass) {
        com.snipsnap.audio.DrumClass.KICK -> listOf(ThumpVoice.KICK)
        com.snipsnap.audio.DrumClass.SNARE -> listOf(ThumpVoice.SNARE, ThumpVoice.RIM, ThumpVoice.CLAP)
        com.snipsnap.audio.DrumClass.CLAP -> listOf(ThumpVoice.CLAP, ThumpVoice.SNARE)
        com.snipsnap.audio.DrumClass.HAT_CLOSED -> listOf(ThumpVoice.HAT_CLOSED, ThumpVoice.HAT_OPEN)
        com.snipsnap.audio.DrumClass.HAT_OPEN -> listOf(ThumpVoice.HAT_OPEN, ThumpVoice.HAT_CLOSED)
        com.snipsnap.audio.DrumClass.TOM -> listOf(ThumpVoice.TOM, ThumpVoice.KICK)
        com.snipsnap.audio.DrumClass.PERC -> listOf(ThumpVoice.COWBELL, ThumpVoice.RIM, ThumpVoice.CLAP, ThumpVoice.TOM)
        else -> ThumpVoice.entries
    }

    /** The nearest patch to [snip], every voice considered, or [voices] only. */
    fun nearest(snip: Snip, voices: Collection<ThumpVoice> = ThumpVoice.entries, name: String = "De-sampled"): Match {
        require(snip.frameCount > 0) { "the source is empty" }
        require(voices.isNotEmpty()) { "no voice to search" }
        val target = FeatureExtractor.extract(snip)
        var best: Point? = null
        var bestDistance = Float.MAX_VALUE
        for (p in grid) {
            if (p.voice !in voices) continue
            val d = Similar.distance(target, p.features)
            if (d < bestDistance) {
                bestDistance = d
                best = p
            }
        }
        val start = best ?: throw IllegalStateException("an empty grid")

        // Coordinate descent from the grid's best point.
        var macros = start.macros.toMutableMap()
        var distance = bestDistance
        var step = REFINE_STEP
        repeat(REFINE_ROUNDS) {
            var improved = false
            for (n in macros.keys.toList()) {
                for (dir in floatArrayOf(-1f, 1f)) {
                    val trial = macros.toMutableMap()
                    trial[n] = (macros.getValue(n) + dir * step).coerceIn(0f, 1f)
                    if (trial[n] == macros[n]) continue
                    val d = Similar.distance(target, FeatureExtractor.extract(Thump.render(start.voice, trial)))
                    if (d < distance) {
                        distance = d
                        macros = trial
                        improved = true
                    }
                }
            }
            if (!improved) step /= 2f
        }
        return Match(ThumpPatch(name, start.voice, macros), distance)
    }
}
