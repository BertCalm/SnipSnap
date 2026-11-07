package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent raw-audio references frozen before the native extension port.
 * The fixture comes from c9807eba and the published listening studies, never
 * from the implementation under test. Regenerating it is a sound-model change.
 */
class OnsetNativeAudioCompatibilityTest {
    @Test fun `native extensions reproduce every Study 009 raw listening file exactly`() {
        val cases = referenceCases().filter { it.getValue("group").str() == "study-009-extension" }
        assertEquals(21, cases.size)
        assertEquals(setOf("TOM", "PERC_PLUCK", "PERC_WOOD", "PERC_METAL"), cases.map { it.getValue("voice").str() }.toSet())
        assertEquals(7, cases.map { it.getValue("setupId").str() }.toSet().size)
        for (case in cases) assertRawReference(case)
    }

    @Test fun `legacy models and accepted production bank retain frozen raw PCM`() {
        val cases = referenceCases().filter { it.getValue("group").str() != "study-009-extension" }
        assertEquals(40, cases.count { it.getValue("group").str() == "legacy-core" })
        assertEquals(16, cases.count { it.getValue("group").str() == "accepted-production" })
        assertEquals(setOf(1, 2, 3), cases.map { it.getValue("modelVersion").int() }.toSet())
        for (case in cases) {
            val legacy = assertRawReference(case)
            if (case.getValue("modelVersion").int() == 3) {
                val voice = OnsetVoice.valueOf(case.getValue("voice").str())
                val macros = case.getValue("macros").obj().mapValues { it.value.num().toFloat() }
                val current = Onset.render(voice, macros, modelVersion = 4)
                assertContentEquals(legacy.samples, current.samples, "${case.getValue("id").str()} model4 recolored the accepted core")
                assertEquals(case.getValue("rawWavSha256").str(), rawHash(current))
            }
        }
    }

    @Test fun `new native voice ordinals append after the four saved core voices`() {
        assertEquals(
            listOf("KICK", "HAT_CLOSED", "SNARE", "HAT_OPEN", "TOM", "PERC_PLUCK", "PERC_WOOD", "PERC_METAL"),
            OnsetVoice.entries.map { it.name },
        )
    }

    private fun assertRawReference(case: Map<String, com.snipsnap.json.JsonValue>): com.snipsnap.audio.Snip {
        val id = case.getValue("id").str()
        val voice = OnsetVoice.valueOf(case.getValue("voice").str())
        val controls = case.getValue("macros").obj().mapValues { it.value.num().toFloat() }
        assertEquals(setOf("TUNE", "DECAY", "BLEND", "SNAP", "BODY", "EDGE"), controls.keys, id)
        val patch = OnsetPatch(id, voice, controls, modelVersion = case.getValue("modelVersion").int())
        val restored = OnsetPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored, id)
        val rendered = restored.render()
        assertEquals(44_100, rendered.sampleRate, id)
        assertEquals(1, rendered.channels, id)
        assertEquals(case.getValue("frames").int(), rendered.frameCount, id)
        assertTrue(rendered.samples.all { it.isFinite() }, id)
        assertEquals(case.getValue("rawWavSha256").str(), rawHash(rendered), "$id raw PCM24 differs from frozen reference")
        return rendered
    }

    private fun referenceCases(): List<Map<String, com.snipsnap.json.JsonValue>> {
        val text = checkNotNull(javaClass.getResourceAsStream("/onset/native-audio-reference.json"))
            .bufferedReader().use { it.readText() }
        val value = Json.parse(text).obj()
        assertEquals("ONSET_NATIVE_AUDIO_REFERENCE_V1", value.getValue("format").str())
        return value.getValue("cases").arr().map { it.obj() }
    }

    private fun rawHash(snip: com.snipsnap.audio.Snip): String {
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24) }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
