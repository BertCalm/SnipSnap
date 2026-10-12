package com.snipsnap.synth

/**
 * The six dry voice defaults and one settled held wheel for initial listening.
 * Additional contact and resin recipes wait for the owner's dry audition verdict.
 */
object PitchwheelPresets {
    private fun p(voice: PitchwheelVoice, name: String, vararg macros: Pair<String, Float>) =
        PitchwheelPatch(name, voice, Pitchwheel.defaults(voice) + macros.toMap())

    private val presets = listOf(
        p(PitchwheelVoice.CLUNK, "Wooden Ratchet"),
        p(PitchwheelVoice.PLUCK, "Pitched Tooth"),
        p(PitchwheelVoice.DRAW, "Resin Thread"),
        p(PitchwheelVoice.RECOIL, "Returning Tooth"),
        p(PitchwheelVoice.THAWED, "Warm Passage"),
        p(PitchwheelVoice.TURN, "Balanced Turn"),
        p(PitchwheelVoice.TURN, "Endless Turn", "HOLD" to 1f),
    )

    fun forVoice(voice: PitchwheelVoice): List<PitchwheelPatch> = presets.filter { it.voice == voice }

    fun all(): List<PitchwheelPatch> = presets
}
