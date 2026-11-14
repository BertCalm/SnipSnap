package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved BREAKER sound: voice + macro settings + a hand-written name, and the renderer [model] it
 * was made with, so a later revision of the chain can keep old saves sounding as they did.
 *
 * Serialized as JSON (the shape every engine shares through [Patches]); a pad keeps it next to its
 * rendered WAV through [PadRecipe].
 */
data class BreakerPatch(
    override val name: String,
    val voice: BreakerVoice,
    override val macros: Map<String, Float>,
    val model: Int = Breaker.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Breaker.macrosFor(voice, model))
        require(model in 1..Breaker.MODEL_VERSION) { "unsupported breaker model $model" }
        require(model >= 2 || voice == BreakerVoice.KICK || voice == BreakerVoice.SNARE) { "breaker model 1 has no $voice" }
        require(model >= 3 || (voice != BreakerVoice.CRASH && voice != BreakerVoice.RISER)) { "breaker model $model has no $voice" }
        require(model >= 5 || voice != BreakerVoice.BUMP) { "breaker model $model has no $voice" }
        require(model >= 7 || voice != BreakerVoice.FILL) { "breaker model $model has no $voice" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(1f)

    /** [take] re-strikes the drum (a fresh SKIN strike); take 0 is the reference hit. */
    fun render(velocity: Float, take: Int = 0) = Breaker.render(voice, macros, velocity, take, model)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "BREAKER"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> BreakerVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                BreakerPatch(name, voice, macros, value.obj()["model"]?.int() ?: Breaker.MODEL_VERSION)
            }

        fun fromJsonText(text: String): BreakerPatch = fromJsonValue(Json.parse(text)) as BreakerPatch
    }
}
