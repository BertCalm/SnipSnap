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
 * real but largely masked difference. Round three tries a mechanism with
 * more perceptual weight instead — see [GLIDE_CENTS] and [bank]'s own
 * KDoc.
 *
 * Same contract as every engine: macros are 0..1 mapped onto bounded
 * musical ranges (SCRAMBLE can't land on garbage), the DSP renders at
 * `RATE * Dsp.OVERSAMPLE` and decimates (U6) — not optional here, since
 * the pickup's nonlinearity makes harmonics the linear resonator never
 * had — and TUNE snaps to semitones like every melodic engine.
 */
enum class ForkVoice { TINE, BAR, NODE }

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
        ForkVoice.TINE, ForkVoice.NODE -> TINE_RATIOS
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
     * NODE's own pitch glide (round three), at STRIKE 1: how many cents
     * sharp the strike reads before it settles. Shipped at 15 first — a
     * plausible, tasteful-sounding starting point — but two rounds of
     * blind A/B against TINE (round two's pickup position, then this)
     * both came back "too similar" by ear, so this is temporarily pushed
     * to a deliberately exaggerated value as a diagnostic: confirm the
     * mechanism is actually audible at all before concluding anything
     * about FORK's own ceiling. 80 cents is comfortably past any
     * ambiguity — most of a semitone — and not a candidate for the
     * shipped default. See [bank]'s own KDoc for the mechanism and why
     * it is NODE-only for now.
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
     * [ForkVoice.NODE] applies [NODE_POSITION_GAIN] on top of the plain
     * `1/ratio` every voice starts from — the same tine, same ratio table,
     * only where the pickup reads it — indexed by mode position (0..3), not
     * by the mode's own (possibly STIFF-stretched) ratio: [STRIKE_BRIGHT_BOOST]
     * already takes that same "by index, not by current ratio" shape, since
     * STIFF's morph is a sound-design liberty layered on top of the physics,
     * not a claim that the modes stay real cantilever eigenmodes at every
     * STIFF setting.
     */
    internal fun modesFor(voice: ForkVoice, macros: Map<String, Float>): List<Modes.Mode> {
        val table = tableFor(voice)
        val stiff = macros.getValue("STIFF")
        val t60Fund = Dsp.expMap(macros.getValue("DECAY"), DECAY_MIN_SECONDS, DECAY_MAX_SECONDS)
        return table.mapIndexed { i, tableK ->
            val ratio = stiffRatio((i + 1).toFloat(), tableK, stiff)
            val positionGain = if (voice == ForkVoice.NODE) NODE_POSITION_GAIN[i] else 1f
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
     */
    internal fun bank(voice: ForkVoice, hz: Float, macros: Map<String, Float>, striker: FloatArray?, rate: Int): FloatArray {
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
     * The full voice at [rate], exact [hz]: the bank, swung by BARK and
     * STRIKE together, through the pickup. This is what [render] decimates
     * and what [Keys.fork] calls directly at an exact keyboard pitch — the
     * same shape [Tines.strike] gives [Keys.ep] and [Keys.musicBox].
     */
    internal fun strike(voice: ForkVoice, hz: Float, macros: Map<String, Float>, striker: FloatArray? = null, rate: Int = RATE * Dsp.OVERSAMPLE): FloatArray {
        val rung = bank(voice, hz, macros, striker, rate)
        val closeness = Dsp.lin(macros.getValue("BARK"), BARK_MIN, BARK_MAX)
        val swing = Dsp.lin(macros.getValue("STRIKE"), STRIKE_SWING_LOW, STRIKE_SWING_HIGH)
        val x = FloatArray(rung.size) { rung[it] * closeness * swing }
        return pickup(x, rate)
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
     * melodic engine.
     */
    fun render(voice: ForkVoice, macros: Map<String, Float> = emptyMap(), striker: FloatArray? = null): Snip {
        val m = settled(macros, voice)
        val hz = frequencyFor(m.getValue("TUNE"))
        val rate = RATE * Dsp.OVERSAMPLE
        val raw = strike(voice, hz, m, striker, rate)
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, RATE)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
