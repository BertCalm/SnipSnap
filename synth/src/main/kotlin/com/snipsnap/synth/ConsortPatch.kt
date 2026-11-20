package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved CONSORT sound. [voice] is the lead. The section ([chairs]: who sits behind the lead and at
 * which interval), the [velocity] and the round-robin [take] are part of the recipe, not macros: a
 * macro is a knob from 0 to 1, and "a bassoon an octave below" is not a position on a knob.
 *
 * The extra fields are written only when they are not the defaults, so a lead-alone patch is the
 * same JSON shape as every other engine's.
 */
data class ConsortPatch(
    override val name: String,
    val voice: ConsortVoice,
    override val macros: Map<String, Float>,
    val chairs: List<ConsortChair> = emptyList(),
    val velocity: Float = Consort.DEFAULT_VELOCITY,
    val take: Int = 0,
) : Patch {
    init {
        Patches.validateMacros(this, Consort.macrosFor(voice))
        require(chairs.size <= Consort.MAX_CHAIRS) { "CONSORT seats at most ${Consort.MAX_CHAIRS} harmony players, got ${chairs.size}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "CONSORT velocity out of 0..1: $velocity" }
        require(take >= 0) { "CONSORT take must not be negative: $take" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Consort.render(voice, macros, chairs, velocity, take)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).apply {
            if (chairs.isNotEmpty()) this["chairs"] = JsonValue.Arr(chairs.map {
                JsonValue.Obj(linkedMapOf("voice" to JsonValue.Str(it.voice.name), "interval" to JsonValue.Str(it.interval.name)))
            })
            if (velocity != Consort.DEFAULT_VELOCITY) this["velocity"] = JsonValue.Num(velocity.toDouble())
            if (take != 0) this["take"] = JsonValue.Num(take.toDouble())
        },
    )

    companion object {
        const val ENGINE = "CONSORT"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch = Patches.decode(value, ENGINE,
            { n -> ConsortVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
            val obj = value.obj()
            val chairs = (obj["chairs"]?.takeUnless { it is JsonValue.Null }?.arr() ?: emptyList()).map { item ->
                val chair = item.obj()
                val who = chair["voice"]?.str() ?: throw JsonException("CONSORT chair has no voice")
                val gap = chair["interval"]?.str() ?: throw JsonException("CONSORT chair has no interval")
                ConsortChair(
                    ConsortVoice.entries.firstOrNull { it.name == who } ?: throw JsonException("unknown CONSORT chair voice $who"),
                    ConsortInterval.entries.firstOrNull { it.name == gap } ?: throw JsonException("unknown CONSORT interval $gap"),
                )
            }
            if (chairs.size > Consort.MAX_CHAIRS) throw JsonException("CONSORT seats at most ${Consort.MAX_CHAIRS} harmony players")
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: Consort.DEFAULT_VELOCITY
            if (!velocity.isFinite() || velocity !in 0f..1f) throw JsonException("CONSORT velocity out of 0..1")
            val take = obj["take"]?.takeUnless { it is JsonValue.Null }?.int() ?: 0
            if (take < 0) throw JsonException("CONSORT take must not be negative")
            ConsortPatch(name, voice, macros, chairs, velocity, take)
        }

        fun fromJsonText(text: String): ConsortPatch = fromJsonValue(Json.parse(text)) as ConsortPatch
    }
}
