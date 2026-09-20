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
            SkinPatch.ENGINE -> SkinPatch.fromJsonValue(value)
            TinesPatch.ENGINE -> TinesPatch.fromJsonValue(value)
            PluckPatch.ENGINE -> PluckPatch.fromJsonValue(value)
            TonewheelPatch.ENGINE -> TonewheelPatch.fromJsonValue(value)
            VelvetPatch.ENGINE -> VelvetPatch.fromJsonValue(value)
            FathomPatch.ENGINE -> FathomPatch.fromJsonValue(value)
            VoxPatch.ENGINE -> VoxPatch.fromJsonValue(value)
            SnapPatch.ENGINE -> SnapPatch.fromJsonValue(value)
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

/** A saved FATHOM sound. */
data class FathomPatch(
    override val name: String,
    val voice: FathomVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Fathom.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Fathom.render(voice, macros)

    companion object {
        const val ENGINE = "FATHOM"
        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> FathomVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                FathomPatch(name, voice, macros)
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

/**
 * A saved SNAP sound: the line read off a photo, as its own 256 numbers,
 * plus the knobs. The photo itself is not kept — the table is the part of
 * it that sounds, and it is small enough to live in `kit.json` beside
 * every other recipe. The one patch with a field beyond the common four,
 * so it writes and reads that field itself around [Patches]' shared shape.
 *
 * The table is copied in and never handed out for writing: a caller
 * editing its own array after building the patch would otherwise change
 * the sound (and the hash) behind the 0..255 check.
 */
class SnapPatch(
    override val name: String,
    val voice: SnapVoice,
    override val macros: Map<String, Float>,
    table: IntArray,
    envelope: IntArray? = null,
) : Patch {
    /** [Snap.TABLE_SIZE] brightness values, 0..255, exactly as [Snap.table] read them or [Draw] drew them. */
    val table: IntArray = table.copyOf()

    /**
     * A drawn volume shape, [Draw.ENVELOPE_SIZE] points 0..255 across the
     * note, or null for SNAP's own DECAY exponential. Optional in the
     * sidecar too: a recipe without one reads as it always did.
     */
    val envelope: IntArray? = envelope?.copyOf()

    init {
        Patches.validateMacros(this, Snap.macrosFor(voice))
        require(table.size == Snap.TABLE_SIZE) { "a SNAP table has ${Snap.TABLE_SIZE} points, got ${table.size}" }
        for ((i, v) in table.withIndex()) require(v in 0..255) { "table[$i] is not a brightness 0..255: $v" }
        // The same refusal Snap.read makes, held here too, so a table
        // typed into kit.json by hand cannot land a silent pad that
        // nothing refused in words.
        require(!Snap.isFlat(table)) { "a SNAP table with no swing in it has no waveform to play" }
        if (envelope != null) {
            require(envelope.size == Draw.ENVELOPE_SIZE) { "a drawn shape has ${Draw.ENVELOPE_SIZE} points, got ${envelope.size}" }
            for ((i, v) in envelope.withIndex()) require(v in 0..255) { "envelope[$i] is not a level 0..255: $v" }
            require(envelope.any { it > 0 }) { "a drawn shape that never opens is silence" }
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Snap.render(table, macros, envelope)

    override fun toJsonValue(): JsonValue.Obj {
        val base = Patches.toJsonValue(this)
        val obj = LinkedHashMap(base.entries)
        obj["table"] = JsonValue.Arr(table.map { JsonValue.Num(it.toDouble()) })
        envelope?.let { obj["envelope"] = JsonValue.Arr(it.map { v -> JsonValue.Num(v.toDouble()) }) }
        return JsonValue.Obj(obj)
    }

    fun copy(
        name: String = this.name,
        voice: SnapVoice = this.voice,
        macros: Map<String, Float> = this.macros,
        table: IntArray = this.table,
        envelope: IntArray? = this.envelope,
    ): SnapPatch = SnapPatch(name, voice, macros, table, envelope)

    // Not a data class: an array member would compare by identity there,
    // and a recipe round-trip test has to compare the numbers.
    override fun equals(other: Any?): Boolean =
        other is SnapPatch && name == other.name && voice == other.voice &&
            macros == other.macros && table.contentEquals(other.table) &&
            (envelope?.contentEquals(other.envelope ?: IntArray(0)) ?: (other.envelope == null))

    override fun hashCode(): Int =
        (((name.hashCode() * 31 + voice.hashCode()) * 31 + macros.hashCode()) * 31 + table.contentHashCode()) * 31 +
            (envelope?.contentHashCode() ?: 0)

    override fun toString(): String =
        "SnapPatch(name=$name, voice=$voice, macros=$macros, table=[${table.size} points]" +
            (envelope?.let { ", envelope=[${it.size} points]" } ?: "") + ")"

    companion object {
        const val ENGINE = "SNAP"

        /**
         * Engine and version are checked first, through the shared
         * [Patches.decode], so a file from a later version says
         * "unsupported version" rather than something about its table;
         * the table is read inside the build step, and a wrong-length,
         * out-of-range or flat one is a [JsonException] like every other
         * malformed recipe, never a bare [IllegalArgumentException].
         */
        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> SnapVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val raw = value.obj()["table"] ?: throw JsonException("SNAP patch has no table")
                val items = raw.arr()
                if (items.size != Snap.TABLE_SIZE) throw JsonException("SNAP table has ${items.size} points, not ${Snap.TABLE_SIZE}")
                val table = IntArray(items.size) { i ->
                    val v = items[i].int()
                    if (v !in 0..255) throw JsonException("SNAP table[$i] is not a brightness 0..255: $v")
                    v
                }
                if (Snap.isFlat(table)) throw JsonException("SNAP table has no swing in it: nothing to play")
                val envelope = value.obj()["envelope"]?.let { rawEnv ->
                    val points = rawEnv.arr()
                    if (points.size != Draw.ENVELOPE_SIZE) throw JsonException("SNAP envelope has ${points.size} points, not ${Draw.ENVELOPE_SIZE}")
                    val env = IntArray(points.size) { i ->
                        val v = points[i].int()
                        if (v !in 0..255) throw JsonException("SNAP envelope[$i] is not a level 0..255: $v")
                        v
                    }
                    if (env.none { it > 0 }) throw JsonException("SNAP envelope never opens: silence")
                    env
                }
                SnapPatch(name, voice, macros, table, envelope)
            }
        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}
