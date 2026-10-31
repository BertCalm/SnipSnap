package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Projection fixtures use their analytic gain, never a normalized render as their oracle. */
class LatticeTest {
    private fun energy(v: Lattice.Vector) = v.x * v.x + v.y * v.y
    private fun energy(values: DoubleArray) = values.sumOf { it * it }
    private fun peak(values: FloatArray) = values.maxOfOrNull { abs(it.toDouble()) } ?: 0.0

    private fun rms(values: FloatArray, from: Double = 0.0, until: Double = values.size.toDouble() / Dsp.RATE): Double {
        val start = (from * Dsp.RATE).toInt().coerceIn(0, values.size)
        val end = (until * Dsp.RATE).toInt().coerceIn(start, values.size)
        return sqrt((start until end).sumOf { values[it].toDouble() * values[it] } / (end - start).coerceAtLeast(1))
    }

    private fun assertVector(expected: Lattice.Vector, actual: Lattice.Vector, message: String, tolerance: Double = 1e-12) {
        assertEquals(expected.x, actual.x, tolerance, "$message x")
        assertEquals(expected.y, actual.y, tolerance, "$message y")
    }

    private fun reference(count: Int, input: Lattice.Vector = Lattice.Vector(1.0, 0.0)): Lattice.ChainResult =
        Lattice.trace(input, DoubleArray(count) { PI * it / (2 * (count - 1)) }, DoubleArray(count) { 1.0 })

    private fun finite() = Lattice.Settings(
        midi = 60, velocity = .75f, turn = .85, stages = .5, twist = .5, color = 0.0, reject = 0.0,
        durationSeconds = .8, excitationSeconds = .5, haloEnabled = false,
    )

    /** A least-squares fit removes one global gain; the residual must then be a timbral change. */
    private fun shapeDifference(a: FloatArray, b: FloatArray, from: Double = .06, until: Double = .4): Double {
        val start = (from * Dsp.RATE).toInt()
        val end = minOf(a.size, b.size, (until * Dsp.RATE).toInt())
        val aa = (start until end).sumOf { a[it].toDouble() * a[it] }
        val ab = (start until end).sumOf { a[it].toDouble() * b[it] }
        val bb = (start until end).sumOf { b[it].toDouble() * b[it] }
        assertTrue(aa > 1e-15 && bb > 1e-15, "a shape comparison requires audible source material")
        val gain = ab / aa
        return sqrt((start until end).sumOf {
            val d = b[it] - gain * a[it]
            d * d
        } / bb)
    }

    /** Windowed quadrature measures the rendered harmonics, including the house output stage. */
    private fun harmonicPowers(samples: FloatArray, midi: Int): DoubleArray {
        val start = (.1 * Dsp.RATE).toInt()
        val end = minOf(samples.size, (.4 * Dsp.RATE).toInt())
        val count = end - start
        val rootHz = Keys.midiHz(midi).toDouble()
        return DoubleArray(Lattice.PARTIALS) { partial ->
            var real = 0.0
            var imaginary = 0.0
            for (j in 0 until count) {
                val window = .5 - .5 * cos(2 * PI * j / (count - 1))
                val angle = 2 * PI * rootHz * (partial + 1) * (start + j) / Dsp.RATE
                val value = samples[start + j] * window
                real += value * cos(angle)
                imaginary += value * sin(angle)
            }
            real * real + imaginary * imaginary
        }
    }

    @Test
    fun `ideal projection preserves its own unit axis rejects the orthogonal axis and keeps signs`() {
        for (theta in listOf(0.0, .37, PI / 2, -.9, 2 * PI + .2)) {
            val u = Lattice.Vector(cos(theta), sin(theta))
            val w = Lattice.Vector(-sin(theta), cos(theta))
            val pass = Lattice.project(u, theta)
            assertVector(u, pass.forward, "axis at $theta")
            assertEquals(0.0, pass.rejected, 1e-12, "aligned rejection at $theta")
            val reject = Lattice.project(w, theta)
            assertVector(Lattice.Vector(0.0, 0.0), reject.forward, "orthogonal input at $theta")
            assertEquals(1.0, reject.rejected, 1e-12, "signed orthogonal component at $theta")
            assertEquals(-1.0, Lattice.project(Lattice.Vector(-w.x, -w.y), theta).rejected, 1e-12,
                "rectification changes the rejection model")

            val once = Lattice.project(Lattice.Vector(.43, -.71), theta)
            assertVector(once.forward, Lattice.project(once.forward, theta).forward, "P squared equals P at $theta")
            assertEquals(.43 * .43 + .71 * .71, energy(once.forward) + once.rejected * once.rejected, 1e-12)
        }
        val finalBasis = Lattice.trace(Lattice.Vector(0.0, 1.0), doubleArrayOf(PI / 2), doubleArrayOf(1.0))
        assertEquals(1.0, finalBasis.passed, 1e-12, "a guided y-axis must be heard in the final gate's basis")
    }

    @Test
    fun `partial insertion bypasses at zero and partitions the current vector at every strength`() {
        val random = Random(39)
        repeat(1000) { trial ->
            val v = Lattice.Vector(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
            val theta = random.nextDouble(-PI, PI)
            val bypass = Lattice.project(v, theta, 0.0)
            assertVector(v, bypass.forward, "bypass trial $trial")
            assertEquals(0.0, bypass.rejected, "a bypass cannot collect rejected sound")
            for (a in listOf(.00001, .25, .5, .99, 1.0)) {
                val p = Lattice.project(v, theta, a)
                assertTrue(energy(p.forward) <= energy(v) + 1e-12, "partial gate adds forward energy at $a")
                assertEquals(energy(v), energy(p.forward) + p.rejected * p.rejected, 1e-12,
                    "partial gate loses or duplicates allocation at trial $trial insertion $a")
            }
        }
    }

    @Test
    fun `ordered chain telescopes once and reversing intermediate gates changes transmission`() {
        val random = Random(3901)
        repeat(500) { trial ->
            val count = random.nextInt(2, 17)
            val v = Lattice.Vector(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
            val angles = DoubleArray(count) { random.nextDouble(-PI, PI) }
            val strengths = DoubleArray(count) { if (it == count - 1) 1.0 else random.nextDouble() }
            val result = Lattice.trace(v, angles, strengths)
            assertEquals(energy(v), energy(result.forward) + energy(result.rejected), 2e-12,
                "trial $trial allocated an upstream reject again downstream")
            assertEquals(energy(result.forward), result.passed * result.passed, 1e-12,
                "fully inserted analyzer must read all final forward energy")
            assertTrue(abs(result.allocationResidual) < 2e-12)
        }
        val a = Lattice.trace(Lattice.Vector(1.0, 0.0), doubleArrayOf(0.0, .2, 1.0, PI / 2), DoubleArray(4) { 1.0 })
        val b = Lattice.trace(Lattice.Vector(1.0, 0.0), doubleArrayOf(0.0, 1.0, .2, PI / 2), DoubleArray(4) { 1.0 })
        assertTrue(abs(a.passed - b.passed) > .1, "the same final angle cannot erase ordered intermediate passage")
        assertTrue(a.rejected.indices.any { abs(a.rejected[it] - b.rejected[it]) > .1 },
            "the collector must reveal the changed rejection location")
    }

    @Test
    fun `crossed ideal fixtures distinguish audio amplitude from unpolarized optical power`() {
        val fixture = listOf(
            Triple(2, 0.0, 0.0), Triple(3, .5, .125),
            Triple(4, .649519052838329, .2109375), Triple(16, .9209058561872373, .4240337979799743),
        )
        for ((count, amplitude, opticalPower) in fixture) {
            val aligned = reference(count)
            val orthogonal = reference(count, Lattice.Vector(0.0, 1.0))
            assertEquals(amplitude, aligned.passed, 1e-12, "$count aligned audio amplitude")
            assertEquals(amplitude * amplitude, energy(aligned.forward), 1e-12, "$count audio power")
            assertEquals(opticalPower, .5 * (energy(aligned.forward) + energy(orthogonal.forward)), 1e-12,
                "$count incoherent equal orthogonal ensemble has exactly one entrance half-power factor")
            assertEquals(1.0, energy(aligned.forward) + energy(aligned.rejected), 1e-12)
        }
    }

    @Test
    fun `compiled integer chains retain stable slots and equally spaced reference orientations`() {
        for (count in 2..16) {
            val geometry = Lattice.compile(Lattice.Controls(turn = 1.0, color = 0.0, twist = .5, gateCount = count.toDouble()))
            assertEquals(count.toDouble(), geometry.effectiveGates, 1e-12)
            assertEquals(4, geometry.groups.size)
            for ((g, group) in geometry.groups.withIndex()) {
                assertEquals(16, group.angles.size, "group $g stable slots")
                assertEquals(16, group.insertions.size)
                assertEquals(16, group.rejection.size)
                assertEquals(0.0, group.sourceAngle, 1e-12, "reference source orientation")
                val active = group.angles.indices.filter { group.insertions[it] > .5 }
                assertEquals(count, active.size, "$count total gates includes the entrance and analyzer")
                assertTrue(group.insertions.all { it == 0.0 || it == 1.0 }, "an integer fixture contains no partial gate")
                for ((j, slot) in active.withIndex()) {
                    assertEquals(PI * j / (2 * (count - 1)), group.angles[slot], 1e-12,
                        "$count gates, group $g, active slot $slot is not an equal step")
                }
                assertEquals(reference(count).passed, group.transmission, 1e-12)
                assertEquals(1.0, group.transmission * group.transmission + energy(group.rejection), 1e-12)
                assertTrue(abs(group.allocationResidual) < 1e-12)
            }
        }
        val curved = Lattice.compile(Lattice.Controls(stages = .5))
        assertEquals(5.5, curved.effectiveGates, 1e-12,
            "the two-to-three-gate gesture needs the agreed quadratic STAGES travel")
    }

    @Test
    fun `fractional stage insertion is bounded and continuous through every integer threshold`() {
        for (count in 3..15) {
            for (color in listOf(0.0, .7)) {
                val left = Lattice.compile(Lattice.Controls(turn = .93, twist = .72, color = color, gateCount = count - 1e-7))
                val right = Lattice.compile(Lattice.Controls(turn = .93, twist = .72, color = color, gateCount = count + 1e-7))
                for (g in left.groups.indices) {
                    val a = left.groups[g]
                    val b = right.groups[g]
                    assertTrue(abs(a.transmission - b.transmission) < 1e-5, "$count threshold group $g forward discontinuity")
                    assertTrue(a.rejection.indices.all { abs(a.rejection[it] - b.rejection[it]) < 1e-5 },
                        "$count threshold group $g moves rejection to a different slot")
                    for (group in listOf(a, b)) {
                        assertTrue(abs(group.transmission) <= 1.0 + 1e-12)
                        assertEquals(1.0, group.transmission * group.transmission + energy(group.rejection), 1e-12)
                    }
                }
            }
        }
    }

    @Test
    fun `rendered extinction and restoration retain one source realization and common gain`() {
        val settings = finite().copy(turn = 0.0, gateCount = 2.0)
        val aligned = Lattice.probe(settings)
        assertTrue(rms(aligned.dry.samples) > .001, "the aligned fixture needs a real audible source")
        for ((count, amplitude) in listOf(2 to 0.0, 3 to .5, 4 to .649519052838329, 16 to .9209058561872373)) {
            val p = Lattice.probe(settings.copy(turn = 1.0, gateCount = count.toDouble()))
            assertEquals(aligned.diagnostics.sourceGain, p.diagnostics.sourceGain, 1e-12,
                "$count gates cannot turn on makeup gain")
            assertEquals(amplitude, rms(p.dry.samples) / rms(aligned.dry.samples), 2e-5,
                "$count gates changed the common-gain analytic transmission")
            val error = sqrt(p.dry.samples.indices.sumOf {
                val d = p.dry.samples[it] - amplitude * aligned.dry.samples[it]
                d * d
            } / p.dry.samples.size)
            assertTrue(error < 1e-6, "$count gates introduced unrelated material or reset the source: RMS error $error")
            if (count == 2) assertTrue(peak(p.snip.samples) < 1e-12, "output conditioning must preserve the exact crossed null")
        }
    }

    @Test
    fun `aligned chains cannot excite a hidden halo and reject one isolates actual discarded material`() {
        val aligned = finite().copy(turn = 0.0, gateCount = 16.0, reject = 1.0, haloEnabled = true)
        val clear = Lattice.probe(aligned)
        assertTrue(peak(clear.dry.samples) > .01)
        assertEquals(0.0, peak(clear.halo.samples), 1e-12, "zero-state aligned gates produce no reject oscillator or noise")
        assertEquals(0.0, peak(clear.snip.samples), 1e-12, "REJECT one must not leak clear transmission")
        assertTrue(clear.diagnostics.stageRejectedEnergy.all { abs(it) < 1e-20 })

        val crossed = Lattice.probe(aligned.copy(turn = 1.0, gateCount = 2.0))
        assertTrue(peak(crossed.dry.samples) < 1e-12)
        assertTrue(rms(crossed.halo.samples) > 1e-6, "a crossed gate must feed its discarded tone into the halo")
        assertTrue(rms(crossed.snip.samples) > 1e-6, "REJECT one must hear that actual collection")
        assertEquals(clear.diagnostics.sourceGain, crossed.diagnostics.sourceGain, 1e-12)
    }

    @Test
    fun `COLOR changes harmonic survival and TWIST changes rejection while declaring its two gate exception`() {
        val base = finite().copy(turn = .9, gateCount = 5.0, twist = .65)
        val common = Lattice.probe(base)
        val colored = Lattice.probe(base.copy(color = .8))
        assertTrue(common.diagnostics.groupTransmission.all { abs(it - common.diagnostics.groupTransmission[0]) < 1e-12 })
        val coloredGains = colored.diagnostics.groupTransmission
        assertTrue(coloredGains.max() - coloredGains.min() > .01, "COLOR must give groups different directional passage")
        assertTrue(shapeDifference(common.dry.samples, colored.dry.samples) > .005,
            "COLOR must change the rendered spectral balance beyond one global output gain")

        val early = Lattice.probe(base.copy(twist = 0.0, reject = 1.0, haloEnabled = true))
        val late = Lattice.probe(base.copy(twist = 1.0, reject = 1.0, haloEnabled = true))
        assertTrue(early.diagnostics.stageRejectedEnergy.indices.any {
            abs(early.diagnostics.stageRejectedEnergy[it] - late.diagnostics.stageRejectedEnergy[it]) > 1e-8
        }, "TWIST must move rejection between real stages")
        assertTrue(shapeDifference(early.halo.samples, late.halo.samples) > .005,
            "stage-local halo routing must reveal where TWIST rejects")

        val two = base.copy(gateCount = 2.0, reject = .5, haloEnabled = true)
        val a = Lattice.probe(two.copy(twist = 0.0))
        val b = Lattice.probe(two.copy(twist = 1.0))
        assertContentEquals(a.snip.samples, b.snip.samples,
            "with two gates TWIST has no intermediate distribution and must not fake brightness or modulation")
    }

    @Test
    fun `near crossed COLOR changes the low harmonic body after removing one global gain`() {
        // The original grouping left h1 through h3 together, so the dominant body never changed.
        // This listening fixture must distinguish the second/third harmonics from the root.
        val settings = finite().copy(turn = .9, gateCount = 2.0, color = 0.0)
        for (profile in Lattice.SourceProfile.entries) {
            val a = Lattice.probe(settings.copy(sourceProfile = profile))
            val b = Lattice.probe(settings.copy(sourceProfile = profile, color = 1.0))
            assertEquals(a.diagnostics.sourceGain, b.diagnostics.sourceGain, 1e-12,
                "COLOR cannot add source makeup for $profile")
            val difference = shapeDifference(a.dry.samples, b.dry.samples)
            assertTrue(difference > .2,
                "$profile near-cross COLOR must alter more than output loudness: shape residual $difference")
        }
    }

    @Test
    fun `source profiles retain distinct lower harmonics with a restrained upper spectrum`() {
        val settings = finite().copy(turn = 0.0, gateCount = 2.0)
        val warm = Lattice.render(settings)
        val pluck = Lattice.render(settings.copy(sourceProfile = Lattice.SourceProfile.PLUCK))
        val hollow = Lattice.render(settings.copy(sourceProfile = Lattice.SourceProfile.HOLLOW))
        val warmPower = harmonicPowers(warm.samples, settings.midi)
        val hollowPower = harmonicPowers(hollow.samples, settings.midi)
        val upperPower = warmPower.indices.filter { (it + 1) * Keys.midiHz(settings.midi) > 2000.0 }
            .sumOf { warmPower[it] }
        // R1's published source measured 10.3% above 2 kHz. This bar tests the intended
        // reduction in bright source energy without encoding R2's authored oscillator weights.
        val upperFraction = upperPower / warmPower.sum()
        assertTrue(upperFraction < .01,
            "warm source retains excessive upper harmonic energy: $upperFraction above 2 kHz")
        assertTrue(hollowPower[2] > 20 * hollowPower[1],
            "hollow source must retain a third-harmonic body with a reduced second harmonic")
        assertTrue(shapeDifference(warm.samples, pluck.samples) > .25,
            "pluck source must offer a distinct harmonic body at the same contour")
        assertTrue(shapeDifference(warm.samples, hollow.samples) > .25,
            "hollow source must offer a distinct harmonic body at the same contour")
        println("LATTICE R2 warm source: upper-harmonic power fraction above 2 kHz $upperFraction")
    }

    @Test
    fun `finite pluck articulation decays while the causal sustain fixture keeps its body`() {
        val settings = finite().copy(turn = 0.0, gateCount = 2.0,
            durationSeconds = 1.1, excitationSeconds = 1.0)
        for (profile in Lattice.SourceProfile.entries) {
            val sustain = Lattice.render(settings.copy(sourceProfile = profile))
            val pluckSettings = settings.copy(sourceProfile = profile, contour = Lattice.Contour.PLUCK)
            val pluck = Lattice.render(pluckSettings)
            val sustainRatio = rms(sustain.samples, .65, .85) / rms(sustain.samples, .1, .3)
            val pluckRatio = rms(pluck.samples, .65, .85) / rms(pluck.samples, .1, .3)
            assertTrue(sustainRatio in .95..1.05,
                "$profile causal sustain reference must keep its steady source body: ratio $sustainRatio")
            assertTrue(pluckRatio < .3,
                "$profile pluck must decay before the release or capped output fade: ratio $pluckRatio")
            assertContentEquals(pluck.samples, Lattice.render(pluckSettings).samples,
                "$profile source and finite contour must be deterministic")
            assertTrue(pluck.samples.all { it.isFinite() } && peak(pluck.samples) < .99)
            assertEquals(0f, pluck.samples.last(), "$profile finite pluck must end closed")
        }
    }

    @Test
    fun `finite extreme renders remain deterministic bounded and end without DC or an open tail`() {
        val settings = finite().copy(color = .75, reject = .65, haloEnabled = true)
        val first = Lattice.probe(settings)
        assertContentEquals(first.snip.samples, Lattice.probe(settings).snip.samples)
        for (s in listOf(
            settings.copy(midi = 36, velocity = .3f, turn = 0.0, stages = 0.0, twist = 0.0),
            settings.copy(midi = 60, velocity = .65f, turn = 1.0, stages = .4, twist = .5),
            settings.copy(midi = 84, velocity = 1f, turn = 1.0, stages = 1.0, twist = 1.0, color = 1.0, reject = 1.0),
        )) {
            val p = Lattice.probe(s)
            assertEquals(1, p.snip.channels)
            assertEquals(44_100, p.snip.sampleRate)
            assertTrue(p.snip.samples.isNotEmpty() && p.snip.samples.all { it.isFinite() })
            assertTrue(p.diagnostics.rawPeak.isFinite() && p.diagnostics.rawPeak <= 1.0,
                "raw chain or halo needs its own headroom; final protection cannot conceal excess gain: ${p.diagnostics.rawPeak}")
            assertTrue(peak(p.snip.samples) <= .99001)
            assertTrue(abs(p.snip.samples.average()) < .001, "finite DC mean for $s")
            assertTrue(p.snip.samples.last() == 0f, "the capped finite tail must end closed")
            assertTrue(abs(p.diagnostics.allocationResidual) < 1e-10)
        }
    }

    @Test
    fun `reference transmission retains the requested pitch across the audition register`() {
        for (midi in listOf(36, 60, 84)) {
            val p = Lattice.probe(finite().copy(midi = midi, turn = 1.0, gateCount = 6.0))
            val pitch = assertNotNull(Pitch.detect(p.snip, fromSec = .08f, windowSec = .3f), "MIDI $midi has no pitched body")
            val expected = Keys.midiHz(midi).toDouble()
            val cents = 1200 * ln(pitch.hz / expected) / ln(2.0)
            assertTrue(abs(cents) < 5.0, "MIDI $midi shifted $cents cents through a static projection chain")
            assertTrue(pitch.confidence > .5, "MIDI $midi has weak pitch evidence ${pitch.confidence}")
        }
    }

    @Test
    fun `velocity changes source effort and articulation rather than being erased by normalization`() {
        val settings = finite().copy(turn = 0.0, gateCount = 2.0)
        val soft = Lattice.probe(settings.copy(velocity = .3f))
        val medium = Lattice.probe(settings.copy(velocity = .65f))
        val hard = Lattice.probe(settings.copy(velocity = 1f))
        assertTrue(rms(soft.dry.samples) < rms(medium.dry.samples))
        assertTrue(rms(medium.dry.samples) < rms(hard.dry.samples))
        assertTrue(shapeDifference(soft.dry.samples, hard.dry.samples) > .001,
            "velocity must alter source attack or harmonic participation in addition to final gain")
        val silent = Lattice.probe(settings.copy(velocity = 0f, haloEnabled = true, reject = .5))
        assertEquals(0.0, peak(silent.snip.samples), "zero source effort excites neither branch")
    }

    @Test
    fun `the rejected halo decays after finite source excitation before the capped output fade`() {
        val p = Lattice.probe(finite().copy(turn = 1.0, gateCount = 2.0, reject = 1.0, haloEnabled = true,
            durationSeconds = 2.0, excitationSeconds = .35))
        val early = rms(p.halo.samples, .55, .8)
        val late = rms(p.halo.samples, 1.55, 1.8)
        assertTrue(early > 1e-7, "decay evidence needs an initially audible halo")
        // The slowest declared mode loses 60 dB in 1.8 seconds: one second costs about 33 dB.
        // A 20 dB bar leaves room for windowed beating and output conditioning, while rejecting
        // an accidentally undamped mode. Both windows precede the final 30 ms capped fade.
        assertTrue(late < .1 * early, "halo fails its declared decay before the guard fade: early RMS $early, late RMS $late")
        println("LATTICE finite halo: early RMS $early, late RMS $late, ratio ${late / early}")
    }

    @Test
    fun `halo operator has a measured resonant budget separate from projection allocation`() {
        for (midi in listOf(36, 60, 84)) {
            val rootHz = Keys.midiHz(midi).toDouble()
            val rootGain = Lattice.haloStageVectorGain(midi, rootHz)
            assertTrue(rootGain in 1.04..1.10,
                "MIDI $midi constant-peak bank lost its explicit fixed resonant gain: $rootGain")
            for (frequency in listOf(0.0, 20.0, rootHz, 2 * rootHz, 6 * rootHz, 17_000.0, 22_050.0)) {
                val gain = Lattice.haloStageVectorGain(midi, frequency)
                assertTrue(gain.isFinite() && gain >= 0.0 && gain <= Lattice.HALO_STAGE_VECTOR_GAIN_BOUND,
                    "MIDI $midi halo at $frequency Hz exceeds its separately declared operator bound: $gain")
            }
        }
    }

    @Test
    fun `interrupted preview rendering stops at a control block and a fresh render remains usable`() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException> { Lattice.render(finite()) }
        } finally {
            Thread.interrupted()
        }
        assertTrue(rms(Lattice.render(finite()).samples) > .001)
    }
}
