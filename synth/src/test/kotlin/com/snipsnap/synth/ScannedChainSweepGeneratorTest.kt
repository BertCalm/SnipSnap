package com.snipsnap.synth

import java.lang.reflect.Modifier
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sweep wiring, export guarantees, and causal checks of the BEND and BLOOM listening contexts. */
class ScannedChainSweepGeneratorTest {
    private fun fields(config: ScannedChainPrototype.Config): Map<String, Any?> =
        ScannedChainPrototype.Config::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .associate { field ->
                field.isAccessible = true
                field.name to field.get(config)
            }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    private fun peak(samples: FloatArray): Double = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0

    private fun windowPower(samples: FloatArray, rate: Int, fromSeconds: Double, toSeconds: Double): Double {
        val from = (fromSeconds * rate).roundToInt()
        val end = (toSeconds * rate).roundToInt()
        assertTrue(from >= 0 && end <= samples.size && end > from)
        var power = 0.0
        for (i in from until end) power += samples[i].toDouble() * samples[i]
        return power / (end - from)
    }

    @Test
    fun `each numeric sweep changes only its declared physical or scanner parameter`() {
        val expectedParameters = mapOf(
            "motion" to "motion",
            "damp" to "dampingPerSecond",
            "bend" to "nonlinear",
            "bloom" to "drive",
            "tune" to "midi",
            "attack" to "attackSeconds",
            "release" to "releaseSeconds",
            "memory" to "restFraction",
            "drive_rate" to "driveHz",
        )
        val controls = ScannedChainSweepGenerator.numericControls()
        assertEquals(expectedParameters.keys, controls.map { it.id }.toSet())
        assertEquals(controls.size, controls.map { it.id }.distinct().size)
        for (control in controls) {
            assertEquals(expectedParameters.getValue(control.id), control.parameter)
            assertTrue(control.steps.size >= 3, "${control.id} needs a useful range")
            assertEquals(control.steps.size, control.steps.map { it.id }.distinct().size)
            assertEquals(control.steps.size, control.steps.map { it.value }.distinct().size)
            assertEquals(1, control.steps.count { it.isDefault }, "one labelled default per control")
            assertEquals(if (control.id == "bloom") 0f else control.defaultValue, control.referenceValue)
            assertEquals(1, control.steps.count { it.value == control.referenceValue }, "one comparison reference per control")
            val baseline = fields(control.baseConfig)
            for (step in control.steps) {
                val point = fields(step.config)
                val changed = baseline.keys.filter { baseline[it] != point[it] }.toSet()
                assertEquals(if (step.isDefault) emptySet() else setOf(control.parameter), changed,
                    "${control.id}/${step.id} must keep the shape, note, seed and remaining configuration fixed")
                assertEquals(control.shapeId, step.shapeId)
                assertContentEquals(control.inputShape, step.inputShape)
            }
        }
        // A driver-rate sweep with no external force is a dead experiment.
        val driveRate = controls.single { it.id == "drive_rate" }
        assertTrue(driveRate.baseConfig.drive > 0f)
        assertTrue(driveRate.baseConfig.motion > 0f && !driveRate.baseConfig.frozen)
    }

    @Test
    fun `sweep inputs and little endian float hashes are deterministic and content sensitive`() {
        val first = ScannedChainSweepGenerator.numericControls()
        val second = ScannedChainSweepGenerator.numericControls()
        assertEquals(first.map { it.id }, second.map { it.id })
        for ((a, b) in first.zip(second)) {
            assertEquals(a.baseConfig, b.baseConfig)
            assertEquals(a.shapeId, b.shapeId)
            assertEquals(128, a.inputShape.size)
            assertTrue(a.inputShape.all { it.isFinite() })
            assertContentEquals(a.inputShape, b.inputShape)
            assertEquals(ScannedChainSweepGenerator.floatHash(a.inputShape), ScannedChainSweepGenerator.floatHash(b.inputShape))
            assertEquals(a.steps.map { it.config }, b.steps.map { it.config })
            val changed = a.inputShape.copyOf().also { it[0] += 0.001f }
            assertTrue(ScannedChainSweepGenerator.floatHash(a.inputShape) != ScannedChainSweepGenerator.floatHash(changed))
        }
        // Independent fixture: SHA-256 of little-endian IEEE-754 float32 [0, 1, -1].
        assertEquals("9b0fb770a5483b463e2e2afaa3b4f3db0d372b37b27260f1652b2a8cb6b25192",
            ScannedChainSweepGenerator.floatHash(floatArrayOf(0f, 1f, -1f)))
    }

    @Test
    fun `raw and matched export use safe constant gains without clipping`() {
        val samples = listOf(
            FloatArray(128) { if (it % 2 == 0) 0.25f else -0.25f },
            FloatArray(128).also { it[17] = 1f },
            FloatArray(128),
        )
        val originals = samples.map { it.copyOf() }
        val plan = ScannedChainSweepGenerator.matchedGains(samples)
        val rawGain = ScannedChainSweepGenerator.rawExportGain(samples)
        assertEquals(0.9, rawGain, "one raw attenuation covers the entire group's largest peak")
        assertEquals(1.0, ScannedChainSweepGenerator.rawExportGain(listOf(samples.first())),
            "raw exports do not amplify quieter groups")
        assertEquals(samples.size, plan.gains.size)
        assertTrue(plan.targetRms > 0 && plan.targetRms < 0.12,
            "the high-crest-factor take lowers the target for the entire comparison")
        for (index in samples.indices) {
            val input = samples[index]
            val gain = plan.gains[index]
            val scaled = ScannedChainSweepGenerator.applyGain(input, gain)
            val raw = ScannedChainSweepGenerator.applyGain(input, rawGain)
            assertContentEquals(originals[index], input, "export must preserve the raw input")
            assertEquals(input.size, scaled.size)
            assertTrue(scaled.all { it.isFinite() })
            assertTrue(peak(scaled) <= 0.900001)
            assertTrue(peak(raw) <= 0.900001)
            for (sample in input.indices) {
                assertEquals((input[sample] * gain).toFloat(), scaled[sample], "one scalar applies to every sample")
                assertEquals((input[sample] * rawGain).toFloat(), raw[sample], "raw clips share the group's scalar")
            }
            if (rms(input) > 1e-8) {
                assertTrue(abs(rms(scaled) - plan.targetRms) < 1e-7, "active takes have a common matched RMS")
            } else {
                assertEquals(1.0, gain)
                assertContentEquals(input, scaled)
            }
        }
    }

    @Test
    fun `silence and numerical residue are not amplified into a listening example`() {
        val inputs = listOf(FloatArray(32), FloatArray(32) { if (it % 2 == 0) 1e-10f else -1e-10f })
        val plan = ScannedChainSweepGenerator.matchedGains(inputs)
        assertEquals(listOf(1.0, 1.0), plan.gains)
        assertEquals(1.0, ScannedChainSweepGenerator.rawExportGain(inputs))
        for ((input, gain) in inputs.zip(plan.gains)) {
            assertContentEquals(input, ScannedChainSweepGenerator.applyGain(input, gain))
        }
    }

    @Test
    fun `bend extremes in the published rough context alter audio beyond scalar gain`() {
        val bend = ScannedChainSweepGenerator.numericControls().single { it.id == "bend" }
        val linear = bend.steps.minBy { it.config.nonlinear }
        val nonlinear = bend.steps.maxBy { it.config.nonlinear }
        assertEquals(0f, linear.config.nonlinear)
        assertEquals(8f, nonlinear.config.nonlinear)
        val a = ScannedChainPrototype.render(linear.config.copy(seconds = 0.3f, releaseSeconds = 0.05f), linear.inputShape)
        val b = ScannedChainPrototype.render(nonlinear.config.copy(seconds = 0.3f, releaseSeconds = 0.05f), nonlinear.inputShape)
        assertContentEquals(a.initialShape, b.initialShape)
        assertTrue(a.raw.samples.all { it.isFinite() } && b.raw.samples.all { it.isFinite() })
        val referencePower = a.raw.samples.sumOf { it.toDouble() * it }
        val changedPower = b.raw.samples.sumOf { it.toDouble() * it }
        assertTrue(referencePower > 1e-8 && changedPower > 1e-8)
        val gain = a.raw.samples.indices.sumOf { a.raw.samples[it].toDouble() * b.raw.samples[it] } / referencePower
        val residualPower = a.raw.samples.indices.sumOf {
            val difference = b.raw.samples[it].toDouble() - gain * a.raw.samples[it]
            difference * difference
        }
        assertTrue(sqrt(residualPower / changedPower) > 0.01,
            "the cubic stiffness control must produce an active waveform change, not only a level change")
    }

    @Test
    fun `bloom default adds a clear later swell relative to its off reference`() {
        val bloom = ScannedChainSweepGenerator.numericControls().single { it.id == "bloom" }
        val off = bloom.steps.single { it.value == bloom.referenceValue }
        val selected = bloom.steps.single { it.isDefault }
        assertEquals(0f, off.config.drive, "A compares against an unforced chain")
        assertEquals(0.8f, selected.config.drive, "the selected default stays distinct from the reference")
        val passive = ScannedChainPrototype.render(off.config, off.inputShape)
        val active = ScannedChainPrototype.render(selected.config, selected.inputShape)
        val repeated = ScannedChainPrototype.render(selected.config, selected.inputShape)
        assertContentEquals(passive.initialShape, active.initialShape)
        assertContentEquals(active.raw.samples, repeated.raw.samples, "the seeded force is deterministic")
        assertContentEquals(active.finalShape, repeated.finalShape)
        for (result in listOf(passive, active, repeated)) {
            assertTrue(result.raw.samples.all { it.isFinite() } && result.diagnostics.finite)
            assertTrue(peak(result.raw.samples) <= 1.0)
            assertTrue(result.diagnostics.maxDisplacement <= 0.9)
        }

        fun lateToEarly(result: ScannedChainPrototype.Result): Double {
            // Early follows the attack; late stays inside the finite driver window
            // and before the output release. Each ratio cancels constant gain.
            val early = windowPower(result.raw.samples, result.raw.sampleRate, 0.1, 0.5)
            val late = windowPower(result.raw.samples, result.raw.sampleRate, 1.2, 1.8)
            assertTrue(early > 1e-8 && late > 1e-8, "both windows contain real signal")
            return late / early
        }
        val contrastDb = 10 * log10(lateToEarly(active) / lateToEarly(passive))
        // The old factory context measured only 1.14 dB here; a phase-only
        // difference or uniform gain cannot meet this envelope-shape threshold.
        assertTrue(contrastDb >= 6.0, "BLOOM must add a later swell after gain is removed: $contrastDb dB")
        assertTrue(sqrt(windowPower(active.raw.samples, active.raw.sampleRate, 1.2, 1.8)) > 0.005,
            "the driven late window must have useful raw signal")
    }
}
