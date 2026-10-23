package com.snipsnap.synth

/** Stable coast/weather references followed by musical model-3 starting points. */
object OspreyPresets {
    /** Missing controls stay absent until the saved render model resolves its defaults. */
    private fun p(voice: OspreyVoice, name: String, vararg macros: Pair<String, Float>) =
        OspreyPatch(name, voice, macros.toMap(), seed = 731)

    private val shores = listOf(
        "ATLANTIC" to 0f, "NORDIC" to .25f, "MEDITERRANEAN" to .5f,
        "PACIFIC" to .75f, "SOUTHERN" to 1f,
    )
    private val seas = listOf("CALM" to .1f, "ROUGH" to .5f, "STORM" to .9f)
    private val comparisons = shores.flatMap { (shore, position) ->
        seas.map { (sea, energy) ->
            p(
                OspreyVoice.SWELL, "$shore $sea",
                "SHORE" to position, "SEA" to energy, "MOTION" to .45f,
                "DEPTH" to .55f, "SPACE" to 0f, "HOLD" to .45f,
            )
        }
    }

    private val presets = comparisons + OspreyCuratedPresets.all()

    /** The original fifteen dry comparisons, in their published order. */
    fun coastWeather(): List<OspreyPatch> = comparisons
    fun forVoice(voice: OspreyVoice): List<OspreyPatch> = presets.filter { it.voice == voice }
    fun all(): List<OspreyPatch> = presets
}
