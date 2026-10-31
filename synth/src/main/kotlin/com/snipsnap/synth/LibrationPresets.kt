package com.snipsnap.synth

/** A finite musical bank; the accepted listening references remain independently replayable. */
object LibrationPresets {
    const val REFERENCE_MODEL = 2
    private val references = listOf(
        LibrationPatch("Gather", LibrationVoice.GATHER, model = REFERENCE_MODEL),
        LibrationPatch("Twins", LibrationVoice.TWINS, model = REFERENCE_MODEL),
        LibrationPatch("Departure", LibrationVoice.DEPARTURE, model = REFERENCE_MODEL),
    )
    private fun p(name: String, voice: LibrationVoice, midi: Int, seconds: Double,
        region: Float, wander: Float, nudge: Float, swarm: Float, resonance: Float) =
        LibrationPatch(name, voice, Libration.defaults(voice) + mapOf("REGION" to region,
            "WANDER" to wander, "NUDGE" to nudge, "SWARM" to swarm, "RESONANCE" to resonance),
            midi = midi, sourceSeconds = seconds)

    private val presets = listOf(
        p("ROOT WOOD", LibrationVoice.GATHER, 36, .42, 0f, .22f, .08f, .22f, .18f),
        p("ROUND KEYS", LibrationVoice.GATHER, 60, 1.10, 0f, .42f, .18f, .48f, .32f),
        p("TWIN GROVE", LibrationVoice.GATHER, 55, 2.40, .125f, .60f, .28f, .75f, .50f),
        p("SLOW ORBIT", LibrationVoice.GATHER, 48, 3.60, 0f, .84f, .36f, .90f, .64f),
        p("GLASS SEED", LibrationVoice.TWINS, 72, .30, 1f, .18f, .08f, .25f, .25f),
        p("FINE KEYS", LibrationVoice.TWINS, 60, .85, 1f, .44f, .16f, .68f, .40f),
        p("DOUBLE HALO", LibrationVoice.TWINS, 67, 2.20, .875f, .50f, .25f, .90f, .56f),
        p("HIGH CANOPY", LibrationVoice.TWINS, 72, 3.40, 1f, .78f, .45f, 1f, .72f),
        p("QUICK RELEASE", LibrationVoice.DEPARTURE, 60, 1.80, .5f, .28f, 1f, .50f, .30f),
        p("OPEN EXIT", LibrationVoice.DEPARTURE, 55, 3.20, .5f, .52f, .70f, .78f, .50f),
        p("SPLIT PATH", LibrationVoice.DEPARTURE, 60, 3.60, .375f, .62f, .62f, .84f, .55f),
        p("LONG GOODBYE", LibrationVoice.DEPARTURE, 48, 5.0, .75f, .68f, .75f, .82f, .78f),
    )

    fun forVoice(voice: LibrationVoice): List<LibrationPatch> = presets.filter { it.voice == voice }

    fun all(): List<LibrationPatch> = presets

    fun references(): List<LibrationPatch> = references
}
