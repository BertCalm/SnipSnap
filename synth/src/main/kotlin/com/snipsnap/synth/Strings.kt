package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * STRINGS - the Karplus-Strong string, shared.
 *
 * PLUCK's loop, lifted out whole so SILK can build on it
 * (docs/superpowers/specs/2026-09-27-silk-string-engine-design.md, "A shared
 * string toolkit"): the tuning budget ([tune]), the pick exciter
 * ([pluckExciter]), the loop itself ([Loop]), the decay-following cut
 * ([trimToDecay]) and the body drive ([bodyRing]). [pluck] composes the first
 * three into the loop `Pluck.ks` always was, and StringsTest holds it to a
 * frozen copy of that loop sample for sample.
 *
 * The long comments on why each stage is there live with the code they
 * explain, moved from Pluck.kt unchanged.
 */
internal object Strings {

    /**
     * The smallest loop length [tune] accepts, in samples. Below this,
     * splitting the loop into an integer delay plus a fractional allpass
     * stops being meaningful - see [tune]'s `require` for why going below it
     * used to produce a silently unstable filter instead of a clear failure.
     */
    const val MIN_LOOP_SAMPLES = 2.0

    /** The loop's low-pass corner and feedback gain - DAMP's two parameters. */
    class Damping(val loopHz: Float, val fb: Float)

    /**
     * DAMP closes the loop filter and pulls the feedback gain down together
     * - one knob, two parameters, always musical.
     */
    fun damping(damp: Float, bodyLoopHz: Float): Damping = Damping(
        loopHz = bodyLoopHz * Dsp.lin(1f - damp, 0.35f, 1.6f),
        fb = Dsp.lin(1f - damp, 0.94f, 0.998f),
    )

    /**
     * The loop's integer delay [n] and the coefficient [a] of the first-order
     * allpass that carries the fractional remainder; [exact] is the budget
     * they were split from.
     */
    class Tuning(val exact: Double, val n: Int, val a: Float)

    /**
     * The tuning budget: the loop's total delay must equal one period of
     * [freq], and every stage in it pays for its own delay at the
     * fundamental. [loopHz] is the loop low-pass's corner, whose phase lag
     * is one of those stages; [stiffness] (see [Loop]) and [jawari] (see
     * [Loop]) are two more, budgeted the same way, and skipped at 0 - the
     * SITAR voice (PLUCK Phase 3a) is what needs them.
     */
    fun tune(freq: Float, loopHz: Float, rate: Int, stiffness: Float = 0f, jawari: Float = 0f, dispersion: Dispersion? = null): Tuning {
        // The loop length is almost never a whole number of samples, and
        // truncating it (the old `(rate / freq).toInt()`) detunes the
        // string by an amount that depends on the fractional remainder at
        // each frequency - non-monotonically across the keyboard, so a
        // pentatonic run of pads came out sour relative to *each other*,
        // not merely transposed (measured against the pre-fix loop: tens
        // of cents flat and growing worse at higher TUNE, see task-11's
        // report for the full table - flat, not the sharp direction this
        // task was originally filed under. Truncation alone is a small
        // sharp error - `44100/880` truncates from 50.11 to 50, ~4 cents -
        // but the loop filter's own phase lag (in [Loop]), unaccounted for in
        // the pre-fix loop, pulls flat and outweighs it at every note
        // measured). The classic Karplus-Strong fix (Jaffe & Smith) keeps
        // the delay line an integer length and carries the leftover
        // fraction through a first-order allpass instead.
        //
        // That alone isn't the whole loop, though: two other stages in the
        // feedback path have their own delay, and both must come out of
        // the same budget the allpass fills in, or the loop still rings
        // flat by an amount that shifts with DAMP and the note (confirmed
        // by zero-crossing and FFT measurement on the rendered tail, not
        // assumed):
        //  - [Loop]'s one-pole lowpass has a frequency-dependent phase
        //    lag at the fundamental - real here, since DAMP can pull
        //    loopHz down close to the note itself. [filterA]/[poleR] use
        //    the same coefficient as [Dsp.OnePole.lp], so this is the
        //    filter's actual closed-form phase, not an approximation.
        //  - [Loop]'s two-tap average (`0.5*(d, d-1)`) is a fixed-phase
        //    FIR, exactly 0.5 samples of delay at every frequency, kept
        //    from the pre-fix loop rather than dropped in favor of a
        //    single-tap read. Its own magnitude response (|cos(w/2)|) is
        //    near-unity at audible frequencies and isn't what's at stake;
        //    what matters is its 0.5-sample shift in the loop's total
        //    length, which - in a loop this resonant (DAMP low enough to
        //    put `fb` near 0.998, dozens of round trips before decay) -
        //    moves the comb's teeth relative to the lowpass's fixed rolloff
        //    and re-rolls which harmonic of the one-period noise burst
        //    rings loudest. Measured, not assumed: dropping the average
        //    for a plain single-tap read put HARP's 2nd harmonic louder
        //    than its fundamental at TUNE semitone 20, enough to fool a
        //    general-purpose pitch detector into an octave error. Keeping
        //    the average (and budgeting its exact 0.5-sample delay here)
        //    reproduces the pre-fix engine's harmonic balance.
        val filterA = 1.0 - exp(-2.0 * PI * min(loopHz, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * freq / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        val filterDelay = -filterPhase / w

        // The stiffness allpass H(z) = (c + z⁻¹) / (1 + c·z⁻¹): its phase at the
        // fundamental is part of the loop's delay, the same way the low-pass's
        // is, so it enters the budget here and the fundamental stays put.
        val stiffDelay = if (stiffness != 0f) {
            val c = stiffness.toDouble()
            val phase = atan2(-sin(w), c + cos(w)) - atan2(-c * sin(w), 1.0 + c * cos(w))
            -phase / w
        } else 0.0

        // The DC blocker after the jawari (see [Loop]) is a one-pole
        // high-pass, and a high-pass leads at the fundamental: its phase
        // delay is negative and, like the low-pass's and the stiffness
        // allpass's, it belongs to the loop's budget or the note reads
        // sharp. A zero at DC nulls the offset at any corner; the corner
        // only sets how fast a slow offset drains and how much lead the
        // loop owes for it - 2 Hz (not 20) keeps that lead under a degree
        // at the lowest SITAR note (C#3) and the dispersion it leaves on
        // the upper partials is 0.11 % at the default note C#4 and 0.23 %
        // at the root.
        // Narrowed to Float here, matching [Loop]'s own dcA exactly (SITAR's
        // original `ks` computed this once as a Float and used it both in
        // this budget and in the per-sample loop) - the full-Double value
        // rounds `r` differently by the time it reaches atan2, and the drift
        // only crosses a bit boundary a couple hundred samples into the
        // loop, so a mismatch here is easy to miss on a short render.
        val dcA = dcBlockerA(rate).toFloat()
        val dcDelay = if (jawari > 0f) {
            val r = 1.0 - dcA
            val phase = atan2(sin(w), 1.0 - cos(w)) - atan2(r * sin(w), 1.0 - r * cos(w))
            -phase / w
        } else 0.0

        // Dispersion (GUZHENG's STIFF, SILK Phase 1b): [dispersion]'s cascade
        // is [dispersion.count] identical first-order allpasses, and LTI
        // stages in series commute - their phase delays simply add
        // (research §3, Z.7 point 2) - so the budget charges one section's
        // delay at the fundamental, times the section count, the same
        // atan2 form the stiffness allpass above uses for its own single
        // section.
        val dispersionDelay = if (dispersion != null) {
            val c = dispersion.a.toDouble()
            val phase = atan2(-sin(w), c + cos(w)) - atan2(-c * sin(w), 1.0 + c * cos(w))
            dispersion.count * (-phase / w)
        } else 0.0

        val exact = (rate / freq) - filterDelay - stiffDelay - dcDelay - dispersionDelay - 0.5
        // n and frac must come from the SAME exact - splitting them and
        // then independently coercing n up (the old `.coerceAtLeast(2)`)
        // decouples them: frac keeps whatever floor(exact) - n produced,
        // which goes negative the moment exact < n. A negative frac drives
        // `a` above 1 - |a|>1 is an unconditionally unstable feedback
        // allpass, not a degraded one. Guaranteeing frac stays in [0,1) by
        // construction means never separating n from exact after this
        // require: as long as exact clears MIN_LOOP_SAMPLES, floor(exact)
        // >= MIN_LOOP_SAMPLES and frac = exact - floor(exact) is safe by
        // definition, no clamp needed. Unreachable today - the closest any
        // voice/TUNE/DAMP corner ever came to it was 175.93 samples, on the
        // since-removed KALIMBA at TUNE=1/DAMP=1 - this is a require, not
        // a silent coerce, so raising a voice root, widening
        // TUNE_SEMITONES, or adding a high-pitched voice fails loudly
        // here, naming the real cause, instead of surfacing later as a
        // distant isFinite() failure with no trail back to this loop.
        require(exact >= MIN_LOOP_SAMPLES) {
            "String loop length ($exact samples, freq=$freq Hz at rate=$rate) fell " +
                "below the Karplus-Strong minimum of $MIN_LOOP_SAMPLES samples - a " +
                "note this high (or a filter delay this large) needs either a lower " +
                "root, a narrower TUNE span, or this allpass revisited; " +
                "coercing the loop length up here without also correcting the " +
                "fractional remainder used to produce an unconditionally unstable " +
                "feedback allpass."
        }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        return Tuning(exact, n, a)
    }

    /** The DC blocker's one-pole coefficient (see [Loop], [tune]) - depends only on [rate]. */
    private fun dcBlockerA(rate: Int): Double = 1.0 - exp(-2.0 * PI * 2.0 / rate)

    /**
     * A cascade of [count] identical first-order allpasses, sharing one
     * coefficient [a] - Rauhala & Välimäki's dispersion filter (DAFx-06,
     * "Dispersion modeling in waveguide piano synthesis using tunable
     * allpass filters"), in the same transposed form [Loop] already carries
     * for SITAR's own single-section stiffness allpass (PLUCK Phase 3a):
     * H(z) = (a + z⁻¹)/(1 + a·z⁻¹). [a] < 0 is what makes partials go sharp
     * of harmonic - the sign the guzheng prototype got backwards (spec,
     * "The prototypes, as reviewed": "the prototype's 0…0.9 range bends
     * them flat"). GUZHENG's STIFF is the first caller; SANTUR's fixed
     * stiffness (Phase 2) reuses the same class.
     */
    class Dispersion(val count: Int, val a: Float) {
        companion object {
            // Rauhala's 2007 dissertation, Eq. 3.8 - the erratum-corrected
            // form of DAFx-06's own Eq. 7, which prints "ln M" a second time
            // where "ln B" belongs and so carries no B at all. Constants
            // from DAFx-06 Table 2 (research §3, Z.7 point 4 - both the
            // erratum and these constants are quoted from the papers
            // themselves, not recalled).
            private const val M1 = 0.0126
            private const val M2 = 0.0606
            private const val M3 = -0.00825
            private const val M4 = 1.97

            /**
             * The cascade for inharmonicity [b] over [count] sections, or
             * null if [b] is silent (0) or the design's own D comes out at
             * or below 1. DAFx-06's own rule for that case: "D was set to
             * be 1, which corresponds to replacing the allpass filters with
             * the transfer function A(z) = 1" (research §3, Z.7 point 4) -
             * read here as *dropping* the cascade rather than building a
             * degenerate one, since a first-order section at D = 1 is a
             * bare unit delay, not an identity. This is also what keeps a
             * weakly-stiff high note from flipping sign (partials flat
             * instead of sharp): past that note's own D = 1 point, STIFF is
             * inert rather than wrong.
             */
            fun forB(b: Float, count: Int = 4): Dispersion? {
                if (b <= 0f) return null
                val lnM = ln(count.toDouble())
                val lnB = ln(b.toDouble())
                val d = exp((M1 * lnM + M2) * lnB + M3 * lnM + M4)
                if (d <= 1.0) return null
                // Clamped per the design's own risk table ("Dispersion
                // coefficient out of range"): |a| < 1 is what keeps the
                // allpass stable, and -0.95 is the margin kept from that
                // edge. `a` is already strictly negative here (d > 1 makes
                // (1-d) negative and (1+d) positive), so only the lower
                // bound is reachable.
                val a = ((1.0 - d) / (1.0 + d)).toFloat().coerceIn(-0.95f, 0f)
                return Dispersion(count, a)
            }
        }
    }

    /**
     * One period of filtered, zero-mean noise - the raw exciter burst before
     * the pick-position comb. Shared by [pluckExciter] and [burstPeak], which
     * both need it and must agree on it: the bridge limiter's reference peak
     * (see [Loop]) is measured on this burst, not on the combed exciter, the
     * way SITAR's own [Pluck.ks] always measured it.
     */
    private fun rawBurst(n: Int, pickHz: Float, seed: Int, rate: Int): FloatArray {
        val noise = Dsp.Noise(seed)
        val pickLp = Dsp.OnePole(rate)
        val burst = FloatArray(n)
        for (i in 0 until n) burst[i] = pickLp.lp(noise.next(), pickHz)
        // Zero-mean the exciter: the loop filter passes DC untouched, so any
        // net offset in the burst survives as a sub-thump long after the
        // string content is damped away — a dark pluck decayed into a fake
        // kick until this subtraction.
        var mean = 0f
        for (v in burst) mean += v
        mean /= n
        for (i in 0 until n) burst[i] -= mean
        return burst
    }

    /**
     * The bridge limiter's reference peak (see [Loop]): the raw burst's own
     * peak, before the pick-position comb - a full swing on this string, so
     * the same jawari drive buzzes the same on every note and fades as the
     * note does. Only called when jawari is on; regenerating the burst here
     * is cheap next to a mode bank, and it keeps [pluckExciter]'s own return
     * type untouched.
     */
    fun burstPeak(n: Int, pickHz: Float, seed: Int, rate: Int): Float {
        val burst = rawBurst(n, pickHz, seed, rate)
        var p0 = 1e-6f
        for (v in burst) if (kotlin.math.abs(v) > p0) p0 = kotlin.math.abs(v)
        return p0
    }

    /**
     * One period of filtered, zero-mean noise, combed by the pick position -
     * the 1983 exciter with Jaffe & Smith's position comb. [n] is the loop's
     * integer delay from [tune]. Returns at most [maxLen] samples; they enter
     * the loop as input, never as its state.
     */
    fun pluckExciter(n: Int, freq: Float, pickHz: Float, position: Float, seed: Int, rate: Int, maxLen: Int): FloatArray {
        val burst = rawBurst(n, pickHz, seed, rate)

        // Pick position (Jaffe & Smith 1983): the burst minus a copy of
        // itself delayed by `position` of one period. The comb's notches
        // fall on every harmonic k where k*position is a whole number: the
        // centre kills the even harmonics, the bridge thins the low ones.
        // The period here is the string's physical period `rate / freq`,
        // not the integer delay-line length `n` - the loop's allpass,
        // filter lag, and two-tap average make up the rest of that period
        // (see `exact` in [tune]), and a comb cut to `n` alone puts its
        // notches ~3% off the true harmonics at high DAMP (measured on
        // the since-removed KALIMBA voice: the 2nd-harmonic null missed
        // the 20 dB gate). The
        // exciter grows to n + combDelay samples, and the extra samples enter
        // the loop as INPUT to [Loop.next], not as initial state -
        // the loop's own length and tuning budget are untouched. position
        // = 0 reproduces the pre-STRIKE exciter sample for sample.
        // The coerceIn(1, n) clamp is unreachable in production: combDelay / n
        // <= ~0.5 * period/(period - lag), at most ~0.5 across the voice
        // table, and the lower bound needs position * period < 0.5 samples.
        val combDelay = if (position > 0f) (position * rate / freq).roundToInt().coerceIn(1, n) else 0
        val excLen = min(n + combDelay, maxLen)
        val out = FloatArray(excLen)
        for (i in 0 until excLen) {
            val x = if (i < n) burst[i] else 0f
            val xd = if (combDelay > 0 && i - combDelay in 0 until n) burst[i - combDelay] else 0f
            out[i] = x - xd
        }
        return out
    }

    /**
     * The feedback loop, one sample at a time: `y = x + fb * lp(ap(avg))`,
     * where `avg` is the two-tap average of the output `n` and `n + 1`
     * samples back, `ap` the tuning allpass and `lp` the loop's one-pole.
     * For the first `n + 1` samples there is no history to feed back and the
     * output is the input - exactly as `Pluck.ks`'s loop always started at
     * `n + 1`. The history is a ring of `n + 2` outputs: both taps, no more.
     *
     * [stiffness] is a first-order allpass coefficient in (-1, 0]; 0 is no
     * allpass and skipped. A negative value delays low partials more than
     * high ones so the upper partials sit sharp of harmonic, the stiff-string
     * law `n*sqrt(1 + B*n^2)` with B rising as the coefficient falls - SITAR's
     * dispersion (PLUCK Phase 3a), applied right after the tuning allpass,
     * before the loop's own low-pass.
     *
     * [jawari] is the bridge limiter's drive in [0, 1): after the low-pass,
     * positive swings are pulled down by `jawari · min(y, jawariP0) · y /
     * jawariP0` (clamped so it never crosses zero), the way a string
     * wrapping on a flat bridge is stopped on one side - SITAR's buzz. A
     * one-pole DC blocker follows because a one-sided term leaves an offset;
     * both are skipped at jawari 0. [jawariP0] is the reference peak (see
     * [burstPeak]) that a full swing on this string looks like, so the same
     * drive buzzes the same on every note.
     */
    class Loop(
        private var n: Int,
        private var a: Float,
        fb: Float,
        private val loopHz: Float,
        private val rate: Int,
        private val stiffness: Float = 0f,
        private val jawari: Float = 0f,
        private val jawariP0: Float = 1e-6f,
        private val dispersion: Dispersion? = null,
    ) {
        private val baseFb = fb
        private var fb = fb
        // The ring is sized once, here, from the constructor's own n - the
        // loop's lowest note (its longest delay), for a voice that will
        // later call [retune]. Every such voice starts at that low note and
        // only ever moves toward a shorter, higher-pitched target (SLIDE
        // glides in from below; PRESS and the SHAMISEN glide bend up), so
        // the ring never needs to grow past this - see [retune].
        private val maxN = n
        private val size = maxN + 2
        private val history = FloatArray(size)
        private var i = 0
        private var apX1 = 0f
        private var apY1 = 0f
        private var stX1 = 0f
        private var stY1 = 0f
        private val loopLp = Dsp.OnePole(rate)
        private val dcA = dcBlockerA(rate).toFloat()
        private var dc = 0f

        // Dispersion's own state: [dispersion.count] identical sections
        // chained, each carrying its own one-sample history - empty arrays,
        // and no work in [next], when [dispersion] is null.
        private val dispX1 = FloatArray(dispersion?.count ?: 0)
        private val dispY1 = FloatArray(dispersion?.count ?: 0)

        fun next(x: Float): Float {
            val y = if (i <= n) {
                x
            } else {
                val d = 0.5f * (history[(i - n) % size] + history[(i - n - 1) % size])
                // First-order allpass: y[i] = a*(x[i] - y[i-1]) + x[i-1]. Order
                // matters here - it's the *tuned* sample that must feed both
                // the loop filter and the output, or the correction never
                // reaches the loop it was meant to fix.
                val tuned = a * (d - apY1) + apX1
                apX1 = d
                apY1 = tuned
                val stiff = if (stiffness != 0f) {
                    val s = stiffness * (tuned - stY1) + stX1
                    stX1 = tuned
                    stY1 = s
                    s
                } else tuned
                var yy = loopLp.lp(stiff, loopHz)
                // GUZHENG's STIFF (SILK Phase 1b), after the loop low-pass -
                // the spec's own architecture diagram order ("loop LP ->
                // [dispersion] -> [collision]"), not SITAR's stiffness
                // position above: the tuning budget above does not care
                // which order the loop's LTI stages run in (their delays
                // just add), so this placement is free to differ from
                // SITAR's without retuning anything.
                if (dispersion != null) {
                    val da = dispersion.a
                    for (k in 0 until dispersion.count) {
                        val s = da * (yy - dispY1[k]) + dispX1[k]
                        dispX1[k] = yy
                        dispY1[k] = s
                        yy = s
                    }
                }
                if (jawari > 0f) {
                    if (yy > 0f) yy -= jawari * min(yy, jawariP0) * yy / jawariP0
                    dc += dcA * (yy - dc)
                    yy -= dc
                }
                x + fb * yy
            }
            history[i % size] = y
            i++
            return y
        }

        /**
         * A pitch envelope beside the fixed-tuning path: re-solves the
         * tuning budget for [freq] (the same [tune] every fixed-pitch
         * voice uses) and carries the tuning allpass' and loop filter's own
         * state through unchanged - no click, no re-priming. OUD's SLIDE,
         * GUZHENG's PRESS and SHAMISEN's built-in glide are the callers,
         * and every one of them starts at a lower, longer-loop note and
         * moves toward a shorter one, which is exactly what [maxN] (the
         * ring this [Loop] was constructed with) already has room for.
         *
         * A [freq] whose own loop would need more than [maxN] samples
         * fails loudly rather than reading history this ring never kept:
         * construct the [Loop] at the glide's lowest note, not its target.
         */
        fun retune(freq: Float) {
            val t = tune(freq, loopHz, rate, stiffness, jawari, dispersion)
            require(t.n <= maxN) {
                "retune($freq) needs a loop of ${t.n} samples, past the $maxN this Loop was built for - " +
                    "construct it at the glide's lowest note, not the target it's moving toward"
            }
            n = t.n
            a = t.a
        }

        /**
         * Scales the loop's own feedback by [scale] against its built
         * value, restored with `gain(1f)` - OUD's SLIDE models the
         * finger's extra damping on a fretless slide as a slightly lower
         * loop gain for the slide's own duration (spec, "OUD", Erkut §2),
         * without touching [retune]'s pitch envelope alongside it.
         */
        fun gain(scale: Float) {
            fb = baseFb * scale
        }
    }

    /**
     * The 1983 plucked string: [pluckExciter] into a [Loop] tuned by [tune].
     * [stiffness] and [jawari] are SITAR's dispersion and buzz; both default
     * to 0, which reproduces the plain string exactly.
     */
    fun pluck(freq: Float, seconds: Float, damping: Damping, pickHz: Float, seed: Int, rate: Int, position: Float = 0f, stiffness: Float = 0f, jawari: Float = 0f, dispersion: Dispersion? = null): FloatArray {
        val t = tune(freq, damping.loopHz, rate, stiffness, jawari, dispersion)
        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(t.n + 2))
        val exc = pluckExciter(t.n, freq, pickHz, position, seed, rate, out.size)
        val jawariP0 = if (jawari > 0f) burstPeak(t.n, pickHz, seed, rate) else 1e-6f
        val loop = Loop(t.n, t.a, damping.fb, damping.loopHz, rate, stiffness, jawari, jawariP0, dispersion)
        for (i in out.indices) out[i] = loop.next(if (i < exc.size) exc[i] else 0f)
        return out
    }

    /**
     * The widest a single [course] loop can drift from [freq], in cents, at
     * [spread] 1 - "shape, not measurement" (spec, OUD's COURSE and
     * SANTUR's COURSE both note no source measures a course's detune),
     * chosen to sit well past where two coupled strings audibly beat
     * (Weinreich; Woodhouse's simulation puts that around 2-5 cents,
     * research §4) while staying inside a semitone, so [course] can never
     * be mistaken for a different note.
     */
    private const val COURSE_MAX_CENTS = 50f

    /**
     * How much each successive [course] loop's feedback is nudged down
     * from the one before it (loop `k` gets `fb * (1 - COURSE_FB_STEP *
     * k)`) - the spec's own "pair decays unevenly" character (course
     * loops at the same frequency still sound like coupled strings, not
     * one voice with extra gain).
     *
     * SILK Phase 1b's OUD work (its own custom per-loop course, not this
     * function - `Strings.course` had no production caller before SANTUR)
     * found a multi-cent tuning miss at this step's original value, 0.01,
     * and fixed it locally by dropping to 0.002. Directly probing
     * `course` itself at that original 0.01 (SANTUR Phase 2, both with a
     * shared identical exciter across loops and with `course`'s own
     * real per-loop exciter variation, across the frequency range SILK's
     * voices actually use) did **not** reproduce a multi-cent miss - see
     * `course's own feedback step, isolated from excitation, stays in
     * tune` and `course at spread 0 is in tune across several seeds, real
     * excitation included` in `StringsTest`, both passing at 0.01 too, not
     * only at this smaller value. So whatever OUD's own render actually
     * hit lives somewhere this direct probe doesn't reach - most likely
     * the U6 oversample/decimate pipeline's own interaction with two
     * differently-decaying loops, which no test here exercises in
     * isolation. This constant is kept small anyway, as a real but
     * unconfirmed-necessary precaution: it costs nothing (COURSE's own
     * "unevenly" character survives at either value) and the claim that
     * actually matters - SANTUR's own tuning, through its real, fully
     * oversampled render path - is what Task 5's own voice-level test
     * checks, not this number in isolation.
     */
    internal const val COURSE_FB_STEP = 0.002f

    /**
     * OUD's course, SANTUR's four strings: [count] loops around [freq],
     * summed, each seeded from [seed] so one pad's shimmer is stable across
     * renders and two pads differ (spec, "OUD"). [spread] 0 keeps every
     * loop at [freq] exactly; [spread] 1 reaches [COURSE_MAX_CENTS]. Each
     * loop's feedback is nudged slightly apart by its own index so the
     * course decays unevenly - the "prompt then aftersound" of coupled
     * strings a single shared `fb` cannot produce.
     *
     * [count] = 1 returns [pluck] itself, untouched by [spread]: the
     * off-by-default point for [course] is "one loop", not "no detune" -
     * a caller reaching [course] with [count] 1 must get exactly what
     * calling [pluck] directly would have given it.
     */
    fun course(freq: Float, seconds: Float, damping: Damping, pickHz: Float, seed: Int, rate: Int, count: Int, spread: Float, position: Float = 0f, stiffness: Float = 0f, jawari: Float = 0f, dispersion: Dispersion? = null): FloatArray {
        require(count >= 1) { "course needs at least 1 loop, got $count" }
        if (count == 1) return pluck(freq, seconds, damping, pickHz, seed, rate, position, stiffness, jawari, dispersion)

        val detunes = courseDetuneCents(seed, count, spread)
        val loops = detunes.mapIndexed { k, cents ->
            val detuned = freq * 2f.pow(cents / 1200f)
            val fbK = (damping.fb * (1f - COURSE_FB_STEP * k)).coerceIn(0f, 0.999f)
            pluck(detuned, seconds, Damping(damping.loopHz, fbK), pickHz, Dsp.seedFor(seed, "COURSE", k), rate, position, stiffness, jawari, dispersion)
        }
        val out = FloatArray(loops.maxOf { it.size })
        for (loop in loops) for (i in loop.indices) out[i] += loop[i]
        return out
    }

    /**
     * The cents each of [count] [course] loops drifts from the note,
     * seeded from [seed] - split out from [course] so its own bounds and
     * determinism are testable without rendering anything. [spread] 0
     * gives every loop exactly 0 - the product zeroes out regardless of
     * the draw - and [spread] 1 spans ±[COURSE_MAX_CENTS]/2.
     */
    internal fun courseDetuneCents(seed: Int, count: Int, spread: Float): List<Float> {
        val random = kotlin.random.Random(seed)
        return (0 until count).map { (random.nextFloat() - 0.5f) * spread.coerceIn(0f, 1f) * COURSE_MAX_CENTS }
    }

    /**
     * Cuts [buf] where its 5 ms RMS envelope has fallen 60 dB below its
     * peak, never under [floorSeconds] - that's the normal case, and
     * the string stopped ringing before the budget ran out.
     *
     * If the scan never finds that point, the string was still ringing when
     * the buffer ran out, and which fade applies depends on why: at the ring
     * ceiling (`buf.size >= ceilingSeconds * rate`) the string was cut
     * off mid-ring for real, so the last 400 ms gets the long squared fade.
     * Short of the ceiling, this was a DAMP-driven budget cut (a high DAMP
     * gave the caller only a few hundred ms to work with), and the render
     * is still audible for nearly all of that budget - re-enveloping the
     * whole thing with a 400 ms fade would choke the very thud DAMP asked
     * for, so only the last 30 ms is faded, just enough to declick the cut.
     * Either way a caller's own 4 ms `Dsp.fadeTail` then has nothing audible
     * left to touch.
     */
    fun trimToDecay(buf: FloatArray, rate: Int, floorSeconds: Float, ceilingSeconds: Float): FloatArray {
        val block = (rate * 0.005f).toInt().coerceAtLeast(1)
        val blocks = (buf.size + block - 1) / block
        if (blocks == 0) return buf
        val rms = DoubleArray(blocks)
        for (b in 0 until blocks) {
            val start = b * block
            val end = min(buf.size, start + block)
            var acc = 0.0
            for (i in start until end) acc += buf[i].toDouble() * buf[i]
            rms[b] = sqrt(acc / (end - start))
        }
        val peak = rms.max()
        if (peak <= 0.0) return buf
        val floorBlocks = ((floorSeconds * rate) / block).toInt()
        var last = blocks - 1
        // The scan never steps below floorBlocks, so that block is always
        // kept; when the loop stops because last == floorBlocks (rather than
        // finding a loud block), the block just above it was already walked
        // and found quiet on the previous iteration, so keeping both here is
        // not a guess.
        while (last > floorBlocks && rms[last] < peak * 0.001) last--
        val end = min(buf.size, (last + 2) * block)
        if (end < buf.size) return buf.copyOf(end)
        if (buf.size >= (ceilingSeconds * rate).toInt()) {
            fadeCeiling(buf, ms = 400f, rate = rate)
        } else {
            fadeCeiling(buf, ms = 30f, rate = rate)
        }
        return buf
    }

    /**
     * A squared fade over the last [ms]. At the ring ceiling the string is
     * still moving, and a linear fade's last few milliseconds would sit
     * only ~30 dB down; squaring it puts them past -60 dB.
     */
    private fun fadeCeiling(buf: FloatArray, ms: Float, rate: Int) {
        val n = min(buf.size, (ms / 1000f * rate).toInt())
        if (n <= 0) return
        val start = buf.size - n
        for (i in 0 until n) {
            val g = 1f - i.toFloat() / n
            buf[start + i] *= g * g
        }
    }

    /**
     * The string drives its body. The drive is the string's FIRST
     * DIFFERENCE, because the bridge force follows the string's slope at
     * the bridge, the velocity-like quantity - not, as a 34 dB tilt might
     * suggest, to hide the burst from the body's low modes. The
     * differentiator's own gain, `2*sin(theta/2)`, and [Modes.ring]'s own
     * onset peak, `1/sin(theta)` (`theta = 2*pi*hz/rate`), multiply to 1.0
     * at every body frequency, so it is the table's GAIN column that
     * governs each mode's burst response, and `ring`'s documented
     * low-frequency onset hazard is cancelled outright, not merely
     * reduced. What differentiating the drive actually buys: it removes
     * the burst's DC step (the spike's knock came from driving the body
     * with the string's raw displacement, DC and all), and it re-tilts
     * the balance among a voice's own sourced modes toward the high ones
     * by the differentiator's own frequency slope - NYLON's 645 Hz mode
     * gains on its 104 Hz mode by about 16 dB, BANJO's 5000 Hz mode on
     * its 220 Hz mode by about 27 dB, KOTO's 100 Hz mode on its 85 Hz
     * mode by about 1.4 dB. The body's level against the string is set by
     * the RMS match below, not by the drive.
     *
     * The body's own longest mode can ring well past the string that
     * struck it: a muted string's DAMP-driven budget is a few hundred ms,
     * a body mode's t60 can run past a second, and [Modes.ring] itself
     * only ever returns as many samples as it was given to excite - it
     * does not extend the ring on its own. So the drive here, and the
     * ring it produces, run `pad` samples past the string's own length
     * (`pad` sized off the table's own longest t60), and the returned
     * buffer follows that ring out toward [ceilingSeconds] rather than
     * being cut where the string itself ends; [trimToDecay] (in the
     * caller) follows the combined tail from there. The RMS match
     * is taken over the string's own length only, on both sides, so
     * [amount] means "times the string" the same way whether or not the
     * table's tail outlives it; the body is then added on top of the
     * string where the string still runs, and on its own past the
     * string's end. Amount 0 returns [string] itself: a body at 0 is the
     * string, byte for byte. [differentiate] exists only so PluckTest's
     * knock test can reproduce the spike's displacement drive for
     * comparison - production never sets it false.
     */
    fun bodyRing(string: FloatArray, table: List<Modes.Mode>, amount: Float, rate: Int, ceilingSeconds: Float, differentiate: Boolean = true): FloatArray {
        if (amount <= 0f) return string
        if (table.isEmpty()) return string
        val pad = (table.maxOf { it.t60 } * rate).toInt()
        val driveLen = string.size + pad
        // `differentiate = false` reproduces the spike's displacement drive;
        // only the knock test passes it, production never does. Either way
        // the drive is silent past the string's own length - there is
        // nothing left to differentiate or copy once the string has ended,
        // and the padding is what lets the body ring on regardless.
        val drive = FloatArray(driveLen)
        if (differentiate) {
            var prev = 0f
            for (i in string.indices) {
                drive[i] = string[i] - prev
                prev = string[i]
            }
        } else {
            string.copyInto(drive)
        }
        val wet = Modes.ring(drive, 1f, table, rate)
        val g = rms(string, string.size) / rms(wet, string.size).coerceAtLeast(1e-9f)
        val outLen = min(driveLen, (ceilingSeconds * rate).toInt())
        val out = FloatArray(outLen)
        for (i in out.indices) out[i] = (if (i < string.size) string[i] else 0f) + amount * g * wet[i]
        return out
    }

    /** RMS of the first [n] samples of [buf] (all of it by default). */
    private fun rms(buf: FloatArray, n: Int = buf.size): Float {
        val len = min(n, buf.size)
        var acc = 0.0
        for (i in 0 until len) acc += buf[i].toDouble() * buf[i]
        return sqrt(acc / len.coerceAtLeast(1)).toFloat()
    }
}
