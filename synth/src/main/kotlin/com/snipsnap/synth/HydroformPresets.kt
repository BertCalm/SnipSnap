package com.snipsnap.synth

/**
 * HYDROFORM's twelve R7 candidate presets, six a voice, moved unchanged from the candidate kit generator
 * so the factory roster and the starter kit can serve them. They reach [Presets] only once
 * [HeldPromotions] ships HYDROFORM, after the owner's picks; until then they serve the candidate
 * generator and the held starter. DIE CUT and CUT RIM render with ARREST's damped top (owner KEEP,
 * round fix-hydroform-1, 2026-11-20).
 */
object HydroformPresets {
    private fun patch(name: String, voice: HydroformVoice, tune: Float, charge: Float,
                      standoff: Float, yield: Float, arrest: Float, hold: Boolean = false) =
        HydroformPatch(name, voice, Hydroform.defaults(voice) + mapOf(
            "TUNE" to tune, "CHARGE" to charge, "STANDOFF" to standoff,
            "YIELD" to yield, "ARREST" to arrest) +
            if (voice == HydroformVoice.DOME) mapOf("HOLD" to if (hold) 1f else 0f) else emptyMap())

    private val all: List<HydroformPatch> = listOf(
        patch("LOW PRESSURE", HydroformVoice.DOME, .5f, .45f, .10f, .80f, .30f),
        patch("PRESSURE VESSEL", HydroformVoice.DOME, .5f, .90f, .35f, .35f, .45f),
        patch("DIE CUT", HydroformVoice.DOME, .5f, .75f, .25f, .40f, 1f),
        patch("RETURN FRONT", HydroformVoice.DOME, .5f, .70f, .85f, .60f, .55f),
        patch("YIELD BREAK", HydroformVoice.DOME, .5f, 1f, .60f, .10f, .70f),
        patch("DIE CYCLE", HydroformVoice.DOME, .5f, .70f, .35f, .50f, .65f, true),
        patch("PLATE TAP", HydroformVoice.BULKHEAD, 5f / 24, .35f, .15f, .85f, .80f),
        patch("BENT PANEL", HydroformVoice.BULKHEAD, 0f, .60f, .45f, .55f, .40f),
        patch("BULK STRIKE", HydroformVoice.BULKHEAD, 3f / 24, .75f, .35f, .50f, .65f),
        patch("CUT RIM", HydroformVoice.BULKHEAD, 8f / 24, .70f, .70f, .65f, 1f),
        patch("TEARING FACE", HydroformVoice.BULKHEAD, 10f / 24, 1f, .65f, .10f, .75f),
        patch("OPEN PANEL", HydroformVoice.BULKHEAD, .5f, .60f, .85f, .70f, .10f),
    )

    fun all(): List<HydroformPatch> = all
    fun forVoice(voice: HydroformVoice): List<HydroformPatch> = all.filter { it.voice == voice }
}
