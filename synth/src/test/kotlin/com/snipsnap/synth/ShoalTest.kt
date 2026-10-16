package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mechanical contracts for the two-voice prototype; sonic acceptance remains a listening decision. */
class ShoalTest {
    private val timbral = listOf("DENSITY", "CURRENT", "COHESION", "STRAY", "GLOW")
    private val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_OPEN, DrumClass.HAT_CLOSED, DrumClass.TOM)

    @Test
    fun `the two prototype voices expose five timbral controls and hold independently of pitch`() {
        assertEquals(setOf(ShoalVoice.GLASS, ShoalVoice.RIPPLE), ShoalVoice.entries.toSet())
        for (voice in ShoalVoice.entries) {
            val specs = Shoal.macrosFor(voice)
            assertEquals(timbral + "HOLD", specs.map { it.name })
            assertTrue(specs.all { it.default in 0f..1f && it.neutral in 0f..1f })
            assertEquals(0f, Shoal.defaults(voice).getValue("HOLD"))
        }
    }

    @Test
    fun `isolated transfers use the previous complete state and conserve nonnegative energy`() {
        val original = doubleArrayOf(1.0, 2.0, 4.0)
        val fractions = arrayOf(doubleArrayOf(0.0, .5, 0.0), doubleArrayOf(0.0, 0.0, .25), doubleArrayOf(.1, 0.0, 0.0))
        val next = Shoal.isolatedTransfer(original, fractions)
        // Hand-calculated simultaneous exchange. An in-place donor loop gives a different result.
        for ((i, expected) in doubleArrayOf(.9, 2.0, 4.1).withIndex()) assertEquals(expected, next[i], 1e-12)
        assertContentEquals(doubleArrayOf(1.0, 2.0, 4.0), original, "transfer mutated its donor snapshot")
        var state = next
        repeat(1_000) {
            state = Shoal.isolatedTransfer(state, fractions)
            assertTrue(state.all { it.isFinite() && it >= 0.0 })
            assertEquals(7.0, state.sum(), 7e-12, "isolated transport created or lost energy")
        }
        // Complete evacuation into another donor still cannot re-transfer that energy this step.
        val exchanged = Shoal.isolatedTransfer(doubleArrayOf(3.0, 0.0),
            arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0)))
        assertContentEquals(doubleArrayOf(0.0, 3.0), exchanged)
    }

    @Test
    fun `invalid transfer states and outgoing budgets are refused before evolution`() {
        val valid = arrayOf(doubleArrayOf(0.0, .5), doubleArrayOf(.5, 0.0))
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Shoal.isolatedTransfer(doubleArrayOf(1.0, bad), valid) }
        }
        for (bad in listOf(-.1, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                Shoal.isolatedTransfer(doubleArrayOf(1.0, 2.0), arrayOf(doubleArrayOf(0.0, bad), doubleArrayOf(0.0, 0.0)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            Shoal.isolatedTransfer(doubleArrayOf(1.0, 2.0), arrayOf(doubleArrayOf(.6, .6), doubleArrayOf(0.0, 0.0)))
        }
    }

    @Test
    fun `fresh renders and macro order reproduce the audio and internal population`() {
        for (voice in ShoalVoice.entries) {
            val macros = Shoal.defaults(voice) + mapOf("CURRENT" to .67f, "STRAY" to .39f)
            val a = Shoal.probe(voice, macros, velocity = .7f, seconds = .8f)
            val b = Shoal.probe(voice, macros.entries.reversed().associate { it.key to it.value }, velocity = .7f, seconds = .8f)
            assertContentEquals(a.raw, b.raw)
            assertContentEquals(a.samples, b.samples)
            assertEquals(a.diagnostics, b.diagnostics)
            assertEquals(a.energyTrace.size, b.energyTrace.size)
            for ((left, right) in a.energyTrace.zip(b.energyTrace)) {
                assertEquals(left.seconds, right.seconds)
                assertContentEquals(left.energy, right.energy)
                assertContentEquals(left.strayCents, right.strayCents)
            }
        }
    }

    @Test
    fun `ordinary and extreme registers retain bounded population and prelevel output`() {
        for (voice in ShoalVoice.entries) for (midi in listOf(24, 60, 84)) for (high in listOf(false, true)) {
            val macros = Shoal.defaults(voice) + if (high) timbral.associateWith { 1f } else emptyMap()
            val p = Shoal.probe(voice, macros, midi, seconds = .8f)
            val label = "$voice MIDI$midi all-high=$high"
            assertTrue(p.raw.isNotEmpty() && p.raw.all { it.isFinite() }, "$label invalid raw sum")
            assertTrue(p.samples.all { it.isFinite() }, "$label invalid conditioned output")
            assertTrue(p.diagnostics.rawPeak < 1.0, "$label raw peak ${p.diagnostics.rawPeak} needs hidden peak rescue")
            assertTrue(rms(p.samples) > 1e-4, "$label energy collapsed")
            assertTrue(abs(p.samples.average()) < .015, "$label accumulates DC")
            assertTrue(p.diagnostics.maxFrequencyHz < Dsp.RATE / 2.0, "$label emits a partial above output Nyquist")
            assertTrue(p.diagnostics.maxTransferResidual < 1e-10, "$label transport residual ${p.diagnostics.maxTransferResidual}")
            assertTrue(p.diagnostics.maxEnergySum <= 2.0 + 1e-12, "$label exceeded the prototype's explicit energy budget")
            assertTrue(p.energyTrace.isNotEmpty())
            for (frame in p.energyTrace) {
                assertTrue(frame.energy.all { it.isFinite() && it >= 0.0 }, "$label negative or non-finite energy")
                assertTrue(frame.centers.all { it.isFinite() }, "$label non-finite cluster state")
                assertTrue(frame.strayCents.all { it.isFinite() }, "$label non-finite departure")
                assertEquals(frame.energy.sum(), frame.totalEnergy, 1e-10, "$label diagnostic budget omits partials")
            }
        }
        val low = Shoal.probe(ShoalVoice.RIPPLE, midi = 24, seconds = .2f)
        val high = Shoal.probe(ShoalVoice.RIPPLE, midi = 84, seconds = .2f)
        assertTrue(high.diagnostics.activePartials < low.diagnostics.activePartials,
            "high notes retain the inaudible bank instead of respecting output bandwidth")
    }

    @Test
    fun `finite all-high energy decays monotonically after supply ends`() {
        for (voice in ShoalVoice.entries) {
            val p = Shoal.probe(voice, timbral.associateWith { 1f } + ("HOLD" to 0f))
            val tail = p.energyTrace.filter { it.seconds > p.supplyCutoffSeconds && it.supply == 0.0 }
            assertTrue(tail.size >= 3, "$voice offers no measured unpowered tail")
            assertTrue(tail.first().totalEnergy > 0.0, "$voice killed the bank when supply stopped")
            for ((before, after) in tail.zipWithNext()) {
                assertTrue(after.totalEnergy <= before.totalEnergy + 1e-12,
                    "$voice controller replenished unpowered energy at ${after.seconds}s")
            }
            assertTrue(tail.last().totalEnergy < tail.first().totalEnergy,
                "$voice all-high corner has no positive damping")
            assertEquals(0.0, p.energyTrace.last().totalEnergy,
                "$voice finite source release leaves moving energy beyond the render budget")
            val lastRaw = p.raw.copyOfRange((p.raw.size - (.02 * Dsp.RATE * Dsp.OVERSAMPLE).toInt()).coerceAtLeast(0), p.raw.size)
            assertTrue(rms(lastRaw) < rms(p.raw) * .01,
                "$voice raw source does not finish at least 40 dB below its average level before output fading")
        }
    }

    @Test
    fun `each macro changes its low and middle steps before final gain`() {
        for (voice in ShoalVoice.entries) for (macro in timbral) {
            val base = Shoal.defaults(voice) + ("HOLD" to 0f)
            val probes = listOf(0f, .25f, .5f).map { Shoal.probe(voice, base + (macro to it), seconds = 1.2f) }
            for ((index, pair) in probes.zipWithNext().withIndex()) {
                val (a, b) = pair
                val difference = normalizedDistance(a.samples, b.samples)
                assertTrue(difference > .001, "$voice $macro step $index is inactive after gain removal ($difference)")
                if (macro == "STRAY") {
                    assertTrue(a.energyTrace.zip(b.energyTrace).any { (left, right) -> !left.strayCents.contentEquals(right.strayCents) },
                        "$voice STRAY changes output without changing departure state")
                } else {
                    assertTrue(a.energyTrace.zip(b.energyTrace).any { (left, right) -> !left.energy.contentEquals(right.energy) },
                        "$voice $macro changes output without acting on the population")
                }
            }
        }
    }

    @Test
    fun `transport local cohesion and stray ablations expose distinct internal mechanisms`() {
        val macros = Shoal.defaults(ShoalVoice.RIPPLE) + mapOf("CURRENT" to .7f, "COHESION" to .85f, "STRAY" to .45f)
        val full = Shoal.probe(ShoalVoice.RIPPLE, macros, seconds = 1.5f)
        for (ablation in listOf(Shoal.Ablation.NO_TRANSPORT, Shoal.Ablation.NO_LOCAL_COHESION)) {
            val removed = Shoal.probe(ShoalVoice.RIPPLE, macros, seconds = 1.5f, ablation = ablation)
            assertTrue(meanDistributionDistance(full, removed) > 1e-4, "$ablation leaves normalized energy motion unchanged")
            assertTrue(normalizedDistance(full.samples, removed.samples) > .001, "$ablation is explained only by gain")
            if (ablation == Shoal.Ablation.NO_TRANSPORT) {
                assertEquals(full.energyTrace.size, removed.energyTrace.size)
                for ((a, b) in full.energyTrace.zip(removed.energyTrace)) {
                    assertEquals(a.seconds, b.seconds)
                    assertEquals(a.totalEnergy, b.totalEnergy, 1e-12, "motion ablation changed the energy envelope")
                    assertEquals(a.supply, b.supply, "motion ablation changed excitation")
                    assertContentEquals(a.centers, b.centers, "motion ablation changed center controls")
                    assertContentEquals(a.strayCents, b.strayCents, "motion ablation removed flow-driven STRAY")
                    assertTrue(distributionDistance(removed.energyTrace.first().energy, b.energy) < 1e-10,
                        "NO_TRANSPORT still moves the normalized population")
                }
                val meanFull = timeWeightedEnergy(full.energyTrace)
                val meanRemoved = timeWeightedEnergy(removed.energyTrace)
                val meanError = meanFull.indices.sumOf { abs(meanFull[it] - meanRemoved[it]) } / meanFull.sum()
                // The engine fits its complete control grid; this independent integration sees the
                // public ~30 Hz trace, so allow one percent for its coarser trapezoidal sampling.
                assertTrue(meanError < .01, "motion ablation changed the mean partial-energy profile ($meanError)")
            }
        }
        val harmonic = Shoal.probe(ShoalVoice.RIPPLE, macros, seconds = 1.5f, ablation = Shoal.Ablation.NO_STRAY)
        assertEquals(full.energyTrace.size, harmonic.energyTrace.size)
        for ((a, b) in full.energyTrace.zip(harmonic.energyTrace)) {
            assertContentEquals(a.energy, b.energy, "STRAY ablation also changed transport")
            assertTrue(b.strayCents.all { it == 0.0 }, "STRAY ablation left a frequency departure")
        }
        assertTrue(full.energyTrace.any { f -> f.strayCents.any { abs(it) > .1 } }, "departures never became active")
        assertTrue(normalizedDistance(full.samples, harmonic.samples) > .001, "STRAY is not audible in the rendered signal")
    }

    @Test
    fun `protected root remains pitched and short attacks avoid drum classes across registers and velocities`() {
        for (voice in ShoalVoice.entries) for (midi in listOf(24, 60, 84)) for (velocity in listOf(.35f, 1f)) {
            val p = Shoal.probe(voice, midi = midi, velocity = velocity, seconds = 1.25f)
            val snip = Snip(p.samples, 1, Dsp.RATE)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(snip, wanted, .12f, .65f)
            // Five cents is a musical tuning contract, rather than a fixture fitted to this implementation.
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 5.0, "$voice MIDI$midi misplaced its root")
            if (wanted >= 40f && wanted <= 1_200f) {
                val detected = assertNotNull(Pitch.detect(snip, .12f, .35f), "$voice MIDI$midi has no periodic pitch")
                assertTrue(detected.confidence >= .3f, "$voice MIDI$midi weak pitch confidence ${detected.confidence}")
                assertTrue(abs(FineTuning.cents(detected.hz.toDouble(), wanted.toDouble())) < 5.0,
                    "$voice MIDI$midi detector hears another pitch: ${detected.hz}")
            }
            // At 1.25 s the classifier cannot take its >1.5 s LOOP shortcut.
            val classification = Classifier.classify(snip)
            assertTrue(classification.drumClass !in drums, "$voice MIDI$midi velocity$velocity reads as ${classification.drumClass}")
            assertTrue(classification.drumClass != DrumClass.LOOP, "classifier probe only tested duration")
        }
    }

    @Test
    fun `captured moving hold closes phase state independent audio cycles and exported PCM`() {
        for (voice in ShoalVoice.entries) for (midi in listOf(24, Shoal.rootMidi(voice), 84).distinct()) {
            val p = Shoal.probe(voice, Shoal.defaults(voice) + mapOf("HOLD" to 1f, "CURRENT" to .7f, "STRAY" to .5f), midi)
            val previous = assertNotNull(p.previousCycle, "$voice MIDI$midi lacks independent preceding-cycle evidence")
            assertEquals(p.loopFrames, p.samples.size)
            assertEquals(p.samples.size, previous.size)
            assertTrue(relativeError(previous, p.samples) < Keys.MAX_SEAM_ERROR, "$voice complete held cycles differ")
            val seam = Keys.seamError(previous + p.samples, previous.size)
            assertEquals(seam, p.seamError, 1e-12, "$voice reports a seam from copied data")
            assertTrue(seam < Keys.MAX_SEAM_ERROR)
            // This is closure of the documented captured trajectory, not convergence of the free model.
            assertTrue(p.periodicCapture, "HOLD no longer labels its captured-model approximation")
            assertTrue(p.stateError.isFinite() && p.stateError < Keys.MAX_SEAM_ERROR, "$voice captured state does not recur")
            assertTrue(2 * PI * p.diagnostics.phaseClosureError < sqrt(Keys.MAX_SEAM_ERROR), "$voice oscillator phase does not recur")
            val first = p.energyTrace.first()
            val last = p.energyTrace.last()
            assertContentEquals(first.energy, last.energy, "$voice control wrap loses energy recurrence")
            assertContentEquals(first.centers, last.centers, "$voice control wrap loses center recurrence")
            assertContentEquals(first.strayCents, last.strayCents, "$voice control wrap loses frequency recurrence")
            val motion = p.energyTrace.zipWithNext().maxOf { (a, b) -> distributionDistance(a.energy, b.energy) }
            assertTrue(motion > 1e-4, "$voice froze its spectral population to obtain HOLD")
            assertTrue(abs(FineTuning.cents(p.effectiveHz, Keys.midiHz(midi).toDouble())) < 1.0,
                "$voice coarsely quantized the root to fit the loop")

            val bytes = ByteArrayOutputStream().also {
                WavWriter.write(it, Snip(previous + p.samples, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
            }
            val exported = WavReader.read(bytes.toByteArray()).samples
            assertTrue(Keys.seamError(exported, previous.size) < Keys.MAX_SEAM_ERROR, "$voice PCM export lost held continuity")
        }
    }

    @Test
    fun `zero velocity is silent for finite and held voices`() {
        for (voice in ShoalVoice.entries) for (hold in listOf(0f, 1f)) {
            val sound = Shoal.render(voice, mapOf("HOLD" to hold), velocity = 0f)
            assertTrue(sound.samples.isNotEmpty())
            assertTrue(sound.samples.all { it == 0f }, "$voice HOLD$hold sounds at zero velocity")
        }
    }

    private fun rms(a: FloatArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size.coerceAtLeast(1))

    private fun normalizedDistance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val ra = rms(a).coerceAtLeast(1e-15)
        val rb = rms(b).coerceAtLeast(1e-15)
        return sqrt((0 until n).sumOf { i -> val d = a[i] / ra - b[i] / rb; d * d } / n)
    }

    private fun distributionDistance(a: DoubleArray, b: DoubleArray): Double {
        val ea = a.sum().coerceAtLeast(1e-15)
        val eb = b.sum().coerceAtLeast(1e-15)
        return a.indices.sumOf { abs(a[it] / ea - b[it] / eb) } / 2
    }

    private fun meanDistributionDistance(a: Shoal.Probe, b: Shoal.Probe) =
        a.energyTrace.zip(b.energyTrace).map { (left, right) -> distributionDistance(left.energy, right.energy) }.average()

    private fun timeWeightedEnergy(trace: List<Shoal.EnergyFrame>): DoubleArray {
        val integral = DoubleArray(trace.first().energy.size)
        for ((a, b) in trace.zipWithNext()) {
            val dt = b.seconds - a.seconds
            for (i in integral.indices) integral[i] += .5 * (a.energy[i] + b.energy[i]) * dt
        }
        val duration = trace.last().seconds - trace.first().seconds
        return DoubleArray(integral.size) { integral[it] / duration }
    }

    private fun relativeError(a: FloatArray, b: FloatArray) =
        a.indices.sumOf { i -> val d = a[i].toDouble() - b[i]; d * d } / a.sumOf { it.toDouble() * it }.coerceAtLeast(1e-20)
}
