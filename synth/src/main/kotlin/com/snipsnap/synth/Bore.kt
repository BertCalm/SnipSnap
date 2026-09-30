package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * BORE — the blown bore (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md).
 *
 * Everything else in `:synth` is a strike — a burst into a body that rings and
 * dies — or an oscillator under an envelope. This is a *valve in a pipe*: mouth
 * pressure pushes a reed (or an air jet) open, the pressure wave it lets in
 * runs down the bore, reflects at the far end and comes back to the valve a
 * few milliseconds later, where it either helps or hinders the next opening.
 * Above a certain blowing pressure the help wins, the loop's gain passes one,
 * and the note sustains itself for as long as the player blows. The pipe's
 * length sets the pitch; the valve's law sets the tone.
 *
 * The pipe is [Strings.Loop] — the same waveguide PLUCK and SILK ring, with
 * its tuning budget (every filter's delay charged against the loop length so
 * the note lands where TUNE says), its allpass for the fractional sample, and
 * the two things a blown bore needed of it that a string does not (BORE's R0:
 * a DC blocker that stands on its own, and [Strings.Loop.reflected] /
 * [Strings.Loop.inject], so the new sample can be a *nonlinear function* of
 * the wave coming back instead of that wave plus an input).
 *
 * Two voices, two valves:
 *  - [BoreVoice.SAX]: a single reed on a cone. The reed is Smith's reflection
 *    table (STK's clarinet): for the pressure difference across it, what
 *    fraction of the returning wave bounces back — clamped to +-1, so the valve
 *    can shape a note but can never add energy without limit. A cone's
 *    harmonics are the full series, so the loop is a full period long with its
 *    sign kept.
 *  - [BoreVoice.FLUTE]: an air jet. The returning wave deflects a jet that
 *    crosses the mouth hole in half a period; a sharp edge (the labium) turns
 *    that deflection into a pressure, through a soft clip. Also a full-period
 *    loop with its sign kept.
 *
 * What is *not* modelled, on purpose (the design's "Simplifications the model
 * makes, stated"): a truncated cone (the loop is a Benade-compensated cone,
 * exactly harmonic), toneholes (TUNE is bore length), a register hole (no
 * overblowing), reed resonance, and a vocal tract.
 *
 * **What every constant below was measured against.** The design's Phase 0
 * proved the loop; R1 measured the knobs: a print-only probe swept BREATH, LIP
 * and TUNE per voice at the raw rate (Hann-windowed harmonics, autocorrelation
 * pitch, onset envelopes) and the numbers in the KDoc of each constant are what
 * it read. Nothing here was *listened to* — the audition gate is what decides
 * whether the tone is a woodwind, and every value marked "listening" is a first
 * guess for it.
 *
 * The engine renders **dry**. The spec's tape stage (MELLO) is the rack's TAPE
 * by another name; a BORE pad *lands* with it in its recipe ([landingChain]) the
 * way SIREN lands with ECHO, and the pad's tape stays editable like every other.
 */
enum class BoreVoice { FLUTE, SAX }

object Bore {

    /** TUNE: two octaves, snapped to semitones (every melodic engine's rule; SPREAD and in-key need it). */
    const val TUNE_SEMITONES = 24

    /** FLUTE's root, C4, so the knob spans C4-C6: the register a flute lives in, where the bore-relative bell (below) keeps the 3rd mode from taking over. */
    const val FLUTE_ROOT_MIDI = 60

    /**
     * SAX's root, C3, so the knob spans C3-C5, an alto/tenor's range. Not lower:
     * a reed's onset takes a fixed number of *periods*, so it is slower the lower
     * the note - measured 0.3-0.4 s to full amplitude at 131 Hz at the default knobs
     * (0.63 s at the tightest lip and hardest breath), 0.4 s at 92 Hz and over 0.8 s at
     * 65 Hz, which is a stab that never speaks. A baritone's octave
     * needs a faster starter (R2).
     */
    const val SAX_ROOT_MIDI = 48

    // ---- the pipe ----------------------------------------------------------

    /** What a trip round the pipe keeps of the wave: the loss the valve has to overcome. STK's clarinet uses 0.95. A higher gain moved the reed's threshold and its onset by less than the noise (0.95 -> 0.995: the same 0.3-0.5 s), so it stays where the design put it. */
    const val LOOP_GAIN = 0.95f

    /**
     * The reed voice's bell one-pole: a fixed frequency, because a bell's radiation
     * cutoff belongs to the instrument, not to the note - so a low note is brighter
     * than a high one, as a real sax's is. BREATH moves it by [BELL_BREATH_LOW]..
     * [BELL_BREATH_HIGH] (below).
     */
    const val REED_BELL_HZ = 2_500f

    /**
     * BREATH's explicit brightness link: the bell's cutoff is multiplied by this
     * from the softest breath to the hardest. Loudness is levelled away by
     * `Dsp.levelTo`, so a harder breath has to be *timbre* or it is nothing: the
     * reed's own nonlinearity brightens it only a little (measured: the 2nd
     * harmonic moves 6 dB across the whole window) and the jet's not at all.
     */
    const val BELL_BREATH_LOW = 0.8f
    const val BELL_BREATH_HIGH = 1.3f

    /**
     * The reed's bite: a presence bell on what the horn radiates, outside the pipe. A reed's
     * edge lives at 1-4 kHz, where the loop's one-pole bell (a low-pass) leaves the partials
     * 15-25 dB under the fundamental. The bell is [BITE_DB] at [BITE_HZ] for the loosest lip,
     * eased to [BITE_TIGHT_SHARE] of that in dB at the tightest, so a pinched reed stays
     * mellow and a loose one buzzes (LIP already drops the 2nd harmonic 8 dB the same way).
     *
     * Outside the loop on purpose. The first try was a brighter in-loop bell (2500 -> 5000 Hz),
     * which did add the bite and also woke the pipe's upper modes: 16 of 117 corners of a
     * SAX scan no longer held a steady pitch to close as a LOOP (2 of 117 do at 2500). A
     * linear filter after the loop cannot do that to it, and a periodic wave through one is
     * still periodic - the LOOP's seam is unmoved.
     */
    const val BITE_HZ = 2_000f
    const val BITE_Q = 0.7f
    const val BITE_DB = 12f
    const val BITE_TIGHT_SHARE = 0.4f

    /**
     * The cone's DC blocker sits at `f0 / this`. The blocker's phase lead is
     * budgeted at the fundamental only, so a high corner leaves the upper partials
     * off their harmonics (Phase 0: 68 cents flat on the 2nd mode at 20 Hz), while
     * a low one lets the reed's operating point relax at a sub-audio rate (a slow
     * chug under the note); 25 is the measured compromise, not a solution - the
     * honest alternatives are the apex allpass or a two-segment cone.
     */
    const val SAX_BLOCKER_DIVISOR = 25f

    /**
     * The played pitch's residual against TUNE, and the constant that cancels it.
     * SAX plays 3-9 cents flat (worst at LIP 0 and the bottom of the range) and
     * FLUTE 0-7 cents sharp (worse the harder it is blown and the higher the note):
     * the nonlinearity's own pitch pull, which no tuning budget charges. A fixed
     * pin leaves the worst case inside +-5 cents in the probe's sweep; the LOOP
     * step retunes exactly (see [renderLoop]).
     */
    const val SAX_PIN_CENTS = 4f
    const val FLUTE_PIN_CENTS = -3f

    // ---- the reed ----------------------------------------------------------

    /**
     * The reed table's slope, fixed (STK clarinet's -0.3). LIP is *not* the
     * slope: `slope * pressure` enters the table as one product, so making LIP
     * the slope with an absolute pressure range was only a gain knob - the
     * corners choked or starved instead of changing tone (Phase 0, finding 2).
     * LIP is the table's offset instead, below.
     */
    const val REED_SLOPE = -0.3f

    /** LIP: the reed's rest reflection - loose (0.5) is open and buzzy, tight is closer to beating and purer. Not 0.85 as Phase 0's spec had it: there the window shrinks to a sliver and the note to a tenth of the amplitude (measured), so the tight end stops where a note still speaks over the whole window. */
    const val OFFSET_LOOSE = 0.5f
    const val OFFSET_TIGHT = 0.78f

    /**
     * BREATH tops out here, as a share of the reed's closing pressure: under it the
     * hardest breath still sits in the plateau, clear of the extinction at 1.0
     * (measured: a tight reed dies there, and the overshoot below adds 20% on the front
     * of the note, so the cap is [CAP_SHARE] on top of this).
     */
    const val SHARE_TOP = 0.93f

    /**
     * BREATH's floor sits this far above the *measured* speaking threshold, so the
     * bottom of the knob is the softest note that speaks quickly - not the softest
     * that speaks eventually. The onset is fastest at shares of about 0.78-0.8 and
     * takes twice as long within a few percent of the threshold, so the floor is
     * in the fast zone (Phase 0's own check: 0.60 measured against 0.58 predicted;
     * R1's wider sweep found the prediction low by 0.05 at LIP 0.5 and 0.10 at the
     * tight end, which is what [thresholdShare] fits).
     */
    const val THRESHOLD_MARGIN = 0.08f

    /** The reed starts from a little more than its steady pressure, relaxing over [OVERSHOOT_SECONDS]: a tongue's release. It shortens the near-floor onset at 131 Hz from 0.5 s to 0.35 s (measured, 0.2). */
    const val OVERSHOOT = 0.2f
    const val OVERSHOOT_SECONDS = 0.12f

    /** The cap on pressure as a share of the closing pressure: the reflection clamps at +1 and the reed stops speaking. */
    const val CAP_SHARE = 0.99f

    // ---- the jet -----------------------------------------------------------

    /**
     * FLUTE's BREATH window, as mouth pressure. The jet starts near 0.65 at every
     * setting and needs 0.80 with the darkest bore (bell 1.5x, which is below
     * LIP's range - measured); above 1.0 nothing changes but the pitch (+5 cents).
     */
    const val FLUTE_PRESSURE_LOW = 0.80f
    const val FLUTE_PRESSURE_HIGH = 1.02f

    /** The jet crosses the mouth hole in half a period: the register condition that locked in Phase 0 (0.32 and 0.40 did not). */
    const val JET_RATIO = 0.5f

    /** How much of the returning wave deflects the jet, and how much reflects straight back off the open end. */
    const val JET_REFLECTION = 0.5f
    const val END_REFLECTION = 0.5f

    /** Pressure as the jet's *input gain*: blowing harder scales the deflection instead of shifting the sigmoid's operating point. */
    const val JET_GAIN_PER_P = 3f

    /**
     * LIP on FLUTE moves two things, both brightness: the jet's rest offset from
     * the edge (0.08 to 0.5: the 2nd harmonic rises from -52 to -36 dB) and the
     * bore's cutoff as a multiple of the note ([FLUTE_BELL_RATIO_LOW] to
     * [FLUTE_BELL_RATIO_HIGH]: the 3rd harmonic rises from -19 to -13 dB). The
     * offset can never be 0: a jet dead-centre on the edge is perfectly
     * symmetric and never starts (measured: silence).
     *
     * The cutoff is a *ratio* to the note, not a frequency, because the jet's
     * tanh is a hard limiter: with a fixed 6 kHz bell a C4's 3rd mode took over
     * above p = 0.8 and jumped an octave-and-a-fifth; with the ratio the
     * fundamental leads everywhere in the window and the timbre follows the note.
     */
    const val JET_OFFSET_LOW = 0.08f
    const val JET_OFFSET_HIGH = 0.5f
    const val FLUTE_BELL_RATIO_LOW = 2f
    const val FLUTE_BELL_RATIO_HIGH = 6f
    const val FLUTE_BELL_BREATH_LOW = 0.9f
    const val FLUTE_BELL_BREATH_HIGH = 1.2f

    // ---- the breath --------------------------------------------------------

    /**
     * Turbulence is multiplicative on the pressure - noise *inside* the loop,
     * filtered by it, the one place no other engine puts it. BREATH moves it from
     * LOW to HIGH. Measured as aperiodicity at C5 (energy left after subtracting the
     * note from itself one period on): FLUTE 0.05 is about -50 dB (a clean tone) and
     * 0.6 about -28 dB (a breathy flute is -20 to -30); SAX 0.03 is about -62 dB and
     * 0.3 about -43 dB loose, -48 dB tight (a reed hides its breath much better, and
     * a tight reed stalls above about 0.7: its level falls to a fifth, and above 0.4 it
     * already starts slowly at C3 - 0.74 s to speak, against 0.54 s at 0.3). The first
     * values here, the spec's 0.02-0.06, were measured -46 to -55 dB once the output
     * tap was the bore wave - inaudible, so BREATH had no breath in it. Listening values.
     */
    const val FLUTE_TURBULENCE_LOW = 0.05f
    const val FLUTE_TURBULENCE_HIGH = 0.6f
    const val REED_TURBULENCE_LOW = 0.03f
    const val REED_TURBULENCE_HIGH = 0.3f

    // ---- the gate ----------------------------------------------------------

    /** HOLD, below its LOOP step: how long the player blows, after the attack. SIREN's mapping. */
    const val HOLD_MIN_SECONDS = 0.3f
    const val HOLD_MAX_SECONDS = 4f

    /** HOLD at or above this is a LOOP (the top step of the knob); SCRAMBLE never lands there. SIREN's own numbers. */
    const val LOOP_THRESHOLD = 0.99f
    const val SCRAMBLE_HOLD_CEILING = 0.95f
    const val DEFAULT_HOLD = 0.45f

    /** CHIFF: the attack runs from a breath swell to a hard tongue... */
    const val ATTACK_SWELL_SECONDS = 0.12f
    const val ATTACK_TONGUE_SECONDS = 0.008f

    /**
     * ...and adds a burst of breath noise on the front of the note, CHIFF times
     * this on top of BREATH's turbulence, dying over [CHIFF_NOISE_SECONDS]: the
     * flute's chiff, and a seed for the loop. Measured: it speeds a 262 Hz reed's
     * onset from 0.35 s to 0.2 s and does nothing below 100 Hz. Listening value.
     */
    const val CHIFF_NOISE = 0.8f
    const val CHIFF_NOISE_SECONDS = 0.05f

    /**
     * The tongue's release: a short pressure pulse when the swell reaches speaking
     * pressure, POP_LEVEL times the breath. A reed's onset is exponential growth from
     * whatever seed the loop has, and the turbulence seeds it at about 1e-3 of the
     * steady amplitude - hence 0.3-0.5 s of nothing at 131-262 Hz. A pulse of area
     * `a` seeds the fundamental at `2 a f0`, about a hundred times more. (With BREATH
     * high enough to put much turbulence in the loop the noise seeds it as well and the
     * pop adds nothing; it is for the soft end of the knob.) Reeds only:
     * the jet starts fast on its own. Listening value.
     */
    const val POP_LEVEL = 0.3f
    const val POP_SECONDS = 0.004
    const val POP_AT = 0.75f

    /** A blown note ends when the breath does: a short linear release, a cut without a click. */
    const val RELEASE_SECONDS = 0.08f

    /**
     * CHIFF's top adds a key thump - the spec's 90 Hz pressure-pad clonk, the one
     * MELLO part with no relative in the tree - relocated to the one place it is
     * engine-native: an onset. Below [THUMP_FROM] there is none, so a default CHIFF
     * never has one. It runs until its envelope is under 1e-3 (-60 dB, about
     * 105 ms at this decay) instead of being cut at -30 dB, which is a click.
     */
    const val THUMP_FROM = 0.7f
    const val THUMP_HZ = 90.0
    const val THUMP_TAU_SECONDS = 0.015
    const val THUMP_SECONDS = 0.105
    const val THUMP_LEVEL = 0.8f

    // ---- vibrato -----------------------------------------------------------

    /**
     * Baked, not a knob, and scaled by HOLD so a 0.3 s stab has none and a long note has
     * the most (a LOOP has none - it has to close). It moves the *pitch*: the loop is
     * retuned every [VIBRATO_BLOCK] samples ([Strings.Loop.retune] carries the loop's
     * state through, no click) by up to [VIBRATO_MAX_CENTS] either side at
     * [VIBRATO_HZ], after [VIBRATO_DELAY_SECONDS] and rising in over
     * [VIBRATO_RISE_SECONDS], the way a player leans into it.
     *
     * Not pressure, which is what the spec said and what R1 first built: a 4% wobble of
     * the breath moved the pitch by under a cent (measured, and by the same under a
     * cent with HOLD at 0), because a reed's and a jet's pitch barely follow pressure -
     * the whole BREATH window moves it 2-5 cents. It was dead code that claimed a
     * feature. Jaw and lip motion change the pipe's *effective length*, which is what
     * [Strings.Loop.retune] changes.
     */
    const val VIBRATO_HZ = 5.2
    const val VIBRATO_DELAY_SECONDS = 0.06
    const val VIBRATO_RISE_SECONDS = 0.2
    const val VIBRATO_MAX_CENTS = 20f
    private const val VIBRATO_BLOCK = 64

    // ---- the output --------------------------------------------------------

    /** The DC goes twice (FORK's rule): the blocker in the loop, then the mean and this high-pass before the level is set. */
    const val OUTPUT_DC_HZ = 20f

    /**
     * The classifier's own length rule: past this a render's duration alone reads
     * LOOP. [drumClassFor] derives from it, never from a hard-coded knob position.
     */
    const val LOOP_THRESHOLD_SECONDS = 1.5f

    // ---- the LOOP step -----------------------------------------------------

    /** A LOOP holds at least this long, in whole periods, so it is over the classifier's line with room. */
    const val LOOP_SECONDS = 2f

    /**
     * The steady state is reached and stable this long before a LOOP is cut: at least this
     * many seconds, and at least [LOOP_WARMUP_PERIODS] periods. A reed at its loosest and
     * hardest was still creeping up in amplitude at 131 Hz after 3 s (the seam at 1.5 s
     * warm-up: 5.6e-2, against a bar of 1e-3), because what it takes is a number of
     * periods, not of seconds.
     */
    const val LOOP_WARMUP_SECONDS = 1.5f
    const val LOOP_WARMUP_PERIODS = 400

    /**
     * The most times the loop's pitch is corrected so its whole periods fill its whole frames, and
     * how close to 1 the measured length over the wanted one must be to stop early. The measure
     * settles to about 1e-7 (a hundred-thousandth of a cent); three passes left 4e-7 at 262
     * periods, and the bite bell weights the top partials, where that is a visible seam.
     */
    private const val LOOP_PASSES = 5
    private const val LOOP_CONVERGED = 3e-7

    /** Frames of steady stretch kept past the loop, so the long-lag measurement has samples to look at. */
    private const val LOOP_PAD_FRAMES = 2048

    /** The frames [Keys.seamError] looks at before the loop start: its own window. */
    private const val SEAM_FRAMES = 256

    /**
     * The rack's TAPE as a one-shot BORE lands with it: the tape-flute story told
     * as a recipe instead of as DSP. A LOOP lands dry, for SIREN's reason - wow at
     * 1.3 Hz cannot ride a wrap unless the loop holds whole wow periods. The
     * numbers are a gate question (the audition A/Bs 0.1 against 0.2 WOBBLE), not
     * a default to guess.
     */
    val LANDING_TAPE: Map<String, Float> = mapOf("WOBBLE" to 0.1f, "DRIVE" to 0.25f, "AGE" to 0.5f)

    fun macrosFor(voice: BoreVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("BREATH", 0.6f),
        MacroSpec("LIP", 0.5f, neutral = 0.5f),
        MacroSpec("CHIFF", 0.4f),
        MacroSpec("HOLD", DEFAULT_HOLD),
    )

    fun defaults(voice: BoreVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(macros: Map<String, Float>, voice: BoreVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun rootMidi(voice: BoreVoice): Int = when (voice) {
        BoreVoice.FLUTE -> FLUTE_ROOT_MIDI
        BoreVoice.SAX -> SAX_ROOT_MIDI
    }

    /** The snapped MIDI note TUNE lands on. */
    fun midiFor(voice: BoreVoice, tune: Float): Int = rootMidi(voice) + Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)

    fun frequencyFor(voice: BoreVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    /** The frequency the loop is *tuned* to so the note *plays* at [hz]: the pin ([SAX_PIN_CENTS], [FLUTE_PIN_CENTS]) applied. */
    internal fun tunedHz(voice: BoreVoice, hz: Float): Float {
        val cents = if (voice == BoreVoice.FLUTE) FLUTE_PIN_CENTS else SAX_PIN_CENTS
        return hz * 2f.pow(cents / 1200f)
    }

    // ---- what the macros mean ----------------------------------------------

    /** LIP on a reed: the table's offset. */
    internal fun reedOffset(lip: Float): Float = Dsp.lin(lip, OFFSET_LOOSE, OFFSET_TIGHT)

    /** The pressure at which the reed beats shut: past it the reflection clamps at +1 and nothing starts. */
    internal fun closingPressure(offset: Float): Float = (1f - offset) / -REED_SLOPE

    /**
     * Where a reed starts to speak, as a share of [closingPressure], fitted to what
     * R1's sweep measured at 131-262 Hz: 0.60 at the loose end to 0.82 at offset
     * 0.85, a line through them, a little above the points in between so the floor
     * that sits on it errs toward speaking. The small-signal prediction
     * `(1/g - offset) / (2 (1 - offset))` is 0.55 at the loose end and 0.675 at 0.85,
     * low by more than the floor's whole margin at the tight end.
     */
    internal fun thresholdShare(offset: Float): Float = 0.60f + 0.63f * (offset - OFFSET_LOOSE)

    /**
     * BREATH on a reed, as mouth pressure: a *share of the closing pressure* between
     * the softest note that speaks quickly and just under extinction. BREATH cannot
     * map linearly onto raw pressure - the window moves with LIP and is about a
     * third of the axis wide - so the bottom of the knob would be air and the top
     * silence (playability rule 3, "sweet spots are wide by construction", fails at
     * both ends).
     */
    internal fun reedPressure(breath: Float, offset: Float): Float {
        val floor = (thresholdShare(offset) + THRESHOLD_MARGIN).coerceAtMost(SHARE_TOP - 0.05f)
        return closingPressure(offset) * Dsp.lin(breath, floor, SHARE_TOP)
    }

    /** BREATH on FLUTE, as mouth pressure: the jet's own window. */
    internal fun flutePressure(breath: Float): Float = Dsp.lin(breath, FLUTE_PRESSURE_LOW, FLUTE_PRESSURE_HIGH)

    internal fun turbulenceFor(voice: BoreVoice, breath: Float): Float =
        if (voice == BoreVoice.FLUTE) Dsp.lin(breath, FLUTE_TURBULENCE_LOW, FLUTE_TURBULENCE_HIGH)
        else Dsp.lin(breath, REED_TURBULENCE_LOW, REED_TURBULENCE_HIGH)

    /** The bore's one-pole cutoff at [hz] for these macros: BREATH brightens both voices, LIP the flute's by the note. */
    internal fun bellHzFor(voice: BoreVoice, hz: Float, breath: Float, lip: Float): Float =
        if (voice == BoreVoice.FLUTE) {
            hz * Dsp.expMap(lip, FLUTE_BELL_RATIO_LOW, FLUTE_BELL_RATIO_HIGH) * Dsp.lin(breath, FLUTE_BELL_BREATH_LOW, FLUTE_BELL_BREATH_HIGH)
        } else {
            REED_BELL_HZ * Dsp.lin(breath, BELL_BREATH_LOW, BELL_BREATH_HIGH)
        }

    /** The presence bell's gain in dB: the reed voice's [BITE_DB], eased down to [BITE_TIGHT_SHARE] of it as LIP tightens. The flute has none. */
    internal fun biteBoostDb(voice: BoreVoice, lip: Float): Float =
        if (voice == BoreVoice.FLUTE) 0f else BITE_DB * Dsp.lin(lip, 1f, BITE_TIGHT_SHARE)

    fun attackSeconds(chiff: Float): Float = Dsp.expMap(chiff, ATTACK_SWELL_SECONDS, ATTACK_TONGUE_SECONDS)

    fun holdSeconds(hold: Float): Float =
        Dsp.expMap(hold.coerceAtMost(LOOP_THRESHOLD) / LOOP_THRESHOLD, HOLD_MIN_SECONDS, HOLD_MAX_SECONDS)

    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD

    /**
     * The exact number of frames a one-shot at [macros] renders at [RATE]: the gate's raw
     * samples (the attack, the breath, the release - sized by [gateFor], the one place
     * [blow] sizes them too), through the decimator's two floored 2:1 steps, which come to
     * `n / OVERSAMPLE` for a whole-number OVERSAMPLE. The fade at the tail does not change
     * the length. `BoreTest` pins this against a real render across the LOOP line, so a
     * change to the decimator cannot move it silently.
     */
    internal fun renderFrames(macros: Map<String, Float>): Int =
        gateFor(macros, RATE * Dsp.OVERSAMPLE, null).total / Dsp.OVERSAMPLE

    /**
     * What a pad holding this sound is filed as. Derived from the *rendered frame
     * count* against the classifier's own 1.5 s line - the same comparison it makes,
     * `frames.toFloat() / RATE > 1.5f` - never from a hard-coded knob position, and never
     * from a duration with padding added (a 10 ms pad here filed a note 1.49998 s long as a
     * LOOP when the classifier reads it PERC): a note is
     * PERC below the line and a LOOP above it. The classifier may read a low SAX
     * note TONAL instead of PERC (its bass gate: over 55% of the head window's
     * magnitude under 200 Hz, ringing past 500 ms - a share the fundamental's own
     * skirts can supply at C3-A3); that is a different non-drum shelf, not a wrong
     * one, and it depends on the spectrum, which macros alone do not promise. The
     * LOOP line is exact.
     */
    fun drumClassFor(voice: BoreVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(macros, voice)
        val long = isLoop(m.getValue("HOLD")) || renderFrames(m).toFloat() / RATE > LOOP_THRESHOLD_SECONDS
        return if (long) DrumClass.LOOP else DrumClass.PERC
    }

    /**
     * SCRAMBLE near a preset (see [Thump.scramble]); HOLD is capped short of LOOP,
     * because the top step is a choice, not a roll. BREATH and LIP are mapped onto
     * the speaking window, so any roll speaks.
     */
    fun scramble(voice: BoreVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + BorePresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        val rolled = Dsp.scrambleNear(seed, temperature, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(SCRAMBLE_HOLD_CEILING)
        return rolled
    }

    /** The chain SEND TO PAD bakes into a BORE recipe: TAPE on a one-shot, nothing on a LOOP. */
    fun landingChain(macros: Map<String, Float>): FxChain? =
        if (isLoop(macros["HOLD"] ?: DEFAULT_HOLD)) null else FxChain().withSection("tape", LANDING_TAPE)

    // ---- the blow ----------------------------------------------------------

    /** What a blow is asked to be: a one-shot's attack / breath / release, or a LOOP's steady stretch (attack, then constant pressure to the end). */
    private class Gate(val attackN: Int, val holdN: Int, val releaseN: Int, val steady: Boolean) {
        val total get() = attackN + holdN + releaseN
    }

    /**
     * The blown bore at exact [hz] (the *tuned* frequency - [tunedHz] applies the pin),
     * at [rate] (the oversampled render rate): the raw loop output, before the band
     * limit, the decimator and the level - so a test can read the physics without
     * [Dsp.levelTo] lifting a silent render to the loudness target and hiding the
     * defect (the `Fork.bank` rule). [macros] must carry every macro ([settled] does that).
     *
     * The overrides are for tests and probes: [turbulence] replaces BREATH's noise
     * (0 is none), [gateSeconds] replaces HOLD's length, and [pressure] replaces
     * BREATH's whole mapping with an absolute mouth pressure - a share of the
     * closing pressure on SAX, so a sweep can find the window's real edges.
     */
    internal fun blow(
        voice: BoreVoice,
        hz: Float,
        macros: Map<String, Float>,
        rate: Int = RATE * Dsp.OVERSAMPLE,
        turbulence: Float? = null,
        gateSeconds: Float? = null,
        pressure: Float? = null,
        pop: Float? = null,
    ): FloatArray {
        return blow(voice, hz, macros, rate, gateFor(macros, rate, gateSeconds), turbulence, pressure, pop)
    }

    /** A one-shot's gate in raw samples at [rate]: the one place its length is worked out, for [blow] and [renderFrames] alike. */
    private fun gateFor(macros: Map<String, Float>, rate: Int, gateSeconds: Float?): Gate {
        val attackN = (attackSeconds(macros.getValue("CHIFF")) * rate).toInt().coerceAtLeast(1)
        val holdN = ((gateSeconds ?: holdSeconds(macros.getValue("HOLD"))) * rate).toInt()
        val releaseN = (RELEASE_SECONDS * rate).toInt().coerceAtLeast(1)
        return Gate(attackN, holdN, releaseN, steady = false)
    }

    private fun blow(
        voice: BoreVoice,
        hz: Float,
        macros: Map<String, Float>,
        rate: Int,
        gate: Gate,
        turbulence: Float?,
        pressure: Float?,
        pop: Float? = null,
    ): FloatArray {
        val flute = voice == BoreVoice.FLUTE
        val breath = macros.getValue("BREATH")
        val lip = macros.getValue("LIP")
        val chiff = macros.getValue("CHIFF")
        val holdMacro = macros.getValue("HOLD")
        // A LOOP's stretch is steady: no vibrato, no chiff, no thump, no overshoot,
        // and no noise - a loop cannot carry a signal that does not repeat.
        val steady = gate.steady

        val bellHz = bellHzFor(voice, hz, breath, lip)
        val dcHz = if (flute) Strings.DC_BLOCK_HZ else hz / SAX_BLOCKER_DIVISOR
        val vibCents = if (steady) 0f else VIBRATO_MAX_CENTS * (holdMacro / LOOP_THRESHOLD).coerceIn(0f, 1f)
        // A retune may only shorten the loop the ring was built for, so the ring is built for the
        // lowest pitch of the swing and brought up to the note.
        val tuning = Strings.tune(hz * 2f.pow(-vibCents / 1200f), bellHz, rate, dcBlock = true, dcHz = dcHz)
        val loop = Strings.Loop(tuning.n, tuning.a, LOOP_GAIN, bellHz, rate, dcBlock = true, dcHz = dcHz)
        if (vibCents > 0f) loop.retune(hz)

        val noise = Dsp.Noise(Dsp.seedFor("BORE", voice, hz))
        val turb = if (steady) 0f else turbulence ?: turbulenceFor(voice, breath)
        val offset = reedOffset(lip)
        val closing = closingPressure(offset)
        val pMax = when {
            pressure == null -> if (flute) flutePressure(breath) else reedPressure(breath, offset)
            flute -> pressure
            else -> closing * pressure
        }
        val cap = if (flute) FLUTE_PRESSURE_HIGH * 1.3f else closing * CAP_SHARE

        // The jet's transit time is a *fractional* delay (linear interpolation): rounded to a whole
        // sample it moved the played pitch in steps of about a quarter of a percent, and a LOOP that
        // corrects the pitch by measuring it oscillated instead of converging.
        val jetDelay = (JET_RATIO * rate / hz).coerceAtLeast(2f)
        val jet = FloatArray(ceil(jetDelay).toInt() + 2)
        var jw = 0
        val jetBias = Dsp.lin(lip, JET_OFFSET_LOW, JET_OFFSET_HIGH)

        val chiffNoise = if (steady) 0f else CHIFF_NOISE * chiff
        val overshoot = if (steady || flute) 0f else OVERSHOOT
        val popLevel = if (steady || flute) 0f else pop ?: POP_LEVEL
        val popStart = (gate.attackN * POP_AT).toInt()
        val popN = (POP_SECONDS * rate).toInt().coerceAtLeast(2)

        val out = FloatArray(gate.total)
        for (i in 0 until gate.total) {
            val env = when {
                i < gate.attackN -> i.toFloat() / gate.attackN
                !steady && i >= gate.attackN + gate.holdN -> ((gate.total - i).toFloat() / gate.releaseN).coerceAtLeast(0f)
                else -> 1f
            }
            val t = i.toDouble() / rate
            if (vibCents > 0f && i % VIBRATO_BLOCK == 0 && t > VIBRATO_DELAY_SECONDS) {
                val rise = ((t - VIBRATO_DELAY_SECONDS) / VIBRATO_RISE_SECONDS).coerceAtMost(1.0)
                val cents = vibCents * (rise * sin(2.0 * PI * VIBRATO_HZ * t)).toFloat()
                loop.retune(hz * 2f.pow(cents / 1200f))
            }
            val over = if (overshoot > 0f) 1f + overshoot * exp(-t / OVERSHOOT_SECONDS).toFloat() else 1f
            val burst = if (chiffNoise > 0f) chiffNoise * exp(-t / CHIFF_NOISE_SECONDS).toFloat() else 0f
            val popNow = if (popLevel > 0f && i >= popStart && i < popStart + popN) popLevel * pMax * sin(PI * (i - popStart) / popN).toFloat() else 0f
            val pm = (pMax * env * over * (1f + (turb + burst) * noise.next()) + popNow).coerceAtMost(cap)
            val returning = loop.reflected()
            val y = if (flute) {
                // The returning wave deflects the jet; the disturbance crosses the mouth (the jet line);
                // the labium turns it into a pressure through a soft clip. Pressure is the jet's input gain.
                val at = jw - jetDelay
                val i0 = kotlin.math.floor(at).toInt()
                val frac = at - i0
                val older = jet[Math.floorMod(i0, jet.size)]
                val newer = jet[Math.floorMod(i0 + 1, jet.size)]
                val jetOut = older + (newer - older) * frac
                jet[jw] = -JET_REFLECTION * returning
                jw = (jw + 1) % jet.size
                tanh(JET_GAIN_PER_P * pm * jetOut + jetBias) * pm.coerceAtMost(1f) + END_REFLECTION * returning
            } else {
                // Smith's reed reflection table: passive by construction, |r| <= 1.
                val delta = returning - pm
                val r = (offset + REED_SLOPE * delta).coerceIn(-1f, 1f)
                pm + delta * r
            }
            loop.inject(y)
            // What is heard is the wave in the bore, not the sample the valve injects: `y` carries the
            // mouth's DC pressure, which after the output high-pass is a sub-200 Hz swell at every
            // onset (the classifier read it as a snare or a clap: R1's first preset roster had 8 of 16
            // in a drum's choke group). The returning wave has been through the loop's DC blocker.
            out[i] = returning
        }

        val thumpGain = ((chiff - THUMP_FROM) / (1f - THUMP_FROM)).coerceIn(0f, 1f)
        if (!steady && thumpGain > 0f && pressure == null) addThump(out, rate, thumpGain * THUMP_LEVEL * pMax)
        return out
    }

    /** The key thump: a damped 90 Hz sine at the onset, run to under -60 dB. */
    private fun addThump(buf: FloatArray, rate: Int, level: Float) {
        val n = minOf(buf.size, (THUMP_SECONDS * rate).toInt())
        for (j in 0 until n) {
            val t = j.toDouble() / rate
            buf[j] += (level * exp(-t / THUMP_TAU_SECONDS) * sin(2.0 * PI * THUMP_HZ * t)).toFloat()
        }
    }

    /**
     * The output chain every melodic engine ends in - and not [Punch], which is
     * the drum engines' and boosts a hit's first two milliseconds under a breath
     * attack that has none: the steep band limit at the oversampled rate, the
     * decimator, the DC removed twice. The level and the tail come after, on the
     * kept part ([finish]), so a LOOP's warm-up never counts in its loudness.
     */
    private fun condition(raw: FloatArray, rate: Int, biteDb: Float): FloatArray {
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, RATE)
        var mean = 0.0
        for (v in out) mean += v
        val m = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - m
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        if (biteDb > 0.05f) {
            val bell = Dsp.Biquad()
            bell.peaking(BITE_HZ, biteDb, BITE_Q)
            for (i in out.indices) out[i] = bell.process(out[i])
        }
        return out
    }

    internal fun finish(raw: FloatArray, rate: Int, biteDb: Float = 0f): FloatArray {
        val out = condition(raw, rate, biteDb)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    // ---- the LOOP ----------------------------------------------------------

    /** A LOOP's shape: [periods] whole periods of the note in [frames] whole frames at [RATE]. */
    internal data class LoopPlan(val periods: Int, val frames: Int)

    internal fun planLoop(hz: Float): LoopPlan {
        val periods = ceil(LOOP_SECONDS * hz).toInt().coerceAtLeast(1)
        return LoopPlan(periods, Math.round(periods * RATE.toDouble() / hz).toInt())
    }

    /**
     * The length, in raw samples, of [periods] periods of the periodic stretch [x]
     * from [from] on: the lag near [nominal] where the signal best matches itself,
     * refined between samples.
     *
     * It climbs a ladder - one period, two, four, ... [periods] - because a jump
     * from one to [periods] cannot be trusted. One period's lag is good to a
     * fraction of a sample (a jet's near-square wave has a *triangular* correlation
     * peak, and a parabola through a triangle is off by up to a quarter sample), and
     * a quarter sample times 740 periods is a whole period: the long-lag search then
     * finds the wrong period's peak. Each rung doubles the last, so its estimate is
     * off by under a sample, which a search of a few samples either side always holds.
     */
    internal fun measureLoopSamples(x: FloatArray, from: Int, nominal: Double, periods: Int): Double {
        val onePeriod = nominal / periods
        var k = 1
        var lag = peakLag(x, from, (onePeriod * 0.94).toInt().coerceAtLeast(1)..(onePeriod * 1.06).toInt() + 1)
        while (k < periods) {
            val next = minOf(periods, k * 2)
            val centre = lag * next / k
            lag = peakLag(x, from, (centre - LADDER_SPAN).toInt()..(centre + LADDER_SPAN).toInt())
            k = next
        }
        return lag
    }

    /** How many samples either side of its estimate each rung of [measureLoopSamples] searches. */
    private const val LADDER_SPAN = 3.0

    /** The lag in [lags] where [x] best matches itself from [from], refined between samples. */
    private fun peakLag(x: FloatArray, from: Int, lags: IntRange): Double {
        val window = minOf((0.25 * RATE * Dsp.OVERSAMPLE).toInt(), x.size - from - lags.last - 2).coerceAtLeast(64)
        var bestLag = lags.first
        var best = Double.NEGATIVE_INFINITY
        for (lag in lags) {
            val r = correlate(x, from, lag, window)
            if (r > best) { best = r; bestLag = lag }
        }
        val a = correlate(x, from, bestLag - 1, window)
        val c = correlate(x, from, bestLag + 1, window)
        val d = a - 2 * best + c
        return if (abs(d) < 1e-30) bestLag.toDouble() else bestLag + 0.5 * (a - c) / d
    }

    private fun correlate(x: FloatArray, from: Int, lag: Int, window: Int): Double {
        var s = 0.0
        for (i in from until from + window) s += x[i].toDouble() * x[i + lag]
        return s
    }

    /** The steady stretch: attack, then constant pressure to the end. */
    internal fun stretch(voice: BoreVoice, macros: Map<String, Float>, tuned: Float, warmFrames: Int, frames: Int): FloatArray {
        val rate = RATE * Dsp.OVERSAMPLE
        val attackN = (attackSeconds(macros.getValue("CHIFF")) * rate).toInt().coerceAtLeast(1)
        val total = (warmFrames + frames + LOOP_PAD_FRAMES) * Dsp.OVERSAMPLE
        return blow(voice, tuned, macros, rate, Gate(attackN, total - attackN, 0, steady = true), null, null)
    }

    /**
     * The held note as one loop, [LoopPlan.periods] whole periods in [LoopPlan.frames]
     * whole frames, so the wrap is seamless: rendered at a steady pressure with no
     * turbulence and no vibrato, the warm-up discarded, the *played* pitch corrected
     * until the periods fill the frames - which also lands the loop at exactly TUNE,
     * the pin's residual gone. Then cut where the two neighbours are smallest, so
     * the one-shot ends on the tone's own edge ([Siren.bestCut]'s rule), and levelled.
     *
     * Checked, not assumed: the kept stretch is compared with itself one loop later
     * ([Keys.seamError], the Organ's bar). If a corner cannot close, this throws - a
     * click shipped silently is the worse failure.
     */
    internal fun renderLoop(voice: BoreVoice, macros: Map<String, Float>): FloatArray {
        val r = renderLoopMeasured(voice, macros)
        require(r.seam < Keys.MAX_SEAM_ERROR) {
            "BORE $voice ${midiFor(voice, settled(macros, voice).getValue("TUNE"))}: the loop does not close (seam %.2e, bar %.0e)".format(java.util.Locale.ROOT, r.seam, Keys.MAX_SEAM_ERROR)
        }
        return r.loop
    }

    /** A rendered LOOP and how well its stretch closes on itself ([Keys.seamError]). */
    internal class LoopRender(val loop: FloatArray, val seam: Double)

    internal fun renderLoopMeasured(voice: BoreVoice, macros: Map<String, Float>): LoopRender {
        val m = settled(macros, voice)
        val target = frequencyFor(voice, m.getValue("TUNE"))
        val plan = planLoop(target)
        val over = Dsp.OVERSAMPLE
        val warm = Math.round(max(LOOP_WARMUP_SECONDS, LOOP_WARMUP_PERIODS / target) * RATE)
        val wanted = plan.frames.toDouble() * over
        var tuned = tunedHz(voice, target).toDouble()
        var raw = stretch(voice, m, tuned.toFloat(), warm, plan.frames)
        for (pass in 1..LOOP_PASSES) {
            val ratio = measureLoopSamples(raw, warm * over, wanted, plan.periods) / wanted
            if (abs(ratio - 1.0) < LOOP_CONVERGED) break
            tuned *= ratio
            raw = stretch(voice, m, tuned.toFloat(), warm, plan.frames)
        }
        val conditioned = condition(raw, RATE * over, biteBoostDb(voice, m.getValue("LIP")))
        // The check that means something: the kept stretch against itself one loop later. (The loop
        // played twice is tautologically periodic - it would pass whatever was in it.)
        val seam = Keys.seamError(conditioned.copyOfRange(warm, warm + plan.frames + SEAM_FRAMES), SEAM_FRAMES)
        val one = conditioned.copyOfRange(warm, warm + plan.frames)
        val cut = Siren.bestCut(one, plan.frames)
        val loop = one.copyOfRange(cut, plan.frames) + one.copyOfRange(0, cut)
        Dsp.levelTo(loop, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return LoopRender(loop, seam)
    }

    // ---- the render --------------------------------------------------------

    fun render(voice: BoreVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return Snip(renderLoop(voice, m), channels = 1, sampleRate = RATE)
        val hz = tunedHz(voice, frequencyFor(voice, m.getValue("TUNE")))
        val rate = RATE * Dsp.OVERSAMPLE
        return Snip(finish(blow(voice, hz, m, rate), rate, biteBoostDb(voice, m.getValue("LIP"))), channels = 1, sampleRate = RATE)
    }
}
