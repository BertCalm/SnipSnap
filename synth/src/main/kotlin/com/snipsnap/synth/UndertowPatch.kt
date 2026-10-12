package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved suction-shell sound. TUNE carries the requested note; the complete settled recipe
 * supplies deterministic chamber and texture seeds. Event velocity reaches the piston through
 * [Velocity] without changing the saved material controls.
 */
data class UndertowPatch(
    override val name: String,
    val voice: UndertowVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Undertow.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Undertow.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "UNDERTOW"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> UndertowVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                UndertowPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): UndertowPatch = fromJsonValue(Json.parse(text)) as UndertowPatch
    }
}
