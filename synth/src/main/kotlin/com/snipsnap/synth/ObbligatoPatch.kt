package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** The complete finite duet recipe, including the player calibration's renderer model. */
data class ObbligatoPatch(
    override val name: String,
    override val macros: Map<String, Float>,
    val midi: Int = Obbligato.DEFAULT_MIDI,
    val velocity: Float = Obbligato.DEFAULT_VELOCITY,
    val seed: Int = 0,
    val model: Int = Obbligato.MODEL,
) : Patch {
    init {
        require(name.isNotBlank()) { "OBBLIGATO patch name must not be blank" }
        Obbligato.settled(macros)
        Obbligato.validateNote(midi, velocity)
        require(model in 1..Obbligato.MODEL) { "unsupported OBBLIGATO model: $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = VOICE
    override fun render() = Obbligato.render(macros, midi, velocity, seed, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).apply {
            this["midi"] = JsonValue.Num(midi.toDouble())
            this["velocity"] = JsonValue.Num(velocity.toDouble())
            this["seed"] = JsonValue.Num(seed.toDouble())
            this["model"] = JsonValue.Num(model.toDouble())
        },
    )

    companion object {
        const val ENGINE = "OBBLIGATO"
        const val VOICE = "AIR_REED"

        fun fromJsonValue(value: JsonValue): ObbligatoPatch {
            val obj = value.obj()
            val model = obj["model"]?.int() ?: Obbligato.MODEL
            if (model !in 1..Obbligato.MODEL) throw JsonException("unsupported OBBLIGATO model: $model")
            val midi = obj["midi"]?.int() ?: Obbligato.DEFAULT_MIDI
            val velocity = obj["velocity"]?.num()?.toFloat() ?: Obbligato.DEFAULT_VELOCITY
            val seed = obj["seed"]?.int() ?: 0
            return Patches.decode(value, ENGINE, { it.takeIf { n -> n == VOICE } }) { name, _, macros ->
                ObbligatoPatch(name, macros, midi, velocity, seed, model)
            } as ObbligatoPatch
        }

        fun fromJsonText(text: String): ObbligatoPatch = fromJsonValue(Json.parse(text))
    }
}
