package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved THUMP sound: voice + macro settings + a hand-written name.
 *
 * Serialized as JSON (shape shared by every engine via [Patches]) so a synth
 * pad can keep its recipe next to its rendered WAV (editable forever) and so
 * patches can be shared as files. Embedding into `kit.json` happens through
 * [PadRecipe]; this is the per-engine format.
 */
data class ThumpPatch(
    override val name: String,
    val voice: ThumpVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Thump.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Thump.render(voice, macros)

    companion object {
        const val ENGINE = "THUMP"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> ThumpVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                ThumpPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): ThumpPatch = fromJsonValue(Json.parse(text)) as ThumpPatch
    }
}
