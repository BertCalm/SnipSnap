package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A deterministic TANGLE recipe. The requested root and playing velocity are
 * saved beside its six macros, so pitch does not consume a timbral control.
 * Oscillator phases and capture/control state are rebuilt from the voice and
 * note seed on every render; no private DSP state enters the patch format.
 */
data class TanglePatch(
    override val name: String,
    val voice: TangleVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Tangle.rootMidi(voice),
    val velocity: Float = 1f,
) : Patch {
    init {
        Patches.validateMacros(this, Tangle.macrosFor(voice))
        require(midi in Tangle.MIN_MIDI..Tangle.MAX_MIDI) {
            "TANGLE midi out of ${Tangle.MIN_MIDI}..${Tangle.MAX_MIDI}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "TANGLE velocity out of 0..1: $velocity" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Tangle.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "TANGLE"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { n -> TangleVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Tangle.rootMidi(voice)
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                TanglePatch(name, voice, macros, midi, velocity)
            }
        }

        fun fromJsonText(text: String): TanglePatch = fromJsonValue(Json.parse(text)) as TanglePatch
    }
}
