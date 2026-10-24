package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Versioned replay and measured user-facing model-two behavior, independently of cache/extraction tests. */
class ScannedChainModelV2Test {
    private fun resource(path: String): String = requireNotNull(javaClass.getResourceAsStream(path)) {
        "missing regression resource $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    private fun power(samples: FloatArray, from: Double, to: Double): Double {
        val start = (from * 44_100).toInt()
        val end = (to * 44_100).toInt()
        require(end <= samples.size && end > start)
        return (start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start)
    }

    /** Average normalized Hann-windowed power; a phase shift alone is not a timbre test. */
    private fun spectralPower(samples: FloatArray, from: Double, to: Double): DoubleArray {
        val size = 8192
        val end = (to * 44_100).toInt()
        var start = (from * 44_100).toInt()
        val powers = DoubleArray(size / 2 + 1)
        var windows = 0
        while (start + size <= end) {
            val re = FloatArray(size) { i ->
                (samples[start + i] * (.5 - .5 * cos(2 * PI * i / (size - 1)))).toFloat()
            }
            val im = FloatArray(size)
            Fft.forward(re, im)
            for (i in powers.indices) powers[i] += re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            start += size / 2
            windows++
        }
        require(windows > 0)
        val total = powers.sum()
        require(total > 1e-20)
        return DoubleArray(powers.size) { powers[it] / total }
    }

    private fun maximumSpectralChange(a: FloatArray, b: FloatArray): Double =
        listOf(.1 to .5, .65 to 1.1, 1.2 to 1.8, 2.1 to 2.5).maxOf { (from, to) ->
            val pa = spectralPower(a, from, to)
            val pb = spectralPower(b, from, to)
            .5 * pa.indices.sumOf { abs(pa[it] - pb[it]) }
        }

    @Test
    fun `successful saved model one factories and authored source keep their exact finished PCM`() {
        val manifest = Json.parse(resource("/scanned-chain-model1/manifest.json")).obj()
        val entries = manifest.getValue("entries").arr()
        assertEquals(4, entries.size)
        for (entry in entries) {
            val expected = entry.obj()
            val text = resource("/scanned-chain-model1/${expected.getValue("resource").str()}")
            val patch = assertIs<ScannedChainPatch>(Patches.fromJsonText(text))
            assertEquals(1, patch.model)
            assertEquals(text.trim(), patch.toJsonText().trim(), "legacy JSON is not silently upgraded")
            assertEquals(1, assertIs<ScannedChainPatch>(Patches.edited(patch, patch.name, patch.macros)).model)
            ScannedChainRenderCache.clear()
            val audio = patch.render()
            assertEquals(expected.getValue("frames").int(), audio.frameCount)
            assertEquals(expected.getValue("sampleRate").int(), audio.sampleRate)
            assertEquals(expected.getValue("channels").int(), audio.channels)
            assertEquals(expected.getValue("pcmFloat32LeSha256").str(), hash(audio.samples), expected.getValue("id").str())
        }
    }

    @Test
    fun `new patches use model two while future models are refused before rendering`() {
        val patch = ScannedChainPatch("New model", ScannedChainVoice.PLUCK)
        assertEquals(2, patch.model)
        assertTrue(ScannedChain.supportsModel(1) && ScannedChain.supportsModel(2))
        assertFalse(ScannedChain.supportsModel(3))
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 3) }
        assertFailsWith<JsonException> {
            Patches.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries + ("model" to JsonValue.Num(3.0))))
        }
    }

    @Test
    fun `the exact legal focused contour is bounded by initial headroom rather than runtime clipping`() {
        val points = Json.parse(resource("/scanned-chain-model2/focused-shape.json")).arr()
            .map { it.num().toFloat() }.toFloatArray()
        val shape = ScannedChainShape(points, ScannedChainShapeSource.DRAWN, "Exact focused regression")
        val voice = ScannedChainVoice.METAL
        val macros = mapOf("MOTION" to 1f, "DAMP" to 0f, "BEND" to 0f, "BLOOM" to 0f)
        val config = ScannedChainConfig.forVoice(voice).copy(seconds = .25f, releaseSeconds = .05f)
        val result = ScannedChain.probeV2(voice, macros, velocity = 1f, shape = shape, config = config)
        val plan = result.headroom
        assertTrue(plan.initialGain > 0 && plan.initialGain < 1, "the reviewed source needs initial energy headroom")
        assertTrue(plan.predictedMaxDisplacement <= .680001)
        assertTrue(result.compatibility.diagnostics.maxDisplacement <= .9)
        assertTrue(result.compatibility.raw.samples.all { it.isFinite() })
        val mean = points.average()
        assertTrue(points.indices.maxOf {
            abs(result.compatibility.initialShape[it] - (points[it] - mean) * plan.initialGain)
        } < 2e-7, "one scalar sets the initial excitation; the contour remains intact")
        assertContentEquals(points, shape.points)

        // Model one keeps successful historical output. A formerly refused
        // legal input may use the conservative fallback instead of failing.
        for (model in listOf(1, 2)) {
            val audio = ScannedChainPatch("Focused", voice, macros, shape, config, velocity = 1f, model = model).render()
            assertTrue(audio.samples.all { it.isFinite() })
            assertTrue(audio.peak() in 1e-5f..0.900001f)
        }
    }

    @Test
    fun `bend moves smooth factory spectral power at low and middle settings`() {
        val voice = ScannedChainVoice.PLUCK
        fun render(bend: Float) = ScannedChain.probeV2(voice,
            ScannedChain.defaults(voice) + ("BEND" to bend)).compatibility.raw.samples
        val off = render(0f)
        val low = render(.1f)
        val middle = render(.5f)
        assertTrue(maximumSpectralChange(off, low) > .005,
            "low BEND must redistribute spectral power beyond the former smooth-source weak range")
        assertTrue(maximumSpectralChange(low, middle) > .01,
            "middle BEND must change the evolving power spectrum, not only phase")
    }

    @Test
    fun `bloom envelope contrast survives changing a source from peak point twelve to point thirty five`() {
        val voice = ScannedChainVoice.BLOOM
        val quiet = ScannedChainShape.factory(voice)
        val quietPoints = quiet.points
        val loud = ScannedChainShape.drawn(FloatArray(128) { quietPoints[it] * (.35f / .12f) }, "Scaled BLOOM contour")
        fun contrast(shape: ScannedChainShape): Double {
            fun relativeLate(drive: Float): Double {
                val audio = ScannedChain.probeV2(voice,
                    ScannedChain.defaults(voice) + ("BLOOM" to drive), shape = shape).compatibility.raw.samples
                return power(audio, 1.2, 1.8) / power(audio, .1, .5)
            }
            return 10 * log10(relativeLate(.8f) / relativeLate(0f))
        }
        val quietContrast = contrast(quiet)
        val loudContrast = contrast(loud)
        assertTrue(quietContrast > 3 && loudContrast > 3, "BLOOM adds a later body for both source levels")
        assertTrue(abs(quietContrast - loudContrast) < .25,
            "source amplitude must not mask the same BLOOM amount: $quietContrast versus $loudContrast dB")
    }

    @Test
    fun `frozen high notes keep the same waveform and bandwidth across bend and bloom`() {
        val voice = ScannedChainVoice.METAL
        fun render(bend: Float, bloom: Float) = ScannedChain.probeV2(voice,
            ScannedChain.defaults(voice) + mapOf("MOTION" to 0f, "BEND" to bend, "BLOOM" to bloom), midi = 89)
        val first = render(0f, 0f)
        val last = render(1f, 1f)
        assertEquals(1.0, first.headroom.initialGain)
        assertEquals(1.0, last.headroom.initialGain)
        assertEquals(0, first.compatibility.diagnostics.physicsSteps)
        assertEquals(0, last.compatibility.diagnostics.physicsSteps)
        assertEquals(first.compatibility.diagnostics.retainedHarmonics, last.compatibility.diagnostics.retainedHarmonics)
        assertContentEquals(first.compatibility.raw.samples, last.compatibility.raw.samples)
    }
}
