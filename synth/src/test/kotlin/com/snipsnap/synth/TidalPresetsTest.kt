package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TidalPresetsTest {
    @Test
    fun `factory encounters keep the six finite complete recipes in their authored order`() {
        val all = TidalPresets.all()
        assertEquals(listOf("TWO BODIES", "DISTANT PAIR", "SHARED PASSAGE", "COUNTERARM",
            "TILTED THREADS", "AFTER PASSAGE"), all.map { it.name })
        assertEquals(listOf(TidalVoice.PAIR, TidalVoice.PAIR, TidalVoice.PAIR,
            TidalVoice.STREAM, TidalVoice.STREAM, TidalVoice.STREAM), all.map { it.voice })
        for (patch in all) {
            assertTrue(patch.name.length <= 14)
            assertEquals(patch.name.uppercase(), patch.name)
            assertEquals(2, patch.model)
            assertEquals(60, patch.midi)
            assertEquals(.75f, patch.velocity)
            assertEquals(0L, patch.seed)
            assertEquals(setOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL", "HOLD"), patch.macros.keys)
            assertEquals(0f, patch.macros.getValue("HOLD"))
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
    }

    @Test
    fun `factory discovery uses the shared dispatcher and leaves every encounter dry`() {
        assertEquals(TidalPresets.all(), Presets.all().filter { it.engine == TidalPatch.ENGINE })
        for (voice in TidalVoice.entries) {
            assertEquals(TidalPresets.forVoice(voice), Presets.forVoice("TIDAL", voice.name))
            for (patch in TidalPresets.forVoice(voice)) {
                assertEquals(patch, Presets.byName("TIDAL", voice.name, patch.name))
                assertNull(Presets.landingFor("TIDAL", voice.name, patch.macros))
            }
        }
        assertTrue(Presets.forVoice("TIDAL", "BRIDGE").isEmpty())
        assertNull(Presets.byName("TIDAL", "PAIR", "Missing"))
    }
}
