package com.snipsnap.synth

/**
 * Provisional references, kept out of [Presets] while the engine is a prototype (`PatchTypes.PROTOTYPES`).
 * They are the 56 one-shots of the four genre kits the owner accepted by ear at round 2 in the Python harness
 * (2026-11-15), filed under the voice that plays them and named after their kit. The Kotlin renders have not been
 * heard yet; accepting them is what moves KINDRED into the factory roster.
 */
object KindredPresets {
    fun forVoice(voice: KindredVoice): List<KindredPatch> {
        val pads = KindredRecipes.ALL.filter { it.voice == voice }.map { r ->
            val kit = r.id.substringBefore('_').lowercase().replaceFirstChar { it.uppercase() }
            KindredPatch.of(r).copy(name = "$kit ${r.name}")
        }
        return pads.ifEmpty { listOf(KindredPatch(voice.name.lowercase().replaceFirstChar { it.uppercase() }, voice, Kindred.defaults(voice))) }
    }

    fun all(): List<KindredPatch> = KindredVoice.entries.flatMap(::forVoice)
}
