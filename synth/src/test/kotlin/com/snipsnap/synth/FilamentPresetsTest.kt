package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The factory roster is the twelve approved model-three listening recipes. */
class FilamentPresetsTest {
    private val macroNames = listOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT", "HOLD")

    @Test
    fun `factory promotion retains every approved control note and geometry setting`() {
        val expected = """
            THREAD|Glass Thread|0.66,0.3,0.38,0.38,0.36,0.0|0|VOICE
            THREAD|Narrow Arrival|0.28,0.36,0.52,0.46,0.4,0.0|11|EARLY
            THREAD|Four Routes|0.58,0.48,0.72,0.78,0.5,0.0|17|COIL
            THREAD|Held Thread|0.48,0.4,0.56,0.5,0.42,1.0|0|VOICE
            PULSE|Pulse Passage|0.72,0.4,0.64,0.68,0.52,0.0|0|VOICE
            PULSE|Long Passage|0.78,0.58,0.9,0.88,0.62,0.0|23|LATE
            PULSE|Early Glints|0.5,0.32,0.48,0.46,0.42,0.0|31|EARLY
            PULSE|Held Procession|0.68,0.54,0.76,0.76,0.56,1.0|41|COIL
            CURL|Loose Curl|0.38,0.5,0.68,0.55,0.58,0.0|47|VOICE
            CURL|Bend Glow|0.56,0.66,0.92,0.86,0.68,0.0|53|LATE
            CURL|Spilled Silver|0.82,0.6,0.68,0.72,0.64,0.0|29|COIL
            CURL|Held Curl|0.48,0.68,0.86,0.82,0.7,1.0|61|LATE
        """.trimIndent().lines()
        val presets = FilamentPresets.all()
        assertEquals(expected, presets.map { patch ->
            listOf(patch.voice, patch.name, macroNames.joinToString(",") { patch.macros.getValue(it).toString() },
                patch.config.seed, patch.config.profile).joinToString("|")
        })
        for (patch in presets) {
            assertEquals(3, patch.model, patch.name)
            assertEquals(48, patch.midi, patch.name)
            assertEquals(1f, patch.velocity, patch.name)
            assertFalse(patch.config.perfectRetention, patch.name)
            assertEquals(macroNames.toSet(), patch.macros.keys, patch.name)
        }
    }

    @Test
    fun `factory roster contains distinct named finite and held recipes for all three voices`() {
        val presets = FilamentPresets.all()
        assertEquals(12, presets.size)
        assertEquals(listOf(FilamentVoice.THREAD, FilamentVoice.PULSE, FilamentVoice.CURL), FilamentVoice.entries)
        assertEquals(presets.size, presets.map { it.name }.distinct().size)
        assertEquals(presets.size, presets.map { listOf(it.voice, it.macros, it.config) }.distinct().size)
        for (voice in FilamentVoice.entries) {
            val group = FilamentPresets.forVoice(voice)
            assertEquals(4, group.size)
            assertEquals(presets.filter { it.voice == voice }, group)
            assertEquals(3, group.count { Filament.drumClassFor(it.voice, it.macros) == DrumClass.TONAL })
            assertEquals(1, group.count { Filament.drumClassFor(it.voice, it.macros) == DrumClass.LOOP })
        }
        for (patch in presets) {
            assertTrue(patch.macros != Filament.defaults(patch.voice), "${patch.name} duplicates the voice defaults")
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
        }
    }

    @Test
    fun `shared factory lookup and recipe JSON retain the approved dry patches`() {
        val presets = FilamentPresets.all()
        assertEquals(presets, Presets.all().filter { it.engine == FilamentPatch.ENGINE })
        for (voice in FilamentVoice.entries) {
            assertEquals(FilamentPresets.forVoice(voice), Presets.forVoice(FilamentPatch.ENGINE, voice.name))
        }
        for (patch in presets) {
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
        }
        assertTrue(Presets.forVoice(FilamentPatch.ENGINE, "UNKNOWN").isEmpty())
        assertNull(Presets.byName(FilamentPatch.ENGINE, FilamentVoice.THREAD.name, "UNKNOWN"))
    }
}
