package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Product persistence/editing must retain the exact note, velocity and approved synthesis model. */
class RegistersPatchTest {
    @Test
    fun `dispatcher replays all audition models including omitted legacy model`() {
        for (model in 1..3) {
            val original = RegistersPatch("Saved audition", RegistersVoice.PIPE,
                Registers.defaults(RegistersVoice.PIPE), midi = 69, velocity = .37f, model = model)
            val decoded = Patches.fromJsonText(original.toJsonText()) as RegistersPatch
            assertEquals(original, decoded)
            assertEquals("REGISTERS", decoded.engine)
            assertEquals("PIPE", decoded.voiceName)
            assertContentEquals(Registers.render(original.voice, original.macros, 69, .37f, model).samples,
                decoded.render().samples)
            val withoutFormat = JsonValue.Obj(original.toJsonValue().entries.filterKeys { it != "format" })
            assertEquals(original, Patches.fromJsonValue(withoutFormat))
        }
        val old = RegistersPatch("Legacy audition", RegistersVoice.MUSETTE,
            Registers.defaults(RegistersVoice.MUSETTE), midi = 45, velocity = .61f, model = 1)
        val missingModel = JsonValue.Obj(old.toJsonValue().entries.filterKeys { it != "model" })
        val decoded = Patches.fromJsonValue(missingModel) as RegistersPatch
        assertEquals(old, decoded)
        assertContentEquals(old.render().samples, decoded.render().samples)
    }

    @Test
    fun `name and timbral edits retain saved explicit note velocity and model`() {
        val original = RegistersPatch("Original", RegistersVoice.PIPE,
            Registers.defaults(RegistersVoice.PIPE), midi = 45, velocity = .37f, model = 2)
        val renamed = Patches.edited(original, "Renamed", original.macros) as RegistersPatch
        assertEquals(45, renamed.midi)
        assertEquals(.37f, renamed.velocity)
        assertEquals(2, renamed.model)
        assertContentEquals(original.render().samples, renamed.render().samples)
        val airy = Patches.edited(original, "More air", original.macros + ("AIR" to .7f)) as RegistersPatch
        assertEquals(45, airy.midi)
        assertEquals(.37f, airy.velocity)
        assertEquals(2, airy.model)
        assertEquals(.7f, airy.macros["AIR"])
        val partial = original.withMacros(mapOf("COLOR" to .2f))
        assertEquals(45, partial.midi, "omitting TUNE is not a pitch edit")
    }

    @Test
    fun `loaded presets and canonical audition sliders make tune edits change pitch`() {
        val preset = RegistersPresets.forVoice(RegistersVoice.PIPE).first()
        val high = Patches.edited(preset, "Higher", preset.macros + ("TUNE" to 1f)) as RegistersPatch
        assertEquals(72, high.midi)
        assertEquals(Registers.MODEL_VERSION, high.model)
        assertEquals(Keys.midiHz(72).toDouble(), high.probe().effectiveHz)

        // Published audition recipes use explicit notes even when their old TUNE is .5.
        val audition = RegistersPatch("A4 audition", RegistersVoice.PIPE,
            Registers.defaults(RegistersVoice.PIPE), midi = 69, velocity = .63f, model = 2)
        val editing = audition.forEditing()
        assertEquals(.875f, editing.macros["TUNE"])
        assertContentEquals(audition.render().samples, editing.render().samples)
        val color = Patches.edited(editing, "Same note", editing.macros + ("COLOR" to .6f)) as RegistersPatch
        assertEquals(69, color.midi)
        assertEquals(.63f, color.velocity)
        assertEquals(2, color.model)
        val middle = color.withMacros(color.macros + ("TUNE" to .5f))
        assertEquals(60, middle.midi)

        val belowSlider = audition.copy(midi = 24).forEditing()
        assertEquals(0f, belowSlider.macros["TUNE"])
        assertEquals(24, belowSlider.withMacros(belowSlider.macros + ("AIR" to .4f)).midi)
        assertEquals(60, belowSlider.withMacros(belowSlider.macros + ("TUNE" to .5f)).midi)
    }

    @Test
    fun `velocity dispatch passes one performance velocity and keeps note and model`() {
        for (voice in RegistersVoice.entries) for (hold in listOf(.35f, 1f)) {
            val patch = RegistersPatch("Quiet recipe", voice, Registers.defaults(voice) + ("HOLD" to hold),
                midi = 69, velocity = .2f, model = 3)
            assertNull(Velocity.brightnessSpec(patch))
            val actual = Velocity.atVelocity(patch, .75f)
            val expected = Registers.render(voice, patch.macros, 69, .75f, model = 3)
            assertContentEquals(expected.samples, actual.samples, "velocity must not multiply the saved .2 twice")
            assertTrue(Velocity.atVelocity(patch, 0f).samples.all { it == 0f })
            val reference = patch.render()
            assertTrue(Velocity.layerAt(reference, patch, null, 0f, null, useAtVelocity = true).samples.all { it == 0f })
            val expectedLayer = Velocity.peakMatch(reference, patch.copy(velocity = .4f).render())
            val layer = Velocity.layerAt(reference, patch, null, .4f, null, useAtVelocity = true)
            assertContentEquals(expectedLayer.samples, layer.samples)
        }
    }

    @Test
    fun `six factory presets reuse published default restrained and expressive settings`() {
        val all = RegistersPresets.all()
        assertEquals(6, all.size)
        assertEquals(6, all.map { it.name }.distinct().size)
        assertTrue(Presets.all().containsAll(all))
        for (voice in RegistersVoice.entries) {
            val group = RegistersPresets.forVoice(voice)
            assertEquals(3, group.size)
            assertEquals(group, Presets.forVoice("REGISTERS", voice.name))
            assertEquals(Registers.defaults(voice), group.first().macros)
            for (preset in group) {
                assertEquals(60, preset.midi)
                assertEquals(1f, preset.velocity)
                assertEquals(3, preset.model)
                assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
                assertEquals(preset, Presets.byName("REGISTERS", voice.name, preset.name))
                assertTrue(preset.name.length <= 14)
                assertNull(Presets.landingFor("REGISTERS", voice.name, preset.macros))
            }
        }
        val pipe = RegistersPresets.forVoice(RegistersVoice.PIPE)
        assertEquals(listOf(.18f, .55f), pipe.drop(1).map { it.macros.getValue("COLOR") })
        assertEquals(listOf(.20f, .72f), pipe.drop(1).map { it.macros.getValue("AIR") })
        assertEquals(listOf(.08f, .62f), pipe.drop(1).map { it.macros.getValue("EDGE") })
        val musette = RegistersPresets.forVoice(RegistersVoice.MUSETTE)
        assertEquals(listOf(.32f, .64f), musette.drop(1).map { it.macros.getValue("COLOR") })
        assertEquals(listOf(.24f, .78f), musette.drop(1).map { it.macros.getValue("AIR") })
        assertEquals(listOf(.20f, .78f), musette.drop(1).map { it.macros.getValue("SPREAD") })
        assertTrue(Presets.forVoice("REGISTERS", "UNKNOWN").isEmpty())
    }
}
