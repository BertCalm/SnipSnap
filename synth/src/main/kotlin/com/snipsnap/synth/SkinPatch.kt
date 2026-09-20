package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved SKIN sound: voice + macro settings + a hand-written name.
 *
 * Serialized as JSON (shape shared by every engine via [Patches]) so a synth
 * pad can keep its recipe next to its rendered WAV (editable forever) and so
 * patches can be shared as files. Embedding into `kit.json` happens through
 * [PadRecipe]; this is the per-engine format.
 */
data class SkinPatch(
    override val name: String,
    val voice: SkinVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Skin.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Skin.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "SKIN"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> SkinVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                SkinPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): SkinPatch = fromJsonValue(Json.parse(text)) as SkinPatch
    }
}
