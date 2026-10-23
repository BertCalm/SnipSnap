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
    @Test fun `eight complete positions distinguish heard timbral anchors from new variations`() {
        val all = CausticPresets.all()
        assertEquals(8, all.size)
        assertEquals(8, all.map { it.name }.toSet().size)
        assertEquals(all, CausticPresets.forVoice(CausticVoice.ALLOY))
        assertEquals(all, Presets.forVoice("CAUSTIC", "ALLOY"))
        assertEquals(all, Presets.all().filter { it.engine == "CAUSTIC" })
        assertTrue(Presets.forVoice("CAUSTIC", "UNKNOWN").isEmpty())
        for (patch in all) {
            assertEquals(CausticInstrument.defaults(patch.voice, patch.model).keys, patch.macros.keys)
            assertEquals(0f, patch.macros.getValue("HOLD"))
            assertEquals(1, patch.model)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Presets.byName("CAUSTIC", "ALLOY", patch.name))
            assertNull(Presets.landingFor("CAUSTIC", "ALLOY", patch.macros))
            assertTrue(!CausticPresets.descriptionFor(patch.name).isNullOrBlank())
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
        }
        assertEquals(setOf("Fuller Body", "Preferred Alloy"), all.filter { CausticPresets.isHeardAnchor(it.name) }.map { it.name }.toSet())
        assertEquals(1f, all.single { it.name == "Fuller Body" }.macros.getValue("BODY"))
        assertEquals(0f, all.single { it.name == "Preferred Alloy" }.macros.getValue("BODY"))
        assertNull(CausticPresets.descriptionFor("MY BODY"))
        assertFalse(CausticPresets.isHeardAnchor("MY BODY"))
    }

    @Test fun `sixteen dry finite pads store a C minor pentatonic walk then the eight positions`() {
        val pads = SynthKits.caustic().map { assertNotNull(it) }
        assertEquals(16, pads.size)
        val recipes = pads.map { PadRecipe.fromJsonValue(assertNotNull(it.recipe)) }
        assertTrue(recipes.all { it.fx == null })
        val patches = recipes.map { assertIs<CausticPatch>(it.patch) }
        assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
        assertEquals(CausticPresets.all(), patches.drop(8))
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
