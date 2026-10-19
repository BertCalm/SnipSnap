package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A dry TRIO scene. Note, playing velocity and the seeded gesture travel with
 * the controls; every render rebuilds its players rather than saving DSP state.
 * The shared patch version describes JSON, while config.model selects synthesis.
 */
data class TrioPatch(
    override val name: String,
    val voice: TrioVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Trio.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val config: TrioConfig = TrioConfig(),
) : Patch {
    init {
        Patches.validateMacros(this, Trio.macrosFor(voice))
        require(midi in Trio.MIN_MIDI..Trio.MAX_MIDI) {
            "TRIO midi out of ${Trio.MIN_MIDI}..${Trio.MAX_MIDI}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "TRIO velocity out of 0..1: $velocity" }
        require(config.model in Trio.LEGACY_MODEL_VERSION..Trio.MODEL_VERSION) { "unsupported TRIO model ${config.model}" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    val model get() = config.model
    override fun render() = Trio.render(voice, macros, midi, velocity, config)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["config"] = JsonValue.Obj(linkedMapOf(
            "model" to JsonValue.Num(config.model.toDouble()),
            "seed" to JsonValue.Num(config.seed.toDouble()),
            "tempoBpm" to JsonValue.Num(config.tempoBpm.toDouble()),
            "beats" to JsonValue.Num(config.beats.toDouble()),
            "press" to JsonValue.Num(config.press.toDouble()),
            "tremoloHz" to JsonValue.Num(config.tremoloHz.toDouble()),
            "strumMs" to JsonValue.Num(config.strumMs.toDouble()),
        ))
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "TRIO"
        const val VERSION = Patches.VERSION
        private val CONFIG_FIELDS = setOf("model", "seed", "tempoBpm", "beats", "press", "tremoloHz", "strumMs")

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { n -> TrioVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Trio.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                val config = obj["config"]?.takeUnless { it is JsonValue.Null }?.let(::readConfig)
                    ?: TrioConfig(model = Trio.LEGACY_MODEL_VERSION)
                TrioPatch(name, voice, macros, midi, velocity, config)
            }
        }

        private fun readConfig(value: JsonValue): TrioConfig {
            val fields = value.obj()
            val unknown = fields.keys - CONFIG_FIELDS
            if (unknown.isNotEmpty()) throw JsonException("unknown TRIO config fields: $unknown")
            val defaults = TrioConfig(model = Trio.LEGACY_MODEL_VERSION)
            fun present(key: String) = fields[key]?.takeUnless { it is JsonValue.Null }
            val model = present("model")?.int() ?: defaults.model
            if (model !in Trio.LEGACY_MODEL_VERSION..Trio.MODEL_VERSION) throw JsonException("unsupported TRIO model $model")
            return TrioConfig(
                seed = present("seed")?.int() ?: defaults.seed,
                tempoBpm = present("tempoBpm")?.num()?.toFloat() ?: defaults.tempoBpm,
                beats = present("beats")?.int() ?: defaults.beats,
                press = present("press")?.num()?.toFloat() ?: defaults.press,
                tremoloHz = present("tremoloHz")?.num()?.toFloat() ?: defaults.tremoloHz,
                strumMs = present("strumMs")?.num()?.toFloat() ?: defaults.strumMs,
                model = model,
            )
        }

        fun fromJsonText(text: String): TrioPatch = fromJsonValue(Json.parse(text)) as TrioPatch
    }
}
