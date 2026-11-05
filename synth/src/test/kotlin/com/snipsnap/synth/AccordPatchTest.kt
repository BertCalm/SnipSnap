package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AccordPatchTest {
    private fun authored(seed: Long = Long.MIN_VALUE) = AccordPatch(
        "Saved ensemble", AccordVoice.TINE,
        Accord.defaults(AccordVoice.TINE) + mapOf("CONTACT" to .7f, "RAFT" to .4f),
        midi = 55, velocity = .42f, seed = seed,
        acousticProfile = AccordAcousticProfile.LONG_RING,
        initializer = AccordInitializer.PROJECT_SINE_V1,
        sourceStartCycles = 1.25, sourceSeconds = 1.0,
    )

    private fun changed(patch: AccordPatch, key: String, value: JsonValue): JsonValue.Obj =
        JsonValue.Obj(patch.toJsonValue().entries + (key to value))

    @Test
    fun `saved ensemble preserves every performance field and exact signed seed through edits`() {
        for (seed in listOf(Long.MIN_VALUE, -1L, 0L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val patch = authored(seed)
            assertEquals(JsonValue.Str(seed.toString()), patch.toJsonValue().entries["seed"])
            assertEquals(patch, AccordPatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            val macros = patch.macros + ("PACE" to .6f)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertEquals(patch.copy(name = "Edited ensemble", macros = macros),
                Patches.edited(patch, "Edited ensemble", macros))
        }
    }

    @Test
    fun `default identity and all supported voice profile combinations round trip`() {
        val default = AccordPatch("Starting point")
        assertEquals(AccordVoice.BRUSH, default.voice)
        assertEquals(AccordAcousticProfile.BASE, default.acousticProfile)
        assertEquals(AccordInitializer.REFERENCE_SINE_20261022, default.initializer)
        assertEquals(Accord.DEFAULT_SEED, default.seed)
        assertEquals(48, default.midi)
        assertEquals(.75f, default.velocity)
        assertEquals(8.0, default.sourceStartCycles)
        assertEquals(8.0, default.sourceSeconds)
        assertEquals(default, AccordPatch.fromJsonText(default.toJsonText()))
        for (voice in AccordVoice.entries) {
            val profiles = if (voice == AccordVoice.TINE) AccordAcousticProfile.entries.filter { it != AccordAcousticProfile.BASE }
                else listOf(AccordAcousticProfile.BASE)
            for (profile in profiles) {
                val patch = default.copy(voice = voice, acousticProfile = profile)
                assertEquals(patch, AccordPatch.fromJsonText(patch.toJsonText()))
            }
        }
    }

    @Test
    fun `round trip regenerates audio and velocity uses saved ensemble instead of brightness fallback`() {
        val patch = authored().copy(sourceStartCycles = 0.0, sourceSeconds = Accord.MIN_SOURCE_SECONDS)
        val restored = AccordPatch.fromJsonText(patch.toJsonText())
        assertContentEquals(patch.render().samples, restored.render().samples)
        assertNull(Velocity.brightnessSpec(patch), "no brightness override is claimed for an unmeasured Accord control")
        assertContentEquals(patch.copy(velocity = .25f).render().samples,
            Velocity.atVelocity(patch, .25f).samples,
            "velocity must retain the note, seed, profile, initializer and passage")
        assertEquals(.42f, patch.velocity)
        assertEquals(AccordAcousticProfile.LONG_RING, patch.acousticProfile)
    }

    @Test
    fun `custom saved fields are required and malformed values cannot change the recipe`() {
        val patch = authored()
        for (key in listOf("model", "midi", "velocity", "seed", "acousticProfile", "initializer", "sourceStartCycles", "sourceSeconds")) {
            assertFailsWith<JsonException>("missing $key") {
                AccordPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries - key))
            }
            assertFailsWith<JsonException>("null $key") {
                AccordPatch.fromJsonValue(changed(patch, key, JsonValue.Null))
            }
        }
        for (bad in listOf(35.0, 73.0, 48.25, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "midi", JsonValue.Num(bad))) }
        }
        for (bad in listOf(0.0, 2.0, 1.5, Double.NaN)) {
            assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "model", JsonValue.Num(bad))) }
        }
        for (bad in listOf(-Double.MIN_VALUE, Math.nextUp(1.0), Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "velocity", JsonValue.Num(bad))) }
        }
        for (bad in listOf(-Double.MIN_VALUE, Math.nextUp(8.0), Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "sourceStartCycles", JsonValue.Num(bad))) }
        }
        for (bad in listOf(Math.nextDown(Accord.MIN_SOURCE_SECONDS), Math.nextUp(Accord.MAX_SOURCE_SECONDS), Double.NaN, Double.NEGATIVE_INFINITY)) {
            val failure = assertFailsWith<JsonException>("sourceSeconds=$bad must fail at the saved-duration boundary") {
                AccordPatch.fromJsonValue(changed(patch, "sourceSeconds", JsonValue.Num(bad)))
            }
            kotlin.test.assertTrue("sourceSeconds" in failure.message.orEmpty(),
                "duration $bad was refused for another field: ${failure.message}")
        }
        for (bad in listOf("9223372036854775808", "1.5", "-01", "-0", "+1", " 1", "01")) {
            assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "seed", JsonValue.Str(bad))) }
        }
        assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "seed", JsonValue.Num(1.0))) }
        assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "acousticProfile", JsonValue.Str("FUTURE"))) }
        assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "initializer", JsonValue.Str("FUTURE"))) }
    }

    @Test
    fun `stored controls validate at JSON precision before Float conversion`() {
        val patch = authored()
        for (bad in listOf(-Double.MIN_VALUE, Math.nextUp(1.0), Double.NaN, Double.POSITIVE_INFINITY)) {
            val macros = JsonValue.Obj(mapOf("CONTACT" to JsonValue.Num(bad)))
            assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "macros", macros)) }
        }
        // These positive values disappear at Float precision. A saved finite
        // recipe must still refuse an explicit request for unsupported sustain.
        for (bad in listOf(1e-100, .01, 1.0)) {
            val macros = JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(bad)))
            assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "macros", macros)) }
        }
        assertFailsWith<JsonException> {
            AccordPatch.fromJsonValue(changed(patch, "macros", JsonValue.Obj(mapOf("UNKNOWN" to JsonValue.Num(.5)))))
        }
        assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(patch, "macros", JsonValue.Arr(emptyList()))) }
        val partial = changed(patch, "macros", JsonValue.Obj(mapOf("CONTACT" to JsonValue.Num(.25))))
        assertEquals(mapOf("CONTACT" to .25f), AccordPatch.fromJsonValue(partial).macros)
        assertEquals(emptyMap(), AccordPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries - "macros")).macros)
        assertEquals(emptyMap(), AccordPatch.fromJsonValue(changed(patch, "macros", JsonValue.Null)).macros)
    }

    @Test
    fun `fixed reference initializer and acoustic profiles cannot be silently remapped`() {
        val reference = AccordPatch("Reference")
        assertFailsWith<IllegalArgumentException> { reference.copy(seed = 7) }
        assertFailsWith<JsonException> { AccordPatch.fromJsonValue(changed(reference, "seed", JsonValue.Str("7"))) }
        assertFailsWith<IllegalArgumentException> { reference.copy(acousticProfile = AccordAcousticProfile.REFERENCE) }
        assertFailsWith<IllegalArgumentException> { reference.copy(voice = AccordVoice.TINE) }
        assertFailsWith<JsonException> {
            AccordPatch.fromJsonValue(changed(reference, "acousticProfile", JsonValue.Str("SOFT_ATTACK")))
        }
        assertFailsWith<JsonException> {
            AccordPatch.fromJsonValue(changed(authored(), "acousticProfile", JsonValue.Str("BASE")))
        }
        assertFailsWith<IllegalArgumentException> { reference.copy(macros = mapOf("HOLD" to Float.MIN_VALUE)) }
        assertFailsWith<IllegalArgumentException> { reference.copy(macros = mapOf("PACE" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { reference.copy(macros = mapOf("UNKNOWN" to .5f)) }
        assertFailsWith<IllegalArgumentException> { reference.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { reference.copy(sourceStartCycles = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { reference.copy(sourceSeconds = .1) }
    }
}
