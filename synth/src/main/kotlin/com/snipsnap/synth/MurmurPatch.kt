package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete dry FM/formant recipe. Root and excitation velocity live beside the six
 * macros; operator routing, ratios, mouth targets, trajectory, envelopes and render
 * model are saved explicitly so a pad rebuilds the sound that was auditioned.
 */
data class MurmurPatch(
    override val name: String,
    val voice: MurmurVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Murmur.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val config: MurmurConfig = Murmur.configurationFor(voice),
) : Patch {
    init {
        Patches.validateMacros(this, Murmur.macrosFor(voice))
        require(midi in Murmur.MIN_MIDI..Murmur.MAX_MIDI) {
            "MURMUR midi out of ${Murmur.MIN_MIDI}..${Murmur.MAX_MIDI}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "MURMUR velocity out of 0..1: $velocity" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Murmur.render(voice, macros, midi, velocity, config)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["config"] = configToJson(config)
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "MURMUR"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> MurmurVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val obj = value.obj()
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Murmur.DEFAULT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                try {
                    val config = obj["config"]?.takeUnless { it is JsonValue.Null }?.let(::configFromJson)
                        ?: Murmur.configurationFor(voice, model = 1)
                    MurmurPatch(name, voice, macros, midi, velocity, config)
                } catch (e: IllegalArgumentException) {
                    throw JsonException(e.message ?: "invalid MURMUR recipe")
                }
            }

        fun fromJsonText(text: String): MurmurPatch = fromJsonValue(Json.parse(text)) as MurmurPatch

        private fun numbers(values: List<Double>) = JsonValue.Arr(values.map { JsonValue.Num(it) })

        private fun configToJson(config: MurmurConfig): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "routing" to JsonValue.Str(config.routing.name),
            "ratios" to numbers(config.ratios),
            "trajectory" to JsonValue.Str(config.trajectory.name),
            "direction" to JsonValue.Num(config.direction.toDouble()),
            "referenceSeconds" to JsonValue.Num(config.referenceSeconds),
            "attack" to JsonValue.Num(config.attack),
            "decay" to JsonValue.Num(config.decay),
            "sustain" to JsonValue.Num(config.sustain),
            "release" to JsonValue.Num(config.release),
            "targets" to JsonValue.Arr(config.targets.map { target -> JsonValue.Obj(linkedMapOf(
                "name" to JsonValue.Str(target.name),
                "centers" to numbers(target.centers),
                "widths" to numbers(target.widths),
                "gains" to numbers(target.gains),
                "sourceBalance" to JsonValue.Num(target.sourceBalance),
            )) }),
            "model" to JsonValue.Num(config.model.toDouble()),
        ))

        private fun configFromJson(value: JsonValue): MurmurConfig {
            val obj = value.obj()
            fun field(name: String) = obj[name] ?: throw JsonException("MURMUR config has no $name")
            return MurmurConfig(
                routing = MurmurRouting.entries.firstOrNull { it.name == field("routing").str() }
                    ?: throw JsonException("unknown MURMUR routing"),
                ratios = field("ratios").arr().map { it.num() },
                trajectory = MurmurTrajectory.entries.firstOrNull { it.name == field("trajectory").str() }
                    ?: throw JsonException("unknown MURMUR trajectory"),
                direction = field("direction").int(),
                referenceSeconds = field("referenceSeconds").num(),
                attack = field("attack").num(),
                decay = field("decay").num(),
                sustain = field("sustain").num(),
                release = field("release").num(),
                targets = field("targets").arr().map { raw ->
                    val target = raw.obj()
                    fun targetField(name: String) = target[name] ?: throw JsonException("MURMUR target has no $name")
                    MurmurFormantTarget(
                        name = targetField("name").str(),
                        centers = targetField("centers").arr().map { it.num() },
                        widths = targetField("widths").arr().map { it.num() },
                        gains = targetField("gains").arr().map { it.num() },
                        sourceBalance = targetField("sourceBalance").num(),
                    )
                },
                // Existing saved definitions predate model selection and retain their original renderer.
                model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: 1,
            )
        }
    }
}
