package com.snipsnap.synth

/** Three owner-approved body directions, explicitly opt-in. The historical factory
 * roster and old saved patches remain unchanged while the mapped bank is verified. */
object AlloyMaterialPresets {
    val roundedPlug: AlloyPatch by lazy {
        val anchor = AlloyPresets.forVoice(AlloyVoice.BASS).single { it.name == "Rounded Plug" }
        anchor.copy(macros = anchor.macros + ("MATERIAL" to .5f), materialResponse = AlloyMaterialResponse(
            originalWeightPower = 2,
            operators = listOf(
                AlloyMaterialResponse.Operator(0.2, 0.35, 0.85),
                AlloyMaterialResponse.Operator(0.1, 0.2, 0.8),
                AlloyMaterialResponse.Operator(0.95, 0.18, 1.0),
                AlloyMaterialResponse.Operator(0.7, 0.1, 0.95),
            ),
            routing = listOf(
                AlloyMaterialResponse.Route(1, 0, 0.18),
                AlloyMaterialResponse.Route(3, 2, 0.28),
            ),
            woodLosses = listOf(0.04, 0.012, 0.008),
            woodGains = listOf(0.1, 1.0, 0.6),
            cutoffRatio = 16.0,
        ))
    }

    val longPrism: AlloyPatch by lazy {
        val anchor = AlloyPresets.forVoice(AlloyVoice.KEY).single { it.name == "Long Prism" }
        anchor.copy(macros = anchor.macros + ("MATERIAL" to .5f), materialResponse = AlloyMaterialResponse(
            originalWeightPower = 1,
            operators = listOf(
                AlloyMaterialResponse.Operator(0.65, 0.45, 0.85),
                AlloyMaterialResponse.Operator(0.75, 0.3, 0.9),
                AlloyMaterialResponse.Operator(0.25, 0.15, 0.55),
                AlloyMaterialResponse.Operator(0.1, 0.08, 0.1),
            ),
            routing = listOf(
                AlloyMaterialResponse.Route(1, 0, 0.04),
                AlloyMaterialResponse.Route(2, 1, 0.03),
            ),
            woodLosses = listOf(0.18, 0.045, 0.012, 0.008),
            woodGains = listOf(0.7, 0.45, 0.2, 0.1),
            cutoffRatio = 10.0,
        ))
    }

    val velvetRise: AlloyPatch by lazy {
        val anchor = AlloyPresets.forVoice(AlloyVoice.SOFT).single { it.name == "Velvet Rise" }
        anchor.copy(macros = anchor.macros + ("MATERIAL" to .5f), materialResponse = AlloyMaterialResponse(
            originalWeightPower = 1,
            operators = listOf(
                AlloyMaterialResponse.Operator(0.2, 1.0, 1.0),
                AlloyMaterialResponse.Operator(0.05, 0.3, 0.15),
                AlloyMaterialResponse.Operator(0.95, 1.2, 1.0),
                AlloyMaterialResponse.Operator(0.55, 0.8, 0.95),
            ),
            routing = listOf(
                AlloyMaterialResponse.Route(1, 0, 0.9),
                AlloyMaterialResponse.Route(2, 1, 0.2),
                AlloyMaterialResponse.Route(3, 0, 0.25),
            ),
            woodLosses = listOf(0.085, 0.025, 0.008),
            woodGains = listOf(0.15, 0.6, 0.85),
            cutoffRatio = 18.0,
        ))
    }

    fun all(): List<AlloyPatch> = listOf(roundedPlug, longPrism, velvetRise)

    /** Separate picker labels keep every historical factory name and saved recipe
     * intact. These reuse the three approved responses; they are not new voices. */
    fun pickerAdditions(): List<AlloyPatch> = listOf(
        roundedPlug.copy(name = "Rounded Body"),
        longPrism.copy(name = "Prism Body"),
        velvetRise.copy(name = "Velvet Body"),
    )

    fun pickerForVoice(voice: AlloyVoice): List<AlloyPatch> =
        pickerAdditions().filter { it.voice == voice }
}
