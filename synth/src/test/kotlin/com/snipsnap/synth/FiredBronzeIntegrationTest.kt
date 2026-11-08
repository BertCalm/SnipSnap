package com.snipsnap.synth

import kotlin.test.*

class FiredBronzeIntegrationTest {
    @Test fun `editable factory kit preserves all 64 approved renders and metadata`() {
        val approved = FiredBronzePerformance.study()
        val editable = FiredBronzeInstrument.kit()
        assertEquals(16, editable.size)
        approved.zip(editable).forEach { (old, new) ->
            assertContentEquals(old.snip.samples, new.snip.samples)
            old.softVariants.zip(new.softVariants).forEach { (a, b) -> assertContentEquals(a.samples, b.samples) }
            assertEquals(old.level, new.level)
            assertEquals(old.muteGroup, new.muteGroup)
            assertEquals(old.layerNames, new.layerNames)
            val recipe = PadRecipe.fromJsonValue(new.recipe!!)
            assertContentEquals(new.snip.samples, recipe.render().samples)
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
        }
    }

    @Test fun `macros remain editable after patch roundtrip and each changes the render`() {
        val p = FiredBronzePatch("Edge Ring", FiredBronzeDryKit.Pad.TIN)
        val base = p.render()
        for (spec in FiredBronzeInstrument.macros) {
            val changed = p.withMacros(p.macros + (spec.name to if (spec.default < .5f) .9f else .1f))
            val restored = Patches.fromJsonValue(changed.toJsonValue())
            val rendered = restored.render()
            assertEquals(changed, restored)
            assertFalse(base.samples.contentEquals(rendered.samples), spec.name)
            assertTrue(rendered.samples.all { it.isFinite() && kotlin.math.abs(it) <= 1f }, spec.name)
            assertContentEquals(rendered.samples, restored.render().samples)
        }
        assertFailsWith<IllegalArgumentException> { p.withMacros(mapOf("HEAT" to Float.NaN)) }
        assertEquals(FiredBronzeInstrument.defaults(), FiredBronzeInstrument.settled(mapOf("HEAT" to Float.NaN)))
    }
}
