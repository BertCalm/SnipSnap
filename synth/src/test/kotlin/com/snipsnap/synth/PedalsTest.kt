package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The registry's shape: the laws every entry obeys, so the fiftieth pedal costs what the third does. */
class PedalsTest {

    @Test
    fun `words and keys are unique, and every chain names real sections with no macro at its neutral`() {
        val entries = Pedals.entries
        assertEquals(entries.size, entries.map { it.word }.distinct().size, "a word is drawn twice")
        assertEquals(entries.size, entries.map { it.key }.distinct().size, "a key is saved twice")
        for (e in entries) {
            assertTrue(e.sections.isNotEmpty(), "${e.word} sets nothing")
            for (name in e.sections) {
                val neutrals = FxChain.macrosOf(name).associate { it.name to it.neutral }
                for ((macro, v) in e.chain.section(name)!!) {
                    assertTrue(v != (neutrals[macro] ?: 0f), "${e.word} sets $name $macro at its neutral: AMT could never move it")
                }
            }
        }
    }

    @Test
    fun `a pedal's key is a rack character and a board's is not, so treat and a pick never disagree`() {
        for (e in Pedals.entries) {
            if (e.isBoard) assertFalse(e.key in Treatments.names, "${e.word} is a board with a character's name")
            else {
                assertTrue(e.key in Treatments.names, "${e.word}'s key is not a character")
                assertEquals(Treatments.chain(e.key, 1f), e.chain, "${e.word} is not the character it names")
            }
        }
    }

    @Test
    fun `sides follow the rack - the amp sits at AMP and cannot move, a board containing it too`() {
        for (e in Pedals.entries) {
            val hasAmp = "valve" in e.sections
            assertEquals(hasAmp, e.side == PedalSide.AMP, "${e.word} sets ${e.sections} on the ${e.side.id} side")
            if (hasAmp) assertEquals(listOf(PedalSide.AMP), e.sides)
            if (e.isBoard) assertFalse(e.movable, "a board (${e.word}) moves")
            if (e.movable) assertEquals(listOf(PedalSide.PRE, PedalSide.POST), e.sides)
            if (!e.isBoard && !hasAmp) assertEquals(Pedals.sideOf(e.sections.single()), e.side, "${e.word}'s default side is not its slot's")
        }
        assertEquals(PedalSide.PRE, Pedals.sideOf("wah"))
        assertEquals(PedalSide.AMP, Pedals.sideOf("valve"))
        assertEquals(PedalSide.POST, Pedals.sideOf("echo"))
    }

    @Test
    fun `the fade flag is true exactly where a chain's DRIVE sits above VALVE's own`() {
        val drive = Pedals.byKey("driven")
        assertTrue(drive.fade, "DRIVE runs the hot amp")
        assertTrue(Pedals.byKey("rig").fade, "RIG carries DRIVE")
        assertFalse(Pedals.byKey("compressed").fade, "COMP has no amp")
        assertFalse(Treatments.needsPreAmpFade(Treatments.chain("amped", 1f)), "amped sits under VALVE's default and never faded")
        assertFalse(Treatments.needsPreAmpFade(FxChain()), "no amp, no fade")
    }

    @Test
    fun `DRIVE, COMP and the wah's three moves draw chips, RIG and DELAY wait for their gates`() {
        assertEquals(listOf("DRIVE", "COMP", "WAH", "TOUCH", "ROCK"), Pedals.gated().map { it.word })
        assertEquals(listOf("DRIVE", "COMP", "WAH", "TOUCH", "ROCK", "RIG", "DELAY"), Pedals.entries.map { it.word })
        assertTrue(Pedals.byKey("rig").isBoard)
        assertEquals(listOf("squash", "valve", "echo"), Pedals.byKey("rig").sections, "RIG is a compressor into the amp into a slapback, in rack order")
    }

    @Test
    fun `an unknown key is refused by name, with the library listed`() {
        val e = assertFailsWith<IllegalArgumentException> { Pedals.byKey("sparkled") }
        assertTrue(e.message!!.contains("driven"), e.message)
    }

    @Test
    fun `chainAt follows the character law - full at 0_999 and up, faded between, nothing at 0`() {
        val e = Pedals.byKey("compressed")
        assertEquals(e.chain, e.chainAt(1f))
        assertEquals(e.chain, e.chainAt(0.999f))
        assertEquals(Treatments.chain("compressed", 0.7f), e.chainAt(0.7f))
        assertTrue(e.chainAt(0f).isBypass)
    }
}
