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
            // CLACK: an optional pre-strike squeeze (S3's "Agogô squeeze or
            // pre-flam") - a short decaying noise burst before the mallet
            // lands, the two-tone bell's own interlock click. Off by
            // default: most bell presets are a plain strike.
            MacroSpec("CLACK", 0f),
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

    /**
     * What one voice hands the shared bank, after POS ([Modes.atPosition])
     * and before any input: its modes, its note, its droop depth, its buffer
     * length (a CLACK pre-roll included), the render rate and where the
     * strike lands. HIT's colouring is computed from this at render, so a
     * driven pad that is retuned or re-pitched is recoloured from its new
     * modes.
     */
    internal class Body(
        val modes: List<Modes.Mode>,
        val fundamentalHz: Float,
        val droopDepth: Float,
        val frames: Int,
        val rate: Int,
        val onsetSamples: Int,
    )

    /**
     * The bank's two optional inputs, both indexed from the strike and both
     * holding their last value past their end: [level] is one curve per
     * mode, a factor on that mode's table gain; [pitch] is a factor on the
     * droop line, clamped to [PITCH_MIN]..[PITCH_MAX].
     */
    internal class BankInputs(val level: Array<FloatArray>? = null, val pitch: FloatArray? = null)

    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip = renderWith(voice, macros, inputsFor = null)

    /**
     * [render] with the bank's two optional inputs, built from the voice's own
     * [Body] by [inputsFor]. A null [inputsFor], or one that returns null, is
     * today's render, byte for byte (TerraFrozenTest).
     */
    internal fun renderWith(voice: TerraVoice, macros: Map<String, Float>, inputsFor: ((Body) -> BankInputs?)?): Snip {
        val m = settled(voice, macros)
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE, same contract as
        // every other engine, so the exciters' pulse edges and (on
        // RESONANT_CAVITY/TUNED_BAR) the cavity's tanh saturator and the
        // buzz threshold all fold down above 22.05kHz instead of aliasing
        // into the audible band - the source spec's own reference code ran
        // its nonlinear stages straight at 44.1kHz with no oversample.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val (raw, clackFrames) = when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate, inputsFor) to 0
            TerraVoice.RESONANT_CAVITY -> resonantCavity(m, renderRate, inputsFor) to 0
            TerraVoice.CONICAL_BELL -> conicalBell(m, renderRate, inputsFor)
            TerraVoice.TUNED_BAR -> tunedBar(m, renderRate, inputsFor) to 0
        }
        // A fixed level into Punch.saturate's nonlinearity, same reason
        // Thump.render normalizes raw before its own Punch call (Punch.kt's
        // own KDoc on applyOversampled) - strikeAndModalBank's exciter/modal
        // mix is otherwise an unbounded, preset-dependent amplitude. Done
        // jointly, before any CLACK split below, so the click and the body
        // stay level-consistent with each other.
        Dsp.normalize(raw)
        // Every other engine here runs its strike through some transient
        // shaping; TERRA had none at all, which measured out as a real
        // contributor to reading "small, meek, dull" next to Thump's own
        // kick/snare (bare exciter+modal mix straight to decimate). A fixed
        // internal amount, not a macro yet - the same "first-pass listening
        // call" TERRA's exciter/modal mix ratio already is.
        //
        // Punch's own onset-boost always anchors at frame 0 of whatever
        // buffer it's given (Punch.kt's own Window, no onset parameter) -
        // fine for every voice except a CLACKed CONICAL_BELL, where frame 0
        // is the pre-strike click, not the real strike. Measured: applying
        // Punch to the whole buffer there put the click's own peak at the
        // render's absolute ceiling regardless of CLACK_GAIN, inverting the
        // "quiet click, then a real hit" the whole feature is for. Splitting
        // at clackFrames and Punching only the body (decimated separately,
        // the same reduced-context edge every render's own frame 0 already
        // has) keeps the boost on the actual strike.
        val out = if (clackFrames > 0) {
            val preroll = Dsp.decimate(raw.copyOfRange(0, clackFrames), RATE)
            val body = Punch.applyOversampled(raw.copyOfRange(clackFrames, raw.size), TERRA_PUNCH_AMOUNT, RATE)
            preroll + body
        } else {
            Punch.applyOversampled(raw, TERRA_PUNCH_AMOUNT, RATE)
        }
        Dsp.limitPeak(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * The bank alone for [voice], with the same optional inputs as
     * [renderWith]: before the cavity stage, BUZZ and the output chain, at the
     * render rate. The physics claims read this, as ForkTest reads `Fork.bank`.
     */
    internal fun bankWith(voice: TerraVoice, macros: Map<String, Float>, inputsFor: ((Body) -> BankInputs?)?): FloatArray {
        val m = settled(voice, macros)
        val renderRate = RATE * Dsp.OVERSAMPLE
        return when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate, inputsFor)
            TerraVoice.RESONANT_CAVITY -> resonantCavity(m, renderRate, inputsFor, bankOnly = true)
            TerraVoice.CONICAL_BELL -> conicalBell(m, renderRate, inputsFor).first
            TerraVoice.TUNED_BAR -> tunedBar(m, renderRate, inputsFor, bankOnly = true)
        }
    }

    /** [macros] over [voice]'s defaults, each clamped to 0..1; names the voice does not have are ignored. */
    private fun settled(voice: TerraVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
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

    // FLESH_PALM's own noise ceiling (see fleshPalmExciter's own KDoc):
    // matched to HARD_STICK_NOISE_WEIGHT rather than held under it. A lower
    // value (0.18f) measured as close to imperceptible - FORCE 0 vs 1 barely
    // moved flatness/highRatio at all, on a macro whose whole job is to make
    // hardness audible. Verified up to this value: absolute flatness on even
    // the hardest hit stays two to three orders of magnitude under CLAP's
    // 0.35 gate (no risk of reading as white noise), no kit pad's classifier
    // drifted, no sample left -1..1.
    private const val FLESH_NOISE_GAIN = 0.40f

    // HARD_STICK's own strike length (S4's sLen): shorter than FLESH_PALM's
    // shortest (0.003s at FORCE=1), a mallet's tick against a stick's own
    // seed keeps every HARD_STICK voice's own noise stream distinct.
    private const val HARD_STICK_SECONDS = 0.0018f

    // HARD_STICK's own noise ceiling (see hardStickExciter's own KDoc).
    // Verified: the isolated exciter's own grit still sits well short of a
    // pure-noise ceiling at this value, and no kit pad's classifier drifted.
    private const val HARD_STICK_NOISE_WEIGHT = 0.55f

    // Every other engine here shapes its strike's transient (Thump.render
    // always runs Punch.applyOversampled); TERRA never has, and measured out
    // as a real contributor to reading dull next to Thump's own kick/snare -
    // see Terra.render's own comment. Pushed to Punch's own ceiling (its
    // boost/saturate scale as amount^3, so the 0.7-1.0 stretch carries most
    // of the available effect): verified every voice's default and all 16
    // shipped kit pads stay in -1..1 and keep their declared DrumClass at
    // this value. Not a macro yet, the same "first-pass listening call"
    // spirit as the exciter/modal mix ratio it sits beside.
    //
    // Pulling this back to buy more perceived body loudness was investigated
    // and rejected (PR #384): even a 40% cut (down to 0.60, alongside an
    // added pre-Punch attack compressor) only reduced Dsp.limitPeak's own
    // rescale-down by roughly half, and that internal gain almost entirely
    // failed to survive into anything a listener would call louder -
    // AuditionLevel.level() (the audition tool's own fair-A/B renormalize)
    // absorbs most of it regardless, while the crispness cost (crest factor,
    // onset energy, spectral centroid all down double digits) was real and
    // large. Four separate amount/compressor combinations measured this same
    // trade; none paid for itself.
    private const val TERRA_PUNCH_AMOUNT = 1.0f

    // CLACK's own ceiling: the spec's own Agogô Clack preset (S5, pad 15)
    // uses interlockClackMs = 20ms; 30ms gives the macro a little more
    // travel above that without the pre-strike click starting to read as
    // its own separate hit.
    private const val CLACK_MAX_SECONDS = 0.03f

    // A held-over 0.35f measured as a near-inaudible pre-roll: the click
    // peaked at only ~16% of the struck body's own peak, a whisper under the
    // hit rather than an audible squeeze - only the pre-roll's length scales
    // with the CLACK macro, never its own loudness. Verified this value
    // roughly doubles that to ~35%, still comfortably under the 50% ceiling
    // "clack is quiet, and the bell only starts ringing after it" already
    // holds it to.
    private const val CLACK_GAIN = 0.8f

    private const val TWO_PI = (2.0 * Math.PI).toFloat()

    // -60dB in nepers - the same constant [Modes.ring] uses for its own
    // per-mode decay, so a t60 here means the same thing as a Modes.Mode t60.
    private const val T60_NEPERS = 6.9078f

    // S2.1's tau range is 8-40ms; a droopDecayMs macro to vary this
    // independently of DROOP's depth is follow-up work - no preset needs it
    // yet, and the spec's own default (20ms) sits at this range's centre.
    private const val DROOP_TAU_SECONDS = 0.020f

    // The pitch input's clamp, applied before the skip guard: two octaves
    // either way, so a downward curve can never reach hz <= 0 and the top
    // mode at TUNE 1 stays far under the render rate's Nyquist (440 Hz x
    // 5.92 x 4 x 1.65 is about 17 kHz against 88.2 kHz).
    internal const val PITCH_MIN = 0.25f
    internal const val PITCH_MAX = 4f

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
     * FLESH_PALM (S3's ExciterType): a soft, broad raised-cosine impulse. A
     * harder strike is a shorter pulse, plus a touch of skin-contact grit
     * scaled by [hardness] - measured (FeatureExtractor flatness/highRatio
     * both pinned to 0.0000 across the whole FORCE range) as a real gap
     * against every other exciter here: HARD_STICK already scales its own
     * noise by hardness, and a hand slapping a drumhead is not perfectly
     * smooth even softly struck. [FLESH_NOISE_GAIN] matches HARD_STICK's own
     * [HARD_STICK_NOISE_WEIGHT] - a palm's grit isn't quieter than a
     * mallet's, just a broader, longer pulse around it.
     */
    private fun fleshPalmExciter(hardness: Float, rate: Int, seed: Int): (Int) -> Float {
        val pulseLen = (rate * (0.003f + (1f - hardness) * 0.009f)).toInt().coerceAtLeast(1)
        val noise = Dsp.Noise(seed)
        return { i ->
            if (i < pulseLen) {
                val pulse = 0.5f * (1f - cos(TWO_PI * i / pulseLen))
                pulse * (1f - FLESH_NOISE_GAIN) + noise.next() * pulse * FLESH_NOISE_GAIN * hardness
            } else {
                0f
            }
        }
    }

    /**
     * HARD_STICK (S3/S4): a short raised-cosine pulse with a noise
     * component scaled by hardness - a mallet's tick, not a palm's push.
     * [HARD_STICK_NOISE_WEIGHT]'s own isolated grit still sits short of a
     * pure-noise ceiling at this value (measured against a no-tone-term
     * reference), so it reads as a hard mallet's edge, not hiss.
     */
    private fun hardStickExciter(hardness: Float, rate: Int, seed: Int): (Int) -> Float {
        val stickLen = (rate * HARD_STICK_SECONDS).toInt().coerceAtLeast(1)
        val noise = Dsp.Noise(seed)
        return { i ->
            if (i < stickLen) {
                val pulse = 0.5f * (1f - cos(TWO_PI * i / stickLen))
                pulse * (1f - HARD_STICK_NOISE_WEIGHT) + noise.next() * pulse * HARD_STICK_NOISE_WEIGHT * hardness
            } else {
                0f
            }
        }
    }

    /**
     * Wraps [mainExciter] with an optional pre-strike squeeze (S3's CLACK):
     * [clackSamples] of decaying noise, then [mainExciter] starting fresh
     * right after it - the whole strike shifts later by [clackSamples], so
     * the caller must size its buffer for that (see [conicalBell]).
     * `clackSamples <= 0` returns [mainExciter] unchanged.
     */
    private fun withPreStrikeClack(clackSamples: Int, seed: Int, mainExciter: (Int) -> Float): (Int) -> Float {
        if (clackSamples <= 0) return mainExciter
        val noise = Dsp.Noise(seed)
        return { i ->
            if (i < clackSamples) {
                val env = 1f - i.toFloat() / clackSamples
                noise.next() * env * CLACK_GAIN
            } else {
                mainExciter(i - clackSamples)
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
     *
     * [onsetSamples] is where the body actually starts ringing - 0 for every
     * topology except a CLACKed CONICAL_BELL, where it's the clack's own
     * pre-roll length. Before it, modalSum is exactly 0 (the bell hasn't
     * been struck yet); the phase/decay clock then starts fresh at
     * [onsetSamples], so a used CLACK is silent underneath the click rather
     * than a bell already partway through decaying.
     *
     * [level] (one curve per mode, a factor on `mode.gain`) and [pitch] (a
     * factor on the droop line) are the two optional inputs HIT, BEND and
     * TALK fill, both indexed from [onsetSamples]; see [drivenBank]. With
     * neither, the loop below runs today's two expressions verbatim - the
     * only form that is byte-identical by construction. The loop in
     * [drivenBank] repeats this one's shared rules and must change with it.
     */
    internal fun strikeAndModalBank(
        modes: List<Modes.Mode>,
        fundamentalHz: Float,
        droopDepth: Float,
        frames: Int,
        rate: Int,
        exciterAt: (Int) -> Float,
        onsetSamples: Int = 0,
        level: Array<FloatArray>? = null,
        pitch: FloatArray? = null,
    ): FloatArray {
        if (level != null || pitch != null) return drivenBank(modes, fundamentalHz, droopDepth, frames, rate, exciterAt, onsetSamples, level, pitch)
        val out = FloatArray(frames)
        val phases = FloatArray(modes.size)
        val nyquist = rate / 2f

        for (i in out.indices) {
            val exciter = exciterAt(i)
            var modalSum = 0f
            if (i >= onsetSamples) {
                val t = (i - onsetSamples).toFloat() / rate

                // Tension droop (S2.1): the strike temporarily sharps the
                // body, settling back exponentially onto fundamentalHz.
                // droopDepth is 0 for a rigid body (CONICAL_BELL,
                // TUNED_BAR), collapsing this to fundamentalHz exactly.
                val currentF0 = fundamentalHz * (1f + droopDepth * exp(-t / DROOP_TAU_SECONDS))

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
            }
            // First-pass mix, exciter-vs-body - a listening call for the
            // audition gate, not derived from anything.
            out[i] = 0.35f * exciter + 0.65f * modalSum
        }
        return out
    }

    /**
     * [strikeAndModalBank] with at least one input. The level factor
     * multiplies the table gain before the sine, `sin * (gain * level) *
     * decay`, and the pitch factor multiplies the droop line before f0,
     * `f0 * (droop * pitch)`. Both groupings are Phase 0's `P0Bank`'s
     * (Phase-0 record, Appendix B.1), so an input of exactly 1f changes no
     * bit and a curve through this bank matches the prototype's. (The spec
     * writes the pitch half left to right; `P0Bank` does not, and this
     * follows `P0Bank`.) The skip guard reads the table gain only: a mode
     * whose level is 0 still advances its phase and reopens where it would
     * have been (spec, "Architecture"; TerraTest's `a mode held at zero
     * level reopens in phase`).
     *
     * This loop is a deliberate copy of [strikeAndModalBank]'s own, differing
     * only in those two factors, because one loop with the factors hoisted
     * would no longer run today's expressions verbatim. A change to a rule
     * the two share (the Nyquist or t60 skip, the phase wrap, the mix) goes
     * in both; TerraFrozenTest's `identity curves alone and together render
     * the frozen TERRA` fails on all forty cases if they drift apart.
     */
    private fun drivenBank(
        modes: List<Modes.Mode>,
        fundamentalHz: Float,
        droopDepth: Float,
        frames: Int,
        rate: Int,
        exciterAt: (Int) -> Float,
        onsetSamples: Int,
        level: Array<FloatArray>?,
        pitch: FloatArray?,
    ): FloatArray {
        if (level != null) require(level.size == modes.size && level.all { it.isNotEmpty() }) { "a level curve per mode (${modes.size}), each at least one value long" }
        if (pitch != null) require(pitch.isNotEmpty()) { "a pitch curve has at least one value" }
        val out = FloatArray(frames)
        val phases = FloatArray(modes.size)
        val nyquist = rate / 2f
        for (i in out.indices) {
            val exciter = exciterAt(i)
            var modalSum = 0f
            if (i >= onsetSamples) {
                val n = i - onsetSamples
                val t = n.toFloat() / rate
                val currentF0 = if (pitch == null) {
                    fundamentalHz * (1f + droopDepth * exp(-t / DROOP_TAU_SECONDS))
                } else {
                    val p = pitch[minOf(n, pitch.size - 1)].coerceIn(PITCH_MIN, PITCH_MAX)
                    fundamentalHz * ((1f + droopDepth * exp(-t / DROOP_TAU_SECONDS)) * p)
                }
                for (k in modes.indices) {
                    val mode = modes[k]
                    val hz = currentF0 * mode.ratio
                    if (hz <= 0f || hz >= nyquist || mode.t60 <= 0f || mode.gain == 0f) continue
                    phases[k] += TWO_PI * hz / rate
                    if (phases[k] >= TWO_PI) phases[k] -= TWO_PI
                    val decay = exp(-T60_NEPERS * t / mode.t60)
                    modalSum += if (level == null) {
                        sin(phases[k]) * mode.gain * decay
                    } else {
                        val curve = level[k]
                        sin(phases[k]) * (mode.gain * curve[minOf(n, curve.size - 1)]) * decay
                    }
                }
            }
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

    private fun compoundMembrane(m: Map<String, Float>, rate: Int, inputsFor: ((Body) -> BankInputs?)? = null): FloatArray {
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 55f, 440f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        // Not a plain 0..1 pass-through: Modes.atPosition weights each mode
        // by |sin(n*pi*p)|, which is exactly mirror-symmetric under
        // p <-> 1-p for every integer n (|sin(n*pi*(1-p))| == |sin(n*pi*p)|
        // is an algebraic identity) - a symmetric range like (0.02, 0.98)
        // (Thump.snare's own STRIKE dodges the same |sin| edge-silence trap
        // this way) hands POS=0 and POS=1 the identical per-mode weight
        // magnitude, measured as an exact-to-the-float-digit null: two
        // presets picking "opposite" strike positions (TerraKits' own
        // Tabla Tin at POS=0.95 vs Tabla Tun at POS=0.05, meant to sound
        // like rim vs center) rendered indistinguishably. Anchoring at 0.5
        // instead breaks the mirror pairing: POS=0 lands on the formula's
        // own true center (max fundamental weight, even harmonics silenced,
        // matching this macro's documented "0 centre" meaning) and POS=1 on
        // a genuine rim, so the two ends of the knob's travel actually
        // diverge instead of both landing near-edge and canceling out.
        val position = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val droopDepth = Dsp.lin(m.getValue("DROOP"), 0f, 0.65f)

        val baseModes = MEMBRANE_RATIOS.indices.map { i ->
            val gamma = 1f + i * MEMBRANE_GAMMA_STEP
            Modes.Mode(ratio = MEMBRANE_RATIOS[i], gain = MEMBRANE_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        val inputs = inputsFor?.invoke(Body(modes, fundamentalHz, droopDepth, frames, rate, onsetSamples = 0))
        return strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate, seed = 11), level = inputs?.level, pitch = inputs?.pitch)
    }

    private fun resonantCavity(m: Map<String, Float>, rate: Int, inputsFor: ((Body) -> BankInputs?)? = null, bankOnly: Boolean = false): FloatArray {
        // Udu/cajón territory: the spec's own presets sit at 55-60Hz: see
        // TERRA_World_Percussion_Synth_Spec.md S5, pads 01 and 04.
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 45f, 300f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        // Not a plain 0..1 pass-through: Modes.atPosition weights each mode
        // by |sin(n*pi*p)|, which is exactly mirror-symmetric under
        // p <-> 1-p for every integer n (|sin(n*pi*(1-p))| == |sin(n*pi*p)|
        // is an algebraic identity) - a symmetric range like (0.02, 0.98)
        // (Thump.snare's own STRIKE dodges the same |sin| edge-silence trap
        // this way) hands POS=0 and POS=1 the identical per-mode weight
        // magnitude, measured as an exact-to-the-float-digit null: two
        // presets picking "opposite" strike positions (TerraKits' own
        // Tabla Tin at POS=0.95 vs Tabla Tun at POS=0.05, meant to sound
        // like rim vs center) rendered indistinguishably. Anchoring at 0.5
        // instead breaks the mirror pairing: POS=0 lands on the formula's
        // own true center (max fundamental weight, even harmonics silenced,
        // matching this macro's documented "0 centre" meaning) and POS=1 on
        // a genuine rim, so the two ends of the knob's travel actually
        // diverge instead of both landing near-edge and canceling out.
        val position = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val droopDepth = Dsp.lin(m.getValue("DROOP"), 0f, 0.65f)
        val cavityMix = m.getValue("CAVITY")
        val buzzAmount = m.getValue("BUZZ")

        val baseModes = CAVITY_RATIOS.indices.map { i ->
            val gamma = 1f + i * CAVITY_GAMMA_STEP
            Modes.Mode(ratio = CAVITY_RATIOS[i], gain = CAVITY_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        val inputs = inputsFor?.invoke(Body(modes, fundamentalHz, droopDepth, frames, rate, onsetSamples = 0))
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate, seed = 31), level = inputs?.level, pitch = inputs?.pitch)
        if (bankOnly) return raw

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

    /**
     * Returns the render alongside its own pre-strike clack length in
     * [rate]-scale frames (0 when CLACK is off) - [render] needs it to keep
     * Punch's onset-boost off the click and anchored on the real strike
     * instead (see [render]'s own comment on why).
     */
    private fun conicalBell(m: Map<String, Float>, rate: Int, inputsFor: ((Body) -> BankInputs?)? = null): Pair<FloatArray, Int> {
        // Agogô territory: the spec's own presets span D5-A5 (587-880Hz):
        // TERRA_World_Percussion_Synth_Spec.md S5, pads 13-15.
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 500f, 950f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        // Not a plain 0..1 pass-through: Modes.atPosition weights each mode
        // by |sin(n*pi*p)|, which is exactly mirror-symmetric under
        // p <-> 1-p for every integer n (|sin(n*pi*(1-p))| == |sin(n*pi*p)|
        // is an algebraic identity) - a symmetric range like (0.02, 0.98)
        // (Thump.snare's own STRIKE dodges the same |sin| edge-silence trap
        // this way) hands POS=0 and POS=1 the identical per-mode weight
        // magnitude, measured as an exact-to-the-float-digit null: two
        // presets picking "opposite" strike positions (TerraKits' own
        // Tabla Tin at POS=0.95 vs Tabla Tun at POS=0.05, meant to sound
        // like rim vs center) rendered indistinguishably. Anchoring at 0.5
        // instead breaks the mirror pairing: POS=0 lands on the formula's
        // own true center (max fundamental weight, even harmonics silenced,
        // matching this macro's documented "0 centre" meaning) and POS=1 on
        // a genuine rim, so the two ends of the knob's travel actually
        // diverge instead of both landing near-edge and canceling out.
        val position = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val clackSamples = (m.getValue("CLACK") * CLACK_MAX_SECONDS * rate).toInt()

        // gamma_m = 1 + 0.85*m^2 (S2.2's "high damping" row): the upper
        // partials of a struck cone die far faster than a linear curve
        // would give them, which is most of what makes this read as
        // forged metal rather than a drum.
        val baseModes = BELL_RATIOS.indices.map { i ->
            val gamma = 1f + BELL_GAMMA_QUADRATIC * i * i
            Modes.Mode(ratio = BELL_RATIOS[i], gain = BELL_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        // Room for the clack pre-roll ahead of the strike, when CLACK asks
        // for one - onsetSamples below holds the bell silent for exactly
        // that long, so the click is heard on its own before the bell
        // actually starts ringing, rather than underneath a ring that's
        // already partway through decaying.
        val frames = framesFor(t60Base, rate) + clackSamples
        // No DROOP: a forged bell has no membrane tension to relax.
        val exciter = withPreStrikeClack(clackSamples, seed = 29, hardStickExciter(hardness, rate, seed = 17))
        val inputs = inputsFor?.invoke(Body(modes, fundamentalHz, 0f, frames, rate, onsetSamples = clackSamples))
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, exciter, onsetSamples = clackSamples, level = inputs?.level, pitch = inputs?.pitch)
        return raw to clackSamples
    }

    private fun tunedBar(m: Map<String, Float>, rate: Int, inputsFor: ((Body) -> BankInputs?)? = null, bankOnly: Boolean = false): FloatArray {
        // Balafon territory: the spec's own presets sit at 220-330Hz:
        // TERRA_World_Percussion_Synth_Spec.md S5, pads 07 and 16.
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 180f, 400f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        // Not a plain 0..1 pass-through: Modes.atPosition weights each mode
        // by |sin(n*pi*p)|, which is exactly mirror-symmetric under
        // p <-> 1-p for every integer n (|sin(n*pi*(1-p))| == |sin(n*pi*p)|
        // is an algebraic identity) - a symmetric range like (0.02, 0.98)
        // (Thump.snare's own STRIKE dodges the same |sin| edge-silence trap
        // this way) hands POS=0 and POS=1 the identical per-mode weight
        // magnitude, measured as an exact-to-the-float-digit null: two
        // presets picking "opposite" strike positions (TerraKits' own
        // Tabla Tin at POS=0.95 vs Tabla Tun at POS=0.05, meant to sound
        // like rim vs center) rendered indistinguishably. Anchoring at 0.5
        // instead breaks the mirror pairing: POS=0 lands on the formula's
        // own true center (max fundamental weight, even harmonics silenced,
        // matching this macro's documented "0 centre" meaning) and POS=1 on
        // a genuine rim, so the two ends of the knob's travel actually
        // diverge instead of both landing near-edge and canceling out.
        val position = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val buzzAmount = m.getValue("BUZZ")

        val baseModes = BAR_RATIOS.indices.map { i ->
            val gamma = 1f + i * BAR_GAMMA_STEP
            Modes.Mode(ratio = BAR_RATIOS[i], gain = BAR_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        // No DROOP: a wooden bar has no membrane tension to relax.
        val inputs = inputsFor?.invoke(Body(modes, fundamentalHz, 0f, frames, rate, onsetSamples = 0))
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, hardStickExciter(hardness, rate, seed = 19), level = inputs?.level, pitch = inputs?.pitch)
        if (bankOnly) return raw
        return applyBuzz(raw, buzzAmount, seed = 23)
    }
}
