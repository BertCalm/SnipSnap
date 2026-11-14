package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Immutable heard fixtures precede any new population law or acoustic change.
 * The five matched PCM16 recordings are pinned by SHA-256 rather than shipped.
 */
class SplayAcceptedBankTest {
    private fun resource(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/splay-accepted/$name"),
    ) { "Missing immutable SPLAY fixture $name" }.use { it.readBytes() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pcm(samples: FloatArray): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, Snip(samples, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
    }.toByteArray()

    @Test fun `five kept populations retain exact original recipes recordings and native linear values`() {
        assertEquals(listOf(1, 2, 4, 8, 16), SplayAcceptedBank.entries.map { it.mechanisms })
        val manifest = Json.parse(resource("manifest.json").decodeToString()).obj()
        assertEquals(SplayAcceptedBank.SOURCE_ARCHIVE_SHA256, manifest.getValue("archiveSha256").str())
        assertEquals(SplayAcceptedBank.WAVE3_MANIFEST_SHA256, manifest.getValue("wave3ManifestSha256").str())
        for (entry in SplayAcceptedBank.entries) {
            val recorded = manifest.getValue("bank").arr().map { it.obj() }
                .single { it.getValue("id").str() == entry.id }
            val recipeBytes = resource("${entry.id}.recipe.json")
            val saved = Json.parse(recipeBytes.decodeToString()).obj()
            assertEquals(entry.originalRecipeSha256, sha(recipeBytes), entry.id)
            assertEquals(entry.patch, SplayPatch.fromJsonValue(saved.getValue("patch")), entry.id)
            val nativePatch = resource("${entry.id}.patch.json")
            assertEquals(recorded.getValue("nativePatchSha256").str(), sha(nativePatch), entry.id)
            assertEquals(entry.patch, Patches.fromJsonText(nativePatch.decodeToString()), entry.id)
            assertEquals(entry.patch, Patches.fromJsonText(entry.patch.toJsonText()), entry.id)
            assertEquals(entry.patch, PadRecipe.fromJsonText(PadRecipe(entry.patch).toJsonText()).patch)
            val render = saved.getValue("render").obj()
            assertEquals("FULL", render.getValue("mode").str())
            assertEquals("raw", render.getValue("sampleSource").str())
            assertEquals(SplayAcceptedBank.MATCHED_GAIN, render.getValue("matchedGain").num())
            // The heard PCM16 recordings stay out of the public repository; their pinned
            // SHA-256 values must agree with the recovery manifest that recorded them.
            assertEquals(recorded.getValue("matchedWavSha256").str(), entry.matchedWavSha256, entry.id)
            assertEquals(recorded.getValue("rawWavSha256").str(), entry.rawWavSha256, entry.id)
            val population = entry.patch.macros.getValue("POPULATION")
            assertEquals((entry.mechanisms - 1) / 99f, population)
            assertTrue(kotlin.math.abs(Splay.populationWeights(population).sum() - entry.mechanisms) < 1e-5)
            assertEquals(2, entry.patch.model)
            assertEquals(SplayVoice.TONGUE, entry.patch.voice)
            assertEquals(0f, entry.patch.macros.getValue("HOLD"))
        }
        assertEquals(SplayAcceptedBank.entries.map { it.patch },
            Presets.all().filter { it.engine == SplayPatch.ENGINE },
            "Only the five exact KEEP populations enter the factory roster")
    }

    @Test fun `recovered raw and matched PCM replay every kept population without native normalization substitution`() {
        for (entry in SplayAcceptedBank.entries) {
            val p = entry.patch
            val result = Splay.renderDetailed(p.voice, p.macros, p.midi, p.velocity, p.seed, p.model)
            assertEquals(entry.rawWavSha256, sha(pcm(result.raw)), "${entry.id}: original raw PCM")
            val matched = FloatArray(result.raw.size) { (result.raw[it] * SplayAcceptedBank.MATCHED_GAIN).toFloat() }
            // The whole regenerated WAV (header included) must hash to the heard recording.
            assertEquals(entry.matchedWavSha256, sha(pcm(matched)), "${entry.id}: original matched WAV")
        }
    }
}
