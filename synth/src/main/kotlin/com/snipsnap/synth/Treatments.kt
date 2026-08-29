package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue

/**
 * The remix bank's five characters, available one pad at a time —
 * reversed, crushed, slapback, washed, punched. [amount] scales the
 * chain's macros linearly (1 = the bank's own settings, lower = subtler);
 * structure switches like reverse stay switched.
 *
 * Every application returns the fx-only recipe that made it, so a treated
 * pad remains re-treatable and undoable like everything else in a kit.
 */
object Treatments {

    val names: List<String> get() = Shuffle.TREATMENTS.map { it.first }

    fun chain(name: String, amount: Float = 1f): FxChain {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        val base = Shuffle.TREATMENTS.firstOrNull { it.first == name }?.second
            ?: throw IllegalArgumentException(
                "unknown treatment '$name' - try one of: ${names.joinToString(", ")}",
            )
        // An unknown name is refused even at AMT 0 - a typo must not
        // silently become "no treatment". Checked above, before bypass.
        if (amount <= 0f) return FxChain()
        if (amount >= 0.999f) return base
        fun scale(params: Map<String, Float>?): Map<String, Float>? =
            params?.mapValues { (_, v) -> (v * amount).coerceIn(0f, 1f) }
        return base.copy(
            eq = scale(base.eq),
            squash = scale(base.squash),
            crunch = scale(base.crunch),
            tape = scale(base.tape),
            echo = scale(base.echo),
            spring = scale(base.spring),
        )
    }

    data class Treated(val snip: Snip, val recipe: JsonValue.Obj)

    fun apply(name: String, snip: Snip, amount: Float = 1f): Treated {
        val fx = chain(name, amount)
        if (fx.isBypass) return Treated(snip, PadRecipe(fx = fx).toJsonValue())
        return Treated(fx.process(snip), PadRecipe(fx = fx).toJsonValue())
    }
}
