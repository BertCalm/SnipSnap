package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * FORK — the modal electric piano engine
 * (docs/superpowers/specs/2026-09-27-fork-electric-piano-engine-design.md).
 *
 * Every other struck engine here is the strike: THUMP and SKIN shape a
 * transient and let it die. FORK's identity is what happens *after* the
 * strike — a magnetic pickup reading a decaying bar through a curve that
 * is not straight, so the tone's own harmonics change as it rings down,
 * with no envelope faking it. A sine passed through a curved function
 * comes out with harmonics; an asymmetric curve adds *even* harmonics too,
 * and that even-harmonic growl is the bark. As the tine's swing decays the
 * signal explores less of the curve and the tone cleans up on its own —
 * TINES fakes exactly that with its BITE envelope; here it is free.
 *
 * The first two voices are two answers to "what is the bar":
 * [ForkVoice.TINE] is a cantilever (screwed down at one end, free at the
 * other — the physical shape of a real tine, whose overtones sit far from
 * the fundamental and die in tens of milliseconds), [ForkVoice.BAR] is a
 * free-free bar (the vibraphone's own shape, and the ratios the brief's
 * spec named). Both are shipped because nearly all the harmonic content a
 * listener calls "the sound of an electric piano" comes from the pickup,
 * not the bar, so which bar reads as the instrument is a listening
 * question, not a derivation.
 *
 * Round one's own audition (both voices, sixteen presets) came back: closer
 * on TINE than BAR, but neither read as "piano" outright. [ForkVoice.NODE]
 * is round two's answer — the same cantilever tine as TINE, only read at a
 * different spot: its second mode's own internal node (see
 * [NODE_PICKUP_XI]), which cannot see that mode at all and barely sees the
 * third or fourth either. Same bar, same ratios, only where the pickup
 * listens — a purer, more fundamental-forward tone than TINE's own tip
 * read, without inventing a new physical claim to get there. Round two's
 * own A/B against TINE (nine matched settings) came back too close to call
 * reliably: the pickup's own nonlinearity dominates the audible bark far
 * more than the pre-pickup modal balance does, so silencing one mode was a
 * real but largely masked difference. Round three's pitch glide, even
 * pushed to a deliberately exaggerated 80 cents, came back the same way —
 * the piano-ness gate closed there with FORK standing as its own
 * instrument, not a piano emulation (see [GLIDE_CENTS]'s own KDoc).
 *
 * [ForkVoice.REED] is round four's answer to a different question: not
 * "closer to a Rhodes" but a second real electric-piano family entirely —
 * the Wurlitzer, whose reed is read by an electrostatic (capacitive)
 * pickup rather than a magnetic one. Naively re-deriving that pickup gives
 * FORK's own formula straight back (a capacitive pickup's charge and a
 * magnetic pickup's flux both scale as 1/gap, and differentiating either
 * produces the same curve) — not a new mechanism. The real, sourced
 * difference is the comb-shaped electrode real units use (several plates
 * at different positions shaping the overtones) and the reported sound
 * (sharper, closer to a sawtooth, odd-harmonic-dominant, versus a magnetic
 * pickup's even-harmonic bark) — a comb electrode does not skew asymmetric
 * the way a single flat plate does, so a *symmetric* saturating curve is
 * the physically appropriate model, not another asymmetric one. See
 * [reedPickup]'s own KDoc for the mechanism, and [reedContact]'s for the
 * discrete mechanical-contact rattle layered on top of it once a
 * prototyping pass established that a curve alone reads as a different
 * *tone*, not a different *character*.
 *
 * Same contract as every engine: macros are 0..1 mapped onto bounded
 * musical ranges (SCRAMBLE can't land on garbage), the DSP renders at
 * `RATE * Dsp.OVERSAMPLE` and decimates (U6) — not optional here, since
 * the pickup's nonlinearity makes harmonics the linear resonator never
 * had — and TUNE snaps to semitones like every melodic engine.
 */
enum class ForkVoice { TINE, BAR, NODE, REED }

object Fork {

    /** TUNE: 24 semitones from C3 — the bark lives in the bass and tenor, unlike SIREN's C4. */
    const val TUNE_SEMITONES = 24
    const val ROOT_MIDI = 48 // C3

    /**
     * The two bars' partial ratios, each mode's position relative to the
     * fundamental. TINE is the cantilever eigenvalues (βL = 1.8751, 4.6941,
     * 7.8548, 10.9955, squared and normalised) — the same family
     * [Tines.KALIMBA_PARTIALS] carries for its first three, extended here
     * by the fourth so FORK has the same four-mode budget on every voice.
     * BAR is the free-free bar, exactly [Modes.tableFor]'s own
     * `METAL_BAR` ratios (Euler-Bernoulli free-free eigenvalues 4.730,
     * 7.853, 10.996, 14.137 squared and normalised) — Fletcher & Rossing,
     * Blevins, the same citation `Modes.kt` carries. [ForkVoice.NODE]
     * shares TINE's own table exactly (see [tableFor]) — it is the same
     * tine, only read from a different spot; see [NODE_POSITION_GAIN].
     */
    val TINE_RATIOS = floatArrayOf(1f, 6.267f, 17.548f, 34.386f)
    val BAR_RATIOS = floatArrayOf(1f, 2.756f, 5.404f, 8.933f)

    /** [tableFor] internal for testability: [ForkTest] reads it directly rather than re-deriving the voice-to-table mapping. */
    internal fun tableFor(voice: ForkVoice): FloatArray = when (voice) {
        ForkVoice.TINE, ForkVoice.NODE, ForkVoice.REED -> TINE_RATIOS
        ForkVoice.BAR -> BAR_RATIOS
    }

    /**
     * The cantilever's own eigenvalues (βL), to full double precision —
     * [TINE_RATIOS]' own KDoc rounds these to four digits for the ratio
     * table, which is fine for a ratio but not for [cantileverModeShape]:
     * `cosh(βL)` grows fast enough that Float32's own ~7-digit precision,
     * not just a rounded literal, already moved mode 4's boundary-condition
     * check past 1e-3 (checked directly before switching this to `Double`,
     * not assumed). These satisfy the cantilever characteristic equation
     * `cosh(βL)·cos(βL) = -1` to 3e-5 or tighter at every mode — Blevins,
     * "Formulas for Natural Frequency and Mode Shape", the same family
     * [TINE_RATIOS] cites.
     */
    internal val CANTILEVER_BETA_L = doubleArrayOf(1.8751040687, 4.694091133, 7.854757438, 10.995540734)

    /**
     * The cantilever's own mode shape (Euler-Bernoulli, fixed at ξ=0, free at
     * ξ=1), evaluated at a point [xi] along the bar — only ever used here as
     * a *ratio* between two [xi] values, so no normalisation is applied.
     *
     * Derived, not recalled: the fixed end forces `φ(0)=φ'(0)=0`, which
     * collapses the general solution to `A[cosh(βξ)-cos(βξ)] +
     * B[sinh(βξ)-sin(βξ)]`; the free end's own two conditions (`φ''(1)=0`,
     * `φ'''(1)=0`) then fix `B/A` and, requiring both to agree, reproduce
     * the textbook characteristic equation `cosh(β)cos(β) = -1` — the same
     * equation [CANTILEVER_BETA_L] is checked against. That agreement is
     * the verification: an error anywhere in this derivation would not
     * also happen to reproduce the independently-known equation.
     */
    internal fun cantileverModeShape(betaL: Double, xi: Float): Float {
        val b = betaL
        val x = xi.toDouble()
        val alpha = (kotlin.math.sin(b) - kotlin.math.sinh(b)) / (kotlin.math.cosh(b) + kotlin.math.cos(b))
        val shape = (kotlin.math.cosh(b * x) - kotlin.math.cos(b * x)) + alpha * (kotlin.math.sinh(b * x) - kotlin.math.sin(b * x))
        return shape.toFloat()
    }

    /**
     * [ForkVoice.NODE]'s own pickup position along the tine, root (0) to tip
     * (1): the second mode's own internal node, solved by bisection against
     * [cantileverModeShape] (0.783445 — the same value beam-vibration
     * references tabulate for a cantilever's second mode, a cross-check this
     * derivation did not have to pass but did).
     */
    const val NODE_PICKUP_XI = 0.783445f

    /**
     * Per-mode gain [modesFor] applies for [ForkVoice.NODE] on top of the
     * `1/ratio` every voice already carries: [cantileverModeShape] at
     * [NODE_PICKUP_XI] over its own value at the tip (ξ=1, TINE's own read),
     * mode by mode. Mode 2 (index 1) lands at ~0 by construction — that is
     * the node [NODE_PICKUP_XI] was solved for — and modes 3 and 4 are
     * strongly reduced too (their own shape is simply small in that region,
     * not zero); mode 1 is never zero (a cantilever's fundamental has no
     * internal node) and stays positive throughout. A negative entry (modes
     * 3 and 4 here) is not an error: that mode's own displacement really
     * does sit in antiphase at this position relative to the tip, and nothing
     * downstream ([Modes.Mode.gain], [Modes.ring]) requires a positive gain.
     */
    internal val NODE_POSITION_GAIN = FloatArray(CANTILEVER_BETA_L.size) { i ->
        val b = CANTILEVER_BETA_L[i]
        cantileverModeShape(b, NODE_PICKUP_XI) / cantileverModeShape(b, 1f)
    }

    /**
     * REED's own "comb" read: a real electrostatic pickup's electrode is not
     * one flat plate at one spot but several, shaping the overtone balance
     * (the sourced "Tonal Design" claim [ForkVoice.REED]'s own KDoc cites).
     * Modelled as a weighted sum of [cantileverModeShape] at two positions —
     * the tip ([REED_TAP_XI_TIP], TINE's own read) and a point partway down
     * the reed ([REED_TAP_XI_INNER]) — rather than NODE's single relocated
     * tap, so REED's modal color is a genuinely different shape from both
     * TINE's flat 1/ratio and NODE's single-node notch, not a retuned copy
     * of either.
     */
    const val REED_TAP_XI_TIP = 1f
    const val REED_TAP_XI_INNER = 0.35f
    const val REED_TAP_WEIGHT_TIP = 0.4f
    const val REED_TAP_WEIGHT_INNER = 0.6f

    internal val REED_POSITION_GAIN = FloatArray(CANTILEVER_BETA_L.size) { i ->
        val b = CANTILEVER_BETA_L[i]
        val tip = cantileverModeShape(b, 1f)
        (
            REED_TAP_WEIGHT_TIP * cantileverModeShape(b, REED_TAP_XI_TIP) +
                REED_TAP_WEIGHT_INNER * cantileverModeShape(b, REED_TAP_XI_INNER)
            ) / tip
    }

    /**
     * NODE's own pitch glide (round three), at STRIKE 1: how many cents
     * sharp the strike reads before it settles. Shipped at 15 first — a
     * plausible, tasteful-sounding starting point — but two rounds of
     * blind A/B against TINE (round two's pickup position, then this)
     * both came back "too similar" by ear. Round three-B pushed this to
     * 80 cents as a diagnostic, to rule out "not perceptually salient" as
     * the explanation before concluding anything about FORK's own
     * ceiling; the owner's call at that gate was to settle here rather
     * than keep chasing a subtler value — FORK's pickup-and-bar
     * architecture reads as its own instrument, not a piano emulation,
     * and 80 cents is the shipped default. See [bank]'s own KDoc for the
     * mechanism and why it is NODE-only for now.
     */
    const val GLIDE_CENTS = 80f

    /** How long NODE's own glide takes to settle to the tuned pitch - a plausible fast attack-only window, open for the audition gate to move alongside [GLIDE_CENTS]. */
    const val GLIDE_TIME_SECONDS = 0.06f

    /**
     * How much faster each higher mode's t60 falls off relative to the
     * fundamental's: `t60_k = t60_1 / ratio_k^SLOPE`. The spec's two Q
     * figures (2000 at the fundamental, 50 at mode 4) imply a slope near
     * 2.7; bar damping physics sits nearer 2. This starts at the lower,
     * more physical number — open for the audition gate to move.
     */
    const val DECAY_SLOPE = 2f

    const val DECAY_MIN_SECONDS = 0.4f
    const val DECAY_MAX_SECONDS = 5f

    /** STRIKE moves the hammer's cutoff, the burst's own t60, and how hard the tine swings — all together, because a harder hammer is brighter, shorter and bigger. */
    const val STRIKE_CUTOFF_LOW_HZ = 1_500f
    const val STRIKE_CUTOFF_HIGH_HZ = 9_000f
    const val STRIKE_BURST_T60_LONG_MS = 3f
    const val STRIKE_BURST_T60_SHORT_MS = 1f
    const val STRIKE_SWING_LOW = 0.25f
    const val STRIKE_SWING_HIGH = 1f

    /**
     * How much extra, per mode index (0 for the fundamental, up through 3
     * for the fourth mode), STRIKE piles onto that mode's gain at its own
     * top: mode k's gain is multiplied by `1 + STRIKE_BRIGHT_BOOST * k` at
     * STRIKE 1, unchanged at STRIKE 0 — real hammer-strike physics (a
     * harder strike excites higher partials disproportionately more), and
     * the mechanism that actually carries STRIKE's brightness where the
     * excitation cutoff alone cannot: BAR's four modes sit close enough
     * together (ratios up to 8.933) that all of them already clear even
     * STRIKE 0's 1.5 kHz cutoff, leaving that channel nothing to give.
     */
    const val STRIKE_BRIGHT_BOOST = 2.2f

    /**
     * BARK: the pickup's closeness, as a fraction of the gap the safety
     * clamp guards. 0.7 is the harmonic-series estimate (the spec's own
     * arithmetic: at 0.85 the twentieth harmonic already sits *above* the
     * fundamental) rather than a taste call — the aliasing-floor test is
     * what would move it.
     */
    const val BARK_MIN = 0.05f
    const val BARK_MAX = 0.7f

    /** The spec's own safety clamp: `1 - x` never reads below this. Proven dead code at every legal macro corner by [BARK_MAX] * [STRIKE_SWING_HIGH] <= 0.7, tested directly. */
    const val GAP_FLOOR = 0.01f

    /** The pickup's DC-removal high-pass. */
    const val DC_HIGHPASS_HZ = 20f

    /**
     * REED's own drive, in place of TINE/BAR/NODE's asymmetric reluctance
     * curve: [Dsp.drive] (a symmetric tanh, unity make-up) applied to the
     * resonator's own rate of change, so a sine comes out with *odd*
     * harmonics only — the sourced Wurlitzer signature — rather than the
     * even-harmonic bark an asymmetric curve gives. [REED_DRIVE_RAMP_MS]
     * ramps the amount up from zero over the first few milliseconds instead
     * of applying it from sample zero: the resonator's own peak sits in the
     * hammer's own broadband onset click, not the settled swing, and a
     * prototyping pass measured that pushing that click straight into full
     * saturation reads as a burst of harsh, static-like distortion with
     * nothing to do with REED's own character. See [reedPickup].
     */
    const val REED_DRIVE_GAIN = 9f
    const val REED_DRIVE_AT_STRIKE_0 = 0.12f
    const val REED_DRIVE_AT_STRIKE_1 = 1f
    const val REED_DRIVE_RAMP_MS = 8f

    /**
     * BARK, for REED: how close the electrode plate sits, same identity as
     * every other voice's BARK, but two things move together instead of one
     * closeness fraction — the drive's own ceiling (a closer plate is a more
     * sensitive pickup), and [REED_CONTACT_THRESHOLD]'s own position (a
     * closer plate is also physically nearer the reed, so contact happens
     * more readily). BARK 0 keeps drive at a quarter of its STRIKE-coupled
     * value rather than zero — a knob with a dead end at 0 is worse than one
     * that keeps a little identity there, the same reasoning [DRIVE_AT_STRIKE_0]
     * keeps STRIKE off zero.
     */
    const val REED_BARK_DRIVE_LOW = 0.25f
    const val REED_BARK_DRIVE_HIGH = 1f

    /**
     * The physical stop past which the reed cannot swing further without
     * touching the plate, as a fraction of [bank]'s own peak-normalised
     * swing (the same space [STRIKE_SWING_LOW]/[STRIKE_SWING_HIGH] scale
     * into) — high at BARK 0 (a distant plate, contact rare even at
     * STRIKE 1) down to a readily-reached point at BARK 1. See
     * [reedContact].
     */
    const val REED_CONTACT_THRESHOLD_LOW_BARK = 0.9f
    const val REED_CONTACT_THRESHOLD_HIGH_BARK = 0.5f

    /**
     * [reedContact]'s own timing. [REED_CONTACT_GRACE_MS] holds the stop off
     * for the excitation's own onset click (measured: the resonator's
     * absolute peak sits there, not in the settled swing, so an ungated
     * contact model was firing its very first, loudest knock right on top
     * of the hammer's own transient). [REED_CONTACT_REFRACTORY_MS] then
     * holds off retriggering for a stretch after each knock — an ungated
     * version was firing once on nearly every half-cycle the swing stayed
     * over threshold (measured: ~40-50 events for a single hard note), which
     * reads as a sustained buzz rather than the occasional mechanical
     * rattle a real plate contact would be.
     */
    const val REED_CONTACT_GRACE_MS = 4f
    const val REED_CONTACT_REFRACTORY_MS = 20f

    /**
     * The knock itself: an impulse at each contact instant excites two
     * short-ringing resonant filters rather than an enveloped noise burst —
     * a real mechanical rattle has pitch and resonance, not just noise
     * shaped by a volume curve, the same "impulse excites a resonant mode"
     * shape [Modes.ring] already gives the bar itself, just two very short,
     * very undamped modes standing in for the plate's own ring. The click
     * ([REED_CLICK_F0_LOW]..[REED_CLICK_F0_HIGH]) sits below the 2-5kHz
     * band the ear is most sensitive to — measured directly: a first attempt
     * at 3-5kHz read as a piercing "ping" rather than a duller knock, for
     * exactly that reason. The body ([REED_BODY_F0_LOW]..[REED_BODY_F0_HIGH])
     * is a second, lower ring added underneath it: a single resonance reads
     * as a thin click on its own, and a real knock has a thud under the
     * clack, the same reason a struck object of any kind rings at more than
     * one frequency. Both drift slightly per event (picked at random inside
     * their own range) — a real contact does not strike the same spot at
     * the same angle every time.
     */
    const val REED_IMPULSE_STRENGTH = 1f
    const val REED_CLICK_Q = 9f
    const val REED_CLICK_F0_LOW = 1_000f
    const val REED_CLICK_F0_HIGH = 1_800f
    const val REED_CLICK_GAIN = 1.6f
    const val REED_BODY_Q = 7f
    const val REED_BODY_F0_LOW = 350f
    const val REED_BODY_F0_HIGH = 550f
    const val REED_BODY_GAIN = 2.2f

    /** The exciter's second mode: a captured snip's head, kept in the recipe at this length and this sample rate — 882 numbers, the shape SNAP's table set for a 256-point wavetable, DRAW's for a 64-point envelope. */
    const val STRIKER_MS = 20f
    val STRIKER_SAMPLES: Int = (STRIKER_MS / 1000f * RATE).roundToInt()

    /** The 2 ms raised-cosine fade at the striker's own tail, so a cut mid-waveform is not a click. */
    private const val STRIKER_FADE_MS = 2f

    /**
     * A render's own length past the fundamental's t60. t60 is already
     * defined as -60 dB - inaudible - so this is a small safety pad, not
     * a second decay stage; [Dsp.fadeTail] is the truncation's own safety
     * net. Kept modest on purpose: at the default DECAY (t60 ~1.41 s) the
     * render must stay under the classifier's 1.5 s TONAL line, the same
     * way the spec's own numbers assume.
     */
    private const val TAIL_PAD_SECONDS = 0.05f

    /**
     * The classifier's own length rule (the same 1.5 s line SIREN's LOOP
     * presets hit): past this, a render's total duration alone is enough
     * to read LOOP. Below it, FORK's pickup-heavy attack — measured, not
     * assumed — does not clear the classifier's separate decay-shape gate
     * for TONAL either, so it reads PERC. There is no DECAY setting where
     * FORK reads strict TONAL; [drumClassFor] tells the truth about that
     * rather than claiming a middle ground that is not there.
     */
    const val LOOP_THRESHOLD_SECONDS = 1.5f

    fun macrosFor(voice: ForkVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("STRIKE", 0.5f),
        MacroSpec("BARK", 0.4f),
        MacroSpec("STIFF", 0.5f, neutral = 0.5f),
        MacroSpec("DECAY", 0.5f),
        // Defaults to 0 so every existing preset, auditioned in mono, stays
        // byte-identical - widening them silently would discard that
        // listening work (the same convention `Thump.macrosFor`'s own SNARE
        // WIDTH takes). See `bank`'s own KDoc for where it spreads the modes
        // and `render`'s for where mono/stereo forks structurally.
        MacroSpec("WIDTH", 0f),
    )

    fun defaults(voice: ForkVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /**
     * What a pad holding this voice's sound is filed as — DECAY alone
     * decides it (measured in `ForkPresetsTest`: every preset's own
     * DECAY predicts its classifier reading), the same shape [Siren]'s
     * own HOLD-driven [Siren.drumClassFor] takes, since neither engine's
     * pitch-stable, decay-driven identity survives being forced into a
     * static per-voice property.
     */
    fun drumClassFor(voice: ForkVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val decay = macros["DECAY"] ?: defaults(voice).getValue("DECAY")
        val t60 = Dsp.expMap(decay.coerceIn(0f, 1f), DECAY_MIN_SECONDS, DECAY_MAX_SECONDS)
        return if (t60 + TAIL_PAD_SECONDS > LOOP_THRESHOLD_SECONDS) DrumClass.LOOP else DrumClass.PERC
    }

    /** SCRAMBLE near a preset (the same shape every other engine's own `scramble` takes — see [Skin.scramble]'s own KDoc for why around sixteen hand-placed points, not one default). */
    fun scramble(voice: ForkVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + ForkPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    fun midiFor(tune: Float): Int = ROOT_MIDI + Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)

    fun frequencyFor(tune: Float): Float = Keys.midiHz(midiFor(tune))

    private fun settled(macros: Map<String, Float>, voice: ForkVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    /**
     * A mode's ratio at [stiff]: piecewise-linear in the ratio itself, not
     * in some intermediate multiplier, because the ear reads a mode's
     * *position* and a straight line between two positions is the morph
     * with no surprise in the middle. Pinned at every stop: `harmonicK`
     * (0, a string), [tableK] (0.5, the voice's own table), and
     * `1 + 1.5*(tableK - 1)` (1, stretched half again past the table). At
     * `harmonicK == tableK == 1` — mode 1 always — every stop collapses to
     * exactly 1: the fundamental never moves, at any STIFF.
     */
    internal fun stiffRatio(harmonicK: Float, tableK: Float, stiff: Float): Float {
        val s = stiff.coerceIn(0f, 1f)
        return if (s < 0.5f) {
            Dsp.lin(s / 0.5f, harmonicK, tableK)
        } else {
            val stretch = 1f + 1.5f * (tableK - 1f)
            Dsp.lin((s - 0.5f) / 0.5f, tableK, stretch)
        }
    }

    /**
     * The four modes FORK rings for [voice] at [macros]' STIFF and DECAY.
     * Exposed on its own (not just inlined into [bank]) so STIFF's ratio
     * placement and DECAY's per-mode falloff are each a direct, exact
     * claim to test — no spectral estimation needed to check the mapping
     * itself, only to check the render actually carries it.
     *
     * [ForkVoice.NODE] applies [NODE_POSITION_GAIN] and [ForkVoice.REED]
     * applies [REED_POSITION_GAIN] on top of the plain `1/ratio` every voice
     * starts from — the same tine, same ratio table, only where the pickup
     * reads it — indexed by mode position (0..3), not by the mode's own
     * (possibly STIFF-stretched) ratio: [STRIKE_BRIGHT_BOOST] already takes
     * that same "by index, not by current ratio" shape, since STIFF's morph
     * is a sound-design liberty layered on top of the physics, not a claim
     * that the modes stay real cantilever eigenmodes at every STIFF setting.
     */
    internal fun modesFor(voice: ForkVoice, macros: Map<String, Float>): List<Modes.Mode> {
        val table = tableFor(voice)
        val stiff = macros.getValue("STIFF")
        val t60Fund = Dsp.expMap(macros.getValue("DECAY"), DECAY_MIN_SECONDS, DECAY_MAX_SECONDS)
        return table.mapIndexed { i, tableK ->
            val ratio = stiffRatio((i + 1).toFloat(), tableK, stiff)
            val positionGain = when (voice) {
                ForkVoice.NODE -> NODE_POSITION_GAIN[i]
                ForkVoice.REED -> REED_POSITION_GAIN[i]
                else -> 1f
            }
            Modes.Mode(
                ratio = ratio,
                gain = positionGain / ratio,
                t60 = (t60Fund / ratio.pow(DECAY_SLOPE)).coerceAtLeast(0.001f),
            )
        }
    }

    /**
     * The exciter, at [rate]: [striker] resampled and STRIKE-filtered when
     * given, otherwise STRIKE-shaped band-passed noise — a hammer, not a
     * hiss: 0.2 ms attack, a t60 STRIKE shortens from 3 ms to 1 ms, one
     * STRIKE-driven low-pass (harder velocity, more high frequency content
     * through — the original brief's own hammer table), seeded from the
     * voice and note so every render is reproducible with no state carried
     * between calls.
     */
    internal fun excite(voice: ForkVoice, hz: Float, strikeM: Float, striker: FloatArray?, frames: Int, rate: Int): FloatArray {
        val out = FloatArray(frames)
        val cutoff = Dsp.expMap(strikeM, STRIKE_CUTOFF_LOW_HZ, STRIKE_CUTOFF_HIGH_HZ)
        val pole = Dsp.OnePole(rate)
        if (striker != null) {
            val upsampled = if (rate == RATE) striker else Resampler.resample(Snip(striker, 1, RATE), rate).samples
            val n = minOf(upsampled.size, out.size)
            for (i in 0 until n) out[i] = pole.lp(upsampled[i], cutoff)
        } else {
            val noise = Dsp.Noise(Dsp.seedFor("FORK", voice, hz))
            val burstT60 = Dsp.lin(strikeM, STRIKE_BURST_T60_LONG_MS, STRIKE_BURST_T60_SHORT_MS) / 1000f
            val env = Dsp.Env(attackSeconds = 0.0002f, decay2T60 = burstT60)
            // Past about eight t60s the envelope is inaudible; the rest of
            // `out` stays correctly zero without walking the noise generator
            // (and the one-pole's state) through frames nothing will hear.
            val burstFrames = minOf(frames, ((burstT60 * 8f + 0.002f) * rate).toInt().coerceAtLeast(32))
            for (i in 0 until burstFrames) {
                val t = i.toFloat() / rate
                out[i] = pole.lp(noise.next() * env.at(t), cutoff)
            }
        }
        return out
    }

    /**
     * The tine's displacement, peak-normalised to 1 — the resonator alone,
     * with no pickup yet. [Modes.ring]'s own KDoc requires exactly this
     * before mixing its output against anything else: a resonator's onset
     * peak depends on pitch and rate in ways that have nothing to do with
     * the macros, so the macros' *meaning* (BARK's closeness, STRIKE's
     * swing) has to be applied to a level the resonator's own physics
     * hasn't already scaled unpredictably (`Thump.snare`'s `bodyPeak` is
     * the same move). This is the hook the "in tune" and "the bar is where
     * the table says" and "higher modes die first" tests measure — the
     * clean resonator, before the pickup's harmonics are added on top of
     * it (see [strike] for where the pickup happens).
     *
     * [width] above 0 rings interleaved stereo instead: each mode gets its
     * own pan via [Modes.spread] before ringing (mode 0, the fundamental,
     * always lands dead centre - `spread`'s own `reach` is exactly 0 there -
     * with every mode above it spread wider as [width] grows), fold-normalised
     * ([Dsp.normalizeByFold]) rather than peak-normalised since this feeds
     * [pickup]/[reedPickup]'s own nonlinearity next, same convention
     * `Thump.snare`'s own WIDTH takes for the same reason. `width <= 0`
     * (every existing preset) takes the untouched mono path below,
     * unchanged from before WIDTH existed.
     */
    internal fun bank(voice: ForkVoice, hz: Float, macros: Map<String, Float>, striker: FloatArray?, rate: Int, width: Float = 0f): FloatArray {
        val t60Fund = Dsp.expMap(macros.getValue("DECAY"), DECAY_MIN_SECONDS, DECAY_MAX_SECONDS)
        val frames = ((t60Fund + TAIL_PAD_SECONDS) * rate).toInt().coerceAtLeast((TAIL_PAD_SECONDS * rate).toInt())
        val strikeM = macros.getValue("STRIKE")
        val excitation = excite(voice, hz, strikeM, striker, frames, rate)
        val modes = modesFor(voice, macros)

        // NODE's own pitch glide (round three): every mode's own frequency
        // glides down from a few cents sharp to its tuned ratio over
        // GLIDE_TIME_SECONDS, then holds - a real struck bar's large-
        // amplitude vibration briefly stiffens it, reading sharp right at
        // the strike and settling as the swing dies down, the same
        // amplitude-dependent effect strings and bars both show. GLIDE_CENTS
        // and GLIDE_TIME_SECONDS are a plausible starting point, not a
        // sourced figure - open for the audition gate to move, the same way
        // DECAY_SLOPE started. Rung as one continuously-swept resonator
        // (glideSamples below), not two static-pitch renders crossfaded
        // together: two near-identical frequencies briefly coexisting would
        // beat, which is exactly what an existing test measuring the clean
        // resonator's own decay-shape caught on the first attempt at this -
        // real signal, not description, per this file's own testing
        // philosophy. Scaled by STRIKE (harder strike, bigger swing, bigger
        // glide), the same lever every other STRIKE-linked mechanism here
        // already uses.
        val glideCents = if (voice == ForkVoice.NODE) GLIDE_CENTS * strikeM else 0f
        val glideSamples = if (glideCents > 0f) (GLIDE_TIME_SECONDS * rate).toInt() else 0
        if (width > 0f) {
            val spread = Modes.spread(modes, width, Dsp.seedFor("FORK-SPREAD", voice, hz))
            val rung = ringModesStereo(hz, spread, excitation, strikeM, frames, rate, glideCents, glideSamples)
            Dsp.normalizeByFold(rung, channels = 2, target = 1f)
            return rung
        }
        val rung = ringModes(hz, modes, excitation, strikeM, frames, rate, glideCents, glideSamples)
        Dsp.normalize(rung, 1f)
        return rung
    }

    /**
     * The bank's own mode sum at [hz]. Each mode rung on its own
     * (Modes.ring's per-mode loop is already independent and additive -
     * `out[i] += y`, one mode at a time - so summing four single-mode
     * calls is exactly what one call with all four would do) and scaled
     * by its own STRIKE_BRIGHT_BOOST before the sum, so a harder strike
     * relatively excites the higher partials more - mode 1 (index 0)
     * never moves, only what rides above it.
     *
     * At [glideSamples] 0 (every voice but NODE, and NODE at STRIKE 0)
     * this is [Modes.ring] itself, one mode at a time, unchanged from
     * before the glide existed. At [glideSamples] > 0 it instead reruns
     * that same two-pole recurrence by hand with the pole angle
     * recomputed every sample from the instantaneous (gliding) frequency
     * rather than held fixed for the call - [glideCents] sharp at sample
     * 0, linearly down to the tuned ratio by [glideSamples], flat after.
     */
    internal fun ringModes(hz: Float, modes: List<Modes.Mode>, excitation: FloatArray, strikeM: Float, frames: Int, rate: Int, glideCents: Float, glideSamples: Int): FloatArray {
        val rung = FloatArray(frames)
        if (glideSamples <= 0) {
            for ((i, mode) in modes.withIndex()) {
                val boost = 1f + STRIKE_BRIGHT_BOOST * i * strikeM
                val ringed = Modes.ring(excitation, hz, listOf(mode), rate)
                for (j in ringed.indices) rung[j] += ringed[j] * boost
            }
            return rung
        }
        val nyquist = rate / 2f
        for ((i, mode) in modes.withIndex()) {
            val modeHz = hz * mode.ratio
            if (modeHz <= 0f || modeHz >= nyquist || mode.t60 <= 0f || mode.gain == 0f) continue
            val boost = 1f + STRIKE_BRIGHT_BOOST * i * strikeM
            val g = mode.gain * boost
            val r = kotlin.math.exp(-6.9078 / (mode.t60.toDouble() * rate)).toFloat()
            var y1 = 0f
            var y2 = 0f
            for (j in 0 until frames) {
                val cents = if (j < glideSamples) glideCents * (1f - j.toFloat() / glideSamples) else 0f
                val instHz = modeHz * 2f.pow(cents / 1200f)
                val theta = 2.0 * Math.PI * instHz / rate
                val a1 = (2.0 * r * kotlin.math.cos(theta)).toFloat()
                val a2 = -(r * r)
                val y = g * excitation[j] + a1 * y1 + a2 * y2
                y2 = y1
                y1 = y
                rung[j] += y
            }
        }
        return rung
    }

    /**
     * [ringModes], interleaved stereo: each [mode]'s own `.pan` (already set
     * by [Modes.spread] before this is called) splits that mode's ring
     * linearly into L/R - `Modes.ringStereo`'s own convention, chosen there
     * because it sums back to exactly the mono fold with no comb-notching,
     * unlike equal-power panning. Not a call to `Modes.ringStereo` itself:
     * that delegates to plain [Modes.ring], which cannot carry NODE's own
     * per-sample gliding pole angle, so both of [ringModes]'s own branches
     * (no-glide, glide) are mirrored here rather than shared.
     */
    internal fun ringModesStereo(hz: Float, modes: List<Modes.Mode>, excitation: FloatArray, strikeM: Float, frames: Int, rate: Int, glideCents: Float, glideSamples: Int): FloatArray {
        val rung = FloatArray(frames * 2)
        if (glideSamples <= 0) {
            for ((i, mode) in modes.withIndex()) {
                val boost = 1f + STRIKE_BRIGHT_BOOST * i * strikeM
                val ringed = Modes.ring(excitation, hz, listOf(mode), rate)
                val p = mode.pan.coerceIn(0f, 1f)
                for (j in ringed.indices) {
                    val y = ringed[j] * boost
                    rung[j * 2] += y * (1f - p)
                    rung[j * 2 + 1] += y * p
                }
            }
            return rung
        }
        val nyquist = rate / 2f
        for ((i, mode) in modes.withIndex()) {
            val modeHz = hz * mode.ratio
            if (modeHz <= 0f || modeHz >= nyquist || mode.t60 <= 0f || mode.gain == 0f) continue
            val boost = 1f + STRIKE_BRIGHT_BOOST * i * strikeM
            val g = mode.gain * boost
            val p = mode.pan.coerceIn(0f, 1f)
            val r = kotlin.math.exp(-6.9078 / (mode.t60.toDouble() * rate)).toFloat()
            var y1 = 0f
            var y2 = 0f
            for (j in 0 until frames) {
                val cents = if (j < glideSamples) glideCents * (1f - j.toFloat() / glideSamples) else 0f
                val instHz = modeHz * 2f.pow(cents / 1200f)
                val theta = 2.0 * Math.PI * instHz / rate
                val a1 = (2.0 * r * kotlin.math.cos(theta)).toFloat()
                val a2 = -(r * r)
                val y = g * excitation[j] + a1 * y1 + a2 * y2
                y2 = y1
                y1 = y
                rung[j * 2] += y * (1f - p)
                rung[j * 2 + 1] += y * p
            }
        }
        return rung
    }

    /**
     * The pickup, and the whole reason FORK exists: `out = v[n] /
     * (1 - x[n])^2`, the time derivative of the standard reluctance model
     * `1 / (1 - x)` with the gap normalised to 1. [x] is already scaled so
     * BARK and STRIKE's swing are exactly [x]'s peak magnitude — never
     * clamped in anger at any legal macro corner, since
     * [BARK_MAX] * [STRIKE_SWING_HIGH] = 0.7 leaves `1 - x >= 0.3`, far
     * above [GAP_FLOOR]. The mean is removed outright (the whole buffer is
     * known, offline), then a [DC_HIGHPASS_HZ] one-pole takes the slow
     * residue the spec's own equation says the formula leaves behind.
     */
    private fun pickup(x: FloatArray, rate: Int): FloatArray {
        val out = FloatArray(x.size)
        var prev = 0f
        for (i in x.indices) {
            val v = x[i] - prev
            prev = x[i]
            val gap = (1f - x[i]).coerceAtLeast(GAP_FLOOR)
            out[i] = v / (gap * gap)
        }
        if (out.isNotEmpty()) {
            var mean = 0.0
            for (v in out) mean += v
            val m = (mean / out.size).toFloat()
            for (i in out.indices) out[i] -= m
        }
        val hp = Dsp.OnePole(rate)
        for (i in out.indices) out[i] -= hp.lp(out[i], DC_HIGHPASS_HZ)
        return out
    }

    /**
     * REED's own mechanical stop: past [threshold] the reed's own swing is
     * a physical event, not just a bigger sample value, so on each
     * rising-edge crossing (after [REED_CONTACT_GRACE_MS], and not again
     * until [REED_CONTACT_REFRACTORY_MS] has passed) this excites the two
     * short resonant rings [REED_CLICK_F0_LOW]/[REED_BODY_F0_LOW] describe.
     * [x] itself is returned unclipped — an earlier version also hard-clipped
     * every over-threshold sample, which measurably left a near-square-wave
     * clip running for as long as the swing stayed over threshold (100ms+
     * on a hard strike), feeding [reedPickup]'s own drive stage a sustained
     * source of harmonics that had nothing to do with the discrete knock;
     * the knock alone carries the "contact" character now.
     */
    internal fun reedContact(x: FloatArray, threshold: Float, rate: Int, seed: Int): FloatArray {
        val out = x.copyOf()
        val noise = Dsp.Noise(seed)
        val click = Dsp.Biquad()
        click.bandpass((REED_CLICK_F0_LOW + REED_CLICK_F0_HIGH) / 2f, REED_CLICK_Q, rate)
        val body = Dsp.Biquad()
        body.bandpass((REED_BODY_F0_LOW + REED_BODY_F0_HIGH) / 2f, REED_BODY_Q, rate)
        val graceSamples = (REED_CONTACT_GRACE_MS / 1000f * rate).toInt()
        val refractorySamples = (REED_CONTACT_REFRACTORY_MS / 1000f * rate).toInt()
        var wasOver = false
        var cooldownUntil = 0
        for (i in out.indices) {
            val raw = out[i]
            val over = kotlin.math.abs(raw) > threshold
            var impulse = 0f
            if (over && !wasOver && i >= graceSamples && i >= cooldownUntil) {
                val clickSpread = (noise.next() + 1f) / 2f
                click.bandpass(REED_CLICK_F0_LOW + clickSpread * (REED_CLICK_F0_HIGH - REED_CLICK_F0_LOW), REED_CLICK_Q, rate)
                val bodySpread = (noise.next() + 1f) / 2f
                body.bandpass(REED_BODY_F0_LOW + bodySpread * (REED_BODY_F0_HIGH - REED_BODY_F0_LOW), REED_BODY_Q, rate)
                impulse = REED_IMPULSE_STRENGTH * (if (raw < 0f) -1f else 1f)
                cooldownUntil = i + refractorySamples
            }
            wasOver = over
            out[i] += click.process(impulse * REED_CLICK_GAIN) + body.process(impulse * REED_BODY_GAIN)
        }
        return out
    }

    /**
     * REED's own pickup: [Dsp.drive] (a symmetric tanh) on the rate of
     * change, ramping in over [REED_DRIVE_RAMP_MS] — see this file's own
     * class KDoc and [REED_DRIVE_GAIN]'s own KDoc for why symmetric and why
     * ramped. Otherwise the same shape [pickup] takes: mean removed
     * outright, then a [DC_HIGHPASS_HZ] one-pole for the slow residue.
     */
    internal fun reedPickup(x: FloatArray, driveTarget: Float, rate: Int): FloatArray {
        val out = FloatArray(x.size)
        var prev = 0f
        val rampSamples = (REED_DRIVE_RAMP_MS / 1000f * rate).toInt()
        for (i in x.indices) {
            val v = x[i] - prev
            prev = x[i]
            val ramp = if (i < rampSamples) i.toFloat() / rampSamples else 1f
            out[i] = Dsp.drive(v * REED_DRIVE_GAIN, driveTarget * ramp)
        }
        if (out.isNotEmpty()) {
            var mean = 0.0
            for (v in out) mean += v
            val m = (mean / out.size).toFloat()
            for (i in out.indices) out[i] -= m
        }
        val hp = Dsp.OnePole(rate)
        for (i in out.indices) out[i] -= hp.lp(out[i], DC_HIGHPASS_HZ)
        return out
    }

    /**
     * The full voice at [rate], exact [hz]: the bank, swung by BARK and
     * STRIKE together, through the pickup. This is what [render] decimates
     * and what [Keys.fork] calls directly at an exact keyboard pitch — the
     * same shape [Tines.strike] gives [Keys.ep] and [Keys.musicBox]. REED
     * takes its own path from here — [reedContact] then [reedPickup] in
     * place of the shared swing-then-[pickup] every other voice takes — since
     * its pickup is a different physical mechanism, not a retuned version of
     * the same one.
     *
     * WIDTH above 0 (`macros["WIDTH"]`) rings [bank] in interleaved stereo;
     * the pickup stages below have no cross-channel physics of their own, so
     * each channel runs the same mono [pickup]/[reedContact]/[reedPickup]
     * independently, on its own deinterleaved array and its own fresh
     * recursive state (mean, highpass, contact timing) - never a shared
     * `prev`/`hp` walking both channels' history together. WIDTH 0 never
     * deinterleaves at all, so it is byte-identical to before WIDTH existed.
     */
    internal fun strike(voice: ForkVoice, hz: Float, macros: Map<String, Float>, striker: FloatArray? = null, rate: Int = RATE * Dsp.OVERSAMPLE): FloatArray {
        val width = macros["WIDTH"] ?: 0f
        val channels = if (width > 0f) 2 else 1
        val rung = bank(voice, hz, macros, striker, rate, width)
        val swing = Dsp.lin(macros.getValue("STRIKE"), STRIKE_SWING_LOW, STRIKE_SWING_HIGH)
        if (voice == ForkVoice.REED) {
            val bark = macros.getValue("BARK")
            val strikeM = macros.getValue("STRIKE")
            val x = FloatArray(rung.size) { rung[it] * swing }
            val threshold = Dsp.lin(bark, REED_CONTACT_THRESHOLD_LOW_BARK, REED_CONTACT_THRESHOLD_HIGH_BARK)
            val driveCeiling = Dsp.lin(bark, REED_BARK_DRIVE_LOW, REED_BARK_DRIVE_HIGH)
            val driveTarget = Dsp.lin(strikeM, REED_DRIVE_AT_STRIKE_0, REED_DRIVE_AT_STRIKE_1) * driveCeiling
            if (channels == 2) {
                val (l, r) = deinterleave(x)
                val seed = Dsp.seedFor("FORK-REED-CONTACT", hz)
                val outL = reedPickup(reedContact(l, threshold, rate, seed), driveTarget, rate)
                val outR = reedPickup(reedContact(r, threshold, rate, seed + 1), driveTarget, rate)
                return interleave(outL, outR)
            }
            val contacted = reedContact(x, threshold, rate, Dsp.seedFor("FORK-REED-CONTACT", hz))
            return reedPickup(contacted, driveTarget, rate)
        }
        val closeness = Dsp.lin(macros.getValue("BARK"), BARK_MIN, BARK_MAX)
        val x = FloatArray(rung.size) { rung[it] * closeness * swing }
        if (channels == 2) {
            val (l, r) = deinterleave(x)
            return interleave(pickup(l, rate), pickup(r, rate))
        }
        return pickup(x, rate)
    }

    /** [buf]'s left/right channels, split from interleaved L/R pairs. */
    private fun deinterleave(buf: FloatArray): Pair<FloatArray, FloatArray> {
        val frames = buf.size / 2
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (f in 0 until frames) {
            left[f] = buf[f * 2]
            right[f] = buf[f * 2 + 1]
        }
        return left to right
    }

    /** [left]/[right] rewoven into one interleaved L/R buffer. */
    private fun interleave(left: FloatArray, right: FloatArray): FloatArray {
        val out = FloatArray(left.size * 2)
        for (f in left.indices) {
            out[f * 2] = left[f]
            out[f * 2 + 1] = right[f]
        }
        return out
    }

    /**
     * A captured snip's head as the exciter: peak-normalised, faded over
     * the last [STRIKER_FADE_MS] so a cut mid-waveform is not a click, and
     * always exactly [STRIKER_SAMPLES] long at [RATE] regardless of the
     * source's own length or rate — a striker shorter than 20 ms
     * zero-pads, one at another sample rate is resampled first, so the
     * recipe this becomes is the same shape every time. A silent source
     * renders a silent striker (`Dsp.normalize` leaves silence alone),
     * never a crash.
     */
    fun striker(source: Snip): FloatArray {
        val mono = if (source.channels == 1) source else Cleanup.toMono(source)
        val atRate = if (mono.sampleRate == RATE) mono else Resampler.resample(mono, RATE)
        val head = FloatArray(STRIKER_SAMPLES) { i -> atRate.samples.getOrElse(i) { 0f } }
        Dsp.normalize(head, 1f)
        val fadeN = (STRIKER_FADE_MS / 1000f * RATE).roundToInt().coerceAtMost(head.size)
        for (i in 0 until fadeN) {
            val g = 0.5f * (1f + kotlin.math.cos(Math.PI.toFloat() * i / fadeN))
            head[head.size - fadeN + i] *= g
        }
        return head
    }

    /**
     * The one-shot pad render: TUNE snapped to a semitone, decimated back
     * from [Dsp.OVERSAMPLE] (not optional — the pickup makes harmonics
     * above Nyquist by construction), levelled and tailed like every
     * melodic engine. WIDTH above 0 renders interleaved stereo; every stage
     * from here down ([Dsp.decimate], [Dsp.levelTo], [Dsp.fadeTail]) already
     * takes a `channels` parameter, [bandLimitStereo] is this function's own
     * wrapper for the one stage that doesn't ([Tide.bandLimit] is not
     * channel-safe - a single filter run across interleaved L/R would mix
     * the two channels' history).
     */
    fun render(voice: ForkVoice, macros: Map<String, Float> = emptyMap(), striker: FloatArray? = null): Snip {
        val m = settled(macros, voice)
        val hz = frequencyFor(m.getValue("TUNE"))
        val rate = RATE * Dsp.OVERSAMPLE
        val channels = if (m.getValue("WIDTH") > 0f) 2 else 1
        val raw = strike(voice, hz, m, striker, rate)
        if (channels == 2) bandLimitStereo(raw, rate) else Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, RATE, channels)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET, channels = channels)
        Dsp.fadeTail(out, rate = RATE, channels = channels)
        return Snip(out, channels = channels, sampleRate = RATE)
    }

    /** [Tide.bandLimit], run independently per channel on deinterleaved L/R. */
    private fun bandLimitStereo(buf: FloatArray, rate: Int) {
        val (left, right) = deinterleave(buf)
        Tide.bandLimit(left, rate)
        Tide.bandLimit(right, rate)
        for (f in left.indices) {
            buf[f * 2] = left[f]
            buf[f * 2 + 1] = right[f]
        }
    }
}
