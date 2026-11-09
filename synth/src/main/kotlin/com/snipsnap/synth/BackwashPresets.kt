package com.snipsnap.synth

/** The original kit plus the four approved processing treatments as editable presets. */
object BackwashPresets {
    private fun p(voice: BackwashVoice, name: String, vararg settings: Pair<String, Float>) =
        BackwashPatch(name, voice, Backwash.defaults(voice) + settings)

    /** Bank-A pad order is also the export source of truth. */
    private val originalBank = listOf(
        p(BackwashVoice.KICK, "Core Kick"),
        p(BackwashVoice.SNARE, "Core Snare"),
        p(BackwashVoice.HAT_CLOSED, "Core Closed"),
        p(BackwashVoice.HAT_OPEN, "Core Open"),
        p(BackwashVoice.KICK, "Tight Kick", "DAMP" to .9f),
        p(BackwashVoice.SNARE, "Tight Snare", "DAMP" to .9f),
        p(BackwashVoice.HAT_CLOSED, "Tight Closed", "DAMP" to .9f),
        p(BackwashVoice.HAT_OPEN, "Open Ring", "DAMP" to .6f),
        p(BackwashVoice.TOM, "Low Tom", "TUNE" to .15f),
        p(BackwashVoice.TOM, "Mid Tom"),
        p(BackwashVoice.TOM, "High Tom", "TUNE" to .58f),
        p(BackwashVoice.TOM, "Tight Tom", "DAMP" to .9f),
        p(BackwashVoice.KICK, "Double Kick", "SWAY" to .45f, "TRAIL" to .4f),
        p(BackwashVoice.SNARE, "Double Snare", "SWAY" to .45f, "TRAIL" to .4f),
        p(BackwashVoice.HAT_CLOSED, "Double Closed", "SWAY" to .45f, "TRAIL" to .4f),
        p(BackwashVoice.HAT_OPEN, "Double Open", "SWAY" to .45f, "TRAIL" to .4f),
    ).map { it.copy(model = 3) }
    private val processing = BackwashVoice.entries.filter { it.legacy }.flatMap { voice ->
        val suffix = when (voice) {
            BackwashVoice.KICK -> "Kick"
            BackwashVoice.SNARE -> "Snare"
            BackwashVoice.TOM -> "Tom"
            BackwashVoice.HAT_CLOSED -> "Closed"
            BackwashVoice.HAT_OPEN -> "Open"
            else -> error("processing study covers the original voices")
        }
        listOf(
            p(voice, "Seasick $suffix", "SWAY" to 1f, "TRAIL" to 1f),
            p(voice, "Clamped $suffix", "PRESS" to 1f),
            p(voice, "Overloaded $suffix", "DRIVE" to 1f),
            p(voice, "Pushed $suffix", "SWAY" to 1f, "TRAIL" to 1f, "PRESS" to 1f, "DRIVE" to 1f),
        )
    }
    private val replacements = listOf(
        p(BackwashVoice.CROSS_STICK, "Cross Stick"), p(BackwashVoice.TAMBOURINE, "Tambourine"),
        p(BackwashVoice.COWBELL, "Cowbell"), p(BackwashVoice.RIDE, "Ride Cymbal"),
    )
    private val bank = originalBank.take(12) + replacements
    /** Historical audition source only; retired Double recipes remain replayable from JSON. */
    internal fun legacyKitOrder(): List<BackwashPatch> = originalBank

    /** Core row combined, second row clamped, toms overloaded, last row seasick. */
    fun processingKitOrder(): List<BackwashPatch> = listOf(
        "Pushed Kick", "Pushed Snare", "Pushed Closed", "Pushed Open",
        "Clamped Kick", "Clamped Snare", "Clamped Closed", "Clamped Open",
        "Overloaded Tom", "Pushed Tom", "Clamped Tom", "Seasick Tom",
        "Cross Stick", "Tambourine", "Cowbell", "Ride Cymbal",
    ).map { name -> (processing + replacements).single { it.name == name } }

    fun kitOrder(): List<BackwashPatch> = bank
    fun all(): List<BackwashPatch> = BackwashVoice.entries.flatMap(::forVoice)
    // The first preset for each voice keeps the heard default, including the mid tom.
    fun forVoice(voice: BackwashVoice): List<BackwashPatch> = (bank + processing).filter { it.voice == voice }
        .sortedBy { if (it.macros == Backwash.defaults(voice)) 0 else 1 }
}
