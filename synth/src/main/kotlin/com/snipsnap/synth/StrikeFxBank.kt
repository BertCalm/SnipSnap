package com.snipsnap.synth

import com.snipsnap.audio.Snip

/**
 * The STRIKE FX knobs as a three-layer bank: DRY (the source), ROOM (a spring
 * reverb sized by the room knob) and ECHO (repeats spaced by the slap knob).
 * Each effect receives the same dry source, so the room never smears the echo's gaps.
 * The knob values live in the chains themselves and are read back from them.
 */
object StrikeFxBank {
    const val DEFAULT_ROOM = 0.42f
    const val DEFAULT_SLAP = 0.9f
    const val TERMINAL_FADE_MS = 4f

    val NAMES = listOf("DRY", "ROOM", "ECHO")

    fun layers(room: Float, slap: Float): List<LayerRecipe> {
        require(room in 0f..1f) { "room must be in 0..1, was $room" }
        require(slap in 0f..1f) { "slap must be in 0..1, was $slap" }
        return listOf(
            LayerRecipe(NAMES[0]),
            LayerRecipe(
                NAMES[1],
                FxChain(spring = mapOf("SIZE" to room, "TONE" to 0.7f, "MIX" to 1f)),
                TERMINAL_FADE_MS,
            ),
            LayerRecipe(
                NAMES[2],
                FxChain(echo = mapOf("TIME" to slap, "REPEAT" to 0.55f, "TONE" to 0.8f, "MIX" to 0.9f)),
                TERMINAL_FADE_MS,
            ),
        )
    }

    fun isStrikeFx(recipe: PadRecipe?): Boolean {
        val l = recipe?.layers ?: return false
        return l.size == NAMES.size && l.map { it.name } == NAMES
    }

    fun roomOf(recipe: PadRecipe): Float? =
        if (isStrikeFx(recipe)) recipe.layers!![1].fx?.spring?.get("SIZE") else null

    fun slapOf(recipe: PadRecipe): Float? =
        if (isStrikeFx(recipe)) recipe.layers!![2].fx?.echo?.get("TIME") else null

    fun render(source: Snip, room: Float, slap: Float): List<Snip> =
        PadRecipe(layers = layers(room, slap)).processBank(source)
}
