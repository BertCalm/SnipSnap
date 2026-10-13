package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * Versioned standalone audition recipe. It intentionally does not implement [Patch]: listening
 * comes before app/kit/export registration. All macro defaults are saved in full for replay.
 */
data class RegistersPatch(
    val name: String,
    val voice: RegistersVoice,
    val macros: Map<String, Float>,
    val midi: Int = Registers.midiFor(macros["TUNE"] ?: .5f),
    val velocity: Float = 1f,
    val model: Int = Registers.MODEL_VERSION,
) {
    init {
        require(model == Registers.MODEL_VERSION) { "unsupported REGISTERS model $model" }
        require(midi in 24..96) { "REGISTERS MIDI must be in 24..96" }
        require(velocity.isFinite() && velocity in 0f..1f) { "REGISTERS velocity must be in 0..1" }
        val defaults = Registers.defaults(voice)
        require(macros.keys.all { it in defaults }) { "unknown REGISTERS macro" }
        require(macros.values.all { it.isFinite() && it in 0f..1f }) { "REGISTERS macros must be finite in 0..1" }
    }

    fun render() = Registers.render(voice, macros, midi, velocity)
    fun probe() = Registers.probe(voice, macros, midi, velocity)

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "format" to JsonValue.Str(FORMAT),
        "version" to JsonValue.Num(VERSION.toDouble()),
        "engine" to JsonValue.Str(ENGINE),
        "model" to JsonValue.Num(model.toDouble()),
        "name" to JsonValue.Str(name),
        "voice" to JsonValue.Str(voice.name),
        "midi" to JsonValue.Num(midi.toDouble()),
        "velocity" to JsonValue.Num(velocity.toDouble()),
        "macros" to JsonValue.Obj(Registers.settled(voice, macros).mapValues { JsonValue.Num(it.value.toDouble()) }),
    ))

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val ENGINE = "REGISTERS"
        const val FORMAT = "snipsnap-audition-patch"
        const val VERSION = 1

        fun fromJsonValue(value: JsonValue): RegistersPatch {
            val obj = value.obj()
            fun field(name: String): JsonValue = obj[name] ?: throw JsonException("missing REGISTERS $name")
            if (field("format").str() != FORMAT) throw JsonException("not a REGISTERS audition recipe")
            if (field("engine").str() != ENGINE) throw JsonException("unexpected REGISTERS engine")
            if (field("version").int() != VERSION) throw JsonException("unsupported REGISTERS recipe version")
            val model = field("model").int()
            if (model != Registers.MODEL_VERSION) throw JsonException("unsupported REGISTERS model $model")
            val voiceName = field("voice").str()
            val voice = RegistersVoice.entries.firstOrNull { it.name == voiceName }
                ?: throw JsonException("unknown REGISTERS voice $voiceName")
            return RegistersPatch(field("name").str(), voice,
                field("macros").obj().mapValues { it.value.num().toFloat() },
                field("midi").int(), field("velocity").num().toFloat(), model)
        }

        fun fromJsonText(text: String): RegistersPatch = fromJsonValue(Json.parse(text))
    }
}
