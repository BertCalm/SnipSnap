package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * TERRA — world percussion, physically modeled.
 *
 * All four topologies from `TERRA_World_Percussion_Synth_Spec.md` S2.2 are a
 * bank of inharmonic partials, mode ratios/gains/damping curves sourced from
 * that table, excited by a strike:
 * - COMPOUND_MEMBRANE (djembe, dholak, dumbek, tabla) and RESONANT_CAVITY
 *   (udu, cajón) are hand-struck: a FLESH_PALM exciter (a raised-cosine
 *   impulse, no noise), and both carry DROOP (S2.1) - a membrane's or a
 *   cavity's own surface can genuinely sag in pitch under a hard hit.
 * - CONICAL_BELL (agogô) and TUNED_BAR (balafon) are mallet-struck: a
 *   HARD_STICK exciter (a shorter pulse with a noise component scaled by
 *   hardness), and neither carries DROOP - a solid metal bell or wooden bar
 *   has no membrane tension to relax, so the macro would have nothing
 *   physical to control.
 *
 * RESONANT_CAVITY's Helmholtz coupling (S2.3, CAVITY) is specific to it -
 * nothing else here has a coupled air cavity. The parasitic contact buzz
 * (S2.4, BUZZ) is shared by RESONANT_CAVITY and TUNED_BAR (a loose cajón
 * boundary, a balafon's spider-egg membrane), factored into [applyBuzz].
 * MICRO_FLOCK (the spec's third exciter, for seed/scraper instruments) has
 * no user in the spec's own 16-pad kit (S5) and is deferred indefinitely,
 * not landed-later work like the other four topologies were.
 *
 * Unlike [Modes.ring], the modal bank here can't be a fixed two-pole
 * recursion: DROOP needs the fundamental itself to slide during the decay,
 * so each mode is rung by a phase-accumulated oscillator re-tuned every
 * sample from that sliding f0, instead of [Modes.ring]'s single-fundamental
 * filter. The per-mode table still borrows [Modes.Mode] for its shape
 * (ratio/gain/t60), and strike position still borrows [Modes.atPosition] -
 * only the accumulation itself ([strikeAndModalBank]) had to be bespoke,
 * and every topology shares it.
 */
enum class TerraVoice { COMPOUND_MEMBRANE, RESONANT_CAVITY, CONICAL_BELL, TUNED_BAR }

object Terra {

    fun macrosFor(voice: TerraVoice): List<MacroSpec> = when (voice) {
        TerraVoice.COMPOUND_MEMBRANE -> listOf(
            MacroSpec("TUNE", 0.35f),
            MacroSpec("DECAY", 0.5f),
            // FORCE: strike hardness (S2's strikeHardness) - a harder hit is
            // a shorter, sharper exciter pulse.
            MacroSpec("FORCE", 0.5f),
            // POS: strike position, 0 centre .. 1 rim - the same 0..1
            // meaning as Thump.snare's and Pluck's own STRIKE macros, but
            // named differently from them: Fork's STRIKE macro is hammer
            // hardness/velocity (Fork.kt), an unrelated axis, and this
            // engine already has a hardness macro of its own (FORCE) - POS
            // avoids colliding with either.
            MacroSpec("POS", 0.25f),
            MacroSpec("DROOP", 0.23f),
        )
        TerraVoice.RESONANT_CAVITY -> listOf(
            MacroSpec("TUNE", 0.3f),
            MacroSpec("DECAY", 0.5f),
            MacroSpec("FORCE", 0.5f),
            MacroSpec("POS", 0.25f),
            MacroSpec("DROOP", 0.1f),
            // CAVITY: how much the Helmholtz air resonance (S2.3) is mixed
            // into the strike, 0 dry .. 1 full coupling. The point of this
            // topology, so it defaults on rather than off.
            MacroSpec("CAVITY", 0.5f),
            // BUZZ: parasitic contact buzz (S2.4) - a loose boundary rattling
            // against the body, amplitude-gated noise above a threshold.
            // Not called RATTLE: Thump.snare's RATTLE is a decay-TIME control
            // (a multiplier on how long the wires ring), a different axis
            // entirely - this is the same shape as Tines.kalimba's own BUZZ
            // (threshold-gated noise scaled by the excess), just a new
            // per-engine threshold/gain rather than a shared implementation.
            // Off by default: most cavity presets (Udu) don't want it, only
            // a rattly cajón does.
            MacroSpec("BUZZ", 0f),
        )
        TerraVoice.CONICAL_BELL -> listOf(
            MacroSpec("TUNE", 0.4f),
            MacroSpec("DECAY", 0.5f),
            MacroSpec("FORCE", 0.5f),
            MacroSpec("POS", 0.25f),
        )
        TerraVoice.TUNED_BAR -> listOf(
            MacroSpec("TUNE", 0.35f),
            MacroSpec("DECAY", 0.5f),
            MacroSpec("FORCE", 0.5f),
            MacroSpec("POS", 0.25f),
            MacroSpec("BUZZ", 0f),
        )
    }

    /** The factory macro settings for [voice]. */
    fun defaults(voice: TerraVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE, same contract as
        // every other engine, so the exciters' pulse edges and (on
        // RESONANT_CAVITY/TUNED_BAR) the cavity's tanh saturator and the
        // buzz threshold all fold down above 22.05kHz instead of aliasing
        // into the audible band - the source spec's own reference code ran
        // its nonlinear stages straight at 44.1kHz with no oversample.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate)
            TerraVoice.RESONANT_CAVITY -> resonantCavity(m, renderRate)
            TerraVoice.CONICAL_BELL -> conicalBell(m, renderRate)
            TerraVoice.TUNED_BAR -> tunedBar(m, renderRate)
        }
        val out = Dsp.decimate(raw, RATE)
        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    // Mode ratios, gains, and damping curves: TERRA_World_Percussion_Synth_Spec.md
    // S2.2. COMPOUND_MEMBRANE and RESONANT_CAVITY's curves are linear in the
    // mode index (gamma_m = 1 + step*m); CONICAL_BELL's is quadratic (high
    // damping, gamma_m = 1 + 0.85*m^2) and computed inline in [conicalBell]
    // rather than forcing a step constant it doesn't have.
    private val MEMBRANE_RATIOS = floatArrayOf(1.00f, 1.99f, 2.98f, 3.99f, 4.88f, 5.92f)
    private val MEMBRANE_GAINS = floatArrayOf(1.00f, 0.65f, 0.45f, 0.25f, 0.12f, 0.08f)
    private const val MEMBRANE_GAMMA_STEP = 0.65f

    private val CAVITY_RATIOS = floatArrayOf(1.00f, 2.14f, 3.20f, 4.45f)
    private val CAVITY_GAINS = floatArrayOf(1.00f, 0.35f, 0.15f, 0.05f)
    private const val CAVITY_GAMMA_STEP = 1.2f

    private val BELL_RATIOS = floatArrayOf(1.00f, 1.48f, 2.14f, 2.87f, 3.42f, 4.15f)
    private val BELL_GAINS = floatArrayOf(1.00f, 0.72f, 0.45f, 0.30f, 0.18f, 0.09f)
    private const val BELL_GAMMA_QUADRATIC = 0.85f

    // Euler-Bernoulli free-bar series (S2.2's TUNED_BAR row).
    private val BAR_RATIOS = floatArrayOf(1.00f, 6.27f, 17.55f, 34.39f)
    private val BAR_GAINS = floatArrayOf(1.00f, 0.25f, 0.08f, 0.02f)
    private const val BAR_GAMMA_STEP = 1.8f

    // The Helmholtz cavity resonance (S2.3). The spec's own reference
    // derives its resonator from a hand-rolled pole-radius formula tied to
    // one implicit bandwidth; this instead reuses Dsp.Biquad's tested RBJ
    // bandpass with a plain Q, which is both less code and consistent with
    // how every other engine's resonant stages (Svf, TptSvf, Ladder) are
    // built. CAVITY_Q and CAVITY_DRIVE are a first-pass listening call, not
    // derived, same as the exciter/body mix ratio below.
    private const val CAVITY_FREQ_HZ = 75f
    private const val CAVITY_Q = 8f
    private const val CAVITY_DRIVE = 1.15f

    // Parasitic contact buzz (S2.4): only the part of the signal above this
    // threshold rattles, scaled by BUZZ. Numbers carried over from the
    // spec's own reference (its theta_thresh/K_rattle), which had no stated
    // derivation either.
    private const val BUZZ_THRESHOLD = 0.12f
    private const val BUZZ_GAIN = 0.45f

    // HARD_STICK's own strike length (S4's sLen): shorter than FLESH_PALM's
    // shortest (0.003s at FORCE=1), a mallet's tick against a stick's own
    // seed keeps every HARD_STICK voice's own noise stream distinct.
    private const val HARD_STICK_SECONDS = 0.0018f

    private const val TWO_PI = (2.0 * Math.PI).toFloat()

    // -60dB in nepers - the same constant [Modes.ring] uses for its own
    // per-mode decay, so a t60 here means the same thing as a Modes.Mode t60.
    private const val T60_NEPERS = 6.9078f

    // S2.1's tau range is 8-40ms; a droopDecayMs macro to vary this
    // independently of DROOP's depth is follow-up work - no preset needs it
    // yet, and the spec's own default (20ms) sits at this range's centre.
    private const val DROOP_TAU_SECONDS = 0.020f

    // DECAY as RT60 (the house convention - see Dsp.Env's decay2T60), shared
    // by every topology: the fundamental mode (gamma=1) falls 60dB by
    // t60Base exactly. Sizing a render buffer at 1.4x it (see [framesFor],
    // the same margin Thump's kick/tom/etc. use) leaves every mode audibly
    // silent well before the cut, unlike the source spec's own reference
    // code (whose decayMs is a 1/e time constant sized to exactly one
    // buffer length, so its loudest mode is still at -8.7dB, not -60dB,
    // right at the truncation point).
    private const val DECAY_MIN_SECONDS = 0.08f
    private const val DECAY_DEFAULT_SECONDS = 0.35f
    private const val DECAY_MAX_SECONDS = 0.9f
    private fun t60BaseFor(m: Map<String, Float>): Float =
        Dsp.around(m.getValue("DECAY"), DECAY_MIN_SECONDS, DECAY_DEFAULT_SECONDS, DECAY_MAX_SECONDS)

    /** Every topology's render buffer: [t60Base] (the fundamental's own RT60) times 1.4, floored at 64 frames. */
    private fun framesFor(t60Base: Float, rate: Int): Int =
        (t60Base * 1.4f * rate).toInt().coerceAtLeast(64)

    /**
     * FLESH_PALM (S3's ExciterType): a soft, broad raised-cosine impulse, no
     * noise. A harder strike is a shorter pulse.
     */
    private fun fleshPalmExciter(hardness: Float, rate: Int): (Int) -> Float {
        val pulseLen = (rate * (0.003f + (1f - hardness) * 0.009f)).toInt().coerceAtLeast(1)
        return { i -> if (i < pulseLen) 0.5f * (1f - cos(TWO_PI * i / pulseLen)) else 0f }
    }

    /**
     * HARD_STICK (S3/S4): a short raised-cosine pulse with a noise
     * component scaled by hardness - a mallet's tick, not a palm's push.
     */
    private fun hardStickExciter(hardness: Float, rate: Int, seed: Int): (Int) -> Float {
        val stickLen = (rate * HARD_STICK_SECONDS).toInt().coerceAtLeast(1)
        val noise = Dsp.Noise(seed)
        return { i ->
            if (i < stickLen) {
                val pulse = 0.5f * (1f - cos(TWO_PI * i / stickLen))
                pulse * 0.6f + noise.next() * pulse * 0.4f * hardness
            } else {
                0f
            }
        }
    }

    /**
     * The exciter-plus-modal-bank core every TERRA topology shares:
     * [exciterAt] into [modes], each mode rung by a phase-accumulated
     * oscillator re-tuned every sample from a droop-sliding fundamental
     * (S2.1; pass `droopDepth = 0f` for a topology with no membrane tension
     * to relax). [modes] is assumed already position-weighted (see
     * [Modes.atPosition]).
     */
    private fun strikeAndModalBank(
        modes: List<Modes.Mode>,
        fundamentalHz: Float,
        droopDepth: Float,
        frames: Int,
        rate: Int,
        exciterAt: (Int) -> Float,
    ): FloatArray {
        val out = FloatArray(frames)
        val phases = FloatArray(modes.size)
        val nyquist = rate / 2f

        for (i in out.indices) {
            val t = i.toFloat() / rate
            val exciter = exciterAt(i)

            // Tension droop (S2.1): the strike temporarily sharps the body,
            // settling back exponentially onto fundamentalHz. droopDepth is
            // 0 for a rigid body (CONICAL_BELL, TUNED_BAR), collapsing this
            // to fundamentalHz exactly.
            val currentF0 = fundamentalHz * (1f + droopDepth * exp(-t / DROOP_TAU_SECONDS))

            var modalSum = 0f
            for (k in modes.indices) {
                val mode = modes[k]
                val hz = currentF0 * mode.ratio
                // Skipped, not folded - same guard as Modes.ring's own.
                if (hz <= 0f || hz >= nyquist || mode.t60 <= 0f || mode.gain == 0f) continue
                phases[k] += TWO_PI * hz / rate
                if (phases[k] >= TWO_PI) phases[k] -= TWO_PI
                val decay = exp(-T60_NEPERS * t / mode.t60)
                modalSum += sin(phases[k]) * mode.gain * decay
            }
            // First-pass mix, exciter-vs-body - a listening call for the
            // audition gate, not derived from anything.
            out[i] = 0.35f * exciter + 0.65f * modalSum
        }
        return out
    }

    /**
     * Parasitic contact buzz (S2.4), shared by RESONANT_CAVITY and
     * TUNED_BAR: only the part of [raw] above [BUZZ_THRESHOLD] rattles,
     * scaled by [amount]. A no-op copy below the 0.001 gate a patch's
     * default (0) always takes, so a preset that never asks for buzz pays
     * nothing beyond the copy.
     */
    private fun applyBuzz(raw: FloatArray, amount: Float, seed: Int): FloatArray {
        if (amount <= 0.001f) return raw
        val noise = Dsp.Noise(seed)
        val out = raw.copyOf()
        for (i in out.indices) {
            val absS = abs(out[i])
            if (absS > BUZZ_THRESHOLD) {
                out[i] += (absS - BUZZ_THRESHOLD) * noise.next() * amount * BUZZ_GAIN
            }
        }
        return out
    }

    private fun compoundMembrane(m: Map<String, Float>, rate: Int): FloatArray {
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 55f, 440f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        val position = m.getValue("POS")
        val droopDepth = Dsp.lin(m.getValue("DROOP"), 0f, 0.65f)

        val baseModes = MEMBRANE_RATIOS.indices.map { i ->
            val gamma = 1f + i * MEMBRANE_GAMMA_STEP
            Modes.Mode(ratio = MEMBRANE_RATIOS[i], gain = MEMBRANE_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        return strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate))
    }

    private fun resonantCavity(m: Map<String, Float>, rate: Int): FloatArray {
        // Udu/cajón territory: the spec's own presets sit at 55-60Hz: see
        // TERRA_World_Percussion_Synth_Spec.md S5, pads 01 and 04.
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 45f, 300f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        val position = m.getValue("POS")
        val droopDepth = Dsp.lin(m.getValue("DROOP"), 0f, 0.65f)
        val cavityMix = m.getValue("CAVITY")
        val buzzAmount = m.getValue("BUZZ")

        val baseModes = CAVITY_RATIOS.indices.map { i ->
            val gamma = 1f + i * CAVITY_GAMMA_STEP
            Modes.Mode(ratio = CAVITY_RATIOS[i], gain = CAVITY_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate))

        val cavity = Dsp.Biquad().apply { bandpass(CAVITY_FREQ_HZ, CAVITY_Q, rate) }
        val out = raw.copyOf()
        if (cavityMix > 0.001f) {
            // Helmholtz cavity coupling (S2.3): a resonant bandpass into a
            // soft clip, crossfaded against the dry signal.
            for (i in out.indices) {
                val cavitySat = tanh(cavity.process(out[i]) * CAVITY_DRIVE)
                out[i] = out[i] * (1f - cavityMix) + cavitySat * cavityMix
            }
        }
        return applyBuzz(out, buzzAmount, seed = 13)
    }

    private fun conicalBell(m: Map<String, Float>, rate: Int): FloatArray {
        // Agogô territory: the spec's own presets span D5-A5 (587-880Hz):
        // TERRA_World_Percussion_Synth_Spec.md S5, pads 13-15.
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 500f, 950f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        val position = m.getValue("POS")

        // gamma_m = 1 + 0.85*m^2 (S2.2's "high damping" row): the upper
        // partials of a struck cone die far faster than a linear curve
        // would give them, which is most of what makes this read as
        // forged metal rather than a drum.
        val baseModes = BELL_RATIOS.indices.map { i ->
            val gamma = 1f + BELL_GAMMA_QUADRATIC * i * i
            Modes.Mode(ratio = BELL_RATIOS[i], gain = BELL_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        // No DROOP: a forged bell has no membrane tension to relax.
        return strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, hardStickExciter(hardness, rate, seed = 17))
    }

    private fun tunedBar(m: Map<String, Float>, rate: Int): FloatArray {
        // Balafon territory: the spec's own presets sit at 220-330Hz:
        // TERRA_World_Percussion_Synth_Spec.md S5, pads 07 and 16.
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 180f, 400f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        val position = m.getValue("POS")
        val buzzAmount = m.getValue("BUZZ")

        val baseModes = BAR_RATIOS.indices.map { i ->
            val gamma = 1f + i * BAR_GAMMA_STEP
            Modes.Mode(ratio = BAR_RATIOS[i], gain = BAR_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        // No DROOP: a wooden bar has no membrane tension to relax.
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, hardStickExciter(hardness, rate, seed = 19))
        return applyBuzz(raw, buzzAmount, seed = 23)
    }
}
