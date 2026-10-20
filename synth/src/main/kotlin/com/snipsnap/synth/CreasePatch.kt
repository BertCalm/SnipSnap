package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A native CREASE recipe. The render model, root and excitation velocity are saved
 * explicitly, so editing a macro or generating velocity zones preserves the instrument.
 * Native patches without model metadata start at model 3; standalone audition recipes
 * retain their separate legacy-model decoding rule. */
data class CreasePatch(
    override val name: String,
    val voice: CreaseVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Crease.DEFAULT_MIDI,
    val velocity: Float = .75f,
    val model: Int = Crease.MODEL_VERSION,
) : Patch {
    init {
        require(Crease.supportsModel(model)) { "unsupported CREASE model $model" }
        require(midi in Crease.MIDI_MIN..Crease.MIDI_MAX) {
            "CREASE MIDI must be ${Crease.MIDI_MIN}..${Crease.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "CREASE velocity must be finite in 0..1: $velocity" }
        Patches.validateMacros(this, Crease.macrosFor(voice, model))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Crease.render(voice, macros, midi, velocity, model)
    fun probe() = Crease.probe(voice, macros, midi, velocity, model = model)
    override fun withMacros(macros: Map<String, Float>): CreasePatch = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
        },
    )

    companion object {
        const val ENGINE = "CREASE"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = Crease.MODEL_VERSION
        /** Model of the first native Patch schema, independent of future engine defaults. */
        const val MISSING_MODEL_VERSION = 3

        fun fromJsonValue(value: JsonValue): CreasePatch = try {
            Patches.decode(value, ENGINE, { name -> CreaseVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                val obj = value.obj()
                val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: MISSING_MODEL_VERSION
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Crease.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: .75f
                CreasePatch(name, voice, macros, midi, velocity, model)
            } as CreasePatch
        } catch (error: IllegalArgumentException) {
            throw JsonException(error.message ?: "invalid CREASE patch")
        }

        fun fromJsonText(text: String): CreasePatch = fromJsonValue(Json.parse(text))
    }
}
