package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/**
 * The BREAKER kit: sixteen pads in the layout the owner agreed (2026-11-09, revised after rounds 2 and 3;
 * docs/BREAKER.md, "The kit"). A01 is bottom left, as the MPC lays them out.
 *
 * | | | | |
 * |---|---|---|---|
 * | A13 Ride | A14 Crash | A15 Snare Riser | A16 Shaker |
 * | A09 Floor Tom | A10 Rack Tom | A11 High Tom | A12 Crushed Snare |
 * | A05 Crushed Boom | A06 Drip Snare | A07 Cross Stick | A08 Tambourine |
 * | A01 Boom | A02 Bap | A03 Closed Hat | A04 Open Hat |
 *
 * Every pad is a [BreakerPatch], so it carries its own recipe. The starter deals each pad's velocity zones
 * and takes through the shared round-robin grid (`Robin`, in the shell): a softer zone is the drum
 * re-rendered at a softer velocity, and each take is the drum struck again ([Restrike]). Nothing here is a
 * private round-robin system.
 *
 * Literal: each drum's own settings, as the owner heard them (rounds 0 to 3). Shape: the second row's
 * pushes (A05, A06, A12), authored here and unheard, and the pad levels, set from the balance the owner
 * heard in the round-3 beats (ride and crash about a quarter and a third of the kick).
 */
object BreakerKits {
    const val ID = "breaker"

    /** The tempo every BREAKER beat was heard at. */
    const val BPM = 92f

    /** The grid each pad is dealt: three velocity zones of three takes, as the BACKWASH kits. */
    const val ZONES = 3
    const val TAKES = 3

    /**
     * Pad [slot]'s grid, (zones, takes). Every drum is struck by a synth and gets [ZONES] x [TAKES], except the
     * tambourine, whose source is recordings: two dynamic layers of two takes. A third zone would land on a
     * layer already used and differ only in level, and a third take would repeat the first, so it gets
     * exactly what was recorded.
     */
    fun gridFor(slot: Int): Pair<Int, Int> =
        if (roles[slot - 1].voice == BreakerVoice.TAMBOURINE) {
            val tamb = BackwashVoice.TAMBOURINE
            BackwashAcoustic.layerCount(tamb) to BackwashAcoustic.takeCount(tamb)
        } else ZONES to TAKES

    private class Role(val name: String, val voice: BreakerVoice, val level: Float, val push: Map<String, Float> = emptyMap())

    private val roles = listOf(
        Role("Boom", BreakerVoice.KICK, .90f),
        Role("Bap", BreakerVoice.SNARE, .80f),
        Role("Closed Hat", BreakerVoice.HAT_CLOSED, .35f),
        Role("Open Hat", BreakerVoice.HAT_OPEN, .35f),
        // The second row pushes the core: the kick and a snare crushed harder into a hotter amp, and the
        // snare thrown deeper into the spring with more slap.
        Role("Crushed Boom", BreakerVoice.KICK, .85f, mapOf("CRUSH" to 1f, "DUST" to .5f, "AMP" to .55f)),
        Role("Drip Snare", BreakerVoice.SNARE, .75f, mapOf("DRIP" to .9f, "SLAP" to .35f)),
        Role("Cross Stick", BreakerVoice.STICK, .55f),
        Role("Tambourine", BreakerVoice.TAMBOURINE, .45f),
        Role("Floor Tom", BreakerVoice.TOM_FLOOR, .70f),
        Role("Rack Tom", BreakerVoice.TOM_RACK, .70f),
        Role("High Tom", BreakerVoice.TOM_HIGH, .70f),
        Role("Crushed Snare", BreakerVoice.SNARE, .75f, mapOf("CRUSH" to 1f, "DUST" to .5f, "AMP" to .55f, "DRIP" to .35f)),
        Role("Ride", BreakerVoice.RIDE, .27f),
        Role("Crash", BreakerVoice.CRASH, .32f),
        Role("Snare Riser", BreakerVoice.RISER, .60f),
        Role("Shaker", BreakerVoice.SHAKER, .35f),
    )

    /** The sixteen pads' sounds, A01 first. */
    fun patches(): List<BreakerPatch> = roles.map { BreakerPatch(it.name, it.voice, Breaker.defaults(it.voice) + it.push) }

    /** The pads' levels, A01 first: the kit's balance. */
    fun levels(): List<Float> = roles.map { it.level }

    /** The sixteen pads, each its full-strength first take with its recipe; the starter deals the grid. */
    fun build(): List<ArrangedPad> = roles.zip(patches()).map { (role, patch) ->
        ArrangedPad(
            patch.render(), Breaker.drumClassFor(patch.voice), PadRecipe(patch).toJsonValue(),
            level = role.level, displayName = patch.name,
            // The open hat chokes the closed one; nothing else chokes, the cymbals included.
            muteGroup = if (patch.voice == BreakerVoice.HAT_CLOSED || patch.voice == BreakerVoice.HAT_OPEN) 1 else 0,
            source = mapOf("engine" to "BREAKER", "model" to patch.model.toString(), "kit" to ID),
        )
    }
}
