package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Note, touch, exact seed and model survive recipe edits and JSON replay. */
data class FerryPatch(
    override val name: String,
    val voice: FerryVoice = FerryVoice.PASSAGE,
    override val macros: Map<String, Float> = Ferry.defaults(voice),
    val midi: Int = Ferry.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val seed: Long = Ferry.DEFAULT_SEED,
    val model: Int = Ferry.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Ferry.macrosFor(voice))
        require(midi in Ferry.MIN_MIDI..Ferry.MAX_MIDI) { "FERRY MIDI outside ${Ferry.MIN_MIDI}..${Ferry.MAX_MIDI}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "FERRY velocity must be finite and in 0..1" }
        require(model == Ferry.MODEL_VERSION) { "unsupported ferry model $model" }
        require((macros["HOLD"] ?: 0f) == 0f) { "FERRY model 1 is finite; HOLD must be 0" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Ferry.render(voice, macros, midi, velocity, seed)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).also {
        it["model"] = JsonValue.Num(model.toDouble())
        it["midi"] = JsonValue.Num(midi.toDouble())
        it["velocity"] = JsonValue.Num(velocity.toDouble())
        it["seed"] = JsonValue.Str(seed.toString())
    })
    companion object {
        const val ENGINE = "FERRY"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            fun field(name: String) = obj[name]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("FERRY patch has no $name")
            val model = field("model").int()
            if (model != Ferry.MODEL_VERSION) throw JsonException("unsupported ferry model $model")
            val midi = field("midi").int()
            if (midi !in Ferry.MIN_MIDI..Ferry.MAX_MIDI) throw JsonException("FERRY MIDI outside range")
            val velocity = field("velocity").num()
            if (!velocity.isFinite() || velocity !in 0.0..1.0) throw JsonException("FERRY velocity must be finite and in 0..1")
            val seedText = field("seed").str()
            val seed = seedText.toLongOrNull()?.takeIf { it.toString() == seedText }
                ?: throw JsonException("FERRY seed must be a canonical signed 64-bit decimal string")
            val raw = obj["macros"]
            if (raw is JsonValue.Obj) for ((key, entry) in raw.entries) {
                val number = entry.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("FERRY $key must be finite and in 0..1")
                if (key == "HOLD" && number != 0.0) throw JsonException("FERRY model 1 is finite; HOLD must be 0")
            }
            return Patches.decode(value, ENGINE, { name -> FerryVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                FerryPatch(name, voice, macros, midi, velocity.toFloat(), seed, model)
            }
        }
        fun fromJsonText(text: String): FerryPatch = fromJsonValue(Json.parse(text)) as FerryPatch
    }
}
