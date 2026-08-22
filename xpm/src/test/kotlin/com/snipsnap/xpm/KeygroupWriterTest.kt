package com.snipsnap.xpm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeygroupWriterTest {

    private fun program() = KeygroupProgram(
        name = "Test Keys",
        keygroups = listOf(
            Keygroup(33, 35, rootNote = 35, layers = listOf(VelocityLayer("Keys_A1", 44_100L, 1, 127))),
            Keygroup(
                36, 38, rootNote = 38,
                layers = listOf(
                    VelocityLayer("Keys_C2_soft", 22_050L, 1, 63),
                    VelocityLayer("Keys_C2", 44_100L, 64, 127),
                ),
            ),
        ),
    )

    @Test
    fun `writes the corpus-verified keygroup shape on the 2_1 chassis`() {
        val xml = KeygroupWriter().write(program())
        // Our verified chassis...
        assertTrue("<File_Version>2.1</File_Version>" in xml)
        assertTrue("<Program type=\"Keygroup\">" in xml)
        assertTrue("<ProgramName>Test Keys</ProgramName>" in xml)
        // ...carrying the vocabulary as real programs write it.
        assertTrue("<KeygroupNumKeygroups>2</KeygroupNumKeygroups>" in xml)
        assertTrue("<KeygroupPitchBendRange>0.000000</KeygroupPitchBendRange>" in xml)
        assertTrue("<LowNote>33</LowNote>" in xml)
        assertTrue("<HighNote>38</HighNote>" in xml)
        assertTrue("<SampleName>Keys_C2_soft</SampleName>" in xml)
        assertTrue("<VelEnd>63</VelEnd>" in xml)
    }

    @Test
    fun `filled layers carry the real root note and empty layers write 0`() {
        val xml = KeygroupWriter().write(program())
        assertTrue("<RootNote>35</RootNote>" in xml)
        assertTrue("<RootNote>38</RootNote>" in xml)
        // Instrument 0 has one filled layer and seven empty ones.
        val first = xml.substringAfter("<Instrument number=\"0\">").substringBefore("</Instrument>")
        assertEquals(1, Regex("<RootNote>35</RootNote>").findAll(first).count())
        assertEquals(7, Regex("<RootNote>0</RootNote>").findAll(first).count())
    }

    @Test
    fun `KeyTrack is False everywhere - 13083 of 13083 real layers say so`() {
        val xml = KeygroupWriter().write(program())
        assertFalse("<KeyTrack>True</KeyTrack>" in xml)
        assertTrue("<KeyTrack>False</KeyTrack>" in xml)
    }

    @Test
    fun `instrument level carries no Active Tune Transpose RootNote KeyTrack or OneShot`() {
        val xml = KeygroupWriter().write(program())
        val preLayers = xml.substringAfter("<Instrument number=\"0\">").substringBefore("<Layers>")
        for (banned in listOf("<Active>", "<Tune>", "<Transpose>", "<RootNote>", "<KeyTrack>", "<OneShot>")) {
            assertFalse(banned in preLayers, "$banned must not appear at instrument level")
        }
        // TuneCoarse/TuneFine replace Tune/Transpose there.
        assertTrue("<TuneCoarse>0</TuneCoarse>" in preLayers)
        assertTrue("<TuneFine>0</TuneFine>" in preLayers)
    }

    @Test
    fun `keygroup instruments carry 8 layer slots and empty ones cannot ghost-trigger`() {
        val xml = KeygroupWriter().write(program())
        val first = xml.substringAfter("<Instrument number=\"0\">").substringBefore("</Instrument>")
        assertTrue("<Layer number=\"8\">" in first)
        // Keygroup 1 has one real layer; layers 2-8 are inactive with 0..0.
        assertEquals(7, Regex("<Active>False</Active>").findAll(first).count())
        assertEquals(7, Regex("<VelStart>0</VelStart>\\s*<VelEnd>0</VelEnd>").findAll(first).count())
    }

    @Test
    fun `Keygroup block sits after the maps and the note map is chromatic identity`() {
        val xml = KeygroupWriter().write(program())
        val order = listOf("</Instruments>", "</PadNoteMap>", "</PadGroupMap>", "<KeygroupNumKeygroups>")
            .map { xml.indexOf(it).also { i -> assertTrue(i >= 0, "missing $it") } }
        assertEquals(order, order.sorted(), "program tail must be Instruments, PadNoteMap, PadGroupMap, Keygroup*")
        // Pad N plays note N-1 — keygroups are chromatic on pads.
        assertTrue("<PadNote number=\"1\">\n        <Note>0</Note>" in xml)
        assertTrue("<PadNote number=\"128\">\n        <Note>127</Note>" in xml)
    }

    @Test
    fun `no layer-level Loop or Mute - looping is SliceLoop`() {
        val xml = KeygroupWriter().write(program())
        assertFalse("<Loop>" in xml)
        assertFalse("<Mute>" in xml)
        assertTrue("<SliceLoop>0</SliceLoop>" in xml)
    }

    /**
     * The generalisation of every assertion above: each element we emit must
     * appear, at the same nesting level, in a real commercial program.
     *
     * The individual tests pin the eight defects we know about. This one
     * catches the ninth — because every one of those defects was the same
     * mistake, an element invented rather than observed, and the MPC's answer
     * to an element it has never seen is silence rather than an error.
     *
     * A subset is fine and expected: real programs carry ~158 instrument-level
     * elements and we write 15. The corpus also shows the parser tolerates
     * surprises (one vendor ships a duplicate `<Resonance2>`), so this guards
     * invention, not omission.
     */
    @Test
    fun `emits no element absent from real programs`() {
        val dir = File("../reference/golden/keygroup")
        val corpus = dir.listFiles { f -> f.extension == "xpm" }
        assertTrue(corpus != null && corpus.isNotEmpty(), "reference corpus missing: ${dir.absolutePath}")

        fun tags(fragment: String) =
            Regex("""<([A-Za-z_][A-Za-z0-9_.]*)[ >]""").findAll(fragment).map { it.groupValues[1] }

        fun atInstrumentLevel(xml: String) =
            Regex("""<Instrument number="\d+">(.*?)<Layers>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).flatMap { tags(it.groupValues[1]) }.toSet()

        fun atLayerLevel(xml: String) =
            Regex("""<Layer number="\d+">(.*?)</Layer>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).flatMap { tags(it.groupValues[1]) }.toSet()

        val realInstrument = corpus!!.flatMap { atInstrumentLevel(it.readText()) }.toSet()
        val realLayer = corpus.flatMap { atLayerLevel(it.readText()) }.toSet()

        val ours = KeygroupWriter().write(program())
        assertEquals(
            emptySet(), atInstrumentLevel(ours) - realInstrument,
            "instrument-level elements that appear in no real program",
        )
        assertEquals(
            emptySet(), atLayerLevel(ours) - realLayer,
            "layer-level elements that appear in no real program",
        )
    }

    @Test
    fun `is deterministic and validates its inputs`() {
        assertEquals(KeygroupWriter().write(program()), KeygroupWriter().write(program()))
        assertFailsWith<IllegalArgumentException> {
            Keygroup(60, 50, rootNote = 55, layers = listOf(VelocityLayer("x", 1L, 1, 127)))
        }
        assertFailsWith<IllegalArgumentException> { KeygroupProgram("Empty", emptyList()) }
    }
}
