package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * One finite evolving note (or an explicit legacy model-one phrase). Root and excitation velocity are independent of the six
 * macros; the players' starting shores, levels, motion seed, duration, room shore,
 * direction and renderer model are stored in full. Macro editing retains that recipe.
 * Role lists are ordered BASS, HARMONY, MELODY, RHYTHM.
 */
data class OsteriaPatch(
    override val name: String,
    val voice: OsteriaVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Osteria.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val config: OsteriaConfig = Osteria.configurationFor(voice),
    /** Null retains the exact historic single-note recipe and renderer. */
    val strum: OsteriaStrum.Articulation? = null,
) : Patch {
    init {
        Patches.validateMacros(this, Osteria.macrosFor(voice))
        require(midi in Osteria.MIN_MIDI..Osteria.MAX_MIDI) {
            "OSTERIA midi out of ${Osteria.MIN_MIDI}..${Osteria.MAX_MIDI}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "OSTERIA velocity out of 0..1: $velocity" }
        require(voice == OsteriaVoice.QUARTET || config.model == 2) { "OSTERIA model 1 is only valid for QUARTET" }
        // The source remains a finite PLUCK at every HOLD position; a chord has one attack per tone.
        strum?.let {
            require(voice == OsteriaVoice.PLUCK && config.model == 2) { "STRUM requires fixed-root PLUCK" }
            it.notes(midi)
        }
    }

    val rootRange: IntRange get() = strum?.rootRange ?: (Osteria.MIN_MIDI..Osteria.MAX_MIDI)

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = if (strum == null) Osteria.render(voice, macros, midi, velocity, config)
        else OsteriaStrum.render(this)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = number(midi)
        obj["velocity"] = number(velocity)
        val savedConfig = linkedMapOf(
            "initialShores" to numbers(config.initialShores),
            "roleLevels" to numbers(config.roleLevels),
            "seed" to number(config.seed),
            "seconds" to number(config.seconds),
            "tavernShore" to number(config.tavernShore),
            "direction" to number(config.direction),
            "model" to number(config.model),
        )
        // Optional articulation extends existing model-two recipes without rewriting
        // their canonical JSON or changing how an older saved note sounds.
        if (config.attackSeconds != .035) savedConfig["attackSeconds"] = number(config.attackSeconds)
        if (config.releaseSeconds != .8) savedConfig["releaseSeconds"] = number(config.releaseSeconds)
        obj["config"] = JsonValue.Obj(savedConfig)
        strum?.let {
            obj["version"] = number(STRUM_VERSION)
            obj["strum"] = it.toJsonValue()
        }
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "OSTERIA"
        const val VERSION = Patches.VERSION
        const val STRUM_VERSION = 2

        private fun number(value: Number) = JsonValue.Num(value.toDouble())
        private fun numbers(values: List<Double>) = JsonValue.Arr(values.map(::number))

        /** Core saved state is required; optional articulation retains the original note defaults. */
        fun fromJsonValue(value: JsonValue): Patch = checked {
            val obj = value.obj()
            val version = required(obj, "version").int()
            val articulation = obj["strum"]?.takeUnless { it == JsonValue.Null }
            val strum = when (version) {
                VERSION -> {
                    if (articulation != null) throw JsonException("OSTERIA STRUM requires patch version 2")
                    null
                }
                STRUM_VERSION -> {
                    if (articulation == null) throw JsonException("OSTERIA version 2 requires STRUM")
                    if (obj.keys != setOf("engine", "version", "name", "voice", "macros", "midi", "velocity", "config", "strum"))
                        throw JsonException("Unknown or incomplete OSTERIA STRUM patch fields")
                    OsteriaStrum.fromJsonValue(articulation)
                }
                else -> throw JsonException("unsupported OSTERIA patch version $version")
            }
            // Shared decoding allows sparse maps. A saved map must still be an object,
            // and values are checked before Float conversion can round an invalid bound.
            val savedMacros = required(obj, "macros")
            if (savedMacros !is JsonValue.Obj) {
                throw JsonException("OSTERIA macros must be an object, got ${savedMacros::class.simpleName}")
            }
            savedMacros.entries.forEach { (name, raw) ->
                val v = raw.num()
                if (!v.isFinite() || v !in 0.0..1.0) throw JsonException("OSTERIA macro $name out of 0..1: $v")
            }
            val midi = required(obj, "midi").int()
            val rawVelocity = required(obj, "velocity").num()
            if (!rawVelocity.isFinite() || rawVelocity !in 0.0..1.0) {
                throw JsonException("OSTERIA velocity out of 0..1: $rawVelocity")
            }
            val configValue = required(obj, "config")
            if (version == STRUM_VERSION && !setOf("initialShores", "roleLevels", "seed", "seconds", "tavernShore", "direction", "model", "attackSeconds", "releaseSeconds").containsAll(configValue.obj().keys))
                throw JsonException("Unknown OSTERIA STRUM source configuration fields")
            val config = readConfig(configValue)
            // Dispatch reaches this codec before common version checking. Only this validated engine-specific
            // v2 shape normalizes the common-fields call; old builds still refuse v2 at their shared decoder.
            val common = if (version == STRUM_VERSION) JsonValue.Obj(obj + ("version" to number(VERSION))) else value
            Patches.decode(common, ENGINE, { n -> OsteriaVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                OsteriaPatch(name, voice, macros, midi, rawVelocity.toFloat(), config, strum)
            }
        }

        fun fromJsonText(text: String): OsteriaPatch = fromJsonValue(Json.parse(text)) as OsteriaPatch

        private fun readConfig(value: JsonValue): OsteriaConfig {
            val obj = value.obj()
            return OsteriaConfig(
                initialShores = required(obj, "initialShores").arr().map { it.num() },
                roleLevels = required(obj, "roleLevels").arr().map { it.num() },
                seed = required(obj, "seed").int(),
                seconds = required(obj, "seconds").num(),
                tavernShore = required(obj, "tavernShore").num(),
                direction = required(obj, "direction").int(),
                model = required(obj, "model").int(),
                attackSeconds = obj["attackSeconds"]?.num() ?: .035,
                releaseSeconds = obj["releaseSeconds"]?.num() ?: .8,
            )
        }

        private fun required(obj: Map<String, JsonValue>, key: String): JsonValue =
            obj[key] ?: throw JsonException("OSTERIA recipe has no $key")

        private inline fun <T> checked(build: () -> T): T = try {
            build()
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid OSTERIA recipe")
        }
    }
}
