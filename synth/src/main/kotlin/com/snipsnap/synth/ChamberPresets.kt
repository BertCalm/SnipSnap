package com.snipsnap.synth

/** Provisional finite recipes, available to the listening bench without joining the factory roster. */
object ChamberPresets {
    private fun p(voice: ChamberVoice, name: String, vararg macros: Pair<String, Float>) =
        ChamberPatch(name, voice, Chamber.defaults(voice) + macros)

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
