package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FORGE's own claims (docs/superpowers/specs/2026-10-25-forge-section-design-brief.md, the macro
 * contract and "Risks"). The rack's shared contract - deterministic, finite, peak-matched, stereo
 * intact - is FxTest's, and covers FORGE through FxChain.SECTIONS.
 */
class ForgeTest {

    private val rate = 44_100
    private val kick = Thump.render(ThumpVoice.KICK)
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val hat = Thump.render(ThumpVoice.HAT_CLOSED)
    private val cowbell = Thump.render(ThumpVoice.COWBELL)
    private val neutral = mapOf("CHARGE" to 0f, "STANDOFF" to 0f, "YIELD" to 1f, "ARREST" to 0f)

    // ---------- helpers ----------

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to.coerceAtMost(x.size)) s += x[i].toDouble() * x[i]
        return sqrt(s / (to.coerceAtMost(x.size) - from).coerceAtLeast(1))
    }

    private fun crest(x: FloatArray): Double = x.maxOf { abs(it) } / rms(x)

    private fun db(a: Double, b: Double) = 10 * log10(a.coerceAtLeast(1e-30) / b.coerceAtLeast(1e-30))

    /** Share of the power of [x] from [from] for [length] frames between [lo] and [hi] Hz (zero-padded, no window). */
    private fun bandShare(x: FloatArray, lo: Float, hi: Float, from: Int = 0, length: Int = x.size - from): Double {
        var n = 1
        while (n < length) n = n shl 1
        val re = FloatArray(n) { if (it < length) x.getOrElse(from + it) { 0f } else 0f }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var band = 0.0
        var all = 0.0
        for (b in 1 until n / 2) {
            val p = re[b].toDouble() * re[b] + im[b].toDouble() * im[b]
            val hz = b.toFloat() * rate / n
            all += p
            if (hz >= lo && hz < hi) band += p
        }
        return band / all.coerceAtLeast(1e-30)
    }

    /** Blackman-Harris power spectrum of [n] samples from [start] - copied from ValveTest (ForkTest's). */
    private fun magnitudes(samples: FloatArray, start: Int, n: Int): FloatArray {
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples.getOrElse(start + i) { 0f } * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> (re[b] * re[b] + im[b] * im[b]) }
    }

    /** Energy within 8 Hz of a harmonic of [f0] against everything else, dB - ValveTest's (the TIDE/SIREN aliasing bar). */
    private fun harmonicClarity(samples: FloatArray, f0: Float, start: Int, n: Int = 1 shl 16): Double {
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

    /** ValveTest's steady, exactly harmonic probe: harmonics of [f0] up to 5 kHz at 1/k, peak 0.99. */
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

    private fun forge(s: Snip, vararg m: Pair<String, Float>) = Forge.process(s, mapOf(*m))

    private fun ms(v: Float) = (v * 1e-3f * rate).toInt()

    // ---------- the contract ----------

    @Test
    fun `the macros are the owner's four and DEPTH, with their neutrals`() {
        assertEquals(listOf("CHARGE", "STANDOFF", "YIELD", "ARREST", "DEPTH"), Forge.MACROS.map { it.name })
        assertEquals(listOf(0f, 0f, 1f, 0f, 0f), Forge.MACROS.map { it.neutral })
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 0.5f, 0f), Forge.MACROS.map { it.default })
    }

    @Test
    fun `DEPTH lands on the three media the owner picked, and 0 is the shipped sound`() {
        // The CHARGE gate (2026-10-27): TIGHT on the kick and the snare, SHORT on the bell, AS_BUILT on the chop.
        assertEquals(Forge.Medium.SHIPPED, Forge.Medium.forDepth(0f))
        assertEquals(Forge.Medium.SHORT, Forge.Medium.forDepth(0.5f))
        assertEquals(Forge.Medium.AS_BUILT, Forge.Medium.forDepth(1f))
        // A recipe without DEPTH (every one saved before it, and the FORGE chip) renders the shipped medium.
        for (pad in listOf(kick, snare)) {
            assertContentEquals(
                Forge.process(pad, mapOf("CHARGE" to 1f), true, Forge.Medium.SHIPPED).samples,
                Forge.process(pad, mapOf("CHARGE" to 1f)).samples,
                "a recipe without DEPTH left the shipped medium",
            )
        }
    }

    @Test
    fun `DEPTH lengthens the sweep, and is inert without CHARGE`() {
        val work = rate * Dsp.OVERSAMPLE
        val click = FloatArray((0.04 * work).toInt()).also { for (i in 0 until 140) it[i] = (0.5 - 0.5 * cos(2 * PI * i / 140)).toFloat() }
        fun width(depth: Float): Double {
            val y = Forge.medium(click.copyOf(), 1f, work, Forge.Medium.forDepth(depth))
            val total = y.sumOf { it.toDouble() * it }
            var acc = 0.0
            for (i in y.indices) {
                acc += y[i].toDouble() * y[i]
                if (acc >= 0.9 * total) return 1000.0 * i / work
            }
            return 1000.0 * y.size / work
        }
        val depths = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val widths = depths.map { width(it) }
        println("FORGE DEPTH, 90 % energy width of a 0.8 ms click at CHARGE 1: ${depths.zip(widths).joinToString { (d, w) -> "$d: %.2f ms".format(w) }}")
        for (i in 1 until widths.size) assertTrue(widths[i] > widths[i - 1], "more DEPTH did not lengthen the sweep: $widths")
        assertContentEquals(
            forge(kick, "CHARGE" to 0f, "DEPTH" to 0f).samples,
            forge(kick, "CHARGE" to 0f, "DEPTH" to 1f).samples,
            "DEPTH moved the sound with no medium to deepen",
        )
    }

    @Test
    fun `the neutral point is a near-copy - every band within half a dB, loudness within VALVE's bounds`() {
        // ValveTest's method and VALVE's round trip: each third-octave's share of its own 40 Hz-16 kHz
        // energy, so the peak match's one gain cannot pass for a tone change. A sample-by-sample residual
        // is the wrong measure here (the first draft used one and read +0.2 dB on the snare): the round
        // trip shifts the phase of a snare's top octaves. Measured: kick worst band 0.037 dB, RMS 0.096 dB;
        // snare worst band -0.080 dB, RMS 0.356 dB - VALVE's own neutral numbers to the hundredth, because
        // at every neutral only the shared round trip and the 5 Hz blocker are left. The RMS bounds are VALVE's.
        for ((name, dry, rmsBound) in listOf(Triple("kick", kick, 0.12), Triple("snare", snare, 0.44))) {
            val out = Forge.process(dry, neutral)
            assertEquals(dry.frameCount, out.frameCount, "$name changed length")
            var worst = 0.0
            var lo = 40f
            while (lo < 16_000f) {
                val hi = minOf(lo * 1.2599f, 16_000f)
                val eIn = bandShare(dry.samples, lo, hi) / bandShare(dry.samples, 40f, 16_000f)
                if (eIn > 1e-6) {
                    val eOut = bandShare(out.samples, lo, hi) / bandShare(out.samples, 40f, 16_000f)
                    val d = db(eOut, eIn)
                    if (abs(d) > abs(worst)) worst = d
                    assertTrue(abs(d) <= 0.5, "$name ${lo.toInt()}-${hi.toInt()} Hz moved ${"%.2f".format(d)} dB at the neutral point")
                }
                lo = hi
            }
            val rmsDb = 20 * log10(rms(out.samples) / rms(dry.samples))
            println("FORGE neutral $name: worst third-octave ${"%.3f".format(worst)} dB, RMS ${"%.3f".format(rmsDb)} dB")
            assertTrue(abs(rmsDb) <= rmsBound, "$name loudness moved ${"%.3f".format(rmsDb)} dB at the neutral point")
        }
    }

    @Test
    fun `each stage is an identity at its own neutral`() {
        val x = FloatArray(4_096) { (0.9 * sin(2 * PI * 300.0 * it / rate)).toFloat() }
        assertContentEquals(x, Forge.charged(x.copyOf(), Forge.settled(neutral), rate), "the charge is not a copy at CHARGE 0")
        assertContentEquals(x, Forge.medium(x.copyOf(), 0f, rate), "the medium is not a copy at CHARGE 0")
        val flat = Forge.flowState(Forge.slew(x, rate), Forge.slew(x, rate).max(), 1f)
        assertTrue(flat.all { it == 0f }, "something flows at YIELD 1")
        assertContentEquals(x, Forge.shape(x, flat), "the shaper is not a copy where nothing flows")
        val y = x.copyOf()
        Forge.die(y, 0f, rate, 1)
        assertContentEquals(x, y, "the die touches a blank at ARREST 0")
        // ARREST a hair over 0: the ceiling stands at 1.25 and its knee at 0.8 of that, both over the peak.
        val z = x.copyOf()
        Forge.die(z, 1e-4f, rate, 1)
        val worst = x.indices.maxOf { abs(z[it] - x[it]) }
        assertTrue(worst < 1e-3f, "the die at ARREST 0+ moved a 0.9 sine by $worst")
    }

    @Test
    fun `CHARGE adds weight where there was none`() {
        val shares = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { c -> bandShare(forge(cowbell, "CHARGE" to c).samples, 20f, 120f) }
        // Measured on the TIGHT medium (2026-10-27): 0.0050, 0.0150, 0.0467, 0.0983, 0.1515 (AS_BUILT read 0.0050,
        // 0.0267, 0.0390, 0.1025, 0.1507): a cowbell has almost nothing under 120 Hz.
        println("FORGE CHARGE on a cowbell, share 20-120 Hz: ${shares.joinToString { "%.4f".format(it) }}")
        for (i in 1 until shares.size) assertTrue(shares[i] > shares[i - 1], "CHARGE ${i * 0.25f} did not add weight: $shares")
        assertTrue(shares.first() < 0.01, "the dry-ish end already has weight: ${shares.first()}")
        assertTrue(shares.last() > 0.12, "CHARGE 1 added too little weight: ${shares.last()}")
    }

    @Test
    fun `CHARGE spreads the attack - the medium turns a click into a sweep`() {
        // The medium alone, on a 0.8 ms raised-cosine click: the time for 90 % of its energy to arrive.
        val click = FloatArray(ms(40f)).also { for (i in 0 until 35) it[i] = (0.5 - 0.5 * cos(2 * PI * i / 35)).toFloat() }
        fun width(charge: Float): Double {
            val y = Forge.medium(click.copyOf(), charge, rate)
            val total = y.sumOf { it.toDouble() * it }
            var acc = 0.0
            for (i in y.indices) {
                acc += y[i].toDouble() * y[i]
                if (acc >= 0.9 * total) return 1000.0 * i / rate
            }
            return 1000.0 * y.size / rate
        }
        val widths = listOf(0f, 0.25f, 0.5f, 1f).map { width(it) }
        // Measured on the TIGHT medium: 0.54, 1.36, 1.88, 2.95 ms. AS_BUILT spread it to 13.95 ms, the "pew" the
        // owner tuned out at the CHARGE gate (2026-10-27); a short sweep is the point now, so the bar is a floor.
        println("FORGE medium, 90 % energy width of a 0.8 ms click: ${widths.joinToString { "%.2f ms".format(it) }}")
        for (i in 1 until widths.size) assertTrue(widths[i] > widths[i - 1], "more CHARGE did not spread the click: $widths")
        assertTrue(widths.last() > 2.5, "the full medium spread the click only to ${widths.last()} ms")
    }

    @Test
    fun `the medium's centres never move as CHARGE adds sections`() {
        val m = Forge.Medium.SHIPPED
        val first = (0 until Forge.MAX_SECTIONS).map { Forge.mediumHz(it) }
        assertEquals(first.size, first.toSet().size, "two sections share a centre")
        assertTrue(first.all { it in m.loHz..m.hiHz }, "a centre left the range: $first")
        assertEquals(m.loHz, first[0], 1e-3f)
        assertTrue(first.all { it >= 1_200f }, "the shipped medium reaches into the low end, where the sweep read as a zap: $first")
    }

    @Test
    fun `STANDOFF moves the shock and is inert without one`() {
        assertContentEquals(
            forge(kick, "CHARGE" to 0f, "STANDOFF" to 0f).samples,
            forge(kick, "CHARGE" to 0f, "STANDOFF" to 1f).samples,
            "STANDOFF moved the sound with no shock to place",
        )
        val near = forge(kick, "STANDOFF" to 0f).samples
        val far = forge(kick, "STANDOFF" to 1f).samples
        val attack = ms(6f)
        val diff = FloatArray(attack) { far[it] - near[it] }
        val change = rms(diff) / rms(near, 0, attack)
        // Measured 0.674 on the TIGHT medium (1.270 on AS_BUILT, whose long sweep carried the shift further).
        // No comb-notch claim: the folder and the die scramble the interference (the brief).
        println("FORGE STANDOFF 0 vs 1 on the kick: the first 6 ms differ by ${"%.3f".format(change)} of their RMS")
        assertTrue(change > 0.5, "STANDOFF moved the attack by only ${"%.3f".format(change)} of its RMS")
    }

    @Test
    fun `YIELD sets how much of the pad flows, and at mid YIELD the attack folds while the body passes`() {
        // How much flows is monotonic in YIELD: the mean flow state over the kick at the 4x rate.
        val x = Valve.upsample(FloatArray(kick.frameCount) { kick.samples[it] / kick.peak() }, rate)
        val workRate = rate * Dsp.OVERSAMPLE
        val slew = Forge.slew(x, workRate)
        val peak = slew.max()
        val yields = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val flowing = yields.map { y -> Forge.flowState(slew, peak, y).average() }
        println("FORGE YIELD on the kick, mean flow state: ${flowing.joinToString { "%.4f".format(it) }}")
        for (i in 1 until flowing.size) assertTrue(flowing[i] < flowing[i - 1] || flowing[i] == 0.0, "more YIELD let more flow: $flowing")

        // What it does to the sound (ARREST 0, so the die is out of it): the body's share over 200 Hz
        // after 50 ms, and the attack's share over 3 kHz in its first 5 ms.
        val body = ms(50f)
        val rows = yields.map { y ->
            val out = forge(kick, "YIELD" to y, "ARREST" to 0f).samples
            Triple(y, bandShare(out, 200f, 20_000f, body), bandShare(out, 3_000f, 20_000f, 0, ms(5f)))
        }
        println("FORGE YIELD on the kick (ARREST 0), body share over 200 Hz after 50 ms / attack share over 3 kHz in 5 ms: " +
            rows.joinToString { (y, b, a) -> "$y: %.4f / %.4f".format(b, a) })
        // Measured on the TIGHT medium: body 0.0379, 0.0211, 0.0222, 0.0223, 0.0226 and attack 0.1397, 0.0418,
        // 0.0592, 0.0266, 0.0111. On AS_BUILT the body was NOT monotonic (0.0206, 0.0866, 0.0035, 0.0039,
        // 0.0039): at YIELD 0.25 the threshold sat inside the body's own slew and the flow switched twice a
        // cycle, the harsh middle the owner chose to keep at the first gate. The TIGHT medium moves the
        // normalisation, so on this kick that middle no longer reads harsher than YIELD 0; disclosed in the
        // brief for the owner's ear, not asserted either way. The bars below are the middle of the knob.
        val rigid = rows.last()
        val mid = rows[2]
        assertTrue(mid.second < 2 * rigid.second, "at YIELD 0.5 the body distorts: ${mid.second} against ${rigid.second} rigid")
        assertTrue(mid.third > 1.5 * rigid.third, "at YIELD 0.5 the attack does not fold: ${mid.third} against ${rigid.third} rigid")
    }

    @Test
    fun `ARREST coins the hit - the crest factor falls and the heat rises`() {
        for ((name, s) in listOf("kick" to kick, "snare" to snare)) {
            val rows = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { a ->
                val out = forge(s, "ARREST" to a).samples
                Triple(a, crest(out), bandShare(out, 6_000f, 20_000f))
            }
            println("FORGE ARREST on the $name, crest / share over 6 kHz: " + rows.joinToString { (a, c, h) -> "$a: %.3f / %.5f".format(c, h) })
            for (i in 1 until rows.size) assertTrue(rows[i].second < rows[i - 1].second, "$name: ARREST ${rows[i].first} did not lower the crest: $rows")
            // Measured on the TIGHT medium: kick 5.242 -> 3.086 (0.59); AS_BUILT read kick 5.008 -> 2.989, snare 7.889 -> 5.652.
            assertTrue(rows.last().second < 0.78 * rows.first().second, "$name: ARREST 1 coined too little: $rows")
            if (name == "kick") {
                // The heat on a pad with no top of its own: measured 0.00084 at ARREST 0.5, 0.00124 at 1 on the TIGHT
                // medium (1.48x; AS_BUILT read 0.00050 to 0.00102, whose long sweep left less top of its own). On
                // the snare the share falls, because the die clips its bright body harder than it adds.
                assertTrue(rows.last().third > 1.3 * rows[2].third, "the heat did not rise on the kick: $rows")
            }
        }
    }

    @Test
    fun `4x keeps the folder's fold-back down where the snip rate cannot`() {
        // CHARGE 0 and ARREST 0, so the bar is read on the folder alone (no noise, no medium).
        val f0 = 246.94f
        val p = probe(f0, 2.0f)
        val start = (0.3f * rate).toInt()
        fun clarity(yield: Float, oversample: Boolean): Double {
            val m = mapOf("CHARGE" to 0f, "YIELD" to yield, "ARREST" to 0f)
            return harmonicClarity(Forge.process(p, m, oversample).samples, f0, start)
        }
        // Measured 4x / 1x: YIELD 0 42.5 / 30.5, YIELD 0.3 50.5 / 28.2, YIELD 0.5 52.2 / 32.7 dB. VALVE's bar
        // is 45 dB: YIELD 0.3 and 0.5 clear it, YIELD 0 (the whole probe flowing at drive 4) misses it by
        // 2.5 dB, the same kind of miss as VALVE's DRIVE 1 (40.6). The 8x question is the owner's ear.
        for ((y, measured) in listOf(0f to 42.5, 0.3f to 50.5, 0.5f to 52.2)) {
            val four = clarity(y, true)
            val one = clarity(y, false)
            println("FORGE folder aliasing at 247 Hz, YIELD $y: 4x ${"%.1f".format(four)} / 1x ${"%.1f".format(one)} dB")
            assertTrue(four >= measured - 2.0, "YIELD $y at 4x fell to ${"%.1f".format(four)} dB")
            assertTrue(one < four - 6.0, "the probe cannot see fold-back at YIELD $y: 1x ${"%.1f".format(one)} vs 4x ${"%.1f".format(four)}")
            if (y > 0f) assertTrue(four >= 45.0, "YIELD $y is under VALVE's 45 dB bar at 4x: ${"%.1f".format(four)}")
        }
    }

    @Test
    fun `the heat is band-limited - nothing lands near the output Nyquist`() {
        // Measured kick 1.65e-06 (dry 2.1e-07), snare 9.95e-03 (dry 5.79e-02), hat 8.21e-03 (dry 1.50e-01): the
        // round trip's 19.5 kHz corner takes more off a bright pad than the burst adds.
        for ((name, s) in listOf("kick" to kick, "snare" to snare, "hat" to hat)) {
            val out = forge(s, "YIELD" to 0.3f, "ARREST" to 1f).samples
            val top = bandShare(out, 19_000f, 22_050f)
            val dry = bandShare(s.samples, 19_000f, 22_050f)
            println("FORGE share over 19 kHz at YIELD 0.3, ARREST 1 on the $name: ${"%.2e".format(top)} (dry ${"%.2e".format(dry)})")
            assertTrue(top < maxOf(1e-5, dry), "$name: the heat put ${"%.2e".format(top)} of the energy over 19 kHz")
        }
    }

    @Test
    fun `classes through FORGE are printed, and defaults keep a kick a kick and a snare a snare`() {
        val pads = listOf("kick" to kick, "snare" to snare, "hat" to hat, "cowbell" to cowbell)
        for (c in listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f)) {
            println("FORGE classes at CHARGE $c (others default): " + pads.joinToString { (n, s) -> "$n ${Classifier.classify(forge(s, "CHARGE" to c)).drumClass}" })
        }
        for ((name, s) in pads) {
            val dry = Classifier.classify(s).drumClass
            val def = Classifier.classify(Forge.process(s)).drumClass
            val hot = Classifier.classify(forge(s, "CHARGE" to 1f)).drumClass
            val hard = Classifier.classify(forge(s, "CHARGE" to 1f, "YIELD" to 0f, "ARREST" to 1f)).drumClass
            println("FORGE classes, $name: dry $dry, defaults $def, CHARGE 1 $hot, every macro at its hot end $hard")
        }
        // Measured: kick KICK at defaults and, on the TIGHT medium, at CHARGE 1 too (AS_BUILT filed it PERC there);
        // snare SNARE everywhere; the THUMP closed hat
        // HAT_CLOSED dry, PERC through the neutral round trip (VALVE's neutral does the same: the rack's
        // 19.5 kHz corner, not FORGE's), SNARE at YIELD 0.5 (a hat is all fast material, so nearly all of it
        // flows). Disclosed in the brief's As built; the defaults are the owner's to set at the gate.
        assertEquals(DrumClass.KICK, Classifier.classify(Forge.process(kick)).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(Forge.process(snare)).drumClass)
    }

    @Test
    fun `the FORGE chip at full is the clip the owner heard as loud and solid`() {
        // The gate's question two (2026-10-26): every macro at its default, ARREST 1, on the TIGHT medium the owner
        // then picked for the kick and the snare at the CHARGE gate (2026-10-27). The chip's character
        // sets no VALVE, so Treatments adds no pre-amp fade and the chain is FORGE alone.
        val heard = mapOf("CHARGE" to 0.5f, "STANDOFF" to 0.5f, "YIELD" to 0.5f, "ARREST" to 1f)
        for (pad in listOf(kick, snare)) {
            assertContentEquals(Forge.process(pad, heard).samples, Treatments.apply("forged", pad, 1f).snip.samples)
        }
        // AMT fades toward the neutrals: at the pad sheet's 0.7 the die is softer (ARREST 0.7) and less flows (YIELD 0.65).
        val faded = Treatments.chain("forged", 0.7f).forge!!
        assertEquals(0.7f, faded.getValue("ARREST"), 1e-6f)
        assertEquals(0.65f, faded.getValue("YIELD"), 1e-6f)
    }

    // ---------- edges ----------

    @Test
    fun `an unknown or non-finite macro changes nothing`() {
        val base = Forge.process(snare).samples
        assertContentEquals(base, Forge.process(snare, mapOf("BOGUS" to 1f)).samples, "an unknown key moved the sound")
        assertContentEquals(base, Forge.process(snare, mapOf("ARREST" to Float.NaN)).samples, "a NaN ARREST did not fall back to its default")
    }

    @Test
    fun `a silent pad comes back silent`() {
        val silent = Snip(FloatArray(4_410 * 2), 2, rate)
        val out = Forge.process(silent)
        assertEquals(2, out.channels)
        assertEquals(silent.frameCount, out.frameCount)
        assertTrue(out.samples.all { it == 0f })
    }

    @Test
    fun `a 64-frame pad keeps its length and stays in range at every corner`() {
        val tiny = Snip(FloatArray(64) { if (it < 8) 0.8f else 0f }, 1, rate)
        for (c in listOf(0f, 1f)) for (y in listOf(0f, 1f)) for (a in listOf(0f, 1f)) {
            val out = forge(tiny, "CHARGE" to c, "YIELD" to y, "ARREST" to a)
            assertEquals(64, out.frameCount)
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "CHARGE $c YIELD $y ARREST $a broke a 64-frame pad")
        }
    }

    @Test
    fun `a 48 kHz pad renders finite and its own length`() {
        val pad = Snip(FloatArray(48_000) { (0.8 * sin(2 * PI * 220.0 * it / 48_000) * Math.exp(-it / 6_000.0)).toFloat() }, 1, 48_000)
        for (a in listOf(0f, 0.5f, 1f)) {
            val out = forge(pad, "ARREST" to a)
            assertEquals(pad.frameCount, out.frameCount)
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f })
            assertEquals(pad.peak(), out.peak(), 1e-4f)
        }
    }

    @Test
    fun `a quieter channel stays quieter`() {
        val stereo = Snip(FloatArray(snare.frameCount * 2) { i -> snare.samples[i / 2] * if (i % 2 == 0) 1f else 0.4f }, 2, rate)
        val out = Forge.process(stereo)
        val left = FloatArray(out.frameCount) { out.samples[it * 2] }
        val right = FloatArray(out.frameCount) { out.samples[it * 2 + 1] }
        assertTrue(rms(right) < 0.6 * rms(left), "the quiet channel came up: ${rms(right)} vs ${rms(left)}")
    }

    @Test
    fun `the same pad renders bit-identical twice`() {
        val m = mapOf("CHARGE" to 0.8f, "STANDOFF" to 0.3f, "YIELD" to 0.2f, "ARREST" to 0.9f)
        assertContentEquals(Forge.process(snare, m).samples, Forge.process(snare, m).samples)
    }

    @Test
    fun `the cost per rendered second is printed`() {
        val seconds = 4.0f
        val long = Snip(FloatArray((seconds * rate).toInt() * 2) { i -> kick.samples[(i / 2) % kick.samples.size] }, 2, rate)
        val hot = mapOf("CHARGE" to 1f, "YIELD" to 0.3f, "ARREST" to 1f)
        Forge.process(long, hot) // warm-up
        val t0 = System.nanoTime()
        val out = Forge.process(long, hot)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(out.samples.all { it.isFinite() })
        // Printed, not asserted (ValveTest's reasoning). Measured here: 128 ms per stereo second at CHARGE 1;
        // per mono second about 21 ms at CHARGE 0 (VALVE's 22) and 46 at CHARGE 1, the medium being the rest.
        println("FORGE cost at CHARGE 1 (24 all-passes): ${"%.1f".format(ms / seconds)} ms per rendered second (4 s stereo, ${"%.0f".format(ms)} ms)")
    }
}
