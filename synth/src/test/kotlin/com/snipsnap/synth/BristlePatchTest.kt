package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import kotlin.test.*

class BristlePatchTest {
    @Test fun `registered presets replay their source recipes and survive common codecs`() {
        for (route in Bristle.Route.entries) {
            val patch = BristlePresets.forVoice(route).single()
            assertEquals(listOf(patch), Presets.forVoice("BRISTLE", route.name))
            assertTrue(patch in Presets.all())
            val loaded = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, loaded)
            assertContentEquals(Bristle.Recipe(route, macros = Bristle.defaults).render().samples, loaded.render().samples)
            assertContentEquals(loaded.render().samples, loaded.render().samples)
        }
    }
    @Test fun `editing preserves performance and applies tune once`() {
        val patch = BristlePresets.all().last().copy(midi = 32, velocity = .65f)
        val edited = Patches.edited(patch, "Saved Bass", patch.macros + ("TUNE" to .75f)) as BristlePatch
        assertEquals(32, edited.midi)
        assertEquals(.65f, edited.velocity)
        assertEquals(38, Bristle.rootMidi(edited))
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        assertContentEquals(Bristle.Recipe(edited.voice, 38, .65f, edited.macros).render().samples, edited.render().samples)
    }
    @Test fun `future models and malformed saved values cannot silently migrate`() {
        val patch = BristlePresets.all().first()
        for ((key, value) in listOf("model" to JsonValue.Num(2.0), "midi" to JsonValue.Num(49.0),
            "velocity" to JsonValue.Num(1.000000001), "future" to JsonValue.Num(1.0))) {
            assertFails { Patches.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries + (key to value))) }
        }
        val macros = JsonValue.Obj(mapOf("GRIT" to JsonValue.Num(-.000000001)))
        assertFails { Patches.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries + ("macros" to macros))) }
    }
    @Test fun `all controls are registered and scramble remains bounded`() {
        for (route in Bristle.Route.entries) {
            assertEquals(Bristle.defaults.keys, Bristle.macrosFor(route).map { it.name }.toSet())
            val m = Bristle.scramble(route, kotlin.random.Random(22))
            assertEquals(Bristle.defaults.keys, m.keys)
            assertTrue(m.values.all { it.isFinite() && it in 0f..1f })
        }
    }
}
