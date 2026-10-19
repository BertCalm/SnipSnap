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
    fun inspect(cancelled: () -> Boolean = { false }): Caustic.Rendered =
        Caustic.inspect(config, variant, cancelled)

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "format" to JsonValue.Str(FORMAT),
        "version" to JsonValue.Num(VERSION.toDouble()),
        "model" to JsonValue.Num(config.model.toDouble()),
        "variant" to JsonValue.Str(variant.name),
        "configuration" to JsonValue.Obj(linkedMapOf(
            "source" to JsonValue.Num(config.source),
            "focus" to JsonValue.Num(config.focus),
            "midi" to JsonValue.Num(config.midi.toDouble()),
            "velocity" to JsonValue.Num(config.velocity),
            "seconds" to JsonValue.Num(config.seconds),
            "aperture" to JsonValue.Num(config.aperture),
            "delayCycles" to JsonValue.Num(config.delayCycles),
            "rayCount" to JsonValue.Num(config.rayCount.toDouble()),
        )),
    ))

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val FORMAT = "snipsnap.caustic.static-prototype"
        const val VERSION = 1

        fun fromJsonText(text: String): CausticRecipe = fromJsonValue(Json.parse(text))

        fun fromJsonValue(value: JsonValue): CausticRecipe {
            val root = value.obj()
            requireKeys(root, setOf("format", "version", "model", "variant", "configuration"), "recipe")
            if (root.getValue("format").str() != FORMAT) throw JsonException("not a CAUSTIC static prototype recipe")
            if (root.getValue("version").int() != VERSION) throw JsonException("unsupported CAUSTIC recipe version")
            val model = root.getValue("model").int()
            val variantName = root.getValue("variant").str()
            val variant = Caustic.Mode.entries.firstOrNull { it.name == variantName }
                ?: throw JsonException("unknown CAUSTIC diagnostic variant $variantName")
            val c = root.getValue("configuration").obj()
            requireKeys(c, setOf("source", "focus", "midi", "velocity", "seconds", "aperture", "delayCycles", "rayCount"), "configuration")
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
            ), variant)
        }

        private fun requireKeys(values: Map<String, JsonValue>, expected: Set<String>, label: String) {
            if (values.keys != expected) throw JsonException(
                "CAUSTIC $label fields differ: missing ${expected - values.keys}, unknown ${values.keys - expected}",
            )
        }
    }
}
