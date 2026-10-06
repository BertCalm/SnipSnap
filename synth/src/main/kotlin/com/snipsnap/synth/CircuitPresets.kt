package com.snipsnap.synth

/**
 * Twelve starting points for the imaginary moving ensemble. Names fit the
 * fourteen-character preset strip. These are audition candidates: the
 * engine's own canyon paths supply the space, so every preset lands dry.
 */
object CircuitPresets {

    private fun p(voice: CircuitVoice, name: String, vararg macros: Pair<String, Float>) =
        CircuitPatch(name, voice, Circuit.defaults(voice) + macros.toMap())

    fun forVoice(voice: CircuitVoice): List<CircuitPatch> = when (voice) {
        CircuitVoice.ROOT -> root
        CircuitVoice.PROCESSION -> procession
        CircuitVoice.ANSWER -> answer
        CircuitVoice.VOICED -> voiced
        CircuitVoice.EXPANSE -> expanse
        CircuitVoice.CONFLUENCE -> confluence
    }

    fun all(): List<CircuitPatch> = CircuitVoice.entries.flatMap { forVoice(it) }

    private val root = listOf(
        p(CircuitVoice.ROOT, "THREE BREATHS", "BREATH" to 0.55f, "DIAMETER" to 0.30f, "ORBIT" to 0.15f, "PACE" to 0.20f, "CANYON" to 0.35f),
        p(CircuitVoice.ROOT, "CLOSE CIRCLE", "BREATH" to 0.40f, "DIAMETER" to 0.05f, "ORBIT" to 0f, "PACE" to 0.25f, "CANYON" to 0.20f),
    )

    private val procession = listOf(
        p(CircuitVoice.PROCESSION, "SLOW PARADE", "BREATH" to 0.45f, "DIAMETER" to 0.45f, "ORBIT" to 0.20f, "PACE" to 0.25f, "CANYON" to 0.45f),
        p(CircuitVoice.PROCESSION, "MOVING ACCENTS", "BREATH" to 0.60f, "DIAMETER" to 0.65f, "ORBIT" to 0.80f, "PACE" to 0.55f, "CANYON" to 0.50f),
    )

    private val answer = listOf(
        p(CircuitVoice.ANSWER, "PAIRED WOOD", "BREATH" to 0.40f, "DIAMETER" to 0.45f, "ORBIT" to 0.15f, "PACE" to 0.35f, "CANYON" to 0.65f),
        p(CircuitVoice.ANSWER, "CLAY REPLY", "TUNE" to 7 / 24f, "BREATH" to 0.50f, "DIAMETER" to 0.60f, "ORBIT" to 0.30f, "PACE" to 0.25f, "CANYON" to 0.85f),
    )

    private val voiced = listOf(
        p(CircuitVoice.VOICED, "CHEST GESTURE", "TUNE" to 5 / 24f, "BREATH" to 0.85f, "DIAMETER" to 0.35f, "ORBIT" to 0.25f, "PACE" to 0.45f, "CANYON" to 0.40f),
        p(CircuitVoice.VOICED, "CANYON GAP", "BREATH" to 0.65f, "DIAMETER" to 0.55f, "ORBIT" to 0.20f, "PACE" to 0.15f, "CANYON" to 0.90f),
    )

    private val expanse = listOf(
        p(CircuitVoice.EXPANSE, "WIDE CIRCLE", "BREATH" to 0.45f, "DIAMETER" to 0.95f, "ORBIT" to 0.15f, "PACE" to 0.20f, "CANYON" to 0.85f),
        p(CircuitVoice.EXPANSE, "RETURNED DRONE", "BREATH" to 0.60f, "DIAMETER" to 0.75f, "ORBIT" to 0.30f, "PACE" to 0.30f, "CANYON" to 0.95f, "HOLD" to 0.80f),
    )

    private val confluence = listOf(
        p(CircuitVoice.CONFLUENCE, "FAST GATHER", "BREATH" to 0.75f, "DIAMETER" to 0.60f, "ORBIT" to 0.85f, "PACE" to 0.90f, "CANYON" to 0.60f),
        p(CircuitVoice.CONFLUENCE, "HELD CIRCLE", "BREATH" to 0.60f, "DIAMETER" to 0.55f, "ORBIT" to 0.55f, "PACE" to 0.65f, "CANYON" to 0.65f, "HOLD" to 1f),
    )
}
