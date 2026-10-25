package com.snipsnap.synth

import com.snipsnap.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Model 1 keeps the exact raw and leveled float samples from the independently
 * generated pre-change reference. The fixture records its source commit and recipes.
 */
class StoopLegacyAudioTest {
    @Test
    fun `all eighteen legacy recipes preserve their raw and output float bits`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/stoop-model1-audio-reference.json"))
        val fixture = Json.parse(stream.bufferedReader().use { it.readText() }).obj()
        val cases = fixture.getValue("cases").arr().map { it.obj() }
        assertEquals(18, fixture.getValue("caseCount").int())
        assertEquals(18, cases.size)
        assertEquals(18, cases.map { it.getValue("id").str() }.toSet().size)
        val sampleRate = fixture.getValue("sampleRate").int()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val id = case.getValue("id").str()
            val patch = assertIs<StoopPatch>(StoopPatch.fromJsonValue(case.getValue("patch")))
            assertEquals(1, patch.model, "$id must use the frozen legacy renderer")
            val frames = case.getValue("frameCount").int()
            val renders = listOf(
                Triple("raw", Stoop.renderRaw(patch), case.getValue("rawFloat32Sha256LE").str()),
                Triple("output", patch.render(), case.getValue("outputFloat32Sha256LE").str()),
            )
            for ((stage, rendered, expectedHash) in renders) {
                if (rendered.frameCount != frames) {
                    failures += "$id $stage frames ${rendered.frameCount}, expected $frames"
                }
                if (rendered.channels != 1 || rendered.sampleRate != sampleRate) {
                    failures += "$id $stage format ${rendered.channels} channels at ${rendered.sampleRate} Hz, expected mono at $sampleRate Hz"
                }
                val actualHash = float32Sha256LE(rendered.samples)
                if (actualHash != expectedHash) {
                    failures += "$id $stage SHA-256 $actualHash, expected $expectedHash"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** Hash four little-endian raw IEEE754 bytes per sample, without a WAV header or quantization. */
    private fun float32Sha256LE(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val word = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            word.clear()
            word.putInt(sample.toRawBits())
            digest.update(word.array())
        }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
