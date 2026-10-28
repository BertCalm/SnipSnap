package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * Source-independent live settings. The host supplies its current tempo separately.
 * All controls and the unsigned seed are resolved; no offline tail, fade or source
 * recipe is implied. Global bypass preserves the configured live processor's clock.
 * The policy identifies bounded transitions and the native stereo-linked sample
 * peak guard. Its actual ceiling is queried from the native host, not copied here.
 */
data class AquariumPerformanceSettings(
    val settings: AquariumRecipe = AquariumRecipe(),
    val enabled: Boolean = false,
    val outputGain: Float = 0.5f,
) {
    init {
        require(outputGain.isFinite() && outputGain in 0f..1f) {
            "Aquarium performance gain must be finite and in 0..1: $outputGain"
        }
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "aquariumPerformance" to JsonValue.Num(VERSION.toDouble()),
        "settings" to settings.toJsonValue(),
        "enabled" to JsonValue.Bool(enabled),
        "outputGain" to JsonValue.Num(outputGain.toDouble()),
        "transitionPolicy" to JsonValue.Str(TRANSITION_POLICY),
        "outputPolicy" to JsonValue.Str(OUTPUT_POLICY),
    ))

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val VERSION = 1
        const val TRANSITION_POLICY = "fade-wet-v1"
        const val OUTPUT_POLICY = "linked-sample-peak-v1"
        private val FIELDS = setOf("aquariumPerformance", "settings", "enabled", "outputGain", "transitionPolicy", "outputPolicy")

        fun fromJsonText(text: String): AquariumPerformanceSettings = fromJsonValue(Json.parse(text))

        fun fromJsonValue(value: JsonValue): AquariumPerformanceSettings {
            val obj = value.obj()
            val unknown = obj.keys - FIELDS
            if (unknown.isNotEmpty()) throw JsonException("unknown Aquarium performance fields: ${unknown.sorted().joinToString()}")
            val missing = FIELDS - obj.keys
            if (missing.isNotEmpty()) throw JsonException("missing Aquarium performance fields: ${missing.sorted().joinToString()}")
            if (obj.getValue("aquariumPerformance").int() != VERSION) throw JsonException("unsupported Aquarium performance version")
            if (obj.getValue("transitionPolicy").str() != TRANSITION_POLICY) throw JsonException("unsupported Aquarium performance transition policy")
            if (obj.getValue("outputPolicy").str() != OUTPUT_POLICY) throw JsonException("unsupported Aquarium performance output policy")
            val gain = obj.getValue("outputGain").num()
            // Validate Double before narrowing so a saved overflow cannot round into range.
            if (!gain.isFinite() || gain !in 0.0..1.0) throw JsonException("Aquarium performance gain must be finite and in 0..1")
            if (gain > 0.0 && gain.toFloat() == 0f) throw JsonException("Aquarium performance gain is too small for native Float precision")
            return AquariumPerformanceSettings(
                settings = AquariumRecipe.fromJsonValue(obj.getValue("settings")),
                enabled = obj.getValue("enabled").bool(),
                outputGain = gain.toFloat(),
            )
        }
    }
}
