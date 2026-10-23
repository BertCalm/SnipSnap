package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A replayable finite rolling gesture. The saved model, root and velocity pin
 * the render and its derived seed context; TUNE adjusts the saved root.
 * HOLD remains refused while this prototype has no validated loop construction.
 */
data class SkirlPatch(
    override val name: String,
    val voice: SkirlVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Skirl.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Skirl.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Skirl.macrosFor(voice))
        require(midi in Skirl.MIDI_MIN..Skirl.MIDI_MAX) {
            "SKIRL midi must be ${Skirl.MIDI_MIN}..${Skirl.MIDI_MAX}"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "SKIRL velocity must be 0..1" }
        require(model == Skirl.MODEL_VERSION) { "unsupported skirl model $model" }
        require((macros["HOLD"] ?: 0f) == 0f) { "SKIRL finite prototype requires HOLD 0" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Skirl.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
        },
    )

    companion object {
        const val ENGINE = "SKIRL"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): SkirlPatch = try {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("SKIRL patch has no $key")
            val model = required("model").int()
            if (model != Skirl.MODEL_VERSION) throw JsonException("unsupported skirl model $model")
            val midi = required("midi").int()
            val velocity = required("velocity").num()
            if (!velocity.isFinite() || velocity !in 0.0..1.0) throw JsonException("SKIRL velocity must be 0..1")
            // Reject out-of-range values before Float conversion can round them
            // onto a boundary or turn a tiny positive HOLD into zero.
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("SKIRL $key must be 0..1")
                if (key == "HOLD" && number != 0.0) throw JsonException("SKIRL finite prototype requires HOLD 0")
            }
            Patches.decode(value, ENGINE, { name -> SkirlVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                SkirlPatch(name, voice, macros, midi, velocity.toFloat(), model)
            } as SkirlPatch
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid SKIRL patch")
        }

        fun fromJsonText(text: String): SkirlPatch = fromJsonValue(Json.parse(text))
    }
}
