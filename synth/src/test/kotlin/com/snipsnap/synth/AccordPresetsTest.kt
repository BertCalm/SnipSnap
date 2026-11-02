package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccordPresetsTest {
    @Test
    fun `factory roster preserves the accepted anchors and every TINE option`() {
        val bank = AccordPresets.all()
        assertEquals(6, bank.size)
        assertEquals(AccordVoice.BRUSH, AccordPresets.default().voice)
        assertEquals(setOf(AccordAcousticProfile.REFERENCE, AccordAcousticProfile.SOFT_ATTACK,
            AccordAcousticProfile.BRIGHT_EDGE, AccordAcousticProfile.LONG_RING),
            AccordPresets.forVoice(AccordVoice.TINE).map { it.acousticProfile }.toSet())
        for (voice in AccordVoice.entries) {
            assertEquals(AccordPresets.forVoice(voice), Presets.forVoice("ACCORD", voice.name))
        }
        assertTrue(Presets.all().containsAll(bank))
        assertTrue(Presets.forVoice("ACCORD", "UNKNOWN").isEmpty())
        for (patch in bank) {
            assertEquals(Accord.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys)
            assertEquals(0f, patch.macros.getValue("HOLD"))
            assertEquals(48, patch.midi)
            assertEquals(.75f, patch.velocity)
            assertEquals(8.0, patch.sourceStartCycles)
            assertEquals(8.0, patch.sourceSeconds)
            assertEquals(AccordInitializer.REFERENCE_SINE_20261022, patch.initializer)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
            assertTrue(patch.name.length <= 14)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
    }

    @Test
    fun `native factory renders replay and file as finite duration-class loops`() {
        for (patch in AccordPresets.all()) {
            val original = patch.render()
            val saved = PadRecipe.fromJsonText(PadRecipe(patch).toJsonText())
            assertContentEquals(original.samples, saved.render().samples, patch.name)
            assertTrue(original.samples.all { it.isFinite() })
            assertTrue(original.peak() <= 1f)
            assertEquals(DrumClass.LOOP, Classifier.classify(original).drumClass)
            assertEquals(DrumClass.LOOP, Accord.drumClassFor(patch))
        }
    }

    @Test
    fun `starter kit has eight BRUSH scale notes and all accepted dry profiles`() {
        val patches = AccordKits.patches()
        assertEquals(16, patches.size)
        assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
        assertTrue(patches.take(8).all { it.voice == AccordVoice.BRUSH })
        assertEquals(AccordAcousticProfile.entries.filter { it != AccordAcousticProfile.BASE }.toSet(),
            patches.filter { it.voice == AccordVoice.TINE }.map { it.acousticProfile }.toSet())
        // Inspect complete saved kit recipes without rendering sixteen eight-second notes.
        val pads = AccordKits.arranged { patch ->
            com.snipsnap.audio.Snip(FloatArray(441), channels = 1, sampleRate = 44_100)
        }
        assertEquals(patches, pads.map { PadRecipe.fromJsonValue(requireNotNull(it.recipe)).patch })
        assertTrue(pads.all { it.oneShot && it.takes.isEmpty() && it.softVariants.isEmpty() })
        assertTrue(pads.all { PadRecipe.fromJsonValue(requireNotNull(it.recipe)).fx == null })
        assertTrue(pads.all { it.drumClass == DrumClass.LOOP })
    }
}
