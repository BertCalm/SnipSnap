package com.snipsnap.mpc3

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Mpc3TrackWriterTest {

    private val writer = Mpc3TrackWriter()

    private fun program() = DrumProgram(
        name = "SnipSnap MPC3 Kit",
        pads = listOf(
            Pad("SS_Kick_01", 22_050L, muteGroup = 0, color = 0xE8542E),
            Pad("SS_Snare_01", 18_000L, tuneCoarse = -2, tuneFine = 10),
            Pad("SS_Hat_Cl_01", 6_000L, muteGroup = 1),
            Pad("SS_Hat_Op_01", 30_000L, muteGroup = 1, oneShot = false),
            null,
            Pad(
                "SS_Snare_01", 18_000L,
                velocityLayers = listOf(
                    VelocityLayer("SS_Snare_soft", 15_000L, 0, 63),
                    VelocityLayer("SS_Snare_01", 18_000L, 64, 127),
                ),
            ),
        ),
    )

    private fun payload(): Map<String, JsonValue> =
        (Json.parse(writer.payloadText(program())) as JsonValue.Obj).entries

    private fun data(): Map<String, JsonValue> = (payload()["data"] as JsonValue.Obj).entries

    private fun instruments(): List<Map<String, JsonValue>> {
        val prog = (data()["program"] as JsonValue.Obj).entries
        val drum = (prog["drum"] as JsonValue.Obj).entries
        return (drum["instruments"] as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }
    }

    private fun layers(inst: Map<String, JsonValue>): List<Map<String, JsonValue>> =
        (inst["layersv"] as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }

    // ---- container --------------------------------------------------------

    @Test
    fun `writes a gzip ACVS SerialisableTrackData container the reader accepts`() {
        val bytes = writer.write(program())
        assertTrue(Acvs.isGzip(bytes), "an .xtd starts 1F 8B")
        val project = Mpc3Project.read(bytes)
        assertTrue(project.isTrack)
        assertEquals(1, project.tracks.size)
        assertEquals(listOf("SnipSnap MPC3 Kit"), project.trackNames)
        assertEquals(1, project.drumPrograms().size, "the reader must surface the drum program")
        assertTrue("1 drum" in project.describe(), project.describe())
    }

    @Test
    fun `output is deterministic`() {
        assertTrue(writer.write(program()).contentEquals(writer.write(program())))
    }

    // ---- the corpus-proven load-bearing facts -----------------------------

    @Test
    fun `all 128 instrument slots are emitted fully formed`() {
        val ins = instruments()
        assertEquals(128, ins.size)
        // Every slot, filled or empty, carries the same key set.
        assertEquals(ins[0].keys, ins[127].keys)
        ins.forEach { assertEquals(8, layers(it).size, "8 layersv slots, always") }
    }

    @Test
    fun `length lives in sliceInfo End and sampleEnd stays zero`() {
        val kick = layers(instruments()[0])[0]
        val sliceInfo = (kick["sliceInfo"] as JsonValue.Obj).entries
        assertEquals(22_050, sliceInfo["End"]!!.int())
        assertEquals(0, kick["sampleEnd"]!!.int(), "sampleEnd is accepted and ignored - never carry length there")
    }

    @Test
    fun `samples are named twice and the pool matches the layers exactly`() {
        val kick = layers(instruments()[0])[0]
        assertEquals("SS_Kick_01", kick["sampleName"]!!.str())
        assertEquals("SS_Kick_01.wav", kick["sampleFile"]!!.str())

        val pool = (data()["samples"] as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }
        val poolNames = pool.map { it["name"]!!.str() }
        assertEquals(poolNames.toSet().size, poolNames.size, "pool is deduplicated")
        val layerNames = instruments().flatMap { layers(it) }
            .mapNotNull { it["sampleName"]!!.str().ifEmpty { null } }.toSet()
        assertEquals(layerNames, poolNames.toSet(), "pool 1:1 with layer references")
        pool.forEach { assertEquals(it["name"]!!.str() + ".wav", it["path"]!!.str()) }
    }

    @Test
    fun `empty pads use the corpus empty-slot encoding`() {
        val empty = instruments()[4]
        val l0 = layers(empty)[0]
        assertEquals("", l0["sampleName"]!!.str())
        assertEquals("", l0["sampleFile"]!!.str())
        assertEquals(128, l0["sliceIndex"]!!.int(), "128 is the empty sentinel")
        assertEquals(0, ((l0["sliceInfo"] as JsonValue.Obj).entries)["End"]!!.int())
    }

    @Test
    fun `pad note map is 0-based chromatic from 36`() {
        val prog = (data()["program"] as JsonValue.Obj).entries
        val map = ((prog["padNoteMap"] as JsonValue.Obj).entries["noteForPad"] as JsonValue.Obj).entries
        assertEquals(128, map.size)
        assertEquals(36, map["value0"]!!.int(), "pad A01 = note 36, value0 - MPC 3 is 0-based here")
        assertEquals(51, map["value15"]!!.int())
        assertEquals(35, map["value127"]!!.int(), "wraps around at the top")
    }

    @Test
    fun `poliphony is misspelled at drum level and spelled correctly per instrument`() {
        val prog = (data()["program"] as JsonValue.Obj).entries
        val drum = (prog["drum"] as JsonValue.Obj).entries
        assertTrue("poliphony" in drum, "the format's own typo, verbatim")
        assertFalse("polyphony" in drum)
        assertTrue("polyphony" in instruments()[0])
        assertFalse("poliphony" in instruments()[0])
    }

    @Test
    fun `velocity zones are written loudest first descending`() {
        val zones = layers(instruments()[5]).filter { it["sampleName"]!!.str().isNotEmpty() }
        assertEquals(2, zones.size)
        // Our model is soft-first; MPC 3's convention is layer 0 loudest.
        assertEquals("SS_Snare_01", zones[0]["sampleName"]!!.str())
        assertEquals(64, zones[0]["velocityStart"]!!.int())
        assertEquals(127, zones[0]["velocityEnd"]!!.int())
        assertEquals("SS_Snare_soft", zones[1]["sampleName"]!!.str())
        assertEquals(0, zones[1]["velocityStart"]!!.int())
        assertEquals(63, zones[1]["velocityEnd"]!!.int())
    }

    @Test
    fun `mute groups tune and trigger mode carry across`() {
        val ins = instruments()
        assertEquals(1, ins[2]["whichMuteGroup"]!!.int())
        assertEquals(1, ins[3]["whichMuteGroup"]!!.int())
        assertEquals(-2, ins[1]["coarseTune"]!!.int())
        assertEquals(10, ins[1]["fineTune"]!!.int())
        assertEquals(0, ins[0]["triggerMode"]!!.int(), "one-shot = 0")
        assertEquals(2, ins[3]["triggerMode"]!!.int(), "sustained = 2, Note On")
    }

    @Test
    fun `pan centre is a plain half never the float32 artefact`() {
        val text = writer.payloadText(program())
        assertFalse("0.5039370059967041" in text, "float32(64/127) is a serializer artefact, not a pan value")
        val prog = (data()["program"] as JsonValue.Obj).entries
        val mix = (prog["mixable"] as JsonValue.Obj).entries
        assertEquals(0.5, mix["pan"]!!.num())
    }

    @Test
    fun `per-pad colours land in programPads as packed ints`() {
        val prog = (data()["program"] as JsonValue.Obj).entries
        val pads = (prog["programPads"] as JsonValue.Obj).entries
        assertEquals(false, ((pads["Universal"] as JsonValue.Obj).entries["value0"])!!.bool())
        val colours = (pads["pads"] as JsonValue.Obj).entries
        assertEquals(0xE8542E, colours["value0"]!!.int())
        assertEquals(0, colours["value1"]!!.int(), "colourless pad stays 0")
    }

    @Test
    fun `floats keep their decimal point in the payload`() {
        val text = writer.payloadText(program())
        assertTrue("\"pitch\": 0.0" in text, "the firmware writes floats as floats")
        assertTrue("\"volume\": 1.0" in text)
        assertTrue("\"tempo\": 120.0" in text)
    }

    // ---- the generalisation: no invented keys -----------------------------

    /**
     * Every key path we emit must exist, at the same path, in at least one
     * real commercial drum `.xtd`. The MPC 2 keygroup defects were all the
     * same mistake - an element invented rather than observed - and the
     * MPC's answer to the unknown is silence, not an error. This pins the
     * whole class for the MPC 3 writer from day one.
     *
     * Paths are normalised: `valueN` keys and array indices collapse to `*`.
     * A subset is expected - real kits carry QLink targets, insert effects
     * and clip events we never write.
     */
    @Test
    fun `emits no key path absent from real drum tracks`() {
        val dir = File("../reference/golden/mpc3-track")
        val corpus = dir.listFiles { f -> f.extension == "xtd" }
        assertTrue(corpus != null && corpus.isNotEmpty(), "reference corpus missing: ${dir.absolutePath}")

        fun norm(key: String) = if (Regex("value\\d+").matches(key)) "value*" else key

        fun paths(v: JsonValue, prefix: String, out: MutableSet<String>) {
            when (v) {
                is JsonValue.Obj -> v.entries.forEach { (k, child) ->
                    val p = "$prefix.${norm(k)}"
                    out.add(p)
                    paths(child, p, out)
                }
                is JsonValue.Arr -> v.items.forEach { paths(it, "$prefix[*]", out) }
                else -> {}
            }
        }

        val real = mutableSetOf<String>()
        for (f in corpus!!) {
            val text = GZIPInputStream(f.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            paths(Json.parse(text.split("\n", limit = 6)[5]), "", real)
        }

        val ours = mutableSetOf<String>()
        paths(Json.parse(writer.payloadText(program())), "", ours)

        assertEquals(emptySet(), ours - real, "key paths that appear in no real drum track")
    }
}
