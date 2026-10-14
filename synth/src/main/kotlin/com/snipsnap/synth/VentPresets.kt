package com.snipsnap.synth

/**
 * Three provisional VENT starting points for the CLI audition. These recipes
 * are deliberately outside [Presets] until the owner's listening verdict.
 */
object VentPresets {
    private val presets = listOf(
        VentPatch("Round Orbit Prototype", VentVoice.ROUND, Vent.defaultsFor(VentVoice.ROUND)),
        VentPatch("Wire Orbit Prototype", VentVoice.WIRE, Vent.defaultsFor(VentVoice.WIRE)),
        VentPatch("Torn Orbit Prototype", VentVoice.TEAR, Vent.defaultsFor(VentVoice.TEAR)),
    )

    fun forVoice(voice: VentVoice): List<VentPatch> = presets.filter { it.voice == voice }

    fun all(): List<VentPatch> = presets
}
