package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A complete generated-attack/PM recipe, including the material and held routing. */
data class AlloyPatch(
    override val name: String,
    val voice: AlloyVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Alloy.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Alloy.MODEL_VERSION,
    val config: AlloyConfig = Alloy.defaultConfigFor(voice),
) : Patch {
    init {
        Patches.validateMacros(this, Alloy.macrosFor(voice))
        require(midi in Alloy.MIDI_MIN..Alloy.MIDI_MAX) {
            "ALLOY midi out of ${Alloy.MIDI_MIN}..${Alloy.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "ALLOY velocity out of 0..1: $velocity" }
        require(model == Alloy.MODEL_VERSION) { "unsupported alloy model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Alloy.render(voice, macros, midi, velocity, config)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["config"] = config.toJsonValue()
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "ALLOY"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> AlloyVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val obj = value.obj()
                val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Alloy.MODEL_VERSION
                if (model != Alloy.MODEL_VERSION) throw JsonException("unsupported alloy model $model")
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Alloy.DEFAULT_MIDI
                if (midi !in Alloy.MIDI_MIN..Alloy.MIDI_MAX) throw JsonException("ALLOY midi out of ${Alloy.MIDI_MIN}..${Alloy.MIDI_MAX}: $midi")
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                if (!velocity.isFinite() || velocity !in 0f..1f) throw JsonException("ALLOY velocity out of 0..1: $velocity")
                // Missing configuration belongs to the original model-1 recipe,
                // even after a different voice is selected for newly made patches.
                val config = obj["config"]?.takeUnless { it is JsonValue.Null }?.let { AlloyConfig.fromJsonValue(it) }
                    ?: Alloy.configFor(voice)
                AlloyPatch(name, voice, macros, midi, velocity, model, config)
            }

        fun fromJsonText(text: String): AlloyPatch = fromJsonValue(Json.parse(text)) as AlloyPatch
    }
}
