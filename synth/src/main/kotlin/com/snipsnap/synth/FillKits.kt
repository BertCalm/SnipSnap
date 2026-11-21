package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/**
 * FILL's starter: the eight kept fills as they are (HUMANIZE off) on A01-A08, then the same eight with
 * the stronger HUMANIZE at full on A09-A16, so a tight and a loose take of every fill sit a row apart.
 * Every pad has three native velocity zones; the MPC velocity curve owns their level, as RIME's do.
 * Held by [HeldPromotions] until the owner keeps it.
 */
object FillKits {
    const val ID = "fill"
    const val SOFT_VELOCITY = .3f
    const val MID_VELOCITY = .6f
    /** The second row's HUMANIZE: the R1.2 strength at full. */
    const val LOOSE_HUMANIZE = 1f

    fun patches(): List<FillPatch> = FillPresets.all() + FillPresets.all().map {
        it.copy(name = "${it.name} LOOSE", macros = it.macros + ("HUMANIZE" to LOOSE_HUMANIZE))
    }

    fun build(): List<ArrangedPad?> = patches().map { patch ->
        val recipe = PadRecipe(patch)
        val main = recipe.render()
        ArrangedPad(main, Fill.drumClassFor(patch.voice, patch.macros), recipe = recipe.toJsonValue(), oneShot = true,
            softVariants = listOf(SOFT_VELOCITY, MID_VELOCITY).map { v ->
                Velocity.peakMatch(main, Fill.render(patch.voice, patch.macros, velocity = v))
            }, displayName = patch.name,
            source = linkedMapOf("engine" to FillPatch.ENGINE, "model" to Fill.MODEL_VERSION.toString(),
                "velocitySoft" to SOFT_VELOCITY.toString(), "velocityMid" to MID_VELOCITY.toString(),
                "velocityMain" to "1.0", "velocityNormalization" to "peak-match-main"))
    }
}
