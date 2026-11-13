package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Factory discovery retains the existing PEARL recipes and their saved performances. */
class NacrePresetTest {
    @Test
    fun `three starting recipes preserve the accepted source controls and their return settings`() {
        val presets = NacrePresets.all().take(3)
        assertEquals(listOf("Pearl Close", "Coral Reply", "Kelp Passage"), presets.map { it.name })
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        assertEquals(presets, NacrePresets.forVoice(NacreVoice.PEARL).take(3))
        for (patch in presets) {
            assertTrue(patch.name.isNotBlank())
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f }, patch.name)
            assertEquals(3, patch.model, "${patch.name} silently selected a different saved model")
            assertEquals(NacreVoice.PEARL, patch.voice)
            assertEquals(48, patch.midi)
            assertEquals(1f, patch.velocity)
            assertEquals(7L, patch.seed)
            val venue = if (patch.name == "Kelp Passage") 1f else 0f
            val bloom = if (patch.name == "Pearl Close") .15f else .35f
            assertEquals(mapOf("TOUCH" to .30f, "DUET" to .15f, "CURRENT" to .20f,
                "VENUE" to venue, "BLOOM" to bloom, "HOLD" to 0f), patch.macros, patch.name)
        }
    }

    @Test
    fun `starting recipes replay without a separate audition operation`() {
        // Unscaled PCM24 WAVs from the published playable audition, source d4fde05.
        // These independent hashes pin promotion to the existing published sounds.
        val published = mapOf(
            "Pearl Close" to "1ef65cb36f26145f704e29c1c4912c7a16919a52fac3e95f9dfce74d4489f487",
            "Coral Reply" to "23996716fad5464666937c8880f32d9de0ff12a98824b68614e83ffc09db1951",
            "Kelp Passage" to "14fa2b8dfcbc4fb5fcd036b15b081d1b695e30201986cd69370592214cfc3303",
        )
        val renders = mutableListOf<FloatArray>()
        for (patch in NacrePresets.all().take(3)) {
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            val rendered = patch.render()
            assertEquals(1, rendered.channels)
            assertEquals(44_100, rendered.sampleRate)
            assertEquals(264_600, rendered.frameCount)
            assertTrue(rendered.samples.all { it.isFinite() })
            assertTrue(rendered.peak() in .001f.. .950001f)
            val wav = ByteArrayOutputStream().also { WavWriter.write(it, rendered) }.toByteArray()
            val sha256 = MessageDigest.getInstance("SHA-256").digest(wav)
                .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
            assertEquals(published.getValue(patch.name), sha256, "${patch.name} differs from the published preset WAV")
            assertContentEquals(rendered.samples, restored.render().samples,
                "${patch.name} lost a production return setting during replay")
            renders += rendered.samples
        }
        for (a in renders.indices) for (b in a + 1 until renders.size) {
            assertFalse(renders[a].contentEquals(renders[b]), "two named starting sounds render identically")
        }
    }

    @Test
    fun `factory discovery serves retained and kept contact recipes and lands them dry`() {
        val expected = NacrePresets.all()
        assertEquals(expected, Presets.all().filterIsInstance<NacrePatch>())
        assertEquals(expected, Presets.forVoice("NACRE", "PEARL"))
        assertFalse(NacrePatch.ENGINE in PatchTypes.PROTOTYPES)
        for (patch in expected) {
            assertEquals(patch, Presets.byName("NACRE", "PEARL", patch.name))
            assertNull(Presets.landingFor("NACRE", "PEARL", patch.macros))
        }
        assertTrue(Presets.forVoice("NACRE", "UNKNOWN").isEmpty())
        assertNull(Presets.byName("NACRE", "PEARL", "Missing"))
    }

    @Test
    fun `restoring an older same-name recipe retains its renderer and native performance`() {
        val factory = NacrePresets.all().first()
        for (model in listOf(1, 2)) {
            val legacy = factory.copy(model = model, macros = NacreModels.defaults(factory.voice, model),
                midi = 43, velocity = .42f, seed = Long.MIN_VALUE + 17)
            val restored = NacrePatch.fromJsonText(legacy.toJsonText())
            assertEquals(legacy, restored)
            assertEquals(model, restored.model)
            assertEquals(factory, Presets.byName("NACRE", "PEARL", restored.name))
            assertFalse(restored == factory, "factory discovery must not rewrite a saved recipe with the same label")
        }
    }
}
