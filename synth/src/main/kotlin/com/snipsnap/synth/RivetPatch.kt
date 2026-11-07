package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Collections

/** A complete immutable RIVET control snapshot with an explicit renderer version. */
class RivetPatch(
    override val name: String,
    val voice: RivetVoice,
    macros: Map<String, Float> = Rivet.defaults(voice),
    val modelVersion: Int = Rivet.MODEL_VERSION,
) : Patch {
    // A caller may reuse a mutable slider map. A saved hit owns a snapshot,
    // including when exposed through Patch.macros.
    override val macros: Map<String, Float> = Collections.unmodifiableMap(LinkedHashMap(macros))

    init {
        Patches.validateMacros(this, Rivet.macrosFor(voice))
        require(this.macros.keys == Rivet.macrosFor(voice).map { it.name }.toSet()) {
            "RIVET patches must save all controls"
        }
        Rivet.requireModelVersion(voice, modelVersion)
        Rivet.requireFinite(this.macros)
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Rivet.render(voice, macros, modelVersion)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    fun copy(
        name: String = this.name,
        voice: RivetVoice = this.voice,
        macros: Map<String, Float> = this.macros,
        modelVersion: Int = this.modelVersion,
    ) = RivetPatch(name, voice, macros, modelVersion)

    override fun equals(other: Any?): Boolean = other is RivetPatch &&
        name == other.name && voice == other.voice && macros == other.macros && modelVersion == other.modelVersion

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + voice.hashCode()
        result = 31 * result + macros.hashCode()
        return 31 * result + modelVersion
    }

    override fun toString() = "RivetPatch(name=$name, voice=$voice, macros=$macros, modelVersion=$modelVersion)"

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["modelVersion"] = JsonValue.Num(modelVersion.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "RIVET"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): RivetPatch {
            val obj = value.obj()
            // This is RIVET's first editable format. Never infer a missing version
            // from the current renderer: that would silently change a saved sound.
            val modelVersion = obj["modelVersion"]?.int()
                ?: throw JsonException("RIVET patch has no modelVersion")
            val macros = when (val raw = obj["macros"]) {
                null, JsonValue.Null -> throw JsonException("RIVET patch requires its complete macro snapshot")
                is JsonValue.Obj -> raw.entries
                else -> throw JsonException("patch macros must be an object, got ${raw::class.simpleName}")
            }
            for ((key, value) in macros) {
                val number = value.num()
                if (!number.isFinite() || number !in 0.0..1.0) {
                    throw JsonException("RIVET macro $key out of 0..1: $number")
                }
            }
            return try {
                Patches.decode(value, ENGINE, { n -> RivetVoice.entries.firstOrNull { it.name == n } }) { name, voice, controls ->
                    RivetPatch(name, voice, controls, modelVersion)
                } as RivetPatch
            } catch (e: IllegalArgumentException) {
                throw JsonException(e.message ?: "invalid RIVET patch")
            }
        }

        fun fromJsonText(text: String): RivetPatch = fromJsonValue(Json.parse(text))
    }
}
