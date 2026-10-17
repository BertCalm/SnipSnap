package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A reproducible Shoal recipe. Root and playing energy remain independent of
 * its six controls; partial energies, phases and transport state are rebuilt.
 * Saved model versions retain their renderer. Recipes without model metadata
 * belong to the first audition and remain model 1.
 */
data class ShoalPatch(
    override val name: String,
    val voice: ShoalVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Shoal.rootMidi(voice),
    val velocity: Float = 1f,
    val model: Int = Shoal.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Shoal.macrosFor(voice))
        require(midi in Shoal.MIN_MIDI..Shoal.MAX_MIDI) { "SHOAL MIDI out of ${Shoal.MIN_MIDI}..${Shoal.MAX_MIDI}: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SHOAL velocity out of 0..1: $velocity" }
        require(Shoal.supportsModel(model)) { "unsupported SHOAL model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Shoal.render(voice, macros, midi, velocity, model = model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "SHOAL"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { name -> ShoalVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Shoal.rootMidi(voice)
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Shoal.LEGACY_MODEL_VERSION
                ShoalPatch(name, voice, macros, midi, velocity, model)
            }
        }

        fun fromJsonText(text: String): ShoalPatch = fromJsonValue(Json.parse(text)) as ShoalPatch
    }
}
