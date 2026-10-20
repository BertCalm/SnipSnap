package com.snipsnap.synth

/**
 * Ten original dry recipes for OVEN's listening gate. The four
 * articulations share one struck-string and iron-body core; no external rack
 * supplies the halo or bloom. The second roster separates attack, colour and
 * decay at a common note and velocity; saved patches retain their own controls.
 * The owner accepted this revised ten-preset roster as the integration baseline.
 */
object OvenPresets {
    private fun p(
        voice: OvenVoice,
        name: String,
        midi: Int = Oven.DEFAULT_MIDI,
        velocity: Float = .8f,
        vararg macros: Pair<String, Float>,
    ) = OvenPatch(name, voice, Oven.defaults(voice) + macros.toMap(), midi, velocity)

    private val roster = listOf(
        p(OvenVoice.GRAND, "Open Lid", velocity = .8f),
        p(OvenVoice.GRAND, "Iron Parlour", velocity = .75f,
            macros = arrayOf("HAMMER" to .35f, "BODY" to 1f, "RING" to .65f, "BLOOM" to 0f, "HALO" to .40f)),
        p(OvenVoice.GRAND, "Bright Ledger", velocity = .9f,
            macros = arrayOf("HAMMER" to 1f, "BODY" to 0f, "RING" to .42f, "BLOOM" to 0f, "HALO" to 0f)),
        p(OvenVoice.FELT, "Felt Ledger", velocity = .7f,
            macros = arrayOf("HAMMER" to 0f, "BODY" to 1f, "RING" to .05f, "BLOOM" to 0f, "HALO" to 0f)),
        p(OvenVoice.FELT, "Quiet Room", velocity = .55f,
            macros = arrayOf("HAMMER" to .50f, "BODY" to .05f, "RING" to .90f, "BLOOM" to 0f, "HALO" to .05f)),
        p(OvenVoice.FELT, "Soft Hearth", midi = 57, velocity = .65f,
            macros = arrayOf("HAMMER" to .05f, "BODY" to .85f, "RING" to .70f, "BLOOM" to .45f, "HALO" to .45f)),
        p(OvenVoice.FORGED, "Hammered Iron", midi = 55, velocity = .85f,
            macros = arrayOf("HAMMER" to 1f, "BODY" to 1f, "RING" to .28f, "BLOOM" to 0f, "HALO" to 0f)),
        p(OvenVoice.FORGED, "Cold Rivet", midi = 67, velocity = .9f,
            macros = arrayOf("HAMMER" to 1f, "BODY" to 0f, "RING" to 0f, "BLOOM" to 0f, "HALO" to 0f)),
        p(OvenVoice.BLOOM, "Afterglow", velocity = .75f,
            macros = arrayOf("HAMMER" to .65f, "BODY" to .10f, "RING" to .25f, "BLOOM" to .55f, "HALO" to 1f)),
        p(OvenVoice.BLOOM, "Hanging Frame", midi = 53, velocity = .7f,
            macros = arrayOf("HAMMER" to 1f, "BODY" to 1f, "RING" to 1f, "BLOOM" to 1f, "HALO" to .75f)),
    )

    private val roles = mapOf(
        "Open Lid" to "Balanced piano strike with a clear attack and natural ring.",
        "Iron Parlour" to "Dark, weighted piano tone with a resonant frame.",
        "Bright Ledger" to "Bright, direct piano notes for a melodic line.",
        "Felt Ledger" to "Compact, muted felt notes for close, rhythmic playing.",
        "Quiet Room" to "Clear, gentle piano notes with a lingering decay.",
        "Soft Hearth" to "Warm notes that ease into a rounded swell.",
        "Hammered Iron" to "Firm, weighty strikes with a short iron-body ring.",
        "Cold Rivet" to "Dry, bright taps for pitched percussion.",
        "Afterglow" to "A moderate swell followed by a harmonic after-ring.",
        "Hanging Frame" to "A slow, dark swell with a long frame resonance.",
    )

    fun all(): List<OvenPatch> = roster

    fun forVoice(voice: OvenVoice): List<OvenPatch> = roster.filter { it.voice == voice }

    /** The musical role of a factory recipe; unknown or user-authored names have none. */
    fun roleDescription(name: String): String? = roles[name]
}
