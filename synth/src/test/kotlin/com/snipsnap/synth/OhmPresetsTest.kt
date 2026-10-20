package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OhmPresetsTest {
    @Test
    fun `factory bank keeps the five complete published encounters in order`() {
        val all = OhmPresets.all()
        assertEquals(listOf("Solo", "Argument", "Communion", "Quiet Company", "Gather"), all.map { it.name })
        val relationships = listOf(0f to 0f, .9f to 0f, .9f to 1f, .25f to 1f, .9f to 1f)
        for ((index, patch) in all.withIndex()) {
            assertEquals(OhmVoice.STRING, patch.voice)
            assertEquals(mapOf("TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f,
                "MEDDLING" to relationships[index].first, "COMMUNE" to relationships[index].second), patch.macros)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertEquals(60, patch.midi)
            assertEquals(.8f, patch.velocity)
            assertEquals(731, patch.seed)
            assertEquals(2, patch.model)
            assertEquals(if (index == 4) OhmGesture.GATHER else OhmGesture.STEADY, patch.gesture)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
    }

    @Test
    fun `published raw PCM16 audio is unchanged by factory promotion`() {
        // Unscaled raw WAV hashes from the published model-2 audition at source 253faab.
        // Its rawExportGain was 1, so these pin the complete heard recipes without a testkit dependency.
        val expected = listOf(
            "1bf4ae492283cde6eaa446199eb7d7beb6c6375506372db335014755e746cf28",
            "70242b622d5d0b4b252c330e7c5bf1e7f0a9c916166c6538dcfe663d78c24f35",
            "3b05fb5cc6ffc2adb2cc81682412ea802618b9ccaa9d47abae44ca4af6d1c0c3",
            "f504a85ade4587362ed8db6dd0e372b677e72fedf8d9f064d6e09e7462b3ad28",
            "37c5e1aeaf61ae19f22d82680c37ef7c9a8294612fb59b8692814a5bff6583e3",
        )
        for ((index, patch) in OhmPresets.all().withIndex()) {
            val raw = Ohm.renderRaw(patch.macros, patch.midi, patch.velocity, patch.seed, patch.gesture, patch.model)
            assertTrue(raw.samples.all { it.isFinite() })
            assertTrue(raw.samples.any { it != 0f }, "${patch.name} is silent")
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, raw, WavWriter.BitDepth.PCM_16) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected[index], hash, "${patch.name} differs from its published raw audio")
        }
    }

    @Test
    fun `factory discovery names and dry landing use the common dispatcher`() {
        assertEquals(OhmPresets.all(), OhmPresets.forVoice(OhmVoice.STRING))
        assertEquals(OhmPresets.all(), Presets.forVoice("OHM", "STRING"))
        assertEquals(OhmPresets.all(), Presets.all().filter { it.engine == "OHM" })
        assertTrue(Presets.forVoice("OHM", "BOW").isEmpty())
        for (patch in OhmPresets.all()) {
            assertEquals(patch, Presets.byName("OHM", "STRING", patch.name))
            assertNull(Presets.landingFor("OHM", "STRING", patch.macros))
        }
        assertNull(Presets.byName("OHM", "STRING", "Missing"))
    }
}
