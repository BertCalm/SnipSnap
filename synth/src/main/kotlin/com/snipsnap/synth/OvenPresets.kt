package com.snipsnap.synth

/**
 * Ten original dry recipes for OVEN's first listening gate. The four
 * articulations share one struck-string and iron-body core; no external rack
 * supplies the halo or bloom. This small roster awaits the owner's sonic verdict.
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
            macros = arrayOf("HAMMER" to .35f, "BODY" to .76f, "RING" to .45f, "BLOOM" to .12f, "HALO" to .12f)),
        p(OvenVoice.GRAND, "Bright Ledger", velocity = .9f,
            macros = arrayOf("HAMMER" to .82f, "BODY" to .42f, "RING" to .56f, "BLOOM" to .10f, "HALO" to .10f)),
        p(OvenVoice.FELT, "Felt Ledger", velocity = .7f,
            macros = arrayOf("HAMMER" to .18f, "BODY" to .58f, "RING" to .42f, "BLOOM" to .12f, "HALO" to .10f)),
        p(OvenVoice.FELT, "Quiet Room", velocity = .55f,
            macros = arrayOf("HAMMER" to .08f, "BODY" to .34f, "RING" to .24f, "BLOOM" to 0f, "HALO" to .07f)),
        p(OvenVoice.FELT, "Soft Hearth", midi = 57, velocity = .65f,
            macros = arrayOf("HAMMER" to .28f, "BODY" to .72f, "RING" to .68f, "BLOOM" to .30f, "HALO" to .22f)),
        p(OvenVoice.FORGED, "Hammered Iron", midi = 55, velocity = .85f,
            macros = arrayOf("HAMMER" to .82f, "BODY" to .60f, "RING" to .56f, "BLOOM" to .10f, "HALO" to .16f)),
        p(OvenVoice.FORGED, "Cold Rivet", midi = 67, velocity = .9f,
            macros = arrayOf("HAMMER" to 1f, "BODY" to .28f, "RING" to .24f, "BLOOM" to 0f, "HALO" to .05f)),
        p(OvenVoice.BLOOM, "Afterglow", velocity = .75f,
            macros = arrayOf("HAMMER" to .42f, "BODY" to .55f, "RING" to .80f, "BLOOM" to .70f, "HALO" to .42f)),
        p(OvenVoice.BLOOM, "Hanging Frame", midi = 53, velocity = .7f,
            macros = arrayOf("HAMMER" to .22f, "BODY" to .72f, "RING" to .92f, "BLOOM" to .90f, "HALO" to .65f)),
    )

    fun all(): List<OvenPatch> = roster

    fun forVoice(voice: OvenVoice): List<OvenPatch> = roster.filter { it.voice == voice }
}
