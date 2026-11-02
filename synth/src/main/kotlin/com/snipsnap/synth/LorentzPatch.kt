package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** A complete finite collision recipe. Model 3 is the approved audition source. */
data class LorentzPatch(
    override val name: String,
    val voice: LorentzVoice,
    override val macros: Map<String, Float> = Lorentz.defaults(voice),
    val velocity: Float = 1f,
    val model: Int = Lorentz.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Lorentz.macrosFor(voice))
        require(model == Lorentz.MODEL_VERSION) { "unsupported LORENTZ model $model" }
        require(velocity.isFinite() && velocity in 0f..1f) { "LORENTZ velocity must be finite in 0..1" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Lorentz.render(voice, macros, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue() = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).also {
        it["velocity"] = JsonValue.Num(velocity.toDouble())
        it["model"] = JsonValue.Num(model.toDouble())
    })
    companion object {
        const val ENGINE = "LORENTZ"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { n -> LorentzVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                LorentzPatch(name, voice, macros,
                    obj["velocity"]?.num()?.toFloat() ?: 1f, obj["model"]?.int() ?: Lorentz.MODEL_VERSION)
            }
        }
        fun fromJsonText(text: String) = fromJsonValue(Json.parse(text)) as LorentzPatch
    }
}
