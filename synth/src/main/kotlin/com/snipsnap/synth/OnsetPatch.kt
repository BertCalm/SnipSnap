package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Collections

/**
 * An editable ONSET hit. The complete six-control map and renderer version travel
 * with the sample, so reopening a pad cannot pick up a newer voice's defaults.
 * Model 1 retains the original kick; model 2 retains the accepted membrane kick.
 * Snare and open hat first exist in model 3.
 */
class OnsetPatch(
    override val name: String,
    val voice: OnsetVoice,
    macros: Map<String, Float> = Onset.defaults(voice),
    val modelVersion: Int = Onset.MODEL_VERSION,
) : Patch {
    // A caller may reuse a mutable slider map. A saved hit owns a snapshot,
    // including when exposed through Patch.macros or a factory preset.
    override val macros: Map<String, Float> = Collections.unmodifiableMap(LinkedHashMap(macros))

    init {
        Patches.validateMacros(this, Onset.macrosFor(voice))
        require(this.macros.keys == Onset.macrosFor(voice).map { it.name }.toSet()) {
            "ONSET patches must save all six macros"
        }
        require(modelVersion in 1..Onset.MODEL_VERSION) { "unsupported ONSET model version: $modelVersion" }
        require(modelVersion >= 3 || voice == OnsetVoice.KICK || voice == OnsetVoice.HAT_CLOSED) {
            "$voice was introduced in ONSET model 3"
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Onset.render(voice, macros, modelVersion)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    fun copy(
        name: String = this.name,
        voice: OnsetVoice = this.voice,
        macros: Map<String, Float> = this.macros,
        modelVersion: Int = this.modelVersion,
    ) = OnsetPatch(name, voice, macros, modelVersion)

    override fun equals(other: Any?): Boolean = other is OnsetPatch &&
        name == other.name && voice == other.voice && macros == other.macros && modelVersion == other.modelVersion

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + voice.hashCode()
        result = 31 * result + macros.hashCode()
        return 31 * result + modelVersion
    }

    override fun toString() = "OnsetPatch(name=$name, voice=$voice, macros=$macros, modelVersion=$modelVersion)"

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["modelVersion"] = JsonValue.Num(modelVersion.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "ONSET"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): OnsetPatch {
            val obj = value.obj()
            // This is ONSET's first editable format. Never infer a missing version
            // from the current renderer: that would silently change a saved sound.
            val modelVersion = obj["modelVersion"]?.int()
                ?: throw JsonException("ONSET patch has no modelVersion")
            val macros = when (val raw = obj["macros"]) {
                null, JsonValue.Null -> throw JsonException("ONSET patch requires its complete macro snapshot")
                is JsonValue.Obj -> raw.entries
                else -> throw JsonException("patch macros must be an object, got ${raw::class.simpleName}")
            }
            for ((key, value) in macros) {
                val number = value.num()
                if (!number.isFinite() || number !in 0.0..1.0) {
                    throw JsonException("ONSET macro $key out of 0..1: $number")
                }
            }
            return try {
                Patches.decode(value, ENGINE, { n -> OnsetVoice.entries.firstOrNull { it.name == n } }) { name, voice, controls ->
                    OnsetPatch(name, voice, controls, modelVersion)
                } as OnsetPatch
            } catch (e: IllegalArgumentException) {
                throw JsonException(e.message ?: "invalid ONSET patch")
            }
        }

        fun fromJsonText(text: String): OnsetPatch = fromJsonValue(Json.parse(text))
    }
}
