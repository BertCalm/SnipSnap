package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A complete dry ensemble recipe, including the observer paths and performer seed. */
data class RevelPatch(
    override val name: String,
    val voice: RevelVoice,
    override val macros: Map<String, Float>,
    val configuration: RevelConfig = RevelConfig(),
    val velocity: Float = 1f,
) : Patch {
    init {
        Patches.validateMacros(this, Revel.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "REVEL velocity out of 0..1: $velocity" }
        val count = configuration.micCount ?: Revel.defaultMicCount(voice)
        for ((field, size) in listOf(
            "trajectories" to configuration.trajectories.size,
            "phaseOffsets" to configuration.phaseOffsets.size,
            "directions" to configuration.directions.size,
            "speedRatios" to configuration.speedRatios.size,
        )) require(size == 0 || size == count) { "REVEL $field needs $count entries, got $size" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Revel.render(voice, macros, velocity = velocity, configuration = configuration)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["configuration"] = JsonValue.Obj(linkedMapOf(
            "micCount" to (configuration.micCount?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "phraseTempo" to JsonValue.Num(configuration.phraseTempo.toDouble()),
            "phraseBeats" to JsonValue.Num(configuration.phraseBeats.toDouble()),
            "trajectories" to JsonValue.Arr(configuration.trajectories.map { JsonValue.Str(it.name) }),
            "phaseOffsets" to JsonValue.Arr(configuration.phaseOffsets.map { JsonValue.Num(it.toDouble()) }),
            "directions" to JsonValue.Arr(configuration.directions.map { JsonValue.Num(it.toDouble()) }),
            "speedRatios" to JsonValue.Arr(configuration.speedRatios.map { JsonValue.Num(it.toDouble()) }),
            // JsonValue.Num is a Double: a string preserves every bit of an arbitrary Long seed.
            "seed" to JsonValue.Str(configuration.seed.toString()),
        ))
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "REVEL"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> RevelVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                try {
                    val obj = value.obj()
                    val config = obj["configuration"]?.let { raw ->
                        val fields = raw.obj()
                        RevelConfig(
                            micCount = fields["micCount"]?.takeUnless { it is JsonValue.Null }?.int(),
                            phraseTempo = fields["phraseTempo"]?.num()?.toFloat() ?: 104f,
                            phraseBeats = fields["phraseBeats"]?.int() ?: 4,
                            trajectories = fields["trajectories"]?.arr()?.map { item ->
                                val trajectory = item.str()
                                RevelTrajectory.entries.firstOrNull { it.name == trajectory }
                                    ?: throw JsonException("unknown REVEL trajectory $trajectory")
                            } ?: emptyList(),
                            phaseOffsets = fields["phaseOffsets"]?.arr()?.map { it.num().toFloat() } ?: emptyList(),
                            directions = fields["directions"]?.arr()?.map { it.int() } ?: emptyList(),
                            speedRatios = fields["speedRatios"]?.arr()?.map { it.num().toFloat() } ?: emptyList(),
                            seed = fields["seed"]?.let { readSeed(it) } ?: 0L,
                        )
                    } ?: RevelConfig()
                    val velocity = obj["velocity"]?.num()?.toFloat() ?: 1f
                    RevelPatch(name, voice, macros, config, velocity)
                } catch (e: IllegalArgumentException) {
                    throw JsonException(e.message ?: "invalid REVEL patch")
                }
            }

        private fun readSeed(value: JsonValue): Long = when (value) {
            is JsonValue.Str -> value.value.toLongOrNull() ?: throw JsonException("invalid REVEL seed ${value.value}")
            is JsonValue.Num -> {
                if (!value.value.isFinite() || kotlin.math.abs(value.value) > 9_007_199_254_740_991.0) {
                    throw JsonException("REVEL numeric seed must be an exact safe integer; use a string for a Long seed")
                }
                value.long()
            }
            else -> throw JsonException("REVEL seed needs an integer string")
        }

        fun fromJsonText(text: String): RevelPatch = fromJsonValue(Json.parse(text)) as RevelPatch
    }
}
