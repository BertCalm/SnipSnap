package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.random.Random

/**
 * RE-STRIKE: another take of a struck synth pad, hit again rather than
 * resampled. A round-robin chain built from one recorded take can only
 * nudge that take's level, start and pitch, and a pitch nudge detunes the
 * whole drum (root, overtones and decay together). A synth pad can do what
 * a drummer does instead, and land the next hit:
 * - a little softer or harder ([FORCE_SPREAD], through [StrikeVelocity]:
 *   the modes' balance, the bend and the grit move together);
 * - a little off the last spot, on TERRA ([POSITION_SPREAD] on POS, which
 *   reweights the modes by where the head is struck; a voice without POS,
 *   DAYAN, keeps its spot);
 * - with its own click or grit, since each take re-seeds the strike's noise
 *   (`take` on [Skin.render] and [Terra.render]).
 * The root and the decay are the drum's own on every take.
 *
 * Only the modal drums re-strike ([canRestrike]): SKIN's [Skin.STRUCK]
 * voices and every TERRA voice. Every take is deterministic in (patch,
 * velocity, seed, take); take 0 is the reference itself.
 */
object Restrike {
    /** A take lands up to this much softer than the hit it repeats (a fraction of the velocity). Shape. */
    const val FORCE_SPREAD = 0.15f

    /** How far a TERRA take's strike position moves, either way, on POS's 0..1. Shape. */
    const val POSITION_SPREAD = 0.04f

    /** True when [patch] has a strike model to vary, so a chain of it can re-strike. */
    fun canRestrike(patch: Patch?): Boolean =
        (patch is SkinPatch && patch.voice in Skin.STRUCK) || patch is TerraPatch

    /** The velocity and the TERRA POS offset take [take] lands with, drawn from [seed]; take 0 is (velocity, 0). */
    internal fun landing(velocity: Float, seed: Int, take: Int): Pair<Float, Float> {
        if (take == 0) return velocity to 0f
        val rng = Random(Dsp.seedFor("RESTRIKE", seed, take))
        // Both drawn every time, so the second never depends on the first's value.
        val force = rng.nextFloat()
        val position = rng.nextFloat() * 2f - 1f
        return velocity * (1f - FORCE_SPREAD * force) to POSITION_SPREAD * position
    }

    /**
     * Take [take] of [patch] hit at [velocity], seeded by [seed]; null when
     * [patch] cannot re-strike. Take 0 is `Velocity.atVelocity(patch,
     * velocity)`'s own render for these engines.
     */
    fun take(patch: Patch, velocity: Float, seed: Int, take: Int): Snip? {
        require(take >= 0) { "a take is 0 or more, got $take" }
        if (!canRestrike(patch)) return null
        val (v, offset) = landing(velocity.coerceIn(0f, 1f), seed, take)
        return when (patch) {
            is SkinPatch -> Skin.render(patch.voice, patch.macros, velocity = v, take = take)
            is TerraPatch -> {
                // A voice with no POS (DAYAN's finger is always at the rim) keeps where it lands.
                val pos = Terra.defaults(patch.voice)["POS"]
                val moved = if (take == 0 || pos == null) patch.macros
                else patch.macros + ("POS" to ((patch.macros["POS"] ?: pos) + offset).coerceIn(0f, 1f))
                Terra.render(patch.voice, moved, patch.striker, velocity = v, take = take)
            }
            else -> null
        }
    }
}
