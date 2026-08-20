package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved TINES sound — same contract as [ThumpPatch], different engine tag.
 * The `engine` field is what keeps a THUMP file from loading into a TINES
 * panel: refusing early beats rendering the wrong sound quietly.
 */
data class TinesPatch(
    override val name: String,
    val voice: TinesVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Tines.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Tines.render(voice, macros)

    companion object {
        const val ENGINE = "TINES"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> TinesVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TinesPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): TinesPatch = fromJsonValue(Json.parse(text)) as TinesPatch
    }
}
