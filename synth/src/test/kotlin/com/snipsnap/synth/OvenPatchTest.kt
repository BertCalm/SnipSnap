package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved strikes, native touch and dry kit regeneration through the public pipeline. */
class OvenPatchTest {
    @Test
    fun `saved strikes round trip and macro edits retain note velocity and model`() {
        for (voice in OvenVoice.entries) {
            val patch = OvenPatch("Saved $voice", voice, Oven.defaults(voice) + ("HALO" to .22f),
                midi = 57, velocity = .65f)
            val text = patch.toJsonText()
            val restored = OvenPatch.fromJsonText(text)
            assertEquals(patch, restored)
            assertEquals(patch, Patches.fromJsonText(text))
            assertEquals(text, restored.toJsonText())
            assertTrue(setOf("midi", "velocity", "model").all { it in restored.toJsonValue().entries })
            assertContentEquals(patch.render().samples, restored.render().samples, "$voice saved strike")

            val changed = patch.macros + ("BODY" to .82f)
            assertEquals(patch.copy(name = "Edited", macros = changed), Patches.edited(patch, "Edited", changed))
            assertEquals(patch.copy(macros = changed), patch.withMacros(changed))
        }
    }

    @Test
    fun `partial older-shaped recipes use root velocity and original model defaults`() {
        val text = """{"engine":"OVEN","version":1,"name":"Partial","voice":"FELT","macros":{"BODY":0.7}}"""
        val patch = OvenPatch.fromJsonText(text)
        assertEquals(Oven.DEFAULT_MIDI, patch.midi)
        assertEquals(1f, patch.velocity)
        assertEquals(1, patch.model)
        assertEquals(mapOf("BODY" to .7f), patch.macros)
        assertContentEquals(
            Oven.render(patch.voice, Oven.defaults(patch.voice) + patch.macros, patch.midi, patch.velocity).samples,
            patch.render().samples,
        )
    }

    @Test
    fun `a saved first-roster felt recipe retains its controls after factory revision`() {
        val text = """{"engine":"OVEN","version":1,"name":"Felt Ledger","voice":"FELT","macros":{"TUNE":0.5,"HAMMER":0.18,"BODY":0.58,"RING":0.42,"BLOOM":0.12,"HALO":0.1},"model":1,"midi":60,"velocity":0.7}"""
        val saved = OvenPatch.fromJsonText(text)
        val original = OvenPatch("Felt Ledger", OvenVoice.FELT,
            mapOf("TUNE" to .5f, "HAMMER" to .18f, "BODY" to .58f, "RING" to .42f, "BLOOM" to .12f, "HALO" to .10f),
            midi = 60, velocity = .7f, model = 1)
        assertEquals(original, saved)
        assertEquals(saved, Patches.fromJsonText(saved.toJsonText()))
        assertContentEquals(original.render().samples, saved.render().samples)
        assertFalse(saved.macros == OvenPresets.forVoice(OvenVoice.FELT).first { it.name == saved.name }.macros)
    }

    @Test
    fun `bad tags notes energy models and controls are refused`() {
        val patch = OvenPatch("Saved", OvenVoice.GRAND, Oven.defaults(OvenVoice.GRAND))
        fun changed(name: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).also { it[name] = value },
        )
        assertFailsWith<JsonException> { OvenPatch.fromJsonValue(changed("engine", JsonValue.Str("TINES"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("voice", JsonValue.Str("KICK"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("midi", JsonValue.Num(48.5))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("model", JsonValue.Num(2.0))) }
        for (midi in listOf(Oven.MIDI_MIN - 1, Oven.MIDI_MAX + 1)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        }
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HAMMER" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 0) }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HOLD" to 1f)) }
    }

    @Test
    fun `native velocity keeps root tuning and material while changing hammer excitation`() {
        val patch = OvenPatch("Touch", OvenVoice.GRAND,
            Oven.defaults(OvenVoice.GRAND) + ("TUNE" to .75f) + ("HAMMER" to .7f),
            midi = 55, velocity = .85f)
        val soft = Velocity.atVelocity(patch, .25f)
        assertContentEquals(patch.copy(velocity = .25f).render().samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples))
        assertEquals(.85f, patch.velocity)
        assertEquals(55, patch.midi)
        assertEquals(.75f, patch.macros.getValue("TUNE"))
        assertEquals(.7f, patch.macros.getValue("HAMMER"))
        assertEquals(Oven.MODEL_VERSION, patch.model)
    }

    @Test
    fun `ten complete original presets are discoverable with dry finite tails`() {
        val all = OvenPresets.all()
        assertEquals(10, all.size)
        assertEquals(all.size, all.map { it.voice to it.name }.toSet().size)
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice("OVEN", "UNKNOWN").isEmpty())
        for (voice in OvenVoice.entries) {
            assertTrue(OvenPresets.forVoice(voice).isNotEmpty())
            assertEquals(OvenPresets.forVoice(voice), Presets.forVoice("OVEN", voice.name))
        }
        for (patch in all) {
            assertEquals(Oven.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys, patch.name)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(null, Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
            assertEquals(DrumClass.TONAL, Oven.drumClassFor(patch.voice, patch.macros))
            assertFalse("HOLD" in patch.macros, "${patch.name} is a finite strike")
            assertFalse(OvenPresets.roleDescription(patch.name).isNullOrBlank(), "${patch.name} needs a musical role")
        }
        assertEquals(null, OvenPresets.roleDescription("MY PIANO"))
        val openLid = all.first { it.name == "Open Lid" }
        assertEquals(OvenPatch("Open Lid", OvenVoice.GRAND,
            mapOf("TUNE" to .5f, "HAMMER" to .55f, "BODY" to .55f, "RING" to .55f, "BLOOM" to 0f, "HALO" to .20f),
            midi = 60, velocity = .8f, model = 1), openLid, "approved Open Lid stays exactly the same")
    }

    @Test
    fun `sixteen dry pads retain an ascending scale all articulations and regenerating recipes`() {
        val kit = SynthKits.oven()
        assertEquals(16, kit.size)
        val voices = mutableSetOf<OvenVoice>()
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        val identities = listOf("Iron Parlour", "Felt Ledger", "Quiet Room", "Bright Ledger",
            "Hammered Iron", "Cold Rivet", "Afterglow", "Hanging Frame")
        for ((i, maybePad) in kit.withIndex()) {
            val pad = requireNotNull(maybePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = recipe.patch as OvenPatch
            voices += patch.voice
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
            assertEquals(null, recipe.fx, "A${i + 1} lands dry")
            assertFalse(recipe.alias)
            assertEquals(DrumClass.TONAL, pad.drumClass)
            assertEquals(Oven.MODEL_VERSION, patch.model)
            assertEquals(1, pad.snip.channels)
            assertEquals(44_100, pad.snip.sampleRate)
            if (i < walk.size) {
                assertEquals(OvenVoice.GRAND, patch.voice)
                assertEquals(Oven.ROOT_MIDI + walk[i], patch.midi)
                assertEquals(.5f, patch.macros.getValue("TUNE"))
                assertEquals(.8f, patch.velocity)
                assertEquals(Oven.defaults(OvenVoice.GRAND), patch.macros)
            } else {
                assertEquals(identities[i - walk.size], patch.name)
            }
            if (i in listOf(0, 10, 12, 14)) {
                assertContentEquals(pad.snip.samples, recipe.render().samples, "A${i + 1} regenerates")
            }
        }
        assertEquals(OvenVoice.entries.toSet(), voices)
    }
}
