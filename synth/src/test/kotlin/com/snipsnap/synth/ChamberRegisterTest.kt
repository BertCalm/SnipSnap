package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Register continuity and physical-network guards for the explicit fifth audition. */
class ChamberRegisterTest {
    private val controls = listOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT")

    @Test
    fun `stock C3 and C4 defaults and all single control endpoints remain model four exact`() {
        val settings = listOf(emptyMap<String, Float>()) + controls.flatMap { name -> listOf(0f, 1f).map { mapOf(name to it) } }
        val options = Chamber.ProbeOptions(seconds = .35f, recordStems = false, normalize = true)
        for (voice in ChamberVoice.entries) for (midi in listOf(48, 60)) for (setting in settings) {
            val before = Chamber.probe(voice, setting, midi = midi, model = Chamber.WIDE_RANGE_MODEL, options = options)
            val after = Chamber.probe(voice, setting, midi = midi, model = Chamber.REGISTER_MODEL, options = options)
            val label = "$voice MIDI$midi $setting"
            assertContentEquals(before.rawMix, after.rawMix, "$label raw Float32 changed")
            assertContentEquals(before.samples, after.samples, "$label production Float32 changed")
            assertEquals(before.geometry, after.geometry, "$label geometry changed")
            assertEquals(before.diagnostics.energy, after.diagnostics.energy, "$label network states changed")
        }
        assertEquals(3, Chamber.MODEL_VERSION)
        assertTrue(ChamberPresets.all().all { it.model == 3 })
    }

    @Test
    fun `high material response retains a substantial late body and distinct material range at C6`() {
        val options = Chamber.ProbeOptions(seconds = 2.5f, recordStems = true)
        for (voice in ChamberVoice.entries) {
            val lowerReference = Chamber.probe(voice, mapOf("MATERIAL" to 1f), midi = 60,
                model = Chamber.WIDE_RANGE_MODEL, options = options)
            val old = Chamber.probe(voice, mapOf("MATERIAL" to 1f), midi = 84,
                model = Chamber.WIDE_RANGE_MODEL, options = options)
            val current = Chamber.probe(voice, mapOf("MATERIAL" to 1f), midi = 84,
                model = Chamber.REGISTER_MODEL, options = options)
            val dryMaterial = Chamber.probe(voice, mapOf("MATERIAL" to 0f), midi = 84,
                model = Chamber.REGISTER_MODEL, options = options)
            fun answerRatio(probe: Chamber.Probe): Double = rms(window(probe.rawMix, .75, 1.5)) /
                rms(window(probe.rawMix, 0.0, .2)).coerceAtLeast(1e-30)
            val before = answerRatio(old)
            val after = answerRatio(current)
            // This ratio is invariant to master gain. A longer, quieter exported
            // recording cannot pass by attaching silence or raising the attack.
            println("CHAMBER register $voice C6 material-high answer/attack current=$before candidate=$after")
            assertTrue(after > before * 1.25, "$voice C6 material answer did not grow meaningfully relative to its attack")
            assertTrue(after >= answerRatio(lowerReference) * .5,
                "$voice C6 material answer retained less than half its C4 relative body")
            assertTrue(energyTime90(current.rawMix) >= energyTime90(lowerReference.rawMix) * .6,
                "$voice C6 decay retained less than 60% of its C4 cumulative-energy time")
            val lowAnswer = window(dryMaterial.rawMix, .55, 2.0)
            val highAnswer = window(current.rawMix, .55, 2.0)
            val staticSpectrum = TangleMeasure.spectralDistance(lowAnswer, highAnswer)
            val materialDistance = jointTimeFrequencyDistance(lowAnswer, highAnswer)
            println("CHAMBER register $voice C6 late material static spectral distance=$staticSpectrum full-window joint distance=$materialDistance")
            assertTrue(materialDistance > .05, "$voice C6 MATERIAL endpoints lost their full-tail temporal/spectral contrast")
            assertEquals(old.geometry, current.geometry, "$voice adaptation moved the physical room")
            assertEquals(old.diagnostics.modalCount + 10, current.diagnostics.modalCount,
                "$voice adaptation must add exactly one sympathetic root per boundary")
            assertEquals(old.diagnostics.travelingSamples, current.diagnostics.travelingSamples, "$voice adaptation changed propagation delays")
            assertPassive(current, "$voice high material")
            assertPassive(dryMaterial, "$voice low material")
        }
    }

    @Test
    fun `late material measure detects decay and colour while rejecting no op and gain alone`() {
        val frames = (1.45 * Dsp.RATE).toInt()
        fun ring(hz: Double, t60: Double) = FloatArray(frames) { frame ->
            (sin(2 * PI * hz * frame / Dsp.RATE) * exp(-ln(1000.0) * frame / (t60 * Dsp.RATE))).toFloat()
        }
        val root = Keys.midiHz(84).toDouble()
        val long = ring(root, 2.5)
        val short = ring(root, .35)
        val octave = ring(root * 2, 2.5)
        val gainOnly = FloatArray(frames) { long[it] * .37f }
        val decayDistance = jointTimeFrequencyDistance(short, long)
        val colourDistance = jointTimeFrequencyDistance(long, octave)
        val gainDistance = jointTimeFrequencyDistance(long, gainOnly)
        println("CHAMBER material metric counterexample static=${TangleMeasure.spectralDistance(short, long)} decay=$decayDistance colour=$colourDistance gain=$gainDistance")
        assertEquals(0.0, jointTimeFrequencyDistance(long, long), "unchanged audio must fail the material-change gate")
        assertTrue(gainDistance < 1e-5, "gain alone must fail the material-change gate: $gainDistance")
        assertTrue(decayDistance > .05, "same-pitch decay changes must remain a material gesture")
        assertTrue(colourDistance > .05, "frequency colour changes must remain a material gesture")
    }

    /**
     * Total variation of one normalized time/frequency energy distribution.
     * Every sample in the supplied tail participates through overlapping Hann
     * windows; there is no hidden .15-second offset. Normalizing once for the
     * complete grid removes master gain while retaining decay information.
     * Normalizing each frame separately would erase exactly that information.
     */
    private fun jointTimeFrequencyDistance(a: FloatArray, b: FloatArray): Double {
        require(a.isNotEmpty() && a.size == b.size)
        val size = 2048
        val hop = 512
        val bands = 12
        val frames = (a.size + hop - 1) / hop
        fun distribution(samples: FloatArray): DoubleArray {
            val energy = DoubleArray(frames * bands)
            for (frame in 0 until frames) {
                val start = frame * hop - size / 2
                val block = FloatArray(size) { index -> samples.getOrNull(start + index) ?: 0f }
                val magnitude = Fft.magnitudeSpectrum(block, size)
                for (bin in 1 until magnitude.size) {
                    val hz = bin.toDouble() * Dsp.RATE / size
                    val band = (ln(hz.coerceAtLeast(30.0) / 30.0) / ln(2.0) * 1.5)
                        .toInt().coerceIn(0, bands - 1)
                    energy[frame * bands + band] += magnitude[bin].toDouble() * magnitude[bin]
                }
            }
            val total = energy.sum().coerceAtLeast(1e-30)
            for (index in energy.indices) energy[index] /= total
            return energy
        }
        val first = distribution(a)
        val second = distribution(b)
        return first.indices.sumOf { abs(first[it] - second[it]) } * .5
    }

    @Test
    fun `intermediate high metal strikes retain their actual fundamentals above octave down`() {
        val options = Chamber.ProbeOptions(seconds = .85f, roomEnabled = false, recordStems = true)
        for (voice in ChamberVoice.entries) for (strike in listOf(.65f, .75f, .85f, 1f)) {
            val probe = Chamber.probe(voice, mapOf("STRIKE" to strike), midi = 84,
                model = Chamber.REGISTER_MODEL, options = options)
            for (source in 2..3) {
                val actualRoot = Keys.midiHz(84).toDouble() * Chamber.configFor(voice).tuningRatios[source]
                val stem = probe.instrumentStems[source]
                val share = TangleMeasure.toneShare(stem, actualRoot)
                val octave = TangleMeasure.toneShare(stem, actualRoot / 2)
                val measured = FineTuning.measuredHz(stem, Dsp.RATE, actualRoot.toFloat(), .03f, .40f)
                val cents = FineTuning.cents(measured, actualRoot)
                println("CHAMBER register $voice C6 STRIKE$strike source$source rootShare=$share octaveDown=$octave cents=$cents")
                assertTrue(share > .10 && share > octave * 4,
                    "$voice STRIKE$strike source$source lost its actual root: $share vs octave-down $octave")
                assertTrue(abs(cents) < 20.0, "$voice STRIKE$strike source$source root shifted by $cents cents")
            }
            assertPassive(probe, "$voice STRIKE$strike")
        }
    }

    @Test
    fun `upper bar body remains excited at C6 rather than only preserving the fundamental`() {
        val options = Chamber.ProbeOptions(seconds = .7f, roomEnabled = false, recordStems = true)
        for (voice in ChamberVoice.entries) {
            val reference = Chamber.probe(voice, midi = 60, model = Chamber.WIDE_RANGE_MODEL, options = options)
            val old = Chamber.probe(voice, midi = 84, model = Chamber.WIDE_RANGE_MODEL, options = options)
            val revised = Chamber.probe(voice, midi = 84, model = Chamber.REGISTER_MODEL, options = options)
            // The source's identifying first body partial drives the glass
            // boundaries. Root-only checks miss a contact notch at this mode.
            val referenceBody = TangleMeasure.toneShare(reference.instrumentStems[1], Keys.midiHz(60).toDouble() * 3.96)
            val oldBody = TangleMeasure.toneShare(old.instrumentStems[1], Keys.midiHz(84).toDouble() * 3.96)
            val revisedBody = TangleMeasure.toneShare(revised.instrumentStems[1], Keys.midiHz(84).toDouble() * 3.96)
            println("CHAMBER register $voice bar body share C4=$referenceBody C6current=$oldBody C6revised=$revisedBody")
            assertTrue(referenceBody > .001, "$voice C4 reference has no meaningful bar body")
            assertTrue(revisedBody > referenceBody * .5, "$voice C6 bar body lost over half its C4 energy participation")
            assertTrue(revisedBody > oldBody * 4, "$voice C6 bar body did not recover from its old contact notch")
            assertPassive(revised, "$voice C6 bar body")
        }
    }

    @Test
    fun `register model complete recipes replay with the exact seed and reordered controls`() {
        val patch = ChamberPatch("Register replay", ChamberVoice.AQUARIUM,
            Chamber.defaults(ChamberVoice.AQUARIUM) + mapOf("STRIKE" to .75f, "MATERIAL" to 1f),
            midi = 84, velocity = .75f, seed = Long.MIN_VALUE + 37,
            configuration = ChamberConfig.aquarium().copy(placementMode = ChamberPlacementMode.EXPLICIT),
            model = Chamber.REGISTER_MODEL)
        val replay = ChamberPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, replay)
        assertEquals(Chamber.REGISTER_MODEL, (patch.withMacros(patch.macros + ("DIVIDE" to 0f)) as ChamberPatch).model)
        val options = Chamber.ProbeOptions(seconds = .7f, recordStems = false)
        val first = Chamber.probe(patch.voice, patch.macros, patch.midi, patch.velocity,
            patch.configuration, patch.seed, patch.model, options)
        val second = Chamber.probe(replay.voice, replay.macros.entries.reversed().associate { it.key to it.value },
            replay.midi, replay.velocity, replay.configuration, replay.seed, replay.model, options)
        assertContentEquals(first.rawMix, second.rawMix)
        assertContentEquals(first.samples, second.samples)
        assertEquals(first.diagnostics.energy, second.diagnostics.energy)
        assertPassive(first, "complete recipe")
    }

    @Test
    fun `near room and source focus extremes keep full length raw headroom at maximum velocity`() {
        val options = Chamber.ProbeOptions(seconds = 4.5f, recordStems = false)
        for (voice in ChamberVoice.entries) for (balance in listOf(0f, 1f)) {
            val macros = mapOf("STRIKE" to 1f, "BALANCE" to balance, "MATERIAL" to 1f,
                "DIVIDE" to 0f, "PLACEMENT" to 0f)
            val probe = Chamber.probe(voice, macros, midi = 84, velocity = 1f,
                model = Chamber.REGISTER_MODEL, options = options)
            val label = "$voice C6 near room BALANCE$balance velocity1"
            println("CHAMBER register headroom $label rawPeak=${probe.rawPeak} primaryWork=${probe.diagnostics.primaryWork}")
            assertTrue(probe.rawMix.all(Float::isFinite), "$label non-finite raw recording")
            assertPassive(probe, label)
        }
    }

    @Test
    fun `bright high register and maximum valid source tuning retain the strict eight rate reference`() {
        val options = Chamber.ProbeOptions(seconds = .7f, recordDiagnostics = false, recordStems = false)
        val bright = controls.associateWith { 1f }
        for (voice in ChamberVoice.entries) {
            val stock = Chamber.configFor(voice)
            val maximum = stock.copy(tuningRatios = ChamberTuningRatios(8f, 8f, 8f, 8f))
            for ((name, configuration, room) in listOf(
                Triple("full room", stock, true), Triple("direct", stock, false),
                Triple("maximum valid tuning direct", maximum, false))) {
                fun render(rate: Int) = Chamber.probe(voice, bright, midi = 84, velocity = 1f,
                    configuration = configuration, model = Chamber.REGISTER_MODEL,
                    options = options.copy(oversample = rate, roomEnabled = room))
                val four = render(4)
                val eight = render(8)
                val a = referenceSpectrum(four.rawMix)
                val b = referenceSpectrum(eight.rawMix)
                val cosine = a.indices.sumOf { sqrt(a[it] * b[it]) } /
                    sqrt(a.sum() * b.sum()).coerceAtLeast(1e-30)
                val ratio = rms(four.rawMix) / rms(eight.rawMix).coerceAtLeast(1e-30)
                fun highShare(power: DoubleArray) = power.indices.filter {
                    it * Dsp.RATE.toDouble() / (power.size * 2) > 16_000
                }.sumOf { power[it] } / power.sum().coerceAtLeast(1e-30)
                val excess = highShare(a) - highShare(b)
                println("CHAMBER register 4x/8x $voice $name cosine=$cosine rmsRatio=$ratio highBandExcess=$excess")
                assertTrue(four.diagnostics.finiteStates && eight.diagnostics.finiteStates)
                assertTrue(four.rawMix.all(Float::isFinite) && eight.rawMix.all(Float::isFinite))
                assertTrue(rms(eight.rawMix) > 1e-6, "$voice $name reference was silent")
                assertTrue(ratio in .85..1.15, "$voice $name pressure units diverged: $ratio")
                assertTrue(cosine > .95, "$voice $name spectral magnitudes diverged: $cosine")
                assertTrue(excess < 1e-5, "$voice $name excess four-rate high-band share: $excess")
            }
        }
    }

    private fun rms(samples: FloatArray): Double = TangleMeasure.rms(samples)
    private fun energyTime90(samples: FloatArray): Double {
        val total = samples.sumOf { it.toDouble() * it }
        assertTrue(total > 1e-16, "cumulative-energy reference was silent")
        var energy = 0.0
        for (i in samples.indices) {
            energy += samples[i].toDouble() * samples[i]
            if (energy >= total * .9) return i.toDouble() / Dsp.RATE
        }
        error("cumulative energy never reached its own 90% target")
    }
    private fun window(samples: FloatArray, start: Double, end: Double): FloatArray = samples.copyOfRange(
        (start * Dsp.RATE).toInt().coerceIn(0, samples.size), (end * Dsp.RATE).toInt().coerceIn(0, samples.size))

    /** Same Hann window, FFT size and 16 kHz boundary as the earlier strict guard. */
    private fun referenceSpectrum(samples: FloatArray): DoubleArray {
        val from = (.025 * Dsp.RATE).roundToInt()
        val count = minOf((.4 * Dsp.RATE).roundToInt(), samples.size - from)
        val real = FloatArray(32768)
        val imaginary = FloatArray(real.size)
        for (i in 0 until count)
            real[i] = (samples[from + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(real, imaginary)
        return DoubleArray(real.size / 2) { real[it].toDouble() * real[it] + imaginary[it].toDouble() * imaginary[it] }
    }

    private fun assertPassive(probe: Chamber.Probe, label: String) {
        val d = probe.diagnostics
        assertTrue(d.finiteStates, "$label non-finite network")
        assertTrue(probe.rawPeak < 1f, "$label exhausted raw PCM headroom: ${probe.rawPeak}")
        assertTrue(d.maxEnergy <= d.primaryWork * 1.0001 + 1e-10, "$label network exceeds primary work")
        assertTrue(d.primaryWork <= d.primaryWorkBudget * 1.0001 + 1e-10, "$label strike exceeds declared work budget")
        assertTrue(d.maxUnforcedEnergyRise <= d.maxEnergy * 1e-4 + 1e-10, "$label unforced energy grows")
        assertTrue(d.maxDelayGain <= 1.0 && d.passiveCouplingNorm <= 1.0000001, "$label non-passive feedback")
    }
}
