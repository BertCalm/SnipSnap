package com.snipsnap.synth

/**
 * Six finite factory recipes for the HYBRID and AQUARIUM rooms.
 * The original listening recipes retain their controls; model 3 carries the accepted membrane mix.
 */
object ChamberPresets {
    const val MODEL = 3

    private fun p(voice: ChamberVoice, name: String, vararg macros: Pair<String, Float>) =
        ChamberPatch(name, voice, Chamber.defaults(voice) + macros, model = MODEL)

    private val presets = listOf(
        p(ChamberVoice.HYBRID, "Four Answers"),
        p(ChamberVoice.HYBRID, "Soft Split", "STRIKE" to .20f, "DIVIDE" to .90f, "PLACEMENT" to .25f),
        p(ChamberVoice.HYBRID, "Glass Corners", "STRIKE" to .80f, "MATERIAL" to .70f, "DIVIDE" to 1f, "PLACEMENT" to .85f),
        p(ChamberVoice.AQUARIUM, "Aquarium Ceiling"),
        p(ChamberVoice.AQUARIUM, "Quiet Canopy", "STRIKE" to .20f, "BALANCE" to .35f, "DIVIDE" to .90f, "PLACEMENT" to .25f),
        p(ChamberVoice.AQUARIUM, "High Water", "STRIKE" to .80f, "BALANCE" to .65f, "DIVIDE" to 1f, "PLACEMENT" to .85f),
    )

    fun forVoice(voice: ChamberVoice): List<ChamberPatch> = presets.filter { it.voice == voice }

    fun all(): List<ChamberPatch> = presets
}
