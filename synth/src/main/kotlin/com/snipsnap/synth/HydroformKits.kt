package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.ArrangedPad

/**
 * HYDROFORM's starter: the R7 candidate kit's sixteen pads, unchanged in layout. A01-A08 walk LOW PRESSURE
 * up G minor pentatonic from G0 to C2, A09-A14 are the six BULKHEAD presets, A15 YIELD BREAK and A16
 * DIE CYCLE (the held two-second phrase, played once). Three native velocity zones a pad, peak-matched
 * to the hard strike; the MPC velocity curve owns their level. Held by [HeldPromotions] until the owner
 * picks the presets; a dropped preset leaves its pad here to be replaced.
 */
object HydroformKits {
    const val ID = "hydroform"
    val KEY: KeySpec = KeySpec.parse("Gminpent")
    const val TEMPO_BPM = 120f
    val VELOCITIES = listOf(.3f, .6f, 1f)
    private val WALK = listOf(0 to "G0", 3 to "BB0", 5 to "C1", 7 to "D1", 10 to "F1", 12 to "G1", 15 to "BB1", 17 to "C2")

    fun patches(): List<HydroformPatch> {
        val presets = HydroformPresets.all()
        val low = presets.single { it.name == "LOW PRESSURE" }
        return WALK.map { (n, note) -> low.copy(name = "PRESSURE $note", macros = low.macros + ("TUNE" to n / 24f)) } +
            HydroformPresets.forVoice(HydroformVoice.BULKHEAD) +
            listOf(presets.single { it.name == "YIELD BREAK" }, presets.single { it.name == "DIE CYCLE" })
    }

    fun build(): List<ArrangedPad?> = patches().map { p ->
        val recipe = PadRecipe(p)
        val main = recipe.render()
        ArrangedPad(main, Hydroform.drumClassFor(p.voice, p.macros), recipe = recipe.toJsonValue(), oneShot = true,
            softVariants = VELOCITIES.take(2).map { v -> Velocity.peakMatch(main, p.copy(velocity = v).render()) },
            displayName = p.name, layerNames = listOf("SOFT", "MIDDLE", "HARD"),
            source = linkedMapOf("engine" to HydroformPatch.ENGINE, "model" to Hydroform.MODEL_VERSION.toString(),
                "velocitySoft" to ".3", "velocityMid" to ".6", "velocityMain" to "1.0",
                "velocityNormalization" to "peak-match-main"))
    }
}
