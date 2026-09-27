package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
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
     * is one of those stages.
     */
    fun tune(freq: Float, loopHz: Float, rate: Int): Tuning {
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

        val exact = (rate / freq) - filterDelay - 0.5
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

    /**
     * One period of filtered, zero-mean noise, combed by the pick position -
     * the 1983 exciter with Jaffe & Smith's position comb. [n] is the loop's
     * integer delay from [tune]. Returns at most [maxLen] samples; they enter
     * the loop as input, never as its state.
     */
    fun pluckExciter(n: Int, freq: Float, pickHz: Float, position: Float, seed: Int, rate: Int, maxLen: Int): FloatArray {
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
     */
    class Loop(private val n: Int, private val a: Float, private val fb: Float, private val loopHz: Float, rate: Int) {
        private val size = n + 2
        private val history = FloatArray(size)
        private var i = 0
        private var apX1 = 0f
        private var apY1 = 0f
        private val loopLp = Dsp.OnePole(rate)

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
                x + fb * loopLp.lp(tuned, loopHz)
            }
            history[i % size] = y
            i++
            return y
        }
    }

    /** The 1983 plucked string: [pluckExciter] into a [Loop] tuned by [tune]. */
    fun pluck(freq: Float, seconds: Float, damping: Damping, pickHz: Float, seed: Int, rate: Int, position: Float = 0f): FloatArray {
        val t = tune(freq, damping.loopHz, rate)
        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(t.n + 2))
        val exc = pluckExciter(t.n, freq, pickHz, position, seed, rate, out.size)
        val loop = Loop(t.n, t.a, damping.fb, damping.loopHz, rate)
        for (i in out.indices) out[i] = loop.next(if (i < exc.size) exc[i] else 0f)
        return out
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
