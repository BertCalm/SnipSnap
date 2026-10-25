package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Saving or softening an authored sound must keep the renderer that made it. */
class StoopModelCompatibilityTest {
    private val models = listOf(Stoop.LEGACY_MODEL_VERSION, Stoop.MODEL_VERSION)
    private val voices = listOf(StoopVoice.CLAV, StoopVoice.ORGAN)

    private fun custom(model: Int, voice: StoopVoice) = StoopPatch(
        "Authored $voice", voice,
        mapOf("TOUCH" to .61f, "COLOR" to .43f, "SHADOW" to .68f,
            "WEAR" to .57f, "LENGTH" to .16f, "HOLD" to 0f),
        midi = 55, velocity = .79f,
        source = StoopSource(shape = .72f, sub = .18f, fmRatio = 1.5f,
            fmIndex = .86f, harmonicTilt = .61f, detuneCents = -4.5f),
        shadowInterval = 7, shadowDelayMs = 151.5f, shadowArticulation = .27f, shadowReplies = 2,
        chordIntervals = listOf(0, 3, 7), model = model,
    )

    @Test
    fun `new sounds use model two while only the six published anchors keep model one`() {
        assertEquals(2, StoopPatch("New patch", StoopVoice.CLAV, emptyMap()).model)
        val anchors = setOf("Round Corner", "Dust Tines", "Soft Clav", "Amber Organ", "Nose Talk", "Velvet Stack")
        val factory = StoopPresets.all()
        assertEquals(anchors, factory.filter { it.model == 1 }.map { it.name }.toSet())
        val revised = factory.filter { it.name !in anchors }
        assertEquals(42, revised.size)
        for (preset in revised) assertEquals(2, preset.model, preset.name)
    }

    @Test
    fun `both models retain the complete authored recipe through patch and pad saves`() {
        for (model in models) for (voice in voices) {
            val original = custom(model, voice)
            val context = "$voice model $model"
            val json = original.toJsonValue()
            assertEquals(model, json.entries.getValue("model").int(), context)
            val dispatched = assertIs<StoopPatch>(Patches.fromJsonValue(json), context)
            assertEquals(original, dispatched, context)
            assertEquals(original, StoopPatch.fromJsonText(dispatched.toJsonText()), context)
            assertEquals(original, Patches.fromJsonText(original.toJsonText()), context)

            val pad = PadRecipe(patch = dispatched)
            assertEquals(pad, PadRecipe.fromJsonValue(pad.toJsonValue()), context)
            assertEquals(pad, PadRecipe.fromJsonText(pad.toJsonText()), context)
        }
    }

    @Test
    fun `saving an old file without a model never silently upgrades its authored recipe`() {
        for (voice in voices) for (explicitNull in listOf(false, true)) {
            val legacy = custom(Stoop.LEGACY_MODEL_VERSION, voice)
            val oldFields = LinkedHashMap(legacy.toJsonValue().entries).apply {
                if (explicitNull) this["model"] = JsonValue.Null else remove("model")
            }
            val loaded = assertIs<StoopPatch>(Patches.fromJsonValue(JsonValue.Obj(oldFields)))
            assertEquals(legacy, loaded, "$voice null model $explicitNull")
            val saved = loaded.toJsonText()
            assertEquals(legacy, Patches.fromJsonText(saved), "$voice legacy re-save")
            assertEquals(Stoop.LEGACY_MODEL_VERSION,
                loaded.toJsonValue().entries.getValue("model").int(), "$voice saved model")
        }
    }

    @Test
    fun `macro and name edits preserve each model and every independent recipe field`() {
        for (model in models) for (voice in voices) {
            val original = custom(model, voice)
            val context = "$voice model $model"
            val moved = original.macros + ("TOUCH" to .82f) + ("WEAR" to .23f)
            val withMacros = assertIs<StoopPatch>(original.withMacros(moved), context)
            assertEquals(original.copy(macros = moved), withMacros, context)

            val edited = assertIs<StoopPatch>(Patches.edited(original, "Saved variation", moved), context)
            assertEquals(original.copy(name = "Saved variation", macros = moved), edited, context)
            val savedPad = PadRecipe.fromJsonText(PadRecipe(patch = edited).toJsonText())
            assertEquals(edited, savedPad.patch, "$context edited pad re-save")
        }
    }

    @Test
    fun `velocity layers render the saved model and authored source answers and voicing`() {
        for (model in models) for (voice in voices) {
            val authored = custom(model, voice)
            val saved = PadRecipe.fromJsonText(PadRecipe(patch = authored).toJsonText())
            val patch = assertIs<StoopPatch>(saved.patch)
            val velocity = .35f
            val expected = authored.copy(velocity = velocity).render()
            val actual = Velocity.atVelocity(patch, velocity)
            val context = "$voice model $model"
            assertTrue(expected.samples.any { it != 0f }, "$context must exercise audible output")
            assertEquals(expected.channels, actual.channels, context)
            assertEquals(expected.sampleRate, actual.sampleRate, context)
            assertContentEquals(expected.samples, actual.samples,
                "$context velocity regeneration changed the saved recipe")
        }
    }
}
