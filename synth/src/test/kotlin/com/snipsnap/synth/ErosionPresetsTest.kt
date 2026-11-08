package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErosionPresetsTest {
    @Test fun `factory discovery exposes the accepted recipes without changing their values`() {
        val expected = ErosionBank.entries().map { it.patch }
        assertEquals(listOf("THUD", "CHIP", "RASP", "WIRE", "DUST", "DROPS", "SPLINTER", "SCAR"), expected.map { it.name })
        assertEquals(expected, ErosionPresets.all())
        assertEquals(expected, Presets.all().filter { it.engine == ErosionPatch.ENGINE })
        for (voice in ErosionVoice.entries) {
            assertTrue(Presets.forVoice("EROSION", voice.name).isNotEmpty())
            assertEquals(expected.filter { it.voice == voice }, Presets.forVoice("EROSION", voice.name))
        }
        for (patch in expected) {
            assertEquals(Erosion.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys)
            assertEquals(patch, Presets.byName("EROSION", patch.voiceName, patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
            // Reopened soft touches must keep their native dynamics when the picker edits a macro.
            val soft = patch.copy(velocity = .35f)
            val edited = Patches.edited(soft, "Edited", soft.macros + ("FEED" to .42f)) as ErosionPatch
            assertEquals(soft.velocity, edited.velocity)
            assertEquals(soft.model, edited.model)
        }
        assertTrue(Presets.forVoice("EROSION", "UNKNOWN").isEmpty())
    }
}
