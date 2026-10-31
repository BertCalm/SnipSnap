package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.WavWriter
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class LibrationPerformanceTest {
    private fun rms(s: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt(); val b = min(s.size, (to * Dsp.RATE).toInt())
        return sqrt((a until b).sumOf { s[it].toDouble().pow(2) } / (b - a))
    }

    @Test fun `performance pivot preserves the accepted six-second waveforms exactly`() {
        val expected = mapOf(
            LibrationVoice.GATHER to "16c780d8b30c7b4e0a2f7e81010fc781668cc212a8704eb262cafa805e5bf0bd",
            LibrationVoice.TWINS to "8360ae42e6ffb31c78d668fdc582f1e8f5f4f73ee132578ab00b0e01a52c7a0c",
            LibrationVoice.DEPARTURE to "60e60c63de928524704ad3944ec3de5f0eab9b464b0534e550024a24a11b408a",
        )
        val dir = Files.createTempDirectory("libration-reference-").toFile()
        try {
            for (patch in LibrationPresets.references()) {
                val reference = Libration.probe(patch.voice, patch.macros, patch.midi, patch.velocity, model = 2)
                val performance = Libration.probe(patch.voice, patch.macros, patch.midi, patch.velocity, model = 3)
                assertContentEquals(reference.raw.samples, performance.raw.samples, patch.name)
                assertContentEquals(reference.samples.samples, performance.samples.samples, patch.name)
                val file = WavWriter.write(java.io.File(dir, "reference.wav"), reference.raw, WavWriter.BitDepth.PCM_24)
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(expected.getValue(patch.voice), hash, patch.name)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `finite gate stops the direct source and lets sympathetic memory decay`() {
        val voice = LibrationVoice.GATHER
        val options = Libration.Options(seconds = .30)
        val dry = Libration.probe(voice, mapOf("RESONANCE" to 0f), options = options)
        val wet = Libration.probe(voice, mapOf("RESONANCE" to .5f), options = options)
        assertTrue(rms(dry.raw.samples, .55, .9) < 1e-6)
        assertTrue(rms(wet.raw.samples, .55, .9) > .001)
        assertTrue(rms(wet.raw.samples, 2.0, 2.4) < rms(wet.raw.samples, .55, .9) * .25)
        assertTrue(wet.raw.peak() < .9f)
    }

    @Test fun `touch changes articulation after level matching while preserving a clear root`() {
        for (voice in LibrationVoice.entries) {
            val options = Libration.Options(seconds = .8)
            val physics = Libration.population(voice, emptyMap(), options)
            val soft = Libration.probe(voice, velocity = .3f, options = options, cached = physics)
            val hard = Libration.probe(voice, velocity = 1f, options = options, cached = physics)
            val a = soft.raw.samples; val b = hard.raw.samples
            val gain = sqrt(b.sumOf { it.toDouble().pow(2) } / a.sumOf { it.toDouble().pow(2) })
            val difference = sqrt(a.indices.sumOf { (a[it] * gain - b[it]).pow(2) } / b.sumOf { it.toDouble().pow(2) })
            assertTrue(difference > .012, "$voice matched touch difference $difference")
            assertTrue(Loudness.of(hard.samples) > Loudness.of(soft.samples) * 1.5f)
            val pitch = assertNotNull(Pitch.detect(hard.samples, .1f, .4f))
            assertTrue(abs(1200 * log2(pitch.hz / Keys.midiHz(60))) < 15)
            println("$voice matched touch difference: $difference")
        }
    }

    @Test fun `note length recalls through patch and pad recipes and malformed gates are rejected`() {
        val patch = LibrationPresets.all().first().copy(sourceSeconds = .37, midi = 43, velocity = .35f)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch = patch).toJsonText()).patch)
        for (seconds in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 8.1)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(sourceSeconds = seconds) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 2) }
        assertFalse(LibrationPresets.references().first().toJsonText().contains("sourceSeconds"))
        assertTrue(patch.copy(velocity = 0f).render().samples.all { it == 0f })
    }

    @Test fun `bank and starter retain complete finite recipes in the factory roster`() {
        val bank = LibrationPresets.all()
        assertEquals(12, bank.size)
        assertEquals(12, bank.map { it.name }.toSet().size)
        for (voice in LibrationVoice.entries) assertEquals(4, LibrationPresets.forVoice(voice).size)
        assertTrue(bank.all { it in Presets.all() })
        for (voice in LibrationVoice.entries) {
            assertEquals(LibrationPresets.forVoice(voice), Presets.forVoice(LibrationPatch.ENGINE, voice.name))
        }
        assertTrue(LibrationPresets.references().none { it in Presets.all() })
        val pads = LibrationKits.patches()
        assertEquals(16, pads.size)
        assertTrue(pads.all { it.model == 3 && it.velocity == 1f && it.macros["HOLD"] == 0f })
        assertEquals(16, pads.map { it.name }.toSet().size)
    }
}
