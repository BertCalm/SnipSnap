package com.snipsnap.synth

/** Five provisional model-2 recipes for the listening bench, outside the shared factory roster. */
object SplayPresets {
    // New starting points select model 2 explicitly; previously saved model-1 recipes keep their own model.
    private const val MODEL = 2

    private fun p(name: String, vararg macros: Pair<String, Float>) = SplayPatch(
        name, SplayVoice.TONGUE,
        Splay.defaults(SplayVoice.TONGUE, MODEL) + ("POPULATION" to (15f / 99f)) + macros,
        model = MODEL,
    )

    private val presets = listOf(
        p("First Seams"),
        p("Soft Brush", "CONTACT" to .10f),
        p("Early Scatter", "TRACE" to .72f, "SEED GAP" to .82f, "ENERGY" to .85f),
        p("Slow Thread", "TRACE" to .25f),
        p("Small Mechanism", "POPULATION" to (3f / 99f)),
    )

    fun forVoice(voice: SplayVoice): List<SplayPatch> = presets.filter { it.voice == voice }
    fun all(): List<SplayPatch> = presets
}
