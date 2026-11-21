package com.snipsnap.synth

/**
 * Six finite factory recipes for the HYBRID and AQUARIUM rooms.
 * Four Answers is the accepted model-3 sound. The owner found the original six "all pretty
 * similar" (20 Nov 2026), so the other five were re-voiced on the wider response.
 */
object ChamberPresets {
    const val MODEL = 3

    private fun first(voice: ChamberVoice, name: String, vararg macros: Pair<String, Float>) =
        ChamberPatch(name, voice, Chamber.defaults(voice) + macros, model = MODEL)

    /**
     * The six recipes as first published, all on model 3. The sixteen-pad kit is built on
     * these and stays exactly as the owner heard and kept it; only the factory roster moved.
     */
    private val published = listOf(
        first(ChamberVoice.HYBRID, "Four Answers"),
        first(ChamberVoice.HYBRID, "Soft Split", "STRIKE" to .20f, "DIVIDE" to .90f, "PLACEMENT" to .25f),
        first(ChamberVoice.HYBRID, "Glass Corners", "STRIKE" to .80f, "MATERIAL" to .70f, "DIVIDE" to 1f, "PLACEMENT" to .85f),
        first(ChamberVoice.AQUARIUM, "Aquarium Ceiling"),
        first(ChamberVoice.AQUARIUM, "Quiet Canopy", "STRIKE" to .20f, "BALANCE" to .35f, "DIVIDE" to .90f, "PLACEMENT" to .25f),
        first(ChamberVoice.AQUARIUM, "High Water", "STRIKE" to .80f, "BALANCE" to .65f, "DIVIDE" to 1f, "PLACEMENT" to .85f),
    )

    fun original(): List<ChamberPatch> = published

    private fun p(voice: ChamberVoice, name: String, vararg macros: Pair<String, Float>) =
        ChamberPatch(name, voice, Chamber.defaults(voice) + macros, model = Chamber.REGISTER_MODEL)

    // Each recipe puts a different instrument and a different room in front. The five re-voiced
    // ones need the wider response (model 5): on model 3 MATERIAL and DIVIDE moved the whole-note
    // spectrum by 0.1 dB and BALANCE never let one instrument lead.
    private val presets = listOf(
        // All four at the room's own balance. The kit's note row; kept as heard.
        published.first(),
        // The drum skin, struck softly, close, in a dry wooden room.
        p(ChamberVoice.HYBRID, "Soft Split", "STRIKE" to .05f, "BALANCE" to 0f, "MATERIAL" to 0f, "DIVIDE" to .90f, "PLACEMENT" to .25f),
        // The triangle, struck hard, in a ringing glass room.
        p(ChamberVoice.HYBRID, "Glass Corners", "STRIKE" to .90f, "BALANCE" to 1f, "MATERIAL" to 1f, "DIVIDE" to 1f, "PLACEMENT" to .85f),
        // The cowbell, struck hard, under the water-glass canopy.
        p(ChamberVoice.AQUARIUM, "Aquarium Ceiling", "STRIKE" to .90f, "BALANCE" to .67f),
        // The bar, struck softly and close: the quiet, pitched one.
        p(ChamberVoice.AQUARIUM, "Quiet Canopy", "STRIKE" to .10f, "BALANCE" to .33f, "DIVIDE" to .90f, "PLACEMENT" to .25f),
        // Cowbell and triangle, hard, in one continuous glass room: the long ring.
        p(ChamberVoice.AQUARIUM, "High Water", "STRIKE" to 1f, "BALANCE" to .85f, "MATERIAL" to 1f, "DIVIDE" to 0f, "PLACEMENT" to .85f),
    )

    fun forVoice(voice: ChamberVoice): List<ChamberPatch> = presets.filter { it.voice == voice }

    fun all(): List<ChamberPatch> = presets
}
