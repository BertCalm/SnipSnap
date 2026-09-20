package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** Small DSP toolbox for offline voice rendering. Nothing here is real-time. */
internal object Dsp {

    const val RATE = 44_100

    /**
     * U6 (docs/SYNTH_UPGRADE.md): an engine's per-voice synthesis runs at
     * `RATE * OVERSAMPLE` internally (see [decimate]) so naive oscillators'
     * own aliasing folds down above audible range instead of into it.
     * All 8 engines - [Thump], [Tines], [Velvet], [Fathom], [Tonewheel],
     * [Vox], [Pluck], [Skin] - are wired to this contract. Skin was born
     * wired to it (SYNTH_ROADMAP.md's S6, shipped after the other seven's
     * own U6 migration) rather than migrated to it after the fact.
     */
    const val OVERSAMPLE = 4

    /** Linear macro map: macro 0..1 onto [lo, hi]. */
    fun lin(macro: Float, lo: Float, hi: Float): Float = lo + (hi - lo) * macro.coerceIn(0f, 1f)

    /**
     * Exponential macro map — the right curve for anything the ear judges
     * (frequency, time): equal macro steps sound like equal steps.
     */
    fun expMap(macro: Float, lo: Float, hi: Float): Float =
        (lo * exp(ln((hi / lo).toDouble()) * macro.coerceIn(0f, 1f))).toFloat()

    /**
     * Raises a too-slow beat toward audibility - it does not complete one.
     * Two oscillators a ratio r apart beat at baseHz*(r-1); below this
     * floor a "FAT" macro is a static comb tint rather than movement.
     * [cycles] stays a small fraction on purpose: forcing a *full* beat
     * cycle inside a short note (the original ask) takes far more detune
     * than any macro should grant - tens of cents at typical bass notes -
     * and that reads as an out-of-tune interval, not width. This function
     * only raises the floor; it has no idea what the caller's macro is
     * allowed to ask for, so callers must still clamp the result to their
     * own ceiling (see [Velvet.detuneFor]).
     *
     * [cycles]'s default of 0.25f is a PLACEHOLDER awaiting the audition
     * gate, not a settled decision - MEASURE-NEVER-GUESS forbids shipping
     * a taste call as if it were derived, and "how much beat movement is
     * enough to hear" is exactly that: a listening judgment, not something
     * a spectrum can answer. The controller's own ruling on it: "0.25 may
     * be too little movement to hear. That is an audition-gate question."
     *
     * There used to be a hard number here about how large [cycles] could
     * go before this floor fully swallowed [Velvet.detuneFor]'s FAT macro
     * (a `coerceAtMost(askedHi)` collision at `cycles >= ~0.4651`, BASS
     * being the tightest voice). [Velvet.detuneFor]'s own clamp no longer
     * has that failure mode - it caps the floor to
     * `askedHi / Velvet.MIN_AUTHORITY_RATIO`, a ceiling FAT's own top
     * always clears by construction, for any [cycles] this function is
     * ever asked for. So raising [cycles] no longer has a boundary to
     * collide with; how much beat movement is enough to hear is purely an
     * audition-gate question now, with no DSP mechanism left to bound it
     * for you.
     */
    fun minBeatDetune(baseHz: Float, seconds: Float, cycles: Float = 0.25f): Float {
        if (baseHz <= 0f || seconds <= 0f) return 1f
        return 1f + (cycles / seconds) / baseHz
    }

    /**
     * [cutoffHz] scaled toward [baseHz]'s own pitch relative to
     * [referenceHz] — the note an engine's cutoff mapping is voiced to be
     * neutral at. Without this, a filter mapped to an absolute Hz gets
     * proportionally duller as a voice climbs (the same cutoff covers
     * fewer harmonics of a higher fundamental) and proportionally brighter
     * as it descends — brightness drifts across a run instead of staying
     * an interval above the note.
     *
     * [amount] 0 leaves [cutoffHz] untouched; [amount] 1 locks the cutoff
     * to [baseHz]/[referenceHz]'s exact ratio above (or below) it, so every
     * note in a run keeps precisely the interval [cutoffHz] had at
     * [referenceHz]. In between, the scaling ratio is raised to [amount] -
     * a log-domain blend, not a linear one, because "half tracking" should
     * mean half the octaves of movement, not half the Hz.
     */
    fun keyTrack(cutoffHz: Float, baseHz: Float, referenceHz: Float, amount: Float): Float {
        if (referenceHz <= 0f || baseHz <= 0f) return cutoffHz
        val ratio = baseHz / referenceHz
        return cutoffHz * ratio.pow(amount.coerceIn(0f, 1f))
    }

    /**
     * SCRAMBLE near a seed: perturb each of [seed]'s macros by a gaussian
     * scaled by [temperature], clamped back to 0..1. `temperature = 0`
     * returns [seed] untouched; `temperature = 1` discards it and rolls
     * every macro flat-uniform, exactly the pre-U2 SCRAMBLE — so that
     * behaviour stays reachable at the extreme (docs/SYNTH_UPGRADE.md, U2).
     */
    fun scrambleNear(seed: Map<String, Float>, temperature: Float, random: Random): Map<String, Float> {
        val t = temperature.coerceIn(0f, 1f)
        return when {
            t <= 0f -> seed
            t >= 1f -> seed.mapValues { random.nextFloat() }
            else -> seed.mapValues { (_, v) -> (v + gaussian(random) * t).coerceIn(0f, 1f) }
        }
    }

    /**
     * A stable seed for a render. Derived from the patch's own identity, so
     * two different voices decorrelate while one voice stays reproducible —
     * golden files and `--undo` byte-identity need the second half.
     */
    fun seedFor(vararg parts: Any): Int {
        var h = 17
        for (p in parts) h = h * 31 + p.toString().hashCode()
        return h
    }

    /**
     * [count] start phases in [0, 1), spread from [seed]. Every oscillator in
     * every engine used to start at exactly 0.0, so detuned pairs began locked
     * and each attack was the same coherent transient.
     */
    fun phases(count: Int, seed: Int): DoubleArray {
        val random = Random(seed)
        return DoubleArray(count) { random.nextDouble() }
    }

    /** Standard-normal sample via Box-Muller; `kotlin.random.Random` has no `nextGaussian()`. */
    private fun gaussian(random: Random): Float {
        val u1 = 1f - random.nextFloat() // (0, 1], never 0, so ln() stays finite
        val u2 = random.nextFloat()
        return sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
    }

    /** Deterministic noise; same seed, same grains, stable tests. */
    class Noise(seed: Int) {
        private var state = if (seed == 0) 1 else seed
        fun next(): Float {
            state = (state * 1103515245 + 12345) and 0x7fffffff
            return (state.toFloat() / 0x3fffffff) - 1f
        }
    }

    /** Naive square — aliasing and all. Lo-fi is on-brand, and these are percussive. */
    fun square(phase: Double): Float = if (sin(2.0 * PI * phase) >= 0.0) 1f else -1f

    /**
     * Chamberlin state-variable filter. One instance per voice per pass;
     * process() advances one sample and exposes all three outputs.
     */
    class Svf(private val rate: Int = RATE) {
        var low = 0f; var band = 0f; var high = 0f
        fun process(input: Float, freqHz: Float, damp: Float) {
            val f = (2.0 * sin(PI * min(freqHz, rate * 0.22f) / rate)).toFloat()
            low += f * band
            high = input - low - damp * band
            band += f * high
        }
        fun reset() { low = 0f; band = 0f; high = 0f }
    }

    /**
     * Topology-preserving (trapezoidal-integrated) state-variable filter,
     * after Andy Simper's Cytomic papers — the "linear trap" design the
     * modern open synths use.
     *
     * The Chamberlin [Svf] above goes unstable as its frequency coefficient
     * nears 1 (≈7 kHz here), which is why VELVET's cutoff used to be capped
     * at 5.2 kHz. This one is stable to Nyquist and clean under fast
     * modulation, so filters can finally open all the way. [k] is damping:
     * 2 = no resonance, small = ringing (keep ≥ ~0.1).
     */
    class TptSvf(private val rate: Int = RATE) {
        var low = 0f; var band = 0f; var high = 0f
        private var ic1 = 0f
        private var ic2 = 0f
        /**
         * [saturate] self-limits the feedback state instead of letting it
         * ring cleanly into the final normalise (docs/SYNTH_UPGRADE.md, U5)
         * - plain tanh, near-identity below ~0.5, only compressing as a
         * state genuinely runs toward and past unity (self-oscillation
         * territory). Defaults to off: every existing caller keeps today's
         * exact linear filter. It's opt-in per call, not a blanket switch,
         * because it isn't free even at typical settings.
         *
         * Measured, not assumed (synth-depth phase-0, Task 7): flipping it
         * on at FATHOM's call site (fixed `damp = 1.2f`, driven by a `tanh`
         * pre-stage) broke four green tests. DEEP's own KICK classification
         * held, but `DRIVE adds harmonics` did not - DEEP's DRIVE-driven
         * centroid brighten fell to 68.96Hz -> 89.30Hz (1.295x), just under
         * the 1.3x contract, because the saturator eats the harmonics DRIVE
         * exists to add. GRIND's factory default drifted TOM -> PERC, its
         * `DIRTY GROWL` preset drifted KICK -> TOM, and `HOLLOW GROWL` fell
         * under the peak floor at 0.473. Same story at PadFilter's and
         * Wobble's call sites, measured directly on `TptSvf`: at PadFilter's
         * settings (cutoff 800Hz, k=0.3) peak dropped 0.570 -> 0.330 with
         * centroid barely moving (704Hz -> 681Hz) - exactly backwards for a
         * preview whose own contract is "the output's peak is held at the
         * input's, so a resonant peak never reads as loudness"; at Wobble's
         * (RESONANCE_K = 0.6, full sweep) peak dropped 0.789 -> 0.610 while
         * centroid moved under 2% (2070Hz -> 2043Hz), a difference Wobble's
         * own post-sweep makeup gain mostly erases anyway. Enable it only
         * for a filter whose caller has actually checked its own tests stay
         * green with it on - FATHOM, PadFilter, and Wobble all failed that
         * check and stay off; VELVET is still the one exception.
         */
        fun process(input: Float, freqHz: Float, k: Float, saturate: Boolean = false) {
            val g = kotlin.math.tan(PI * (freqHz.coerceIn(10f, rate * 0.49f)) / rate).toFloat()
            val kk = k.coerceAtLeast(0.1f)
            val a1 = 1f / (1f + g * (g + kk))
            val a2 = g * a1
            val a3 = g * a2
            val v3 = input - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            val nextIc1 = 2f * v1 - ic1
            val nextIc2 = 2f * v2 - ic2
            ic1 = if (saturate) tanh(nextIc1.toDouble()).toFloat() else nextIc1
            ic2 = if (saturate) tanh(nextIc2.toDouble()).toFloat() else nextIc2
            low = v2
            band = v1
            high = input - kk * v1 - v2
        }
        fun reset() { ic1 = 0f; ic2 = 0f; low = 0f; band = 0f; high = 0f }
    }

    /**
     * Biquad section with coefficients from the RBJ Audio EQ Cookbook —
     * the community-standard shelf and bell formulas (public-domain math,
     * indexed by every DSP resource list worth reading). Direct form I.
     */
    class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        private fun set(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0
            this.a1 = a1 / a0; this.a2 = a2 / a0
        }

        fun lowShelf(f0: Float, gainDb: Float, rate: Int = RATE) {
            val a = Math.pow(10.0, gainDb / 40.0).toFloat()
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / 2f * kotlin.math.sqrt(2f) // shelf slope S = 1
            val sqA = kotlin.math.sqrt(a)
            set(
                a * ((a + 1) - (a - 1) * cw + 2 * sqA * alpha),
                2 * a * ((a - 1) - (a + 1) * cw),
                a * ((a + 1) - (a - 1) * cw - 2 * sqA * alpha),
                (a + 1) + (a - 1) * cw + 2 * sqA * alpha,
                -2 * ((a - 1) + (a + 1) * cw),
                (a + 1) + (a - 1) * cw - 2 * sqA * alpha,
            )
        }

        fun highShelf(f0: Float, gainDb: Float, rate: Int = RATE) {
            val a = Math.pow(10.0, gainDb / 40.0).toFloat()
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / 2f * kotlin.math.sqrt(2f)
            val sqA = kotlin.math.sqrt(a)
            set(
                a * ((a + 1) + (a - 1) * cw + 2 * sqA * alpha),
                -2 * a * ((a - 1) + (a + 1) * cw),
                a * ((a + 1) + (a - 1) * cw - 2 * sqA * alpha),
                (a + 1) - (a - 1) * cw + 2 * sqA * alpha,
                2 * ((a - 1) - (a + 1) * cw),
                (a + 1) - (a - 1) * cw - 2 * sqA * alpha,
            )
        }

        /** Constant-peak-gain bandpass — the RBJ cookbook's formant workhorse. */
        fun bandpass(f0: Float, q: Float, rate: Int = RATE) {
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / (2f * q)
            set(alpha, 0f, -alpha, 1 + alpha, -2 * cw, 1 - alpha)
        }

        /**
         * An allpass: every magnitude passes through untouched, the phase
         * rotated through 180 degrees around [f0]. The RBJ cookbook's
         * form — the numerator is the denominator reversed, which is what
         * makes the magnitude flat at every frequency.
         */
        fun allpass(f0: Float, q: Float, rate: Int = RATE) {
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / (2f * q)
            set(1 - alpha, -2 * cw, 1 + alpha, 1 + alpha, -2 * cw, 1 - alpha)
        }

        fun peaking(f0: Float, gainDb: Float, q: Float, rate: Int = RATE) {
            val a = Math.pow(10.0, gainDb / 40.0).toFloat()
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / (2f * q)
            set(
                1 + alpha * a,
                -2 * cw,
                1 - alpha * a,
                1 + alpha / a,
                -2 * cw,
                1 - alpha / a,
            )
        }
    }

    /** One-pole low-pass; subtract from input for a high-pass. */
    class OnePole(private val rate: Int = RATE) {
        private var state = 0f
        fun lp(input: Float, cutoffHz: Float): Float {
            val a = (1.0 - exp(-2.0 * PI * min(cutoffHz, rate * 0.45f) / rate)).toFloat()
            state += a * (input - state)
            return state
        }
    }

    /** Soft saturation with unity make-up so DRIVE changes tone, not loudness. */
    fun drive(x: Float, amount: Float): Float {
        if (amount <= 0f) return x
        val g = 1f + 6f * amount
        return (tanh((x * g).toDouble()) / tanh(g.toDouble())).toFloat()
    }

    /** Exponential decay envelope reaching -60 dB at [t60] seconds. */
    fun envAt(t: Float, t60: Float): Float = exp((-6.9078 * t / t60).toDouble()).toFloat()

    /**
     * Shared envelope primitive (docs/SYNTH_UPGRADE.md, U5) — a linear
     * attack ramp times a decay curve, both measured from t=0 the way every
     * engine's own hand-rolled `attack * envAt(t, t60)` already worked, so
     * adopting this changes nothing on its own. The decay can be a single
     * [envAt] stage (the default: [decay1Seconds] = 0, decay starts at full
     * level under the ramp) or two-stage — a fast linear drop to
     * [decay1Level] over [decay1Seconds] (the "thwack"), then an [envAt]
     * tail from there at [decay2T60] (the "body") — plus an optional
     * [holdSeconds] at full level between the ramp and the decay, for a
     * sustaining/gated voice.
     */
    class Env(
        private val attackSeconds: Float,
        private val decay2T60: Float,
        private val holdSeconds: Float = 0f,
        private val decay1Seconds: Float = 0f,
        private val decay1Level: Float = 1f,
    ) {
        fun at(t: Float): Float {
            val attack = if (attackSeconds <= 0f) 1f else (t / attackSeconds).coerceAtMost(1f)
            return attack * decayAt(t)
        }

        private fun decayAt(t: Float): Float {
            if (t <= holdSeconds) return 1f
            val afterHold = t - holdSeconds
            if (decay1Seconds <= 0f) return Dsp.envAt(afterHold, decay2T60)
            if (afterHold < decay1Seconds) return Dsp.lin(afterHold / decay1Seconds, 1f, decay1Level)
            return decay1Level * Dsp.envAt(afterHold - decay1Seconds, decay2T60)
        }
    }

    /**
     * Shared [levelTo] target for every melodic engine - VELVET, FATHOM,
     * VOX, TONEWHEEL, PLUCK. Measured, not chosen: the median of
     * `Loudness.of` across all 17 factory-default voices as they rendered
     * before this change, peak-normalized at 0.95 (task-4-report.md has the
     * full before-table). Median rather than mean because it keeps overall
     * kit level roughly where it already sat - nothing that was already
     * loud suddenly clips, nothing already quiet suddenly vanishes - while
     * collapsing the spread between voices, which was the actual defect: a
     * sine-heavy pad and a buzzy one used to land at the same *peak* and
     * very different loudness.
     *
     * Per-voice offsets deliberately stay at zero: each melodic engine
     * keeps its own `LOUDNESS_OFFSET` map (all zero right now) that this
     * constant is added to, so a future listening pass - whether a kick
     * should sit above a hat, and by how much - is a table edit there, not
     * a refactor here.
     *
     * Not every voice actually lands here. [levelTo]'s ceiling still caps
     * the old peak at 0.95 * (0.99 / 0.95); a voice whose crest factor is
     * high enough that reaching this target would need more than that -
     * PLUCK's Karplus-Strong pluck, sharpest of the five engines - gets
     * capped short instead (task-4-report.md has the exact numbers, e.g.
     * KALIMBA settling at ~0.064). That is [levelTo] working as designed,
     * not a bug: a shared target across five different crest factors can
     * move a peaky voice's loudness *down* to match the rest freely, but
     * can only move it *up* as far as digital full scale allows.
     *
     * STALE MEASUREMENT: the median above, task-4-report.md's 17-voice
     * table, its 2.86x residual spread, and its "7 of 17 voices
     * ceiling-pinned" count all describe renders from before Task 6 (key
     * tracking, which moves VELVET/FATHOM preset brightness by -15.9% to
     * +7.2%) and before Task 11 (PLUCK oversampling and retune, which
     * changes its crest factor). This branch no longer produces the
     * renders that number was measured from. [levelTo] still collapses
     * the spread by construction regardless of the target's exact value,
     * so nothing here is broken - but the median, the spread figure, and
     * the ceiling-pinned count must be re-measured before anyone cites
     * them again (Phase 1 plans to). Do not treat 0.1834f itself as wrong
     * in the meantime; it just isn't re-derived yet.
     */
    const val MELODIC_LOUDNESS_TARGET = 0.1834f

    /** Peak-normalize in place to [target]; silence is left alone. */
    fun normalize(buf: FloatArray, target: Float = 0.95f) {
        var peak = 0f
        for (v in buf) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        if (peak <= 1e-9f) return
        val g = target / peak
        for (i in buf.indices) buf[i] *= g
    }

    /**
     * Scale [buf] so its measured loudness ([Loudness.of]) hits [target],
     * then hold a sample-peak [ceiling] with [limitPeak] - [limitPeak]
     * scans raw sample magnitude, with no oversampling for inter-sample
     * peaks, so it is not a true-peak ceiling. Not a live bug: every
     * melodic engine already renders oversampled and Tape self-
     * renormalises downstream, but the claim itself was broader than what
     * the code does.
     *
     * Peak normalisation makes a sine-heavy patch sit quieter than a saw at
     * the same target number - crest factor, not perceived level, decides
     * where the peak lands. That's why THUMP alone used to read as "loud
     * enough": [Punch.rescaleToLoudness] happens to be a loudness rescale
     * too, but for a different reason - it *preserves* THUMP's pre-Punch
     * level across the transient shaping, it does not *choose* one. This is
     * the first function in the codebase that picks an absolute loudness
     * target on purpose, which is why callers (see each melodic engine's
     * `render`) derive [target] from a measurement instead of a guess.
     *
     * The [ceiling] pass matters because a loudness match and a peak limit
     * are different constraints: a signal with almost no crest factor (a
     * square-ish wave, or several engines' voices stacked) can hit the
     * loudness target while its peak is already near or past digital full
     * scale. Rescaling for loudness alone would let that clip on export;
     * [limitPeak] afterward only steps in for the voices that actually reach
     * it, exactly like it does downstream of [Punch].
     */
    fun levelTo(buf: FloatArray, rate: Int, target: Float, ceiling: Float = 0.99f) {
        if (buf.isEmpty()) return
        val measured = Loudness.of(Snip(buf.copyOf(), channels = 1, sampleRate = rate))
        if (measured <= 1e-6f) return
        val gain = target / measured
        for (i in buf.indices) buf[i] *= gain
        limitPeak(buf, ceiling)
    }

    /**
     * Scales down only if [buf]'s peak exceeds [ceiling] - a clipping safety
     * net, not a level target (unlike [normalize], which always rescales).
     * A stage like [Punch] that deliberately sets its own final level (U3,
     * docs/SYNTH_UPGRADE.md - "swap the peak target for a perceived-level
     * target") needs exactly this after it: something that only intervenes
     * when the shaping pushed a sample past what's safe, rather than
     * unconditionally overwriting the level that stage just chose.
     */
    fun limitPeak(buf: FloatArray, ceiling: Float = 1f) {
        var peak = 0f
        for (v in buf) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        if (peak <= ceiling || peak <= 1e-9f) return
        val g = ceiling / peak
        for (i in buf.indices) buf[i] *= g
    }

    /**
     * Decimates [buf] (rendered at `rate * OVERSAMPLE`) back down to [rate],
     * via [Resampler] - the same windowed-sinc kernel already used for
     * device-rate conversion at bake time. Two cascaded 2x steps, not one
     * 4x step: `Resampler`'s own doc comment scopes its well-rejected range
     * to roughly 0.5x-2x of unity, and a straight 4:1 pass measured only
     * -10.9 dB rejection just above the new Nyquist versus -17.9 dB
     * cascaded (25 kHz probe, 176.4 kHz source) - each stage this way stays
     * inside the kernel's own documented comfort zone.
     */
    fun decimate(buf: FloatArray, rate: Int): FloatArray {
        val oversampled = Snip(buf, channels = 1, sampleRate = rate * OVERSAMPLE)
        val half = Resampler.resample(oversampled, rate * OVERSAMPLE / 2)
        return Resampler.resample(half, rate).samples
    }

    /** Short linear fade-out so a truncated tail never clicks. */
    fun fadeTail(buf: FloatArray, ms: Float = 4f) {
        val n = min(buf.size, (ms / 1000f * RATE).toInt())
        for (i in 0 until n) {
            buf[buf.size - 1 - i] *= i.toFloat() / n
        }
    }

    /**
     * A variable-speed head: for each output frame, [headAt] says where to
     * read (fractional) and how loud. Linearly interpolated between
     * neighbours; a position at or past the last source frame, or a zero
     * gain, leaves that output frame silent — a stop that outruns its
     * material simply runs out.
     *
     * The rack's two varispeed stages read through here — MOTION's capstan,
     * where the position is the integral of a speed ramp, and SPEED's pitch,
     * where it is a straight line — so they cannot drift apart in quality.
     */
    inline fun readAt(snip: Snip, outFrames: Int, headAt: (Int) -> Pair<Double, Float>): Snip {
        val ch = snip.channels
        val src = snip.samples
        val last = snip.frameCount - 1
        val out = FloatArray(outFrames * ch)
        for (k in 0 until outFrames) {
            val (pos, gain) = headAt(k)
            if (pos >= last || gain <= 0f) continue
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            for (c in 0 until ch) {
                val a = src[i * ch + c]
                val b = src[(i + 1) * ch + c]
                out[k * ch + c] = (a + (b - a) * frac) * gain
            }
        }
        return Snip(out, ch, snip.sampleRate)
    }
}
