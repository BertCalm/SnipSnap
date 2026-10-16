package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Listening-prototype checks. Distances independently remove gain and compare several
 * time windows: different level, initial phase or one changed attack cannot alone prove
 * the pressure history, layer load and unfolding gestures work.
 */
class CreaseTest {
    private val timbral = listOf("FOLD", "PRESSURE", "DRAG", "SPLIT", "RELAX")

    @Test
    fun `repeat renders and reordered macro maps are sample identical`() {
        for (voice in CreaseVoice.entries) {
            val macros = Crease.defaults(voice)
            val a = Crease.probe(voice, macros, midi = 55, velocity = .63f)
            val b = Crease.probe(voice, macros.entries.reversed().associate { it.key to it.value }, midi = 55, velocity = .63f)
            assertContentEquals(a.prelevel, b.prelevel, "$voice prelevel determinism")
            assertContentEquals(a.samples, b.samples, "$voice output determinism")
            assertContentEquals(a.trace.deformationA, b.trace.deformationA, "$voice history determinism")
            assertContentEquals(a.trace.loadB, b.trace.loadB, "$voice load determinism")
            assertEquals(44_100, a.sampleRate)
            assertFalse(a.diagnostics.held)
        }
    }

    @Test
    fun `default and all high notes retain numerical headroom before leveling`() {
        for (voice in CreaseVoice.entries) for (midi in listOf(24, 54, 84)) for (high in listOf(false, true)) {
            val macros = Crease.defaults(voice) + if (high) timbral.associateWith { 1f } else emptyMap()
            val p = Crease.probe(voice, macros, midi = midi, velocity = 1f)
            val label = "$voice MIDI$midi allHigh=$high"
            val d = p.diagnostics
            assertTrue(p.prelevel.isNotEmpty() && p.samples.isNotEmpty(), "$label empty audio")
            assertTrue(p.prelevel.all { it.isFinite() } && p.samples.all { it.isFinite() }, "$label non-finite audio")
            for (trace in listOf(p.trace.timeSeconds, p.trace.pressure, p.trace.deformationA, p.trace.deformationB, p.trace.loadA, p.trace.loadB)) {
                assertTrue(trace.isNotEmpty() && trace.all { it.isFinite() }, "$label non-finite or absent trace")
            }
            assertTrue(d.rawPeak.isFinite() && d.rawPeak < 1.1, "$label raw peak ${d.rawPeak}")
            assertTrue(d.maxAbsState.isFinite() && d.maxAbsState < 1.01, "$label runaway state ${d.maxAbsState}")
            assertTrue(d.maxCoefficientBudget <= .900001, "$label phase-map coefficient budget ${d.maxCoefficientBudget}")
            assertTrue(d.minMapDerivative >= .099999, "$label phase reversal ${d.minMapDerivative}")
            assertTrue(rms(p.prelevel) > 1e-4, "$label silent raw signal")
            assertTrue(abs(p.prelevel.average()) < .01, "$label prelevel DC ${p.prelevel.average()}")
            assertTrue(abs(p.samples.average()) < .01, "$label exported DC ${p.samples.average()}")
            assertTrue(p.samples.all { it in -1f..1f }, "$label clipped final output")
            println("CREASE bounds $label rawPeak=${d.rawPeak} state=${d.maxAbsState} budget=${d.maxCoefficientBudget} derivative=${d.minMapDerivative} dc=${p.prelevel.average()}")
        }
    }

    @Test
    fun `each macro changes both low and middle steps after gain matching`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) for (macro in timbral) {
            val base = Crease.defaults(voice) + ("HOLD" to 0f)
            val low = Crease.probe(voice, base + (macro to 0f)).prelevel
            val quarter = Crease.probe(voice, base + (macro to .25f)).prelevel
            val middle = Crease.probe(voice, base + (macro to .5f)).prelevel
            val first = distance(low, quarter)
            val next = distance(quarter, middle)
            val firstSpectrum = trajectoryDistance(low, quarter)
            val nextSpectrum = trajectoryDistance(quarter, middle)
            println("CREASE macro $voice $macro first=$first middle=$next firstSpectrum=$firstSpectrum middleSpectrum=$nextSpectrum")
            issues.expect(first > .001, "$voice/$macro low quarter is inactive after gain matching: $first")
            issues.expect(next > .001, "$voice/$macro middle quarter is inactive after gain matching: $next")
            issues.expect(firstSpectrum > .0001, "$voice/$macro low quarter changes no measured spectral trajectory: $firstSpectrum")
            issues.expect(nextSpectrum > .0001, "$voice/$macro middle quarter changes no measured spectral trajectory: $nextSpectrum")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `history and reciprocal load change spectral evolution with the same excitation envelope`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) {
            val macros = Crease.defaults(voice) + mapOf("PRESSURE" to .8f, "DRAG" to .65f, "SPLIT" to .65f, "RELAX" to .7f)
            val memory = Crease.probe(voice, macros)
            val instantaneous = Crease.probe(voice, macros, history = CreaseHistory.INSTANTANEOUS)
            val independent = Crease.probe(voice, macros, coupling = false)
            assertEquals(memory.samples.size, instantaneous.samples.size, "$voice history changed the render envelope length")
            assertEquals(memory.samples.size, independent.samples.size, "$voice coupling changed render duration")
            assertContentEquals(memory.trace.pressure, instantaneous.trace.pressure, "$voice history also changed excitation")
            assertContentEquals(memory.trace.pressure, independent.trace.pressure, "$voice coupling also changed excitation")
            val wholeHistory = trajectoryDistance(memory.prelevel, instantaneous.prelevel)
            val historyWindows = listOf(.04, .12, .25, .5, .8).map { seconds ->
                spectralDistanceAt(memory.prelevel, instantaneous.prelevel, (seconds * Dsp.RATE).toInt())
            }
            val historyMean = historyWindows.average()
            val historyMaximum = historyWindows.max()
            val coupling = trajectoryDistance(memory.prelevel, independent.prelevel)
            println("CREASE mechanism $voice historyBodyMean=$historyMean historyBodyMaximum=$historyMaximum historyWholeNote=$wholeHistory bodyWindows=$historyWindows coupling=$coupling")
            // First prototype probes: minimum mean .001568, minimum maximum .002938.
            // History must change the evolving body, and may finish unfolding before the tail.
            // These regression floors measure operation; listening determines audibility.
            issues.expect(historyMean > .001, "$voice history has no sustained spectral difference during the body: $historyMean")
            issues.expect(historyMaximum > .002, "$voice history has no distinct spectral gesture during the body: $historyMaximum")
            issues.expect(coupling > .0001, "$voice layer load has no independent measured spectral change: $coupling")
            issues.expect(distance(memory.prelevel, instantaneous.prelevel) > .01, "$voice history only changes gain")
            issues.expect(distance(memory.prelevel, independent.prelevel) > .001, "$voice coupling only changes gain")
            val afterimage = memory.trace.pressure.indices.filter {
                memory.trace.timeSeconds[it] > .2f && memory.trace.pressure[it] < .08f
            }.maxOfOrNull {
                maxOf(memory.trace.deformationA[it] - instantaneous.trace.deformationA[it],
                    memory.trace.deformationB[it] - instantaneous.trace.deformationB[it]).toDouble()
            } ?: 0.0
            println("CREASE history $voice deformationAfterPressureFalls=$afterimage")
            issues.expect(afterimage > .01, "$voice deformation forgets pressure immediately: $afterimage")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `velocity changes excitation shape rather than only normalized level`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) {
            val soft = Crease.probe(voice, velocity = .25f)
            val hard = Crease.probe(voice, velocity = 1f)
            val spectral = trajectoryDistance(soft.prelevel, hard.prelevel)
            val shape = distance(soft.prelevel, hard.prelevel)
            println("CREASE velocity $voice normalizedDistance=$shape spectralDistance=$spectral")
            issues.expect(shape > .01, "$voice velocity changes only gain: $shape")
            issues.expect(spectral > .002, "$voice velocity has no independent spectral effect: $spectral")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `moderate voices preserve a measurable requested root at low middle and high velocity`() {
        for (voice in CreaseVoice.entries) for (midi in listOf(24, 36, 48, 60, 72, 84)) for (velocity in listOf(.3f, .65f, 1f)) {
            val p = Crease.probe(voice, midi = midi, velocity = velocity)
            val snip = Snip(p.samples, 1, p.sampleRate)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(snip, wanted, .12f, .6f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            val share = toneShare(p.samples, wanted.toDouble(), p.sampleRate)
            println("CREASE pitch $voice MIDI$midi velocity=$velocity cents=$cents rootShare=$share")
            assertTrue(abs(cents) < 10.0, "$voice MIDI$midi velocity$velocity root error $cents cents")
            assertTrue(share > .08, "$voice MIDI$midi velocity$velocity has only a weak root component: $share")
            if (midi in 36..72) {
                val detected = Pitch.detect(snip, .12f, .35f)
                assertTrue(detected != null && detected.confidence > .3f, "$voice MIDI$midi velocity$velocity weak periodic body: $detected")
                assertTrue(abs(FineTuning.cents(detected!!.hz.toDouble(), wanted.toDouble())) < 50.0,
                    "$voice MIDI$midi velocity$velocity detector chose ${detected.hz}Hz instead of $wanted")
            }
        }
    }

    @Test
    fun `finite HOLD steps extend the articulation before the loop endpoint`() {
        for (voice in CreaseVoice.entries) {
            val steps = listOf(0f, .25f, .5f).map { hold ->
                Crease.probe(voice, Crease.defaults(voice) + ("HOLD" to hold))
            }
            for (p in steps) assertFalse(p.diagnostics.held, "$voice finite HOLD unexpectedly selected a loop")
            for ((short, long) in steps.zipWithNext()) {
                assertTrue(long.samples.size > short.samples.size + Dsp.RATE / 20,
                    "$voice finite HOLD has a dead quarter step: ${short.samples.size} / ${long.samples.size} frames")
                val release = short.samples.size - Dsp.RATE / 4
                val shortTail = rms(short.samples.copyOfRange(release, short.samples.size))
                val longBody = rms(long.samples.copyOfRange(release, short.samples.size))
                assertTrue(longBody > shortTail * 1.2,
                    "$voice HOLD pads silence rather than extending articulation: $shortTail / $longBody")
            }
        }
    }

    @Test
    fun `zero velocity produces finite silence for notes and converged loops`() {
        for (voice in CreaseVoice.entries) for (hold in listOf(0f, 1f)) {
            val p = Crease.probe(voice, Crease.defaults(voice) + ("HOLD" to hold), velocity = 0f)
            assertTrue(p.samples.all { it == 0f } && p.prelevel.all { it == 0f }, "$voice HOLD$hold zero velocity is not silent")
            assertTrue(p.diagnostics.seamError.isFinite(), "$voice HOLD$hold zero-velocity seam is NaN")
            assertTrue(p.diagnostics.maxAbsState.isFinite(), "$voice HOLD$hold zero-velocity state is non-finite")
        }
    }

    @Test
    fun `unprobed pitches outside the supported prototype range are refused`() {
        for (midi in listOf(23, 85)) {
            val error = assertFailsWith<IllegalArgumentException> { Crease.render(CreaseVoice.ROUND, midi = midi) }
            assertTrue(error.message.orEmpty().contains("MIDI 24..84"), "unsupported range refusal lacks context: ${error.message}")
        }
    }

    @Test
    fun `held loops recur through independent prior audio and survive PCM export with active deformation`() {
        for (voice in CreaseVoice.entries) {
            val p = Crease.probe(voice, Crease.defaults(voice) + ("HOLD" to 1f))
            val d = p.diagnostics
            assertTrue(d.held && d.converged, "$voice did not converge a held trajectory")
            assertEquals(d.loopFrames, p.samples.size, "$voice loop period does not match exported audio")
            assertEquals(p.samples.size, p.previousCycle.size, "$voice missing independent previous period")
            assertTrue(d.prerollCycles in 2..64, "$voice unbounded or unmeasured preroll ${d.prerollCycles}")
            assertTrue(d.stateError.isFinite() && d.stateError < 1e-3, "$voice synthesis state recurrence ${d.stateError}")
            assertTrue(p.loopStateStart.isNotEmpty(), "$voice missing full-state boundary snapshots")
            assertEquals(p.loopStateStart.size, p.loopStateEnd.size, "$voice incompatible boundary snapshots")
            val independentStateError = p.loopStateStart.indices.maxOf { abs(p.loopStateStart[it] - p.loopStateEnd[it]) }
            assertEquals(independentStateError, d.stateError, 1e-12, "$voice state-error diagnostic disagrees with snapshots")
            assertTrue(independentStateError < 1e-3, "$voice independently measured state recurrence $independentStateError")
            assertTrue(d.rootCycles > 0, "$voice lacks a closed oscillator clock")
            assertEquals(d.rootCycles * p.sampleRate.toDouble() / p.samples.size, d.renderedHz, 1e-12,
                "$voice oscillator did not complete an integer number of cycles")
            val independentContext = p.previousCycle + p.samples
            val measuredSeam = Keys.seamError(independentContext, p.previousCycle.size)
            assertTrue(measuredSeam < 1e-3, "$voice independent-period seam $measuredSeam")
            assertTrue(distance(p.previousCycle, p.samples) < .003, "$voice periods differ outside the seam window")
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, Snip(independentContext, 1, p.sampleRate), WavWriter.BitDepth.PCM_24) }
            val pcm = WavReader.read(bytes.toByteArray()).samples
            val exportedSeam = Keys.seamError(pcm, p.previousCycle.size)
            assertTrue(exportedSeam < 1e-3, "$voice exported PCM24 independent-period seam $exportedSeam")
            val pressureSpan = span(p.trace.pressure)
            val deformationSpan = maxOf(span(p.trace.deformationA), span(p.trace.deformationB))
            println("CREASE hold $voice cycles=${d.prerollCycles} state=${d.stateError} seam=$measuredSeam pcm=$exportedSeam pressureSpan=$pressureSpan deformationSpan=$deformationSpan")
            assertTrue(pressureSpan > .01, "$voice held pressure became static")
            assertTrue(deformationSpan > .005, "$voice held deformation became static")
            val wanted = Keys.midiHz(48)
            val measured = FineTuning.measuredHz(p.samples, p.sampleRate, wanted, .12f, .6f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 10.0, "$voice HOLD moved requested root")
        }
    }

    @Test
    fun `held RELAX and the other memory controls still reshape a constant amplitude sustain`() {
        val cases = CreaseVoice.entries.map { it to "RELAX" } +
            listOf("PRESSURE", "DRAG", "SPLIT").map { CreaseVoice.RUBBER to it }
        val issues = mutableListOf<String>()
        for ((voice, macro) in cases) {
            val base = Crease.defaults(voice) + ("HOLD" to 1f)
            val low = Crease.probe(voice, base + (macro to 0f))
            val high = Crease.probe(voice, base + (macro to 1f))
            assertEquals(low.samples.size, high.samples.size, "$voice/$macro changed the held pressure period")
            assertContentEquals(low.trace.pressure, high.trace.pressure, "$voice/$macro changed the held excitation schedule")
            val shape = distance(low.prelevel, high.prelevel)
            val spectral = trajectoryDistance(low.prelevel, high.prelevel)
            println("CREASE held control $voice $macro normalizedDistance=$shape spectralDistance=$spectral")
            issues.expect(shape > .005, "$voice/$macro has no held motion after gain matching: $shape")
            issues.expect(spectral > .0001, "$voice/$macro does not reshape held spectral evolution: $spectral")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `four times oversampling remains close to a sixteen times high register reference`() {
        for (voice in CreaseVoice.entries) {
            val macros = Crease.defaults(voice) + timbral.associateWith { 1f }
            val normal = Crease.probe(voice, macros, midi = 84, velocity = 1f, oversample = 4)
            val reference = Crease.probe(voice, macros, midi = 84, velocity = 1f, oversample = 16)
            val spectral = trajectoryDistance(normal.prelevel, reference.prelevel)
            println("CREASE reference $voice MIDI84 all-high spectralDistance4x16x=$spectral")
            assertTrue(spectral < 1e-4, "$voice four-times output differs materially from sixteen-times reference: $spectral")
        }
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    /** Whole-signal unit-RMS distance; pure gain changes return zero. */
    private fun distance(a: FloatArray, b: FloatArray): Double {
        val size = minOf(a.size, b.size)
        val ar = rms(a).coerceAtLeast(1e-12)
        val br = rms(b).coerceAtLeast(1e-12)
        return sqrt((0 until size).sumOf { i -> val d = a[i] / ar - b[i] / br; d * d } / size.coerceAtLeast(1))
    }

    /** Mean total variation of normalized spectral power at attack, body and release. */
    private fun trajectoryDistance(a: FloatArray, b: FloatArray): Double {
        val length = minOf(a.size, b.size)
        val starts = listOf(.02, .14, .35, .65).map { (it * length).toInt() }
        return starts.sumOf { start -> spectralDistanceAt(a, b, start) } / starts.size
    }

    private fun spectralDistanceAt(a: FloatArray, b: FloatArray, start: Int): Double {
        val x = spectrum(a, start)
        val y = spectrum(b, start)
        return x.indices.sumOf { abs(x[it] - y[it]) } / 2.0
    }

    private fun spectrum(samples: FloatArray, start: Int): DoubleArray {
        val size = 8192
        val magnitude = Fft.magnitudeSpectrum(samples.copyOfRange(start, minOf(samples.size, start + size)), size)
        val bands = DoubleArray(48)
        for (bin in 1 until magnitude.size) {
            val hz = bin * Dsp.RATE.toDouble() / size
            val band = (ln(hz.coerceAtLeast(25.0) / 25.0) / ln(2.0) * 4.5).toInt().coerceIn(0, bands.lastIndex)
            bands[band] += magnitude[bin].toDouble() * magnitude[bin]
        }
        val total = bands.sum().coerceAtLeast(1e-30)
        return DoubleArray(bands.size) { bands[it] / total }
    }

    private fun toneShare(samples: FloatArray, hz: Double, rate: Int): Double {
        val from = (.12 * rate).toInt()
        val to = minOf(samples.size, from + (.6 * rate).toInt())
        var re = 0.0
        var im = 0.0
        var power = 0.0
        for (i in from until to) {
            val x = samples[i].toDouble()
            re += x * cos(2.0 * PI * hz * i / rate)
            im += x * sin(2.0 * PI * hz * i / rate)
            power += x * x
        }
        return (2.0 * (re * re + im * im) / ((to - from) * power).coerceAtLeast(1e-30)).coerceIn(0.0, 1.0)
    }

    private fun span(values: FloatArray): Double = (values.maxOrNull()!! - values.minOrNull()!!).toDouble()

    private fun MutableList<String>.expect(condition: Boolean, message: String) {
        if (!condition) add(message)
    }
}
