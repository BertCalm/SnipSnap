package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved THAW sound. TUNE stores the requested note and every independent render starts frozen.
 * The voice and note determine the material texture seed, so a pad recipe regenerates exactly.
 * Gesture velocity belongs to [Velocity.atVelocity], rather than the saved macro map.
 */
data class ThawPatch(
    override val name: String,
    val voice: ThawVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Thaw.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Thaw.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "THAW"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> ThawVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                ThawPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): ThawPatch = fromJsonValue(Json.parse(text)) as ThawPatch
    }
}
