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
