package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import kotlin.math.ln

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
        // AMT 0.7 DRIVE's LOG fade law walks the gain 70 percent of the way there in dB, to gain 51 at
        // DRIVE 0.80, where the old linear walk of the knob left it at 0.7, VALVE's own gain 11.3) with little
        // sag and the speaker barely in. TONE is left out (its centre is its neutral); CAB takes a small
        // non-neutral value so AMT can move it (a missing macro would be VALVE's default 0.6). The
        // owner's pick, heard against a hot crunch and a lead tone at the pedal board's round two
        // (docs/superpowers/specs/2026-10-04-pedal-board-design.md). On a pad that is already through an
        // amp this is a second amp, not a pedal in front of one. apply() fades the pad's last
        // milliseconds before it, see PRE_AMP_FADE.
        "driven" to FxChain(valve = mapOf("DRIVE" to 1.0f, "SAG" to 0.15f, "CAB" to 0.08f)),
        // The sustain pedal (the SUSTAIN chip): the COMPRESSOR section at full AMOUNT, ATTACK and RECOVER left
        // at their defaults (each is its own neutral, so the AMT fade walks AMOUNT alone). It replaced SQUASH at
        // its strongest after the owner heard that as too little twice; passed its gate on 2026-10-24
        // (docs/superpowers/specs/2026-10-23-compressor-section-design.md). The bank's "punched" is SQUASH
        // plus CRUNCH, the punch rather than the sustain.
        "compressed" to FxChain(compressor = mapOf("AMOUNT" to 1.0f)),
        // A wah: a resonant band-pass swept by a free-running, flat-topped sine. AUTO is the owner's pick of the
        // three moves at the pedal board's round-two listen; SPEED and Q are left out (each is its own
        // neutral, so AMT could never move it). The WAH chip is this character once its gate passes.
        "wahed" to FxChain(wah = mapOf("AUTO" to 1.0f)),
        // The same section driven by the pad's own envelope: opens on the hit, closes as the note dies.
        // CLI and saved kits only: no chip of its own.
        "touchwahed" to FxChain(wah = mapOf("TOUCH" to 1.0f)),
        // One heel-to-toe-to-heel gesture, then it waits. CLI and saved kits only.
        "rockwahed" to FxChain(wah = mapOf("ROCK" to 1.0f)),
        // A delay pedal: ECHO's long, dark repeats (225 ms apart, feedback 0.66, darkening) fed only the note's
        // first 120 ms, so a held note's attack comes back as a repeat. MIX 0.75 is the owner's pick
        // (docs/superpowers/specs/2026-10-22-delay-head-design.md). The DELAY chip is this character once its gate passes.
        "delayed" to FxChain(echo = mapOf("TIME" to 0.75f, "REPEAT" to 0.85f, "TONE" to 0.35f, "MIX" to 0.75f, "HEAD" to 1.0f)),
        // A hit formed against a die (the FORGE chip): every macro at FORGE's own default with the die shut, ARREST 1,
        // the clip the owner heard as "loud and solid" at the gate (2026-10-26), the heat at impact heard as heat,
        // not a click; the defaults heard as fine on a hat. Its medium is TIGHT since the CHARGE gate (2026-10-27),
        // the chip the owner picked on the kick and the snare there. AMT walks all four
        // toward their neutrals, YIELD's being 1 (nothing flows), so a lower AMT is a softer die over less shock
        // and less fold. docs/superpowers/specs/2026-10-25-forge-section-design-brief.md, "Heard".
        "forged" to FxChain(forge = mapOf("CHARGE" to 0.5f, "STANDOFF" to 0.5f, "YIELD" to 0.5f, "ARREST" to 1.0f)),
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
    fun fade(chain: FxChain, amount: Float): FxChain = fade(chain, amount, laws = true)

    /**
     * [fade] with every macro walked linearly, the law before [FadeLaw] existed: what a [FadeLaw.LOG]
     * macro would have faded to. For the tests that pin "byte-identical except where a LOG macro is
     * faded" and the fade-law listen; nothing in the product calls it.
     */
    internal fun linearFade(chain: FxChain, amount: Float): FxChain = fade(chain, amount, laws = false)

    private fun fade(chain: FxChain, amount: Float, laws: Boolean): FxChain {
        var out = chain
        for (name in FxChain.SECTION_NAMES) {
            val macros = chain.section(name) ?: continue
            val specs = FxChain.macrosOf(name).associateBy { it.name }
            out = out.withSection(
                name,
                macros.mapValues { (macro, v) ->
                    val spec = specs[macro]
                    val neutral = spec?.neutral ?: 0f
                    if (laws && spec?.law == FadeLaw.LOG) {
                        val gain = FxChain.gainOf(name, macro)
                            ?: error("$name $macro fades by LOG but its section declares no gain law")
                        logFade(gain, neutral, v, amount)
                    } else {
                        (neutral + (v - neutral) * amount).coerceIn(0f, 1f)
                    }
                },
            )
        }
        return out
    }

    /**
     * The knob between [neutral] and [value] whose [gain] is [amount] of the way from the neutral's
     * gain to the value's in decibels (log gain): exactly [neutral] at 0, exactly [value] at 1. The
     * law is VALVE's own ([Valve.gainFor]), searched by bisection rather than inverted by hand, so the
     * gain law lives in one place. It must be positive and monotonic between the two ends; VALVE's is
     * strictly increasing (V1's exponential up to its pivot, log-linear above it).
     */
    private fun logFade(gain: (Float) -> Float, neutral: Float, value: Float, amount: Float): Float {
        if (amount <= 0f) return neutral
        if (amount >= 1f || value == neutral) return value
        val gn = gain(neutral).toDouble()
        val gv = gain(value).toDouble()
        require(gn > 0.0 && gv > 0.0) { "a LOG macro's gain must be positive at both ends: $gn and $gv" }
        val target = ln(gn) + (ln(gv) - ln(gn)) * amount
        var lo = minOf(neutral, value).toDouble()
        var hi = maxOf(neutral, value).toDouble()
        val rising = gain(hi.toFloat()) >= gain(lo.toFloat())
        repeat(60) {
            val mid = 0.5 * (lo + hi)
            if ((ln(gain(mid.toFloat()).toDouble()) < target) == rising) lo = mid else hi = mid
        }
        return (0.5 * (lo + hi)).toFloat().coerceIn(0f, 1f)
    }

    data class Treated(val snip: Snip, val recipe: JsonValue.Obj)

    /**
     * The fade that goes ahead of a hot amp ([needsPreAmpFade] says which chains run one). VALVE lifts a quiet tail by
     * its own gain (1000 at the top of DRIVE), so a cut nobody hears dry ends in a click through it,
     * and a fade ahead of the amp survives the amp (MAGNET's lesson). The fade is the tail's alone:
     * its length follows the amp's lift (see [endFaded]: 57 percent of the full length at the pad
     * sheet's AMT 0.7 under DRIVE's LOG fade law, a third under the old linear one; none where the amp
     * does not lift, which is AMT under about 0.30), and it never reaches the first half of a
     * pad. The full length and shape are chosen against the spec's bars and no others, from a declared grid:
     * the fade must leave a snare and a choir ending at least 53 dB under their peaks (the spec's 50 dB
     * plus 3 dB of margin) with a DC mean under 1e-4, at AMT 0.7, 0.8, 0.9 and 1, and the grid is 30 to
     * 120 ms (steps of 5 to 10) and powers 2 to 7. The first cell of that grid, by length and then by
     * power, that passes is 95 ms at power 5: 95 ms at power 4 ends the snare at AMT 1 at -52.0 dB, and
     * 90 ms misses the DC bar at every power up to 7 (1.02e-4 at best), because a saturated tail the
     * fade has not reached leaves its tube's asymmetry in the mean. Finer cells pass too (94 ms at
     * power 5 by under 1 percent, 90 ms at power 8): they were not taken, because the DC mean moves a
     * few percent from one millisecond to the next, so a pass that close to its bar is fragile, and
     * a power of 8 is not mild. No claim is made of a 1 ms optimum.
     */
    private val PRE_AMP = EndFade(ms = 95f, power = 5)

    private class EndFade(val ms: Float, val power: Int)

    /**
     * Whether [chain] runs the hot amp the fade exists for: its VALVE DRIVE sits above VALVE's own
     * default. Keyed on the chain, not on a character's name, so a pedal board's amp pass and any
     * later character that pushes DRIVE get the same fade `driven` does; `amped` (DRIVE 0.6, under
     * the default 0.7) does not, as before.
     */
    fun needsPreAmpFade(chain: FxChain): Boolean {
        val drive = chain.section("valve")?.get("DRIVE") ?: return false
        return drive > Valve.defaults().getValue("DRIVE")
    }

    /** The log of the gain the chain's VALVE DRIVE sets, never under 0: an amp that does not lift needs no fade. */
    private fun ampLift(fx: FxChain): Double {
        val drive = fx.section("valve")?.get("DRIVE") ?: Valve.defaults().getValue("DRIVE")
        return ln(Valve.gainFor(drive).toDouble()).coerceAtLeast(0.0)
    }

    /**
     * [snip] with its tail faded ahead of the amp that [faded] runs, where [full] (the same chain
     * before AMT faded it) [needsPreAmpFade]; [snip] itself otherwise, or where the amp does not
     * lift. The fade is [PRE_AMP]'s length times the amp's lift in [faded] over its lift in [full]
     * (the dB of the gain DRIVE sets at this AMT, against the dB it sets at the full character), at
     * most the pad's second half, and shapes the gain by `x^power` (x is 0 at the last frame, 1 at
     * the fade's start). Everything before the fade is [snip]'s own samples.
     */
    internal fun endFaded(snip: Snip, faded: FxChain, full: FxChain): Snip {
        if (!needsPreAmpFade(full)) return snip
        val fullLift = ampLift(full)
        if (fullLift <= 0.0) return snip
        val lift = (ampLift(faded) / fullLift).coerceAtMost(1.0)
        val frames = snip.frameCount
        val n = minOf(frames / 2, (PRE_AMP.ms / 1000.0 * lift * snip.sampleRate).toInt())
        if (n <= 0) return snip
        val out = snip.samples.copyOf()
        for (d in 0 until n) {
            val g = Math.pow(d.toDouble() / n, PRE_AMP.power.toDouble()).toFloat()
            val frame = frames - 1 - d
            for (c in 0 until snip.channels) out[frame * snip.channels + c] *= g
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** [endFaded] for the character [name] at [amount]: the chain AMT made against the full one. */
    internal fun endFaded(snip: Snip, name: String, amount: Float): Snip {
        if (amount <= 0f) return snip
        return endFaded(snip, chain(name, amount), chain(name, 1f))
    }

    fun apply(name: String, snip: Snip, amount: Float = 1f): Treated {
        val fx = chain(name, amount)
        val recipe = PadRecipe(fx = fx, treatment = name, amount = amount).toJsonValue()
        if (fx.isBypass) return Treated(snip, recipe)
        return Treated(fx.process(endFaded(snip, name, amount)), recipe)
    }
}
