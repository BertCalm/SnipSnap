package com.snipsnap.synth

import kotlin.test.*

class DrumlinePresetsTest {
    @Test fun `audition references cover every voice with complete replayable recipes outside the factory bank`() {
        val candidates = DrumlinePresets.all()
        assertEquals(32, candidates.size)
        assertEquals(candidates.size, candidates.map { it.name }.toSet().size)
        assertEquals(DrumlineVoice.entries.toSet(), candidates.map { it.voice }.toSet())
        for (voice in DrumlineVoice.entries) {
            assertEquals(candidates.filter { it.voice == voice }, DrumlinePresets.forVoice(voice))
            assertTrue(Presets.forVoice(DrumlinePatch.ENGINE, voice.name).isEmpty())
        }
        for (patch in candidates) {
            assertEquals(Drumline.defaults(patch.voice).keys, patch.macros.keys)
            assertEquals(Drumline.MODEL_VERSION, patch.model)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
    }
}
