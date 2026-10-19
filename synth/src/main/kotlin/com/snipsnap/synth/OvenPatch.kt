package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved OVEN strike: the requested root, playing velocity and material model
 * travel beside the six controls. TUNE adjusts the saved root by ±12 semitones;
 * the body, strings and hammer are rebuilt from this recipe on every render.
 * Each patch describes one finite note, with its own baked resonance tail.
 */
data class OvenPatch(
    override val name: String,
    val voice: OvenVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Oven.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Oven.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Oven.macrosFor(voice))
        require(midi in Oven.MIDI_MIN..Oven.MIDI_MAX) {
            "OVEN midi out of ${Oven.MIDI_MIN}..${Oven.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "OVEN velocity out of 0..1: $velocity" }
        require(model == Oven.MODEL_VERSION) { "unsupported oven model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Oven.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "OVEN"
        const val VERSION = Patches.VERSION
        private const val ORIGINAL_MODEL = 1

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            // A missing model always means the first model, even after a future revision.
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: ORIGINAL_MODEL
            if (model != Oven.MODEL_VERSION) throw JsonException("unsupported oven model $model")
            return Patches.decode(value, ENGINE, { n -> OvenVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Oven.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                OvenPatch(name, voice, macros, midi, velocity, model)
            }
        }

        fun fromJsonText(text: String): OvenPatch = fromJsonValue(Json.parse(text)) as OvenPatch
    }
}
