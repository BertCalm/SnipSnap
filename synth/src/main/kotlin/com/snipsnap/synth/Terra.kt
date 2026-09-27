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
 * COMPOUND_MEMBRANE (hand-struck membranes: djembe, dholak, dumbek, tabla)
 * and RESONANT_CAVITY (air-cavity instruments: udu, cajón) are both a bank
 * of inharmonic partials — mode ratios and damping curves sourced from
 * `TERRA_World_Percussion_Synth_Spec.md` S2.2 — excited by a FLESH_PALM
 * strike (a raised-cosine impulse, no noise). RESONANT_CAVITY adds two
 * things COMPOUND_MEMBRANE doesn't need: a Helmholtz cavity resonance
 * (S2.3) and a parasitic contact buzz (S2.4). CONICAL_BELL, TUNED_BAR (the
 * spec's other two topologies) and the HARD_STICK/MICRO_FLOCK exciters land
 * as follow-up work, the same way [SkinVoice]'s eight voices landed across
 * two PRs.
 *
 * Unlike [Modes.ring], the modal bank here can't be a fixed two-pole
 * recursion: DROOP needs the fundamental itself to slide during the decay
 * (a struck membrane's tension relaxing, S2.1), so each mode is rung by a
 * phase-accumulated oscillator re-tuned every sample from that sliding f0,
 * instead of [Modes.ring]'s single-fundamental filter. The per-mode table
 * still borrows [Modes.Mode] for its shape (ratio/gain/t60), and strike
 * position still borrows [Modes.atPosition] — only the accumulation itself
 * ([strikeAndModalBank]) had to be bespoke, and both topologies share it.
 */
enum class TerraVoice { COMPOUND_MEMBRANE, RESONANT_CAVITY }

object Terra {

    fun macrosFor(voice: TerraVoice): List<MacroSpec> = when (voice) {
        TerraVoice.COMPOUND_MEMBRANE -> listOf(
            MacroSpec("TUNE", 0.35f),
            MacroSpec("DECAY", 0.5f),
            // FORCE: strike hardness (S2's strikeHardness) - a harder hit is
            // a shorter, sharper exciter pulse. Not called STRIKE: THUMP's
            // SNARE already uses that name for strike POSITION, below.
            MacroSpec("FORCE", 0.5f),
            // STRIKE: strike position, 0 centre .. 1 rim - same name and the
            // same 0..1 meaning as Thump.snare's own STRIKE macro.
            MacroSpec("STRIKE", 0.25f),
            MacroSpec("DROOP", 0.23f),
        )
        TerraVoice.RESONANT_CAVITY -> listOf(
            MacroSpec("TUNE", 0.3f),
            MacroSpec("DECAY", 0.5f),
            MacroSpec("FORCE", 0.5f),
            MacroSpec("STRIKE", 0.25f),
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
    }

    /** The factory macro settings for [voice]. */
    fun defaults(voice: TerraVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE, same contract as
        // every other engine, so the exciter's raised-cosine edge and (on
        // RESONANT_CAVITY) the cavity's tanh saturator and the buzz
        // threshold all fold down above 22.05kHz instead of aliasing into
        // the audible band - the source spec's own reference code ran both
        // of those nonlinear stages straight at 44.1kHz with no oversample.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate)
            TerraVoice.RESONANT_CAVITY -> resonantCavity(m, renderRate)
        }
        val out = Dsp.decimate(raw, RATE)
        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    // COMPOUND_MEMBRANE mode ratios and gains, and its damping curve
    // gamma_m = 1 + 0.65m: TERRA_World_Percussion_Synth_Spec.md S2.2.
    private val MEMBRANE_RATIOS = floatArrayOf(1.00f, 1.99f, 2.98f, 3.99f, 4.88f, 5.92f)
    private val MEMBRANE_GAINS = floatArrayOf(1.00f, 0.65f, 0.45f, 0.25f, 0.12f, 0.08f)
    private const val MEMBRANE_GAMMA_STEP = 0.65f

    // RESONANT_CAVITY mode ratios and gains, and its damping curve
    // gamma_m = 1 + 1.2m: TERRA_World_Percussion_Synth_Spec.md S2.2 ("Coupled
    // Helmholtz" row).
    private val CAVITY_RATIOS = floatArrayOf(1.00f, 2.14f, 3.20f, 4.45f)
    private val CAVITY_GAINS = floatArrayOf(1.00f, 0.35f, 0.15f, 0.05f)
    private const val CAVITY_GAMMA_STEP = 1.2f

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

    private const val TWO_PI = (2.0 * Math.PI).toFloat()

    // -60dB in nepers - the same constant [Modes.ring] uses for its own
    // per-mode decay, so a t60 here means the same thing as a Modes.Mode t60.
    private const val T60_NEPERS = 6.9078f

    // S2.1's tau range is 8-40ms; a droopDecayMs macro to vary this
    // independently of DROOP's depth is follow-up work - no preset needs it
    // yet, and the spec's own default (20ms) sits at this range's centre.
    private const val DROOP_TAU_SECONDS = 0.020f

    /**
     * The exciter-plus-modal-bank core every TERRA topology shares: a
     * FLESH_PALM raised-cosine strike into [modes], each mode rung by a
     * phase-accumulated oscillator re-tuned every sample from a
     * droop-sliding fundamental (S2.1). [modes] is assumed already
     * strike-position-weighted (see [Modes.atPosition]).
     */
    private fun strikeAndModalBank(
        modes: List<Modes.Mode>,
        fundamentalHz: Float,
        droopDepth: Float,
        hardness: Float,
        frames: Int,
        rate: Int,
    ): FloatArray {
        val out = FloatArray(frames)
        val phases = FloatArray(modes.size)
        val nyquist = rate / 2f

        // FLESH_PALM: a soft, broad raised-cosine impulse - no noise, unlike
        // HARD_STICK (follow-up work). A harder strike is a shorter pulse.
        val pulseLen = (rate * (0.003f + (1f - hardness) * 0.009f)).toInt().coerceAtLeast(1)

        for (i in out.indices) {
            val t = i.toFloat() / rate
            val exciter = if (i < pulseLen) 0.5f * (1f - cos(TWO_PI * i / pulseLen)) else 0f

            // Tension droop (S2.1): the strike temporarily sharps the body,
            // settling back exponentially onto fundamentalHz.
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

    private fun compoundMembrane(m: Map<String, Float>, rate: Int): FloatArray {
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 55f, 440f)
        // DECAY as RT60 (the house convention - see Dsp.Env's decay2T60):
        // the fundamental mode (gamma=1) falls 60dB by t60Base exactly, so
        // sizing the buffer at 1.4x it (same margin Thump's kick/tom/etc.
        // use) leaves every mode audibly silent well before the cut, unlike
        // the source spec's own reference code (whose decayMs is a 1/e time
        // constant sized to exactly one buffer length, so its loudest mode
        // is still at -8.7dB, not -60dB, right at the truncation point).
        val t60Base = Dsp.around(m.getValue("DECAY"), 0.08f, 0.35f, 0.9f)
        val hardness = m.getValue("FORCE")
        val position = m.getValue("STRIKE")
        val droopDepth = Dsp.lin(m.getValue("DROOP"), 0f, 0.65f)

        val baseModes = MEMBRANE_RATIOS.indices.map { i ->
            val gamma = 1f + i * MEMBRANE_GAMMA_STEP
            Modes.Mode(ratio = MEMBRANE_RATIOS[i], gain = MEMBRANE_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = (t60Base * 1.4f * rate).toInt().coerceAtLeast(64)
        return strikeAndModalBank(modes, fundamentalHz, droopDepth, hardness, frames, rate)
    }

    private fun resonantCavity(m: Map<String, Float>, rate: Int): FloatArray {
        // Udu/cajón territory: the spec's own presets sit at 55-60Hz: see
        // TERRA_World_Percussion_Synth_Spec.md S5, pads 01 and 04.
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 45f, 300f)
        val t60Base = Dsp.around(m.getValue("DECAY"), 0.08f, 0.35f, 0.9f)
        val hardness = m.getValue("FORCE")
        val position = m.getValue("STRIKE")
        val droopDepth = Dsp.lin(m.getValue("DROOP"), 0f, 0.65f)
        val cavityMix = m.getValue("CAVITY")
        val buzzAmount = m.getValue("BUZZ")

        val baseModes = CAVITY_RATIOS.indices.map { i ->
            val gamma = 1f + i * CAVITY_GAMMA_STEP
            Modes.Mode(ratio = CAVITY_RATIOS[i], gain = CAVITY_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = (t60Base * 1.4f * rate).toInt().coerceAtLeast(64)
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth, hardness, frames, rate)

        val cavity = Dsp.Biquad().apply { bandpass(CAVITY_FREQ_HZ, CAVITY_Q, rate) }
        val buzzNoise = Dsp.Noise(13)
        val out = FloatArray(frames)
        for (i in out.indices) {
            var sample = raw[i]
            // Helmholtz cavity coupling (S2.3): a resonant bandpass into a
            // soft clip, crossfaded against the dry signal.
            if (cavityMix > 0.001f) {
                val cavitySat = tanh(cavity.process(sample) * CAVITY_DRIVE)
                sample = sample * (1f - cavityMix) + cavitySat * cavityMix
            }
            // Parasitic contact buzz (S2.4): only what clears the threshold
            // rattles, scaled by BUZZ.
            if (buzzAmount > 0.001f) {
                val absS = abs(sample)
                if (absS > BUZZ_THRESHOLD) {
                    sample += (absS - BUZZ_THRESHOLD) * buzzNoise.next() * buzzAmount * BUZZ_GAIN
                }
            }
            out[i] = sample
        }
        return out
    }
}
