package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OhmOptionsTest {
    private fun capture() = OhmCapture(FloatArray(2048) { i ->
        (.6 * sin(2 * PI * i * 397 / 44_100)).toFloat()
    }, "Original stroke")

    @Test
    fun `legacy defaults remain model two and do not gain optional fields`() {
        val legacy = OhmPatch("Accepted", Ohm.defaults())
        assertEquals(2, legacy.model)
        assertEquals(3, Ohm.MODEL_VERSION)
        assertEquals(5, Ohm.MACROS.size)
        assertEquals(6, Ohm.macrosFor(3).size)
        assertFalse("options" in legacy.toJsonValue().entries)
        assertFalse("releaseSeconds" in legacy.toJsonValue().entries)
        assertFailsWith<IllegalArgumentException> { legacy.copy(macros = legacy.macros + ("HOLD" to 1f)) }
        assertFailsWith<IllegalArgumentException> { legacy.copy(options = OhmOptions(elder = .1f)) }
        assertFailsWith<IllegalArgumentException> { legacy.copy(releaseSeconds = .8f) }
    }

    @Test
    fun `the entire captured family survives dispatch edits recipes and native velocity`() {
        val source = OhmPatch("Recorded family", Ohm.defaults(3) + mapOf("LENGTH" to 0f, "COMMUNE" to .6f),
            midi = 57, velocity = .7f, seed = -805, gesture = OhmGesture.GATHER, model = 3,
            options = OhmOptions(OhmExciter.CAPTURED, .4f, .3f, .5f, capture = capture()), releaseSeconds = .9f)
        assertEquals(source, Patches.fromJsonText(source.toJsonText()))
        val restored = PadRecipe.fromJsonText(PadRecipe(source).toJsonText())
        assertEquals(source, restored.patch)
        val edited = assertIs<OhmPatch>(Patches.edited(source, "Moved", source.macros + ("BODY" to .8f)))
        assertEquals(source.options, edited.options)
        assertEquals(.9f, edited.releaseSeconds)
        assertContentEquals(source.copy(velocity = .4f).render().samples, Velocity.atVelocity(source, .4f).samples)
    }

    @Test
    fun `invalid source state is rejected instead of silently becoming another instrument`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            assertFailsWith<IllegalArgumentException> { OhmOptions(elder = bad) }
            assertFailsWith<IllegalArgumentException> { OhmOptions(grains = bad) }
            assertFailsWith<IllegalArgumentException> { OhmOptions(freeze = bad) }
        }
        assertFailsWith<IllegalArgumentException> { OhmOptions(OhmExciter.CAPTURED) }
        assertFailsWith<IllegalArgumentException> { OhmOptions(fmIndex = 8f) }
        assertFailsWith<IllegalArgumentException> { OhmOptions(fmRatio = 4f) }
        assertFailsWith<IllegalArgumentException> { OhmOptions(grainSeconds = .5f) }
        assertFailsWith<JsonException> {
            OhmOptions.fromJsonValue(JsonValue.Obj(OhmOptions().toJsonValue().entries + ("spectralFreeze" to JsonValue.Num(1.0))))
        }
        val legacy = OhmPatch("Legacy", emptyMap())
        assertFailsWith<JsonException> {
            OhmPatch.fromJsonValue(JsonValue.Obj(legacy.toJsonValue().entries + ("options" to OhmOptions(elder = .5f).toJsonValue())))
        }
        val modern = legacy.copy(model = 3)
        assertTrue(Ohm.isHeld(modern.copy(macros = mapOf("HOLD" to 1f))))
        assertFalse(Ohm.isHeld(modern))
    }
}
