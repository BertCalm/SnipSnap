package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fixed-alignment HOLD gates. The previous cycle is rendered through real source/acoustic/output states. */
class MeridianHeldTest {
    private val voice = MeridianVoice.SWEEP

    @Test
    fun `model one keeps pre-HOLD finite raw bits including recipes without a model field`() {
        // Pinned from the public model-1 renderer before the model-2 work, recorded
        // by /workspace/meridian-speed-baseline.json. Hashes use float raw bits LE.
        val cases = listOf(
            Triple(MeridianPatch("Legacy default", voice, emptyMap(), model = 1), 129813,
                "b67ddbce05d846eb357b1a47d4d81d3cf167bb12f0129d83a4e42c15c12cf8b5"),
            Triple(MeridianPatch("Legacy finite top", voice, mapOf("HOLD" to 1f), model = 1), 218013,
                "64ccf3bba81ed02e29448f998e7709be7bcaed55b130a56c55d9a0f7f8f28c17"),
            Triple(MeridianPatch("Legacy seeded", voice, mapOf("MEMORY" to .7f, "SURFACE" to .2f),
                midi = 51, velocity = .65f, model = 1, seedContext = 41), 164951,
                "5e436034d149134b5644195ab47358aff95b060c82dc84754d73ac44f268b168"),
        )
        for ((patch, frames, hash) in cases) {
            val rendered = patch.render()
            assertEquals(frames, rendered.frameCount, patch.name)
            assertEquals(hash, rawBitsHash(rendered.samples), "${patch.name} changed its original finite waveform")
        }
        val legacy = cases[1].first
        val missingModel = JsonValue.Obj(LinkedHashMap(legacy.toJsonValue().entries).apply { remove("model") })
        val decoded = MeridianPatch.fromJsonValue(missingModel) as MeridianPatch
        assertEquals(1, decoded.model, "old recipes must not silently acquire held semantics")
        assertFalse(Meridian.isLoop(decoded.macros, decoded.model))
        assertEquals(cases[1].third, rawBitsHash(decoded.render().samples))
    }

    @Test
    fun `the model two top step selects a loop while lower settings retain finite excitation`() {
        assertEquals(2, MeridianPatch("New", voice, emptyMap()).model)
        assertFalse(Meridian.isLoop(emptyMap(), 2))
        assertFalse(Meridian.isLoop(mapOf("HOLD" to .974f), 2))
        assertTrue(Meridian.isLoop(mapOf("HOLD" to .975f), 2))
        assertTrue(Meridian.isLoop(mapOf("HOLD" to 1f), 2))
        assertFalse(Meridian.isLoop(mapOf("HOLD" to 1f), 1))
        assertFalse(Meridian.isLoop(mapOf("HOLD" to Float.NaN), 2))
        val finite = MeridianPatch("Same finite gesture", voice, mapOf("HOLD" to .4f, "MEMORY" to .6f),
            midi = 51, velocity = .7f, model = 1, seedContext = 17)
        assertContentEquals(finite.render().samples, finite.copy(model = 2).render().samples,
            "model 2 needlessly changed finite noise or tuning below the held step")
    }

    @Test
    fun `held recipes regenerate byte identically and explicitly freeze the traversal alignment`() {
        val patch = MeridianPatch("Held ring", voice, mapOf("HOLD" to 1f, "LATITUDE" to .8f, "MEMORY" to .7f),
            midi = 57, velocity = .65f, seedContext = -29)
        val held = Meridian.renderHeld(voice, patch.macros, patch.midi, patch.velocity, patch.seedContext)
        val roundTrip = Patches.fromJsonText(patch.toJsonText()) as MeridianPatch
        assertEquals(patch, roundTrip)
        assertContentEquals(held.snip.samples, roundTrip.render().samples)
        val reversedLatitude = Meridian.renderHeld(voice, patch.macros + ("LATITUDE" to .2f),
            patch.midi, patch.velocity, patch.seedContext)
        // LATITUDE chooses the plane reached after the finite gesture's first
        // two seconds. That selected plane is then fixed throughout the loop.
        val expectedNorth = MeridianMotion.INITIAL_ORIENTATION -
            2 * MeridianMotion.MAX_PRECESSION * sin(PI * (.8f.toDouble() - .5))
        val expectedSouth = MeridianMotion.INITIAL_ORIENTATION -
            2 * MeridianMotion.MAX_PRECESSION * sin(PI * (.2f.toDouble() - .5))
        assertEquals(expectedNorth, held.fixedOrientation, 1e-12)
        assertEquals(expectedSouth, reversedLatitude.fixedOrientation, 1e-12)
        assertFalse(held.snip.samples.contentEquals(reversedLatitude.snip.samples),
            "LATITUDE never selected a different held material alignment")
    }

    @Test
    fun `actual consecutive cycles close source acoustic and conditioned output states`() {
        val held = Meridian.renderHeld(voice, mapOf("HOLD" to 1f, "LENGTH" to .35f, "MEMORY" to .75f),
            midi = 57, velocity = .8f, seedContext = 17)
        val samples = held.snip.samples
        val start = held.loopStartFrame
        val end = held.loopEndFrameExclusive
        assertTrue(start >= 256)
        assertEquals(start * 2, end, "keep the actual previous cycle with the exported cycle")
        assertEquals(end, held.snip.frameCount)
        assertEquals(start, held.loopFrames)
        assertEquals(start.toDouble() / Dsp.RATE / held.swings, held.effectiveSwingPeriodSeconds, 1e-12)
        assertTrue(start.toDouble() / Dsp.RATE in .75..1.5, "held work escaped its bounded cycle duration")
        assertTrue(held.seamError.isFinite() && held.seamError < Keys.MAX_SEAM_ERROR)
        assertTrue(held.cycleError.isFinite() && held.cycleError < 1e-3,
            "seam endpoints concealed an unconverged complete cycle: ${held.cycleError}")
        assertTrue(held.stateError.isFinite() && held.stateError < 1e-3,
            "acoustic state failed to return: ${held.stateError}")
        assertTrue(held.noiseStateError.isFinite() && held.noiseStateError < 1e-9,
            "periodic roughness filter state failed to return: ${held.noiseStateError}")
        assertEquals(held.seamError, Keys.seamError(samples, start), 1e-12,
            "reported seam does not describe the actual retained previous cycle")
        val fullCycleError = cycleDifference(samples, start, skip = 256)
        assertTrue(fullCycleError < 1e-3, "conditioning left different successive cycles: $fullCycleError")
        assertTrue(rms(samples, start - 256, start) > 1e-5, "silent wrap made the seam metric vacuous")
        val corruptPrevious = samples.copyOf()
        for (i in start - 256 until start) corruptPrevious[i] = -corruptPrevious[i]
        assertTrue(Keys.seamError(corruptPrevious, start) > 1,
            "the seam test did not notice a broken actual preceding cycle")
        println("MERIDIAN HOLD cycle=${start.toDouble() / Dsp.RATE}s seam=${held.seamError} " +
            "cycle=${held.cycleError} measuredCycle=$fullCycleError state=${held.stateError}")
    }

    @Test
    fun `held cycles keep the requested root across low long-memory and high bright corners`() {
        val cases = listOf(
            36 to mapOf("HOLD" to 1f, "LENGTH" to 0f, "REACH" to 1f, "MEMORY" to 1f),
            57 to mapOf("HOLD" to 1f, "LENGTH" to 1f, "REACH" to 0f, "MEMORY" to 0f),
            96 to mapOf("HOLD" to 1f, "TUNE" to 1f, "SURFACE" to 1f, "MEMORY" to .8f),
        )
        for ((midi, macros) in cases) {
            val held = Meridian.renderHeld(voice, macros, midi, velocity = .8f, seedContext = 11)
            val samples = held.snip.samples
            assertEquals(1, held.snip.channels)
            assertEquals(Dsp.RATE, held.snip.sampleRate)
            assertTrue(samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(abs(samples.average()) < .005, "MIDI $midi held DC ${samples.average()}")
            val loudness = Loudness.of(held.snip)
            assertTrue(loudness >= Dsp.MELODIC_LOUDNESS_TARGET * .9 || held.snip.peak() >= .95f,
                "MIDI $midi held loop missed house level: $loudness")
            assertTrue(held.seamError.isFinite() && held.seamError < 1e-3, "MIDI $midi seam ${held.seamError}")
            assertTrue(held.cycleError.isFinite() && held.cycleError < 1e-3)
            assertTrue(held.stateError.isFinite() && held.stateError < 1e-3)
            assertTrue(held.noiseStateError.isFinite() && held.noiseStateError < 1e-9)
            assertTrue(held.rawPeak.isFinite() && held.rawPeak < 8.0)
            assertTrue(held.peakEnergy.isFinite() && held.peakEnergy < 100.0)
            assertTrue(held.activeSurfaces in 2..6, "fixed contact unexpectedly excited ${held.activeSurfaces} surfaces")
            assertTrue(abs(held.rootCents) < .05,
                "fitting the force grid moved the requested root by ${held.rootCents} cents")
            val hz = Meridian.frequencyFor(voice, macros["TUNE"] ?: .5f, midi)
            val from = held.loopStartFrame.toFloat() / Dsp.RATE
            val cents = FineTuning.cents(FineTuning.measuredHz(samples, Dsp.RATE, hz, from, .5f), hz.toDouble())
            assertTrue(abs(cents) < 25.0, "MIDI $midi held root moved $cents cents")
            println("MERIDIAN HOLD MIDI $midi root=$cents cents, loudness=$loudness " +
                "seam=${held.seamError}, cycle=${held.cycleError}, state=${held.stateError}, " +
                "rawPeak=${held.rawPeak}, energy=${held.peakEnergy}, gridCents=${held.rootCents}")
        }
    }

    @Test
    fun `the actual conditioned previous cycle still closes after PCM16 encoding`() {
        val held = Meridian.renderHeld(voice, mapOf("HOLD" to 1f, "MEMORY" to .7f), midi = 51,
            velocity = .65f, seedContext = 41)
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, held.snip, WavWriter.BitDepth.PCM_16) }
        val decoded = WavReader.read(bytes.toByteArray())
        assertEquals(held.snip.frameCount, decoded.frameCount)
        assertTrue(Keys.seamError(decoded.samples, held.loopStartFrame) < Keys.MAX_SEAM_ERROR,
            "PCM export broke the independently evolved cycle seam")
    }

    @Test
    fun `held zero velocity is silent and cancellation interrupts periodic preparation`() {
        val silent = Meridian.renderHeld(voice, mapOf("HOLD" to 1f), velocity = 0f)
        assertTrue(silent.snip.samples.all { it == 0f })
        assertEquals(0.0, silent.seamError)
        assertEquals(0.0, silent.cycleError)
        assertEquals(0.0, silent.stateError)
        assertTrue(silent.noiseStateError.isFinite() && silent.noiseStateError < 1e-9)
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException> {
                Meridian.renderHeld(voice, mapOf("HOLD" to 1f, "MEMORY" to 1f))
            }
        } finally {
            Thread.interrupted()
        }
    }

    private fun rawBitsHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun rms(samples: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from))
    }

    private fun cycleDifference(samples: FloatArray, start: Int, skip: Int): Double {
        var difference = 0.0
        var energy = 0.0
        for (i in skip until start) {
            val a = samples[i].toDouble()
            val b = samples[start + i].toDouble()
            difference += (a - b) * (a - b)
            energy += a * a
        }
        return difference / energy.coerceAtLeast(1e-30)
    }
}
