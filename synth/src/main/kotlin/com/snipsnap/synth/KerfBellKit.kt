package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.abs
import kotlin.math.pow

/**
 * KERF BELLS (2026-11-21): the owner asked for bells first, and the pitched bell family had only six clean notes, so
 * bells are a percussion kit instead of a scale. The loops whose clean notes centre at 4 kHz or brighter (sleigh and
 * Tibetan bells, glockenspiel, gamelan metals; `prototype/kerf/tonal_bank.py`) give fourteen strikes ([KerfBank.bells]),
 * each rung out at its own pitch ([KerfTonal], [KerfRender.ring]), low to high and named for its note:
 *
 * - **A01 to A14:** the strikes.
 * - **A15:** a roll: sixteen strikes of [ROLL_PAD] speeding up over one bar, softest first, each a fresh take.
 * - **A16:** a riser: twenty-four strikes of [RISER_PAD] tightening over one bar and climbing seven semitones.
 *
 * Every pad is 3 touch zones by 4 takes, scaled by one gain so its loudest cell peaks at [Kerf.PEAK]. Bells ring past
 * the classifier's loop threshold, so every pad is declared PERC.
 *
 * Bump [VERSION] when any pad's audio changes.
 */
object KerfBellKit {
    const val ID = "kerf-bells"
    const val VERSION = 1
    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16
    const val HITS = 14

    /** The strike the roll repeats: the middle of the kit. */
    const val ROLL_PAD = 6

    /** The strike the riser climbs from: a low one, so seven semitones up stays a bell. */
    const val RISER_PAD = 2

    /** How long the roll and the riser ring after their last strike. */
    const val TAIL_SECONDS = 2.0

    fun bpm(): Int = KerfBank.bells.first

    private fun hits(): List<KerfTonalNote> = KerfBank.bells.second

    fun names(): List<String> = hits().map { "Bell " + KerfTonalKits.noteName(Math.round(it.rootMidi).toInt()) }
        .let { n -> n.mapIndexed { i, s -> if (n.count { it == s } > 1) "$s ${n.take(i + 1).count { it == s }}" else s } } +
        listOf("Bell Roll", "Bell Riser")

    /** One cell of a pad before the pad's gain ([build]). Pure. */
    fun render(pad: Int, velocity: Float, take: Int): Snip {
        require(pad in 0 until PADS && take in 0 until TAKES)
        val h = hits()
        return when (pad) {
            in 0 until HITS -> h[pad].let { KerfTonal.render(it, it.rootMidi, velocity.toDouble(), take) }
            HITS -> strikes(h[ROLL_PAD], 16, velocity, take, at = { .92 * (1 - (1 - it).pow(1.6)) }, force = { .3 + .7 * it }, tune = { 0.0 })
            else -> strikes(h[RISER_PAD], 24, velocity, take, at = { .95 * (1 - (1 - it).pow(2.2)) }, force = { .2 + .8 * it.pow(1.5) }, tune = { 7.0 * it })
        }
    }

    private fun strikes(note: KerfTonalNote, count: Int, velocity: Float, take: Int,
                        at: (Double) -> Double, force: (Double) -> Double, tune: (Double) -> Double): Snip {
        val rate = Dsp.RATE
        val bar = 4 * 60.0 / bpm()
        val out = FloatArray(((bar + TAIL_SECONDS) * rate).toInt())
        val parts = (0 until count).toList().parallelStream().map { i ->
            val p = i / (count - 1.0)
            val x = KerfTonal.render(note, note.rootMidi + tune(p), (velocity * force(p)).coerceIn(.02, 1.0), take * count + i + 1).samples
            (at(p) * bar * rate).toInt() to x
        }.collect(java.util.stream.Collectors.toList())
        for ((start, x) in parts) for (k in x.indices) if (start + k < out.size) out[start + k] += x[k]
        val fade = (0.15 * rate).toInt()
        for (k in 0 until fade) out[out.size - 1 - k] *= k / fade.toFloat()
        return Snip(out, 1, rate)
    }

    /** The kit's sixteen pads, each with its full grid. */
    fun build(): List<ArrangedPad> = build((0 until PADS).toList())

    /** Only [pads] (0-based), each levelled by its own grid, so it is the same pad as in a full [build]. */
    fun build(pads: List<Int>): List<ArrangedPad> {
        val cells = pads.flatMap { p -> velocities.indices.flatMap { z -> (0 until TAKES).map { t -> Triple(p, z, t) } } }
        val raw = cells.parallelStream().map { (p, z, t) -> Triple(p, z, t) to render(p, velocities[z], t) }
            .collect(java.util.stream.Collectors.toList()).toMap()
        val names = names()
        return pads.map { p ->
            val loudest = cells.filter { it.first == p }.maxOf { c -> raw.getValue(c).samples.maxOf { abs(it) } }
            val gain = Kerf.PEAK / loudest.coerceAtLeast(1e-6f)
            val grid = velocities.indices.map { z -> List(TAKES) { t ->
                val s = raw.getValue(Triple(p, z, t)); s.copy(samples = FloatArray(s.samples.size) { s.samples[it] * gain })
            } }
            ArrangedPad(
                grid.last().first(), DrumClass.PERC, displayName = names[p], velocityTakes = grid, muteGroup = 0,
                source = mapOf(
                    "engine" to "KERF", "kit" to ID, "kitVersion" to VERSION.toString(), "bank" to "bells",
                    "role" to p.toString(), "rootMidi" to Math.round(hits()[if (p < HITS) p else if (p == HITS) ROLL_PAD else RISER_PAD].rootMidi).toString(),
                    "bpm" to bpm().toString(), "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to (if (p == HITS) "roll" else if (p == HITS + 1) "riser" else "finite"),
                ),
            )
        }
    }
}
