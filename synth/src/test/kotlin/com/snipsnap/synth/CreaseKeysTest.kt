package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Continuous-note checks. Export metadata and player note-off are tested by the shell builder. */
class CreaseKeysTest {
    private data class Example(val label: String, val patch: CreasePatch, val midi: Int, val probe: CreaseKeyProbe)

    companion object {
        // Share immutable observations across the numerical, pitch and actual-PCM checks.
        // Each case still starts its own physical state; no test mutates these arrays.
        private val examples: List<Example> by lazy {
            val paper = CreasePresets.patches().single { it.name == "Paper Keys" }
            val ink = CreasePresets.patches().single { it.name == "Ink Ribbon" }
            val reed = CreasePresets.patches().single { it.name == "Reed Mark" }
            listOf(paper to 24, paper to 60, ink to 60, ink to 84, reed to 60).map { (patch, midi) ->
                Example("${patch.name} MIDI$midi", patch, midi, CreaseKeys.probe(patch, midi))
            }
        }
    }

    @Test
    fun `fresh onsets continue into a bounded independent final cycle at the selected keyboard roots`() {
        for ((label, patch, midi, p) in examples) {
            val note = p.note
            val s = note.snip.samples
            val d = p.diagnostics
            val start = note.loopStartFrame.toInt()
            assertEquals(1, note.snip.channels, label)
            assertEquals(44_100, note.snip.sampleRate, label)
            assertEquals(start, p.prefixFrames, label)
            assertEquals(start / Crease.RATE.toDouble(), p.prefixSeconds, 1e-12, label)
            assertTrue(start >= d.loopFrames && start < s.size, "$label missing real prior-cycle context")
            assertEquals(d.loopFrames, s.size - start, "$label loop must run to the sample's end")
            assertTrue(p.prefixSeconds > p.transitionEndSeconds, "$label loop starts while its source is still morphing")
            assertTrue(note.snip.durationSeconds <= CreaseKeys.MAX_SAMPLE_SECONDS, "$label exceeded the sample bound")
            assertTrue(d.held && d.converged, "$label did not converge")
            assertTrue(d.prerollCycles in 3..CreaseKeys.MAX_SETTLE_CYCLES, "$label unbounded settling")
            assertEquals(3, d.modelVersion, "$label silently selected a historical model")
            assertEquals(patch.model, d.modelVersion, label)
            assertEquals(Keys.midiHz(midi).toDouble(), d.requestedHz, 1e-12, label)
            assertEquals(s.size, p.prelevel.size, label)
            assertTrue(s.all { it.isFinite() && abs(it) <= .991f }, "$label invalid leveled output")
            assertTrue(p.prelevel.all { it.isFinite() }, "$label invalid conditioned source")
            assertTrue(d.rawPeak.isFinite() && d.rawPeak < 1.0, "$label clipped or nonfinite raw source")
            assertTrue(d.maxAbsState.isFinite() && d.maxAbsState <= 1.00001, "$label runaway state")
            assertTrue(d.minMapDerivative >= 1.0 / Crease.MAX_MAP_DERIVATIVE - 1e-6, "$label phase reversal")
            assertTrue(d.maxMapDerivative <= Crease.MAX_MAP_DERIVATIVE + 1e-4, "$label excess phase compression")
            assertTrue(d.maxMapCurvature.isFinite() && d.maxMapCurvature <= Crease.MAX_MAP_CURVATURE, "$label excess curvature")
            val sustainRms = rms(s, start, s.size)
            assertTrue(sustainRms > 1e-4, "$label silent sustain")
            assertTrue(rms(s, 0, 44) < sustainRms * .1, "$label began at settled amplitude instead of speaking")
            assertTrue(rms(s, 441, 4410) > sustainRms * .01, "$label padded its onset with silence")
            assertTrue(abs(s.first()) < sustainRms * .01, "$label discontinuous onset")
            assertTrue(abs(s.average()) < .01, "$label excessive output DC")
            for (trace in listOf(p.trace.timeSeconds, p.trace.pressure, p.trace.deformationA,
                p.trace.deformationB, p.trace.loadA, p.trace.loadB)) {
                assertTrue(trace.isNotEmpty() && trace.all { it.isFinite() }, "$label invalid source trace")
            }
            assertTrue(p.trace.pressure.first() < .01f && p.trace.deformationA.first() < .01f,
                "$label began from a settled control state")
            // The whole attack and sustain share one scalar gain, rather than independently
            // normalizing a finite head and a different held rendering before joining them.
            val at = p.prelevel.indices.maxBy { abs(p.prelevel[it]) }
            val gain = s[at] / p.prelevel[at]
            for (i in s.indices step 127) {
                assertEquals(p.prelevel[i] * gain, s[i], 2e-6f, "$label gain discontinuity at frame $i")
            }
            println("CREASE KEYS $label prefixSeconds=${p.prefixSeconds} loopFrames=${d.loopFrames} " +
                "prerollCycles=${d.prerollCycles} renderSeconds=${d.renderNanos / 1e9} rawPeak=${d.rawPeak}")
        }
    }

    @Test
    fun `the real preceding cycle and full physical boundary states recur without duplicated evidence`() {
        for ((label, _, _, p) in examples) {
            val s = p.note.snip.samples
            val start = p.prefixFrames
            val frames = s.size - start
            assertEquals(frames, p.previousCycle.size, "$label missing independent predecessor")
            // The retained buffer receives the common level and peak scalars in two
            // Float operations; the independent snapshot uses their composite scalar.
            // Permit rounding only, while checking every actual predecessor sample.
            val predecessorError = p.previousCycle.indices.maxOf {
                abs(s[start - frames + it] - p.previousCycle[it])
            }
            assertTrue(predecessorError <= 2e-7f,
                "$label previousCycle differs from its actual retained source period: $predecessorError")
            assertTrue(p.loopStateStart.isNotEmpty(), "$label missing boundary state")
            assertEquals(p.loopStateStart.size, p.loopStateEnd.size, label)
            assertTrue(p.loopStateStart.all { it.isFinite() } && p.loopStateEnd.all { it.isFinite() }, label)
            val stateError = p.loopStateStart.indices.maxOf { abs(p.loopStateStart[it] - p.loopStateEnd[it]) }
            assertEquals(stateError, p.diagnostics.stateError, 1e-12, "$label state diagnostic disagrees")
            assertTrue(stateError < Crease.LOOP_STATE_TOLERANCE, "$label state does not recur: $stateError")
            val independentSeam = Keys.seamError(s, start)
            assertTrue(independentSeam < Keys.MAX_SEAM_ERROR, "$label independent source seam $independentSeam")
            assertTrue(unitRmsDistance(s.copyOfRange(0, frames), s.copyOfRange(start, s.size)) > .01,
                "$label replaced its real attack with a repeated settled period")
            val slopes = DoubleArray(frames - 1) { i -> (s[start + i + 1] - s[start + i]).toDouble() }
            val slopeRms = sqrt(slopes.sumOf { it * it } / slopes.size)
            val jump = abs(s.last() - s[start])
            assertTrue(jump < slopeRms * 8.0, "$label wrap step exceeds ordinary waveform motion")
            println("CREASE KEYS STATE $label stateError=$stateError seam=$independentSeam wrapSlopeRatio=${jump / slopeRms}")
        }
    }

    @Test
    fun `actual PCM16 and PCM24 retain the independent wrap and native root`() {
        for ((label, _, midi, p) in examples) {
            val start = p.prefixFrames
            val d = p.diagnostics
            assertTrue(d.rootCycles > 0, "$label missing exact oscillator clock")
            assertEquals(d.rootCycles * Crease.RATE.toDouble() / d.loopFrames, d.renderedHz, 1e-12, label)
            assertTrue(abs(FineTuning.cents(d.renderedHz, Keys.midiHz(midi).toDouble())) <= 1.0,
                "$label loop planning retuned the keyboard root")
            for (depth in listOf(WavWriter.BitDepth.PCM_16, WavWriter.BitDepth.PCM_24)) {
                val bytes = ByteArrayOutputStream().also { WavWriter.write(it, p.note.snip, depth) }.toByteArray()
                val decoded = WavReader.read(bytes)
                assertEquals(p.note.snip.frameCount, decoded.frameCount, "$label $depth")
                val seam = Keys.seamError(decoded.samples, start)
                assertTrue(seam < Keys.MAX_SEAM_ERROR, "$label $depth independent seam $seam")
                val sustain = Snip(decoded.samples.copyOfRange(start, decoded.samples.size), 1, 44_100)
                val hz = FineTuning.measuredHz(sustain, Keys.midiHz(midi), .12f, .6f)
                val cents = FineTuning.cents(hz, Keys.midiHz(midi).toDouble())
                assertTrue(abs(cents) <= 1.0, "$label $depth requested root drift $cents cents")
                println("CREASE KEYS PCM $label $depth seam=$seam cents=$cents")
            }
        }
    }

    @Test
    fun `the sixteen times reference preserves the same steady harmonic shape and loop clock`() {
        val normal = examples.single { it.patch.name == "Paper Keys" && it.midi == 60 }.probe
        val patch = CreasePresets.patches().single { it.name == "Paper Keys" }
        val reference = CreaseKeys.probe(patch, 60, oversample = 16)
        assertEquals(normal.diagnostics.loopFrames, reference.diagnostics.loopFrames)
        assertEquals(normal.diagnostics.rootCycles, reference.diagnostics.rootCycles)
        val a = harmonicPower(normal.note.snip.samples, normal.prefixFrames, normal.diagnostics.renderedHz)
        val b = harmonicPower(reference.note.snip.samples, reference.prefixFrames, reference.diagnostics.renderedHz)
        val distance = a.indices.sumOf { abs(a[it] - b[it]) } / 2.0
        // This new note-mode reference is a gain/phase-independent comparison, not a
        // claim to measure every alias. The existing engine tests cover MIDI84 extremes.
        assertTrue(distance < .001, "key sustain differs from the higher-rate reference: $distance")
        assertTrue(Keys.seamError(reference.note.snip.samples, reference.prefixFrames) < Keys.MAX_SEAM_ERROR)
        println("CREASE KEYS REFERENCE harmonicPowerDistance4x16x=$distance")
    }

    @Test
    fun `the keyboard span clips to the supported register while retaining its selected root`() {
        assertEquals((48..72 step 3).toList(), Keys.creasePadMidis(60))
        assertEquals((24..36 step 3).toList(), Keys.creasePadMidis(24))
        assertEquals((72..84 step 3).toList(), Keys.creasePadMidis(84))
        for (root in listOf(25, 59, 83)) {
            val roots = Keys.creasePadMidis(root)
            assertEquals(roots.distinct().sorted(), roots)
            assertTrue(root in roots)
            assertTrue(roots.all { it in Crease.MIDI_MIN..Crease.MIDI_MAX })
        }
        for (root in listOf(23, 85)) assertFailsWith<IllegalArgumentException> { Keys.creasePadMidis(root) }
    }

    @Test
    fun `legacy keys invalid roots and cancelled work refuse without upgrading saved patches`() {
        val paper = CreasePresets.patches().single { it.name == "Paper Keys" }
        for (model in listOf(1, 2)) {
            val error = assertFailsWith<IllegalArgumentException> { Keys.creasePad(paper.copy(model = model)) }
            assertTrue(error.message.orEmpty().contains("model"), "legacy refusal should explain its model: ${error.message}")
        }
        for (midi in listOf(23, 85)) assertFailsWith<IllegalArgumentException> { Keys.creasePad(paper, midi) }
        assertFailsWith<IllegalArgumentException> { CreaseKeys.probe(paper, oversample = 8) }
        assertFailsWith<CancellationException> { Keys.creasePad(paper, cancelled = { true }) }
        var checks = 0
        assertFailsWith<CancellationException> {
            CreaseKeys.probe(paper, cancelled = { ++checks >= 3 })
        }
        assertTrue(checks >= 3, "cancellation was never polled during source evolution")
        assertEquals(3, paper.model)
        assertEquals(.25f, paper.macros.getValue("HOLD"), "authoring a held key must not mutate the finite source recipe")
    }

    @Test
    fun `zero authoring velocity is finite silence with valid loop metadata`() {
        val patch = CreasePresets.patches().single { it.name == "Paper Keys" }.copy(velocity = 0f)
        val p = CreaseKeys.probe(patch)
        assertTrue(p.note.snip.samples.isNotEmpty() && p.note.snip.samples.all { it == 0f })
        assertTrue(p.prelevel.all { it == 0f })
        assertTrue(p.diagnostics.seamError.isFinite() && p.diagnostics.stateError.isFinite())
        assertTrue(p.note.loopStartFrame in 1 until p.note.snip.frameCount.toLong())
    }

    @Test
    fun `approved R3 HOLD samples remain bit identical after adding the keyboard mode`() {
        // Archived approved R3 manifest: independent of the new instrument render path.
        val hashes = mapOf(
            CreaseVoice.ROUND to "7ee0e69bb8b3c097058d0c059390e9bcbf45b7461080073cc743804cb4a0589d",
            CreaseVoice.PINCH to "224d47fea6d427545c237f53a5f632a6725fb341cc5d7dcbd7599e8aba182e3f",
            CreaseVoice.RUBBER to "81208feeb5e615f73a13edf462a5b0740599e2aecf8438e7114f6592945248d5",
        )
        val macros = mapOf("FOLD" to .6f, "PRESSURE" to .85f, "DRAG" to .85f,
            "SPLIT" to .7f, "RELAX" to .8f, "HOLD" to 1f)
        for ((voice, expected) in hashes) {
            val samples = Crease.render(voice, macros, midi = 60, velocity = .8f, model = 3).samples
            val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) bytes.putFloat(sample)
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "$voice existing HOLD changed")
        }
    }

    private fun rms(s: FloatArray, from: Int = 0, until: Int = s.size): Double =
        sqrt((from until until).sumOf { s[it].toDouble() * s[it] } / (until - from).coerceAtLeast(1))

    private fun unitRmsDistance(a: FloatArray, b: FloatArray): Double {
        val ar = rms(a).coerceAtLeast(1e-20)
        val br = rms(b).coerceAtLeast(1e-20)
        return sqrt(a.indices.sumOf { val d = a[it] / ar - b[it] / br; d * d } / a.size)
    }

    private fun harmonicPower(s: FloatArray, start: Int, hz: Double): DoubleArray {
        val n = 11_025
        val window = FloatArray(65_536)
        for (i in 0 until n) window[i] = (s[start + i] * (.5 - .5 * cos(2 * Math.PI * i / (n - 1)))).toFloat()
        val spectrum = Fft.magnitudeSpectrum(window, window.size)
        val power = DoubleArray(32)
        for (h in power.indices) {
            val center = hz * (h + 1)
            for (bin in 1 until spectrum.size) {
                if (abs(bin * Crease.RATE.toDouble() / window.size - center) <= 12.0)
                    power[h] += spectrum[bin].toDouble() * spectrum[bin]
            }
        }
        val total = power.sum().coerceAtLeast(1e-30)
        return DoubleArray(power.size) { power[it] / total }
    }
}
