package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A reproducible finite paired-harp gesture. Root, strike energy, model and exact seed
 * travel beside the controls; string, current and venue states are rebuilt on render.
 * This first listening model refuses HOLD rather than saving an unsupported loop.
 */
data class NacrePatch(
    override val name: String,
    val voice: NacreVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Nacre.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val seed: Long = Nacre.DEFAULT_SEED,
    val model: Int = Nacre.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Nacre.macrosFor(voice))
        require(midi in Nacre.MIN_MIDI..Nacre.MAX_MIDI) {
            "NACRE midi must be ${Nacre.MIN_MIDI}..${Nacre.MAX_MIDI}"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "NACRE velocity must be 0..1" }
        require(model == Nacre.MODEL_VERSION) { "unsupported nacre model $model" }
        require((macros["HOLD"] ?: 0f) == 0f) { "NACRE prototype supports finite gestures only; HOLD must be 0" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Nacre.render(voice, macros, midi, velocity, seed)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        // JSON numbers are Doubles; a decimal string preserves every signed 64-bit seed.
        obj["seed"] = JsonValue.Str(seed.toString())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "NACRE"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("NACRE patch has no $key")
            val model = required("model").int()
            if (model != Nacre.MODEL_VERSION) throw JsonException("unsupported nacre model $model")
            val midi = required("midi").int()
            if (midi !in Nacre.MIN_MIDI..Nacre.MAX_MIDI) {
                throw JsonException("NACRE midi must be ${Nacre.MIN_MIDI}..${Nacre.MAX_MIDI}")
            }
            val velocity = required("velocity").num()
            if (!velocity.isFinite() || velocity !in 0.0..1.0) throw JsonException("NACRE velocity must be 0..1")
            val seedText = required("seed").str()
            val seed = seedText.toLongOrNull()
                ?: throw JsonException("NACRE seed must be a decimal 64-bit integer string")
            if (seed.toString() != seedText) throw JsonException("NACRE seed must be a canonical decimal integer string")
            // Refuse malformed values at JSON precision before conversion can round them into range.
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("NACRE $key must be 0..1")
                if (key == "HOLD" && number != 0.0) {
                    throw JsonException("NACRE prototype supports finite gestures only; HOLD must be 0")
                }
            }
            return Patches.decode(value, ENGINE, { n -> NacreVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                NacrePatch(name, voice, macros, midi, velocity.toFloat(), seed, model)
            }
        }

        fun fromJsonText(text: String): NacrePatch = fromJsonValue(Json.parse(text)) as NacrePatch
    }
}
