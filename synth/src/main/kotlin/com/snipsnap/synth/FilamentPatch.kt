package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete FILAMENT recipe. Geometry variation belongs to [config], so editing
 * a control or rendering another velocity does not replace the strand roster.
 * Prepared taps, delay history and resonator state are rebuilt on every render.
 * The saved model preserves the original material when a recipe is edited.
 */
data class FilamentPatch(
    override val name: String,
    val voice: FilamentVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Filament.rootMidi(voice),
    val velocity: Float = 1f,
    val config: FilamentConfig = FilamentConfig(),
    val model: Int = Filament.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Filament.macrosFor(voice))
        require(midi in Filament.MIN_MIDI..Filament.MAX_MIDI) {
            "FILAMENT midi out of ${Filament.MIN_MIDI}..${Filament.MAX_MIDI}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "FILAMENT velocity out of 0..1: $velocity" }
        require(model == Filament.ORIGINAL_MODEL || model == Filament.CHARACTER_MODEL || model == Filament.MODEL_VERSION) {
            "unsupported filament model $model"
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Filament.render(voice, macros, midi, velocity, config, model = model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also {
            it["model"] = JsonValue.Num(model.toDouble())
            it["midi"] = JsonValue.Num(midi.toDouble())
            it["velocity"] = JsonValue.Num(velocity.toDouble())
            it["config"] = JsonValue.Obj(linkedMapOf(
                "seed" to JsonValue.Num(config.seed.toDouble()),
                "profile" to JsonValue.Str(config.profile.name),
                "perfectRetention" to JsonValue.Bool(config.perfectRetention),
            ))
        },
    )

    companion object {
        const val ENGINE = "FILAMENT"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            fun optional(key: String) = obj[key]?.takeUnless { it is JsonValue.Null }
            // Omitted model always names the original rendering contract.
            val model = optional("model")?.int() ?: Filament.ORIGINAL_MODEL
            if (model != Filament.ORIGINAL_MODEL && model != Filament.CHARACTER_MODEL && model != Filament.MODEL_VERSION) {
                throw JsonException("unsupported filament model $model")
            }
            val configObject = optional("config")?.obj()
            fun configuration(key: String) = configObject?.get(key)?.takeUnless { it is JsonValue.Null }
            val profileName = configuration("profile")?.str() ?: FilamentProfile.VOICE.name
            val profile = FilamentProfile.entries.firstOrNull { it.name == profileName }
                ?: throw JsonException("unknown filament profile $profileName")
            val config = FilamentConfig(
                seed = configuration("seed")?.int() ?: 0,
                profile = profile,
                perfectRetention = configuration("perfectRetention")?.bool() ?: false,
            )
            val velocity = optional("velocity")?.num() ?: 1.0
            if (!velocity.isFinite() || velocity !in 0.0..1.0) throw JsonException("FILAMENT velocity out of 0..1: $velocity")
            // Check before narrowing to Float so values outside the bounds cannot round into them.
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("FILAMENT $key out of 0..1: $number")
            }
            return Patches.decode(value, ENGINE, { n -> FilamentVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                FilamentPatch(name, voice, macros, optional("midi")?.int() ?: Filament.rootMidi(voice), velocity.toFloat(), config, model)
            }
        }

        fun fromJsonText(text: String): FilamentPatch = fromJsonValue(Json.parse(text)) as FilamentPatch
    }
}
