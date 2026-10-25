package com.snipsnap.synth

import com.snipsnap.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AquariumControlsTest {
    private fun savedContext(): AquariumRenderRecipe = AquariumRenderRecipe(
        AquariumRecipe(AquariumControls.preset("full").settings.parameters, seed = 0xffff_ffffL),
        bpm = 117.125, tailSeconds = 1.75, fadeSeconds = .007, outputGain = .375f, peakCeiling = .875f,
    )

    @Test fun `packaged accepted presets match native canonical JSON without another numeric table`() {
        val source = Json.parse(File("../app/src/main/cpp/aquarium/presets.json").readText()).obj()
        assertEquals(source.keys.toList(), AquariumControls.presets.map { it.id })
        assertEquals(listOf("dry", "fathom", "drift", "tide", "reef", "surface", "biolume", "depth", "motion", "color", "full"), AquariumControls.presets.map { it.id })
        for (preset in AquariumControls.presets) {
            val expected = AquariumRecipe(source.getValue(preset.id).obj().mapValues { it.value.num().toFloat() })
            assertEquals(expected, preset.settings, preset.id)
            assertEquals(52, preset.settings.parameters.size, preset.id)
            assertEquals(preset.settings.toJsonText(), AquariumRecipe.fromJsonText(preset.settings.toJsonText()).toJsonText())
        }
        assertTrue(AquariumControls.preset("dry").settings.isBypass)
        assertFalse(AquariumControls.preset("full").settings.isBypass)
        assertFailsWith<IllegalArgumentException> { AquariumControls.preset("not-a-preset") }
    }

    @Test fun `conceptual pairs expose every native control once with folded details`() {
        assertEquals(listOf("depth", "motion", "color"), AquariumControls.groups.map { it.id })
        assertEquals(listOf(listOf("fathom", "surface"), listOf("drift", "tide"), listOf("reef", "biolume")), AquariumControls.groups.map { it.effects.map { effect -> effect.id } })
        val controls = AquariumControls.effects.flatMap { it.primary + it.details }
        assertEquals(AquariumRecipe.PARAMETERS.map { it.id }.toSet(), controls.map { it.id }.toSet())
        assertEquals(52, controls.size, "controls cannot be duplicated or stranded outside the panel")
        for (effect in AquariumControls.effects) {
            assertEquals(effect.mixId, effect.primary.first().id)
            assertEquals(4, effect.primary.size)
            assertTrue(effect.details.isNotEmpty())
            assertTrue((effect.primary + effect.details).all { it.id.startsWith("aqua_${effect.id}_") })
        }
    }

    @Test fun `normalized sliders are monotonic bounded and selector values always follow native steps`() {
        for (effect in AquariumControls.effects) for (control in effect.primary + effect.details) {
            assertEquals(control.spec.minimum, control.fromNormalized(0f), control.id)
            assertEquals(control.spec.maximum, control.fromNormalized(1f), control.id)
            assertEquals(0f, control.toNormalized(control.spec.minimum), control.id)
            assertEquals(1f, control.toNormalized(control.spec.maximum), control.id)
            var previous = control.spec.minimum
            for (index in 0..1000) {
                val value = control.fromNormalized(index / 1000f)
                control.spec.validate(value)
                assertTrue(value >= previous, control.id)
                previous = value
                val roundTrip = control.fromNormalized(control.toNormalized(value))
                if (control.spec.isDiscrete) assertEquals(value, roundTrip, control.id)
                else assertTrue(abs(roundTrip - value) <= (control.spec.maximum - control.spec.minimum) * .000002f + .000001f, control.id)
            }
            for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.001f, 1.001f)) {
                assertFailsWith<IllegalArgumentException>(control.id) { control.fromNormalized(bad) }
            }
            assertFailsWith<IllegalArgumentException>(control.id) { control.toNormalized(control.spec.maximum + 1f) }
        }
    }

    @Test fun `frequency and delay travel use physical log spacing and native choice readouts`() {
        val frequency = AquariumControls.control("aqua_surface_freq")
        assertTrue(abs(frequency.fromNormalized(.5f) - sqrt(200f * 16000f)) < .001f)
        assertEquals("4 kHz", frequency.format(4000f))
        assertEquals("0.001 Hz", AquariumControls.control("aqua_drift_rate").format(.001f))
        val delay = AquariumControls.control("aqua_drift_depth")
        assertEquals(0f, delay.fromNormalized(0f))
        assertTrue(abs(delay.fromNormalized(.5f) - (sqrt(41f) - 1f)) < .00001f)
        assertEquals("5 ms", delay.format(5f))
        assertEquals("-4.5 dB", AquariumControls.control("aqua_surface_gain").format(-4.5f))
        assertEquals("40%", AquariumControls.control("aqua_fathom_mix").format(.4f))
        assertEquals("Both", AquariumControls.control("aqua_tide_mode").format(2f))
        assertEquals("S&H", AquariumControls.control("aqua_tide_shape").format(2f))
        assertEquals("32 beats", AquariumControls.control("aqua_tide_syncDiv").format(0f))
        assertEquals("1/4 beat", AquariumControls.control("aqua_tide_syncDiv").format(7f))
        assertEquals("4 octaves", AquariumControls.control("aqua_surface_sweepRange").format(1f))
        assertEquals("Fold", AquariumControls.control("aqua_biolume_satMode").format(2f))
    }

    @Test fun `changing one control retains every other control seed and saved print context`() {
        val original = savedContext()
        val editor = AquariumEditor(original)
        assertEquals("full", editor.presetId)
        for (spec in AquariumRecipe.PARAMETERS) {
            val value = AquariumControls.control(spec.id).fromNormalized(.37f)
            val changed = editor.setNormalized(spec.id, .37f)
            assertEquals(value, changed.render.settings.parameters.getValue(spec.id), spec.id)
            assertEquals(original.settings.parameters - spec.id, changed.render.settings.parameters - spec.id, spec.id)
            assertEquals(original.settings.seed, changed.render.settings.seed)
            assertEquals(original.settings.model, changed.render.settings.model)
            assertEquals(original.copy(settings = changed.render.settings), changed.render)
        }
        assertEquals(original, editor.render, "editing cannot mutate the previous value")
        assertNull(editor.set("aqua_drift_feedback", .63f).presetId)
        assertFailsWith<IllegalArgumentException> { editor.set("aqua_tide_mode", .5f) }
        assertFailsWith<IllegalArgumentException> { editor.set("aqua_missing_mix", .5f) }
    }

    @Test fun `accepted preset selection replaces controls but preserves existing seed and render policy`() {
        val original = savedContext()
        for (preset in AquariumControls.presets) {
            val changed = AquariumEditor(original).selectPreset(preset.id)
            assertEquals(preset.id, changed.presetId)
            assertEquals(preset.settings.parameters, changed.render.settings.parameters)
            assertEquals(original.settings.seed, changed.render.settings.seed)
            assertEquals(original.copy(settings = changed.render.settings), changed.render)
        }
    }

    @Test fun `bypass restores remembered nonzero mixes and fresh stages use accepted single effect mixes`() {
        val saved = savedContext()
        for (effect in AquariumControls.effects) {
            val on = AquariumEditor(saved).set(effect.mixId, .63f)
            val off = on.toggle(effect.id)
            assertEquals(0f, off.render.settings.parameters.getValue(effect.mixId))
            assertEquals(on.render.settings.parameters - effect.mixId, off.render.settings.parameters - effect.mixId)
            assertEquals(on.render, off.toggle(effect.id).render)
            val editedContext = off.render.copy(bpm = 93.5, tailSeconds = .5, outputGain = .25f)
            val restored = off.withRender(editedContext).toggle(effect.id)
            assertEquals(editedContext.copy(settings = on.render.settings), restored.render)
            val manualZero = on.set(effect.mixId, 0f).toggle(effect.id)
            assertEquals(.63f, manualZero.render.settings.parameters.getValue(effect.mixId))
            val fresh = AquariumEditor().toggle(effect.id)
            assertEquals(AquariumControls.preset(effect.id).settings.parameters.getValue(effect.mixId), fresh.render.settings.parameters.getValue(effect.mixId))
        }
        assertTrue(AquariumEditor().render.isBypass)
        assertEquals("dry", AquariumEditor().presetId)
        assertFailsWith<IllegalArgumentException> { AquariumEditor().toggle("unknown") }
    }
}
