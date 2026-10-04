package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved FLOTILLA sound. Pitch and velocity sit beside the macros: they are
 * part of the seed and not a seventh knob. Missing `midi` / `velocity` /
 * `model` read as the defaults. An unknown field is ignored. A model other
 * than [Flotilla.MODEL_VERSION] is refused, the same way a bad patch version is.
 */
data class FlotillaPatch(
    override val name: String,
    val voice: FlotillaVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Flotilla.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Flotilla.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Flotilla.macrosFor(voice))
        require(midi in Flotilla.MIDI_MIN..Flotilla.MIDI_MAX) {
            "FLOTILLA midi out of ${Flotilla.MIDI_MIN}..${Flotilla.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "FLOTILLA velocity out of 0..1: $velocity" }
        require(model == Flotilla.MODEL_VERSION) { "unsupported flotilla model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Flotilla.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "FLOTILLA"

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Flotilla.MODEL_VERSION
            if (model != Flotilla.MODEL_VERSION) throw JsonException("unsupported flotilla model $model")
            val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Flotilla.DEFAULT_MIDI
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            return Patches.decode(value, ENGINE, { n -> FlotillaVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                FlotillaPatch(name, voice, macros, midi, velocity, model)
            }
        }

        fun fromJsonText(text: String): FlotillaPatch = fromJsonValue(Json.parse(text)) as FlotillaPatch
    }
}
