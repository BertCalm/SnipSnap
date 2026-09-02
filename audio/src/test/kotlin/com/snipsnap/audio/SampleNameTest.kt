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
    fun `bare note letters in drum names are not keys`() {
        assertNull(SampleName.parse("Drum_Loop_A_01").key, "A is a take letter, not a key")
        assertNull(SampleName.parse("F_kick").key, "F is a kick drum, not a key")
        assertNull(SampleName.parse("C_to_G_riser").key, "C and G are the word \"to\", not a key")
        assertNull(SampleName.parse("hat_D_closed").key, "D is a hi-hat, not a key")
        assertNull(SampleName.parse("Perc_G_shaker").key, "G is a shaker, not a key")
        assertNull(SampleName.parse("riser_B_up").key, "B is a riser, not a key")
    }

    @Test
    fun `keys need a quality, an accidental, or the word key`() {
        assertEquals("E minor", SampleName.parse("BASS_Em_dark").key?.label)
        assertEquals("A# major", SampleName.parse("808_Bb_sub").key?.label, "a flat alone is corroboration")
        assertEquals("A minor", SampleName.parse("vocal_chop_Am").key?.label)
        assertEquals("C minor", SampleName.parse("loop_Cmin_92").key?.label)
        assertEquals("C major", SampleName.parse("key of C").key?.label)
        assertEquals("C major", SampleName.parse("keyC_sample").key?.label)
        assertEquals("A minor", SampleName.parse("_key_Am_riff").key?.label)
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
