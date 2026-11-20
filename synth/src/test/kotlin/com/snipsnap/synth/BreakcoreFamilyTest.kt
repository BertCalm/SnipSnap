package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class BreakcoreFamilyTest {
    private val kits = listOf<BreakcoreKit>(Shatter, Soundclash, Redline, Lull, Strobe, Cartridge, Barricade)

    @Test fun `every pad has timbral velocity, fresh takes, and is deterministic`() {
        for (kit in kits) for (p in kit.layout.indices) {
            val name = "${kit.id} ${kit.layout[p].name}"
            val hard = kit.renderPad(p); val medium = kit.renderPad(p, .65); val soft = kit.renderPad(p, .3)
            assertContentEquals(hard.samples, kit.renderPad(p).samples, "$name is not deterministic")
            assertTrue(soft.peak() < medium.peak() && medium.peak() < hard.peak(), "$name velocity peaks ${soft.peak()} ${medium.peak()} ${hard.peak()}")
            val n = min(soft.samples.size, hard.samples.size)
            val distance = (0 until n).sumOf { (soft.samples[it] / soft.peak() - hard.samples[it] / hard.peak()).toDouble().pow(2) }
            assertTrue(distance > 1e-4, "$name velocity changes only gain")
            for (t in 1..2) assertFalse(hard.samples.contentEquals(kit.renderPad(p, 1.0, t).samples), "$name cloned take $t")
            assertTrue(hard.peak() in .25f..1f, "$name full-force peak ${hard.peak()}")
            assertTrue(abs(hard.samples.average()) < .005, "$name DC")
            assertTrue(hard.samples.last() == 0f, "$name tail")
            assertTrue(kit.renderPad(p, 0.0).samples.all { it == 0f }, "$name sounds at zero force")
        }
    }

    @Test fun `the family has eight kits with distinct ids and tempos and eighteen pads each`() {
        // Every kit that exists: Splinter, Shatter, Soundclash, Redline, Lull, Strobe, Cartridge, Barricade.
        val all = kits + Splinter
        assertEquals(setOf(Splinter, Shatter, Soundclash, Redline, Lull, Strobe, Cartridge, Barricade), all.toSet())
        assertEquals(8, all.map { it.id }.toSet().size)
        assertEquals(8, all.map { it.bpm }.toSet().size)
        assertTrue(all.all { it.layout.size == 18 && it.layout[16].kind == com.snipsnap.audio.DrumClass.LOOP && it.layout[17].kind == com.snipsnap.audio.DrumClass.LOOP })
    }

    @Test fun `shatter slices are windows of one take, so they carry what was sounding`() {
        // Slice One of the break (Ghost, a single ghost note) still has the ride and kick tail under it:
        // its first millisecond is not silence, unlike an isolated hit.
        val ghost = Shatter.renderPad(1).samples
        val head = (0 until 44).maxOf { abs(ghost[it]) }
        assertTrue(head > 1e-3, "the ghost slice starts in silence: no bleed")
        // Pads tile the two bars: the first fifteen pads end where the next begins.
        val beat = Breakcore.beat(Shatter.bpm)
        assertEquals(beat * 3 / 4, Shatter.renderPad(0).frameCount.toDouble(), .006 * Dsp.RATE)
    }

    @Test fun `redline kicks sit on their notes`() {
        // The tuned kicks' bodies, after the dive, hold root, minor third, fifth and octave.
        for ((pad, semis) in listOf(0 to 0, 1 to 3, 2 to 7, 3 to 12)) {
            val x = Redline.renderPad(pad, .3).samples.copyOfRange(Breakcore.frames(.06), Breakcore.frames(.3))
            val crossings = x.toList().zipWithNext().count { (a, b) -> a <= 0f && b > 0f }
            val hz = crossings / (x.size.toDouble() / Dsp.RATE)
            assertEquals(Redline.ROOT_HZ * 2.0.pow(semis / 12.0), hz, 6.0, "pad $pad")
        }
    }

    @Test fun `the spin-back runs backwards`() {
        // Rewind: after the grab the read speed is negative, so the output holds material from
        // before the grab point played in reverse; it must not be silent after the grab.
        val x = Soundclash.renderPad(9).samples
        val after = x.copyOfRange(Breakcore.frames(.5), Breakcore.frames(.8))
        assertTrue(after.maxOf { abs(it) } > .05f)
    }

    private fun crossings(x: FloatArray, from: Int, to: Int): Int = (from + 1 until to).count { (x[it - 1] <= 0f) != (x[it] <= 0f) }

    @Test fun `lull piano stop falls in pitch like a tape machine stopping`() {
        for ((v, t) in listOf(1.0 to 0, .3 to 0, .65 to 1, 1.0 to 2)) {
            val x = Lull.renderPad(14, v, t).samples
            // Sounding material: from the first to the last sample within 2% of the peak.
            val floor = .02f * x.maxOf { abs(it) }
            val a = x.indexOfFirst { abs(it) > floor }; val b = x.indexOfLast { abs(it) > floor } + 1
            val third = (b - a) / 3
            val first = crossings(x, a, a + third); val last = crossings(x, b - third, b)
            assertTrue(first > 50, "v=$v take $t: first third has only $first crossings")
            assertTrue(last * 2 < first, "v=$v take $t: zero crossings first third $first, last third $last")
        }
    }

    @Test fun `lull fading repeat slows down and its onset gaps grow`() {
        for ((v, t) in listOf(1.0 to 0, .65 to 1, 1.0 to 2)) {
            val planned = Lull.repeatOnsets(v, t)
            val x = Lull.renderPad(16, v, t).samples
            val gaps = planned.zipWithNext { a, b -> b - a }
            assertTrue(gaps.size >= 4 && gaps.zipWithNext().all { (a, b) -> b > a }, "v=$v take $t: gaps $gaps")
            // Each repeat chokes the one before it: near silence in the millisecond before an onset, an attack after it.
            for (at in planned.drop(1)) {
                val before = (at - Breakcore.frames(.001) until at).maxOf { abs(x[it]) }
                val after = (at until at + Breakcore.frames(.03)).maxOf { abs(x[it]) }
                assertTrue(before < .3 * after, "v=$v take $t: no attack at $at ($before before, $after after)")
            }
            // And each repeat is quieter than the last, over the 60 ms that follow its onset.
            val heard = planned
            val peaks = heard.map { at -> (at until at + Breakcore.frames(.06)).maxOf { abs(x[it]) } }
            assertTrue(peaks.zipWithNext().all { (a, b) -> b < a }, "v=$v take $t: peaks $peaks")
        }
    }

    /** The pitch of [x] from [from], over [len] frames: the lag of the highest normalized autocorrelation between 20 and 600 Hz
     * (the earliest lag within 1% of the best). A stack of detuned saws with an octave sub has no clean zero crossings, and its
     * waveform repeats at the sub's period, so this reads the sub; it reads it the same way at both ends of a dive. */
    private fun pitchHz(x: FloatArray, from: Int, len: Int): Double {
        val lo = Dsp.RATE / 600; val hi = Dsp.RATE / 20
        val energy = { a: Int -> (a until a + len).sumOf { x[it].toDouble() * x[it] } }
        val e0 = energy(from)
        val c = DoubleArray(hi + 1) { lag ->
            if (lag < lo) 0.0 else (from until from + len).sumOf { x[it].toDouble() * x[it + lag] } / sqrt(e0 * energy(from + lag))
        }
        var best = lo; for (l in lo..hi) if (c[l] > c[best] * 1.01) best = l
        return Dsp.RATE.toDouble() / best
    }

    @Test fun `strobe whine dive ends an octave below where it starts`() {
        for ((v, t) in listOf(1.0 to 0, .3 to 0, .65 to 1, 1.0 to 2)) {
            val x = Strobe.renderPad(1, v, t).samples
            // The scoop is over by 80 ms and the dive starts at 150 ms; it has finished by 564 ms.
            val start = pitchHz(x, Breakcore.frames(.085), 3000); val end = pitchHz(x, Breakcore.frames(.64), 3000)
            assertEquals(2.0, start / end, .2, "v=$v take $t: started at $start Hz, ended at $end Hz")
        }
    }

    @Test fun `strobe sped break is one bar at 145`() {
        val bar = 4 * Breakcore.beat(145)
        for (t in 0..2) assertEquals(bar, Strobe.renderPad(15, 1.0, t).frameCount.toDouble(), .01 * bar, "take $t")
        // It was played at 115 and is read faster: its pitch rose by about 4 semitones.
        assertEquals(4.0, 12 * log2(Strobe.bpm.toDouble() / Strobe.BREAK_PLAYED_BPM), .1)
    }

    @Test fun `cartridge triangle has exactly sixteen levels per cycle before decimation`() {
        // 441 Hz is 100 base-rate frames a cycle, so 400 samples a cycle at 4x.
        val up = Cartridge.triangleUp(1000, 0.0, { 441.0 }, { 15 })
        assertEquals(1000 * Dsp.OVERSAMPLE, up.size)
        for (c in 0 until 9) assertEquals(16, up.copyOfRange(c * 400, (c + 1) * 400).toSet().size, "cycle $c")
        assertEquals(16, up.toSet().size)
        // The helper itself: 32 steps a cycle, each of the 16 levels met twice, once rising and once falling.
        val levels = (0 until 32).map { Cartridge.triLevel((it + .5) / 32) }
        assertEquals((0..15).toList(), levels.toSet().sorted())
        assertTrue(levels.groupingBy { it }.eachCount().values.all { it == 2 })
        // A volume step scales the levels; it does not add levels.
        assertEquals(16, Cartridge.triangleUp(1000, 0.0, { 441.0 }, { 7 }).toSet().size)
    }

    @Test fun `cartridge shift register loops 93 steps in short mode and 32767 in long mode`() {
        assertEquals(93, Cartridge.lfsrPeriod(true))
        assertEquals(32767, Cartridge.lfsrPeriod(false))
    }

    private fun goertzel(x: FloatArray, from: Int, len: Int, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in 0 until len) { val a = 2 * PI * hz * i / Dsp.RATE; re += x[from + i] * cos(a); im += x[from + i] * sin(a) }
        return re * re + im * im
    }

    @Test fun `cartridge arp cycles three pitches one per sixty hertz frame`() {
        val schedule = Cartridge.arpSchedule(12, 72, intArrayOf(0, 3, 7))
        assertContentEquals(intArrayOf(72, 75, 79, 72, 75, 79, 72, 75, 79, 72, 75, 79), schedule)
        // The pad plays that schedule: in each chip frame the pitch that sounds is the scheduled one.
        val pitches = listOf(72, 75, 79).map { 440.0 * 2.0.pow((it - 69) / 12.0) }
        val frame = Dsp.RATE / Cartridge.FPS
        val x = Cartridge.renderPad(6, 1.0, 0).samples
        for (f in 2..9) {
            val from = f * frame + 60; val len = frame - 120
            val power = pitches.map { goertzel(x, from, len, it) }
            val want = schedule[f] - 72
            val k = listOf(0, 3, 7).indexOf(want)
            for (j in power.indices) if (j != k) assertTrue(power[k] > 4 * power[j], "frame $f: power at the scheduled pitch ${power[k]} vs ${power[j]}")
        }
    }

    @Test fun `cartridge noise roll lands its hits closer together`() {
        val gaps = Cartridge.rollOnsetBeats().zipWithNext { a, b -> b - a }
        assertEquals(35, gaps.size)
        assertTrue(gaps.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(gaps.first() > 3.9 * gaps.last())
    }

    /** The magnitude spectrum of the [n] (a power of two) samples of [x] from [from], Hann-windowed, by an in-place radix-2 FFT at the full rate. */
    private fun spectrum(x: FloatArray, from: Int, n: Int): DoubleArray {
        require(n and (n - 1) == 0 && from + n <= x.size)
        val re = DoubleArray(n) { x[from + it] * (.5 - .5 * cos(2 * PI * it / n)) }; val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b } }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi; val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi; re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
        return DoubleArray(n / 2) { hypot(re[it], im[it]) }
    }

    @Test fun `the spectrum helper finds a sine where it is`() {
        val x = FloatArray(32768) { sin(2 * PI * 1000.0 * it / Dsp.RATE).toFloat() }
        val m = spectrum(x, 0, 32768); val peak = m.indices.maxByOrNull { m[it] }!!
        assertEquals(1000.0, peak * Dsp.RATE.toDouble() / 32768, 2.0)
    }

    @Test fun `barricade power chord sits in the guitar band`() {
        for (v in listOf(1.0, .3)) {
            val x = Barricade.renderPad(0, v, 0).samples
            val m = spectrum(x, 0, 32768)
            var num = 0.0; var den = 0.0
            for (k in 1 until m.size) { num += m[k] * k * Dsp.RATE.toDouble() / 32768; den += m[k] }
            val centroid = num / den
            assertTrue(centroid in 400.0..3000.0, "v=$v: spectral centroid $centroid Hz")
        }
    }

    /** Autocorrelation pitch of [len] frames from [from], lags for 15 to 600 Hz, the earliest lag within 1% of the best. A power
     * chord is a root and its fifth, and the fifth (3:2) is half a cycle out at the root's lag, so the waveform repeats at twice
     * the root's period: this reads E1 (41 Hz) for E2 and E0 (20.6 Hz) for E1, the same way at both ends of the dive. */
    private fun chordPitchHz(x: FloatArray, from: Int, len: Int): Double {
        val lo = Dsp.RATE / 600; val hi = Dsp.RATE / 15
        val energy = { a: Int -> (a until a + len).sumOf { x[it].toDouble() * x[it] } }
        val e0 = energy(from)
        val c = DoubleArray(hi + 1) { lag -> if (lag < lo) 0.0 else (from until from + len).sumOf { x[it].toDouble() * x[it + lag] } / sqrt(e0 * energy(from + lag)) }
        var best = lo; for (l in lo..hi) if (c[l] > c[best] * 1.01) best = l
        return Dsp.RATE.toDouble() / best
    }

    /** The strongest spectral line between [lo] and [hi] Hz in the 16384 frames from [from]. */
    private fun lineHz(x: FloatArray, from: Int, lo: Double, hi: Double): Double {
        val m = spectrum(x, from, 16384); val bin = 16384.0 / Dsp.RATE
        return (ceil(lo * bin).toInt()..floor(hi * bin).toInt()).maxByOrNull { m[it] }!! / bin
    }

    @Test fun `barricade dive bomb falls about an octave from the chord's root`() {
        for ((v, t) in listOf(1.0 to 0, .3 to 0, .65 to 1, 1.0 to 2)) {
            val x = Barricade.renderPad(4, v, t).samples
            // The chord's root is E2 (82.4 Hz). It holds for 250 ms, falls over .8 s, and has settled by 1.05 s.
            val start = chordPitchHz(x, Breakcore.frames(.02), 8192); val end = chordPitchHz(x, Breakcore.frames(1.1), 8192)
            assertEquals(2.0, start / end, .2, "v=$v take $t: started at $start Hz, ended at $end Hz")
            // And the root itself, as a spectral line: E2 before the dive, E1 after it.
            val rootStart = lineHz(x, Breakcore.frames(.02), 60.0, 110.0); val rootEnd = lineHz(x, Breakcore.frames(1.1), 30.0, 55.0)
            assertEquals(82.4, rootStart, 6.0, "v=$v take $t: root line before the dive")
            assertEquals(41.2, rootEnd, 3.0, "v=$v take $t: root line after the dive")
        }
    }
}
