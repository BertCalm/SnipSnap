package com.snipsnap.shell

import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ScannedChainConfig
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainVoice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScannedChainEditorTest {
    @Test
    fun `gentle damping and short envelopes have extra travel with every endpoint retained`() {
        assertEquals(0f, ScannedChainEditor.macroValue("DAMP", 0f))
        assertEquals(.25f, ScannedChainEditor.macroValue("DAMP", .5f))
        assertEquals(1f, ScannedChainEditor.macroValue("DAMP", 1f))
        assertEquals(.25f, ScannedChainEditor.attackSeconds(.5f))
        assertEquals(.5f, ScannedChainEditor.releaseSeconds(.5f))
        assertEquals(0f, ScannedChainEditor.attackSeconds(0f))
        assertEquals(1f, ScannedChainEditor.attackSeconds(1f))
        assertEquals(0f, ScannedChainEditor.releaseSeconds(0f))
        assertEquals(2f, ScannedChainEditor.releaseSeconds(1f))
        for (name in listOf("MOTION", "BEND", "BLOOM")) {
            for (value in listOf(0f, .013f, .25f, .43f, .8f, 1f)) {
                assertEquals(value, ScannedChainEditor.macroPosition(name, value))
                assertEquals(value, ScannedChainEditor.macroValue(name, value))
            }
        }
    }

    @Test
    fun `the whole travel is monotone and recalled values map back without quantization`() {
        var previous = -1f
        for (i in 0..1000) {
            val value = i / 1000f
            val travel = ScannedChainEditor.macroPosition("DAMP", value)
            assertTrue(travel > previous)
            assertTrue(abs(value - ScannedChainEditor.macroValue("DAMP", travel)) < 2e-7f)
            assertTrue(abs(value - ScannedChainEditor.attackSeconds(ScannedChainEditor.attackPosition(value))) < 2e-7f)
            val release = value * 2f
            assertTrue(abs(release - ScannedChainEditor.releaseSeconds(ScannedChainEditor.releasePosition(release))) < 4e-7f)
            previous = travel
        }
    }

    @Test
    fun `displaying a saved recipe never changes its physical values or long envelope`() {
        for (model in listOf(1, 2)) {
            val patch = ScannedChainPatch("Kept sound", ScannedChainVoice.BLOOM,
                macros = mapOf("MOTION" to .43f, "DAMP" to .137f, "BEND" to .61f, "BLOOM" to .78f),
                config = ScannedChainConfig.forVoice(ScannedChainVoice.BLOOM).copy(
                    seconds = .25f, attackSeconds = .831f, releaseSeconds = 1.731f,
                ), model = model)
            val recipe = PadRecipe(patch = patch)
            val saved = recipe.toJsonText()
            val recalled = PadRecipe.fromJsonText(saved).patch as ScannedChainPatch
            recalled.macros.forEach { (name, value) -> ScannedChainEditor.macroPosition(name, value) }
            ScannedChainEditor.attackPosition(recalled.config.attackSeconds)
            ScannedChainEditor.releasePosition(recalled.config.releaseSeconds)
            assertEquals(saved, PadRecipe(patch = recalled).toJsonText())
        }
    }

    @Test
    fun `invalid positions and non-finite values cannot reach the patch`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.001f, 1.001f)) {
            assertFailsWith<IllegalArgumentException> { ScannedChainEditor.macroValue("DAMP", value) }
            assertFailsWith<IllegalArgumentException> { ScannedChainEditor.macroPosition("DAMP", value) }
            assertFailsWith<IllegalArgumentException> { ScannedChainEditor.attackSeconds(value) }
            assertFailsWith<IllegalArgumentException> { ScannedChainEditor.releaseSeconds(value) }
        }
        assertFailsWith<IllegalArgumentException> { ScannedChainEditor.attackPosition(1.1f) }
        assertFailsWith<IllegalArgumentException> { ScannedChainEditor.releasePosition(2.1f) }
    }
}
