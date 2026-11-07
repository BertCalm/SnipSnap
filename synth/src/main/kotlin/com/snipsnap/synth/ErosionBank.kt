package com.snipsnap.synth

/**
 * Provisional playing recipes for the owner's next listen, not a factory roster.
 * The original sixteen recipes remain available; the contrast bank uses six controls.
 */
object ErosionBank {
    val velocities = listOf(.35f, .65f, 1f)
    data class Entry(val name: String, val role: String, val patch: ErosionPatch)
    /** Eight roles for the contrast audition; previous recipes remain below. */
    fun entries(): List<Entry> = listOf(
        contrast("THUD", "short low body", ErosionVoice.CRATER, 0f,.18f,0f,0f,1f,1f),
        contrast("CHIP", "coarse bright strike", ErosionVoice.ARC, 1f,0f,0f,1f,0f,.85f),
        contrast("RASP", "rough mid-band packets", ErosionVoice.ARC, .30f,.25f,.30f,.15f,0f,.65f),
        contrast("WIRE", "fine closed wire", ErosionVoice.ARC, 1f,.90f,.08f,1f,0f,0f),
        contrast("DUST", "long continuous dust", ErosionVoice.ARC, .55f,1f,1f,.65f,0f,0f),
        contrast("DROPS", "spaced tuned collapses", ErosionVoice.CRATER, .85f,0f,1f,.20f,.95f,1f),
        contrast("SPLINTER", "loose bright crackle", ErosionVoice.ARC, .70f,.70f,.55f,1f,.05f,.95f),
        contrast("SCAR", "crack then separated aftershock", ErosionVoice.SCAR, .35f,.40f,.65f,.55f,.45f,.35f),
    )
    private fun contrast(name: String, role: String, voice: ErosionVoice, tune: Float,
                         voltage: Float, feed: Float, fluid: Float, slag: Float, grain: Float) =
        Entry(name, role, ErosionPatch(name, voice, mapOf("TUNE" to tune, "VOLTAGE" to voltage,
            "FEED" to feed, "FLUID" to fluid, "SLAG" to slag, "GRAIN" to grain)))

    fun legacyEntries(): List<Entry> {
        fun arc(name: String, role: String, tune: Float, voltage: Float, feed: Float, fluid: Float, slag: Float) =
            entry(name, role, ErosionVoice.ARC, tune, voltage, feed, fluid, slag)
        fun crater(name: String, role: String, tune: Float, voltage: Float, feed: Float, fluid: Float, slag: Float) =
            entry(name, role, ErosionVoice.CRATER, tune, voltage, feed, fluid, slag)
        return listOf(
            arc("PIN", "short tick", .65f,.28f,.03f,.78f,.02f),
            arc("SHAVING", "fine closed texture", .70f,.70f,.10f,.90f,.01f),
            arc("TACK", "dark short tick", .35f,.42f,.06f,.30f,.08f),
            arc("WIRE", "wire accent", .70f,.52f,.28f,.68f,.12f),
            arc("FILINGS", "loose grain", .58f,.18f,.40f,.65f,.05f),
            arc("SHEEN", "open bright dust", .72f,.82f,.72f,.84f,.03f),
            arc("ASH", "open dark dust", .40f,.65f,.78f,.27f,.08f),
            arc("ARC START", "native reference", .50f,.65f,.35f,.60f,.08f),
            crater("PIT", "tight low crater", .10f,.18f,.08f,.32f,.84f),
            crater("DENT", "mid body accent", .44f,.35f,.20f,.50f,.62f),
            crater("PEEN", "high body accent", .83f,.25f,.15f,.63f,.54f),
            crater("RIVET", "metal and body", .55f,.53f,.30f,.78f,.36f),
            crater("SPALL", "broken wire accent", .35f,.10f,.50f,.68f,.32f),
            crater("CINDER", "dark loose body", .18f,.52f,.62f,.22f,.70f),
            crater("BASIN", "long low crater", .05f,.40f,.75f,.35f,.90f),
            crater("CRATER START", "native reference", .50f,.30f,.50f,.60f,.65f),
        )
    }
    private fun entry(name: String, role: String, voice: ErosionVoice, tune: Float,
                      voltage: Float, feed: Float, fluid: Float, slag: Float) = Entry(name, role,
        ErosionPatch(name, voice, mapOf("TUNE" to tune, "VOLTAGE" to voltage, "FEED" to feed,
            "FLUID" to fluid, "SLAG" to slag)))
}
