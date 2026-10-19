package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BellowsPresetsTest {
    @Test
    fun `the library has six distinct finite model two recipes per family`() {
        val presets = BellowsPresets.all()
        assertEquals(24, presets.size)
        assertEquals(24, presets.map { it.name }.toSet().size)
        for (voice in BellowsVoice.entries) {
            val family = BellowsPresets.forVoice(voice)
            assertEquals(6, family.size, voice.name)
            assertEquals(6, family.map { it.macros to it.gesture }.toSet().size, "$voice duplicate identity")
            assertTrue(family.map { it.gesture }.toSet().size >= 3, "$voice lacks articulation choices")
            assertTrue(family.maxOf { it.macros.getValue("BODY") } >= .84f, "$voice lacks an enclosed voice")
            assertTrue(family.minOf { it.macros.getValue("BODY") } <= .22f, "$voice lacks a direct voice")
            assertTrue(family.minOf { it.macros.getValue("HOLD") } <= .15f, "$voice lacks a concise note")
            assertTrue(family.maxOf { it.macros.getValue("HOLD") } >= .76f, "$voice lacks a long phrase")
            for (patch in family) {
                assertTrue(patch.name.length <= 14, patch.name)
                assertEquals(2, patch.model, "${patch.name}: the accepted renderer is explicitly pinned")
                assertEquals(48, patch.midi)
                assertEquals(1f, patch.velocity)
                assertEquals(.5f, patch.macros.getValue("TUNE"))
                assertEquals(Bellows.macrosFor(voice).map { it.name }.toSet(), patch.macros.keys)
                assertFalse(Bellows.isLoop(patch.macros.getValue("HOLD")), patch.name)
                assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros), patch.name)
            }
        }
        val solo = presets.single { it.name == "Solo Thread" }
        assertEquals(0f, solo.macros.getValue("DRONE"))
        assertEquals(0f, solo.macros.getValue("BUZZ"))
    }

    @Test
    fun `the first eight accepted recipes keep their exact fields and stable indices`() {
        fun saved(name: String, voice: BellowsVoice, gesture: BellowsGesture, vararg macros: Pair<String, Float>) =
            BellowsPatch(name, voice, linkedMapOf("TUNE" to .5f) + macros.toMap(),
                midi = 48, velocity = 1f, model = 2, gesture = gesture)
        val accepted = listOf(
            saved("Amber Breath", BellowsVoice.CHAMBER, BellowsGesture.SWELL,
                "PRESSURE" to .54f, "BODY" to .76f, "MOTION" to .25f, "HOLD" to .56f, "REGISTER" to .24f, "BEAT" to .24f),
            saved("Silver Ranks", BellowsVoice.CHAMBER, BellowsGesture.STEADY,
                "PRESSURE" to .54f, "BODY" to .34f, "MOTION" to .18f, "HOLD" to .42f, "REGISTER" to .82f, "BEAT" to .68f),
            saved("Midnight Pull", BellowsVoice.COUNTER, BellowsGesture.SWELL,
                "PRESSURE" to .40f, "BODY" to .45f, "MOTION" to .25f, "HOLD" to .47f, "REGISTER" to .26f, "DIRECTION" to 0f),
            saved("Forward Push", BellowsVoice.COUNTER, BellowsGesture.ACCENT,
                "PRESSURE" to .68f, "BODY" to .45f, "MOTION" to .25f, "HOLD" to .28f, "REGISTER" to .73f, "DIRECTION" to 1f),
            saved("Village Stab", BellowsVoice.FOLK, BellowsGesture.ACCENT,
                "PRESSURE" to .72f, "BODY" to .56f, "MOTION" to .25f, "HOLD" to .10f, "REGISTER" to .78f, "BEAT" to .38f),
            saved("Dancing Reeds", BellowsVoice.FOLK, BellowsGesture.PULSE,
                "PRESSURE" to .63f, "BODY" to .32f, "MOTION" to .76f, "HOLD" to .53f, "REGISTER" to .66f, "BEAT" to .74f),
            saved("Quiet Wheel", BellowsVoice.WHEEL, BellowsGesture.STEADY,
                "PRESSURE" to .36f, "BODY" to .45f, "MOTION" to .16f, "HOLD" to .58f, "DRONE" to .72f, "BUZZ" to .28f),
            saved("Wrist Sparks", BellowsVoice.WHEEL, BellowsGesture.PULSE,
                "PRESSURE" to .84f, "BODY" to .45f, "MOTION" to .66f, "HOLD" to .61f, "DRONE" to .42f, "BUZZ" to .88f),
        )
        assertEquals(accepted, BellowsPresets.all().take(8))
        for (voice in BellowsVoice.entries) {
            assertEquals(accepted.filter { it.voice == voice }, BellowsPresets.forVoice(voice).take(2), voice.name)
        }
    }

    @Test
    fun `library growth keeps the starter at sixteen dry pads with its original contrasts`() {
        val pads = SynthKits.bellows().map { requireNotNull(it) }
        assertEquals(16, pads.size)
        val patches = pads.map { pad ->
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            assertNull(recipe.fx)
            assertEquals(DrumClass.TONAL, pad.drumClass)
            assertTrue(pad.oneShot)
            assertIs<BellowsPatch>(recipe.patch)
        }
        assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
        assertTrue(patches.take(8).all { it.macros.getValue("TUNE") == 0f })
        assertEquals(BellowsPresets.all().take(8), patches.drop(8), "the accepted contrast pads keep their order")
    }
}
