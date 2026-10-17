package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete finite OSPREY sea gesture. The requested note, excitation velocity,
 * variation seed, duration and model travel beside the coastline macros so a
 * saved pad regenerates the sample that was auditioned. HOLD extends excitation;
 * it does not certify a repeating loop. New recipes use the current model;
 * older JSON without a model retains the first published renderer.
 */
data class OspreyPatch(
    override val name: String,
    val voice: OspreyVoice = OspreyVoice.SWELL,
    override val macros: Map<String, Float>,
    val midi: Int = Osprey.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val seed: Int = 0,
    val durationSeconds: Float = 8f,
    val modelVersion: Int = Osprey.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Osprey.macrosFor(voice))
        require(midi in Osprey.MIDI_MIN..Osprey.MIDI_MAX) {
            "OSPREY midi out of ${Osprey.MIDI_MIN}..${Osprey.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "OSPREY velocity out of 0..1: $velocity" }
        require(durationSeconds.isFinite() && durationSeconds in .25f..Osprey.MAX_SECONDS) {
            "OSPREY duration out of .25..${Osprey.MAX_SECONDS} seconds: $durationSeconds"
        }
        require(modelVersion in 1..Osprey.MODEL_VERSION) { "unsupported OSPREY model $modelVersion" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Osprey.render(voice, macros, midi, velocity, seed, durationSeconds, modelVersion = modelVersion)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["modelVersion"] = JsonValue.Num(modelVersion.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["seed"] = JsonValue.Num(seed.toDouble())
        obj["durationSeconds"] = JsonValue.Num(durationSeconds.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "OSPREY"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val rawMacros = value.obj()["macros"]
            if (rawMacros != null && rawMacros !is JsonValue.Null && rawMacros !is JsonValue.Obj) {
                throw JsonException("OSPREY macros must be an object")
            }
            return Patches.decode(value, ENGINE, { n -> OspreyVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val obj = value.obj()
                fun field(name: String) = obj[name]?.takeUnless { it is JsonValue.Null }
                try {
                    OspreyPatch(
                        name, voice, macros,
                        midi = field("midi")?.int() ?: Osprey.DEFAULT_MIDI,
                        velocity = field("velocity")?.num()?.toFloat() ?: 1f,
                        seed = field("seed")?.int() ?: 0,
                        durationSeconds = field("durationSeconds")?.num()?.toFloat() ?: 8f,
                        // Missing model means the first published recipe, even after future models exist.
                        modelVersion = field("modelVersion")?.int() ?: 1,
                    )
                } catch (e: IllegalArgumentException) {
                    throw JsonException(e.message ?: "invalid OSPREY recipe")
                }
            }
        }

        fun fromJsonText(text: String): OspreyPatch = fromJsonValue(Json.parse(text)) as OspreyPatch
    }
}
