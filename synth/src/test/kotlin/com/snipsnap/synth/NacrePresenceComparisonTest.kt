package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prove source preservation, separated finite returns and a single anchored gain, not taste. */
class NacrePresenceComparisonTest {
    companion object {
        private val clips by lazy { NacrePresenceComparison.render() }
    }

    @Test
    fun `two independent studies preserve the published source and produce identical returns`() {
        val again = NacrePresenceComparison.render()
        val reference = Json.parse(checkNotNull(javaClass.getResourceAsStream(NacrePresenceComparison.REFERENCE_RESOURCE))
            .bufferedReader().use { it.readText() }).obj()
        val original = clips.first()
        assertEquals(reference.getValue("prelevelSamplesSha256").str(), samplesHash(original.raw))
        assertEquals(reference.getValue("nativeSamplesSha256").str(), samplesHash(original.native.samples))
        assertEquals(listOf("original", "coral", "seaweed"), clips.map { it.id })
        for ((a, b) in clips.zip(again)) {
            assertContentEquals(a.raw, b.raw)
            assertContentEquals(a.returnSamples, b.returnSamples)
            assertContentEquals(original.native.samples, a.native.samples)
            assertEquals(a.returnGain, b.returnGain)
            assertEquals(original.events, a.events)
            assertEquals(original.patch.macros, a.patch.macros)
            assertEquals(original.patch.midi, a.patch.midi)
            assertEquals(original.patch.seed, a.patch.seed)
            assertEquals(1, a.patch.model)
            assertEquals(a.patch, NacrePatch.fromJsonText(a.patch.toJsonText()))
            for (stem in listOf("bass", "upper", "sympathetic")) {
                assertContentEquals(original.stems.getValue(stem), a.stems.getValue(stem))
            }
            assertContentEquals(original.returnSamples, a.originalReturn)
            var maxForegroundError = 0.0
            for (i in a.raw.indices) {
                val expected = original.raw[i].toDouble() - original.returnSamples[i]
                val actual = a.raw[i].toDouble() - a.returnSamples[i]
                maxForegroundError = maxOf(maxForegroundError, abs(expected - actual))
            }
            assertTrue(maxForegroundError < 5e-8, "${a.id} changed the original foreground: $maxForegroundError")
        }
    }

    @Test
    fun `return paths are causal separated and strong enough to test without global sustain`() {
        val original = clips.first()
        val foreground = FloatArray(original.raw.size) { original.raw[it] - original.returnSamples[it] }
        val foregroundEnergy = energy(foreground)
        val ratios = clips.drop(1).map { sqrt(energy(it.returnSamples) / foregroundEnergy) }
        assertTrue(ratios.max() <= .450001, "return calibration exceeded the joint RMS bound: $ratios")
        assertTrue(ratios.min() > .15 && ratios.max() > .25,
            "headroom calibration hid the new path again: $ratios")
        assertEquals(clips[1].returnGain, clips[2].returnGain)
        for ((index, clip) in clips.drop(1).withIndex()) {
            val minDelay = clip.pathDetails.minOf { it.delayFrames }
            assertTrue(clip.returnSamples.take(minDelay).all { it == 0f }, "${clip.id} has a noncausal return")
            assertEquals(1.0, clip.pathDetails.sumOf { it.weight }, 1e-12)
            assertTrue(clip.pathDetails.all { it.lowPassHz in 650.0..1600.0 })
            val after = energy(clip.returnSamples, (.8 * 44_100).toInt()) / energy(clip.returnSamples)
            val delta = FloatArray(clip.raw.size) { clip.returnSamples[it] - clip.originalReturn[it] }
            val deltaRatio = sqrt(energy(delta) / foregroundEnergy)
            val e99 = energyTime(clip.raw, .99)
            println("NACRE presence ${clip.id}: return/foreground=${ratios[index]}, delta/foreground=$deltaRatio, return after.8s=$after, E99=$e99 s, commonReturnGain=${clip.returnGain}")
            assertTrue(after > if (clip.id == "seaweed") .08 else .015,
                "${clip.id} still speaks only under the foreground: after.8s=$after")
            assertTrue(deltaRatio > .15, "${clip.id} repeats the quiet-coloration experiment: $deltaRatio")
            assertTrue(e99 < 1.6, "${clip.id} has become a sustained bed: E99=$e99")
            assertTrue(energy(clip.returnSamples, 2 * 44_100) < .001 * energy(clip.returnSamples),
                "${clip.id} leaves an excessive late return")
        }
    }

    @Test
    fun `seaweed apertures change the return shape without changing foreground or family gain`() {
        val still = NacrePresenceComparison.render(seaweedMotion = false)
        assertContentEquals(clips[0].raw, still[0].raw)
        assertContentEquals(clips[1].raw, still[1].raw)
        assertEquals(clips[2].returnGain, still[2].returnGain)
        val movingReturn = clips[2].returnSamples
        val stillReturn = still[2].returnSamples
        val similarity = movingReturn.indices.sumOf { movingReturn[it].toDouble() * stillReturn[it] } /
            sqrt(energy(movingReturn) * energy(stillReturn))
        assertTrue(similarity < .98, "seaweed only changes a scalar gain: normalized similarity=$similarity")
        assertTrue(clips[2].pathDetails.all { it.apertureHz == 2.0 && it.apertureDepth == .9 })
        assertTrue(still[2].pathDetails.all { it.apertureHz == 0.0 })
        for (name in listOf("bass", "upper", "sympathetic")) {
            assertContentEquals(clips[2].stems.getValue(name), still[2].stems.getValue(name))
        }
    }

    @Test
    fun `anchored playback stays finite unclipped and on the original root`() {
        for (clip in clips) {
            assertTrue(clip.raw.all { it.isFinite() })
            assertTrue(clip.returnSamples.all { it.isFinite() })
            assertTrue(clip.diagnostics.values.all { it.isFinite() })
            assertTrue(abs(clip.raw.sumOf { it.toDouble() } / clip.raw.size) < .005)
            val played = clip.raw.map { (it * NacrePresenceComparison.REFERENCE_LISTENING_GAIN).toFloat() }.toFloatArray()
            assertTrue(played.maxOf { abs(it) } < .92f, "${clip.id} needs a different per-clip playback gain")
            val note = com.snipsnap.audio.Snip(played, channels = 1, sampleRate = 44_100)
            val pitch = requireNotNull(Pitch.detect(note, fromSec = .45f, windowSec = .4f)) { "${clip.id}: no root detected" }
            val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(48).toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 15.0 && pitch.confidence >= .3f,
                "${clip.id}: pitch=$cents cents confidence=${pitch.confidence}")
        }
    }

    private fun energy(samples: FloatArray, from: Int = 0): Double =
        (from until samples.size).sumOf { samples[it].toDouble() * samples[it] }

    private fun energyTime(samples: FloatArray, fraction: Double): Double {
        val target = energy(samples) * fraction
        var sum = 0.0
        for (i in samples.indices) { sum += samples[i].toDouble() * samples[i]; if (sum >= target) return i / 44_100.0 }
        return samples.size / 44_100.0
    }

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
