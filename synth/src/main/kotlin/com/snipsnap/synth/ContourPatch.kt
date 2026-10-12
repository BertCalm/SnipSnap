package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete original wavetable recipe. Table selection and layer/envelope relationships
 * stay with the patch when macros change; each render creates fresh oscillator/filter state.
 * TUNE stores the requested note relative to the voice's root. Velocity is excitation energy.
 */
data class ContourPatch(
    override val name: String,
    val voice: ContourVoice,
    override val macros: Map<String, Float>,
    val config: ContourConfig = ContourConfig.forVoice(voice),
    val velocity: Float = 1f,
    val model: Int = ContourEngine.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, ContourEngine.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "CONTOUR velocity out of 0..1: $velocity" }
        require(model == ContourEngine.MODEL_VERSION) { "unsupported contour model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = ContourEngine.render(voice, macros, velocity, config)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["config"] = encodeConfig(config)
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "CONTOUR"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: ContourEngine.MODEL_VERSION
            if (model != ContourEngine.MODEL_VERSION) throw JsonException("unsupported contour model $model")
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            return Patches.decode(value, ENGINE, { n -> ContourVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                // Additive timbre controls must stay neutral for previously saved model-1 recipes,
                // including recipes that predate the config object. Fresh voice defaults may evolve.
                val legacy = ContourConfig.legacyForVoice(voice)
                val config = obj["config"]?.takeUnless { it is JsonValue.Null }
                    ?.let { decodeConfig(it, legacy) } ?: legacy
                ContourPatch(name, voice, macros, config, velocity, model)
            }
        }

        fun fromJsonText(text: String): ContourPatch = fromJsonValue(Json.parse(text)) as ContourPatch

        private fun encodeConfig(c: ContourConfig): JsonValue.Obj {
            fun n(v: Float) = JsonValue.Num(v.toDouble())
            return JsonValue.Obj(linkedMapOf(
                "bodyTable" to JsonValue.Str(c.bodyTable.name),
                "edgeTable" to JsonValue.Str(c.edgeTable.name),
                "edgeRatio" to n(c.edgeRatio), "edgeLag" to n(c.edgeLag),
                "relation" to JsonValue.Str(c.relation.name),
                "bodyLevel" to n(c.bodyLevel), "edgeLevel" to n(c.edgeLevel), "subLevel" to n(c.subLevel),
                "bodyHarmonics" to n(c.bodyHarmonics), "edgeRootLevel" to n(c.edgeRootLevel),
                "edgeAttack" to n(c.edgeAttack), "edgeDecay" to n(c.edgeDecay), "edgeSustain" to n(c.edgeSustain),
                "bodyScanBias" to n(c.bodyScanBias), "edgeScanBias" to n(c.edgeScanBias),
                "bodyTravel" to n(c.bodyTravel), "edgeTravel" to n(c.edgeTravel),
                "ampAttack" to n(c.ampAttack), "ampDecay" to n(c.ampDecay), "ampSustain" to n(c.ampSustain),
                "ampRelease" to n(c.ampRelease), "sustainSeconds" to n(c.sustainSeconds),
                "filterAttack" to n(c.filterAttack), "filterDecay" to n(c.filterDecay), "filterAmount" to n(c.filterAmount),
                "motionShape" to JsonValue.Str(c.motionShape.name), "motionSeconds" to n(c.motionSeconds),
                "filterType" to JsonValue.Str(c.filterType.name), "loopSeconds" to n(c.loopSeconds),
            ))
        }

        /** Older fields use the voice recipe; missing additive timbre controls use legacy-neutral values. */
        private fun decodeConfig(value: JsonValue, d: ContourConfig): ContourConfig {
            val o = value.obj()
            fun f(key: String, fallback: Float) = o[key]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: fallback
            fun <E : Enum<E>> e(key: String, entries: List<E>, fallback: E): E {
                val name = o[key]?.takeUnless { it is JsonValue.Null }?.str() ?: return fallback
                return entries.firstOrNull { it.name == name } ?: throw JsonException("unknown contour $key $name")
            }
            return ContourConfig(
                bodyTable = e("bodyTable", ContourTable.entries, d.bodyTable),
                edgeTable = e("edgeTable", ContourTable.entries, d.edgeTable),
                edgeRatio = f("edgeRatio", d.edgeRatio), edgeLag = f("edgeLag", d.edgeLag),
                relation = e("relation", ContourRelation.entries, d.relation),
                bodyLevel = f("bodyLevel", d.bodyLevel), edgeLevel = f("edgeLevel", d.edgeLevel), subLevel = f("subLevel", d.subLevel),
                bodyHarmonics = f("bodyHarmonics", 1f), edgeRootLevel = f("edgeRootLevel", 1f),
                edgeAttack = f("edgeAttack", .005f), edgeDecay = f("edgeDecay", .5f), edgeSustain = f("edgeSustain", 1f),
                bodyScanBias = f("bodyScanBias", d.bodyScanBias), edgeScanBias = f("edgeScanBias", d.edgeScanBias),
                bodyTravel = f("bodyTravel", d.bodyTravel), edgeTravel = f("edgeTravel", d.edgeTravel),
                ampAttack = f("ampAttack", d.ampAttack), ampDecay = f("ampDecay", d.ampDecay),
                ampSustain = f("ampSustain", d.ampSustain), ampRelease = f("ampRelease", d.ampRelease),
                sustainSeconds = f("sustainSeconds", d.sustainSeconds),
                filterAttack = f("filterAttack", d.filterAttack), filterDecay = f("filterDecay", d.filterDecay),
                filterAmount = f("filterAmount", d.filterAmount),
                motionShape = e("motionShape", ContourMotionShape.entries, d.motionShape),
                motionSeconds = f("motionSeconds", d.motionSeconds),
                filterType = e("filterType", ContourFilterType.entries, d.filterType), loopSeconds = f("loopSeconds", d.loopSeconds),
            )
        }
    }
}
