package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete finite CHAMBER recipe. Room geometry and surface policy travel with
 * the sound, alongside the requested note, event velocity, exact seed and model.
 * Editing the controls never replaces that authored room with a factory default.
 */
data class ChamberPatch(
    override val name: String,
    val voice: ChamberVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Chamber.DEFAULT_MIDI,
    val velocity: Float = .75f,
    val configuration: ChamberConfig = ChamberConfig.forVoice(voice),
    val seed: Long = Chamber.DEFAULT_SEED,
    val model: Int = Chamber.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Chamber.macrosFor(voice))
        require(midi in Chamber.MIDI_MIN..Chamber.MIDI_MAX) {
            "CHAMBER midi must be ${Chamber.MIDI_MIN}..${Chamber.MIDI_MAX}"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "CHAMBER velocity must be 0..1" }
        require(model in 1..Chamber.MODEL_VERSION) { "unsupported chamber model $model" }
        require((macros["HOLD"] ?: 0f) == 0f) { "CHAMBER first-listen prototype supports finite strikes only; HOLD must be 0" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Chamber.render(voice, macros, midi, velocity, configuration, seed, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
            // JSON numbers are Doubles; decimal text preserves every signed 64-bit seed.
            it["seed"] = JsonValue.Str(seed.toString())
            it["configuration"] = configuration.toJsonValue()
        },
    )

    companion object {
        const val ENGINE = "CHAMBER"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): ChamberPatch = try {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("CHAMBER patch has no $key")
            val model = required("model").int()
            if (model !in 1..Chamber.MODEL_VERSION) throw JsonException("unsupported chamber model $model")
            val midi = required("midi").int()
            val velocity = required("velocity").num()
            if (!velocity.isFinite() || velocity !in 0.0..1.0) throw JsonException("CHAMBER velocity must be 0..1")
            val seedText = required("seed").str()
            val seed = seedText.toLongOrNull()
                ?: throw JsonException("CHAMBER seed must be a decimal 64-bit integer string")
            if (seed.toString() != seedText) throw JsonException("CHAMBER seed must be a canonical decimal integer string")
            // Validate before Float conversion can round an out-of-range JSON value onto a bound.
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("CHAMBER $key must be 0..1")
                if (key == "HOLD" && number != 0.0) throw JsonException("CHAMBER first-listen prototype requires HOLD 0")
            }
            val configuration = ChamberConfig.fromJsonValue(required("configuration"))
            Patches.decode(value, ENGINE, { name -> ChamberVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                ChamberPatch(name, voice, macros, midi, velocity.toFloat(), configuration, seed, model)
            } as ChamberPatch
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid CHAMBER patch")
        }

        fun fromJsonText(text: String): ChamberPatch = fromJsonValue(Json.parse(text))
    }
}
