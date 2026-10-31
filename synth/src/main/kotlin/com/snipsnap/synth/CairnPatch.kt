package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** Model and cue choice travel with the recipe. The full launch remains renderable. */
data class CairnPatch(
    override val name: String,
    val voice: CairnVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Cairn.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val startAtContact: Boolean = true,
    val model: Int = Cairn.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Cairn.macrosFor(voice))
        require(midi in Cairn.MIN_MIDI..Cairn.MAX_MIDI)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(model == Cairn.MODEL_VERSION) { "unsupported CAIRN model $model" }
        require((macros["HOLD"] ?: 0f) < Cairn.HOLD_RESERVED) { "CAIRN reserves HOLD's top step in this milestone" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Cairn.render(voice, macros, midi, velocity, startAtContact)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue() = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).also {
        it["model"] = JsonValue.Num(model.toDouble())
        it["midi"] = JsonValue.Num(midi.toDouble())
        it["velocity"] = JsonValue.Num(velocity.toDouble())
        it["startAtContact"] = JsonValue.Bool(startAtContact)
    })
    companion object {
        const val ENGINE = "CAIRN"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { n -> CairnVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                CairnPatch(name, voice, macros,
                    obj["midi"]?.int() ?: Cairn.DEFAULT_MIDI,
                    obj["velocity"]?.num()?.toFloat() ?: 1f,
                    obj["startAtContact"]?.bool() ?: true,
                    obj["model"]?.int() ?: Cairn.MODEL_VERSION)
            }
        }
        fun fromJsonText(text: String) = fromJsonValue(Json.parse(text)) as CairnPatch
    }
}
