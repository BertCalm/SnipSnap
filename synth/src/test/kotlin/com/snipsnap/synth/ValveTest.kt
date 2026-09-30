package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VALVE's own claims (docs/superpowers/specs/2026-09-29-magnet-valve-design.md,
 * "Testing"). The rack's shared contract - deterministic, clean, peak-matched,
 * stereo intact - is FxTest's, and covers VALVE once it is in FxChain.SECTIONS.
 */
class ValveTest {

    private val rate = 44_100
    private val kick = Thump.render(ThumpVoice.KICK)
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val neutral = mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f)

    /** The DRIVE 1 (gain 1000) steady-probe clarity at 4x, dB: the measured value; the aliasing test asserts it less 2 dB. */
    private val measuredDrive1Clarity = 40.6

    // ---------- helpers ----------

    private fun rms(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / x.size.coerceAtLeast(1))
    }

    private fun mean(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v
        return s / x.size.coerceAtLeast(1)
    }

    private fun db(a: Double, b: Double) = 10 * log10(a.coerceAtLeast(1e-30) / b.coerceAtLeast(1e-30))

    /** Power per bin of the whole mono signal, zero-padded to a power of two (no window: a hit is shorter than any window). */
    private fun power(x: FloatArray): Pair<FloatArray, Int> {
        var n = 1
        while (n < x.size) n = n shl 1
        val re = FloatArray(n) { x.getOrElse(it) { 0f } }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> re[b] * re[b] + im[b] * im[b] } to n
    }

    private fun bandEnergy(p: FloatArray, n: Int, lo: Float, hi: Float): Double {
        var e = 0.0
        for (b in 1 until p.size) {
            val hz = b.toFloat() * rate / n
            if (hz >= lo && hz < hi) e += p[b]
        }
        return e
    }

    private fun centroid(x: FloatArray): Double {
        val (p, n) = power(x)
        var num = 0.0
        var den = 0.0
        for (b in 1 until p.size) {
            num += p[b].toDouble() * b * rate / n
            den += p[b]
        }
        return num / den
    }

    /** Blackman-Harris power spectrum of [n] samples from [start] - copied from ForkTest.magnitudes. */
    private fun magnitudes(samples: FloatArray, start: Int, n: Int): FloatArray {
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples.getOrElse(start + i) { 0f } * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> (re[b] * re[b] + im[b] * im[b]) }
    }

    /** Energy within 8 Hz of a harmonic of [f0] against everything else, dB - copied from ForkTest.harmonicClarity (the TIDE/SIREN aliasing bar). */
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

    /**
     * A steady, exactly harmonic probe: harmonics of [f0] up to 5 kHz at 1/k,
     * peak 0.99. The only aliasing measure a decaying string does not confound
     * (the spec's "VALVE's placement": harmonicClarity on a string mis-ranks).
     */
    private fun probe(f0: Float, seconds: Float): Snip {
        val n = (seconds * rate).toInt()
        val x = FloatArray(n)
        var k = 1
        while (k * f0 <= 5_000f) {
            for (i in 0 until n) x[i] += (sin(2 * PI * k * f0 * i / rate) / k).toFloat()
            k++
        }
        val peak = x.maxOf { abs(it) }
        for (i in x.indices) x[i] *= 0.99f / peak
        return Snip(x, 1, rate)
    }

    private fun sine(hz: Float, seconds: Float, amp: Float): Snip =
        Snip(FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }, 1, rate)

    private fun saw(hz: Float, seconds: Float): Snip =
        Snip(FloatArray((seconds * rate).toInt()) { val ph = (hz * it / rate) % 1f; 0.9f * (2f * ph - 1f) }, 1, rate)

    // ---------- the contract VALVE adds ----------

    @Test
    fun `the macros are the four the spec names, with their neutrals`() {
        assertEquals(listOf("DRIVE", "SAG", "TONE", "CAB"), Valve.MACROS.map { it.name })
        assertEquals(mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f), Valve.MACROS.associate { it.name to it.neutral })
        assertEquals(mapOf("DRIVE" to 0.7f, "SAG" to 0.35f, "TONE" to 0.5f, "CAB" to 0.6f), Valve.defaults())
    }

    @Test
    fun `the gain law is V1's up to DRIVE 0_6, then log-linear to 1000 at the top`() {
        assertEquals(0.05f, Valve.gainFor(0f), 1e-6f)
        // V1's law exactly at and below the pivot: 0.05 * 700^d.
        for (d in listOf(0.1f, 0.3f, 0.45f, 0.6f)) {
            assertEquals(0.05f * Math.pow(700.0, d.toDouble()).toFloat(), Valve.gainFor(d), 1e-4f * Valve.gainFor(d))
        }
        assertEquals(2.547f, Valve.gainFor(0.6f), 1e-3f)
        assertEquals(1_000f, Valve.gainFor(1f), 1e-2f)
        // The pivot has no kink in value, and the whole law is strictly rising.
        assertEquals(Valve.gainFor(0.6f), Valve.gainFor(0.6001f), 0.01f)
        var prev = Valve.gainFor(0f)
        for (i in 1..100) {
            val g = Valve.gainFor(i / 100f)
            assertTrue(g > prev, "the gain law stops rising at DRIVE ${i / 100f}: $prev then $g")
            prev = g
        }
        // The owner's two listened points on the new law: the choir's gain 100 at about DRIVE 0.845, and gain 4.90 (the V1 law's DRIVE 0.7) at about 0.644.
        assertEquals(100f, Valve.gainFor(0.845f), 3f)
        assertEquals(4.90f, Valve.gainFor(0.644f), 0.1f)
    }

    @Test
    fun `the neutral point is a near-copy - every band within half a dB, loudness within 12 hundredths of a dB on a kick and 44 on a snare`() {
        // Both bounds are measurements plus about 20 % (the plan kept the kick at the
        // spec's 0.1; final-review ruling: 0.0985 measured is 1.5 % of headroom on an
        // accident of the fixture, so 0.12). Kick 0.0985 dB: the 4x round trip alone
        // -0.005, the 5 Hz blocker moving which sample is the kick's peak +0.096 - a
        // quantity that swings about +/-0.05 dB with THUMP's own macros (15 of 36 small
        // retunes cross 0.1) and is non-monotonic in DC_HZ. Snare 0.361 dB: the round
        // trip shifts the phase of its top octaves, lowering its peak 0.75 dB and its
        // RMS 0.38 dB (energy above 16 kHz is what it loses) before the peak match lifts
        // the RMS back. Every band within 0.08 dB. The band loop compares each
        // third-octave's share of its own 40 Hz-16 kHz energy, not absolute level: the
        // peak match's single gain is the RMS clause's to judge. The kick skips 8 bands
        // above 1.6 kHz it barely has (< 1e-6 of its energy); the snare skips none.
        // These numbers are those of the 4x render (V1's): the V1.1 gain law, wall and
        // supply leave the neutral point alone (DRIVE 0, SAG 0, CAB 0).
        for ((name, dry, rmsBound) in listOf(Triple("kick", kick, 0.12), Triple("snare", snare, 0.44))) {
            val out = Valve.process(dry, neutral)
            assertEquals(dry.frameCount, out.frameCount, "$name changed length")
            val (pIn, n) = power(dry.samples)
            val (pOut, _) = power(out.samples)
            // Compared relative to each signal's own 40 Hz-16 kHz energy, so the
            // peak match's single gain cannot pass for a tone change.
            val totIn = bandEnergy(pIn, n, 40f, 16_000f)
            val totOut = bandEnergy(pOut, n, 40f, 16_000f)
            var lo = 40f
            var skipped = 0
            while (lo < 16_000f) {
                val hi = minOf(lo * 2f.pow(1f / 3f), 16_000f)
                val eIn = bandEnergy(pIn, n, lo, hi)
                if (eIn / totIn > 1e-6) {
                    val d = db(bandEnergy(pOut, n, lo, hi) / totOut, eIn / totIn)
                    println("VALVE neutral $name ${lo.toInt()}-${hi.toInt()} Hz: ${"%.3f".format(d)} dB")
                    assertTrue(abs(d) <= 0.5, "$name ${lo.toInt()}-${hi.toInt()} Hz moved ${"%.2f".format(d)} dB at the neutral point")
                } else {
                    skipped++
                }
                lo = hi
            }
            println("VALVE neutral $name skipped $skipped of the bands")
            if (name == "snare") assertEquals(0, skipped, "the snare skipped $skipped bands, so the 40 Hz-16 kHz claim no longer covers them")
            val rmsDb = 20 * log10(rms(out.samples) / rms(dry.samples))
            println("VALVE neutral $name RMS: ${"%.3f".format(rmsDb)} dB")
            assertTrue(abs(rmsDb) <= rmsBound, "$name loudness moved ${"%.3f".format(rmsDb)} dB at the neutral point")
        }
    }

    @Test
    fun `4x keeps the fold-back under the bar where the snip rate cannot`() {
        // SAG, TONE and CAB at their neutrals so the bar is read on the tube alone.
        // DRIVE 0.7 (the default, gain 11.3) is the case the test's name describes: 72.4 dB
        // at 4x against 32.9 dB at 1x, either side of the 45 dB bar. The other two are pins:
        // at DRIVE 0.6 (gain 2.5) the 1x path also clears the bar, and at DRIVE 1 (gain
        // 1000; V1 pinned 51.3 dB at 4x / 28.8 at 1x at gain 35) the 4x path misses it,
        // 40.6 dB at 4x / 26.8 at 1x, and the owner chose that top knowing the bar is missed.
        val f0 = 246.94f
        val p = probe(f0, 2.0f)
        val start = (0.3f * rate).toInt()
        fun clarity(drive: Float, oversample: Boolean): Double {
            val macros = mapOf("DRIVE" to drive, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f)
            return harmonicClarity(Valve.process(p, macros, oversample).samples, rate, f0, start)
        }
        val four6 = clarity(0.6f, true)
        val one6 = clarity(0.6f, false)
        val four7 = clarity(0.7f, true)
        val one7 = clarity(0.7f, false)
        val four10 = clarity(1f, true)
        val one10 = clarity(1f, false)
        println("VALVE aliasing at 247 Hz: DRIVE 0.6 4x ${"%.1f".format(four6)} / 1x ${"%.1f".format(one6)} dB; DRIVE 0.7 4x ${"%.1f".format(four7)} / 1x ${"%.1f".format(one7)} dB; DRIVE 1 4x ${"%.1f".format(four10)} / 1x ${"%.1f".format(one10)} dB")
        assertTrue(four7 >= 45.0, "energy between harmonics is only ${"%.1f".format(four7)} dB down at 4x, DRIVE 0.7")
        assertTrue(one7 < 45.0, "the probe cannot see fold-back at DRIVE 0.7: 1x reads ${"%.1f".format(one7)} dB, over the bar")
        assertTrue(four6 >= 45.0, "energy between harmonics is only ${"%.1f".format(four6)} dB down at 4x, DRIVE 0.6")
        assertTrue(one6 < four6 - 6.0, "the probe cannot see fold-back at DRIVE 0.6: 1x ${"%.1f".format(one6)} vs 4x ${"%.1f".format(four6)}")
        assertTrue(four10 >= measuredDrive1Clarity - 2.0, "DRIVE 1 at 4x fell to ${"%.1f".format(four10)} dB")
        assertTrue(one10 < four10 - 6.0, "the probe cannot see fold-back: 1x ${"%.1f".format(one10)} vs 4x ${"%.1f".format(four10)}")
    }

    @Test
    fun `the public entry point runs the 4x path at every DRIVE`() {
        // The 4x round trip is always on through Valve.process(snip, macros). V1's aliasing
        // test used to send the 4x side through the public entry for this reason and now
        // forces the overload, so this test carries the promise that the default path oversamples.
        for (drive in listOf(0f, 0.3f, 0.7f, 1f)) {
            val m = mapOf("DRIVE" to drive, "SAG" to 0.35f, "TONE" to 0.5f, "CAB" to 0.5f)
            assertContentEquals(
                Valve.process(snare, m).samples,
                Valve.process(snare, m, oversample = true).samples,
                "the public entry point is not the 4x path at DRIVE $drive",
            )
        }
    }

    @Test
    fun `the upsampler's image level is printed`() {
        // Print-only: the aliasing probe above stops at 5 kHz and cannot see how
        // well the interpolator rejects the images of a high tone, which sit just
        // above the snip's Nyquist. A tone at f zero-stuffed by 4 has images at
        // 44100 - f (and 44100 + f); the interpolator is Tide.bandLimit.
        for (f in listOf(10_000f, 15_000f)) {
            val up = Valve.upsample(sine(f, 1.0f, 0.9f).samples, rate)
            assertTrue(up.all { it.isFinite() }, "the upsampled $f Hz sine has a non-finite sample")
            val upRate = rate * Dsp.OVERSAMPLE
            val n = 1 shl 16
            val mag = magnitudes(up, 8_192, n)
            fun near(target: Float): Double {
                var e = 0.0
                for (b in 1 until mag.size) {
                    if (abs(b.toFloat() * upRate / n - target) <= 100f) e += mag[b]
                }
                return e
            }
            val image = db(near(rate - f), near(f))
            println("VALVE upsampler image of a ${f.toInt()} Hz sine at ${(rate - f).toInt()} Hz: ${"%.1f".format(image)} dB re the tone")
        }
    }

    @Test
    fun `a kick through VALVE is still a kick and a snare still a snare`() {
        assertEquals(DrumClass.KICK, Classifier.classify(Valve.process(kick)).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(Valve.process(snare)).drumClass)
    }

    // ---------- each macro does its one thing ----------

    @Test
    fun `DRIVE distorts - a sine gains harmonics`() {
        fun offFundamentalDb(drive: Float): Double {
            val out = Valve.process(sine(220f, 1.0f, 0.9f), mapOf("DRIVE" to drive, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f))
            return -harmonicClarityAgainstFundamental(out.samples, 220f)
        }
        val clean = offFundamentalDb(0f)
        val hot = offFundamentalDb(1f)
        println("VALVE DRIVE: energy off the fundamental ${"%.1f".format(clean)} dB at 0, ${"%.1f".format(hot)} dB at 1")
        assertTrue(hot - clean >= 20.0, "DRIVE 1 added only ${"%.1f".format(hot - clean)} dB of harmonics")
    }

    /** Fundamental (within 8 Hz) against everything else, dB, from 0.1 s. */
    private fun harmonicClarityAgainstFundamental(x: FloatArray, f0: Float): Double {
        val n = 1 shl 15
        val mag = magnitudes(x, (0.1f * rate).toInt(), n)
        var on = 0.0
        var off = 0.0
        for (b in 1 until mag.size) {
            val hz = b.toFloat() * rate / n
            if (hz > 20_000f) break
            if (abs(hz - f0) < 8f) on += mag[b] else off += mag[b]
        }
        return 10 * log10(on / off)
    }

    @Test
    fun `CAB darkens - a snare's centroid falls`() {
        val open = centroid(Valve.process(snare, mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f)).samples)
        val wall = centroid(Valve.process(snare, mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 1f)).samples)
        println("VALVE CAB: centroid ${"%.0f".format(open)} Hz at 0, ${"%.0f".format(wall)} Hz at 1")
        assertTrue(wall < 0.8 * open, "CAB 1 left the centroid at ${"%.0f".format(wall)} Hz against ${"%.0f".format(open)}")
    }

    @Test
    fun `CAB leaving 0 is continuous - a hundredth of CAB stays within half a dB of CAB 0 in every band`() {
        // With the coil map opening only to 20 kHz, CAB 0.01 read -1.52 dB in the top
        // third-octave (12.9-16 kHz) against CAB 0's exact bypass. Measured after the map
        // opened to the one-pole's own cap: the top third-octave -0.10 dB, and the worst
        // band 403-507 Hz at -0.32 dB - the network's notch fading in as it should
        // (-9 dB x 0.04 = -0.36 dB at 500 Hz).
        val a = Valve.process(snare, mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f))
        val b = Valve.process(snare, mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0.01f))
        val (pA, n) = power(a.samples)
        val (pB, _) = power(b.samples)
        val totA = bandEnergy(pA, n, 40f, 16_000f)
        val totB = bandEnergy(pB, n, 40f, 16_000f)
        var worst = 0.0
        var worstBand = ""
        var top = 0.0
        var lo = 40f
        while (lo < 16_000f) {
            val hi = minOf(lo * 2f.pow(1f / 3f), 16_000f)
            val eA = bandEnergy(pA, n, lo, hi)
            if (eA / totA > 1e-6) {
                val d = db(bandEnergy(pB, n, lo, hi) / totB, eA / totA)
                if (abs(d) > abs(worst)) {
                    worst = d
                    worstBand = "${lo.toInt()}-${hi.toInt()} Hz"
                }
                if (hi == 16_000f) top = d
            }
            lo = hi
        }
        // Every band within half a dB is the worst band within half a dB; printed first.
        println("VALVE CAB 0 -> 0.01: worst band $worstBand at ${"%.3f".format(worst)} dB, the top third-octave ${"%.3f".format(top)} dB")
        assertTrue(abs(worst) <= 0.5, "CAB 0.01 moved $worstBand by ${"%.2f".format(worst)} dB against CAB 0")
    }

    @Test
    fun `TONE 0 scoops the mids and cuts the top, TONE 1 pushes the mids and top forward`() {
        // Each band's share of the 40 Hz-16 kHz energy on a 110 Hz saw at DRIVE 0 / SAG 0 /
        // CAB 0. The filters are -12 dB at 380 Hz and a -6 dB shelf above 3 kHz at 0, +6 dB
        // at 650 Hz and a +6 dB shelf at 1; a share moves by less than its filter's gain,
        // because the other bands move the total too. Measured against TONE 0.5: the 3-16 kHz
        // share 2.29 dB down at 0 and 3.76 dB up at 1, the 500-800 Hz share 4.12 dB up at 1
        // (the 300-460 Hz scoop 8.19 dB at 0). The new bounds are those less about 20 %
        // (1.8, 3.0, 3.3). They guard the shelf's sign and the boost branch, which a
        // mutation could flip unseen while only the scoop was asserted.
        val tone110 = saw(110f, 1.0f)
        fun share(tone: Float, lo: Float, hi: Float): Double {
            val out = Valve.process(tone110, mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to tone, "CAB" to 0f))
            val (p, n) = power(out.samples)
            return db(bandEnergy(p, n, lo, hi), bandEnergy(p, n, 40f, 16_000f))
        }
        fun midShare(tone: Float) = share(tone, 300f, 460f)
        fun hiMidShare(tone: Float) = share(tone, 500f, 800f)
        fun topShare(tone: Float) = share(tone, 3_000f, 16_000f)
        for (t in listOf(0f, 0.5f, 1f)) {
            println("VALVE TONE $t: 300-460 Hz share ${"%.2f".format(midShare(t))} dB, 500-800 Hz ${"%.2f".format(hiMidShare(t))} dB, 3-16 kHz ${"%.2f".format(topShare(t))} dB")
        }
        val flat = midShare(0.5f)
        val scooped = midShare(0f)
        assertTrue(scooped <= flat - 6.0, "TONE 0 took only ${"%.1f".format(flat - scooped)} dB out of the mids")
        val topCut = topShare(0.5f) - topShare(0f)
        val hiMidUp = hiMidShare(1f) - hiMidShare(0.5f)
        val topUp = topShare(1f) - topShare(0.5f)
        assertTrue(topCut >= 1.8, "TONE 0 took only ${"%.2f".format(topCut)} dB out of the top")
        assertTrue(hiMidUp >= 3.3, "TONE 1 pushed 500-800 Hz up only ${"%.2f".format(hiMidUp)} dB")
        assertTrue(topUp >= 3.0, "TONE 1 pushed the top up only ${"%.2f".format(topUp)} dB")
    }

    @Test
    fun `SAG dips a driven sound and adds no end step`() {
        // The V1.1 supply sag is post-tube: y = t / (1 + SAG * 3 * env(|t|)), env a 5 ms /
        // 120 ms follower of the tube's own output. Measured on the kick at DRIVE 0.6 (gain
        // 2.5): the kick's level 60-160 ms after the hit, re its first 20 ms, falls by about
        // 2.2 dB at SAG 1 against SAG 0 (spike: 2.19), and it ends with no DC step
        // (spike: under 0.02 % of peak), which the V1 bias mechanism did not (0.8 % at DRIVE
        // 0.6, and 3.6 % at gain 35, the spike's kick at DRIVE 1 on the V1 law).
        fun level(x: FloatArray, from: Float, to: Float): Double =
            rms(x.copyOfRange((from * rate).toInt(), (to * rate).toInt()))
        fun dipDb(sag: Float): Double {
            val out = Valve.process(kick, mapOf("DRIVE" to 0.6f, "SAG" to sag, "TONE" to 0.5f, "CAB" to 0.5f)).samples
            return 20 * log10(level(out, 0.060f, 0.160f) / level(out, 0f, 0.020f))
        }
        val d0 = dipDb(0f)
        val d25 = dipDb(0.25f)
        val d50 = dipDb(0.5f)
        val d100 = dipDb(1f)
        println("VALVE SAG at DRIVE 0.6: 60-160 ms re first 20 ms ${"%.2f".format(d0)} / ${"%.2f".format(d25)} / ${"%.2f".format(d50)} / ${"%.2f".format(d100)} dB at SAG 0 / 0.25 / 0.5 / 1")
        assertTrue(d100 <= d0 - 1.5, "SAG 1 dipped the tail only ${"%.2f".format(d0 - d100)} dB")
        assertTrue(d25 > d50 && d50 > d100, "SAG is not monotonic: 0.25 $d25, 0.5 $d50, 1 $d100 dB")
        // The tube's own asymmetry leaves a step at gain 1000 whether SAG is up or not (1.7 % on
        // the kick at SAG 0, DRIVE 1), so the claim is that SAG adds none. Mutation check, with
        // `stage` put back to V1's bias mechanism: the dip assertion above fails (SAG 1 dips the
        // tail only 0.34 dB, -11.90 to -12.24) and so does this one at DRIVE 0.6 (gain 2.5: 0.005 %
        // of peak at SAG 0, 0.775 % at SAG 1, limit 0.505 %). The same mutant takes the kick from
        // 0.06 % to 3.57 % at gain 35 (DRIVE 0.7755, not in this loop; the spike's 0.06 % to
        // 3.6 %) and fails the condition there too, but it PASSES it at gain 1000 (1.72 % at SAG
        // 0, 0.002 % at SAG 1), so the DRIVE 1 case guards SAG adding a step to the tube's own,
        // not the old mechanism.
        fun endStep(drive: Float, sag: Float): Double {
            val hot = Valve.process(kick, mapOf("DRIVE" to drive, "SAG" to sag, "TONE" to 0.5f, "CAB" to 0.5f)).samples
            val tail = hot.copyOfRange(hot.size - (0.010f * rate).toInt(), hot.size)
            return abs(mean(tail)) / hot.maxOf { abs(it) }
        }
        for (drive in listOf(0.6f, 1f)) {
            val flat = endStep(drive, 0f)
            val sagged = endStep(drive, 1f)
            println("VALVE SAG end step at DRIVE $drive: ${"%.4f".format(flat)} of peak at SAG 0, ${"%.4f".format(sagged)} at SAG 1")
            assertTrue(sagged <= flat + 0.005, "SAG 1 added a DC step at DRIVE $drive: $flat -> $sagged of peak, the rest point moved")
        }
    }

    @Test
    fun `the supply follower charges in 5 ms, recovers in 120 ms, and reads the previous sample`() {
        val hz = rate * Dsp.OVERSAMPLE
        fun frames(seconds: Float) = (seconds * hz).toInt()
        val first = frames(0.050f)
        val t = FloatArray(first + frames(0.500f)) { if (it < first) 1f else 0f }
        val env = Valve.supplyEnv(t, hz)
        assertEquals(0f, env[0], "the first sample must see the follower at rest (previous-sample rule)")
        val atFive = env[frames(0.005f) + 1]
        val charged = env[first - 1]
        val at120 = env[first + frames(0.120f)]
        val at500 = env[t.size - 1]
        println("VALVE supply follower: ${"%.3f".format(atFive)} at 5 ms, ${"%.3f".format(charged)} charged, ${"%.3f".format(at120)} 120 ms and ${"%.4f".format(at500)} 500 ms after the target drops to 0")
        assertEquals(1f - Math.exp(-1.0).toFloat(), atFive, 0.02f, "5 ms is one attack time constant, about 63 %")
        assertTrue(charged > 0.99f, "the follower reached only $charged after 50 ms")
        assertEquals(charged * Math.exp(-1.0).toFloat(), at120, 0.02f, "120 ms is one release time constant, about 37 % of where it was")
        assertTrue(at500 < 0.02f, "500 ms after the drop the follower is still at $at500")
    }

    @Test
    fun `CAB 1 is a darker wall - noise centroid near 2050 Hz, falling steadily from CAB 0_6`() {
        // Spike: seeded white noise through the whole chain at DRIVE 0 / SAG 0 / TONE 0.5:
        // V1's wall (4500 Hz, one pole) 4480 Hz centroid and -3 dB at 5088 Hz; the chosen
        // wall (3200 Hz, two poles) 2052 Hz centroid and -3 dB at 2934 Hz. This test's own
        // noise (seed 7, 0.5 s) measures 2005 Hz at CAB 1, 2.3 % under the spike's 2052 (another
        // noise realisation), 3273 Hz at 0.8 and 4863 Hz at 0.6; the bound is the spike's
        // figure within 10 %.
        val rnd = java.util.Random(7)
        val noise = Snip(FloatArray((0.5f * rate).toInt()) { (rnd.nextFloat() * 2f - 1f) * 0.9f }, 1, rate)
        fun c(cab: Float) = centroid(Valve.process(noise, mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to cab)).samples)
        val at06 = c(0.6f)
        val at08 = c(0.8f)
        val at10 = c(1f)
        println("VALVE wall: noise centroid ${"%.0f".format(at06)} Hz at CAB 0.6, ${"%.0f".format(at08)} at 0.8, ${"%.0f".format(at10)} at 1")
        assertTrue(at06 > at08 && at08 > at10, "the wall does not darken steadily: $at06, $at08, $at10")
        assertEquals(2052.0, at10, 0.10 * 2052.0, "CAB 1 centroid moved from the chosen wall")
    }

    @Test
    fun `CAB has no step at 0_6 or at 1 - a hundredth of CAB moves no band by more than 0_75 dB`() {
        // The test's job is to catch a STEP in CAB, not to bound the ramp's slope. Above 0.6 the
        // corner falls about 45 Hz per hundredth and the second pole fades in, so the top
        // third-octave (12.9-16 kHz) falls about 0.52 dB per hundredth from 0.9 up (0.54 at 0.9 to
        // 0.91); V1's own slope leaves 0.6 -> 0.61 at 0.497 dB in the 40-50 Hz band. Measured
        // worst band per pair: 0.59 -> 0.6 0.108 dB (403-507 Hz), 0.6 -> 0.61 0.497 (40-50 Hz),
        // 0.61 -> 0.62 0.287 (40-50 Hz), 0.98 -> 0.99 0.524 (12.9-16 kHz), 0.99 -> 1 0.517
        // (12.9-16 kHz). The bound is 0.75 dB, 43 % over the worst. A snapped-in second pole
        // would show as a step of 3 dB or more: the one-pole and two-pole 3.2 kHz walls differ by
        // 5.1 dB at 5 kHz and 11.5 dB at 12 kHz on noise (spike). An earlier 0.5 dB bound failed
        // 0.99 -> 1 at 0.517 dB on the slope alone and passed 0.6 -> 0.61 by 0.003 dB.
        for ((a, b) in listOf(0.59f to 0.6f, 0.6f to 0.61f, 0.61f to 0.62f, 0.98f to 0.99f, 0.99f to 1f)) {
            val x = Valve.process(snare, mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to a))
            val y = Valve.process(snare, mapOf("DRIVE" to 0f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to b))
            val (pX, n) = power(x.samples)
            val (pY, _) = power(y.samples)
            val totX = bandEnergy(pX, n, 40f, 16_000f)
            val totY = bandEnergy(pY, n, 40f, 16_000f)
            var worst = 0.0
            var worstBand = ""
            var lo = 40f
            while (lo < 16_000f) {
                val hi = minOf(lo * 2f.pow(1f / 3f), 16_000f)
                val eX = bandEnergy(pX, n, lo, hi)
                if (eX / totX > 1e-6) {
                    val d = db(bandEnergy(pY, n, lo, hi) / totY, eX / totX)
                    if (abs(d) > abs(worst)) {
                        worst = d
                        worstBand = "${lo.toInt()}-${hi.toInt()} Hz"
                    }
                }
                lo = hi
            }
            println("VALVE CAB $a -> $b: worst band $worstBand at ${"%.3f".format(worst)} dB")
            assertTrue(abs(worst) <= 0.75, "CAB $a -> $b moved $worstBand by ${"%.2f".format(worst)} dB")
        }
    }

    @Test
    fun `below DRIVE 0_6, SAG 0 and CAB 0_6 the sound is V1's`() {
        // Golden values captured from the V1 code (base 1a2ec180): the RMS and six samples of
        // the kick and snare at DRIVE 0.6 / CAB 0.6, DRIVE 0.3 / CAB 0.25, DRIVE 0.6 / CAB 0.5
        // and DRIVE 0.6 / CAB 0.1 (the last pins the ramp below CAB 0.25, where the network is
        // only partly in), all SAG 0 and TONE 0.5, forced through the 4x path.
        val goldens: List<Triple<String, Pair<Float, Float>, Pair<Double, List<Float>>>> = listOf(
            Triple("kick", 0.6f to 0.6f, 0.23071308447352024 to listOf(0.66134125f, -0.7810704f, -0.3686499f, -0.0011201216f, 0.009041333f, 0.0016381168f)),
            Triple("kick", 0.3f to 0.25f, 0.1926597508245963 to listOf(0.75100744f, -0.54861236f, -0.18609507f, 7.6583226E-4f, 0.00432172f, 7.611417E-4f)),
            Triple("kick", 0.6f to 0.5f, 0.22948237884389408 to listOf(0.66986495f, -0.7467402f, -0.36712107f, -5.418543E-4f, 0.0090928925f, 0.0016371422f)),
            Triple("snare", 0.6f to 0.6f, 0.13658699214066872 to listOf(0.14662017f, 0.20710678f, -0.05767708f, -0.0019851686f, -0.008956235f, 0.0014699006f)),
            Triple("snare", 0.3f to 0.25f, 0.09143330077535367 to listOf(0.05193938f, 0.114540525f, -0.025876775f, -5.715725E-4f, -0.0042426726f, 6.932373E-4f)),
            Triple("snare", 0.6f to 0.5f, 0.13423213247546018 to listOf(0.13764937f, 0.18671855f, -0.055957038f, -0.0018377537f, -0.008672879f, 0.0014205342f)),
            Triple("kick", 0.6f to 0.1f, 0.2853043012622534 to listOf(0.801458f, -0.8432499f, -0.4571536f, 0.010008363f, 0.01318882f, 0.0021314775f)),
            Triple("snare", 0.6f to 0.1f, 0.13940423546424763 to listOf(-0.28726712f, 0.5657891f, 0.0013478531f, 0.008655398f, -0.00674964f, 9.2908967E-4f)),
        )
        for ((name, dc, want) in goldens) {
            val dry = if (name == "kick") kick else snare
            val out = Valve.process(dry, mapOf("DRIVE" to dc.first, "SAG" to 0f, "TONE" to 0.5f, "CAB" to dc.second), oversample = true).samples
            assertEquals(want.first, rms(out), 1e-5, "$name at DRIVE ${dc.first} CAB ${dc.second}: rms")
            val at = listOf(100, 1000, 3000, 6000, 9000, 12000).map { out[it] }
            for (i in at.indices) assertEquals(want.second[i], at[i], 1e-5f, "$name at DRIVE ${dc.first} CAB ${dc.second}: sample ${listOf(100, 1000, 3000, 6000, 9000, 12000)[i]}")
        }
    }

    // ---------- the inputs a person will hand it ----------

    @Test
    fun `a quieter channel stays quieter`() {
        val frames = kick.frameCount
        val stereo = Snip(FloatArray(frames * 2) { i -> if (i % 2 == 0) kick.samples[i / 2] else 0.5f * kick.samples[i / 2] }, 2, rate)
        fun channelPeak(s: Snip, ch: Int): Float {
            var p = 0f
            for (f in 0 until s.frameCount) p = maxOf(p, abs(s.samples[f * 2 + ch]))
            return p
        }
        val clean = Valve.process(stereo, neutral)
        val ratio = channelPeak(clean, 1) / channelPeak(clean, 0)
        assertEquals(0.5f, ratio, 0.01f, "the neutral point moved the image: R/L $ratio")
        val hot = Valve.process(stereo)
        assertTrue(channelPeak(hot, 1) < channelPeak(hot, 0), "at defaults the quieter channel came out as loud as the louder one")
    }

    @Test
    fun `identical channels stay identical with the sag live - one follower per channel`() {
        // The supply follower keys on the tube's own output at any level, so it is live at
        // every DRIVE, the default included, and FxTest's identical-channels test runs VALVE
        // with the sag working. This test drives it harder, DRIVE 1 and SAG 1. A follower
        // carried from L into R would sag R from its first frame.
        val frames = kick.frameCount
        val doubled = Snip(FloatArray(frames * 2) { i -> kick.samples[i / 2] }, 2, rate)
        val out = Valve.process(doubled, mapOf("DRIVE" to 1f, "SAG" to 1f))
        for (f in 0 until frames) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "L and R differ at frame $f")
    }

    @Test
    fun `a silent pad comes back silent`() {
        val silent = Snip(FloatArray(4_410 * 2), 2, rate)
        val out = Valve.process(silent)
        assertEquals(2, out.channels)
        assertEquals(silent.frameCount, out.frameCount)
        assertTrue(out.samples.all { it == 0f })
    }

    @Test
    fun `a 64-frame pad keeps its length, to the last frame`() {
        val tiny = Snip(FloatArray(64) { if (it < 8) 0.8f else 0f }, 1, rate)
        val out = Valve.process(tiny)
        assertEquals(64, out.frameCount)
        assertTrue(out.samples.all { it.isFinite() && it in -1f..1f })
        // The frame count is set by construction (process zero-pads via getOrElse), so a
        // decimator one frame short would pass the count and zero the last frames: a
        // constant pad shows it. Measured 0.800 at frame 63 (0.796 at 62).
        val flat = Snip(FloatArray(64) { 0.8f }, 1, rate)
        val flatOut = Valve.process(flat)
        assertEquals(64, flatOut.frameCount)
        assertTrue(abs(flatOut.samples[63]) > 0.4f, "the last frame came back as ${flatOut.samples[63]}: the round trip came up short and was zero-padded")
    }

    @Test
    fun `a 48 kHz pad renders finite and its own length`() {
        // The round trip's band-limit corner is absolute (19.5 kHz), so a 48 kHz pad loses
        // its 19-24 kHz (see the header KDoc); it must still render sanely at every DRIVE.
        val pad = Snip(FloatArray(48_000) { (0.8 * sin(2 * PI * 220.0 * it / 48_000)).toFloat() }, 1, 48_000)
        for (drive in listOf(0f, 0.3f, 0.7f, 1f)) {
            val out = Valve.process(pad, mapOf("DRIVE" to drive))
            assertEquals(pad.frameCount, out.frameCount)
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "DRIVE $drive at 48 kHz produced a bad sample")
            // The length is set by construction (a short render is zero-padded) and the range by
            // the peak match, so only the peak proves the render is not silent.
            assertEquals(pad.peak(), out.peak(), 1e-4f, "DRIVE $drive at 48 kHz did not come back at the pad's peak")
        }
    }

    @Test
    fun `a pad with a DC offset stays finite and in range, and the blocker drains the offset`() {
        val offset = Snip(FloatArray(snare.samples.size) { 0.5f * snare.samples[it] + 0.3f }, 1, rate)
        val out = Valve.process(offset, mapOf("DRIVE" to 1f))
        assertTrue(out.samples.all { it.isFinite() && it in -1f..1f })
        assertEquals(offset.peak(), out.peak(), 1e-4f)
        // Measured 0.103 at DRIVE 1 (gain 1000, the V1.1 law's top; V1 read 0.179 at gain 35), so
        // the bound is that plus about 20 %. V1 mutation check (not re-run on V1.1): with the
        // blocker's subtraction removed the ratio read 1.769 and this was the only test in
        // ValveTest and FxTest to fail - the one assertion that the blocker is there.
        val drained = abs(mean(out.samples)) / abs(mean(offset.samples))
        println("VALVE DC blocker: mean ${"%.4f".format(mean(offset.samples))} in, ${"%.4f".format(mean(out.samples))} out (ratio ${"%.3f".format(drained)})")
        assertTrue(drained < 0.124, "the 5 Hz blocker left ${"%.3f".format(drained)} of the input's DC")
    }

    @Test
    fun `the same pad renders bit-identical twice`() {
        assertContentEquals(Valve.process(snare).samples, Valve.process(snare).samples)
    }

    @Test
    fun `the cost per rendered second is printed`() {
        // Spec target: at most 20 ms per rendered second on the JVM. Printed,
        // not asserted - timing on a shared CI runner is not a property of
        // the code. The V1 listen records the phone's number beside it.
        val seconds = 4.0f
        val long = Snip(FloatArray((seconds * rate).toInt() * 2) { i -> kick.samples[(i / 2) % kick.samples.size] }, 2, rate)
        Valve.process(long) // warm-up
        val t0 = System.nanoTime()
        val out = Valve.process(long)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(out.samples.all { it.isFinite() })
        println("VALVE cost: ${"%.1f".format(ms / seconds)} ms per rendered second (4 s stereo, ${"%.0f".format(ms)} ms)")
    }
}
