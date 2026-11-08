package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.*

class RivetPresetsTest {
    @Test fun `accepted factory bank is the audition kit with all six voices registered`() {
        val bank = RivetPresets.all()
        assertEquals(RivetKits.patches(.55f), bank)
        assertEquals(16, bank.size)
        assertEquals(16, bank.map { it.name }.toSet().size)
        assertEquals(RivetVoice.entries.toSet(), bank.map { it.voice }.toSet())
        assertEquals(bank, Presets.all().filterIsInstance<RivetPatch>())
        for (voice in RivetVoice.entries) {
            assertEquals(bank.filter { it.voice == voice }, Presets.forVoice("RIVET", voice.name))
        }
        assertTrue(Presets.forVoice("RIVET", "UNKNOWN").isEmpty())
        for (patch in bank) {
            assertEquals(0f, patch.macros["HOLD"])
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertContentEquals(patch.render().samples,
                Rivet.render(patch.voice, patch.macros, patch.modelVersion).samples)
        }
    }

    @Test fun `old recipes keep full strike and native velocity survives edits and replay`() {
        for (voice in RivetVoice.entries) {
            val original = RivetPatch("Old hit", voice,
                modelVersion = if (voice == RivetVoice.KICK || voice == RivetVoice.SNARE) 1 else 2)
            val oldJson = JsonValue.Obj(original.toJsonValue().entries - "velocity")
            val old = assertIs<RivetPatch>(Patches.fromJsonValue(oldJson))
            assertEquals(original, old)
            assertContentEquals(original.render().samples, old.render().samples)
            val soft = original.copy(velocity = .37f)
            val edited = assertIs<RivetPatch>(Patches.edited(soft, "Edited", soft.macros + ("SIZE" to .87f)))
            assertEquals(.37f, edited.velocity)
            assertEquals(original.modelVersion, edited.modelVersion)
            assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
            assertContentEquals(edited.render().samples, Velocity.atVelocity(edited, 1f).samples)
            assertContentEquals(edited.copy(velocity = .185f).render().samples,
                Velocity.atVelocity(edited, .5f).samples)
            assertFalse(original.render().samples.contentEquals(soft.render().samples))
        }
    }

    @Test fun `invalid saved strike is refused rather than clamped`() {
        val p = RivetPatch("Hit", RivetVoice.METAL)
        for (v in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { p.copy(velocity = v.toFloat()) }
            assertFailsWith<JsonException> {
                Patches.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries + ("velocity" to JsonValue.Num(v))))
            }
        }
    }
}
