package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved SKIN sound: voice + macro settings + a hand-written name.
 *
 * Serialized as JSON (shape shared by every engine via [Patches]) so a synth
 * pad can keep its recipe next to its rendered WAV (editable forever) and so
 * patches can be shared as files. Embedding into `kit.json` happens through
 * [PadRecipe]; this is the per-engine format.
 */
data class SkinPatch(
    override val name: String,
    val voice: SkinVoice,
    override val macros: Map<String, Float>,
    val model: Int = 1,
) : Patch {
    init {
        require(model == 1 || model == 2 && voice == SkinVoice.SNARE) { "unsupported SKIN model $model for $voice" }
        Patches.validateMacros(this, macroSpecs())
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    fun macroSpecs() = if (model == 2) BackroomSnare.macros else Skin.macrosFor(voice)
    fun defaults() = if (model == 2) BackroomSnare.defaults() else Skin.defaults(voice)
    override fun render() = render(1f)
    fun render(velocity: Float, take: Int = 0) = if (model == 2) BackroomSnare.render(macros, velocity, take)
        else Skin.render(voice, macros, velocity = velocity, take = take)
    override fun toJsonValue() = if (model == 1) Patches.toJsonValue(this)
        else JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).apply {
            put("model", JsonValue.Num(model.toDouble()))
        })
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "SKIN"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> SkinVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                SkinPatch(name, voice, macros, value.obj()["model"]?.int() ?: 1)
            }

        fun fromJsonText(text: String): SkinPatch = fromJsonValue(Json.parse(text)) as SkinPatch
    }
}
