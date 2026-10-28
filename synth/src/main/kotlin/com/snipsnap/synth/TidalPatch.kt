package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A replayable encounter. The source model and optional held model pin both render paths. */
data class TidalPatch(
    override val name: String,
    val voice: TidalVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Tidal.DEFAULT_MIDI,
    val velocity: Float = .75f,
    val seed: Long = Tidal.DEFAULT_SEED,
    val model: Int = Tidal.MODEL_VERSION,
    val heldModel: Int = TidalKeys.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Tidal.macrosFor(voice, model))
        require(midi in Tidal.MIN_MIDI..Tidal.MAX_MIDI) { "TIDAL midi must be ${Tidal.MIN_MIDI}..${Tidal.MAX_MIDI}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "TIDAL velocity must be 0..1" }
        require(Tidal.supportsModel(model)) { "unsupported tidal model $model" }
        require((macros["HOLD"] ?: 0f) in setOf(0f, 1f)) { "TIDAL HOLD must be 0 or 1" }
        require(heldModel == TidalKeys.MODEL_VERSION) { "unsupported TIDAL held model $heldModel" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Tidal.render(voice, macros, midi, velocity, seed, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            if (Tidal.isHeld(macros["HOLD"] ?: 0f)) it["heldModel"] = JsonValue.Num(heldModel.toDouble())
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
            // A JSON Double cannot represent every signed 64-bit seed.
            it["seed"] = JsonValue.Str(seed.toString())
        },
    )

    companion object {
        const val ENGINE = "TIDAL"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): TidalPatch = try {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("TIDAL patch has no $key")
            val model = required("model").int()
            if (!Tidal.supportsModel(model)) throw JsonException("unsupported tidal model $model")
            val midi = required("midi").int()
            val velocity = required("velocity").num()
            if (!velocity.isFinite() || velocity !in 0.0..1.0) throw JsonException("TIDAL velocity must be 0..1")
            val seedText = required("seed").str()
            val seed = seedText.toLongOrNull()
                ?: throw JsonException("TIDAL seed must be a decimal 64-bit integer string")
            if (seed.toString() != seedText) throw JsonException("TIDAL seed must be a canonical decimal integer string")
            // Reject values outside the domain before Float conversion can round them onto a bound.
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("TIDAL $key must be 0..1")
                if (key == "HOLD" && number != 0.0 && number != 1.0) throw JsonException("TIDAL HOLD must be 0 or 1")
            }
            val held = (rawMacros as? JsonValue.Obj)?.entries?.get("HOLD")?.num() == 1.0
            val heldModel = obj["heldModel"]?.int() ?: if (held)
                throw JsonException("TIDAL HOLD recipe has no heldModel") else TidalKeys.MODEL_VERSION
            Patches.decode(value, ENGINE, { name -> TidalVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                TidalPatch(name, voice, macros, midi, velocity.toFloat(), seed, model, heldModel)
            } as TidalPatch
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid TIDAL patch")
        }

        fun fromJsonText(text: String): TidalPatch = fromJsonValue(Json.parse(text))
    }
}
