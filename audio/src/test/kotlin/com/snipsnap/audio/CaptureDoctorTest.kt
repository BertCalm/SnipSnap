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
