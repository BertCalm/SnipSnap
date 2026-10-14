package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** Reproducible finite attractor passage, including its integration model and starting history. */
data class VentPatch(
    override val name: String,
    val voice: VentVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Vent.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val seed: Int = 0,
    val model: Int = Vent.MODEL,
) : Patch {
    init {
        Patches.validateMacros(this, Vent.macrosFor(voice))
        require(midi in Vent.MIDI_MIN..Vent.MIDI_MAX) { "VENT MIDI must be ${Vent.MIDI_MIN}..${Vent.MIDI_MAX}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "VENT velocity must be 0..1" }
        require(Vent.supportsModel(model)) { "unsupported VENT model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Vent.render(voice, macros, midi, velocity, seed, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
            it["seed"] = JsonValue.Num(seed.toDouble())
            it["model"] = JsonValue.Num(model.toDouble())
        },
    )

    companion object {
        const val ENGINE = "VENT"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Vent.DEFAULT_MIDI
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            val seed = obj["seed"]?.takeUnless { it is JsonValue.Null }?.int() ?: 0
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Vent.MODEL
            return Patches.decode(value, ENGINE, { name -> VentVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                VentPatch(name, voice, macros, midi, velocity, seed, model)
            }
        }

        fun fromJsonText(text: String): VentPatch = fromJsonValue(Json.parse(text)) as VentPatch
    }
}
