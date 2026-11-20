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
        assertEquals(10, bank.size)
        assertEquals(AccordVoice.BRUSH, AccordPresets.default().voice)
        // The six accord-native-1 anchors keep their places; one preset per later voice follows, in voice order.
        assertEquals(listOf("BRUSH", "WOOD", "REFERENCE", "SOFT ATTACK", "BRIGHT EDGE", "LONG RING",
            "REED", "COURSE", "ESCAPEMENT", "BELL"), bank.map { it.name })
        for (voice in AccordKits.NEW_VOICES) {
            // The recipe kept in accord-voices-1: default controls, reference seed and initializer, C3, normal touch.
            assertEquals(AccordPatch(voice.name, voice, Accord.defaults(voice)), AccordPresets.forVoice(voice).single())
            assertEquals(AccordAcousticProfile.BASE, AccordPresets.forVoice(voice).single().acousticProfile)
            assertEquals(Accord.DEFAULT_SEED, AccordPresets.forVoice(voice).single().seed)
        }
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

    @Test
    fun `ACCORD VOICES plays each new voice's preset at C2 to C5, normal touch, built like the ACCORD kit`() {
        val patches = AccordKits.voicesPatches()
        assertEquals(16, patches.size)
        assertEquals(AccordKits.NEW_VOICES.flatMap { v -> List(4) { v } }, patches.map { it.voice })
        assertEquals(List(4) { listOf(36, 48, 60, 72) }.flatten(), patches.map { it.midi })
        assertEquals(listOf("REED C2", "REED C3", "REED C4", "REED C5", "COURSE C2", "COURSE C3", "COURSE C4", "COURSE C5",
            "ESCAPEMENT C2", "ESCAPEMENT C3", "ESCAPEMENT C4", "ESCAPEMENT C5", "BELL C2", "BELL C3", "BELL C4", "BELL C5"),
            patches.map { it.name })
        for (patch in patches) {
            assertTrue(patch.name.length <= 14, patch.name)
            // Only the name and the note differ from the preset: the heard default recipe at another root.
            assertEquals(AccordPresets.forVoice(patch.voice).single().copy(name = patch.name, midi = patch.midi), patch)
            assertEquals(.75f, patch.velocity)
        }
        val pads = AccordKits.voicesArranged { com.snipsnap.audio.Snip(FloatArray(441), channels = 1, sampleRate = 44_100) }
        assertEquals(patches, pads.map { PadRecipe.fromJsonValue(requireNotNull(it.recipe)).patch })
        assertEquals(patches.map { it.name }, pads.map { it.displayName })
        assertTrue(pads.all { it.oneShot && it.takes.isEmpty() && it.softVariants.isEmpty() })
        assertTrue(pads.all { PadRecipe.fromJsonValue(requireNotNull(it.recipe)).fx == null })
        assertTrue(pads.all { it.source["playback"] == "FINITE_ONE_SHOT" && it.source["engine"] == AccordPatch.ENGINE })
        assertEquals(4, pads.map { it.colorHex }.toSet().size, "one colour per voice")
        // The ACCORD starter is unchanged by the new kit.
        assertTrue(AccordKits.patches().none { it.voice in AccordKits.NEW_VOICES })
    }
}
