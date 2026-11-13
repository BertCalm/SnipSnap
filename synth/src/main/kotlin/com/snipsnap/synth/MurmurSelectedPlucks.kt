package com.snipsnap.synth

/**
 * Explicit listening selections, separate from MurmurPresets and the factory kit.
 * The wire pair and the supported low pluck are complete, independent recipes, not
 * a keyboard split that changes body at a magic note. Copy MIDI/velocity to play them.
 * Normal render() retains house leveling; audition gain/provenance live in the review bank.
 */
object MurmurSelectedPlucks {
    private fun wireConfig(): MurmurConfig {
        val source = Murmur.configurationFor(MurmurVoice.PLUCK, model = 2)
        return source.copy(model = 4, body = MurmurBody.HOLLOW_WIRE_V1,
            ratios = listOf(1.0, 2.0, 2.006, 3.0),
            targets = source.targets.map { it.copy(widths = it.widths.map { width -> (width * 1.15).coerceAtMost(2000.0) }) })
    }

    fun openStringHollowWire() = MurmurPatch("OPEN STRING", MurmurVoice.PLUCK,
        mapOf("MOUTH" to .84f, "EDGE" to .22f, "BREATH" to .04f, "MOTION" to .02f, "BODY" to .88f, "HOLD" to 1f),
        midi = 60, velocity = .86f, config = wireConfig())

    fun shortAnswerHollowWire() = MurmurPatch("SHORT ANSWER", MurmurVoice.PLUCK,
        Murmur.defaults(MurmurVoice.PLUCK), midi = 60, velocity = 1f, config = wireConfig())

    fun shortAnswerRootSupported() = MurmurPatch("SHORT ANSWER", MurmurVoice.PLUCK,
        Murmur.defaults(MurmurVoice.PLUCK), midi = 36, velocity = .65f,
        config = Murmur.configurationFor(MurmurVoice.PLUCK, model = 2).copy(model = 4, body = MurmurBody.STEADY_ROOT_V1))
}
