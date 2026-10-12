package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved vessel gesture. TUNE stores the requested note; each render starts a new vessel.
 * Performance velocity belongs to [Velocity.atVelocity], rather than the saved macro map.
 */
data class SuturePatch(
    override val name: String,
    val voice: SutureVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Suture.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Suture.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "SUTURE"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> SutureVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                SuturePatch(name, voice, macros)
            }

        fun fromJsonText(text: String): SuturePatch = fromJsonValue(Json.parse(text)) as SuturePatch
    }
}
