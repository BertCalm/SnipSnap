package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChamberPresetsTest {
    @Test
    fun `factory promotion preserves the six complete finite listening recipes`() {
        val expected = listOf(
            Triple("Four Answers", ChamberVoice.HYBRID, listOf(.50f, .45f, .40f, .90f, .60f)),
            Triple("Soft Split", ChamberVoice.HYBRID, listOf(.20f, .45f, .40f, .90f, .25f)),
            Triple("Glass Corners", ChamberVoice.HYBRID, listOf(.80f, .45f, .70f, 1f, .85f)),
            Triple("Aquarium Ceiling", ChamberVoice.AQUARIUM, listOf(.35f, .60f, .95f, .80f, .70f)),
            Triple("Quiet Canopy", ChamberVoice.AQUARIUM, listOf(.20f, .35f, .95f, .90f, .25f)),
            Triple("High Water", ChamberVoice.AQUARIUM, listOf(.80f, .65f, .95f, 1f, .85f)),
        )
        val controls = listOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT")
        val all = ChamberPresets.all()
        assertEquals(expected.size, all.size)
        assertEquals(expected.map { it.first }, all.map { it.name })
        for ((patch, identity) in all.zip(expected)) {
            assertEquals(identity.second, patch.voice)
            assertEquals(controls.zip(identity.third).toMap() + ("HOLD" to 0f), patch.macros)
            assertEquals(3, patch.model, "factory recipes retain the accepted membrane mix revision")
            assertEquals(60, patch.midi)
            assertEquals(.75f, patch.velocity)
            assertEquals(0L, patch.seed)
            assertEquals(ChamberConfig.forVoice(patch.voice), patch.configuration)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
        }
    }

    @Test
    fun `factory discovery serves both room voices and lands every recipe dry`() {
        assertEquals(ChamberPresets.all(), Presets.all().filterIsInstance<ChamberPatch>())
        for (voice in ChamberVoice.entries) {
            val bank = ChamberPresets.forVoice(voice)
            assertEquals(3, bank.size)
            assertEquals(bank, Presets.forVoice("CHAMBER", voice.name))
            for (patch in bank) {
                assertEquals(patch, Presets.byName("CHAMBER", voice.name, patch.name))
                assertNull(Presets.landingFor("CHAMBER", voice.name, patch.macros))
            }
            assertNull(Presets.byName("CHAMBER", voice.name, "Missing"))
        }
        assertTrue(Presets.forVoice("CHAMBER", "TIMBER").isEmpty())
        assertTrue(Presets.forVoice("CHAMBER", "UNKNOWN").isEmpty())
    }
}
