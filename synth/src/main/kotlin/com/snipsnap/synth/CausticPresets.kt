package com.snipsnap.synth

/**
 * The eight original Alloy recipes, plus one starting patch for each approved
 * character direction. The three new directions were heard as listening
 * prototypes; their Kotlin ports and control variations still await listening.
 * Six Alloy variations likewise remain new positions rather than heard anchors.
 */
object CausticPresets {
    private data class Entry(val patch: CausticPatch, val description: String, val heardAnchor: Boolean = false)

    /** Shape used by UserPresets' generated roster expressions. */
    private fun p(voice: CausticVoice, name: String, vararg macros: Pair<String, Float>) =
        CausticPatch(name, voice, macros.toMap())

    private fun entry(
        name: String, description: String,
        source: Float = .5f, focus: Float = .19f, body: Float = 1f, length: Float = 1f,
        midi: Int = 60, velocity: Float = .75f, heardAnchor: Boolean = false,
        voice: CausticVoice = CausticVoice.ALLOY,
    ) = Entry(CausticPatch(name, voice, linkedMapOf(
        "SOURCE" to source, "FOCUS" to focus, "BODY" to body, "LENGTH" to length, "HOLD" to 0f,
    ), midi, velocity, model = 1), description, heardAnchor)

    private val roster = listOf(
        entry("Fuller Body", "The heard fuller Alloy timbre, with the native finite-note delivery.", heardAnchor = true),
        entry("Preferred Alloy", "The earlier preferred bright Alloy timbre, before the fuller tonal drive.", body = 0f, heardAnchor = true),
        // Re-voiced on the owner's 20 Nov 2026 verdict "First 3 sound the same": all three sat on the
        // full body within 2.1 dB of each other (third-octave shape). Each now has its own body.
        entry("Parallel Body", "New position: parallel illumination and a focused pickup on the bright, lighter body, kept short.", source = 0f, focus = .12f, body = 0f, length = .4f),
        entry("Rim Vessel", "New position: rim-source illumination on a body halfway between bright and full.", source = 1f, body = .5f, length = .75f),
        entry("Wide Shoulder", "New position: well off the concentration, a broader and quieter fairly full body.", focus = .6f, body = .75f, length = .75f),
        entry("Sparse Alloy", "New native position: a sparse pickup exposes the quiet pitched support.", focus = .85f),
        // The same verdict: "second to last is abrupt". At LENGTH 0 it was 60 dB down in a third of
        // a second; it now takes more than twice as long and is still the shortest preset (1.25 s,
        // so it stays a TONAL pad in the kit).
        entry("Short Glint", "New position: a short, lighter metal accent at C5 that rings out.", body = .3f, length = .1f, midi = 72, velocity = .7f),
        entry("Low Vessel", "New position: a lower, moderately full body at C2.", body = .75f, length = .55f, midi = 36, velocity = .8f),
        entry("Hollow Mallet", "The approved hollow-knock direction. This native port awaits listening.",
            body = .5f, heardAnchor = true, voice = CausticVoice.HOLLOW_MALLET),
        entry("Rubbed Ring", "The approved pressure-swell direction. This native port awaits listening.",
            body = .5f, heardAnchor = true, voice = CausticVoice.RUBBED_RING),
        entry("Travelling Catches", "The approved moving-contact direction. This native port awaits listening.",
            body = 0f, heardAnchor = true, voice = CausticVoice.TRAVELLING_CATCHES),
    )

    fun all(): List<CausticPatch> = roster.map { it.patch }
    fun forVoice(voice: CausticVoice): List<CausticPatch> = all().filter { it.voice == voice }
    fun descriptionFor(name: String): String? = roster.firstOrNull { it.patch.name == name }?.description
    fun isHeardAnchor(name: String): Boolean = roster.firstOrNull { it.patch.name == name }?.heardAnchor == true
}
