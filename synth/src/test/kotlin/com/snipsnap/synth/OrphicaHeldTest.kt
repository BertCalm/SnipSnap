package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrphicaHeldTest {
    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    /** Judge the played boundary against normal carrier differences in each channel. */
    private fun assertWrap(snip: Snip, start: Int = 0) {
        for (channel in 0..1) {
            val samples = FloatArray(snip.frameCount - start) { snip.samples[(start + it) * 2 + channel] }
            val ordinaryStep = (1 until samples.size).maxOf { abs(samples[it] - samples[it - 1]) }
            val ordinaryKink = (2 until samples.size).maxOf {
                abs((samples[it] - samples[it - 1]) - (samples[it - 1] - samples[it - 2]))
            }
            val before = samples.last() - samples[samples.lastIndex - 1]
            val across = samples.first() - samples.last()
            val after = samples[1] - samples.first()
            assertTrue(abs(across) <= ordinaryStep * 1.1f + 1e-5f, "channel $channel has an exceptional wrap step")
            assertTrue(maxOf(abs(across - before), abs(after - across)) <= ordinaryKink * 1.2f + 1e-5f,
                "channel $channel has a wrap slope discontinuity")
        }
    }

    @Test
    fun `full HOLD pads are populated periodic stereo material even for clear dry settings`() {
        for (material in OrphicaMaterial.entries) for (mode in OrphicaMode.entries) {
            val config = OrphicaConfig(material = material, mode = mode, hold = 1f,
                fracture = if (mode == OrphicaMode.CLEAR) 0f else .75f, seed = 91743L)
            val loop = Orphica.render(config)
            assertEquals(2, loop.channels)
            assertEquals(44100, loop.sampleRate)
            assertTrue(loop.samples.all { it.isFinite() && abs(it) <= .35f }, "$material $mode invalid held output")
            assertTrue(rms(loop.samples) > .01, "$material $mode captured silence")
            assertTrue((0 until loop.frameCount).any { abs(loop.samples[it * 2] - loop.samples[it * 2 + 1]) > 1e-5f },
                "$material $mode lost its stereo sustain")
            assertWrap(loop)
        }
    }

    @Test
    fun `finite HOLD adds continuous captured sustain and releases instead of marking a loop`() {
        val config = OrphicaConfig(mode = OrphicaMode.CLEAR, fracture = 0f, damping = .6f, seconds = 4f)
        var previousLevel = 0.0
        for (hold in listOf(.2f, .55f, .9f)) {
            val held = Orphica.renderHeld(config.copy(hold = hold))
            assertEquals(0L, held.loopStartFrame)
            assertEquals((4f * Dsp.RATE).toInt(), held.snip.frameCount)
            val late = held.snip.samples.copyOfRange((2.5f * Dsp.RATE).toInt() * 2, (3.2f * Dsp.RATE).toInt() * 2)
            val level = rms(late)
            assertTrue(level > previousLevel + .005, "HOLD $hold did not strengthen captured sustain")
            previousLevel = level
            assertTrue(held.snip.samples.last() == 0f, "finite hold has no release")
            val attackSamples = (Orphica.ATTACK_SECONDS * Dsp.RATE).toInt() * 2
            assertContentEquals(Orphica.render(config).samples.copyOf(attackSamples), held.snip.samples.copyOf(attackSamples))
        }
        assertTrue(rms(Orphica.render(config).samples.copyOfRange(Dsp.RATE * 5, Dsp.RATE * 6)) < previousLevel * .1,
            "fixture's original dry tail still dominates the sustain")
        assertTrue(!Orphica.isLoop(.98f) && Orphica.isLoop(.99f) && Orphica.isLoop(1f))
    }

    @Test
    fun `key notes retain the pluck then enter the same settled loop the pad plays`() {
        val config = OrphicaConfig(mode = OrphicaMode.REVERSE, hold = 1f, midi = 63, seed = 987654321L)
        val key = Keys.orphicaPad(config, midi = 67)
        assertTrue(key.loopStartFrame > 0 && key.loopStartFrame < key.snip.frameCount)
        val resolved = config.copy(midi = 67)
        val pad = Orphica.render(resolved)
        val loopStart = key.loopStartFrame.toInt()
        assertEquals(pad.frameCount, key.snip.frameCount - loopStart)
        assertContentEquals(pad.samples, key.snip.samples.copyOfRange(loopStart * 2, key.snip.samples.size))
        val attack = (Orphica.ATTACK_SECONDS * Dsp.RATE).toInt() * 2
        assertContentEquals(Orphica.render(resolved.copy(hold = 0f)).samples.copyOf(attack), key.snip.samples.copyOf(attack))
        assertWrap(key.snip, loopStart)
        assertContentEquals(key.snip.samples, Keys.orphicaPad(config, midi = 67).snip.samples)
    }

    @Test
    fun `the independently evaluated circular process and PCM boundary both close`() {
        val config = OrphicaConfig(material = OrphicaMaterial.STEEL, mode = OrphicaMode.SCATTER,
            grainSizeMs = 120f, grainRateHz = 100f, scatter = 1f, fracture = 1f, hold = 1f, seed = Long.MIN_VALUE)
        val actual = Orphica.heldMaterial(config)
        assertTrue(actual.cycle.indices.maxOf { abs(actual.cycle[it] - actual.nextCycle[it]) } < 1e-6f,
            "an independently evaluated second cycle changes the carrier or wrapped grains")
        assertTrue(actual.cycle !== actual.nextCycle, "the periodicity check received the same array twice")
        val original = Snip(actual.cycle, 2, Dsp.RATE)
        assertWrap(original)
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, original) }.toByteArray()
        val reread = WavReader.read(bytes)
        assertWrap(reread)
        assertContentEquals(bytes, ByteArrayOutputStream().also { WavWriter.write(it, Orphica.render(config)) }.toByteArray())
        // Play three cycles from decoded PCM and judge both real wrap positions.
        val played = FloatArray(reread.samples.size * 3) { reread.samples[it % reread.samples.size] }
        for (turn in 1..2) for (channel in 0..1) {
            val boundary = turn * reread.samples.size + channel
            val jump = abs(played[boundary] - played[boundary - 2])
            val normal = (channel + 2 until reread.samples.size step 2).maxOf {
                abs(reread.samples[it] - reread.samples[it - 2])
            }
            assertTrue(jump < normal * 1.1f + 1e-5f, "PCM playback clicks on wrap $turn channel $channel")
        }
    }

    @Test
    fun `held material keeps the requested MIDI root and audible seeded articulation`() {
        for (material in OrphicaMaterial.entries) for (midi in listOf(36, 60, 84)) {
            val config = OrphicaConfig(material = material, mode = OrphicaMode.SCATTER, midi = midi, hold = 1f, fracture = .5f)
            val loop = Orphica.render(config)
            val mono = Snip(FloatArray(loop.frameCount) { loop.samples[it * 2] }, 1, Dsp.RATE)
            val hz = Keys.midiHz(midi)
            val cents = FineTuning.cents(FineTuning.measuredHz(mono, hz, .05f, .25f), hz.toDouble())
            assertTrue(abs(cents) < 5, "$material MIDI $midi held pitch misses by $cents cents")
            val soft = Orphica.render(config.copy(velocity = .3f))
            assertTrue(rms(loop.samples) > rms(soft.samples) * 2f, "$material held velocity lost excitation strength")
        }
    }

    @Test
    fun `short low captures wait for written periods and late high captures retain populated sound`() {
        for (midi in listOf(Orphica.MIDI_MIN, 23, Orphica.MIDI_MAX)) for (capture in listOf(.03f, .95f)) {
            val config = OrphicaConfig(midi = midi, captureSeconds = capture, seconds = 1.2f,
                mode = OrphicaMode.CLEAR, damping = 1f, fracture = 0f, hold = 1f,
                grainSizeMs = 8f, grainRateHz = 2f, seed = Long.MAX_VALUE)
            val held = Orphica.heldMaterial(config)
            assertTrue(held.captureFrame >= (3 * Dsp.RATE / Keys.midiHz(midi)).toInt(), "capture reads an unwritten period")
            assertTrue(held.captureFrame < held.source.size)
            assertTrue(rms(held.cycle) > .005, "MIDI $midi capture $capture froze silence")
            assertTrue(abs(held.grainRateHz - config.grainRateHz) / config.grainRateHz < .02,
                "the musical loop fit changes the requested grain rate excessively")
            assertWrap(Snip(held.cycle, 2, Dsp.RATE))
        }
        assertTrue(Orphica.render(OrphicaConfig(hold = 1f, velocity = 0f)).samples.all { it == 0f })
    }

    @Test
    fun `cancellation stops finite and held renders before and during work`() {
        for (hold in listOf(0f, .5f, 1f)) {
            val config = OrphicaConfig(hold = hold)
            assertFailsWith<CancellationException> { Orphica.render(config) { true } }
            var checks = 0
            assertFailsWith<CancellationException> { Orphica.render(config) { ++checks > 15 } }
            assertTrue(checks > 15)
        }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { Keys.orphicaPad(OrphicaConfig(hold = 1f)) }
        } finally {
            Thread.interrupted()
        }
    }
}
