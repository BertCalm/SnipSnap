package com.snipsnap.shell

import com.snipsnap.synth.AquariumControls
import com.snipsnap.synth.AquariumEditor
import com.snipsnap.synth.AquariumRenderRecipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AquariumSummaryTest {
    @Test
    fun `a pad with no print says what the section holds, and never untouched`() {
        assertEquals(AquariumSummary.HOLDS, AquariumSummary.line(null, marked = false))
        assertFalse("UNTOUCHED" in AquariumSummary.HOLDS)
    }

    @Test
    fun `a marker the recipe cannot be read from says so`() {
        assertEquals(AquariumSummary.UNREADABLE, AquariumSummary.line(null, marked = true))
    }

    @Test
    fun `a dry recipe reads bypassed`() {
        assertEquals("BYPASSED", AquariumSummary.line(AquariumRenderRecipe(), marked = true))
    }

    @Test
    fun `a kept print reads its preset, or custom settings`() {
        val preset = AquariumControls.presets.first { !AquariumEditor().selectPreset(it.id).render.isBypass }
        val kept = AquariumEditor().selectPreset(preset.id)
        assertEquals("KEPT · ${preset.name}", AquariumSummary.line(kept.render, marked = true))

        // Nudge one control off the preset's own value: the settings match no preset now.
        val control = AquariumControls.effect("reef").primary.first { it.id != AquariumControls.effect("reef").mixId }
        val now = kept.render.settings.parameters.getValue(control.id)
        val moved = listOf(0.21f, 0.77f).map(control::fromNormalized).first { it != now }
        val custom = kept.set(control.id, moved)
        assertEquals(null, custom.presetId, "no preset owns the nudged settings")
        assertEquals("KEPT · ${AquariumSummary.CUSTOM}", AquariumSummary.line(custom.render, marked = true))
    }

    @Test
    fun `every line fits the strip`() {
        val lines = listOf(AquariumSummary.HOLDS, AquariumSummary.UNREADABLE, "KEPT · ${AquariumSummary.CUSTOM}", "BYPASSED") +
            AquariumControls.presets.map { "KEPT · ${it.name}" }
        for (line in lines) assertTrue(line.length <= PadSheetBoxes.SUMMARY_CHARS, "'$line' is ${line.length} characters")
    }
}
