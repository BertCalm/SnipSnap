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

    /**
     * The pad struck at [velocity] (0..1, [KerfVelocity]): the hit softer, duller and with a softer tick, the
     * neighbours' bleed where it was. Velocity 1 is [render], byte for byte.
     */
    fun renderAt(velocity: Double): Snip =
        if (velocity >= 1.0) render()
        else KerfRender.toSnip(KerfRender.chopped(KerfVelocity.strike(patch, velocity), prev, next, bleed, slop, controls, draw, KerfVelocity.hitGain(velocity)).samples)

    /**
     * Take [take] of a round robin at [velocity]: the hit cut again with another knife and other noise
     * ([KerfVelocity.takeDraw], [KerfVelocity.takeSeed]), so each take is a fresh strike. Take 0, or [amount] 0, is
     * [renderAt] itself.
     */
    fun take(take: Int, velocity: Double = 1.0, amount: Double = 1.0): Snip {
        if (take == 0 || amount <= 0.0) return renderAt(velocity)
        val own = draw ?: KerfRender.drawFor(patch.seed)
        val knife = KerfVelocity.takeDraw(own, patch.seed, take, amount)
        // The noise draw is what makes a take a fresh strike, but one draw of a short noisy hit can land 0.65x or
        // 1.36x as bright as another (kit D's snare, measured 2026-11-20): a different drum, not another hit of this
        // one. So each take tries [TAKE_CANDIDATES] draws and keeps the one whose band energies sit nearest the pad's
        // own at this velocity. Deterministic: the candidates and the choice depend only on the take.
        val reference = KerfRender.bandEnergies(renderAt(velocity).samples)
        var best: FloatArray? = null; var bestDistance = Double.MAX_VALUE
        for (k in 0 until TAKE_CANDIDATES) {
            val struck = KerfVelocity.strike(patch.copy(seed = KerfVelocity.takeSeed(patch.seed, take * TAKE_CANDIDATES + k)), velocity)
            val samples = KerfRender.chopped(struck, prev, next, bleed, slop, controls, knife, KerfVelocity.hitGain(velocity)).samples
            val d = profileDistance(reference, KerfRender.bandEnergies(samples))
            if (d < bestDistance) { bestDistance = d; best = samples }
        }
        return KerfRender.toSnip(best!!)
    }

    /** Mean |dB| between two band-energy profiles, over the bands within 30 dB of [reference]'s loudest. */
    private fun profileDistance(reference: DoubleArray, candidate: DoubleArray): Double {
        val top = reference.max(); var sum = 0.0; var n = 0
        for (b in reference.indices) {
            if (reference[b] < top * 1e-3) continue
            sum += kotlin.math.abs(10 * kotlin.math.log10((candidate[b] + 1e-30) / (reference[b] + 1e-30))); n++
        }
        return if (n == 0) 0.0 else sum / n
    }

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
        /** Noise draws a round-robin take chooses among ([take]). */
        const val TAKE_CANDIDATES = 4

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
