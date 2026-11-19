package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue

/**
 * What a pad keeps so its KERF twin can be made again and re-cut: the hit's fitted numbers, the two neighbours BLEED and
 * SLOP read, where the knife landed, and the controls (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md,
 * section 6, the owner's decision A of 2026-11-19: the numbers ride the pad recipe, not a patch type).
 *
 * It is a source, as a [Patch] is: [PadRecipe.render] plays it and then runs whatever rack rides the pad.
 */
data class KerfRecipe(
    val patch: KerfPatchData,
    val prev: KerfPatchData?,
    val next: KerfPatchData?,
    val bleed: Double = 1.0,
    val slop: Double = 0.5,
    /** Fixed when the pad was made, so the pad chops the same way after every save; null draws it from the seed. */
    val draw: KerfDraw? = null,
    val controls: KerfRender.Controls = KerfRender.Controls(),
    /** The renderer the pad was made with. A save keeps the model it was heard on. */
    val model: Int = KerfRender.MODEL_VERSION,
) {
    init {
        require(bleed.isFinite() && bleed in 0.0..4.0) { "bleed must be within 0..4: $bleed" }
        require(slop.isFinite() && slop in 0.0..1.0) { "slop must be within 0..1: $slop" }
        require(model == KerfRender.MODEL_VERSION) { "unsupported KERF model $model" }
    }

    fun render(): Snip = KerfRender.toSnip(KerfRender.chopped(patch, prev, next, bleed, slop, controls, draw).samples)

    fun toJsonValue(): JsonValue.Obj {
        val o = LinkedHashMap<String, JsonValue>()
        o["model"] = JsonValue.Num(model.toDouble())
        o["patch"] = patch.toJsonValue()
        prev?.let { o["prev"] = it.toJsonValue() }
        next?.let { o["next"] = it.toJsonValue() }
        o["bleed"] = JsonValue.Num(bleed); o["slop"] = JsonValue.Num(slop)
        draw?.let { o["draw"] = JsonValue.Arr(listOf(JsonValue.Num(it.start), JsonValue.Num(it.end), JsonValue.Num(it.which))) }
        o["cut"] = JsonValue.Num(controls.cut); o["floor"] = JsonValue.Num(controls.floor)
        o["decay"] = JsonValue.Num(controls.decay); o["tune"] = JsonValue.Num(controls.tuneSemitones)
        return JsonValue.Obj(o)
    }

    companion object {
        fun fromJsonValue(value: JsonValue): KerfRecipe {
            val o = value.obj()
            return KerfRecipe(
                patch = KerfPatchData.fromJsonValue(o.getValue("patch")),
                prev = o["prev"]?.let(KerfPatchData::fromJsonValue), next = o["next"]?.let(KerfPatchData::fromJsonValue),
                bleed = o["bleed"]?.num() ?: 1.0, slop = o["slop"]?.num() ?: 0.5,
                draw = o["draw"]?.arr()?.let { KerfDraw(it[0].num(), it[1].num(), it[2].num()) },
                controls = KerfRender.Controls(
                    cut = o["cut"]?.num() ?: 1.0, floor = o["floor"]?.num() ?: 1.0,
                    decay = o["decay"]?.num() ?: 1.0, tuneSemitones = o["tune"]?.num() ?: 0.0,
                ),
                model = o["model"]?.int() ?: KerfRender.MODEL_VERSION,
            )
        }
    }
}
