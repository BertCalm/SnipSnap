package com.snipsnap.synth

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What ARCO's tests measure on the bow's own wave, at the rate the bow runs at (the raw core, before the body,
 * the band limit, the decimator and the level, so a stuck or silent render cannot be lifted to full scale and hide).
 *
 * One slip a period is read off the string's velocity under the bow ([Strings.Bow.bowPoint]) as the *gaps between
 * its slips*, not as a count over a fixed window. A count over ten nominal periods reads 11 or 9 whenever the
 * note is a few cents off its nominal pitch (ten periods of a note 3 cents sharp are 10.02 long, so one window in
 * fifty holds an eleventh slip) - the first R1b maps lost half their cells to that. A gap near one period is a
 * clean Helmholtz slip; a gap near half or a third of it is a double or a triple slip, and no pitch error can
 * make that.
 */
internal object ArcoMeasure {

    /** The rate the bow runs at: [Dsp.RATE] times [Dsp.OVERSAMPLE]. */
    val RATE = Dsp.RATE * Dsp.OVERSAMPLE

    /** A gap is clean when it is one period long to within this share. */
    const val CLEAN_TOLERANCE = 0.15

    /** Where [bowPoint] falls through half of [vBow] (a slip begins), to a fraction of a sample, up to sample [to]. */
    fun slipTimes(bowPoint: FloatArray, to: Int, vBow: Float = Arco.V_SUSTAIN): DoubleArray {
        val threshold = 0.5f * vBow
        val found = ArrayList<Double>()
        for (i in 1 until minOf(to, bowPoint.size)) {
            if (bowPoint[i - 1] >= threshold && bowPoint[i] < threshold) {
                found.add(i - 1 + (bowPoint[i - 1] - threshold).toDouble() / (bowPoint[i - 1] - bowPoint[i]))
            }
        }
        return found.toDoubleArray()
    }

    /** True when the gap from [slips]`[i - 1]` to [slips]`[i]` is not one period of [hz] to within [CLEAN_TOLERANCE]. */
    fun unclean(slips: DoubleArray, i: Int, hz: Float): Boolean {
        val period = RATE / hz.toDouble()
        val gap = slips[i] - slips[i - 1]
        return gap < (1 - CLEAN_TOLERANCE) * period || gap > (1 + CLEAN_TOLERANCE) * period
    }

    /** How many gaps between slips that end after [fromSeconds] are not one period of [hz] long. */
    fun uncleanGaps(slips: DoubleArray, hz: Float, fromSeconds: Double = 0.0): Int {
        var n = 0
        for (i in 1 until slips.size) if (slips[i] / RATE >= fromSeconds && unclean(slips, i, hz)) n++
        return n
    }

    /**
     * When the string locks into one slip a period for good, in seconds: the time of the first slip after the
     * last unclean gap up to sample [end]; -1 if there are too few slips to tell or the last gap is still unclean.
     */
    fun lockSeconds(bowPoint: FloatArray, end: Int, hz: Float, vBow: Float = Arco.V_SUSTAIN): Double {
        val slips = slipTimes(bowPoint, end, vBow)
        if (slips.size < 12) return -1.0
        var lastBad = -1
        for (i in 1 until slips.size) if (unclean(slips, i, hz)) lastBad = i
        if (lastBad == slips.size - 1) return -1.0
        return (if (lastBad < 0) slips[0] else slips[lastBad]) / RATE
    }

    // ---- the cells: one note's macros, and the raw core played at them ---------------------------------------

    /** GRIP's path as the speaks test walks it: 0 to 1 in tenths. */
    val GRIP_POINTS: List<Float> = (0..10).map { it / 10f }

    /** The TUNE value that snaps to [step] semitones above the voice's root (the step, not a float that might round the other way). */
    fun tuneOf(voice: ArcoVoice, step: Int): Float = step.toFloat() / Arco.tuneSemitones(voice)

    /** Every TUNE step of a voice: 25 for the CELLO, 20 for the ERHU. */
    fun steps(voice: ArcoVoice): IntRange = 0..Arco.tuneSemitones(voice)

    fun hzOf(voice: ArcoVoice, step: Int): Float = Arco.frequencyFor(voice, tuneOf(voice, step))

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** The note TUNE lands on, as a player writes it: C2, D#4, A5. */
    fun noteName(voice: ArcoVoice, step: Int): String {
        val midi = Arco.rootMidi(voice) + step
        return NOTE_NAMES[midi % 12] + (midi / 12 - 1)
    }

    /** Every macro of one note: BODY at 0 by default, because the physics tests read the string and not the box. */
    fun macros(
        voice: ArcoVoice, step: Int,
        bow: Float = Arco.DEFAULT_BOW, grip: Float = Arco.DEFAULT_GRIP, body: Float = 0f, hold: Float = Arco.DEFAULT_HOLD,
    ): Map<String, Float> =
        Arco.defaults(voice) + mapOf("TUNE" to tuneOf(voice, step), "BOW" to bow, "GRIP" to grip, "BODY" to body, "HOLD" to hold)

    /**
     * A raw render and where it is in time: the bow is on the string for the first [holdN] samples, the velocity
     * ramps to nothing, the bow lifts at [liftN], and the stop runs to the end of [out]. [bowPoint] is the string's
     * velocity under the bow, which is what the slip counter reads.
     */
    class Core(
        val voice: ArcoVoice, val step: Int, val hz: Float,
        val out: FloatArray, val bowPoint: FloatArray, val holdN: Int, val liftN: Int,
    ) {
        val stopN: Int get() = out.size - liftN
    }

    /**
     * The engine's own core ([Arco.bow]) at one TUNE step, with the sizes the gate works out reproduced here so a
     * test can say "past the lock" and "after the lift" in samples. The bow is on for [gateSeconds] (else HOLD's
     * seconds), vibrato is OFF unless asked for (a physics test reads a plain string), and [pressure], [cornerHz]
     * and [overshoot] replace what GRIP and BOW would have chosen. The stop's length is still worked out from
     * GRIP's corner, whatever [cornerHz] says: that is what the engine does. [overshootMax], [biteSeconds] and [pressureBite] replace the
     * voice's own bite ([Arco.bow]'s probe overrides): `overshootMax = 1.75f, biteSeconds = 0f, pressureBite = 1f` is R1b's CELLO stroke.
     */
    fun core(
        voice: ArcoVoice, step: Int,
        bow: Float = Arco.DEFAULT_BOW, grip: Float = Arco.DEFAULT_GRIP, body: Float = 0f, hold: Float = Arco.DEFAULT_HOLD,
        gateSeconds: Float? = null, pressure: Float? = null, cornerHz: Float? = null, overshoot: Float? = null,
        vibrato: Boolean = false, lifted: Boolean = false, overshootMax: Float? = null, biteSeconds: Float? = null, pressureBite: Float? = null,
    ): Core {
        val hz = hzOf(voice, step)
        val holdSeconds = gateSeconds ?: Arco.holdSeconds(hold)
        val holdN = (holdSeconds * RATE).toInt().coerceAtLeast(1)
        val liftN = holdN + (Arco.RELEASE_RAMP_SECONDS * RATE).toInt()
        // generous: the tap must not be shorter than the render, and a stop is never longer than the longest free ring
        val tap = FloatArray(liftN + 8 * RATE)
        val out = Arco.bow(
            voice, hz, macros(voice, step, bow, grip, body, hold), RATE,
            pressure = pressure, cornerHz = cornerHz, overshoot = overshoot, gateSeconds = gateSeconds,
            vibrato = vibrato, lifted = lifted, bowPointOut = tap, overshootMax = overshootMax, biteSeconds = biteSeconds, pressureBite = pressureBite,
        )
        require(out.size < tap.size) { "the tap buffer is shorter than the render" }
        return Core(voice, step, hz, out, tap.copyOf(out.size), holdN, liftN)
    }

    /** What [Arco.render] does to a raw wave after the core, as the engine's one function, [Arco.finished]: the box, the band limit, the decimator, the DC, the level (with the lift above the knee) and the fade. The raw wave is not touched. */
    fun finished(raw: FloatArray, voice: ArcoVoice, body: Float): FloatArray =
        Arco.finished(raw.copyOf(), voice, body, RATE)

    // ---- speaking: the judgement the speaks test and its negative control share ---------------------------------

    /**
     * What a note's first [Core.holdN] samples did: [lock] seconds until one slip a period for good (-1 never), [slips]
     * in all, [tailPeriods] from the last slip to the end of the bow-on (a string that fell silent has a long one), and
     * [peak] the autocorrelation peak of the last 0.3 s. There is no count of unclean gaps after the lock: [lock] is the
     * first slip after the last unclean gap, so that count is zero for every note that locks, and a note whose last gap
     * is still unclean has no lock (-1) and fails on that.
     */
    class Verdict(val lock: Double, val slips: Int, val tailPeriods: Double, val peak: Double)

    fun verdict(c: Core): Verdict {
        val end = c.holdN
        val lock = lockSeconds(c.bowPoint, end, c.hz)
        val slips = slipTimes(c.bowPoint, end)
        val period = RATE / c.hz.toDouble()
        val tail = if (slips.isEmpty()) Double.POSITIVE_INFINITY else (end - slips.last()) / period
        val len = (0.3 * RATE).toInt().coerceAtMost(end)
        val peak = BowMeter.pitch(c.out, end - len, len, c.hz).second
        return Verdict(lock, slips.size, tail, peak)
    }

    // ---- pitch ------------------------------------------------------------------------------------------------

    /** The settled pitch in cents from the note: by the windowed FFT on the finished 44.1 kHz render (the record's measure) and by autocorrelation on the raw wave (the cross-check). */
    class PitchRead(val fftCents: Double, val acCents: Double, val acPeak: Double)

    fun pitchOf(c: Core, fromSeconds: Float, bodySeconds: Float): PitchRead {
        val snip = com.snipsnap.audio.Snip(finished(c.out, c.voice, 0f), channels = 1, sampleRate = Dsp.RATE)
        val fft = FineTuning.cents(FineTuning.measuredHz(snip, c.hz, fromSeconds, bodySeconds), c.hz.toDouble())
        val from = (fromSeconds * RATE).toInt()
        val len = min((bodySeconds * RATE).toInt(), (0.5 * RATE).toInt())
        val (hz, peak) = BowMeter.pitch(c.out, from, len, c.hz)
        return PitchRead(fft, BowMeter.cents(hz, c.hz.toDouble()), peak)
    }

    // ---- levels and onsets -------------------------------------------------------------------------------------

    /** A window of whole periods of [hz], at least [minSeconds] and [minPeriods] long, in samples: an RMS over it does not wobble with the sawtooth's phase. */
    fun wholePeriods(hz: Float, minSeconds: Double = 0.02, minPeriods: Int = 1): Int =
        (ceil(minSeconds * hz).coerceAtLeast(minPeriods.toDouble()) * RATE / hz).roundToInt()

    /**
     * The level of the string's fundamental over [from, from + len) in dB (relative, so only differences mean anything): the
     * strongest Hann-windowed Fourier magnitude within 1 percent of [hz]. It sees the fundamental alone, so neither the
     * faster-dying upper harmonics nor the slow net displacement of the onset can pollute a ring-down slope.
     */
    fun fundamentalDb(x: FloatArray, from: Int, len: Int, hz: Float): Double {
        var best = 0.0
        for (k in -10..10) {
            val step = 2.0 * Math.PI * hz * (1.0 + 0.001 * k) / RATE
            var re = 0.0
            var im = 0.0
            for (i in 0 until len) {
                val w = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (len - 1))
                val v = x[from + i] * w
                re += v * kotlin.math.cos(step * i)
                im += v * kotlin.math.sin(step * i)
            }
            best = max(best, sqrt(re * re + im * im))
        }
        return 20.0 * log10(max(best, 1e-12))
    }

    /**
     * Running sums of the samples and of their squares, so any window's level is a few lookups. The AC level takes the
     * window's own mean out first: the bow's onset leaves a net displacement in the string that nothing but the bridge's
     * 0.95 a period drains, and a level that includes it is the DC's decay, not the string's ring.
     */
    class Energy(x: FloatArray) {
        private val cum = DoubleArray(x.size + 1)
        private val sum = DoubleArray(x.size + 1)
        val size = x.size

        init {
            for (i in x.indices) {
                cum[i + 1] = cum[i] + x[i].toDouble() * x[i]
                sum[i + 1] = sum[i] + x[i]
            }
        }

        /** The RMS of [from, from + len), the samples past the end counting as silence. */
        fun rms(from: Int, len: Int): Double {
            val a = from.coerceIn(0, size)
            val b = (from + len).coerceIn(0, size)
            return sqrt(max(cum[b] - cum[a], 0.0) / len)
        }

        /** The RMS of the same window with its mean taken out. */
        fun ac(from: Int, len: Int): Double {
            val a = from.coerceIn(0, size)
            val b = (from + len).coerceIn(0, size)
            val mean = (sum[b] - sum[a]) / len
            return sqrt(max((cum[b] - cum[a]) / len - mean * mean, 0.0))
        }

        fun db(from: Int, len: Int): Double = 20.0 * log10(max(rms(from, len), 1e-12))

        fun acDb(from: Int, len: Int): Double = 20.0 * log10(max(ac(from, len), 1e-12))
    }

    /**
     * Milliseconds until a sliding window of whole periods (at least 10 ms; its centre is the time) first holds
     * [fraction] of the RMS read over the steady stretch [steadyFrom] to [steadyTo] seconds.
     */
    fun msToFraction(out: FloatArray, hz: Float, steadyFrom: Double, steadyTo: Double, fraction: Double = 0.9): Double {
        val e = Energy(out)
        val w = wholePeriods(hz, 0.010)
        val steady = e.rms((steadyFrom * RATE).toInt(), ((steadyTo - steadyFrom) * RATE).toInt())
        val step = RATE / 1000
        var s = 0
        while (s + w < out.size) {
            if (e.rms(s, w) >= fraction * steady) return (s + w / 2) * 1000.0 / RATE
            s += step
        }
        return -1.0
    }

    // ---- the bite (R1c) ------------------------------------------------------------------------------------------

    /** The windows the bite is read over, in seconds from the start of the stroke: 0 to 50, 50 to 100, 100 to 200 and 200 to 400 ms. */
    val BITE_WINDOWS: List<Pair<Double, Double>> = listOf(0.0 to 0.05, 0.05 to 0.10, 0.10 to 0.20, 0.20 to 0.40)

    /**
     * The gain in dB of [stroke] over [plain] (the same stroke with no bite) in the RMS of the raw string over [fromSeconds] to [toSeconds]. It is the RMS
     * over the whole window and not a sliding one, so a bite that is over in 20 ms is averaged with the sustain that follows it in the window.
     */
    fun windowGainDb(stroke: FloatArray, plain: FloatArray, fromSeconds: Double, toSeconds: Double): Double {
        val from = (fromSeconds * RATE).toInt()
        val to = (toSeconds * RATE).toInt()
        fun rms(x: FloatArray): Double {
            var acc = 0.0
            for (i in from until to) acc += x[i].toDouble() * x[i]
            return sqrt(acc / (to - from))
        }
        return 20.0 * log10(max(rms(stroke), 1e-12) / max(rms(plain), 1e-12))
    }

    // ---- the series ----------------------------------------------------------------------------------------------

    /**
     * The least-squares slope, in dB per octave, of levels at harmonics 1, 2, 3 ... (the index plus one): the line
     * through (log2 k, level) points. An ideal sawtooth, 1 over k, is -6.02; a flat series is 0.
     */
    fun octaveSlope(levelsDb: DoubleArray): Double {
        val n = levelsDb.size
        val x = DoubleArray(n) { log2((it + 1).toDouble()) }
        val mx = x.average()
        val my = levelsDb.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (x[i] - mx) * (levelsDb[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
        }
        return sxy / sxx
    }
}
