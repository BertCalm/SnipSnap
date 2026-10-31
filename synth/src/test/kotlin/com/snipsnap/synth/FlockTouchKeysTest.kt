package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlockTouchKeysTest {
    private data class Example(val patch: FlockPatch, val bank: FlockTouchKeys.Bank)

    companion object {
        private val examples by lazy {
            val traveling = FlockPresets.all().single { it.name == "Traveling Turn" }
                .copy(playback = FlockPlayback.HELD)
            listOf(traveling, traveling.copy(model = 1, seed = Long.MIN_VALUE + 42001L,
                macros = traveling.macros + ("FLOW" to .35f)),
                traveling.copy(model = 2, seed = Long.MIN_VALUE + 42001L,
                    macros = traveling.macros + ("FLOW" to .35f)))
                .map { Example(it, FlockTouchKeys.probe(it)) }
        }
    }

    @Test
    fun `physical touch changes spectral motion after gain is removed`() {
        for ((patch, bank) in examples) {
            assertEquals(FlockTouchKeys.TOUCHES, bank.layers.map { it.touch })
            assertEquals(FlockTouchKeys.VELOCITY_WINDOWS, bank.layers.map { it.velocityWindow })
            for (layer in bank.layers) assertEquals(patch.velocity * layer.touch, layer.excitationVelocity)
            for ((soft, hard) in bank.layers.zipWithNext()) {
                // Normalized power spectra cannot be changed by making the same
                // sample louder. Use several early/body windows, independently of
                // the endpoint that each disturbance selects for its eventual loop.
                val distances = listOf(.10, 1.0, 2.5, 4.5).map { from ->
                    variation(profile(soft.note.snip.samples, from), profile(hard.note.snip.samples, from))
                }
                assertTrue(distances.average() > .005,
                    "model${patch.model} touches ${soft.touch}/${hard.touch} only changed gain: $distances")
                println("FLOCK TOUCH SPECTRUM model=${patch.model} seed=${patch.seed} " +
                    "pair=${soft.touch}/${hard.touch} normalizedPowerTV=$distances")
            }
        }
    }

    @Test
    fun `sustain matches the accepted hard capture before the existing touch gain`() {
        for ((patch, bank) in examples) {
            val originalHard = FlockHeld.render(patch)
            val hardLevel = loopLevel(KeyNote(originalHard.snip, originalHard.loopStartFrame.toLong()))
            val target = hardLevel * bank.sharedHeadroomGain
            val levels = bank.layers.map { layer ->
                val samples = layer.note.snip.samples
                val level = loopLevel(layer.note)
                assertEquals(target, level, target * 3e-5,
                    "model${patch.model} unequal sustain for touch${layer.touch}")
                assertTrue(layer.levelGain.isFinite() && layer.levelGain > 0.0)
                assertTrue(samples.all { it.isFinite() && abs(it) <= .900001f })
                assertTrue(abs(samples.average()) < .005)
                level
            }
            assertEquals(1.0, bank.layers.last().levelGain)
            val matchedPeak = bank.layers.maxOf { layer ->
                val source = FlockHeld.render(patch.copy(velocity = layer.excitationVelocity))
                source.snip.samples.maxOf { abs((it.toDouble() * layer.levelGain).toFloat()).toDouble() }
            }
            val expectedHeadroom = if (matchedPeak > .90) .90 / matchedPeak else 1.0
            assertEquals(expectedHeadroom, bank.sharedHeadroomGain)
            if (bank.sharedHeadroomGain == 1.0) assertContentEquals(originalHard.snip.samples,
                bank.layers.last().note.snip.samples, "accepted hard PCM changed without a headroom reason")
            val firstBoundary = 20.0 * log10((levels[1] * 43) / (levels[0] * 42))
            val secondBoundary = 20.0 * log10((levels[2] * 85) / (levels[1] * 84))
            assertEquals(20.0 * log10(43.0 / 42.0), firstBoundary, .001)
            assertEquals(20.0 * log10(85.0 / 84.0), secondBoundary, .001)
            println("FLOCK TOUCH LEVEL model=${patch.model} sustain=$levels " +
                "sharedHeadroom=${bank.sharedHeadroomGain} boundaryDb=$firstBoundary/$secondBoundary")
        }
    }

    @Test
    fun `each complete attack blend and source context survives only two uniform gains`() {
        for ((patch, bank) in examples) for (layer in bank.layers) {
            val source = FlockHeld.render(patch.copy(velocity = layer.excitationVelocity))
            val note = layer.note
            assertEquals(source.diagnostics, layer.sourceDiagnostics,
                "source diagnostics must describe the original held capture")
            assertEquals(source.loopStartFrame.toLong(), note.loopStartFrame)
            assertEquals(source.loopEndExclusive, note.snip.frameCount)
            assertEquals(2L * Dsp.RATE, note.loopStartFrame)
            val points = (source.snip.samples.indices step 179).toList() +
                listOf(source.loopStartFrame - 1, source.loopStartFrame, source.loopStartFrame + 1,
                    source.snip.samples.lastIndex - 1, source.snip.samples.lastIndex)
            for (i in points) {
                val matched = (source.snip.samples[i].toDouble() * layer.levelGain).toFloat()
                val expected = (matched.toDouble() * bank.sharedHeadroomGain).toFloat()
                assertEquals(expected, note.snip.samples[i], "model${patch.model} touch${layer.touch} frame$i")
            }
            assertEquals(0f, note.snip.samples.first())
            assertTrue(abs(note.snip.samples.last()) > 1e-8, "sample received a baked release fade")
        }
    }

    @Test
    fun `source caches remain untouched and every saved model and full seed replays deterministically`() {
        for ((patch, bank) in examples) {
            val saved = patch.toJsonText()
            val replay = FlockTouchKeys.render(FlockPatch.fromJsonText(saved))
            val renamed = FlockTouchKeys.render(patch.copy(name = "Rename", releaseSeconds = 1.2f,
                macros = patch.macros + ("HOLD" to 0f)))
            for ((i, layer) in bank.layers.withIndex()) {
                assertContentEquals(layer.note.snip.samples, replay[i].snip.samples)
                assertContentEquals(layer.note.snip.samples, renamed[i].snip.samples)
                val sourcePatch = patch.copy(velocity = layer.excitationVelocity)
                val source = FlockHeld.render(sourcePatch).snip.samples
                replay[i].snip.samples.fill(Float.NaN)
                assertContentEquals(source, FlockHeld.render(sourcePatch).snip.samples,
                    "touch caller mutated a source-cache entry")
            }
            assertEquals(saved, patch.toJsonText(), "render rewrote source state")
        }
        val one = examples[1].bank.layers.last().note.snip.samples
        val two = examples[2].bank.layers.last().note.snip.samples
        assertTrue(!one.contentEquals(two), "off-default source models were silently collapsed")
    }

    @Test
    fun `narrow flock roots remain pitched at the register limits for every touch`() {
        val patch = examples.first().patch.copy(macros = Flock.defaults(FlockVoice.RIBBON) +
            mapOf("SPREAD" to 0f, "PULL" to 1f, "SCATTER" to 0f, "EDGE" to 0f))
        for (midi in listOf(36, 84)) for (note in FlockTouchKeys.render(patch, midi)) {
            val estimate = assertNotNull(Pitch.detect(note.snip, fromSec = 2.5f, windowSec = .4f))
            val cents = 1200 * ln(estimate.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(estimate.confidence >= .5f)
            assertTrue(abs(cents) < 25, "MIDI$midi touch root drifted $cents cents")
            assertTrue(note.snip.samples.all { it.isFinite() && abs(it) <= .900001f })
            assertTrue(abs(note.snip.samples.average()) < .005)
        }
    }

    @Test
    fun `zero and subnormal anchors stay finite and cancellation can stop cached scaling`() {
        val patch = examples.first().patch
        for (velocity in listOf(0f, Float.MIN_VALUE, java.lang.Float.MIN_NORMAL * .5f)) {
            val bank = FlockTouchKeys.probe(patch.copy(velocity = velocity))
            assertTrue(bank.sharedHeadroomGain.isFinite())
            for (layer in bank.layers) {
                assertTrue(layer.levelGain.isFinite())
                assertTrue(layer.note.loopStartFrame > 0)
                assertTrue(layer.note.snip.samples.all { it.isFinite() && abs(it) <= .900001f })
                if (velocity == 0f) assertTrue(layer.note.snip.samples.all { it == 0f })
            }
        }
        assertFailsWith<CancellationException> { FlockTouchKeys.render(patch, cancelled = { true }) }
        // Warm all source captures, then interrupt the matching/scaling stage.
        FlockTouchKeys.render(patch)
        var polls = 0
        assertFailsWith<CancellationException> {
            FlockTouchKeys.render(patch, cancelled = { ++polls > 20 })
        }
        assertTrue(polls > 20)
        val unsupported = assertFailsWith<IllegalArgumentException> {
            FlockTouchKeys.render(patch.copy(playback = FlockPlayback.FINITE))
        }
        assertTrue(unsupported.message.orEmpty().contains("held RIBBON"))
        val outside = assertFailsWith<IllegalArgumentException> { FlockTouchKeys.render(patch, 85) }
        assertTrue(outside.message.orEmpty().contains("MIDI"))
    }

    private fun loopLevel(note: KeyNote): Double = Loudness.of(
        note.snip.samples.copyOfRange(note.loopStartFrame.toInt(), note.snip.samples.size),
        channels = 1, sampleRate = Dsp.RATE).toDouble()

    private fun variation(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) } * .5

    private fun profile(samples: FloatArray, from: Double): DoubleArray {
        val size = 32768
        val count = 16384
        val start = (from * Dsp.RATE).toInt()
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        for (i in 0 until count) real[i] = (samples[start + i] *
            (.5 - .5 * cos(2.0 * PI * i / (count - 1)))).toFloat()
        Fft.forward(real, imaginary)
        val power = DoubleArray(size / 2) { real[it].toDouble() * real[it] + imaginary[it].toDouble() * imaginary[it] }
        val total = power.sum().coerceAtLeast(1e-30)
        return DoubleArray(power.size) { power[it] / total }
    }
}
