package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CircuitIntegrationTest {

    @Test
    fun `every voice and every macro survives the shared patch and pad recipe formats`() {
        for (voice in CircuitVoice.entries) {
            val macros = Circuit.defaults(voice) + mapOf("TUNE" to 0.25f, "HOLD" to 1f)
            val patch = CircuitPatch("Saved Circle", voice, macros)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), voice.name)
            assertEquals(patch, CircuitPatch.fromJsonText(patch.toJsonText()), voice.name)
            val recipe = PadRecipe(patch, FxChain(tape = mapOf("WOBBLE" to 0.1f)))
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()), voice.name)
            assertEquals(
                patch.copy(name = "Edited Circle", macros = mapOf("ORBIT" to 0f)),
                Patches.edited(patch, "Edited Circle", mapOf("ORBIT" to 0f)),
            )
        }
        val partial = CircuitPatch("Sparse", CircuitVoice.ROOT, mapOf("PACE" to 0.1f))
        assertEquals(partial, Patches.fromJsonText(partial.toJsonText()), "partial recipes keep omitted defaults implicit")
    }

    @Test
    fun `the patch refuses unknown macros invalid values and incompatible serialized identities`() {
        assertFailsWith<IllegalArgumentException> { CircuitPatch(" ", CircuitVoice.ROOT, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { CircuitPatch("x", CircuitVoice.ROOT, mapOf("TEMPO" to 0.5f)) }
        for (bad in listOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { CircuitPatch("x", CircuitVoice.ROOT, mapOf("BREATH" to bad)) }
        }
        val patch = CircuitPatch("x", CircuitVoice.ROOT, emptyMap())
        val source = patch.toJsonText()
        assertFailsWith<JsonException> { CircuitPatch.fromJsonText(source.replace("CIRCUIT", "TREMOR")) }
        assertFailsWith<JsonException> { Patches.fromJsonText(source.replace("ROOT", "NOT_A_VOICE")) }
        val wrongVersion = JsonValue.Obj(patch.toJsonValue().entries + ("version" to JsonValue.Num(99.0)))
        assertFailsWith<JsonException> { Patches.fromJsonValue(wrongVersion) }
    }

    @Test
    fun `the twelve presets are complete distinct named sounds reachable through the common roster`() {
        val presets = CircuitPresets.all()
        assertEquals(12, presets.size)
        val reserved = Presets.all().map { it.engine }.toSet() +
            CircuitVoice.entries.map { it.name } + FxChain.SECTION_NAMES.map { it.uppercase(Locale.ROOT) } +
            setOf("KICK", "SNARE", "CLAP", "HAT", "TOM")
        for (voice in CircuitVoice.entries) {
            val roster = CircuitPresets.forVoice(voice)
            assertEquals(2, roster.size)
            assertEquals(roster, Presets.forVoice("CIRCUIT", voice.name))
            assertEquals(roster.size, roster.map { it.name }.toSet().size)
            assertTrue(PresetTestSupport.rmsDistance(roster[0].macros, roster[1].macros) > 0.05f, voice.name)
            for (patch in roster) {
                assertTrue(patch.name.length <= 14, patch.name)
                assertEquals(patch.name.uppercase(Locale.ROOT), patch.name)
                assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
                assertTrue(patch.name.split(' ').none { it in reserved }, patch.name)
                assertEquals(Circuit.macrosFor(voice).map { it.name }.toSet(), patch.macros.keys, patch.name)
                assertEquals(patch, Presets.byName("CIRCUIT", voice.name, patch.name))
                assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
                assertEquals(null, Presets.landingFor("CIRCUIT", voice.name, patch.macros))
            }
        }
        assertTrue(Presets.forVoice("CIRCUIT", "NOT_A_VOICE").isEmpty())
        assertEquals(presets, Presets.all().filter { it.engine == CircuitPatch.ENGINE })
    }

    @Test
    fun `the pitched kit carries dry editable recipes across the scale and all six voices`() {
        val pads = SynthKits.circuit()
        assertEquals(16, pads.size)
        val patches = pads.mapIndexed { i, nullable ->
            val pad = requireNotNull(nullable)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = assertIs<CircuitPatch>(recipe.patch)
            assertEquals(null, recipe.fx, "pad ${i + 1} keeps the canyon inside CIRCUIT")
            assertEquals(Circuit.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertTrue(pad.drumClass in setOf(DrumClass.TONAL, DrumClass.PERC, DrumClass.LOOP), "pitched ensemble routing")
            assertTrue(pad.snip.peak() > 0f, "pad ${i + 1} has audio")
            patch
        }
        assertEquals(CircuitVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertEquals(listOf(36, 39, 41, 43, 46, 48, 51, 53), patches.take(8).map { Circuit.midiFor(it.macros.getValue("TUNE")) })
        assertTrue(patches.all { Circuit.midiFor(it.macros.getValue("TUNE")) % 12 in setOf(0, 3, 5, 7, 10) }, "every pad belongs to the starter's declared C minor pentatonic key")
        assertTrue(patches.take(8).all { it.voice == CircuitVoice.ROOT && it.macros.getValue("HOLD") == 0f })
        assertTrue(Circuit.isLoop(patches.last().macros.getValue("HOLD")), "the last pad is the recurring ensemble")
        val restored = PadRecipe.fromJsonValue(pads.first()!!.recipe!!).render()
        assertTrue(restored.samples.contentEquals(pads.first()!!.snip.samples), "stored recipes regenerate the pad exactly")
    }

    @Test
    fun `velocity layers use the ensemble's event energy without editing its macros`() {
        val patch = CircuitPatch("Touch", CircuitVoice.VOICED, Circuit.defaults(CircuitVoice.VOICED))
        val asked = patch.macros.toMap()
        val soft = Velocity.atVelocity(patch, 0.25f)
        val expected = Circuit.render(patch.voice, patch.macros, velocity = 0.25f)
        assertTrue(expected.samples.contentEquals(soft.samples), "native velocity reaches CIRCUIT")
        assertEquals(asked, patch.macros)
        assertFalse(soft.samples.contentEquals(patch.render().samples), "quiet event energy changes the generated ensemble")
    }
}
