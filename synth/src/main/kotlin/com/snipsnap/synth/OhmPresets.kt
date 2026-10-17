package com.snipsnap.synth

/** Provisional listening recipes; kept outside the factory picker until auditioned. */
object OhmPresets {
    private val base get() = Ohm.defaults() + mapOf("TOUCH" to .55f, "BODY" to .55f, "LENGTH" to .55f)

    fun all(): List<OhmPatch> = listOf(
        OhmPatch("Solo", base + mapOf("MEDDLING" to 0f, "COMMUNE" to 0f)),
        OhmPatch("Argument", base + mapOf("MEDDLING" to .9f, "COMMUNE" to 0f)),
        OhmPatch("Communion", base + mapOf("MEDDLING" to .9f, "COMMUNE" to 1f)),
        OhmPatch("Quiet Company", base + mapOf("MEDDLING" to .25f, "COMMUNE" to 1f)),
        OhmPatch("Gather", base + mapOf("MEDDLING" to .9f, "COMMUNE" to 1f), gesture = OhmGesture.GATHER),
    )
}
