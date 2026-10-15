package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A replayable finite performance: note, energy and exact 64-bit seed travel with the six macros. */
data class FlockPatch(
    override val name: String,
    val voice: FlockVoice,
    override val macros: Map<String, Float>,
    val midi: Int = 60,
    val velocity: Float = 1f,
    val seed: Long = Flock.DEFAULT_SEED,
    val model: Int = Flock.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Flock.macrosFor(voice))
        require(midi in Flock.MIN_MIDI..Flock.MAX_MIDI) { "FLOCK midi must be ${Flock.MIN_MIDI}..${Flock.MAX_MIDI}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "FLOCK velocity must be 0..1" }
        require(model == Flock.MODEL_VERSION) { "unsupported flock model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Flock.render(voice, macros, midi, velocity, seed)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        // JsonValue.Num is a Double. A decimal string preserves every possible Long exactly.
        obj["seed"] = JsonValue.Str(seed.toString())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "FLOCK"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("FLOCK patch has no $key")
            val model = required("model").int()
            if (model != Flock.MODEL_VERSION) throw JsonException("unsupported flock model $model")
            val midi = required("midi").int()
            val velocityDouble = required("velocity").num()
            if (!velocityDouble.isFinite() || velocityDouble !in 0.0..1.0) throw JsonException("FLOCK velocity must be 0..1")
            val seedText = required("seed").str()
            val seed = seedText.toLongOrNull() ?: throw JsonException("FLOCK seed must be a decimal 64-bit integer string")
            if (seed.toString() != seedText) throw JsonException("FLOCK seed must be a canonical decimal integer string")
            // Check at JSON precision before the common decoder converts macro values to Float.
            // Values just outside a bound must not become valid merely by rounding to 0f or 1f.
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("FLOCK $key must be 0..1")
            }
            return Patches.decode(value, ENGINE, { n -> FlockVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                FlockPatch(name, voice, macros, midi, velocityDouble.toFloat(), seed, model)
            }
        }

        fun fromJsonText(text: String): FlockPatch = fromJsonValue(Json.parse(text)) as FlockPatch
    }
}
