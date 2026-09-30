package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** A saved GLINT sound — same contract as [TinesPatch], different engine tag. */
data class GlintPatch(
    override val name: String,
    val voice: GlintVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Glint.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Glint.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "GLINT"
        const val VERSION = Patches.VERSION

        /**
         * A voice name as a patch was saved: the voice it plays as today and,
         * for a name from before docs/superpowers/specs/2026-09-29-glint-paths-design.md,
         * how its BLOOM remaps. The old name is the migration marker —
         * `Patches.VERSION` is shared by every engine and cannot mark GLINT
         * alone — and a loaded patch is saved under its voice's own name, so
         * the marker is gone after one load and nothing is remapped twice.
         */
        private class SavedVoice(val voice: GlintVoice, val bloom: ((Float) -> Float)? = null)

        // Old BLOOM ran 0 (still) to 1 (widest) and fell into PEAK on every
        // voice but RATCHET, whose ladder climbed. Bipolar BLOOM is still at
        // 0.5, falling above it and rising below, so the old range folds onto
        // one side or the other.
        private fun falling(voice: GlintVoice) = SavedVoice(voice) { b -> 0.5f + b / 2f }
        private fun climbing(voice: GlintVoice) = SavedVoice(voice) { b -> 0.5f - b / 2f }

        private val LEGACY = mapOf(
            "REED" to falling(GlintVoice.SWEEP),
            "BOTTLE" to falling(GlintVoice.SWEEP),
            "KAZOO" to falling(GlintVoice.SWEEP),
            "CICADA" to falling(GlintVoice.SWEEP),
            "PLATE" to falling(GlintVoice.BRASS),
            "RATCHET" to climbing(GlintVoice.STEP),
        )

        /**
         * The old BLOOM default, which is what a patch saved without a BLOOM
         * meant. Today's default (0.675) is this value through the falling
         * remap: the same gesture for SWEEP and BRASS, not for STEP, whose
         * ancestor RATCHET climbed. So an old patch is always given a BLOOM,
         * never left to read today's default.
         */
        private const val LEGACY_DEFAULT_BLOOM = 0.35f

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(
                value, ENGINE,
                { n -> GlintVoice.entries.firstOrNull { it.name == n }?.let { SavedVoice(it) } ?: LEGACY[n] },
            ) { name, saved, macros ->
                GlintPatch(name, saved.voice, migrated(saved, macros))
            }

        /**
         * [macros] as an old patch meant them: BLOOM remapped, and always
         * written (see [LEGACY_DEFAULT_BLOOM]). An old BLOOM outside 0..1 is
         * left as saved for [Patches.validateMacros] to refuse, as an old
         * build did; remapped, it could land back in range and load.
         */
        private fun migrated(saved: SavedVoice, macros: Map<String, Float>): Map<String, Float> {
            val remap = saved.bloom ?: return macros
            val old = macros["BLOOM"] ?: LEGACY_DEFAULT_BLOOM
            val bloom = if (old in 0f..1f) remap(old) else old
            return macros + ("BLOOM" to bloom)
        }

        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}
