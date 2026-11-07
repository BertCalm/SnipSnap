package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * Meridian's complete recipe. The saved model preserves the original finite
 * gesture; model 2 adds fixed-alignment sustain at HOLD's top step. Missing model
 * metadata means model 1, so old recipes retain their samples and finite ending.
 * A macro edit keeps the saved note, velocity, model and material-noise identity.
 */
data class MeridianPatch(
    override val name: String,
    val voice: MeridianVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Meridian.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Meridian.MODEL_VERSION,
    val seedContext: Int = 0,
) : Patch {
    init {
        Patches.validateMacros(this, Meridian.macrosFor(voice))
        require(midi in Meridian.MIDI_MIN..Meridian.MIDI_MAX) {
            "MERIDIAN midi out of ${Meridian.MIDI_MIN}..${Meridian.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) {
            "MERIDIAN velocity out of 0..1: $velocity"
        }
        require(model in LEGACY_MODEL..Meridian.MODEL_VERSION) { "unsupported meridian model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Meridian.render(voice, macros, midi, velocity, seedContext, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["seedContext"] = JsonValue.Num(seedContext.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "MERIDIAN"
        const val VERSION = Patches.VERSION
        const val LEGACY_MODEL = 1

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> MeridianVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val obj = value.obj()
                val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: LEGACY_MODEL
                if (model !in LEGACY_MODEL..Meridian.MODEL_VERSION) throw JsonException("unsupported meridian model $model")
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Meridian.DEFAULT_MIDI
                if (midi !in Meridian.MIDI_MIN..Meridian.MIDI_MAX) {
                    throw JsonException("MERIDIAN midi out of ${Meridian.MIDI_MIN}..${Meridian.MIDI_MAX}: $midi")
                }
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                if (!velocity.isFinite() || velocity !in 0f..1f) {
                    throw JsonException("MERIDIAN velocity out of 0..1: $velocity")
                }
                val seed = obj["seedContext"]?.takeUnless { it is JsonValue.Null }?.int() ?: 0
                MeridianPatch(name, voice, macros, midi, velocity, model, seed)
            }

        fun fromJsonText(text: String): MeridianPatch = fromJsonValue(Json.parse(text)) as MeridianPatch
    }
}
