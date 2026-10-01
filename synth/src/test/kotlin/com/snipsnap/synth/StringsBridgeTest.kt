package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GYRE R0's claims (docs/superpowers/plans/2026-10-01-gyre-round-1.md): the membrane refuses
 * any weights outside the passive shape (and scales Float rounding past 1 back to 1), one mode is
 * positive real as it runs (in Double), the bridge's common-motion gain never exceeds 1, a bridge at
 * coupling 0 leaves its strings independent, coupling moves energy into an unplucked string,
 * and a coupled network at the loss ceiling decays - also with the coupling swinging, which
 * the frequency argument does not cover.
 */
class StringsBridgeTest {

    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    // ---------- the membrane's shape ----------

    @Test
    fun `a membrane refuses weights outside the passive shape`() {
        val m = Strings.Membrane(rate)
        val hz = floatArrayOf(200f, 330f)
        val q = floatArrayOf(4f, 4f)
        assertFailsWith<IllegalArgumentException> { m.tune(hz, q, floatArrayOf(0.8f, -0.1f)) }
        assertFailsWith<IllegalArgumentException> { m.tune(hz, q, floatArrayOf(0.7f, 0.4f)) }
        assertFailsWith<IllegalArgumentException> { m.tune(hz, q, floatArrayOf(0.5f)) }
        assertFailsWith<IllegalArgumentException> { m.tune(floatArrayOf(200f, 0.46f * rate), q, floatArrayOf(0.5f, 0.5f)) }
        assertFailsWith<IllegalArgumentException> { m.tune(hz, floatArrayOf(4f, Strings.Membrane.MAX_Q + 1f), floatArrayOf(0.5f, 0.5f)) }
        m.tune(hz, q, floatArrayOf(0.6f, 0.4f))
        assertFailsWith<IllegalArgumentException> { m.weigh(floatArrayOf(1f, -1f)) }
        assertFailsWith<IllegalArgumentException> { m.weigh(floatArrayOf(0.9f, 0.2f)) }
        // Rounding past 1 is accepted and scaled back: two coincident modes at their centre add to 1, not more.
        val twin = Strings.Membrane(rate)
        twin.tune(floatArrayOf(300f, 300f), floatArrayOf(4f, 4f), floatArrayOf(0.5000004f, 0.5000004f))
        val h = twin.response(300.0)
        assertTrue(hypot(h[0], h[1]) <= 1.0 + 1e-12, "accepted rounding made the membrane louder than 1: ${hypot(h[0], h[1])}")
    }

    // ---------- the bound, as a frequency response ----------

    private fun grid(extra: FloatArray): DoubleArray {
        val n = 600
        val log = DoubleArray(n) { 20.0 * (20_000.0 / 20.0).pow(it / (n - 1.0)) }
        return (log.toList() + extra.map { it.toDouble() }).toDoubleArray()
    }

    @Test
    fun `one membrane mode is positive real, as it runs`() {
        // Re H = |H|^2 for the RBJ bandpass; a Float Biquad loses it at narrow modes (the class KDoc).
        var worst = Double.POSITIVE_INFINITY
        for (f0 in floatArrayOf(40f, 110f, 440f, 2_000f, 9_000f, 30_000f)) for (q in floatArrayOf(0.5f, 3f, 12f, 40f, Strings.Membrane.MAX_Q)) {
            val m = Strings.Membrane(rate).also { it.tune(floatArrayOf(f0), floatArrayOf(q), floatArrayOf(1f)) }
            for (hz in grid(floatArrayOf(f0))) {
                val h = m.response(hz)
                worst = minOf(worst, h[0] - (h[0] * h[0] + h[1] * h[1]))
            }
        }
        println("membrane mode: worst Re H - |H|^2 = $worst")
        assertTrue(worst > -1e-9, "Re H - |H|^2 went to $worst")
    }

    @Test
    fun `the bridge's common-motion gain never exceeds 1`() {
        val random = Random(20261001)
        var worst = 0.0
        repeat(200) {
            val k = 1 + random.nextInt(5)
            val hz = FloatArray(k) { (40.0 * (8_000.0 / 40.0).pow(random.nextDouble())).toFloat() }
            val q = FloatArray(k) { (0.5 * (Strings.Membrane.MAX_Q / 0.5).pow(random.nextDouble())).toFloat() }
            val raw = FloatArray(k) { random.nextFloat() }
            val total = raw.sum()
            val scale = (0.2f + 0.8f * random.nextFloat()) / total
            val w = FloatArray(k) { raw[it] * scale }
            val m = Strings.Membrane(rate).also { it.tune(hz, q, w) }
            for (f in grid(hz)) {
                val h = m.response(f)
                for (c in doubleArrayOf(0.25, 0.5, 0.75, 1.0)) worst = max(worst, hypot(1 - 2 * c * h[0], -2 * c * h[1]))
            }
        }
        println("bridge: worst |1 - 2cH| = $worst")
        assertTrue(worst <= 1.0 + 1e-6, "|1 - 2cH| reached $worst")
    }

    // ---------- the bridge, driving real loops ----------

    private class Net(val loops: List<Strings.Loop>, val bridge: Strings.Bridge)

    private fun net(baseHz: Float, fb: Float, membrane: Strings.Membrane): Net {
        val loops = (1..4).map { k ->
            val f = baseHz * k
            val loopHz = minOf(f * 12f, 0.4f * rate)
            val t = Strings.tune(f, loopHz, rate)
            Strings.Loop(t.n, t.a, fb, loopHz, rate)
        }
        return Net(loops, Strings.Bridge(4, membrane))
    }

    private fun membrane(): Strings.Membrane =
        Strings.Membrane(rate).also { it.tune(floatArrayOf(190f, 310f, 439f, 707f), floatArrayOf(4f, 6f, 8f, 10f), floatArrayOf(0.4f, 0.3f, 0.2f, 0.1f)) }

    /** Plucks the strings in [plucked] (one burst each) and runs [seconds]; returns each string's written wave. */
    private fun run(net: Net, seconds: Float, plucked: Set<Int>, c: (Int) -> Float): Array<FloatArray> {
        val len = (seconds * rate).toInt()
        val bursts = net.loops.indices.map { k ->
            if (k !in plucked) FloatArray(0)
            else Strings.pluckExciter(Strings.tune(110f * (k + 1), 3_000f, rate).n, 110f * (k + 1), 3_000f, 0.13f, Dsp.seedFor("bridge", k), rate, len)
        }
        val out = Array(net.loops.size) { FloatArray(len) }
        val r = FloatArray(net.loops.size)
        val back = FloatArray(net.loops.size)
        for (i in 0 until len) {
            for (k in net.loops.indices) r[k] = net.loops[k].reflected()
            net.bridge.couple(r, c(i), back)
            for (k in net.loops.indices) {
                val x = bursts[k].let { if (i < it.size) it[i] else 0f }
                out[k][i] = net.loops[k].inject(x + back[k])
            }
        }
        return out
    }

    @Test
    fun `at coupling 0 the strings are independent`() {
        val coupled = run(net(110f, 0.995f, membrane()), 0.5f, setOf(0, 1, 2, 3)) { 0f }
        val alone = run(net(110f, 0.995f, membrane()), 0.5f, setOf(0, 1, 2, 3)) { 0f }
        for (k in 0 until 4) {
            val t = Strings.tune(110f * (k + 1), minOf(110f * (k + 1) * 12f, 0.4f * rate), rate)
            val loop = Strings.Loop(t.n, t.a, 0.995f, minOf(110f * (k + 1) * 12f, 0.4f * rate), rate)
            val burst = Strings.pluckExciter(Strings.tune(110f * (k + 1), 3_000f, rate).n, 110f * (k + 1), 3_000f, 0.13f, Dsp.seedFor("bridge", k), rate, coupled[k].size)
            for (i in coupled[k].indices) {
                val plain = loop.next(if (i < burst.size) burst[i] else 0f)
                assertTrue(plain == coupled[k][i], "string $k sample $i: $plain alone, ${coupled[k][i]} on the bridge")
            }
            for (i in coupled[k].indices) assertTrue(coupled[k][i] == alone[k][i])
        }
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun `coupling moves energy into a string nobody plucked`() {
        val levels = floatArrayOf(0f, 0.1f, 0.3f, 0.6f, 1f).map { c ->
            val out = run(net(110f, 0.995f, membrane()), 0.5f, setOf(0)) { c }
            Triple(c, rms(out[0]), rms(out[2]))
        }
        println("coupling (c, plucked rms, unplucked rms): $levels")
        assertEquals(0.0, levels[0].third, "nothing reaches string 3 at coupling 0")
        for (i in 1 until levels.size) assertTrue(levels[i].third > levels[i - 1].third, "string 3 at c=${levels[i].first} is not louder than at c=${levels[i - 1].first}")
    }

    @Test
    fun `a coupled network at the loss ceiling decays, coupling fixed or swinging`() {
        val seconds = 8f
        val random = Random(7)
        val swings = listOf<Pair<String, (Int) -> Float>>(
            "c=1" to { _ -> 1f },
            "c swinging at 3 Hz" to { i -> (0.5 + 0.5 * sin(2 * PI * 3.0 * i / rate)).toFloat() },
            "c swinging at 110 Hz" to { i -> (0.5 + 0.5 * sin(2 * PI * 110.0 * i / rate)).toFloat() },
        )
        for (trial in 0 until 4) {
            val k = 1 + random.nextInt(4)
            val hz = FloatArray(k) { (60.0 * (2_000.0 / 60.0).pow(random.nextDouble())).toFloat() }
            val q = FloatArray(k) { (1.0 * (60.0).pow(random.nextDouble())).toFloat() }
            val w = FloatArray(k) { 1f / k }
            for ((label, c) in swings) {
                val mem = Strings.Membrane(rate).also { it.tune(hz, q, w) }
                val out = run(net(110f, 0.9995f, mem), seconds, setOf(0, 1, 2, 3), c)
                val sum = FloatArray(out[0].size) { i -> out.sumOf { it[i].toDouble() }.toFloat() }
                val head = (0.5f * rate).toInt()
                var headPeak = 0f; var tailPeak = 0f
                for (i in sum.indices) {
                    val a = abs(sum[i])
                    assertTrue(a.isFinite(), "trial $trial $label: non-finite at $i")
                    if (i < head) headPeak = max(headPeak, a) else tailPeak = max(tailPeak, a)
                }
                val first = rms(sum, 0, head); val last = rms(sum, sum.size - head, sum.size)
                println("trial $trial $label: head peak $headPeak, later peak $tailPeak, rms ${"%.3g".format(first)} -> ${"%.3g".format(last)}")
                assertTrue(tailPeak <= headPeak, "trial $trial $label: a later peak $tailPeak passed the attack's $headPeak")
                assertTrue(last < first, "trial $trial $label: no decay, rms $first -> $last")
            }
        }
    }
}
