package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A reproducible first-listen orbital instrument. Pitch and playing energy are independent
 * of the six movement controls; trajectories and modal states are rebuilt from the saved model.
 */
data class ApsisPatch(
    override val name: String,
    val voice: ApsisVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Apsis.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val config: ApsisConfig = ApsisConfig(),
) : Patch {
    init {
        Patches.validateMacros(this, Apsis.macrosFor(voice))
        require(midi in Apsis.MIDI_MIN..Apsis.MIDI_MAX) { "APSIS MIDI out of ${Apsis.MIDI_MIN}..${Apsis.MIDI_MAX}: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "APSIS velocity must be finite in 0..1" }
        require(Apsis.supportsModel(voice, config.modelVersion)) { "unsupported APSIS $voice model ${config.modelVersion}" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Apsis.render(voice, macros, midi, velocity, config)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["config"] = JsonValue.Obj(linkedMapOf(
            "modelVersion" to JsonValue.Num(config.modelVersion.toDouble()),
            "seedContext" to JsonValue.Num(config.seedContext.toDouble()),
        ))
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "APSIS"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): ApsisPatch {
            val obj = value.obj()
            val configuration = obj["config"]?.takeUnless { it is JsonValue.Null }?.obj()
            val savedModel = configuration?.get("modelVersion")?.takeUnless { it is JsonValue.Null }?.int()
            if (savedModel != null && !Apsis.supportsModel(savedModel)) throw JsonException("unsupported APSIS model $savedModel")
            val seed = configuration?.get("seedContext")?.takeUnless { it is JsonValue.Null }?.int() ?: 0
            return Patches.decode(value, ENGINE, { n -> ApsisVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val model = savedModel ?: if (voice == ApsisVoice.PASSAGE) Apsis.LEGACY_MODEL_VERSION else Apsis.POPULATION_MODEL_VERSION
                if (!Apsis.supportsModel(voice, model)) throw JsonException("unsupported APSIS $voice model $model")
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Apsis.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                ApsisPatch(name, voice, macros, midi, velocity, ApsisConfig(model, seed))
            } as ApsisPatch
        }

        fun fromJsonText(text: String): ApsisPatch = fromJsonValue(Json.parse(text))
    }
}
