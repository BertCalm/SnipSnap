package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MUTATE card's words, held to the house style
 * (`docs/superpowers/specs/2026-10-04-mutate-card-redesign-design.md`,
 * "Testing", the copy laws).
 *
 * The card's labels live in `MutateSheet`, where PersonalityTest's
 * reflective shout law cannot see them, and the toasts it shows are
 * `Copy` functions, which that law cannot see either (it reflects over
 * fields). So this file holds both by hand: one word for the one input,
 * PARTNER; no word that means two things on the card; every label in
 * capitals and inside its budget.
 */
class MutateWordsTest {

    /**
     * The words the redesign retires from MUTATE's user-facing copy: one
     * input had six names on and around the card (parent, partner,
     * neighbour, crate, deal, another kit). PARTNER is the one left.
     */
    private val retired = Regex("""\b(PARENTS?|NEIGHBOU?RS?|CRATES?|DEALS?)\b""")

    private fun assertNoRetiredWord(where: String, text: String) {
        val hit = retired.find(text.uppercase())
        assertTrue(hit == null, "$where says '${hit?.value}', a word MUTATE retired for PARTNER: '$text'")
    }

    @Test
    fun `the toasts MUTATE shows say PARTNER, never a retired word`() {
        assertEquals(
            "SPLICE: A01 × B07. THE TAKE BEFORE IT SLEEPS IN THE BIN.",
            Copy.mutated("SPLICE", "A01", "B07"),
        )
        assertEquals("UNDONE. THE TAKE BEFORE THE LAST MUTATE IS BACK FROM THE BIN.", Copy.UNMUTATED)
        assertEquals("THE SHELF HOLDS NO OTHER PAD. ROULETTE HAS NOTHING TO PICK.", Copy.CRATE_EMPTY)
        assertEquals("NOT A PARTNER: THAT FILE IS SILENT.", Copy.fileRefused("that file is silent"))
        assertEquals(
            "MUTATE (SPLICE WITH KIT A02) NEEDS ITS PARTNER - NOT CARRIED.",
            Copy.replayNeedsPartner("SPLICE", listOf("KIT A02")),
        )
        for ((where, text) in listOf(
            "Copy.mutated" to Copy.mutated("MORPH", "A02 SNARE", "SOUL B02"),
            "Copy.UNMUTATED" to Copy.UNMUTATED,
            "Copy.CRATE_EMPTY" to Copy.CRATE_EMPTY,
            "Copy.fileRefused" to Copy.fileRefused("too big for the tape"),
            "Copy.replayNeedsPartner" to Copy.replayNeedsPartner("BECOME", listOf("SOUL A03")),
            "Copy.drifted" to Copy.drifted("A02", "SOUL:B03"),
        )) {
            assertNoRetiredWord(where, text)
        }
    }
}
