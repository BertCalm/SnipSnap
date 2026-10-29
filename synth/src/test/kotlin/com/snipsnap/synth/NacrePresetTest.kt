package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Starting recipes pin the new production model without assuming factory-roster promotion. */
class NacrePresetTest {
    @Test
    fun `three starting recipes preserve the accepted source controls and their return settings`() {
        val presets = NacrePresets.all()
        assertEquals(listOf("Pearl Close", "Coral Reply", "Kelp Passage"), presets.map { it.name })
        assertEquals(presets, NacrePresets.forVoice(NacreVoice.PEARL))
        for (patch in presets) {
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
        for (patch in NacrePresets.all()) {
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            assertContentEquals(patch.render().samples, restored.render().samples,
                "${patch.name} lost a production return setting during replay")
        }
    }
}
