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
 * Modal mallets and drums re-strike ([canRestrike]): SKIN's [Skin.STRUCK]
 * voices, every TERRA voice, HALOBAR mallets, BREAKER (whose source is a
 * struck SKIN drum), BACKWASH's model 5, FIRED BRONZE's model 2 and ACCORD, whose takes are its own
 * passage-offset takes ([Accord.renderTake]), kept by ear in accord-rr-1. Every take is deterministic in
 * (patch, velocity, seed, take); take 0 is the reference itself.
 */
object Restrike {
    /** A take lands up to this much softer than the hit it repeats (a fraction of the velocity). Shape. */
    const val FORCE_SPREAD = 0.15f

    /** How far a TERRA take's strike position moves, either way, on POS's 0..1. Shape. */
    const val POSITION_SPREAD = 0.04f

    /** True when [patch] has a strike model to vary, so a chain of it can re-strike. */
    fun canRestrike(patch: Patch?): Boolean =
        (patch is SkinPatch && patch.voice in Skin.STRUCK) || patch is TerraPatch ||
            patch is DrumlinePatch || patch is HalobarPatch || patch is BreakerPatch || patch is SootPatch || (patch is BackwashPatch && patch.model == 5) || patch is EtchPatch ||
            (patch is ThumpPatch && patch.voice == ThumpVoice.SUB) ||
            (patch is FiredBronzePatch && patch.model >= 2) || patch is KindredPatch || patch is AccordPatch

    /** ACCORD's take for Restrike take [take]: 0 is 0, later takes cycle through 1 until [Accord.TAKES], never back to 0. */
    internal fun accordTake(take: Int): Int = if (take == 0) 0 else 1 + (take - 1) % (Accord.TAKES - 1)

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
     * velocity)`'s own render for these engines, except ACCORD, whose
     * [velocity] scales its saved touch (take 0 at 1 is the saved render).
     */
    fun take(patch: Patch, velocity: Float, seed: Int, take: Int): Snip? {
        require(take >= 0) { "a take is 0 or more, got $take" }
        if (!canRestrike(patch)) return null
        if (patch is DrumlinePatch) return patch.render(velocity, take)
        if (patch is BackwashPatch) return patch.copy(take = patch.take + take).render(velocity)
        // ETCH deals its own five-dimension takes (Etch.takeFor), accepted by ear; no extra force spread on top.
        if (patch is EtchPatch) return patch.render(velocity.coerceIn(0f, 1f), take, seed)
        // ACCORD deals its own passage-offset takes (Accord.renderTake), kept by ear in accord-rr-1. Its
        // velocity is the timbre (how hard the machine is driven), so no force spread on top, and [velocity]
        // scales the saved touch: Robin's untouched hit (1) is the saved performance, so ROBIN on a pad deals
        // exactly the kept takes of its render. The takes are deterministic in the patch alone: no seed.
        if (patch is AccordPatch) return Accord.renderTake(patch.copy(velocity = patch.velocity * velocity.coerceIn(0f, 1f)), accordTake(take))
        val (v, offset) = landing(velocity.coerceIn(0f, 1f), seed, take)
        return when (patch) {
            is HalobarPatch -> Halobar.renderTake(patch.voice, patch.macros, v, seed, take, patch.model)
            is SkinPatch -> patch.render(velocity = v, take = take)
            // THUMP's SUB deals its settle, click, drive and slide timing out per take (Thump.SUB_TAKES).
            is ThumpPatch -> Thump.render(patch.voice, patch.macros, velocity = v, take = take)
            // BREAKER's source is a struck SKIN drum: each take is a fresh strike through the same chain.
            is BreakerPatch -> patch.render(velocity = v, take = take)
            // FIRED BRONZE model 2: each take is a fresh strike through the approved chain (FiredBronzeInstrument).
            is FiredBronzePatch -> patch.render(v, take)
            // SOOT's takes are fresh strikes: new noise, a moved strike position, fresh metal phases.
            is SootPatch -> patch.render(velocity = v, take = take)
            // KINDRED's takes are fresh strikes: free-running oscillator phases, new noise, a few cents of drift.
            is KindredPatch -> patch.copy(velocity = v, take = (patch.take + take) % (Kindred.MAX_TAKE + 1)).render()
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
