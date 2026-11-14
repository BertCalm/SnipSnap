package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A finite SPLAY performance. Mechanical histories are rebuilt from the saved controls and model. */
data class SplayPatch(
    override val name: String,
    val voice: SplayVoice,
    override val macros: Map<String, Float>,
    val midi: Int = 60,
    val velocity: Float = .8f,
    /** Bounded acoustic roughness only; initial mechanical spacing belongs to SEED GAP. */
    val seed: Int = 41001,
    val model: Int = Splay.MODEL_VERSION,
) : Patch {
    init {
        require(Splay.supportsModel(model)) { "unsupported SPLAY model $model" }
        Patches.validateMacros(this, Splay.macrosFor(voice, model))
        require(midi in Splay.MIDI_MIN..Splay.MIDI_MAX) { "SPLAY midi must be ${Splay.MIDI_MIN}..${Splay.MIDI_MAX}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SPLAY velocity must be 0..1" }
        require((macros["HOLD"] ?: 0f) == 0f) { "SPLAY finite prototype requires HOLD 0" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Splay.render(voice, macros, midi, velocity, seed, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
            // Every Int is represented exactly by the shared Double-backed JSON number.
            it["seed"] = JsonValue.Num(seed.toDouble())
        },
    )

    companion object {
        const val ENGINE = "SPLAY"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): SplayPatch = try {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("SPLAY patch has no $key")
            val model = required("model").int()
            if (!Splay.supportsModel(model)) throw JsonException("unsupported SPLAY model $model")
            val midi = required("midi").int()
            val velocity = required("velocity").num()
            if (!velocity.isFinite() || velocity !in 0.0..1.0) throw JsonException("SPLAY velocity must be 0..1")
            val seed = required("seed").int()
            // Reject at JSON precision before Float conversion can round onto a valid endpoint.
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("SPLAY $key must be 0..1")
                if (key == "HOLD" && number != 0.0) throw JsonException("SPLAY finite prototype requires HOLD 0")
            }
            Patches.decode(value, ENGINE, { name -> SplayVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                SplayPatch(name, voice, macros, midi, velocity.toFloat(), seed, model)
            } as SplayPatch
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid SPLAY patch")
        }

        fun fromJsonText(text: String): SplayPatch = fromJsonValue(Json.parse(text))
    }
}
