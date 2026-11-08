package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import com.snipsnap.synth.FiredBronzePatch
import com.snipsnap.synth.PadRecipe

/** Narrow ownership check: only the native four-strike bank can be regenerated.
 * Imported layers and later printed treatments retain the usual rewrite guard. */
object FiredBronzePads {
    fun canEdit(pad: KitPad?): Boolean {
        if (pad == null || pad.chain != null) return false
        val recipe = pad.recipe?.let { runCatching { PadRecipe.fromJsonValue(it) }.getOrNull() } ?: return false
        if (recipe.patch !is FiredBronzePatch || com.snipsnap.synth.Velocity.patchForLayers(recipe) == null) return false
        val layers = pad.velocityLayers
        return layers.size == 4 && layers.map { it.velStart to it.velEnd } ==
            listOf(1 to 31, 32 to 63, 64 to 95, 96 to 127) &&
            layers.map { it.displayName } == listOf("LIGHT", "MEDIUM", "FIRM", "FULL") &&
            layers.last().sampleFile == pad.sampleFile && layers.map { it.sampleFile }.distinct().size == 4
    }
}
