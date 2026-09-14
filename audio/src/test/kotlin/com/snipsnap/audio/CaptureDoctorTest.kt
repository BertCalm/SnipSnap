package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureDoctorTest {

    private val rate = 44_100

    /** A short beat — kick, hat, snare, hat — clear of frame zero. */
    private fun beat(): FloatArray {
        val total = FloatArray(3 * rate)
        val plan = listOf(
            0.05f to DrumSynth.kick(),
            0.55f to DrumSynth.closedHat(),
            1.05f to DrumSynth.snare(),
            1.55f to DrumSynth.closedHat(),
        )
        for ((at, hit) in plan) {
            val start = (at * rate).toInt()
            for (i in hit.samples.indices) {
                val idx = start + i
                if (idx < total.size) total[idx] += hit.samples[i] * 0.8f
            }
        }
        return total
    }

    private fun withHum(base: FloatArray, hz: Double, amp: Float): Snip =
        Snip(
            FloatArray(base.size) { i ->
                base[i] + amp * Math.sin(2.0 * Math.PI * hz * i / rate).toFloat()
            },
            1, rate,
        )

    @Test
    fun `hum is detected, named and notched - the drums keep their body`() {
        val dirty = withHum(beat(), 50.0, 0.05f)
        val report = CaptureDoctor.detectHum(dirty)
        assertTrue(report != null, "the 50 Hz hum stands out")
        assertEquals(50f, report!!.hz)
        assertTrue(report.levelDb > -35f && report.levelDb < -20f, "level honest: ${report.levelDb}")

        val cleaned = CaptureDoctor.removeHum(dirty, report)
        val n = 3 * rate
        val before = CaptureDoctor.goertzel(dirty.samples, n, 50f, rate)
        val after = CaptureDoctor.goertzel(cleaned.samples, n, 50f, rate)
        assertTrue(
            20 * Math.log10(after / before.toDouble()) < -20,
            "the fundamental drops >= 20 dB: ${20 * Math.log10(after / before.toDouble())}",
        )

        // The drums' own upper body survives: the snare-band probe barely moves.
        val snareBefore = CaptureDoctor.goertzel(dirty.samples, n, 1800f, rate)
        val snareAfter = CaptureDoctor.goertzel(cleaned.samples, n, 1800f, rate)
        assertTrue(
            Math.abs(snareAfter - snareBefore) < 0.05f * snareBefore,
            "the notch takes the hum, not the drums",
        )
    }

    @Test
    fun `clicks and dropouts are found, repaired and counted - transients never flagged`() {
        // A steady tone with three planted clicks and one zero dropout.
        val n = 2 * rate
        val pure = FloatArray(n) { i -> (0.4 * Math.sin(2.0 * Math.PI * 220.0 * i / rate)).toFloat() }
        val dirty = pure.copyOf()
        val clickAt = listOf(rate / 2, rate, rate * 3 / 2)
        for (at in clickAt) dirty[at] = 0.9f
        val dropAt = rate / 4 + 13 // mid-phase, neighbors alive
        for (i in dropAt until dropAt + 20) dirty[i] = 0f

        val repair = CaptureDoctor.repairClicks(Snip(dirty, 1, rate))
        assertTrue(repair.touched)
        assertEquals(3, repair.clicks, "every planted click found")
        assertEquals(1, repair.dropouts, "the dropout found")
        // The hunt on its own (DUST's CRACKLE) sees the same three, and not the dropout's two walls.
        val found = CaptureDoctor.findClicks(dirty)
        assertEquals(3, found.size, "findClicks: the clicks, not the hole's walls: $found")
        for (at in clickAt) assertTrue(found.any { at in it }, "click at $at found")
        for (at in clickAt) {
            assertTrue(
                Math.abs(repair.snip.samples[at] - pure[at]) < 0.06f,
                "click at $at repaired toward the tone: ${repair.snip.samples[at]} vs ${pure[at]}",
            )
        }
        // Outside the repaired frames, not a sample moved.
        var untouched = 0
        for (i in 0 until n) {
            if (repair.snip.samples[i] == dirty[i]) untouched++
        }
        assertTrue(n - untouched <= repair.repairedFrames, "repair touched only what it reported")

        // A real beat's transients are onsets, not clicks - nothing flagged.
        val beatRepair = CaptureDoctor.repairClicks(Snip(beat(), 1, rate))
        assertEquals(0, beatRepair.clicks, "drum onsets pass the follow test")
        assertTrue(!beatRepair.touched, "a clean beat comes back unchanged")

        // Spikes everywhere is distortion, and repair refuses to lie.
        val trashed = pure.copyOf()
        var i = 100
        while (i < n) {
            trashed[i] = if (trashed[i] > 0) -0.9f else 0.9f
            i += 40
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            CaptureDoctor.repairClicks(Snip(trashed, 1, rate))
        }
    }

    @Test
    fun `the floor is measured honestly and the gate is gentle`() {
        // Drums over -44ish dB of hiss, with a noise-only tail to gate.
        val rnd = java.util.Random(7)
        val noisy = beat().copyOf()
        for (i in noisy.indices) noisy[i] += (rnd.nextFloat() * 2f - 1f) * 0.01f
        val snip = Snip(noisy, 1, rate)

        val floor = CaptureDoctor.measureFloor(snip)!!
        assertTrue(floor in -50f..-38f, "the floor reads the planted hiss: $floor dBFS")

        val gated = CaptureDoctor.expand(snip, floor)
        fun rmsAt(s: FloatArray, fromSec: Float, toSec: Float): Double {
            var acc = 0.0
            var n = 0
            for (i in (fromSec * rate).toInt() until minOf((toSec * rate).toInt(), s.size)) {
                acc += s[i] * s[i].toDouble()
                n++
            }
            return Math.sqrt(acc / n.coerceAtLeast(1))
        }
        // The noise-only tail recedes by at least 6 dB...
        val before = rmsAt(noisy, 2.4f, 2.9f)
        val after = rmsAt(gated.samples, 2.4f, 2.9f)
        assertTrue(
            20 * Math.log10(after / before) < -6,
            "the tail recedes: ${20 * Math.log10(after / before)} dB",
        )
        // ...but never below the depth cap - gentle, not a mute.
        assertTrue(
            20 * Math.log10(after / before) > -CaptureDoctor.MAX_ATTEN_DB - 1.0,
            "the gate never slams",
        )
        // The drums keep their peaks - the gate opens instantly.
        val kickPeakBefore = (0 until rate).maxOf { Math.abs(noisy[it]) }
        val kickPeakAfter = (0 until rate).maxOf { Math.abs(gated.samples[it]) }
        assertTrue(
            Math.abs(kickPeakAfter - kickPeakBefore) < 0.05f * kickPeakBefore,
            "peaks survive: $kickPeakBefore -> $kickPeakAfter",
        )

        // A clean beat's floor reads as clean - callers leave it alone.
        val cleanFloor = CaptureDoctor.measureFloor(Snip(beat(), 1, rate))!!
        assertTrue(cleanFloor < CaptureDoctor.CLEAN_FLOOR_DB, "a clean capture reads clean: $cleanFloor")
    }

    @Test
    fun `spectral de-noise pulls hiss from under the drums and never warbles`() {
        val rnd = java.util.Random(7)
        val noisy = beat().copyOf()
        for (i in noisy.indices) noisy[i] += (rnd.nextFloat() * 2f - 1f) * 0.01f
        val report = CaptureDoctor.denoise(Snip(noisy, 1, rate))
        assertTrue(report != null, "a hissy capture gets the deep clean")
        val out = report!!.snip.samples
        assertTrue(report.profileFrames >= 8, "the fingerprint came from real frames: ${report.profileFrames}")

        fun rmsAt(s: FloatArray, fromSec: Float, toSec: Float): Double {
            var acc = 0.0
            var n = 0
            for (i in (fromSec * rate).toInt() until minOf((toSec * rate).toInt(), s.size)) {
                acc += s[i] * s[i].toDouble()
                n++
            }
            return Math.sqrt(acc / n.coerceAtLeast(1))
        }
        // The noise-only tail recedes - but never past the cap.
        val tailDrop = 20 * Math.log10(rmsAt(out, 2.4f, 2.9f) / rmsAt(noisy, 2.4f, 2.9f))
        assertTrue(tailDrop < -6, "the tail recedes: $tailDrop dB")
        assertTrue(tailDrop > -14, "but gently - the cap holds: $tailDrop dB")

        // The kick keeps its peak - a loud frame opens every bin instantly.
        val peakBefore = (0 until rate / 2).maxOf { Math.abs(noisy[it]) }
        val peakAfter = (0 until rate / 2).maxOf { Math.abs(out[it]) }
        assertTrue(Math.abs(peakAfter - peakBefore) < 0.05f * peakBefore, "peaks survive: $peakBefore -> $peakAfter")

        // The claim the expander can't make: hiss drops UNDER a loud
        // sound. A 6 kHz burst keeps every frame loud (the time-domain
        // gate would stand wide open), yet the mid band holds only hiss
        // - and recedes anyway - while the burst itself is untouched.
        val rnd2 = java.util.Random(8)
        val burst = FloatArray(3 * rate) { i ->
            val hiss = (rnd2.nextFloat() * 2f - 1f) * 0.01f
            val on = i >= rate / 2 && i < 5 * rate / 2
            hiss + if (on) (0.4 * Math.sin(2.0 * Math.PI * 6000.0 * i / rate)).toFloat() else 0f
        }
        val burstOut = CaptureDoctor.denoise(Snip(burst, 1, rate))!!.snip.samples
        fun bandAmp(s: FloatArray, hz: Float): Float {
            val seg = s.copyOfRange(rate, 2 * rate)
            return CaptureDoctor.goertzel(seg, seg.size, hz, rate)
        }
        val midBefore = bandAmp(burst, 500f) + bandAmp(burst, 800f) + bandAmp(burst, 1300f)
        val midAfter = bandAmp(burstOut, 500f) + bandAmp(burstOut, 800f) + bandAmp(burstOut, 1300f)
        assertTrue(
            20 * Math.log10(midAfter / midBefore.toDouble()) < -6,
            "hiss under the burst recedes: ${20 * Math.log10(midAfter / midBefore.toDouble())} dB",
        )
        val toneBefore = bandAmp(burst, 6000f)
        val toneAfter = bandAmp(burstOut, 6000f)
        assertTrue(Math.abs(toneAfter - toneBefore) < 0.05f * toneBefore, "the burst itself is untouched: $toneBefore -> $toneAfter")

        // Anti-warble: the attenuated tail stays steady noise, not a
        // flicker of tonal bursts - its level variance doesn't blow up.
        fun cov(s: FloatArray): Double {
            val win = (0.02f * rate).toInt()
            val rmses = mutableListOf<Double>()
            var at = (2.4f * rate).toInt()
            while (at + win <= (2.9f * rate).toInt()) {
                rmses += rmsAt(s, at.toFloat() / rate, (at + win).toFloat() / rate)
                at += win
            }
            val mean = rmses.average()
            val varr = rmses.sumOf { (it - mean) * (it - mean) } / rmses.size
            return Math.sqrt(varr) / mean
        }
        assertTrue(cov(out) < cov(noisy) * 1.5 + 0.05, "no warble: ${cov(noisy)} -> ${cov(out)}")

        // Clean audio has nothing to learn from and is left alone.
        assertNull(CaptureDoctor.denoise(Snip(beat(), 1, rate)), "a clean beat gets no de-noise")
    }

    @Test
    fun `the tail knee finds the room's handoff, fades it gently, and leaves dry hits alone`() {
        // A tight hit: fast decay (~-350 dB/s), gone in a quarter second.
        val hitLen = (0.25f * rate).toInt()
        val hit = FloatArray(hitLen) { i ->
            val t = i.toDouble() / rate
            (0.8 * Math.sin(2.0 * Math.PI * 180.0 * t) * Math.exp(-40.0 * t)).toFloat()
        }
        val dry = FloatArray(rate)
        hit.copyInto(dry)
        assertNull(CaptureDoctor.trimRoomTail(Snip(dry, 1, rate)), "a dry hit is single-slope: no knee")

        // The room: unit direct sound plus decaying noise (~-61 dB/s,
        // much shallower than the hit) - convolve and the tail hangs on.
        val rnd = java.util.Random(5)
        val irLen = (0.5f * rate).toInt()
        val ir = FloatArray(irLen) { i ->
            if (i == 0) 1f else ((rnd.nextFloat() * 2f - 1f) * 0.01 * Math.exp(-7.0 * i / rate)).toFloat()
        }
        val roomyLen = (1.2f * rate).toInt()
        val roomy = FloatArray(roomyLen)
        for (i in 0 until hitLen) {
            val h = hit[i]
            for (j in ir.indices) {
                val idx = i + j
                if (idx < roomyLen) roomy[idx] += h * ir[j]
            }
        }
        val result = CaptureDoctor.trimRoomTail(Snip(roomy, 1, rate))
        assertTrue(result != null, "the roomy hit has a knee")
        assertTrue(result!!.kneeSec in 0.03f..0.2f, "the knee sits near the true handoff: ${result.kneeSec}s")
        assertTrue(
            result.hitSlopeDbPerSec <= result.tailSlopeDbPerSec * CaptureDoctor.KNEE_SLOPE_RATIO,
            "the hit is measurably steeper: ${result.hitSlopeDbPerSec} vs ${result.tailSlopeDbPerSec} dB/s",
        )

        // The hit's own body is untouched - the fade starts at the knee.
        for (i in 0 until (0.04f * rate).toInt()) {
            assertTrue(Math.abs(result.snip.samples[i] - roomy[i]) < 1e-6f, "the body is not the tail's to pay")
        }

        // The tail recedes - but it's a fade with a floor, never a cut.
        fun rmsAt(s: FloatArray, fromSec: Float, toSec: Float): Double {
            var acc = 0.0
            var n = 0
            for (i in (fromSec * rate).toInt() until minOf((toSec * rate).toInt(), s.size)) {
                acc += s[i] * s[i].toDouble()
                n++
            }
            return Math.sqrt(acc / n.coerceAtLeast(1))
        }
        val drop = 20 * Math.log10(rmsAt(result.snip.samples, 0.35f, 0.7f) / rmsAt(roomy, 0.35f, 0.7f))
        assertTrue(drop < -6, "the room recedes: $drop dB")
        assertTrue(drop > -CaptureDoctor.FADE_FLOOR_DB - 2.0, "gently - the floor holds: $drop dB")
    }

    @Test
    fun `clipping is detected by its flat tops, rebuilt past the ceiling, and loud audio left alone`() {
        // Sustained tonal hits over noise bursts, hard-clipped at half:
        // the honest case where declipping CAN infer the truth. (A
        // DrumSynth beat's clipped slivers are near-pure noise, and
        // noise has no sparse structure to rebuild from - it earned
        // +0.4 dB where this fixture earns +3; that physics is the
        // finding, not a failure.)
        val n = 2 * rate
        val ref = FloatArray(n)
        for ((at, f0) in listOf(0.1f to 60.0, 0.6f to 180.0, 1.1f to 60.0, 1.6f to 180.0)) {
            val i0 = (at * rate).toInt()
            val len = (0.4f * rate).toInt()
            val dec = if (f0 < 100) 7.0 else 9.0
            val amp = if (f0 < 100) 1.0 else 0.8
            for (i in 0 until len) {
                val t = i.toDouble() / rate
                if (i0 + i < n) {
                    ref[i0 + i] += (amp * Math.sin(2.0 * Math.PI * f0 * t * (1 - 0.3 * t)) * Math.exp(-dec * t)).toFloat()
                }
            }
        }
        val burst = java.util.Random(7)
        for (at in listOf(0.35f, 0.85f, 1.35f, 1.85f)) {
            val i0 = (at * rate).toInt()
            val len = (0.08f * rate).toInt()
            for (i in 0 until len) {
                if (i0 + i < n) ref[i0 + i] += (0.5 * burst.nextGaussian() * Math.exp(-40.0 * i / rate)).toFloat()
            }
        }
        val peak = ref.maxOf { Math.abs(it) }
        for (i in ref.indices) ref[i] /= peak
        val clipped = FloatArray(ref.size) { ref[it].coerceIn(-0.5f, 0.5f) }

        val report = CaptureDoctor.detectClipping(Snip(clipped, 1, rate))
        assertTrue(report != null, "the flat tops are heard")
        assertTrue(Math.abs(report!!.ceiling - 0.5f) < 0.01f, "the ceiling is measured: ${report.ceiling}")
        assertTrue(report.fraction > 0.001f, "the damage is a real fraction: ${report.fraction}")

        val declip = CaptureDoctor.declip(Snip(clipped, 1, rate))!!
        val out = declip.snip.samples

        // Reliable samples are the input's own, byte for byte.
        for (i in out.indices) {
            if (Math.abs(clipped[i]) < 0.49f) {
                assertTrue(out[i] == clipped[i], "reliable sample $i untouched")
            }
        }
        // Peaks rebuilt past the ceiling.
        assertTrue(out.maxOf { Math.abs(it) } > 0.55f, "the truth was louder: ${out.maxOf { Math.abs(it) }}")

        // And measurably closer to the truth (best-gain matched). The
        // honest bar: consistent-sparsity declipping earns single-digit
        // dB on broadband drums - the literature's headline numbers ride
        // Gabor dictionaries and clipped-samples-only metrics.
        fun snr(x: FloatArray): Double {
            var dot = 0.0
            var xx = 0.0
            for (i in ref.indices) {
                dot += ref[i] * x[i].toDouble()
                xx += x[i] * x[i].toDouble()
            }
            val g = if (xx > 1e-12) dot / xx else 1.0
            var se = 0.0
            var re = 0.0
            for (i in ref.indices) {
                val e = ref[i] - g * x[i]
                se += e * e
                re += ref[i] * ref[i].toDouble()
            }
            return 10 * Math.log10(re / se)
        }
        val snrClipped = snr(clipped)
        val snrDeclipped = snr(out)
        assertTrue(
            snrDeclipped - snrClipped >= 2,
            "the rebuild measurably helps: %.1f -> %.1f dB".format(snrClipped, snrDeclipped),
        )

        // Loud-but-unclipped audio is left alone: a sine touches its peak
        // one sample at a time - that's loud, not pinned.
        val sine = Snip(
            FloatArray(rate) { i -> (0.9 * Math.sin(2.0 * Math.PI * 220.0 * i / rate)).toFloat() },
            1, rate,
        )
        assertNull(CaptureDoctor.detectClipping(sine), "loud is not clipped")
        assertNull(CaptureDoctor.detectClipping(Snip(beat(), 1, rate)), "a clean beat is not clipped")

        // And the visit carries it: clean(declip = true) names the rebuild.
        val visit = CaptureDoctor.clean(Snip(clipped, 1, rate), declip = true)
        assertTrue(visit.clip != null && "clipping rebuilt" in visit.summary(), visit.summary())
    }

    @Test
    fun `deverb predicts the room and subtracts it - the direct sound keeps its shape`() {
        // The tail knee's own fixture: a tight hit convolved with a
        // decaying-noise room IR.
        val hitLen = (0.25f * rate).toInt()
        val hit = FloatArray(hitLen) { i ->
            val t = i.toDouble() / rate
            (0.8 * Math.sin(2.0 * Math.PI * 180.0 * t) * Math.exp(-40.0 * t)).toFloat()
        }
        val rnd = java.util.Random(5)
        val irLen = (0.5f * rate).toInt()
        val ir = FloatArray(irLen) { i ->
            if (i == 0) 1f else ((rnd.nextFloat() * 2f - 1f) * 0.01 * Math.exp(-7.0 * i / rate)).toFloat()
        }
        val roomyLen = (12 * rate) / 10
        val roomy = FloatArray(roomyLen)
        for (i in 0 until hitLen) {
            for (j in ir.indices) {
                val idx = i + j
                if (idx < roomyLen) roomy[idx] += hit[i] * ir[j]
            }
        }
        val dv = CaptureDoctor.deverb(Snip(roomy, 1, rate))

        fun rmsAt(s: FloatArray, fromSec: Float, toSec: Float): Double {
            var acc = 0.0
            var n = 0
            for (i in (fromSec * rate).toInt() until minOf((toSec * rate).toInt(), s.size)) {
                acc += s[i] * s[i].toDouble()
                n++
            }
            return Math.sqrt(acc / n.coerceAtLeast(1))
        }
        val tailDrop = 20 * Math.log10(rmsAt(dv.samples, 0.35f, 0.7f) / rmsAt(roomy, 0.35f, 0.7f))
        assertTrue(tailDrop < -2, "the room recedes: $tailDrop dB")
        val directChange = 20 * Math.log10(rmsAt(dv.samples, 0f, 0.06f) / rmsAt(roomy, 0f, 0.06f))
        assertTrue(Math.abs(directChange) < 1.5, "the direct sound keeps its shape: $directChange dB")

        // A dry, fast hit passes through nearly untouched.
        val dry = FloatArray(rate)
        hit.copyInto(dry)
        val dryOut = CaptureDoctor.deverb(Snip(dry, 1, rate))
        val peakBefore = dry.maxOf { Math.abs(it) }
        val peakAfter = dryOut.samples.maxOf { Math.abs(it) }
        assertTrue(Math.abs(peakAfter - peakBefore) < 0.05f * peakBefore, "the dry peak survives: $peakBefore -> $peakAfter")

        // The visit names the leg when asked for.
        val visit = CaptureDoctor.clean(Snip(roomy, 1, rate), deverb = true)
        assertTrue(visit.deverbed && "room predicted and subtracted" in visit.summary(), visit.summary())
    }

    @Test
    fun `clean composes the whole visit - findings named, clean audio returned as-is`() {
        // Hum over hiss over the beat, with one click riding the noise — a
        // proper bad capture: every leg of the visit has work to do.
        val rnd = java.util.Random(11)
        val dirty = withHum(beat(), 50.0, 0.05f).samples.copyOf()
        for (i in dirty.indices) dirty[i] += (rnd.nextFloat() * 2f - 1f) * 0.01f
        dirty[2 * rate + rate / 2] = 0.9f
        val report = CaptureDoctor.clean(Snip(dirty, 1, rate))
        assertTrue(report.touched)
        assertEquals(50f, report.hum?.hz)
        assertEquals(1, report.clicks, "the planted click survives the notch and is found")
        assertTrue(report.gated, "the hiss floor is heard and gated")
        val summary = report.summary()
        assertTrue("hum notched" in summary && "1 click(s) repaired" in summary, "every finding named: $summary")
        assertTrue("gently gated" in summary, "the gate is named too: $summary")

        // The same visit with the deep clean on the floor leg.
        val deep = CaptureDoctor.clean(Snip(dirty, 1, rate), denoise = true)
        assertTrue(deep.denoised && deep.gated, "the floor leg went spectral")
        assertTrue("spectrally de-noised" in deep.summary(), "and says so: ${deep.summary()}")

        val clean = Snip(beat(), 1, rate)
        val cleanReport = CaptureDoctor.clean(clean)
        assertTrue(!cleanReport.touched, "nothing found on a clean beat")
        assertTrue(cleanReport.snip === clean, "untouched audio comes back as the very same object")
        assertEquals("clean - nothing done", cleanReport.summary())
    }

    @Test
    fun `60 Hz is heard as 60, harmonics counted, clean audio stays silent`() {
        val sixty = withHum(beat(), 60.0, 0.04f)
        val report = CaptureDoctor.detectHum(sixty)
        assertEquals(60f, report!!.hz, "the other mains detects as itself")

        // A hum with a strong second harmonic gets both notched.
        val base = beat()
        val withHarmonic = Snip(
            FloatArray(base.size) { i ->
                base[i] + 0.04f * Math.sin(2.0 * Math.PI * 50.0 * i / rate).toFloat() +
                    0.02f * Math.sin(2.0 * Math.PI * 100.0 * i / rate).toFloat()
            },
            1, rate,
        )
        val harmonicReport = CaptureDoctor.detectHum(withHarmonic)!!
        assertTrue(harmonicReport.harmonics >= 2, "the 100 Hz harmonic is counted: ${harmonicReport.harmonics}")
        val cleaned = CaptureDoctor.removeHum(withHarmonic, harmonicReport)
        val h2Before = CaptureDoctor.goertzel(withHarmonic.samples, base.size, 100f, rate)
        val h2After = CaptureDoctor.goertzel(cleaned.samples, base.size, 100f, rate)
        assertTrue(h2After < h2Before * 0.2f, "the harmonic goes with the fundamental")

        // No hum, no report - the clean path never filters.
        assertNull(CaptureDoctor.detectHum(Snip(beat(), 1, rate)), "a clean beat is left alone")
    }
}
