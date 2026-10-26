package com.snipsnap.synth

/** PIPE/MUSETTE audition settings and four TRANSISTOR registrations at C4. */
object RegistersPresets {
    private fun p(voice: RegistersVoice, name: String, vararg macros: Pair<String, Float>) =
        RegistersPatch(name, voice, Registers.defaults(voice) + macros.toMap(),
            midi = 60, velocity = 1f, model = if (voice == RegistersVoice.TRANSISTOR) 6 else 3)

    private val presets = listOf(
        p(RegistersVoice.PIPE, "Pipe Air"),
        p(RegistersVoice.PIPE, "Quiet Speech", "COLOR" to .18f, "AIR" to .20f,
            "EDGE" to .08f, "SPREAD" to .06f, "MOTION" to .05f, "HOLD" to .35f),
        p(RegistersVoice.PIPE, "Open Speech", "COLOR" to .55f, "AIR" to .72f,
            "EDGE" to .62f, "SPREAD" to .62f, "MOTION" to .55f, "HOLD" to .35f),
        p(RegistersVoice.MUSETTE, "Three Reeds"),
        p(RegistersVoice.MUSETTE, "Quiet Bellows", "COLOR" to .32f, "AIR" to .24f,
            "EDGE" to .12f, "SPREAD" to .20f, "MOTION" to .08f, "HOLD" to .35f),
        p(RegistersVoice.MUSETTE, "Open Bellows", "COLOR" to .64f, "AIR" to .78f,
            "EDGE" to .52f, "SPREAD" to .78f, "MOTION" to .50f, "HOLD" to .35f),
        p(RegistersVoice.TRANSISTOR, "Compact Keys"),
        p(RegistersVoice.TRANSISTOR, "Clean Combo", "COLOR" to .25f, "AIR" to .10f,
            "EDGE" to .05f, "SPREAD" to 0f, "MOTION" to .08f, "HOLD" to .35f),
        p(RegistersVoice.TRANSISTOR, "Garage Buzz", "COLOR" to .78f, "AIR" to .45f,
            "EDGE" to .78f, "SPREAD" to .18f, "MOTION" to .55f, "HOLD" to .35f),
        // The owner's preferred depth direction keeps Compact Keys' registration and
        // keyer, with enough side-bank movement and vibrato to carry through sustain.
        p(RegistersVoice.TRANSISTOR, "Rich Ensemble", "SPREAD" to .65f, "MOTION" to .32f),
    )

    fun forVoice(voice: RegistersVoice): List<RegistersPatch> = presets.filter { it.voice == voice }
    fun all(): List<RegistersPatch> = presets
}
