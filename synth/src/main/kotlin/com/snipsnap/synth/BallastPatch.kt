package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A saved BALLAST instrument state, including its requested root and strike velocity. */
data class BallastPatch(
    override val name: String,
    val voice: BallastVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Ballast.DEFAULT_MIDI,
    val velocity: Float = 1f,
) : Patch {
    init {
        Patches.validateMacros(this, Ballast.macrosFor(voice))
        require(midi in 24..72) { "BALLAST pitch is MIDI 24..72, got $midi" }
        require(velocity in 0f..1f) { "BALLAST velocity is outside 0..1: $velocity" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Ballast.render(voice, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "BALLAST"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> BallastVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val midi = value.obj()["midi"]?.int() ?: Ballast.DEFAULT_MIDI
                val velocity = value.obj()["velocity"]?.num()?.toFloat() ?: 1f
                try {
                    BallastPatch(name, voice, macros, midi, velocity)
                } catch (e: IllegalArgumentException) {
                    throw JsonException(e.message ?: "invalid BALLAST patch")
                }
            }

        fun fromJsonText(text: String): BallastPatch = fromJsonValue(Json.parse(text)) as BallastPatch
    }
}
