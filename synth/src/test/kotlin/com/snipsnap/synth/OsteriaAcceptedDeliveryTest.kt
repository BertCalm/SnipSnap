package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Factory promotion may rename a chip, never silently alter its accepted recipe. */
class OsteriaAcceptedDeliveryTest {
    @Test
    fun `six musical voices expose every accepted performance and retain the original Note roster`() {
        val factory = OsteriaPresets.all()
        assertEquals(6, OsteriaVoice.entries.size)
        assertEquals(12, OsteriaPresets.selected().size)
        assertEquals(13, factory.size) // twelve accepted sounds plus the original Evolving Note
        assertEquals(OsteriaPresets.original(), factory.take(3))
        for (voice in OsteriaVoice.entries) {
            val presets = Presets.forVoice("OSTERIA", voice.name)
            assertEquals(OsteriaPresets.forVoice(voice), presets)
            assertTrue(presets.size >= 2, voice.name)
            assertEquals(presets.size, presets.map { it.name.uppercase() }.distinct().size)
            for (preset in presets) assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
        }
        for (accepted in OsteriaPresets.selected()) {
            assertEquals(1, factory.count { it.copy(name = accepted.name) == accepted }, accepted.name)
        }
    }

    @Test
    fun `NOTE stays a display label and legacy QUARTET recipes retain their versioned identity`() {
        val current = OsteriaPresets.original().first()
        assertEquals("QUARTET", current.voiceName)
        val legacy = current.copy(config = current.config.copy(model = 1))
        for (patch in listOf(current, legacy)) {
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val edited = Patches.edited(patch, "EDITED", patch.macros + ("SHORE" to .61f)) as OsteriaPatch
            assertEquals(patch.config, edited.config)
            assertEquals(patch.midi, edited.midi)
            assertEquals(patch.velocity, edited.velocity)
            assertEquals(patch.voice, edited.voice)
        }
    }
}
