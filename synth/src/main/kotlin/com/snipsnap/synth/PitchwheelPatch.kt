package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved wooden wheel. Each render rebuilds wheel, fingers, resin and thermal state
 * from the deterministic recipe. Root note, gesture velocity and model version
 * travel beside the macros; TUNE is a relative adjustment to the saved root.
 */
data class PitchwheelPatch(
    override val name: String,
    val voice: PitchwheelVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Pitchwheel.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Pitchwheel.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Pitchwheel.macrosFor(voice))
        require(midi in Pitchwheel.MIDI_MIN..Pitchwheel.MIDI_MAX) {
            "PITCHWHEEL midi out of ${Pitchwheel.MIDI_MIN}..${Pitchwheel.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "PITCHWHEEL velocity out of 0..1: $velocity" }
        require(model == Pitchwheel.MODEL_VERSION) { "unsupported pitchwheel model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Pitchwheel.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "PITCHWHEEL"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Pitchwheel.MODEL_VERSION
            if (model != Pitchwheel.MODEL_VERSION) throw JsonException("unsupported pitchwheel model $model")
            val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Pitchwheel.DEFAULT_MIDI
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            return Patches.decode(value, ENGINE, { n -> PitchwheelVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                PitchwheelPatch(name, voice, macros, midi, velocity, model)
            }
        }

        fun fromJsonText(text: String): PitchwheelPatch = fromJsonValue(Json.parse(text)) as PitchwheelPatch
    }
}
