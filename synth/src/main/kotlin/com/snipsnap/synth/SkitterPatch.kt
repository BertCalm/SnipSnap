package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved circuit recipe. Root, excitation velocity and deterministic seed live
 * beside the six macros, so editing a knob preserves the note and noise history.
 * Filter states and recovery histories are rebuilt on each render.
 */
data class SkitterPatch(
    override val name: String,
    val voice: SkitterVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Skitter.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val seed: Int = 0,
) : Patch {
    init {
        Patches.validateMacros(this, Skitter.macrosFor(voice))
        require(midi in Skitter.MIDI_MIN..Skitter.MIDI_MAX) {
            "SKITTER midi out of ${Skitter.MIDI_MIN}..${Skitter.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "SKITTER velocity out of 0..1: $velocity" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Skitter.render(voice, macros, midi, velocity, seed)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["seed"] = JsonValue.Num(seed.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "SKITTER"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Skitter.DEFAULT_MIDI
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            val seed = obj["seed"]?.takeUnless { it is JsonValue.Null }?.int() ?: 0
            return Patches.decode(value, ENGINE, { n -> SkitterVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                SkitterPatch(name, voice, macros, midi, velocity, seed)
            }
        }

        fun fromJsonText(text: String): SkitterPatch = fromJsonValue(Json.parse(text)) as SkitterPatch
    }
}
