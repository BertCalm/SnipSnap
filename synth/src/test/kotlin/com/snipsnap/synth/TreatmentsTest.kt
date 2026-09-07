package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `Shuffle.TREATMENTS` is picked by index from a seeded `Random` in
 * `withRemixBank` — reordering, adding, or removing an entry changes what
 * every previously-saved seed produces. This is not covered by the pad-sheet
 * task brief; it is added because the launching agent flagged
 * `Shuffle.TREATMENTS` reproducibility as the single most important
 * constraint on this task, and no existing test pinned the list.
 */
class TreatmentsTest {

    @Test
    fun `bank B's five treatments, in the order the seeded shuffle depends on`() {
        assertEquals(
            listOf("reversed", "crushed", "slapback", "washed", "punched"),
            Shuffle.TREATMENTS.map { it.first },
        )
    }

    @Test
    fun `the named characters are the bank's five and then the extras, smeared among them`() {
        assertEquals(
            listOf(
                "reversed", "crushed", "slapback", "washed", "punched",
                "smeared", "ghosted", "stopped", "started", "skimmed", "dubbed", "swelled",
            ),
            Treatments.names,
        )
        assertTrue(Treatments.EXTRA.none { it.first in Shuffle.TREATMENTS.map { t -> t.first } }, "an extra never shadows a bank name")
    }

    @Test
    fun `smeared takes the attack out of a snare and records a smear-only recipe`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        fun headShare(s: Snip): Double {
            val head = (0.02f * s.sampleRate).toInt() * s.channels
            var h = 0.0
            var all = 0.0
            for (i in s.samples.indices) {
                val e = s.samples[i] * s.samples[i].toDouble()
                if (i < head) h += e
                all += e
            }
            return h / all
        }
        val treated = Treatments.apply("smeared", snare, 1f)
        assertTrue(headShare(treated.snip) < headShare(snare), "less of the energy sits in the first 20 ms")
        val recipe = PadRecipe.fromJsonValue(treated.recipe)
        assertEquals("smeared", recipe.treatment)
        assertEquals(1f, recipe.amount)
        assertEquals(mapOf("AMOUNT" to 0.85f), recipe.fx!!.smear)
        assertTrue(recipe.fx.eq == null && recipe.fx.spring == null, "a smear-only chain")
    }

    @Test
    fun `AMT scales the smear like every other section`() {
        assertEquals(0.85f * 0.5f, Treatments.chain("smeared", 0.5f).smear!!.getValue("AMOUNT"), 1e-6f)
    }

    @Test
    fun `AMT zero bypasses the chain entirely`() {
        assertTrue(Treatments.chain("crushed", 0f).isBypass)
    }

    @Test
    fun `AMT zero leaves the audio alone`() {
        val src = Snip(FloatArray(2205) { i -> if (i % 2 == 0) 0.5f else -0.5f }, 1, 44_100)
        val treated = Treatments.apply("crushed", src, 0f)
        assertEquals(src.samples.toList(), treated.snip.samples.toList())
    }

    @Test
    fun `an unknown treatment is refused even at AMT zero`() {
        assertFailsWith<IllegalArgumentException> { Treatments.chain("wobbel", 0f) }
    }

    @Test
    fun `ghosted, stopped and started each record a one-section recipe`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        val ghosted = PadRecipe.fromJsonValue(Treatments.apply("ghosted", snare, 1f).recipe)
        assertEquals(mapOf("AMOUNT" to 0.9f), ghosted.fx!!.ghost)
        val stopped = PadRecipe.fromJsonValue(Treatments.apply("stopped", snare, 0.5f).recipe)
        assertEquals(0.3f, stopped.fx!!.motion!!.getValue("STOP"), 1e-6f)
        val started = PadRecipe.fromJsonValue(Treatments.apply("started", snare, 1f).recipe)
        assertEquals(mapOf("START" to 0.5f), started.fx!!.motion)
    }
}
