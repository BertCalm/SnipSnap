package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Model 4 is a candidate with wider behaviour, while old recipes stay exact. */
class ChamberWideRangeTest {
    private val controls = listOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT")
    private val quick = Chamber.ProbeOptions(seconds = .85f, recordStems = false)

    @Test
    fun `explicit candidate round trips without changing the accepted default or factory models`() {
        assertEquals(3, Chamber.MODEL_VERSION)
        assertTrue(ChamberPresets.all().all { it.model == 3 })
        val patch = ChamberPatch("Range candidate", ChamberVoice.HYBRID,
            Chamber.defaults(ChamberVoice.HYBRID) + ("MATERIAL" to 1f),
            midi = 48, seed = Long.MIN_VALUE + 17, model = Chamber.WIDE_RANGE_MODEL)
        assertEquals(patch, ChamberPatch.fromJsonText(patch.toJsonText()))
        assertEquals(Chamber.WIDE_RANGE_MODEL, (patch.withMacros(patch.macros + ("STRIKE" to 0f)) as ChamberPatch).model)
        assertFailsWith<IllegalArgumentException> { patch.copy(model = Chamber.MAX_MODEL_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("HOLD" to .01f)) }
    }

    @Test
    fun `both accepted centres retain every sample stem and energy state in the candidate`() {
        for (voice in ChamberVoice.entries) {
            val options = quick.copy(recordStems = true, normalize = true)
            val accepted = Chamber.probe(voice, model = 3, options = options)
            val candidate = Chamber.probe(voice, model = Chamber.WIDE_RANGE_MODEL, options = options)
            assertContentEquals(accepted.rawMix, candidate.rawMix, "$voice raw centre changed")
            assertContentEquals(accepted.samples, candidate.samples, "$voice production centre changed")
            assertContentEquals(accepted.direct, candidate.direct)
            assertContentEquals(accepted.early, candidate.early)
            assertContentEquals(accepted.surface, candidate.surface)
            assertContentEquals(accepted.late, candidate.late)
            for (index in 0..3) assertContentEquals(accepted.instrumentStems[index], candidate.instrumentStems[index])
            for (index in 0..9) assertContentEquals(accepted.surfaceStems[index], candidate.surfaceStems[index])
            assertEquals(accepted.geometry, candidate.geometry)
            assertEquals(accepted.diagnostics.energy, candidate.diagnostics.energy)
        }
    }

    @Test
    fun `candidate endpoints gain level independent spectral and late body variation`() {
        var acceptedSpectralSpan = 0.0
        var candidateSpectralSpan = 0.0
        for (voice in ChamberVoice.entries) for (control in controls) {
            val accepted = listOf(0f, 1f).map { value ->
                Chamber.probe(voice, mapOf(control to value), model = 3, options = quick)
            }
            val candidate = listOf(0f, 1f).map { value ->
                Chamber.probe(voice, mapOf(control to value), model = Chamber.WIDE_RANGE_MODEL, options = quick)
            }
            val before = TangleMeasure.spectralDistance(accepted[0].rawMix, accepted[1].rawMix)
            val after = TangleMeasure.spectralDistance(candidate[0].rawMix, candidate[1].rawMix)
            acceptedSpectralSpan += before
            candidateSpectralSpan += after
            println("CHAMBER range $voice $control spectral span current=$before candidate=$after")
            for (index in 0..1) {
                val current = accepted[index]
                val wide = candidate[index]
                assertTrue(wide.rawMix.all { it.isFinite() }, "$voice $control non-finite endpoint")
                // Unit-RMS comparison removes a louder recording as the reason
                // for the difference. The separate late window rejects an attack-only trick.
                val full = TangleMeasure.distance(current.rawMix, wide.rawMix)
                val from = (.35 * Chamber.RATE).toInt()
                val late = TangleMeasure.distance(current.rawMix.copyOfRange(from, current.rawMix.size),
                    wide.rawMix.copyOfRange(from, wide.rawMix.size))
                assertTrue(full > .02, "$voice $control endpoint$index did not change shape after matching gain: $full")
                assertTrue(late > .015, "$voice $control endpoint$index left the late body unchanged: $late")
                assertPassive(wide, "$voice $control endpoint$index")
            }
        }
        // A waveform phase shift alone can pass the shape checks above. Demand
        // wider normalized spectral separation over the complete control study too.
        println("CHAMBER total normalized spectral span current=$acceptedSpectralSpan candidate=$candidateSpectralSpan")
        assertTrue(candidateSpectralSpan > acceptedSpectralSpan * 1.15,
            "the candidate did not expand the control study's normalized spectral span by 15%")
    }

    @Test
    fun `balance endpoints separate the sources rather than raising master level`() {
        val options = quick.copy(recordStems = true, roomEnabled = false)
        for (voice in ChamberVoice.entries) for (end in listOf(0f, 1f)) {
            val accepted = Chamber.probe(voice, mapOf("BALANCE" to end), model = 3, options = options)
            val candidate = Chamber.probe(voice, mapOf("BALANCE" to end), model = Chamber.WIDE_RANGE_MODEL, options = options)
            val selected = if (end == 0f) 0 else 3
            val distant = if (selected == 0) 3 else 0
            fun leakage(probe: Chamber.Probe) = TangleMeasure.rms(probe.instrumentStems[distant]) /
                TangleMeasure.rms(probe.instrumentStems[selected])
            assertTrue(leakage(candidate) < leakage(accepted) * .5,
                "$voice BALANCE$end did not halve distant-source leakage")
            assertEquals(listOf(0.0, 0.0, 0.0, 0.0), candidate.diagnostics.sharedStrikeTimes)
        }
    }

    @Test
    fun `placement widens near far geometry within clearances and respects explicit positions`() {
        for (voice in ChamberVoice.entries) {
            val configuration = Chamber.configFor(voice)
            val current = mutableListOf<ChamberGeometry>()
            val candidate = mutableListOf<ChamberGeometry>()
            for (value in listOf(0f, 1f)) {
                val m = Chamber.defaults(voice) + ("PLACEMENT" to value)
                val profile = assertNotNull(ChamberWideRange.resolve(voice, m))
                val before = ChamberRoom.prepare(configuration, m.getValue("MATERIAL"), m.getValue("DIVIDE"), value)
                val after = ChamberWideRange.geometry(configuration, m.getValue("MATERIAL"), m.getValue("DIVIDE"), value, profile)
                current += before
                candidate += after
                assertEquals(before.listener, after.listener)
                for (index in after.sources.indices) {
                    assertTrue(after.sources[index].distanceTo(after.listener) >= ChamberRoom.MIN_POINT_DISTANCE_METRES)
                    for (other in 0 until index)
                        assertTrue(after.sources[index].distanceTo(after.sources[other]) >= ChamberRoom.MIN_POINT_DISTANCE_METRES)
                }
                val explicit = configuration.copy(placementMode = ChamberPlacementMode.EXPLICIT)
                assertEquals(ChamberRoom.prepare(explicit, m.getValue("MATERIAL"), m.getValue("DIVIDE"), value),
                    ChamberWideRange.geometry(explicit, m.getValue("MATERIAL"), m.getValue("DIVIDE"), value, profile),
                    "an authored explicit source was moved")
            }
            fun distanceRatio(geometries: List<ChamberGeometry>) = geometries[1].directPaths.sumOf { it.distanceMetres.toDouble() } /
                geometries[0].directPaths.sumOf { it.distanceMetres.toDouble() }
            assertTrue(distanceRatio(candidate) > distanceRatio(current) * 1.5,
                "$voice placement did not extend the physical near/far range")
        }
    }

    @Test
    fun `new hard and soft contacts keep the actual membrane and bar roots above subharmonics`() {
        val options = quick.copy(recordStems = true, roomEnabled = false)
        for (midi in listOf(48, 84)) for (strike in listOf(0f, 1f)) {
            val probe = Chamber.probe(ChamberVoice.HYBRID, mapOf("STRIKE" to strike), midi = midi,
                model = Chamber.WIDE_RANGE_MODEL, options = options)
            val root = Keys.midiHz(midi)
            for (source in 0..1) {
                val stem = probe.instrumentStems[source]
                val hz = FineTuning.measuredHz(stem, Chamber.RATE, root, .03f, .40f)
                val cents = FineTuning.cents(hz, root.toDouble())
                val rootShare = TangleMeasure.toneShare(stem, root.toDouble())
                val subharmonicShare = TangleMeasure.toneShare(stem, root.toDouble() / 2)
                println("CHAMBER range root MIDI$midi STRIKE$strike source$source cents=$cents rootShare=$rootShare octaveDown=$subharmonicShare")
                assertTrue(abs(cents) < 20.0, "source$source MIDI$midi root moved $cents cents")
                assertTrue(rootShare > .10 && rootShare > subharmonicShare * 4,
                    "source$source MIDI$midi lost its meaningful requested root: $rootShare vs octave-down $subharmonicShare")
            }
        }
    }

    @Test
    fun `candidate complete recipes remain deterministic with reordered controls and exact long seed`() {
        val m = Chamber.defaults(ChamberVoice.AQUARIUM) + mapOf("STRIKE" to 0f, "MATERIAL" to 1f,
            "DIVIDE" to 0f, "PLACEMENT" to 0f)
        val patch = ChamberPatch("Wide deterministic room", ChamberVoice.AQUARIUM, m,
            seed = Long.MAX_VALUE - 17, model = Chamber.WIDE_RANGE_MODEL)
        val replay = ChamberPatch.fromJsonText(patch.toJsonText())
        val first = Chamber.probe(patch.voice, patch.macros, seed = patch.seed, model = patch.model, options = quick)
        val second = Chamber.probe(replay.voice, replay.macros.entries.reversed().associate { it.key to it.value },
            seed = replay.seed, model = replay.model, options = quick)
        assertContentEquals(first.rawMix, second.rawMix)
        assertEquals(first.diagnostics.energy, second.diagnostics.energy)
        assertEquals(first.geometry, second.geometry)
    }

    @Test
    fun `bright candidate high register retains pressure and spectrum against an eight rate reference`() {
        val options = quick.copy(seconds = .7f, recordDiagnostics = false)
        val high = controls.associateWith { 1f }
        for (voice in ChamberVoice.entries) {
            fun render(oversample: Int, roomEnabled: Boolean) = Chamber.probe(voice, high,
                midi = 84, velocity = 1f, model = Chamber.WIDE_RANGE_MODEL,
                options = options.copy(oversample = oversample, roomEnabled = roomEnabled))
            val standard = render(4, true)
            val reference = render(8, true)
            val directStandard = render(4, false)
            val directReference = render(8, false)
            for ((name, pair) in listOf("full room" to (standard to reference),
                "direct without room" to (directStandard to directReference))) {
                val four = pair.first
                val eight = pair.second
                assertTrue(four.diagnostics.finiteStates && eight.diagnostics.finiteStates)
                assertTrue(four.rawMix.all(Float::isFinite) && eight.rawMix.all(Float::isFinite))
                val a = referenceSpectrum(four.rawMix)
                val b = referenceSpectrum(eight.rawMix)
                val cosine = a.indices.sumOf { sqrt(a[it] * b[it]) } /
                    sqrt(a.sum() * b.sum()).coerceAtLeast(1e-30)
                val rmsRatio = TangleMeasure.rms(four.rawMix) / TangleMeasure.rms(eight.rawMix)
                fun highShare(power: DoubleArray) = power.indices.filter {
                    it * Chamber.RATE.toDouble() / (power.size * 2) > 16_000
                }.sumOf { power[it] } / power.sum()
                val highDifference = highShare(a) - highShare(b)
                println("CHAMBER range 4x/8x $voice $name MIDI84 magnitudeCosine=$cosine rmsRatio=$rmsRatio highBandDifference=$highDifference")
                assertTrue(cosine.isFinite() && rmsRatio.isFinite() && highDifference.isFinite())
                assertTrue(TangleMeasure.rms(eight.rawMix) > 1e-6, "$voice $name reference was silent")
                assertTrue(rmsRatio in .85..1.15, "$voice $name pressure units diverged: $rmsRatio")
                assertTrue(cosine > .95, "$voice $name spectral magnitudes diverged: $cosine")
                assertTrue(highDifference < 1e-5, "$voice $name excess four-rate high-band share: $highDifference")
            }
        }
    }

    /** Same Hann-windowed FFT and 16 kHz boundary as the accepted-model rate guard. */
    private fun referenceSpectrum(samples: FloatArray): DoubleArray {
        val from = (.025 * Chamber.RATE).roundToInt()
        val count = minOf((.4 * Chamber.RATE).roundToInt(), samples.size - from)
        val real = FloatArray(32768)
        val imaginary = FloatArray(real.size)
        for (index in 0 until count)
            real[index] = (samples[from + index] * (.5 - .5 * cos(2 * PI * index / (count - 1)))).toFloat()
        Fft.forward(real, imaginary)
        return DoubleArray(real.size / 2) { real[it].toDouble() * real[it] + imaginary[it].toDouble() * imaginary[it] }
    }

    private fun assertPassive(probe: Chamber.Probe, label: String) {
        val d = probe.diagnostics
        assertTrue(d.finiteStates, "$label non-finite network")
        assertTrue(d.maxEnergy <= d.primaryWork * 1.0001 + 1e-10, "$label network exceeds primary work")
        assertTrue(d.primaryWork <= d.primaryWorkBudget * 1.0001 + 1e-10, "$label strike exceeds declared work budget")
        assertTrue(d.maxUnforcedEnergyRise <= d.maxEnergy * 1e-4 + 1e-10, "$label unforced energy grows")
        assertTrue(d.maxDelayGain <= 1.0 && d.passiveCouplingNorm <= 1.0000001, "$label non-passive feedback")
    }
}
