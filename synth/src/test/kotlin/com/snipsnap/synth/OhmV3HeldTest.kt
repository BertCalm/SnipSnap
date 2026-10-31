package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Model-3 source replay, continued physical cycles, and explicitly disclosed captured bow sustains. */
class OhmV3HeldTest {
    private val base = OhmV2.defaults() + mapOf(
        "TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .2f,
        "MEDDLING" to .65f, "COMMUNE" to .7f, "HOLD" to 0f,
    )

    @Test
    fun `finite model three pluck replays raw and final samples deterministically`() {
        val options = OhmOptions(exciter = OhmExciter.PLUCK)
        for (gesture in OhmGesture.entries) for (midi in listOf(57, 72)) {
            val macros = base + ("LENGTH" to .1f)
            val reordered = macros.entries.reversed().associate { it.key to it.value }
            val seed = if (midi == 57) -905 else Int.MIN_VALUE
            assertContentEquals(
                OhmV3.renderRaw(macros, midi, .63f, seed, gesture, options).samples,
                OhmV3.renderRaw(reordered, midi, .63f, seed, gesture, options).samples,
                "$gesture MIDI$midi: model-3 raw pluck changed on replay or macro iteration order",
            )
            assertContentEquals(
                OhmV3.render(macros, midi, .63f, seed, gesture, options).samples,
                OhmV3.render(reordered, midi, .63f, seed, gesture, options).samples,
                "$gesture MIDI$midi: model-3 final pluck changed on replay or macro iteration order",
            )
        }
    }

    @Test
    fun `epsilon source amounts approach the same model three off schedule continuously`() {
        val macros = base + mapOf("MEDDLING" to .8f, "COMMUNE" to .7f)
        val offRaw = OhmV3.renderRaw(macros, midi = 60, velocity = .8f, seed = 731)
        val offFinal = OhmV3.render(macros, midi = 60, velocity = .8f, seed = 731)
        for ((name, options) in listOf(
            "elder" to OhmOptions(elder = 1e-4f),
            "grains" to OhmOptions(grains = 1e-4f),
            "freeze" to OhmOptions(freeze = 1e-4f),
        )) {
            val epsilonRaw = OhmV3.renderRaw(macros, midi = 60, velocity = .8f, seed = 731, options = options)
            val epsilonFinal = OhmV3.render(macros, midi = 60, velocity = .8f, seed = 731, options = options)
            val rawError = relativeEnergy(offRaw.samples, epsilonRaw.samples)
            val finalError = relativeEnergy(offFinal.samples, epsilonFinal.samples)
            assertTrue(rawError < 1e-6, "$name epsilon changes the raw schedule at its zero boundary: $rawError")
            assertTrue(finalError < 1e-6, "$name epsilon changes the final schedule at its zero boundary: $finalError")
            println("OHM $name 1e-4 versus OFF: raw $rawError, final $finalError")
        }
    }

    @Test
    fun `each held exciter retains an onset and reports its actual sustain method`() {
        for (exciter in OhmExciter.entries) {
            val options = options(exciter)
            val macros = base + ("HOLD" to 1f)
            val measured = OhmV3.heldProbe(macros, midi = 60, velocity = .8f, seed = 731,
                gesture = OhmGesture.STEADY, options = options)
            assertHeld(measured, exciter.name, capturedBow = exciter == OhmExciter.BOW)
            if (exciter == OhmExciter.BOW) {
                // This known fixture does not close its stick/slip trajectory. Its
                // real future must remain evidence, separate from the playable cut.
                assertTrue(relativeEnergy(measured.physicalLoop, measured.rawNextCycle) > 1e-10,
                    "BOW fixture's actual future was replaced with the captured loop")
                assertTrue(relativeEnergy(measured.physicalLoop, measured.rawLoop) > 1e-12,
                    "BOW claims a blend without preserving its separate unmodified source stretch")
            }

            val replay = OhmV3.renderHeld(macros, midi = 60, velocity = .8f, seed = 731,
                gesture = OhmGesture.STEADY, options = options)
            assertEquals(measured.note.loopStartFrame, replay.loopStartFrame, "$exciter moved its marker on replay")
            assertContentEquals(measured.note.snip.samples, replay.snip.samples, "$exciter held render is not deterministic")
        }
    }

    @Test
    fun `captured bow sustains remain bounded across keygroup registers and relationships`() {
        val options = OhmOptions(exciter = OhmExciter.BOW)
        val relationships = listOf(0f to 0f, .65f to .5f, 1f to 1f)
        for ((midi, touch, body) in listOf(Triple(48, .25f, .75f), Triple(60, .5f, .5f), Triple(72, .75f, .25f))) {
            for ((meddling, commune) in relationships) {
                val macros = base + mapOf("HOLD" to 1f, "TOUCH" to touch, "BODY" to body,
                    "MEDDLING" to meddling, "COMMUNE" to commune)
                val measured = OhmV3.heldProbe(macros, midi = midi, velocity = .8f, seed = 731, options = options)
                assertHeld(measured, "BOW MIDI$midi T$touch B$body M$meddling C$commune", capturedBow = true)
            }
        }
    }

    @Test
    fun `gather retains its populated entrance before an independently continued sustain`() {
        val measured = OhmV3.heldProbe(base + ("HOLD" to 1f), midi = 60, velocity = .8f, seed = -73,
            gesture = OhmGesture.GATHER, options = OhmOptions())
        assertHeld(measured, "GATHER")
    }

    @Test
    fun `bow excitation has a different normalized harmonic profile from a finite pluck`() {
        val macros = base + mapOf("MEDDLING" to 0f, "COMMUNE" to 0f, "LENGTH" to .3f)
        val pluck = OhmV3.renderRaw(macros, midi = 60, velocity = .8f, seed = 731,
            options = OhmOptions(exciter = OhmExciter.PLUCK))
        val bow = OhmV3.renderRaw(macros, midi = 60, velocity = .8f, seed = 731,
            options = OhmOptions(exciter = OhmExciter.BOW))
        val pluckProfile = harmonicProfile(pluck)
        val bowProfile = harmonicProfile(bow)
        val shiftedMass = .5 * pluckProfile.indices.sumOf { abs(pluckProfile[it] - bowProfile[it]) }
        println("OHM C4 BOW/PLUCK normalized harmonic-band mass difference $shiftedMass")
        assertTrue(shiftedMass > .005,
            "BOW has the same normalized harmonic balance as PLUCK; changing level or phase cannot establish a different excitation")
        assertTrue(rms(bow.samples) > 1e-5, "the bow fixture is silent")
    }

    private fun assertHeld(measured: OhmV3.HeldProbe, label: String, capturedBow: Boolean = false) {
        val note = measured.note
        val samples = note.snip.samples
        assertEquals(1, note.snip.channels, "$label must be mono")
        assertEquals(Dsp.RATE, note.snip.sampleRate)
        assertEquals(measured.loopStart.toLong(), note.loopStartFrame)
        assertTrue(measured.loopStart >= 256 && measured.loopStart < samples.size, "$label lost its real onset prefix")
        assertEquals(measured.loopFrames, samples.size - measured.loopStart, "$label must loop through EOF")
        assertEquals(note.loopStartFrame, measured.rawNote.loopStartFrame, "$label raw export moved the loop marker")
        assertEquals(note.snip.frameCount, measured.rawNote.snip.frameCount, "$label raw export changed attack or sustain length")
        assertContentEquals(measured.rawLoop,
            measured.rawNote.snip.samples.copyOfRange(measured.loopStart, measured.rawNote.snip.frameCount),
            "$label raw note's marked region differs from the measured unscaled cycle")
        assertTrue(measured.loopFrames >= 256)
        assertTrue(measured.settledCycles >= 2, "$label has no independently advanced source history")
        assertTrue(samples.all { it.isFinite() && abs(it) <= .990001f }, "$label final audio is non-finite or exceeds its peak guard")
        assertTrue(measured.maxState.isFinite() && measured.maxState < 8f, "$label lost physical state headroom")
        assertTrue(rms(samples.copyOfRange(0, measured.loopStart)) > 1e-5, "$label onset prefix is silent")

        val loop = samples.copyOfRange(measured.loopStart, samples.size)
        assertEquals(loop.size, measured.nextCycle.size)
        assertEquals(loop.size, measured.rawLoop.size)
        assertEquals(loop.size, measured.rawNextCycle.size)
        assertEquals(loop.size, measured.physicalLoop.size)
        assertTrue(measured.rawLoop !== measured.rawNextCycle, "$label received the same raw array twice")
        assertTrue(measured.physicalLoop !== measured.rawNextCycle, "$label received copied physical continuation evidence")
        assertTrue(samples !== measured.nextCycle, "$label received exported audio as its future cycle")
        assertTrue(measured.rawLoop.all { it.isFinite() } && measured.rawNextCycle.all { it.isFinite() },
            "$label raw continuation is non-finite")
        assertTrue(measured.nextCycle.all { it.isFinite() }, "$label final continuation is non-finite")
        assertTrue(measured.physicalLoop.all { it.isFinite() }, "$label unmodified physical history is non-finite")
        assertTrue(rms(loop) > 1e-5 && rms(measured.rawLoop) > 1e-8, "$label closes by capturing silence")

        // These errors are difference energy / signal energy, matching Keys.seamError's
        // convention. Recompute from the samples rather than trusting scalar diagnostics.
        val rawError = relativeEnergy(measured.rawLoop, measured.rawNextCycle)
        val finalError = relativeEnergy(loop, measured.nextCycle)
        val seam = Keys.seamError(samples, measured.loopStart)
        if (capturedBow) {
            assertEquals("captured-loop", measured.sustainMethod)
            assertTrue(measured.appliedBlend, "$label hides its captured-loop treatment")
            assertTrue(measured.blendFrames in 1 until loop.size, "$label has no bounded blend region")
            assertTrue(measured.joinCorrelation.isFinite() && measured.joinCorrelation >= .85,
                "$label captured join correlation ${measured.joinCorrelation}")
            assertTrue(measured.blendDipDb.isFinite() && measured.blendDipDb >= -2.0,
                "$label captured join dips ${measured.blendDipDb} dB")
            assertTrue(measured.wrapStepRatio.isFinite() && measured.wrapStepRatio in 0.0..1.5,
                "$label captured wrap step ratio ${measured.wrapStepRatio}")
            assertTrue(measured.wrapSlopeRatio.isFinite() && measured.wrapSlopeRatio in 0.0..1.5,
                "$label captured wrap slope ratio ${measured.wrapSlopeRatio}")
            val physicalError = relativeEnergy(measured.physicalLoop, measured.rawNextCycle)
            assertEquals(physicalError, measured.rawContinuationError, 1e-12,
                "$label replaced its unmodified physical continuation error with a seam/copy metric")
        } else {
            assertEquals("physical-periodic", measured.sustainMethod)
            assertFalse(measured.appliedBlend, "$label repaired the loop instead of measuring continued state")
            assertEquals(0, measured.blendFrames)
            assertTrue(rawError < 1e-3, "$label actual raw following cycle differs: $rawError")
            assertTrue(finalError < 1e-3, "$label actual final following cycle differs: $finalError")
            assertTrue(measured.rawContinuationError.isFinite() && measured.rawContinuationError < 1e-3,
                "$label physical-state cycle error ${measured.rawContinuationError}")
            assertTrue(measured.continuationError.isFinite() && measured.continuationError < 1e-3,
                "$label final cycle diagnostic ${measured.continuationError}")
        }
        // For a captured bow this checks manufactured playback continuity only. Its
        // separate unmodified physical/future arrays above are allowed to disagree.
        assertTrue(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR, "$label stored playback seam $seam")

        // Decode at the same frame position carried by KeyNote/layer metadata. This
        // checks PCM quantization against the real preceding cycle, not a copied loop.
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
        val decoded = WavReader.read(bytes)
        val pcmSeam = Keys.seamError(decoded.samples, measured.loopStart)
        assertEquals(note.snip.frameCount, decoded.frameCount)
        assertTrue(pcmSeam.isFinite() && pcmSeam < Keys.MAX_SEAM_ERROR, "$label decoded PCM16 seam $pcmSeam")
        if (capturedBow) assertCapturedWrap(decoded.samples.copyOfRange(measured.loopStart, decoded.samples.size), label)
        println("OHM held $label ${measured.sustainMethod}: ${measured.settledCycles} source cycles, ${measured.loopFrames} frames, raw $rawError, final $finalError, seam $seam, PCM16 $pcmSeam")
    }

    /** Inspect decoded playback movements independently of the recorded blend diagnostics. */
    private fun assertCapturedWrap(loop: FloatArray, label: String) {
        val ordinaryStep = (1 until loop.size).maxOf { abs(loop[it].toDouble() - loop[it - 1]) }
        val ordinaryBend = (2 until loop.size).maxOf { abs(loop[it].toDouble() - 2 * loop[it - 1] + loop[it - 2]) }
        val across = loop.first().toDouble() - loop.last()
        val before = loop.last().toDouble() - loop[loop.lastIndex - 1]
        val after = loop[1].toDouble() - loop[0]
        assertTrue(abs(across) <= ordinaryStep * 1.5 + 2.0 / 32768,
            "$label decoded captured loop has an exceptional wrap step")
        assertTrue(maxOf(abs(across - before), abs(after - across)) <= ordinaryBend * 1.5 + 4.0 / 32768,
            "$label decoded captured loop has an exceptional wrap slope change")
    }

    private fun options(exciter: OhmExciter): OhmOptions = OhmOptions(
        exciter = exciter,
        capture = if (exciter == OhmExciter.CAPTURED) capture() else null,
    )

    private fun capture(): OhmCapture {
        val hz = Keys.midiHz(60).toDouble()
        val source = FloatArray((.24 * Dsp.RATE).toInt()) { i ->
            val time = i.toDouble() / Dsp.RATE
            ((.5 * sin(2 * PI * hz * time) + .2 * sin(6 * PI * hz * time) +
                .1 * sin(14 * PI * hz * time)) * exp(-8 * time)).toFloat()
        }
        return OhmCapture.fromSnip(Snip(source, 1, Dsp.RATE), label = "OHM held test")
    }

    /** Hann-windowed power shares remove uniform gain as an explanation for profile changes. */
    private fun harmonicProfile(snip: Snip): DoubleArray {
        val size = 8192
        val root = Keys.midiHz(60).toDouble()
        val edges = doubleArrayOf(20.0, root * sqrt(2.0), root * 2 * sqrt(2.0),
            root * 4 * sqrt(2.0), root * 8 * sqrt(2.0), 12_000.0)
        val power = DoubleArray(edges.size - 1)
        var offset = (.05 * snip.sampleRate).toInt()
        val until = minOf(snip.frameCount, (.95 * snip.sampleRate).toInt())
        while (offset + size <= until) {
            val real = FloatArray(size) { i ->
                (snip.samples[offset + i] * (.5 - .5 * cos(2 * PI * i / (size - 1)))).toFloat()
            }
            val imaginary = FloatArray(size)
            Fft.forward(real, imaginary)
            for (bin in 1 until size / 2) {
                val hz = bin.toDouble() * snip.sampleRate / size
                val energy = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                for (band in power.indices) if (hz >= edges[band] && hz < edges[band + 1]) power[band] += energy
            }
            offset += size / 2
        }
        val total = power.sum()
        assertTrue(total.isFinite() && total > 1e-20, "spectral fixture has no measurable power")
        return DoubleArray(power.size) { power[it] / total }
    }

    private fun relativeEnergy(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        val energy = a.sumOf { it.toDouble() * it }
        assertTrue(energy > 1e-24, "continuation comparison has no reference signal")
        val difference = a.indices.sumOf { i -> val delta = a[i].toDouble() - b[i]; delta * delta }
        return difference / energy
    }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
}
