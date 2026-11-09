package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Saved mallet voice and shared dub controls. Audio identity survives edits. */
data class HalobarPatch(
    override val name: String,
    val voice: HalobarVoice,
    override val macros: Map<String,Float>,
    val model: Int = Halobar.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this,Halobar.macrosFor(voice))
        require(model==Halobar.MODEL_VERSION) { "unsupported HALOBAR model $model" }
    }
    override val engine get()=ENGINE
    override val voiceName get()=voice.name
    override fun render()=Halobar.render(voice,macros,model=model)
    override fun withMacros(macros: Map<String,Float>)=copy(macros=macros)
    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).apply {
        put("model",JsonValue.Num(model.toDouble()))
    })
    companion object {
        const val ENGINE="HALOBAR"
        const val VERSION=Patches.VERSION
        fun fromJsonValue(value: JsonValue): HalobarPatch {
            val model=value.obj()["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: 1
            if(model!=Halobar.MODEL_VERSION)throw JsonException("unsupported HALOBAR model $model")
            return Patches.decode(value,ENGINE,{ n -> HalobarVoice.entries.firstOrNull { it.name==n } }) { name,voice,macros ->
                HalobarPatch(name,voice,macros,model)
            } as HalobarPatch
        }
        fun fromJsonText(text: String)=fromJsonValue(Json.parse(text))
    }
}
