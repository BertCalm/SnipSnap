package com.snipsnap.synth

/**
 * Provisional references, kept out of [Presets] until the owner hears round 1 (the engine is a
 * prototype, `PatchTypes.PROTOTYPES`). Each is a setting the owner heard at round 0: the high crush
 * they picked, the glue below it, and the spring opened up.
 */
object BreakerPresets {
    fun forVoice(voice: BreakerVoice): List<BreakerPatch> = listOf(
        BreakerPatch("Crushed", voice, Breaker.defaults(voice)),
        BreakerPatch("Glued", voice, Breaker.defaults(voice) + ("CRUSH" to .2f)),
        BreakerPatch("Drip", voice, Breaker.defaults(voice) + ("DRIP" to .6f) + ("CRUSH" to .5f)),
    )

    fun all(): List<BreakerPatch> = BreakerVoice.entries.flatMap(::forVoice)
}
