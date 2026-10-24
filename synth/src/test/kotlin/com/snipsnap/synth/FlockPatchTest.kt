package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlockPatchTest {
    private fun patch(seed: Long = Flock.DEFAULT_SEED) = FlockPatch("Saved flock", FlockVoice.RIBBON,
        Flock.defaults(FlockVoice.RIBBON) + ("HOLD" to 0f), midi = 57, velocity = .68f, seed = seed)

    @Test
    fun `patch serialization preserves all state including full width signed seeds`() {
        for (seed in listOf(Long.MIN_VALUE, -1, 0, 9_007_199_254_740_993, Long.MAX_VALUE)) {
            val p = patch(seed)
            val restored = FlockPatch.fromJsonText(p.toJsonText())
            assertEquals(p, restored)
            assertEquals(JsonValue.Str(seed.toString()), p.toJsonValue().entries["seed"])
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
        }
        val p = patch()
        val restored = FlockPatch.fromJsonText(p.toJsonText())
        assertContentEquals(p.render().samples, restored.render().samples)
        val edit = p.macros + ("EDGE" to .81f)
        assertEquals(p.copy(macros = edit), p.withMacros(edit))
        assertEquals(p.copy(name = "Edited", macros = edit), Patches.edited(p, "Edited", edit))
    }

    @Test
    fun `malformed note energy model seed and macros refuse instead of changing the recipe`() {
        val p = patch()
        fun changed(key: String, value: JsonValue): JsonValue.Obj = JsonValue.Obj(p.toJsonValue().entries + (key to value))
        for (key in listOf("midi", "velocity", "seed", "model")) {
            assertFails(key) { FlockPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries - key)) }
            assertFails("$key null") { FlockPatch.fromJsonValue(changed(key, JsonValue.Null)) }
        }
        for (bad in listOf(35.0, 85.0, 60.25, Double.NaN)) assertFails {
            FlockPatch.fromJsonValue(changed("midi", JsonValue.Num(bad)))
        }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) assertFails {
            FlockPatch.fromJsonValue(changed("velocity", JsonValue.Num(bad)))
        }
        for (bad in listOf("9223372036854775808", "1.5", "-01", "+1", " 1")) assertFails {
            FlockPatch.fromJsonValue(changed("seed", JsonValue.Str(bad)))
        }
        assertFails { FlockPatch.fromJsonValue(changed("seed", JsonValue.Num(1.0))) }
        for (model in listOf(0.0, 1.5, 3.0)) assertFails {
            FlockPatch.fromJsonValue(changed("model", JsonValue.Num(model)))
        }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) assertFails {
            FlockPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("FLOW" to JsonValue.Num(bad)))))
        }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = mapOf("NOISE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = mapOf("FLOW" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { p.copy(name = " ") }
    }

    @Test
    fun `stored model one and two remain distinct through rendering serialization and editing`() {
        assertEquals(2, patch().model)
        for (model in listOf(1, 2)) {
            val p = patch(Long.MAX_VALUE).copy(model = model, macros = patch().macros + ("FLOW" to 0f))
            assertEquals(p, FlockPatch.fromJsonText(p.toJsonText()))
            val edited = p.withMacros(p.macros + ("EDGE" to .8f))
            assertEquals(model, edited.model)
            assertEquals(p.seed, edited.seed)
            assertContentEquals(Flock.render(p.voice, p.macros, p.midi, p.velocity, p.seed, model = model).samples,
                p.render().samples, "patch discarded stored model $model")
        }
    }

    @Test
    fun `preset audition is finite complete and uniquely replayable`() {
        val presets = FlockPresets.all()
        assertEquals(9, presets.size)
        assertEquals(9, presets.map { it.name }.toSet().size)
        assertEquals(9, presets.map { it.seed }.toSet().size)
        for (voice in FlockVoice.entries) {
            assertEquals(3, FlockPresets.forVoice(voice).size)
            assertTrue(FlockPresets.forVoice(voice).all { it.macros.keys == Flock.defaults(voice).keys })
        }
    }

    @Test
    fun `held state is explicit versioned and preserved through editing without changing old HOLD`() {
        val finite = patch().copy(macros = patch().macros + ("HOLD" to 1f))
        assertTrue("playback" !in finite.toJsonValue().entries)
        assertTrue("heldVersion" !in finite.toJsonValue().entries)
        assertTrue("releaseSeconds" !in finite.toJsonValue().entries)
        assertEquals(FlockPlayback.FINITE, FlockPatch.fromJsonText(finite.toJsonText()).playback)
        for (model in listOf(1, 2)) {
            val p = patch(Long.MIN_VALUE).copy(model = model, playback = FlockPlayback.HELD, releaseSeconds = 1.1f)
            assertEquals(p, FlockPatch.fromJsonText(p.toJsonText()))
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
            val macros = p.macros + ("EDGE" to .21f)
            assertEquals(p.copy(name = "Held edit", macros = macros), Patches.edited(p, "Held edit", macros))
            assertEquals(p.copy(macros = macros), p.withMacros(macros))
            val toggled = p.copy(playback = FlockPlayback.FINITE)
            assertEquals(toggled, FlockPatch.fromJsonText(toggled.toJsonText()), "finite toggle discarded release/model/seed")
            assertEquals(JsonValue.Num(1.0), p.toJsonValue().entries["heldVersion"])
        }
        assertFailsWith<IllegalArgumentException> { finite.copy(voice = FlockVoice.GATHER, playback = FlockPlayback.HELD) }
        assertFailsWith<IllegalArgumentException> { finite.copy(voice = FlockVoice.SPLIT, playback = FlockPlayback.HELD) }
    }

    @Test
    fun `new held fields decode strictly before rounding and require a supported capture version`() {
        val p = patch().copy(playback = FlockPlayback.HELD)
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(p.toJsonValue().entries + (key to value))
        for (bad in listOf(JsonValue.Null, JsonValue.Num(1.0), JsonValue.Str("held"), JsonValue.Str("FOREVER")))
            assertFails { FlockPatch.fromJsonValue(changed("playback", bad)) }
        for (bad in listOf(JsonValue.Null, JsonValue.Str("1"), JsonValue.Num(0.0), JsonValue.Num(1.5), JsonValue.Num(2.0)))
            assertFails { FlockPatch.fromJsonValue(changed("heldVersion", bad)) }
        assertFails { FlockPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries - "heldVersion")) }
        for (bad in listOf(.1 - 1e-10, 1.5 + 1e-10, Double.NaN, Double.POSITIVE_INFINITY))
            assertFails { FlockPatch.fromJsonValue(changed("releaseSeconds", JsonValue.Num(bad))) }
        for (bad in listOf(JsonValue.Null, JsonValue.Str(".6")))
            assertFails { FlockPatch.fromJsonValue(changed("releaseSeconds", bad)) }
        for (release in listOf(.1, .6, 1.5)) {
            val restored = FlockPatch.fromJsonValue(changed("releaseSeconds", JsonValue.Num(release))) as FlockPatch
            assertEquals(release.toFloat(), restored.releaseSeconds)
        }
        for (release in listOf(Float.NaN, .099f, 1.501f))
            assertFailsWith<IllegalArgumentException> { p.copy(releaseSeconds = release) }
        assertFailsWith<IllegalArgumentException> { p.copy(heldVersion = 2) }
    }
}
