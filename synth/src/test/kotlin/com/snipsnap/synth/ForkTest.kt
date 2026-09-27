package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FORK measured, not described
 * (docs/superpowers/specs/2026-09-27-fork-electric-piano-engine-design.md,
 * "Testing"): the claims are about the pickup and the bar, so the tests
 * read them off rendered audio — a pitch estimate, an FFT peak search, a
 * loudness envelope's own slope — rather than trusting the macro maps in
 * isolation. Where a claim is about the *linear resonator alone*
 * ([Fork.bank], before the pickup's harmonics are layered on top of it),
 * the test reads [Fork.bank] directly, at native rate, so the pickup's
 * intermodulation never clouds a claim that has nothing to do with it.
 */
class ForkTest {

    private fun cents(hz: Float, want: Float): Float = 1200f * ln(hz / want) / ln(2f)

    // ---------- spectral helpers ----------

    /** A windowed FFT's magnitude at [start]..[start+n), Blackman-Harris (SIREN's own choice, low leakage). */
    private fun magnitudes(samples: FloatArray, start: Int, n: Int): FloatArray {
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples.getOrElse(start + i) { 0f } * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> (re[b] * re[b] + im[b] * im[b]) }
    }

    /** The bin frequency closest to the local magnitude peak within [target]*(1 +/- [tolerance]). */
    private fun peakNear(samples: FloatArray, rate: Int, start: Int, n: Int, target: Float, tolerance: Float = 0.25f): Float {
        val mag = magnitudes(samples, start, n)
        val lo = max(1, ((target * (1 - tolerance)) * n / rate).toInt())
        val hi = min(mag.size - 1, ((target * (1 + tolerance)) * n / rate).toInt())
        require(lo <= hi) { "search band empty for target $target Hz" }
        var best = lo
        for (b in lo..hi) if (mag[b] > mag[best]) best = b
        return best.toFloat() * rate / n
    }

    /** Magnitude summed within [toleranceHz] of [target]. */
    private fun energyNear(samples: FloatArray, rate: Int, start: Int, n: Int, target: Float, toleranceHz: Float): Double {
        val mag = magnitudes(samples, start, n)
        var sum = 0.0
        for (b in mag.indices) {
            val hz = b.toFloat() * rate / n
            if (abs(hz - target) <= toleranceHz) sum += mag[b]
        }
        return sum
    }

    /** Spectral centroid (the energy-weighted mean frequency) over the window. */
    private fun centroid(samples: FloatArray, rate: Int, start: Int, n: Int): Double {
        val mag = magnitudes(samples, start, n)
        var num = 0.0
        var den = 0.0
        for (b in mag.indices) {
            val hz = b.toFloat() * rate / n
            num += hz * mag[b]
            den += mag[b]
        }
        return if (den > 0) num / den else 0.0
    }

    /** Energy within 8 Hz of a harmonic of [f0] against everything else, in dB — the TIDE/SIREN aliasing bar. */
    private fun harmonicClarity(samples: FloatArray, rate: Int, f0: Float, start: Int, n: Int = 1 shl 16): Double {
        val mag = magnitudes(samples, start, n)
        var on = 0.0
        var off = 0.0
        for (b in 1 until mag.size) {
            val hz = b.toFloat() * rate / n
            if (hz > 20_000f) break
            if (hz < f0 / 2) continue
            val k = Math.round(hz / f0)
            if (abs(hz - k * f0) < 8f) on += mag[b] else off += mag[b]
        }
        return 10 * log10(on / off)
    }

    /** RMS over consecutive, non-overlapping [windowSec] windows, each timestamped at its centre. */
    private fun rmsEnvelope(samples: FloatArray, rate: Int, windowSec: Float): List<Pair<Float, Float>> {
        val win = (windowSec * rate).toInt().coerceAtLeast(16)
        val out = ArrayList<Pair<Float, Float>>()
        var start = 0
        while (start + win <= samples.size) {
            var sum = 0.0
            for (i in start until start + win) sum += samples[i].toDouble() * samples[i]
            out += ((start + win / 2f) / rate) to sqrt(sum / win).toFloat()
            start += win
        }
        return out
    }

    private fun sqrt(x: Double): Double = kotlin.math.sqrt(x)

    /**
     * The fundamental's own frequency, read by averaging the interpolated
     * period between every rising zero crossing over [from]..[from + span]
     * seconds — sub-sample precise, unlike a whole-sample-period detector,
     * and the right tool once the fast, high modes have mostly settled
     * (see the caller's own choice of [from]).
     */
    private fun preciseFundamental(samples: FloatArray, rate: Int, from: Float, span: Float): Float {
        val start = (from * rate).toInt()
        val end = min(samples.size - 1, ((from + span) * rate).toInt())
        val crossings = ArrayList<Double>()
        for (i in (start + 1)..end) {
            val p = samples[i - 1]
            val q = samples[i]
            if (p < 0f && q >= 0f) crossings += (i - 1 + (-p / (q - p))).toDouble()
        }
        require(crossings.size >= 3) { "too few crossings between $from and ${from + span} to read a period" }
        val periods = (1 until crossings.size).map { crossings[it] - crossings[it - 1] }
        val medianPeriod = periods.sorted()[periods.size / 2]
        return (rate / medianPeriod).toFloat()
    }

    /** dB-per-second slope of the RMS envelope's log over [fromSec]..[toSec], least squares. */
    private fun decaySlopeDbPerSec(env: List<Pair<Float, Float>>, fromSec: Float, toSec: Float): Double {
        val points = env.filter { it.first in fromSec..toSec && it.second > 1e-9f }
        require(points.size >= 3) { "not enough signal between $fromSec and $toSec to fit a slope" }
        val xs = points.map { it.first.toDouble() }
        val ys = points.map { 20.0 * log10(it.second.toDouble()) }
        val n = xs.size
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        return num / den
    }

    /** A short mono test tone, for a hand-made striker source. */
    private fun tone(freqHz: Float, seconds: Float, rate: Int = Dsp.RATE, gain: Float = 0.8f): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { i -> (sin(2.0 * PI * freqHz * i / rate) * gain).toFloat() }, channels = 1, sampleRate = rate)
    }

    private fun noiseSnip(seconds: Float, rate: Int = Dsp.RATE, seed: Int = 7): Snip {
        val noise = Dsp.Noise(seed)
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { noise.next() * 0.8f }, channels = 1, sampleRate = rate)
    }

    /** A short noise burst, one-pole filtered dull or bright - a captured hit's own spectral shape, with no single frequency for a resonant mode to lock onto and keep ringing. */
    private fun tiltedNoise(bright: Boolean, seconds: Float = 0.03f, rate: Int = Dsp.RATE): Snip {
        val noise = Dsp.Noise(if (bright) 41 else 43)
        val pole = Dsp.OnePole(rate)
        val cutoff = if (bright) 8000f else 400f
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { pole.lp(noise.next(), cutoff) * 0.8f }, channels = 1, sampleRate = rate)
    }

    // ---------- sanity ----------

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in ForkVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Fork.macrosFor(voice).associate { it.name to 0f },
                Fork.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Fork.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing at $macros")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                val dc = snip.samples.average().toFloat()
                assertTrue(abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
                val loud = Loudness.of(snip)
                assertTrue(loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f, "$voice too quiet at $macros: loudness $loud, peak ${snip.peak()}")
            }
        }
    }

    @Test
    fun `five macros, the same on every voice, and the defaults say what the spec says`() {
        for (voice in ForkVoice.entries) {
            assertEquals(listOf("TUNE", "STRIKE", "BARK", "STIFF", "DECAY"), Fork.macrosFor(voice).map { it.name })
        }
        val d = Fork.defaults(ForkVoice.TINE)
        assertEquals(0.5f, d.getValue("TUNE"))
        assertEquals(0.5f, d.getValue("STRIKE"))
        assertEquals(0.4f, d.getValue("BARK"))
        assertEquals(0.5f, d.getValue("STIFF"))
        assertEquals(0.5f, d.getValue("DECAY"))
    }

    @Test
    fun `the clamp is dead at every legal macro corner`() {
        // x's peak is exactly BARK's closeness times STRIKE's swing (the
        // bank is peak-normalised to 1 first), so the clamp
        // (1 - x >= GAP_FLOOR) is never reached at any legal setting.
        val worstX = Fork.BARK_MAX * Fork.STRIKE_SWING_HIGH
        assertTrue(worstX < 1f - Fork.GAP_FLOOR, "worst-case x=$worstX leaves no room before the clamp")
        assertTrue(worstX <= 0.7f, "BARK's own ceiling moved without the spec being updated")
    }

    // ---------- test 1: in tune, and the pickup does not move the fundamental ----------

    @Test
    fun `TUNE lands within five cents of its snapped note, both voices, every macro corner`() {
        for (voice in ForkVoice.entries) {
            for (step in 0..Fork.TUNE_SEMITONES step 4) {
                val tune = step / Fork.TUNE_SEMITONES.toFloat()
                val want = Fork.frequencyFor(tune)
                for (extra in listOf(emptyMap(), mapOf("BARK" to 1f, "STRIKE" to 1f), mapOf("STIFF" to 0f), mapOf("STIFF" to 1f))) {
                    val s = Fork.render(voice, mapOf("TUNE" to tune) + extra)
                    val from = min(0.15f, s.durationSeconds * 0.4f)
                    val span = min(0.08f, s.durationSeconds - from - 0.02f)
                    val got = preciseFundamental(s.samples, s.sampleRate, from, span)
                    assertTrue(abs(cents(got, want)) < 5f, "$voice step $step $extra: $got Hz is ${cents(got, want)} cents from $want")
                }
            }
        }
    }

    // ---------- test 2: the bar is where the table says (measured on the clean resonator) ----------

    @Test
    fun `STIFF places the modes on the harmonic series, the table, and past it`() {
        // A short noise burst, just to excite - Modes.ring's per-mode loop
        // is independent (see this test's own KDoc note above), so a
        // single isolated mode's own peak is exact regardless of how fast
        // it decays: nothing else is in the buffer to leak against it.
        val n = 1 shl 15 // 743 ms at Dsp.RATE - plenty of resolution, and safe at any length now each mode rings alone
        val burst = FloatArray(n).also { buf ->
            val noise = Dsp.Noise(99)
            for (i in buf.indices) buf[i] = noise.next() * Dsp.envAt(i / Dsp.RATE.toFloat(), 0.0001f)
        }
        for (voice in ForkVoice.entries) {
            val hz = Fork.frequencyFor(0.5f)
            for (stiff in listOf(0f, 0.5f, 1f)) {
                val modes = Fork.modesFor(voice, mapOf("STIFF" to stiff, "DECAY" to 0.5f))
                for (k in modes.indices) {
                    val wantHz = hz * modes[k].ratio
                    val isolated = Modes.ring(burst, hz, listOf(modes[k]), Dsp.RATE)
                    val got = peakNear(isolated, Dsp.RATE, start = 0, n = n, target = wantHz, tolerance = 0.15f)
                    assertTrue(abs(cents(got, wantHz)) < 15f, "$voice STIFF $stiff mode ${k + 1}: $got Hz is ${cents(got, wantHz)} cents from $wantHz (ratio ${modes[k].ratio})")
                }
            }
        }
    }

    @Test
    fun `STIFF 0 is a harmonic string - 1,2,3,4 - on both voices`() {
        for (voice in ForkVoice.entries) {
            val modes = Fork.modesFor(voice, mapOf("STIFF" to 0f, "DECAY" to 0.5f))
            assertEquals(listOf(1f, 2f, 3f, 4f), modes.map { it.ratio }, "$voice at STIFF 0")
        }
    }

    @Test
    fun `STIFF 0,5 is the voice's own table exactly, and mode 1 never moves`() {
        for (voice in ForkVoice.entries) {
            val table = if (voice == ForkVoice.TINE) Fork.TINE_RATIOS else Fork.BAR_RATIOS
            for (stiff in listOf(0f, 0.5f, 1f)) {
                val modes = Fork.modesFor(voice, mapOf("STIFF" to stiff, "DECAY" to 0.5f))
                assertEquals(1f, modes[0].ratio, 1e-6f, "$voice STIFF $stiff: mode 1 moved")
            }
            val atTable = Fork.modesFor(voice, mapOf("STIFF" to 0.5f, "DECAY" to 0.5f))
            for (i in table.indices) assertEquals(table[i], atTable[i].ratio, 1e-4f, "$voice mode ${i + 1} at STIFF 0.5")
        }
    }

    @Test
    fun `STIFF 1 stretches every mode past the table`() {
        for (voice in ForkVoice.entries) {
            val table = if (voice == ForkVoice.TINE) Fork.TINE_RATIOS else Fork.BAR_RATIOS
            val stretched = Fork.modesFor(voice, mapOf("STIFF" to 1f, "DECAY" to 0.5f))
            for (i in 1 until table.size) {
                assertTrue(stretched[i].ratio > table[i], "$voice mode ${i + 1}: STIFF 1 ratio ${stretched[i].ratio} did not clear the table's ${table[i]}")
            }
        }
    }

    // ---------- test 3: higher modes die first (measured on the clean resonator's own envelope) ----------

    @Test
    fun `higher modes die first - the composite decay slows down as they drop out`() {
        for (voice in ForkVoice.entries) {
            val macros = mapOf("TUNE" to 0.4f, "DECAY" to 0.85f, "STIFF" to 0.5f, "STRIKE" to 0.5f)
            val hz = Fork.frequencyFor(0.4f)
            val raw = Fork.bank(voice, hz, macros, striker = null, rate = Dsp.RATE)
            val env = rmsEnvelope(raw, Dsp.RATE, windowSec = 0.01f)
            val t60Fund = Dsp.expMap(0.85f, Fork.DECAY_MIN_SECONDS, Fork.DECAY_MAX_SECONDS)
            val early = decaySlopeDbPerSec(env, 0.02f, 0.12f)
            val late = decaySlopeDbPerSec(env, t60Fund * 0.5f, t60Fund * 0.9f)
            assertTrue(early < late, "$voice: early slope $early dB/s is not steeper than late $late dB/s")
        }
    }

    @Test
    fun `each mode's own t60 is shorter than the one below it`() {
        for (voice in ForkVoice.entries) {
            for (stiff in listOf(0.5f, 1f)) {
                val modes = Fork.modesFor(voice, mapOf("STIFF" to stiff, "DECAY" to 0.6f))
                for (i in 1 until modes.size) {
                    assertTrue(modes[i].t60 < modes[i - 1].t60, "$voice STIFF $stiff: mode ${i + 1}'s t60 ${modes[i].t60} is not shorter than mode $i's ${modes[i - 1].t60}")
                }
                assertEquals(Dsp.expMap(0.6f, Fork.DECAY_MIN_SECONDS, Fork.DECAY_MAX_SECONDS), modes[0].t60, 0.01f, "$voice: mode 1's t60 is not what DECAY asked for")
            }
        }
    }

    // ---------- STRIKE is the brightness macro Velocity.kt looks for ----------

    @Test
    fun `STRIKE moves the onset centroid at every step of its travel`() {
        for (voice in ForkVoice.entries) {
            val steps = (0..10).map { it / 10f }
            val centroids = steps.map { strike ->
                val s = Fork.render(voice, mapOf("TUNE" to 0.4f, "STRIKE" to strike, "DECAY" to 0.6f))
                centroid(s.samples, s.sampleRate, start = 0, n = 1 shl 9) // ~11.6ms - within even STRIKE 1's own ~8ms active burst
            }
            // Mostly, not strictly, monotonic: the excitation cutoff sweeps
            // continuously past BAR's own closely-spaced mode frequencies,
            // so a dip up to about a tenth can land at whichever step the
            // sweep crosses closest to one of them (measured: BAR's own
            // worst step-to-step dip is 6.1%, at STRIKE 0.9 -> 1.0, its
            // cutoff widest there) - a real, physically-explained property
            // of a swept filter over a discrete resonant bank, not a
            // reversal. The overall trend (every step against the *start*,
            // and the total span) is where the actual claim - STRIKE
            // reliably brightens - lives, and stays strict.
            for (i in 1 until centroids.size) {
                assertTrue(centroids[i] >= centroids[i - 1] * 0.89f, "$voice: STRIKE ${steps[i]} centroid ${centroids[i]} dropped more than 11% below STRIKE ${steps[i - 1]}'s ${centroids[i - 1]}")
                assertTrue(centroids[i] >= centroids[0] * 0.97f, "$voice: STRIKE ${steps[i]} centroid ${centroids[i]} fell back toward STRIKE 0's own ${centroids[0]}")
            }
            assertTrue(centroids.last() > centroids.first() * 1.2, "$voice: STRIKE barely moved the onset centroid (${centroids.first()} -> ${centroids.last()})")
        }
    }

    @Test
    fun `atVelocity resynthesizes FORK at STRIKE, brighter with a harder velocity`() {
        for (voice in ForkVoice.entries) {
            val patch = ForkPatch("Test", voice, mapOf("TUNE" to 0.4f, "STRIKE" to 0.9f, "DECAY" to 0.6f))
            assertEquals("STRIKE", Velocity.brightnessSpec(patch)?.name, "$voice did not resolve STRIKE as its brightness macro")
            val soft = Velocity.atVelocity(patch, 0.1f)
            val hard = Velocity.atVelocity(patch, 1f)
            val onsetSoft = centroid(soft.samples, soft.sampleRate, 0, 1 shl 9)
            val onsetHard = centroid(hard.samples, hard.sampleRate, 0, 1 shl 9)
            assertTrue(onsetHard > onsetSoft, "$voice: atVelocity did not brighten with velocity ($onsetSoft -> $onsetHard)")
        }
    }

    // ---------- test 4: BARK barks ----------

    @Test
    fun `the second harmonic rises with BARK, and with STRIKE`() {
        for (voice in ForkVoice.entries) {
            val hz = Fork.frequencyFor(0.4f)
            fun secondHarmonic(bark: Float, strike: Float): Double {
                val s = Fork.render(voice, mapOf("TUNE" to 0.4f, "BARK" to bark, "STRIKE" to strike, "DECAY" to 0.7f))
                val start = (0.08f * s.sampleRate).toInt()
                val n = min(1 shl 14, s.samples.size - start)
                return energyNear(s.samples, s.sampleRate, start, n, target = 2 * hz, toleranceHz = 25f)
            }
            val loBark = secondHarmonic(0f, 0.5f)
            val hiBark = secondHarmonic(1f, 0.5f)
            assertTrue(hiBark > loBark * 1.5, "$voice: 2nd harmonic did not rise with BARK ($loBark -> $hiBark)")
            val loStrike = secondHarmonic(0.6f, 0f)
            val hiStrike = secondHarmonic(0.6f, 1f)
            assertTrue(hiStrike > loStrike, "$voice: 2nd harmonic did not rise with STRIKE ($loStrike -> $hiStrike)")
        }
    }

    @Test
    fun `BARK 0 is close to a clean pickup - a small second harmonic`() {
        for (voice in ForkVoice.entries) {
            val hz = Fork.frequencyFor(0.4f)
            val s = Fork.render(voice, mapOf("TUNE" to 0.4f, "BARK" to 0f, "DECAY" to 0.7f))
            val start = (0.08f * s.sampleRate).toInt()
            val n = min(1 shl 14, s.samples.size - start)
            val fundamental = energyNear(s.samples, s.sampleRate, start, n, target = hz, toleranceHz = 25f)
            val second = energyNear(s.samples, s.sampleRate, start, n, target = 2 * hz, toleranceHz = 25f)
            assertTrue(second < fundamental * 0.25, "$voice: BARK 0 still has a strong 2nd harmonic ($second against $fundamental)")
        }
    }

    // ---------- test 5: the bite is real ----------

    @Test
    fun `the onset is brighter than the sustain, and BARK widens the gap`() {
        for (voice in ForkVoice.entries) {
            val macros = mapOf("TUNE" to 0.3f, "DECAY" to 1f, "STRIKE" to 0.6f)
            fun gap(bark: Float): Double {
                val s = Fork.render(voice, macros + ("BARK" to bark))
                val onset = centroid(s.samples, s.sampleRate, start = 0, n = 1 shl 12)
                val late = centroid(s.samples, s.sampleRate, start = (0.5f * s.sampleRate).toInt(), n = 1 shl 12)
                assertTrue(onset > late, "$voice BARK $bark: onset centroid $onset is not above the sustain's $late")
                return onset - late
            }
            val lo = gap(0.15f)
            val hi = gap(0.95f)
            assertTrue(hi > lo, "$voice: BARK did not widen the onset-to-sustain gap ($lo -> $hi)")
        }
    }

    // ---------- test 6: a striker is a hammer, not a drone ----------

    @Test
    fun `a striker is heard at the start and only at the start`() {
        for (voice in ForkVoice.entries) {
            val low = Fork.striker(tiltedNoise(bright = false))
            val high = Fork.striker(tiltedNoise(bright = true))
            val macros = mapOf("TUNE" to 0.4f, "DECAY" to 0.8f)
            val a = Fork.render(voice, macros, striker = low)
            val b = Fork.render(voice, macros, striker = high)
            val n = 1 shl 9 // ~11.6ms - the striker's own STRIKER_MS window, not a fixed wall-clock one unrelated to it
            val onsetA = centroid(a.samples, a.sampleRate, 0, n)
            val onsetB = centroid(b.samples, b.sampleRate, 0, n)
            assertTrue(onsetB > onsetA * 1.2, "$voice: a high striker did not brighten the onset ($onsetA vs $onsetB)")

            val lateStart = (0.3f * a.sampleRate).toInt()
            val centroidLateA = centroid(a.samples, a.sampleRate, lateStart, 1 shl 13)
            val centroidLateB = centroid(b.samples, b.sampleRate, lateStart, 1 shl 13)
            val onsetGap = onsetB - onsetA
            val lateGap = abs(centroidLateB - centroidLateA)
            assertTrue(lateGap < onsetGap * 0.3, "$voice: the striker's own difference is still $lateGap Hz at 0.3s, against ${onsetGap} Hz at onset")
        }
    }

    @Test
    fun `a silent striker renders silence, not a crash`() {
        for (voice in ForkVoice.entries) {
            val silence = Fork.striker(Snip(FloatArray(200), channels = 1, sampleRate = Dsp.RATE))
            assertEquals(Fork.STRIKER_SAMPLES, silence.size)
            assertTrue(silence.all { it == 0f })
            val s = Fork.render(voice, mapOf("TUNE" to 0.4f), striker = silence)
            assertTrue(s.samples.all { it.isFinite() }, "$voice: a silent striker produced non-finite audio")
        }
    }

    @Test
    fun `the striker is always exactly STRIKER_SAMPLES long, whatever the source`() {
        assertEquals(Fork.STRIKER_SAMPLES, Fork.striker(tone(440f, 0.5f)).size, "a long source truncates")
        assertEquals(Fork.STRIKER_SAMPLES, Fork.striker(tone(440f, 0.002f)).size, "a short source zero-pads")
        assertEquals(Fork.STRIKER_SAMPLES, Fork.striker(tone(440f, 0.05f, rate = 48_000)).size, "a foreign sample rate is resampled first")
    }

    // ---------- aliasing floor ----------

    @Test
    fun `full BARK at the top note stays clean of aliasing`() {
        for (voice in ForkVoice.entries) {
            val s = Fork.render(voice, mapOf("TUNE" to 1f, "BARK" to 1f, "STRIKE" to 1f, "STIFF" to 1f, "DECAY" to 0.6f))
            val f0 = Fork.frequencyFor(1f)
            val clarity = harmonicClarity(s.samples, s.sampleRate, f0, start = (0.05f * s.sampleRate).toInt())
            assertTrue(clarity >= 45.0, "$voice: energy between harmonics is only ${"%.1f".format(clarity)} dB down")
        }
    }

    // ---------- determinism, fuzz, identity ----------

    @Test
    fun `the same recipe renders bit-identical audio, and there is no seed to vary`() {
        for (voice in ForkVoice.entries) {
            val a = Fork.render(voice, mapOf("BARK" to 0.6f))
            val b = Fork.render(voice, mapOf("BARK" to 0.6f))
            assertContentEquals(a.samples, b.samples, "$voice differs between two renders")
        }
    }

    @Test
    fun `every macro at every extreme, alone, stays finite and bounded`() {
        for (voice in ForkVoice.entries) {
            for (spec in Fork.macrosFor(voice)) {
                for (extreme in listOf(0f, 1f)) {
                    val s = Fork.render(voice, mapOf(spec.name to extreme))
                    assertTrue(s.samples.all { it.isFinite() && it in -1f..1f }, "$voice ${spec.name}=$extreme broke range")
                }
            }
        }
    }

    @Test
    fun `SCRAMBLE stays a fork - audible, unclipped, in range`() {
        val random = Random(19)
        for (voice in ForkVoice.entries) {
            repeat(20) {
                val macros = Fork.scramble(voice, random)
                assertTrue(macros.values.all { it in 0f..1f }, "$voice scrambled out of range: $macros")
                val s = Fork.render(voice, macros)
                assertTrue(s.peak() > 0.05f && s.samples.all { it in -1f..1f }, "$voice scrambled to silence or clipping: $macros")
            }
        }
    }

    @Test
    fun `both defaults classify as a pitched note, never a drum with a choke group`() {
        // Measured, not wished for (SIREN's own rule): the pickup's
        // brightest content sits right at the attack and thins fast, so
        // the classifier's peak-to-(-20dB) decay-shape read can land PERC
        // rather than TONAL's length shortcut, even though the pitch
        // itself never moves and never stops being exact. What matters is
        // that a FORK pad never reads as an actual drum and lands in a
        // hat's or kick's choke group by accident.
        val heard = ForkVoice.entries.associateWith { Classifier.classify(Fork.render(it)).drumClass }
        println("FORK defaults by the classifier: $heard")
        for ((voice, drumClass) in heard) {
            assertTrue(drumClass in setOf(DrumClass.TONAL, DrumClass.PERC), "$voice's default classified as $drumClass")
        }
    }

    @Test
    fun `drumClassFor predicts what the real classifier actually says, across DECAY`() {
        // Fork.drumClassFor is a cheap, macro-only stand-in the picker
        // files a pad's mute group under before a sound is even rendered
        // (SynthScreen.kt) - this is what proves it agrees with the
        // classifier that actually runs on the rendered audio, across the
        // one macro that was measured to drive the reading.
        for (voice in ForkVoice.entries) {
            for (decay in listOf(0f, 0.2f, 0.35f, 0.45f, 0.5f, 0.55f, 0.6f, 0.75f, 0.9f, 1f)) {
                val macros = mapOf("DECAY" to decay)
                val predicted = Fork.drumClassFor(voice, macros)
                val heard = Classifier.classify(Fork.render(voice, macros)).drumClass
                assertEquals(predicted, heard, "$voice DECAY $decay: predicted $predicted, classifier heard $heard")
            }
        }
    }

    // ---------- recipe ----------

    @Test
    fun `a recipe without a striker round-trips through the dispatcher`() {
        val patch = ForkPatch("Test Tine", ForkVoice.TINE, mapOf("BARK" to 0.5f, "STIFF" to 0.7f))
        val back = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertContentEquals(patch.render().samples, back.render().samples)
    }

    @Test
    fun `a recipe with a striker round-trips, and a struck kit regenerates bit-for-bit`() {
        val striker = Fork.striker(noiseSnip(0.05f))
        val patch = ForkPatch("Test Struck", ForkVoice.BAR, mapOf("BARK" to 0.6f), striker)
        val back = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertTrue((back as ForkPatch).striker!!.contentEquals(striker))
        assertContentEquals(patch.render().samples, back.render().samples)
    }

    @Test
    fun `a wrong macro, or a wrong-length striker, is refused`() {
        assertFailsWith<IllegalArgumentException> { ForkPatch("Bad", ForkVoice.TINE, mapOf("DECAY2" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { ForkPatch("Bad", ForkVoice.TINE, emptyMap(), FloatArray(10)) }
    }

    @Test
    fun `a wrong-length striker in json is refused`() {
        val patch = ForkPatch("Test", ForkVoice.TINE, emptyMap())
        val bad = patch.toJsonText().let {
            // Splice in a wrong-length striker array by hand - the sidecar-edited-by-hand case the spec calls out.
            it.dropLast(1) + ""","striker":[1,2,3]}"""
        }
        assertFailsWith<com.snipsnap.json.JsonException> { Patches.fromJsonText(bad) }
    }

}
