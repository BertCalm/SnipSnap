package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue

/**
 * The rack's named characters, available one pad at a time — the remix
 * bank's five (reversed, crushed, slapback, washed, punched) and the
 * ones that arrived after the bank's table was pinned ([EXTRA]).
 * [amount] fades every macro toward its own neutral (1 = the character's
 * own settings, 0 = every macro at the point where it does nothing) — see
 * [fade]. A macro whose neutral is 0 fades to silence like a plain scale
 * always did, but a centered one (EQ's flat, PITCH's native) fades toward
 * its centre instead of being dragged off it, so a centered macro sitting
 * below its neutral actually *rises* as [amount] falls; structure switches
 * like reverse stay switched.
 *
 * Every application returns the fx-only recipe that made it, so a treated
 * pad remains re-treatable and undoable like everything else in a kit.
 */
object Treatments {

    /**
     * Characters the seeded remix bank never rolls: `Shuffle.TREATMENTS`
     * is indexed by a seeded `Random`, so adding to *that* list would
     * change what every saved seed produces. New characters land here
     * instead — `treat` and the pad sheet see all of them, bank B keeps
     * its five.
     */
    internal val EXTRA: List<Pair<String, FxChain>> = listOf(
        // The attack gone, the wash kept: a hit played as its own tail.
        "smeared" to FxChain(smear = mapOf("AMOUNT" to 0.85f)),
        // The tone and the attack gone, the breath kept.
        "ghosted" to FxChain(ghost = mapOf("AMOUNT" to 0.9f)),
        // The capstan lets go: pitch and level fall away over the last stretch.
        "stopped" to FxChain(motion = mapOf("STOP" to 0.6f)),
        // The reel spins up into the sound.
        "started" to FxChain(motion = mapOf("START" to 0.5f)),
        // The banded smear: the click goes, the thump stays.
        "skimmed" to FxChain(smear = mapOf("AMOUNT" to 0.9f, "FLOOR" to 0.6f)),
        // A dub of a dub of a dub.
        "dubbed" to FxChain(dub = mapOf("GENERATIONS" to 0.6f)),
        // The sound arrives before it strikes.
        "swelled" to FxChain(swell = mapOf("RISE" to 0.6f)),
        // The attack leaned on rather than taken away.
        "spiked" to FxChain(spike = mapOf("ATTACK" to 0.7f, "SUSTAIN" to 0.45f)),
        // Multiplied by a sine: metal, bells, radio.
        "ringed" to FxChain(ring = mapOf("FREQ" to 0.45f, "MIX" to 0.5f)),
        // The record under the hit: rumble, groove, and every play before this one.
        "vinyl" to FxChain(vinyl = mapOf("CRACKLE" to 0.5f, "RUMBLE" to 0.3f, "HISS" to 0.35f)),
        // Four allpasses, swept.
        "phased" to FxChain(phase = mapOf("RATE" to 0.3f, "DEPTH" to 0.7f, "FEEDBACK" to 0.4f)),
        // The transport: the same hit, played slower.
        "pitched" to FxChain(speed = mapOf("SEMITONES" to 0.25f)),
    )

    private val ALL: List<Pair<String, FxChain>> get() = Shuffle.TREATMENTS + EXTRA

    val names: List<String> get() = ALL.map { it.first }

    fun chain(name: String, amount: Float = 1f): FxChain {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        val base = ALL.firstOrNull { it.first == name }?.second
            ?: throw IllegalArgumentException(
                "unknown treatment '$name' - try one of: ${names.joinToString(", ")}",
            )
        // An unknown name is refused even at AMT 0 - a typo must not
        // silently become "no treatment". Checked above, before bypass.
        if (amount <= 0f) return FxChain()
        if (amount >= 0.999f) return base
        return fade(base, amount)
    }

    /**
     * [chain]'s macros faded toward their neutrals by [amount] — 1 leaves the
     * chain alone, 0 lands every macro where it does nothing. A macro whose
     * neutral is 0 fades to silence, exactly as `v * amount` always did; a
     * centered one (EQ's flat, PITCH's native) fades to its centre instead of
     * being dragged off it.
     */
    fun fade(chain: FxChain, amount: Float): FxChain {
        var out = chain
        for (name in FxChain.SECTION_NAMES) {
            val macros = chain.section(name) ?: continue
            val neutrals = FxChain.macrosOf(name).associate { it.name to it.neutral }
            out = out.withSection(
                name,
                macros.mapValues { (macro, v) ->
                    val neutral = neutrals[macro] ?: 0f
                    (neutral + (v - neutral) * amount).coerceIn(0f, 1f)
                },
            )
        }
        return out
    }

    data class Treated(val snip: Snip, val recipe: JsonValue.Obj)

    fun apply(name: String, snip: Snip, amount: Float = 1f): Treated {
        val fx = chain(name, amount)
        val recipe = PadRecipe(fx = fx, treatment = name, amount = amount).toJsonValue()
        if (fx.isBypass) return Treated(snip, recipe)
        return Treated(fx.process(snip), recipe)
    }
}
