package com.snipsnap.synth

import java.util.Collections

/**
 * Eight dry model-2 recipes following the owner's positive engine auditions.
 * FIRST ARC preserves the starting swing's complete sound; the other seven
 * are new listening candidates, not an already approved preset selection.
 *
 * Native note, velocity and noise context stay fixed so the bank's differences
 * come from contact, cadence, contrast and retained state rather than a louder
 * performance or a changed register. The last two recipes use fixed-alignment
 * sustain. LATITUDE selects their held plane rather than ongoing traversal.
 * Material words describe designed responses, not measured physical objects.
 */
object MeridianPresets {
    const val MODEL = 2
    const val MIDI = 57
    const val VELOCITY = 1f
    const val SEED_CONTEXT = 4810

    /** Same pasteable shape as UserPresets.rosterLine, with no implicit macro defaults. */
    private fun p(voice: MeridianVoice, name: String, vararg macros: Pair<String, Float>): MeridianPatch =
        MeridianPatch(name, voice, Collections.unmodifiableMap(linkedMapOf(*macros)),
            midi = MIDI, velocity = VELOCITY, model = MODEL, seedContext = SEED_CONTEXT)

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
    )

    fun all(): List<MeridianPatch> = roster

    fun forVoice(voice: MeridianVoice): List<MeridianPatch> = when (voice) {
        MeridianVoice.SWEEP -> roster
    }

    /** Intended musical role; the new recipes still await the owner's listening verdict. */
    fun roleDescription(name: String): String? = roles[name]
}
