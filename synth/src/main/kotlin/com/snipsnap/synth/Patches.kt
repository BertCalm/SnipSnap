package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * What every engine's saved sound has in common: an engine tag, a voice, a
 * macro map, and the ability to render itself. The tag is the contract —
 * a THUMP file refuses to open in a TINES panel, and [Patches.fromJsonValue]
 * uses it to hand a recipe to the right engine without anyone guessing.
 */
sealed interface Patch {
    val name: String
    val engine: String
    val voiceName: String
    val macros: Map<String, Float>

    fun render(): Snip

    fun toJsonValue(): JsonValue.Obj = Patches.toJsonValue(this)
    fun toJsonText(): String = Json.write(toJsonValue())
}

/** The engine dispatcher: one place that knows every patch format. */
object Patches {

    const val VERSION = 1

    fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))

    fun fromJsonValue(value: JsonValue): Patch {
        val engine = value.obj()["engine"]?.str() ?: throw JsonException("patch has no engine")
        return when (engine) {
            ThumpPatch.ENGINE -> ThumpPatch.fromJsonValue(value)
            TinesPatch.ENGINE -> TinesPatch.fromJsonValue(value)
            PluckPatch.ENGINE -> PluckPatch.fromJsonValue(value)
            TonewheelPatch.ENGINE -> TonewheelPatch.fromJsonValue(value)
            VelvetPatch.ENGINE -> VelvetPatch.fromJsonValue(value)
            VoxPatch.ENGINE -> VoxPatch.fromJsonValue(value)
            else -> throw JsonException("unknown engine $engine")
        }
    }

    internal fun toJsonValue(patch: Patch): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "engine" to JsonValue.Str(patch.engine),
            "version" to JsonValue.Num(VERSION.toDouble()),
            "name" to JsonValue.Str(patch.name),
            "voice" to JsonValue.Str(patch.voiceName),
            "macros" to JsonValue.Obj(
                patch.macros.entries.associateTo(LinkedHashMap()) { (k, v) ->
                    k to JsonValue.Num(v.toDouble())
                },
            ),
        ),
    )

    /** Shared decode: engine check, version check, the common fields. */
    internal fun <V> decode(
        value: JsonValue,
        engine: String,
        voiceOf: (String) -> V?,
        build: (name: String, voice: V, macros: Map<String, Float>) -> Patch,
    ): Patch {
        val obj = value.obj()
        val actual = obj["engine"]?.str() ?: throw JsonException("patch has no engine")
        if (actual != engine) throw JsonException("not a $engine patch: $actual")
        val version = obj["version"]?.int() ?: throw JsonException("patch has no version")
        if (version != VERSION) throw JsonException("unsupported patch version $version")
        val voiceName = obj["voice"]?.str() ?: throw JsonException("patch has no voice")
        val voice = voiceOf(voiceName) ?: throw JsonException("unknown voice $voiceName")
        val macros = (obj["macros"] as? JsonValue.Obj)?.entries
            ?.mapValues { (_, v) -> v.num().toFloat() }
            ?: emptyMap()
        val name = obj["name"]?.str() ?: throw JsonException("patch has no name")
        return build(name, voice, macros)
    }

    internal fun validateMacros(patch: Patch, known: List<MacroSpec>) {
        require(patch.name.isNotBlank()) { "patch name must not be blank" }
        val names = known.map { it.name }.toSet()
        for ((k, v) in patch.macros) {
            require(k in names) { "unknown macro $k for ${patch.voiceName} (knows $names)" }
            require(v in 0f..1f) { "macro $k out of 0..1: $v" }
        }
    }
}

/** A saved PLUCK sound. */
data class PluckPatch(
    override val name: String,
    val voice: PluckVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Pluck.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Pluck.render(voice, macros)

    companion object {
        const val ENGINE = "PLUCK"
        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> PluckVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                PluckPatch(name, voice, macros)
            }
        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}

/** A saved TONEWHEEL registration. */
data class TonewheelPatch(
    override val name: String,
    val voice: TonewheelVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Tonewheel.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Tonewheel.render(voice, macros)

    companion object {
        const val ENGINE = "TONEWHEEL"
        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> TonewheelVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TonewheelPatch(name, voice, macros)
            }
        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}

/** A saved VELVET sound. */
data class VelvetPatch(
    override val name: String,
    val voice: VelvetVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Velvet.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Velvet.render(voice, macros)

    companion object {
        const val ENGINE = "VELVET"
        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> VelvetVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                VelvetPatch(name, voice, macros)
            }
        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}

/** A saved VOX sound. */
data class VoxPatch(
    override val name: String,
    val voice: VoxVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Vox.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Vox.render(voice, macros)

    companion object {
        const val ENGINE = "VOX"
        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> VoxVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                VoxPatch(name, voice, macros)
            }
        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}
