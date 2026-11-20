package com.snipsnap.synth

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How hard a KERF twin was hit (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md, section 11).
 *
 * A soft hit in a chopped break is not the same hit turned down. The ghost notes in the owner's loops, measured against
 * the full snares of the same loop (`prototype/kerf/ghost_vs_snare.py`, nine loops), say what changes:
 *
 * | at about −11 dB of hit            | measured                  | here                               |
 * |-----------------------------------|---------------------------|------------------------------------|
 * | bleed under the hit, against it   | +12.2 dB, 9 of 9 loops    | the neighbours are not scaled      |
 * | floor against the hit             | +5.3 dB, 8 of 9           | the floor follows half the drop    |
 * | tick against the hit's peak       | −2.2 dB, 7 of 9           | the ticks fall a little further    |
 * | spectral centroid                 | 0.86×, 6 of 9             | the top end tilts down             |
 * | crack against ring                | no consistent change      | left alone                         |
 * | body glide                        | snares do not glide       | kicks: [StrikeVelocity]'s droop     |
 *
 * The loops held no soft kicks, and a kick is mostly body below 1 kHz, which the rows above only turn down: the first
 * kit-level check found soft kicks within −26 to −32 dB of a copy at another level. So a kick takes the app's own
 * physics for a struck drum ([StrikeVelocity.droopFactor], SKIN's and TERRA's): a softer hit bends the head less, so the
 * body's pitch glide shrinks with the square of the velocity. That row is borrowed, not measured from the loops.
 *
 * So the wash does not follow the hit down. A ghost note sits deeper in its neighbours' room, which a sample played
 * softer cannot do. Every constant below is "shape": fitted to those medians, not yet heard.
 *
 * [strike] changes only the hit's own patch. [KerfRender.chopped] then mixes the previous hit's tail at its own level,
 * so the bleed's rise needs no code here. The next hit's front, where the cut runs late, follows the hit instead
 * ([KerfRender.chopped]'s `nextGain`): the ghost notes say nothing about it (the chopper cut at the next onset), and
 * left at full level it became the loudest moment of a soft layer, a bright flam at its end that peak matching then
 * set the level by. Found on the first kit-level test of SOFT HITS (2026-11-20). The sampler owns loudness: the app peak-matches each layer to the pad, and what is
 * left is the change of character.
 */
object KerfVelocity {
    /** How far the hit falls, in dB, from velocity 1 to velocity 0. At 0.45, a typical soft layer, it is −11 dB. */
    const val HIT_DB_RANGE = 20.0

    /** The share of the hit's drop the floor follows, in dB. Measured: +5.3 dB of floor against an 11 dB drop. */
    const val FLOOR_FOLLOW = 0.5

    /**
     * The tilt above 1 kHz, as a power exponent per unit of softness, set so the centroid lands near the ghosts'
     * 0.86× at 0.45. 0.6 was tried first and gave 0.79× over the bank's 63 pads: too dull.
     */
    const val TILT = 0.35

    /** How much further the ticks fall than the hit, in dB, at velocity 0. Measured: 2.2 dB at 0.45. */
    const val TICK_EXTRA_DB = 4.0

    /**
     * How strongly velocity changes the strike, as a multiple of the ghost-note medians above. 1 is the measured law.
     * The owner heard 1 as "a bit indistinguishable" (2026-11-20): the medians are what a loop's ghost notes average,
     * not what an ear needs from a pad's soft layer. Above 1 the tone rows deepen with it (the tilt, the tick) and
     * the crack (the fast part of each band) is softened too, which the medians did not show. The hit's drop against
     * its neighbours does not: scaled with depth, a soft kick at depth 3 fell so far that the hat before it took over,
     * 5.8x the kick's brightness (measured 2026-11-20).
     */
    const val DEPTH = 1.0

    /** How much further the crack falls than the ring, in dB at velocity 0, per unit of depth above 1. */
    const val CRACK_DB = 6.0

    /** The hit's amplitude factor at [velocity]. */
    fun hitGain(velocity: Double, @Suppress("UNUSED_PARAMETER") depth: Double = DEPTH): Double = 10.0.pow(-HIT_DB_RANGE * softness(velocity) / 20.0)

    private fun softness(velocity: Double) = 1.0 - velocity.coerceIn(0.0, 1.0)

    /** Power factor of the tilt at [hz]: 1 up to 1 kHz, falling above it as the hit softens. */
    private fun tilt(hz: Double, s: Double): Double = if (hz <= 1000.0) 1.0 else (hz / 1000.0).pow(-TILT * s)
    // [strike] passes softness × depth as s, so the tilt deepens with depth too

    /** [patch] struck at [velocity] (0..1). Velocity 1 is [patch] itself, unchanged. */
    fun strike(patch: KerfPatchData, velocity: Double, depth: Double = DEPTH): KerfPatchData {
        val s = softness(velocity)
        if (s <= 0.0) return patch
        val ds = s * depth
        val amp = 10.0.pow(-HIT_DB_RANGE * s / 20.0)
        val power = amp * amp
        val floorPower = power.pow(FLOOR_FOLLOW)
        val tickAmp = amp * 10.0.pow(-TICK_EXTRA_DB * ds / 20.0)
        val crack = 10.0.pow(-CRACK_DB * s * (depth - 1.0).coerceAtLeast(0.0) / 10.0)
        return patch.copy(
            partials = patch.partials.map {
                // the body's glide: a softer hit bends the head less (StrikeVelocity), below 300 Hz where kicks live
                val glide = if (it.hzEnd < 300.0) it.glideHz * StrikeVelocity.droopFactor(velocity.toFloat()) else it.glideHz
                it.copy(amp = it.amp * amp * sqrt(tilt(it.hzEnd, ds)), glideHz = glide)
            },
            bands = patch.bands.mapIndexed { b, band ->
                val g = power * tilt(KerfRender.bandCentreHz(b), ds)
                band.copy(
                    snap = band.snap * g * crack, level = band.level * g, floor = band.floor * floorPower,
                    layers = band.layers.map { it.copy(snap = it.snap * g * crack, level = it.level * g) },
                )
            },
            ticks = patch.ticks.map { it.copy(amp = it.amp * tickAmp * sqrt(tilt(it.hz, ds))) },
        )
    }

    /** How far a take's knife may move from the pad's own, in draw units: about a millisecond at the start at SLOP 0.5. */
    const val TAKE_START_JITTER = 0.15
    /**
     * The same for the end, where the next hit's front comes in: a millisecond or two at SLOP 0.5. 0.25 let a kick's
     * takes swing 2.8 dB, from more or less of the loud snare after it.
     */
    const val TAKE_END_JITTER = 0.1

    /**
     * Take [take]'s knife: the pad's own cut ([own]), nudged. The same pad chopped again by a slightly different hand,
     * not a new chop: the start moves about a millisecond and the end a few, and whether the cut is early or late
     * ([KerfDraw.which]) is kept. A fresh draw per take was tried first and was wrong for round robin: on a quiet hat one
     * take kept a few milliseconds of the kick before it and the next did not, 9 dB and 3× the brightness apart.
     * [amount] (0..1) scales the nudge: 0 is the pad's own knife.
     */
    fun takeDraw(own: KerfDraw, seed: Long, take: Int, amount: Double): KerfDraw {
        val a = amount.coerceIn(0.0, 1.0)
        val r = KerfRender.drawFor(Dsp.seedFor(seed, "take", take).toLong())
        return KerfDraw(
            (own.start + (r.start - 0.5) * 2 * TAKE_START_JITTER * a).coerceIn(0.0, 1.0),
            (own.end + (r.end - 0.5) * 2 * TAKE_END_JITTER * a).coerceIn(0.0, 1.0),
            own.which,
        )
    }

    /** The noise seed of take [take]: a different draw of the same noise, so the hiss and the ring are not repeated. */
    fun takeSeed(seed: Long, take: Int): Long = if (take == 0) seed else max(1L, (seed xor (take.toLong() * 0x9E3779B97F4A7C15uL.toLong())) and 0x7fffffffffffL)
}
