package com.snipsnap.synth

/** The ten proposed listening positions, kept dry so the ensemble supplies its own motion. */
object RevelPresets {
    private fun p(voice: RevelVoice, name: String, vararg macros: Pair<String, Float>) =
        RevelPatch(name, voice, Revel.defaults(voice) + macros.toMap())

    fun forVoice(voice: RevelVoice): List<RevelPatch> = roster.filter { it.voice == voice }
    fun all(): List<RevelPatch> = roster

    private val roster = listOf(
        p(RevelVoice.CIRCLE, "Inner Circle", "PLAY" to 0.55f, "SKIN" to 0.50f, "ORBIT" to 0.35f, "WEAVE" to 0.10f, "REACH" to 0.55f),
        p(RevelVoice.CIRCLE, "Skin Conversation", "PLAY" to 0.42f, "SKIN" to 0.28f, "ORBIT" to 0.22f, "WEAVE" to 0.18f, "REACH" to 0.42f),
        p(RevelVoice.CLOSE, "Close Pass", "PLAY" to 0.50f, "SKIN" to 0.55f, "ORBIT" to 0.40f, "WEAVE" to 0.25f, "REACH" to 0.85f),
        p(RevelVoice.CROSSING, "Two Directions", "PLAY" to 0.60f, "SKIN" to 0.50f, "ORBIT" to 0.50f, "WEAVE" to 0.45f, "REACH" to 0.70f),
        p(RevelVoice.SPIRO, "Three Listeners", "PLAY" to 0.70f, "SKIN" to 0.60f, "ORBIT" to 0.55f, "WEAVE" to 0.85f, "REACH" to 0.75f),
        p(RevelVoice.SPIRO, "Flower Path", "PLAY" to 0.56f, "SKIN" to 0.72f, "ORBIT" to 0.38f, "WEAVE" to 0.98f, "REACH" to 0.90f),
        p(RevelVoice.FRICTION, "Elastic Answer", "PLAY" to 0.45f, "SKIN" to 0.40f, "ORBIT" to 0.35f, "WEAVE" to 0.55f, "REACH" to 0.70f),
        p(RevelVoice.PROCESSION, "Rolling Floor", "PLAY" to 0.82f, "SKIN" to 0.48f, "ORBIT" to 0.36f, "WEAVE" to 0.32f, "REACH" to 0.67f),
        p(RevelVoice.PROCESSION, "Deep Gathering", "TUNE" to 0.25f, "PLAY" to 0.65f, "SKIN" to 0.18f, "ORBIT" to 0.18f, "WEAVE" to 0.20f, "REACH" to 0.46f),
        p(RevelVoice.CROSSING, "Held Revel", "PLAY" to 0.58f, "SKIN" to 0.46f, "ORBIT" to 0.45f, "WEAVE" to 0.60f, "REACH" to 0.72f, "HOLD" to 1f),
    )
}
