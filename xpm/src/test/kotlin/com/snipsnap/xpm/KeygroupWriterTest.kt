package com.snipsnap.xpm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeygroupWriterTest {

    private fun program() = KeygroupProgram(
        name = "Test Keys",
        keygroups = listOf(
            Keygroup(33, 35, listOf(VelocityLayer("Keys_A1", 44_100L, 1, 127))),
            Keygroup(
                36, 38,
                listOf(
                    VelocityLayer("Keys_C2_soft", 22_050L, 1, 63),
                    VelocityLayer("Keys_C2", 44_100L, 64, 127),
                ),
            ),
        ),
    )

    @Test
    fun `writes the keygroup vocabulary on the 2_1 chassis`() {
        val xml = KeygroupWriter().write(program())
        // Our verified chassis...
        assertTrue("<File_Version>2.1</File_Version>" in xml)
        assertTrue("<Program type=\"Keygroup\">" in xml)
        assertTrue("<ProgramName>Test Keys</ProgramName>" in xml)
        // ...carrying the keygroup vocabulary from the reference exporter.
        assertTrue("<KeygroupNumKeygroups>2</KeygroupNumKeygroups>" in xml)
        assertTrue("<KeygroupPitchBendRange>12</KeygroupPitchBendRange>" in xml)
        assertTrue("<LowNote>33</LowNote>" in xml)
        assertTrue("<HighNote>38</HighNote>" in xml)
        assertTrue("<KeyTrack>True</KeyTrack>" in xml)
        assertTrue("<RootNote>0</RootNote>" in xml, "RootNote 0 = auto-detect convention")
        assertTrue("<SampleName>Keys_C2_soft</SampleName>" in xml)
        assertTrue("<VelEnd>63</VelEnd>" in xml)
    }

    @Test
    fun `empty layers carry a 0-0 window so they cannot ghost-trigger`() {
        val xml = KeygroupWriter().write(program())
        // Keygroup 1 has one real layer; layers 2-4 are inactive with 0..0.
        val firstInstrument = xml.substringAfter("<Instrument number=\"0\">").substringBefore("</Instrument>")
        assertEquals(3, Regex("<Active>False</Active>").findAll(firstInstrument).count())
        assertEquals(3, Regex("<VelStart>0</VelStart>\\s*<VelEnd>0</VelEnd>").findAll(firstInstrument).count())
    }

    @Test
    fun `sample path prefix adds pack-relative File entries`() {
        val xml = KeygroupWriter(samplePathPrefix = "Samples/Test Keys").write(program())
        assertTrue("<File>Samples/Test Keys/Keys_A1.wav</File>" in xml)
        assertTrue("<SampleFile>Keys_A1.wav</SampleFile>" in xml)
    }

    @Test
    fun `is deterministic and validates its inputs`() {
        assertEquals(KeygroupWriter().write(program()), KeygroupWriter().write(program()))
        assertFailsWith<IllegalArgumentException> { Keygroup(60, 50, listOf(VelocityLayer("x", 1L, 1, 127))) }
        assertFailsWith<IllegalArgumentException> { KeygroupProgram("Empty", emptyList()) }
    }
}
