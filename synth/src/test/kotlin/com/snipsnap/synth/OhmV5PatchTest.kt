package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OhmV5PatchTest {
    @Test
    fun `saved reply ring release and performance survive editing pad recipes and loaded replay`() {
        val source = Ohm.replyRingPatch("Saved answers").copy(midi = 57, velocity = .65f, seed = -805,
            reply = .25f, ring = .75f, releaseSeconds = .15f)
        val fields = source.toJsonValue().entries
        assertEquals(.25, fields.getValue("reply").num())
        assertEquals(.75, fields.getValue("ring").num())
        assertEquals(.15f, fields.getValue("releaseSeconds").num().toFloat())
        val restored = assertIs<OhmPatch>(Patches.fromJsonText(source.toJsonText()))
        assertEquals(source, restored)
        val pad = PadRecipe.fromJsonText(PadRecipe(source).toJsonText())
        assertEquals(source, pad.patch)
        val macros = source.macros + ("BODY" to .8f)
        assertEquals(source.copy(name = "Edited answers", macros = macros),
            Patches.edited(source, "Edited answers", macros))
        assertEquals(source.copy(macros = macros), source.withMacros(macros))
        assertContentEquals(source.render().samples, pad.render().samples)
    }

    @Test
    fun `native velocity keeps the saved reply and ring controls`() {
        val p = Ohm.replyRingPatch("Native velocity").copy(reply = 0f, ring = 1f, seed = -731)
        assertContentEquals(p.copy(velocity = .35f).render().samples, Velocity.atVelocity(p, .35f).samples)
    }

    @Test
    fun `only model five writes reply and ring while omitted midpoint fields load safely`() {
        val p = Ohm.replyRingPatch()
        assertTrue("reply" in p.toJsonValue().entries && "ring" in p.toJsonValue().entries)
        for (model in 1..4) {
            val legacy = OhmPatch("Saved model$model", Ohm.defaults(model), model = model)
            assertFalse("reply" in legacy.toJsonValue().entries)
            assertFalse("ring" in legacy.toJsonValue().entries)
            assertEquals(legacy, Patches.fromJsonText(legacy.toJsonText()))
            assertFailsWith<IllegalArgumentException> { legacy.copy(reply = .25f) }
            assertFailsWith<IllegalArgumentException> { legacy.copy(ring = .75f) }
            for ((key, value) in listOf("reply" to .25, "ring" to .75)) {
                assertFailsWith<JsonException> {
                    OhmPatch.fromJsonValue(JsonValue.Obj(legacy.toJsonValue().entries + (key to JsonValue.Num(value))))
                }
            }
        }
        for (entries in listOf(p.toJsonValue().entries - "reply" - "ring",
            p.toJsonValue().entries + mapOf("reply" to JsonValue.Null, "ring" to JsonValue.Null))) {
            assertEquals(p, OhmPatch.fromJsonValue(JsonValue.Obj(entries)))
        }
        // Appended fields leave old positional calls through releaseSeconds unambiguous.
        val oldPositional = OhmPatch("Old positional", Ohm.defaults(3), 60, .8f, 731,
            OhmGesture.GATHER, 3, OhmOptions(), .15f)
        assertEquals(.5f, oldPositional.reply)
        assertEquals(.5f, oldPositional.ring)
        assertEquals(.15f, oldPositional.releaseSeconds)
    }

    @Test
    fun `invalid construction and saved combinations are refused without silently dropping fields`() {
        val p = Ohm.replyRingPatch()
        assertFailsWith<IllegalArgumentException> { p.copy(gesture = OhmGesture.GATHER) }
        for (hold in listOf(0f, 1f)) {
            assertFailsWith<IllegalArgumentException> { p.copy(macros = p.macros + ("HOLD" to hold)) }
        }
        // Even dormant details are unsupported in the approved pluck model; isNeutral is insufficient.
        for (options in listOf(OhmOptions(exciter = OhmExciter.BOW), OhmOptions(elder = .1f),
            OhmOptions(grains = .1f), OhmOptions(freeze = .1f), OhmOptions(fmRatio = .5f))) {
            assertFailsWith<IllegalArgumentException> { p.copy(options = options) }
            assertFailsWith<JsonException> {
                OhmPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries + ("options" to options.toJsonValue())))
            }
        }
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, -.01f, 1.01f)) {
            assertFailsWith<IllegalArgumentException> { p.copy(reply = bad) }
            assertFailsWith<IllegalArgumentException> { p.copy(ring = bad) }
        }
        for ((key, value) in listOf(
            "reply" to JsonValue.Num(-.1), "ring" to JsonValue.Num(1.1),
            "reply" to JsonValue.Str("0.5"), "ring" to JsonValue.Str("0.5"),
            "gesture" to JsonValue.Str("GATHER"),
        )) assertFailsWith<JsonException>(key) {
            OhmPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries + (key to value)))
        }
        val withHold = JsonValue.Obj(p.macros.mapValues { JsonValue.Num(it.value.toDouble()) } + ("HOLD" to JsonValue.Num(0.0)))
        assertFailsWith<JsonException> {
            OhmPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries + ("macros" to withHold)))
        }
        for (release in listOf(.02f, .15f, 3f)) {
            val valid = p.copy(releaseSeconds = release)
            assertEquals(valid, Patches.fromJsonText(valid.toJsonText()))
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, .019f, 3.01f)) {
            assertFailsWith<IllegalArgumentException> { p.copy(releaseSeconds = bad) }
        }
        for (bad in listOf(.019, 3.01)) assertFailsWith<JsonException> {
            OhmPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries + ("releaseSeconds" to JsonValue.Num(bad))))
        }
    }
}
