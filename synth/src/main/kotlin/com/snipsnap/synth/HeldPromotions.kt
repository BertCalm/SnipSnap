package com.snipsnap.synth

/**
 * Engines whose factory roster, SYNTH picker entry and starter kit are written but held back until the
 * owner keeps them in a listening round. Everything that would ship one of these reads [shipped], so the
 * prepared code stays in the tree, tested, and the app behaves exactly as before.
 *
 * Shipping an engine is removing it from [HELD], then updating what counts the shelf and the picker
 * (StarterKitsTest's stable id list, the README starter counts, PresetsTest's roster sum). Ship them in
 * the order listed here, or move a shipped starter above the held ones in `StarterKits`: the stored
 * starter order may only grow at its end.
 *
 * FILL: its eight presets and the stronger HUMANIZE wait on the owner's round promote-fhl-1.
 * HYDROFORM: its twelve R7 candidate presets ([HydroformPresets]) wait on the owner's picks.
 * LORENTZ: its presets are on the roster already (PR 808); the picker and starter wait on the owner's
 * first listen to the Kotlin render.
 */
object HeldPromotions {
    val HELD: Set<String> = setOf(FillPatch.ENGINE, HydroformPatch.ENGINE, LorentzPatch.ENGINE)

    fun shipped(engine: String): Boolean = engine !in HELD
}
