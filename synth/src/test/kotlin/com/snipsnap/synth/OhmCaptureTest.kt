package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OhmCaptureTest {
    @Test
    fun `captured PCM is an immutable content value and survives portable JSON`() {
        val input = floatArrayOf(-1f, -.3f, 0f, .2f, 1f)
        val capture = OhmCapture(input, "Palm tap")
        val original = capture.samples()
        input.fill(0f)
        capture.samples().fill(0f)
        assertContentEquals(original, capture.samples())
        assertTrue(original.all { it.isFinite() && it in -1f..1f })
        assertEquals(-1f, original.first())
        assertEquals(32767 / 32768f, original.last())
        val restored = OhmCapture.fromJsonText(capture.toJsonText())
        assertEquals(capture, restored)
        assertEquals(capture.hashCode(), restored.hashCode())
        assertEquals(capture.sha256, restored.sha256)
        assertContentEquals(original, restored.samples())
        assertNotEquals(capture, OhmCapture(original, "Different label"))
        assertEquals(5, capture.frameCount)
    }

    @Test
    fun `source capture aligns audible stereo onset resamples only its head and shapes clean endpoints`() {
        val rate = 48_000
        val lead = rate
        val interleaved = FloatArray((lead + rate) * 2) { i ->
            val frame = i / 2
            if (frame < lead) 0f else (.3 * sin(2 * PI * 437 * (frame - lead) / rate)).toFloat()
        }
        interleaved[0] = Float.NaN
        interleaved[1] = Float.POSITIVE_INFINITY
        val captured = OhmCapture.fromSnip(Snip(interleaved, 2, rate), "Captured pad")
        val samples = captured.samples()
        assertEquals(11_025, captured.frameCount)
        assertEquals(0f, samples.first())
        assertEquals(0f, samples.last())
        assertTrue(samples.all { it.isFinite() && abs(it) <= .901f })
        assertTrue(rms(samples) > .3, "onset capture kept lead silence instead of the audible event")
        assertTrue(interleaved[0].isNaN(), "capture must not rewrite the source recording")
        assertEquals(Float.POSITIVE_INFINITY, interleaved[1])
        assertEquals(captured, OhmCapture.fromSnip(Snip(interleaved, 2, rate), "Captured pad"))
    }

    @Test
    fun `capture durations are bounded and silence does not become an exciter`() {
        val tone = FloatArray(44_100) { i -> (.2 * sin(2 * PI * 211 * i / 44_100)).toFloat() }
        assertEquals(22_050, OhmCapture.fromSnip(Snip(tone, 1, 44_100), seconds = .5f).frameCount)
        assertEquals(4_410, OhmCapture.fromSnip(Snip(tone, 1, 44_100), seconds = .1f).frameCount)
        for (samples in listOf(FloatArray(100), FloatArray(100) { Float.NaN }, FloatArray(100) { .2f }, FloatArray(100) { 1e-8f })) {
            assertFailsWith<IllegalArgumentException> { OhmCapture.fromSnip(Snip(samples, 1, 44_100)) }
        }
        for (duration in listOf(0f, .501f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { OhmCapture.fromSnip(Snip(tone, 1, 44_100), seconds = duration) }
        }
        assertFailsWith<IllegalArgumentException> { OhmCapture(FloatArray(22_051) { .1f }) }
        assertFailsWith<IllegalArgumentException> { OhmCapture(FloatArray(32)) }
        assertFailsWith<IllegalArgumentException> { OhmCapture(floatArrayOf(.1f, Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { OhmCapture(floatArrayOf(.1f, 1.1f)) }
        assertFailsWith<IllegalArgumentException> { OhmCapture(floatArrayOf(.1f, .2f), "x".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { OhmCapture(floatArrayOf(.1f, .2f), "line\nbreak") }
    }

    @Test
    fun `payload framing canonical encoding and optional checksum are checked before replay`() {
        val capture = OhmCapture(floatArrayOf(.2f, -.1f, .4f), "Pad A01")
        val fields = capture.toJsonValue().obj()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(fields + (key to value))
        for (bad in listOf(
            changed("frames", JsonValue.Num(22_051.0)),
            changed("frames", JsonValue.Num(2.0)),
            changed("sampleRate", JsonValue.Num(48_000.0)),
            changed("encoding", JsonValue.Str("WAV")),
            changed("pcm16", JsonValue.Str("!".repeat(58_804))),
            changed("pcm16", JsonValue.Str("!".repeat(8))),
            changed("pcm16", JsonValue.Str(Base64.getEncoder().encodeToString(ByteArray(6)))),
            changed("sha256", JsonValue.Str("0".repeat(64))),
            changed("label", JsonValue.Str("x".repeat(65))),
        )) assertFailsWith<JsonException> { OhmCapture.fromJsonValue(bad) }
        val withoutHash = OhmCapture.fromJsonValue(JsonValue.Obj(fields - "sha256"))
        assertEquals(capture, withoutHash)
        assertEquals(capture.sha256, withoutHash.toJsonValue().obj().getValue("sha256").str())
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
