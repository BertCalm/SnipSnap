package com.snipsnap.xpm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XpmWriterTest {

    private val writer = XpmWriter()

    private fun kit(name: String = "SnipSnap Kit 01", pads: List<Pad?>) =
        DrumProgram(name = name, pads = pads)

    private fun pad(n: Int) = Pad(sampleName = "SS_Pad_%02d".format(n), frameCount = 1000L + n)

    private fun fullBank() = kit(pads = (1..16).map { pad(it) })

    @Test
    fun `emits one instrument per pad`() {
        val xml = writer.write(fullBank())
        assertEquals(16, Regex("<Instrument number=").findAll(xml).count())
    }

    @Test
    fun `instrument numbering is zero based by default`() {
        val xml = writer.write(fullBank())
        assertContains(xml, "<Instrument number=\"0\">")
        assertContains(xml, "<Instrument number=\"15\">")
        assertTrue("<Instrument number=\"16\">" !in xml)
    }

    @Test
    fun `instrument base index is configurable`() {
        val xml = XpmWriter(instrumentBaseIndex = 1).write(fullBank())
        assertContains(xml, "<Instrument number=\"1\">")
        assertContains(xml, "<Instrument number=\"16\">")
        assertTrue("<Instrument number=\"0\">" !in xml)
    }

    @Test
    fun `gaps keep later pads on their own instrument slot`() {
        // Pads 1 and 5 only. Pad 5's sample must land on instrument 4, not 1 —
        // collapsing the gap would silently shift the whole kit.
        val pads = listOf<Pad?>(pad(1), null, null, null, pad(5))
        val xml = writer.write(kit(pads = pads))

        assertEquals(5, Regex("<Instrument number=").findAll(xml).count())

        val instrument4 = xml.substringAfter("<Instrument number=\"4\">")
        assertContains(instrument4.substringBefore("</Instrument>"), "<SampleName>SS_Pad_05</SampleName>")

        val instrument1 = xml.substringAfter("<Instrument number=\"1\">").substringBefore("</Instrument>")
        assertContains(instrument1, "<SampleName></SampleName>")
    }

    @Test
    fun `trailing empty pads are not emitted`() {
        val pads = listOf<Pad?>(pad(1), pad(2)) + List(14) { null }
        val xml = writer.write(kit(pads = pads))
        assertEquals(2, Regex("<Instrument number=").findAll(xml).count())
    }

    @Test
    fun `sample length becomes slice end`() {
        val xml = writer.write(kit(pads = listOf(Pad("Kick", frameCount = 22_050L))))
        assertContains(xml, "<SliceEnd>22050</SliceEnd>")
    }

    @Test
    fun `only layer one carries the sample`() {
        val xml = writer.write(kit(pads = listOf(Pad("Kick", frameCount = 100L))))
        val instrument = xml.substringAfter("<Instrument number=\"0\">").substringBefore("</Instrument>")

        assertEquals(4, Regex("<Layer number=").findAll(instrument).count())
        assertEquals(1, Regex("<SampleName>Kick</SampleName>").findAll(instrument).count())
        assertEquals(3, Regex("<SampleName></SampleName>").findAll(instrument).count())
    }

    @Test
    fun `pad note map is complete and starts with the classic MPC layout`() {
        val xml = writer.write(fullBank())
        assertEquals(128, Regex("<PadNote number=").findAll(xml).count())
        assertContains(xml, "<PadNote number=\"1\">\n        <Note>37</Note>")
        assertContains(xml, "<PadNote number=\"2\">\n        <Note>36</Note>")
        assertContains(xml, "<PadNote number=\"128\">\n        <Note>18</Note>")
    }

    @Test
    fun `program pads blob is escaped json with all 128 entries`() {
        val xml = writer.write(fullBank())
        val blob = xml.substringAfter("<ProgramPads>").substringBefore("</ProgramPads>")

        assertContains(blob, "&quot;universalPad&quot;: 32512")
        assertContains(blob, "&quot;value0&quot;: 0")
        assertContains(blob, "&quot;value127&quot;: 0")
        assertTrue("\"" !in blob, "raw quotes must be escaped inside element text")
        // 128 pad entries plus Universal/Type/UnusedPads.
        assertEquals(131, Regex("&quot;value\\d+&quot;").findAll(blob).count())
    }

    @Test
    fun `names are xml escaped`() {
        val xml = writer.write(
            kit(name = "Jay & Co <demo>", pads = listOf(Pad("Snare \"hard\"", 10L))),
        )
        assertContains(xml, "<ProgramName>Jay &amp; Co &lt;demo&gt;</ProgramName>")
        assertContains(xml, "<SampleName>Snare &quot;hard&quot;</SampleName>")
    }

    @Test
    fun `floats use a dot regardless of locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val xml = writer.write(fullBank())
            assertContains(xml, "<Volume>0.710000</Volume>")
            assertTrue("0,710000" !in xml)
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    @Test
    fun `output is deterministic`() {
        assertEquals(writer.write(fullBank()), writer.write(fullBank()))
    }

    @Test
    fun `writes a file named after the program`() {
        val dir = createTempDir()
        try {
            val file = writer.writeTo(dir, fullBank())
            assertEquals("SnipSnap Kit 01.xpm", file.name)
            assertContains(file.readText(), "<ProgramName>SnipSnap Kit 01</ProgramName>")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `matches the golden file`() {
        val golden = File("src/test/resources/golden/SnipSnapRef16.xpm")
        assertTrue(golden.exists(), "golden file missing: ${golden.absolutePath}")

        val expected = golden.readText()
        val actual = writer.write(goldenProgram())

        if (expected != actual) {
            val at = expected.zip(actual).indexOfFirst { (a, b) -> a != b }
            fail(expected, actual, at)
        }
        assertEquals(expected, actual)
    }

    private fun fail(expected: String, actual: String, at: Int) {
        val from = maxOf(0, at - 120)
        throw AssertionError(
            buildString {
                appendLine("Golden file mismatch at offset $at.")
                appendLine("--- expected ---")
                appendLine(expected.substring(from, minOf(expected.length, at + 120)))
                appendLine("--- actual ---")
                appendLine(actual.substring(from, minOf(actual.length, at + 120)))
                appendLine(
                    "If this change is intentional, regenerate with " +
                        "`gradle :xpm:regenerateGolden` and re-verify on hardware.",
                )
            },
        )
    }

    @Test
    fun `rejects impossible programs`() {
        assertFailsWith<IllegalArgumentException> { DrumProgram("", listOf(pad(1))) }
        assertFailsWith<IllegalArgumentException> { DrumProgram("x", List(129) { pad(1) }) }
        assertFailsWith<IllegalArgumentException> { Pad("", 1L) }
        assertFailsWith<IllegalArgumentException> { Pad("x", -1L) }
        assertFailsWith<IllegalArgumentException> { Pad("x", 1L, tuneCoarse = 99) }
        assertFailsWith<IllegalArgumentException> { Pad("x", 1L, pan = 2f) }
    }

    companion object {
        /**
         * The fixed input behind the golden file. Deliberately exercises a bit of
         * everything: a mute group, a tuned pad, a panned pad, a gap, and a
         * non-default level.
         */
        fun goldenProgram(): DrumProgram = DrumProgram(
            name = "SnipSnapRef16",
            pads = listOf(
                Pad("SS_Kick_01", 18_522L),
                Pad("SS_Snare_01", 24_110L),
                Pad("SS_HatClosed_01", 6_301L, muteGroup = 1),
                Pad("SS_HatOpen_01", 31_884L, muteGroup = 1),
                Pad("SS_Clap_01", 15_004L, pan = 0.35f),
                Pad("SS_Rim_01", 5_120L, tuneCoarse = -2),
                Pad("SS_Perc_01", 9_876L, tuneFine = 25),
                Pad("SS_Perc_02", 12_345L, level = 0.5f),
                null,
                Pad("SS_Vox_01", 44_100L, oneShot = false),
                Pad("SS_Vox_02", 22_050L),
                Pad("SS_Fx_01", 88_200L),
                Pad("SS_Fx_02", 7_777L),
                Pad("SS_Tom_01", 19_200L),
                Pad("SS_Tom_02", 20_480L),
                Pad("SS_Crash_01", 132_300L),
            ),
        )
    }
}

private fun createTempDir(): File =
    java.nio.file.Files.createTempDirectory("xpmtest").toFile()
