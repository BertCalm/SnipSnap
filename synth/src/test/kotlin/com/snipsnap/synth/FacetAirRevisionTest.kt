package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reducing the diffuse veil must preserve the accepted studies and the resonant light underneath. */
class FacetAirRevisionTest {
    private fun probe(voice: FacetVoice, direction: FacetLightDirection, seconds: Float) =
        FacetLightStudy.probe(voice, direction, midi = 48, velocity = .8f, seed = 17,
            initialAlignment = 0f, options = FacetOptions(seconds = seconds))

    private fun wavHash(snip: Snip): String {
        val bytes = ByteArrayOutputStream().apply {
            WavWriter.write(this, snip, WavWriter.BitDepth.PCM_24, SmplChunk(48))
        }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `published glass and airy studies retain raw native and matched PCM24 goldens`() {
        // Captured from the published light-study-v1 files before adding AIRY_RESONANCE.
        val goldens = listOf(
            Triple(FacetVoice.BLOOM, FacetLightDirection.GLASS_GLINTS, listOf(
                "c95a68e630eeb0626c59721f2545b9437990dbc30d6ce04a2c09a9d49495a345",
                "ed8eec3bc22176880d007e665d52e34883358f85aaf79c8944122ca0506b97cc",
                "bcba51322d0f44600ac35b9c3fd65f60de08304d1d536771aa710c6c894332c4")),
            Triple(FacetVoice.BLOOM, FacetLightDirection.AIRY_WASH, listOf(
                "18cb8613da44b7cddb42312f75edc7a99887cccf29973b709b0580bdf7db63aa",
                "f26be59869947dae111c31515dd30fc6feefd445ab1505b9abcdb974935c50ee",
                "b3c31655ea4ce38442db5a1eb60f883c6b62ee5ea3e8b7e738094c1a34d3b808")),
            Triple(FacetVoice.SHIMMER, FacetLightDirection.GLASS_GLINTS, listOf(
                "72160825897757a80dcae0a58f1f6a6c83c179b716d8333c43600f84f0bfc2b4",
                "ea33de418744a4ead6e67bf4573b14b45cd9d02ed537a61a4d46cfbe71698157",
                "de6203951d9b1c42dcbeeb4ec56c01022244d263d14332194e137390821462f2")),
            Triple(FacetVoice.SHIMMER, FacetLightDirection.AIRY_WASH, listOf(
                "ceb47287a989249862a64f9dc3ed84756abb39340e92bdf003c123afe60cacb8",
                "0d96c6eb92be0bd152b89a5c5e582917dc5f552a25d939b2790739760e60b327",
                "1ae98aab9e4c247948fa00ae93cc7405a613884f7a031c316c9dc6af606413df")),
        )
        for ((voice, direction, expected) in goldens) {
            val rendered = probe(voice, direction, 6f)
            val raw = Snip(rendered.raw, channels = 1, sampleRate = Dsp.RATE)
            val actual = listOf(raw, rendered.snip, AuditionLevel.level(rendered.snip)).map(::wavHash)
            assertEquals(expected, actual, "$voice $direction changed published raw/native/matched WAVs")
        }
    }

    private data class Texture(val offModeFraction: Double, val flatness: Double, val modalPower: Double)

    /**
     * Welch body spectra distinguish a broad veil from the same sixteen narrow resonances.
     * Fractions and flatness are invariant to playback gain, so a quieter render cannot pass
     * by itself. Absolute modal-band power also checks that the ringing body remains present.
     */
    private fun texture(samples: FloatArray): Texture {
        val size = 16_384
        val first = (Dsp.RATE * .4).roundToInt()
        val until = (Dsp.RATE * 1.65).roundToInt()
        val power = DoubleArray(size / 2)
        var windows = 0
        for (start in first..until - size step size / 2) {
            val re = FloatArray(size) { i -> samples[start + i] *
                (.5 - .5 * cos(2 * PI * i / (size - 1))).toFloat() }
            val im = FloatArray(size)
            Fft.forward(re, im)
            for (i in power.indices) power[i] += re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            windows++
        }
        require(windows > 0)
        val root = 440.0 * 2.0.pow((48 - 69) / 12.0)
        val ratios = doubleArrayOf(1.0, 2.0, 3.01, 4.01, 5.98, 8.02, 10.01, 12.03,
            16.02, 20.01, 24.02, 28.04, 32.02, 40.01, 48.03, 56.01)
        var total = 0.0
        var modal = 0.0
        var logPower = 0.0
        var bins = 0
        for (i in power.indices) {
            val hz = i * Dsp.RATE.toDouble() / size
            if (hz !in 2_000.0..10_000.0) continue
            val p = power[i] / windows
            total += p
            if (ratios.any { abs(hz - root * it) < 35.0 }) modal += p
            logPower += ln(p.coerceAtLeast(1e-30))
            bins++
        }
        require(total > 1e-15 && bins > 0)
        return Texture((total - modal) / total, exp(logPower / bins) / (total / bins), modal)
    }

    @Test
    fun `less diffuse air retains identical geometry while exposing the resonant body`() {
        for (voice in FacetVoice.entries) {
            val old = probe(voice, FacetLightDirection.AIRY_WASH, 2f)
            val revised = probe(voice, FacetLightDirection.AIRY_RESONANCE, 2f)
            assertEquals(old.raw.size, revised.raw.size, "$voice air revision changed duration")
            assertEquals(old.trace, revised.trace, "$voice air revision changed saved reflection geometry")
            assertEquals(old.diagnostics.startPhase, revised.diagnostics.startPhase)
            assertEquals(old.diagnostics.endPhase, revised.diagnostics.endPhase)
            assertEquals(old.diagnostics.rotorHz, revised.diagnostics.rotorHz)
            assertTrue(revised.raw.all { it.isFinite() } && revised.snip.samples.all { it.isFinite() })
            val before = texture(old.raw)
            val after = texture(revised.raw)
            println("FACET airy revision $voice before=$before after=$after")
            assertTrue(after.offModeFraction < before.offModeFraction * .75,
                "$voice diffuse broadband share did not fall substantially at equal level")
            assertTrue(after.flatness < before.flatness * .8,
                "$voice upper spectrum did not become less noise-like")
            assertTrue(after.modalPower > before.modalPower * .15,
                "$voice air revision removed the resonant body with the veil")
        }
    }
}
