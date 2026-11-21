package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.abs
import kotlin.math.pow

/**
 * KERF's genre kits (round 4 of docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md): four authored
 * sixteen-pad kits of synthetic chops from the KERF engine ([Kerf]). Each takes most of its hits from one of the four
 * loops' banks, so each has its own drums, not the same drums at other settings, and each sets its own tuning, decay,
 * wash, bleed and knife:
 *
 * - [Genre.DUST], 88 BPM: boom bap. Short kicks and snares, two ghost notes, heavy bleed and a loose knife, a little
 *   low.
 * - [Genre.SOUL], 94 BPM: a soul break. Round kicks, a roomy snare, long hats, the bank's measured bleed and knife.
 * - [Genre.FUNK], 104 BPM: a tight funk break. Fast kicks, crisp snares, open cymbals; a clean knife, little bleed,
 *   short decays, a touch high.
 * - [Genre.STOMP], 84 BPM: hard and dirty. Big kicks, a fat snare and a crash stack; the wash pushed, the knife
 *   sloppiest.
 *
 * Every pad is a grid of three velocity zones and four takes, as SOOT's:
 * - **A01 to A14:** single hits, each a KERF voice and pool hit at this genre's settings.
 * - **A15:** a roll: sixteen strikes of the genre's roll pad, accelerating over one bar, each strike a fresh take.
 * - **A16:** a riser: twenty-four strikes of the genre's riser pad over one bar, rising in pitch, density and force.
 *
 * Levels: every pad's grid is scaled by one gain so its loudest cell peaks at [Kerf.PEAK] under its [Pad.levelDb]
 * (hats and ghosts sit under the kick and snare, as in the loops). One gain per pad keeps the zones and takes as they
 * were struck, and nothing a pad deals can clip.
 *
 * Bump [VERSION] when any pad's audio changes; saved kits keep the version they were printed with.
 */
object KerfKits {
    const val VERSION = 1
    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16
    const val HITS = 14

    /** A hit pad: its name, voice, pool hit, level and choke group, and any macro the genre's settings do not set. */
    class Pad internal constructor(
        val name: String, val voice: KerfVoice, val hit: Int, val levelDb: Double = 0.0, val mute: Int = 0,
        val over: Map<String, Float> = emptyMap(),
    )

    enum class Genre(
        val id: String, val title: String, val bpm: Int,
        val tune: Float, val decay: Float, val floor: Float, val bleed: Float, val slop: Float,
        /** The pads (0-based, among the hits) the roll and the riser strike. */
        val rollPad: Int, val riserPad: Int,
    ) {
        DUST("kerf-dust", "Dust", 88, tune = .45f, decay = .45f, floor = .55f, bleed = .65f, slop = .6f, rollPad = 6, riserPad = 12),
        SOUL("kerf-soul", "Soul", 94, tune = .5f, decay = .55f, floor = .5f, bleed = .5f, slop = .5f, rollPad = 3, riserPad = 10),
        FUNK("kerf-funk", "Funk", 104, tune = .55f, decay = .4f, floor = .4f, bleed = .3f, slop = .3f, rollPad = 9, riserPad = 11),
        STOMP("kerf-stomp", "Stomp", 84, tune = .4f, decay = .6f, floor = .7f, bleed = .75f, slop = .8f, rollPad = 3, riserPad = 12),
    }

    private fun p(name: String, voice: KerfVoice, hit: Int, levelDb: Double = 0.0, vararg over: Pair<String, Float>) =
        Pad(name, voice, hit, levelDb, if (voice == KerfVoice.HAT || voice == KerfVoice.HAT_OPEN) 1 else 0, mapOf(*over))

    /** The fourteen hits, A01 first. Pool indices are [Kerf.pool]'s: kits A, B, C, D in that order within each voice. */
    fun pads(g: Genre): List<Pad> = when (g) {
        Genre.DUST -> listOf(
            p("Kick", KerfVoice.KICK, 4), p("Kick Short", KerfVoice.KICK, 7), p("Kick Knock", KerfVoice.KICK, 6, -2.0),
            p("Snare", KerfVoice.SNARE, 4), p("Snare Tight", KerfVoice.SNARE, 7, -1.0), p("Snare Crack", KerfVoice.SNARE, 5, -1.0),
            p("Ghost", KerfVoice.GHOST, 0, -8.0), p("Ghost Low", KerfVoice.GHOST, 1, -8.0),
            p("Hat", KerfVoice.HAT, 1, -7.0), p("Hat Tick", KerfVoice.HAT, 0, -8.0), p("Hat Thud", KerfVoice.HAT, 2, -7.0),
            p("Open Hat", KerfVoice.HAT_OPEN, 7, -6.0),
            p("Stack", KerfVoice.PERC, 1, -3.0), p("Thump", KerfVoice.PERC, 2, -3.0),
        )
        Genre.SOUL -> listOf(
            p("Kick", KerfVoice.KICK, 12), p("Kick Knock", KerfVoice.KICK, 14, -1.0), p("Kick Round", KerfVoice.KICK, 15, -1.0),
            p("Snare", KerfVoice.SNARE, 12), p("Snare Room", KerfVoice.SNARE, 13, -1.0), p("Snare Fat", KerfVoice.SNARE, 14),
            p("Ghost", KerfVoice.GHOST, 4, -8.0), p("Ghost Brush", KerfVoice.GHOST, 5, -9.0),
            p("Hat", KerfVoice.HAT, 6, -6.0), p("Hat Loose", KerfVoice.HAT, 7, -7.0), p("Hat Long", KerfVoice.HAT, 8, -7.0),
            p("Open Hat", KerfVoice.HAT_OPEN, 9, -5.0),
            p("Clack", KerfVoice.PERC, 0, -4.0), p("Rim", KerfVoice.PERC, 4, -4.0),
        )
        Genre.FUNK -> listOf(
            p("Kick", KerfVoice.KICK, 0), p("Kick Quick", KerfVoice.KICK, 1, -1.0), p("Kick Thud", KerfVoice.KICK, 2, -1.0),
            p("Snare", KerfVoice.SNARE, 0), p("Snare Ring", KerfVoice.SNARE, 2, -1.0), p("Snare Dry", KerfVoice.SNARE, 3),
            p("Ghost", KerfVoice.GHOST, 0, -9.0, "TUNE" to .62f), p("Ghost Brush", KerfVoice.GHOST, 5, -9.0),
            p("Hat", KerfVoice.HAT, 9, -6.0), p("Hat Tick", KerfVoice.HAT, 10, -7.0),
            p("Open Hat", KerfVoice.HAT_OPEN, 3, -5.0), p("Open Hat Long", KerfVoice.HAT_OPEN, 5, -5.0),
            p("Cymbal Wash", KerfVoice.HAT_OPEN, 1, -6.0), p("Clack", KerfVoice.PERC, 0, -4.0),
        )
        Genre.STOMP -> listOf(
            p("Kick", KerfVoice.KICK, 8), p("Kick Boom", KerfVoice.KICK, 10), p("Kick Low", KerfVoice.KICK, 11, -1.0),
            p("Snare", KerfVoice.SNARE, 8), p("Snare Room", KerfVoice.SNARE, 9), p("Snare Fat", KerfVoice.SNARE, 1, -1.0),
            p("Ghost", KerfVoice.GHOST, 2, -8.0), p("Ghost Low", KerfVoice.GHOST, 3, -8.0),
            p("Hat", KerfVoice.HAT, 3, -7.0), p("Hat Tick", KerfVoice.HAT, 4, -8.0), p("Hat Sizzle", KerfVoice.HAT, 5, -7.0),
            p("Open Hat", KerfVoice.HAT_OPEN, 8, -5.0),
            p("Crash Stack", KerfVoice.PERC, 3, -2.0), p("Rim", KerfVoice.PERC, 4, -4.0),
        )
    }

    fun names(g: Genre): List<String> = pads(g).map { it.name } + listOf("${pads(g)[g.rollPad].name} Roll", "${pads(g)[g.riserPad].name} Riser")

    private fun classOf(g: Genre, role: Int): DrumClass = when (role) {
        in 0 until HITS -> pads(g)[role].voice.drumClass
        else -> DrumClass.PERC // the roll and the riser ring past 1.5 s; declared, never classified
    }

    /** A hit pad's macros: the genre's settings, the pad's hit, and the pad's own overrides. */
    fun macrosOf(g: Genre, pad: Pad): Map<String, Float> = Kerf.settled(pad.voice, mapOf(
        "HIT" to Kerf.hitFor(pad.voice, pad.hit), "TUNE" to g.tune, "DECAY" to g.decay,
        "FLOOR" to g.floor, "BLEED" to g.bleed, "SLOP" to g.slop,
    ) + pad.over)

    private fun db(x: Double) = 10.0.pow(x / 20.0)

    private fun scaled(s: Snip, gain: Double) = s.copy(samples = FloatArray(s.samples.size) { (s.samples[it] * gain).toFloat() })

    /** One cell of a pad before the pad's gain ([build]). Pure: the same arguments give the same bytes. */
    fun render(g: Genre, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return when (role) {
            in 0 until HITS -> pads(g)[role].let { Kerf.render(it.voice, macrosOf(g, it), velocity, take) }
            HITS -> roll(g, velocity, take)
            else -> riser(g, velocity, take)
        }
    }

    /** One bar at the genre's tempo, in seconds. */
    fun barSeconds(g: Genre) = 4 * 60.0 / g.bpm

    /** How long the roll and the riser ring after their last strike. */
    const val TAIL_SECONDS = 0.8

    private const val ROLL_STRIKES = 16
    private const val RISER_STRIKES = 24

    /**
     * [count] strikes of [pad] laid over one bar: strike i lands at [at] of its place p (0..1), struck at [force] of p
     * times the cell's velocity, tuned by [tune] of p semitones. Each strike is its own take, so no two are the same
     * chop. Raw: not levelled.
     */
    private fun strikes(g: Genre, pad: Pad, count: Int, velocity: Float, take: Int,
                        at: (Double) -> Double, force: (Double) -> Double, tune: (Double) -> Double): FloatArray {
        val rate = Dsp.RATE
        val bar = barSeconds(g)
        val out = FloatArray(((bar + TAIL_SECONDS) * rate).toInt())
        val base = macrosOf(g, pad)
        val parts = (0 until count).toList().parallelStream().map { i ->
            val p = i / (count - 1.0)
            val tuned = (base.getValue("TUNE") + tune(p) / 14.0).toFloat().coerceIn(0f, 1f)
            val hit = Kerf.recipe(pad.voice, base + ("TUNE" to tuned))
                .take(take * count + i + 1, (velocity * force(p)).coerceIn(.02, 1.0)).samples
            Triple(i, (at(p) * bar * rate).toInt(), hit)
        }.collect(java.util.stream.Collectors.toList()).sortedBy { it.first }
        for ((_, start, hit) in parts) for (k in hit.indices) if (start + k < out.size) out[start + k] += hit[k]
        val fade = (0.01 * rate).toInt()
        for (k in 0 until fade) out[out.size - 1 - k] *= k / fade.toFloat()
        return out
    }

    /** The roll: sixteen strikes speeding up over one bar (SOOT's curve), softest first. */
    fun roll(g: Genre, velocity: Float, take: Int): Snip = Snip(
        strikes(g, pads(g)[g.rollPad], ROLL_STRIKES, velocity, take, at = { .92 * (1 - (1 - it).pow(1.6)) }, force = { .3 + .7 * it }, tune = { 0.0 }),
        1, Dsp.RATE,
    )

    /** The riser: twenty-four strikes tightening over one bar, five semitones under the pad climbing to two over. */
    fun riser(g: Genre, velocity: Float, take: Int): Snip = Snip(
        strikes(g, pads(g)[g.riserPad], RISER_STRIKES, velocity, take, at = { .95 * (1 - (1 - it).pow(2.2)) }, force = { .2 + .8 * it.pow(1.5) }, tune = { -5.0 + 7.0 * it }),
        1, Dsp.RATE,
    )

    /** The pad's level: the hit's own, or 2 dB under the loudest hits for the roll and the riser. */
    fun levelDb(g: Genre, role: Int): Double = if (role < HITS) pads(g)[role].levelDb else -2.0

    /** One kit's sixteen pads, each with its full grid, rendered in parallel. */
    fun build(g: Genre): List<ArrangedPad> {
        val cells = (0 until PADS).flatMap { role -> velocities.indices.flatMap { z -> (0 until TAKES).map { t -> Triple(role, z, t) } } }
        val raw = cells.parallelStream().map { (role, z, t) -> Triple(role, z, t) to render(g, role, velocities[z], t) }
            .collect(java.util.stream.Collectors.toList()).toMap()
        val gains = (0 until PADS).associateWith { role ->
            val loudest = cells.filter { it.first == role }.maxOf { c -> raw.getValue(c).samples.maxOf { abs(it) } }
            Kerf.PEAK * db(levelDb(g, role)) / loudest.coerceAtLeast(1e-6f)
        }
        val rendered = raw.mapValues { (c, s) -> scaled(s, gains.getValue(c.first)) }
        val names = names(g)
        val pads = pads(g)
        return List(PADS) { role ->
            val grid = velocities.indices.map { z -> List(TAKES) { t -> rendered.getValue(Triple(role, z, t)) } }
            val pad = pads.getOrNull(role)
            ArrangedPad(
                grid.last().first(), classOf(g, role), displayName = names[role], velocityTakes = grid,
                muteGroup = pad?.mute ?: 2,
                source = mapOf(
                    "engine" to "KERF", "kit" to g.id, "kitVersion" to VERSION.toString(), "model" to Kerf.MODEL_VERSION.toString(),
                    "role" to role.toString(), "bpm" to g.bpm.toString(),
                    "voice" to (pad?.voice ?: pads[if (role == HITS) g.rollPad else g.riserPad].voice).name,
                    "hit" to (pad?.hit ?: pads[if (role == HITS) g.rollPad else g.riserPad].hit).toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to (if (role == HITS) "roll" else if (role == HITS + 1) "riser" else "finite"),
                ),
            )
        }
    }
}
