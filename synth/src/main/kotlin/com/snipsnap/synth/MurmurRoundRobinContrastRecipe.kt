package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A separate opt-in repeat identity for finite selected model-4 PLUCK shapes. The immutable JSON
 * owns every source control. It is a separate source format, not a new voice or PadRecipe,
 * and does not change v1 recipes, the selected v1 bank, or ordinary Patch/cache identity.
 * Operator starting phases and transient index/contact duration vary; root tuning stays fixed.
 */
class MurmurRoundRobinContrastRecipe(patch: MurmurPatch, val take: Int) {
    private val sourcePatchJson: String = patch.toJsonText()
    val selectionId: String

    init {
        require(take in 0 until TAKE_COUNT) { "MURMUR round-robin take must be 0..3" }
        val source = this.patch()
        require(Json.parse(sourcePatchJson) == source.toJsonValue()) {
            "MURMUR round-robin source must be complete and canonical"
        }
        require(source.macros.keys == Murmur.defaults(source.voice).keys) {
            "MURMUR round-robin source must save every control"
        }
        val eligible = requireNotNull(MurmurSelectedInstrumentSource.eligibility(source)) {
            "MURMUR round-robin needs a supported selected model-4 PLUCK source"
        }
        require(take == 0 || source.macros.getValue("BREATH") > 0f) {
            "MURMUR native breath round-robin needs positive BREATH"
        }
        require(source.macros.getValue("HOLD") == 0f) {
            "MURMUR contrast round-robin is finite only; held sources retain their existing policy"
        }
        selectionId = eligible.selectionId
    }

    /** A fresh deep decode on every read prevents caller mutations from changing later playback. */
    fun patch(): MurmurPatch = MurmurPatch.fromJsonText(sourcePatchJson)
    fun render() = Murmur.renderRoundRobinContrast(patch(), take)

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "kind" to JsonValue.Str(KIND),
        "version" to JsonValue.Num(VERSION.toDouble()),
        "policy" to JsonValue.Str(POLICY),
        "selectionId" to JsonValue.Str(selectionId),
        "take" to JsonValue.Num(take.toDouble()),
        "sourcePatch" to Json.parse(sourcePatchJson),
    ))
    fun toJsonText(): String = Json.write(toJsonValue())
    override fun equals(other: Any?): Boolean = other is MurmurRoundRobinContrastRecipe &&
        take == other.take && patch() == other.patch()
    override fun hashCode(): Int = 31 * patch().hashCode() + take

    companion object {
        const val KIND = "MURMUR_SELECTED_ROUND_ROBIN_CONTRAST"
        const val VERSION = 2
        const val POLICY = "NATIVE_OPERATOR_CONTACT_V2"
        const val TAKE_COUNT = 4

        fun fromJsonText(text: String): MurmurRoundRobinContrastRecipe = fromJsonValue(Json.parse(text))
        fun fromJsonValue(value: JsonValue): MurmurRoundRobinContrastRecipe {
            val obj = value.obj()
            require(obj.keys == setOf("kind", "version", "policy", "selectionId", "take", "sourcePatch")) {
                "MURMUR round-robin has missing or unknown fields"
            }
            require(obj.getValue("kind").str() == KIND && obj.getValue("version").int() == VERSION &&
                obj.getValue("policy").str() == POLICY) { "Unsupported MURMUR round-robin policy" }
            val raw = obj.getValue("sourcePatch")
            val patch = MurmurPatch.fromJsonValue(raw) as MurmurPatch
            require(raw == patch.toJsonValue()) { "MURMUR round-robin source is not complete and canonical" }
            val recipe = MurmurRoundRobinContrastRecipe(patch, obj.getValue("take").int())
            require(obj.getValue("selectionId").str() == recipe.selectionId) {
                "MURMUR round-robin selection disagrees with the complete source"
            }
            return recipe
        }
    }
}
