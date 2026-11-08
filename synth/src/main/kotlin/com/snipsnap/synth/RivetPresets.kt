package com.snipsnap.synth

/** The sixteen accepted audition roles, at the common Factory room setting. */
object RivetPresets {
    /** Paste complete UserPresets roster lines here; the accepted kit stays fixed. */
    private val promoted: List<RivetPatch> = listOf()

    fun forVoice(voice: RivetVoice): List<RivetPatch> = all().filter { it.voice == voice }

    fun all(): List<RivetPatch> = RivetKits.patches(.55f) + promoted

    /** Fixed comparison cases retained for regenerating the original audition. */
    fun spaceCases(voice: RivetVoice): List<RivetPatch> = listOf(
        "Workshop" to .10f, "Factory" to .55f, "Warehouse" to 1f,
    ).map { (name, size) ->
        RivetPatch("$name ${voice.name.lowercase()}", voice,
            Rivet.defaults(voice) + mapOf("SIZE" to size, "WALLS" to .75f))
    }
}
