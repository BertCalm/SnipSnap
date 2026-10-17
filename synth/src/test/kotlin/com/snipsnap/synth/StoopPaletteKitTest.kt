package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoopPaletteKitTest {
    @Test
    fun `the sixteen dry pads retain their roots and expose the contrasting factory roles`() {
        val pads = SynthKits.stoop().map { requireNotNull(it) }
        assertEquals(16, pads.size)
        val roots = listOf(36, 39, 41, 43, 60, 63, 65, 67, 48, 55, 48, 72, 48, 60, 48, 48)
        val factories = listOf(
            "Round Corner", "Round Corner", "Round Corner", "Round Corner",
            "Dust Tines", "Dust Tines", "Dust Tines", "Dust Tines",
            "Soft Clav", "Short Wire", "Small Pipes", "Pocket Whistle",
            "Small Cinema", "After Key", "Amber Organ", "Velvet Stack",
        )
        val voices = mutableSetOf<StoopVoice>()
        pads.forEachIndexed { index, pad ->
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = assertIs<StoopPatch>(recipe.patch)
            val factory = StoopPresets.all().single { it.name == factories[index] }
            val macros = factory.macros + ("HOLD" to if (index >= 14) 1f else 0f) +
                if (index == 13) mapOf("SHADOW" to .6f) else emptyMap()
            val expected = factory.copy(name = requireNotNull(pad.displayName), midi = roots[index], macros = macros,
                chordIntervals = if (factory.voice == StoopVoice.CHORD) listOf(0, 7, 12) else factory.chordIntervals)
            assertEquals(expected, patch, "pad ${index + 1}: save the complete selected source and answer recipe")
            assertNull(recipe.fx, "pad ${index + 1}: factory roles land dry")
            assertEquals(roots[index].toString(), pad.source["rootMidi"])
            assertEquals(Scales.nameOf(roots[index]), pad.source["rootNote"])
            assertEquals(factory.name, pad.source["factoryPreset"])
            if (patch.voice == StoopVoice.CHORD) assertEquals("0,7,12", pad.source["chordIntervals"])
            assertEquals(if (index >= 14) DrumClass.LOOP else DrumClass.TONAL, pad.drumClass)
            assertTrue(pad.snip.frameCount > 0 && pad.snip.samples.all { it.isFinite() }, "pad ${index + 1}: audible bank audio")
            assertTrue(pad.snip.peak() > .01f, "pad ${index + 1}: the selected role must produce audible audio")
            voices += patch.voice
        }
        assertEquals(StoopVoice.entries.toSet(), voices)
    }
}
