package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * The editable board mapping over the heard standalone processors.
 * Why it is built this way:
 * - AMT remains the destruction-core control at the heard 35% and 70% settings.
 * - Below 35%, the complete result blends in from dry, including the core's fixed filters.
 * - SHIMMER is independently saved; 15% is a provisional starting point, not a listening preference.
 * - The clock, pattern and all three model identities travel with the recipe.
 * Neither public processor's equations or standalone defaults change.
 */
object ObliterateBoard {
    const val MODEL = "obliterate-board-r1"
    const val FULL_ENGAGEMENT_AMOUNT = .35f
    const val DEFAULT_AMOUNT = FULL_ENGAGEMENT_AMOUNT
    internal fun engagement(amount: Double): Double = minOf(1.0, amount / FULL_ENGAGEMENT_AMOUNT.toDouble())

    data class Settings(
        val shimmerMix: Double = .15,
        val pitched: Boolean = true,
        val bpm: Double = 120.0,
        val division: Int = 16,
        val seed: Long = 20261023L,
    ) {
        init {
            ObliterateShimmer.Settings(shimmerMix, pitched)
            ObliterateCore.Settings(bpm = bpm, division = division, seed = seed)
        }

        /** Inner processor settings; the board applies its low-AMT dry/result blend separately. */
        fun at(amount: Float): Obliterate.Settings {
            require(amount.isFinite() && amount in 0f..1f) { "board amount must be finite and in 0..1" }
            return Obliterate.Settings(
                ObliterateShimmer.Settings(shimmerMix, pitched),
                ObliterateCore.Settings(amount.toDouble(), bpm, division, seed),
            )
        }

        fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "model" to JsonValue.Str(MODEL),
            "shimmerModel" to JsonValue.Str(ObliterateShimmer.MODEL),
            "coreModel" to JsonValue.Str(ObliterateCore.MODEL),
            "shimmerMix" to JsonValue.Num(shimmerMix),
            "pitched" to JsonValue.Bool(pitched),
            "bpm" to JsonValue.Num(bpm),
            "division" to JsonValue.Num(division.toDouble()),
            "seed" to JsonValue.Num(seed.toDouble()),
        ))

        companion object {
            fun fromJsonValue(value: JsonValue): Settings {
                val o = value.obj()
                val fields = setOf("model", "shimmerModel", "coreModel", "shimmerMix", "pitched", "bpm", "division", "seed")
                if (o.keys != fields) throw JsonException("OBLITERATE settings require exactly $fields")
                for ((key, expected) in mapOf("model" to MODEL, "shimmerModel" to ObliterateShimmer.MODEL, "coreModel" to ObliterateCore.MODEL)) {
                    if (o.getValue(key).str() != expected) throw JsonException("unsupported OBLITERATE $key")
                }
                val seed = o.getValue("seed").num()
                if (!seed.isFinite() || seed !in 0.0..4294967295.0 || seed != Math.rint(seed))
                    throw JsonException("OBLITERATE seed must be an unsigned 32-bit integer")
                return try {
                    Settings(o.getValue("shimmerMix").num(), o.getValue("pitched").bool(),
                        o.getValue("bpm").num(), o.getValue("division").int(), seed.toLong())
                } catch (e: IllegalArgumentException) {
                    throw JsonException(e.message ?: "invalid OBLITERATE settings")
                }
            }
        }
    }

    fun process(snip: Snip, amount: Float, settings: Settings = Settings()): Snip {
        val mapped = settings.at(amount)
        if (amount == 0f) return snip
        return ObliterateBoardCache.render(snip, mapped)
    }

    /** Read-only preflight, including every velocity zone before a KEEP captures any files. */
    fun requireInput(snip: Snip) {
        require(snip.sampleRate == ObliterateShimmer.RATE && snip.frameCount in 1..ObliterateShimmer.MAX_INPUT_SECONDS * ObliterateShimmer.RATE) {
            "OBLITERATE needs 1 frame to 9 seconds at 44.1 kHz"
        }
        require(snip.samples.all { it.isFinite() && it in -1f..1f }) { "OBLITERATE needs finite PCM in -1..1" }
    }
}
