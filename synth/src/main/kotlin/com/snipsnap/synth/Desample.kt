package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Features
import com.snipsnap.audio.Similar
import com.snipsnap.audio.Snip
import kotlin.math.pow

/**
 * DE-SAMPLE (XX3) — the nearest synth patch to a captured hit. The
 * inverse of rendering: a patch is a voice and a handful of 0..1 macros,
 * so every voice's macro space is walked on a coarse grid ([GRID] levels
 * per macro), each point rendered once and measured by the classifier's
 * own extractor; the hit is measured the same way and [Similar]'s
 * distance picks the nearest point. A short coordinate descent then
 * refines the macros from that point, halving its step until it stops
 * helping. The result is a patch the engine can render again forever —
 * the capture's sound, as a recipe.
 *
 * Honest about distance: [Match.distance] is reported, and past [FAR]
 * the nearest patch is named as far, not sold as the sound.
 * Deterministic: no seed, all measurement.
 *
 * ## Two engines, and why the grid is built a voice at a time
 *
 * This searched THUMP alone until SKIN arrived; now [Voices.ALL] spans
 * both drum engines, so an acoustic capture can come back as an acoustic
 * voice instead of the nearest analog approximation of one.
 *
 * That doubling had to be paid for rather than absorbed. **Measured
 * before it was written:** the THUMP-only grid is 450 points and took
 * ~110 seconds to build on a desktop JVM, all of it on the first call,
 * behind `PadSheetScreen`'s bare busy flag. SKIN's 180 points would have
 * made that ~150.
 *
 * So the grid is no longer one list built in full on first use. Each
 * voice's points are built and kept the first time that voice is
 * actually searched, which turns the cost from a property of the roster
 * into a property of the question: [KitBuilder]'s own call passes
 * [voicesFor], one to six voices, and now renders only those. The
 * exhaustive search a bare [nearest] still does costs what it always
 * did, plus SKIN — but nothing on the pad sheet takes that path.
 */
object Desample {

    /** Levels per macro on the grid: the corners are bad neighbours, the thirds are not. */
    val GRID: FloatArray = floatArrayOf(0.15f, 0.5f, 0.85f)

    /**
     * Macros the grid doesn't search over - they colour a voice rather than
     * identify it, so walking them just adds confusable dimensions near a
     * voice boundary (U3's PUNCH, cranked up, makes any voice's transient
     * dominate its own tail, which can measure nearer to an unrelated
     * voice's default shape than to the source voice's own quieter grid
     * points). Left at [Thump.render]'s own default for every grid point.
     */
    private val UNSEARCHED_MACROS = setOf("PUNCH")

    /** The refinement's first step, halved whenever a round no longer helps. */
    const val REFINE_STEP = 0.175f
    const val REFINE_ROUNDS = 8

    /** Past this distance the nearest patch is a stranger, and said to be. */
    const val FAR = 0.45f

    data class Match(val patch: Patch, val distance: Float) {
        val far: Boolean get() = distance > FAR
    }

    /**
     * One searchable voice, whichever engine owns it.
     *
     * Equality is on [engine] and [name] rather than identity, because
     * callers hold these across calls and compare them (`voice in
     * voicesFor(cls)`); an identity-only handle would make that quietly
     * false for an equal voice obtained twice.
     */
    class Voice internal constructor(
        val engine: String,
        val name: String,
        internal val searchedMacros: List<String>,
        internal val render: (Map<String, Float>) -> Snip,
        internal val patchOf: (String, Map<String, Float>) -> Patch,
    ) {
        /** How many grid points this voice contributes, without building any. */
        val points: Int get() = GRID.size.toDouble().pow(searchedMacros.size).toInt()

        override fun equals(other: Any?) = other is Voice && other.engine == engine && other.name == name
        override fun hashCode() = 31 * engine.hashCode() + name.hashCode()
        override fun toString() = "$engine/$name"
    }

    /**
     * Every voice DE-SAMPLE can answer with, in the order it searches
     * them. THUMP first and in `ThumpVoice.entries` order, deliberately:
     * ties go to the first point examined, so keeping that prefix
     * unchanged keeps every THUMP-only answer exactly what it was before
     * SKIN existed.
     */
    object Voices {
        val THUMP: List<Voice> = ThumpVoice.entries.map { v ->
            Voice(
                ThumpPatch.ENGINE, v.name,
                Thump.macrosFor(v).map { it.name }.filter { it !in UNSEARCHED_MACROS },
                { m -> Thump.render(v, m) },
                { n, m -> ThumpPatch(n, v, m) },
            )
        }

        val SKIN: List<Voice> = SkinVoice.entries.map { v ->
            Voice(
                SkinPatch.ENGINE, v.name,
                Skin.macrosFor(v).map { it.name }.filter { it !in UNSEARCHED_MACROS },
                { m -> Skin.render(v, m) },
                { n, m -> SkinPatch(n, v, m) },
            )
        }

        val ALL: List<Voice> = THUMP + SKIN

        fun of(engine: String, name: String): Voice? =
            ALL.firstOrNull { it.engine == engine && it.name == name }
    }

    private class Point(val voice: Voice, val macros: Map<String, Float>, val features: Features)

    /**
     * Built per voice, the first time that voice is searched, and kept.
     * See this object's own KDoc for the measurement that made a single
     * eager grid untenable once there were two engines in it.
     */
    private val builtGrids = java.util.concurrent.ConcurrentHashMap<Voice, List<Point>>()

    private fun pointsFor(voice: Voice): List<Point> = builtGrids.computeIfAbsent(voice) { v ->
        val out = ArrayList<Point>(v.points)
        val names = v.searchedMacros
        val counts = IntArray(names.size)
        while (true) {
            val macros = names.indices.associate { i -> names[i] to GRID[counts[i]] }
            out.add(Point(v, macros, FeatureExtractor.extract(v.render(macros))))
            var k = 0
            while (k < counts.size) {
                counts[k]++
                if (counts[k] < GRID.size) break
                counts[k] = 0
                k++
            }
            if (k == counts.size) break
        }
        out
    }

    /**
     * How many points the whole grid holds — counted, not built, so
     * asking is free. It was `grid.size` when the grid was one eager
     * list; a property that rendered a few hundred sounds to answer
     * "how big" would now be a trap rather than a readout.
     */
    val gridSize: Int get() = Voices.ALL.sumOf { it.points }

    private fun thump(vararg v: ThumpVoice) = v.map { Voices.of(ThumpPatch.ENGINE, it.name)!! }
    private fun skin(vararg v: SkinVoice) = v.map { Voices.of(SkinPatch.ENGINE, it.name)!! }

    /**
     * The voices kindred to a drum class — where a kit pad's search
     * starts, so a hat comes back as a hat and not as the snare that
     * happened to measure a hair nearer. Unknown classes search
     * everything.
     *
     * Now that it narrows what is *built* as well as what is searched,
     * this is the difference between a handful of renders and the whole
     * roster, so the lists earn their keep twice.
     *
     * **SKIN's SHAKER and STICK are listed under PERC, not SNARE, and
     * that is on purpose.** Measured, both classify as SNARE at their
     * own defaults — `SkinPresetsTest` records it — so the mechanical
     * reading would file them beside snares. But this table is not a
     * mirror of the classifier, it is a list of answers a player would
     * accept: a shaker offered back for a snare capture is a wrong
     * answer that happens to measure well.
     */
    fun voicesFor(drumClass: com.snipsnap.audio.DrumClass): List<Voice> = when (drumClass) {
        com.snipsnap.audio.DrumClass.KICK ->
            thump(ThumpVoice.KICK) + skin(SkinVoice.KICK)
        com.snipsnap.audio.DrumClass.SNARE ->
            thump(ThumpVoice.SNARE, ThumpVoice.RIM, ThumpVoice.CLAP) + skin(SkinVoice.SNARE)
        com.snipsnap.audio.DrumClass.CLAP ->
            thump(ThumpVoice.CLAP, ThumpVoice.SNARE) + skin(SkinVoice.SNARE)
        com.snipsnap.audio.DrumClass.HAT_CLOSED ->
            thump(ThumpVoice.HAT_CLOSED, ThumpVoice.HAT_OPEN) + skin(SkinVoice.HAT_CLOSED, SkinVoice.HAT_OPEN)
        com.snipsnap.audio.DrumClass.HAT_OPEN ->
            thump(ThumpVoice.HAT_OPEN, ThumpVoice.HAT_CLOSED) + skin(SkinVoice.HAT_OPEN, SkinVoice.RIDE)
        com.snipsnap.audio.DrumClass.TOM ->
            thump(ThumpVoice.TOM, ThumpVoice.KICK) + skin(SkinVoice.TOM)
        com.snipsnap.audio.DrumClass.PERC ->
            thump(ThumpVoice.COWBELL, ThumpVoice.RIM, ThumpVoice.CLAP, ThumpVoice.TOM) +
                skin(SkinVoice.STICK, SkinVoice.SHAKER, SkinVoice.RIDE)
        else -> Voices.ALL
    }

    /**
     * The nearest patch to [snip], every voice considered, or [voices]
     * only. Only the voices actually searched are ever rendered.
     *
     * The walk is over [Voices.ALL] filtered by membership rather than
     * over [voices] in the caller's order, and that is not fussiness: a
     * tie goes to the first point examined, so iterating the caller's
     * order would make the answer depend on how a list was written. This
     * way `voicesFor(TOM)` returns the same patch whether it reads TOM
     * then KICK or the reverse — and every THUMP-only answer is the one
     * this function gave before SKIN was in the roster.
     */
    fun nearest(snip: Snip, voices: Collection<Voice> = Voices.ALL, name: String = "De-sampled"): Match {
        require(snip.frameCount > 0) { "the source is empty" }
        require(voices.isNotEmpty()) { "no voice to search" }
        val target = FeatureExtractor.extract(snip)
        var best: Point? = null
        var bestDistance = Float.MAX_VALUE
        for (voice in Voices.ALL) {
            if (voice !in voices) continue
            for (p in pointsFor(voice)) {
                val d = Similar.distance(target, p.features)
                if (d < bestDistance) {
                    bestDistance = d
                    best = p
                }
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
                    val d = Similar.distance(target, FeatureExtractor.extract(start.voice.render(trial)))
                    if (d < distance) {
                        distance = d
                        macros = trial
                        improved = true
                    }
                }
            }
            if (!improved) step /= 2f
        }
        return Match(start.voice.patchOf(name, macros), distance)
    }
}
