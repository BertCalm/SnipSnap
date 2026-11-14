package com.snipsnap.synth

import com.snipsnap.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Metadata contracts run before the more expensive original-source PCM replay. */
class SplayFactoryRegistrationTest {
    @Test fun `factory dispatcher serves five original accepted inputs under one voice`() {
        val factory = Presets.forVoice("SPLAY", "TONGUE")
        assertEquals(listOf("TONGUE"), SplayVoice.entries.map { it.name })
        assertEquals(listOf("1 mechanism", "2 mechanisms", "4 mechanisms", "8 mechanisms", "16 mechanisms"),
            factory.map { it.name })
        assertEquals(factory, Presets.all().filter { it.engine == SplayPatch.ENGINE })
        assertTrue(Presets.forVoice("SPLAY", "POPULATION_16").isEmpty())
        for ((index, id) in listOf("pc-1", "pc-2", "pc-4", "pc-8", "pc-16").withIndex()) {
            val source = requireNotNull(javaClass.getResourceAsStream("/splay-accepted/$id.patch.json"))
                .bufferedReader().use { Json.parse(it.readText()) }
            assertEquals(source, factory[index].toJsonValue(), "$id: every accepted native input remains exact")
            assertEquals(factory[index], Patches.fromJsonValue(source), "$id: actual sealed patch registry")
        }
    }

    @Test fun `historical provisional bank keeps its order defaults and independent API`() {
        val legacy = SplayPresets.all()
        assertEquals(listOf("First Seams", "Soft Brush", "Early Scatter", "Slow Thread", "Small Mechanism"),
            legacy.map { it.name })
        assertEquals(legacy, SplayPresets.forVoice(SplayVoice.TONGUE))
        assertTrue(legacy.all { it.model == 2 && it.midi == 60 && it.velocity == .8f && it.seed == 41001 })
        assertEquals(15f / 99f, legacy.first().macros.getValue("POPULATION"))
        assertEquals(3f / 99f, legacy.last().macros.getValue("POPULATION"))
        assertTrue(Presets.all().none { it.engine == "SPLAY" && it.name in legacy.map { p -> p.name } },
            "Registration does not silently promote unheard provisional recipes")
    }

    @Test fun `saved acoustic models retain their macro defaults and continue refusing positive hold`() {
        for (model in listOf(1, 2)) {
            val patch = SplayPatch("Partial legacy $model", SplayVoice.TONGUE, emptyMap(),
                midi = 49, velocity = .37f, seed = Int.MIN_VALUE, model = model)
            val restored = Patches.fromJsonText(patch.toJsonText()) as SplayPatch
            assertEquals(patch, restored)
            assertEquals(Splay.macrosFor(SplayVoice.TONGUE, model), Velocity.macroSpecsFor(restored))
            val refused = assertFailsWith<IllegalArgumentException> { restored.withMacros(mapOf("HOLD" to .01f)) }
            assertTrue(refused.message.orEmpty().contains("requires HOLD 0"))
        }
    }
}
