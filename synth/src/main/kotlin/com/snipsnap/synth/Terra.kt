package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * TERRA — world percussion, physically modeled.
 *
 * COMPOUND_MEMBRANE is the first topology: a hand-struck membrane (djembe,
 * dholak, dumbek, tabla) as a bank of inharmonic partials — mode ratios and
 * their damping curve sourced from `TERRA_World_Percussion_Synth_Spec.md`
 * S2.2 — excited by a FLESH_PALM strike (a raised-cosine impulse, no noise).
 * RESONANT_CAVITY, CONICAL_BELL and TUNED_BAR (the spec's other three
 * topologies) and the HARD_STICK/MICRO_FLOCK exciters land as follow-up
 * work, the same way [SkinVoice]'s eight voices landed across two PRs.
 *
 * Unlike [Modes.ring], the bank here can't be a fixed two-pole recursion:
 * DROOP needs the fundamental itself to slide during the decay (a struck
 * membrane's tension relaxing, S2.1), so each mode is rung by a
 * phase-accumulated oscillator re-tuned every sample from that sliding f0,
 * instead of [Modes.ring]'s single-fundamental filter. The per-mode table
 * still borrows [Modes.Mode] for its shape (ratio/gain/t60), and strike
 * position still borrows [Modes.atPosition] — only the accumulation itself
 * had to be bespoke.
 */
enum class TerraVoice { COMPOUND_MEMBRANE }

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
    }

    /** The factory macro settings for [voice]. */
    fun defaults(voice: TerraVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE, same contract as
        // every other engine, so the exciter's raised-cosine edge folds down
        // above 22.05kHz instead of aliasing into the audible band.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate)
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

    private const val TWO_PI = (2.0 * Math.PI).toFloat()

    // -60dB in nepers - the same constant [Modes.ring] uses for its own
    // per-mode decay, so a t60 here means the same thing as a Modes.Mode t60.
    private const val T60_NEPERS = 6.9078f

    // S2.1's tau range is 8-40ms; a droopDecayMs macro to vary this
    // independently of DROOP's depth is follow-up work - no preset needs it
    // yet, and the spec's own default (20ms) sits at this range's centre.
    private const val DROOP_TAU_SECONDS = 0.020f

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
        val out = FloatArray(frames)
        val phases = FloatArray(modes.size)
        val nyquist = rate / 2f

        // FLESH_PALM: a soft, broad raised-cosine impulse - no noise, unlike
        // HARD_STICK (follow-up work). A harder strike is a shorter pulse.
        val pulseLen = (rate * (0.003f + (1f - hardness) * 0.009f)).toInt().coerceAtLeast(1)

        for (i in out.indices) {
            val t = i.toFloat() / rate
            val exciter = if (i < pulseLen) 0.5f * (1f - cos(TWO_PI * i / pulseLen)) else 0f

            // Tension droop (S2.1): the strike temporarily sharps the
            // membrane, settling back exponentially onto fundamentalHz.
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
}
