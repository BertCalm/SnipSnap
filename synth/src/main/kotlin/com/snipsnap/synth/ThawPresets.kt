package com.snipsnap.synth

/**
 * THAW's initial factory roster: twelve material gestures, two for each voice, including two
 * settled held sounds. Every sound is dry and names all seven host controls; the plate network
 * supplies its own transformation without a rack effect. TUNE spans C3 through C5 in semitones.
 * These names and settings remain proposals for the owner's audition.
 */
object ThawPresets {

    private fun p(voice: ThawVoice, name: String, vararg macros: Pair<String, Float>) =
        ThawPatch(name, voice, macros.toMap())

    fun forVoice(voice: ThawVoice): List<ThawPatch> = when (voice) {
        ThawVoice.BRITTLE -> brittle
        ThawVoice.RUNNER -> runner
        ThawVoice.MELT -> melt
        ThawVoice.CHANNEL -> channel
        ThawVoice.FROST -> frost
        ThawVoice.SHEET -> sheet
    }

    fun all(): List<ThawPatch> = ThawVoice.entries.flatMap { forVoice(it) }

    private val brittle = listOf(
        p(ThawVoice.BRITTLE, "FIRST CONTACT", "TUNE" to 0f, "CONTACT" to 0.40f, "HEAT" to 0.20f, "FREEZE" to 0.70f, "CHANNELS" to 0.25f, "THICKNESS" to 0.35f, "HOLD" to 0f),
        p(ThawVoice.BRITTLE, "THIN ICE", "TUNE" to 12 / 24f, "CONTACT" to 0.25f, "HEAT" to 0.32f, "FREEZE" to 0.65f, "CHANNELS" to 0.38f, "THICKNESS" to 0.15f, "HOLD" to 0f),
    )

    private val runner = listOf(
        p(ThawVoice.RUNNER, "COPPER RUNNER", "TUNE" to 0f, "CONTACT" to 0.60f, "HEAT" to 0.50f, "FREEZE" to 0.40f, "CHANNELS" to 0.30f, "THICKNESS" to 0.50f, "HOLD" to 0f),
        p(ThawVoice.RUNNER, "FROZEN BRIDGE", "TUNE" to 12 / 24f, "CONTACT" to 0.48f, "HEAT" to 0.35f, "FREEZE" to 0.76f, "CHANNELS" to 0.78f, "THICKNESS" to 0.60f, "HOLD" to 0f),
    )

    private val melt = listOf(
        p(ThawVoice.MELT, "SOFT MELT", "TUNE" to 12 / 24f, "CONTACT" to 0.40f, "HEAT" to 0.75f, "FREEZE" to 0.25f, "CHANNELS" to 0.45f, "THICKNESS" to 0.45f, "HOLD" to 0f),
        p(ThawVoice.MELT, "WET EDGE", "TUNE" to 7 / 24f, "CONTACT" to 0.65f, "HEAT" to 0.90f, "FREEZE" to 0.16f, "CHANNELS" to 0.60f, "THICKNESS" to 0.28f, "HOLD" to 0f),
    )

    private val channel = listOf(
        p(ThawVoice.CHANNEL, "CLEAR CHANNEL", "TUNE" to 7 / 24f, "CONTACT" to 0.45f, "HEAT" to 0.55f, "FREEZE" to 0.45f, "CHANNELS" to 0.80f, "THICKNESS" to 0.55f, "HOLD" to 0f),
        p(ThawVoice.CHANNEL, "COLD CHOIR", "TUNE" to 12 / 24f, "CONTACT" to 0.32f, "HEAT" to 0.45f, "FREEZE" to 0.55f, "CHANNELS" to 0.90f, "THICKNESS" to 0.68f, "HOLD" to 1f),
    )

    private val frost = listOf(
        p(ThawVoice.FROST, "RETURNING FROST", "TUNE" to 5 / 24f, "CONTACT" to 0.50f, "HEAT" to 0.55f, "FREEZE" to 0.85f, "CHANNELS" to 0.70f, "THICKNESS" to 0.40f, "HOLD" to 0f),
        p(ThawVoice.FROST, "FINE FRACTURE", "TUNE" to 17 / 24f, "CONTACT" to 0.68f, "HEAT" to 0.72f, "FREEZE" to 0.95f, "CHANNELS" to 0.88f, "THICKNESS" to 0.30f, "HOLD" to 0f),
    )

    private val sheet = listOf(
        p(ThawVoice.SHEET, "SLOW SHEET", "TUNE" to 0f, "CONTACT" to 0.55f, "HEAT" to 0.45f, "FREEZE" to 0.35f, "CHANNELS" to 0.55f, "THICKNESS" to 0.85f, "HOLD" to 0f),
        p(ThawVoice.SHEET, "THERMAL CYCLE", "TUNE" to 0f, "CONTACT" to 0.45f, "HEAT" to 0.60f, "FREEZE" to 0.52f, "CHANNELS" to 0.70f, "THICKNESS" to 0.75f, "HOLD" to 1f),
    )
}
