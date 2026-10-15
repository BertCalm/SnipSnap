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
        assertFails { FlockPatch.fromJsonValue(changed("model", JsonValue.Num(2.0))) }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) assertFails {
            FlockPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("FLOW" to JsonValue.Num(bad)))))
        }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = mapOf("NOISE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = mapOf("FLOW" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { p.copy(name = " ") }
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
}
