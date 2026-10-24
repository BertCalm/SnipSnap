package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A native directional-gate note. The explicit root, excitation velocity and render model
 * survive edits, kit storage and velocity-zone generation. The voice selects one of the
 * three accepted source profiles; HOLD and the gate geometry remain ordinary saved macros.
 */
data class LatticePatch(
    override val name: String,
    val voice: LatticeVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Lattice.DEFAULT_MIDI,
    val velocity: Float = .75f,
    val model: Int = MODEL_VERSION,
) : Patch {
    init {
        require(model == MODEL_VERSION) { "unsupported LATTICE model $model" }
        require(midi in Lattice.MIDI_MIN..Lattice.MIDI_MAX) {
            "LATTICE MIDI must be ${Lattice.MIDI_MIN}..${Lattice.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "LATTICE velocity must be finite in 0..1: $velocity" }
        Patches.validateMacros(this, Lattice.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Lattice.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>): LatticePatch = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
        },
    )

    companion object {
        const val ENGINE = "LATTICE"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = Lattice.MODEL_VERSION
        /** Missing native metadata always selects the first native model, never a future default. */
        private const val MISSING_MODEL_VERSION = 2

        fun fromJsonValue(value: JsonValue): LatticePatch = try {
            Patches.decode(value, ENGINE, { name -> LatticeVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                val obj = value.obj()
                val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: MISSING_MODEL_VERSION
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Lattice.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: .75f
                LatticePatch(name, voice, macros, midi, velocity, model)
            } as LatticePatch
        } catch (error: IllegalArgumentException) {
            throw JsonException(error.message ?: "invalid LATTICE patch")
        }

        fun fromJsonText(text: String): LatticePatch = fromJsonValue(Json.parse(text))
    }
}
