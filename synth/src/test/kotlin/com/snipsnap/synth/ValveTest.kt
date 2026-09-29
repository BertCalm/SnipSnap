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
        assertEquals(mapOf("DRIVE" to 0.45f, "SAG" to 0.35f, "TONE" to 0.5f, "CAB" to 0.6f), Valve.defaults())
        assertEquals(0.05f, Valve.gainFor(0f), 1e-6f)
        assertEquals(35f, Valve.gainFor(1f), 1e-3f)
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
    fun `4x keeps the fold-back under the TIDE bar where the snip rate cannot`() {
        // Spike, part B, Extra A: 247 Hz at DRIVE 1 read 49.4 dB at 4x, 31.9 at 1x.
        // SAG, TONE and CAB at their neutrals so the bar is read on the tube alone:
        // through the default speaker (CAB 0.6, coil 5.0 kHz) the read was 55.1 dB at
        // 4x / 32.9 at 1x, the coil stripping high spurs and flattering it by ~4 dB;
        // pinned: 51.3 dB at 4x, 28.8 at 1x.
        val f0 = 246.94f
        val p = probe(f0, 2.0f)
        val start = (0.3f * rate).toInt()
        val hot = mapOf("DRIVE" to 1f, "SAG" to 0f, "TONE" to 0.5f, "CAB" to 0f)
        // The 4x side goes through the public entry point, so the test also fails
        // if the default path stops oversampling.
        val four = harmonicClarity(Valve.process(p, hot).samples, rate, f0, start)
        val one = harmonicClarity(Valve.process(p, hot, oversample = false).samples, rate, f0, start)
        println("VALVE aliasing at 247 Hz, DRIVE 1: 4x ${"%.1f".format(four)} dB, 1x ${"%.1f".format(one)} dB")
        assertTrue(four >= 45.0, "energy between harmonics is only ${"%.1f".format(four)} dB down at 4x")
        assertTrue(one < four - 6.0, "the probe cannot see fold-back: 1x ${"%.1f".format(one)} vs 4x ${"%.1f".format(four)}")
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
    fun `SAG changes a hot sound`() {
        // Measured -5.97 / -2.68 / -0.98 dB at SAG 0.25 / 0.5 / 1. A boolean or constant
        // use of the macro would pass the -40 dB wiring bar, so the ordering is asserted too.
        val a = Valve.process(kick, mapOf("DRIVE" to 1f, "SAG" to 0f)).samples
        // The RMS of the difference from the SAG 0 render, re that render, on the kick at DRIVE 1.
        fun diffDb(sag: Float): Double {
            val b = Valve.process(kick, mapOf("DRIVE" to 1f, "SAG" to sag)).samples
            val diff = FloatArray(a.size) { a[it] - b[it] }
            return 20 * log10(rms(diff) / rms(a))
        }
        val d25 = diffDb(0.25f)
        val d50 = diffDb(0.5f)
        val d100 = diffDb(1f)
        println("VALVE SAG at DRIVE 1: difference ${"%.2f".format(d25)} / ${"%.2f".format(d50)} / ${"%.2f".format(d100)} dB at SAG 0.25 / 0.5 / 1 re the SAG 0 render")
        assertTrue(d100 > -40.0, "SAG 1 changed a DRIVE 1 kick by only ${"%.1f".format(d100)} dB")
        assertTrue(d25 < d50 && d50 < d100, "SAG is not monotonic: 0.25 $d25, 0.5 $d50, 1 $d100 dB")
    }

    @Test
    fun `SAG falls at its release rate while the signal is still above the rail`() {
        // The follower's target is the overshoot |v| - 1 (0 under the rail). It
        // charges toward a higher target with a 5 ms time constant and recovers with a
        // 120 ms one (63 % of the way in one constant, not complete), including toward a
        // lower target still above the rail: from a steady overshoot of 2 down to one
        // of 0.5, 100 ms in it is still near 0.5 + 1.5*e^(-100/120) = 1.15.
        val hz = rate * Dsp.OVERSAMPLE
        fun frames(seconds: Float) = (seconds * hz).toInt()
        val first = frames(0.050f)
        val v = FloatArray(first + frames(0.500f)) { if (it < first) 3f else 1.5f }
        val track = Valve.sagTrack(v, hz)
        val charged = track[first - 1]
        val at100 = track[first + frames(0.100f)]
        val at500 = track[v.size - 1]
        val rise = Valve.sagTrack(FloatArray(frames(0.020f)) { -3f }, hz)[frames(0.007f)]
        println("VALVE SAG follower: ${"%.3f".format(charged)} charged, ${"%.3f".format(at100)} 100 ms and ${"%.3f".format(at500)} 500 ms into a lower overshoot; ${"%.3f".format(rise)} 7 ms into a rise to 2")
        assertTrue(charged > 1.9f, "the follower reached only $charged after 50 ms at an overshoot of 2")
        assertTrue(at100 > 0.9f, "100 ms into the lower overshoot the follower is already down to $at100: it is not recovering at 120 ms")
        assertTrue(at500 < 0.6f, "500 ms into the lower overshoot the follower is still at $at500")
        assertTrue(rise >= 0.63f * 2f, "7 ms into a rise to 2 the follower is only at $rise")
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
        // At the default DRIVE the follower never leaves 0 on these fixtures (gain 0.953,
        // |v| < 1), so FxTest's identical-channels test runs VALVE with the sag dead. A
        // follower carried from L into R would bias R from its first frame.
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
    fun `a pad with a DC offset stays finite and in range, and the blocker drains the offset`() {
        val offset = Snip(FloatArray(snare.samples.size) { 0.5f * snare.samples[it] + 0.3f }, 1, rate)
        val out = Valve.process(offset, mapOf("DRIVE" to 1f))
        assertTrue(out.samples.all { it.isFinite() && it in -1f..1f })
        assertEquals(offset.peak(), out.peak(), 1e-4f)
        // Measured 0.179 at DRIVE 1, so the bound is that plus about 20 %. Mutation check:
        // with the `- dc.lp(t, DC_HZ)` term removed the ratio read 1.769 and this was the
        // only test in ValveTest and FxTest to fail - the one assertion that the blocker
        // is there.
        val drained = abs(mean(out.samples)) / abs(mean(offset.samples))
        println("VALVE DC blocker: mean ${"%.4f".format(mean(offset.samples))} in, ${"%.4f".format(mean(out.samples))} out (ratio ${"%.3f".format(drained)})")
        assertTrue(drained < 0.215, "the 5 Hz blocker left ${"%.3f".format(drained)} of the input's DC")
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
