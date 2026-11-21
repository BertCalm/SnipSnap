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
 * FILL, HYDROFORM and LORENTZ were written behind this gate and shipped on 21 Nov 2026, when the owner
 * kept them (rounds promote-fill-1, promote-hydroform-1, promote-lorentz-1); none is held now.
 */
object HeldPromotions {
    val HELD: Set<String> = emptySet()

    fun shipped(engine: String): Boolean = engine !in HELD
}
