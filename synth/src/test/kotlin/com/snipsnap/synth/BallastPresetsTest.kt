package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BallastPresetsTest {
    @Test
    fun `the roster is complete named and legally clean`() {
        for (voice in BallastVoice.entries) {
            val presets = BallastPresets.forVoice(voice)
            assertEquals(2, presets.size)
            assertEquals(2, presets.map { it.name }.toSet().size)
            for (preset in presets) {
                assertEquals(preset.name.uppercase(), preset.name)
                assertTrue(preset.name.length <= 14)
                assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name))
                assertEquals(Ballast.macrosFor(voice).map { it.name }.toSet(), preset.macros.keys)
            }
        }
    }

    @Test
    fun `every preset round trips and dispatchers know BALLAST`() {
        for (preset in BallastPresets.all()) {
            val restored = BallastPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored)
            assertContentEquals(preset.render().samples, restored.render().samples)
        }
        assertEquals(BallastPresets.forVoice(BallastVoice.WIRE), Presets.forVoice("BALLAST", "WIRE"))
        assertTrue(Presets.all().containsAll(BallastPresets.all()))
        assertEquals(16, SynthKits.ballast().size)
    }
}
