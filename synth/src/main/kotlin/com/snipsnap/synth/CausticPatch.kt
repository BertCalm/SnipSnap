package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * Editable native CAUSTIC sound. Its native render model is independent of
 * the archived [CausticRecipe] models; note and excitation survive macro edits.
 * This first instrument renders finite notes and reserves HOLD at zero.
 */
data class CausticPatch(
    override val name: String,
    val voice: CausticVoice,
    override val macros: Map<String, Float>,
    val midi: Int = CausticInstrument.DEFAULT_MIDI,
    val velocity: Float = .75f,
    val model: Int = CausticInstrument.MODEL_VERSION,
) : Patch {
    init {
        require(CausticInstrument.supportsModel(model)) { "unsupported CAUSTIC native model $model" }
        require(midi in CausticInstrument.MIDI_MIN..CausticInstrument.MIDI_MAX) {
            "CAUSTIC MIDI must be ${CausticInstrument.MIDI_MIN}..${CausticInstrument.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "CAUSTIC velocity must be finite in 0..1: $velocity" }
        Patches.validateMacros(this, CausticInstrument.macrosFor(voice, model))
        require((macros["HOLD"] ?: 0f) == 0f) { "CAUSTIC HOLD is reserved at 0; this instrument renders finite notes" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = CausticInstrument.render(voice, macros, midi, velocity, model)
    override fun withMacros(macros: Map<String, Float>): CausticPatch = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
        },
    )

    companion object {
        const val ENGINE = "CAUSTIC"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = CausticInstrument.MODEL_VERSION
        /** First native model, even if a later instrument changes its defaults. */
        const val MISSING_MODEL_VERSION = 1

        fun fromJsonValue(value: JsonValue): CausticPatch = try {
            Patches.decode(value, ENGINE, { name -> CausticVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                val obj = value.obj()
                val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: MISSING_MODEL_VERSION
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: CausticInstrument.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num() ?: .75
                require(velocity.isFinite() && velocity in 0.0..1.0) { "CAUSTIC velocity must be finite in 0..1: $velocity" }
                CausticPatch(name, voice, macros, midi, velocity.toFloat(), model)
            } as CausticPatch
        } catch (error: IllegalArgumentException) {
            throw JsonException(error.message ?: "invalid CAUSTIC patch")
        }

        fun fromJsonText(text: String): CausticPatch = fromJsonValue(Json.parse(text))
    }
}
