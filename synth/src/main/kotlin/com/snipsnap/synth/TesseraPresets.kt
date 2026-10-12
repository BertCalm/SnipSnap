package com.snipsnap.synth

/**
 * Eight complete dry recipes spanning the material morph, receiving routes and powered chamber.
 * The settings are provisional until the owner hears the audition; all replies belong to the
 * instrument itself, so no preset needs a rack effect to establish its identity.
 */
object TesseraPresets {
    private fun p(voice: TesseraVoice, name: String, vararg macros: Pair<String, Float>) =
        TesseraPatch(name, voice, Tessera.defaults(voice) + macros.toMap())

    private val presets = listOf(
        p(TesseraVoice.WOOD, "Wooden Arrival"),
        p(TesseraVoice.COURSE, "Paired Wire", "HAMMER" to .65f, "SCALE" to .42f, "FOLD" to .46f, "MOTION" to .30f),
        p(TesseraVoice.TUBE, "Long Tube", "MATERIAL" to .94f, "HAMMER" to .38f, "SCALE" to .72f, "FOLD" to .30f),
        p(TesseraVoice.ANSWER, "Other Material", "MATERIAL" to .25f, "SCALE" to .68f, "FOLD" to .74f),
        p(TesseraVoice.FOLDING, "Closing Passage", "MATERIAL" to .60f, "HAMMER" to .72f, "SCALE" to .38f, "FOLD" to .90f, "MOTION" to .85f),
        p(TesseraVoice.CHAMBER, "Expanding Hall", "MATERIAL" to .45f, "SCALE" to .95f, "FOLD" to .35f, "MOTION" to .68f),
        p(TesseraVoice.ANSWER, "Crossed Returns", "MATERIAL" to .65f, "HAMMER" to .55f, "SCALE" to .70f, "FOLD" to .85f, "MOTION" to .48f),
        p(TesseraVoice.CHAMBER, "Held Chamber", "HAMMER" to .30f, "MOTION" to .42f, "HOLD" to 1f),
    )

    fun forVoice(voice: TesseraVoice): List<TesseraPatch> = presets.filter { it.voice == voice }

    fun all(): List<TesseraPatch> = presets
}
