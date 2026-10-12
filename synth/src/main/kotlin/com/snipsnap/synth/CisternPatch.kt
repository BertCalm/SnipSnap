package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A saved liquid membrane. Pitch, strike velocity and model version travel beside its six macros. */
data class CisternPatch(
    override val name: String,
    val voice: CisternVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Cistern.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Cistern.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Cistern.macrosFor(voice))
        require(midi in Cistern.MIDI_MIN..Cistern.MIDI_MAX) {
            "CISTERN midi out of ${Cistern.MIDI_MIN}..${Cistern.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "CISTERN velocity out of 0..1: $velocity" }
        require(model == Cistern.MODEL_VERSION) { "unsupported cistern model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Cistern.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "CISTERN"

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Cistern.MODEL_VERSION
            if (model != Cistern.MODEL_VERSION) throw JsonException("unsupported cistern model $model")
            val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Cistern.DEFAULT_MIDI
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            return Patches.decode(value, ENGINE, { n -> CisternVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                CisternPatch(name, voice, macros, midi, velocity, model)
            }
        }

        fun fromJsonText(text: String): CisternPatch = fromJsonValue(Json.parse(text)) as CisternPatch
    }
}
