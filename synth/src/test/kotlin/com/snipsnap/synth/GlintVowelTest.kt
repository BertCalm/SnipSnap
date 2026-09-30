package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-09-29-glint-paths-design.md §2.3. */
class GlintVowelTest {

    @Test
    fun `the vowel line - PEAK walks OO OH AH EH EE`() {
        val f = FloatArray(2)
        Glint.vowelAt(0f, f); assertEquals(300f, f[0], 0.01f); assertEquals(870f, f[1], 0.01f)
        Glint.vowelAt(2f, f); assertEquals(730f, f[0], 0.01f); assertEquals(1090f, f[1], 0.01f)
        Glint.vowelAt(4f, f); assertEquals(270f, f[0], 0.01f); assertEquals(2290f, f[1], 0.01f)
        // Halfway between OO and OH is the geometric mean - log2 interpolation.
        Glint.vowelAt(0.5f, f); assertEquals(kotlin.math.sqrt(300f * 570f), f[0], 0.05f)
        assertEquals(0f, Glint.vowelPosition(0f)); assertEquals(4f, Glint.vowelPosition(1f))
    }

    @Test
    fun `VOWEL has no FOLLOW - its formants are fixed Hz`() {
        assertEquals(listOf("TUNE", "PEAK", "BODY", "BLOOM", "DECAY"), Glint.macrosFor(GlintVoice.VOWEL).map { it.name })
    }

    @Test
    fun `the two bursts sit at F1 and F2 over the note`() {
        val m = Glint.defaults(GlintVoice.VOWEL) + mapOf("PEAK" to 0.5f, "BODY" to 0.5f, "BLOOM" to 0.5f, "TUNE" to 0f)
        val path = GlintPath.of(GlintVoice.VOWEL, m, 110f)
        val k = FloatArray(2)
        path.ratios(0f, -1, k)
        assertEquals(730f / 110f, k[0], 1e-3f)
        assertEquals(1090f / 110f, k[1], 1e-3f)
    }

    @Test
    fun `BODY scales both formants together`() {
        val k = FloatArray(2)
        fun at(body: Float): FloatArray {
            val m = Glint.defaults(GlintVoice.VOWEL) + mapOf("BODY" to body, "BLOOM" to 0.5f, "TUNE" to 0f)
            GlintPath.of(GlintVoice.VOWEL, m, 110f).ratios(0f, -1, k)
            return k.copyOf()
        }
        val small = at(1f); val big = at(0f)
        val ratio = kotlin.math.sqrt(2f)   // 2^(0.5*0.5) / 2^(-0.5*0.5)
        assertEquals(ratio, small[0] / big[0], 1e-3f)
        assertEquals(ratio, small[1] / big[1], 1e-3f)
    }

    @Test
    fun `a formant below the note pins to the fundamental instead of vanishing`() {
        val m = Glint.defaults(GlintVoice.VOWEL) + mapOf("PEAK" to 0f, "BODY" to 0f, "BLOOM" to 0.5f, "TUNE" to 1f)
        val f0 = Glint.frequencyFor(GlintVoice.VOWEL, 1f)   // A4, 440 Hz
        val k = FloatArray(2)
        GlintPath.of(GlintVoice.VOWEL, m, f0).ratios(0f, -1, k)
        assertEquals(Glint.VOWEL_K_MIN, k[0])
    }

    @Test
    fun `VOWEL at the top of its range with the darkest vowel still sounds`() {
        val snip = Glint.render(GlintVoice.VOWEL, mapOf("TUNE" to 1f, "PEAK" to 0f, "BODY" to 0f))
        assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f })
        assertTrue(snip.peak() > 0.5f, "too quiet: ${snip.peak()}")
    }

    /** The 10-40 ms head of a VOWEL note at PEAK 0.5 (AH), before BLOOM's glide has landed. */
    private fun head(bloom: Float): Snip {
        val snip = Glint.render(GlintVoice.VOWEL, mapOf("PEAK" to 0.5f, "BLOOM" to bloom, "DECAY" to 0.7f))
        return Snip(snip.samples.copyOfRange(441, 1764), 1, snip.sampleRate)
    }

    private fun headCentroid(bloom: Float): Float = FeatureExtractor.extract(head(bloom)).centroidHz

    /** Share of [window]'s power above 1.5 kHz, power-weighted (m^2) like the centroid: where EE's F2 lives. */
    private fun shareAbove1500(window: Snip): Float {
        val spectrum = Fft.magnitudeSpectrum(window.samples, 4096)
        var above = 0.0
        var total = 0.0
        for (bin in spectrum.indices) {
            val power = spectrum[bin].toDouble() * spectrum[bin]
            total += power
            if (Fft.binToHz(bin, 4096, window.sampleRate) > 1500f) above += power
        }
        return (above / total).toFloat()
    }

    /**
     * BLOOM 1 starts on EE and BLOOM 0 on OO, each gliding into AH. The darker
     * side is read on spectral centroid. The brighter side is read on the share
     * of power above 1.5 kHz instead (plan ruling, 2026-09-29): centroid cannot
     * be trusted to put EE above AH, because EE has the lowest F1 of the five
     * vowels and it is its F2 (2290 Hz) that makes it bright, and F2 energy
     * above 1.5 kHz is that property directly. Both readings print for BLOOM
     * 0, 0.5 and 1.
     */
    @Test
    fun `BLOOM glides between vowels - above centre from a brighter one`() {
        for (bloom in listOf(0f, 0.5f, 1f)) {
            println("VOWEL BLOOM $bloom, 10-40 ms: centroid ${"%.1f".format(headCentroid(bloom))} Hz, share above 1.5 kHz ${"%.4f".format(shareAbove1500(head(bloom)))}")
        }
        val bright = shareAbove1500(head(1f))
        val still = shareAbove1500(head(0.5f))
        assertTrue(bright > still * 1.2f, "BLOOM 1 should start on a brighter vowel: share above 1.5 kHz $bright vs $still")
        val dark = headCentroid(0f)
        val flat = headCentroid(0.5f)
        assertTrue(dark < flat / 1.1f, "BLOOM 0 should start on a darker vowel: centroid $dark vs $flat")
    }

    /**
     * Spec §2.3: burst levels 1.0 and 0.5, "both on the amp envelope (one
     * mouth, one source; not BODY's separate envelope)". Held still on EE, F1
     * (270 Hz) and F2 (2290 Hz) sit either side of 1.5 kHz. On one envelope
     * they fade together, so the share of power above 1.5 kHz is the same
     * early and late; on BODY's own faster envelope F2 would fade first.
     */
    @Test
    fun `the second burst is the same mouth - a fixed level on the one envelope`() {
        for (body in listOf(0f, 0.5f, 1f)) {
            val m = Glint.defaults(GlintVoice.VOWEL) + ("BODY" to body)
            assertEquals(Glint.VOWEL_LEVEL2, GlintPath.of(GlintVoice.VOWEL, m, 220f).level2, "BODY $body is a mouth size, not a level")
        }
        val snip = Glint.render(GlintVoice.VOWEL, mapOf("PEAK" to 1f, "BLOOM" to 0.5f, "DECAY" to 0.9f))
        fun share(fromSec: Float): Float {
            val a = (fromSec * snip.sampleRate).toInt()
            return shareAbove1500(Snip(snip.samples.copyOfRange(a, a + 2048), 1, snip.sampleRate))
        }
        val early = share(0.01f)
        val late = share(0.3f)
        println("VOWEL one envelope, still EE: share above 1.5 kHz early $early, late $late")
        assertEquals(early, late, early * 0.1f, "F2 should fade with F1: share above 1.5 kHz $early early, $late late")
    }
}
