package com.snipsnap.synth

/** Provisional audition starting points; deliberately absent from the factory Presets roster. */
object OverlapPresets {
    private val plain = mapOf("KNOT" to 1f, "PULSE" to 0f, "LOCK" to 0f,
        "GLOW" to 0f, "RING" to .65f, "HOLD" to 0f)

    fun all(): List<OverlapPatch> = listOf(
        OverlapPatch("Self", OverlapTopology.SELF, plain, 48, .8f),
        OverlapPatch("Trefoil", OverlapTopology.TREFOIL, plain, 48, .8f),
        OverlapPatch("Mirror", OverlapTopology.MIRROR, plain, 48, .8f),
        OverlapPatch("Torus", OverlapTopology.TORUS, plain, 48, .8f),
        OverlapPatch("Glass thread", OverlapTopology.TREFOIL,
            plain + mapOf("RING" to .25f, "GLOW" to .2f), 60, .8f),
        OverlapPatch("Breathing circuit", OverlapTopology.TORUS,
            plain + mapOf("PULSE" to .55f, "RING" to .9f, "HOLD" to .8f, "LOCK" to .75f, "GLOW" to .4f), 48, .8f),
    )
}
