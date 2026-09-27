package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class GlintVoice { REED, BOTTLE, KAZOO, CICADA, RATCHET, PLATE }

/**
 * GLINT — phase distortion, where the formant is generated rather than
 * carved. Each cycle of the fundamental runs a sine burst at [k] times f0,
 * multiplied by a window that reaches exactly zero by the cycle's end.
 *
 * That zero is the whole engine. It means the burst always lands on silence
 * at the wrap, so `k` need not be an integer and can slide continuously
 * without a click — the formant sweeps while the pitch does not move at all.
 * Only the window's *slope* jumps across the wrap, and that slope
 * discontinuity is the buzz the engine is made of. Do not smooth it.
 *
 * BODY is a second burst under the same window, two and a half octaves below
 * the main formant and on its own envelope — a second source, not a copy of
 * the first. (An earlier version mixed the bare window back in instead; that
 * added the same harmonic series the burst already carries and was
 * inaudible however loud it was mixed.)
 *
 * Design: `docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md`.
 */
object Glint {

    const val TUNE_SEMITONES = 24

    /** Below two burst cycles inside the window there is no peak, only a dull fragment. */
    const val K_MIN = 2f

    /**
     * The musical ceiling. Aliasing is not what bounds this: the burst is
     * generated at `Dsp.RATE * Dsp.OVERSAMPLE`, so folding starts only above
     * `k·f0 ≈ 88 kHz` — k ≈ 1443 at A1, ≈ 361 at A3, ≈ 76 at C6.
     */
    const val K_MAX = 40f

    /** At or below this, `k` snaps to integers so the peak lands on a harmonic. */
    const val SNAP_CEILING = 12f

    /**
     * Below this the ratio runs free. Between [K_MIN] and 3 there is only
     * one integer, so snapping there quantises nothing — it flattens the
     * whole range onto 2, and that flattening is what made velocity's
     * floor-scaled layer land on the same ratio as the full one: measured
     * dead across PEAK 0.00-0.06 before this floor, and nowhere after it.
     * The audition's own verdict points the same way: k=3, 5 and 8 were
     * marked KEEP and k=2 only "ok".
     *
     * PEAK exactly 0 is still identical for both velocity layers even with
     * this floor — kBase is 2.0 either way — but that row is covered by
     * `Velocity.kt`'s own `asked <= 1e-6f` guard, which routes a macro
     * parked at zero to `soften` instead of scaling it. This floor and that
     * guard are two separate fixes for the same PEAK 0.00-0.06 range; move
     * that guard's threshold and this claim needs re-checking.
     */
    const val SNAP_FLOOR = 3f

    /** KAZOO's trapezoid holds at full for this fraction of the cycle, then ramps out. */
    const val KAZOO_FLAT = 0.7f

    /**
     * How many times CICADA's carrier re-clocks inside one cycle of `f0`.
     * Integer by necessity - but not for the reason periodicity first
     * suggests. Measured (D2 diagnostic, 2026-09-27): at N = 4.37 the
     * engine-only one-period correlation is 0.9999999 - still exactly
     * periodic - because `phase` wraps independently of the sub-clock, so
     * `frac(N * phase)` repeats whenever `phase` does. A fractional N does
     * NOT break periodicity at `f0`.
     *
     * What actually forces N to be an integer is the wrap: a fractional N
     * leaves a truncated final sub-cycle, so the window does not reach zero
     * at the instant the carrier restarts, and that restart clicks. Same
     * diagnostic: worst adjacent jump goes 0.132858 -> 0.705022, a 5.3x
     * jump at the truncated restart. This is the same zero-crossing promise
     * `CICADA does not click at its inner restarts` already guards for the
     * integer case (there, a window read at the wrong phase); a fractional
     * N would break it from a different direction.
     *
     * Four gives a lattice dense enough to read as its own texture at the
     * voice's A3 root (four edges per cycle, 880 edges a second) without the
     * sub-rate climbing so far that the burst's own harmonics fold. A value
     * to revisit at the audition, not a derived quantity.
     */
    const val CICADA_SUBCYCLES = 4

    /**
     * The second formant's t60 as a fraction of the amp t60. Single-
     * enveloped, unlike the body term this replaced: that one was scaled by
     * its own envelope and then again by the amp envelope, so two
     * exponentials composed and its real decay was ~0.31x t60 rather than
     * the 0.45 the constant claimed.
     */
    const val BODY_DECAY_RATIO = 0.8f

    /**
     * How far below the main formant the second one sits — k/5.6, about two
     * and a half octaves — but only once `kBase` clears 11.2, where
     * [bodyRatio]'s `(kBase / BODY_RATIO_DIVISOR).coerceAtLeast(K_MIN)`
     * stops returning K_MIN. Below that it pins the body to K_MIN (2)
     * instead, and the real interval is smaller than 5.6. That binds for
     * most of the knob: 11.2 is PEAK ≈0.575 at the reference note on the
     * raw, unsnapped ratio, though `kBase` itself is snapped to integers in
     * [SNAP_FLOOR]..[SNAP_CEILING], so in practice the first reachable
     * `kBase` that clears the clamp is 12, not a smooth crossing at 11.2.
     * At the shipped default (PEAK 0.45, kBase 8) the body sits at exactly
     * 2×f0 — a 4x interval, not 5.6x. The old BODY mixed the mean-removed
     * window back in, which is the same harmonic series the burst already
     * carries, so it was inaudible however loud it was mixed. A second
     * burst at its own ratio is a second *source*: "two formants, two
     * speeds, is a vowel and the room it sits in" (design 2026-09-26).
     */
    const val BODY_RATIO_DIVISOR = 5.6f

    /** The second formant's level relative to the main burst at BODY 1. */
    const val BODY_MIX = 0.7f

    /**
     * BLOOM's ceiling: the peak opens to 1 + this many times its settled
     * ratio — 4x at full BLOOM.
     *
     * That 4x is clamped against [K_MAX] (40) in `synthesize`, and the two
     * interact: full BLOOM starts clipping against the ceiling once
     * `kBase > 10` (PEAK ≈ 0.54, since `kBase = 2 * 20^PEAK`), and is
     * entirely inert at PEAK 1, where `kBase` is already 40 and has nowhere
     * left to open. Not a bug — undocumented behaviour someone auditioning
     * BLOOM at high PEAK would otherwise read as the knob being broken.
     */
    const val BLOOM_MAX = 3f

    /**
     * The formant sweep's t60, fixed. It used to run from 0.30 s at low
     * BLOOM down to 0.06 s at full — depth and rate on one knob, so a big
     * sweep was always a fast one and a wide, unhurried one could not be
     * dialled at all. Measured at the shipped coupling the centroid fell to
     * 0.92x of its opening value and then sat flat for the rest of the note;
     * at 0.45 s it travels to 0.35x over 300 ms, which is the single change
     * the 2026-09-26 audition marked KEEP on both voices tested.
     *
     * BLOOM now means how far the formant travels. Each voice supplies how
     * it travels — D2's PLATE ties it to loudness and RATCHET steps it.
     */
    const val BLOOM_T60 = 0.45f

    fun macrosFor(voice: GlintVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, 0.5f),
        MacroSpec("PEAK", 0.45f),
        MacroSpec("FOLLOW", 0.8f),
        MacroSpec("BODY", 0.4f),
        MacroSpec("BLOOM", 0.35f),
        MacroSpec("DECAY", 0.5f),
    )

    fun defaults(voice: GlintVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /**
     * No preset roster yet — Phase 3 authors one and this gains the
     * preset-seeded form every other engine uses (see `Resin.scramble`).
     */
    fun scramble(
        voice: GlintVoice,
        random: Random,
        temperature: Float = 0.35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** The bottom of each voice's own two-octave TUNE range. */
    fun rootHz(voice: GlintVoice): Float = when (voice) {
        GlintVoice.REED -> 110f     // A2
        GlintVoice.BOTTLE -> 220f   // A3
        GlintVoice.KAZOO -> 220f    // A3
        GlintVoice.CICADA -> 220f   // A3 — shares BOTTLE's register with its window
        GlintVoice.RATCHET -> 220f  // A3 — shares KAZOO's register with its window
        GlintVoice.PLATE -> 110f    // A2 — a struck plate sits low, like REED
    }

    fun frequencyFor(voice: GlintVoice, tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return rootHz(voice) * 2f.pow(semis / 12f)
    }

    /**
     * The window at [phase] in [0, 1). Must reach exactly zero at phase 1 —
     * every voice's definition below is written so that it does.
     *
     * The three Task 1 voices each reuse an existing shape rather than
     * inventing one, per the spec's voice table — their distinguishing
     * mechanism arrives in a later task, so for now the shape alone has to
     * carry the pairing:
     *   - CICADA takes the triangle (BOTTLE's) because it is the softest
     *     base window here, so a later nested modulation supplies the edge
     *     instead of doubling one the window already has.
     *   - RATCHET takes the trapezoid (KAZOO's) because the toy, clicky
     *     character that window already gives is the point of the voice.
     *   - PLATE takes the saw (REED's) because a struck plate is brightest
     *     right at the strike and decays from there, the same shape as a
     *     ramp that opens at phase 0 and falls to zero.
     */
    fun windowAt(voice: GlintVoice, phase: Float): Float {
        val p = phase.coerceIn(0f, 1f)
        return when (voice) {
            GlintVoice.REED, GlintVoice.PLATE -> 1f - p
            GlintVoice.BOTTLE, GlintVoice.CICADA -> if (p < 0.5f) p * 2f else (1f - p) * 2f
            GlintVoice.KAZOO, GlintVoice.RATCHET -> if (p < KAZOO_FLAT) 1f else (1f - p) / (1f - KAZOO_FLAT)
        }
    }

    /**
     * The ceiling PEAK maps to and the render loop's instantaneous k clamps
     * against, per voice. K_MAX for every voice except CICADA.
     *
     * CICADA's real formant sits at roughly N * k, not k (see
     * [CICADA_SUBCYCLES]), so capping its own k at K_MAX / CICADA_SUBCYCLES
     * makes its *effective* ceiling - kCeiling * CICADA_SUBCYCLES - equal to
     * every other voice's own K_MAX. That is an equalisation, not a
     * restriction: it is what makes 10 the correct number here rather than
     * a value tuned until some test passed.
     *
     * This is not standing in for an anti-aliasing clamp. [K_MAX]'s own doc
     * is explicit that aliasing is not what bounds it - folding starts only
     * around k*f0 ≈ 88 kHz, comfortably above any voice's own ceiling. What
     * this equalises is the *musical* ceiling every voice's K_MAX already
     * represents, which CICADA's re-clocking would otherwise let PEAK sail
     * straight through.
     *
     * Applied in three places that must agree with each other -
     * [ratioAtReference] (what PEAK maps to), [ratioFor]'s own clamp (what a
     * note actually resolves to after FOLLOW), and [synthesize]'s per-sample
     * clamp (what BLOOM can push the instantaneous k to). Only clamping the
     * last of those left PEAK mapped across the full K_MIN..K_MAX band and
     * then chopped after the fact - roughly the top half of PEAK's travel
     * rendered one flat, saturated value, with BLOOM inert on top of it: "a
     * dead macro on five of six voices is not defensible" applies here even
     * though only one voice's ceiling moved. One definition, three call
     * sites, so they cannot drift apart.
     */
    internal fun kCeilingFor(voice: GlintVoice): Float =
        if (voice == GlintVoice.CICADA) K_MAX / CICADA_SUBCYCLES else K_MAX

    /**
     * How long RATCHET holds each rung before jumping to the next. The spec
     * asks for "a hard jump every ~150 ms"; at the shortest DECAY (0.12 s
     * t60) that is under one step, so short notes render a single rung and
     * the ladder only reads on longer ones — which is correct for a tuning
     * dial being turned.
     */
    const val RATCHET_STEP_SECONDS = 0.15f

    /**
     * PEAK as a ratio at the voice's own reference note. Exponential,
     * because the ear judges the peak's position by interval, not by Hz.
     * Maps onto [K_MIN]..[kCeilingFor] rather than the raw [K_MIN]..[K_MAX]
     * band, so PEAK's full 0..1 travel reaches whatever ceiling the voice's
     * own mechanism allows instead of running past it and being chopped.
     */
    fun ratioAtReference(peak: Float, voice: GlintVoice): Float = Dsp.expMap(peak, K_MIN, kCeilingFor(voice))

    /**
     * Integers put the peak exactly on a harmonic — k=3 the octave-and-a-
     * fifth, k=5 two octaves and a major third. Snapping matters only where
     * the ear reads the peak as related to the note, so it applies inside
     * [SNAP_FLOOR]..[SNAP_CEILING] and is identity outside that band.
     *
     * The floor exists because between [K_MIN] and 3 there is only one
     * integer to land on: snapping that range doesn't quantise anything, it
     * flattens the whole range onto 2. That flattening was the velocity
     * bug — a floor-scaled macro and its full-strength twin both fell onto
     * the same flat ratio and rendered byte-identical. Quantization for the
     * ear, full resolution for the diff — never conflate them again.
     *
     * The base ratio snaps; BLOOM modulates continuously on top of it. That
     * is what makes the knob musical and the sweep smooth.
     */
    fun snapRatio(k: Float): Float = if (k in SNAP_FLOOR..SNAP_CEILING) Math.round(k).toFloat() else k

    /** The centre of the voice's own TUNE range — where FOLLOW has no work to do. */
    fun referenceHz(voice: GlintVoice): Float = frequencyFor(voice, 0.5f)

    /**
     * The formant ratio actually used, after FOLLOW, the snap and the clamp.
     *
     * FOLLOW rides `Dsp.keyTrack`: at 1 the peak's Hz scales exactly with the
     * note, so the ratio is constant and the timbre is identical across the
     * range; at 0 the peak's Hz is fixed, so the ratio falls as the note
     * rises and the sound turns vocal — a body resonance rather than a
     * filter. In between, `keyTrack` blends in the log domain, so half
     * tracking means half the octaves of movement.
     *
     * The [K_MIN] floor is unconditional. Across a pad's two-octave range it
     * never binds (a 700 Hz peak is k=12.7 at A1 and 3.2 at A3), but across
     * a four-octave keygroup at FOLLOW 0 it would — see the spec's note, and
     * expect FOLLOW's bottom half to collapse toward tracking up there.
     */
    fun ratioFor(voice: GlintVoice, tune: Float, peak: Float, follow: Float): Float {
        val reference = referenceHz(voice)
        val f0 = frequencyFor(voice, tune)
        val peakHzAtReference = ratioAtReference(peak, voice) * reference
        val peakHz = Dsp.keyTrack(peakHzAtReference, f0, reference, follow)
        return snapRatio((peakHz / f0).coerceIn(K_MIN, kCeilingFor(voice)))
    }

    /**
     * BODY's second-formant ratio for a given [kBase] — see
     * [BODY_RATIO_DIVISOR]. Clamped to [K_MIN] so it never falls below two
     * burst cycles per window and stops being a formant at all; that clamp
     * binds below `kBase ≈ 11.2`, which is most of the knob (see
     * [BODY_RATIO_DIVISOR]'s doc). One definition, shared by [synthesize]
     * and the test that probes it, so a change here can't silently drift
     * out of step with a copy elsewhere.
     */
    internal fun bodyRatio(kBase: Float): Float = (kBase / BODY_RATIO_DIVISOR).coerceAtLeast(K_MIN)

    /**
     * The integer harmonics RATCHET steps through, bottom rung first.
     *
     * Every rung above the bottom is a whole number — "steps between fixed
     * harmonics" is the mechanism, and a fractional rung would be a glide
     * that happens to be quantised in time, a different and much duller
     * thing. The bottom rung is the one exception: it is
     * [snapRatio]`(kBase)`, not `Math.round(kBase)`. Below [SNAP_FLOOR],
     * `snapRatio` is identity, so the bottom rung stays unrounded there —
     * the same trade [SNAP_FLOOR]'s own doc already made for [ratioFor],
     * now honoured here too instead of walked around.
     *
     * Rounding the bottom unconditionally was this function's first
     * version, and it reintroduced [SNAP_FLOOR]'s bug on a second path: two
     * kBase values inside `[K_MIN, SNAP_FLOOR)` that differ from each other
     * — 2.0515814 and 2.1234918, PEAK 0.02's velocity-soft and
     * velocity-hard values — both rounded to bottom rung 2 and produced
     * byte-identical ladders `[2, 3, 4]`. Measured before this fix, at PEAK
     * 0.02, 0.04 and 0.06: RATCHET was the one voice where a soft and a
     * hard render came out identical, failing `velocity always changes the
     * render, at every PEAK`. With the bottom rung left unrounded, the same
     * two kBase values give `[2.0515814, 3, 4]` and `[2.1234918, 3, 4]` —
     * different bottom rungs, different renders.
     *
     * Inside [SNAP_FLOOR]..[SNAP_CEILING] this changes nothing: [snapRatio]
     * already rounds there, the same way `Math.round` did, so a `kBase`
     * that arrives already snapped to an integer — every call from
     * [synthesize] does, via [ratioFor] — produces the identical ladder
     * either way. Measured at kBase=8 (inside the snap band): BLOOM
     * 0.25/0.5/1 give the same rung counts and the same rungs,
     * 8..14 / 8..20 / 8..32, before and after this change.
     *
     * Above [SNAP_CEILING] the same free-bottom-rung change applies as
     * below [SNAP_FLOOR], for the identical reason: [snapRatio] is
     * identity above the ceiling too, so a `kBase` up there stays
     * unrounded rather than snapping to the nearest integer. Measured at
     * PEAK 0.7 (`kBase` = 16.28362, `TUNE` 0.5, `FOLLOW` 0.8): the old,
     * unconditional `Math.round` would have given a bottom rung of 16;
     * this version keeps it at 16.28362. This is exactly what closes the
     * remaining gap between RATCHET's and KAZOO's PEAK-sweep rows in
     * `GlintTest`'s `PEAK sweep is monotonic` — the three points that
     * differed before this fix (steps at PEAK 0.625, 0.75 and 0.875) are
     * all above [SNAP_CEILING], not inside the snap band.
     *
     * The ladder climbs from the bottom by whole harmonics up to `kBase *
     * (1 + bloomAmount)`, BLOOM's extent.
     *
     * Bounded by [kCeilingFor] for [GlintVoice.RATCHET], not a bare [K_MAX]:
     * the same ceiling [ratioFor] already clamped [kBase] to, so this can
     * never ask for a rung a note could not otherwise reach. RATCHET is
     * never CICADA, so this is [K_MAX] today, but the call stays rather than
     * inlining the constant — see [kCeilingFor]'s own doc on why it has to
     * be one definition shared by every site that bounds a ratio, not a
     * value copied into a fourth place that could drift from the other
     * three.
     *
     * Always at least one rung — the bottom is added unconditionally, not
     * only when the climb finds nothing — so a note shorter than one step
     * still has a ratio to render.
     */
    internal fun ratchetLadder(kBase: Float, bloomAmount: Float): FloatArray {
        val kCeiling = kCeilingFor(GlintVoice.RATCHET)
        val bottom = snapRatio(kBase.coerceIn(K_MIN, kCeiling))
        val top = (kBase * (1f + bloomAmount)).coerceIn(K_MIN, kCeiling)
        val rungs = ArrayList<Float>()
        rungs.add(bottom)
        var k = floor(bottom) + 1f
        while (k <= top && rungs.size < kCeiling.toInt()) {
            rungs.add(k)
            k += 1f
        }
        return rungs.toFloatArray()
    }

    internal fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice) + macros
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val kBase = ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))
        val bloomAmount = Dsp.lin(m.getValue("BLOOM"), 0f, BLOOM_MAX)
        val bloomT60 = BLOOM_T60
        // Equalises CICADA's effective ceiling against every other voice's
        // K_MAX - see kCeilingFor's doc for why K_MAX / CICADA_SUBCYCLES is
        // the right number, not a tuned one. Applied here so BLOOM's boost
        // cannot push the instantaneous k past that ceiling either; measured
        // without this, at TUNE 0.3 PEAK 0.4 BLOOM 1 the onset carrier
        // reached ~36.9 kHz (kBase 7 boosted 4x by BLOOM, times the
        // sub-clock's own 4x) against a 22.05 kHz output Nyquist, and the
        // attack BLOOM is supposed to open collapsed instead.
        //
        // bodyRatio's k2 is not itself clamped by this - it is derived from
        // kBase directly, below - but it is not unaffected: kCeilingFor also
        // bounds kBase now (see ratioFor), and CICADA's kBase can never
        // exceed 10, so bodyRatio(10) = 1.79 floors to K_MIN (2) at every
        // PEAK. CICADA's BODY ratio is therefore pinned at K_MIN across its
        // whole range, unlike every other voice, where the same floor only
        // binds below kBase ≈ 11.2 (see BODY_RATIO_DIVISOR's doc). BODY's
        // level still responds to the BODY macro; only its ratio is frozen
        // for this one voice - a known consequence of the equalisation, not
        // something resolved here.
        val kCeiling = kCeilingFor(voice)

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val bodyMix = m.getValue("BODY") * BODY_MIX
        val bodyT60 = t60 * BODY_DECAY_RATIO
        // Its own envelope, not the amp envelope times another one. The term
        // this replaced was scaled twice — by its own decay and then by the
        // amp's — so two exponentials composed and its real decay was ~0.31x
        // t60 rather than the constant it claimed. One envelope now, with
        // the same 2 ms attack so the onset does not click.
        val bodyEnv = Dsp.Env(attackSeconds = 0.002f, decay2T60 = bodyT60)
        // The second formant is pinned to the *base* ratio, not the
        // bloom-modulated one: it is a separate resonance, not a shadow of
        // the first.
        val k2 = bodyRatio(kBase)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)
        // RATCHET resolves its whole ladder once, up front - BLOOM only sets
        // how far it reaches, not a per-sample computation - and then only
        // ever reads one rung of it per sample. `rung` advances exclusively
        // at the phase wrap below; nothing in the per-sample body ever
        // touches it.
        val ladder = if (voice == GlintVoice.RATCHET) ratchetLadder(kBase, bloomAmount) else null
        var rung = 0

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            // CICADA re-clocks the carrier inside every cycle: N nested copies
            // of the window and burst, instead of one. The window is applied
            // to the SUB-phase, which is what makes each inner restart land on
            // silence — window it on `phase` and it clicks N times a cycle
            // instead of never. N is an integer, so the whole pattern still
            // repeats at f0.
            val carrier = if (voice == GlintVoice.CICADA) {
                val scaled = phase * CICADA_SUBCYCLES
                scaled - floor(scaled)
            } else {
                phase
            }
            val w = windowAt(voice, carrier)
            val k = when (voice) {
                // RATCHET's k only ever changes at the phase wrap below, not
                // here: its trapezoid window is 1 at phase 0 (KAZOO_FLAT > 0)
                // and only reaches zero at the cycle's end, so a k picked
                // mid-cycle would multiply a wide-open window by a sine at an
                // arbitrary phase for the OLD k one sample and the NEW k the
                // next - a real discontinuity, once per step. Reading `rung`
                // here is safe precisely because it is frozen for the whole
                // cycle; only the wrap is allowed to move it.
                GlintVoice.RATCHET -> ladder!![rung]
                // kBase is snapped; BLOOM modulates continuously on top of it,
                // so the knob is musical and the sweep is smooth. k moves on
                // the envelope's timescale, far slower than one cycle, so the
                // inner sine stays effectively periodic while restarting at
                // each wrap.
                else -> (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(K_MIN, kCeiling)
            }
            val burst = w * sin(2.0 * PI * k * carrier).toFloat()
            // Not because a windowed sine has no DC — it does, for any
            // window that isn't symmetric about phase 0.5: REED's ramp and
            // KAZOO's trapezoid both integrate to a nonzero mean (BOTTLE's
            // triangle is the one window here that nulls it). This stays
            // uncorrected because subtracting a constant would stop it
            // reaching exactly zero at the wrap — the property this file's
            // class doc calls the whole engine — and the residual is small
            // (a few percent of peak in the head window at BODY 1) and
            // decays with bodyEnv; see `BODY carries a small, bounded DC`
            // in GlintTest.
            // The body rides the same carrier, for the same reason: a second
            // formant still running on `phase` would be non-zero at every
            // sub-boundary and would click there even though the burst is
            // clean.
            val body = bodyMix * w * sin(2.0 * PI * k2 * carrier).toFloat()
            out[i] = amp.at(t) * burst + bodyEnv.at(t) * body
            phase += step
            if (phase >= 1f) {
                phase -= 1f
                // The only instant a ratio change is free: the window has
                // just reached zero, so any k starts the new cycle from
                // silence instead of breaking a wide-open one. `t` is this
                // sample's own time, not the wrapped-to sample's - RATCHET's
                // ~150 ms step is long enough next to one cycle that which
                // side of the wrap names the boundary is inaudible.
                if (ladder != null) {
                    rung = (t / RATCHET_STEP_SECONDS).toInt().coerceAtMost(ladder.size - 1)
                }
            }
        }
        return out
    }

    fun render(voice: GlintVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6: render at 4x RATE so the burst's harmonics fold above 22.05 kHz
        // instead of into the band, then decimate.
        val renderRate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate)
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
