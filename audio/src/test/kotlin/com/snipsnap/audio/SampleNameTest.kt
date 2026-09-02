package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SampleNameTest {

    @Test
    fun `bpm in the shapes libraries actually write`() {
        assertEquals(92f, SampleName.parse("BREAK_92bpm_dusty").bpm)
        assertEquals(140f, SampleName.parse("loop-140-BPM-drums").bpm)
        assertEquals(87f, SampleName.parse("87 bpm shuffle").bpm)
    }

    @Test
    fun `a bare number is only a bpm when it is plausibly one`() {
        assertEquals(128f, SampleName.parse("techno_128_loop").bpm)
        assertNull(SampleName.parse("kick_04").bpm, "04 is a take number, not a tempo")
        assertNull(SampleName.parse("sample_2048").bpm, "2048 is not a tempo")
    }

    @Test
    fun `keys, in the spellings that show up`() {
        assertEquals("A minor", SampleName.parse("pad_Am_warm").key?.label)
        assertEquals("C# minor", SampleName.parse("lead C#min bright").key?.label)
        assertEquals("F major", SampleName.parse("stab_Fmaj").key?.label)
    }

    @Test
    fun `a stem with neither yields neither, and never throws`() {
        val hints = SampleName.parse("just_a_name")
        assertNull(hints.bpm)
        assertNull(hints.key)
    }

    @Test
    fun `Tempo's own label round-trips back out`() {
        val written = TempoEstimate(92.4f, 0.9f).label
        assertEquals(92f, SampleName.parse("kit_$written").bpm)
    }
}
