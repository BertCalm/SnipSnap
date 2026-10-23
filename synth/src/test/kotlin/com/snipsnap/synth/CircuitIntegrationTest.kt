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
            assertEquals(60, Circuit.midiFor(Circuit.defaults(voice).getValue("TUNE")), "$voice starts at C4")
            assertEquals(.5f, Circuit.macrosFor(voice).first { it.name == "TUNE" }.neutral,
                "$voice retains C3 as the neutral audition companion")
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
    fun `the factory presets are complete distinct named sounds reachable through the common roster`() {
        val presets = CircuitPresets.all()
        assertEquals(15, presets.size)
        fun stored(voice: CircuitVoice, name: String, tune: Float, breath: Float, diameter: Float,
                   orbit: Float, pace: Float, canyon: Float, hold: Float = 0f) =
            CircuitPatch(name, voice, mapOf("TUNE" to tune, "BREATH" to breath, "DIAMETER" to diameter,
                "ORBIT" to orbit, "PACE" to pace, "CANYON" to canyon, "HOLD" to hold))
        // These complete saved maps deliberately do not inherit today's defaults.
        // Adding C4 starting points must retain the older low notes and kit recipes.
        val legacy = listOf(
            stored(CircuitVoice.ROOT, "THREE BREATHS", 1f, .55f, .30f, .15f, .20f, .35f),
            stored(CircuitVoice.ROOT, "CLOSE CIRCLE", 1f, .40f, .05f, 0f, .25f, .20f),
            stored(CircuitVoice.PROCESSION, "SLOW PARADE", 1f, .45f, .45f, .20f, .25f, .45f),
            stored(CircuitVoice.PROCESSION, "MOVING ACCENTS", 1f, .60f, .65f, .80f, .55f, .50f),
            stored(CircuitVoice.ANSWER, "PAIRED WOOD", 1f, .40f, .45f, .15f, .35f, .65f),
            stored(CircuitVoice.ANSWER, "CLAY REPLY", 7 / 24f, .50f, .60f, .30f, .25f, .85f),
            stored(CircuitVoice.VOICED, "CHEST GESTURE", 5 / 24f, .85f, .35f, .25f, .45f, .40f),
            stored(CircuitVoice.VOICED, "CANYON GAP", 1f, .65f, .55f, .20f, .15f, .90f),
            stored(CircuitVoice.EXPANSE, "WIDE CIRCLE", 1f, .45f, .95f, .15f, .20f, .85f),
            stored(CircuitVoice.EXPANSE, "RETURNED DRONE", 1f, .60f, .75f, .30f, .30f, .95f, .80f),
            stored(CircuitVoice.CONFLUENCE, "FAST GATHER", 1f, .75f, .60f, .85f, .90f, .60f),
            stored(CircuitVoice.CONFLUENCE, "HELD CIRCLE", 1f, .60f, .55f, .55f, .65f, .65f, 1f),
        )
        val added = listOf(
            stored(CircuitVoice.ROOT, "FIRE CIRCLE", 1f, .50f, .25f, .10f, .20f, .50f),
            stored(CircuitVoice.ROOT, "NIGHT REPLY", 1f, .48f, .50f, .20f, .20f, .70f, .55f),
            stored(CircuitVoice.ROOT, "EMBER PULSE", 1f, .60f, .32f, .25f, .27f, .45f, 1f),
        )
        val addedNames = added.map { it.name }.toSet()
        assertEquals(legacy, presets.filterNot { it.name in addedNames }, "all twelve saved settings and their relative order survive")
        assertEquals(legacy.take(2) + added, CircuitPresets.forVoice(CircuitVoice.ROOT))
        for (patch in added) {
            assertEquals(60, Circuit.midiFor(patch.macros.getValue("TUNE")), patch.name)
            val recipe = PadRecipe(patch, FxChain(tape = mapOf("WOBBLE" to .1f)))
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()), patch.name)
            for (other in CircuitPresets.forVoice(CircuitVoice.ROOT).filterNot { it.name == patch.name }) {
                assertTrue(PresetTestSupport.rmsDistance(patch.macros, other.macros) > .05f,
                    "${patch.name} must differ from ${other.name}")
            }
        }
        val reserved = Presets.all().map { it.engine }.toSet() +
            CircuitVoice.entries.map { it.name } + FxChain.SECTION_NAMES.map { it.uppercase(Locale.ROOT) } +
            setOf("KICK", "SNARE", "CLAP", "HAT", "TOM")
        for (voice in CircuitVoice.entries) {
            val roster = CircuitPresets.forVoice(voice)
            assertEquals(if (voice == CircuitVoice.ROOT) 5 else 2, roster.size)
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
