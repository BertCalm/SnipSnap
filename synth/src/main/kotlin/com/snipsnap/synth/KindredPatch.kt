package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved KINDRED pad: voice, macros, how hard it was hit, which take it is, and the kit recipe it was made from
 * (null for a plain voice), so a kit's velocity zones and round-robin takes replay byte for byte from their recipe.
 * The model version travels with it so a later re-voicing cannot silently change a saved pad.
 */
data class KindredPatch(
    override val name: String,
    val voice: KindredVoice,
    override val macros: Map<String, Float>,
    val velocity: Float = 1f,
    val take: Int = 0,
    val recipe: String? = null,
    val model: Int = Kindred.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Kindred.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "KINDRED velocity out of 0..1: $velocity" }
        require(take in 0..Kindred.MAX_TAKE) { "KINDRED take out of 0..${Kindred.MAX_TAKE}: $take" }
        require(model == Kindred.MODEL_VERSION) { "KINDRED model $model is not the one this build renders (${Kindred.MODEL_VERSION})" }
        if (recipe != null) {
            require(KindredRecipes.has(recipe)) { "no KINDRED recipe $recipe" }
            require(KindredRecipes.byId(recipe).voice == voice) { "KINDRED recipe $recipe does not play $voice" }
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Kindred.render(voice, macros, velocity, take, recipe)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["take"] = JsonValue.Num(take.toDouble())
        obj["recipe"] = recipe?.let { JsonValue.Str(it) } ?: JsonValue.Null
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "KINDRED"
        const val VERSION = Patches.VERSION

        /** The patch for a kit recipe, at its own macros. */
        fun of(recipe: KindredRecipes.Recipe, velocity: Float = 1f, take: Int = 0): KindredPatch =
            KindredPatch(recipe.name, recipe.voice, Kindred.settled(recipe.voice, recipe.macros), velocity, take, recipe.id)

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            val take = obj["take"]?.takeUnless { it is JsonValue.Null }?.int() ?: 0
            val recipe = obj["recipe"]?.takeUnless { it is JsonValue.Null }?.str()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Kindred.MODEL_VERSION
            return Patches.decode(value, ENGINE, { n -> KindredVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                KindredPatch(name, voice, macros, velocity, take, recipe, model)
            }
        }

        fun fromJsonText(text: String): KindredPatch = fromJsonValue(Json.parse(text)) as KindredPatch
    }
}
