package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Collections

/** Saved contour model and performance; caller-owned maps cannot mutate a recipe. */
class RimePatch(
    override val name: String,
    val voice: RimeVoice,
    macros: Map<String, Float> = Rime.defaults(voice),
    val midi: Int = Rime.rootMidi(voice),
    val velocity: Float = .8f,
    val gateSeconds: Float = Rime.gateSeconds(voice),
    val seed: Int = 1729,
    val model: Int = Rime.MODEL_VERSION,
) : Patch {
    override val macros: Map<String, Float> = Collections.unmodifiableMap(LinkedHashMap(macros))
    init {
        Patches.validateMacros(this, Rime.macrosFor(voice))
        Rime.settled(voice, macros)
        require(midi in Rime.MIN_MIDI..Rime.MAX_MIDI) { "RIME MIDI must be ${Rime.MIN_MIDI}..${Rime.MAX_MIDI}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "RIME velocity must be finite in 0..1" }
        require(gateSeconds.isFinite() && gateSeconds in .15f..4f) { "RIME gateSeconds must be .15..4" }
        require(model == Rime.MODEL_VERSION) { "unsupported RIME model $model" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Rime.render(voice, macros, midi, velocity, gateSeconds, seed)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    fun copy(name: String = this.name, voice: RimeVoice = this.voice, macros: Map<String, Float> = this.macros,
        midi: Int = this.midi, velocity: Float = this.velocity, gateSeconds: Float = this.gateSeconds,
        seed: Int = this.seed, model: Int = this.model) = RimePatch(name, voice, macros, midi, velocity, gateSeconds, seed, model)
    override fun toJsonValue() = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).apply {
        put("model", JsonValue.Num(model.toDouble())); put("midi", JsonValue.Num(midi.toDouble()))
        put("velocity", JsonValue.Num(velocity.toDouble())); put("gateSeconds", JsonValue.Num(gateSeconds.toDouble()))
        put("seed", JsonValue.Num(seed.toDouble()))
    })
    override fun equals(other: Any?) = other is RimePatch && name == other.name && voice == other.voice && macros == other.macros &&
        midi == other.midi && velocity == other.velocity && gateSeconds == other.gateSeconds && seed == other.seed && model == other.model
    override fun hashCode() = listOf(name, voice, macros, midi, velocity, gateSeconds, seed, model).hashCode()
    companion object {
        const val ENGINE = "RIME"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): RimePatch {
            try {
                val fields = value.obj()
                fun field(key: String) = fields[key] ?: throw JsonException("RIME patch has no $key")
                return Patches.decode(value, ENGINE, { n -> RimeVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                    RimePatch(name, voice, macros, field("midi").int(), field("velocity").num().toFloat(),
                        field("gateSeconds").num().toFloat(), field("seed").int(), field("model").int())
                } as RimePatch
            } catch (error: IllegalArgumentException) { throw JsonException(error.message ?: "invalid RIME patch") }
        }
        fun fromJsonText(text: String) = fromJsonValue(Json.parse(text))
    }
}
