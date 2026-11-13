package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved ETCH note: the root, the playing velocity and the model version travel beside the seven controls. TUNE
 * moves the saved root by plus or minus 12 semitones inside MIDI 24..60. Below HOLD .85 a patch is one TONAL note
 * under 1.5 s; from HOLD .85 it is the voice's sixteen-step line as a seamless two-bar LOOP. Velocity is the accent.
 */
data class EtchPatch(
    override val name: String,
    val voice: EtchVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Etch.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Etch.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Etch.macrosFor(voice))
        require(midi in Etch.MIDI_MIN..Etch.MIDI_MAX) { "ETCH midi out of ${Etch.MIDI_MIN}..${Etch.MIDI_MAX}: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "ETCH velocity out of 0..1: $velocity" }
        require(model == Etch.MODEL_VERSION) { "unsupported etch model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(velocity)

    /** Take [take] of this patch at [velocity] ([Restrike.take]); take 0 is the plain render. */
    fun render(velocity: Float, take: Int = 0, seed: Int = 0) = Etch.render(voice, macros, midi, velocity, take, seed)

    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "ETCH"
        const val VERSION = Patches.VERSION
        private const val ORIGINAL_MODEL = 1

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            // A missing model always means the first model, even after a future revision.
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: ORIGINAL_MODEL
            if (model != Etch.MODEL_VERSION) throw JsonException("unsupported etch model $model")
            return Patches.decode(value, ENGINE, { n -> EtchVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Etch.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                EtchPatch(name, voice, macros, midi, velocity, model)
            }
        }

        fun fromJsonText(text: String): EtchPatch = fromJsonValue(Json.parse(text)) as EtchPatch
    }
}
