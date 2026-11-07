package com.snipsnap.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.*

class ResonantBodyVersionTest {
    private enum class Excitation { IMPULSE, NOISE, SQUARE }

    private data class Canary(
        val name: String,
        val rate: Int,
        val channels: Int,
        val frames: Int,
        val excitation: Excitation,
        val settings: BodySettings,
        val outputFrames: Int,
        val audioSha256: String,
        val wetSha256: String,
    )

    /**
     * Captured from the actual v1 implementation at 1994c619 before the v2 edit.
     * Hashes contain little-endian raw Float bits, including signed zero, not WAV headers.
     * Inputs use only integer arithmetic and explicit Float rounding; no oscillator library
     * or ignored audition directory is needed by CI. Delivered and pre-guard wet goldens
     * independently protect the old control laws, bank ordering, tail and delivery guard.
     */
    private fun canaries() = listOf(
        Canary("low-impulse-short", 44_100, 1, 1, Excitation.IMPULSE,
            BodySettings(tune = 0f, body = 0f, ring = 0f, damp = 0f, mix = 1f, modelVersion = 1), 2206,
            "081f77d30ebbf3561e7ee96a5543a9ed761a333d2ad8d6aa984a2735f151e9f9",
            "081f77d30ebbf3561e7ee96a5543a9ed761a333d2ad8d6aa984a2735f151e9f9"),
        Canary("high-bar-impulse-long", 44_100, 1, 1, Excitation.IMPULSE,
            BodySettings(tune = 1f, body = 1f, ring = 1f, damp = 1f, mix = 1f, modelVersion = 1), 26461,
            "cd5acef706811fe8f9ef8c25dfdc696ff21678ea57e7b38d5239ea27338502cc",
            "d1be17e414ab6acf6b9a88ad4207ef952a90b819fd450a001e86be55a38f2beb"),
        Canary("mono-noise-blend", 44_100, 1, 2048, Excitation.NOISE,
            BodySettings(body = 0.25f, ring = 0.5f, damp = 0.5f, mix = 0.75f, modelVersion = 1), 9687,
            "0c020b5113ba2e9eac2c1f8d02228eed542fb92c70b05027c089bc85e85a6f56",
            "96377db27ee18a83dd0f931cdb4691cad0912fd9de266d9b59bfa68d7560360b"),
        Canary("stereo48-noise-mix", 48_000, 2, 4096, Excitation.NOISE,
            BodySettings(tune = 0.75f, body = 0.75f, ring = 1f, damp = 1f, mix = 0.25f, modelVersion = 1), 32896,
            "22795de065e200cefb5daad38bc0e591f2a1badcd1abf948f62f3a5a3511e9a1",
            "9c177a978f18a53eef9fec7c4089b5e87e65e66786fed85ba990e0dea2b5ea38"),
        Canary("stereo-reversed-banks", 44_100, 2, 1536, Excitation.NOISE,
            BodySettings(tune = 0.5f, body = 0.3f, ring = 0f, damp = 1f, mix = 1f,
                from = BodyFamily.BAR, to = BodyFamily.HARMONIC, modelVersion = 1), 3741,
            "6dcc3ce37f3d4d63f1026ba9e1b58a26b94a2eadf4283bed3c9fc870fa36e133",
            "e67a0f6c76166796012318f56bbaf8cc2c058ef9b8aef1a6893383fec911b3db"),
        Canary("stereo48-impulse", 48_000, 2, 64, Excitation.IMPULSE,
            BodySettings(tune = 0.5f, body = 0.5f, ring = 0.6f, damp = 0.2f, mix = 1f, modelVersion = 1), 10724,
            "1248b821dd2801b5cd6f1470f018d951fb42c78d7599b82c59a7a8e85cf008ba",
            "ee060ee84c6636a68e6dc67c956db777844ff83cd91dad5a90b868d54475237f"),
        Canary("sustained-guard", 44_100, 1, 22050, Excitation.SQUARE,
            BodySettings(body = 0f, ring = 1f, damp = 0f, mix = 1f, modelVersion = 1), 48510,
            "610dfaf0cfb5cbd39ab4a0c2d5bd06aa98a9be745e7cef6d99f9fc4909825654",
            "fde5820c4744275c97ccaa7eb02075967278ba591c59e490104ee1bc53209fc5"),
    )

    private fun source(c: Canary): Snip {
        var state = 0x13579
        val samples = FloatArray(c.frames * c.channels) { index ->
            when (c.excitation) {
                Excitation.IMPULSE -> when {
                    index == 0 -> 0.8f
                    c.channels == 2 && index == 1 -> -0.3f
                    else -> 0f
                }
                Excitation.SQUARE -> if ((index / 168) % 2 == 0) 1f else -1f
                Excitation.NOISE -> {
                    state = state * 1664525 + 1013904223
                    val value = (((state ushr 8) / 8388608.0 - 1.0) * 0.6).toFloat()
                    if (c.channels == 2 && index % 2 == 1) value * -0.5f else value
                }
            }
        }
        return Snip(samples, c.channels, c.rate)
    }

    private fun pcmSha256(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    @Test fun `version one keeps the exact pre-candidate delivered and wet PCM`() {
        for (canary in canaries()) {
            val render = ResonantBody(false).process(source(canary), canary.settings, captureWet = true)
            assertEquals(canary.outputFrames, render.audio.frameCount, canary.name)
            assertEquals(canary.audioSha256, pcmSha256(render.audio.samples), "${canary.name} delivered")
            assertEquals(canary.wetSha256, pcmSha256(assertNotNull(render.rawWet).samples), "${canary.name} wet")
            assertEquals(1, render.diagnostics.modelVersion)
            if (canary.name == "sustained-guard") {
                assertTrue(render.diagnostics.attenuation < 1.0, "the golden must exercise delivery attenuation")
            }
        }
    }

    @Test fun `old defaults stay on version one and the candidate is explicit`() {
        assertEquals(1, ResonantBody.MODEL_VERSION)
        assertEquals(2, ResonantBody.CANDIDATE_MODEL_VERSION)
        assertEquals(BodySettings(modelVersion = 1), BodySettings())
        val old = BodySettings(modelVersion = 1)
        val candidate = BodySettings(modelVersion = ResonantBody.CANDIDATE_MODEL_VERSION)
        assertEquals(old.referenceHz, candidate.referenceHz)
        assertEquals(old.rootMidi, candidate.rootMidi)
        assertEquals(old.rootT60, candidate.rootT60)
        for (version in listOf(-1, 0, 3, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("unsupported version $version") {
                BodySettings(modelVersion = version)
            }
        }
    }

    @Test fun `alternating model versions cannot reuse another model's coefficients or ringing state`() {
        val canary = canaries().first { it.name == "stereo48-noise-mix" }
        val input = source(canary)
        val old = canary.settings.copy(body = 0.5f, mix = 1f)
        val candidate = old.copy(modelVersion = ResonantBody.CANDIDATE_MODEL_VERSION)
        val processor = ResonantBody()
        val oldCold = processor.process(input, old, captureWet = true)
        val candidateCold = processor.process(input, candidate, captureWet = true)
        assertFalse(oldCold.diagnostics.coefficientCacheHit)
        assertFalse(candidateCold.diagnostics.coefficientCacheHit)
        assertNotEquals(BodyModels.identity(old), BodyModels.identity(candidate))
        assertNotEquals(oldCold.diagnostics.tableIdentity, candidateCold.diagnostics.tableIdentity)
        assertFalse(oldCold.audio.samples.contentEquals(candidateCold.audio.samples),
            "the explicit model selection must select the candidate's response")

        for ((settings, expected) in listOf(old to oldCold, candidate to candidateCold, old to oldCold, candidate to candidateCold)) {
            // A different source may warm coefficients but must not leave signal history.
            processor.process(input.copy(samples = FloatArray(input.samples.size) { 0.3f }), settings)
            val warm = processor.process(input, settings, captureWet = true)
            val uncached = ResonantBody(false).process(input, settings, captureWet = true)
            assertTrue(warm.diagnostics.coefficientCacheHit)
            assertEquals(settings.modelVersion, warm.diagnostics.modelVersion)
            assertEquals(settings.coefficientVersion, warm.diagnostics.coefficientVersion)
            assertContentEquals(expected.audio.samples, warm.audio.samples)
            assertContentEquals(assertNotNull(expected.rawWet).samples, assertNotNull(warm.rawWet).samples)
            assertContentEquals(expected.audio.samples, uncached.audio.samples)
            assertContentEquals(assertNotNull(expected.rawWet).samples, assertNotNull(uncached.rawWet).samples)
        }

        for (step in 1..10) processor.process(input, candidate.copy(tune = step / 36f))
        for ((settings, expected) in listOf(old to oldCold, candidate to candidateCold)) {
            val afterEviction = processor.process(input, settings)
            assertFalse(afterEviction.diagnostics.coefficientCacheHit)
            assertContentEquals(expected.audio.samples, afterEviction.audio.samples)
        }
    }

    @Test fun `candidate bypass preserves the original object before PCM validation or caching`() {
        val unusual = Snip(floatArrayOf(-0f, Float.NaN, Float.POSITIVE_INFINITY, 9f), 2, 123)
        val settings = BodySettings(modelVersion = ResonantBody.CANDIDATE_MODEL_VERSION, mix = 0f)
        val processor = ResonantBody()
        val bypass = processor.process(unusual, settings, captureWet = true)
        assertSame(unusual, bypass.audio)
        assertSame(unusual.samples, bypass.audio.samples)
        assertNull(bypass.rawWet)
        assertEquals(ResonantBody.CANDIDATE_MODEL_VERSION, bypass.diagnostics.modelVersion)
        assertEquals(0, bypass.diagnostics.activeModes)
        assertEquals(0, bypass.diagnostics.tailFrames)
        assertFalse(bypass.diagnostics.coefficientCacheHit)
        assertFalse(processor.process(source(canaries().first()), settings.copy(mix = 1f)).diagnostics.coefficientCacheHit)
    }
}
