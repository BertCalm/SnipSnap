package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TanglePresetsTest {
    @Test
    fun `factory roster has the requested family counts full controls and held examples of every voice`() {
        val expected = mapOf(TanglePresetGroup.BASS to 10, TanglePresetGroup.LEAD to 8,
            TanglePresetGroup.PULSE to 6, TanglePresetGroup.METALLIC to 6, TanglePresetGroup.EXPERIMENTAL to 6)
        assertEquals(36, TanglePresets.all().size)
        assertEquals(36, TanglePresets.all().map { it.name }.toSet().size)
        for ((group, count) in expected) assertEquals(count, TanglePresets.forGroup(group).size, group.name)
        for (voice in TangleVoice.entries) {
            val presets = TanglePresets.forVoice(voice)
            assertTrue(presets.isNotEmpty(), "$voice missing from roster")
            assertTrue(presets.any { Tangle.isLoop(it.macros.getValue("HOLD")) }, "$voice missing held variant")
            assertEquals(presets, Presets.forVoice("TANGLE", voice.name))
            for (preset in presets) {
                assertEquals(Tangle.macrosFor(voice).map { it.name }.toSet(), preset.macros.keys, "${preset.name} incomplete macro recipe")
                assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name))
                assertEquals(preset, Presets.byName("TANGLE", voice.name, preset.name))
            }
        }
    }

    @Test
    fun `all recipes roundtrip their explicit pitch velocity and macro settings`() {
        for (preset in TanglePresets.all()) {
            assertEquals(preset, TanglePatch.fromJsonText(preset.toJsonText()), preset.name)
            assertEquals(preset, Patches.fromJsonText(preset.toJsonText()), "dispatcher ${preset.name}")
        }
        // Audio equivalence includes both finite and held recipes while avoiding 72 redundant renders.
        for (voice in TangleVoice.entries) {
            val preset = TanglePresets.forVoice(voice).first().copy(midi = 55, velocity = .63f)
            val restored = Patches.fromJsonText(preset.toJsonText())
            assertContentEquals(preset.render().samples, restored.render().samples, "$voice recipe behavior")
        }
        val held = TanglePresets.all().first { Tangle.isLoop(it.macros.getValue("HOLD")) }
        assertContentEquals(held.render().samples, Patches.fromJsonText(held.toJsonText()).render().samples, "held recipe behavior")
    }

    @Test
    fun `JSON defaults restore the voice root and reject invalid public parameters`() {
        val patch = TanglePatch("Test", TangleVoice.LEAD, emptyMap(), midi = 55, velocity = .5f)
        val fields = LinkedHashMap(patch.toJsonValue().entries)
        fields.remove("midi")
        fields.remove("velocity")
        val defaults = TanglePatch.fromJsonValue(JsonValue.Obj(fields)) as TanglePatch
        assertEquals(Tangle.rootMidi(TangleVoice.LEAD), defaults.midi)
        assertEquals(1f, defaults.velocity)
        for (midi in listOf(23, 85)) assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        for (velocity in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) assertFailsWith<IllegalArgumentException> { patch.copy(velocity = velocity) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("CATCH" to 1.1f)) }
        assertFailsWith<JsonException> { TanglePatch.fromJsonValue(JsonValue.Obj(fields + ("midi" to JsonValue.Num(55.5)))) }
        assertFailsWith<JsonException> { TanglePatch.fromJsonValue(JsonValue.Obj(fields + ("voice" to JsonValue.Str("UNKNOWN")))) }
    }

    @Test
    fun `every factory recipe renders finite audio and avoids drum choke classifications`() {
        for (preset in TanglePresets.all()) {
            val snip = preset.render()
            assertTrue(snip.samples.isNotEmpty(), "${preset.name} empty render")
            assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "${preset.name} invalid output")
            val heard = Classifier.classify(snip).drumClass
            assertTrue(heard !in TangleMeasure.drumClasses, "${preset.name} classified as $heard")
            val filed = Tangle.drumClassFor(preset.voice, preset.macros)
            assertEquals(if (Tangle.isLoop(preset.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL, filed)
        }
    }

    @Test
    fun `native velocity regenerates TANGLE without consuming the brightness macro`() {
        val patch = TanglePatch("Native", TangleVoice.LEAD, Tangle.defaults(TangleVoice.LEAD), midi = 55, velocity = .9f)
        val rendered = Velocity.atVelocity(patch, .35f)
        assertContentEquals(patch.copy(velocity = .35f).render().samples, rendered.samples)
        assertEquals(.7f, patch.macros.getValue("BITE"), "native velocity modified the source recipe")
    }
}
