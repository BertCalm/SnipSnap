package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BellowsPlaybackAuditionTest {
    @Test
    fun `event boundaries preserve exact note timing velocity windows and note-off through the real player`() {
        val layers = listOf(
            InstrumentStore.Layer("soft.wav", 16, 1, 1, 42),
            InstrumentStore.Layer("middle.wav", 16, 1, 43, 84),
            InstrumentStore.Layer("hard.wav", 16, 1, 85, 127),
        )
        val instrument = InstrumentStore.Instrument("TIMING", 2f / BellowsPlaybackAuditionGenerator.RATE,
            listOf(InstrumentStore.Zone(60, 60, 60, "hard.wav", 16, 1, layers)))
        val loaded = InstrumentEngine.Loaded(instrument, BellowsPlaybackAuditionGenerator.RATE,
            mapOf("soft.wav" to FloatArray(16) { .2f }, "middle.wav" to FloatArray(16) { .8f },
                "hard.wav" to FloatArray(16) { 1f }))
        // These starts and note-offs are deliberately not 512-frame block boundaries.
        val events = listOf(
            BellowsPlaybackAuditionGenerator.Event(60, 3, 8, 42 / 127f),
            BellowsPlaybackAuditionGenerator.Event(60, 80, 85, 43 / 127f),
        )
        val actual = BellowsPlaybackAuditionGenerator.renderProgram(loaded, events, 100).samples
        val soft = .2f * (42 / 127f) * InstrumentEngine.VOICE_LEVEL
        val middle = .8f * (43 / 127f) * InstrumentEngine.VOICE_LEVEL
        assertTrue(actual.take(3).all { it == 0f })
        assertEquals(soft, actual[3], 1e-7f)
        assertEquals(soft, actual[7], 1e-7f)
        assertEquals(soft * .5f, actual[8], 1e-7f)
        assertTrue(actual.slice(9 until 80).all { it == 0f })
        assertEquals(middle, actual[80], 1e-7f, "MIDI43 must select the middle timbre")
        assertEquals(middle, actual[84], 1e-7f)
        assertEquals(middle * .5f, actual[85], 1e-7f)
        assertTrue(actual.drop(86).all { it == 0f })
    }
}
