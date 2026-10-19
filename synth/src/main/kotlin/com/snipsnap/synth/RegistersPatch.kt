package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved REGISTERS sound, compatible with the original standalone audition recipes. The
 * explicit note, velocity, complete macro settings and model survive app/kit persistence.
 * Missing model metadata means legacy model 1, never the current default.
 */
data class RegistersPatch(
    override val name: String,
    val voice: RegistersVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Registers.midiFor(macros["TUNE"] ?: .5f),
    val velocity: Float = 1f,
    val model: Int = Registers.MODEL_VERSION,
) : Patch {
    init {
        require(Registers.supportsModel(model, voice)) { "unsupported REGISTERS $voice model $model" }
        require(midi in 24..96) { "REGISTERS MIDI must be in 24..96" }
        require(velocity.isFinite() && velocity in 0f..1f) { "REGISTERS velocity must be in 0..1" }
        val defaults = Registers.defaults(voice)
        require(macros.keys.all { it in defaults }) { "unknown REGISTERS macro" }
        require(macros.values.all { it.isFinite() && it in 0f..1f }) { "REGISTERS macros must be finite in 0..1" }
        Patches.validateMacros(this, Registers.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Registers.render(voice, macros, midi, velocity, model)
    fun probe() = Registers.probe(voice, macros, midi, velocity, model)

    /** A supplied TUNE edit changes pitch; unrelated edits retain the recipe's explicit note. */
    override fun withMacros(macros: Map<String, Float>): RegistersPatch {
        val previousTune = this.macros["TUNE"] ?: Registers.defaults(voice).getValue("TUNE")
        val tune = macros["TUNE"]
        val note = if (tune != null && tune != previousTune) Registers.midiFor(tune) else midi
        return copy(macros = macros, midi = note)
    }

    /**
     * Slider state for a saved explicit note. Old audition recipes could carry TUNE .5 beside
     * any MIDI zone; align the editor once so a later COLOR edit cannot silently retune it.
     * Notes beyond the slider's two octaves retain their exact pitch until TUNE is moved.
     */
    fun forEditing(): RegistersPatch = copy(macros = Registers.settled(voice, macros) +
        ("TUNE" to ((midi - Registers.ROOT_MIDI) / Registers.TUNE_SEMITONES.toFloat()).coerceIn(0f, 1f)))

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
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

    override fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val ENGINE = "REGISTERS"
        const val FORMAT = "snipsnap-audition-patch"
        const val VERSION = 1

        fun fromJsonValue(value: JsonValue): RegistersPatch {
            val obj = value.obj()
            fun field(name: String): JsonValue = obj[name] ?: throw JsonException("missing REGISTERS $name")
            if (obj["format"]?.str()?.let { it != FORMAT } == true) throw JsonException("not a REGISTERS recipe")
            if (field("engine").str() != ENGINE) throw JsonException("unexpected REGISTERS engine")
            if (field("version").int() != VERSION) throw JsonException("unsupported REGISTERS recipe version")
            val model = obj["model"]?.int() ?: Registers.LEGACY_MODEL_VERSION
            if (!Registers.supportsModel(model)) throw JsonException("unsupported REGISTERS model $model")
            val voiceName = field("voice").str()
            val voice = RegistersVoice.entries.firstOrNull { it.name == voiceName }
                ?: throw JsonException("unknown REGISTERS voice $voiceName")
            if (!Registers.supportsModel(model, voice)) throw JsonException("unsupported REGISTERS $voice model $model")
            val macros = when (val raw = obj["macros"]) {
                null, JsonValue.Null -> emptyMap()
                is JsonValue.Obj -> raw.entries.mapValues { it.value.num().toFloat() }
                else -> throw JsonException("patch macros must be an object, got ${raw::class.simpleName}")
            }
            val midi = obj["midi"]?.int() ?: Registers.midiFor(macros["TUNE"] ?: .5f)
            val velocity = obj["velocity"]?.num()?.toFloat() ?: 1f
            return RegistersPatch(field("name").str(), voice, macros, midi, velocity, model)
        }

        fun fromJsonText(text: String): RegistersPatch = fromJsonValue(Json.parse(text))
    }
}
