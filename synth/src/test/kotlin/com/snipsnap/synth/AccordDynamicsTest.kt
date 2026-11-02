package com.snipsnap.synth

import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent mass-matrix, conservation and Python-oracle tests of the actual mechanics. */
class AccordDynamicsTest {
    private val reference = AccordInitializer.REFERENCE_SINE_20261022
    private val seed = AccordDynamics.REFERENCE_SEED

    @Test fun `the whole proposed domain has a positive mass matrix and physical inertia`() {
        for (kappa in listOf(0.0, .5, 1.0)) for (spread in listOf(0.0, .04))
            for ((mass, damping) in listOf(.05 to .005, .6 to .05, 2.4 to .2)) {
                val parameters = AccordDynamics.Parameters(kappa, spread, mass, damping)
                val model = AccordDynamics.model(parameters)
                assertTrue(parameters.analyticLowerBound >= .0796 - 1e-12)
                assertTrue(model.actualLowerBound >= parameters.analyticLowerBound - 1e-12)
                assertTrue(model.inertias().all { it >= .02 })
                val trace = AccordDynamics.prepare(parameters, cycles = 2)
                assertTrue(trace.diagnostics.minD >= model.actualLowerBound - 1e-12)
                assertTrue(trace.stateAt(2.0).all { it.isFinite() })
            }
        assertEquals(.0796, AccordDynamics.Parameters(1.0, .04, .05, .005).analyticLowerBound, 1e-14)
    }

    @Test fun `Schur acceleration agrees with an independently assembled reciprocal mass matrix`() {
        val random = Random(2731)
        for (kappa in listOf(0.0, .35, 1.0)) repeat(12) {
            val p = AccordDynamics.Parameters(kappa, .04, .05, .005)
            val model = AccordDynamics.model(p)
            val inertia = model.inertias()
            val state = DoubleArray(32) { random.nextDouble(-1.0, 1.0) }
            val matrix = Array(16) { DoubleArray(16) }
            val force = DoubleArray(16)
            matrix[15][15] = p.translatingMass
            force[15] = -p.damping * state[31]
            for (i in 0 until 15) {
                matrix[i][i] = inertia[i]
                val coupling = kappa * .02 * cos(state[i])
                matrix[i][15] = coupling
                matrix[15][i] = coupling
                val omega = state[15 + i]
                force[i] = inertia[i] * p.drive * (1 - (state[i] / .38) * (state[i] / .38)) * omega - .02 * sin(state[i])
                force[15] += kappa * .02 * sin(state[i]) * omega * omega
            }
            val direct = solve(matrix, force)
            val actual = DoubleArray(32)
            model.derivative(state, actual)
            for (i in 0 until 15) assertEquals(direct[i], actual[15 + i], 2e-12)
            assertEquals(direct[15], actual[31], 2e-12)
        }
    }

    @Test fun `removing support reaction is detected rather than mistaken for reciprocal coupling`() {
        val p = AccordDynamics.Parameters()
        val model = AccordDynamics.model(p)
        val state = AccordDynamics.initialState(p)
        val derivative = DoubleArray(32)
        model.derivative(state, derivative)
        val inertia = model.inertias()
        var error = 0.0
        for (i in 0 until 15) {
            val q = p.kappa * .02 * cos(state[i])
            val uncoupled = p.drive * (1 - state[i] * state[i] / (.38 * .38)) * state[15 + i] - .02 / inertia[i] * sin(state[i])
            error = max(error, abs(uncoupled - derivative[15 + i]))
            assertEquals(uncoupled - q * derivative[31] / inertia[i], derivative[15 + i], 1e-14)
        }
        assertTrue(error > .005, "Reaction ablation failed to change acceleration: $error")
    }

    @Test fun `conservative energy and momentum converge with grid refinement`() {
        val p = AccordDynamics.Parameters(damping = 0.0, drive = 0.0)
        val coarse = AccordDynamics.prepare(p, cycles = 30, stepsPerCycle = 256)
        val fine = AccordDynamics.prepare(p, cycles = 30, stepsPerCycle = 512)
        assertTrue(coarse.diagnostics.relativeEnergyWorkError <= 1e-6, "${coarse.diagnostics}")
        assertTrue(fine.diagnostics.relativeEnergyWorkError < coarse.diagnostics.relativeEnergyWorkError * .15)
        assertTrue(fine.diagnostics.maxMomentumChange < 1e-7, "${fine.diagnostics}")
        for (knot in 0..30 * 256) assertEquals(0.0, coarse.workAtKnot(knot))
    }

    @Test fun `driven energy follows independent work quadrature and rejects a sign-flipped ledger`() {
        val p = AccordDynamics.Parameters()
        val trace = AccordDynamics.prepare(p, cycles = 30)
        val model = AccordDynamics.model(p)
        val initialEnergy = model.energy(trace.stateAt(0.0))
        assertTrue(trace.diagnostics.relativeEnergyWorkError <= 1e-6, "${trace.diagnostics}")
        // The dense position-Hermite derivative changes its polynomial at each
        // solver knot. Resolve each piece before judging the separate ledger;
        // a two/four-subdivision Simpson comparison was not yet converged.
        val eightTimes = independentWork(trace, model.inertias(), 8)
        val sixteenTimes = independentWork(trace, model.inertias(), 16)
        assertTrue(abs(eightTimes - sixteenTimes) / abs(initialEnergy) <= 1e-8,
            "Independent quadrature did not converge: $eightTimes $sixteenTimes")
        val finalDelta = model.energy(trace.stateAt(30.0)) - initialEnergy
        assertTrue(abs(finalDelta - sixteenTimes) / abs(initialEnergy) < 1e-6)
        var incorrectResidual = 0.0
        for (knot in 0..30 * 256) {
            val state = trace.stateAt(knot / 256.0)
            incorrectResidual = max(incorrectResidual, abs(model.energy(state) - initialEnergy + trace.workAtKnot(knot)) / abs(initialEnergy))
        }
        assertTrue(incorrectResidual > .01, "A sign-flipped power ledger must fail: $incorrectResidual")
    }

    @Test fun `reference events match dense DOP853 and grid halving improves their timing`() {
        val coarse = AccordDynamics.prepare(cycles = 32, stepsPerCycle = 256)
        val fine = AccordDynamics.prepare(cycles = 32, stepsPerCycle = 512)
        val coarseError = oracleEventError(coarse)
        val fineError = oracleEventError(fine)
        assertTrue(coarseError < 2e-6, "Maximum event error $coarseError reference cycles = seconds at slowest PACE")
        assertTrue(fineError < coarseError * .2 + 1e-10, "$coarseError -> $fineError")
        println("ACCORD event error versus Python DOP853, reference cycles: 256=$coarseError, 512=$fineError")
        for (event in coarse.events) {
            assertTrue(event.canonicalSpeed.isFinite() && event.canonicalSpeed > 0)
            val state = coarse.stateAt(event.cycle)
            assertTrue(abs(state[event.mechanism]) < 1e-11)
            assertEquals(event.direction, if (state[15 + event.mechanism] > 0) 1 else -1)
        }
    }

    @Test fun `compatibility pressure preserves interpolation order across the full approved source`() {
        val coarse = AccordDynamics.prepare(cycles = 32, stepsPerCycle = 256)
        val fine = AccordDynamics.prepare(cycles = 32, stepsPerCycle = 512)
        val a = pressureError(coarse)
        val b = pressureError(fine)
        assertTrue(a.first <= 1e-3 && a.second <= 1e-2, "Pressure normalized RMS/max error: $a")
        assertTrue(b.first < a.first * .4 + 1e-9, "$a -> $b")
        println("ACCORD compatibility-pressure normalized RMS/max error: 256=$a, 512=$b")
    }

    @Test fun `coupling zero leaves the support stationary and the other fourteen histories independent`() {
        val p = AccordDynamics.Parameters(kappa = 0.0)
        val initial = AccordDynamics.initialState(p)
        val changed = initial.copyOf().also { it[3] += .2; it[18] -= .17 }
        val original = AccordDynamics.integrate(p, seed, reference, 8, 256, null, initial)
        val perturbed = AccordDynamics.integrate(p, seed, reference, 8, 256, null, changed)
        for (j in 0..320) {
            val a = original.stateAt(j / 40.0)
            val b = perturbed.stateAt(j / 40.0)
            for (i in a.indices) if (i != 3 && i != 18) assertEquals(a[i], b[i], "State $i at descriptor $j")
            assertEquals(0.0, a[30], 0.0)
            assertEquals(0.0, a[31], 0.0)
        }
    }

    @Test fun `isolated drive sustains the measured limit cycle and natural spread is assigned through inertia`() {
        val p = AccordDynamics.Parameters(kappa = 0.0, spread = 0.0)
        val trace = AccordDynamics.prepare(p, cycles = 80)
        var amplitude = 0.0
        for (knot in 60 * 256..80 * 256) amplitude = max(amplitude, abs(trace.stateAt(knot / 256.0)[0]))
        assertEquals(.7624314, amplitude, .0001)
        val crossings = trace.events.filter { it.mechanism == 0 && it.direction == 1 && it.cycle > 60 }
        val measuredPeriod = (crossings.last().cycle - crossings.first().cycle) / (crossings.size - 1) * AccordDynamics.REFERENCE_PERIOD
        assertEquals(7.14737522, measuredPeriod, .00001)
        val spreadParameters = p.copy(spread = .04)
        val inertia = AccordDynamics.model(spreadParameters).inertias()
        val eta = AccordDynamics.rateOffsets(seed, reference)
        for (i in inertia.indices) assertEquals(AccordDynamics.REFERENCE_OMEGA * (1 + .04 * eta[i]), sqrt(.02 / inertia[i]), 1e-15)
    }

    @Test fun `long reference fixtures distinguish gathered phase from frequency-locked splay`() {
        val gathered = AccordDynamics.prepare(cycles = 140)
        val fixed = AccordDynamics.prepare(AccordDynamics.Parameters(kappa = 0.0), cycles = 140)
        val splay = AccordDynamics.prepare(AccordDynamics.Parameters(baseMass = .05, damping = .005), cycles = 140)
        assertTrue(gathered.phaseOrderAt(120.0) > .999)
        assertTrue(gathered.phaseOrderAt(20.0) > .95)
        assertTrue(fixed.phaseOrderAt(120.0) < .8)
        assertTrue(splay.phaseOrderAt(120.0) < .05)
        assertTrue(splay.phaseOrderAt(120.0, 2) < .05)
        assertTrue(relativeFrequencySpread(splay, 120.0, 140.0) < .001)
        assertTrue(relativeFrequencySpread(fixed, 120.0, 140.0) > .01)
    }

    @Test fun `different horizons have byte-identical prefixes and immutable owned output`() {
        val short = AccordDynamics.prepare(cycles = 16)
        val long = AccordDynamics.prepare(cycles = 32)
        for (knot in 0..16 * 256) assertContentEquals(short.stateAt(knot / 256.0), long.stateAt(knot / 256.0))
        assertEquals(short.events, long.events.filter { it.cycle <= 16 })
        val copy = short.stateAt(8.0)
        copy.fill(Double.NaN)
        assertTrue(short.stateAt(8.0).all { it.isFinite() })
        assertFailsWith<UnsupportedOperationException> { (short.events as MutableList<AccordDynamics.Event>).clear() }
        val expected = short.stateAt(8.003)
        assertContentEquals(expected, short.stateAt(8.003))
    }

    @Test fun `project initialization is stable bounded centered and preserves all seed bits`() {
        val initializer = AccordInitializer.PROJECT_SINE_V1
        val offsets = AccordDynamics.rateOffsets(seed, initializer)
        assertContentEquals(PROJECT_OFFSETS, offsets)
        assertTrue(abs(offsets.sum()) < 1e-12 && offsets.all { it in -1.0..1.0 })
        val a = AccordDynamics.initialState(AccordDynamics.Parameters(), Long.MIN_VALUE, initializer)
        val b = AccordDynamics.initialState(AccordDynamics.Parameters(), Long.MIN_VALUE, initializer)
        val c = AccordDynamics.initialState(AccordDynamics.Parameters(), Long.MAX_VALUE, initializer)
        assertContentEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertFalse(a.contentEquals(AccordDynamics.initialState(AccordDynamics.Parameters())))
        assertFailsWith<IllegalArgumentException> { AccordDynamics.prepare(seed = seed + 1) }
        val returnedOffsets = AccordDynamics.rateOffsets(seed, reference)
        returnedOffsets[0] = 99.0
        assertTrue(AccordDynamics.rateOffsets(seed, reference)[0] < 1)
    }

    @Test fun `cancellation and malformed states fail explicitly before cache publication`() {
        assertFailsWith<CancellationException> { AccordDynamics.prepare(cancelled = { true }) }
        var checks = 0
        assertFailsWith<CancellationException> { AccordDynamics.prepare(cancelled = { ++checks == 3 }) }
        assertFailsWith<IllegalArgumentException> { AccordDynamics.prepare(cycles = 257) }
        assertFailsWith<IllegalArgumentException> { AccordDynamics.prepare(stepsPerCycle = 255) }
        assertFailsWith<IllegalArgumentException> { AccordDynamics.Parameters(kappa = Double.NaN) }
        val bad = AccordDynamics.initialState(AccordDynamics.Parameters()).also { it[0] = Double.NaN }
        assertFailsWith<IllegalStateException> { AccordDynamics.model(AccordDynamics.Parameters()).derivative(bad, DoubleArray(32)) }
    }

    private fun independentWork(trace: AccordDynamics.Trace, inertia: DoubleArray, subdivisions: Int): Double {
        val n = trace.cycles * trace.stepsPerCycle * subdivisions
        val dt = AccordDynamics.REFERENCE_PERIOD / (trace.stepsPerCycle * subdivisions)
        val state = DoubleArray(32)
        var sum = 0.0
        for (j in 0..n) {
            trace.stateAt(j.toDouble() / (trace.stepsPerCycle * subdivisions), state)
            var power = -trace.parameters.damping * state[31] * state[31]
            for (i in 0 until 15) power += inertia[i] * trace.parameters.drive * (1 - state[i] * state[i] / (.38 * .38)) * state[15 + i] * state[15 + i]
            sum += power * if (j == 0 || j == n) 1 else if (j % 2 == 1) 4 else 2
        }
        return sum * dt / 3
    }

    private fun oracleEventError(trace: AccordDynamics.Trace): Double {
        val expected = oracle.events.groupBy { it.mechanism to it.direction }
        val actual = trace.events.filter { it.cycle <= 32 }.groupBy { it.mechanism to it.direction }
        assertEquals(expected.keys, actual.keys)
        var maximum = 0.0
        for ((key, events) in expected) {
            val rendered = actual.getValue(key)
            assertEquals(events.size, rendered.size, "Event count for $key")
            for ((a, b) in events.zip(rendered)) maximum = max(maximum, abs(a.cycle - b.cycle))
        }
        return maximum
    }

    private fun pressureError(trace: AccordDynamics.Trace): Pair<Double, Double> {
        val state = DoubleArray(32)
        val expectedState = DoubleArray(32)
        var squareError = 0.0
        var squareReference = 0.0
        var maximum = 0.0
        for (j in 0 until 8 * 2000) {
            val cycle = 8 + j / 2000.0 * 2
            trace.compatibilityStateAt(cycle, state)
            val position = cycle * 40
            val knot = position.toInt()
            val u = position - knot
            val a = oracle.states[knot]
            val b = oracle.states[knot + 1]
            for (i in expectedState.indices) expectedState[i] = a[i] + u * (b[i] - a[i])
            val expected = pressure(expectedState)
            val delta = pressure(state) - expected
            squareError += delta * delta
            squareReference += expected * expected
            maximum = max(maximum, abs(delta))
        }
        return sqrt(squareError / max(squareReference, 1e-16)) to maximum
    }

    private fun pressure(state: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until 15) {
            val zone = max(0.0, 1 - state[i] * state[i] / (.17 * .17))
            sum += zone * zone * minOf(abs(state[15 + i]) / .8, 2.0) / 15
        }
        return sum
    }

    private fun relativeFrequencySpread(trace: AccordDynamics.Trace, start: Double, end: Double): Double {
        val frequencies = (0 until 15).map { i ->
            val crossings = trace.events.filter { it.mechanism == i && it.direction == 1 && it.cycle in start..end }
            (crossings.size - 1) / (crossings.last().cycle - crossings.first().cycle)
        }
        return (frequencies.max() - frequencies.min()) / frequencies.average()
    }

    private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val a = Array(rhs.size) { i -> matrix[i].copyOf() }
        val b = rhs.copyOf()
        for (j in b.indices) {
            val pivot = (j until b.size).maxBy { abs(a[it][j]) }
            val row = a[j]; a[j] = a[pivot]; a[pivot] = row
            val value = b[j]; b[j] = b[pivot]; b[pivot] = value
            assertTrue(abs(a[j][j]) > 1e-12)
            for (i in j + 1 until b.size) {
                val scale = a[i][j] / a[j][j]
                for (k in j until b.size) a[i][k] -= scale * a[j][k]
                b[i] -= scale * b[j]
            }
        }
        val result = DoubleArray(b.size)
        for (i in b.indices.reversed()) {
            var remaining = b[i]
            for (j in i + 1 until b.size) remaining -= a[i][j] * result[j]
            result[i] = remaining / a[i][i]
        }
        return result
    }

    private data class Oracle(val states: List<DoubleArray>, val events: List<AccordDynamics.Event>)
    private val oracle: Oracle by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/accord/dynamics-dop853.csv.gz"))
        val states = ArrayList<DoubleArray>()
        val events = ArrayList<AccordDynamics.Event>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith('#') }.forEach { line ->
                val fields = line.split(',')
                when (fields[0]) {
                    "S" -> states += fields.drop(2).map { it.toDouble() }.toDoubleArray()
                    "E" -> events += AccordDynamics.Event(fields[1].toDouble(), fields[2].toDouble().toInt(),
                        fields[3].toDouble().toInt(), fields[4].toDouble(), events.size.toLong())
                }
            }
        }
        Oracle(states, events)
    }

    private companion object {
        // Generated independently from the documented Dsp seed/LCG arithmetic,
        // including each Float division/subtraction, not from this initializer.
        val PROJECT_OFFSETS = doubleArrayOf(
            -.04061909720012869, -.5919100183424135, .0028240695444752425,
            -.6616052213228325, .31876783106455403, .7624694066345904,
            -.24195805542669976, 1.0, -.0010121442202467013,
            -.5411340003974481, .1063983192702884, .6798091688966067,
            -.011466679612386345, -.5517161660259673, -.2288474128623923,
        )
    }
}
