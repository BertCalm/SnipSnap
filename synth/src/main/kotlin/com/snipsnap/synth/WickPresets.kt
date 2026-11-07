package com.snipsnap.synth

/**
 * Thirty-six WICK factory recipes: six per voice, including the three accepted
 * RATTLE bodies. The published twenty-seven remain intact. [additions] identifies
 * the nine later recipes separately for the listening page; they are authored
 * settings over accepted voices, not a claim of a further listening verdict.
 * The dry engine supplies the identity; none needs a landing FX chain.
 */
object WickPresets {
    private fun p(voice: WickVoice, name: String, vararg macros: Pair<String, Float>) =
        WickPatch(name, voice, Wick.defaults(voice) + macros)

    private val publishedPresets = listOf(
        p(WickVoice.PLUCK, "Small Spark"),
        p(WickVoice.PLUCK, "Soft Wick", "SPARK" to .20f, "FEED" to 0f,
            "CASCADE" to .12f, "TIMBRE" to .18f, "RECOVERY" to .40f),
        p(WickVoice.PLUCK, "Dry Chain", "SPARK" to .60f, "FEED" to .10f,
            "CASCADE" to .60f, "TIMBRE" to .42f, "RECOVERY" to .30f),
        p(WickVoice.PLUCK, "Held Glow", "SPARK" to .40f, "FEED" to .35f,
            "CASCADE" to .40f, "TIMBRE" to .30f, "RECOVERY" to .45f, "HOLD" to 1f),
        p(WickVoice.WOOD, "Dry Wood"),
        p(WickVoice.WOOD, "Soft Body", "SPARK" to .30f, "FEED" to .05f,
            "CASCADE" to .20f, "TIMBRE" to .20f, "RECOVERY" to .55f),
        p(WickVoice.WOOD, "Small Rebounds", "SPARK" to .65f, "FEED" to .20f,
            "CASCADE" to .65f, "TIMBRE" to .50f, "RECOVERY" to .30f),
        p(WickVoice.WOOD, "Held Wood", "SPARK" to .50f, "FEED" to .40f,
            "CASCADE" to .55f, "TIMBRE" to .35f, "RECOVERY" to .50f, "HOLD" to 1f),
        p(WickVoice.BUBBLE, "Bubble Chain"),
        p(WickVoice.BUBBLE, "Slow Feed", "SPARK" to .30f, "FEED" to .35f,
            "CASCADE" to .65f, "TIMBRE" to .30f, "RECOVERY" to .65f),
        p(WickVoice.BUBBLE, "Spent Bubbles", "SPARK" to .60f, "FEED" to .85f,
            "CASCADE" to .85f, "TIMBRE" to .60f, "RECOVERY" to .80f),
        p(WickVoice.BUBBLE, "Held Bubbles", "SPARK" to .45f, "FEED" to .65f,
            "CASCADE" to .75f, "TIMBRE" to .45f, "RECOVERY" to .60f, "HOLD" to 1f),
        p(WickVoice.BUBBLE, "Busy Bubbles", "FEED" to .65f),
        p(WickVoice.BREATH, "Open Air"),
        p(WickVoice.BREATH, "Single Breath", "SPARK" to .25f, "FEED" to 0f,
            "CASCADE" to .15f, "TIMBRE" to .12f, "RECOVERY" to .80f),
        p(WickVoice.BREATH, "Layered Air", "SPARK" to .30f, "FEED" to .80f,
            "CASCADE" to .60f, "TIMBRE" to .35f, "RECOVERY" to .55f),
        p(WickVoice.BREATH, "Held Breath", "SPARK" to .25f, "FEED" to .65f,
            "CASCADE" to .40f, "TIMBRE" to .25f, "RECOVERY" to .70f, "HOLD" to 1f),
        p(WickVoice.FLICKER, "Metal Flash"),
        p(WickVoice.FLICKER, "Single Glint", "SPARK" to .40f, "FEED" to 0f,
            "CASCADE" to 0f, "TIMBRE" to .65f, "RECOVERY" to .45f),
        p(WickVoice.FLICKER, "Bright Chain", "SPARK" to .65f, "FEED" to .55f,
            "CASCADE" to .80f, "TIMBRE" to .95f, "RECOVERY" to .40f),
        p(WickVoice.FLICKER, "Held Flicker", "SPARK" to .50f, "FEED" to .55f,
            "CASCADE" to .70f, "TIMBRE" to .75f, "RECOVERY" to .45f, "HOLD" to 1f),
        p(WickVoice.RATTLE, "Warm Wire"),
        p(WickVoice.RATTLE, "Hollow Reed").copy(rattleSource = Wick.RattleSource.HOLLOW_REED),
        p(WickVoice.RATTLE, "Rubbed Glass").copy(rattleSource = Wick.RattleSource.RUBBED_GLASS),
        p(WickVoice.RATTLE, "Depleted Rattle", "SPARK" to .75f, "FEED" to .80f,
            "CASCADE" to .85f, "TIMBRE" to .65f, "RECOVERY" to .65f),
        p(WickVoice.RATTLE, "Small Rattle", "SPARK" to .45f, "FEED" to .25f,
            "CASCADE" to .45f, "TIMBRE" to .30f, "RECOVERY" to .50f),
        p(WickVoice.RATTLE, "Held Rattle", "SPARK" to .60f, "FEED" to .55f,
            "CASCADE" to .70f, "TIMBRE" to .40f, "RECOVERY" to .65f, "HOLD" to 1f),
    )

    // Contrast the accepted voice settings without changing their source or gate model.
    // The sparse recipes expose the body between answers; the busier ones exercise
    // supply and recovery together rather than differing only in overall output gain.
    private val newRecipes = listOf(
        p(WickVoice.PLUCK, "Quiet Cinders", "SPARK" to .15f, "FEED" to 0f,
            "CASCADE" to .80f, "TIMBRE" to .10f, "RECOVERY" to .75f),
        p(WickVoice.PLUCK, "Bright Relay", "SPARK" to .80f, "FEED" to .38f,
            "CASCADE" to .90f, "TIMBRE" to .72f, "RECOVERY" to .55f),
        p(WickVoice.WOOD, "Hard Grain", "SPARK" to .90f, "FEED" to 0f,
            "CASCADE" to .10f, "TIMBRE" to .75f, "RECOVERY" to .20f),
        p(WickVoice.WOOD, "Hollow Steps", "SPARK" to .45f, "FEED" to .40f,
            "CASCADE" to .85f, "TIMBRE" to .18f, "RECOVERY" to .75f),
        p(WickVoice.BUBBLE, "Clear Beads", "SPARK" to .25f, "FEED" to 0f,
            "CASCADE" to .85f, "TIMBRE" to .18f, "RECOVERY" to .75f),
        p(WickVoice.BREATH, "Faint Current", "SPARK" to .10f, "FEED" to .30f,
            "CASCADE" to .20f, "TIMBRE" to .05f, "RECOVERY" to .90f),
        p(WickVoice.BREATH, "Warm Drift", "SPARK" to .40f, "FEED" to .45f,
            "CASCADE" to .80f, "TIMBRE" to .50f, "RECOVERY" to .45f),
        p(WickVoice.FLICKER, "Distant Metal", "SPARK" to .25f, "FEED" to .18f,
            "CASCADE" to .35f, "TIMBRE" to .45f, "RECOVERY" to .85f),
        p(WickVoice.FLICKER, "Quick Shards", "SPARK" to .85f, "FEED" to .75f,
            "CASCADE" to .95f, "TIMBRE" to .55f, "RECOVERY" to .15f),
    )

    // Preserve the original voice ordering and every voice's established default.
    private val presets = publishedPresets.map { it.voice }.distinct().flatMap { voice ->
        publishedPresets.filter { it.voice == voice } + newRecipes.filter { it.voice == voice }
    }

    fun forVoice(voice: WickVoice): List<WickPatch> = presets.filter { it.voice == voice }

    fun all(): List<WickPatch> = presets

    /** Newly authored settings, kept identifiable until the owner auditions them. */
    fun additions(): List<WickPatch> = newRecipes
}
