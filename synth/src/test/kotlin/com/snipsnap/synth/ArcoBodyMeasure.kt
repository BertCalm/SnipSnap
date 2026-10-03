package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R1d's ruler for ARCO's BODY: how much a bowed note's *colour* moves when the knob moves, read on the finished render the owner
 * hears ([Arco.render]: the box, the band limit, the level, the vibrato and the fade, all of it), with every macro at
 * [Arco.defaults] except TUNE and BODY.
 *
 * **COLOUR DISTANCE D(X vs R)**, in dB. X and R are two renders of the same note (the same TUNE step, so the same
 * string, the same vibrato and the same raw bow, since BODY is a post-processing of the finished string); R is the reference,
 * the BODY 0.5 render ([REFERENCE_BODY], the default). D says how far the *harmonic spectral envelope* of X is from R's once the two are
 * equally loud:
 *
 * 1. f0 is the note's own frequency, [Arco.frequencyFor] of the TUNE step. It is not estimated: the vibrato moves the partials by about
 *    0.6 percent, and the bow's tuning share holds the pitch within a few cents, so a band is wide enough to follow them.
 * 2. The steady window is 0.4 s to 1.0 s of the finished render ([WINDOW_FROM], [WINDOW_TO]), Hann-weighted (periodic), zero-padded to
 *    four times its length, FFT in doubles. At the default HOLD the bow is on until 0.854 s and the velocity ramps to nothing by
 *    0.904 s, so the last 0.1 s is the lifted string falling; the Hann weight is under 0.1 there, and [windowCheck] prints D on two other windows.
 * 3. Harmonic k is every integer with [LOW_HZ] (80 Hz) <= k f0 <= [HIGH_HZ] (5 kHz). Its energy E_k is the sum of the window's spectral
 *    power |X(f)|^2 over the bins with `k f0 - h <= f < k f0 + h`, where `h = min(0.04 k f0, f0 / 2)`: a band of plus and minus 4
 *    percent ([BAND_SHARE]) around k f0, **never reaching past the midpoint to the next harmonic**. The clip matters only from k = 13, where 4
 *    percent of k f0 is more than half a harmonic spacing; there the bands tile the spectrum, so no bin is counted for two harmonics (a plain
 *    4 percent band at k = 25 holds three harmonics and would smear the per-harmonic deltas into each other). Up to k = 12 it is exactly the 4 percent band.
 * 4. `deltaK = 10 log10(E_k of X) - 10 log10(E_k of R)`, in dB (a floor of 1e-30 on a band energy, so a silent band is a number, not minus infinity).
 * 5. Loudness is equalised by taking the mean off: with weights `w_k = 1/k` (so every octave of harmonics counts the same: an octave holds about
 *    ln 2 of the sum wherever it is), `mean = sum(w_k deltaK) / sum(w_k)` and the residual is `r_k = deltaK - mean`.
 * 6. `D = sqrt(sum(w_k r_k^2) / sum(w_k))`: the 1/k-weighted RMS of the residual. Its peak-to-peak is `max r_k - min r_k`.
 *
 * What D is blind to: a change that raises every harmonic by the same dB (loudness), and anything that is not under a harmonic's band
 * (below k = 13 the box's ring between the partials; above it the tiled bands do hold it). What it is for: a box that tilts or humps the
 * envelope the partials sit on, which is what a held note can hear. It is 0 for identical renders, and for a gain.
 *
 * The control, checked by the test and printed on `ARCO control` lines. A render against itself gives D = 0 to 1e-12, and against itself
 * times 2 (+6.02 dB at every harmonic) also 0. A render against a copy with an ideal +6.0206 dB shelf above 1 kHz ([shelved]: the whole
 * render's spectrum times 2 from 1000 Hz up, inverse transformed) gives, by hand, at CELLO C3 (f0 130.8128 Hz, harmonics k = 1 to 38, none of them
 * within 4 percent of 1 kHz: k = 7 is 915.7 Hz and k = 8 is 1046.5 Hz): deltaK = 0 for k <= 7 and G = 20 log10 2 = 6.0206 dB for k = 8 to 38,
 * so with p = (H38 - H7) / H38 = (4.22790 - 2.59286) / 4.22790 = 0.38673 (H_n the harmonic numbers, the weights' sum),
 * `mean = p G` and `D = G sqrt(p (1 - p)) = 6.0206 x 0.48705 = 2.932 dB`. The test computes the prediction from f0 alone (not from the render) and holds the measured D to 0.05 dB of it.
 *
 * **The band lines** say where the colour moved, in the brief's octave bands (50-150, 150-300, 300-600, 600-1200, 1200-2400, 2400-5000 Hz):
 * `RES` is the residual `r_k` averaged in the band with the same 1/k weights (so the six numbers, weighted by each band's share of the sum, add up to 0: the
 * shape of the move with the loudness taken off); `BAND` is the all-energy level of the window's whole spectrum in each band, X minus R, with
 * the total energy equalised (the brief's own measurement of R1c's finished clips, "same RMS", on the window). `CLIP` repeats it on the whole finished
 * clip, Hann over all of it, for the two notes the brief gave numbers for.
 *
 * This is a ruler, not a bar: the only assertions are that nothing is NaN or infinite and that the controls above hold (the ruler's own arithmetic). Run it as
 * `ARCO_BODIES=0,0.5,0.75,1 flock /tmp/arco-gradle.lock ./gradlew --no-daemon :synth:test --rerun --tests 'com.snipsnap.synth.ArcoBodyMeasureTest'`
 * and read the `ARCO` lines in `synth/build/test-results/test/TEST-com.snipsnap.synth.ArcoBodyMeasureTest.xml`. A design that changes [Arco] sweeps BODY through [sweep].
 */
internal object ArcoBodyMeasure {

    /** The finished render's rate. */
    val RATE = Dsp.RATE

    /** The steady window, in seconds of the finished render. */
    const val WINDOW_FROM = 0.4
    const val WINDOW_TO = 1.0

    /** Half the band round a harmonic, as a share of its frequency (the vibrato's 0.6 percent is a seventh of it). */
    const val BAND_SHARE = 0.04

    /** The harmonics counted: those between these frequencies. */
    const val LOW_HZ = 80.0
    const val HIGH_HZ = 5000.0

    /** The reference render's BODY: the default, which R1c and every design keeps to the bit. */
    const val REFERENCE_BODY = 0.5f

    /** The BODY values of the standard sweep (the reference is always rendered as well). */
    val BODIES = listOf(0f, 0.5f, 0.75f, 1f)

    /** The TUNE steps of the grid: CELLO C2, F#2, C3, F#3, C4; ERHU D4, G4, C5, G5, A5. */
    val GRID: Map<ArcoVoice, List<Int>> = mapOf(
        ArcoVoice.CELLO to listOf(0, 6, 12, 18, 24),
        ArcoVoice.ERHU to listOf(0, 5, 10, 15, 19),
    )

    /** The octave bands the band lines are in (the brief's), as edges in Hz. */
    private val EDGES = doubleArrayOf(50.0, 150.0, 300.0, 600.0, 1200.0, 2400.0, 5000.0)
    val BAND_LABELS: List<String> = listOf("50-150", "150-300", "300-600", "600-1200", "1200-2400", "2400-5000")

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun noteName(voice: ArcoVoice, step: Int): String {
        val midi = Arco.rootMidi(voice) + step
        return NOTE_NAMES[midi % 12] + (midi / 12 - 1)
    }

    fun tuneOf(voice: ArcoVoice, step: Int): Float = step.toFloat() / Arco.tuneSemitones(voice)

    /** The note's f0, from the TUNE step: [Arco.frequencyFor], exact. */
    fun f0Of(voice: ArcoVoice, step: Int): Double = Arco.frequencyFor(voice, tuneOf(voice, step)).toDouble()

    /** The finished render the owner hears: [Arco.render] at [Arco.defaults] except TUNE (the step) and BODY. */
    fun viaArco(voice: ArcoVoice, step: Int, body: Float): FloatArray =
        Arco.render(voice, Arco.defaults(voice) + mapOf("TUNE" to tuneOf(voice, step), "BODY" to body)).samples

    // ---- the spectrum -------------------------------------------------------------------------------------------

    /** Power per bin of the windowed span, bins `0..n/2`, at [binHz] apart. */
    class Spectrum(val power: DoubleArray, val binHz: Double)

    private fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** Iterative radix-2 FFT in doubles, in place (the repo's `Fft` is float and builds its twiddles by recurrence, which a ruler should not). */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n > 0 && (n and (n - 1)) == 0) { "length must be a power of two: $n" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val sign = if (inverse) 2.0 * PI / len else -2.0 * PI / len
            for (k in 0 until half) {
                val wr = cos(sign * k)
                val wi = sin(sign * k)
                var i = k
                while (i < n) {
                    val a = i + half
                    val vr = re[a] * wr - im[a] * wi
                    val vi = re[a] * wi + im[a] * wr
                    re[a] = re[i] - vr; im[a] = im[i] - vi
                    re[i] += vr; im[i] += vi
                    i += len
                }
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** The Hann-windowed power spectrum of samples `from` until `to` of [x] (seconds), zero-padded to four times the span. */
    fun spectrum(x: FloatArray, from: Double, to: Double): Spectrum {
        val n0 = (from * RATE).roundToInt()
        val n1 = (to * RATE).roundToInt()
        require(n1 <= x.size) { "the render is ${x.size} samples (${x.size.toDouble() / RATE} s): the window ends at $to s" }
        val len = n1 - n0
        val n = nextPow2(4 * len)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until len) re[i] = x[n0 + i] * (0.5 - 0.5 * cos(2.0 * PI * i / len))
        fft(re, im)
        return Spectrum(DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }, RATE.toDouble() / n)
    }

    /** The power in bins with `lo <= f < hi`. */
    fun band(s: Spectrum, lo: Double, hi: Double): Double {
        var e = 0.0
        val a = ceil(lo / s.binHz).toInt().coerceAtLeast(0)
        val b = ceil(hi / s.binHz).toInt().coerceAtMost(s.power.size)
        for (j in a until b) e += s.power[j]
        return e
    }

    private fun db(e: Double) = 10.0 * log10(maxOf(e, 1e-30))

    // ---- the colour distance ------------------------------------------------------------------------------------

    /** The harmonics [LOW_HZ] to [HIGH_HZ] of [f0]. */
    fun harmonics(f0: Double): IntArray =
        (1..(HIGH_HZ / f0).toInt()).filter { it * f0 >= LOW_HZ && it * f0 <= HIGH_HZ }.toIntArray()

    /** The band of harmonic [k]: plus and minus 4 percent, never past the midpoint to the next harmonic. */
    fun bandOf(k: Int, f0: Double): Pair<Double, Double> {
        val c = k * f0
        val h = min(BAND_SHARE * c, 0.5 * f0)
        return (c - h) to (c + h)
    }

    /**
     * How much of the window's power between [LOW_HZ] and [HIGH_HZ] (plus the half spacing above the top harmonic) lies under a harmonic's band, in percent:
     * 100 where the bands tile (from k = 13), less for a high note whose few partials leave the spectrum between them to the box and the noise floor.
     */
    fun capture(s: Spectrum, f0: Double): Double {
        val ks = harmonics(f0)
        val under = ks.sumOf { k -> val (lo, hi) = bandOf(k, f0); band(s, lo, hi) }
        val all = band(s, LOW_HZ, HIGH_HZ + 0.5 * f0)
        return 100.0 * under / all
    }

    /** The octave band (index into [BAND_LABELS]) that [hz] lies in, -1 outside 50 Hz to 5 kHz. */
    private fun octaveOf(hz: Double): Int {
        for (b in 0 until EDGES.size - 1) if (hz >= EDGES[b] && hz < EDGES[b + 1]) return b
        return if (hz == EDGES.last()) EDGES.size - 2 else -1
    }

    /**
     * One distance: [d] and the residual's [p2p], the per-harmonic [delta] and [residual] (dB, for harmonics [ks]), the [mean] taken off (dB: how much
     * louder X's envelope is on the 1/k average), the `RES` octave-band means ([res], null where no harmonic falls in the band), and the `BAND` all-energy
     * deltas on the window ([bands]).
     */
    class Colour(
        val ks: IntArray, val delta: DoubleArray, val residual: DoubleArray,
        val mean: Double, val d: Double, val p2p: Double,
        val res: Array<Double?>, val bands: DoubleArray,
    ) {
        fun finite(): Boolean = d.isFinite() && p2p.isFinite() && mean.isFinite() && residual.all { it.isFinite() } &&
            delta.all { it.isFinite() } && res.all { it == null || it.isFinite() } && bands.all { it.isFinite() }
    }

    /** The all-energy band deltas in the brief's octave bands, X minus R, with the two spectra's total energy equalised. */
    fun bandDeltas(sx: Spectrum, sr: Spectrum): DoubleArray {
        val offset = db(sx.power.sum()) - db(sr.power.sum())
        return DoubleArray(BAND_LABELS.size) { b ->
            db(band(sx, EDGES[b], EDGES[b + 1])) - db(band(sr, EDGES[b], EDGES[b + 1])) - offset
        }
    }

    /** D of the spectrum [sx] against the reference [sr], for a note whose fundamental is [f0]. */
    fun colour(sx: Spectrum, sr: Spectrum, f0: Double): Colour {
        val ks = harmonics(f0)
        val delta = DoubleArray(ks.size) { i ->
            val (lo, hi) = bandOf(ks[i], f0)
            db(band(sx, lo, hi)) - db(band(sr, lo, hi))
        }
        val w = DoubleArray(ks.size) { 1.0 / ks[it] }
        val wSum = w.sum()
        var mean = 0.0
        for (i in ks.indices) mean += w[i] * delta[i]
        mean /= wSum
        val residual = DoubleArray(ks.size) { delta[it] - mean }
        var ss = 0.0
        for (i in ks.indices) ss += w[i] * residual[i] * residual[i]
        val bw = DoubleArray(BAND_LABELS.size)
        val bs = DoubleArray(BAND_LABELS.size)
        for (i in ks.indices) {
            val b = octaveOf(ks[i] * f0)
            if (b >= 0) { bw[b] += w[i]; bs[b] += w[i] * residual[i] }
        }
        val res = Array(BAND_LABELS.size) { if (bw[it] > 0.0) bs[it] / bw[it] else null }
        return Colour(
            ks, delta, residual, mean, sqrt(ss / wSum),
            (residual.maxOrNull() ?: 0.0) - (residual.minOrNull() ?: 0.0), res, bandDeltas(sx, sr),
        )
    }

    /** D of the render [x] against the render [r] (finished clips at [RATE]), window [from] to [to] seconds. */
    fun colour(x: FloatArray, r: FloatArray, f0: Double, from: Double = WINDOW_FROM, to: Double = WINDOW_TO): Colour =
        colour(spectrum(x, from, to), spectrum(r, from, to), f0)

    // ---- a design's sweep ---------------------------------------------------------------------------------------

    /** One cell of a sweep: the note, the BODY it was rendered at, its render and its distance from the reference render of the same note. */
    class Cell(val voice: ArcoVoice, val step: Int, val body: Float, val samples: FloatArray, val colour: Colour) {
        val name: String get() = noteName(voice, step)
        val f0: Double get() = f0Of(voice, step)
    }

    /**
     * Every cell of the grid: each note of [grid] at each of [bodies], the distance of each from the same note's render at [reference].
     * [render] defaults to the engine as it stands ([viaArco]); a design that changes [Arco] sweeps its own BODY values by
     * passing them here (or through the `ARCO_BODIES` environment variable of the test). The reference is rendered whether or not it is in [bodies];
     * if it is, its cell has D = 0. Notes render in parallel; the order of the result is the grid's, then [bodies]'.
     */
    fun sweep(
        bodies: List<Float> = BODIES, reference: Float = REFERENCE_BODY,
        grid: Map<ArcoVoice, List<Int>> = GRID, render: (ArcoVoice, Int, Float) -> FloatArray = ::viaArco,
    ): List<Cell> {
        val notes = grid.flatMap { (voice, steps) -> steps.map { voice to it } }
        return notes.parallelStream().map { (voice, step) ->
            val f0 = f0Of(voice, step)
            val sr = spectrum(render(voice, step, reference), WINDOW_FROM, WINDOW_TO)
            bodies.map { body ->
                val x = render(voice, step, body)
                Cell(voice, step, body, x, colour(spectrum(x, WINDOW_FROM, WINDOW_TO), sr, f0))
            }
        }.toList().flatten()
    }

    /** D(BODY [body] vs [reference]) of one note, the number a design is judged by. */
    fun distance(
        voice: ArcoVoice, step: Int, body: Float, reference: Float = REFERENCE_BODY,
        render: (ArcoVoice, Int, Float) -> FloatArray = ::viaArco,
    ): Double = colour(render(voice, step, body), render(voice, step, reference), f0Of(voice, step)).d

    fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
    }

    // ---- the controls -------------------------------------------------------------------------------------------

    /** [x] with its whole spectrum times [gain] from [fromHz] up: an ideal shelf, in doubles, so the control is the metric's and not a filter's. */
    fun shelved(x: FloatArray, fromHz: Double, gain: Double): FloatArray {
        val n = nextPow2(2 * x.size)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in x.indices) re[i] = x[i].toDouble()
        fft(re, im)
        for (j in 0..n / 2) {
            if (j.toDouble() * RATE / n >= fromHz) {
                re[j] *= gain; im[j] *= gain
                if (j != 0 && j != n / 2) { re[n - j] *= gain; im[n - j] *= gain }
            }
        }
        fft(re, im, inverse = true)
        return FloatArray(x.size) { re[it].toFloat() }
    }

    /** p: the 1/k share of the harmonics of [f0] at or above [shelfHz]. */
    private fun shelfShare(f0: Double, shelfHz: Double): Double {
        val ks = harmonics(f0)
        return ks.filter { it * f0 >= shelfHz }.sumOf { 1.0 / it } / ks.sumOf { 1.0 / it }
    }

    /** The shelf control's D, worked out from f0 alone: `G sqrt(p (1 - p))`, p the 1/k share of the harmonics at or above [shelfHz]. */
    fun predictedShelfD(f0: Double, shelfHz: Double, gainDb: Double): Double =
        shelfShare(f0, shelfHz).let { gainDb * sqrt(it * (1.0 - it)) }

    /** The shelf control's mean, `p G`. */
    fun predictedShelfMean(f0: Double, shelfHz: Double, gainDb: Double): Double = gainDb * shelfShare(f0, shelfHz)

    // ---- the whole clip, in the brief's way ----------------------------------------------------------------------

    /** `CLIP`: the all-energy octave-band deltas over the whole finished clip, total energy equalised: the brief's measurement of R1c's clips. */
    fun clipBands(x: FloatArray, r: FloatArray): DoubleArray {
        val to = min(x.size, r.size).toDouble() / RATE
        return bandDeltas(spectrum(x, 0.0, to), spectrum(r, 0.0, to))
    }

    // ---- the table ----------------------------------------------------------------------------------------------

    fun f(v: Double, digits: Int = 2): String = "%.${digits}f".format(java.util.Locale.ROOT, v)

    private fun s(v: Double, digits: Int = 1): String = "%+.${digits}f".format(java.util.Locale.ROOT, v)

    private fun resLine(c: Colour): String = c.res.joinToString(" ") { if (it == null) "   . " else s(it).padStart(5) }

    private fun bandLine(b: DoubleArray): String = b.joinToString(" ") { s(it).padStart(5) }

    /** Prints the whole table on `ARCO` lines. */
    fun print(cells: List<Cell>, reference: Float, bodies: List<Float>) {
        println("ARCO R1d ruler: COLOUR DISTANCE D(X vs R) in dB, R = BODY ${f(reference.toDouble())}, window ${f(WINDOW_FROM)}-${f(WINDOW_TO)} s, harmonics ${LOW_HZ.toInt()} Hz to ${HIGH_HZ.toInt()} Hz, band +-${(BAND_SHARE * 100).toInt()} percent clipped at the half spacing, weights 1/k")
        println("ARCO bands (Hz): ${BAND_LABELS.joinToString(" ")}")
        for (c in cells) {
            if (c.body == reference) continue
            val k = c.colour
            println(
                "ARCO D ${c.voice} ${c.name.padEnd(3)} ${f(c.f0, 1).padStart(7)} Hz k=${k.ks.first()}..${k.ks.last()} (${k.ks.size}) BODY ${f(c.body.toDouble())} vs ${f(reference.toDouble())}: " +
                    "D ${f(k.d)} dB  p2p ${f(k.p2p, 1)} dB  mean ${s(k.mean)} dB",
            )
            println("ARCO   RES  ${resLine(k)}")
            println("ARCO   BAND ${bandLine(k.bands)}")
        }
        // the per-harmonic residual of the two notes the brief gave numbers for
        for (c in cells) {
            if (c.body == reference || (c.name != "C3" && c.name != "C5")) continue
            val k = c.colour
            println(
                "ARCO k ${c.voice} ${c.name} BODY ${f(c.body.toDouble())} residual dB by harmonic: " +
                    k.ks.indices.joinToString(" ") { "${k.ks[it]}:${s(k.residual[it])}" },
            )
        }
        // the summary: D by note, per BODY
        // how much of the spectrum the bands see, and whether D climbs with BODY above the reference (the brief's requirement 2, as a printed fact, not a bar)
        for (c in cells.filter { it.body == reference }) {
            val top = cells.filter { it.voice == c.voice && it.step == c.step }.maxByOrNull { it.body }!!
            println(
                "ARCO capture ${c.voice} ${c.name.padEnd(3)}: share of the 80 Hz-5 kHz window power under the harmonic bands ${f(capture(spectrum(c.samples, WINDOW_FROM, WINDOW_TO), c.f0), 1)} percent at BODY ${f(c.body.toDouble())}, " +
                    "${f(capture(spectrum(top.samples, WINDOW_FROM, WINDOW_TO), c.f0), 1)} at BODY ${f(top.body.toDouble())}",
            )
        }
        for (v in ArcoVoice.entries) for (step in cells.filter { it.voice == v }.map { it.step }.distinct()) {
            val above = cells.filter { it.voice == v && it.step == step && it.body >= reference }.sortedBy { it.body }
            if (above.size < 2) continue
            val steps = above.zipWithNext { a, b -> b.colour.d - a.colour.d }
            val drops = above.zipWithNext().filter { (a, b) -> b.colour.d < a.colour.d - 1e-9 }.map { (a, b) -> "${f(a.body.toDouble())}->${f(b.body.toDouble())}" }
            println(
                "ARCO monotone $v ${above.first().name.padEnd(3)}: D over BODY ${above.joinToString(" ") { f(it.body.toDouble()) }} = ${above.joinToString(" ") { f(it.colour.d) }}; " +
                    "${if (drops.isEmpty()) "never decreases" else "DECREASES at ${drops.joinToString(", ")}"}; largest step ${f(steps.max())} dB",
            )
        }
        println("ARCO SUMMARY D(BODY b vs ${f(reference.toDouble())}) in dB, notes in grid order:")
        for (b in bodies) {
            val row = cells.filter { it.body == b }
            if (row.isEmpty()) continue
            val parts = ArcoVoice.entries.joinToString("  |  ") { v ->
                row.filter { it.voice == v }.joinToString(" ") { "${it.name} ${f(it.colour.d)}" }
            }
            println("ARCO   BODY ${f(b.toDouble())}: $parts  | median of ${row.size} ${f(median(row.map { it.colour.d }))}")
        }
    }
}

/**
 * The ruler's one test: prints the table (see [ArcoBodyMeasure] for the metric), checks the ruler on its controls and asserts that nothing is NaN or infinite.
 *
 * R1d saw, on R1c's engine: D(self) 0 and D(self times 2) 0 (bounds 1e-12 and 1e-6), the +6.02 dB shelf at CELLO C3 D = 2.9320 against the hand's 2.9320 (the bound is 0.05 dB: the
 * same shelf read with uniform weights would give 2.33, and one with a different edge another number, so the control can fail), and the BASELINE D(BODY 1 vs 0.5) of the ten
 * notes, median 2.62 dB (2.31 to 2.77), in the table. The sweep's numbers have no bound: a ruler has no bar.
 */
class ArcoBodyMeasureTest {

    /** `ARCO_BODIES=0,0.5,0.75,1` replaces the BODY values swept (the reference 0.5 is rendered regardless). */
    private fun bodies(): List<Float> =
        System.getenv("ARCO_BODIES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.toFloat() } ?: ArcoBodyMeasure.BODIES

    @Test
    fun `the colour distance of BODY against the default, on the finished render, with its controls`() {
        val m = ArcoBodyMeasure
        val bodies = bodies()
        val ref = m.REFERENCE_BODY

        // ---- the controls, on CELLO C3 at the reference BODY
        val c3 = m.viaArco(ArcoVoice.CELLO, 12, ref)
        val f0 = m.f0Of(ArcoVoice.CELLO, 12)
        val self = m.colour(c3, c3, f0)
        val twice = m.colour(FloatArray(c3.size) { c3[it] * 2f }, c3, f0)
        val shelfDb = 20.0 * log10(2.0)
        val shelf = m.colour(m.shelved(c3, 1000.0, 2.0), c3, f0)
        val predicted = m.predictedShelfD(f0, 1000.0, shelfDb)
        val closeToEdge = m.harmonics(f0).filter { k -> val (lo, hi) = m.bandOf(k, f0); lo < 1000.0 && hi > 1000.0 }
        println("ARCO control: CELLO C3 (f0 ${m.f(f0, 4)} Hz, ${m.harmonics(f0).size} harmonics), harmonics whose band straddles 1 kHz: $closeToEdge")
        println(
            "ARCO control: D(self) ${m.f(self.d, 12)}  D(self x2) ${m.f(twice.d, 12)} (mean ${m.f(twice.mean, 4)} dB)  " +
                "D(+${m.f(shelfDb, 4)} dB shelf above 1 kHz) ${m.f(shelf.d, 4)}  predicted by hand ${m.f(predicted, 4)}  (mean ${m.f(shelf.mean, 4)}, hand ${m.f(m.predictedShelfMean(f0, 1000.0, shelfDb), 4)})",
        )
        println("ARCO control: shelf p2p ${m.f(shelf.p2p, 4)} dB (hand ${m.f(shelfDb, 4)}), RES by band ${m.BAND_LABELS.indices.joinToString(" ") { shelf.res[it]?.let { v -> "%+.2f".format(java.util.Locale.ROOT, v) } ?: "." }}")
        assertTrue(self.d < 1e-12, "a render against itself is not 0: ${self.d}")
        assertTrue(twice.d < 1e-6, "a render against itself times 2 is not 0: ${twice.d}")
        assertTrue(closeToEdge.isEmpty(), "a harmonic's band straddles the shelf's edge, so the hand prediction does not hold: $closeToEdge")
        assertEquals(predicted, shelf.d, 0.05, "the shelf control is not the hand's number")

        // ---- the sweep
        val cells = m.sweep(bodies, ref)
        m.print(cells, ref, bodies)

        // ---- the baseline: BODY 1 against the default, ten notes
        val top = cells.filter { it.body == 1f }
        if (top.isNotEmpty()) {
            val ds = top.map { it.colour.d }
            println("ARCO BASELINE D(BODY 1 vs ${m.f(ref.toDouble())}) of ${ds.size} notes: ${top.joinToString(" ") { "${it.voice.name.take(1)}:${it.name} ${m.f(it.colour.d)}" }}")
            println("ARCO BASELINE median ${m.f(m.median(ds), 3)} dB, min ${m.f(ds.min(), 3)}, max ${m.f(ds.max(), 3)}; CELLO median ${m.f(m.median(top.filter { it.voice == ArcoVoice.CELLO }.map { it.colour.d }), 3)}, ERHU median ${m.f(m.median(top.filter { it.voice == ArcoVoice.ERHU }.map { it.colour.d }), 3)}")

            // how much the answer depends on the window: BODY 1 on two other windows (0.4-0.8 s is all bow-on; 0.6-1.0 s is the late half)
            for (c in top) {
                val r = cells.first { it.voice == c.voice && it.step == c.step && it.body == ref }.samples
                val early = m.colour(c.samples, r, c.f0, 0.4, 0.8).d
                val late = m.colour(c.samples, r, c.f0, 0.6, 1.0).d
                println("ARCO window-check ${c.voice} ${c.name.padEnd(3)} BODY 1: D 0.4-1.0 s ${m.f(c.colour.d)}  0.4-0.8 s ${m.f(early)}  0.6-1.0 s ${m.f(late)}")
            }
        }

        // ---- the brief's measurement: whole finished clips, all energy, equal RMS (C3 and C5)
        for (c in cells) {
            if (c.body == ref || (c.name != "C3" && c.name != "C5")) continue
            val r = cells.first { it.voice == c.voice && it.step == c.step && it.body == ref }.samples
            val levels = "rms ${m.f(rms(c.samples), 4)} vs ${m.f(rms(r), 4)}, X is ${"%+.2f".format(java.util.Locale.ROOT, 20.0 * log10(rms(c.samples) / rms(r)))} dB over R before the equalising"
            println("ARCO CLIP ${c.voice} ${c.name} BODY ${m.f(c.body.toDouble())} vs ${m.f(ref.toDouble())}: ${m.BAND_LABELS.joinToString(" ")} Hz: ${m.clipBands(c.samples, r).joinToString(" ") { "%+.1f".format(java.util.Locale.ROOT, it) }}  ($levels, lengths ${c.samples.size} and ${r.size})")
        }

        // ---- the only assertion: nothing is NaN or infinite
        for (c in cells) {
            assertTrue(c.samples.all { it.isFinite() }, "${c.voice} ${c.name} BODY ${c.body}: a render sample is NaN or infinite")
            assertTrue(c.colour.finite(), "${c.voice} ${c.name} BODY ${c.body}: the colour distance has a NaN or infinite part")
        }
    }

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size)
}
