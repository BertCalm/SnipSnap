package com.snipsnap.synth

/** Nine finite listening recipes. Their seeds are part of the musical performance. */
object FlockPresets {
    private fun p(voice: FlockVoice, name: String, seed: Long, vararg macros: Pair<String, Float>) =
        FlockPatch(name, voice, Flock.defaults(voice) + macros.toMap(), seed = seed)

    private val presets = listOf(
        p(FlockVoice.GATHER, "First Gathering", 41001),
        p(FlockVoice.GATHER, "Glass Returning", 41002, "SPREAD" to .78f, "PULL" to .50f, "FLOW" to .62f, "EDGE" to .72f, "HOLD" to .70f),
        p(FlockVoice.GATHER, "Close Feathers", 41003, "SPREAD" to .20f, "PULL" to .88f, "SCATTER" to .38f, "EDGE" to .10f, "HOLD" to .18f),
        p(FlockVoice.RIBBON, "Traveling Turn", 42001),
        p(FlockVoice.RIBBON, "Wide Current", 42002, "SPREAD" to .90f, "PULL" to .12f, "FLOW" to .66f, "SCATTER" to .84f, "HOLD" to .92f),
        p(FlockVoice.RIBBON, "Silver Thread", 42003, "SPREAD" to .32f, "PULL" to .46f, "FLOW" to .92f, "EDGE" to .80f, "HOLD" to .60f),
        p(FlockVoice.SPLIT, "Three Flights", 43001),
        p(FlockVoice.SPLIT, "Open Harmonics", 43002, "SPREAD" to .78f, "PULL" to .30f, "FLOW" to .50f, "SCATTER" to .88f, "EDGE" to .60f, "HOLD" to .85f),
        p(FlockVoice.SPLIT, "Soft Formation", 43003, "SPREAD" to .22f, "PULL" to .82f, "FLOW" to .76f, "SCATTER" to .34f, "EDGE" to .12f, "HOLD" to .45f),
    )

    fun all(): List<FlockPatch> = presets
    fun forVoice(voice: FlockVoice): List<FlockPatch> = presets.filter { it.voice == voice }
}
