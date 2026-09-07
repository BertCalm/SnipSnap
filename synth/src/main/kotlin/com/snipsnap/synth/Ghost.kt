package com.snipsnap.synth

import com.snipsnap.audio.Separate
import com.snipsnap.audio.Snip

/**
 * GHOST — the tone and the attack taken out, the breath kept.
 *
 * SMEAR removes what a hit *does*; GHOST removes what it *is* as well and
 * leaves only the air around it — the noise layer of the anatomy lesson,
 * brought up to be heard. A kick becomes a puff, a snare becomes rain, a
 * chord becomes the room it was played in. [Separate.ghost] does the work;
 * one macro, AMOUNT, all-zeros transparent. Sits right after SMEAR in the
 * rack — anatomy before tone.
 */
object Ghost {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("AMOUNT", 0.85f),  // how much of the tone and attack goes: none to all
    )

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val amount = m.getValue("AMOUNT")
        if (amount <= 0f) return snip
        return Separate.ghost(snip, amount)
    }
}
