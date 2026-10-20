package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * Portable recipe for the static circular CAUSTIC listening prototype.
 *
 * This deliberately is not a [Patch]: the first experiment has a requested
 * MIDI note and SOURCE/FOCUS, with no factory-engine or phone-picker promise.
 * Every preparation/render input is stored; derived paths and modal states
 * are rebuilt by [Caustic]. Diagnostic variants are explicit in the recipe.
 */
data class CausticRecipe(
    val config: Caustic.Config = Caustic.Config(),
    val variant: Caustic.Mode = Caustic.Mode.FULL,
) {
    init {
        require(config.model != 1 || config.character == Caustic.Character.BODY) {
            "CAUSTIC model 1 has no selectable character"
        }
        require(config.model >= 3 || config.bloom == 0.0) { "CAUSTIC older models have no BLOOM control" }
        require(config.model != 3 || config.character == Caustic.Character.ALLOY) { "CAUSTIC model 3 requires ALLOY" }
    }

    fun inspect(cancelled: () -> Boolean = { false }): Caustic.Rendered =
        Caustic.inspect(config, variant, cancelled)

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "format" to JsonValue.Str(FORMAT),
        "version" to JsonValue.Num(config.model.toDouble()),
        "model" to JsonValue.Num(config.model.toDouble()),
        "variant" to JsonValue.Str(variant.name),
        "configuration" to JsonValue.Obj(linkedMapOf<String, JsonValue>(
            "source" to JsonValue.Num(config.source),
            "focus" to JsonValue.Num(config.focus),
            "midi" to JsonValue.Num(config.midi.toDouble()),
            "velocity" to JsonValue.Num(config.velocity),
            "seconds" to JsonValue.Num(config.seconds),
            "aperture" to JsonValue.Num(config.aperture),
            "delayCycles" to JsonValue.Num(config.delayCycles),
            "rayCount" to JsonValue.Num(config.rayCount.toDouble()),
        ).also {
            if (config.model >= 2) it["character"] = JsonValue.Str(config.character.name)
            if (config.model >= 3) it["bloom"] = JsonValue.Num(config.bloom)
        }),
    ))

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val FORMAT = "snipsnap.caustic.static-prototype"
        const val VERSION = 3

        fun fromJsonText(text: String): CausticRecipe = fromJsonValue(Json.parse(text))

        fun fromJsonValue(value: JsonValue): CausticRecipe {
            val root = value.obj()
            requireKeys(root, setOf("format", "version", "model", "variant", "configuration"), "recipe")
            if (root.getValue("format").str() != FORMAT) throw JsonException("not a CAUSTIC static prototype recipe")
            val version = root.getValue("version").int()
            val model = root.getValue("model").int()
            if (version !in 1..VERSION || version != model) throw JsonException("unsupported CAUSTIC recipe version/model $version/$model")
            val variantName = root.getValue("variant").str()
            val variant = Caustic.Mode.entries.firstOrNull { it.name == variantName }
                ?: throw JsonException("unknown CAUSTIC diagnostic variant $variantName")
            val c = root.getValue("configuration").obj()
            val configurationKeys = mutableSetOf("source", "focus", "midi", "velocity", "seconds", "aperture", "delayCycles", "rayCount")
            if (model >= 2) configurationKeys += "character"
            if (model >= 3) configurationKeys += "bloom"
            requireKeys(c, configurationKeys, "configuration")
            val character = if (model >= 2) {
                val name = c.getValue("character").str()
                Caustic.Character.entries.firstOrNull { it.name == name }
                    ?: throw JsonException("unknown CAUSTIC character $name")
            } else Caustic.Character.BODY
            return CausticRecipe(Caustic.Config(
                source = c.getValue("source").num(),
                focus = c.getValue("focus").num(),
                midi = c.getValue("midi").int(),
                velocity = c.getValue("velocity").num(),
                seconds = c.getValue("seconds").num(),
                aperture = c.getValue("aperture").num(),
                delayCycles = c.getValue("delayCycles").num(),
                rayCount = c.getValue("rayCount").int(),
                model = model,
                character = character,
                bloom = if (model >= 3) c.getValue("bloom").num() else 0.0,
            ), variant)
        }

        private fun requireKeys(values: Map<String, JsonValue>, expected: Set<String>, label: String) {
            if (values.keys != expected) throw JsonException(
                "CAUSTIC $label fields differ: missing ${expected - values.keys}, unknown ${values.keys - expected}",
            )
        }
    }
}
