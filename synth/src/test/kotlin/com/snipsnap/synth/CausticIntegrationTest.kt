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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved native notes and production routing; numerical probes live in CausticInstrumentTest. */
class CausticIntegrationTest {
    @Test fun `native patch round trips explicit performance state and partial controls`() {
        val patch = CausticPatch("Saved Alloy", CausticVoice.ALLOY, mapOf("FOCUS" to .28f, "BODY" to .6f),
            midi = 55, velocity = .63f, model = 1)
        val fields = patch.toJsonValue().entries
        assertEquals("CAUSTIC", fields.getValue("engine").str())
        assertEquals(1, fields.getValue("version").int())
        assertEquals(1, fields.getValue("model").int(), "native model numbering is separate from archived prototype models")
        assertEquals(55, fields.getValue("midi").int())
        assertEquals(.63f, fields.getValue("velocity").num().toFloat())
        assertEquals(patch.macros.keys, fields.getValue("macros").obj().keys)
        assertEquals(patch, CausticPatch.fromJsonText(patch.toJsonText()))
        assertEquals(patch, assertIs<CausticPatch>(Patches.fromJsonText(patch.toJsonText())))
        assertEquals(patch.toJsonText(), Patches.fromJsonText(patch.toJsonText()).toJsonText())
        assertTrue(CausticPatch.ENGINE in PatchTypes.engines)
        assertFalse(CausticPatch.ENGINE in PatchTypes.ROSTERLESS || CausticPatch.ENGINE in PatchTypes.PROTOTYPES)
    }

    @Test fun `omitted native fields use stable native defaults and prototype JSON stays separate`() {
        val fields = CausticPatch("Partial", CausticVoice.ALLOY, mapOf("BODY" to .4f)).toJsonValue().entries -
            setOf("midi", "velocity", "model")
        for (obj in listOf(fields, fields + mapOf("midi" to JsonValue.Null, "velocity" to JsonValue.Null, "model" to JsonValue.Null))) {
            val restored = CausticPatch.fromJsonValue(JsonValue.Obj(obj))
            assertEquals(60, restored.midi)
            assertEquals(.75f, restored.velocity)
            assertEquals(CausticPatch.MISSING_MODEL_VERSION, restored.model)
            assertEquals(mapOf("BODY" to .4f), restored.macros)
        }
        assertEquals(emptyMap(), CausticPatch.fromJsonValue(JsonValue.Obj(fields - "macros")).macros)
        assertFailsWith<JsonException> { Patches.fromJsonValue(CausticRecipe().toJsonValue()) }
        assertFailsWith<JsonException> { CausticRecipe.fromJsonValue(JsonValue.Obj(fields)) }
    }

    @Test fun `invalid notes native models controls and reserved HOLD fail before rendering`() {
        val patch = CausticPatch("Valid", CausticVoice.ALLOY, emptyMap())
        for (midi in listOf(35, 85)) assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        for (model in listOf(0, 2, 4)) assertFailsWith<IllegalArgumentException> { patch.copy(model = model) }
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("BODY" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
        for (hold in listOf(.001f, .5f, 1f)) {
            val error = assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("HOLD" to hold)) }
            assertTrue(error.message.orEmpty().contains("HOLD"))
        }
        val fields = patch.toJsonValue().entries
        for ((key, value) in listOf(
            "engine" to JsonValue.Str("CREASE"), "voice" to JsonValue.Str("UNKNOWN"),
            "version" to JsonValue.Num(4.0), "model" to JsonValue.Num(4.0),
            "midi" to JsonValue.Num(60.5), "midi" to JsonValue.Num(85.0),
            "velocity" to JsonValue.Num(1.000000001), "velocity" to JsonValue.Num(Double.NaN),
            "macros" to JsonValue.Arr(emptyList()),
            "macros" to JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(.1))),
        )) assertFailsWith<JsonException>("$key=$value") { CausticPatch.fromJsonValue(JsonValue.Obj(fields + (key to value))) }
    }

    @Test fun `macro edits and dry pad replay retain saved root velocity and native model`() {
        val patch = CausticPatch("Low body", CausticVoice.ALLOY,
            mapOf("SOURCE" to .7f, "BODY" to .6f, "LENGTH" to 0f), midi = 43, velocity = .54f)
        val controls = patch.macros + ("FOCUS" to .31f)
        val edited = assertIs<CausticPatch>(Patches.edited(patch, "Moved body", controls))
        assertEquals(patch.copy(name = "Moved body", macros = controls), edited)
        assertEquals(patch.copy(macros = controls), patch.withMacros(controls))
        val recipe = PadRecipe(edited)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        assertNull(restored.fx)
        assertContentEquals(edited.render().samples, restored.render().samples)
    }

    @Test fun `native velocity changes excitation while retaining geometry root and body`() {
        val patch = CausticPatch("Touch", CausticVoice.ALLOY,
            mapOf("SOURCE" to .5f, "FOCUS" to .19f, "BODY" to .8f, "LENGTH" to 0f),
            midi = 55, velocity = .9f)
        val expected = patch.copy(velocity = .25f).render()
        val routed = Velocity.atVelocity(patch, .25f)
        assertContentEquals(expected.samples, routed.samples)
        assertContentEquals(expected.samples, Velocity.atVelocity(patch, .25f, spec = null).samples)
        val hard = patch.render()
        assertFalse(hard.samples.contentEquals(routed.samples))
        assertFalse(Velocity.soften(hard, .75f).samples.contentEquals(routed.samples), "velocity must reach the exciter rather than a post filter")
        assertEquals(55, patch.midi)
        assertEquals(.9f, patch.velocity)
        assertEquals(.8f, patch.macros.getValue("BODY"))
        assertTrue(Velocity.atVelocity(patch, 0f).samples.all { it == 0f })
    }

    @Test fun `finite notes use actual duration categories without promising a held loop`() {
        val short = CausticPatch("Short", CausticVoice.ALLOY, mapOf("LENGTH" to .125f))
        val long = short.copy(name = "Long", macros = mapOf("LENGTH" to .15f))
        val a = short.render()
        val b = long.render()
        assertTrue(a.durationSeconds <= 1.5f)
        assertTrue(b.durationSeconds > 1.5f)
        assertEquals(DrumClass.TONAL, CausticInstrument.drumClassFor(short.voice, short.macros, short.model))
        assertEquals(DrumClass.LOOP, CausticInstrument.drumClassFor(long.voice, long.macros, long.model))
        assertEquals(DrumClass.LOOP, Classifier.classify(b).drumClass)
        assertTrue(Classifier.classify(a).drumClass in setOf(DrumClass.TONAL, DrumClass.PERC))
        assertEquals(0f, CausticInstrument.defaults(short.voice).getValue("HOLD"))
    }
}
