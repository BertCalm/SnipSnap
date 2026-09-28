package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TerraKits] held to the measured facts it claims: every pad filled, every
 * topology reachable, and every declared [DrumClass] matching what the real
 * [Classifier] actually says about that pad's own render - the same
 * measured-not-guessed discipline [TerraTest] holds each topology to
 * individually, now held across the whole 16-pad kit at once.
 */
class TerraKitsTest {

    @Test
    fun `the world percussion kit fills all sixteen pads`() {
        val kit = TerraKits.classic()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null }, "every pad of a starter kit is filled")
    }

    @Test
    fun `all four topologies reach a pad`() {
        val voices = TerraKits.classic().mapNotNull { pad ->
            pad?.recipe?.let { PadRecipe.fromJsonValue(it) }?.patch?.voiceName
        }.toSet()
        assertEquals(TerraVoice.entries.map { it.name }.toSet(), voices)
    }

    /**
     * Each pad's declared class is checked against the real Classifier
     * output on that pad's own audio, not restated from the design intent -
     * this is what would have caught it immediately if a pad's declared
     * class had drifted from what TerraKits.kt actually renders.
     */
    @Test
    fun `every pad's declared class matches what the classifier actually hears`() {
        for ((i, pad) in TerraKits.classic().withIndex()) {
            val arranged = requireNotNull(pad) { "pad ${i + 1} is empty" }
            val measured = Classifier.classify(arranged.snip).drumClass
            assertEquals(
                arranged.drumClass, measured,
                "pad ${i + 1} is declared ${arranged.drumClass} but the classifier now hears $measured - " +
                    "either the declared class or the DSP behind it has drifted.",
            )
        }
    }

    /** Every pad's recipe round-trips through JSON and regenerates the same audio, bit-for-bit. */
    @Test
    fun `every pad's recipe regenerates its own audio`() {
        for ((i, pad) in TerraKits.classic().withIndex()) {
            val arranged = requireNotNull(pad) { "pad ${i + 1} is empty" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "pad ${i + 1} carries no recipe" })
            val regenerated = recipe.render()
            assertTrue(
                arranged.snip.samples.contentEquals(regenerated.samples),
                "pad ${i + 1}'s recipe did not regenerate the same audio it shipped with",
            )
        }
    }
}
