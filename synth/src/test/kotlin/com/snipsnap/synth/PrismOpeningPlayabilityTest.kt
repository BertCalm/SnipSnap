package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks the delivered body recipes, not a substitute synthetic endpoint bank. */
class PrismOpeningPlayabilityTest {
    @Test
    fun `four delivered openings retain pitch headroom and saved velocity across their registers`() {
        val cases = PrismOpeningReleaseEvidence.cases()
        assertEquals(24, cases.size)
        val rmsByTouch = mutableMapOf<Pair<String, String>, MutableMap<String, Double>>()
        for (case in cases) {
            val patch = case.patch
            val label = case.id
            val motion = assertNotNull(patch.motion, "$label missing its spectral gesture")
            assertNotNull(motion.amplitudesA, "$label missing its approved body opening")
            val probe = Prism.probe(patch.voice, patch.macros, patch.velocity, patch.template,
                normalize = true, motion = motion)
            val measurements = PrismOpeningReleaseEvidence.measure(patch, probe.samples)
            rmsByTouch.getOrPut(patch.name to case.register) { mutableMapOf() }[case.velocityLabel] = measurements.rms
            assertTrue(probe.samples.all { it.isFinite() }, "$label non-finite conditioned output")
            assertTrue(probe.raw.all { it.isFinite() }, "$label non-finite additive sum")
            assertTrue(probe.raw.maxOf { abs(it) } <= Prism.RAW_PEAK_CEILING + 1e-6f,
                "$label exceeds raw headroom before shared output leveling")
            assertTrue(measurements.peak in .001..1.0, "$label silent or clipped output: ${measurements.peak}")
            assertTrue(abs(measurements.dc) < .001, "$label DC offset ${measurements.dc}")
            assertTrue(abs(measurements.pitchCents) < 5.0, "$label root moved ${measurements.pitchCents} cents")
            assertTrue(measurements.periodCorrelation > .7, "$label weak periodic root evidence ${measurements.periodCorrelation}")
            if (measurements.expectedHz >= 40.0) {
                assertTrue(assertNotNull(measurements.detectorConfidence, "$label missing public pitch estimate") > .3f,
                    "$label low public pitch confidence")
            }
            assertTrue(probe.partialHz.isNotEmpty() && probe.partialHz.size <= Prism.PARTIALS)
            assertTrue(probe.partialHz.all { it.isFinite() && it > 0.0 && it < Prism.USABLE_BANDWIDTH_HZ },
                "$label contains a carrier outside the output bandwidth")
            assertTrue(probe.partialWeights.drop(1).all { it <= probe.partialWeights.first() / 1.119f },
                "$label loses its protected root")
            val recipe = PadRecipe(patch)
            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, restored, "$label recipe state changed")
            val replay = restored.render()
            assertEquals(Dsp.RATE, replay.sampleRate)
            assertEquals(1, replay.channels)
            assertContentEquals(probe.samples, replay.samples, "$label exact saved recipe replay")
            // The production velocity door must retain both endpoints and the original template.
            assertContentEquals(probe.samples, Velocity.atVelocity(patch.copy(velocity = .8f), patch.velocity).samples,
                "$label production velocity routing changed the saved sound")
        }
        for ((note, levels) in rmsByTouch) {
            assertTrue(levels.getValue("firm") > levels.getValue("soft") * 1.25,
                "$note loses audible performance dynamics through output leveling")
        }
    }

    @Test
    fun `delivered openings refuse loop HOLD and remain cancellable after synthesis starts`() {
        for (patch in PrismOpeningPresets.all()) {
            assertFailsWith<IllegalArgumentException>("${patch.name} must refuse a cyclic HOLD") {
                patch.withMacros(patch.macros + ("HOLD" to 1f))
            }
            assertFailsWith<IllegalArgumentException> {
                Prism.renderLoop(patch.voice, patch.macros, patch.velocity, patch.template, motion = patch.motion!!)
            }
            var controlBlocks = 0
            assertFailsWith<CancellationException>("${patch.name} must stop a render already in progress") {
                Prism.probe(patch.voice, patch.macros, patch.velocity, patch.template,
                    cancelled = { ++controlBlocks >= 16 }, keepRaw = false, motion = patch.motion)
            }
            assertEquals(16, controlBlocks, "cancellation is observed at its next control-block check")
        }
    }
}

/** Shared test/generator measurements. These are signal probes, not an assertion of sonic approval. */
internal object PrismOpeningReleaseEvidence {
    data class Case(val id: String, val register: String, val velocityLabel: String, val patch: PrismPatch)
    data class Measurements(
        val expectedHz: Double,
        val measuredHz: Double,
        val pitchCents: Double,
        val periodCorrelation: Double,
        val detectorHz: Float?,
        val detectorConfidence: Float?,
        val peak: Double,
        val rms: Double,
        val dc: Double,
    )

    fun cases(): List<Case> = PrismOpeningPresets.all().flatMapIndexed { family, base ->
        listOf("low" to 0f, "middle" to .5f, "high" to 1f).flatMap { (register, tune) ->
            listOf("soft" to .3f, "firm" to 1f).map { (velocityLabel, velocity) ->
                Case("opening-${family + 1}-$register-$velocityLabel", register, velocityLabel,
                    base.copy(macros = base.macros + ("TUNE" to tune), velocity = velocity))
            }
        }
    }

    fun measure(patch: PrismPatch, samples: FloatArray): Measurements {
        val expected = Prism.frequencyFor(patch.voice, patch.macros.getValue("TUNE"))
        // The .8-second reveal has completed here. This window includes over twelve cycles
        // even at BASS's C1; the public pitch detector deliberately starts at 40 Hz.
        val measured = FineTuning.measuredHz(samples, Dsp.RATE, expected, fromSec = 1.05f, bodySeconds = .45f)
        val from = (1.05 * Dsp.RATE).roundToInt()
        val count = (.45 * Dsp.RATE).roundToInt()
        val lag = (Dsp.RATE / expected).roundToInt()
        require(from + count <= samples.size && count > lag)
        var cross = 0.0
        var firstEnergy = 0.0
        var secondEnergy = 0.0
        for (i in from until from + count - lag) {
            val a = samples[i].toDouble()
            val b = samples[i + lag].toDouble()
            cross += a * b
            firstEnergy += a * a
            secondEnergy += b * b
        }
        val correlation = cross / sqrt(firstEnergy * secondEnergy).coerceAtLeast(1e-20)
        val snip = Snip(samples, 1, Dsp.RATE)
        val detected = if (expected >= 40f) Pitch.detect(snip, fromSec = 1.05f, windowSec = .45f) else null
        return Measurements(expected.toDouble(), measured, FineTuning.cents(measured, expected.toDouble()),
            correlation, detected?.hz, detected?.confidence,
            samples.maxOf { abs(it.toDouble()) }, sqrt(samples.sumOf { it.toDouble() * it } / samples.size), samples.average())
    }
}
