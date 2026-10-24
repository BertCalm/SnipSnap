package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keep this experiment about the return, rather than repeating model 2's tonal rewrite. */
class NacreGentleComparisonTest {
    companion object {
        private val clips by lazy { NacreGentleComparison.render() }
    }

    @Test
    fun `original reference is exact and every candidate preserves the instruments and events`() {
        val referenceText = checkNotNull(javaClass.getResourceAsStream(NacreGentleComparison.REFERENCE_RESOURCE))
            .bufferedReader().use { it.readText() }
        val reference = Json.parse(referenceText).obj()
        val original = clips.first()
        assertEquals(listOf("original", "coral", "seaweed"), clips.map { it.id })
        assertEquals(reference.getValue("prelevelSamplesSha256").str(), samplesHash(original.raw))
        assertEquals(reference.getValue("nativeSamplesSha256").str(), samplesHash(original.native.samples))
        for (clip in clips) {
            assertEquals(1, clip.patch.model)
            assertEquals(48, clip.patch.midi)
            assertEquals(1f, clip.patch.velocity)
            assertEquals(7L, clip.patch.seed)
            assertEquals(original.patch.macros - "VENUE", clip.patch.macros - "VENUE")
            assertEquals(original.events, clip.events)
            for (name in listOf("bass", "upper", "sympathetic")) {
                assertContentEquals(original.stems.getValue(name), clip.stems.getValue(name),
                    "${clip.id} changed the original $name state/envelope")
            }
            assertTrue(clip.raw.all { it.isFinite() })
        }
    }

    @Test
    fun `lift is solely postmix evidence and never becomes a saved patch control`() {
        val original = clips.first()
        for (clip in clips.drop(1)) {
            val underlying = NacreModels.inspect(clip.patch.voice, clip.patch.macros,
                clip.patch.midi, clip.patch.velocity, clip.patch.seed, clip.patch.model)
            assertContentEquals(underlying.snip.samples, clip.native.samples,
                "native evidence must remain the ordinary unlifted recipe")
            val sourceVenue = underlying.stems.getValue("venue")
            val venue = clip.stems.getValue("venue")
            assertContentEquals(FloatArray(sourceVenue.size) { (1.8 * sourceVenue[it]).toFloat() }, venue)
            var maxNonVenueError = 0.0
            for (i in venue.indices) {
                val nonVenueOriginal = original.raw[i].toDouble() - original.stems.getValue("venue")[i]
                val nonVenueCandidate = clip.raw[i].toDouble() - venue[i]
                maxNonVenueError = maxOf(maxNonVenueError, abs(nonVenueOriginal - nonVenueCandidate))
            }
            assertTrue(maxNonVenueError < 3e-8, "${clip.id} modified content outside its return: error=$maxNonVenueError")
            val restored = NacrePatch.fromJsonText(clip.patch.toJsonText())
            assertEquals(clip.patch, restored)
            assertFalse(restored.macros.keys.any { it.contains("lift", ignoreCase = true) || it.contains("gain", ignoreCase = true) })
            assertEquals(1.8, clip.venueLift)
            assertEquals(.20f, clip.patch.macros.getValue("CURRENT"))
        }
        assertEquals(0f, clips[1].venueGeometry)
        assertEquals(1f, clips[2].venueGeometry)
    }

    @Test
    fun `return variants do not add model two density upper mids or a long tail`() {
        val original = clips.first()
        val originalEnergy = energy(original.raw)
        val originalMid = upperMidPower(original.raw)
        val originalE99 = energyTime(original.raw, .99)
        for (clip in clips.drop(1)) {
            val ratio = energy(clip.raw) / originalEnergy
            val e99 = energyTime(clip.raw, .99)
            val afterOneSecond = energy(clip.raw, 44_100) / originalEnergy
            val mid = upperMidPower(clip.raw)
            println("NACRE gentle ${clip.id}: energy/original=$ratio, E99=$e99 s, after1s/original=$afterOneSecond, upperMidPower=$mid")
            assertTrue(ratio <= NacreGentleComparison.MAX_ENERGY_RATIO,
                "${clip.id} added too much energy: $ratio")
            assertTrue(e99 <= originalE99 + NacreGentleComparison.MAX_ENERGY99_EXTENSION_SECONDS,
                "${clip.id} extended E99 from $originalE99 to $e99 seconds")
            assertTrue(afterOneSecond <= NacreGentleComparison.MAX_AFTER_ONE_SECOND_ENERGY_FRACTION,
                "${clip.id} leaves too much energy after the original gesture: $afterOneSecond")
            assertTrue(mid <= originalMid + NacreGentleComparison.MAX_UPPER_MID_ENERGY_ADDITION_FRACTION,
                "${clip.id} adds upper-mid density: original=$originalMid candidate=$mid")
            assertTrue(clip.raw.maxOf { abs(it) } < 1f, "${clip.id} needs clipping protection before playback gain")
        }
    }

    private fun energy(samples: FloatArray, from: Int = 0): Double =
        (from until samples.size).sumOf { samples[it].toDouble() * samples[it] }

    private fun energyTime(samples: FloatArray, fraction: Double): Double {
        val target = energy(samples) * fraction
        var sum = 0.0
        for (i in samples.indices) {
            sum += samples[i].toDouble() * samples[i]
            if (sum >= target) return i / 44_100.0
        }
        return samples.size / 44_100.0
    }

    /** Identical early-body window: upper-mid fraction is insensitive to playback gain. */
    private fun upperMidPower(samples: FloatArray): Double {
        val start = (.12 * 44_100).toInt()
        val frames = (.50 * 44_100).toInt()
        val n = 32768
        val re = FloatArray(n)
        for (i in 0 until frames) re[i] = (samples[start + i] * (.5 - .5 * cos(2.0 * PI * i / (frames - 1)))).toFloat()
        val im = FloatArray(n)
        Fft.forward(re, im)
        var total = 0.0
        var mid = 0.0
        for (i in 1 until n / 2) {
            val power = re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            total += power
            val hz = i * 44_100.0 / n
            if (hz in 1000.0..4000.0) mid += power
        }
        return mid / total.coerceAtLeast(1e-20)
    }

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
