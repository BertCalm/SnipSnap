package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved GLINT sound — same contract as [TinesPatch], different engine tag.
 *
 * Phase 2 adds a `window: IntArray?` field here for the TRACE voice, baked
 * into the patch the way [SnapPatch] bakes its table. Nothing references a
 * source file: a patch that points at external material falls out of the
 * "a kit regenerates from kit.json" guarantee and stays out, which is why
 * the GRAINS kit sits outside `PadRecipeTest`'s list to this day.
 */
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

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> GlintVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                GlintPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}
