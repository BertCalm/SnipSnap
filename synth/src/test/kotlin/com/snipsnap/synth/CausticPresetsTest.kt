package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CausticPresetsTest {
    @Test fun `eleven complete positions retain Alloy and add only the approved directions`() {
        val all = CausticPresets.all()
        assertEquals(11, all.size)
        assertEquals(11, all.map { it.name }.toSet().size)
        assertEquals(CausticVoice.entries.toSet(), all.map { it.voice }.toSet())
        for (voice in CausticVoice.entries) {
            assertEquals(CausticPresets.forVoice(voice), Presets.forVoice("CAUSTIC", voice.name))
        }
        assertEquals(all, Presets.all().filter { it.engine == "CAUSTIC" })
        assertTrue(Presets.forVoice("CAUSTIC", "UNKNOWN").isEmpty())
        for (patch in all) {
            assertEquals(CausticInstrument.defaults(patch.voice, patch.model).keys, patch.macros.keys)
            assertEquals(0f, patch.macros.getValue("HOLD"))
            assertEquals(1, patch.model)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Presets.byName("CAUSTIC", patch.voice.name, patch.name))
            assertNull(Presets.landingFor("CAUSTIC", patch.voice.name, patch.macros))
            assertTrue(!CausticPresets.descriptionFor(patch.name).isNullOrBlank())
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
        }
        assertEquals(setOf("Fuller Body", "Preferred Alloy", "Hollow Mallet", "Rubbed Ring", "Travelling Catches"),
            all.filter { CausticPresets.isHeardAnchor(it.name) }.map { it.name }.toSet())
        assertEquals(1f, all.single { it.name == "Fuller Body" }.macros.getValue("BODY"))
        assertEquals(0f, all.single { it.name == "Preferred Alloy" }.macros.getValue("BODY"))
        assertNull(CausticPresets.descriptionFor("MY BODY"))
        assertFalse(CausticPresets.isHeardAnchor("MY BODY"))
    }

    @Test fun `the original eight Alloy recipes retain exact version one JSON`() {
        val alloy = CausticPresets.forVoice(CausticVoice.ALLOY)
        assertEquals(listOf("Fuller Body", "Preferred Alloy", "Parallel Body", "Rim Vessel",
            "Wide Shoulder", "Sparse Alloy", "Short Glint", "Low Vessel"), alloy.map { it.name })
        assertEquals(alloy, CausticPresets.all().take(8))
        for (patch in alloy) {
            val filename = patch.name.lowercase().replace(' ', '-') + ".json"
            val original = requireNotNull(javaClass.getResourceAsStream("/caustic-alloy-v1-presets/$filename"))
                .bufferedReader().use { it.readText().trimEnd() }
            assertEquals(original, patch.toJsonText(), patch.name)
            assertEquals(patch, Patches.fromJsonText(original))
        }
    }

    @Test fun `each approved direction starts at its heard prototype control position`() {
        for ((voice, body) in listOf(CausticVoice.HOLLOW_MALLET to .5f,
            CausticVoice.RUBBED_RING to .5f, CausticVoice.TRAVELLING_CATCHES to 0f)) {
            val patch = CausticPresets.forVoice(voice).single()
            assertEquals(mapOf("SOURCE" to .5f, "FOCUS" to .19f, "BODY" to body,
                "LENGTH" to 1f, "HOLD" to 0f), patch.macros)
            assertEquals(CausticInstrument.defaults(voice, 1), patch.macros)
            assertEquals(60, patch.midi)
            assertEquals(.75f, patch.velocity)
            assertEquals(1, patch.model)
            assertTrue(CausticPresets.isHeardAnchor(patch.name))
            assertTrue(CausticPresets.descriptionFor(patch.name).orEmpty().contains("native port awaits listening"))
        }
    }

    @Test fun `sixteen dry finite pads keep the Alloy walk and reach all four voices`() {
        val pads = SynthKits.caustic().map { assertNotNull(it) }
        assertEquals(16, pads.size)
        val recipes = pads.map { PadRecipe.fromJsonValue(assertNotNull(it.recipe)) }
        assertTrue(recipes.all { it.fx == null })
        val patches = recipes.map { assertIs<CausticPatch>(it.patch) }
        assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
        assertTrue(patches.take(8).all { it.voice == CausticVoice.ALLOY })
        val positions = listOf("Fuller Body", "Preferred Alloy", "Hollow Mallet", "Rubbed Ring",
            "Travelling Catches", "Sparse Alloy", "Short Glint", "Low Vessel")
        assertEquals(positions.map { name -> CausticPresets.all().single { it.name == name } }, patches.drop(8))
        assertEquals(CausticVoice.entries.toSet(), patches.map { it.voice }.toSet())
        for ((index, pad) in pads.withIndex()) {
            assertTrue(pad.oneShot, "finite pad ${index + 1} must not advertise held sustain")
            assertEquals(patches[index].name, pad.displayName)
            assertEquals(0f, patches[index].macros.getValue("HOLD"))
            assertEquals(if (index == 14) DrumClass.TONAL else DrumClass.LOOP, pad.drumClass)
            if (index == 14) assertEquals(.5f, pad.snip.durationSeconds)
            else assertTrue(pad.snip.durationSeconds > 1.5f)
            assertEquals(1, pad.snip.channels)
            assertEquals(44_100, pad.snip.sampleRate)
        }
        assertContentEquals(patches.first().render().samples, pads.first().snip.samples)
        assertContentEquals(patches[14].render().samples, pads[14].snip.samples)
    }
}
