package com.snipsnap.shell

import com.snipsnap.synth.MurmurBody
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Metadata arithmetic only; none of these tests invokes native MURMUR synthesis. */
class MurmurSelectedInstrumentMakerTest {
    @Test
    fun `all allowed authored roots stay present in exactly bounded midpoint zones at one semitone reach`() {
        for (id in listOf(MurmurKeptSources.FINITE_WIRE, MurmurKeptSources.STEADY_C2)) {
            val source = MurmurKeptSources.byId(id)
            for (midi in source.supportedMidi) {
                val spec = MurmurSelectedInstrumentMaker.Spec(source.factoryPatch().copy(midi = midi))
                val zones = MurmurSelectedInstrumentMaker.zones(spec)
                assertTrue(midi in zones.map { it.root })
                assertEquals(source.supportedMidi.toList(), zones.flatMap { (it.low..it.high).toList() })
                assertEquals(zones.size, zones.map { it.root }.distinct().size)
                assertTrue(zones.all { z -> (z.low..z.high).all { kotlin.math.abs(it - z.root) <= 1 } })
                assertEquals(source.supportedMidi.first, zones.first().low)
                assertEquals(source.supportedMidi.last, zones.last().high)
            }
        }
    }

    @Test
    fun `native cell touches are absolute while complete authored root velocity and model stay separately frozen`() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch().copy(midi = 37, velocity = .86f)
        val spec = MurmurSelectedInstrumentMaker.Spec(source)
        val recipes = MurmurSelectedInstrumentMaker.recipes(spec)
        assertEquals(22 * 3, recipes.size)
        assertTrue(37 in recipes.keys.map { it.midi })
        assertEquals(listOf(1..42, 43..84, 85..127), MurmurSelectedInstrumentMaker.VELOCITY_WINDOWS)
        for ((cell, recipe) in recipes) {
            assertEquals(source, recipe.sourcePatch())
            assertEquals(source.copy(midi = cell.midi, velocity = listOf(.35f, .65f, 1f)[cell.touchIndex]), recipe.cellPatch())
            assertEquals(MurmurBody.HOLLOW_WIRE_V1, recipe.cellPatch().config.body)
            assertEquals(4, recipe.cellPatch().config.model)
            assertEquals(MurmurSelectedInstrumentSource.OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1, recipe.outputPolicy)
            assertEquals(.35f, recipe.releaseSeconds)
        }
        (source.macros as MutableMap<String, Float>)["HOLD"] = 0f
        (source.config.ratios as MutableList<Double>)[0] = 9.0
        assertEquals(1f, spec.sourcePatch().macros.getValue("HOLD"))
        assertEquals(1.0, spec.sourcePatch().config.ratios.first())
        assertEquals(.86f, spec.sourcePatch().velocity)
    }

    @Test
    fun `unqualified edits zero authored touch and wrong sampler release do not enter dedicated maker`() {
        val wire = MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch()
        assertTrue(MurmurSelectedInstrumentMaker.supports(wire))
        val edited = wire.copy(macros = wire.macros + ("MOUTH" to .73f))
        assertFalse(MurmurSelectedInstrumentMaker.supports(edited))
        val error = assertFailsWith<IllegalArgumentException> { MurmurSelectedInstrumentMaker.Spec(edited) }
        assertTrue(error.message.orEmpty().contains("selected shape"))
        assertFalse(MurmurSelectedInstrumentMaker.supports(wire.copy(velocity = 0f)))
        for (release in listOf(0f, -1f, 2f, Float.NaN)) {
            val bad = assertFailsWith<IllegalArgumentException> { MurmurSelectedInstrumentMaker.Spec(wire, release) }
            assertTrue(bad.message.orEmpty().contains("sampler release"))
        }
        val low = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()
        assertFalse(MurmurSelectedInstrumentMaker.supports(low.copy(macros = low.macros + ("HOLD" to 1f))))
        assertTrue(LoopPadMaker.supports(com.snipsnap.synth.MurmurPresets.original().single { it.name == "OPEN STRING" }))
        assertFalse(LoopPadMaker.supports(wire))
    }
}
