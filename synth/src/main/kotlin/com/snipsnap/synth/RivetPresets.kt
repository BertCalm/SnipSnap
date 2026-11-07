package com.snipsnap.synth

/** Provisional listening cases, deliberately absent from the factory Presets dispatcher. */
object RivetPresets {
    fun forVoice(voice: RivetVoice): List<RivetPatch> = listOf(
        "Workshop" to .10f, "Factory" to .55f, "Warehouse" to 1f,
    ).map { (name, size) ->
        RivetPatch("$name ${voice.name.lowercase()}", voice,
            Rivet.defaults(voice) + mapOf("SIZE" to size, "WALLS" to .75f))
    }

    fun all(): List<RivetPatch> = RivetVoice.entries.flatMap(::forVoice)
}
