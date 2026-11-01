package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * KEEL R1: the probe table of the spec's section 14 and the per-macro tests of section 11, measured on the
 * Kotlin renders. Every number is printed with a "KEEL" prefix so the As built table comes from this output.
 * The Python prototype levelled every voice with one shared macro set (SETTLE .5, WEIGHT .4, GRIT .2, LENGTH .5);
 * [HARNESS] is that set, so the Kotlin numbers sit beside the harness numbers on the same footing.
 * WEIGHT is the body of section 9 (measured here against the harness table of that section); nothing here
 * establishes that WEIGHT or the settle sound right: only the audition does.
 */
class KeelTest {
    private val rate = Dsp.RATE
    private val voices = KeelVoice.entries
    private val harness = mapOf("SETTLE" to .5f, "WEIGHT" to .4f, "GRIT" to .2f, "SLIDE" to .5f, "LENGTH" to .5f)

    /** The longest one-shot HOLD tried here: the top of the sustain stage, just under the loop region. */
    private val TOP_ONE_SHOT_HOLD = .84f

    /**
     * A held key's loop level may move this much (house-weighted 200 ms windows): a guard just above what the owner
     * heard as no pump (A2 with the bow's hair, .15, 3.5 dB; bow page), not a pump threshold. The noise bow, heard as
     * a pump (keys page, one answer for the page), swung 2.6 dB at C1 to 12.5 at A2; this fails it at six zones of nine.
     */
    private val MAX_LOOP_SWING_DB = 4.0

    /** A press may fall this far under its loop on the way into it: A2's press with the hair, heard, dips 2.2 dB. */
    private val MAX_PRESS_DIP_DB = 3.0

    /** The locked bow alone (no hair) holds the loop this still; it measures 0.3 dB at most. */
    private val MAX_STILL_SWING_DB = .5

    /**
     * Without hair, a press may fall this far under its loop. The locked bow starts in the pluck's phase; given
     * random phases instead, the press dips 1.5 dB at C1 and 3.1 at D#1, and C1 at WEIGHT 1 stops closing (scratch).
     */
    private val MAX_STILL_DIP_DB = 1.0

    private fun render(voice: KeelVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 36, velocity: Float = 1f): Snip =
        Keel.render(voice, harness + macros, midi, velocity)

    private fun f(v: Double, digits: Int = 2) = "%.${digits}f".format(v)
    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    private fun cents(hz: Double, want: Double) = 1200.0 * ln(hz / want) / ln(2.0)
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    private fun rootCents(snip: Snip, midi: Int, from: Float = .35f, window: Float = .25f): Double? {
        val estimate = Pitch.detect(snip, fromSec = from, windowSec = window) ?: return null
        return cents(estimate.hz.toDouble(), Keys.midiHz(midi).toDouble())
    }

    /**
     * McLeod's normalised square-difference pitch, as the harness reads it (first local maximum over 0.8 of the best,
     * parabolic refinement, 25 Hz floor). [Pitch.detect] is the house reader and is used wherever the pitch is steady;
     * it has a 40 Hz floor and returns null on a gliding window, which the SLIDE probe needs to read.
     */
    private fun nsdfHz(x: FloatArray, fromSeconds: Double, seconds: Double, loHz: Double = 25.0, hiHz: Double = 1000.0): Double {
        val first = (fromSeconds * rate).toInt()
        val n = minOf((seconds * rate).toInt(), x.size - first)
        val seg = DoubleArray(n) { x[first + it].toDouble() }
        val mean = seg.average()
        for (i in seg.indices) seg[i] -= mean
        val minLag = (rate / hiHz).toInt()
        val maxLag = minOf((rate / loHz).toInt(), n - 2)
        val cumulative = DoubleArray(n + 1)
        for (i in 0 until n) cumulative[i + 1] = cumulative[i] + seg[i] * seg[i]
        val nsdf = DoubleArray(maxLag + 1)
        for (tau in minLag..maxLag) {
            var acf = 0.0
            for (i in 0 until n - tau) acf += seg[i] * seg[i + tau]
            nsdf[tau] = 2 * acf / max(cumulative[n - tau] + cumulative[n] - cumulative[tau], 1e-12)
        }
        val best = (minLag..maxLag).maxOf { nsdf[it] }
        var lag = (minLag + 1 until maxLag).firstOrNull { nsdf[it] > nsdf[it - 1] && nsdf[it] >= nsdf[it + 1] && nsdf[it] >= 0.8 * best }
            ?: (minLag..maxLag).maxBy { nsdf[it] }
        val curve = nsdf[lag - 1] - 2 * nsdf[lag] + nsdf[lag + 1]
        val refined = lag + if (abs(curve) > 1e-12) 0.5 * (nsdf[lag - 1] - nsdf[lag + 1]) / curve else 0.0
        return rate / refined
    }

    // ---- measurement -----------------------------------------------------------------------------------------

    /** scipy.signal.welch's density estimate (Hann, 50% overlap, constant detrend), so the numbers match the harness. */
    private class Welch(val power: DoubleArray, val binHz: Double) {
        fun share(loHz: Double, hiHz: Double): Double {
            var part = 0.0
            for (i in power.indices) if (i * binHz >= loHz && i * binHz < hiHz) part += power[i]
            return part / power.sum().coerceAtLeast(1e-30)
        }
    }

    private fun welch(x: FloatArray, size: Int, sampleRate: Int = rate, from: Int = 0, to: Int = x.size): Welch {
        var n = size
        while (n > to - from) n /= 2
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val windowEnergy = window.sumOf { it * it }
        val power = DoubleArray(n / 2 + 1)
        var segments = 0
        var start = from
        while (start + n <= to) {
            var mean = 0.0
            for (i in 0 until n) mean += x[start + i]
            mean /= n
            val re = FloatArray(n) { ((x[start + it] - mean) * window[it]).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (k in 0..n / 2) {
                val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                power[k] += p * (if (k == 0 || k == n / 2) 1.0 else 2.0) / (sampleRate * windowEnergy)
            }
            segments++
            start += n / 2
        }
        require(segments > 0) { "no whole segment of $n in $from..$to" }
        for (k in power.indices) power[k] /= segments
        return Welch(power, sampleRate.toDouble() / n)
    }

    private fun welchDb(x: FloatArray) = welch(x, 4096).power.map { 10 * log10(max(it, 1e-14)) }

    /** The harness's macro_activity: log-spectral distance in dB RMS between two levelled renders. */
    private fun distanceDb(a: Snip, b: Snip): Double {
        val n = minOf(a.samples.size, b.samples.size)
        val la = welchDb(a.samples.copyOf(n))
        val lb = welchDb(b.samples.copyOf(n))
        return sqrt(la.indices.sumOf { (la[it] - lb[it]).let { d -> d * d } } / la.size)
    }

    private fun blockRms(x: FloatArray, block: Int = rate / 100): DoubleArray =
        DoubleArray(x.size / block) { b -> sqrt((0 until block).sumOf { x[b * block + it].toDouble().let { v -> v * v } } / block) }

    /** Later block RMS over the attack's: at or under 1 means the note never grows after its first 100 ms. */
    private fun laterOverAttack(x: FloatArray): Double {
        val env = blockRms(x)
        return env.drop(10).max() / env.take(10).max()
    }

    private fun decaySeconds(x: FloatArray): Double {
        val env = blockRms(x)
        val top = env.max()
        val last = env.indices.last { 20 * log10(max(env[it], 1e-9) / top) > -60 }
        return (last + 1) * 0.01
    }

    private fun centroid(x: FloatArray, fromSeconds: Double, seconds: Double): Double {
        val from = (fromSeconds * rate).toInt()
        val w = welch(x, 8192, from = from, to = minOf(x.size, from + (seconds * rate).toInt()))
        var num = 0.0
        var den = 0.0
        for (i in w.power.indices) { num += w.power[i] * i * w.binHz; den += w.power[i] }
        return num / den
    }

    /** Spectral peaks above 0.95 Nyquist louder than -80 dB under the loudest bin: the SHOAL test. */
    private fun partialsAboveEdge(x: FloatArray): Int {
        val w = welch(x, 8192)
        val top = w.power.max()
        return w.power.indices.count { it * w.binHz >= 0.95 * rate / 2 && 10 * log10(max(w.power[it], 1e-20) / top) > -80 }
    }

    // ---- the probe table -------------------------------------------------------------------------------------

    @Test
    fun `renders are byte-identical and independent of macro order, every voice, with and without touch`() {
        for (voice in voices) for (velocity in listOf(1f, .4f)) {
            val m = Keel.defaults(voice) + mapOf("GRIT" to .7f, "SLIDE" to .2f, "HOLD" to .3f)
            val a = Keel.render(voice, m, 36, velocity).samples
            val b = Keel.render(voice, m.entries.reversed().associate { it.key to it.value }, 36, velocity).samples
            assertContentEquals(a, b, "$voice velocity $velocity")
        }
    }

    @Test
    fun `finite, bounded before levelTo, output peak 0 01 to 1, no DC`() {
        var rawLow = Float.MAX_VALUE
        var rawHigh = 0f
        var peakLow = Float.MAX_VALUE
        var peakHigh = 0f
        var dcWorst = 0.0
        for (voice in voices) for (midi in listOf(30, 36, 42)) {
            val probe = Keel.probe(voice, harness, midi)
            assertTrue(probe.raw.all { it.isFinite() }, "$voice $midi raw is finite")
            // The spec's bar of 2 holds for the harness macro set (WEIGHT .4); the corners below are held to Keel.RAW_PEAK_BOUND.
            val raw = peak(probe.raw)
            assertTrue(raw < 2f, "$voice $midi raw internal peak $raw is over 2")
            rawLow = minOf(rawLow, raw); rawHigh = maxOf(rawHigh, raw)
            val out = render(voice, midi = midi)
            assertTrue(out.samples.all { it.isFinite() })
            val p = out.peak()
            assertTrue(p in .01f..1f, "$voice $midi output peak $p")
            peakLow = minOf(peakLow, p); peakHigh = maxOf(peakHigh, p)
            val dc = abs(out.samples.sumOf { it.toDouble() } / out.samples.size)
            assertTrue(dc < .005, "$voice $midi DC $dc")
            dcWorst = max(dcWorst, dc)
        }
        println("KEEL finite raw internal peak ${f(rawLow.toDouble(), 3)}..${f(rawHigh.toDouble(), 3)}; output peak ${f(peakLow.toDouble(), 3)}..${f(peakHigh.toDouble(), 3)}; DC worst ${"%.1e".format(dcWorst)}")
        // The worst corner for headroom: everything up, the sustain stage at its top.
        for (voice in voices) {
            val corner = mapOf("WEIGHT" to 1f, "GRIT" to 1f, "SETTLE" to 1f, "HOLD" to 1f, "LENGTH" to 1f)
            val raw = peak(Keel.probe(voice, corner, 36).raw)
            println("KEEL finite $voice worst corner (WEIGHT, GRIT, SETTLE, HOLD, LENGTH all 1): raw internal peak ${f(raw.toDouble(), 3)}, bound ${Keel.RAW_PEAK_BOUND}")
            assertTrue(raw < Keel.RAW_PEAK_BOUND, "$voice corner raw peak $raw")
        }
        // The worst corner a sweep of every 0 or 1 setting of SETTLE, GRIT, SLIDE, LENGTH and HOLD at WEIGHT 1 found
        // (three notes, two touches, both voices): a fast settle, held, driven, short, sliding from above, on the lowest note.
        for (voice in voices) {
            val corner = mapOf("WEIGHT" to 1f, "GRIT" to 1f, "SETTLE" to 0f, "SLIDE" to 1f, "HOLD" to 1f, "LENGTH" to 0f)
            val raw = peak(Keel.probe(voice, corner, 24, .5f).raw)
            println("KEEL finite $voice sweep-worst corner (SETTLE 0, GRIT 1, SLIDE 1, LENGTH 0, HOLD 1, WEIGHT 1, MIDI 24, touch .5): raw internal peak ${f(raw.toDouble(), 3)}, bound ${Keel.RAW_PEAK_BOUND}")
            assertTrue(raw < Keel.RAW_PEAK_BOUND, "$voice sweep-worst corner raw peak $raw")
            assertTrue(Keel.render(voice, corner, 24, .5f).samples.all { it.isFinite() }, "$voice sweep-worst corner renders")
        }
    }

    @Test
    fun `root pitch at WEIGHT 0 is within 50 cents early and late for every voice at MIDI 30, 36 and 42`() {
        for (voice in voices) for (midi in listOf(30, 36, 42)) {
            val snip = render(voice, mapOf("WEIGHT" to 0f), midi)
            // BELLY's detuned bite changes the short-lag correlation as it fades.
            // Read the house detector while the body is strong, then independently
            // check the late tail with overlap-normalized NSDF rather than dropping it.
            val estimate = requireNotNull(Pitch.detect(snip, .15f, .25f)) { "$voice $midi has no early pitch" }
            val c = cents(estimate.hz.toDouble(), Keys.midiHz(midi).toDouble())
            println("KEEL root $voice midi $midi WEIGHT 0: ${f(c, 1)} cents (${f(estimate.hz.toDouble())} Hz, confidence ${f(estimate.confidence.toDouble())})")
            assertTrue(abs(c) < 50.0, "$voice midi $midi reads ${f(c, 1)} cents from the root")
            assertTrue(estimate.confidence >= .3f, "$voice midi $midi confidence ${estimate.confidence}")
            val late = cents(nsdfHz(snip.samples, .35, .20), Keys.midiHz(midi).toDouble())
            println("KEEL late root $voice midi $midi WEIGHT 0: ${f(late, 1)} cents (NSDF)")
            assertTrue(abs(late) < 50.0, "$voice midi $midi late tail reads ${f(late, 1)} cents from the root")
        }
        // The default WEIGHT .4 at MIDI 36 is the harness's own row.
        for (voice in voices) println("KEEL root $voice midi 36 WEIGHT .4: ${f(rootCents(render(voice), 36) ?: Double.NaN, 1)} cents")
        // The body must not move the root at any WEIGHT (the body test below asserts it at 1 on three notes).
        for (voice in voices) {
            val row = listOf(0f, .4f, .7f, 1f).joinToString(" ") { w ->
                "W${fmt(w)} ${rootCents(render(voice, mapOf("WEIGHT" to w), 42), 42)?.let { f(it, 0) } ?: "none"}"
            }
            println("KEEL root with the body $voice midi 42, cents: $row")
        }
    }

    @Test
    fun `a note never grows after its attack, and the check can fail`() {
        for (voice in voices) for (midi in listOf(30, 36, 42)) {
            val ratio = laterOverAttack(render(voice, midi = midi).samples)
            println("KEEL never-grows $voice midi $midi: later/attack ${f(ratio, 3)}")
            assertTrue(ratio <= 1.0, "$voice midi $midi grows after its attack: $ratio")
        }
        // Proof that the measure is not tautological: the same note played backward grows.
        val reversed = render(KeelVoice.REDUCE).samples.reversedArray()
        assertTrue(laterOverAttack(reversed) > 1.0, "a reversed note must read as growing")
        // The sustain stage is new in Kotlin: report its top without asserting the harness's bar.
        for (voice in voices) {
            val held = laterOverAttack(render(voice, mapOf("HOLD" to TOP_ONE_SHOT_HOLD)).samples)
            println("KEEL never-grows $voice HOLD at the sustain top ($TOP_ONE_SHOT_HOLD): later/attack ${f(held, 3)}")
        }
    }

    @Test
    fun `no partial above 95 percent of Nyquist in the output, and a low alias proxy before the band limit at the worst corner`() {
        // The harness's own nine renders: three notes, the shared macro set, nothing above 0.95 Nyquist.
        for (voice in voices) for (midi in listOf(30, 36, 42)) {
            val count = partialsAboveEdge(render(voice, midi = midi).samples)
            assertEquals(0, count, "$voice midi $midi leaves partials above 0.95 Nyquist")
        }
        println("KEEL alias both voices at MIDI 30, 36, 42, harness macros: 0 partials above 0.95 Nyquist")
        // The worst corner: the slowest settle (brightest longest), full drive, the top note. The harness's top note
        // was MIDI 48 (TUNE 1 on a root of 36); the register runs to MIDI 55, where REDUCE is over the bar. That is
        // disclosed and printed, not asserted: a drive of 24 makes a near-square wave and 4x oversampling is the
        // house's whole answer to it.
        for (voice in voices) {
            val corner = harness + mapOf("GRIT" to 1f, "SETTLE" to 1f)
            for (midi in listOf(48, 55)) {
                val raw = Keel.probe(voice, corner, midi).raw
                val proxy = welch(raw, 8192, Dsp.RATE * Dsp.OVERSAMPLE).share(rate / 2.0, Double.MAX_VALUE)
                println("KEEL alias proxy $voice GRIT 1 SETTLE 1 midi $midi: ${"%.1e".format(proxy)} of the raw energy above the output Nyquist")
                if (midi == 48) assertTrue(proxy < 1e-3, "$voice midi $midi: ${"%.2e".format(proxy)} of the raw energy sits above the output Nyquist")
            }
            // Disclosed, not asserted: a drive of 24 makes a near-square wave, whose own harmonics reach the band edge.
            println("KEEL alias $voice GRIT 1 SETTLE 1 midi 55 output: ${partialsAboveEdge(Keel.render(voice, corner, 55).samples)} bins over -80 dB re the loudest within 0.95..1.0 Nyquist")
        }
    }

    // ---- WEIGHT, the body of section 9 -------------------------------------------------------------------------

    /**
     * scipy.signal.butter(4, [lo, hi], "band", fs=rate, output="sos"): four biquads, 8 poles, unity gain at the
     * band's warped centre. The harness reads its bands with this filter, causal over the whole clip, so the
     * Kotlin numbers sit beside section 9's on the same meter.
     */
    private class BandPass(val loHz: Double, val hiHz: Double, val sampleRate: Int) {
        private val b = DoubleArray(3)
        private val a = Array(4) { DoubleArray(3) }
        private val gain: Double

        init {
            fun warp(hz: Double) = 4.0 * tan(PI * hz / sampleRate)
            val w1 = warp(loHz)
            val w2 = warp(hiHz)
            val bw = w2 - w1
            val w0 = sqrt(w1 * w2)
            var section = 0
            for (m in 0 until 4) {
                // The Butterworth prototype's pole in the upper half plane, moved to the band: two analog poles, one per side.
                val angle = PI * (2 * m + 4 + 1) / 8.0
                val pr = cos(angle) * bw / 2
                val pi = sin(angle) * bw / 2
                // sqrt(p^2 - w0^2) for the complex p.
                val dr = pr * pr - pi * pi - w0 * w0
                val di = 2 * pr * pi
                val mag = hypot(dr, di)
                val sr = sqrt((mag + dr) / 2)
                val si = (if (di < 0) -1.0 else 1.0) * sqrt((mag - dr) / 2)
                for (sign in listOf(1.0, -1.0)) {
                    val poleRe = pr + sign * sr
                    val poleIm = pi + sign * si
                    if (poleIm <= 0) continue
                    // Bilinear transform with the harness's convention: z = (4 + s) / (4 - s).
                    val denRe = (4 - poleRe) * (4 - poleRe) + poleIm * poleIm
                    val zr = ((4 + poleRe) * (4 - poleRe) - poleIm * poleIm) / denRe
                    val zi = (poleIm * (4 - poleRe) + (4 + poleRe) * poleIm) / denRe
                    a[section][0] = 1.0
                    a[section][1] = -2 * zr
                    a[section][2] = zr * zr + zi * zi
                    section++
                }
            }
            check(section == 4) { "the band-pass needs four conjugate pairs, found $section" }
            b[0] = 1.0; b[1] = 0.0; b[2] = -1.0
            gain = 1.0 / (0 until 4).fold(1.0) { acc, i -> acc * magnitude(i, 2 * atan(w0 / 4.0)) }
        }

        private fun magnitude(section: Int, omega: Double): Double {
            val nr = b[0] + b[1] * cos(omega) + b[2] * cos(2 * omega)
            val ni = -(b[1] * sin(omega) + b[2] * sin(2 * omega))
            val dr = a[section][0] + a[section][1] * cos(omega) + a[section][2] * cos(2 * omega)
            val di = -(a[section][1] * sin(omega) + a[section][2] * sin(2 * omega))
            return hypot(nr, ni) / hypot(dr, di)
        }

        /** The response at [hz], in dB. */
        fun gainDb(hz: Double): Double = 20 * log10(gain * (0 until 4).fold(1.0) { acc, i -> acc * magnitude(i, 2 * PI * hz / sampleRate) })

        fun filter(x: FloatArray): DoubleArray {
            var y = DoubleArray(x.size) { x[it].toDouble() * gain }
            for (section in 0 until 4) {
                val out = DoubleArray(y.size)
                var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
                for (i in y.indices) {
                    val v = b[0] * y[i] + b[1] * x1 + b[2] * x2 - a[section][1] * y1 - a[section][2] * y2
                    x2 = x1; x1 = y[i]; y2 = y1; y1 = v
                    out[i] = v
                }
                y = out
            }
            return y
        }
    }

    /** dB of the mean square of [x] in a band, over a window of the clip (the harness's `band_db`). */
    private fun bandDb(x: FloatArray, loHz: Double, hiHz: Double, fromSeconds: Double, toSeconds: Double): Double {
        val y = BandPass(loHz, hiHz, rate).filter(x)
        val from = (fromSeconds * rate).toInt()
        val to = minOf(y.size, (toSeconds * rate).toInt())
        var sum = 0.0
        for (i in from until to) sum += y[i] * y[i]
        return 10 * log10(sum / (to - from) + 1e-20)
    }

    @Test
    fun `the band meter reads minus 3 dB at the band edges, 0 dB at the centre, and filters like its own response`() {
        for ((lo, hi) in listOf(60.0 to 120.0, 120.0 to 400.0, 400.0 to 4000.0)) {
            val band = BandPass(lo, hi, rate)
            assertEquals(-3.0103, band.gainDb(lo), .02, "$lo..$hi at its lower edge")
            assertEquals(-3.0103, band.gainDb(hi), .02, "$lo..$hi at its upper edge")
            val centre = sqrt(lo * hi)
            assertEquals(0.0, band.gainDb(centre), .05, "$lo..$hi at the warped centre is near unity")
            assertTrue(band.gainDb(lo / 4) < -40 && band.gainDb(hi * 3) < -30, "$lo..$hi rejects two octaves out")
            // The recursion agrees with the response: a sine at the centre keeps its level.
            val tone = FloatArray(rate * 2) { (sin(2 * PI * centre * it / rate)).toFloat() }
            val y = band.filter(tone)
            val rms = sqrt((rate until y.size).sumOf { y[it] * y[it] } / (y.size - rate))
            assertEquals(1 / sqrt(2.0), rms, .01 / 1.4142, "$lo..$hi passes its centre at unity")
        }
    }

    /**
     * The same band read by a brick-wall FFT meter (Welch bins), which has no filter skirt. It is the top band's bar:
     * the bar asks whether WEIGHT adds anything above 400 Hz, and on UPRIGHT, whose late top band sits 25 dB under
     * its body, the Butterworth band-pass's skirt (-17 dB at 262 Hz) passes the boosted 4th overtone and reads
     * +1.8 dB where nothing above 400 Hz has risen (the brick-wall reading there is -3.0 dB, the levelling).
     */
    private fun bandDbBrick(x: FloatArray, loHz: Double, hiHz: Double, fromSeconds: Double, toSeconds: Double): Double {
        val w = welch(x, 8192, from = (fromSeconds * rate).toInt(), to = minOf(x.size, (toSeconds * rate).toInt()))
        var sum = 0.0
        for (i in w.power.indices) if (i * w.binHz >= loHz && i * w.binHz < hiHz) sum += w.power[i]
        return 10 * log10(sum + 1e-20)
    }

    @Test
    fun `WEIGHT fills in the body, 120 to 400 Hz rises by 3 dB, 400 to 4000 Hz does not, and the root stays the root`() {
        val bands = listOf(60.0 to 120.0, 120.0 to 400.0, 400.0 to 4000.0)
        val windows = listOf("early" to (.10 to .45), "late" to (.45 to .95))
        val notes = listOf(Triple(KeelVoice.UPRIGHT, 36, "C2"), Triple(KeelVoice.REDUCE, 42, "F#2"), Triple(KeelVoice.REDUCE, 30, "F#1"))
        // Every note is measured and printed before any bar is judged, so one miss does not hide the others.
        val misses = ArrayList<String>()
        for ((voice, midi, name) in notes) {
            val clips = listOf(0f, .5f, 1f).map { w -> w to render(voice, mapOf("LENGTH" to .8f, "WEIGHT" to w), midi) }
            val late = HashMap<Pair<Float, Int>, Double>()
            val topBrick = HashMap<Float, Double>()
            for ((w, snip) in clips) {
                val cells = ArrayList<String>()
                for ((label, span) in windows) for ((index, band) in bands.withIndex()) {
                    val change = bandDb(snip.samples, band.first, band.second, span.first, span.second) -
                        bandDb(clips.first().second.samples, band.first, band.second, span.first, span.second)
                    if (label == "late") late[w to index] = change
                    cells.add("$label ${band.first.toInt()}-${band.second.toInt()} ${"%+.1f".format(change)}")
                }
                val estimate = Pitch.detect(snip, .45f, .25f)
                val cents = estimate?.let { cents(it.hz.toDouble(), Keys.midiHz(midi).toDouble()) }
                val brick = bandDbBrick(snip.samples, 400.0, 4000.0, .45, .95) - bandDbBrick(clips.first().second.samples, 400.0, 4000.0, .45, .95)
                topBrick[w] = brick
                println("KEEL body $voice $name midi $midi WEIGHT ${fmt(w)}: ${cells.joinToString(", ")} dB re WEIGHT 0 (late 400-4000 by the brick-wall FFT meter, the top band's bar, ${"%+.1f".format(brick)}); peak ${f(snip.peak().toDouble(), 3)}; " +
                    "late pitch ${cents?.let { f(it, 1) } ?: "none"} cents (confidence ${estimate?.let { f(it.confidence.toDouble()) } ?: "none"})")
            }
            val body = late.getValue(.5f to 1)
            val full = late.getValue(1f to 1)
            if (!(body > 0 && full > body)) misses.add("$voice $name: the 120 to 400 Hz band does not rise monotonically (0, $body, $full)")
            if (full < 3.0) misses.add("$voice $name: WEIGHT 1 raises 120 to 400 Hz by only ${f(full)} dB")
            for (w in listOf(.5f, 1f)) {
                if (topBrick.getValue(w) > .5) misses.add("$voice $name: 400 to 4000 Hz rises ${f(topBrick.getValue(w))} dB at WEIGHT ${fmt(w)} (brick-wall meter)")
            }
            val estimate = Pitch.detect(clips.last().second, .45f, .25f)
            if (estimate == null) misses.add("$voice $name has no pitch at WEIGHT 1") else {
                val root = cents(estimate.hz.toDouble(), Keys.midiHz(midi).toDouble())
                if (abs(root) >= 50.0) misses.add("$voice $name reads ${f(root, 1)} cents from the root at WEIGHT 1")
                if (estimate.confidence < .3f) misses.add("$voice $name root confidence ${estimate.confidence} at WEIGHT 1")
            }
        }
        assertTrue(misses.isEmpty(), misses.joinToString("; "))
    }

    /** The magnitude and phase of [x] at [k] times the note, by a DFT over [whole] whole periods ending at [end]. */
    private fun partial(x: FloatArray, period: Int, whole: Int, end: Int, k: Int): Pair<Double, Double> {
        var re = 0.0
        var im = 0.0
        for (i in end - whole * period until end) {
            re += x[i] * cos(2 * PI * k * i / period)
            im -= x[i] * sin(2 * PI * k * i / period)
        }
        val scale = 2.0 / (whole * period)
        return hypot(re, im) * scale to kotlin.math.atan2(im, re)
    }

    @Test
    fun `WEIGHT 0 hands the source back untouched, and the body law holds on steady tones`() {
        val internal = Dsp.RATE * Dsp.OVERSAMPLE
        val hz = 63.0
        val period = 2800
        check(internal / hz == period.toDouble())
        val n = internal * 6 / 10
        val tr = Keel.track(hz, 0.0, n)
        val settled = DoubleArray(n)
        val sine = FloatArray(n) { (.5 * sin(2 * PI * tr.phase[it])).toFloat() }
        assertSame(sine, Keel.weightBody(sine, tr, settled, 0f), "WEIGHT 0 returns the source itself")

        // A bare fundamental, settled (w = WEIGHT): the fundamental rises by 9 dB and each missing overtone is floored
        // under the boosted fundamental by 3, 6 and 9 dB, in phase with the fundamental's multiple.
        val boost = 10.0.pow(9.0 / 20)
        val out = Keel.weightBody(sine, tr, settled, 1f)
        val (a1, p1) = partial(out, period, 20, n, 1)
        assertEquals(.5 * boost, a1, .5 * boost * 1e-3, "the fundamental rises by 9 dB")
        for ((k, floorDb) in listOf(2 to -3.0, 3 to -6.0, 4 to -9.0)) {
            val (ak, pk) = partial(out, period, 20, n, k)
            assertEquals(a1 * 10.0.pow(floorDb / 20), ak, a1 * 1e-3, "overtone $k floors at $floorDb dB under the boosted fundamental")
            var drift = (pk - k * p1) % (2 * PI)
            if (drift > PI) drift -= 2 * PI
            if (drift < -PI) drift += 2 * PI
            assertTrue(abs(drift) < 2e-3, "overtone $k lands ${drift} rad off the fundamental's multiple")
        }
        // At the attack (s = 1) the body is at half strength: w = WEIGHT / 2.
        val half = DoubleArray(n) { 1.0 }
        val attack = Keel.weightBody(sine, tr, half, 1f)
        assertEquals(.5 * 10.0.pow(4.5 / 20), partial(attack, period, 20, n, 1).first, 1e-3, "w = WEIGHT / 2 at the attack")

        // An overtone the note already has is raised in its own phase: nothing cancels, nothing is floored over it.
        val two = FloatArray(n) { (.5 * sin(2 * PI * tr.phase[it]) + .4 * sin(4 * PI * tr.phase[it] + 1.0)).toFloat() }
        val (before2, phase2) = partial(two, period, 20, n, 2)
        val raised = Keel.weightBody(two, tr, settled, 1f)
        val (after1, _) = partial(raised, period, 20, n, 1)
        val (after2, afterPhase2) = partial(raised, period, 20, n, 2)
        assertEquals(before2 * boost, after2, before2 * boost * 1e-3, "an existing overtone rises by the same 9 dB")
        // The fundamental-multiple nudge (0.02 |C1| against |C2|) may turn it by up to 0.025 rad before the sum.
        assertEquals(phase2, afterPhase2, .02, "and keeps its own phase")
        assertEquals(.5 * boost, after1, .5 * boost * 1e-3)
    }

    @Test
    fun `SETTLE moves the spectrum, its extremes differ and the note is darker at 0 3 s the faster it settles`() {
        for (voice in voices) {
            val d = distanceDb(render(voice, mapOf("SETTLE" to 0f)), render(voice, mapOf("SETTLE" to 1f)))
            println("KEEL settle $voice extremes: ${f(d)} dB")
            assertTrue(d >= 5.0, "$voice SETTLE extremes differ by only $d dB")
            val centroids = listOf(0f, .25f, .5f, .75f, 1f).map { centroid(render(voice, mapOf("SETTLE" to it, "WEIGHT" to 0f)).samples, .25, .1) }
            println("KEEL settle $voice centroid at 0.3 s for SETTLE 0 .25 .5 .75 1: ${centroids.joinToString(" ") { f(it, 0) }} Hz")
            // A larger SETTLE is a longer half-life (slower settle), so the note is brighter at 0.3 s.
            for (i in 1 until centroids.size) assertTrue(centroids[i] > centroids[i - 1], "$voice centroid does not rise with SETTLE: $centroids")
        }
    }

    @Test
    fun `GRIT moves the spectrum by at least 20 dB between its extremes`() {
        for (voice in voices) {
            val d = distanceDb(render(voice, mapOf("GRIT" to 0f)), render(voice, mapOf("GRIT" to 1f)))
            println("KEEL grit $voice extremes: ${f(d)} dB")
            assertTrue(d >= 20.0, "$voice GRIT extremes differ by only $d dB")
        }
    }

    @Test
    fun `every other macro changes the sound`() {
        for (voice in voices) {
            for (macro in listOf("WEIGHT", "SLIDE", "LENGTH", "HOLD")) {
                val d = distanceDb(render(voice, mapOf(macro to 0f)), render(voice, mapOf(macro to 1f)))
                println("KEEL activity $voice $macro extremes: ${f(d)} dB")
                assertTrue(d > .5, "$voice $macro is a dead control: $d dB")
            }
            val low = Keel.render(voice, harness, 30).samples
            val high = Keel.render(voice, harness + ("TUNE" to 1f), 30).samples
            assertTrue(Keel.midiFor(voice, 1f, 30) == 42 && !low.contentEquals(high), "$voice TUNE moves the note")
            // An unknown key changes nothing.
            assertContentEquals(render(voice).samples, Keel.render(voice, harness + ("NOPE" to 1f), 36).samples)
        }
    }

    @Test
    fun `SLIDE 0 starts under the root and lands on it, and SLIDE 1 starts over it`() {
        val root = Keys.midiHz(36).toDouble()
        val flat = render(KeelVoice.REDUCE, mapOf("WEIGHT" to 0f)).samples
        val below = render(KeelVoice.REDUCE, mapOf("WEIGHT" to 0f, "SLIDE" to 0f)).samples
        val above = render(KeelVoice.REDUCE, mapOf("WEIGHT" to 0f, "SLIDE" to 1f)).samples
        // The harness's windows: early is 10..70 ms, late is 0.4 s on.
        val results = listOf("SLIDE 0" to below, "SLIDE 1" to above, "SLIDE .5" to flat).map { (label, x) ->
            val early = cents(nsdfHz(x, .01, .06), root)
            val late = cents(nsdfHz(x, .4, .25), root)
            println("KEEL slide REDUCE midi 36 $label: early ${f(early, 1)} cents, late ${f(late, 1)} cents")
            early to late
        }
        assertTrue(results[0].first <= -300.0, "SLIDE 0 starts only ${results[0].first} cents under the root")
        assertTrue(results[1].first >= 300.0, "SLIDE 1 starts only ${results[1].first} cents over the root")
        assertTrue(abs(results[2].first) <= 5.0, "SLIDE .5 moves ${results[2].first} cents")
        for ((i, r) in results.withIndex()) assertTrue(abs(r.second) <= 5.0, "slide $i lands ${r.second} cents from the root")
        // The house reader agrees where it can read at all: the late window.
        assertTrue(abs(requireNotNull(rootCents(render(KeelVoice.REDUCE, mapOf("WEIGHT" to 0f, "SLIDE" to 0f)), 36, .4f, .15f))) <= 5.0)
        // SLIDE reaches the string through the retuned loop (WEIGHT's body follows it too: risk 11 below).
        val string = render(KeelVoice.UPRIGHT, mapOf("WEIGHT" to 0f, "SLIDE" to 0f)).samples
        val stringEarly = cents(nsdfHz(string, .01, .06), root)
        val stringLate = cents(nsdfHz(string, .4, .25), root)
        println("KEEL slide UPRIGHT midi 36 SLIDE 0: early ${f(stringEarly, 1)} cents, late ${f(stringLate, 1)} cents")
        assertTrue(stringEarly <= -300.0, "the string did not follow the slide: $stringEarly")
        assertTrue(abs(stringLate) <= 5.0, "the string lands $stringLate cents from the root")
        // Disclosed corner, not asserted: the body's 190 Hz mode sits 45 cents over UPRIGHT's second harmonic at MIDI 42.
        val corner = render(KeelVoice.UPRIGHT, mapOf("WEIGHT" to 0f, "SLIDE" to 1f), midi = 42).samples
        println("KEEL slide UPRIGHT midi 42 SLIDE 1 late window: ${f(cents(nsdfHz(corner, .4, .25), Keys.midiHz(42).toDouble()), 1)} cents (an octave over the root reads as the body's 190 Hz mode carrying the second harmonic)")
    }

    @Test
    fun `the lowest note with full WEIGHT keeps its energy over 20 Hz and still reads as C1`() {
        for (voice in voices) {
            val snip = render(voice, mapOf("WEIGHT" to 1f), midi = 24)
            val under = welch(snip.samples, 16384).share(0.0, 20.0)
            val hz = FineTuning.measuredHz(snip, Keys.midiHz(24), fromSec = .35f, bodySeconds = .25f)
            val c = cents(hz, Keys.midiHz(24).toDouble())
            println("KEEL C1 $voice WEIGHT 1: energy under 20 Hz ${f(100 * under, 3)}%, strongest peak near C1 ${f(hz)} Hz (${f(c, 1)} cents), peak ${f(snip.peak().toDouble(), 3)}")
            assertTrue(under < .01, "$voice puts $under of its energy under 20 Hz at C1")
            assertTrue(abs(c) < 50.0, "$voice C1 reads ${f(c, 1)} cents off")
        }
    }

    @Test
    fun `a one-shot is under 1 5 s, filed TONAL to the frame, and tracks LENGTH`() {
        for (voice in voices) for (length in listOf(0f, .5f, 1f)) for (hold in listOf(0f, .5f, TOP_ONE_SHOT_HOLD)) {
            val macros = harness + mapOf("LENGTH" to length, "HOLD" to hold)
            val snip = Keel.render(voice, macros, 36)
            assertTrue(snip.frameCount.toFloat() / rate < Keel.LOOP_MIN_SECONDS, "$voice LENGTH $length HOLD $hold runs ${snip.frameCount / rate.toDouble()} s")
            assertEquals(DrumClass.TONAL, Keel.drumClassFor(voice, macros))
            assertEquals(Keel.oneShotFrames(Keel.settled(voice, macros)), snip.frameCount, "$voice LENGTH $length HOLD $hold: the class is judged on another length than the render's")
            val cls = Classifier.classify(snip).drumClass
            assertTrue(cls !in setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM),
                "$voice LENGTH $length HOLD $hold: the classifier hears a drum ($cls)")
        }
        val worst = Keel.render(KeelVoice.REDUCE, harness + mapOf("LENGTH" to 1f, "HOLD" to TOP_ONE_SHOT_HOLD), 36)
        println("KEEL length worst corner LENGTH 1 HOLD $TOP_ONE_SHOT_HOLD: ${f(worst.frameCount / rate.toDouble(), 3)} s, ${worst.frameCount} frames")
        for (voice in voices) {
            val decays = listOf(.25f, .5f, .75f).map { decaySeconds(render(voice, mapOf("LENGTH" to it)).samples) }
            val lengths = listOf(.25f, .5f, .75f).map { Keel.lengthSeconds(mapOf("LENGTH" to it)).toDouble() }
            println("KEEL length $voice decay to -60 dB at LENGTH .25 .5 .75: ${decays.joinToString(" ") { f(it) }} s against L ${lengths.joinToString(" ") { f(it) }} s")
            assertTrue(decays[0] < decays[1] && decays[1] < decays[2], "$voice decay does not follow LENGTH: $decays")
        }
    }

    // ---- the sonic risks ---------------------------------------------------------------------------------------

    @Test
    fun `risk 2 the body does not steal the pitch, and the first prototype's body would`() {
        for (midi in listOf(30, 36, 42)) {
            val snip = render(KeelVoice.UPRIGHT, mapOf("WEIGHT" to 0f), midi)
            val late = requireNotNull(rootCents(snip, midi, .35f, .25f)) { "midi $midi has no late pitch" }
            println("KEEL risk2 UPRIGHT midi $midi WEIGHT 0 late window (0.35 s on): ${f(late, 1)} cents")
            assertTrue(abs(late) < 50.0, "midi $midi: the late window reads ${f(late, 1)} cents from the root")
        }
        // The guard, removed. The first prototype's body had T60 0.22 s at gain 0.9 and a 0.3 mix.
        fun lateCents(midi: Int, options: Keel.Options): Double? {
            val probe = Keel.probe(KeelVoice.UPRIGHT, harness + ("WEIGHT" to 0f), midi, options = options)
            return rootCents(levelled(probe.conditioned), midi, .35f, .25f)
        }
        val first = Keel.Options(bodyT60Scale = 2f, bodyLowGain = .9f, bodyMix = .3f)
        val variants = listOf("first prototype body" to first, "T60 0.22 alone" to Keel.Options(bodyT60Scale = 2f),
            "mix 0.3 alone" to Keel.Options(bodyMix = .3f), "gain 0.9 alone" to Keel.Options(bodyLowGain = .9f))
        for ((label, options) in variants) {
            println("KEEL risk2 $label: " + listOf(30, 36, 42).joinToString(" ") { midi -> "midi $midi ${lateCents(midi, options)?.let { f(it, 0) } ?: "none"} cents" })
        }
        for (midi in listOf(30, 36, 42)) {
            val broken = lateCents(midi, first)
            assertTrue(broken == null || abs(broken) > 50.0, "midi $midi: the first prototype's body must fail the probe (${broken}), or the probe guards nothing")
        }
    }

    @Test
    fun `risk 11 WEIGHT during a slide stays finite, bounded and on the root in the late window`() {
        // MIDI 36, not 42: UPRIGHT at MIDI 42 with SLIDE 1 reads an octave high at WEIGHT 0 already (As built, findings).
        for (voice in voices) for (slide in listOf(0f, 1f)) {
            val macros = harness + mapOf("LENGTH" to .8f, "WEIGHT" to 1f, "SLIDE" to slide)
            val probe = Keel.probe(voice, macros, 36)
            assertTrue(probe.raw.all { it.isFinite() }, "$voice SLIDE $slide: raw is not finite")
            val raw = peak(probe.raw)
            assertTrue(raw < Keel.RAW_PEAK_BOUND, "$voice SLIDE $slide: raw internal peak $raw is over ${Keel.RAW_PEAK_BOUND}")
            val snip = Keel.render(voice, macros, 36)
            assertTrue(snip.samples.all { it.isFinite() } && snip.peak() in .01f..1f, "$voice SLIDE $slide: output peak ${snip.peak()}")
            val estimate = requireNotNull(Pitch.detect(snip, .45f, .25f)) { "$voice SLIDE $slide has no late pitch at WEIGHT 1" }
            val c = cents(estimate.hz.toDouble(), Keys.midiHz(36).toDouble())
            println("KEEL risk11 $voice SLIDE ${fmt(slide)} WEIGHT 1 midi 36: raw peak ${f(raw.toDouble(), 3)}, output peak ${f(snip.peak().toDouble(), 3)}, late pitch ${f(c, 1)} cents (confidence ${f(estimate.confidence.toDouble())})")
            assertTrue(abs(c) < 50.0, "$voice SLIDE $slide reads ${f(c, 1)} cents from the root in the late window at WEIGHT 1")
        }
    }

    private fun levelled(samples: FloatArray): Snip {
        val out = samples.copyOf()
        Dsp.levelTo(out, rate, Dsp.MELODIC_LOUDNESS_TARGET)
        return Snip(out, channels = 1, sampleRate = rate)
    }

    // ---- HOLD, velocity, the patch ----------------------------------------------------------------------------

    @Test
    fun `HOLD is the sustain stage under the loop region, and the loop region renders the held loop, filed LOOP to the frame`() {
        assertTrue(Keel.isLoop(1f) && Keel.isLoop(Keel.LOOP_HOLD_AT) && !Keel.isLoop(TOP_ONE_SHOT_HOLD), "isLoop names the loop region")
        for (voice in voices) {
            assertEquals(1f, Keel.settled(voice, mapOf("HOLD" to 1f)).getValue("HOLD"), "settled no longer clamps HOLD")
            val plan = Keel.planLoop(Keys.midiHz(36).toDouble())
            val first = Keel.render(voice, Keel.defaults(voice) + ("HOLD" to Keel.LOOP_HOLD_AT), 36)
            for (hold in listOf(Keel.LOOP_HOLD_AT, .9f, 1f)) {
                val loop = Keel.render(voice, Keel.defaults(voice) + ("HOLD" to hold), 36)
                assertContentEquals(first.samples, loop.samples, "$voice: every HOLD in the loop region is the same loop")
                assertEquals(plan.frames, loop.frameCount, "$voice HOLD $hold: the loop is the planned whole periods")
                assertEquals(DrumClass.LOOP, Keel.drumClassFor(voice, mapOf("HOLD" to hold)))
                assertTrue(loop.frameCount.toFloat() / rate > Keel.LOOP_MIN_SECONDS, "$voice: a loop must run past the classifier's line")
                assertEquals(DrumClass.LOOP, Classifier.classify(loop).drumClass, "$voice HOLD $hold: the classifier agrees")
            }
            assertEquals(DrumClass.TONAL, Keel.drumClassFor(voice, mapOf("HOLD" to TOP_ONE_SHOT_HOLD)))
            // The sustain stage is audible at every setting, not a dead control below its top.
            var last = -1
            for (hold in listOf(0f, .2f, .4f, .6f, .8f)) {
                val snip = Keel.render(voice, Keel.defaults(voice) + ("HOLD" to hold), 36)
                assertTrue(snip.frameCount > last, "$voice HOLD $hold is not longer than the one before")
                last = snip.frameCount
            }
            val short = Keel.render(voice, Keel.defaults(voice), 36).samples
            val held = Keel.render(voice, Keel.defaults(voice) + ("HOLD" to .5f), 36).samples
            val at = (.45 * rate).toInt()
            assertTrue(blockRms(held, rate / 20)[(.45 * 20).toInt()] > 2 * blockRms(short, rate / 20)[(.45 * 20).toInt()],
                "$voice: at 0.45 s the held note should still be near full level (held ${held.size - at} frames later)")
            println("KEEL hold $voice loop ${first.frameCount} frames (${plan.periods} periods); longest one-shot ${f(Keel.render(voice, harness + mapOf("HOLD" to TOP_ONE_SHOT_HOLD, "LENGTH" to 1f), 36).frameCount / rate.toDouble(), 3)} s")
        }
    }

    @Test
    fun `the worst HOLD and LENGTH corner is cut by the render cap, and its level at the cut is disclosed`() {
        for (voice in voices) {
            val snip = Keel.render(voice, harness + mapOf("HOLD" to TOP_ONE_SHOT_HOLD, "LENGTH" to 1f), 36)
            val env = blockRms(snip.samples)
            val top = env.max()
            val atCut = 20 * log10(env[env.size - 2] / top)
            println("KEEL cap $voice HOLD $TOP_ONE_SHOT_HOLD LENGTH 1: ${f(snip.frameCount / rate.toDouble(), 3)} s, level at the cut ${f(atCut, 1)} dB under the peak block")
            assertTrue(snip.frameCount / rate.toDouble() <= Keel.MAX_SECONDS + 1e-3)
        }
    }

    @Test
    fun `touch changes the spectrum before the level, and the output target follows v to the 0 35`() {
        fun share(x: FloatArray) = welch(x, 4096, from = (.02 * rate).toInt(), to = (.17 * rate).toInt()).share(1000.0, rate / 2.0)
        for (voice in voices) {
            val soft = Keel.render(voice, harness + ("WEIGHT" to 0f), 36, .3f)
            val hard = Keel.render(voice, harness + ("WEIGHT" to 0f), 36, 1f)
            val ratio = Loudness.of(soft) / Loudness.of(hard)
            println("KEEL velocity $voice share over 1 kHz soft ${"%.4f".format(share(soft.samples))}, hard ${"%.4f".format(share(hard.samples))}; centroid soft ${f(centroid(soft.samples, .02, .15), 0)} Hz, hard ${f(centroid(hard.samples, .02, .15), 0)} Hz; loudness ratio ${f(ratio.toDouble(), 3)} (.3^.35 = ${f(Math.pow(.3, .35), 3)})")
            assertEquals(Math.pow(.3, .35), ratio.toDouble(), .05, "$voice: the output target follows v^0.35")
            assertNotEquals(share(soft.samples), share(hard.samples), "$voice: touch must move the spectrum")
        }
        // REDUCE: a softer touch is darker, as the index law intends.
        assertTrue(share(Keel.render(KeelVoice.REDUCE, harness, 36, .3f).samples) < share(Keel.render(KeelVoice.REDUCE, harness, 36, 1f).samples))
        // UPRIGHT is disclosed rather than asserted: the pick low-pass is mostly absorbed by the 2.2 kHz loop, and
        // GRIT's touch-scaled drive adds low odd harmonics faster than it adds highs, so the softer touch reads brighter.
        // Velocity 0 is silence, not a crash.
        assertTrue(Keel.render(KeelVoice.REDUCE, harness, 36, 0f).samples.all { it == 0f })
    }

    @Test
    fun `the register, TUNE, scramble and the settled map stay in bounds`() {
        for (voice in voices) {
            assertEquals(Keel.DEFAULT_MIDI, Keel.midiFor(voice, .5f))
            assertEquals(Keel.MIDI_MIN, Keel.midiFor(voice, 0f, 24))
            assertEquals(Keel.MIDI_MAX, Keel.midiFor(voice, 1f, 55))
            assertEquals(Keel.MIDI_MAX, Keel.midiFor(voice, 1f, 54))
            assertEquals(Keel.DEFAULT_MIDI, Keel.midiFor(voice, Float.NaN))
            assertEquals(Keys.midiHz(48), Keel.frequencyFor(voice, 1f))
            val settled = Keel.settled(voice, mapOf("WEIGHT" to Float.NaN, "GRIT" to 7f, "NOPE" to 1f, "HOLD" to 1f))
            assertEquals(Keel.defaults(voice).keys, settled.keys, "unknown keys dropped")
            assertEquals(Keel.defaults(voice).getValue("WEIGHT"), settled.getValue("WEIGHT"), "a non-finite macro falls back to its default")
            assertEquals(1f, settled.getValue("GRIT"))
            assertEquals(1f, settled.getValue("HOLD"))
            val random = Random(7)
            repeat(40) {
                val rolled = Keel.scramble(voice, random, if (it % 2 == 0) .35f else 1f)
                assertEquals(Keel.defaults(voice).keys, rolled.keys)
                assertTrue(rolled.values.all { v -> v in 0f..1f })
                assertTrue(rolled.getValue("HOLD") <= Keel.ROLL_HOLD_TOP && !Keel.isLoop(rolled.getValue("HOLD")), "a roll never reaches the loop region")
            }
            // Every corner of the register renders: the lowest note sliding from an octave below, the highest sliding from above.
            for ((midi, slide) in listOf(24 to 0f, 55 to 1f, 24 to 1f, 55 to 0f)) {
                val snip = Keel.render(voice, Keel.defaults(voice) + ("SLIDE" to slide), midi)
                assertTrue(snip.samples.all { it.isFinite() } && snip.peak() > .01f, "$voice midi $midi SLIDE $slide")
            }
        }
    }

    @Test
    fun `render time stays in the tens of milliseconds a knob turn can afford`() {
        for (voice in voices) for (weight in listOf(0f, 1f)) {
            val macros = harness + mapOf("LENGTH" to .8f, "WEIGHT" to weight)
            repeat(2) { Keel.render(voice, macros, 36) }
            val t0 = System.nanoTime()
            repeat(5) { Keel.render(voice, macros, 36) }
            val ms = (System.nanoTime() - t0) / 5e6
            println("KEEL render time $voice WEIGHT ${fmt(weight)}, LENGTH .8: ${f(ms, 0)} ms per one-shot (warm, this machine; not asserted)")
        }
    }

    @Test
    fun `the patch round trips through JSON and refuses what the engine cannot play`() {
        val patch = KeelPatch("Round", KeelVoice.UPRIGHT, mapOf("WEIGHT" to .7f, "SLIDE" to .2f), midi = 30, velocity = .6f)
        val back = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertContentEquals(patch.render().samples, back.render().samples)
        assertEquals("KEEL", patch.engine)
        val fields = LinkedHashMap(patch.toJsonValue().entries)
        // A saved sound without a model, midi or velocity is the first model at the default root, struck hard.
        for (key in listOf("model", "midi", "velocity")) fields.remove(key)
        val bare = Patches.fromJsonValue(JsonValue.Obj(fields)) as KeelPatch
        assertEquals(Keel.MODEL_VERSION, bare.model)
        assertEquals(Keel.DEFAULT_MIDI, bare.midi)
        assertEquals(1f, bare.velocity)
        fields["model"] = JsonValue.Num(2.0)
        assertFailsWith<JsonException> { Patches.fromJsonValue(JsonValue.Obj(fields)) }
        assertFailsWith<IllegalArgumentException> { KeelPatch("Low", KeelVoice.REDUCE, emptyMap(), midi = 23) }
        assertFailsWith<IllegalArgumentException> { KeelPatch("High", KeelVoice.REDUCE, emptyMap(), midi = 56) }
        assertFailsWith<IllegalArgumentException> { KeelPatch("Loud", KeelVoice.REDUCE, emptyMap(), velocity = 1.1f) }
        assertFailsWith<IllegalArgumentException> { KeelPatch("Odd", KeelVoice.REDUCE, mapOf("BELLY" to .5f)) }
        assertTrue(KeelVoice.entries.any { it.name == "BELLY" }, "BELLY is a voice; the owner heard it as its own sound on 2026-10-31")
        // The velocity door (Velocity.kt) reaches this engine's own touch law.
        assertContentEquals(patch.copy(velocity = .3f).render().samples, Velocity.atVelocity(patch, .3f).samples)
    }
    // ---- R2: the held loop and the key zones ------------------------------------------------------------------

    private val heldSets = linkedMapOf("defaults" to emptyMap<String, Float>(), "WEIGHT 1" to mapOf("WEIGHT" to 1f),
        "SETTLE 0" to mapOf("SETTLE" to 0f), "SETTLE 1" to mapOf("SETTLE" to 1f))

    private fun houseDb(x: FloatArray) = 20.0 * log10(Loudness.of(Snip(x.copyOf(), channels = 1, sampleRate = rate)).toDouble())

    /** A zone's loop against its first [Keel.KEY_LEVEL_SECONDS], in dB (house loudness): the sustain's depth under the attack. */
    private fun sustainGap(x: FloatArray, loopStart: Int) =
        houseDb(x.copyOf((Keel.KEY_LEVEL_SECONDS * rate).toInt())) - houseDb(x.copyOfRange(loopStart, x.size))

    /** The power-weighted mean frequency of an exact loop's DFT lines within 50 cents of [hz] (whole periods: no window). */
    private fun loopPitchHz(loop: FloatArray, hz: Double): Double {
        val n = loop.size
        var weighted = 0.0
        var total = 0.0
        for (k in (hz * 2.0.pow(-50 / 1200.0) * n / rate).toInt()..(hz * 2.0.pow(50 / 1200.0) * n / rate).toInt() + 1) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val a = 2 * PI * ((k.toLong() * i) % n) / n
                re += loop[i] * cos(a)
                im -= loop[i] * sin(a)
            }
            weighted += (re * re + im * im) * k
            total += re * re + im * im
        }
        return weighted / total * rate / n
    }

    /** The house-weighted level (`x - 0.7 lowpass120(x)`, 200 ms windows every 50 ms), in dB, of the windows starting in [from] until [to]. */
    private fun contour(x: FloatArray, from: Int = 0, to: Int = x.size): DoubleArray {
        val a = 1.0 - Math.exp(-2.0 * PI * 120.0 / rate)
        var low = 0.0
        val w = DoubleArray(x.size) { i -> low += a * (x[i] - low); x[i] - 0.7 * low }
        val window = (.2 * rate).toInt()
        val levels = ArrayList<Double>()
        var start = from
        while (start < to && start + window <= w.size) {
            var e = 0.0
            for (i in start until start + window) e += w[i] * w[i]
            levels += 10 * log10(e / window + 1e-20)
            start += rate / 20
        }
        return levels.toDoubleArray()
    }

    /** The largest rise, in dB, of the house-weighted level (200 ms windows every 50 ms) above its running minimum after [from] s. */
    private fun riseAfter(x: FloatArray, from: Double): Double {
        val a = 1.0 - Math.exp(-2.0 * PI * 120.0 / rate)
        var low = 0.0
        val w = DoubleArray(x.size) { i -> low += a * (x[i] - low); x[i] - 0.7 * low }
        val window = (.2 * rate).toInt()
        var least = Double.MAX_VALUE
        var rise = 0.0
        var start = (from * rate).toInt()
        while (start + window <= w.size) {
            var e = 0.0
            for (i in start until start + window) e += w[i] * w[i]
            val level = 10 * log10(e / window + 1e-20)
            least = minOf(least, level)
            rise = max(rise, level - least)
            start += rate / 20
        }
        return rise
    }

    @Test
    fun `the held loop closes on the cycle that really preceded it, every zone at the defaults and three at each corner`() {
        for (voice in voices) {
            var worst = 0.0
            var longest = 0.0
            for ((name, set) in heldSets) for (midi in if (name == "defaults") Keys.keelPadMidis() else listOf(24, 36, 48)) {
                val held = Keel.renderHeld(voice, Keel.defaults(voice) + set, midi)
                assertTrue(held.seam < Keys.MAX_SEAM_ERROR, "$voice $name MIDI $midi: the loop does not close (seam ${held.seam})")
                assertEquals(Keel.planLoop(Keys.midiHz(midi).toDouble()).frames, held.audio.size - held.loopStart, "$voice $name MIDI $midi: one planned loop")
                worst = max(worst, held.seam)
                longest = max(longest, held.audio.size.toDouble() / rate)
            }
            println("KEEL held $voice worst seam ${"%.1e".format(worst)} over ${Keys.keelPadMidis().size + 9} renders; longest held note ${f(longest)} s")
        }
    }

    /**
     * UPRIGHT's noise bow left its loop where the string's own tuning put it (-3.0 to +3.7 cents); the locked bow sets
     * the pitch, and alone it reads on the planned line. The hair is noise on lines 0.33 Hz apart, and the ones within
     * 50 cents of the note pull this power-weighted reading off it (most at C1, where 50 cents is 0.9 Hz).
     */
    @Test
    fun `the loop holds the note, REDUCE and UPRIGHT's locked bow on its planned line, UPRIGHT's hair within a cent`() {
        for (voice in voices) for (midi in listOf(24, 36, 48)) {
            val want = Keys.midiHz(midi).toDouble()
            val c = cents(loopPitchHz(Keel.renderLoop(voice, Keel.defaults(voice), midi), want), want)
            if (voice == KeelVoice.REDUCE) {
                println("KEEL held pitch $voice MIDI $midi: ${f(c, 2)} cents (whole-loop DFT)")
                assertTrue(abs(c) < .05, "$voice MIDI $midi loop reads ${f(c, 2)} cents")
                continue
            }
            val still = Keel.renderHeld(voice, Keel.defaults(voice), midi, options = Keel.Options(bowHair = 0f))
            val locked = cents(loopPitchHz(still.audio.copyOfRange(still.loopStart, still.audio.size), want), want)
            println("KEEL held pitch $voice MIDI $midi: ${f(c, 2)} cents, without the hair ${f(locked, 2)} (whole-loop DFT)")
            assertTrue(abs(locked) < .05, "$voice MIDI $midi locked bow reads ${f(locked, 2)} cents")
            assertTrue(abs(c) < 1.0, "$voice MIDI $midi loop reads ${f(c, 2)} cents")
        }
    }

    @Test
    fun `a key zone is its attack and then the HOLD loop, the same loop up to one gain`() {
        for (voice in voices) for (midi in listOf(24, 48)) {
            val m = Keel.defaults(voice)
            val loop = Keel.render(voice, m + ("HOLD" to 1f), midi).samples
            assertContentEquals(Keel.renderLoop(voice, m, midi), loop, "$voice: HOLD's top renders renderLoop")
            val zone = Keys.keelPad(voice, m, midi)
            val start = zone.loopStartFrame.toInt()
            val tail = zone.snip.samples.copyOfRange(start, zone.snip.frameCount)
            assertEquals(loop.size, tail.size, "$voice MIDI $midi: the zone ends one loop after its marker")
            var dot = 0.0
            var energy = 0.0
            for (i in loop.indices) { dot += tail[i].toDouble() * loop[i]; energy += loop[i].toDouble() * loop[i] }
            val g = dot / energy
            var worst = 0.0
            for (i in loop.indices) worst = max(worst, abs(tail[i] - g * loop[i]))
            println("KEEL zone $voice MIDI $midi: marker at ${f(start / rate.toDouble())} s, loop ${loop.size} frames, worst difference from the HOLD loop ${"%.1e".format(worst / peak(loop))} of its peak")
            assertTrue(worst / peak(loop) < 1e-4, "$voice MIDI $midi: the zone's loop is not HOLD's loop")
        }
    }

    @Test
    fun `a key zone's attack sits on the house target, and only UPRIGHT's sustain is lifted, to its gap under it`() {
        for (voice in voices) for (set in listOf("defaults", "WEIGHT 1")) for (midi in listOf(24, 36, 42, 48)) {
            val m = Keel.defaults(voice) + heldSets.getValue(set)
            val zone = Keys.keelPad(voice, m, midi)
            val x = zone.snip.samples
            val start = zone.loopStartFrame.toInt()
            val attack = houseDb(x.copyOf((Keel.KEY_LEVEL_SECONDS * rate).toInt())) - 20 * log10(Dsp.MELODIC_LOUDNESS_TARGET.toDouble())
            val limited = peak(x) >= .989f
            assertTrue(abs(attack) < .5 || (limited && attack < 0), "$voice $set MIDI $midi: attack ${f(attack, 1)} dB off the target")
            val held = Keel.renderHeld(voice, m, midi)
            val before = sustainGap(held.audio, held.loopStart)
            val after = sustainGap(x, start)
            println("KEEL zone level $voice $set MIDI $midi: attack ${f(attack, 1)} dB re target${if (limited) " (limited)" else ""}; sustain ${f(before, 1)} dB under the attack as rendered, ${f(after, 1)} in the zone")
            if (voice == KeelVoice.UPRIGHT && before > Keel.KEY_SUSTAIN_GAP_DB) {
                assertEquals(Keel.KEY_SUSTAIN_GAP_DB, after, .5, "$voice $set MIDI $midi: the sustain is lifted to its gap")
            } else {
                assertEquals(before, after, .1, "$voice $set MIDI $midi: a sustain is never lowered, and REDUCE's is never lifted")
            }
        }
    }

    @Test
    fun `the lift raises UPRIGHT's sustain without a swell after the attack`() {
        for (set in listOf("defaults", "WEIGHT 1")) for (midi in listOf(24, 27, 30)) {
            val m = Keel.defaults(KeelVoice.UPRIGHT) + heldSets.getValue(set)
            val zone = Keys.keelPad(KeelVoice.UPRIGHT, m, midi).snip.samples
            val held = Keel.renderHeld(KeelVoice.UPRIGHT, m, midi).audio
            val lifted = riseAfter(zone, Keel.KEY_LEVEL_SECONDS)
            val asRendered = riseAfter(held, Keel.KEY_LEVEL_SECONDS)
            println("KEEL zone swell UPRIGHT $set MIDI $midi: largest rise after ${Keel.KEY_LEVEL_SECONDS} s ${f(lifted, 1)} dB lifted, ${f(asRendered, 1)} dB as rendered (the bow's own hair)")
            assertTrue(lifted < asRendered + 1.0, "UPRIGHT $set MIDI $midi: the lift adds a swell (${f(lifted, 1)} against ${f(asRendered, 1)} dB)")
        }
    }

    /** The loop's swing and the press's dip under it, in dB, for a held note whose loop starts at [start]. */
    private fun pump(x: FloatArray, start: Int): Pair<Double, Double> {
        val loop = x.copyOfRange(start, x.size)
        val held = contour(FloatArray(3 * loop.size) { loop[it % loop.size] })
        return (held.max() - held.min()) to held.average() - contour(x, (Keel.KEY_LEVEL_SECONDS * rate).toInt(), start).min()
    }

    /**
     * The owner heard UPRIGHT's held keys pump (keys page, 2026-10-26): the tiled bow noise beat inside the string's
     * resonance, 2.6 dB at C1 and 12.5 at A2, repeating with the loop; and the press dipped 5 to 8 dB under the loop
     * before the loop began, then swelled into it. The locked bow is checked twice: as shipped, against what the
     * owner heard as no pump, and without its hair, where the locked part alone must hold still and start in phase.
     */
    @Test
    fun `a held UPRIGHT key does not pump, the loop holds its level and the press settles into it without a dip`() {
        val faults = ArrayList<String>()
        for (set in listOf("defaults", "WEIGHT 1")) for (midi in Keys.keelPadMidis()) {
            val m = Keel.defaults(KeelVoice.UPRIGHT) + heldSets.getValue(set)
            val zone = Keys.keelPad(KeelVoice.UPRIGHT, m, midi)
            val (swing, dip) = pump(zone.snip.samples, zone.loopStartFrame.toInt())
            val still = Keel.renderKey(KeelVoice.UPRIGHT, m, midi, options = Keel.Options(bowHair = 0f))
            val (stillSwing, stillDip) = pump(still.audio, still.loopStart)
            println("KEEL pump UPRIGHT $set MIDI $midi: loop swing ${f(swing, 1)} dB, press dips ${f(dip, 1)} dB under the loop before it; " +
                "without the hair ${f(stillSwing, 1)} and ${f(stillDip, 1)} dB")
            if (swing > MAX_LOOP_SWING_DB) faults += "UPRIGHT $set MIDI $midi: the loop swings ${f(swing, 1)} dB"
            if (dip > MAX_PRESS_DIP_DB) faults += "UPRIGHT $set MIDI $midi: the press dips ${f(dip, 1)} dB under the loop"
            if (stillSwing > MAX_STILL_SWING_DB) faults += "UPRIGHT $set MIDI $midi: without the hair the loop swings ${f(stillSwing, 1)} dB"
            if (stillDip > MAX_STILL_DIP_DB) faults += "UPRIGHT $set MIDI $midi: without the hair the press dips ${f(stillDip, 1)} dB"
        }
        assertTrue(faults.isEmpty(), faults.joinToString("; "))
    }

    @Test
    fun `held renders are reproducible, stop when cancelled, stay under the raw bound, and the zones are nine minor thirds`() {
        assertEquals((24..48 step 3).toList(), Keys.keelPadMidis())
        for (voice in voices) {
            val m = Keel.defaults(voice)
            assertContentEquals(Keel.renderHeld(voice, m, 36).audio, Keel.renderHeld(voice, m, 36).audio, "$voice held render")
            assertFailsWith<java.util.concurrent.CancellationException> { Keys.keelPad(voice, m, 36) { true } }
            assertFailsWith<IllegalArgumentException> { Keys.keelPad(voice, m, Keel.MIDI_MIN - 1) }
            assertFailsWith<IllegalArgumentException> { Keys.keelPad(voice, m, Keel.MIDI_MAX + 1) }
            for ((label, corner, midi, touch) in listOf(
                Quad("every macro at 1", mapOf("WEIGHT" to 1f, "GRIT" to 1f, "SETTLE" to 1f, "HOLD" to 1f, "LENGTH" to 1f), 36, 1f),
                Quad("the one-shot sweep's worst", mapOf("WEIGHT" to 1f, "GRIT" to 1f, "SETTLE" to 0f, "SLIDE" to 1f, "HOLD" to 1f, "LENGTH" to 0f), 24, .5f),
            )) {
                val raw = Keel.synthesize(voice, corner, midi, touch, held = true).raw
                assertTrue(raw.all { it.isFinite() }, "$voice held $label")
                println("KEEL held finite $voice $label, MIDI $midi, touch $touch: raw internal peak ${f(peak(raw).toDouble(), 3)}, bound ${Keel.RAW_PEAK_BOUND}")
                assertTrue(peak(raw) < Keel.RAW_PEAK_BOUND, "$voice held $label raw peak ${peak(raw)}")
            }
        }
    }

    private data class Quad(val label: String, val macros: Map<String, Float>, val midi: Int, val touch: Float)
}
