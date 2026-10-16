package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A reproducible WICK recipe. The requested root and velocity live beside
 * the six controls; gate charge, recovery, phases and queued events are
 * rebuilt by the renderer rather than saved as private DSP state.
 */
data class WickPatch(
    override val name: String,
    val voice: WickVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Wick.rootMidi(voice),
    val velocity: Float = 1f,
) : Patch {
    init {
        Patches.validateMacros(this, Wick.macrosFor(voice))
        require(midi in Wick.MIN_MIDI..Wick.MAX_MIDI) {
            "WICK midi out of ${Wick.MIN_MIDI}..${Wick.MAX_MIDI}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "WICK velocity out of 0..1: $velocity" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Wick.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
        },
    )

    companion object {
        const val ENGINE = "WICK"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { name -> WickVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Wick.rootMidi(voice)
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                WickPatch(name, voice, macros, midi, velocity)
            }
        }

        fun fromJsonText(text: String): WickPatch = fromJsonValue(Json.parse(text)) as WickPatch
    }
}
