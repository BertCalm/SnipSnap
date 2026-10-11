package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete layered-tone recipe. The profile is stored explicitly: editing a voice's
 * factory template later cannot silently change saved layer sources or articulation.
 * TUNE adjusts the saved root; note, event velocity and render model travel beside it.
 */
data class LacquerPatch(
    override val name: String,
    val voice: LacquerVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Lacquer.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val profile: LacquerProfile = Lacquer.profileFor(voice),
    val model: Int = MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Lacquer.macrosFor(voice))
        require(midi in Lacquer.MIDI_MIN..Lacquer.MIDI_MAX) { "LACQUER midi out of ${Lacquer.MIDI_MIN}..${Lacquer.MIDI_MAX}: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "LACQUER velocity out of 0..1: $velocity" }
        require(model == MODEL_VERSION) { "unsupported lacquer model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Lacquer.render(voice, macros, midi, velocity, profile)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = number(velocity)
        obj["profile"] = profileJson(profile)
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "LACQUER"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = 1

        fun fromJsonValue(value: JsonValue): LacquerPatch = try {
            Patches.decode(value, ENGINE, { n -> LacquerVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val obj = value.obj()
                val model = required(obj, "model").int()
                if (model != MODEL_VERSION) throw JsonException("unsupported lacquer model $model")
                LacquerPatch(
                    name, voice, macros,
                    midi = required(obj, "midi").int(),
                    velocity = float(obj, "velocity"),
                    profile = decodeProfile(required(obj, "profile")),
                    model = model,
                )
            } as LacquerPatch
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid LACQUER patch")
        }

        fun fromJsonText(text: String): LacquerPatch = fromJsonValue(Json.parse(text))

        private fun number(value: Float) = JsonValue.Num(value.toDouble())
        private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields))

        private fun profileJson(profile: LacquerProfile): JsonValue.Obj = obj(
            "layers" to JsonValue.Arr(profile.layers.map { layer ->
                obj(
                    "role" to JsonValue.Str(layer.role.name),
                    "source" to JsonValue.Str(layer.source.name),
                    "ratio" to number(layer.ratio),
                    "gain" to number(layer.gain),
                    "cutoffHz" to number(layer.cutoffHz),
                    "filterEnvelope" to number(layer.filterEnvelope),
                    "envelope" to obj(
                        "attackSeconds" to number(layer.envelope.attackSeconds),
                        "decaySeconds" to number(layer.envelope.decaySeconds),
                        "sustain" to number(layer.envelope.sustain),
                        "releaseSeconds" to number(layer.envelope.releaseSeconds),
                    ),
                    "timing" to obj(
                        "onsetSeconds" to number(layer.timing.onsetSeconds),
                        "reliefSeconds" to number(layer.timing.reliefSeconds),
                        "attackRelief" to number(layer.timing.attackRelief),
                        "decayRelief" to number(layer.timing.decayRelief),
                        "holdPhase" to number(layer.timing.holdPhase),
                    ),
                )
            }),
            "processing" to obj(
                "drive" to number(profile.processing.drive),
                "compression" to number(profile.processing.compression),
                "colorHz" to number(profile.processing.colorHz),
            ),
            "loop" to obj(
                "seconds" to number(profile.loop.seconds),
                "articulationDepth" to number(profile.loop.articulationDepth),
                "spectralDepth" to number(profile.loop.spectralDepth),
                "prerollCycles" to JsonValue.Num(profile.loop.prerollCycles.toDouble()),
            ),
        )

        private fun required(obj: Map<String, JsonValue>, key: String): JsonValue =
            obj[key] ?: throw JsonException("LACQUER recipe has no $key")

        private fun float(obj: Map<String, JsonValue>, key: String): Float {
            val result = required(obj, key).num().toFloat()
            if (!result.isFinite()) throw JsonException("LACQUER $key must be finite")
            return result
        }

        private inline fun <reified E : Enum<E>> enum(obj: Map<String, JsonValue>, key: String): E {
            val name = required(obj, key).str()
            return enumValues<E>().firstOrNull { it.name == name }
                ?: throw JsonException("unknown LACQUER $key $name")
        }

        private fun decodeProfile(value: JsonValue): LacquerProfile {
            val obj = value.obj()
            val layers = required(obj, "layers").arr().map { raw ->
                val layer = raw.obj()
                val envelope = required(layer, "envelope").obj()
                val timing = required(layer, "timing").obj()
                LacquerLayer(
                    role = enum<LacquerRole>(layer, "role"),
                    source = enum<LacquerSource>(layer, "source"),
                    ratio = float(layer, "ratio"),
                    gain = float(layer, "gain"),
                    cutoffHz = float(layer, "cutoffHz"),
                    filterEnvelope = float(layer, "filterEnvelope"),
                    envelope = LacquerEnvelope(
                        float(envelope, "attackSeconds"), float(envelope, "decaySeconds"),
                        float(envelope, "sustain"), float(envelope, "releaseSeconds"),
                    ),
                    timing = LacquerTiming(
                        float(timing, "onsetSeconds"), float(timing, "reliefSeconds"),
                        float(timing, "attackRelief"), float(timing, "decayRelief"), float(timing, "holdPhase"),
                    ),
                )
            }
            val processing = required(obj, "processing").obj()
            val loop = required(obj, "loop").obj()
            return LacquerProfile(
                layers,
                LacquerProcessing(float(processing, "drive"), float(processing, "compression"), float(processing, "colorHz")),
                LacquerLoopProfile(float(loop, "seconds"), float(loop, "articulationDepth"), float(loop, "spectralDepth"), required(loop, "prerollCycles").int()),
            )
        }
    }
}
