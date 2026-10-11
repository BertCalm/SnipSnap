package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved additive note. TUNE fixes its note within the voice's register; the
 * spectral and envelope templates, group profiles and velocity are part of
 * the recipe, so editing macros never silently resets partial development.
 */
data class PrismPatch(
    override val name: String,
    val voice: PrismVoice,
    override val macros: Map<String, Float>,
    val velocity: Float = 1f,
    val template: PrismTemplate = Prism.templateFor(voice),
    val model: Int = Prism.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Prism.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "PRISM velocity out of 0..1: $velocity" }
        require(model == Prism.MODEL_VERSION) { "unsupported prism model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Prism.render(voice, macros, velocity, template)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["template"] = JsonValue.Obj(linkedMapOf(
            "spectralId" to JsonValue.Str(template.spectralId.name),
            "envelopeId" to JsonValue.Str(template.envelopeId.name),
            "ratioOverrides" to numbers(template.ratioOverrides),
            "amplitudeOverrides" to numbers(template.amplitudeOverrides),
            "groupProfiles" to JsonValue.Arr(template.groupProfiles.map { group ->
                JsonValue.Obj(linkedMapOf(
                    "attackScale" to JsonValue.Num(group.attackScale.toDouble()),
                    "decayScale" to JsonValue.Num(group.decayScale.toDouble()),
                    "delaySeconds" to JsonValue.Num(group.delaySeconds.toDouble()),
                    "motionCycles" to JsonValue.Num(group.motionCycles.toDouble()),
                    "motionDepth" to JsonValue.Num(group.motionDepth.toDouble()),
                ))
            }),
            "attackSeconds" to (template.attackSeconds?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "decaySeconds" to (template.decaySeconds?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "loopSeconds" to JsonValue.Num(template.loopSeconds.toDouble()),
        ))
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "PRISM"
        const val VERSION = Patches.VERSION

        private fun numbers(values: List<Float>) = JsonValue.Arr(values.map { JsonValue.Num(it.toDouble()) })

        private fun templateFrom(value: JsonValue, fallback: PrismTemplate): PrismTemplate {
            val obj = value.obj()
            fun optionalFloat(name: String): Float? = obj[name]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat()
            fun floats(name: String, default: List<Float>): List<Float> =
                obj[name]?.takeUnless { it is JsonValue.Null }?.arr()?.map { it.num().toFloat() } ?: default
            val spectralId = obj["spectralId"]?.str()?.let { name ->
                PrismSpectrum.entries.firstOrNull { it.name == name } ?: throw JsonException("unknown prism spectrum $name")
            } ?: fallback.spectralId
            val envelopeId = obj["envelopeId"]?.str()?.let { name ->
                PrismContour.entries.firstOrNull { it.name == name } ?: throw JsonException("unknown prism envelope $name")
            } ?: fallback.envelopeId
            val groups = obj["groupProfiles"]?.takeUnless { it is JsonValue.Null }?.arr()?.map { value ->
                val group = value.obj()
                PrismGroupProfile(
                    attackScale = group["attackScale"]?.num()?.toFloat() ?: 1f,
                    decayScale = group["decayScale"]?.num()?.toFloat() ?: 1f,
                    delaySeconds = group["delaySeconds"]?.num()?.toFloat() ?: 0f,
                    motionCycles = group["motionCycles"]?.int() ?: 1,
                    motionDepth = group["motionDepth"]?.num()?.toFloat() ?: 1f,
                )
            } ?: fallback.groupProfiles
            return PrismTemplate(
                spectralId = spectralId,
                envelopeId = envelopeId,
                ratioOverrides = floats("ratioOverrides", fallback.ratioOverrides),
                amplitudeOverrides = floats("amplitudeOverrides", fallback.amplitudeOverrides),
                groupProfiles = groups,
                attackSeconds = if ("attackSeconds" in obj) optionalFloat("attackSeconds") else fallback.attackSeconds,
                decaySeconds = if ("decaySeconds" in obj) optionalFloat("decaySeconds") else fallback.decaySeconds,
                loopSeconds = optionalFloat("loopSeconds") ?: fallback.loopSeconds,
            )
        }

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Prism.MODEL_VERSION
            if (model != Prism.MODEL_VERSION) throw JsonException("unsupported prism model $model")
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            return Patches.decode(value, ENGINE, { n -> PrismVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val template = obj["template"]?.takeUnless { it is JsonValue.Null }
                    ?.let { templateFrom(it, Prism.templateFor(voice)) } ?: Prism.templateFor(voice)
                PrismPatch(name, voice, macros, velocity, template, model)
            }
        }

        fun fromJsonText(text: String): PrismPatch = fromJsonValue(Json.parse(text)) as PrismPatch
    }
}
