package com.snipsnap.synth

/** Published default, restrained and expressive settings at C4: accepted PIPE, provisional MUSETTE. */
object RegistersPresets {
    private fun p(voice: RegistersVoice, name: String, vararg macros: Pair<String, Float>) =
        RegistersPatch(name, voice, Registers.defaults(voice) + macros.toMap(),
            midi = 60, velocity = 1f, model = 3)

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
    )

    fun forVoice(voice: RegistersVoice): List<RegistersPatch> = presets.filter { it.voice == voice }
    fun all(): List<RegistersPatch> = presets
}
