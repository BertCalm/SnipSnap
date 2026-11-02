package com.snipsnap.synth

/**
 * Provisional playing recipes for the owner's next listen, not a factory roster.
 * Every pad stores all five controls; ARC/CRATER synthesis stays unchanged.
 */
object ErosionBank {
    val velocities = listOf(.35f, .65f, 1f)
    data class Entry(val name: String, val role: String, val patch: ErosionPatch)
    fun entries(): List<Entry> {
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
