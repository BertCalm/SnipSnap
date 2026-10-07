package com.snipsnap.synth

/**
 * Twelve starting vessel gestures from the Suture specification. Every preset names all
 * seven host controls and lands dry. Quiet Murmur and Returning Gap contain settled powered
 * loops; the other sounds contain one opening and closure. Settings await the owner's audition.
 */
object SuturePresets {

    private fun p(voice: SutureVoice, name: String, vararg macros: Pair<String, Float>) =
        SuturePatch(name, voice, macros.toMap())

    fun forVoice(voice: SutureVoice): List<SuturePatch> = when (voice) {
        SutureVoice.BLOOM -> bloom
        SutureVoice.THREAD -> thread
        SutureVoice.CLOSE -> close
        SutureVoice.MURMUR -> murmur
        SutureVoice.STRAIN -> strain
        SutureVoice.SHELL -> shell
    }

    fun all(): List<SuturePatch> = SutureVoice.entries.flatMap { forVoice(it) }

    private val bloom = listOf(
        p(SutureVoice.BLOOM, "OPEN BRONZE", "TUNE" to 0.5f, "GAP" to 0.80f, "STITCH" to 0.30f, "CORD" to 0.30f, "SEAM" to 0.15f, "CAVITY" to 0.55f, "HOLD" to 0f),
        p(SutureVoice.BLOOM, "SOFT THREAD", "TUNE" to 0.5f, "GAP" to 0.55f, "STITCH" to 0.35f, "CORD" to 0.35f, "SEAM" to 0.10f, "CAVITY" to 0.50f, "HOLD" to 0f),
    )

    private val thread = listOf(
        p(SutureVoice.THREAD, "WOODEN EYE", "TUNE" to 0.5f, "GAP" to 0.50f, "STITCH" to 0.65f, "CORD" to 0.80f, "SEAM" to 0.15f, "CAVITY" to 0.35f, "HOLD" to 0f),
        p(SutureVoice.THREAD, "TIGHT CORD", "TUNE" to 0.5f, "GAP" to 0.40f, "STITCH" to 0.75f, "CORD" to 0.90f, "SEAM" to 0.25f, "CAVITY" to 0.40f, "HOLD" to 0f),
    )

    private val close = listOf(
        p(SutureVoice.CLOSE, "SLOW TAKE-UP", "TUNE" to 0.5f, "GAP" to 0.75f, "STITCH" to 0.15f, "CORD" to 0.45f, "SEAM" to 0.30f, "CAVITY" to 0.60f, "HOLD" to 0f),
        p(SutureVoice.CLOSE, "CLOSING SHELL", "TUNE" to 0.5f, "GAP" to 0.60f, "STITCH" to 0.70f, "CORD" to 0.40f, "SEAM" to 0.35f, "CAVITY" to 0.75f, "HOLD" to 0f),
    )

    private val murmur = listOf(
        p(SutureVoice.MURMUR, "FINE SEAM", "TUNE" to 0.5f, "GAP" to 0.35f, "STITCH" to 0.55f, "CORD" to 0.40f, "SEAM" to 0.75f, "CAVITY" to 0.65f, "HOLD" to 0f),
        p(SutureVoice.MURMUR, "QUIET MURMUR", "TUNE" to 0.5f, "GAP" to 0.30f, "STITCH" to 0.40f, "CORD" to 0.35f, "SEAM" to 0.65f, "CAVITY" to 0.75f, "HOLD" to 1f),
    )

    private val strain = listOf(
        p(SutureVoice.STRAIN, "RESISTED STITCH", "TUNE" to 0.5f, "GAP" to 0.80f, "STITCH" to 0.75f, "CORD" to 0.85f, "SEAM" to 0.40f, "CAVITY" to 0.50f, "HOLD" to 0f),
        p(SutureVoice.STRAIN, "STRAINED EDGE", "TUNE" to 0.5f, "GAP" to 0.65f, "STITCH" to 0.85f, "CORD" to 0.90f, "SEAM" to 0.80f, "CAVITY" to 0.45f, "HOLD" to 0f),
    )

    private val shell = listOf(
        p(SutureVoice.SHELL, "DEEP VESSEL", "TUNE" to 0f, "GAP" to 0.45f, "STITCH" to 0.40f, "CORD" to 0.35f, "SEAM" to 0.20f, "CAVITY" to 0.95f, "HOLD" to 0f),
        p(SutureVoice.SHELL, "RETURNING GAP", "TUNE" to 0f, "GAP" to 0.70f, "STITCH" to 0.40f, "CORD" to 0.50f, "SEAM" to 0.25f, "CAVITY" to 0.80f, "HOLD" to 1f),
    )
}
