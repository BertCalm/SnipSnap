package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/**
 * BREAKER's three kits, each sixteen pads in the layout the owner approved, A01 bottom left as the MPC lays
 * them out (docs/BREAKER.md, "The kit" and "The genre kits"):
 * - [Kit.BREAKER]: surf rock by way of boom bap (agreed 2026-11-09, accepted on the open tone 2026-11-12).
 * - [Kit.DUB_TANK]: one-drop dub at 74 BPM. The backbeat is a rimshot on three, a snare is thrown into a
 *   long echo, the toms ring like timbales, and A15 is the tank kicked (layout approved 2026-11-12).
 * - [Kit.TRIP_HOP]: a heavy break at 92 BPM. The kick and snare sit a semitone down and fatter, a damped
 *   ghost snare and a short second kick work the break, and the ride and crash are dialled into the rig
 *   (layout approved 2026-11-12).
 *
 * The genre kits differ from BREAKER and from each other in what the pads are and do, in their tuning and
 * in their damping (DAMP, model 5), not only in how much rig each drum gets. Where a drum is meant to sound
 * damped, its spring is dried too: in a wet spring a damped drum's tail is rebuilt (docs/BREAKER.md,
 * "Model 5").
 *
 * Every pad is a [BreakerPatch], so it carries its own recipe. The starter deals each pad's velocity zones
 * and takes through the shared round-robin grid (`Robin`, in the shell): a softer zone is the drum
 * re-rendered at a softer velocity, and each take is the drum struck again ([Restrike]). Nothing here is a
 * private round-robin system.
 *
 * Literal: BREAKER's drums as the owner heard them (rounds 0 to 3), and the tunings and echo times the
 * layouts name, set from SKIN's own pitch maps and SLAP's time law. Shape: every other push, the damping and
 * the pad levels (set from the balance the owner heard in BREAKER's beats: ride and crash about a quarter and
 * a third of the kick).
 */
object BreakerKits {
    /** The BREAKER kit's starter id. */
    const val ID = "breaker"

    /** The tempo every BREAKER beat was heard at. */
    const val BPM = 92f

    /** The grid each pad is dealt: three velocity zones of three takes, as the BACKWASH kits. */
    const val ZONES = 3
    const val TAKES = 3

    /**
     * A kit's starter id, shelf title, tempo and the renderer model its pads are saved on. The BREAKER kit stays
     * on model 5, the sound the owner accepted (model 4's, at DAMP .5); the genre kits are built on model 6, whose
     * later kick and tom takes land with another beater (`Breaker`, BEATER_SPREAD).
     */
    enum class Kit(val id: String, val title: String, val bpm: Float, val model: Int) {
        BREAKER(ID, "BREAKER", BPM, 5),
        DUB_TANK("breaker-dub", "BREAKER DUB TANK", 74f, 6),
        TRIP_HOP("breaker-triphop", "BREAKER TRIP HOP", 92f, 6),
    }

    fun byId(id: String): Kit? = Kit.entries.firstOrNull { it.id == id }

    /**
     * Pad [slot]'s grid, (zones, takes). Every drum is struck by a synth and gets [ZONES] x [TAKES], except the
     * tambourine, whose source is recordings: two dynamic layers of two takes. A third zone would land on a
     * layer already used and differ only in level, and a third take would repeat the first, so it gets
     * exactly what was recorded.
     */
    fun gridFor(slot: Int, kit: Kit = Kit.BREAKER): Pair<Int, Int> =
        if (roles(kit)[slot - 1].voice == BreakerVoice.TAMBOURINE) {
            val tamb = BackwashVoice.TAMBOURINE
            BackwashAcoustic.layerCount(tamb) to BackwashAcoustic.takeCount(tamb)
        } else ZONES to TAKES

    private class Role(val name: String, val voice: BreakerVoice, val level: Float, val push: Map<String, Float> = emptyMap())

    private fun roles(kit: Kit): List<Role> = when (kit) {
        Kit.BREAKER -> breaker
        Kit.DUB_TANK -> dubTank
        Kit.TRIP_HOP -> tripHop
    }

    private val breaker = listOf(
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

    // TUNE moves a drum by a quarter of SKIN's own range either way. SKIN's kick spans 45 to 75 Hz (8.8
    // semitones), its snare 170 to 260 Hz (7.4) and its tom 90 to 220 Hz (15.5), so one semitone is TUNE
    // .113 on the kick, .136 on the snare and .065 on a tom. The cymbals move three semitones either way.
    // SLAP is a 95 ms slap up to .5 and grows to 375 ms at 1, so 300 ms is SLAP .87.

    /**
     * One-drop dub. The drop is the kick and rimshot together on three. The space is echo more than spring,
     * the toms are left open to ring, and the steppers kick and the dry snare are damped tight.
     */
    private val dubTank = listOf(
        // Low and round: a shade down, less damped, a little spring and the echo behind it.
        Role("Drop Kick", BreakerVoice.KICK, .90f, mapOf("TUNE" to .40f, "DAMP" to .35f, "DRIP" to .25f, "SLAP" to .3f, "CRUSH" to .6f, "AMP" to .3f, "DUST" to .3f)),
        // The backbeat: a rimshot on three, thrown into the 300 ms echo.
        Role("Rimshot", BreakerVoice.STICK, .65f, mapOf("TUNE" to .6f, "SLAP" to .87f, "DRIP" to .3f, "CRUSH" to .6f)),
        Role("Closed Hat", BreakerVoice.HAT_CLOSED, .35f, mapOf("SLAP" to .6f, "DRIP" to .2f, "CRUSH" to .5f)),
        Role("Open Hat", BreakerVoice.HAT_OPEN, .35f, mapOf("DAMP" to .6f, "DRIP" to .3f, "SLAP" to .4f, "CRUSH" to .5f)),
        // A snare thrown into the long echo (375 ms) and deep into the spring.
        Role("Thrown Snare", BreakerVoice.SNARE, .70f, mapOf("SLAP" to 1f, "DRIP" to .7f, "CRUSH" to .6f)),
        // Steppers: four on the floor wants a tight kick, so it is damped and nearly dry. Damped harder (.8)
        // and crushed at .8 its strikes measured as copies (43 dB apart), so it is damped and crushed less.
        Role("Steppers Kick", BreakerVoice.KICK, .85f, mapOf("TUNE" to .5f, "DAMP" to .7f, "DRIP" to .15f, "SLAP" to 0f, "CRUSH" to .6f)),
        // Damped, with its spring dried so the damping is heard.
        Role("Dry Snare", BreakerVoice.SNARE, .75f, mapOf("DAMP" to .75f, "DRIP" to .1f, "SLAP" to 0f, "CRUSH" to .5f)),
        Role("Tambourine", BreakerVoice.TAMBOURINE, .40f, mapOf("DRIP" to .3f, "SLAP" to .5f)),
        // The toms ring: open (low DAMP), into the spring.
        Role("Low Tom", BreakerVoice.TOM_FLOOR, .65f, mapOf("DAMP" to .25f, "DRIP" to .5f, "SLAP" to .4f, "CRUSH" to .6f)),
        Role("Mid Tom", BreakerVoice.TOM_RACK, .65f, mapOf("TUNE" to .55f, "DAMP" to .35f, "DRIP" to .45f, "SLAP" to .4f, "CRUSH" to .6f)),
        // As high as SKIN's tom goes (220 Hz, about 3 semitones over the high tom; the layout asked 5), open and bright.
        Role("High Timbale", BreakerVoice.TOM_HIGH, .60f, mapOf("TUNE" to 1f, "DAMP" to .3f, "DRIP" to .3f, "SLAP" to .5f, "CRUSH" to .5f)),
        Role("Shaker", BreakerVoice.SHAKER, .30f, mapOf("DRIP" to .2f, "SLAP" to .4f)),
        // The ride two semitones up, echoed.
        Role("Ride", BreakerVoice.RIDE, .27f, mapOf("TUNE" to .833f, "SLAP" to .3f)),
        Role("Crash", BreakerVoice.CRASH, .32f),
        // The owner's pick for A15: Bump at its Kicked preset.
        Role("Bump", BreakerVoice.BUMP, .50f),
        Role("Snare Riser", BreakerVoice.RISER, .55f, mapOf("SLAP" to .5f, "DRIP" to .7f)),
    )

    /**
     * A heavy, dusty break. The kick and snare sit a semitone down and fatter (less damped, more dust and
     * crush); the ghost snare and the second kick are damped short and dry so they stay out of the way.
     */
    private val tripHop = listOf(
        Role("Heavy Kick", BreakerVoice.KICK, .90f, mapOf("TUNE" to .274f, "DAMP" to .4f, "DUST" to .5f, "DRIP" to .3f, "SLAP" to .3f, "CRUSH" to .9f, "AMP" to .45f)),
        Role("Fat Snare", BreakerVoice.SNARE, .80f, mapOf("TUNE" to .228f, "DAMP" to .45f, "DUST" to .5f, "DRIP" to .55f, "SLAP" to .3f, "CRUSH" to .85f)),
        Role("Closed Hat", BreakerVoice.HAT_CLOSED, .35f, mapOf("DUST" to .6f, "DRIP" to .05f, "DAMP" to .55f)),
        // A half-open hat is an open hat held: damped.
        Role("Half-open Hat", BreakerVoice.HAT_OPEN, .33f, mapOf("DAMP" to .75f, "DUST" to .5f, "DRIP" to .05f)),
        // Ghost notes: the fat snare damped short and dry.
        Role("Ghost Snare", BreakerVoice.SNARE, .45f, mapOf("TUNE" to .228f, "DAMP" to .85f, "DUST" to .5f, "DRIP" to .1f, "SLAP" to 0f, "CRUSH" to .5f)),
        // The break's second kick: two semitones under the default, short and nearly dry. With no spring at all
        // (DRIP 0), its soft strikes were one body: everything that differed between them was a 0.4 ms click,
        // which the round robin's start trim can cut, leaving two takes 44 dB apart (a copy is 40). A touch of
        // spring, as on the ghost snare, keeps each strike its own (35 dB with the click cut).
        Role("Second Kick", BreakerVoice.KICK, .80f, mapOf("TUNE" to .048f, "DAMP" to .8f, "DUST" to .5f, "DRIP" to .1f, "SLAP" to 0f, "CRUSH" to .9f)),
        Role("Tambourine", BreakerVoice.TAMBOURINE, .40f, mapOf("DUST" to .5f, "DRIP" to .2f, "DAMP" to .65f)),
        Role("Open Hat", BreakerVoice.HAT_OPEN, .32f, mapOf("DAMP" to .3f, "CRUSH" to 1f, "DUST" to .5f)),
        // The toms three semitones down (two on the mid).
        Role("Low Tom", BreakerVoice.TOM_FLOOR, .70f, mapOf("TUNE" to .112f, "DAMP" to .45f, "DUST" to .5f, "DRIP" to .5f, "CRUSH" to .85f)),
        Role("Mid Tom", BreakerVoice.TOM_RACK, .70f, mapOf("TUNE" to .24f, "DAMP" to .45f, "DUST" to .5f, "DRIP" to .45f, "CRUSH" to .85f)),
        Role("Cross Stick", BreakerVoice.STICK, .50f, mapOf("TUNE" to .4f, "DAMP" to .6f, "DUST" to .6f, "DRIP" to .2f)),
        Role("Break Snare", BreakerVoice.SNARE, .75f, mapOf("TUNE" to .228f, "CRUSH" to 1f, "AMP" to .7f, "DUST" to .6f, "DRIP" to .3f)),
        // The ride and crash dialled into the rig.
        Role("Dusty Ride", BreakerVoice.RIDE, .27f, mapOf("TUNE" to .4f, "DUST" to .5f, "CRUSH" to .5f, "AMP" to .35f)),
        Role("Crash", BreakerVoice.CRASH, .32f, mapOf("DRIP" to .45f, "CRUSH" to .3f)),
        Role("Crushed Riser", BreakerVoice.RISER, .55f, mapOf("CRUSH" to .9f, "AMP" to .35f, "DUST" to .5f)),
        Role("Shaker", BreakerVoice.SHAKER, .30f, mapOf("DUST" to .5f, "DRIP" to .1f)),
    )

    /** [kit]'s sixteen pads' sounds, A01 first. */
    fun patches(kit: Kit = Kit.BREAKER): List<BreakerPatch> =
        roles(kit).map { BreakerPatch(it.name, it.voice, Breaker.defaults(it.voice, kit.model) + it.push, kit.model) }

    /** [kit]'s pad levels, A01 first: the kit's balance. */
    fun levels(kit: Kit = Kit.BREAKER): List<Float> = roles(kit).map { it.level }

    /** [kit]'s sixteen pads, each its full-strength first take with its recipe; the starter deals the grid. */
    fun build(kit: Kit = Kit.BREAKER): List<ArrangedPad> = roles(kit).zip(patches(kit)).map { (role, patch) ->
        ArrangedPad(
            patch.render(), Breaker.drumClassFor(patch.voice), PadRecipe(patch).toJsonValue(),
            level = role.level, displayName = patch.name,
            // The open hats choke the closed one; nothing else chokes, the cymbals included.
            muteGroup = if (patch.voice == BreakerVoice.HAT_CLOSED || patch.voice == BreakerVoice.HAT_OPEN) 1 else 0,
            source = mapOf("engine" to "BREAKER", "model" to patch.model.toString(), "kit" to kit.id),
        )
    }
}
