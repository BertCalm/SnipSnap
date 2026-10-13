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
        // The ladder's own contour, dropped onto a hit that was never a synth.
        "contoured" to FxChain(contour = mapOf("CUTOFF" to 0.3f, "CREAM" to 0.6f, "SWEEP" to 0.7f)),
        // Three taps off the finished tone, swimming: the string machine's
        // back half. DEPTH a step past the section's own 0.5, as "phased"
        // and "contoured" sit past theirs: a character is tapped to be
        // heard. RATE is left at its centre — which is its neutral, so
        // setting it here would be a macro AMT could never move.
        "ensembled" to FxChain(ensemble = mapOf("DEPTH" to 0.6f, "WIDTH" to 1f)),
        // A tube amp and its speaker, on a hit that was never a guitar. TONE
        // is left at its centre, which is its neutral and its default: setting
        // it here would be a macro AMT could never move. Its default and
        // neutral are both pinned at 0.5 by ValveTest's `the macros are the
        // four the spec names, with their neutrals`.
        "amped" to FxChain(valve = mapOf("DRIVE" to 0.6f, "SAG" to 0.35f, "CAB" to 0.6f)),
        // The same section with six independent players in place of three locked
        // taps: SECTION at 1 and DEPTH at the players' own 0.5, so the full
        // character is the depth they are written for (AMT fades DEPTH, WIDTH and
        // SECTION together toward their neutrals, so a lower AMT is the chorus
        // coming in under the same six players, all of it shallower and narrower).
        // RATE is left at its
        // centre, its neutral, as "ensembled" leaves it. The names list is pinned
        // in order, so the two pedal recipes below follow it.
        "sectioned" to FxChain(ensemble = mapOf("DEPTH" to 0.5f, "WIDTH" to 1f, "SECTION" to 1f)),
        // A drive pedal: VALVE at the top of its gain law (DRIVE 1.0 is gain 1000; at the pad sheet's
        // AMT 0.7 the recipe's DRIVE fades to 0.7, which is VALVE's own default gain 11.3) with little
        // sag and the speaker barely in. TONE is left out (its centre is its neutral); CAB takes a small
        // non-neutral value so AMT can move it (a missing macro would be VALVE's default 0.6). The
        // owner's pick, heard against a hot crunch and a lead tone at the pedal board's round two
        // (docs/superpowers/specs/2026-10-04-pedal-board-design.md). On a pad that is already through an
        // amp this is a second amp, not a pedal in front of one. apply() fades the pad's last
        // milliseconds before it, see PRE_AMP_FADE.
        "driven" to FxChain(valve = mapOf("DRIVE" to 1.0f, "SAG" to 0.15f, "CAB" to 0.08f)),
        // A compressor pedal for now: the strongest SQUASH there is (AMOUNT at its top, the fastest
        // ATTACK that is not its neutral), the owner's pick at round two. The sustain compressor the
        // owner asked for is its own section (spec, Stage 2); this recipe moves to it then. The bank's
        // "punched" is SQUASH plus CRUNCH; this has no crunch.
        "compressed" to FxChain(squash = mapOf("AMOUNT" to 1.0f, "ATTACK" to 0.05f)),
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

    /**
     * Characters that run a hot amp, and how much of the pad's end fades out BEFORE the chain: VALVE
     * lifts a quiet tail by its own gain (1000 at the top of DRIVE), so a cut nobody hears dry ends
     * in a click through it, and a fade ahead of the amp survives the amp (MAGNET's lesson). The
     * length and shape are measured, not chosen: the shortest, mildest fade from 20, 40, 80 and 150 ms
     * at powers 1 to 3 for which a snare and a choir end at least 53 dB under their peaks at AMT 0.7
     * and 1, with a DC mean under 1e-4. Only 150 ms at power 3 qualifies (the snare at AMT 1 ends
     * 56.3 dB under its peak, against 9.1 with no fade and 32.9 for 80 ms at power 2).
     */
    private val PRE_AMP_FADE: Map<String, EndFade> = mapOf("driven" to EndFade(ms = 150f, power = 3))

    private class EndFade(val ms: Float, val power: Int)

    /** [snip] with its last [fade] milliseconds faded to silence by `x^power` (x is 0 at the last frame, 1 at the fade's start). */
    private fun endFaded(snip: Snip, fade: EndFade): Snip {
        val frames = snip.frameCount
        val n = minOf(frames, (fade.ms / 1000f * snip.sampleRate).toInt())
        if (n <= 0) return snip
        val out = snip.samples.copyOf()
        for (d in 0 until n) {
            val g = Math.pow(d.toDouble() / n, fade.power.toDouble()).toFloat()
            val frame = frames - 1 - d
            for (c in 0 until snip.channels) out[frame * snip.channels + c] *= g
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    fun apply(name: String, snip: Snip, amount: Float = 1f): Treated {
        val fx = chain(name, amount)
        val recipe = PadRecipe(fx = fx, treatment = name, amount = amount).toJsonValue()
        if (fx.isBypass) return Treated(snip, recipe)
        val input = PRE_AMP_FADE[name]?.let { endFaded(snip, it) } ?: snip
        return Treated(fx.process(input), recipe)
    }
}
