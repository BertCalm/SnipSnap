package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Modes.Bank] and [Modes.Friction], MERCURY's R0. Each test pins one claim
 * that Phase 0 measured (`docs/superpowers/plans/2026-10-01-mercury-phase-0-spike.md`),
 * on the class the engine will use rather than on the spike.
 */
class ModesBankTest {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE
    private val ln1000 = 6.907755278982137

    /** Thin ring, inextensional bending (Rayleigh): f_k ∝ k(k²−1)/√(k²+1), k = 2…13. */
    private val ring = DoubleArray(12) { i -> val k = i + 2.0; k * (k * k - 1) / sqrt(k * k + 1) }.let { r -> DoubleArray(12) { r[it] / r[0] } }
    private val vesselDetune = doubleArrayOf(0.035, -0.045, 0.06, -0.07)
    private val ratios = DoubleArray(16) { i -> if (i < 12) ring[i] else ring[i - 12] * (1 + vesselDetune[i - 12]) }

    private fun t60(i: Int) = if (i < 12) 4.0 * ratios[i].pow(-0.6) else 1.8

    /** MERCURY's Phase-0 object: 12 ring modes, 4 vessel modes, springs between neighbours and partners. */
    private fun mercury(hz: Double, kappa: Double): Modes.Bank {
        val bank = Modes.Bank(16, rate)
        for (i in 0 until 16) bank.tune(i, hz * ratios[i], t60(i))
        for (i in 0 until 11) bank.connect(i, i + 1, kappa)
        for (v in 0 until 4) {
            bank.connect(12 + v, v, kappa)
            bank.connect(12 + v, v + 1, kappa)
        }
        return bank
    }

    private fun retune(bank: Modes.Bank, hz: Double) {
        for (i in 0 until 16) bank.tune(i, hz * ratios[i], t60(i))
    }

    private val primaries = DoubleArray(16) { if (it < 12) 1.0 else 0.0 }

    /** Mean energy per 1 ms block over [seconds] of free ringing. */
    private fun blockEnergies(bank: Modes.Bank, seconds: Double): DoubleArray {
        val block = rate / 1000
        val out = DoubleArray((seconds * 1000).toInt())
        for (b in out.indices) {
            var s = 0.0
            repeat(block) { bank.step(); s += bank.energy() }
            out[b] = s / block
        }
        return out
    }

    private fun assertNeverRises(e: DoubleArray, label: String) {
        for (b in 1 until e.size) {
            assertTrue(e[b] <= e[b - 1] * (1 + 1e-12), "$label: energy rose at block $b: ${e[b - 1]} -> ${e[b]}")
        }
        assertTrue(e.last() < e.first(), "$label: energy never fell")
    }

    /** Strongest frequency within ±[span] cents of [hz] in x[from, from+len), Hann window, parabolic refine. */
    private fun peakHz(x: DoubleArray, hz: Double, from: Int, len: Int, span: Double, step: Double = 0.5): Double {
        val n = (2 * span / step).toInt()
        val m = DoubleArray(n + 1) { k ->
            val f = hz * 2.0.pow((-span + k * step) / 1200)
            val w = 2 * PI * f / rate
            var c = 0.0
            var s = 0.0
            for (j in 0 until len) {
                val win = 0.5 - 0.5 * cos(2 * PI * j / (len - 1))
                c += x[from + j] * win * cos(w * j)
                s += x[from + j] * win * sin(w * j)
            }
            hypot(c, s)
        }
        var best = 0
        for (k in m.indices) if (m[k] > m[best]) best = k
        var cents = -span + best * step
        if (best in 1 until n) {
            val den = m[best - 1] - 2 * m[best] + m[best + 1]
            if (den != 0.0) cents += 0.5 * (m[best - 1] - m[best + 1]) / den * step
        }
        return hz * 2.0.pow(cents / 1200)
    }

    private fun cents(a: Double, b: Double) = 1200 * ln(a / b) / ln(2.0)

    @Test
    fun `a free mode is an exact damped rotation`() {
        val hz = 440.0
        val t60 = 1.0
        val bank = Modes.Bank(1, rate)
        bank.tune(0, hz, t60)
        bank.drive(doubleArrayOf(1.0), rate.toDouble()) // v = 1
        val n = 20_000
        repeat(n) { bank.step() }
        val r = exp(-ln1000 / (t60 * rate))
        val th = 2 * PI * hz / rate
        assertEquals(r.pow(n) * cos(n * th), bank.velocity(0), 1e-9)
        assertEquals(r.pow(n) * sin(n * th) / (2 * PI * hz), bank.displacement(0), 1e-12)
    }

    @Test
    fun `retuning on every sample does not pump energy`() {
        // Phase 0 P2: a ±12-semitone, 2 Hz sweep. |z|² is the energy and a rotation keeps it,
        // so the swept bank's energy matches the unswept one's to rounding.
        val bar = doubleArrayOf(1.0, 2.756, 5.404, 8.933)
        fun run(sweep: Boolean): DoubleArray {
            val bank = Modes.Bank(4, rate)
            for (i in 0 until 4) bank.tune(i, 262.0 * bar[i], 2.0)
            bank.drive(DoubleArray(4) { 1.0 }, 0.03 * rate)
            val out = DoubleArray(1500)
            for (b in out.indices) {
                repeat(rate / 1000) { s ->
                    if (sweep) {
                        val t = (b * (rate / 1000) + s).toDouble() / rate
                        val m = 2.0.pow(sin(2 * PI * 2.0 * t))
                        for (i in 0 until 4) bank.tune(i, 262.0 * bar[i] * m, 2.0)
                    }
                    bank.step()
                }
                out[b] = bank.energy()
            }
            return out
        }
        val still = run(false)
        val swept = run(true)
        assertTrue(still.last() < still.first() * 0.5, "the bank should decay")
        for (b in still.indices) assertEquals(1.0, swept[b] / still[b], 1e-9, "block $b")
    }

    @Test
    fun `a coupled bank never gains energy`() {
        // Phase 0 P1 at COUPLE 0.3, 0.6 and 1 (kappa 0.12·COUPLE), plus kappa 0.24, which puts the
        // busiest modes at a kappa sum of 0.96, just under the bound.
        for (kappa in listOf(0.036, 0.072, 0.12, 0.24)) {
            val bank = mercury(261.63, kappa)
            bank.drive(primaries, 0.03 * rate)
            assertNeverRises(blockEnergies(bank, 1.5), "kappa $kappa")
        }
    }

    @Test
    fun `random tunings at the kappa bound stay passive`() {
        val rnd = Random(7)
        repeat(6) { trial ->
            val bank = Modes.Bank(16, rate)
            for (i in 0 until 16) bank.tune(i, 40.0 * 2.0.pow(rnd.nextDouble() * 9), 0.05 + rnd.nextDouble() * 8)
            for (i in 0 until 11) bank.connect(i, i + 1, 0.24)
            for (v in 0 until 4) { bank.connect(12 + v, v, 0.24); bank.connect(12 + v, v + 1, 0.24) }
            bank.drive(DoubleArray(16) { rnd.nextDouble() - 0.5 }, 0.03 * rate)
            assertNeverRises(blockEnergies(bank, 0.5), "trial $trial")
        }
    }

    @Test
    fun `springs cannot take a mode's kappa sum to one`() {
        val bank = Modes.Bank(3, rate)
        bank.connect(0, 1, 0.6)
        assertFailsWith<IllegalArgumentException> { bank.connect(1, 2, 0.4) }
        val e = bank.connect(1, 2, 0.3)
        assertFailsWith<IllegalArgumentException> { bank.setKappa(e, 0.5) }
        bank.setKappa(e, 0.39)
        assertFailsWith<IllegalArgumentException> { bank.connect(0, 0, 0.1) }
        assertFailsWith<IllegalArgumentException> { bank.connect(0, 2, -0.1) }
        assertEquals(2, bank.edges, "a refused spring must leave nothing behind")
    }

    @Test
    fun `the split's stability bound is enforced near Nyquist`() {
        // The PR #428 review's case: two modes at 490 Hz at a 1 kHz rate with kappa 0.5. Each
        // kappa sum is under 1, but x·tan x = 49 at x = 0.49π, so the rotate-then-kick step is
        // unstable. It must be refused, atomically, from either direction.
        val a = Modes.Bank(2, 1000)
        a.tune(0, 490.0, 1e9); a.tune(1, 490.0, 1e9)
        assertFailsWith<IllegalArgumentException> { a.connect(0, 1, 0.5) }
        assertEquals(0, a.edges)
        val b = Modes.Bank(2, 1000)
        b.tune(0, 100.0, 1e9); b.tune(1, 100.0, 1e9)
        b.connect(0, 1, 0.5)
        assertFailsWith<IllegalArgumentException> { b.tune(1, 490.0, 1e9) }
        assertEquals(100.0, b.coupledHz(1) * b.anchorScale(1), 1e-9, "a refused tune must leave the mode where it was")
    }

    @Test
    fun `just inside the bound an undamped coupled bank stays bounded`() {
        // At a low rate, so θ reaches far toward π: random tunings up to 0.48·rate, every mode's
        // kappa sum at 0.98 of min(1, 1/(x·tan x)), no damping. The bound promises stability: the
        // energy must stay within a constant factor, never grow without limit.
        val r = 1000
        val rnd = Random(13)
        repeat(20) { trial ->
            val n = 6
            val hz = DoubleArray(n) { 20.0 + rnd.nextDouble() * 460.0 }
            val bound = DoubleArray(n) { val x = PI * hz[it] / r; 0.98 * minOf(1.0, 1.0 / (x * kotlin.math.tan(x))) }
            val bank = Modes.Bank(n, r)
            for (i in 0 until n) bank.tune(i, hz[i], 1e12)
            for (i in 0 until n - 1) bank.connect(i, i + 1, 0.4999 * minOf(bound[i], bound[i + 1]))
            bank.drive(DoubleArray(n) { rnd.nextDouble() - 0.5 }, r.toDouble())
            val e0 = bank.energy()
            var hi = e0
            repeat(50_000) { bank.step(); hi = maxOf(hi, bank.energy()) }
            assertTrue(hi.isFinite() && hi < 100 * e0, "trial $trial grew: ${hi / e0}×")
        }
    }

    @Test
    fun `the bound keeps K positive definite at every tuning`() {
        // K = diag(ω²) − A with k = kappa·min(ω_i, ω_j)² and every kappa sum < 1 is strictly
        // diagonally dominant whatever the ωs are. Checked on random tunings at the bound.
        val rnd = Random(11)
        val edges = (0 until 11).map { it to it + 1 } + (0 until 4).flatMap { listOf(12 + it to it, 12 + it to it + 1) }
        repeat(200) {
            val w = DoubleArray(16) { 2 * PI * 30.0 * 2.0.pow(rnd.nextDouble() * 10) }
            val k = Array(16) { DoubleArray(16) }
            for (i in 0 until 16) k[i][i] = w[i] * w[i]
            for ((i, j) in edges) {
                val s = 0.2399 * minOf(w[i], w[j]).pow(2)
                k[i][j] -= s; k[j][i] -= s
            }
            val (values, _) = Modes.symmetricEigen(k)
            assertTrue(values.all { it > 0 }, "an eigenvalue went non-positive: ${values.minOrNull()}")
        }
    }

    @Test
    fun `symmetricEigen returns eigenpairs`() {
        val rnd = Random(3)
        val m = Array(16) { DoubleArray(16) }
        for (i in 0 until 16) for (j in i until 16) { val x = rnd.nextDouble() - 0.5; m[i][j] = x; m[j][i] = x }
        val (values, vectors) = Modes.symmetricEigen(m)
        for (c in 0 until 16) for (r in 0 until 16) {
            var mv = 0.0
            for (j in 0 until 16) mv += m[r][j] * vectors[j][c]
            assertEquals(values[c] * vectors[r][c], mv, 1e-10)
        }
    }

    @Test
    fun `the anchor fix puts the coupled fundamental on the note`() {
        // Phase 0 I2d: springs repel the near vessel mode and flatten the anchor; one uniform
        // retune by anchorScale puts it back. kappa 0.12 is COUPLE 1, where Phase 0 measured −85 cents.
        val hz = 261.63
        val bank = mercury(hz, 0.12)
        val scale = bank.anchorScale()
        assertTrue(cents(hz / scale, hz) < -20, "COUPLE 1 should pull the anchor well flat: ${cents(hz / scale, hz)} cents")
        retune(bank, hz * scale)
        assertEquals(hz, bank.coupledHz(), hz * 1e-12, "after the retune the anchor eigenmode is on the note")
        assertEquals(scale, bank.anchorScale(), 1e-12, "the factor does not change under its own retune")
        bank.drive(primaries, 0.03 * rate)
        val len = rate
        val v0 = DoubleArray(len + rate / 10)
        for (n in v0.indices) { bank.step(); v0[n] = bank.velocity(0) }
        val heard = peakHz(v0, hz, rate / 10, len, span = 40.0)
        assertTrue(abs(cents(heard, hz)) < 0.5, "the rendered anchor sits ${cents(heard, hz)} cents off")
    }

    @Test
    fun `the friction force solves its own implicit equation`() {
        val rnd = Random(5)
        val f = Modes.Friction(5000.0)
        repeat(500) {
            val driver = rnd.nextDouble() * 0.06
            val surface = (rnd.nextDouble() - 0.5) * 0.08
            val c = rnd.nextDouble() * 0.9 / f.steepestFall
            val pressure = 1.0 + rnd.nextDouble() * 4
            val force = f.force(driver, surface, pressure, c / pressure)
            // The slip after the force is applied: η = driver − (surface + compliance·force).
            val eta = driver - surface - (c / pressure) * force
            assertEquals(force, pressure * f.curve(eta), 1e-12 * pressure)
        }
        assertEquals(0.0, f.force(0.03, 0.0, 0.0, 1e-5))
        assertFailsWith<IllegalArgumentException> { f.force(0.03, 0.0, 1.0, 1.0 / f.steepestFall) }
    }

    private class Rub(val out: DoubleArray, val v0: DoubleArray)

    /** Phase 0's iteration-3 rub on [bank]: pressure for a max(20 ms, 12-period) e-fold, a 0.2 landing floor. */
    private fun rub(bank: Modes.Bank, hz: Double, contact: DoubleArray, pickup: DoubleArray, seconds: Double): Rub {
        val friction = Modes.Friction(5000.0)
        val driver = 0.03
        val tau = maxOf(0.02, 12 / hz)
        val gamma0 = 2 * ln1000 / t60(0)
        val pressure = (gamma0 + 1 / tau) / (abs(friction.slope(driver)) * contact[0] * contact[0])
        val compliance = bank.compliance(contact)
        bank.drive(contact, 0.2 * driver * rate) // the finger lands
        val ramp = (0.02 * rate).toInt()
        val n = (seconds * rate).toInt()
        val out = DoubleArray(n)
        val v0 = DoubleArray(n)
        for (t in 0 until n) {
            bank.step()
            val vb = driver * minOf(1.0, t.toDouble() / ramp)
            bank.drive(contact, friction.force(vb, bank.velocityAlong(contact), pressure, compliance))
            out[t] = bank.velocityAlong(pickup)
            v0[t] = bank.velocity(0)
        }
        return Rub(out, v0)
    }

    private fun rms(x: DoubleArray, from: Double, to: Double): Double {
        val a = (from * rate).toInt()
        val b = (to * rate).toInt()
        var s = 0.0
        for (i in a until b) s += x[i] * x[i]
        return sqrt(s / (b - a))
    }

    @Test
    fun `a rubbed bank sings on its anchor, in tune and bounded`() {
        // Phase 0 I3b/I4c at MIDI 60, RING, COUPLE 0.25: contact taper ratio^−0.5, anchor fix,
        // e-fold in periods, landing floor 0.2. Mode 0 must win; mode 1 must not capture it.
        val hz = 261.63
        val bank = mercury(hz, 0.03)
        retune(bank, hz * bank.anchorScale())
        val contact = DoubleArray(16) { if (it < 12) ratios[it].pow(-0.5) else 0.0 }
        val pickup = DoubleArray(16) { if (it < 12) cos((it + 2) * 0.4) * ratios[it].pow(-0.2) else 0.25 }
        val r = rub(bank, hz, contact, pickup, 1.6)
        val steady = rms(r.out, 1.3, 1.6)
        val flat = rms(r.out, 1.45, 1.6) / rms(r.out, 1.3, 1.45)
        assertTrue(steady > 0.003, "the rub never sustained: steady RMS $steady")
        assertTrue(flat in 0.97..1.03, "the rub is still growing or dying at the end: $flat")
        assertTrue(r.out.all { it.isFinite() && abs(it) < 0.2 }, "the rub left its bounds")
        val from = (1.3 * rate).toInt()
        val len = (0.3 * rate).toInt()
        val f0 = peakHz(r.v0, hz, from, len, span = 30.0)
        assertTrue(abs(cents(f0, hz)) < 3.0, "the rub sits ${cents(f0, hz)} cents off the note")
        val atAnchor = peakMagnitude(r.out, hz, from, len)
        val atMode1 = peakMagnitude(r.out, hz * ratios[1], from, len)
        assertTrue(atAnchor > 10 * atMode1, "mode 1 is competing with the anchor: $atAnchor vs $atMode1")
    }

    private fun peakMagnitude(x: DoubleArray, hz: Double, from: Int, len: Int): Double {
        var best = 0.0
        for (k in -40..40) {
            val w = 2 * PI * hz * 2.0.pow(k / 1200.0) / rate
            var c = 0.0
            var s = 0.0
            for (j in 0 until len) {
                val win = 0.5 - 0.5 * cos(2 * PI * j / (len - 1))
                c += x[from + j] * win * cos(w * j)
                s += x[from + j] * win * sin(w * j)
            }
            best = maxOf(best, hypot(c, s))
        }
        return best
    }

    @Test
    fun `the bank is deterministic`() {
        fun once(): DoubleArray {
            val bank = mercury(329.63, 0.06)
            val contact = DoubleArray(16) { if (it < 12) ratios[it].pow(-0.5) else 0.0 }
            return rub(bank, 329.63, contact, primaries, 0.2).out
        }
        assertContentEquals(once(), once())
    }

    @Test
    fun `a bank refuses to step until every mode is tuned`() {
        val bank = Modes.Bank(2, rate)
        bank.tune(0, 440.0, 1.0)
        assertFailsWith<IllegalStateException> { bank.step() }
        assertFailsWith<IllegalArgumentException> { bank.tune(1, rate / 2.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { bank.tune(1, 440.0, 0.0) }
        bank.tune(1, 880.0, 1.0)
        bank.step()
    }
}
