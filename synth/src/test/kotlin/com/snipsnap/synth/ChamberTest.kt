package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Causal and numerical checks for the first finite-strike listening milestone. */
class ChamberTest {
    private val quick = Chamber.ProbeOptions(seconds = .7f)

    @Test
    fun `raw and rendered sounds are repeatable independent of control map order`() {
        val controls = Chamber.defaults(ChamberVoice.AQUARIUM)
        val a = Chamber.probe(ChamberVoice.AQUARIUM, controls, seed = 17, options = quick)
        val b = Chamber.probe(ChamberVoice.AQUARIUM, controls.entries.reversed().associate { it.key to it.value },
            seed = 17, options = quick)
        assertContentEquals(a.rawMix, b.rawMix)
        assertContentEquals(a.samples, b.samples)
        for (i in 0..3) assertContentEquals(a.instrumentStems[i], b.instrumentStems[i])
        for (i in 0..9) assertContentEquals(a.surfaceStems[i], b.surfaceStems[i])
        assertEquals(a.geometry, b.geometry)
        assertEquals(a.arrivals, b.arrivals)
        assertEquals(a.diagnostics.energy, b.diagnostics.energy)
        val withoutStems = Chamber.probe(ChamberVoice.AQUARIUM, controls, seed = 17,
            options = quick.copy(recordStems = false))
        assertContentEquals(a.rawMix, withoutStems.rawMix, "diagnostic stem collection changed synthesis")
        assertContentEquals(a.samples, withoutStems.samples)
        assertFalse(a.rawMix.contentEquals(Chamber.probe(ChamberVoice.AQUARIUM, controls,
            seed = 18, options = quick).rawMix), "saved seed never reached the synthesis")
        val rendered = Chamber.render(ChamberVoice.HYBRID, seed = 17)
        assertContentEquals(rendered.samples, Chamber.render(ChamberVoice.HYBRID, seed = 17).samples)
    }

    @Test
    fun `zero velocity leaves every source boundary and traveling store at exact rest`() {
        val high = TIMBRAL.associateWith { 1f }
        for (voice in ChamberVoice.entries) {
            val p = Chamber.probe(voice, high, velocity = 0f, options = quick)
            for (stem in listOf(p.rawMix, p.samples, p.direct, p.early, p.surface, p.late) +
                p.instrumentStems.toList() + p.surfaceStems.toList()) assertTrue(stem.all { it == 0f })
            assertEquals(0.0, p.diagnostics.primaryWork)
            assertEquals(0.0, p.diagnostics.maxEnergy)
            assertEquals(0.0, p.diagnostics.finalEnergy)
            assertEquals(0.0, p.diagnostics.primaryWorkBudget)
            assertTrue(p.diagnostics.sharedStrikeTimes.isEmpty())
            assertTrue(p.diagnostics.energy.all { it.totalEnergy == 0.0 })
            assertTrue(Chamber.render(voice, high, velocity = 0f).samples.all { it == 0f })
        }
    }

    @Test
    fun `four local instruments initiate together while geometry supplies later arrivals`() {
        val p = Chamber.probe(ChamberVoice.HYBRID, options = quick)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), p.diagnostics.sharedStrikeTimes)
        assertEquals(4, p.instrumentStems.size)
        assertTrue(p.instrumentStems.all { rms(it) > 1e-6 }, "BALANCE lost an instrument")
        val direct = p.arrivals.filter { it.kind == "direct" }
        assertEquals(setOf(0, 1, 2, 3), direct.map { it.sourceIndex }.toSet())
        assertTrue(direct.all { it.timeSeconds > 0.0 })
        assertTrue(direct.map { it.timeSeconds }.distinct().size > 1,
            "source synchronization must not remove position-derived listener timing")
        for (instrument in 0..3) {
            val solo = Chamber.probe(ChamberVoice.HYBRID, options = quick.copy(roomEnabled = false,
                isolatedInstrument = instrument))
            assertTrue(rms(solo.instrumentStems[instrument]) > 1e-6)
            assertTrue(solo.instrumentStems.filterIndexed { i, _ -> i != instrument }
                .all { stem -> stem.all { it == 0f } })
        }
    }

    @Test
    fun `bar and membrane carry the requested root with identifying upper modes`() {
        for (midi in listOf(36, 60, 84)) for (strike in listOf(.15f, .85f)) {
            val p = Chamber.probe(ChamberVoice.HYBRID, mapOf("STRIKE" to strike), midi = midi,
                options = quick.copy(roomEnabled = false))
            val root = Keys.midiHz(midi)
            for (instrument in 0..1) {
                val stem = p.instrumentStems[instrument]
                val cents = FineTuning.cents(FineTuning.measuredHz(stem, Dsp.RATE, root, .025f, .4f), root.toDouble())
                assertTrue(abs(cents) < 15.0, "instrument $instrument MIDI $midi STRIKE $strike: root $cents cents")
                val spectrum = spectrum(stem)
                val rootShare = bandShare(spectrum, root.toDouble())
                val upper = root * if (instrument == 0) 1.505 else 3.96
                val upperShare = bandShare(spectrum, upper)
                assertTrue(rootShare > .20, "instrument $instrument MIDI $midi has a mathematically tuned but weak root: $rootShare")
                assertTrue(upperShare > 1e-5, "instrument $instrument MIDI $midi STRIKE $strike lost its identifying upper mode: $upperShare")
                println("CHAMBER native instrument=$instrument midi=$midi strike=$strike cents=$cents rootShare=$rootShare upperShare=$upperShare")
            }
        }
    }

    @Test
    fun `four source spectra remain distinct before room coloration`() {
        val p = Chamber.probe(ChamberVoice.HYBRID, mapOf("STRIKE" to .6f), options = quick.copy(roomEnabled = false))
        val spectra = p.instrumentStems.map(::spectrum)
        for (i in spectra.indices) for (j in 0 until i) {
            val similarity = cosineSimilarity(spectra[i], spectra[j])
            assertTrue(similarity < .98, "source $i and $j collapsed into the same normalized spectrum: $similarity")
            println("CHAMBER source spectra $i/$j cosine=$similarity")
        }
    }

    @Test
    fun `every timbral control changes audio at the first quarter step and across its range`() {
        // A numerical activity gate; the published comparisons still need the owner's listening verdict.
        for (voice in ChamberVoice.entries) for (macro in TIMBRAL) {
            val probes = listOf(0f, .25f, .5f, .75f, 1f).map { value ->
                Chamber.probe(voice, Chamber.defaults(voice) + (macro to value), options = quick.copy(recordStems = false))
            }
            val steps = probes.zipWithNext().map { (a, b) -> relativeDifference(a.rawMix, b.rawMix) }
            assertTrue(steps.all { it > 1e-4 }, "$voice $macro has an inactive quarter step: $steps")
            assertTrue(probes.all { it.rawMix.all(Float::isFinite) })
            println("CHAMBER macro $voice $macro relative quarter steps=$steps")
        }
    }

    @Test
    fun `return bypass changes local instrument decay with listener pickup gains fixed`() {
        for (voice in ChamberVoice.entries) {
            val controls = Chamber.defaults(voice) + mapOf("STRIKE" to .8f, "DIVIDE" to 1f)
            val full = Chamber.probe(voice, controls, options = quick)
            val bypass = Chamber.probe(voice, controls, options = quick.copy(returnsEnabled = false))
            assertEquals(full.geometry, bypass.geometry, "return bypass changed listener paths or room gains")
            assertTrue(rms(bypass.early) > 1e-6 && rms(bypass.surface) > 1e-7,
                "return bypass removed ordinary listener room pickup")
            val changes = (0..3).map { relativeDifference(full.instrumentStems[it], bypass.instrumentStems[it], .06) }
            assertTrue(changes.any { it > .001 }, "$voice returns changed no local resonator decay: $changes")
            assertTrue(relativeDifference(full.rawMix, bypass.rawMix, .06) > .001)
            println("CHAMBER local return effect $voice: $changes")
        }
    }

    @Test
    fun `regional boundaries and placement affect resonance beyond listener delay and gain`() {
        for (voice in ChamberVoice.entries) {
            val controls = Chamber.defaults(voice) + mapOf("DIVIDE" to 1f, "PLACEMENT" to .75f)
            val full = Chamber.probe(voice, controls, options = quick)
            val averaged = Chamber.probe(voice, controls, options = quick.copy(averageSurfaces = true))
            assertEquals(full.geometry, averaged.geometry)
            assertTrue(relativeDifference(full.surface, averaged.surface) > .01,
                "$voice per-region modes were indistinguishable from averaged materials")
            assertTrue(relativeDifference(full.early, averaged.early) > .001,
                "$voice first reflections ignored their actual boundary intersections")
            val near = Chamber.probe(voice, controls + ("PLACEMENT" to 0f), options = quick)
            val far = Chamber.probe(voice, controls + ("PLACEMENT" to 1f), options = quick)
            val localChanges = (0..3).map { relativeDifference(near.instrumentStems[it], far.instrumentStems[it], .06) }
            assertTrue(localChanges.any { it > .001 }, "$voice placement changed no local return interaction: $localChanges")
            val dryNear = Chamber.probe(voice, controls + ("PLACEMENT" to 0f), options = quick.copy(roomEnabled = false))
            val dryFar = Chamber.probe(voice, controls + ("PLACEMENT" to 1f), options = quick.copy(roomEnabled = false))
            for (i in 0..3) assertContentEquals(dryNear.instrumentStems[i], dryFar.instrumentStems[i],
                "$voice placement secretly changed the source instead of propagation")
        }
    }

    @Test
    fun `material family boundaries remain continuous in the actual audio`() {
        for (position in listOf(.25f, .5f, .75f)) {
            val controls = Chamber.defaults(ChamberVoice.HYBRID) + mapOf("DIVIDE" to 0f)
            val low = Chamber.probe(ChamberVoice.HYBRID, controls + ("MATERIAL" to position - .0001f),
                options = quick.copy(recordStems = false))
            val high = Chamber.probe(ChamberVoice.HYBRID, controls + ("MATERIAL" to position + .0001f),
                options = quick.copy(recordStems = false))
            val difference = relativeDifference(low.rawMix, high.rawMix)
            assertTrue(difference < .01, "MATERIAL jumped at family boundary $position: $difference")
            println("CHAMBER material continuity $position relative=$difference")
        }
    }

    @Test
    fun `submerged boundary modulation preserves dry attacks and never enters feedback`() {
        val controls = Chamber.defaults(ChamberVoice.AQUARIUM) + mapOf("DIVIDE" to 1f)
        val moving = Chamber.probe(ChamberVoice.AQUARIUM, controls, options = quick)
        val stationary = Chamber.probe(ChamberVoice.AQUARIUM, controls,
            options = quick.copy(submergedModulationEnabled = false))
        assertContentEquals(moving.direct, stationary.direct)
        assertContentEquals(moving.early, stationary.early)
        assertContentEquals(moving.late, stationary.late)
        for (i in 0..3) assertContentEquals(moving.instrumentStems[i], stationary.instrumentStems[i])
        assertEquals(moving.diagnostics.energy, stationary.diagnostics.energy,
            "listener-only fluid modulation changed the passive feedback network")
        val difference = relativeDifference(moving.surface, stationary.surface)
        assertTrue(difference > .001, "submerged boundary has no measurable wavering answer: $difference")
        println("CHAMBER submerged listener modulation relative=$difference")
    }

    @Test
    fun `unpowered complete coupled network decays at maximum velocity and reflective extremes`() {
        for (voice in ChamberVoice.entries) for (midi in listOf(36, 60, 84)) {
            val p = Chamber.probe(voice, TIMBRAL.associateWith { 1f }, midi = midi, velocity = 1f,
                options = Chamber.ProbeOptions(seconds = Chamber.MAX_SECONDS, recordStems = false))
            val d = p.diagnostics
            assertTrue(d.finiteStates && p.rawMix.all(Float::isFinite), "$voice MIDI $midi nonfinite internal or raw state")
            assertTrue(d.primaryWork.isFinite() && d.primaryWork > 0)
            assertTrue(d.primaryWork <= d.primaryWorkBudget * 1.0001 + 1e-9,
                "$voice MIDI $midi exceeded its finite primary source budget: ${d.primaryWork} > ${d.primaryWorkBudget}")
            assertTrue(d.primaryEndSeconds in 0.0.. .02)
            assertTrue(d.energyAtPrimaryEnd > 0.0)
            assertTrue(d.passiveCouplingNorm <= 1.0 && d.maxDelayGain < 1.0)
            assertTrue(d.travelingSamples > 0 && d.modalCount >= 24, "complete energy ledger omitted the room")
            assertTrue(p.rawPeak < 1f, "$voice MIDI $midi raw peak ${p.rawPeak} exhausted headroom")
            val unpowered = d.energy.filter { it.timeSeconds > .025 }
            assertTrue(unpowered.size > 10)
            for ((a, b) in unpowered.zipWithNext()) assertTrue(
                b.totalEnergy <= a.totalEnergy * 1.00001 + 1e-10,
                "$voice MIDI $midi complete stored energy rose at ${b.timeSeconds}: ${a.totalEnergy} -> ${b.totalEnergy}")
            assertTrue(d.maxUnforcedEnergyRise <= d.maxEnergy * 1e-5 + 1e-10,
                "$voice MIDI $midi unpowered network gained energy: ${d.maxUnforcedEnergyRise}")
            assertTrue(d.maxEnergy <= d.primaryWork * 1.0001 + 1e-9,
                "$voice MIDI $midi state exceeded supplied primary work: ${d.maxEnergy} > ${d.primaryWork}")
            assertTrue(d.finalEnergy < d.maxEnergy * 1e-3,
                "$voice MIDI $midi did not decay in six seconds: ${d.finalEnergy} / ${d.maxEnergy}")
            val tailRms = rms(p.rawMix.copyOfRange(p.rawMix.size - Dsp.RATE / 4, p.rawMix.size))
            val initialRms = rms(p.rawMix.copyOfRange(0, Dsp.RATE / 4))
            println("CHAMBER passive $voice MIDI $midi rawPeak=${p.rawPeak} final/max=${d.finalEnergy / d.maxEnergy} unforcedRise=${d.maxUnforcedEnergyRise} tail/initialRms=${tailRms / initialRms}")
        }
    }

    @Test
    fun `normal ensemble body remains pitched and outside percussion classification`() {
        for (voice in ChamberVoice.entries) for (midi in listOf(36, 60, 84)) {
            val p = Chamber.probe(voice, midi = midi, options = quick.copy(normalize = true, recordStems = false))
            val snip = Snip(p.samples, 1, Dsp.RATE)
            val detected = assertNotNull(Pitch.detect(snip, .08f, .4f), "$voice MIDI $midi lost the ensemble pitch")
            val cents = FineTuning.cents(detected.hz.toDouble(), Keys.midiHz(midi).toDouble())
            assertTrue(abs(cents) < 50.0, "$voice MIDI $midi full ensemble root is $cents cents off (${detected.hz} Hz)")
            val classified = Classifier.classify(snip).drumClass
            assertTrue(classified !in DRUMS, "$voice MIDI $midi sounds like percussion to the measured classifier: $classified")
            println("CHAMBER ensemble $voice MIDI $midi root=$cents cents confidence=${detected.confidence} class=$classified")
        }
    }

    @Test
    fun `bright high register is measured against an eight times rate reference`() {
        for (voice in ChamberVoice.entries) {
            val controls = TIMBRAL.associateWith { 1f }
            val standard = Chamber.probe(voice, controls, midi = 84, velocity = 1f,
                options = quick.copy(recordStems = false))
            val reference = Chamber.probe(voice, controls, midi = 84, velocity = 1f,
                options = quick.copy(recordStems = false, oversample = 8))
            assertTrue(reference.diagnostics.finiteStates && reference.rawMix.all(Float::isFinite))
            val standardSpectrum = spectrum(standard.rawMix)
            val referenceSpectrum = spectrum(reference.rawMix)
            val cosine = cosineSimilarity(standardSpectrum, referenceSpectrum)
            val waveformError = relativeDifference(standard.rawMix, reference.rawMix)
            val rmsRatio = rms(standard.rawMix) / rms(reference.rawMix)
            fun highShare(power: DoubleArray) = power.indices.filter {
                it * Dsp.RATE.toDouble() / (power.size * 2) > 16_000
            }.sumOf { power[it] } / power.sum()
            val highDifference = highShare(standardSpectrum) - highShare(referenceSpectrum)
            println("CHAMBER 4x/8x $voice MIDI84 waveformError=$waveformError magnitudeCosine=$cosine rmsRatio=$rmsRatio highBandDifference=$highDifference")
            // The initial bench measured magnitude cosine >= .9718 and, after
            // pressure-unit calibration, RMS ratios >= .936. These regression
            // margins allow integer-delay quantization and room interference.
            // They measure full-room rate consistency, not isolated alias power.
            assertTrue(cosine.isFinite() && waveformError.isFinite() && rmsRatio.isFinite() && highDifference.isFinite())
            assertTrue(rms(reference.rawMix) > 1e-6, "eight-rate reference was silent")
            assertTrue(rmsRatio in .85..1.15, "$voice four/eight-rate pressure units diverged: $rmsRatio")
            assertTrue(cosine > .95, "$voice four/eight-rate spectral magnitudes diverged: $cosine")
            assertTrue(highDifference < 1e-5, "$voice excess four-rate high-band power share: $highDifference")
        }
    }

    @Test
    fun `finite prototype rejects hold rather than silently exporting an invalid loop`() {
        for (voice in ChamberVoice.entries) for (hold in listOf(.001f, .5f, 1f)) {
            assertFailsWith<IllegalArgumentException> { Chamber.probe(voice, mapOf("HOLD" to hold), options = quick) }
            assertFailsWith<IllegalArgumentException> { Chamber.render(voice, mapOf("HOLD" to hold)) }
        }
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun relativeDifference(a: FloatArray, b: FloatArray, from: Double = 0.0): Double {
        var difference = 0.0
        var energy = 0.0
        for (i in (from * Dsp.RATE).roundToInt() until minOf(a.size, b.size)) {
            val x = a[i].toDouble(); val y = b[i].toDouble()
            difference += (x - y) * (x - y)
            energy += max(x * x, y * y)
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    private fun spectrum(samples: FloatArray): DoubleArray {
        val from = (.025 * Dsp.RATE).roundToInt()
        val count = minOf((.4 * Dsp.RATE).roundToInt(), samples.size - from)
        val real = FloatArray(32768)
        val imaginary = FloatArray(real.size)
        for (i in 0 until count) real[i] = (samples[from + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(real, imaginary)
        return DoubleArray(real.size / 2) { real[it].toDouble() * real[it] + imaginary[it].toDouble() * imaginary[it] }
    }

    private fun bandShare(spectrum: DoubleArray, hz: Double): Double {
        val halfWidth = max(8.0, hz * .015)
        return spectrum.indices.filter { abs(it * Dsp.RATE.toDouble() / (spectrum.size * 2) - hz) <= halfWidth }
            .sumOf { spectrum[it] } / spectrum.sum().coerceAtLeast(1e-30)
    }

    /** Cosine of spectral magnitudes, whose squared norm equals measured audio energy. */
    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double =
        a.indices.sumOf { sqrt(a[it] * b[it]) } / sqrt(a.sum() * b.sum()).coerceAtLeast(1e-30)

    private companion object {
        val TIMBRAL = listOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT")
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
