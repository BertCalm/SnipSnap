package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import com.snipsnap.synth.FiredBronzePatch
import com.snipsnap.synth.PadRecipe

/** Narrow ownership check: only a native FIRED BRONZE bank can be regenerated. Two shapes are native:
 * - **model 1**, the kit approved on MPC: four strike layers, each its own file;
 * - **model 2**, its round-robin grid: one chain of the same four layers, each [com.snipsnap.synth.FiredBronzeInstrument.TAKES]
 *   fresh strikes (`Robin.firedBronzeGrid`).
 * Imported layers, other chains and later printed treatments retain the usual rewrite guard. */
object FiredBronzePads {
    /** The four strike layers' velocity windows, softest first: the approved kit's own. */
    val WINDOWS = listOf(1 to 31, 32 to 63, 64 to 95, 96 to 127)

    /** The same four layers as a chain's zones, which tile every velocity from 0 (a chain must). */
    val GRID_WINDOWS = listOf(0 to 31) + WINDOWS.drop(1)

    fun canEdit(pad: KitPad?): Boolean {
        if (pad == null) return false
        val recipe = pad.recipe?.let { runCatching { PadRecipe.fromJsonValue(it) }.getOrNull() } ?: return false
        val patch = recipe.patch as? FiredBronzePatch ?: return false
        if (com.snipsnap.synth.Velocity.patchForLayers(recipe) == null) return false
        val chain = pad.chain
        if (chain != null) {
            val takes = com.snipsnap.synth.FiredBronzeInstrument.TAKES
            val zones = chain.zones ?: return false
            return patch.model >= 2 && pad.robin == null && pad.velocityLayers.isEmpty() &&
                zones.map { it.velStart to it.velEnd } == GRID_WINDOWS && zones.all { it.cycle == takes } &&
                zones.map { it.baseSlice } == GRID_WINDOWS.indices.map { it * takes } &&
                chain.boundaries.size == GRID_WINDOWS.size * takes
        }
        val layers = pad.velocityLayers
        return layers.size == 4 && layers.map { it.velStart to it.velEnd } == WINDOWS &&
            layers.map { it.displayName } == listOf("LIGHT", "MEDIUM", "FIRM", "FULL") &&
            layers.last().sampleFile == pad.sampleFile && layers.map { it.sampleFile }.distinct().size == 4
    }
}
