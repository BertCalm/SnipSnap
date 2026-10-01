package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class GlintVoice { SWEEP, STEP, BRASS, VOWEL }

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
 * A voice is the path the formant takes — see `GlintPath` and
 * docs/superpowers/specs/2026-09-29-glint-paths-design.md. One window, the
 * saw ramp.
 *
 * BODY is a second burst under the same window, two and a half octaves below
 * the main formant and on its own envelope — a second source, not a copy of
 * the first. (An earlier version mixed the bare window back in instead; that
 * added the same harmonic series the burst already carries and was
 * inaudible however loud it was mixed.)
 *
 * VOWEL is the one voice that reads the two bursts differently: they are a
 * mouth's first two formants, in fixed Hz and on one envelope, and BODY is
 * the size of the mouth, scaling both together (see [VOWEL_LINE]).
 *
 * Design: `docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md`
 * (its voice list is superseded by the paths design above).
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
     * How far the path travels at full BLOOM either way: the start ratio is
     * `kBase·(1 + BLOOM_MAX)` above PEAK or `kBase/(1 + BLOOM_MAX)` below —
     * 4x either way — clamped to [K_MIN]..[K_MAX]. At low PEAK the rising
     * side meets [K_MIN] early and travels less than the falling side; at
     * high PEAK the falling side meets [K_MAX].
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
     * BLOOM sets how far the formant travels and which way (see [BLOOM_MAX]).
     * Each voice supplies how it travels: SWEEP and VOWEL run it on this
     * clock, BRASS ties it to loudness and STEP takes it rung by rung
     * ([STEP_SECONDS]).
     */
    const val BLOOM_T60 = 0.45f

    /** VOWEL's own floor: a formant below the note pins to the fundamental rather than vanishing. */
    const val VOWEL_K_MIN = 1f

    /** F2's level against F1 - the 2026-09-29 audition's ratio ("It works"). */
    const val VOWEL_LEVEL2 = 0.5f

    /** The last position on the vowel line (EE). */
    const val VOWEL_LAST = 4f

    /**
     * OO, OH, AH, EH, EE as (F1, F2) Hz - adult formants, ordered dark to
     * bright so PEAK keeps meaning "how bright". The order holds on F2, the
     * vowels' own brightness axis (`GlintVowelTest` gates the share of power
     * above 1.5 kHz as non-decreasing along the line); spectral centroid does
     * not order it past AH (measured 2026-09-29: it rises OO to AH, then
     * falls, because EH and EE have lower F1s than AH).
     */
    private val VOWEL_LINE = arrayOf(
        floatArrayOf(300f, 870f),
        floatArrayOf(570f, 840f),
        floatArrayOf(730f, 1090f),
        floatArrayOf(530f, 1840f),
        floatArrayOf(270f, 2290f),
    )

    fun vowelPosition(peak: Float): Float = peak.coerceIn(0f, 1f) * VOWEL_LAST

    /** F1 and F2 at [pos] on the vowel line, interpolated in log2(Hz). */
    internal fun vowelAt(pos: Float, out: FloatArray) {
        val p = pos.coerceIn(0f, VOWEL_LAST)
        val i = kotlin.math.floor(p).toInt().coerceAtMost(VOWEL_LINE.size - 2)
        val f = p - i
        for (n in 0..1) {
            val a = VOWEL_LINE[i][n]
            val b = VOWEL_LINE[i + 1][n]
            out[n] = a * (b / a).pow(f)
        }
    }

    fun macrosFor(voice: GlintVoice): List<MacroSpec> = when (voice) {
        // No FOLLOW: a vowel's formants are fixed Hz, so key tracking has
        // nothing to do.
        GlintVoice.VOWEL -> listOf(
            MacroSpec("TUNE", 0.5f, 0.5f),
            MacroSpec("PEAK", 0.5f),          // AH
            MacroSpec("BODY", 0.5f, 0.5f),    // vocal-tract size, centred
            MacroSpec("BLOOM", 0.6f, 0.5f),   // a short glide down into the vowel
            MacroSpec("DECAY", 0.5f),
            // Rounds the window's edge, then fades a plain sine in (GlintShape). 0 is today's sound.
            MacroSpec("DEPTH", 0f),
        )
        else -> listOf(
            MacroSpec("TUNE", 0.5f, 0.5f),
            MacroSpec("PEAK", 0.45f),
            MacroSpec("FOLLOW", 0.8f),
            MacroSpec("BODY", 0.4f),
            // Bipolar: 0.5 is still, above falls into PEAK, below rises into it.
            // 0.675 is the old default 0.35 through GlintPatch's legacy remap.
            MacroSpec("BLOOM", 0.675f, 0.5f),
            MacroSpec("DECAY", 0.5f),
            // Rounds the window's edge, then fades a plain sine in (GlintShape). 0 is today's sound.
            MacroSpec("DEPTH", 0f),
        )
    }

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
        GlintVoice.SWEEP -> 110f  // A2
        GlintVoice.STEP -> 220f   // A3 - RATCHET's register
        GlintVoice.BRASS -> 110f  // A2
        GlintVoice.VOWEL -> 110f  // A2
    }

    /** [rootHz] as a MIDI note, for the held pad's zones. */
    fun rootMidi(voice: GlintVoice): Int = when (voice) {
        GlintVoice.STEP -> 57
        else -> 45
    }

    fun frequencyFor(voice: GlintVoice, tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return rootHz(voice) * 2f.pow(semis / 12f)
    }

    /**
     * The one window: a ramp from 1 to exactly 0 at the cycle's end. It must
     * reach exactly zero at phase 1, because that is the instant every change
     * of k is scheduled on (see the class doc).
     */
    fun windowAt(phase: Float): Float = 1f - phase.coerceIn(0f, 1f)

    /**
     * How long STEP holds each rung. The one-shot renders `t60 * 1.35`
     * seconds ([synthesize]), so a long ladder can outlast a short note and
     * never be heard to its end.
     */
    const val STEP_SECONDS = 0.15f

    /**
     * PEAK as a ratio at the voice's own reference note. Exponential,
     * because the ear judges the peak's position by interval, not by Hz.
     * One map for every voice, onto [K_MIN]..[K_MAX], so PEAK's full 0..1
     * travel reaches the same ceiling on each instead of running past it and
     * being chopped.
     */
    fun ratioAtReference(peak: Float): Float = Dsp.expMap(peak, K_MIN, K_MAX)

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
     * The base ratio snaps; BLOOM's path runs continuously into it on SWEEP
     * and BRASS, and in whole harmonics on STEP. That is what makes the knob
     * musical and the sweep smooth.
     */
    fun snapRatio(k: Float): Float = if (k in SNAP_FLOOR..SNAP_CEILING) Math.round(k).toFloat() else k

    /** BLOOM as a sign value: -1 fully below, 0 still, +1 fully above. */
    fun bloomSign(bloom: Float): Float = 2f * bloom.coerceIn(0f, 1f) - 1f

    /** Where the path starts for sign value [s]: symmetric in log-ratio, clamped to K_MIN..K_MAX. */
    fun startRatio(kBase: Float, s: Float): Float {
        val a = kotlin.math.abs(s) * BLOOM_MAX
        val k = if (s >= 0f) kBase * (1f + a) else kBase / (1f + a)
        return k.coerceIn(K_MIN, K_MAX)
    }

    /**
     * STEP's rungs in time order: whole harmonics from [kStart] toward the
     * landing, then the landing itself — [snapRatio]`(kBase)`, which stays
     * unrounded outside SNAP_FLOOR..SNAP_CEILING for the reason
     * [SNAP_FLOOR]'s own doc gives (two velocity layers must not collapse
     * onto one rung). Falling for a start above PEAK, rising for one below,
     * one rung for BLOOM 0.5.
     */
    internal fun stepLadder(kBase: Float, kStart: Float): FloatArray {
        val landing = snapRatio(kBase.coerceIn(K_MIN, K_MAX))
        val rungs = ArrayList<Float>()
        if (kStart > landing) {
            var k = kotlin.math.floor(kStart)
            while (k > landing) { rungs.add(k); k -= 1f }
        } else if (kStart < landing) {
            var k = kotlin.math.ceil(kStart)
            while (k < landing) { rungs.add(k); k += 1f }
        }
        rungs.add(landing)
        return rungs.toFloatArray()
    }

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
        val peakHzAtReference = ratioAtReference(peak) * reference
        val peakHz = Dsp.keyTrack(peakHzAtReference, f0, reference, follow)
        return snapRatio((peakHz / f0).coerceIn(K_MIN, K_MAX))
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

    internal fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice) + macros
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)
        val path = GlintPath.of(voice, m, f0)
        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        // The second burst's own envelope - one envelope, not two composed
        // (see BODY_DECAY_RATIO's doc). VOWEL's second burst is the same
        // mouth as its first, so it rides the amp envelope.
        val env2 = if (voice == GlintVoice.VOWEL) {
            amp
        } else {
            Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60 * BODY_DECAY_RATIO)
        }
        // The one-shot clock: SWEEP and VOWEL run x down on BLOOM_T60, BRASS
        // on its own level (so it lands as the note dies), STEP by rung.
        fun x(t: Float): Float = if (voice == GlintVoice.BRASS) amp.at(t) else Dsp.envAt(t, BLOOM_T60)
        fun rung(t: Float): Int = if (path.ladder != null) (t / STEP_SECONDS).toInt() else -1
        val k = FloatArray(2)
        // The first read is the path's start (x = 1), not x(0f): BRASS's x is
        // its own level, which is 0 at t = 0 - the first cycle would play at PEAK.
        path.ratios(1f, rung(0f), k)
        val step = f0.toDouble() / rate
        var phase = 0.0
        val out = FloatArray(frames)
        // DEPTH. Null at 0, and then the loop below is exactly the saw-window loop this file has always
        // had: its expressions are left as they were, because adding a zero term can turn a -0f sample into
        // +0f. Above 0 it is the rounded window and, from DEPTH 0.5, the sine (GlintShape).
        val shape = GlintShape.of(m.getValue("DEPTH")) { path.sineGain() }
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            if (shape == null) {
                val w = windowAt(phase.toFloat())
                val burst = w * sin(2.0 * PI * k[0] * phase).toFloat()
                // Left uncorrected on purpose. The saw ramp isn't symmetric about
                // phase 0.5, so a saw-windowed burst carries 1/(2πk) of DC per
                // cycle: a few percent of peak in the head window on the path
                // voices (`BODY carries a small, bounded DC` in GlintTest), and up
                // to about 0.12 on VOWEL at the top of TUNE, where F1 pins to
                // k = 1. It decays with the note. Subtracting a constant would
                // stop the window reaching exactly zero at the wrap - the
                // property this file's class doc calls the whole engine.
                val second = path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
                out[i] = amp.at(t) * burst + env2.at(t) * second
            } else {
                val w = shape.window(phase.toFloat())
                val burst = w * sin(2.0 * PI * k[0] * phase).toFloat()
                val second = path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
                val main = amp.at(t)
                var v = shape.burstWeight * (main * burst + env2.at(t) * second)
                // The sine rides the main envelope. It is skipped, not zeroed, below DEPTH 0.5.
                if (shape.sineWeight != 0f) v += shape.sineWeight * main * sin(2.0 * PI * phase).toFloat()
                out[i] = v
            }
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                // The only instant a ratio change is free: the window has
                // just reached zero. Every voice moves k here and only here.
                path.ratios(x(t), rung(t), k)
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
