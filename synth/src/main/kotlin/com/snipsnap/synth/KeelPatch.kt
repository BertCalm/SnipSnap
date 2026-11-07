package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved KEEL note: the root, the playing velocity and the model version travel beside the seven controls.
 * TUNE moves the saved root by plus or minus 12 semitones inside MIDI 24..55; the string, its body and WEIGHT's
 * body are rebuilt from this recipe on every render. Each patch is one finite note, under 1.5 s, filed TONAL, or
 * from HOLD .85 the settled state as a 3 s loop, filed LOOP.
 */
data class KeelPatch(
    override val name: String,
    val voice: KeelVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Keel.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Keel.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Keel.macrosFor(voice))
        require(midi in Keel.MIDI_MIN..Keel.MIDI_MAX) {
            "KEEL midi out of ${Keel.MIDI_MIN}..${Keel.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "KEEL velocity out of 0..1: $velocity" }
        require(model == Keel.MODEL_VERSION) { "unsupported keel model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Keel.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "KEEL"
        const val VERSION = Patches.VERSION
        private const val ORIGINAL_MODEL = 1

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            // A missing model always means the first model, even after a future revision.
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: ORIGINAL_MODEL
            if (model != Keel.MODEL_VERSION) throw JsonException("unsupported keel model $model")
            return Patches.decode(value, ENGINE, { n -> KeelVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Keel.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                KeelPatch(name, voice, macros, midi, velocity, model)
            }
        }

        fun fromJsonText(text: String): KeelPatch = fromJsonValue(Json.parse(text)) as KeelPatch
    }
}
