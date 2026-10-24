package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verify actual exchanged waves separately from the nominal scalar preparation traces. */
class FilamentExchangeTest {
    private val controls = mapOf("INJECT" to .65f, "BEND" to .6f, "LENGTH" to .65f,
        "BUNDLE" to .7f, "COLLECT" to .5f, "HOLD" to 0f)
    private val config = FilamentConfig(seed = 17, profile = FilamentProfile.COIL)

    private fun energy(samples: FloatArray): Double = samples.sumOf { it.toDouble() * it } / Dsp.RATE

    private fun actualBudget(p: Filament.Probe, label: String): Filament.ExchangeDiagnostics {
        val d = assertNotNull(p.diagnostics.exchange, "$label omitted actual waveform accounting")
        val energies = listOf(d.sourceEnergy, d.launchedEnergy, d.retainedEnergy, d.escapedEnergy,
            d.absorbedEnergy, d.uncollectedEnergy, d.junctionInputEnergy, d.junctionOutputEnergy)
        assertTrue(energies.all { it.isFinite() && it >= -1e-10 }, "$label nonfinite or negative energy: $energies")
        assertEquals(d.sourceEnergy, d.launchedEnergy, 1e-9, "$label launch count changed injected energy")
        assertTrue(d.retainedEnergy + d.escapedEnergy + d.absorbedEnergy <= d.launchedEnergy + 1e-9,
            "$label actual terminal/sink energy exceeded the actual launch budget")
        assertEquals(d.junctionInputEnergy, d.junctionOutputEnergy, 1e-9, "$label integrated junction created energy")
        assertTrue(d.maxJunctionError.isFinite() && d.maxJunctionError < 1e-12,
            "$label an individual junction changed instantaneous power: ${d.maxJunctionError}")
        assertTrue(d.maxSplitError.isFinite() && d.maxSplitError < 1e-12,
            "$label an actual downstream R/T/A split changed instantaneous power: ${d.maxSplitError}")
        assertTrue(d.collectorThroughNorm in 0.0..1.000000001 && d.collectorSpillNorm in 0.0..1.000000001,
            "$label an unbounded collector hid coherent summation")
        assertTrue(d.routeEnergyChanges.all { it.isFinite() }, "$label invalid route transfer")
        assertTrue(abs(d.routeEnergyChanges.sum()) < 1e-9, "$label route transfers do not cancel")
        // Uncollected energy includes filter loss and still-stored energy. This equality is a
        // bookkeeping check; the independently accumulated terminal and junction bounds above
        // are the checks against energy creation.
        assertEquals(d.launchedEnergy, d.retainedEnergy + d.escapedEnergy + d.absorbedEnergy + d.uncollectedEnergy,
            1e-9, "$label reported ledger does not close")
        val expectedScope = if (p.diagnostics.loop) "returned held cycle" else "complete finite propagation"
        assertEquals(expectedScope, d.energyScope, "$label ledger scope does not describe the returned evidence")
        assertEquals(p.samples.size.toLong() * d.oversampling, d.energyFrameCount,
            "$label ledger covers different frames from the exported audio")
        // The exported branch stems have passed the project decimator/DC stage. A 0.1%
        // numerical allowance covers resampler passband ripple without borrowing another cycle.
        assertTrue(energy(p.through) <= d.retainedEnergy * 1.001 + 1e-9,
            "$label collected THROUGH exceeds its actual channel budget")
        assertTrue(energy(p.escaped) <= d.escapedEnergy * 1.001 + 1e-9,
            "$label collected escape exceeds its actual channel budget")
        return d
    }

    @Test
    fun `actual causal junctions preserve power while downstream splits spend only their arriving waves`() {
        for (voice in FilamentVoice.entries) {
            val p = Filament.inspect(voice, controls, midi = 60, config = config, model = 3)
            val d = actualBudget(p, "$voice finite")
            assertTrue(d.enabled && !d.frozen && d.oversampling == 4)
            assertTrue(d.sourceEnergy > 0.0 && d.junctionInputEnergy > 0.0)
            assertTrue(d.routeEnergyChanges.sumOf { abs(it) } > 1e-9, "$voice exchanged no actual route energy")
            assertTrue(d.maxMemory in 0.0..1.0 && d.meanMemory in 0.0..d.maxMemory,
                "$voice memory escaped its bounded range")
            assertTrue(d.memorySpan > 1e-5 && d.maxAngleRadians > .001,
                "$voice exchange remained a static metadata change")
        }
    }

    @Test
    fun `exchange and its memory alter an ordinary pulse without changing its revised excitation`() {
        for (hold in listOf(0f, 1f)) {
            val macros = controls + ("HOLD" to hold)
            val original = Filament.inspect(FilamentVoice.PULSE, macros, midi = 48, velocity = .7f,
                config = config, model = 2)
            val active = Filament.inspect(FilamentVoice.PULSE, macros, midi = 48, velocity = .7f,
                config = config, model = 3)
            assertContentEquals(original.source, active.source, "HOLD$hold changed the model-2 excitation")
            val activeBudget = actualBudget(active, "PULSE HOLD$hold")
            assertTrue(activeBudget.memorySpan > 1e-5, "HOLD$hold live memory never moved")
            for ((label, options) in listOf("disabled" to Filament.Options(disableExchange = true),
                "frozen" to Filament.Options(freezeMemory = true))) {
                val ablated = Filament.inspect(FilamentVoice.PULSE, macros, midi = 48, velocity = .7f,
                    config = config, options = options, model = 3)
                assertContentEquals(active.source, ablated.source, "HOLD$hold $label changed comparison effort")
                val d = actualBudget(ablated, "HOLD$hold $label")
                assertEquals(label != "disabled", d.enabled)
                assertEquals(label == "frozen", d.frozen)
                if (label == "frozen") assertTrue(d.memorySpan < 1e-12, "frozen diagnostic retained evolving memory")
                val distance = TangleMeasure.distance(active.raw, ablated.raw)
                val spectrum = TangleMeasure.spectralDistance(active.raw, ablated.raw)
                println("FILAMENT R3 PULSE HOLD$hold $label waveform=$distance spectrum=$spectrum")
                assertTrue(distance > .01, "HOLD$hold $label changes only gain or hidden state: $distance")
                assertTrue(spectrum > .002, "HOLD$hold $label has no material spectral effect: $spectrum")
            }
        }
    }

    @Test
    fun `held exchange accounts for the returned cycle and repeats full live memory and traveling history`() {
        val p = Filament.inspect(FilamentVoice.PULSE, controls + ("HOLD" to 1f), midi = 60,
            config = config, model = 3)
        val d = actualBudget(p, "held causal exchange")
        val previous = assertNotNull(p.previous)
        assertTrue(d.enabled && !d.frozen && d.memorySpan > 1e-5)
        assertTrue(p.diagnostics.loopConverged)
        val stateError = assertNotNull(p.diagnostics.stateError)
        assertTrue(stateError.isFinite() && stateError < 1e-3, "live memory/rings failed recurrence: $stateError")
        val fullCycleError = assertNotNull(p.diagnostics.fullCycleError)
        assertTrue(fullCycleError.isFinite() && fullCycleError < 1e-5)
        assertTrue(Keys.seamError(previous + p.samples, previous.size) < Keys.MAX_SEAM_ERROR)
        val reference = previous.sumOf { it.toDouble() * it }
        val difference = previous.indices.sumOf { val delta = previous[it].toDouble() - p.samples[it]; delta * delta }
        assertTrue(reference > 0.0 && difference / reference < 1e-5,
            "returned cycle did not recur in its actual independently rendered predecessor")
    }

    @Test
    fun `high register exchange approaches the eight times diagnostic rendering without losing its root`() {
        val macros = controls + mapOf("INJECT" to 1f, "BEND" to .8f, "BUNDLE" to 1f)
        val normal = Filament.inspect(FilamentVoice.PULSE, macros, midi = 84, config = config, model = 3)
        val reference = Filament.inspect(FilamentVoice.PULSE, macros, midi = 84, config = config,
            options = Filament.Options(referenceOversampling = 8), model = 3)
        assertEquals(normal.samples.size, reference.samples.size)
        assertEquals(4, actualBudget(normal, "4x high-note exchange").oversampling)
        assertEquals(8, actualBudget(reference, "8x high-note exchange").oversampling)
        val waveform = TangleMeasure.distance(normal.raw, reference.raw)
        val spectrum = TangleMeasure.spectralDistance(normal.raw, reference.raw)
        println("FILAMENT R3 high-register 4x/8x waveform=$waveform spectrum=$spectrum")
        assertTrue(waveform < .1 && spectrum < .05,
            "4x exchange diverges from its higher-rate diagnostic: waveform=$waveform spectrum=$spectrum")
        for (p in listOf(normal, reference)) {
            val wanted = Keys.midiHz(84)
            val measured = FineTuning.measuredHz(Snip(p.samples, 1, Dsp.RATE), wanted, .12f, .5f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 25.0,
                "high-rate exchange comparison lost the requested root")
        }
    }
}
