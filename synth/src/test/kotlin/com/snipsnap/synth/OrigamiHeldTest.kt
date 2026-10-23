package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The held transport adds a continuous field without changing the accepted finite recipe. */
class OrigamiHeldTest {
    private val voices = listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)
    private val rate = Dsp.RATE

    private fun patch(voice: OrigamiVoice, motion: Float = 0f): OrigamiPatch =
        OrigamiPresets.forVoice(voice).first().copy(
            name = "Saved held paper", midi = 72, velocity = .71f, seed = -9007199254740993L,
            macros = OrigamiPresets.forVoice(voice).first().macros + mapOf("TUNE" to 0f, "MOTION" to motion))

    private fun probe(patch: OrigamiPatch, midi: Int): OrigamiHeld.Probe =
        probes.computeIfAbsent(patch to midi) { OrigamiKeys.probe(patch, midi) }

    private fun note(patch: OrigamiPatch, midi: Int): KeyNote = probe(patch, midi).note

    @Test
    fun `held notes retain the authored onset and exact root across C3 C4 and C5`() {
        for (voice in voices) for (midi in listOf(48, 60, 72)) {
            val patch = patch(voice)
            val measuredField = probe(patch, midi)
            val held = measuredField.note
            assertEquals(1, held.snip.channels)
            assertEquals(rate, held.snip.sampleRate)
            assertTrue(held.loopStartFrame in 1 until held.snip.frameCount.toLong())
            assertTrue(held.snip.samples.all { it.isFinite() && it in -1f..1f })
            val finite = patch.copy(midi = midi).render()
            val prefix = measuredField.finitePrefixFrames
            assertTrue(prefix >= (rate * if (voice == OrigamiVoice.FAN) .12 else .58).toInt(),
                "$voice truncated its completed finite attack")
            assertContentEquals(finite.samples.copyOfRange(0, prefix), held.snip.samples.copyOfRange(0, prefix),
                "$voice MIDI$midi changed the accepted attack before entering its sustain")
            val loop = held.snip.samples.copyOfRange(held.loopStartFrame.toInt(), held.snip.frameCount)
            val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val measured = FineTuning.measuredHz(loop, rate, expected.toFloat(), .1f, .7f)
            val cents = FineTuning.cents(measured, expected)
            println("ORIGAMI HELD $voice MIDI$midi: root ${cents}c, loop ${loop.size.toDouble() / rate}s, RMS ${rms(loop)}")
            assertTrue(abs(cents) < .5, "$voice MIDI$midi held root moved ${cents}c")
            assertTrue(abs(measuredField.rootCents) < .1, "$voice MIDI$midi loop fit retuned the anchor")
            assertTrue(measuredField.maxFrequencyCorrectionHz <= .5 * rate / measuredField.loopFrames + 1e-8,
                "$voice MIDI$midi fitting moved a partial by more than half a cycle over the loop")
            assertTrue(rms(loop) > .015, "$voice MIDI$midi a silent field cannot establish continuity")
            val body = rms(finite.samples, (rate * .65).toInt(), (rate * 1.25).toInt())
            assertTrue(rms(loop) > body * .5 && rms(loop) < body * 2,
                "$voice MIDI$midi sustain changed level materially ($body -> ${rms(loop)})")
            assertTrue(rms(loop, loop.size - rate / 5, loop.size) > rms(loop) * .45,
                "$voice MIDI$midi the held file still has a finite tail fade")
            assertContinuation(measuredField, "$voice MIDI$midi")
        }
    }

    @Test
    fun `source full folds and moving geometry corners close without a silent seam or onset dip`() {
        val corners = listOf(
            "source" to mapOf("FOLD" to 0f, "MOTION" to 1f),
            "full fold" to mapOf("FOLD" to 1f, "MOTION" to .8f),
            "geometry corner" to mapOf("CREASE" to 0f, "FOLD" to 1f, "TURN" to 1f, "WARP" to 0f, "MOTION" to 1f),
        )
        for (voice in voices) for ((label, macros) in corners) {
            val patch = patch(voice).copy(macros = patch(voice).macros + macros,
                seed = if (label == "geometry corner") Long.MIN_VALUE + 41 else 20261012L)
            val measured = probe(patch, if (label == "geometry corner") 72 else 60)
            assertContinuation(measured, "$voice $label")
            val samples = measured.note.snip.samples
            val window = rate / 20
            val before = rms(samples, measured.joinStartFrame - window, measured.joinStartFrame)
            val after = rms(samples, measured.joinEndFrame, measured.joinEndFrame + window)
            assertTrue(before > .01 && after > .01, "$voice $label silent attack or held field")
            for (start in measured.joinStartFrame until measured.joinEndFrame step window) {
                val level = rms(samples, start, start + window)
                assertTrue(level > minOf(before, after) * .55,
                    "$voice $label transition cancels the note: $before -> $level -> $after")
                assertTrue(level < maxOf(before, after) * 1.7,
                    "$voice $label transition creates a level surge: $before -> $level -> $after")
            }
        }
    }

    @Test
    fun `a saved finite recipe stays finite and deterministic held rendering owns its arrays`() {
        for (voice in voices) {
            val patch = patch(voice, .67f)
            val saved = patch.toJsonText()
            val finite = patch.render()
            val first = note(patch, 60)
            val second = OrigamiKeys.note(patch, 60)
            assertContentEquals(first.snip.samples, second.snip.samples, "$voice fresh held renders diverged")
            assertTrue(first.snip.samples !== second.snip.samples)
            assertEquals(first.loopStartFrame, second.loopStartFrame)
            assertEquals(saved, patch.toJsonText(), "held transport mutated the saved finite recipe")
            val restored = OrigamiPatch.fromJsonText(saved)
            assertEquals(patch, restored)
            assertContentEquals(finite.samples, restored.render().samples)
            assertTrue(finite.samples.takeLast(rate / 50).all { abs(it) < .005f },
                "$voice normal patch playback acquired the held field")
            val zonePitch = patch.copy(midi = 48, macros = patch.macros + ("TUNE" to 1f))
            assertContentEquals(first.snip.samples, OrigamiKeys.note(zonePitch, 60).snip.samples,
                "the explicit keyboard zone must override saved MIDI and TUNE")
        }
    }

    @Test
    fun `axis motion travels inside the held folded body while the root remains fixed`() {
        for (voice in voices) {
            val still = loop(note(patch(voice), 60))
            val moving = loop(note(patch(voice, .8f), 60))
            val root = 440.0 * 2.0.pow((60 - 69) / 12.0)
            val target = root * if (voice == OrigamiVoice.FAN) 1.5 else 2.5
            fun centers(samples: FloatArray, hz: Double): List<Double> = listOf(.125, .375, .625, .875).map { fraction ->
                centroid(samples, (samples.size * fraction).toInt(), hz * .92, hz * 1.08)
            }
            val staticCenters = centers(still, target)
            val movingCenters = centers(moving, target)
            val staticTravel = staticCenters.max() - staticCenters.min()
            val movingTravel = movingCenters.max() - movingCenters.min()
            val rootCenters = centers(moving, root)
            val rootTravel = rootCenters.max() - rootCenters.min()
            println("ORIGAMI HELD $voice: folded centroid travel $staticTravel Hz still, $movingTravel Hz moving; root travel $rootTravel Hz")
            assertTrue(movingTravel > staticTravel + root * .001,
                "$voice the held field froze MOTION ($staticTravel -> $movingTravel Hz)")
            assertTrue(rootTravel < root * .0002, "$voice motion translated the anchor by $rootTravel Hz")
            assertTrue(bandFraction(moving, target * .92, target * 1.08) > .01,
                "$voice motion cannot be demonstrated with a muted folded body")
        }
    }

    @Test
    fun `unsupported finite identities invalid notes and cancellation refuse before returning audio`() {
        assertEquals(1, OrigamiKeys.MODEL_VERSION)
        for (voice in voices) {
            val patch = patch(voice)
            assertTrue(OrigamiKeys.supports(patch))
            for (model in 1..3) {
                assertFalse(OrigamiKeys.supports(patch.copy(model = model)))
                assertFailsWith<IllegalArgumentException> { OrigamiKeys.note(patch.copy(model = model), 60) }
            }
            for (midi in listOf(Int.MIN_VALUE, 47, 73, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { OrigamiKeys.note(patch, midi) }
            }
            assertFailsWith<CancellationException> { OrigamiKeys.note(patch, 60) { true } }
        }
        val crane = OrigamiPresets.forVoice(OrigamiVoice.CRANE).first()
        assertFalse(OrigamiKeys.supports(crane))
        assertFailsWith<IllegalArgumentException> { OrigamiKeys.note(crane, 60) }
        assertTrue(OrigamiKeys.note(patch(OrigamiVoice.FAN).copy(velocity = 0f), 60).snip.samples.all { it == 0f })
        var checks = 0
        assertFailsWith<CancellationException> {
            OrigamiKeys.note(patch(OrigamiVoice.STILL), 60) { ++checks >= 3 }
        }
        assertEquals(3, checks, "cancelled work kept polling or returned audio")
        assertTrue(Thread.getAllStackTraces().keys.none {
            it.isAlive && it.name.startsWith("origami-held-reference-")
        }, "cancellation left the finite reference worker running")
    }

    @Test
    fun `cancellable decimation exactly preserves whole-buffer filtering across chunk boundaries`() {
        val random = Random(20261021)
        for (frames in listOf(1, 17, 4095, 4096, 4097, 8193)) {
            val raw = FloatArray(frames * Dsp.OVERSAMPLE) { index ->
                (.21 + .31 * kotlin.math.sin(index * .131) + random.nextDouble(-.1, .1)).toFloat()
            }
            // Nonzero endpoints expose artificial chunk-boundary padding or a dropped final frame.
            raw[raw.lastIndex] = .37f
            assertContentEquals(Dsp.decimate(raw, rate), OrigamiHeld.decimate(raw),
                "$frames output frames: chunk context changed the accepted sinc filter")
        }
        var callbacks = 0
        assertFailsWith<CancellationException> {
            OrigamiHeld.decimate(FloatArray(9000 * Dsp.OVERSAMPLE) { .2f }) { ++callbacks >= 3 }
        }
        assertEquals(3, callbacks, "conversion ignored cancellation between completed chunks")
    }

    /** Compare real synthesized continuations, then inspect the discontinuity a player actually wraps over. */
    private fun assertContinuation(measured: OrigamiHeld.Probe, label: String) {
        val loop = loop(measured.note)
        assertTrue(measured.maxPhaseClosureCycles < 1e-6,
            "$label the unwrapped frequency integral does not close before the phase lookup repeats")
        assertEquals(measured.loopFrames, loop.size)
        assertEquals(loop.size, measured.nextCycle.size)
        assertEquals(loop.size, measured.rawLoop.size)
        assertEquals(loop.size, measured.rawNextCycle.size)
        assertTrue(relativeError(measured.rawLoop, measured.rawNextCycle) < 1e-7,
            "$label independently synthesized raw cycles do not agree")
        assertTrue(relativeError(loop, measured.nextCycle) < 1e-7,
            "$label conditioning or gain broke continuation")
        val boundaryStep = abs(loop.first() - loop.last())
        val ordinaryStep = (1 until loop.size).maxOf { abs(loop[it] - loop[it - 1]) }
        val boundaryBend = abs((loop[1] - loop[0]) - (loop[0] - loop.last()))
        val ordinaryBend = (2 until loop.size).maxOf { abs((loop[it] - loop[it - 1]) - (loop[it - 1] - loop[it - 2])) }
        assertTrue(boundaryStep <= ordinaryStep * 1.03 + 1e-7, "$label an exceptional step appears at the wrap")
        assertTrue(boundaryBend <= ordinaryBend * 1.03 + 1e-7, "$label the wrap changes the waveform's slope abruptly")
        assertTrue(measured.rawLoop.all { it.isFinite() && abs(it) < .31f }, "$label unsafe raw output")
        assertTrue(rms(loop) > .015, "$label silent closure")
        assertTrue(abs(rms(loop) - rms(measured.nextCycle)) < 1e-7, "$label sustain level changes across wraps")
    }

    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        val difference = a.indices.sumOf { val d = a[it] - b[it].toDouble(); d * d }
        return sqrt(difference / a.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30))
    }

    private fun loop(note: KeyNote): FloatArray =
        note.snip.samples.copyOfRange(note.loopStartFrame.toInt(), note.snip.frameCount)

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double =
        sqrt((from until until).sumOf { samples[it].toDouble() * samples[it] } / (until - from))

    private data class Spectrum(val powers: DoubleArray, val binHz: Double)

    private fun spectrum(samples: FloatArray, center: Int = samples.size / 2): Spectrum {
        val count = rate / 2
        val first = (center - count / 2).coerceIn(0, samples.size - count)
        val real = FloatArray(65536)
        val imaginary = FloatArray(real.size)
        for (i in 0 until count) {
            real[i] = (samples[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        }
        Fft.forward(real, imaginary)
        return Spectrum(DoubleArray(real.size / 2) {
            real[it].toDouble() * real[it] + imaginary[it].toDouble() * imaginary[it]
        }, rate.toDouble() / real.size)
    }

    private fun centroid(samples: FloatArray, center: Int, low: Double, high: Double): Double {
        val s = spectrum(samples, center)
        val band = s.powers.indices.filter { it * s.binHz in low..high }
        val energy = band.sumOf { s.powers[it] }
        assertTrue(energy > 1e-9, "no spectral body in $low..$high Hz")
        return band.sumOf { s.powers[it] * it * s.binHz } / energy
    }

    private fun bandFraction(samples: FloatArray, low: Double, high: Double): Double {
        val s = spectrum(samples)
        return s.powers.indices.filter { it * s.binHz in low..high }.sumOf { s.powers[it] } /
            s.powers.drop(1).sum().coerceAtLeast(1e-20)
    }

    companion object {
        private val probes = ConcurrentHashMap<Pair<OrigamiPatch, Int>, OrigamiHeld.Probe>()
    }
}
