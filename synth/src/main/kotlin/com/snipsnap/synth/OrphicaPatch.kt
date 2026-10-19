package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete microsound-harp note. The saved MIDI root, material, mode, physical
 * grain settings and source seed survive macro edits and regenerate together.
 * TUNE is a semitone offset around that root, with .5 as its neutral position.
 */
data class OrphicaPatch(
    override val name: String,
    val config: OrphicaConfig = OrphicaConfig(),
    override val macros: Map<String, Float> = emptyMap(),
    val model: Int = Orphica.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Orphica.macrosFor(config.material))
        require(model == Orphica.MODEL_VERSION) { "unsupported orphica model $model" }
    }

    override val engine get() = ENGINE
    /** Material is the saved voice enum used by the shared engine registry. */
    val voice get() = config.material
    override val voiceName get() = config.material.name
    override fun render() = Orphica.render(Orphica.configWithMacros(config, macros))
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["config"] = encodeConfig(config, (macros["HOLD"] ?: config.hold) > 0f)
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "ORPHICA"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = Orphica.MODEL_VERSION

        fun fromJsonValue(value: JsonValue): OrphicaPatch = try {
            Patches.decode(value, ENGINE, { n -> OrphicaMaterial.entries.firstOrNull { it.name == n } }) { name, material, macros ->
                val obj = value.obj()
                val model = required(obj, "model").int()
                if (model != MODEL_VERSION) throw JsonException("unsupported orphica model $model")
                val config = decodeConfig(required(obj, "config"))
                if (config.material != material) throw JsonException("ORPHICA voice $material differs from config material ${config.material}")
                OrphicaPatch(name, config, macros, model)
            } as OrphicaPatch
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid ORPHICA patch")
        }

        fun fromJsonText(text: String): OrphicaPatch = fromJsonValue(Json.parse(text))

        private fun number(value: Float) = JsonValue.Num(value.toDouble())

        private fun encodeConfig(c: OrphicaConfig, sustained: Boolean): JsonValue.Obj {
            val fields = linkedMapOf(
                "material" to JsonValue.Str(c.material.name), "mode" to JsonValue.Str(c.mode.name),
                "midi" to JsonValue.Num(c.midi.toDouble()), "velocity" to number(c.velocity),
                "pluck" to number(c.pluck), "fracture" to number(c.fracture),
                "grainSizeMs" to number(c.grainSizeMs), "grainRateHz" to number(c.grainRateHz),
                "scatter" to number(c.scatter), "damping" to number(c.damping),
                "seconds" to number(c.seconds),
                // JSON numbers are doubles in this codebase; a string preserves all 64 seed bits.
                "seed" to JsonValue.Str(c.seed.toString()), "captureSeconds" to number(c.captureSeconds),
            )
            // Missing fields are the original approved finite recipe. Keep those
            // JSON bytes unchanged while explicitly versioning captured sustain.
            if (c.hold != 0f || sustained) fields["hold"] = number(c.hold)
            if (c.hold != 0f || sustained || c.sustainModel != Orphica.SUSTAIN_MODEL_VERSION)
                fields["sustainModel"] = JsonValue.Num(c.sustainModel.toDouble())
            return JsonValue.Obj(fields)
        }

        private fun required(obj: Map<String, JsonValue>, key: String): JsonValue =
            obj[key] ?: throw JsonException("ORPHICA recipe has no $key")

        private fun float(obj: Map<String, JsonValue>, key: String): Float {
            val result = required(obj, key).num().toFloat()
            if (!result.isFinite()) throw JsonException("ORPHICA $key must be finite")
            return result
        }

        private inline fun <reified E : Enum<E>> enum(obj: Map<String, JsonValue>, key: String): E {
            val name = required(obj, key).str()
            return enumValues<E>().firstOrNull { it.name == name }
                ?: throw JsonException("unknown ORPHICA $key $name")
        }

        private fun decodeConfig(value: JsonValue): OrphicaConfig {
            val obj = value.obj()
            val seed = required(obj, "seed").str().toLongOrNull()
                ?: throw JsonException("ORPHICA seed must be a decimal 64-bit integer string")
            return OrphicaConfig(
                material = enum<OrphicaMaterial>(obj, "material"),
                mode = enum<OrphicaMode>(obj, "mode"), midi = required(obj, "midi").int(),
                velocity = float(obj, "velocity"), pluck = float(obj, "pluck"),
                fracture = float(obj, "fracture"), grainSizeMs = float(obj, "grainSizeMs"),
                grainRateHz = float(obj, "grainRateHz"), scatter = float(obj, "scatter"),
                damping = float(obj, "damping"), seconds = float(obj, "seconds"),
                seed = seed, captureSeconds = float(obj, "captureSeconds"),
                hold = obj["hold"]?.num()?.toFloat() ?: 0f,
                sustainModel = obj["sustainModel"]?.int() ?: Orphica.SUSTAIN_MODEL_VERSION,
            )
        }
    }
}
