package com.snipsnap.synth

import kotlin.test.*
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class BackwashPromotionTest {
    @Test fun `factory hard strikes match the published approved WAVs`() {
        val fixture = Json.parse(requireNotNull(javaClass.getResource("/backwash/accepted-processing.json")).readText()).arr()
        for (entry in fixture) {
            val item = entry.obj()
            val voice = BackwashVoice.valueOf(item.getValue("voice").str())
            val patch = BackwashPresets.forVoice(voice).single { it.name.startsWith(item.getValue("prefix").str()) }
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, patch.render(), WavWriter.BitDepth.PCM_24) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(item.getValue("sha256").str(), hash, patch.name)
        }
    }
    @Test fun `unversioned recipes retain the full original knob range`() {
        val macros = Backwash.defaults(BackwashVoice.SNARE).mapValues { 1f }
        val current = BackwashPatch("Saved", BackwashVoice.SNARE, macros)
        val fields = current.toJsonValue().entries.toMutableMap().apply { remove("model") }
        val old = BackwashPatch.fromJsonValue(com.snipsnap.json.JsonValue.Obj(fields)) as BackwashPatch
        assertEquals(3, old.model)
        assertContentEquals(BackwashV3.render(old.voice, macros).samples, old.render().samples)
        assertEquals(3, (Patches.edited(old, "Renamed", macros) as BackwashPatch).model)
        assertFalse(current.render().samples.contentEquals(old.render().samples))
        assertFailsWith<IllegalArgumentException> { current.copy(model = 99) }
    }

    @Test fun `factory treatments reproduce the approved study at all source velocities`() {
        for (voice in BackwashVoice.entries) {
            val presets = BackwashPresets.forVoice(voice)
            for ((prefix, treatment) in listOf("Seasick" to BackwashTreatment.SEASICK,
                "Clamped" to BackwashTreatment.CLAMPED, "Overloaded" to BackwashTreatment.OVERLOADED,
                "Pushed" to BackwashTreatment.COMBINED)) {
                val patch = presets.single { it.name.startsWith(prefix) }
                assertEquals(patch, BackwashPatch.fromJsonText(patch.toJsonText()))
                for (velocity in listOf(.3f, .6f, 1f)) {
                    val expected = BackwashProcessingStudy.render(voice, emptyMap(), velocity, treatment)
                    val actual = Backwash.render(voice, patch.macros, velocity)
                    assertContentEquals(expected.samples, actual.samples, "$voice $prefix $velocity")
                }
            }
        }
        assertEquals(16, BackwashPresets.processingKitOrder().map { it.name }.toSet().size)
    }
}
