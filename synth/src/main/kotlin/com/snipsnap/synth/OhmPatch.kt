package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** One foreground identity in this first audition. */
enum class OhmVoice { STRING }

/** A complete finite family encounter, with source randomness and performance saved explicitly. */
data class OhmPatch(
    override val name: String,
    override val macros: Map<String, Float>,
    val midi: Int = Ohm.ROOT_MIDI,
    val velocity: Float = 1f,
    val seed: Int = 1,
    val gesture: OhmGesture = OhmGesture.STEADY,
    val model: Int = Ohm.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Ohm.MACROS)
        require(midi in 48..72) { "OHM midi out of 48..72: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OHM velocity out of 0..1: $velocity" }
        require(Ohm.supportsModel(model)) { "unsupported OHM model $model" }
    }

    override val engine get() = ENGINE
    val voice get() = OhmVoice.STRING
    override val voiceName get() = voice.name
    override fun render() = Ohm.render(macros, midi, velocity, seed, gesture, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
            it["seed"] = JsonValue.Num(seed.toDouble())
            it["gesture"] = JsonValue.Str(gesture.name)
        },
    )

    companion object {
        const val ENGINE = "OHM"

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { if (it == "STRING") it else null }) { name, _, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Ohm.ROOT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                val seed = obj["seed"]?.takeUnless { it is JsonValue.Null }?.int() ?: 1
                val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: 1
                val gestureName = obj["gesture"]?.takeUnless { it is JsonValue.Null }?.str() ?: "STEADY"
                val gesture = OhmGesture.entries.firstOrNull { it.name == gestureName }
                    ?: throw JsonException("unknown OHM gesture $gestureName")
                try { OhmPatch(name, macros, midi, velocity, seed, gesture, model) }
                catch (e: IllegalArgumentException) { throw JsonException(e.message ?: "invalid OHM patch") }
            }
        }

        fun fromJsonText(text: String): OhmPatch = fromJsonValue(Json.parse(text)) as OhmPatch
    }
}
