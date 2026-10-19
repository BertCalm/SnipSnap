package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared measurements for the factory-bank tests, independent of its production catalog tables. */
internal object EmbouchurePresetAcceptance {
    val golden: Map<String, JsonValue> by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/embouchure-model2-initial-golden.json"))
        Json.parse(stream.bufferedReader().use { it.readText() }).obj()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun assertWav(snip: Snip, fixture: Map<String, JsonValue>, label: String) {
        assertEquals(fixture.getValue("frames").int(), snip.frameCount, "$label phrase length changed")
        val depth = when (fixture.getValue("bits").int()) {
            16 -> WavWriter.BitDepth.PCM_16
            24 -> WavWriter.BitDepth.PCM_24
            else -> error("unexpected golden PCM depth")
        }
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, depth) }.toByteArray()
        assertEquals(fixture.getValue("sha256").str(), sha256(bytes), "$label accepted PCM changed")
    }

    fun wanted(patch: EmbouchurePatch): Float = Keys.midiHz(
        patch.midi + Math.round(patch.macros.getValue("TUNE") * Embouchure.TUNE_SEMITONES))

    fun rms(samples: FloatArray, rate: Int, from: Double = 0.0,
        to: Double = samples.size.toDouble() / rate): Double {
        val first = (from * rate).toInt().coerceIn(0, samples.size)
        val end = (to * rate).toInt().coerceIn(first, samples.size)
        return sqrt((first until end).sumOf { samples[it].toDouble() * samples[it] } / (end - first).coerceAtLeast(1))
    }

    fun assertAudio(snip: Snip, label: String) {
        assertEquals(1, snip.channels, label)
        assertEquals(Dsp.RATE, snip.sampleRate, label)
        assertTrue(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() && abs(it) <= 1f }, "$label invalid output")
        assertTrue(snip.samples.none { it != 0f && abs(it) < java.lang.Float.MIN_NORMAL }, "$label subnormal output")
        assertTrue(rms(snip.samples, snip.sampleRate) > 1e-4, "$label silent output")
        assertTrue(abs(snip.samples.average()) < .02, "$label output DC")
    }

    fun assertRoot(snip: Snip, patch: EmbouchurePatch, from: Float, seconds: Float = .25f) {
        val label = "${patch.name} velocity=${patch.velocity} MIDI=${patch.midi} TUNE=${patch.macros["TUNE"]} at ${from}s"
        val expected = wanted(patch)
        val pitch = assertNotNull(Pitch.detect(snip, from, seconds), "$label has no captured root")
        val cents = FineTuning.cents(pitch.hz.toDouble(), expected.toDouble())
        assertTrue(abs(cents) < 25, "$label selects ${pitch.hz}Hz: $cents cents")
        assertTrue(pitch.confidence > .4f, "$label pitch confidence ${pitch.confidence}")
        val local = FineTuning.cents(FineTuning.measuredHz(snip, expected, from, seconds), expected.toDouble())
        assertTrue(abs(local) < 25, "$label local root is $local cents off")
    }

    fun assertPlaybackBoundary(snip: Snip, label: String) {
        val x = snip.samples
        val wrap = x.first() - x.last()
        val interiorStep = (1 until x.size).maxOf { abs(x[it] - x[it - 1]) }
        val interiorSlope = (2 until x.size).maxOf { abs(x[it] - 2 * x[it - 1] + x[it - 2]) }
        val slopeChange = abs((x[1] - x.first()) - wrap)
        assertTrue(abs(wrap) < interiorStep * 1.5f + 1e-4f, "$label excessive playback wrap step")
        assertTrue(slopeChange < interiorSlope * 1.5f + 1e-4f, "$label excessive playback wrap slope")
        val beginning = rms(x, snip.sampleRate, 0.0, .1)
        val ending = rms(x, snip.sampleRate, snip.durationSeconds - .1)
        assertTrue(ending / beginning in .75..1.3, "$label pumps at playback wrap: $beginning -> $ending")
    }
}
