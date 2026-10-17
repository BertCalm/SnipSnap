package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Published recipes stay on R1 while the denser, moving source is authored as R2. */
class FacetModelVersionTest {
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun normalizedDistance(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        val aRms = rms(a)
        val bRms = rms(b)
        return sqrt(a.indices.sumOf { i ->
            val difference = a[i] / aRms - b[i] / bRms
            difference * difference
        } / a.size)
    }

    @Test
    fun `published model one default PCM24 bytes retain both audition goldens`() {
        val goldens = mapOf(
            FacetVoice.BLOOM to "60595f05aba36a312eb0db53320da9cd9fb7b3ae221e53d3f55047c7d1f2bec4",
            FacetVoice.SHIMMER to "55167b60fde6f613a4ec948c2267b4e82e4fa4728e827bd19e185ec5de3ad2d3",
        )
        for ((voice, expected) in goldens) {
            val rendered = Facet.probe(voice, midi = 48, velocity = .8f, seed = 17, initialAlignment = 0f,
                options = FacetOptions(seconds = 6f), modelVersion = 1)
            val bytes = ByteArrayOutputStream().apply {
                WavWriter.write(this, Snip(rendered.raw, channels = 1, sampleRate = Dsp.RATE),
                    WavWriter.BitDepth.PCM_24, SmplChunk(48))
            }.toByteArray()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "$voice must reproduce the published R1 raw WAV")
        }
    }

    @Test
    fun `new recipes select model two and versionless recipes retain model one through editing and rendering`() {
        assertEquals(2, Facet.MODEL_VERSION)
        assertEquals(1, Facet.LEGACY_MODEL_VERSION)
        assertTrue(Facet.supportsModel(1) && Facet.supportsModel(2))
        assertFalse(Facet.supportsModel(0) || Facet.supportsModel(3))
        for (voice in FacetVoice.entries) {
            val authored = FacetPatch("Current", voice, emptyMap(), midi = 57, velocity = .65f,
                seed = -713, initialAlignment = .3125f)
            assertEquals(2, authored.modelVersion)
            val oldJson = JsonValue.Obj(authored.toJsonValue().entries - "modelVersion")
            val legacy = Patches.fromJsonValue(oldJson) as FacetPatch
            assertEquals(1, legacy.modelVersion)
            val saved = PadRecipe(patch = legacy)
            assertEquals(legacy, PadRecipe.fromJsonText(saved.toJsonText()).patch)
            assertContentEquals(Facet.render(voice, midi = legacy.midi, velocity = legacy.velocity,
                seed = legacy.seed, initialAlignment = legacy.initialAlignment, modelVersion = 1).samples,
                legacy.render().samples, "$voice missing version selected the new model")
            assertFalse(authored.render().samples.contentEquals(legacy.render().samples), "$voice model revision has no audio effect")
            for (model in listOf(1, 2)) {
                val patch = authored.copy(modelVersion = model, beamMode = "OVERRIDE",
                    beams = listOf(FacetBeam(5, intensity = .4f, color = .7f)))
                assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
                assertEquals(patch.copy(name = "Edited", macros = mapOf("CHROMA" to .45f)),
                    Patches.edited(patch, "Edited", mapOf("CHROMA" to .45f)))
                assertEquals(model, patch.withMacros(mapOf("REVOLVE" to 0f)).modelVersion)
            }
        }
    }

    private data class Richness(val aboveOneKhz: Double, val effectiveHarmonics: Double, val activeHarmonics: Int)

    /** A steady-body Hann spectrum separates harmonic density from output loudness. */
    private fun richness(samples: FloatArray): Richness {
        val first = (Dsp.RATE * .35).roundToInt()
        val count = (Dsp.RATE * .75).roundToInt()
        var size = 1
        while (size < count) size *= 2
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val harmonicPower = DoubleArray(64)
        var total = 0.0
        var upper = 0.0
        val rootHz = 130.8127826502993 // MIDI48; spectral analysis never substitutes for pitch detection.
        for (bin in 1 until size / 2) {
            val hz = bin * Dsp.RATE.toDouble() / size
            val power = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            total += power
            if (hz >= 1_000.0) upper += power
            val harmonic = (hz / rootHz).roundToInt() - 1
            if (harmonic in harmonicPower.indices) harmonicPower[harmonic] += power
        }
        val mass = harmonicPower.sum()
        val normalized = harmonicPower.map { it / mass }
        val entropy = -normalized.filter { it > 0.0 }.sumOf { it * ln(it) }
        return Richness(upper / total, exp(entropy), normalized.count { it >= .001 })
    }

    @Test
    fun `dense model two adds upper harmonic energy and spectral participation to both defaults`() {
        for (voice in FacetVoice.entries) {
            fun render(model: Int, motion: Boolean = false) = Facet.probe(voice, midi = 48, velocity = .8f, seed = 17,
                options = FacetOptions(seconds = 2f, sourceMotion = motion), modelVersion = model).raw
            val legacy = richness(render(1))
            val dense = richness(render(2))
            val living = richness(render(2, motion = true))
            println("FACET R2 richness $voice legacy=$legacy dense=$dense living=$living")
            assertTrue(dense.aboveOneKhz > .02 && dense.aboveOneKhz > legacy.aboveOneKhz * 3,
                "$voice density did not add substantial upper harmonic energy")
            assertTrue(dense.effectiveHarmonics > legacy.effectiveHarmonics * 1.5,
                "$voice harmonic participation did not increase substantially")
            assertTrue(dense.activeHarmonics > legacy.activeHarmonics, "$voice has no additional audible harmonic bands")
            assertTrue(living.aboveOneKhz > dense.aboveOneKhz * .5,
                "$voice source motion erased the added upper harmonic body: clean=$dense living=$living")
        }
    }

    @Test
    fun `living upper sources change stopped audio while retaining the exact saved geometry`() {
        for (voice in FacetVoice.entries) {
            fun render(motion: Boolean) = Facet.probe(voice, mapOf("REVOLVE" to 0f),
                midi = 48, velocity = .8f, seed = 17, initialAlignment = .1875f,
                options = FacetOptions(seconds = 2f, sourceMotion = motion), modelVersion = 2)
            val clean = render(false)
            val living = render(true)
            assertEquals(0f, living.diagnostics.rotorHz)
            assertEquals(clean.diagnostics.startPhase, living.diagnostics.startPhase)
            assertEquals(clean.diagnostics.endPhase, living.diagnostics.endPhase)
            assertEquals(clean.trace, living.trace, "$voice source motion edited the saved geometry")
            assertEquals(clean.raw.size, living.raw.size, "$voice source motion changed the note duration")
            val distance = normalizedDistance(clean.raw, living.raw)
            println("FACET R2 source motion $voice normalizedDistance=$distance")
            assertTrue(distance > .01, "$voice source motion has no useful audio effect")
            assertTrue(abs(living.diagnostics.rawDc) < .001f, "$voice living source introduced DC")
        }
    }
}
