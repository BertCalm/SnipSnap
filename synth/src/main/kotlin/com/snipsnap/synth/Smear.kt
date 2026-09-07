package com.snipsnap.synth

import com.snipsnap.audio.Separate
import com.snipsnap.audio.Snip

/**
 * SMEAR — the Séance's first trick, as a rack section: the attack taken
 * out, the wash kept.
 *
 * Every other section in the rack colours a hit; this one changes what
 * the hit *is*. A snare becomes the room it was struck in, a kick
 * becomes its own sub bloom, a hat becomes air — the sampling-era move
 * of playing the tail of a sound as the sound. The DSP is
 * [Separate.smear]: the STN transient mask, scaled by AMOUNT, taken
 * away from every bin, the result brought back to the source's own
 * peak (capped) so what remains is heard rather than merely left.
 *
 * One macro, 0..1, all-zeros transparent, same discipline as every
 * other section. It sits right after REVERSE in [FxChain] — anatomy
 * before tone — so the smeared body is what EQ, SQUASH and the rest
 * shape.
 */
object Smear {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("AMOUNT", 0.75f),  // how much of the attack goes: none to all
    )

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val amount = m.getValue("AMOUNT")
        if (amount <= 0f) return snip
        return Separate.smear(snip, amount)
    }
}
