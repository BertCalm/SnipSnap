package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GYRE R2a: `Strings.Bow`'s bridge port, excitation input and contact amount change nothing for a bow
 * that does not use them (docs/superpowers/specs/2026-10-01-gyre-coupled-string-engine-design.md, G3,
 * "Where the bridge is, once there is a bow"). [LegacyBow] is the bow as it was. Every grid cell plays
 * a stroke with an attack, a few cents of stepped vibrato (the retune path) and a lift with a stop, so
 * every hook a caller has is exercised.
 */
class StringsBowPortTest {

    private val rate = RATE * Dsp.OVERSAMPLE

    private class Cell(val f: Float, val beta: Float, val pressure: Float)

    private val grid = listOf(
        Cell(65.41f, 0.133f, 0.9f), Cell(130.81f, 0.133f, 0.5f), Cell(130.81f, 0.3f, 0.9f),
        Cell(220f, 0.08f, 0.7f), Cell(440f, 0.133f, 0.9f), Cell(880f, 0.133f, 0.7f),
    )

    /** A stroke: [len] samples, the bow's velocity and slope at each, a retune every 64 samples from a quarter in, a lift at 70 percent. */
    private fun stroke(len: Int, c: Cell, next: (Float, Float) -> Float, retune: (Float) -> Unit, lift: () -> Unit, gain: (Float) -> Unit): FloatArray {
        val attack = len / 10
        val liftAt = len * 7 / 10
        return FloatArray(len) { i ->
            if (i >= len / 4 && i < liftAt && i % 64 == 0) retune(c.f * (1f + 0.002f * ((i / 64) % 3)))
            if (i == liftAt) { lift(); gain(0.9f) }
            val v = if (i < attack) 0.13f * i / attack else 0.13f
            next(v, 5f - 4f * c.pressure)
        }
    }

    private fun legacy(c: Cell, len: Int): FloatArray {
        val bow = LegacyBow(c.f, c.beta, rate = rate)
        return stroke(len, c, bow::next, bow::retune, bow::lift, bow::gain)
    }

    @Test
    fun `a bow that uses none of it is the frozen bow, sample for sample`() {
        val len = rate / 2
        for (c in grid) {
            val bow = Strings.Bow(c.f, c.beta, rate = rate)
            val now = stroke(len, c, bow::next, bow::retune, bow::lift, bow::gain)
            assertContentEquals(legacy(c, len), now, "${c.f} Hz, beta ${c.beta}, pressure ${c.pressure}")
        }
    }

    @Test
    fun `a bridge that leaves every wave as it found it changes nothing`() {
        val len = rate / 2
        for (c in grid) {
            val bow = Strings.Bow(c.f, c.beta, rate = rate)
            // From the first sample the port can be read, and written once the bow is ready (bridgeAge in).
            var writes = 0
            val now = stroke(len, c, { v, s -> if (bow.ready) { bow.toBridge(bow.atBridge()); writes++ }; bow.next(v, s, 0f, 1f) }, bow::retune, bow::lift, bow::gain)
            assertEquals(len - bow.bridgeAge, writes, "${c.f} Hz: the port was written every sample from bridgeAge on")
            assertContentEquals(legacy(c, len), now, "${c.f} Hz, beta ${c.beta}, pressure ${c.pressure}")
        }
    }

    @Test
    fun `a bow at no contact is a lifted bow`() {
        // Both struck with the same burst and bowed at full speed: the lifted one ignores the bow, the
        // one at contact 0 must too, sample for sample (a silent pair would prove nothing).
        val len = rate / 4
        val burst = FloatArray(64) { if (it < 32) 0.5f else -0.5f }
        for (c in grid) {
            val lifted = Strings.Bow(c.f, c.beta, rate = rate).also { it.lift() }
            val light = Strings.Bow(c.f, c.beta, rate = rate)
            val a = FloatArray(len) { i -> lifted.next(0.13f, 5f - 4f * c.pressure, if (i < burst.size) burst[i] else 0f) }
            val b = FloatArray(len) { i -> light.next(0.13f, 5f - 4f * c.pressure, if (i < burst.size) burst[i] else 0f, 0f) }
            assertTrue(a.any { it != 0f }, "${c.f} Hz: the burst made no sound")
            assertContentEquals(a, b, "${c.f} Hz")
        }
    }

    @Test
    fun `an excitation is heard at the bow's pitch and rings down as the string does`() {
        // A lifted bow struck once with a short burst rings at its note: the excitation enters both waves
        // under the bow, so the string's own period sets the pitch, and doubling it doubles every sample
        // (to Float's rounding: the loop's filters round each product, exactly only while it is normal).
        // A free string is tuned at the full share (tune's own rule): SHARE is a bowed string's correction
        // and leaves a free one flat (measured at the pinned share: 440 Hz rings 5.7 cents low).
        for (f in floatArrayOf(130.81f, 440f)) {
            val one = Strings.Bow(f, 0.133f, share = 1f, rate = rate).also { it.lift() }
            val two = Strings.Bow(f, 0.133f, share = 1f, rate = rate).also { it.lift() }
            val burst = FloatArray(64) { if (it < 32) 0.5f else -0.5f }
            val a = FloatArray(rate) { i -> one.next(0f, 5f, if (i < burst.size) burst[i] else 0f) }
            val b = FloatArray(rate) { i -> two.next(0f, 5f, if (i < burst.size) 2f * burst[i] else 0f) }
            val top = a.maxOf { abs(it) }
            for (i in a.indices) assertTrue(abs(b[i] - 2f * a[i]) <= 1e-6f * top, "$f Hz sample $i: ${b[i]} against ${2f * a[i]}")
            val decimated = Dsp.decimate(a.copyOf(), RATE)
            val cents = FineTuning.cents(FineTuning.measuredHz(decimated, RATE, f, 0.05f, 0.3f), f.toDouble())
            println("$f Hz: an excited, lifted bow rings at ${"%.1f".format(cents)} cents")
            assertTrue(abs(cents) < 5.0, "$f Hz: an excited bow rings $cents cents off")
        }
    }

    @Test
    fun `a wave put at the bridge reaches the bow about half a bridge round trip later`() {
        // Read at the bow (bowPoint, both arriving waves): next's own return is the wave leaving toward
        // the bridge, which has been round the nut segment first.
        val bow = Strings.Bow(220f, 0.133f, rate = rate).also { it.lift() }
        for (i in 0 until 2_000) bow.next(0f, 5f)
        bow.toBridge(1f)
        var first = -1
        for (i in 0 until 2_000) { bow.next(0f, 5f); if (bow.bowPoint != 0f && first < 0) first = i }
        println("bridge port at ${bow.bridgeAge} samples; the wave reached the bow after $first")
        assertTrue(abs(first - bow.bridgeAge) <= 2, "the port is ${bow.bridgeAge} samples from the far end, the wave took $first")
    }

    @Test
    fun `a write at the bridge before anything has reached it is refused, and lands once the bow is ready`() {
        // From the first sample a bridge port has nothing in flight: the loop reads its first samples as
        // silence whatever is stored, so a write there would vanish. It is refused instead, and after
        // bridgeAge silent samples (a lifted bow) a write at the very next sample arrives at the bow
        // bridgeAge samples later.
        val bow = Strings.Bow(220f, 0.133f, rate = rate).also { it.lift() }
        assertTrue(!bow.ready)
        assertFailsWith<IllegalArgumentException> { bow.toBridge(1f) }
        repeat(bow.bridgeAge) { bow.next(0f, 5f) }
        assertTrue(bow.ready)
        bow.toBridge(1f)
        var first = -1
        for (i in 0 until 2_000) { bow.next(0f, 5f); if (bow.bowPoint != 0f && first < 0) first = i }
        println("primed for ${bow.bridgeAge} samples; a wave written at the next reached the bow after $first")
        assertTrue(abs(first - bow.bridgeAge) <= 2, "the port is ${bow.bridgeAge} samples from the far end, the wave took $first")
    }

    @Test
    fun `a retune that would shorten the bridge segment to its port is refused, and changes neither segment`() {
        // Copilot's review of #449: the guard sits after the capacity check and before either segment
        // is retuned, so a refused retune leaves a bow whose two halves are tuned to the same note.
        // An octave and a bit up halves the bridge segment to its port; an octave short of that still
        // retunes (the port is at half the segment's built length).
        val f = 220f
        val refused = Strings.Bow(f, 0.133f, rate = rate)
        val untouched = Strings.Bow(f, 0.133f, rate = rate)
        val e = assertFailsWith<IllegalArgumentException> { refused.retune(f * 2.2f) }
        assertTrue("bridge port" in (e.message ?: ""), "refused for another reason: ${e.message}")
        val a = FloatArray(rate / 4) { refused.next(0.13f, 1.4f) }
        val b = FloatArray(rate / 4) { untouched.next(0.13f, 1.4f) }
        assertContentEquals(b, a, "a refused retune changed the bow")
        Strings.Bow(f, 0.133f, rate = rate).retune(f * 1.8f)
    }

    @Test
    fun `a contact outside 0 to 1 is refused`() {
        val bow = Strings.Bow(220f, 0.133f, rate = rate)
        assertFailsWith<IllegalArgumentException> { bow.next(0.1f, 2f, 0f, 1.5f) }
        assertFailsWith<IllegalArgumentException> { bow.next(0.1f, 2f, 0f, -0.1f) }
    }
}
