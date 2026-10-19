package com.snipsnap.synth

/** The five published model-2 encounters, retaining their complete auditioned recipes. */
object OhmPresets {
    /** Preserve the supplied macro map; saved model and performance fields can be copied afterward. */
    private fun p(voice: OhmVoice, name: String, vararg macros: Pair<String, Float>) = when (voice) {
        OhmVoice.STRING -> OhmPatch(name, macros.toMap(), midi = 60, velocity = .8f, seed = 731, model = 2)
    }

    private fun encounter(name: String, meddling: Float, commune: Float, gesture: OhmGesture = OhmGesture.STEADY) =
        p(OhmVoice.STRING, name, "TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f,
            "MEDDLING" to meddling, "COMMUNE" to commune).copy(gesture = gesture)

    private val presets = listOf(
        encounter("Solo", 0f, 0f),
        encounter("Argument", .9f, 0f),
        encounter("Communion", .9f, 1f),
        encounter("Quiet Company", .25f, 1f),
        encounter("Gather", .9f, 1f, OhmGesture.GATHER),
    )

    fun forVoice(voice: OhmVoice): List<OhmPatch> = presets.filter { it.voice == voice }
    fun all(): List<OhmPatch> = presets
}
