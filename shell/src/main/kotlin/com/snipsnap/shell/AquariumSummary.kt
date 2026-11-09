package com.snipsnap.shell

import com.snipsnap.synth.AquariumControls
import com.snipsnap.synth.AquariumEditor
import com.snipsnap.synth.AquariumRenderRecipe

/**
 * The closed line of the pad editor's AQUARIUM section (clarity spec 7.3: a
 * section's closed line says what is inside or its current value, never
 * "UNTOUCHED"). One line of the pixel face, so it fits [PadSheetBoxes.SUMMARY_CHARS].
 */
object AquariumSummary {
    /** What the line reads when the pad carries no Aquarium print: what the section holds. */
    const val HOLDS = "SIX EFFECTS · PRESETS · 52 CONTROLS"

    /** The pad carries an Aquarium marker its recipe cannot be read from. */
    const val UNREADABLE = "KEPT RECIPE UNREADABLE"

    /** A kept print whose settings match no preset. */
    const val CUSTOM = "CUSTOM SETTINGS"

    /** "KEPT · HALL", "KEPT · CUSTOM SETTINGS", "BYPASSED", [UNREADABLE], or [HOLDS]. */
    fun line(applied: AquariumRenderRecipe?, marked: Boolean): String = when {
        applied == null -> if (marked) UNREADABLE else HOLDS
        applied.isBypass -> "BYPASSED"
        else -> "KEPT · " + (AquariumEditor(applied).presetId?.let { AquariumControls.preset(it).name } ?: CUSTOM)
    }
}
