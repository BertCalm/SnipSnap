package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbitBankTest {

    private val rate = 48_000
    private val step = 6_000 // frames per 16th at 120 BPM

    /** Evenly spaced decaying bursts: onsets the chopper will find. */
    private fun clicks(frames: Int, count: Int): Snip {
        val out = FloatArray(frames * 2)
        val every = frames / count
        for (c in 0 until count) {
            val at = c * every
            for (i in 0 until 600) {
                val f = at + i
                if (f >= frames) break
                val v = (1f - i / 600f) * if (i % 3 == 0) 0.9f else -0.9f
                out[f * 2] = v
                out[f * 2 + 1] = v
            }
        }
        return Snip(out, 2, rate)
    }

    private class OneLoop(private val snip: Snip?) : SampleSource {
        override fun loop(sampleFile: String): Snip? = snip
        override fun pad(kit: String, slot: Int): Snip? = null
    }

    private fun snipSet(steps: Int) = OrbitSet(listOf(Orbit("tape", steps, SnipOrbit("a.wav"))), 120f, rate)

    @Test
    fun `a snip exactly one period long fits as is`() {
        val set = snipSet(16)
        val bank = OrbitBank.prepare(set, OneLoop(clicks(16 * step, 4)))
        val report = assertNotNull(bank.fit(set, set.orbits[0]))
        assertEquals(LoopFit.AS_IS, report.fit)
        assertEquals("FITS AS IS", report.label)
    }

    @Test
    fun `a snip a hair short is padded, and says by how much`() {
        val set = snipSet(16)
        val bank = OrbitBank.prepare(set, OneLoop(clicks(16 * step - 960, 4))) // 1% short
        val report = assertNotNull(bank.fit(set, set.orbits[0]))
        assertEquals(LoopFit.PADDED, report.fit)
        assertEquals("PADDED 1% WITH SILENCE", report.label)
        assertEquals(16 * step, bank.loop(set, set.orbits[0])!!.frameCount)
    }

    @Test
    fun `a snip well off the ring is sliced, and counts its hits`() {
        val set = snipSet(16)
        val bank = OrbitBank.prepare(set, OneLoop(clicks((16 * step * 1.4).toInt(), 4)))
        val report = assertNotNull(bank.fit(set, set.orbits[0]))
        assertEquals(LoopFit.SLICED, report.fit)
        assertTrue(report.slices >= 4, "expected at least the four bursts as slices, got ${report.slices}")
        assertTrue(report.label.startsWith("SLICED AT ${report.slices} HITS · SQUEEZED 40%"), report.label)
    }

    @Test
    fun `sustained material with nothing to slice on reports the trim it actually got`() {
        val set = snipSet(16)
        val frames = (16 * step * 1.3).toInt()
        val drone = Snip(FloatArray(frames * 2) { if (it % 2 == 0) 0.5f else -0.5f }, 2, rate)
        val report = assertNotNull(OrbitBank.prepare(set, OneLoop(drone)).fit(set, set.orbits[0]))
        assertEquals(LoopFit.TRIMMED, report.fit)
        assertEquals("TRIMMED 30% OFF THE END", report.label)
    }

    @Test
    fun `a missing file has no fit and no peaks`() {
        val set = snipSet(16)
        val bank = OrbitBank.prepare(set, OneLoop(null))
        assertNull(bank.fit(set, set.orbits[0]))
        assertNull(bank.peaks(set, set.orbits[0], 8))
    }

    @Test
    fun `peaks are the loudest sample per equal slice of the ring`() {
        val frames = 16 * step
        val ramp = Snip(FloatArray(frames * 2) { (it / 2).toFloat() / frames }, 2, rate)
        val set = snipSet(16)
        val peaks = assertNotNull(OrbitBank.prepare(set, OneLoop(ramp)).peaks(set, set.orbits[0], 4))
        assertEquals(4, peaks.size)
        assertTrue(peaks[0] < peaks[1] && peaks[1] < peaks[2] && peaks[2] < peaks[3], peaks.joinToString())
        assertTrue(peaks[3] > 0.99f, "the last bucket holds the top of the ramp: ${peaks[3]}")
        assertTrue(peaks[0] <= 0.26f, "the first bucket tops out a quarter of the way up: ${peaks[0]}")
    }

    @Test
    fun `peaks of nothing are zeros, and more buckets than frames still works`() {
        assertTrue(OrbitBank.peaksOf(Snip(FloatArray(0), 2, rate), 5).all { it == 0f })
        val tiny = Snip(floatArrayOf(0.2f, 0.2f, -0.7f, -0.7f), 2, rate)
        val peaks = OrbitBank.peaksOf(tiny, 6)
        assertEquals(6, peaks.size)
        assertEquals(0.7f, peaks.max())
    }
}
