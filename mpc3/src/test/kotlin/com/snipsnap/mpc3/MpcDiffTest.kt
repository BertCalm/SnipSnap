package com.snipsnap.mpc3

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.DrumProgram
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpcDiffTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mpcdiff").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(mapOf(*pairs))
    private fun num(d: Double) = JsonValue.Num(d)
    private fun str(s: String) = JsonValue.Str(s)

    @Test
    fun `identical trees diff to nothing`() {
        val writer = Mpc3TrackWriter()
        val tree = Json.parse(writer.payloadText(DrumProgram("Same Kit", listOf(null))))
        val result = MpcDiff.diff(tree, tree)
        assertTrue(result.identical, "$result")
    }

    @Test
    fun `structural differences land in the only-in sets, normalised`() {
        val a = obj("shared" to num(1.0), "nest" to obj("mine" to num(2.0)))
        val b = obj("shared" to num(1.0), "nest" to obj("yours" to num(3.0)))
        val result = MpcDiff.diff(a, b)
        assertEquals(setOf(".nest.mine"), result.onlyInA)
        assertEquals(setOf(".nest.yours"), result.onlyInB)

        // valueN keys collapse: 128 pads vs 16 pads is zero structural noise.
        val pads = { n: Int ->
            obj("pads" to JsonValue.Obj((0 until n).associate { "value$it" to num(0.0) }))
        }
        assertTrue(MpcDiff.diff(pads(128), pads(16)).onlyInA.isEmpty())
    }

    @Test
    fun `value differences carry concrete paths and both renderings`() {
        val writer = Mpc3TrackWriter()
        val a = Json.parse(writer.payloadText(DrumProgram("Kit A", listOf(null))))
        val b = Json.parse(writer.payloadText(DrumProgram("Kit B", listOf(null))))
        val result = MpcDiff.diff(a, b)
        assertTrue(result.onlyInA.isEmpty() && result.onlyInB.isEmpty(), "same writer, same shape")
        assertTrue(result.valueDiffs.isNotEmpty())
        val named = result.valueDiffs.filter { it.a == "\"Kit A\"" && it.b == "\"Kit B\"" }
        assertTrue(named.isNotEmpty(), "the program name delta is visible: ${result.valueDiffs}")
    }

    @Test
    fun `sentinels and float dust are not deltas`() {
        val a = obj(
            "length" to num(9.223372036854776E18),
            "level" to num(0.7079460000001),
            "moved" to num(0.5),
        )
        val b = obj(
            "length" to num(9.223372036854774E18),
            "level" to num(0.707946),
            "moved" to num(0.75),
        )
        val result = MpcDiff.diff(a, b)
        assertEquals(listOf(".moved"), result.valueDiffs.map { it.path }, "$result")
    }

    @Test
    fun `array length mismatch is one line, common prefix still compares`() {
        val a = obj("samples" to JsonValue.Arr(listOf(str("kick"), str("snare"))))
        val b = obj("samples" to JsonValue.Arr(listOf(str("kick"), str("clap"), str("hat"))))
        val result = MpcDiff.diff(a, b)
        assertTrue(result.valueDiffs.any { it.path == ".samples[]" && it.a == "2 items" })
        assertTrue(result.valueDiffs.any { it.path == ".samples[1]" && it.b == "\"clap\"" })
    }

    /**
     * The corpus guard, reproduced through the bench tool: every key path
     * our writer emits exists in at least one real commercial drum track —
     * so intersecting `onlyInA` across the corpus leaves nothing — while
     * every real kit carries paths we never write (QLinks, insert effects),
     * so `onlyInB` is never empty. These are the documented deltas.
     */
    @Test
    fun `our export vs the golden corpus reproduces the known deltas`() {
        val corpus = File("../reference/golden/mpc3-track")
            .listFiles { f -> f.extension == "xtd" }
        assertTrue(!corpus.isNullOrEmpty(), "reference corpus missing")

        val writer = Mpc3TrackWriter()
        val ours = Json.parse(writer.payloadText(DrumProgram("Bench Kit", listOf(null)), clip = clip()))

        var unexplained: Set<String>? = null
        for (golden in corpus!!) {
            val result = MpcDiff.diff(ours, MpcDiff.load(golden))
            assertTrue(result.onlyInB.isNotEmpty(), "${golden.name}: a real kit always carries more")
            unexplained = unexplained?.intersect(result.onlyInA) ?: result.onlyInA
        }
        assertEquals(emptySet(), unexplained, "key paths that appear in no real drum track")
    }

    @Test
    fun `mpc 2 xml files diff like anything else`() {
        fun xpm(name: String, extra: String = "") = File(temp, "$name.xpm").apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
                |<MPCVObject>
                |  <Version><File_Version>2.1</File_Version></Version>
                |  <Program type="drumProgram">
                |    <ProgramName>$name</ProgramName>
                |    <Instruments>
                |      <Instrument number="1"><Volume>0.70</Volume></Instrument>
                |      <Instrument number="2"><Volume>0.70</Volume></Instrument>
                |    </Instruments>
                |    $extra
                |  </Program>
                |</MPCVObject>
                """.trimMargin(),
            )
        }
        val a = xpm("KitA")
        assertTrue(MpcDiff.diff(a, a).identical)

        val b = xpm("KitB", extra = "<Slider1><Parameter>TUNE</Parameter></Slider1>")
        val result = MpcDiff.diff(a, b)
        assertEquals(setOf(".MPCVObject.Program.Slider1", ".MPCVObject.Program.Slider1.Parameter"), result.onlyInB)
        assertTrue(
            result.valueDiffs.any {
                it.path == ".MPCVObject.Program.ProgramName" && it.a == "\"KitA\"" && it.b == "\"KitB\""
            },
            "$result",
        )
        assertEquals("MPC 2 (XML)", MpcDiff.formatLabel(a))
    }

    @Test
    fun `load refuses what it cannot read and reads bare json`() {
        val json = File(temp, "kit.json").apply { writeText("""{"name": "Kit"}""") }
        assertEquals("Kit", (MpcDiff.load(json) as JsonValue.Obj).entries.getValue("name").str())
        assertEquals("JSON", MpcDiff.formatLabel(json))

        val junk = File(temp, "junk.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { MpcDiff.load(junk) }
    }

    private fun clip() = Mpc3Clip(
        "Bench Groove", 1,
        listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, 960, 0.8f)),
    )
}
