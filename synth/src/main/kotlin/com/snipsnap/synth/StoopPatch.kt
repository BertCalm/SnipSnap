package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete deterministic groove voice recipe. Pitch, source registration,
 * answering gesture and fixed chord voicing travel beside the six macros.
 * HOLD saves a settled loop; its delayed attack is absent from loop-only export.
 * New recipes use model 2. Older recipes keep model 1, including JSON written
 * before the model field existed, so opening or editing a sound retains its renderer.
 */
data class StoopPatch(
    override val name: String,
    val voice: StoopVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Stoop.DEFAULT_MIDI,
    val velocity: Float = 1f,
    val source: StoopSource = Stoop.defaultSource(voice),
    val shadowInterval: Int = 0,
    val shadowDelayMs: Float = 110f,
    val shadowArticulation: Float = .25f,
    val shadowReplies: Int = 1,
    val chordIntervals: List<Int> = listOf(0, 7, 12),
    val model: Int = Stoop.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Stoop.macrosFor(voice))
        require(midi in Stoop.MIDI_MIN..Stoop.MIDI_MAX) { "STOOP midi out of ${Stoop.MIDI_MIN}..${Stoop.MIDI_MAX}: $midi" }
        finiteRange("velocity", velocity, 0f..1f)
        finiteRange("source.shape", source.shape, 0f..1f)
        finiteRange("source.sub", source.sub, 0f..1f)
        finiteRange("source.fmRatio", source.fmRatio, .25f..8f)
        finiteRange("source.fmIndex", source.fmIndex, 0f..8f)
        finiteRange("source.harmonicTilt", source.harmonicTilt, 0f..1f)
        finiteRange("source.detuneCents", source.detuneCents, -20f..20f)
        require(shadowInterval in -24..24) { "STOOP shadow interval out of -24..24: $shadowInterval" }
        finiteRange("shadowDelayMs", shadowDelayMs, 25f..250f)
        finiteRange("shadowArticulation", shadowArticulation, 0f..1f)
        require(shadowReplies in 1..2) { "STOOP permits one or two shadow replies: $shadowReplies" }
        require(chordIntervals.size in 1..4 && 0 in chordIntervals && chordIntervals.distinct() == chordIntervals && chordIntervals.all { it in -24..24 }) {
            "STOOP chord intervals must contain root and one to four distinct intervals in -24..24: $chordIntervals"
        }
        require(model == Stoop.LEGACY_MODEL_VERSION || model == Stoop.MODEL_VERSION) { "unsupported stoop model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Stoop.render(this)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = number(model)
        obj["midi"] = number(midi)
        obj["velocity"] = number(velocity)
        obj["source"] = JsonValue.Obj(linkedMapOf(
            "shape" to number(source.shape), "sub" to number(source.sub),
            "fmRatio" to number(source.fmRatio), "fmIndex" to number(source.fmIndex),
            "harmonicTilt" to number(source.harmonicTilt), "detuneCents" to number(source.detuneCents),
        ))
        obj["shadowInterval"] = number(shadowInterval)
        obj["shadowDelayMs"] = number(shadowDelayMs)
        obj["shadowArticulation"] = number(shadowArticulation)
        obj["shadowReplies"] = number(shadowReplies)
        obj["chordIntervals"] = JsonValue.Arr(chordIntervals.map { number(it) })
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "STOOP"
        const val VERSION = Patches.VERSION

        private fun number(value: Number) = JsonValue.Num(value.toDouble())
        private fun finiteRange(field: String, value: Float, range: ClosedFloatingPointRange<Float>) {
            require(value.isFinite() && value in range) { "STOOP $field out of $range: $value" }
        }

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            // Partial macro maps are valid; malformed maps must not silently become defaults.
            obj["macros"]?.let { if (it !is JsonValue.Obj) throw JsonException("STOOP macros must be an object") }
            return Patches.decode(value, ENGINE, { n -> StoopVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                fun field(key: String) = obj[key]?.takeUnless { it is JsonValue.Null }
                fun integer(key: String, default: Int) = field(key)?.int() ?: default
                fun scalar(key: String, default: Float) = field(key)?.num()?.toFloat() ?: default
                val model = integer("model", Stoop.LEGACY_MODEL_VERSION)
                if (model != Stoop.LEGACY_MODEL_VERSION && model != Stoop.MODEL_VERSION) throw JsonException("unsupported stoop model $model")
                val defaults = Stoop.defaultSource(voice)
                val sourceFields = field("source")?.obj()
                fun sourceScalar(key: String, default: Float) = sourceFields?.get(key)?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: default
                val source = StoopSource(
                    shape = sourceScalar("shape", defaults.shape), sub = sourceScalar("sub", defaults.sub),
                    fmRatio = sourceScalar("fmRatio", defaults.fmRatio), fmIndex = sourceScalar("fmIndex", defaults.fmIndex),
                    harmonicTilt = sourceScalar("harmonicTilt", defaults.harmonicTilt), detuneCents = sourceScalar("detuneCents", defaults.detuneCents),
                )
                StoopPatch(
                    name, voice, macros, integer("midi", Stoop.DEFAULT_MIDI), scalar("velocity", 1f), source,
                    integer("shadowInterval", 0), scalar("shadowDelayMs", 110f), scalar("shadowArticulation", .25f),
                    integer("shadowReplies", 1), field("chordIntervals")?.arr()?.map { it.int() } ?: listOf(0, 7, 12), model,
                )
            }
        }

        fun fromJsonText(text: String): StoopPatch = fromJsonValue(Json.parse(text)) as StoopPatch
    }
}
