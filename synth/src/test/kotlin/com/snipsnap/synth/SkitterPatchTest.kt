package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved note/seed contracts and the dry public preset and kit entry points. */
class SkitterPatchTest {
    @Test
    fun `every voice saves six macros plus note velocity and seed and regenerates exactly`() {
        for (voice in SkitterVoice.entries) {
            val patch = SkitterPatch("Saved $voice", voice, Skitter.defaults(voice) + ("STATIC" to .32f),
                midi = 57, velocity = .65f, seed = -91827)
            val text = patch.toJsonText()
            val restored = SkitterPatch.fromJsonText(text)
            assertEquals(patch, restored)
            assertEquals(patch, Patches.fromJsonText(text))
            assertEquals(text, restored.toJsonText())
            assertEquals(setOf("BUZZ", "SKITTER", "AGITATE", "VENT", "STATIC", "HOLD"), restored.macros.keys)
            assertTrue(setOf("midi", "velocity", "seed").all { it in restored.toJsonValue().entries })
            assertContentEquals(patch.render().samples, restored.render().samples, "$voice saved behavior")

            val macros = patch.macros + ("VENT" to .8f)
            val edited = Patches.edited(patch, "Edited $voice", macros)
            assertEquals(patch.copy(name = "Edited $voice", macros = macros), edited)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
        }
    }

    @Test
    fun `missing optional fields use defaults and partial controls render with voice defaults`() {
        for (optional in listOf("", "\"midi\":null,\"velocity\":null,\"seed\":null,")) {
            val text = """{"engine":"SKITTER","version":1,"name":"Partial","voice":"HOLLOW",$optional"macros":{"VENT":0.7},"extra":"ignored"}"""
            val patch = SkitterPatch.fromJsonText(text)
            assertEquals(Skitter.DEFAULT_MIDI, patch.midi)
            assertEquals(1f, patch.velocity)
            assertEquals(0, patch.seed)
            assertEquals(mapOf("VENT" to .7f), patch.macros)
            assertEquals(patch, Patches.fromJsonText(text))
            assertContentEquals(
                Skitter.render(patch.voice, Skitter.defaults(patch.voice) + patch.macros).samples,
                patch.render().samples,
            )
        }
    }

    @Test
    fun `invalid saved tags voices notes velocities seeds and controls are refused`() {
        val patch = SkitterPatch("Saved", SkitterVoice.LEAD, Skitter.defaults(SkitterVoice.LEAD))
        fun changed(name: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).also { it[name] = value },
        )
        assertFailsWith<JsonException> { SkitterPatch.fromJsonValue(changed("engine", JsonValue.Str("BORE"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("voice", JsonValue.Str("KICK"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("midi", JsonValue.Num(48.5))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("seed", JsonValue.Num(1.5))) }
        for (midi in listOf(23, 85)) assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("AGITATE" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
    }

    @Test
    fun `thirty six complete presets are discoverable dry and pitched with held variants`() {
        val all = SkitterPresets.all()
        assertEquals(36, all.size)
        val counts = mapOf(SkitterVoice.BASS to 8, SkitterVoice.LEAD to 8, SkitterVoice.HOLLOW to 6,
            SkitterVoice.FLUTTER to 6, SkitterVoice.CHIRP to 4, SkitterVoice.FRAY to 4)
        assertEquals(counts, all.groupingBy { it.voice }.eachCount())
        assertEquals(all.size, all.map { it.voice to it.name }.toSet().size)
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice("SKITTER", "UNKNOWN").isEmpty())
        for (voice in SkitterVoice.entries) {
            assertEquals(SkitterPresets.forVoice(voice), Presets.forVoice("SKITTER", voice.name))
        }
        for (patch in all) {
            assertEquals(Skitter.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys, patch.name)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(null, Presets.landingFor(patch.engine, patch.voiceName, patch.macros), "${patch.name} dry identity")
            val held = Skitter.isLoop(patch.macros.getValue("HOLD"))
            assertEquals(if (held) DrumClass.LOOP else DrumClass.TONAL, Skitter.drumClassFor(patch.voice, patch.macros))
            assertTrue(Classifier.classify(patch.render()).drumClass !in DRUMS, "${patch.name} actual classification")
        }
        assertTrue(all.any { Skitter.isLoop(it.macros.getValue("HOLD")) }, "no held factory recipe")
        assertTrue(all.any { it.macros.getValue("AGITATE") == 0f && it.macros.getValue("STATIC") == 0f }, "no clean factory recipe")
    }

    @Test
    fun `the dry kit saves ascending bass notes all six identities and exact held recipes`() {
        val kit = SynthKits.skitter()
        assertEquals(16, kit.size)
        val voices = mutableSetOf<SkitterVoice>()
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        var heldCount = 0
        for ((i, maybePad) in kit.withIndex()) {
            val pad = requireNotNull(maybePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = recipe.patch as SkitterPatch
            voices += patch.voice
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
            assertEquals(null, recipe.fx, "A${i + 1} lands dry")
            assertFalse(recipe.alias)
            val held = Skitter.isLoop(patch.macros.getValue("HOLD"))
            if (held) heldCount++
            assertEquals(if (held) DrumClass.LOOP else DrumClass.TONAL, pad.drumClass, "A${i + 1} melodic routing")
            if (i < walk.size) {
                assertEquals(SkitterVoice.BASS, patch.voice)
                assertEquals(48 + walk[i], patch.midi)
            }
            if (i in listOf(0, 8, 14, 15)) {
                assertContentEquals(pad.snip.samples, recipe.render().samples, "A${i + 1} regenerates exactly")
            }
        }
        assertEquals(SkitterVoice.entries.toSet(), voices)
        assertTrue(heldCount >= 2, "kit needs representative held recipes")
    }

    @Test
    fun `native velocity rendering retains the saved note and seed`() {
        val patch = SkitterPatch("Velocity", SkitterVoice.FLUTTER, Skitter.defaults(SkitterVoice.FLUTTER),
            midi = 63, velocity = .85f, seed = 8743)
        val soft = Velocity.atVelocity(patch, .25f)
        assertContentEquals(Skitter.render(patch.voice, patch.macros, patch.midi, .25f, patch.seed).samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples))
        assertEquals(.85f, patch.velocity)
        assertEquals(8743, patch.seed)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
