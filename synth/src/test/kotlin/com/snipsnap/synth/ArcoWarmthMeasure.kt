package com.snipsnap.synth

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R1e's ruler for ARCO's warmth page: how a candidate differs from THE PLAIN ONE of the same note in **absolute** terms, on the finished render the owner hears, and **not level-matched**.
 * R1d's ruler ([ArcoBodyMeasure], D and the BAND lines) took the loudness off both clips first, which is what hid a boost to the low mids as a cut to the top: that is the defect R1e removes.
 * The grid, the window, the spectrum and the harmonic bands are [ArcoBodyMeasure]'s (CELLO C2 F#2 C3 F#3 C4, ERHU D4 G4 C5 F5 A5, steady window 0.4 to 1.0 s, Hann, plus and minus 4 percent
 * round each partial clipped at the half spacing); nothing in that file is changed.
 *
 *  - **BANDS**: for each of 80-300, 300-1k, 1k-3k and 3k-8k Hz, the energy of the harmonics k f0 that fall in the band (`lo <= k f0 < hi`), in dB, candidate minus plain, no level taken off. A band that
 *    holds no harmonic of the note (an erhu has none under its fundamental, and CELLO C2's own fundamental is under 80 Hz) is `null` and prints as a dot. **F0** is the same for the fundamental alone.
 *  - **ALL** is the same four bands on the window's whole spectrum (the harmonics and what lies between them), the cross-check that the partials are not the only thing that moved.
 *  - **RMS** is the whole finished clip's RMS rise in dB; **LOUD** the finished clip's [com.snipsnap.audio.Loudness.of] rise in dB (the engine's own meter: the RMS of the loudest 200 ms after a gentle one-pole 120 Hz low cut);
 *    **A** the A-weighted rise in dB ([AWeighting], the steady window), which is what the LOUDER-ONLY control is matched on, and **AW** the same A-weighted RMS over the whole clip (the cross-check that the steady window is not what makes the match);
 *    **PEAK** the finished clip's largest sample as rendered (the generator renders without the engine's 0.99 ceiling except for one clip, see [ArcoWarmthCandidates.render]), and `ceiling` whether the engine's ceiling would act on it,
 *    which would duck the whole clip by the dB shown (the string is then no longer at the plain's level), or did act on it (the clip the engine could actually make).
 *  - **PRED** is what the shaping alone should do to a band: the measured response of the very [Dsp.Biquad]s ([responseOf]: an impulse through them, an FFT in doubles) at each harmonic, weighted by the
 *    plain's own harmonic energies, `10 log10(sum E_k |H(k f0)|^2 / sum E_k)`. A candidate that is added on top of the plain's level reads PRED in BANDS; one that was levelled afresh would read PRED minus its loudness rise.
 *
 * The ruler has controls of its own ([controlsFailures] returns what is wrong, the generator throws on it): a clip against itself reads 0 everywhere to 1e-9 dB; a clip times 2 reads +6.0206 dB in every band, RMS,
 * LOUD, A and AW and twice the peak; the A-weighting reads the standard's table (a bypassed filter would read 0 where the control expects -16.1 dB at 125 Hz) (so the ruler does not level-match, which would read 0); and an ideal +6.0206 dB shelf above 1 kHz on CELLO C3 ([ArcoBodyMeasure.shelved]) reads 0 in 80-300 and 300-1k and +6.0206 in 1k-3k and 3k-8k
 * to 0.05 dB (no partial of C3 has a band straddling 1 kHz: k = 7 is 915.7 Hz, k = 8 is 1046.5 Hz). A ruler that equalised loudness would read the shelf's two low bands below 0, not 0.
 */
internal object ArcoWarmthMeasure {

    /** The four bands, in Hz: `lo <= f < hi`. */
    val BANDS_HZ: List<Pair<Double, Double>> = listOf(80.0 to 300.0, 300.0 to 1000.0, 1000.0 to 3000.0, 3000.0 to 8000.0)
    val BAND_LABELS: List<String> = listOf("80-300", "300-1k", "1k-3k", "3k-8k")

    /** The top bands: indexes of 1k-3k and 3k-8k in [BANDS_HZ]. The brief's target is that these stay within [TOP_FLOOR_DB] of the plain for WEIGHT, WARMTH and BOTH. */
    val TOP_BANDS: List<Int> = listOf(2, 3)
    const val TOP_FLOOR_DB = -0.5

    private val RATE = ArcoBodyMeasure.RATE
    private const val FLOOR = 1e-30

    private fun db(e: Double) = 10.0 * log10(maxOf(e, FLOOR))

    // ---- the plain, measured once ---------------------------------------------------------------

    /** A finished clip measured once, to be the reference of other clips: its [spec]trum, RMS, [Loudness.of], A-weighted RMS (steady window and whole clip) and peak. */
    class Base(val samples: FloatArray, val f0: Double) {
        val spec: ArcoBodyMeasure.Spectrum = ArcoBodyMeasure.spectrum(samples, ArcoBodyMeasure.WINDOW_FROM, ArcoBodyMeasure.WINDOW_TO)
        val rms: Double = rmsOf(samples)
        val loud: Double = ArcoWarmthCandidates.loudnessOf(samples).toDouble()
        val aSteady: Double = aRmsSteady(samples)
        val aWhole: Double = aRmsWhole(samples)
        val peak: Float = samples.fold(0f) { p, v -> maxOf(p, abs(v)) }
    }

    fun rmsOf(x: FloatArray): Double {
        var acc = 0.0
        for (v in x) acc += v.toDouble() * v
        return sqrt(acc / x.size)
    }

    /** The harmonics k of [f0] with `lo <= k f0 < hi`. */
    fun harmonicsIn(f0: Double, lo: Double, hi: Double): IntArray {
        val first = Math.ceil(lo / f0 - 1e-9).toInt().coerceAtLeast(1)
        val ks = ArrayList<Int>()
        var k = first
        while (k * f0 < hi) { if (k * f0 >= lo) ks += k; k++ }
        return ks.toIntArray()
    }

    /** The energy under harmonic [k]'s band in [s]. */
    private fun harmonicEnergy(s: ArcoBodyMeasure.Spectrum, f0: Double, k: Int): Double {
        val (lo, hi) = ArcoBodyMeasure.bandOf(k, f0)
        return ArcoBodyMeasure.band(s, lo, hi)
    }

    /** The harmonics' energy in the band, or null where the band holds none. */
    private fun bandHarmonicEnergy(s: ArcoBodyMeasure.Spectrum, f0: Double, lo: Double, hi: Double): Double? {
        val ks = harmonicsIn(f0, lo, hi)
        return if (ks.isEmpty()) null else ks.sumOf { harmonicEnergy(s, f0, it) }
    }

    // ---- one clip against the plain -------------------------------------------------------------

    /** The ruler's numbers for one clip against THE PLAIN ONE of its note: [aRise] the A-weighted rise over the steady window, [aRiseWhole] over the whole clip. */
    class Numbers(
        val bands: Array<Double?>, val all: DoubleArray, val fundamental: Double,
        val rmsRise: Double, val loudRise: Double, val aRise: Double, val aRiseWhole: Double, val peak: Float,
    ) {
        fun finite(): Boolean = bands.all { it == null || it.isFinite() } && all.all { it.isFinite() } && fundamental.isFinite() && rmsRise.isFinite() && loudRise.isFinite() &&
            aRise.isFinite() && aRiseWhole.isFinite() && peak.isFinite()
    }

    /** [x] (a finished clip, same rate and window as [plain]) against [plain]. */
    fun compare(x: FloatArray, plain: Base): Numbers {
        val sx = ArcoBodyMeasure.spectrum(x, ArcoBodyMeasure.WINDOW_FROM, ArcoBodyMeasure.WINDOW_TO)
        val f0 = plain.f0
        val bands = Array(BANDS_HZ.size) { b ->
            val (lo, hi) = BANDS_HZ[b]
            val ex = bandHarmonicEnergy(sx, f0, lo, hi)
            val er = bandHarmonicEnergy(plain.spec, f0, lo, hi)
            if (ex == null || er == null) null else db(ex) - db(er)
        }
        val all = DoubleArray(BANDS_HZ.size) { b ->
            val (lo, hi) = BANDS_HZ[b]
            db(ArcoBodyMeasure.band(sx, lo, hi)) - db(ArcoBodyMeasure.band(plain.spec, lo, hi))
        }
        val fundamental = db(harmonicEnergy(sx, f0, 1)) - db(harmonicEnergy(plain.spec, f0, 1))
        val loud = ArcoWarmthCandidates.loudnessOf(x).toDouble()
        return Numbers(
            bands, all, fundamental,
            20.0 * log10(maxOf(rmsOf(x), FLOOR) / maxOf(plain.rms, FLOOR)), 20.0 * log10(maxOf(loud, FLOOR) / maxOf(plain.loud, FLOOR)),
            20.0 * log10(maxOf(aRmsSteady(x), FLOOR) / maxOf(plain.aSteady, FLOOR)), 20.0 * log10(maxOf(aRmsWhole(x), FLOOR) / maxOf(plain.aWhole, FLOOR)),
            x.fold(0f) { p, v -> maxOf(p, abs(v)) },
        )
    }

    // ---- the A-weighted rise: the ear's weighting, for the LOUDER-ONLY control -----------------------

    /**
     * IEC 61672's A-weighting as the standard analogue prototype (four zeros at 0 Hz; poles at 20.599 Hz twice, 107.653, 737.862 and 12194.217 Hz twice) through the bilinear transform at the clip rate: six first-order
     * sections in doubles and one gain that puts 1 kHz at 0 dB. Its response is within 0.15 dB of the standard's table from 63 Hz to 4 kHz (the generator's controls hold it to that); the bilinear transform warps the top
     * (about 0.7 dB low at 8 kHz, 1.5 dB at 10 kHz), where these clips hold little.
     */
    object AWeighting {
        private val POLES_HZ = doubleArrayOf(20.598997, 20.598997, 107.65265, 737.86223, 12194.217, 12194.217)

        /** One first-order section `(n0 + n1 z^-1) / (d0 + d1 z^-1)`: a pole at the analogue [POLES_HZ] and a zero at 0 Hz (the first four) or at Nyquist (the last two, the prototype's zeros at infinity). */
        private class Section(val n0: Double, val n1: Double, val d0: Double, val d1: Double)

        private fun sections(rate: Int): List<Section> {
            val k = 2.0 * rate
            return POLES_HZ.mapIndexed { i, hz ->
                val w = 2.0 * PI * hz
                Section(1.0, if (i < 4) -1.0 else 1.0, k + w, w - k)
            }
        }

        private fun magnitude(secs: List<Section>, hz: Double, rate: Int): Double {
            val w = 2.0 * PI * hz / rate
            val c = cos(w)
            val s = sin(w)
            var mag = 1.0
            for (sec in secs) mag *= hypot(sec.n0 + sec.n1 * c, -sec.n1 * s) / hypot(sec.d0 + sec.d1 * c, -sec.d1 * s)
            return mag
        }

        /** The response in dB at [hz]: 0 dB at 1 kHz. */
        fun responseDb(hz: Double, rate: Int): Double {
            val secs = sections(rate)
            return 20.0 * log10(magnitude(secs, hz, rate) / magnitude(secs, 1000.0, rate))
        }

        /** [x] through the filter from rest, in doubles. */
        fun apply(x: FloatArray, rate: Int): DoubleArray {
            val secs = sections(rate)
            val norm = 1.0 / magnitude(secs, 1000.0, rate)
            val xPrev = DoubleArray(secs.size)
            val yPrev = DoubleArray(secs.size)
            val out = DoubleArray(x.size)
            for (n in x.indices) {
                var v = x[n].toDouble() * norm
                for (i in secs.indices) {
                    val sec = secs[i]
                    val y = (sec.n0 * v + sec.n1 * xPrev[i] - sec.d1 * yPrev[i]) / sec.d0
                    xPrev[i] = v
                    yPrev[i] = y
                    v = y
                }
                out[n] = v
            }
            return out
        }
    }

    private fun rmsOf(y: DoubleArray, from: Int, to: Int): Double {
        var acc = 0.0
        for (i in from until to) acc += y[i] * y[i]
        return sqrt(acc / (to - from).coerceAtLeast(1))
    }

    /** The A-weighted RMS of the finished clip [x] over the steady window ([ArcoBodyMeasure.WINDOW_FROM] to [ArcoBodyMeasure.WINDOW_TO] s), the filter run from the clip's first sample so it is settled by then. */
    fun aRmsSteady(x: FloatArray): Double {
        val y = AWeighting.apply(x, Dsp.RATE)
        val from = (ArcoBodyMeasure.WINDOW_FROM * Dsp.RATE).toInt().coerceAtMost(y.size - 1)
        val to = (ArcoBodyMeasure.WINDOW_TO * Dsp.RATE).toInt().coerceAtMost(y.size)
        return rmsOf(y, from, to)
    }

    /** The A-weighted RMS over the whole clip, attack and tail included. */
    fun aRmsWhole(x: FloatArray): Double = AWeighting.apply(x, Dsp.RATE).let { rmsOf(it, 0, it.size) }

    /** The A-weighted rise of [x] over [reference] in dB, over the steady window: what the LOUDER-ONLY control is matched on. */
    fun aRiseDb(x: FloatArray, reference: FloatArray): Double = 20.0 * log10(maxOf(aRmsSteady(x), FLOOR) / maxOf(aRmsSteady(reference), FLOOR))

    // ---- what the shaping alone should do ---------------------------------------------------------

    /** Raw rate of the shaping and the length of the impulse the response is read from: 2 to the 18 is 1.5 s at 176.4 kHz, bins 0.67 Hz apart; a 200 Hz shelf is gone in a few milliseconds. */
    private const val RESPONSE_N = 1 shl 18

    private val responses = ConcurrentHashMap<String, (Double) -> Double>()

    /** Amplitude response of [shape] (the [Dsp.Biquad]s as [ArcoWarmthCandidates.applyShape] runs them): an impulse through them at [rate], an FFT in doubles, linear in the bins. */
    fun responseOf(shape: List<ArcoWarmthCandidates.Stage>, rate: Int): (Double) -> Double {
        val buf = FloatArray(RESPONSE_N)
        buf[0] = 1f
        ArcoWarmthCandidates.applyShape(buf, shape, rate)
        val re = DoubleArray(RESPONSE_N) { buf[it].toDouble() }
        val im = DoubleArray(RESPONSE_N)
        ArcoBodyMeasure.fft(re, im)
        val mag = DoubleArray(RESPONSE_N / 2 + 1) { hypot(re[it], im[it]) }
        return { hz ->
            val pos = hz * RESPONSE_N / rate
            val i = pos.toInt().coerceIn(0, RESPONSE_N / 2 - 1)
            val fr = pos - i
            mag[i] * (1.0 - fr) + mag[i + 1] * fr
        }
    }

    private fun cachedResponse(voice: ArcoVoice, kind: ArcoWarmthCandidates.Kind): (Double) -> Double =
        responses.computeIfAbsent("$voice/$kind") { responseOf(ArcoWarmthCandidates.shapeOf(kind, voice), Dsp.RATE * Dsp.OVERSAMPLE) }

    /** PRED per band for a shaped [kind]: the shaping's response at each harmonic, weighted by the plain's own harmonic energies. Null where the band holds no harmonic. */
    fun predicted(plain: Base, voice: ArcoVoice, kind: ArcoWarmthCandidates.Kind): Array<Double?> {
        val h = cachedResponse(voice, kind)
        return Array(BANDS_HZ.size) { b ->
            val (lo, hi) = BANDS_HZ[b]
            val ks = harmonicsIn(plain.f0, lo, hi)
            if (ks.isEmpty()) null else {
                var e0 = 0.0
                var e1 = 0.0
                for (k in ks) {
                    val e = harmonicEnergy(plain.spec, plain.f0, k)
                    val g = h(k * plain.f0)
                    e0 += e; e1 += e * g * g
                }
                db(e1) - db(e0)
            }
        }
    }

    // ---- one reading ------------------------------------------------------------------------------

    /**
     * One clip of the grid: its note, what it is, the ruler's [numbers], PRED ([predicted], null for the repeat and the flat-gain control: the shaping's own response, with no ceiling in it) and the [rendered] clip with the
     * ceiling's verdict. [peakBefore] is the largest sample with the string at the plain's level, [ceilingBackDb] the dB the engine's ceiling actually took off the whole clip (0 where the clip was rendered without it):
     * a measured delta plus this is the clip as it would be had the ceiling not acted.
     */
    class Reading(
        val voice: ArcoVoice, val step: Int, val kind: ArcoWarmthCandidates.Kind, val numbers: Numbers,
        val predicted: Array<Double?>?, val plainPeak: Float, val rendered: ArcoWarmthCandidates.Rendered, val louderGainDb: Double? = null,
    ) {
        val name: String get() = ArcoBodyMeasure.noteName(voice, step)
        val peakBefore: Float get() = rendered.peakBeforeCeiling
        val ceilingWouldAct: Boolean get() = rendered.ceilingWouldAct
        val ceilingActed: Boolean get() = rendered.ceilingActed
        val duckDb: Double get() = rendered.duckDb
        val ceilingBackDb: Double get() = rendered.tookOffDb
    }

    // ---- the controls -----------------------------------------------------------------------------

    /** What is wrong with the ruler, by its controls on CELLO C3's plain [c3] (empty: nothing). */
    fun controlsFailures(c3: FloatArray, f0: Double): List<String> {
        val out = ArrayList<String>()
        val base = Base(c3, f0)
        val self = compare(c3, base)
        if (!self.finite()) out += "the self comparison is not finite"
        val selfMax = (self.bands.filterNotNull() + self.all.toList() + listOf(self.fundamental, self.rmsRise, self.loudRise, self.aRise, self.aRiseWhole)).maxOf { abs(it) }
        if (selfMax > 1e-9) out += "a clip against itself reads ${f3(selfMax)} dB, not 0"
        val twice = compare(FloatArray(c3.size) { c3[it] * 2f }, base)
        val g = 20.0 * log10(2.0)
        val twiceOff = (twice.bands.filterNotNull() + twice.all.toList() + listOf(twice.fundamental, twice.rmsRise, twice.loudRise, twice.aRise, twice.aRiseWhole)).maxOf { abs(it - g) }
        if (twiceOff > 1e-4) out += "a clip times 2 is ${f3(twiceOff)} dB off +${f3(g)} dB: the ruler is not absolute"
        if (abs(twice.peak - 2f * base.peak) > 1e-6f) out += "a clip times 2 does not double the peak"
        val shelf = compare(ArcoBodyMeasure.shelved(c3, 1000.0, 2.0), base)
        val expected = doubleArrayOf(0.0, 0.0, g, g)
        for (b in BANDS_HZ.indices) {
            val v = shelf.bands[b]
            if (v == null || abs(v - expected[b]) > 0.05) out += "the ideal shelf reads ${v?.let { f3(it) }} in ${BAND_LABELS[b]}, expected ${f3(expected[b])}"
        }
        out += aWeightingFailures()
        return out
    }

    /** IEC 61672's A-weighting table at the octave centres, dB: the filter must read it (the bar is [A_TABLE_BAR_DB], and [A_8K_BAR_DB] at 8 kHz where the bilinear transform warps). */
    private val A_TABLE: List<Pair<Double, Double>> = listOf(31.5 to -39.4, 63.0 to -26.2, 125.0 to -16.1, 250.0 to -8.6, 500.0 to -3.2, 1000.0 to 0.0, 2000.0 to 1.2, 4000.0 to 1.0, 8000.0 to -1.1)
    private const val A_TABLE_BAR_DB = 0.2
    private const val A_8K_BAR_DB = 0.8

    /**
     * What is wrong with [AWeighting] (empty: nothing): its response against [A_TABLE] (R1e saw at most 0.13 dB off from 31.5 Hz to 4 kHz and 0.7 at 8 kHz; the bars are 0.2 and 0.8), and the filter itself on a sine in the time domain
     * (125 Hz must read -16.1 dB against the unweighted RMS and 1 kHz 0 dB, to 0.1 dB: a bypassed filter reads 0 and 0 and fails the first by 16 dB).
     */
    private fun aWeightingFailures(): List<String> {
        val out = ArrayList<String>()
        for ((hz, want) in A_TABLE) {
            val got = AWeighting.responseDb(hz, Dsp.RATE)
            val bar = if (hz >= 8000.0) A_8K_BAR_DB else A_TABLE_BAR_DB
            if (abs(got - want) > bar) out += "A-weighting at ${f1(hz)} Hz reads ${f2(got)} dB, the table says ${f1(want)} (bar ${f2(bar)})"
        }
        for ((hz, want) in listOf(125.0 to -16.1, 1000.0 to 0.0)) {
            val sine = FloatArray(Dsp.RATE * 3) { sin(2.0 * PI * hz * it / Dsp.RATE).toFloat() }
            val y = AWeighting.apply(sine, Dsp.RATE)
            val got = 20.0 * log10(rmsOf(y, Dsp.RATE, y.size) / rmsOf(DoubleArray(sine.size) { sine[it].toDouble() }, Dsp.RATE, sine.size))
            if (abs(got - want) > 0.1) out += "the A-weighting filter reads ${f2(got)} dB on a ${f1(hz)} Hz sine, expected ${f1(want)}"
        }
        return out
    }

    // ---- the table --------------------------------------------------------------------------------

    fun f1(v: Double) = "%.1f".format(Locale.ROOT, v)
    fun f2(v: Double) = "%.2f".format(Locale.ROOT, v)
    fun f3(v: Double) = "%.3f".format(Locale.ROOT, v)
    fun s1(v: Double) = "%+.1f".format(Locale.ROOT, v)
    fun s2(v: Double) = "%+.2f".format(Locale.ROOT, v)

    private fun bandsLine(a: Array<Double?>): String = a.joinToString(" ") { if (it == null) "    ." else s1(it).padStart(5) }

    /** The whole table, on `ARCO warmth` lines, every candidate at every note of the grid. */
    fun print(readings: List<Reading>) {
        println(
            "ARCO warmth ruler: ABSOLUTE band deltas vs THE PLAIN ONE of the same note, dB, NOT level-matched. Harmonic energy per band (${BAND_LABELS.joinToString(" ")} Hz), window " +
                "${ArcoBodyMeasure.f(ArcoBodyMeasure.WINDOW_FROM)}-${ArcoBodyMeasure.f(ArcoBodyMeasure.WINDOW_TO)} s; a dot is a band with no harmonic. F0 the fundamental alone; RMS the whole clip; LOUD the engine's Loudness.of; " +
                "A the A-weighted rise (steady window; AW the whole clip); PRED the shaping's own response at the partials (plain's energies); ALL all the spectrum in the band.",
        )
        for (voice in ArcoVoice.entries) {
            println("ARCO warmth ${voice.name}: kind | note f0 | BANDS ${BAND_LABELS.joinToString(" ")} | PRED same | ALL same | F0 | RMS LOUD A AW | PEAK (plain) ceiling")
            for (kind in listOf(ArcoWarmthCandidates.Kind.REPEAT, ArcoWarmthCandidates.Kind.LOUD) + ArcoWarmthCandidates.SHAPED) {
                for (r in readings.filter { it.voice == voice && it.kind == kind }) {
                    val n = r.numbers
                    println(
                        "ARCO warmth ${voice.name.take(1)} ${kind.key.padEnd(17)} ${r.name.padEnd(3)} ${f1(ArcoBodyMeasure.f0Of(voice, r.step)).padStart(6)} Hz | " +
                            "${bandsLine(n.bands)} | ${r.predicted?.let { bandsLine(it) } ?: "  n/a   n/a   n/a   n/a"} | ${n.all.joinToString(" ") { s1(it).padStart(5) }} | ${s1(n.fundamental).padStart(5)} | " +
                            "${s2(n.rmsRise).padStart(6)} ${s2(n.loudRise).padStart(6)} ${s2(n.aRise).padStart(6)} ${s2(n.aRiseWhole).padStart(6)} | ${f3(n.peak.toDouble())} (${f3(r.plainPeak.toDouble())}) " +
                            (if (r.ceilingActed) "CEILING ACTED: the string needed ${f3(r.peakBefore.toDouble())}, the whole clip is ${s1(r.duckDb)} dB" else if (r.ceilingWouldAct) "CEILING WOULD ACT (whole clip ${s1(r.duckDb)} dB)" else "no") +
                            (r.louderGainDb?.let { " | flat gain ${s2(it)} dB" } ?: ""),
                    )
                }
            }
        }
    }

    /** Per voice and shaped candidate, min / median / max of each band's delta over the five notes, and of RMS, LOUD and the peak ratio. */
    fun printSummary(readings: List<Reading>) {
        println("ARCO warmth SUMMARY per voice and candidate over its five grid notes: min / median / max, dB (bands and F0, RMS, LOUD, A) and the peak (the string at the plain's level, before any ceiling) as a ratio to the plain's")
        for (voice in ArcoVoice.entries) for (kind in ArcoWarmthCandidates.SHAPED + ArcoWarmthCandidates.Kind.LOUD) {
            val rs = readings.filter { it.voice == voice && it.kind == kind }
            fun trio(values: List<Double>): String = if (values.isEmpty()) "  .  " else "${s1(values.min())}/${s1(ArcoBodyMeasure.median(values))}/${s1(values.max())}"
            val bands = BAND_LABELS.indices.joinToString("  ") { b -> "${BAND_LABELS[b]} ${trio(rs.mapNotNull { it.numbers.bands[b] })}" }
            val ratio = rs.map { it.peakBefore.toDouble() / it.plainPeak }
            println(
                "ARCO warmth SUMMARY ${voice.name.take(1)} ${kind.key.padEnd(17)} $bands  F0 ${trio(rs.map { it.numbers.fundamental })}  RMS ${trio(rs.map { it.numbers.rmsRise })}  LOUD ${trio(rs.map { it.numbers.loudRise })}  " +
                    "A ${trio(rs.map { it.numbers.aRise })}  PEAK x${f2(ratio.min())}/${f2(ratio.max())} max ${f3(rs.maxOf { it.peakBefore.toDouble() })}",
            )
        }
    }
}
