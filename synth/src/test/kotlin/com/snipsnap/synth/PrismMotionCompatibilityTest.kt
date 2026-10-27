package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Compare against the real pre-editor renderer on this JVM, without platform-specific PCM goldens. */
class PrismMotionCompatibilityTest {
    private fun legacy(motion: PrismMotion?) = motion?.let {
        LegacyStudioMotion(it.amplitudesB, it.morph, it.evolve, it.seconds, it.groupLag, it.amplitudesA)
    }

    @Test
    fun `accepted finite openings keep the exact pre-editor samples after edits and saved replay`() {
        for (base in PrismOpeningPresets.all()) {
            val variants = listOf(base, base.copy(
                macros = base.macros + mapOf("TUNE" to 1f, "GRAIN" to .67f, "DRIFT" to .52f, "BODY" to .24f),
                velocity = .35f,
                motion = assertNotNull(base.motion).copy(morph = .17f, evolve = .73f, seconds = 1.3f, groupLag = .61f),
            ))
            for (patch in variants) {
                val before = LegacyStudioPrism.render(patch.voice, patch.macros, patch.velocity, patch.template, legacy(patch.motion))
                val after = patch.render()
                assertContentEquals(before.samples, after.samples, "${patch.name}: finite source sound changed")
                assertEquals(before.sampleRate, after.sampleRate)
                assertEquals(before.channels, after.channels)
                val replay = PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).render()
                assertContentEquals(before.samples, replay.samples, "${patch.name}: saved replay changed")
            }
        }
    }

    @Test
    fun `ordinary finite and held presets retain the exact pre-editor renderer`() {
        for (voice in listOf(PrismVoice.KEY, PrismVoice.ORGAN, PrismVoice.GLASS)) {
            val base = PrismPresets.forVoice(voice).first { it.motion == null }
            for (hold in listOf(0f, 1f)) {
                val patch = base.copy(macros = base.macros + ("HOLD" to hold))
                val before = LegacyStudioPrism.render(patch.voice, patch.macros, patch.velocity, patch.template)
                assertContentEquals(before.samples, patch.render().samples, "$voice HOLD $hold: legacy sound changed")
            }
        }
    }
}
