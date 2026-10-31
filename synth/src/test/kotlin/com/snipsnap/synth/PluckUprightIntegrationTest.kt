package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved-state and velocity integration for the one new PLUCK-family voice. */
class PluckUprightIntegrationTest {
    private val legacyVoices = listOf(
        PluckVoice.NYLON, PluckVoice.HARP, PluckVoice.KOTO, PluckVoice.BANJO, PluckVoice.SITAR,
    )

    @Test
    fun `legacy patches retain their exact pre-upright JSON shape`() {
        // This is the original five-field serializer, captured before the
        // velocity field was added. Its exact text guards field order too.
        for (voice in legacyVoices) {
            val patch = PluckPatch("LEGACY", voice, linkedMapOf("TUNE" to .5f, "DAMP" to .25f, "PICK" to .75f))
            val expected = """
                {
                  "engine": "PLUCK",
                  "version": 1,
                  "name": "LEGACY",
                  "voice": "${voice.name}",
                  "macros": {
                    "TUNE": 0.5,
                    "DAMP": 0.25,
                    "PICK": 0.75
                  }
                }
            """.trimIndent()
            assertEquals(expected, patch.toJsonText(), voice.name)
            assertEquals(patch, PluckPatch.fromJsonText(expected))
            // Legacy decode already ignored unknown top-level state; adding
            // a bass field must not reinterpret that state for old strings.
            val extra = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).apply {
                put("velocity", JsonValue.Num(.2))
            })
            assertEquals(patch, PluckPatch.fromJsonValue(extra))
        }
    }

    @Test
    fun `upright patch and pad recipe replay the saved excitation strength`() {
        val patch = PluckPresets.forVoice(PluckVoice.UPRIGHT).first().copy(velocity = .35f)
        val restored = assertIs<PluckPatch>(Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch, restored)
        assertEquals(.35f, restored.velocity)
        assertTrue(patch.render().samples.contentEquals(restored.render().samples))
        val recipe = PadRecipe(patch = patch)
        val reopened = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(patch, reopened.patch)
        assertTrue(recipe.render().samples.contentEquals(reopened.render().samples))
    }

    @Test
    fun `upright editing keeps velocity while replacing macros and name`() {
        val patch = PluckPresets.forVoice(PluckVoice.UPRIGHT).first().copy(velocity = .35f)
        val edited = assertIs<PluckPatch>(Patches.edited(patch, "LOW WOOD", patch.macros + ("WOOD" to .9f)))
        assertEquals("LOW WOOD", edited.name)
        assertEquals(.35f, edited.velocity)
        assertEquals(.9f, edited.macros["WOOD"])
        assertEquals(PluckVoice.UPRIGHT, edited.voice)
        assertEquals(.35f, (patch.withMacros(patch.macros + ("DAMP" to 1f)) as PluckPatch).velocity)
    }

    @Test
    fun `upright missing or null velocity means the original unit excitation`() {
        val patch = PluckPatch("PARTIAL", PluckVoice.UPRIGHT, emptyMap())
        for (field in listOf<JsonValue?>(null, JsonValue.Null)) {
            val fields = LinkedHashMap(patch.toJsonValue().entries)
            if (field == null) fields.remove("velocity") else fields["velocity"] = field
            val reopened = assertIs<PluckPatch>(PluckPatch.fromJsonValue(JsonValue.Obj(fields)))
            assertEquals(1f, reopened.velocity)
            assertEquals(emptyMap(), reopened.macros)
            assertTrue(patch.render().samples.contentEquals(reopened.render().samples))
        }
    }

    @Test
    fun `upright native velocity replaces saved strength instead of moving a macro`() {
        val patch = PluckPresets.forVoice(PluckVoice.UPRIGHT).first().copy(velocity = .8f)
        assertNull(Velocity.brightnessSpec(patch), "native excitation must not be mistaken for a brightness macro")
        for (velocity in listOf(.2f, .65f, 1f)) {
            val expected = patch.copy(velocity = velocity).render()
            val actual = Velocity.atVelocity(patch, velocity)
            assertTrue(expected.samples.contentEquals(actual.samples), "velocity $velocity did not reach the UPRIGHT renderer")
        }
        val zero = Velocity.atVelocity(patch, 0f)
        assertTrue(zero.samples.all { it == 0f }, "zero excitation must remain silent")
    }

    @Test
    fun `upright velocity variants reach its excitation renderer`() {
        val patch = PluckPresets.forVoice(PluckVoice.UPRIGHT).first()
        val reference = patch.render()
        val layers = Velocity.variantsAt(reference, patch, count = 2)
        for ((index, velocity) in listOf(1f - 2f / 3f, 1f - 1f / 3f).withIndex()) {
            val expected = Velocity.peakMatch(reference, patch.copy(velocity = velocity).render())
            assertTrue(expected.samples.contentEquals(layers[index].samples), "layer $index used a filtered finished waveform")
        }
        assertFalse(layers[0].samples.contentEquals(layers[1].samples), "soft and mid excitation lost their timbral difference")
    }

    @Test
    fun `upright does not change the old pluck brightness route`() {
        for (voice in legacyVoices) {
            val patch = PluckPatch("LEGACY", voice, emptyMap())
            assertEquals("PICK", Velocity.brightnessSpec(patch)?.name, voice.name)
            assertEquals(1f, patch.velocity)
        }
    }

    @Test
    fun `upright patches reject invalid velocity and foreign macros`() {
        for (velocity in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val error = assertFailsWith<IllegalArgumentException> {
                PluckPatch("BAD", PluckVoice.UPRIGHT, emptyMap(), velocity)
            }
            assertTrue(error.message.orEmpty().contains("velocity"))
        }
        val error = assertFailsWith<IllegalArgumentException> {
            PluckPatch("BAD", PluckVoice.UPRIGHT, mapOf("HOLD" to 1f))
        }
        assertTrue(error.message.orEmpty().contains("unknown macro HOLD"))
        val wrongType = JsonValue.Obj(LinkedHashMap(PluckPatch("BAD", PluckVoice.UPRIGHT, emptyMap()).toJsonValue().entries).apply {
            put("velocity", JsonValue.Str("soft"))
        })
        assertFailsWith<com.snipsnap.json.JsonException> { PluckPatch.fromJsonValue(wrongType) }
    }

    @Test
    fun `upright factory roster contains only the two approved settings`() {
        val presets = PluckPresets.forVoice(PluckVoice.UPRIGHT)
        assertEquals(listOf("ROUND", "MUTED"), presets.map { it.name })
        assertEquals(listOf(.18f, 1f), presets.map { it.macros.getValue("DAMP") })
        for (patch in presets) {
            assertEquals(.65f, patch.velocity)
            assertEquals(5f / 24f, patch.macros["TUNE"])
            assertEquals(.5f, patch.macros["TOUCH"])
            assertEquals(.55f, patch.macros["WOOD"])
            assertEquals(presets, Presets.forVoice("PLUCK", "UPRIGHT"))
        }
    }
}
