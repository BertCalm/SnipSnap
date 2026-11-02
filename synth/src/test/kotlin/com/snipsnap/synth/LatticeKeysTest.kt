package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatticeKeysTest {
    private data class Example(val midi: Int, val speed: Float, val probe: LatticeKeyProbe)

    companion object {
        private fun patch(speed: Float = .5f, velocity: Float = .75f) = LatticePatch("Trace Keys",
            LatticeVoice.TRACE, Lattice.defaults(LatticeVoice.TRACE) + ("SPEED" to speed), velocity = velocity)

        // Evidence is immutable between checks; every case runs its own source and collector.
        private val examples by lazy {
            listOf(48, 60, 72).flatMap { midi -> listOf(0f, .5f, 1f).map { speed ->
                Example(midi, speed, LatticeKeys.probe(patch(speed), midi))
            } }
        }
    }

    @Test
    fun `the source keeps the accepted pluck then reaches a monotone nonzero floor`() {
        val velocity = .75f
        val attack = .025 - .013 * velocity
        assertEquals(0.0, LatticeKeys.sourceEffort(0.0, velocity), "the causal source must start at rest")
        for (i in 1..300) {
            val t = i / 1000.0
            if (t >= attack) assertEquals(exp(-t / .23), LatticeKeys.sourceEffort(t, velocity), 1e-15)
        }
        var previous = LatticeKeys.sourceEffort(.30, velocity)
        for (i in 1..1500) {
            val value = LatticeKeys.sourceEffort(.30 + i / 10_000.0, velocity)
            assertTrue(value <= previous + 1e-14, "source effort rose during transition at $i")
            assertTrue(value >= LatticeKeys.SOURCE_FLOOR - 1e-14, "source effort undershot the floor at $i")
            previous = value
        }
        assertEquals(LatticeKeys.SOURCE_FLOOR, LatticeKeys.sourceEffort(.45, velocity))
        assertEquals(LatticeKeys.SOURCE_FLOOR, LatticeKeys.sourceEffort(20.0, velocity))
        val epsilon = 1e-7
        val before = (LatticeKeys.sourceEffort(.30, velocity) - LatticeKeys.sourceEffort(.30 - epsilon, velocity)) / epsilon
        val after = (LatticeKeys.sourceEffort(.30 + epsilon, velocity) - LatticeKeys.sourceEffort(.30, velocity)) / epsilon
        assertEquals(before, after, 2e-5, "the decay slope jumped at the transition")
        assertTrue(abs((LatticeKeys.sourceEffort(.45, velocity) - LatticeKeys.sourceEffort(.45 - epsilon, velocity)) / epsilon) < 2e-5)
    }

    @Test
    fun `three roots and all speed extremes keep a causal onset and real periodic rejected sustain`() {
        for ((midi, speed, p) in examples) {
            val label = "MIDI$midi SPEED$speed"
            val samples = p.note.snip.samples
            val loop = samples.copyOfRange(p.prefixFrames, samples.size)
            assertEquals(1, p.note.snip.channels, label)
            assertEquals(Dsp.RATE, p.note.snip.sampleRate, label)
            assertEquals(p.loopFrames, loop.size, label)
            assertTrue(p.prefixFrames >= p.loopFrames, "$label discarded its onset")
            assertTrue(p.prefixSeconds > p.transitionEndSeconds, "$label loops a changing source")
            assertTrue(samples.size.toDouble() / Dsp.RATE <= LatticeKeys.MAX_SAMPLE_SECONDS, label)
            assertTrue(samples.all { it.isFinite() && abs(it) <= .99f }, label)
            assertTrue(rms(loop) > .001, "$label did not sustain actual collector output")
            val onsetRms = rms(samples.copyOfRange(441, 4410))
            // The first millisecond belongs to the direct pluck. Compare it with that
            // onset, rather than the much quieter and pitch-dependent rejected sustain.
            assertTrue(rms(samples.copyOfRange(0, 44)) < onsetRms * .02, "$label began without a quiet attack")
            assertTrue(onsetRms > .001, "$label onset was padded")
            assertTrue(p.directSustainPeak < 1e-12, "$label sustained a bypass source: ${p.directSustainPeak}")
            assertTrue(abs(samples.average()) < .005, "$label excessive DC")
            assertTrue(abs(p.pitchErrorCents) < .02, "$label root fit moved pitch ${p.pitchErrorCents} cents")
            val detected = FineTuning.measuredHz(Snip(loop, 1, Dsp.RATE), Keys.midiHz(midi), .12f, .5f)
            assertTrue(abs(FineTuning.cents(detected, p.requestedHz)) < 10.0, "$label lost root pitch: $detected")
            assertEquals(LatticeMotion.physicalTime(.45, LatticeMotion.timeScale(speed)), p.transitionEndSeconds, 1e-12)
            println("LATTICE KEYS $label prefix=${p.prefixSeconds}s loop=${p.loopFrames} " +
                "state=${p.stateError} cycle=${p.cycleError} seam=${p.seamError} " +
                "pcm16=${p.pcm16MaxCycleDifference} pcm24=${p.pcm24MaxCycleDifference} renderMs=${p.renderMillis}")
        }
        for (midi in listOf(48, 60, 72)) {
            val rendered = examples.filter { it.midi == midi }.map { it.probe.note.snip.samples }
            for (samples in rendered.drop(1)) assertContentEquals(rendered.first().copyOf(1102), samples.copyOf(1102),
                "SPEED changed the preserved first 25 ms of MIDI$midi")
        }
    }

    @Test
    fun `actual preceding cycles and native states recur through the real PCM writer`() {
        for ((midi, speed, p) in examples) {
            val samples = p.note.snip.samples
            assertContentEquals(samples.copyOfRange(p.prefixFrames - p.loopFrames, p.prefixFrames), p.previousCycle)
            assertTrue(p.loopStateStart.indices.maxOf { abs(p.loopStateStart[it] - p.loopStateEnd[it]) } < LatticeKeys.STATE_TOLERANCE)
            assertTrue(p.cycleError < LatticeKeys.CYCLE_TOLERANCE)
            assertTrue(p.seamError < Keys.MAX_SEAM_ERROR)
            val loop = samples.copyOfRange(p.prefixFrames, samples.size)
            for (depth in WavWriter.BitDepth.entries) {
                val previous = encoded(p.previousCycle, depth)
                val current = encoded(loop, depth)
                // WavReader uses the writer's positive full-scale divisor (32767/8388607).
                // Recover integer counts so decoded Float rounding cannot move the LSB bar.
                val scale = if (depth == WavWriter.BitDepth.PCM_16) 32767f else 8_388_607f
                val allowedCounts = if (depth == WavWriter.BitDepth.PCM_16) 1 else 2
                assertTrue(previous.indices.maxOf { abs((previous[it] * scale).roundToInt() - (current[it] * scale).roundToInt()) } <= allowedCounts,
                    "MIDI$midi SPEED$speed $depth lost full-cycle recurrence")
                val persisted = encoded(samples, depth)
                assertTrue(Keys.seamError(persisted, p.prefixFrames) < Keys.MAX_SEAM_ERROR,
                    "MIDI$midi SPEED$speed $depth does not close at the exported marker")
            }
        }
    }

    @Test
    fun `absolute MIDI and held key own tune and duration without rewriting the finite patch`() {
        val original = patch().copy(midi = 36, macros = patch().macros + mapOf("TUNE" to 1f, "HOLD" to 1f))
        val saved = original.toJsonText()
        val rendered = LatticeKeys.note(original, 60)
        val expected = examples.single { it.midi == 60 && it.speed == .5f }.probe.note
        assertEquals(expected.loopStartFrame, rendered.loopStartFrame)
        assertContentEquals(expected.snip.samples, rendered.snip.samples)
        assertEquals(saved, original.toJsonText())
        assertTrue(LatticeKeys.supports(original))
        for (voice in LatticeVoice.entries.filter { it != LatticeVoice.TRACE }) {
            val other = LatticePatch("Static", voice, Lattice.defaults(voice))
            assertFalse(LatticeKeys.supports(other))
            assertFailsWith<IllegalArgumentException> { LatticeKeys.note(other) }
        }
        for (midi in listOf(47, 73)) assertFailsWith<IllegalArgumentException> { LatticeKeys.note(original, midi) }
    }

    @Test
    fun `velocity silence is valid and sample layers remove only the host gain once`() {
        for (velocity in listOf(0f, .000001f, .2f, 1f)) {
            val patch = patch(velocity = velocity)
            val native = LatticeKeys.note(patch)
            val layer = LatticeKeys.layer(patch)
            assertEquals(native.loopStartFrame, layer.loopStartFrame)
            assertTrue(layer.snip.samples.all { it.isFinite() && abs(it) <= .99f }, "velocity $velocity layer headroom")
            if (velocity == 0f) {
                assertTrue(native.snip.samples.all { it == 0f })
                assertTrue(layer.snip.samples.all { it == 0f })
            } else {
                val gain = velocity.toDouble().pow(.72).toFloat()
                for (i in native.snip.samples.indices step 97) assertEquals(native.snip.samples[i] / gain,
                    layer.snip.samples[i], "velocity timbre/sample changed at frame $i")
            }
        }
    }

    @Test
    fun `the frozen finite B remains exact and expensive held generation stays cancellable`() {
        val finite = patch().render().samples
        val bytes = ByteBuffer.allocate(finite.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        finite.forEach { bytes.putFloat(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
        assertEquals("fc748c8679d79173cc15b2fe2718dcf56ba343f7bbb1465be8b5cd0ef64acb67", hash)
        assertFailsWith<CancellationException> { LatticeKeys.note(patch(), cancelled = { true }) }
        var polls = 0
        assertFailsWith<CancellationException> { LatticeKeys.note(patch(), cancelled = { ++polls >= 200 }) }
        assertTrue(polls <= 200)
        val ordinary = patch().render().samples
        assertContentEquals(finite, ordinary, "held setup changed subsequent finite rendering")
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun encoded(samples: FloatArray, depth: WavWriter.BitDepth): FloatArray =
        WavReader.read(ByteArrayOutputStream().also { WavWriter.write(it, Snip(samples, 1, Dsp.RATE), depth) }.toByteArray()).samples
}
