package com.snipsnap.synth

/**
 * Dry endpoint/midpoint comparisons from the listening studies.
 * The original twelve retain model 3; the seven Study 009 setups use model 4.
 * Factory recipes save every control explicitly so a future default cannot revoice them.
 */
object OnsetPresets {
    private fun controls(tune: Float, decay: Float, blend: Float, snap: Float, body: Float, edge: Float) =
        linkedMapOf("TUNE" to tune, "DECAY" to decay, "BLEND" to blend,
            "SNAP" to snap, "BODY" to body, "EDGE" to edge)

    private fun legacy(voice: OnsetVoice, name: String, blend: Float): OnsetPatch {
        val macros = when (voice) {
            OnsetVoice.KICK -> controls(.35f, .45f, blend, .5f, .6f, .5f)
            OnsetVoice.SNARE -> controls(.4f, .5f, blend, .6f, .6f, .5f)
            OnsetVoice.HAT_CLOSED -> controls(.5f, .3f, blend, .35f, .45f, .5f)
            OnsetVoice.HAT_OPEN -> controls(.5f, .55f, blend, .35f, .45f, .5f)
            else -> error("$voice has no model-3 factory preset")
        }
        return OnsetPatch(name, voice, macros, modelVersion = 3)
    }

    private val legacyPresets: List<OnsetPatch> by lazy {
        listOf(
            legacy(OnsetVoice.KICK, "PULSE KICK", 0f),
            legacy(OnsetVoice.KICK, "HYBRID KICK", .5f),
            legacy(OnsetVoice.KICK, "MEMBRANE KICK", 1f),
            legacy(OnsetVoice.SNARE, "MEMBRANE SNARE", 0f),
            legacy(OnsetVoice.SNARE, "HYBRID SNARE", .5f),
            legacy(OnsetVoice.SNARE, "FM SNARE", 1f),
            legacy(OnsetVoice.HAT_CLOSED, "SQUARE CLOSED", 0f),
            legacy(OnsetVoice.HAT_CLOSED, "HYBRID CLOSED", .5f),
            legacy(OnsetVoice.HAT_CLOSED, "RESONANT CL", 1f),
            legacy(OnsetVoice.HAT_OPEN, "SQUARE OPEN", 0f),
            legacy(OnsetVoice.HAT_OPEN, "HYBRID OPEN", .5f),
            legacy(OnsetVoice.HAT_OPEN, "RESONANT OPEN", 1f),
        )
    }

    private fun study(
        voice: OnsetVoice, names: List<String>, tune: Float, decay: Float,
        snap: Float, body: Float, edge: Float,
    ): List<OnsetPatch> = listOf(0f, .5f, 1f).zip(names) { blend, name ->
        OnsetPatch(name, voice, controls(tune, decay, blend, snap, body, edge), modelVersion = 4)
    }

    // These are the Float snapshots used by Study 009, not newly authored variations.
    // Wood and metal keep separate source/blend branches when BODY is edited.
    private val extensionPresets: List<OnsetPatch> by lazy {
        study(OnsetVoice.TOM, listOf("FLOOR PULSE", "FLOOR HYBRID", "FLOOR MEMBRANE"),
            .1575757903796332f, .56f, .38f, .62f, .36f) +
        study(OnsetVoice.TOM, listOf("MID PULSE", "MID HYBRID", "MID MEMBRANE"),
            .47057701472336333f, .46f, .44f, .58f, .44f) +
        study(OnsetVoice.TOM, listOf("HIGH PULSE", "HIGH HYBRID", "HIGH MEMBRANE"),
            .7887558876326781f, .36f, .5f, .5f, .52f) +
        study(OnsetVoice.PERC_PLUCK, listOf("DRY PULSE", "DRY HYBRID", "DRY STRING"),
            .31739380551401475f, .28f, .32f, .24f, .34f) +
        study(OnsetVoice.PERC_PLUCK, listOf("WIRE PULSE", "WIRE HYBRID", "WIRE STRING"),
            .8072899079189928f, .62f, .54f, .72f, .76f) +
        study(OnsetVoice.PERC_WOOD, listOf("WOOD TAP", "WOOD HYBRID", "WOOD KNOCK"),
            .21166135567408279f, .18f, .4f, .18f, .34f) +
        study(OnsetVoice.PERC_METAL, listOf("METAL FM", "METAL HYBRID", "METAL RING"),
            .7625662524497593f, .72f, .48f, .86f, .74f)
    }

    /** Seven Study 009 midpoint recipes, in Floor/Mid/High/Dry/Wire/Wood/Metal kit order. */
    fun extensionStudy(): List<OnsetPatch> = extensionPresets.filter { it.macros.getValue("BLEND") == .5f }

    fun forVoice(voice: OnsetVoice): List<OnsetPatch> = all().filter { it.voice == voice }

    fun all(): List<OnsetPatch> = legacyPresets + extensionPresets
}
