package com.snipsnap.synth

/**
 * Provisional references, kept out of [Presets] while the engine is a prototype
 * (`PatchTypes.PROTOTYPES`). The drums' are settings the owner heard at round 0: the high crush they
 * picked, the glue below it, and the spring opened up. The cymbals' and the riser's start from the
 * round-3 picks (dry cymbals, the tamed riser) and dial the rig in. The fill's start from the owner's pick (2026-11-14).
 */
object BreakerPresets {
    fun forVoice(voice: BreakerVoice): List<BreakerPatch> = when (voice) {
        // The cymbals default dry; the other two dial the rig in.
        BreakerVoice.RIDE, BreakerVoice.CRASH -> listOf(
            BreakerPatch("Dry", voice, Breaker.defaults(voice)),
            BreakerPatch("Through the rig", voice, Breaker.defaults(voice) + mapOf("DUST" to .3f, "CRUSH" to .4f, "AMP" to .35f)),
            BreakerPatch("In the spring", voice, Breaker.defaults(voice) + mapOf("DRIP" to .45f, "CRUSH" to .3f)),
        )
        BreakerVoice.BUMP -> listOf(
            BreakerPatch("Kicked", voice, Breaker.defaults(voice)),
            BreakerPatch("Booted", voice, Breaker.defaults(voice) + mapOf("DRIP" to 1f, "CRUSH" to .8f)),
            BreakerPatch("Nudged", voice, Breaker.defaults(voice) + mapOf("DRIP" to .35f, "DAMP" to .75f)),
        )
        // The fill: the owner's pick, its spring at the toms' own as first built, and pushed into the crush.
        BreakerVoice.FILL -> listOf(
            BreakerPatch("Triplets down", voice, Breaker.defaults(voice)),
            BreakerPatch("Wet toms", voice, Breaker.defaults(voice) + ("DRIP" to .45f)),
            BreakerPatch("Crushed run", voice, Breaker.defaults(voice) + mapOf("CRUSH" to 1f, "AMP" to .55f)),
        )
        BreakerVoice.RISER -> listOf(
            BreakerPatch("Tamed", voice, Breaker.defaults(voice)),
            BreakerPatch("Pumping", voice, Breaker.defaults(voice) + mapOf("CRUSH" to .9f, "AMP" to .35f)),
            BreakerPatch("Short spring", voice, Breaker.defaults(voice) + ("DRIP" to .2f)),
        )
        else -> listOf(
            BreakerPatch("Crushed", voice, Breaker.defaults(voice)),
            BreakerPatch("Glued", voice, Breaker.defaults(voice) + ("CRUSH" to .2f)),
            BreakerPatch("Drip", voice, Breaker.defaults(voice) + ("DRIP" to .6f) + ("CRUSH" to .5f)),
        )
    }

    fun all(): List<BreakerPatch> = BreakerVoice.entries.flatMap(::forVoice)
}
