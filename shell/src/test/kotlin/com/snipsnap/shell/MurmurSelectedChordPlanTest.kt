package com.snipsnap.shell

import com.snipsnap.synth.ChordStabs
import com.snipsnap.synth.MurmurKeptSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MurmurSelectedChordPlanTest {
    private fun low() = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()
    private fun wire() = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()

    @Test
    fun `all expanded tones validate before any clamping or native patch is returned`() {
        val error = assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromNotes(low(), listOf(48, 51, 55)) }
        assertTrue(error.message.orEmpty().contains("outside"))
        val plan = MurmurSelectedChordPlan.fromNotes(low(), listOf(44, 48, 51, 48))
        assertEquals(listOf(44, 48, 51), plan.notes)
        assertEquals(plan.notes, plan.patches().map { it.midi })
        assertTrue(plan.patches().all { it.config == low().config && it.velocity == low().velocity })
        assertEquals(plan.notes, plan.cellRecipes(1).map { it.midi })
        assertTrue(plan.cellRecipes(1).all { it.outputPolicy == com.snipsnap.synth.MurmurSelectedInstrumentSource.OutputPolicy.FACTORY_SINGLE_FINITE_V1 })
        val held = MurmurSelectedChordPlan.fromNotes(MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch(), listOf(60, 64, 67))
        assertTrue(held.cellRecipes(1).all { it.outputPolicy == com.snipsnap.synth.MurmurSelectedInstrumentSource.OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1 })
        assertTrue(held.cellRecipes(1).all { it.sourcePatch().velocity == .86f && it.cellPatch().velocity == .65f })
        assertEquals(36, low().midi)
        assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromNotes(low(), listOf(35, 36, 36)) }
        assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromNotes(wire(), listOf(95, 99, 102)) }
        assertEquals(listOf(89, 92, 96), MurmurSelectedChordPlan.fromNotes(wire(), listOf(89, 92, 96)).notes)
    }

    @Test
    fun `real chord note expansion keeps inversions wide root octave and every requested layer`() {
        val inversion = ChordStabs.Stab("Cm inversion", 48, listOf(-4, 0, 3))
        assertEquals(listOf(44, 48, 51), MurmurSelectedChordPlan.fromStab(low(), inversion).notes)
        val wide = ChordStabs.Stab("Wide", 48, listOf(0, 3, 7, 12))
        val wideError = assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromStab(low(), wide) }
        assertTrue(wideError.message.orEmpty().contains("outside"))
        assertEquals(listOf(48, 51, 55, 60, 63, 67, 72),
            MurmurSelectedChordPlan.fromStab(wire(), wide, listOf(0, 1)).notes)
        assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromStab(wire(), wide, listOf(0, 4)) }
    }

    @Test
    fun `empty over budget malformed and edited source requests refuse before producing a chord plan`() {
        assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromNotes(wire(), emptyList()) }
        val groups = assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromNotes(wire(), (48..56).toList()) }
        assertTrue(groups.message.orEmpty().contains("eight"))
        assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromStab(wire(), ChordStabs.Stab("Bad", Int.MAX_VALUE, listOf(Int.MAX_VALUE))) }
        assertFailsWith<IllegalArgumentException> { MurmurSelectedChordPlan.fromStab(wire(), ChordStabs.Stab("Bad", 48, listOf(0)), listOf(Int.MAX_VALUE)) }
        assertFailsWith<IllegalArgumentException> {
            MurmurSelectedChordPlan.fromNotes(wire().copy(macros = wire().macros + ("EDGE" to .91f)), listOf(48, 52, 55))
        }
    }
}
