package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Excitation changes inside OHM's shared body, rather than separate layered instruments. */
enum class OhmExciter { PLUCK, BOW, CAPTURED }

/**
 * Source choices for model 3 and later. These sit beside the six performance macros so the
 * relationship controls keep the same meaning across the different exciters.
 * FM index is phase deviation in radians; freeze holds an actual audio buffer.
 */
data class OhmOptions(
    val exciter: OhmExciter = OhmExciter.PLUCK,
    val elder: Float = 0f,
    val grains: Float = 0f,
    val freeze: Float = 0f,
    val fmRatio: Float = 1.5f,
    val fmIndex: Float = 1.5f,
    val grainSeconds: Float = .08f,
    val capture: OhmCapture? = null,
) {
    init {
        for ((name, value) in listOf("elder" to elder, "grains" to grains, "freeze" to freeze)) {
            require(value.isFinite() && value in 0f..1f) { "OHM $name must be finite and in 0..1" }
        }
        require(fmRatio.isFinite() && fmRatio in .5f..2f) { "OHM FM ratio must be in 0.5..2" }
        require(fmIndex.isFinite() && fmIndex in 0f..3f) { "OHM FM index must be in 0..3 radians" }
        require(grainSeconds.isFinite() && grainSeconds in .02f.. .20f) { "OHM grain duration must be in 0.02..0.20 seconds" }
        require(exciter != OhmExciter.CAPTURED || capture != null) { "OHM captured excitation needs a saved capture" }
    }

    /** Inactive detail settings cannot alter a plain pluck. A retained capture is dormant. */
    val isNeutral: Boolean get() = exciter == OhmExciter.PLUCK && elder == 0f && grains == 0f && freeze == 0f

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "exciter" to JsonValue.Str(exciter.name),
        "elder" to JsonValue.Num(elder.toDouble()),
        "grains" to JsonValue.Num(grains.toDouble()),
        "freeze" to JsonValue.Num(freeze.toDouble()),
        "fmRatio" to JsonValue.Num(fmRatio.toDouble()),
        "fmIndex" to JsonValue.Num(fmIndex.toDouble()),
        "grainSeconds" to JsonValue.Num(grainSeconds.toDouble()),
        "capture" to (capture?.toJsonValue() ?: JsonValue.Null),
    ))

    companion object {
        fun fromJsonValue(value: JsonValue): OhmOptions {
            val obj = value.obj()
            val known = setOf("exciter", "elder", "grains", "freeze", "fmRatio", "fmIndex", "grainSeconds", "capture")
            val unknown = obj.keys - known
            if (unknown.isNotEmpty()) throw JsonException("unknown OHM source options: ${unknown.joinToString()}")
            fun number(name: String, default: Float): Float =
                obj[name]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: default
            val exciterName = obj["exciter"]?.takeUnless { it is JsonValue.Null }?.str() ?: "PLUCK"
            val exciter = OhmExciter.entries.firstOrNull { it.name == exciterName }
                ?: throw JsonException("unknown OHM exciter $exciterName")
            return try {
                OhmOptions(
                    exciter, number("elder", 0f), number("grains", 0f), number("freeze", 0f),
                    number("fmRatio", 1.5f), number("fmIndex", 1.5f), number("grainSeconds", .08f),
                    obj["capture"]?.takeUnless { it is JsonValue.Null }?.let(OhmCapture::fromJsonValue),
                )
            } catch (e: IllegalArgumentException) {
                throw JsonException(e.message ?: "invalid OHM source options")
            }
        }
    }
}
