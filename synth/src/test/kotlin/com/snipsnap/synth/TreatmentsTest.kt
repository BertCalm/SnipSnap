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
            Treatments.names,
        )
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
}
