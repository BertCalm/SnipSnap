package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.Patches
import kotlin.test.Test
import kotlin.test.assertEquals

class CausticSynthDraftsTest {
    @Test
    fun `an edited Caustic draft keeps its root velocity and native model after its highlight clears`() {
        val source = CausticPatch("Saved metal", CausticVoice.ALLOY,
            mapOf("BODY" to .81f), midi = 48, velocity = .37f, model = 1)
        val settings = mutableMapOf("engine" to "CAUSTIC", "voice" to "ALLOY",
            "preset.CAUSTIC.ALLOY" to source.name)
        SynthDrafts.write(settings, listOf(source))
        settings.remove("preset.CAUSTIC.ALLOY")
        val editedMacros = CausticInstrument.defaults(source.voice, source.model) + source.macros +
            mapOf("SOURCE" to .63f, "FOCUS" to .27f, "LENGTH" to .2f)
        for ((name, value) in editedMacros) settings["macro.CAUSTIC.ALLOY.$name"] = value.toString()

        val restored = SynthDrafts.read(settings).single()
        val sliders = editedMacros.mapValues { (name, _) ->
            settings.getValue("macro.CAUSTIC.ALLOY.$name").toFloat()
        }
        val edited = Patches.edited(restored, "Edited metal", sliders)
        assertEquals(source.copy(name = "Edited metal", macros = editedMacros), edited)
        SynthDrafts.write(settings, listOf(edited))
        assertEquals(listOf(edited), SynthDrafts.read(settings))
        assertEquals("CAUSTIC", settings["engine"])
        assertEquals("ALLOY", settings["voice"])
        assertEquals(.37f, (SynthDrafts.read(settings).single() as CausticPatch).velocity)
    }

    @Test
    fun `unsupported native models reserved HOLD and misplaced drafts do not become working sounds`() {
        val valid = CausticPatch("Valid", CausticVoice.ALLOY, emptyMap(), midi = 69, velocity = .47f)
        val unsupported = JsonValue.Obj(valid.toJsonValue().entries + ("model" to JsonValue.Num(4.0)))
        val held = JsonValue.Obj(valid.toJsonValue().entries +
            ("macros" to JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(.5)))))
        for (invalid in listOf(Json.write(unsupported), Json.write(held), "not JSON")) {
            val settings = mapOf("recipe.CAUSTIC.ALLOY" to invalid)
            assertEquals(emptyList(), SynthDrafts.read(settings))
        }
        assertEquals(emptyList(), SynthDrafts.read(mapOf("recipe.CAUSTIC.OTHER" to valid.toJsonText())))
        assertEquals(listOf(valid), SynthDrafts.read(mapOf("recipe.CAUSTIC.ALLOY" to valid.toJsonText())))
    }
}
