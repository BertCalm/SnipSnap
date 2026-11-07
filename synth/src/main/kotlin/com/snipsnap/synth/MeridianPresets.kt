package com.snipsnap.synth

import java.util.Collections

/**
 * Eight preserved model-2 SWEEP recipes and a finite/held pair for each of
 * the three model-3 material voices. FIRST ARC preserves the starting swing's
 * complete sound. The new finite recipes retain the material study's control
 * settings; their held partners repeat that contact at a stationary plane.
 *
 * Native note, velocity and noise context stay fixed so the bank's differences
 * come from contact, cadence, contrast and retained state rather than a louder
 * performance or a changed register. Each voice's held recipes use fixed-alignment
 * sustain. LATITUDE selects their held plane rather than ongoing traversal.
 * Material words describe designed responses, not measured physical objects.
 */
object MeridianPresets {
    /** The original SWEEP bank's saved renderer, independent of later voices. */
    const val MODEL = 2
    const val MIDI = 57
    const val VELOCITY = 1f
    const val SEED_CONTEXT = 4810

    /** Same pasteable shape as UserPresets.rosterLine, with no implicit macro defaults. */
    private fun p(voice: MeridianVoice, name: String, vararg macros: Pair<String, Float>): MeridianPatch =
        MeridianPatch(name, voice, Collections.unmodifiableMap(linkedMapOf(*macros)),
            midi = MIDI, velocity = VELOCITY, model = Meridian.defaultModelFor(voice), seedContext = SEED_CONTEXT)

    private val roster: List<MeridianPatch> = Collections.unmodifiableList(listOf(
        p(MeridianVoice.SWEEP, "FIRST ARC",
            "TUNE" to .50f, "LATITUDE" to .80f, "LENGTH" to .45f, "REACH" to .60f,
            "SURFACE" to .55f, "MEMORY" to .40f, "HOLD" to 0f),
        p(MeridianVoice.SWEEP, "BACK ARC",
            "TUNE" to .50f, "LATITUDE" to .20f, "LENGTH" to .45f, "REACH" to .60f,
            "SURFACE" to .55f, "MEMORY" to .40f, "HOLD" to 0f),
        p(MeridianVoice.SWEEP, "CLOSE GRAIN",
            "TUNE" to .50f, "LATITUDE" to .70f, "LENGTH" to .32f, "REACH" to .42f,
            "SURFACE" to .16f, "MEMORY" to .18f, "HOLD" to 0f),
        p(MeridianVoice.SWEEP, "DEEP KNOCK",
            "TUNE" to .50f, "LATITUDE" to .90f, "LENGTH" to .18f, "REACH" to .92f,
            "SURFACE" to .72f, "MEMORY" to .28f, "HOLD" to 0f),
        p(MeridianVoice.SWEEP, "STILL PAIR",
            "TUNE" to .50f, "LATITUDE" to .50f, "LENGTH" to .50f, "REACH" to .55f,
            "SURFACE" to .60f, "MEMORY" to .45f, "HOLD" to 0f),
        p(MeridianVoice.SWEEP, "LONG TRAIL",
            "TUNE" to .50f, "LATITUDE" to .72f, "LENGTH" to .78f, "REACH" to .70f,
            "SURFACE" to .62f, "MEMORY" to .84f, "HOLD" to .65f),
        p(MeridianVoice.SWEEP, "ROUND THREAD",
            "TUNE" to .50f, "LATITUDE" to .50f, "LENGTH" to .55f, "REACH" to .50f,
            "SURFACE" to .18f, "MEMORY" to .60f, "HOLD" to 1f),
        p(MeridianVoice.SWEEP, "FINE STRAND",
            "TUNE" to .50f, "LATITUDE" to .20f, "LENGTH" to .78f, "REACH" to .76f,
            "SURFACE" to .90f, "MEMORY" to .78f, "HOLD" to 1f),
        p(MeridianVoice.CLACK, "HARD GRAIN",
            "TUNE" to .50f, "LATITUDE" to .80f, "LENGTH" to .45f, "REACH" to .60f,
            "SURFACE" to 1f, "MEMORY" to .40f, "HOLD" to 0f),
        p(MeridianVoice.CLACK, "GRAIN THREAD",
            "TUNE" to .50f, "LATITUDE" to .50f, "LENGTH" to .45f, "REACH" to .60f,
            "SURFACE" to 1f, "MEMORY" to .40f, "HOLD" to 1f),
        p(MeridianVoice.CHIME, "HOLLOW TONE",
            "TUNE" to .50f, "LATITUDE" to .80f, "LENGTH" to .45f, "REACH" to .60f,
            "SURFACE" to 1f, "MEMORY" to .40f, "HOLD" to 0f),
        p(MeridianVoice.CHIME, "LANTERN TONE",
            "TUNE" to .50f, "LATITUDE" to .50f, "LENGTH" to .45f, "REACH" to .60f,
            "SURFACE" to 1f, "MEMORY" to .40f, "HOLD" to 1f),
        p(MeridianVoice.RASP, "BRIGHT EDGE",
            "TUNE" to .50f, "LATITUDE" to .80f, "LENGTH" to .45f, "REACH" to .60f,
            "SURFACE" to 1f, "MEMORY" to .40f, "HOLD" to 0f),
        p(MeridianVoice.RASP, "EDGE THREAD",
            "TUNE" to .50f, "LATITUDE" to .50f, "LENGTH" to .45f, "REACH" to .60f,
            "SURFACE" to 1f, "MEMORY" to .40f, "HOLD" to 1f),
    ))

    private val roles = mapOf(
        "FIRST ARC" to "The accepted starting swing: changing contacts with moderate contrast and memory.",
        "BACK ARC" to "The same cadence, reach and memory traversing the material ring in the opposite direction.",
        "CLOSE GRAIN" to "Shallower contact, restrained material contrast and shorter surface ringing.",
        "DEEP KNOCK" to "Quick swings with deep engagement and a stronger contrast between contact responses.",
        "STILL PAIR" to "Repeated contact at a stationary alignment, with no precession.",
        "LONG TRAIL" to "A slower, longer finite gesture whose earlier surface responses persist.",
        "ROUND THREAD" to "Fixed-alignment sustain with restrained material contrast and moderate retained ringing.",
        "FINE STRAND" to "Slower fixed-alignment sustain on the wire/glass side, with stronger contrast and memory.",
        "HARD GRAIN" to "A finite journey across short bar-like resonances with a pronounced upper-mode attack.",
        "GRAIN THREAD" to "Repeated bar-like contacts at one fixed alignment, retaining the dry material response.",
        "HOLLOW TONE" to "A finite journey through dispersed metallic upper modes around the requested root.",
        "LANTERN TONE" to "Fixed-alignment contact sustaining a hollow, dispersed metallic response.",
        "BRIGHT EDGE" to "A finite journey through bright contact roughness and widely spaced wire-like modes.",
        "EDGE THREAD" to "Fixed-alignment contact sustaining bright roughness and a wire-like response.",
    )

    fun all(): List<MeridianPatch> = roster

    fun forVoice(voice: MeridianVoice): List<MeridianPatch> = roster.filter { it.voice == voice }

    /** Intended musical role; the new recipes still await the owner's listening verdict. */
    fun roleDescription(name: String): String? = roles[name]
}
