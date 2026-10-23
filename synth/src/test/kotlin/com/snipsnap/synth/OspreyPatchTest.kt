package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The saved recipe must reproduce the particular seeded finite sample the user auditioned. */
class OspreyPatchTest {
    private val patch = OspreyPatch(
        "My Crossing", OspreyVoice.SWELL,
        mapOf("SHORE" to .43f, "SEA" to .67f, "MOTION" to .51f,
            "DEPTH" to .8f, "SPACE" to 0f, "HOLD" to .2f),
        midi = 55, velocity = .63f, seed = -7_104, durationSeconds = 3.5f,
    )

    @Test
    fun `shared dispatcher roundtrips the complete recipe and repeats exact audio`() {
        for (model in 1..Osprey.MODEL_VERSION) {
            val original = patch.copy(modelVersion = model)
            val text = original.toJsonText()
            val restored = OspreyPatch.fromJsonText(text)
            assertEquals(original, restored)
            assertEquals(original, Patches.fromJsonText(text))
            assertEquals(text, restored.toJsonText())
            assertTrue(original.toJsonValue().entries.keys.containsAll(
                setOf("modelVersion", "midi", "velocity", "seed", "durationSeconds")))
            assertContentEquals(original.render().samples, restored.render().samples)
            assertContentEquals(
                Osprey.render(original.voice, original.macros, original.midi, original.velocity,
                    original.seed, original.durationSeconds, modelVersion = model).samples,
                Patches.fromJsonText(text).render().samples,
            )
        }
    }

    @Test
    fun `name and macro editing preserve note velocity seed duration and model`() {
        val macros = patch.macros + ("SHORE" to .81f) + ("SEA" to .15f)
        assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
        val edited = Patches.edited(patch, "Return Home", macros)
        assertEquals(patch.copy(name = "Return Home", macros = macros), edited)
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
    }

    @Test
    fun `omitted and null optional fields retain the first model recipe defaults`() {
        val base = linkedMapOf<String, JsonValue>(
            "engine" to JsonValue.Str("OSPREY"), "version" to JsonValue.Num(1.0),
            "name" to JsonValue.Str("Default Sea"), "voice" to JsonValue.Str("SWELL"),
            "macros" to JsonValue.Obj(mapOf("SEA" to JsonValue.Num(.5))),
            "futureField" to JsonValue.Str("ignored"),
        )
        val expected = OspreyPatch("Default Sea", OspreyVoice.SWELL, mapOf("SEA" to .5f), modelVersion = 1)
        assertEquals(expected, OspreyPatch.fromJsonValue(JsonValue.Obj(base)))
        val explicitNulls = LinkedHashMap(base)
        for (field in listOf("modelVersion", "midi", "velocity", "seed", "durationSeconds")) {
            explicitNulls[field] = JsonValue.Null
        }
        assertEquals(expected, OspreyPatch.fromJsonValue(JsonValue.Obj(explicitNulls)))
    }

    private fun changed(field: String, value: JsonValue) = JsonValue.Obj(
        LinkedHashMap(patch.toJsonValue().entries).also { it[field] = value })

    @Test
    fun `unsupported model and malformed saved field types are refused`() {
        for ((field, value) in listOf(
            "engine" to JsonValue.Str("OTHER"), "voice" to JsonValue.Str("HARBOR"),
            "version" to JsonValue.Num(2.0), "modelVersion" to JsonValue.Num((Osprey.MODEL_VERSION + 1).toDouble()),
            "modelVersion" to JsonValue.Num(1.5), "midi" to JsonValue.Num(60.5),
            "seed" to JsonValue.Num(3.5), "seed" to JsonValue.Num(1e20),
            "velocity" to JsonValue.Str("strong"), "durationSeconds" to JsonValue.Bool(true),
            "macros" to JsonValue.Arr(emptyList()),
        )) assertFailsWith<JsonException>("accepted malformed $field=$value") {
            OspreyPatch.fromJsonValue(changed(field, value))
        }
        assertFailsWith<JsonException> {
            OspreyPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("SEA" to JsonValue.Str("storm")))))
        }
    }

    @Test
    fun `invalid saved recipe values never become a partially usable patch`() {
        for ((field, value) in listOf(
            "midi" to JsonValue.Num(35.0), "midi" to JsonValue.Num(85.0),
            "velocity" to JsonValue.Num(-.1), "velocity" to JsonValue.Num(1.1),
            "durationSeconds" to JsonValue.Num(.24), "durationSeconds" to JsonValue.Num(12.1),
            "durationSeconds" to JsonValue.Num(1e100), "name" to JsonValue.Str(" "),
            "macros" to JsonValue.Obj(mapOf("TUNE" to JsonValue.Num(.5))),
            "macros" to JsonValue.Obj(mapOf("SEA" to JsonValue.Num(1.1))),
        )) assertFailsWith<JsonException>("accepted invalid $field=$value") {
            Patches.fromJsonValue(changed(field, value))
        }
    }

    @Test
    fun `direct recipe construction rejects nonfinite and out of range controls`() {
        for (midi in listOf(35, 85)) assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("SEA" to value)) }
        }
        for (duration in listOf(.24f, 12.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(durationSeconds = duration) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(modelVersion = 0) }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
        // Every signed Int seed is legal and preserved exactly in the recipe.
        for (seed in listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)) {
            val seeded = patch.copy(seed = seed)
            assertEquals(seeded, Patches.fromJsonText(seeded.toJsonText()))
        }
    }

    @Test
    fun `factory comparisons cover five shores and three seas with strict dry finite routing`() {
        val presets = OspreyPresets.coastWeather()
        assertEquals(15, presets.size)
        assertEquals(15, presets.map { it.name }.toSet().size)
        assertEquals(setOf(0f, .25f, .5f, .75f, 1f), presets.map { it.macros.getValue("SHORE") }.toSet())
        assertEquals(3, presets.map { it.macros.getValue("SEA") }.toSet().size)
        assertEquals(1, presets.map { it.macros.getValue("HOLD") }.toSet().size,
            "shore and sea comparisons must share the same finite articulation")
        assertEquals(presets, Presets.forVoice("OSPREY", "SWELL").take(presets.size))
        assertTrue(Presets.all().containsAll(presets))
        for (preset in presets) {
            assertEquals(Osprey.MODEL_VERSION, preset.modelVersion, "new factory recipes must select the current model")
            assertEquals(0f, preset.macros.getValue("SPACE"))
            assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
            assertEquals(DrumClass.TONAL, Osprey.drumClassFor(preset.voice, preset.macros))
        }
        // Extending excitation is not a seamless-loop certification.
        for (hold in listOf(0f, .5f, 1f)) {
            assertEquals(DrumClass.TONAL, Osprey.drumClassFor(OspreyVoice.SWELL, patch.macros + ("HOLD" to hold)))
        }
    }

    @Test
    fun `new recipes select the current model while edits keep a legacy recipe on model one`() {
        assertEquals(Osprey.MODEL_VERSION, patch.modelVersion)
        val legacy = patch.copy(modelVersion = 1)
        val edited = Patches.edited(legacy, "Old Crossing", legacy.macros + ("SEA" to .2f)) as OspreyPatch
        assertEquals(1, edited.modelVersion)
        assertEquals(legacy.seed, edited.seed)
        assertEquals(legacy.durationSeconds, edited.durationSeconds)
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
    }

    @Test
    fun `velocity variants preserve the selected model and the complete seeded recipe`() {
        for (model in 1..Osprey.MODEL_VERSION) {
            val original = patch.copy(modelVersion = model)
            for (velocity in listOf(.25f, .8f)) {
                assertContentEquals(original.copy(velocity = velocity).render().samples,
                    Velocity.atVelocity(original, velocity).samples,
                    "model $model velocity $velocity changed pitch seed duration or rendering model")
            }
        }
    }
}
