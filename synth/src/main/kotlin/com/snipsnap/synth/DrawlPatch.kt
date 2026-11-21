package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved DRAWL sound. The [velocity], the round-robin [take] and the [strum] (a chord, see
 * [DrawlStrum]) are part of the performance, not macros (a macro is a knob from 0 to 1; a take is
 * which pass of the player you hear). Each is written only when it is not the default, so a plain
 * patch is the same JSON shape as every other engine's.
 */
data class DrawlPatch(
    override val name: String,
    val voice: DrawlVoice,
    override val macros: Map<String, Float> = emptyMap(),
    val velocity: Float = Drawl.DEFAULT_VELOCITY,
    val take: Int = 0,
    val strum: DrawlStrum.Articulation? = null,
) : Patch {
    init {
        Patches.validateMacros(this, Drawl.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "DRAWL velocity out of 0..1: $velocity" }
        require(take >= 0) { "DRAWL take must not be negative: $take" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Drawl.render(voice, macros, velocity, take, strum)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).apply {
            if (velocity != Drawl.DEFAULT_VELOCITY) this["velocity"] = JsonValue.Num(velocity.toDouble())
            if (take != 0) this["take"] = JsonValue.Num(take.toDouble())
            if (strum != null) this["strum"] = strum.toJsonValue()
        },
    )

    companion object {
        const val ENGINE = "DRAWL"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch = Patches.decode(value, ENGINE,
            { n -> DrawlVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
            val obj = value.obj()
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: Drawl.DEFAULT_VELOCITY
            if (!velocity.isFinite() || velocity !in 0f..1f) throw JsonException("DRAWL velocity out of 0..1")
            val take = obj["take"]?.takeUnless { it is JsonValue.Null }?.int() ?: 0
            if (take < 0) throw JsonException("DRAWL take must not be negative")
            val strum = obj["strum"]?.takeUnless { it is JsonValue.Null }?.let {
                try { DrawlStrum.fromJsonValue(it) } catch (e: IllegalArgumentException) { throw JsonException("DRAWL strum: ${e.message}") }
            }
            DrawlPatch(name, voice, macros, velocity, take, strum)
        }

        fun fromJsonText(text: String): DrawlPatch = fromJsonValue(Json.parse(text)) as DrawlPatch
    }
}
