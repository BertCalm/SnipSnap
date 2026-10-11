package com.snipsnap.synth

/** Dry factory starting points for the six same-note metal characters and their suspension. */
object NimbusPresets {
    // SAVE AS PRESET exports this helper's shape for pasting a player's recipe into the roster.
    private fun p(voice: NimbusVoice, name: String, vararg macros: Pair<String, Float>) =
        NimbusPatch(name, voice, macros.toMap())

    private fun sound(
        voice: NimbusVoice,
        name: String,
        excite: Float,
        spacing: Float,
        height: Float,
        field: Float,
        funnel: Float,
        hold: Float = 0f,
    ) = p(voice, name,
        "TUNE" to 0.5f,
        "EXCITE" to excite,
        "SPACING" to spacing,
        "HEIGHT" to height,
        "FIELD" to field,
        "FUNNEL" to funnel,
        "HOLD" to hold,
    )

    private val ring = listOf(
        sound(NimbusVoice.RING, "SIX RINGS", .50f, .70f, .65f, .55f, .35f),
        sound(NimbusVoice.RING, "WIDE MOUTH", .48f, .75f, .95f, .50f, .30f),
    )
    private val shimmer = listOf(
        sound(NimbusVoice.SHIMMER, "THIN CROWN", .35f, .50f, .70f, .45f, .50f),
        sound(NimbusVoice.SHIMMER, "DARK PLATE", .28f, .48f, .35f, .55f, .78f),
    )
    private val gather = listOf(
        sound(NimbusVoice.GATHER, "GATHERING STACK", .65f, .35f, .50f, .35f, .55f),
        sound(NimbusVoice.GATHER, "SOFT FIELD", .50f, .40f, .55f, .15f, .45f),
    )
    private val throat = listOf(
        sound(NimbusVoice.THROAT, "NARROW THROAT", .45f, .45f, .15f, .50f, .85f),
    )
    private val contact = listOf(
        sound(NimbusVoice.CONTACT, "RIM KISS", .65f, .10f, .50f, .55f, .55f),
    )
    private val suspend = listOf(
        sound(NimbusVoice.SUSPEND, "HELD METAL", .30f, .55f, .55f, .65f, .65f, 1f),
    )

    fun forVoice(voice: NimbusVoice): List<NimbusPatch> = when (voice) {
        NimbusVoice.RING -> ring
        NimbusVoice.SHIMMER -> shimmer
        NimbusVoice.GATHER -> gather
        NimbusVoice.THROAT -> throat
        NimbusVoice.CONTACT -> contact
        NimbusVoice.SUSPEND -> suspend
    }

    fun all(): List<NimbusPatch> = NimbusVoice.entries.flatMap { forVoice(it) }
}
