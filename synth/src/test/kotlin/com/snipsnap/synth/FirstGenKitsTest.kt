package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The starter kits VELVET, TONEWHEEL and RESIN gained in the engine scan's first-generation round. */
class FirstGenKitsTest {

    private fun check(name: String, kit: List<ArrangedPad?>, walkVoice: String, tuneSemitones: Int) {
        assertEquals(16, kit.size, name)
        val semitones = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        val names = mutableSetOf<String>()
        for ((i, pad) in kit.withIndex()) {
            val present = requireNotNull(pad) { "$name pad ${i + 1} is empty" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(present.recipe) { "$name pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch)
            assertEquals(null, recipe.fx, "$name pad ${i + 1} lands dry")
            assertEquals(DrumClass.TONAL, present.drumClass, "$name pad ${i + 1}")
            assertTrue(present.snip.samples.any { it != 0f }, "$name pad ${i + 1} renders silence")
            names += patch.name
            if (i < 8) {
                assertEquals(walkVoice, patch.voiceName, "$name pad ${i + 1}")
                assertEquals(semitones[i] / tuneSemitones.toFloat(), patch.macros.getValue("TUNE"), 1e-6f, "$name pad ${i + 1} is off the walk")
            }
        }
        assertEquals(16, names.size, "$name: every pad is a different sound")
    }

    @Test fun `VELVET kit walks BASS then lands two presets of each voice`() =
        check("VELVET", SynthKits.velvet(), "BASS", Velvet.TUNE_SEMITONES)

    @Test fun `TONEWHEEL kit walks FULL then lands FULL SOUL and STAB presets`() =
        check("TONEWHEEL", SynthKits.tonewheel(), "FULL", Tonewheel.TUNE_SEMITONES)

    @Test fun `RESIN kit walks BASS then lands BASS LEAD and BRASS presets`() =
        check("RESIN", SynthKits.resin(), "BASS", Resin.TUNE_SEMITONES)
}
