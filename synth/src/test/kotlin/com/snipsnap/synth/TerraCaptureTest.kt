package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HIT's capture rule (spec, "HIT, the design", the capture rule; "Testing",
 * test 3, the capture half). It is FORK's striker wherever FORK's is safe,
 * and safe where FORK's has its five hazards: silence, lead silence, NaN or
 * Inf, a noise lead-in, and a whole foreign-rate file.
 */
class TerraCaptureTest {

    private val rate = Dsp.RATE
    private val kick = TerraStrikers.source("tkick")

    private fun leadZeros(n: Int, s: Snip) = Snip(FloatArray(n) + s.samples, 1, s.sampleRate)

    @Test
    fun `the capture is FORK's striker on the measured strikers`() {
        for (s in TerraStrikers.TEN) {
            val head = assertNotNull(Terra.captureStriker(s.snip), "${s.name} captured as silence")
            assertContentEquals(Fork.striker(s.snip), head, s.name)
        }
        assertEquals(10, TerraStrikers.TEN.size)
    }

    /** By construction (spec): any mono 44.1 kHz source above the floor whose onset is within 44 samples of the start reads from sample 0, as FORK's does. */
    @Test
    fun `the capture is FORK's striker on any mono source whose onset is within 1 ms of the start`() {
        var cases = 0
        for (seed in 0 until 50) {
            val r = Random(seed)
            val lead = r.nextInt(0, 45)
            val peak = 0.01f + 0.99f * r.nextFloat()
            val x = FloatArray(lead + 900 + r.nextInt(5000)) { i -> if (i < lead) 0f else (r.nextFloat() * 2f - 1f) * peak }
            x[lead] = peak
            val s = Snip(x, 1, rate)
            assertContentEquals(Fork.striker(s), assertNotNull(Terra.captureStriker(s)), "seed $seed, onset $lead")
            cases++
        }
        assertEquals(50, cases)
    }

    @Test
    fun `every hostile source captures a real hit or nothing, never a broken head`() {
        for (h in TerraStrikers.hostile()) {
            val head = Terra.captureStriker(h.snip)
            println("TERRA capture: ${h.label} -> ${if (head == null) "no striker (the pad keeps today's body)" else "a hit"}")
            assertEquals(h.expectsHit, head != null, h.label)
            if (head != null) {
                assertEquals(Fork.STRIKER_SAMPLES, head.size, h.label)
                assertTrue(head.all { it.isFinite() && abs(it) <= 1.000001f }, "${h.label}: a head sample is not finite or is over full scale")
                assertTrue(head.any { it != 0f }, "${h.label}: an all-zero head")
            }
        }
    }

    @Test
    fun `a non-finite sample is zeroed where it stood and the rest is the kick's own head`() {
        for ((at, bad) in listOf(10 to Float.NaN, 5 to Float.POSITIVE_INFINITY)) {
            val broken = kick.samples.copyOf().also { it[at] = bad }
            val zeroed = kick.samples.copyOf().also { it[at] = 0f }
            assertContentEquals(Terra.captureStriker(Snip(zeroed, 1, rate)), Terra.captureStriker(Snip(broken, 1, rate)), "$bad at $at")
        }
    }

    /** Onset alignment: 1 ms of lead before the hit, however much silence came first. */
    @Test
    fun `lead silence of any length captures the kick's own head`() {
        val reference = assertNotNull(Terra.captureStriker(leadZeros(44, kick)))
        for (ms in listOf(20, 40, 100, 3000)) {
            assertContentEquals(reference, Terra.captureStriker(leadZeros(ms * rate / 1000, kick)), "$ms ms of silence")
        }
    }

    /**
     * A -90 dBFS lead-in (3.16e-5) sits under the 1 % onset line, so the head
     * starts 1 ms before the kick. The noise can only differ in those 44
     * lead samples, by at most 3.16e-5 over the head's own peak. The THUMP
     * KICK's head peak is above 0.5, so 1e-4 is a bound derived from the
     * signal, not a guess; the measured difference is printed.
     */
    @Test
    fun `a -90 dBFS lead-in does not become the hammer`() {
        val noisy = TerraStrikers.hostile().first { it.label == "a kick after 30 ms of -90 dBFS noise" }.snip
        val reference = assertNotNull(Terra.captureStriker(leadZeros(44, kick)))
        val head = assertNotNull(Terra.captureStriker(noisy))
        var worst = 0f
        for (i in head.indices) worst = maxOf(worst, abs(head[i] - reference[i]))
        println("TERRA capture: -90 dBFS lead-in, largest difference from the clean head ${"%.2e".format(worst)}")
        assertTrue(worst <= 1e-4f, "the lead-in moved the head by $worst")
    }

    @Test
    fun `a single sample lands 1 ms into the head, alone, at full scale`() {
        for (at in listOf(0, 881, 882, 5000)) {
            val x = FloatArray(6000).also { it[at] = 0.5f }
            val head = assertNotNull(Terra.captureStriker(Snip(x, 1, rate)), "spike at $at")
            val where = minOf(at, 44)
            assertEquals(1f, head[where], "spike at $at")
            assertTrue(head.indices.all { it == where || head[it] == 0f }, "spike at $at: something besides the spike in the head")
        }
    }

    @Test
    fun `a quiet click before a louder hit is refused by the head's own peak (decision 20)`() {
        val source = TerraStrikers.hostile().first { it.label == "a quiet click before a louder hit (decision 20)" }.snip
        assertTrue(source.peak() >= 1e-4f, "the source as a whole must clear the floor, or it cannot tell the two rules apart")
        assertNull(Terra.captureStriker(source))
    }

    @Test
    fun `a stereo source whose channels cancel gives no striker`() {
        val k = kick.samples
        val cancel = Snip(FloatArray(k.size * 2) { i -> if (i % 2 == 0) k[i / 2] else -k[i / 2] }, 2, rate)
        assertNull(Terra.captureStriker(cancel))
        val same = Snip(FloatArray(k.size * 2) { i -> k[i / 2] }, 2, rate)
        assertContentEquals(Terra.captureStriker(kick), Terra.captureStriker(same), "identical channels fold to the mono kick")
    }

    /**
     * New in R1, and outside G-C1 (spec, "HIT, the design", step 1): a
     * foreign-rate source is cut to 10 ms before its onset through 100 ms
     * after, and only then resampled. Phase 0 measured 61 ms to resample a
     * whole 3 s file at 48 kHz. A NaN in the lead silence and an Inf long
     * after the window fall outside the cut and change nothing. The match to
     * the 44.1 kHz capture is the best normalised correlation within three
     * samples' lag; 0.95 is the starting bar, printed. If it fails, record
     * the printed value and set the bar from it with about 20 % margin, never
     * below 0.9.
     */
    @Test
    fun `a 48 kHz source is cut near its onset before it is resampled, and still captures the kick`() {
        val at48 = Resampler.resample(kick, 48_000).samples
        val long = Snip(FloatArray(24_000) + at48 + FloatArray(48_000 * 3), 1, 48_000)
        val head = assertNotNull(Terra.captureStriker(long))
        val reference = assertNotNull(Terra.captureStriker(leadZeros(44, kick)))
        var best = -1.0
        for (lag in -3..3) {
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in head.indices) {
                val j = i + lag
                if (j !in reference.indices) continue
                ab += head[i].toDouble() * reference[j]
                aa += head[i].toDouble() * head[i]
                bb += reference[j].toDouble() * reference[j]
            }
            best = maxOf(best, ab / sqrt(aa * bb))
        }
        println("TERRA capture: 48 kHz kick against the 44.1 kHz capture, best NCC within 3 samples ${"%.4f".format(best)}")
        assertTrue(best >= 0.95, "the 48 kHz capture is not the kick: NCC $best")
        val dirty = long.samples.copyOf().also { it[100] = Float.NaN; it[it.size - 10] = Float.POSITIVE_INFINITY }
        assertContentEquals(head, Terra.captureStriker(Snip(dirty, 1, 48_000)), "a NaN before the cut or an Inf after it reached the head")
    }
}
